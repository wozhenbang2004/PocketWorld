package dev.pocketengine.features.memory;

import org.json.JSONObject;

/**
 * Native task authority, supplied by the host, never reconstructed from model or JSON input.
 * admit() must check the same task/run/epoch, scope, cancellation, consent and model configuration
 * under the host's existing admission lock and run the short action before releasing that lock.
 * check() is for before/after slow I/O; it is not a substitute for atomic final admission.
 */
public interface MemoryHostFence {
    void check()throws Exception;
    JSONObject admit(Admission action)throws Exception;
    interface Admission {JSONObject run()throws Exception;}
}
