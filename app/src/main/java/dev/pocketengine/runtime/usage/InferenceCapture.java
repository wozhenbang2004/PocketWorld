package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelBinding;
import java.util.UUID;
import org.json.JSONObject;

/** One physical Gateway invocation; streaming snapshots and JSON negotiation share one identity. */
public final class InferenceCapture {
    public interface Admission {void check()throws Exception;}
    public interface Sink {
        void started(InferenceReceipt receipt)throws Exception;
        void finished(InferenceReceipt receipt)throws Exception;
    }
    public static final class Target {
        public final InferenceReceipt.Context context;
        private final Sink sink;
        private final Admission admission;
        public Target(InferenceReceipt.Context context,Sink sink){this(context,sink,()->{});}
        public Target(InferenceReceipt.Context context,Sink sink,Admission admission){this.context=java.util.Objects.requireNonNull(context);this.sink=java.util.Objects.requireNonNull(sink);this.admission=java.util.Objects.requireNonNull(admission);}
    }
    private static volatile Sink presentationObserver;
    /** Native connection-page observation only; a future capture pins the currently installed observer. */
    public static void installPresentationObserver(Sink observer){presentationObserver=java.util.Objects.requireNonNull(observer);}
    private static final Sink NONE=new Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){}};
    public static Target unattributed(){return new Target(InferenceReceipt.Context.unattributed(),NONE);}
    /** Observe the same physical receipt without replacing its original admission or durable sink. */
    public static Target observe(Target original,Sink observer){
        java.util.Objects.requireNonNull(original);java.util.Objects.requireNonNull(observer);
        return new Target(original.context,new Sink(){
            public void started(InferenceReceipt receipt)throws Exception{original.sink.started(receipt);observer.started(receipt);}
            public void finished(InferenceReceipt receipt)throws Exception{original.sink.finished(receipt);observer.finished(receipt);}
        },original.admission);
    }
    /** Add a currentness check without replacing the original run admission or durable sink. */
    public static Target withAdmission(Target original,Admission extra){
        java.util.Objects.requireNonNull(original);java.util.Objects.requireNonNull(extra);
        return new Target(original.context,original.sink,()->{original.admission.check();extra.check();});
    }
    public final String callId=UUID.randomUUID().toString();
    public final ProviderUsage.Collector usage;
    private final Target target;
    private final Sink observer;
    private InferenceFailure failure;
    private final String secret;
    private final ModelConfig pricingConfig;
    private JSONObject frozenPrice;
    private final JSONObject route=new JSONObject();
    private long startedAt,startNanos;
    private boolean started,finished;
    public InferenceCapture(ModelConfig config,RunControl control,Target target)throws Exception{this(config,control,target,config.usesAccount()?"openai_responses":config.provider);}
    private InferenceCapture(ModelConfig config,RunControl control,Target target,String protocol)throws Exception{
        this.target=java.util.Objects.requireNonNull(target);observer=presentationObserver;
        secret=config.apiKey;pricingConfig=config;
        InferenceReceipt.Context context=target.context;
        if(!context.taskId.isEmpty()){
            boolean matches=context.bindingKind==InferenceReceipt.BindingKind.EXTERNAL_TASK_RUN
                    ?!control.hasHostRun()&&control.matchesHostRun(context.taskId,"",0)
                    :control.matchesHostRun(context.taskId,context.runId,context.epoch);
            if(!matches)throw new SecurityException("inference_context_does_not_match_original_run");
        }
        usage=new ProviderUsage.Collector(protocol);
        route.put("kind",config.usesAccount()?"account":"api_key").put("protocol",protocol).put("requestedModel",config.model)
                .put("responseModel",JSONObject.NULL);
        if(config.usesAccount())route.put("accountId",config.accountModel.accountId).put("accountVersion",config.accountModel.accountVersion);
        ModelBinding binding=config.capabilityBinding;
        if(binding!=null)route.put("connectionId",binding.connectionId()).put("providerId",binding.providerId())
                .put("credentialId",binding.credentialId()).put("credentialRevision",binding.credentialRevision())
                .put("installationId",binding.installationId()).put("installationRevision",binding.installationRevision());
        // A key accidentally pasted into a public model/connection label is still a secret.
        boolean redacted=false;java.util.Iterator<String> keys=route.keys();java.util.List<String> hidden=new java.util.ArrayList<>();
        if(!secret.isEmpty())while(keys.hasNext()){
            String key=keys.next();Object value=route.opt(key);if(value instanceof String&&((String)value).contains(secret))hidden.add(key);
        }
        for(String key:hidden){route.put(key,JSONObject.NULL);redacted=true;}
        route.put("identityRedacted",redacted);
    }
    /** Native Realtime API-key transport only, not a ChatGPT plan or a guessed model route. */
    public static InferenceCapture realtime(ModelConfig captured,long profileRevision,RunControl original,Target target)throws Exception{
        if(captured.usesAccount()||profileRevision<1)throw new SecurityException("native_realtime_api_profile_required");
        InferenceCapture capture=new InferenceCapture(captured,original,target,"openai_realtime");capture.route.put("realtimeProfileRevision",profileRevision).put("requestOrigin","server_response_observed");return capture;
    }
    public static InferenceCapture realtimeTranscription(ModelConfig captured,long profileRevision,RunControl original,Target target)throws Exception{
        if(captured.usesAccount()||profileRevision<1||target.context.purpose!=InferenceReceipt.Purpose.REALTIME_TRANSCRIPTION)throw new SecurityException("native_transcription_session_required");
        InferenceCapture capture=new InferenceCapture(captured,original,target,"openai_realtime_transcription");capture.route.put("realtimeProfileRevision",profileRevision).put("requestOrigin","server_transcription_observed");return capture;
    }
    /** Actual committed native input item, not a response.created event or proof of provider charge. */
    public void observedServerTranscription(String itemId)throws Exception{
        if(started||!"server_transcription_observed".equals(route.optString("requestOrigin"))||itemId==null||!itemId.matches("[A-Za-z0-9_.:-]{1,200}"))throw new SecurityException("invalid_native_transcription_observation");
        route.put("providerItemId",secret.isEmpty()||!itemId.contains(secret)?itemId:JSONObject.NULL).put("startEvidence","input_audio_buffer.committed");dispatch();
    }
    /** The server's response id is session-local correlation, never the physical callId or a grant. */
    public void observedServerResponse(String responseId)throws Exception{
        if(started||!"server_response_observed".equals(route.optString("requestOrigin"))||responseId==null||!responseId.matches("[A-Za-z0-9_.:-]{1,200}"))throw new SecurityException("invalid_native_response_observation");
        route.put("providerResponseId",secret.isEmpty()||!responseId.contains(secret)?responseId:JSONObject.NULL);dispatch();
    }
    /** Register uncertain dispatch before network I/O; a storage failure prevents dispatch. */
    public void dispatch()throws Exception{
        if(started)return;
        target.admission.check();
        startedAt=System.currentTimeMillis();startNanos=System.nanoTime();
        frozenPrice=InferencePricing.freeze(pricingConfig,startedAt);
        InferenceReceipt receipt=receipt(InferenceReceipt.Outcome.STARTED);target.sink.started(receipt);started=true;observe(receipt,true);
        // Never call another coordinator while holding TaskStore's SQLite monitor.
        target.admission.check();
    }
    public void accountVersion(long actual)throws Exception{route.put("accountVersion",actual);}
    public void finish(InferenceReceipt.Outcome outcome)throws Exception{
        if(!started||finished)return;
        if(outcome==InferenceReceipt.Outcome.STARTED)throw new IllegalArgumentException("terminal_receipt_required");
        finished=true;InferenceReceipt receipt=receipt(outcome);target.sink.finished(receipt);observe(receipt,false);
    }
    public void failure(InferenceFailure nativeFailure){if(!finished)failure=java.util.Objects.requireNonNull(nativeFailure);}
    private void observe(InferenceReceipt receipt,boolean started){
        if(observer==null)return;
        try{if(started)observer.started(receipt);else observer.finished(receipt);}catch(Exception ignored){/* A presentation failure never changes the already committed original receipt. */}
    }
    public void failed(InferenceReceipt.Outcome outcome,Exception original){
        try{finish(outcome);}catch(Exception storage){original.addSuppressed(storage);}
    }
    private InferenceReceipt receipt(InferenceReceipt.Outcome outcome)throws Exception{
        String model=usage.reportedModel();
        if(model!=null&&(secret.isEmpty()||!model.contains(secret)))route.put("responseModel",model);
        return new InferenceReceipt(callId,target.context,outcome,startedAt,
                outcome==InferenceReceipt.Outcome.STARTED?0:System.currentTimeMillis(),Math.max(0,System.nanoTime()-startNanos),route,InferencePricing.apply(usage.toJson(),frozenPrice),outcome==InferenceReceipt.Outcome.STARTED?null:failure);
    }
}
