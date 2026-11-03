package dev.pocketengine.phone.setup;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Short owner-created input window. A claim is one-shot and never stores the pairing code. */
public final class PairingEntrySession {
    public static final long WINDOW_MS = 120_000;
    public record Entry(String id, String host, int port, long expiresAt, String sourceIdentity) { }
    private final LongSupplier clock;
    private Entry active;

    public PairingEntrySession(LongSupplier monotonicClock) {
        clock = Objects.requireNonNull(monotonicClock);
    }

    public synchronized Entry begin(String host, int port) {
        return begin(host, port, "");
    }

    public synchronized Entry begin(String host, int port, String sourceIdentity) {
        if (host == null || host.isBlank() || host.length() > 160 || port < 1 || port > 65535)
            throw new IllegalArgumentException("invalid_local_pairing_endpoint");
        Objects.requireNonNull(sourceIdentity);
        expireCurrent();
        // A repeated tap neither replaces a live notification nor extends its authority.
        if (active != null && active.host.equals(host) && active.port == port
                && active.sourceIdentity.equals(sourceIdentity)) return active;
        active = new Entry(UUID.randomUUID().toString(), host, port,
                Math.addExact(clock.getAsLong(), WINDOW_MS), sourceIdentity);
        return active;
    }

    public synchronized JSONObject submit(String sessionId, String code) throws Exception {
        return submit(sessionId, code, "");
    }

    public synchronized JSONObject submit(String sessionId, String code, String sourceIdentity) throws Exception {
        expireCurrent();
        Entry entry = active;
        if (entry == null || !entry.id.equals(sessionId)) throw new SecurityException("pairing_entry_expired");
        if (!entry.sourceIdentity.equals(sourceIdentity)) {
            active = null;
            throw new SecurityException("pairing_source_changed");
        }
        if (code == null || !code.matches("[0-9]{6}"))
            throw new IllegalArgumentException("pairing_code_requires_six_digits");
        // Claim before the native dispatch. An unknown outcome must be checked through the original ADB flow.
        active = null;
        return new JSONObject().put("host", entry.host).put("pairingPort", entry.port).put("pairingCode", code);
    }

    public synchronized boolean cancel(String expectedSessionId) {
        if (active == null || !active.id.equals(expectedSessionId)) return false;
        active = null;
        return true;
    }

    public synchronized void cancel() { active = null; }

    public synchronized boolean expire(String expectedSessionId) {
        if (active == null || !active.id.equals(expectedSessionId) || clock.getAsLong() < active.expiresAt) return false;
        active = null;
        return true;
    }

    public synchronized JSONObject snapshot() throws Exception {
        expireCurrent();
        Entry entry = active;
        return new JSONObject().put("active", entry != null)
                .put("sessionId", entry == null ? JSONObject.NULL : entry.id)
                .put("remainingMs", entry == null ? 0 : Math.max(0, entry.expiresAt - clock.getAsLong()))
                .put("pairingPort", entry == null ? JSONObject.NULL : entry.port)
                .put("storesPairingCode", false).put("connectionVerified", false);
    }

    private void expireCurrent() {
        if (active != null && clock.getAsLong() >= active.expiresAt) active = null;
    }
}
