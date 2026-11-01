package dev.pocketengine.features.local.preview;

/** Native execution owner observation, never a model URL/JSON or permission grant.
 * Implementations must be opaque values issued by the original host. requireCurrent binds
 * the same task/job/run, exact 127.0.0.1 port, connection and expiry. The owner must close the
 * actual tunnel on revocation/expiry so already-upgraded WebSockets cannot outlive its lease.
 * Closing a view alone does not attest that the original job/tunnel stopped. */
public interface OwnedLoopbackPreview {
    String identity();
    String baseUrl();
    void requireCurrent() throws Exception;
}
