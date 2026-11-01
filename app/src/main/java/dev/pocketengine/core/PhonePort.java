package dev.pocketengine.core;

import org.json.JSONObject;

/** Calls run on a worker; implementations marshal Android callbacks to main safely. */
public interface PhonePort {
    boolean isConnected();
    JSONObject observe(boolean includeImage, RunControl control) throws Exception;
    JSONObject execute(String toolName, JSONObject args, RunControl control) throws Exception;
    void clearReferences();
}
