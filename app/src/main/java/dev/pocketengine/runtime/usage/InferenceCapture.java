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
        public final LogicalInferenceAuthority logical;
        private final Sink sink;
        private final Admission admission;
        public Target(InferenceReceipt.Context context,Sink sink){this(context,sink,()->{});}
        public Target(InferenceReceipt.Context context,Sink sink,Admission admission){this(context,sink,admission,null);}
        public Target(InferenceReceipt.Context context,Sink sink,Admission admission,LogicalInferenceAuthority logical){this.logical=logical;this.context=java.util.Objects.requireNonNull(context);this.sink=java.util.Objects.requireNonNull(sink);this.admission=java.util.Objects.requireNonNull(admission);}
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
        },original.admission,original.logical);
    }
    /** Add a currentness check without replacing the original run admission or durable sink. */
    public static Target withAdmission(Target original,Admission extra){
        java.util.Objects.requireNonNull(original);java.util.Objects.requireNonNull(extra);
        return new Target(original.context,original.sink,()->{original.admission.check();extra.check();},original.logical);
    }
    public static Target withLogicalAuthority(Target original,LogicalInferenceAuthority authority){return new Target(original.context,original.sink,original.admission,java.util.Objects.requireNonNull(authority));}
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
    private boolean started,finished,terminalPersisted;
    private long deadlineAt=Long.MAX_VALUE;
    public InferenceCapture(ModelConfig config,RunControl control,Target target)throws Exception{this(config,control,target,config.usesAccount()?"openai_responses":config.provider);}
    private InferenceCapture(ModelConfig config,RunControl control,Target target,String protocol)throws Exception{
        this.target=java.util.Objects.requireNonNull(target);observer=presentationObserver;
        secret=config.apiKey;pricingConfig=config;
        requireOriginalContext(control,target);
        usage=new ProviderUsage.Collector(protocol);
        route.put("kind",config.usesAccount()?"account":"api_key").put("protocol",protocol).put("requestedModel",config.model)
                .put("responseModel",JSONObject.NULL).put("historyDigest",JSONObject.NULL);
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
    /** Same constructor identity gate, before presentation wrappers or local preflight callbacks. */
    public static void requireOriginalContext(RunControl control,Target target){
        InferenceReceipt.Context context=target.context;
        if(!context.taskId.isEmpty()){
            boolean matches=context.bindingKind==InferenceReceipt.BindingKind.EXTERNAL_TASK_RUN
                    ?!control.hasHostRun()&&control.matchesHostRun(context.taskId,"",0)
                    :control.matchesHostRun(context.taskId,context.runId,context.epoch);
            if(!matches)throw new SecurityException("inference_context_does_not_match_original_run");
        }
    }
    /** Native routing metadata only; original physical usage remains the sole money fact. */
    public void logical(String id,int attempt,String candidate,String alias,String ingress,String retryReason,long deadline)throws Exception {
        if(started||attempt<1||attempt>3||!id.matches("[a-fA-F0-9-]{36}"))throw new SecurityException("invalid_logical_attempt");
        deadlineAt=deadline;route.put("logicalRequestId",id).put("attemptNo",attempt).put("candidateId",candidate).put("requestedAlias",alias).put("ingressProtocol",ingress).put("egressProtocol",route.getString("protocol")).put("retryReason",retryReason).put("deadlineAt",deadline);
    }
    public void returnedHistory(JSONObject assistant)throws Exception {if(finished)throw new SecurityException("receipt_already_finished");JSONObject metadata=assistant.optJSONObject("provider_data");if(metadata!=null)route.put("historyDigest",historyDigest(metadata));}
    public static String historyDigest(JSONObject metadata)throws Exception {JSONObject copy=new JSONObject(metadata.toString());copy.remove("originCallId");byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(canonical(copy).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String canonical(Object value)throws Exception {if(value instanceof JSONObject j){java.util.List<String> keys=new java.util.ArrayList<>();j.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(j.get(key)));}return out.append('}').toString();}if(value instanceof org.json.JSONArray a){StringBuilder out=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)out.append(',');out.append(canonical(a.get(i)));}return out.append(']').toString();}return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);}
    public long remainingMillis(ModelConfig config)throws Exception{long remaining=Math.min(config.requestTimeoutSeconds*1000L,deadlineAt-System.currentTimeMillis());if(remaining<=0)throw new java.net.SocketTimeoutException("logical_deadline_exceeded");return remaining;}
    public boolean wasDispatched(){return started;}
    public boolean terminalPersisted(){return !started||terminalPersisted;}
    public InferenceFailure failureObservation(){return failure;}
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
        finished=true;InferenceReceipt receipt=receipt(outcome);target.sink.finished(receipt);terminalPersisted=true;observe(receipt,false);
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
