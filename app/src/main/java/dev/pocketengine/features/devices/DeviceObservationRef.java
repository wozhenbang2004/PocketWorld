package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** In-memory native frame reference. Never restored from wire JSON, disk, or another phone. */
public final class DeviceObservationRef {
    private final RunControl original;
    private final String deviceId, taskId, runId, sessionId, observationId, frameId, windowsDigest;
    private final int epoch, displayId;
    private final long createdAt, pauseRevision;
    private final JSONObject value;
    private boolean consumed;

    private DeviceObservationRef(String deviceId, RunControl control, JSONObject observed, long now) throws Exception {
        RunControl.HostRunIdentity run = control.hostRunIdentity();
        if (run == null || !Boolean.TRUE.equals(observed.opt("ok"))) throw new SecurityException("没有本机原执行的完整观察");
        this.original = control;
        this.deviceId = deviceId;
        this.taskId = run.taskId(); this.runId = run.runId(); this.epoch = run.epoch();
        this.sessionId = observed.getString("sessionId"); this.displayId = observed.getInt("displayId");
        this.observationId = observed.getString("observationId");
        JSONArray windows = observed.optJSONArray("windows");
        if (sessionId.isEmpty() || displayId < 0 || observationId.isEmpty() || windows == null)
            throw new SecurityException("观察缺少本机会话、显示或窗口身份");
        for (int i = 0; i < windows.length(); i++)
            if (windows.getJSONObject(i).getInt("displayId") != displayId) throw new SecurityException("观察包含其他显示的窗口");
        this.windowsDigest = TaskStore.capabilityFingerprint(new JSONObject().put("windows", windows));
        this.frameId = UUID.randomUUID().toString(); this.createdAt = now; this.pauseRevision = control.pauseRevision();
        this.value = new JSONObject().put("deviceId", deviceId).put("taskId", taskId).put("runId", runId)
                .put("executorEpoch", epoch).put("sessionId", sessionId).put("displayId", displayId)
                .put("windowDigest", windowsDigest).put("observationId", observationId).put("frameId", frameId)
                .put("capturedAt", createdAt).put("pauseRevision", pauseRevision);
    }
    static DeviceObservationRef capture(String deviceId, RunControl control, JSONObject actual, long now) throws Exception {
        return new DeviceObservationRef(deviceId, control, actual, now);
    }
    public JSONObject json() throws Exception { return new JSONObject(value.toString()); }
    /** Consumed before an action is prepared; even a refused/unknown action needs a new observation. */
    public synchronized JSONObject consume(JSONObject supplied, RunControl control, long now) throws Exception {
        control.check();
        if (consumed || original != control || !control.matchesHostRun(taskId, runId, epoch)
                || control.isPaused() || pauseRevision != control.pauseRevision() || now < createdAt || now - createdAt > 120_000
                || supplied == null || !TaskStore.capabilityFingerprint(value).equals(TaskStore.capabilityFingerprint(supplied)))
            throw new SecurityException("设备画面引用已过期或不属于本机原显示/会话/窗口/执行代次，请重新观察");
        consumed = true;
        return new JSONObject().put("observationId", observationId).put("sessionId", sessionId).put("displayId", displayId);
    }
    public synchronized void expire() { consumed = true; }
}
