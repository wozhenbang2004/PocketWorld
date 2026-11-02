package dev.pocketengine.core;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Process-local dispatch fence, not durable task state. Until device isolation is
 * verified, browser execution and either phone mode cannot overlap.
 */
public final class ExecutionResources {
    private final LongSupplier clock;
    private final dev.pocketengine.phone.access.PhoneAppResources phoneApps=new dev.pocketengine.phone.access.PhoneAppResources();
    public dev.pocketengine.phone.access.PhoneAppResources phoneApps(){return phoneApps;}
    private String phoneToken;
    private String browserToken;
    private long browserExpiresAt;

    public ExecutionResources() { this(System::currentTimeMillis); }

    public ExecutionResources(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    public synchronized boolean acquirePhone(String token) {
        validate(token);
        expireBrowser(clock.getAsLong());
        if (browserToken != null) return false;
        if (phoneToken != null) return phoneToken.equals(token);
        phoneToken = token;
        return true;
    }

    public synchronized void releasePhone(String token) {
        validate(token);
        if (token.equals(phoneToken)) phoneToken = null;
    }

    /** Repeating an acquisition does not extend its original dispatch deadline. */
    public synchronized boolean acquireBrowser(String token, long expiresAt) {
        validate(token);
        long now = clock.getAsLong();
        expireBrowser(now);
        if (expiresAt <= now || phoneToken != null) return false;
        if (browserToken != null) return browserToken.equals(token);
        browserToken = token;
        browserExpiresAt = expiresAt;
        return true;
    }

    /** Native canonical-authority refresh only; cannot recreate a released or expired owner. */
    public synchronized boolean renewBrowser(String token,long expiresAt){
        validate(token);long now=clock.getAsLong();expireBrowser(now);
        if(!token.equals(browserToken)||phoneToken!=null||expiresAt<=now)return false;browserExpiresAt=expiresAt;return true;
    }
    public synchronized void releaseBrowser(String token) {
        validate(token);
        expireBrowser(clock.getAsLong());
        if (token.equals(browserToken)) {
            browserToken = null;
            browserExpiresAt = 0;
        }
    }

    /** An existing lease check must never recreate a released lease. */
    public synchronized boolean ownsBrowser(String token) {
        validate(token);
        expireBrowser(clock.getAsLong());
        return token.equals(browserToken);
    }

    private void expireBrowser(long now) {
        if (browserToken != null && now >= browserExpiresAt) {
            browserToken = null;
            browserExpiresAt = 0;
        }
    }

    private static void validate(String token) {
        if (token == null || token.trim().isEmpty() || token.length() > 200)
            throw new IllegalArgumentException("Invalid execution resource token");
    }
}
