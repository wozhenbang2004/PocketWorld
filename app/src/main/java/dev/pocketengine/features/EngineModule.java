package dev.pocketengine.features;

import org.json.JSONObject;

/** A feature's worker-thread command surface; no UI or channel can bypass its grants. */
public interface EngineModule extends AutoCloseable {
    String id();
    JSONObject snapshot() throws Exception;
    JSONObject handle(String command, JSONObject input) throws Exception;
    @Override default void close() { }
}
