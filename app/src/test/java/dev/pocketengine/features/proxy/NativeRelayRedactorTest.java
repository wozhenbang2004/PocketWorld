package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.providers.ApiTurnStream;
import dev.pocketengine.runtime.providers.ProviderAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Production SSE validation and credential redaction, with a deterministic in-memory producer. */
public final class NativeRelayRedactorTest {
    private static final String SECRET = "sk-synthetic-private";
    private static final ProviderAdapter TEXT = new ProviderAdapter() {
        public URL endpoint(ModelConfig config) { throw new UnsupportedOperationException(); }
        public JSONObject request(ModelConfig config, JSONArray messages, JSONArray tools) { throw new UnsupportedOperationException(); }
        public Map<String, String> headers(ModelConfig config) { throw new UnsupportedOperationException(); }
        public JSONObject response(JSONObject wire) throws Exception {
            return new JSONObject().put("role", "assistant").put("content", wire.getJSONArray("output")
                    .getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"));
        }
    };

    private static JSONObject part(String text) throws Exception {
        return new JSONObject().put("type", "output_text").put("text", text);
    }
    private static JSONObject item(int index, String text) throws Exception {
        return new JSONObject().put("id", "message-" + index).put("type", "message")
                .put("role", "assistant").put("status", "completed").put("content", new JSONArray().put(part(text)));
    }
    private static JSONObject textEvent(String type, int item, int content, String text) throws Exception {
        return new JSONObject().put("type", type).put("output_index", item).put("item_id", "message-" + item)
                .put("content_index", content).put(type.endsWith(".delta") ? "delta" : "text", text);
    }
    private static JSONObject boundary(String type, int index, String text) throws Exception {
        if (type.equals("response.completed")) return new JSONObject().put("type", type).put("response",
                new JSONObject().put("id", "response-one").put("status", "completed")
                        .put("output", new JSONArray().put(item(index, text))));
        if (type.equals("response.output_item.done")) return new JSONObject().put("type", type)
                .put("output_index", index).put("item_id", "message-" + index).put("item", item(index, text));
        JSONObject event = textEvent(type, index, 0, text);
        if (type.equals("response.content_part.done")) { event.remove("text"); event.put("part", part(text)); }
        return event;
    }
    private static final class Frames {
        final StringBuilder body = new StringBuilder(); int sequence;
        void add(JSONObject event) throws Exception {
            event.put("sequence_number", sequence++);
            body.append("event: ").append(event.getString("type")).append("\ndata: ").append(event).append("\n\n");
        }
    }
    private static String stream(String text, String closingType, boolean wrongIdentity) throws Exception {
        Frames frames = new Frames();
        frames.add(new JSONObject().put("type", "response.created").put("response",
                new JSONObject().put("id", "response-one").put("status", "in_progress")));
        frames.add(new JSONObject().put("type", "response.output_item.added").put("output_index", 0)
                .put("item", new JSONObject().put("id", "message-0").put("type", "message")
                        .put("role", "assistant").put("content", new JSONArray())));
        frames.add(new JSONObject().put("type", "response.content_part.added").put("output_index", 0)
                .put("item_id", "message-0").put("content_index", 0).put("part", part("")));
        for (int offset = 0; offset < text.length(); offset += 2000)
            frames.add(textEvent("response.output_text.delta", 0, 0, text.substring(offset, Math.min(offset + 2000, text.length()))));
        JSONObject close = boundary(closingType, 0, text);
        if (wrongIdentity) close.put("item_id", "other-message");
        frames.add(close);
        if (!closingType.equals("response.completed")) frames.add(boundary("response.completed", 0, text));
        return frames.body.toString();
    }
    private static final class Capture {
        final List<JSONObject> frames = new ArrayList<>();
        final NativeRelayStream.Redactor redactor;
        Capture() { this(SECRET); }
        Capture(String secret) { redactor = new NativeRelayStream.Redactor("openai_responses", secret, (event, data) -> frames.add(data)); }
        void accept(JSONObject event) throws Exception { redactor.accept(event.getString("type"), event); }
        String deltas(int item, int content) {
            StringBuilder text = new StringBuilder();
            for (JSONObject frame : frames) if (frame.optString("type").equals("response.output_text.delta")
                    && frame.optInt("output_index", -1) == item && frame.optInt("content_index", -1) == content)
                text.append(frame.optString("delta"));
            return text.toString();
        }
    }
    private static JSONObject read(String body, Capture capture) throws Exception {
        return ApiTurnStream.relayNative(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                "openai_responses", TEXT, new JSONArray(), new RunControl(), capture.redactor::accept);
    }
    private static void longReplyCloses(String type) throws Exception {
        String text = "界".repeat(100000) + "s", body = stream(text, type, false);
        assertTrue(boundary(type, 0, text).toString().getBytes(StandardCharsets.UTF_8).length > 256 * 1024);
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length < ApiTurnStream.MAX_BYTES);
        Capture capture = new Capture();
        assertEquals(text, read(body, capture).getString("content"));
        capture.redactor.finish();
        assertEquals(text, capture.deltas(0, 0));
        assertEquals("response.completed", capture.frames.get(capture.frames.size() - 1).getString("type"));
    }

