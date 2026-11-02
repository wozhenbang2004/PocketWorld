package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;

/** Native service mailbox, not a task ledger. The accepted wire facts remain in original SQLite.
 * Process death discards this mailbox and cannot recreate execution or replay accepted operations. */
public final class DeviceToolsSession {
    private final String taskId;
    private final RunControl original;
    private final DeviceExecutionAdmission first;
    private final dev.pocketengine.store.TaskStoreDeviceTasks tasks;
    public DeviceToolsSession(String taskId, RunControl original, DeviceExecutionAdmission first, dev.pocketengine.store.TaskStoreDeviceTasks tasks) throws Exception {
        this.tasks = java.util.Objects.requireNonNull(tasks); this.taskId = java.util.Objects.requireNonNull(taskId); this.original = java.util.Objects.requireNonNull(original); this.first = java.util.Objects.requireNonNull(first);
        if (first.kind() != DeviceExecutionAdmission.Kind.DEVICE_TOOLS || original.hostRunIdentity() == null || !taskId.equals(original.hostRunIdentity().taskId()))
            throw new SecurityException("设备工具服务只能持有原TaskStore已准入控制");
        offer(first);
    }
    public String taskId() { return taskId; }
    public RunControl originalControl() { return original; }
    public DeviceExecutionAdmission firstAdmission() { return first; }
    public void offer(DeviceExecutionAdmission admission) throws Exception {
        admission.checkCurrent();
        if (original.isCancelled() || admission.kind() != DeviceExecutionAdmission.Kind.DEVICE_TOOLS
                || !first.request().callerDeviceId().equals(admission.request().callerDeviceId())
                || !first.request().json().getString("localTaskId").equals(admission.request().json().getString("localTaskId")))
            throw new SecurityException("不是该原设备会话的当前请求");
        // The original SQL inbound index is the bounded durable mailbox; do not duplicate its state in memory.
    }
    public DeviceExecutionAdmission poll() throws Exception {
        DeviceTaskEnvelope pending = tasks.nextAcceptedTool(taskId);
        if (pending == null) { Thread.sleep(250); return null; }
        return first.reauthorizeAccepted(pending);
    }
    public long deadline() { return first.deadline(); }
}
