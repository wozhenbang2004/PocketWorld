package dev.pocketengine.phone.display.adb;

import org.json.JSONObject;

/** Owner bootstrap adapter; implementations never own TaskStore task execution. */
public interface AdbBootstrapClient extends AutoCloseable {
    JSONObject snapshot();
    JSONObject discover();
    JSONObject pair(JSONObject input)throws Exception;
    JSONObject connect(JSONObject input,JSONObject bootstrap,AdbBootstrap.AuthenticatedStatus authenticatedStatus)throws Exception;
    JSONObject cancel();
    JSONObject forgetIdentity();
    @Override void close();
}