    @Test public void textDoneReleasesItsSuffixBeforeTheLargeSnapshotIsBuffered() throws Exception {
        longReplyCloses("response.output_text.done");
    }
    @Test public void contentDoneReleasesItsSuffixWithoutRequiringTextDone() throws Exception {
        longReplyCloses("response.content_part.done");
    }
    @Test public void itemDoneReleasesItsTextWithoutRequiringPartDone() throws Exception {
        longReplyCloses("response.output_item.done");
    }
    @Test public void validatedCompletionReleasesOutstandingSuffixes() throws Exception {
        longReplyCloses("response.completed");
    }
    @Test public void closingAnotherContentPathDoesNotReleaseAnUnfinishedCredential() throws Exception {
        Capture capture = new Capture();
        capture.accept(textEvent("response.output_text.delta", 0, 0, "before sk-"));
        capture.accept(textEvent("response.output_text.delta", 0, 1, "other"));
        capture.accept(textEvent("response.output_text.done", 0, 1, "other"));
        assertTrue(capture.frames.isEmpty());
        capture.accept(textEvent("response.output_text.delta", 0, 0, "synthetic-private after"));
        assertFalse(capture.deltas(0, 0).contains(SECRET));
        assertEquals("before [upstream credential hidden] after", capture.deltas(0, 0));
    }
    @Test public void closingAnotherItemDoesNotReleaseAnUnfinishedCredential() throws Exception {
        Capture capture = new Capture();
        capture.accept(textEvent("response.output_text.delta", 0, 0, "before sk-"));
        capture.accept(textEvent("response.output_text.delta", 1, 0, "other"));
        capture.accept(boundary("response.output_item.done", 1, "other"));
        assertTrue(capture.frames.isEmpty());
        capture.accept(textEvent("response.output_text.delta", 0, 0, "synthetic-private after"));
        assertEquals("before [upstream credential hidden] after", capture.deltas(0, 0));
    }
    @Test public void functionArgumentsDoneClosesOnlyItsOwnArgumentPath() throws Exception {
        Capture capture = new Capture("}synthetic-private");
        JSONObject arguments = new JSONObject().put("type", "response.function_call_arguments.delta")
                .put("output_index", 0).put("item_id", "function-0").put("delta", "{}");
        capture.accept(arguments);
        assertTrue(capture.frames.isEmpty());
        capture.accept(new JSONObject().put("type", "response.function_call_arguments.done")
                .put("output_index", 0).put("item_id", "function-0").put("arguments", "{}"));
        assertEquals(2, capture.frames.size());
    }
    @Test public void completedCredentialsRemainHiddenInDeltasAndSnapshots() throws Exception {
        Capture capture = new Capture();
        capture.accept(textEvent("response.output_text.delta", 0, 0, "before sk-"));
        capture.accept(textEvent("response.output_text.delta", 0, 0, "synthetic-private after s"));
        capture.accept(boundary("response.output_text.done", 0, "before " + SECRET + " after s"));
        capture.redactor.finish();
        assertEquals("before [upstream credential hidden] after s", capture.deltas(0, 0));
        for (JSONObject frame : capture.frames) assertFalse(frame.toString().contains(SECRET));
    }
    @Test public void unresolvedOtherPathsStillEnforceTheSameBufferBudget() throws Exception {
        Capture capture = new Capture();
        capture.accept(textEvent("response.output_text.delta", 0, 0, "s"));
        IOException failure = assertThrows(IOException.class,
                () -> capture.accept(textEvent("response.output_text.delta", 1, 0, "x".repeat(256 * 1024))));
        assertTrue(failure.getMessage().contains("有界缓冲限制"));
        assertTrue(capture.frames.isEmpty());
    }
    @Test public void anUnvalidatedDoneCannotReleaseTheCredentialTail() throws Exception {
        Capture capture = new Capture();
        assertThrows(Exception.class, () -> read(stream("safe prefix s", "response.output_text.done", true), capture));
        assertEquals("", capture.deltas(0, 0));
        for (JSONObject frame : capture.frames) assertFalse(frame.getString("type").endsWith(".done")
                || frame.getString("type").equals("response.completed"));
    }
}
