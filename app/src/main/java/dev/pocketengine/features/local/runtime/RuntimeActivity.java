package dev.pocketengine.features.local.runtime;

import java.util.Set;
import org.json.JSONObject;

/** Read-only, body-free observation of the original job. Silence cannot prove that
 * a program wants input, has succeeded, or may safely be replayed. No task authority. */
final class RuntimeActivity {
    static final long IDLE_AFTER_MILLIS=2000;
    private RuntimeActivity(){}

    static JSONObject describe(String operation,JSONObject original,Long silenceMillis,boolean outputObserved)throws Exception {
        return describe(operation,original,silenceMillis,outputObserved,null);
    }
    static JSONObject describe(String operation,JSONObject original,Long silenceMillis,boolean outputObserved,Long uptimeMillis)throws Exception {
        String contract=original.optString("nativeResultContract");
        boolean pty=NativeProcessSupervisor.knownSessionExitContract(contract);
        boolean nativeContract=NativeProcessSupervisor.knownOrdinaryExitContract(contract)||pty;
        boolean released=yes(original,"resourcesReleased"),exit=yes(original,"processExited")&&nativeContract;
        boolean settled=exit&&released&&yes(original,"receiptReceived")&&yes(original,"processGroupAbsent")
                &&(!pty||yes(original,"processSessionAbsent"))&&Set.of("completed","failed").contains(original.optString("status"));
        boolean noChild=released&&NativeProcessSupervisor.validatesNoChildReceipt(original);
        String state;
        if(settled)state="exited";
        else if(noChild)state="not_launched";
        else if(exit&&!released)state="awaiting_resource_release";
        else if(!yes(original,"ownedWorkerLive")||"unknown".equals(original.optString("status")))state="observation_unknown";
        else if(yes(original,"cancelRequested"))state="stopping";
        else if(original.has("preparationPhase"))state="preparing";
        else if(!yes(original,"nativeStartObserved"))state="starting";
        else if(silenceMillis!=null&&silenceMillis>=IDLE_AFTER_MILLIS)state="inferred_idle";
        else state=outputObserved?"active_output":"running";
        boolean liveSilence=yes(original,"ownedWorkerLive")&&yes(original,"nativeStartObserved")
                &&!exit&&silenceMillis!=null&&silenceMillis>=0;
        JSONObject input=new JSONObject().put("state","none").put("scope","original_host_published_byte_ack_only")
                .put("queueState","not_observed").put("applicationConsumptionProven",false);
        JSONObject ack=original.optJSONObject("lastTerminalInput");
        if(ack!=null){
            String status=ack.optString("status"),id=ack.optString("inputOperationId");
            // This cache is written only after the original typed host sink has returned.
            // Even a delivered byte ACK says nothing about application-level consumption.
            input.put("state",yes(ack,"nativeAcknowledged")&&Set.of("delivered","not_dispatched").contains(status)?status:"unknown");
            if(id.matches("[A-Za-z0-9_-]{1,80}"))input.put("operationId",id);
        }
        JSONObject result=new JSONObject().put("format","pocketworld.runtime-activity.v1").put("operationId",operation)
                .put("state",state).put("inferred",state.equals("inferred_idle"))
                .put("silenceMillis",liveSilence?silenceMillis:JSONObject.NULL).put("idleAfterMillis",IDLE_AFTER_MILLIS)
                .put("silenceScope","monotonic_original_live_owner_callbacks_only")
                .put("outputObservedThisOwner",yes(original,"ownedWorkerLive")&&outputObserved)
                .put("waitingForInput","unknown").put("input",input)
                .put("processTerminal",settled||noChild).put("businessVerified",false).put("replayAllowed",false);
        if(original.has("foregroundWaitMillis")&&yes(original,"backgroundObservationExplicitlyIncluded")){
            long wait=original.getLong("foregroundWaitMillis");
            boolean live=yes(original,"ownedWorkerLive")&&yes(original,"nativeStartObserved")&&!exit&&!yes(original,"cancelRequested")&&!"unknown".equals(original.optString("status"));
            String phase=settled||noChild?"exited":live&&uptimeMillis!=null&&uptimeMillis>=0?(uptimeMillis<wait?"foreground_wait":"background_observation"):"observation_unknown";
            result.put("observationWindow",new JSONObject().put("phase",phase).put("foregroundWaitMillis",wait)
                    .put("source","original_reviewed_command_and_live_monotonic_start").put("sameExecution",true)
                    .put("totalTimeoutMillis",original.optLong("timeoutMillis")).put("deadlineExtended",false).put("executionAuthorized",false));
        }
        return result;
    }
    private static boolean yes(JSONObject value,String key){return Boolean.TRUE.equals(value.opt(key));}
}
