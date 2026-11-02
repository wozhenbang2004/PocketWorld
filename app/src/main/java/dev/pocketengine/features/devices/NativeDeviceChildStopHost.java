package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original parent stop propagation. No model/tool quota, source-execution revival or retry loop. */
public final class NativeDeviceChildStopHost {
    public interface NativeStops {
        PairingCoordinator.OutgoingGrant currentManagementGrant(String targetDeviceId,String originalGrantId)throws Exception;
        DeviceCapabilityPort.Reply sendManagement(DeviceTaskEnvelope request,TaskStoreDeviceTasks.OutboundSendTicket originalTicket,RunControl ioCancellation)throws Exception;
    }
    private final TaskStore tasks;private final DeviceIdentity.LocalIdentity local;private final NativeStops source;
    public NativeDeviceChildStopHost(TaskStore originalTasks,DeviceIdentity.LocalIdentity existingLocalIdentity,NativeStops source){tasks=java.util.Objects.requireNonNull(originalTasks);local=java.util.Objects.requireNonNull(existingLocalIdentity);this.source=java.util.Objects.requireNonNull(source);}
    /** Invoke on the existing bounded IO worker, never the control/UI/App-lock thread. Caller only enqueues after native SQL cancellation. */
    public JSONObject stopOriginalChildren(String taskId,RunControl originalCancelled)throws Exception{
        JSONArray results=new JSONArray();int unknown=0;for(var candidate:tasks.deviceTasks().originalStopCandidates(taskId,originalCancelled)){
            try{var grant=source.currentManagementGrant(candidate.targetDeviceId(),candidate.grantId());var dispatch=tasks.deviceTasks().prepareOriginalStop(candidate,local,grant);if(dispatch.knownOperation()){results.put(dispatch.view());unknown++;continue;}RunControl io=new RunControl();try{var actual=source.sendManagement(dispatch.request(),dispatch.ticket(),io);JSONObject result=tasks.deviceTasks().recordOriginalStop(dispatch,actual);results.put(result);if(!result.optBoolean("stopped"))unknown++;}catch(Exception unavailable){results.put(dispatch.view().put("state","unknown").put("requested",false));unknown++;}finally{io.cancel();}}
            catch(Exception unavailable){results.put(new JSONObject().put("sourceOperationId",candidate.operationId()).put("targetDeviceId",candidate.targetDeviceId()).put("state","unknown").put("stopped",false).put("retrySubmitted",false));unknown++;}
        }return new JSONObject().put("taskId",taskId).put("managementOnly",true).put("operations",results).put("unknownOrUnconfirmed",unknown).put("allStopped",unknown==0).put("allocationAuthorizationRetained",true).put("remoteAuthorizationRevoked",false);
    }
}
