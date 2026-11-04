package dev.pocketengine.runtime.providers.bedrock;

import dev.pocketengine.runtime.providers.ProtocolJson;
import dev.pocketengine.runtime.providers.bedrock.eventstream.HeaderType;
import dev.pocketengine.runtime.providers.bedrock.eventstream.HeaderValue;
import dev.pocketengine.runtime.providers.bedrock.eventstream.Message;
import dev.pocketengine.runtime.providers.bedrock.eventstream.MessageDecoder;
import dev.pocketengine.runtime.providers.bedrock.eventstream.internal.Utils;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/** Bounded response envelope over the adapted AWS decoder.
 * This class does not interpret Anthropic terminal states, close the owned HTTP body,
 * dispatch tools, start threads or retry. The original stream accumulator owns completion. */
public final class BedrockEventStream {
    private static final int MAX_FRAME_BYTES = 512 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final long MAX_STREAM_BYTES = 4L * 1024 * 1024;
    private static final int MAX_FRAMES = 8192;
    private static final Set<String> EXCEPTIONS = Set.of(
        "internalServerException", "InternalServerException",
        "modelStreamErrorException", "ModelStreamErrorException",
        "validationException", "ValidationException",
        "throttlingException", "ThrottlingException",
        "modelTimeoutException", "ModelTimeoutException",
        "serviceUnavailableException", "ServiceUnavailableException"
    );

    public enum Kind { INCOMPLETE, PROTOCOL, SERVICE }

    /** Code and message never contain provider payload, headers, credentials or arbitrary text. */
    public static final class Failure extends IOException {
        public final Kind kind;
        public final String code;
        private Failure(Kind kind, String code) {
            super(code);
            this.kind = kind;
            this.code = code;
        }
    }

    private final InputStream input;
    private final MessageDecoder decoder = new MessageDecoder(MAX_FRAME_BYTES);
    private final byte[] buffer = new byte[8192];
    private long received;
    private int frames;
    private boolean ended;
    private IOException failure;

    public BedrockEventStream(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public byte[] nextPayload() throws IOException {
        if (failure != null) throw failure;
        if (ended) return null;
        try {
            while (true) {
                int wanted = Math.min(buffer.length, decoder.bytesNeeded());
                int count = input.read(buffer, 0, wanted);
                if (count == 0) {
                    int next = input.read();
                    if (next >= 0) { buffer[0] = (byte) next; count = 1; }
                    else count = -1;
                }
                if (count < 0) {
                    if (!decoder.atMessageBoundary()) throw fail(Kind.INCOMPLETE, "bedrock_frame_incomplete");
                    ended = true;
                    return null;
                }
                received += count;
                if (received > MAX_STREAM_BYTES) throw fail(Kind.PROTOCOL, "bedrock_stream_application_limit");
                try {
                    decoder.feed(buffer, 0, count);
                } catch (MessageDecoder.LimitExceeded tooLarge) {
                    throw fail(Kind.PROTOCOL, "bedrock_frame_application_limit");
                } catch (RuntimeException invalid) {
                    throw fail(Kind.PROTOCOL, "bedrock_frame_invalid");
                }
                List<Message> ready = decoder.getDecodedMessages();
                if (ready.isEmpty()) continue;
                // bytesNeeded() deliberately stops at the first complete message.
                if (ready.size() != 1) throw fail(Kind.PROTOCOL, "bedrock_frame_boundary_invalid");
                if (++frames > MAX_FRAMES) throw fail(Kind.PROTOCOL, "bedrock_frame_count_limit");
                return payload(ready.get(0));
            }
        } catch (IOException stopped) {
            failure = stopped;
            throw stopped;
        } catch (RuntimeException invalid) {
            failure = fail(Kind.PROTOCOL, "bedrock_frame_invalid");
            throw failure;
        }
    }

    private byte[] payload(Message message) throws IOException {
        Map<String, HeaderValue> headers = message.getHeaders();
        String type = stringHeader(headers, ":message-type");
        Set<String> allowed;
        if ("event".equals(type)) allowed = Set.of(":message-type", ":event-type", ":content-type");
        else if ("exception".equals(type)) allowed = Set.of(":message-type", ":exception-type", ":content-type");
        else if ("error".equals(type)) allowed = Set.of(":message-type", ":error-code", ":error-message", ":content-type");
        else throw fail(Kind.PROTOCOL, "bedrock_message_type_unsupported");

        for (Map.Entry<String, HeaderValue> header : headers.entrySet()) {
            if (!allowed.contains(header.getKey()) || header.getValue().getType() != HeaderType.STRING) {
                throw fail(Kind.PROTOCOL, "bedrock_header_unsupported");
            }
        }
        byte[] envelope = message.getPayload();
        JSONObject object = null;
        if ("error".equals(type)) {
            // Smithy error frames may carry only error-code/error-message headers.
            // The arbitrary message is never copied into a Failure or receipt.
            if (!headers.containsKey(":error-message")) throw fail(Kind.PROTOCOL, "bedrock_header_invalid");
        }
        if (!("error".equals(type) && envelope.length == 0)) {
            if (!"application/json".equals(stringHeader(headers, ":content-type"))) {
                throw fail(Kind.PROTOCOL, "bedrock_content_type_unsupported");
            }
            try {
                object = ProtocolJson.parse(Utils.strictUtf8(envelope));
            } catch (Exception invalid) {
                throw fail(Kind.PROTOCOL, "bedrock_envelope_json_invalid");
            }
        } else if (headers.containsKey(":content-type")
                && !"application/json".equals(stringHeader(headers, ":content-type"))) {
            throw fail(Kind.PROTOCOL, "bedrock_content_type_unsupported");
        }

        if ("exception".equals(type) || "error".equals(type)) {
            String code = stringHeader(headers, "exception".equals(type) ? ":exception-type" : ":error-code");
            if (!EXCEPTIONS.contains(code)) throw fail(Kind.PROTOCOL, "bedrock_service_type_unsupported");
            throw fail(Kind.SERVICE, code);
        }
        if (!"chunk".equals(stringHeader(headers, ":event-type"))) {
            throw fail(Kind.PROTOCOL, "bedrock_event_type_unsupported");
        }
        Object value = object.opt("bytes");
        if (object.length() != 1 || !(value instanceof String)) {
            throw fail(Kind.PROTOCOL, "bedrock_chunk_shape_invalid");
        }
        String encoded = (String) value;
        int maximumEncodedLength = ((MAX_PAYLOAD_BYTES + 2) / 3) * 4;
        if (encoded.isEmpty() || encoded.length() > maximumEncodedLength || encoded.length() % 4 != 0) {
            throw fail(Kind.PROTOCOL, "bedrock_chunk_base64_invalid");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES
                || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
                throw fail(Kind.PROTOCOL, "bedrock_chunk_base64_invalid");
            }
            // JSON syntax/duplicate keys and fragment Unicode are validated by the original
            // Anthropic ProtocolJson.parseEvent path. Preserve the exact bytes for that path.
            Utils.strictUtf8(bytes);
        } catch (IllegalArgumentException invalid) {
            throw fail(Kind.PROTOCOL, "bedrock_chunk_encoding_invalid");
        }
        return bytes;
    }

    private static String stringHeader(Map<String, HeaderValue> headers, String name) throws Failure {
        HeaderValue value = headers.get(name);
        if (value == null || value.getType() != HeaderType.STRING || value.getString().isEmpty()) {
            throw fail(Kind.PROTOCOL, "bedrock_header_invalid");
        }
        return value.getString();
    }

    private static Failure fail(Kind kind, String code) {
        return new Failure(kind, code);
    }
}

