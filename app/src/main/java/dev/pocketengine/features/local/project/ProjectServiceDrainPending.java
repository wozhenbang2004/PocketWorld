package dev.pocketengine.features.local.project;

import org.json.JSONObject;

/** Native original-service cleanup did not obtain an actual released terminal receipt. */
public final class ProjectServiceDrainPending extends IllegalStateException {
    public static final String CODE="project_service_drain_pending";
    private final JSONObject actual;
    public ProjectServiceDrainPending(JSONObject observation)throws Exception {
        super("原项目服务尚未确认停止并释放资源；保留原执行待核对，不自动重启");
        actual=ProjectSources.copy(observation).put("code",CODE).put("replayAllowed",false).put("businessVerified",false);
    }
    public JSONObject evidence()throws Exception {return ProjectSources.copy(actual);}
}
