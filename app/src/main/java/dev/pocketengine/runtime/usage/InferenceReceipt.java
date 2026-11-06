package dev.pocketengine.runtime.usage;

import org.json.JSONObject;

/** Content-free, native-origin evidence. This receipt grants no task or tool authority. */
public final class InferenceReceipt {
    public enum Purpose { MAIN, SUMMARY, MEMBER, TOOL, CONNECTION_TEST, MODEL_CAPABILITY_PROBE, VOICE_TURN, REALTIME, REALTIME_TRANSCRIPTION, RELAY, SKILL_DRAFT, CHANNEL_CHAT, UNATTRIBUTED }
    public enum Outcome { STARTED, COMPLETED, FAILED, CANCELLED }
    public enum BindingKind { HOST_RUN, EXTERNAL_TASK_RUN, UNATTRIBUTED }
    /** Native coordinator identity; it describes a call site and grants no execution authority. */
    public static final class CallSite {
        public final String turnId;
        public final int round,runGeneration,memberGeneration;
        public CallSite(String turnId,int round,int runGeneration,int memberGeneration){
            this.turnId=Context.id(turnId);this.round=round;this.runGeneration=runGeneration;this.memberGeneration=memberGeneration;
            if(this.turnId.isEmpty()||round<0||runGeneration<0||memberGeneration<0)throw new IllegalArgumentException("invalid_native_model_call_site");
        }
        JSONObject json()throws Exception{return new JSONObject().put("turnId",turnId).put("round",round)
                .put("runGeneration",runGeneration).put("memberGeneration",memberGeneration);}
    }
    public static final class Context {
        public final String taskId,runId,memberId;
        public final int epoch;
        public final Purpose purpose;
        public final BindingKind bindingKind;
        public final CallSite callSite;
        public Context(String taskId,String runId,int epoch,Purpose purpose,String memberId){
            this(taskId,runId,epoch,purpose,memberId,taskId!=null&&taskId.isEmpty()?BindingKind.UNATTRIBUTED:BindingKind.HOST_RUN,null);
        }
        private Context(String taskId,String runId,int epoch,Purpose purpose,String memberId,BindingKind bindingKind,CallSite callSite){
            this.taskId=id(taskId);this.runId=id(runId);this.memberId=id(memberId);this.epoch=epoch;
            this.purpose=java.util.Objects.requireNonNull(purpose);
            this.bindingKind=java.util.Objects.requireNonNull(bindingKind);this.callSite=callSite;
            if(epoch<0||this.taskId.isEmpty()!=this.runId.isEmpty()||this.taskId.isEmpty()&&epoch!=0
                    ||purpose==Purpose.MEMBER&&this.memberId.isEmpty())throw new IllegalArgumentException("invalid_native_inference_context");
            if(bindingKind==BindingKind.EXTERNAL_TASK_RUN&&(epoch!=0||this.taskId.isEmpty()||callSite==null))throw new IllegalArgumentException("invalid_external_task_run_evidence");
        }
        public static Context unattributed(){return new Context("","",0,Purpose.UNATTRIBUTED,"");}
        /** Native journal factory only: the site describes this existing HostRun, never grants one. */
        public static Context hostRun(String taskId,String runId,int epoch,Purpose purpose,String memberId,CallSite callSite){
            return new Context(taskId,runId,epoch,purpose,memberId,BindingKind.HOST_RUN,callSite);
        }
        /** Existing TaskStore external-run documents use epoch=0; this is explicitly not a HostRun grant. */
        public static Context externalTaskRun(String taskId,String runId,Purpose purpose,String memberId,CallSite callSite){
            return new Context(taskId,runId,0,purpose,memberId,BindingKind.EXTERNAL_TASK_RUN,java.util.Objects.requireNonNull(callSite));
        }
        private static String id(String value){if(value==null||value.length()>200||!value.matches("[A-Za-z0-9_.:-]*"))throw new IllegalArgumentException("invalid_native_inference_identity");return value;}
        public JSONObject toJson()throws Exception{return new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",epoch)
                .put("purpose",purpose.name()).put("memberId",memberId).put("bindingKind",bindingKind.name())
                .put("callSite",callSite==null?JSONObject.NULL:callSite.json());}
    }
    public final String callId;
    public final Context context;
    public final Outcome outcome;
    public final long startedAt,finishedAt,elapsedNanos;
    /** Local observation of the first provider text delta; absent for old receipts and non-streaming replies. */
    public final Long firstVisibleTextAt,firstVisibleTextElapsedNanos;
    public final Long lastVisibleTextAt,lastVisibleTextElapsedNanos;
    public final String timingEvidence;
    private final String routeJson,usageJson,failureJson;
    private final InferenceTokenReservation.NativeIdentity budgetIdentity;
    InferenceReceipt(String callId,Context context,Outcome outcome,long startedAt,long finishedAt,long elapsedNanos,
                     JSONObject route,JSONObject usage)throws Exception{
        this(callId,context,outcome,startedAt,finishedAt,elapsedNanos,route,usage,null);
    }
    InferenceReceipt(String callId,Context context,Outcome outcome,long startedAt,long finishedAt,long elapsedNanos,
                     JSONObject route,JSONObject usage,InferenceFailure failure)throws Exception{
        this(callId,context,outcome,startedAt,finishedAt,elapsedNanos,route,usage,failure,InferenceTokenReservation.unknownIdentity());
    }
    InferenceReceipt(String callId,Context context,Outcome outcome,long startedAt,long finishedAt,long elapsedNanos,
                     JSONObject route,JSONObject usage,InferenceFailure failure,InferenceTokenReservation.NativeIdentity budgetIdentity)throws Exception{
        this(callId,context,outcome,startedAt,finishedAt,elapsedNanos,route,usage,failure,budgetIdentity,null,null);
    }
    InferenceReceipt(String callId,Context context,Outcome outcome,long startedAt,long finishedAt,long elapsedNanos,
                     JSONObject route,JSONObject usage,InferenceFailure failure,InferenceTokenReservation.NativeIdentity budgetIdentity,
                     Long firstVisibleTextAt,Long firstVisibleTextElapsedNanos)throws Exception{
        this(callId,context,outcome,startedAt,finishedAt,elapsedNanos,route,usage,failure,budgetIdentity,firstVisibleTextAt,firstVisibleTextElapsedNanos,null,null);
    }
    InferenceReceipt(String callId,Context context,Outcome outcome,long startedAt,long finishedAt,long elapsedNanos,
                     JSONObject route,JSONObject usage,InferenceFailure failure,InferenceTokenReservation.NativeIdentity budgetIdentity,
                     Long firstVisibleTextAt,Long firstVisibleTextElapsedNanos,Long lastVisibleTextAt,Long lastVisibleTextElapsedNanos)throws Exception{
        this.callId=callId;this.context=context;this.outcome=outcome;this.startedAt=startedAt;
        this.finishedAt=finishedAt;this.elapsedNanos=elapsedNanos;routeJson=route.toString();usageJson=usage.toString();failureJson=failure==null?null:failure.toJson().toString();
        this.budgetIdentity=java.util.Objects.requireNonNull(budgetIdentity);
        boolean observed=outcome!=Outcome.STARTED&&firstVisibleTextAt!=null&&firstVisibleTextElapsedNanos!=null;
        this.firstVisibleTextAt=observed?firstVisibleTextAt:null;this.firstVisibleTextElapsedNanos=observed?firstVisibleTextElapsedNanos:null;
        boolean last=observed&&lastVisibleTextAt!=null&&lastVisibleTextElapsedNanos!=null&&lastVisibleTextElapsedNanos>=firstVisibleTextElapsedNanos;
        this.lastVisibleTextAt=last?lastVisibleTextAt:null;this.lastVisibleTextElapsedNanos=last?lastVisibleTextElapsedNanos:null;
        timingEvidence=observed?"provider_text_delta":null;
    }
    /** Native TaskStore only. Private binding is intentionally excluded from toJson(). */
    public InferenceTokenReservation.NativeIdentity nativeBudgetIdentity(){return budgetIdentity;}
    public JSONObject toJson()throws Exception{JSONObject value=new JSONObject().put("type","inference_receipt").put("version",1)
            .put("receiptVersion",1).put("state",outcome==Outcome.STARTED?"started":"finished")
            .put("callId",callId).put("context",context.toJson()).put("outcome",outcome.name())
            .put("errorCode",outcome==Outcome.FAILED?"request_failed":outcome==Outcome.CANCELLED?"request_cancelled":JSONObject.NULL)
            .put("startedAt",startedAt).put("finishedAt",finishedAt==0?JSONObject.NULL:finishedAt)
            .put("firstVisibleTextAt",firstVisibleTextAt==null?JSONObject.NULL:firstVisibleTextAt)
            .put("firstVisibleTextElapsedNanos",firstVisibleTextElapsedNanos==null?JSONObject.NULL:firstVisibleTextElapsedNanos)
            .put("lastVisibleTextAt",lastVisibleTextAt==null?JSONObject.NULL:lastVisibleTextAt)
            .put("lastVisibleTextElapsedNanos",lastVisibleTextElapsedNanos==null?JSONObject.NULL:lastVisibleTextElapsedNanos)
            .put("timingEvidence",timingEvidence==null?JSONObject.NULL:timingEvidence)
            .put("elapsedNanos",elapsedNanos).put("route",new JSONObject(routeJson)).put("usage",new JSONObject(usageJson))
            .put("dispatchMeaning",java.util.Set.of("server_response_observed","server_transcription_observed").contains(new JSONObject(routeJson).optString("requestOrigin"))?"server_event_observed_not_client_pre_dispatch_or_charge_proof":"transport_attempt_not_proof_of_provider_charge").put("evidenceOnly",true);
        if(failureJson!=null)value.put("failure",new JSONObject(failureJson));return value;}
}
