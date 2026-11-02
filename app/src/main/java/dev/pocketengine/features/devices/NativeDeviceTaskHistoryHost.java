package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import org.json.JSONObject;

/** Explicit local owner history UI, never a model tool or an execution-scope fallback. */
public final class NativeDeviceTaskHistoryHost {
    public interface NativeReads {
        /** Restore READ-only route using the actual currently authenticated target and original
         * persisted grant receipt; never reconstruct an execution OutgoingGrant from JSON. */
        PairingCoordinator.HistoryRoute currentHistoryRoute(String targetDeviceId,String originalGrantId)throws Exception;
        DeviceCapabilityPort.Reply readHistory(DeviceTaskEnvelope freshQuery,RunControl ioCancellation)throws Exception;
    }
    private final TaskStore tasks;private final DeviceIdentity.LocalIdentity local;private final NativeReads source;
    public NativeDeviceTaskHistoryHost(TaskStore originalTasks,DeviceIdentity.LocalIdentity existingLocalIdentity,NativeReads source){tasks=java.util.Objects.requireNonNull(originalTasks);local=java.util.Objects.requireNonNull(existingLocalIdentity);this.source=java.util.Objects.requireNonNull(source);}
    public Window open(String originalTaskId,String originalOperationId)throws Exception{return new Window(tasks.deviceTasks().ownerHistoryEvidence(originalTaskId,originalOperationId));}
    public final class Window implements AutoCloseable {
        private final TaskStoreDeviceTasks.OwnerHistoryEvidence original;private final RunControl io=new RunControl();private volatile boolean closed;
        private Window(TaskStoreDeviceTasks.OwnerHistoryEvidence original){this.original=original;}
        private void require()throws Exception{if(closed)throw new SecurityException("原历史窗口已关闭");io.check();original.requireCurrent();}
        public JSONObject view()throws Exception{require();return original.view();}
        /** Original bounded IO worker only; no network IO while holding TaskStore/SQLite lock. */
        public JSONObject query()throws Exception{
            require();JSONObject frozen=original.originalWire();if(!local.deviceId().equals(frozen.getString("callerDeviceId")))throw new SecurityException("当前原生设备身份不是原SQL caller");PairingCoordinator.HistoryRoute route=source.currentHistoryRoute(original.targetDeviceId(),original.grantId());long now=System.currentTimeMillis();var query=route.historyQuery(local,"operation.get",original.operationId(),original.resourceBinding(),now,5000,null);if(!frozen.getString("instanceId").equals(query.envelope().instanceId()))throw new SecurityException("目标原实例已改变；不能把新实例空结果认作旧操作未发生");
            DeviceCapabilityPort.Reply actual=source.readHistory(query.envelope(),io);require();query.requireTargetPeer(actual.peer(),System.currentTimeMillis());JSONObject response=DeviceProtocol.validateMessage(actual.response());if(!"DeviceResponse".equals(response.getString("messageType"))||!query.envelope().requestId().equals(response.getString("requestId"))||!"operation.get".equals(response.getString("method"))||!original.targetDeviceId().equals(response.getString("executorDeviceId"))||!frozen.getString("instanceId").equals(response.getString("instanceId")))throw new SecurityException("不是这次原对象历史query的实际响应");JSONObject typed=response.getJSONObject("result");tasks.deviceTasks().recordOwnerHistory(original,actual.peer(),typed);return new JSONObject().put("readOnly",true).put("newRun",false).put("retrySubmitted",false).put("unknownRefunded",false).put("operationReceipt",typed).put("unknown",!typed.getBoolean("accepted")||typed.getString("effectState").equals("unknown")).put("businessVerified",false);
        }
        @Override public void close(){closed=true;io.cancel();}
    }
}
