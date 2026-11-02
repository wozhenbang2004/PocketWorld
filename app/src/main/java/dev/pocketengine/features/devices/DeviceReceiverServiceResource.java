package dev.pocketengine.features.devices;

import java.util.UUID;
import org.json.JSONObject;

/** Native service-only receiver resource, not a task, grant, RunControl or Runner.
 * Constructing it does NOT listen. Only the explicit B-owner enable path starts it.
 * Owner disable fences NEW ingress first, then closes listeners/connections; accepted SQL jobs
 * retain their original task controls and receipts and are never marked stopped by this class. */
public final class DeviceReceiverServiceResource {
    public interface Backend {
        /** Actual pinned-mTLS control and bulk listeners. No invitation/bootstrap listener here. */
        void start() throws Exception;
        /** Must query the actual bound, unclosed listener(s), not a configured port or prior boolean. */
        boolean isListening() throws Exception;
        /** Close network resources only. Must not cancel, finish, recreate or delete business tasks. */
        void close() throws Exception;
    }
    public interface OwnerPolicy {
        /** Current LOCAL owner enable/configuration; short local check only, never wire JSON. */
        void requireEnabledCurrent() throws Exception;
        /** Persist local owner's explicit disable. Do not revoke grants or cancel accepted jobs. */
        void disableByOwner() throws Exception;
    }
    private final String id=UUID.randomUUID().toString();
    private final Backend backend;
    private final OwnerPolicy policy;
    private final Object admissionGate=new Object();
    private volatile boolean accepting,stopRequested;
    private volatile String state="not_started",failure="";
    public DeviceReceiverServiceResource(Backend nativeBackend,OwnerPolicy nativeOwnerPolicy){backend=java.util.Objects.requireNonNull(nativeBackend);policy=java.util.Objects.requireNonNull(nativeOwnerPolicy);}
    public String resourceId(){return id;}
    public void requireOwnerEnabled()throws Exception{if(stopRequested)throw new SecurityException("本机接收资源已要求关闭");policy.requireEnabledCurrent();}
    /** Root ingress factory calls this immediately before parsing/authorizing every NEW request.
     * Do not retain it in an accepted task's continuing DeviceExecutionAdmission.LocalPolicy. */
    public void checkReceiving()throws Exception{
        if(!accepting||stopRequested)throw new SecurityException("设备接收当前未开启；已接受任务状态另由原TaskStore保留");
        policy.requireEnabledCurrent();if(!accepting||stopRequested)throw new SecurityException("设备接收已关闭");
    }
    /** Fixed lock order: receiver admissionGate -> original TaskStore/SQLite. The callback must
     * only commit original acceptance, never start a service/model or perform network/port I/O. */
    public JSONObject acceptNewRequest(DeviceTaskCoordinator.Acceptance originalSqlAcceptance)throws Exception{
        synchronized(admissionGate){checkReceiving();return java.util.Objects.requireNonNull(originalSqlAcceptance).commit();}
    }
    /** Run on receiver I/O executor, not the sole task worker or under TaskStore's monitor. */
    public void start()throws Exception{
        synchronized(this){if(state.equals("listening")){requireOwnerEnabled();if(!backend.isListening())throw new SecurityException("原监听已断开");return;}if(!state.equals("not_started"))throw new SecurityException("该原生接收资源不能重开或换身份");state="starting";}
        try{
            requireOwnerEnabled();backend.start();requireOwnerEnabled();
            if(!backend.isListening())throw new SecurityException("实际mTLS监听未就绪");
            synchronized(this){if(stopRequested)throw new SecurityException("开启期间主人已关闭接收");accepting=true;state="listening";}
        }catch(Exception unavailable){
            accepting=false;failure="native_receiver_start_unavailable";
            try{backend.close();}catch(Exception closeUnknown){unavailable.addSuppressed(closeUnknown);}
            synchronized(this){state=closedProof()?"stopped":"stop_unconfirmed";stopRequested=true;}
            throw unavailable;
        }
    }
    /** Short local fence before asynchronous close. This is NOT a task cancellation request. */
    public void requestStop(){synchronized(admissionGate){accepting=false;stopRequested=true;}synchronized(this){if(!state.equals("stopped"))state="stopping";}}
    public void disableByOwner()throws Exception{requestStop();policy.disableByOwner();}
    public void close()throws Exception{
        requestStop();Exception problem=null;try{backend.close();}catch(Exception unknown){problem=unknown;}
        boolean stopped=closedProof();synchronized(this){state=stopped?"stopped":"stop_unconfirmed";if(!stopped)failure="native_receiver_close_unconfirmed";}
        if(!stopped){if(problem!=null)throw problem;throw new IllegalStateException("原监听关闭尚未证实");}
    }
    private boolean closedProof(){try{return !backend.isListening();}catch(Exception unknown){return false;}}
    public boolean stopped(){return state.equals("stopped");}
    public JSONObject snapshot()throws Exception{
        Object listening;try{listening=backend.isListening();}catch(Exception unknown){listening=JSONObject.NULL;}
        return new JSONObject().put("serviceResourceId",id).put("state",state).put("listening",listening)
                .put("acceptingNewRequests",accepting&&!stopRequested).put("failure",failure).put("taskStateIndependent",true)
                .put("disableStopsAcceptedTasks",false).put("restartAutomatic",false);
    }
}
