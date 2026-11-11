package dev.pocketengine.phone.display.adb;

import org.json.JSONObject;

/** Owner bootstrap adapter; implementations never own TaskStore task execution. */
public interface AdbBootstrapClient extends AutoCloseable {
    JSONObject snapshot();
    JSONObject discover();
    JSONObject pair(JSONObject input)throws Exception;
    JSONObject connect(JSONObject input,JSONObject bootstrap,AdbBootstrap.AuthenticatedStatus authenticatedStatus)throws Exception;
    default JSONObject connectForPreparation(JSONObject input,JSONObject bootstrap,AdbBootstrap.AuthenticatedStatus authenticatedStatus,
            dev.pocketengine.core.RunControl.AuthorityCheck current)throws Exception {
        throw new IllegalStateException("当前ADB启动器不支持原任务连接准入，请在设置中连接原助手");
    }
    JSONObject cancel();
    JSONObject forgetIdentity();
    @Override void close();
}
