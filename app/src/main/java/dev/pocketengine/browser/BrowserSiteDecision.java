package dev.pocketengine.browser;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** One process-local native callback. It is not a persistent task grant or permission ledger. */
public final class BrowserSiteDecision {
    public static final long LIMIT_MS = 120_000;
    public interface Current { void check() throws Exception; }
    public interface Reply { void deliver(boolean allowed) throws Exception; }
    private final String id = UUID.randomUUID().toString();
    private final JSONObject identity;
    private final Current current;
    private final Reply reply;
    private final LongSupplier uptime;
    private final long openedAt;
    private boolean ownerApproved, consumed, delivered;
    private String status = "waiting_owner";

    public BrowserSiteDecision(JSONObject nativeIdentity, Current current, Reply actualCallback,
            LongSupplier uptime) throws Exception {
        this.identity = new JSONObject(nativeIdentity.toString());
        for (String key : new String[]{"tabId", "documentId", "browserSessionId"})
            BrowserTaskSpace.uuid(identity.getString(key));
        if (identity.getLong("navigation") < 0) throw new SecurityException("Invalid document revision");
        BrowserFrameOrigins.exact(identity.getString("sourceOrigin"));
        this.current = Objects.requireNonNull(current);
        this.reply = Objects.requireNonNull(actualCallback);
        this.uptime = Objects.requireNonNull(uptime);
        openedAt = uptime.getAsLong();
        requireCurrent();
    }

    public String id() { return id; }
    public synchronized boolean pending() { return !consumed; }
    public synchronized boolean ownerApproved() { return ownerApproved && !consumed; }
    public synchronized void requireCurrent() throws Exception {
        if (consumed || uptime.getAsLong() - openedAt >= LIMIT_MS)
            throw new SecurityException("The original website request expired");
        current.check();
    }

    /** Only the native owner button knows this exact token; model/page strings never approve it. */
    public synchronized void chooseForOwner(String expectedId, boolean allow) throws Exception {
        if (!id.equals(expectedId)) throw new SecurityException("The website request changed");
        requireCurrent();
        if (ownerApproved) throw new SecurityException("Owner decision already consumed");
        if (!allow) { consume(false, "denied"); return; }
        ownerApproved = true;
        status = "waiting_platform";
    }

    /** Android's result is necessary in addition to the preceding native owner decision. */
    public synchronized void platformResult(boolean allowed) throws Exception {
        requireCurrent();
        if (!ownerApproved) throw new SecurityException("No exact owner decision");
        consume(allowed, allowed ? "delivered" : "denied");
    }

    public synchronized boolean cancel() throws Exception {
        if (consumed) return false;
        consume(false, "cancelled");
        return true;
    }

    /** The WebView has already revoked its callback; do not call the old handle again. */
    public synchronized void platformCancelled() {
        if (!consumed) { consumed = true; status = "cancelled"; }
    }

    private void consume(boolean allowed, String outcome) throws Exception {
        consumed = true;
        status = outcome;
        try { reply.deliver(allowed); delivered = true; }
        catch (Exception failure) { status = "unknown"; throw failure; }
    }

    public synchronized JSONObject snapshot() throws Exception {
        return new JSONObject(identity.toString()).put("requestId", id).put("status", status)
                .put("ownerApproved", ownerApproved).put("callbackDelivered", delivered)
                .put("automaticReplayAllowed", false).put("businessSuccessVerified", false);
    }
}
