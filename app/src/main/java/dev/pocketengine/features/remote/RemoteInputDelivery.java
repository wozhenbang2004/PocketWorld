package dev.pocketengine.features.remote;

import java.util.Set;
import org.json.JSONObject;

/** Read-only presentation of observations from the original job, never a grant,
 * a dispatch claim, or evidence that the model consumed an accepted message. */
public final class RemoteInputDelivery {
    public static final String FORMAT="pocketworld.remote-input-delivery.v1";
    private RemoteInputDelivery(){}

    public static JSONObject describe(JSONObject source)throws Exception {
        if(source==null)source=new JSONObject();
        String worker=source.optString("worker"),protocol=source.optString("protocol");
        boolean codex="codex_app_server".equals(worker)&&"codex_app_server".equals(protocol);
        boolean claude="claude".equals(worker)&&"claude_stream_json".equals(protocol);
        boolean pi="pi".equals(worker)&&"pi_rpc_v1".equals(protocol);
        boolean identity=present(source,"jobId")&&present(source,"jobOperationId")&&present(source,"threadId");
        boolean nativeObserved=(codex||claude||pi)&&yes(source,"protocolVerified")&&identity;
        if(claude)nativeObserved=nativeObserved&&yes(source,"controlProtocolVerified")&&yes(source,"sessionIdentityConfirmed")&&present(source,"promptRequestId")&&source.getString("threadId").equals(source.opt("sessionId"));
        // Only this mounted Pi adapter's no-tools session is described. A future
        // tool-enabled adapter must not inherit this capability or an old draft.
        if(pi)nativeObserved=nativeObserved&&yes(source,"noTools")&&present(source,"promptRequestId")&&source.getString("threadId").equals(source.opt("sessionId"));
        JSONObject controls=source.optJSONObject("controls");
        String status=source.optString("executorStatus");
        boolean live=Set.of("running","awaiting_approval").contains(status);
        boolean settled=Set.of("completed","failed","cancelled").contains(status);
        boolean notStopped=Boolean.FALSE.equals(source.opt("cancelRequested"))&&!yes(source,"steeringUnknown");
        JSONObject current=mode(codex?"original_turn_steer":"not_mounted",nativeObserved&&codex,
                nativeObserved&&notStopped&&codex&&live&&present(source,"turnId")&&yes(controls,"inputSteering"))
                .put("requiresExactTurn",true).put("acceptanceProvesModelUse",false);
        boolean next=nativeObserved&&notStopped&&settled&&yes(controls,"threadResume");
        if(pi){JSONObject cleanup=source.optJSONObject("processCleanupProof");next&=yes(source,"nativeSettled")&&Boolean.FALSE.equals(source.opt("resourcesHeld"))&&yes(cleanup,"processGroupStoppedVerified");}
        JSONObject continuation=mode("settled_original_session",nativeObserved,next)
                .put("requiresNativeSettlement",true).put("requiresOriginalOwnerDispatch",true)
                .put("automaticallySent",false).put("addsBudget",false);
        if(pi)continuation.put("noToolsSessionOnly",true);
        String stopping=settled?"native_cleanup_only":codex?"turn_interrupt":claude?"prompt_interrupt":pi?"agent_abort":"not_mounted";
        JSONObject interrupt=mode(stopping,nativeObserved,nativeObserved&&yes(controls,"cancel"))
                .put("requestIsStopProof",false).put("requiresNativeAcknowledgement",true)
                .put("reversesCompletedEffects",false);
        return new JSONObject().put("format",FORMAT).put("dataOnly",true).put("authorizesExecution",false)
                .put("protocolEvidenceReported",nativeObserved)
                .put("driver",nativeObserved?worker:"unknown")
                .put("currentTurn",current).put("nextTurn",continuation).put("interrupt",interrupt);
    }
    private static JSONObject mode(String mode,boolean mounted,boolean available)throws Exception {
        return new JSONObject().put("mode",mode).put("adapterMounted",mounted).put("observedAvailable",available);
    }
    private static boolean yes(JSONObject value,String key){return value!=null&&Boolean.TRUE.equals(value.opt(key));}
    private static boolean present(JSONObject value,String key){Object raw=value.opt(key);return raw instanceof String&&!((String)raw).trim().isEmpty();}
}
