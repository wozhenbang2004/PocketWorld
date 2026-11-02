package dev.pocketengine.features.devices.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

/** The only device stream framing, inside authenticated TLS (never a relay identity).
 * A binary frame is UUID(16), offset(8), chunk SHA256(32), and at most 256 KiB of content. */
public final class DeviceFrameCodec {
    public static final int MAX_CONTROL = 65_536, MAX_CHUNK = 262_144, MAX_BINARY = MAX_CHUNK + 56;
    private DeviceFrameCodec() { }
    public static byte[] control(byte[] json) {
        if (json == null || json.length == 0 || json.length > MAX_CONTROL) throw new IllegalArgumentException("Control frame outside bound");
        return ByteBuffer.allocate(4 + json.length).putInt(json.length).put(json).array();
    }
    public static byte[] chunk(UUID transfer, long offset, byte[] bytes) {
        if (transfer == null || offset < 0 || bytes == null || bytes.length > MAX_CHUNK) throw new IllegalArgumentException("Artifact frame outside bound");
        return ByteBuffer.allocate(60 + bytes.length).putInt(0x80000000 | (56 + bytes.length))
            .putLong(transfer.getMostSignificantBits()).putLong(transfer.getLeastSignificantBits()).putLong(offset).put(hash(bytes)).put(bytes).array();
    }
    private static byte[] hash(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public interface Listener {
        void control(byte[] json) throws Exception;
        void chunk(UUID transfer, long offset, byte[] bytes) throws Exception;
    }
    /** At most one bounded frame retained; arbitrary TLS fragmentation/coalescing is supported. */
    public static final class Decoder {
        private final byte[] header = new byte[4];
        private final int controlLimit;
        private final boolean allowBinary;
        public Decoder() { this(MAX_CONTROL, true); }
        public Decoder(int maximumControlBytes, boolean allowBinary) {
            if (maximumControlBytes < 1 || maximumControlBytes > MAX_CONTROL) throw new IllegalArgumentException("Control frame limit outside protocol bound");
            controlLimit = maximumControlBytes; this.allowBinary = allowBinary;
        }
        private int headerSize, position;
        private byte[] payload;
        private boolean binary;
        public void accept(ByteBuffer input, Listener listener) throws Exception {
            while (input.hasRemaining()) {
                if (payload == null) {
                    while (input.hasRemaining() && headerSize < 4) header[headerSize++] = input.get();
                    if (headerSize != 4) return;
                    int value = ByteBuffer.wrap(header).getInt(); binary = value < 0;
                    int length = value & 0x7fffffff;
                    if (binary && !allowBinary || length < (binary ? 56 : 1) || length > (binary ? MAX_BINARY : controlLimit)) throw new IOException("Invalid bounded device frame");
                    payload = new byte[length]; position = 0;
                }
                int count = Math.min(input.remaining(), payload.length - position);
                input.get(payload, position, count); position += count;
                if (position == payload.length) {
                    byte[] complete = payload; boolean wasBinary = binary;
                    payload = null; headerSize = 0; position = 0;
                    if (!wasBinary) listener.control(complete);
                    else {
                        ByteBuffer chunk = ByteBuffer.wrap(complete);
                        UUID transfer = new UUID(chunk.getLong(), chunk.getLong()); long offset = chunk.getLong();
                        if (offset < 0) throw new IOException("Negative artifact offset");
                        byte[] expected = new byte[32]; chunk.get(expected);
                        byte[] bytes = new byte[chunk.remaining()]; chunk.get(bytes);
                        if (!java.security.MessageDigest.isEqual(expected, hash(bytes))) throw new IOException("Artifact chunk checksum mismatch");
                        listener.chunk(transfer, offset, bytes);
                    }
                }
            }
        }
        public boolean incomplete() { return headerSize != 0 || payload != null; }
        public void clear() { if (payload != null) java.util.Arrays.fill(payload, (byte) 0); payload = null; headerSize = position = 0; }
    }
}
