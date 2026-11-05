package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.providers.ProviderAdapter;
import dev.pocketengine.runtime.providers.AccountResponsesAdapter;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import dev.pocketengine.runtime.providers.ApiTurnStream;
import dev.pocketengine.runtime.providers.bedrock.BedrockEventStream;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.runtime.usage.LogicalInferenceAuthority;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.InferenceFailure;
import dev.pocketengine.runtime.usage.ProviderUsage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** One cancellable HTTP transport with native wire adapters; no tool retries or second agent loop. */
public final class ModelGateway {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    /** Local serialized transport bound, not an inferred model context window. */
    public static final int MAX_REQUEST_BYTES = 24 * 1024 * 1024;
    private static final OkHttpClient STREAM_HTTP=new OkHttpClient.Builder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).cache(null).build();

    private ModelGateway() { }
    private static final ModelCallAdmission MODEL_CALLS=new ModelCallAdmission();
    @FunctionalInterface public interface ModelGroupAdmission {void check(ModelRoutePlan plan,String candidateId,ModelConfig candidate)throws Exception;}
    private static volatile ModelGroupAdmission modelGroupAdmission;
    /** Original native SettingsStore owner only; snapshots never freeze revocation. */
    public static void installModelGroupAdmission(ModelGroupAdmission source){modelGroupAdmission=java.util.Objects.requireNonNull(source);}
    private static void checkGroup(ModelRoutePlan plan,ModelRoutePlan.Candidate candidate)throws Exception {if(plan.groupRevision==0)return;ModelGroupAdmission source=modelGroupAdmission;if(source==null)throw new SecurityException("model_group_owner_unavailable");source.check(plan,candidate.candidateId(),candidate.config());if(source!=modelGroupAdmission)throw new SecurityException("model_group_owner_changed");}


    /** Trusted application-only seam. Never a feature command, model tool, or credential getter. */
    public interface AccountInferencePort {
        /** Lock-free native metadata projection only; never take Gateway/Book/TaskStore locks or perform I/O. */
        default boolean accountIdentityCurrent(ModelConfig.AccountModelRef reference){return false;}
        default boolean imageInputSupported(ModelConfig.AccountModelRef reference){return false;}
        /** Immutable exact-version native facts only; no account/book/task monitor or I/O. */
        default dev.pocketengine.runtime.models.AccountCatalogFacts modelFacts(ModelConfig.AccountModelRef reference)throws GatewayFailure{return null;}
        default boolean planUsageLimited(ModelConfig.AccountModelRef reference){return false;}
        void stream(ModelConfig.AccountModelRef reference,ResponsesRequest request,GatewayCall operation,
                    ChatGptProvider.EventSink sink)throws GatewayFailure;
        /** Compatibility ports keep exact-version behavior until their trusted host wires renewal CAS. */
        default void streamWithVersion(ModelConfig.AccountModelRef reference,ResponsesRequest request,GatewayCall operation,
                                       AccountVersionSink versions,ChatGptProvider.EventSink sink)throws GatewayFailure {
            versions.authorized(reference.accountVersion);stream(reference,request,operation,sink);
        }
    }
    public interface AccountVersionSink {void authorized(long actualVersion)throws GatewayFailure;}
    private static volatile AccountInferencePort accountInference;
    // Ephemeral authorization continuity only. Task/operation state remains exclusively in TaskStore.
    private static final java.util.Map<RunControl,AccountBinding> accountBindings=new java.util.WeakHashMap<>();
    public static void installAccountInferencePort(AccountInferencePort port){synchronized(accountBindings){accountInference=port;accountBindings.clear();}}
    public static boolean accountInferenceInstalled(){return accountInference!=null;}
    /** Same original run's actually accepted renewal lineage plus current native revocation state.
     * This does not authorize a POST; AccountGateway still rechecks credentials/scope before dispatch.
     * Safe under TaskStore's monitor: no gateway/account-book monitor or network is entered.
     */
    public static boolean accountSelectionContinues(RunControl original,ModelConfig.AccountModelRef captured,ModelConfig.AccountModelRef selected){
        AccountInferencePort port=accountInference;if(port==null||original==null||original.isCancelled()||captured==null||selected==null||!captured.sameModel(selected))return false;
        boolean lineage;synchronized(accountBindings){AccountBinding bound=accountBindings.get(original);lineage=bound==null?captured.matches(selected):bound.selectionContinues(captured,selected);}
        if(!lineage)return false;
        try{return port.accountIdentityCurrent(selected)&&port==accountInference&&!original.isCancelled();}catch(RuntimeException unavailable){return false;}
    }
    /** No network or credential access. Unknown, stale, disabled, and missing evidence all deny images. */
    public static boolean accountSupportsImageInput(ModelConfig.AccountModelRef reference){
        AccountInferencePort port=accountInference;if(port==null||reference==null)return false;
        try{return port.imageInputSupported(reference)&&port==accountInference;}catch(RuntimeException unavailable){return false;}
    }
    public static dev.pocketengine.runtime.models.AccountCatalogFacts accountModelFacts(ModelConfig.AccountModelRef reference)throws GatewayFailure {
        AccountInferencePort port=accountInference;if(port==null||reference==null)throw new GatewayFailure("GATEWAY_CLOSED");
        dev.pocketengine.runtime.models.AccountCatalogFacts facts=port.modelFacts(reference);
        if(port!=accountInference)throw new GatewayFailure("GATEWAY_CLOSED");return facts;
    }
    /** Read-only UI readiness for this exact account/model. Never authorizes inference or renews credentials. */
    public static boolean accountModelAvailableForDisplay(ModelConfig.AccountModelRef reference){
        AccountInferencePort port=accountInference;if(port==null||reference==null)return false;
        try{
            if(!port.accountIdentityCurrent(reference))return false;
            port.modelFacts(reference);
            return port==accountInference&&port.accountIdentityCurrent(reference);
        }catch(GatewayFailure|RuntimeException unavailable){return false;}
    }
    /** Only this actual Run's accepted native renewal lineage may advance a captured budget identity. */
    public static ModelConfig accountBudgetConfig(ModelConfig captured,RunControl original)throws GatewayException {
        if(!captured.usesAccount())return captured;
        if(original==null||original.isCancelled())throw new GatewayException("原任务已停止，不能读取新账号能力。");
        synchronized(accountBindings){AccountBinding bound=accountBindings.get(original);
            if(bound==null)return captured;
            if(!bound.accepts(captured.accountModel))throw new GatewayException("原任务账号模型身份已变化，请核对原连接。");
            return captured.withAccountModel(bound.current());
        }
    }
    /** Exact native account hold, not a diagnosis inferred from a provider message. */
    public static boolean accountPlanUsageLimited(ModelConfig.AccountModelRef reference){
        AccountInferencePort port=accountInference;if(port==null||reference==null)return false;
        try{return port.planUsageLimited(reference)&&port==accountInference;}catch(RuntimeException unavailable){return false;}
    }
    public static void validate(ModelConfig config)throws GatewayException {
        if(config==null||config.model.isEmpty())throw new GatewayException("请先选择模型。");
        if(config.usesAccount()){
            if(accountInference==null)throw new GatewayException("账号推理尚未连接到应用宿主，请重新打开应用或选择API密钥模型。");
        }else {endpoint(config);try{ModelRequestPolicy.resolve(config);}catch(ModelRequestPolicy.Rejected refused){throw new GatewayException(refused.getMessage());}catch(Exception unavailable){throw new GatewayException("当前模型能力状态无法读取，请回模型页核对。");}}
    }

    public static JSONObject testConnection(ModelConfig config) {
        return testConnection(config,new RunControl(),InferenceCapture.unattributed());
    }
    /** Native host passes the same original task/call target; compatibility overload is not a durable host. */
    public static JSONObject testConnection(ModelConfig config,RunControl original,InferenceCapture.Target target) {
        JSONObject result = new JSONObject();
        try {
            JSONArray messages = new JSONArray().put(new JSONObject()
                    .put("role", "user").put("content", "Reply with OK."));
            complete(config, messages, null, original,target);
            result.put("ok", true).put("message", "文字连接正常；图片理解和手机工具能力尚未验证。")
                    .put("model", config.model).put("visionVerified", false).put("toolsVerified", false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            putFailure(result, "连接测试已停止。", "cancelled");
        } catch (Exception error) {
            putFailure(result, safeMessage(error, config), "connection_failed");
        }
        return result;
    }

    /** Returns the actual assistant message, including structured tool_calls. */
    public static JSONObject complete(ModelConfig config, JSONArray messages, JSONArray tools,
                                      RunControl control) throws Exception {
        return complete(config,messages,tools,control,InferenceCapture.unattributed());
    }
    public static JSONObject complete(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,
                                      InferenceCapture.Target target)throws Exception{
        return completeWithAdmission(config,messages,tools,control,target,candidate->{});
    }
    /** Additional original-consumer budget, checked for every candidate and again before dispatch. */
    public static JSONObject completeWithAdmission(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,InferenceCapture.Target target,CandidateAdmission originalBudget)throws Exception {
        return completeBounded(config,messages,tools,control,target,originalBudget,Long.MAX_VALUE);
    }
    /** A native multi-phase caller can only shorten this original request's existing deadline. */
    public static JSONObject completeBefore(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,InferenceCapture.Target target,long originalDeadlineAt)throws Exception {
        if(originalDeadlineAt<=0)throw new IllegalArgumentException("original_model_deadline_required");
        return completeBounded(config,messages,tools,control,target,candidate->{},originalDeadlineAt);
    }
    private static JSONObject completeBounded(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,InferenceCapture.Target target,CandidateAdmission originalBudget,long originalDeadlineAt)throws Exception {
        return executeLogical(config,messages,tools,control,target,"INTERNAL",null,(candidate,capture,commit)->{
            JSONObject result=candidate.usesAccount()?completeAccount(candidate,messages,tools,control,null,capture):completeApi(candidate,messages,tools,control,null,capture,target);
            commit.beforeFlush(capture.callId,1);return result;
        },candidate->{validate(candidate);if(!candidate.usesAccount())applyRequestPolicy(candidate,messages,tools,ProviderAdapters.selected(candidate.provider).request(candidate,messages,tools),target,control);originalBudget.check(candidate);},originalDeadlineAt);
    }
    /** Resolve only the actual physical receipt against this native frozen plan, not a later default. */
    public static ModelConfig actualConfigForReceipt(ModelConfig captured,InferenceReceipt receipt)throws Exception {
        if(receipt==null||receipt.outcome!=InferenceReceipt.Outcome.COMPLETED)throw new SecurityException("original_complete_model_receipt_required");
        JSONObject route=receipt.toJson().getJSONObject("route");java.util.List<ModelRoutePlan.Candidate> candidates=captured.routePlan==null?java.util.List.of(new ModelRoutePlan.Candidate("exact",captured,"")):captured.routePlan.candidates;
        for(ModelRoutePlan.Candidate candidate:candidates){ModelConfig config=candidate.config();if(config==null||!candidate.exclusion().isEmpty()||!config.model.equals(route.optString("requestedModel"))||!(config.usesAccount()?"openai_responses":config.provider).equals(route.optString("protocol")))continue;
            if(route.has("candidateId")&&!candidate.candidateId().equals(route.getString("candidateId")))continue;
            if(config.usesAccount()){if(config.accountModel.accountId.equals(route.optString("accountId"))&&route.optLong("accountVersion")>=config.accountModel.accountVersion)return config.withAccountModel(config.accountModel.withVersion(route.getLong("accountVersion")));continue;}
            var binding=config.capabilityBinding;if(binding==null){if(!route.has("connectionId"))return config;}else if(binding.connectionId().equals(route.optString("connectionId"))&&binding.credentialId().equals(route.optString("credentialId"))&&binding.credentialRevision()==route.optLong("credentialRevision")&&binding.installationId().equals(route.optString("installationId"))&&binding.installationRevision()==route.optLong("installationRevision"))return config;
        }
        throw new SecurityException("receipt_not_in_original_model_plan");
    }

    public enum StreamKind { STARTED, TEXT_DELTA, COMPLETED, FAILED, CANCELLED }

    /** Presentation only. This event is never a tool receipt, task status, permission or reasoning dump. */
    public static final class StreamEvent {
        public final StreamKind kind;
        public final String requestId,provider,text,code;
        public final long sequence,pauseRevision;
        public final boolean provisional;
        private StreamEvent(StreamKind kind,String requestId,long sequence,String provider,long pauseRevision,
                            String text,String code){
            this.kind=kind;this.requestId=requestId;this.sequence=sequence;this.provider=provider;
            this.pauseRevision=pauseRevision;this.text=text;this.code=code;this.provisional=kind==StreamKind.TEXT_DELTA;
        }
    }
    public interface StreamSink { void event(StreamEvent event)throws Exception; }

    /**
     * Opt-in streaming of visible text; the return value alone contains completed validated tools.
     * Old complete() keeps its JSON request contract. Each physical attempt is single-dispatch;
     * native logical recovery is bounded and ends as soon as semantic output is committed.
     * Native account deltas share this presentation channel; only the verified terminal yields tools.
     */
    public static JSONObject completeStreaming(ModelConfig config,JSONArray messages,JSONArray tools,
                                               RunControl control,StreamSink sink)throws Exception {
        return completeStreaming(config,messages,tools,control,sink,InferenceCapture.unattributed());
    }
    public static JSONObject completeStreaming(ModelConfig config,JSONArray messages,JSONArray tools,
                                               RunControl control,StreamSink sink,InferenceCapture.Target target)throws Exception {
        return completeStreaming(config,messages,tools,control,sink,target,null);
    }
    /** Original Runner presentation/currentness only. Gateway remains the sole retry owner. */
    public interface RetryObserver {
        void beforeWait(String logicalRequestId,String failedCallId,int retryNumber,long waitMillis)throws Exception;
        void afterWait()throws Exception;
    }
    public static final class RequestRefreshRequired extends Exception {public RequestRefreshRequired(){super("original_request_context_changed");}}
    public static JSONObject completeStreaming(ModelConfig config,JSONArray messages,JSONArray tools,
            RunControl control,StreamSink sink,InferenceCapture.Target target,RetryObserver retryObserver)throws Exception {
        return completeStreaming(config,messages,tools,control,sink,target,retryObserver,null);
    }
    /** The original Runner captures this revision before polling its durable owner mailbox. */
    public static JSONObject completeStreaming(ModelConfig config,JSONArray messages,JSONArray tools,
            RunControl control,StreamSink sink,InferenceCapture.Target target,RetryObserver retryObserver,long ownerInputRevision)throws Exception {
        try(RunControl.ModelRequest request=control.openModelRequest(ownerInputRevision)){
            return completeStreaming(config,messages,tools,control,sink,target,retryObserver,request);
        }
    }
    private static JSONObject completeStreaming(ModelConfig config,JSONArray messages,JSONArray tools,
            RunControl control,StreamSink sink,InferenceCapture.Target target,RetryObserver retryObserver,RunControl.ModelRequest request)throws Exception {
        InferenceCapture.requireOriginalContext(control,target);
        try{return executeLogical(config,messages,tools,control,target,"INTERNAL",null,new PhysicalAttempt(){
            private StreamSession stream;
            public RunControl.ModelRequest modelRequest(){return request;}
            public JSONObject run(ModelConfig candidate,InferenceCapture capture,SemanticCommit commit)throws Exception {
                stream=new StreamSession(candidate,control,sink,capture.callId,commit,request);
                stream.emit(StreamKind.STARTED,"","request_started",true);
                return candidate.usesAccount()?completeAccount(candidate,messages,tools,control,stream,capture):completeApi(candidate,messages,tools,control,stream,capture,target);
            }
            public void afterPersist(InferenceCapture capture)throws Exception {stream.finish();}
            public void rejected(String logical,ModelConfig candidate)throws Exception {stream=new StreamSession(candidate,control,sink,logical,(call,sequence)->{throw new SecurityException("rejected_request_cannot_flush");},request);stream.failed(StreamKind.FAILED,"candidate_admission_failed");}
            public void failed(Exception failure){if(stream!=null)stream.failed(failure instanceof InterruptedException||failure instanceof java.util.concurrent.CancellationException?StreamKind.CANCELLED:StreamKind.FAILED,"stream_failed");}
            public boolean receivedText(){return stream!=null&&stream.textReceived;}
            public void beforeWait(String logical,String call,int retry,long delay)throws Exception {if(retryObserver!=null)retryObserver.beforeWait(logical,call,retry,delay);}
            public void afterWait()throws Exception {if(retryObserver!=null)retryObserver.afterWait();}
        });}catch(InterruptedException|java.util.concurrent.CancellationException stopped){throw stopped;}
        catch(Exception failure){
            if(failure instanceof GatewayException||failure instanceof RequestRefreshRequired||failure instanceof dev.pocketengine.core.RunJournal.NativeBudgetPaused||failure instanceof dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable)throw failure;
            throw controlled(new GatewayException("模型流或显示回调未能完整结束；没有派发工具或自动重试。"),target.logical!=null);
        }
    }

    /** All protocol ingress joins the same native logical/physical pipeline. Never executes tools. */
    public static JSONObject completeNative(ModelConfig config,dev.pocketengine.runtime.providers.GatewayRequest request,RunControl control,
            InferenceCapture.Target target,dev.pocketengine.runtime.providers.ProtocolGateway.SemanticCommit externalCommit,
            dev.pocketengine.runtime.providers.ProtocolGateway.WireSink sink)throws Exception {
        if(config.usesAccount())throw new SecurityException("CHATGPT_PLAN_APP_ONLY");
        if(config.routePlan!=null&&config.routePlan.groupRevision>0&&!"EXTERNAL_RELAY".equals(config.routePlan.audience))throw new SecurityException("model_group_external_audience_not_granted");
        return executeLogical(config,request.messages,request.tools,control,target,request.ingressProtocol,request.requestDigest,(candidate,capture,commit)->{
            var prepared=request.forCandidate(candidate.withRoutePlan(config.routePlan));
            try{return dev.pocketengine.runtime.providers.ProtocolGateway.attempt(candidate,prepared,control,capture,(call,seq)->{commit.beforeFlush(call,seq);if(externalCommit!=null)externalCommit.beforeFlush(call,seq);},sink);}
            catch(dev.pocketengine.runtime.providers.ProtocolGateway.Failure failure){throw new GatewayException(failure.getMessage(),failure.observation());}
        },candidate->request.forCandidate(candidate.withRoutePlan(config.routePlan)).revalidate());
    }

    @FunctionalInterface public interface SemanticCommit {void beforeFlush(String callId,long sequence)throws Exception;}
    @FunctionalInterface private interface PhysicalAttempt {
        JSONObject run(ModelConfig candidate,InferenceCapture capture,SemanticCommit commit)throws Exception;
        default RunControl.ModelRequest modelRequest(){return null;}
        default void afterPersist(InferenceCapture capture)throws Exception { }
        default void rejected(String logical,ModelConfig candidate)throws Exception { }
        default void failed(Exception failure){ }
        default boolean receivedText(){return false;}
        default void beforeWait(String logical,String call,int retry,long delay)throws Exception { }
        default void afterWait()throws Exception { }
    }
    @FunctionalInterface public interface CandidateAdmission {void check(ModelConfig candidate)throws Exception;}
    /** The one retry owner. A physical HTTP implementation remains strictly single-dispatch. */
    private static JSONObject executeLogical(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,InferenceCapture.Target target,String ingress,String nativeDigest,PhysicalAttempt physical)throws Exception {
        return executeLogical(config,messages,tools,control,target,ingress,nativeDigest,physical,candidate->{
            validate(candidate);
            if(!candidate.usesAccount())applyRequestPolicy(candidate,messages,tools,ProviderAdapters.selected(candidate.provider).request(candidate,messages,tools),target,control);
        });
    }
    private static JSONObject executeLogical(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,InferenceCapture.Target target,String ingress,String nativeDigest,PhysicalAttempt physical,CandidateAdmission preflight)throws Exception {
        return executeLogical(config,messages,tools,control,target,ingress,nativeDigest,physical,preflight,Long.MAX_VALUE);
    }
    private static JSONObject executeLogical(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,InferenceCapture.Target target,String ingress,String nativeDigest,PhysicalAttempt physical,CandidateAdmission preflight,long originalDeadlineAt)throws Exception {
        InferenceCapture.requireOriginalContext(control,target);
        config=config.forPurpose(target.context.purpose.name());
        RunControl.ModelRequest request=physical.modelRequest();
        checkRequest(control,request);LogicalInferenceAuthority authority=target.logical;
        if(config.routePlan!=null&&authority==null)throw new SecurityException("模型组需要原TaskStore逻辑请求准入；没有调用未归因通路");
        ModelRoutePlan plan=config.routePlan==null?new ModelRoutePlan(config.model,0,target.context.purpose.name(),target.context.purpose==InferenceReceipt.Purpose.RELAY?"EXTERNAL_RELAY":"APP_OWNER",java.util.List.of(new ModelRoutePlan.Candidate("exact",config,"")),authority==null?1:3,0):config.routePlan;
        long deadline=Math.min(originalDeadlineAt,System.currentTimeMillis()+config.requestTimeoutSeconds*1000L);JSONObject snapshot=plan.toJson();JSONArray rows=snapshot.getJSONArray("orderedCandidates");java.util.List<ModelRoutePlan.Candidate> eligible=new java.util.ArrayList<>();Exception rejection=null;
        boolean opaque=false;for(int i=0;messages!=null&&i<messages.length();i++)if(messages.getJSONObject(i).optJSONObject("provider_data")!=null)opaque=true;
        for(int i=0;i<plan.candidates.size();i++){
            ModelRoutePlan.Candidate candidate=plan.candidates.get(i);if(candidate.config()==null||!candidate.exclusion().isEmpty()||!plan.dispatches(candidate))continue;
            try{
                if(candidate.config().usesAccount()&&target.context.purpose==InferenceReceipt.Purpose.MODEL_CAPABILITY_PROBE)throw new SecurityException("API_ONLY_MODEL_CAPABILITY_PROBE");
                if(candidate.config().usesAccount()&&(!"APP_OWNER".equals(plan.audience)||target.context.purpose==InferenceReceipt.Purpose.RELAY))throw new SecurityException("CHATGPT_PLAN_APP_ONLY");
                if(opaque&&authority!=null)for(int n=0;n<messages.length();n++){JSONObject data=messages.getJSONObject(n).optJSONObject("provider_data");if(data!=null&&!(candidate.config().usesAccount()&&ModelConfig.ACCOUNT_PROVIDER.equals(data.optString("provider")))&&!authority.matchesHistoryOrigin(data.optString("originCallId"),candidate.config(),data))throw new SecurityException("opaque_history_origin_mismatch");}
                checkGroup(plan,candidate);preflight.check(candidate.config());eligible.add(candidate);
            }catch(Exception refused){if(refused instanceof InterruptedException)throw refused;rejection=refused;rows.getJSONObject(i).put("exclusion",refused instanceof ModelRequestPolicy.Rejected?((ModelRequestPolicy.Rejected)refused).code:refused instanceof SecurityException?"origin_or_audience_denied":"candidate_capability_unavailable");}
        }
        String logical=java.util.UUID.randomUUID().toString();int maximum=plan.dispatchMaxAttempts();
        String resourceOwner=null;int inheritedResourceLimit=Integer.MAX_VALUE;
        if(authority!=null){String digest=nativeDigest==null?requestDigest(messages,tools):nativeDigest;JSONObject admitted=authority.begin(logical,digest,snapshot,deadline,plan.dispatchMaxAttempts());deadline=Math.min(deadline,admitted.getLong("deadlineAt"));maximum=Math.min(plan.dispatchMaxAttempts(),admitted.getJSONObject("control").getInt("maxAttempts"));
            if(admitted.has("modelResourceScope")){
                if(!(admitted.opt("modelResourceScope") instanceof JSONObject))throw new SecurityException("original_native_model_resource_scope_required");
                JSONObject resources=admitted.getJSONObject("modelResourceScope");
                String owner=resources.getString("ownerTaskId");Object raw=resources.get("maxConcurrentModelCalls");
                if(!owner.equals(admitted.getString("budgetOwnerRef"))||!owner.matches("[a-f0-9-]{36}")||!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<1||((Number)raw).longValue()>2)throw new SecurityException("original_native_model_resource_scope_required");
                resourceOwner=owner;inheritedResourceLimit=((Number)raw).intValue();
            }
        }
        if(eligible.isEmpty()){
            physical.rejected(logical,config);
            if(authority!=null)authority.finish(logical,"FAILED","failed","none");if(rejection!=null)throw controlled(rejection,authority!=null);throw new GatewayException("模型组没有可用候选；请在模型页核对原连接和用途。没有回退到其他账号。");
        }
        int candidateIndex=0,switches=0;String retryReason="initial_ordered_candidate";long waited=0;boolean semantic=false,anyDispatched=false;
        try { for(int attempt=1;attempt<=maximum;attempt++){
            checkRequest(control,request);if(System.currentTimeMillis()>=deadline){if(authority!=null)authority.finish(logical,"FAILED","unknown","none");throw controlled(localFailure("模型请求总期限已到，等待与重试没有重置期限。",InferenceFailure.Kind.TIMEOUT),authority!=null);}
            ModelRoutePlan.Candidate chosen=eligible.get(candidateIndex);ModelConfig selected=chosen.config();
            RunControl.HostRunIdentity originalRun=control.hostRunIdentity();String originalTask=resourceOwner!=null?resourceOwner:originalRun==null?target.context.taskId:originalRun.taskId();
            ModelCallAdmission.Permit permit=MODEL_CALLS.enter(control,originalTask,deadline,inheritedResourceLimit,request);InferenceCapture capture;
            try{checkGroup(plan,chosen);preflight.check(selected);capture=new InferenceCapture(selected,control,InferenceCapture.withAdmission(target,()->checkGroup(plan,chosen)));capture.limitDeadlineAt(deadline);capture.tokenReservation(messages,tools);
                if(authority!=null){capture.logical(logical,attempt,chosen.candidateId(),plan.requestedAlias,ingress,retryReason,deadline);authority.reserve(logical,capture.callId,attempt,chosen.candidateId(),retryReason);}
            }catch(Exception|Error refused){permit.close();throw refused;}
            final boolean[] committed={false};SemanticCommit commit=(call,sequence)->{checkRequest(control,request);checkGroup(plan,chosen);if(!capture.callId.equals(call))throw new SecurityException("semantic_call_identity_changed");if(!committed[0]){if(authority!=null)authority.commit(logical,call,sequence);committed[0]=true;}};
            try{
                JSONObject result=physical.run(selected,capture,commit);checkRequest(control,request);commit.beforeFlush(capture.callId,1);
                capture.returnedHistory(result);capture.finish(InferenceReceipt.Outcome.COMPLETED);physical.afterPersist(capture);checkRequest(control,request);
                if(authority!=null){authority.attemptEnded(logical,capture.callId,capture.wasDispatched(),"complete");authority.finish(logical,"SUCCEEDED","complete","complete");JSONObject metadata=result.optJSONObject("provider_data");if(metadata!=null&&!selected.usesAccount())metadata.put("originCallId",capture.callId);}
                return result;
            }catch(Exception failure){
                permit.close();anyDispatched|=capture.wasDispatched();
                semantic|=committed[0];boolean cancelled=failure instanceof InterruptedException||failure instanceof java.util.concurrent.CancellationException||control.isCancelled();
                observeFailure(capture,failure,physical.receivedText(),cancelled);capture.failed(cancelled?InferenceReceipt.Outcome.CANCELLED:InferenceReceipt.Outcome.FAILED,failure);physical.failed(failure);
                InferenceFailure facts=failure instanceof GatewayException e?e.observation():null;
                boolean uncertain=uncertainCompletion(selected,capture.wasDispatched(),facts);
                if(authority!=null)try{authority.attemptEnded(logical,capture.callId,capture.wasDispatched(),cancelled?"cancelled":semantic?"partial":uncertain?"unknown":"failed");}catch(Exception storage){failure.addSuppressed(storage);throw controlled(failure,true);}
                // A failed physical receipt does not prove that Bedrock did not process or bill
                // the request. Its original call stays available for owner reconciliation.
                boolean retry=capture.terminalPersisted()&&!cancelled&&!semantic&&!opaque&&!selected.usesAccount()&&!selected.usesBedrock()&&capture.wasDispatched()&&attempt<maximum&&retryableLogical(facts);
                long delay=500L*attempt;
                if(retry&&facts!=null){JSONObject after=facts.retry();if("INVALID".equals(after.optString("status")))retry=false;else if("VALID".equals(after.optString("status")))delay=Math.max(delay,after.getLong("estimatedNotBefore")-System.currentTimeMillis());}
                if(delay>30_000-waited||delay>=deadline-System.currentTimeMillis())retry=false;
                if(retry&&authority!=null&&!authority.reserveRetry(logical,capture.callId,delay))retry=false;
                if(!retry){if(authority!=null)authority.finish(logical,cancelled?"CANCELLED":semantic?"PARTIAL":uncertain?"UNKNOWN":"FAILED",semantic||uncertain?"unknown":"failed",semantic?"partial":"none");if(semantic&&facts!=null&&facts.kind==InferenceFailure.Kind.INCOMPLETE_STREAM)failure=new GatewayException("本次输出已提交，但模型流在终态前中断；没有自动重发或切换模型。请在原任务核对片段并明确继续，本次未派发工具。",facts);throw controlled(failure,authority!=null);}
                // Explicit transient 429 never changes RouteKey. Unknown 429 is not retryable.
                if(facts.httpStatus!=429&&switches<plan.maxRouteSwitches&&candidateIndex+1<eligible.size()){candidateIndex++;switches++;retryReason="transient_failure_next_candidate";}else retryReason="transient_failure_same_route";
                physical.beforeWait(logical,capture.callId,attempt,delay);waited+=delay;if(request==null)control.sleep(delay);else request.sleep(delay);checkRequest(control,request);physical.afterWait();
            }finally{permit.close();}
        }
        throw new IllegalStateException("logical_attempt_loop_exhausted");
        }catch(Exception stopped){
            boolean knownNotDispatched=stopped instanceof ModelCallAdmission.Refused&&!anyDispatched;
            if(authority!=null)try{authority.finish(logical,stopped instanceof InterruptedException||control.isCancelled()?"CANCELLED":semantic?"PARTIAL":knownNotDispatched?"FAILED":"UNKNOWN",knownNotDispatched?"failed":"unknown",semantic?"partial":"none");}catch(Exception storage){stopped.addSuppressed(storage);}
            if(stopped instanceof ModelCallAdmission.Refused capacity)stopped=localFailure(capacity.getMessage(),capacity.deadline?InferenceFailure.Kind.TIMEOUT:InferenceFailure.Kind.LOCAL_REFUSAL);
            throw controlled(stopped,authority!=null);
        }
    }
    private static boolean retryableLogical(InferenceFailure f){return f!=null&&f.httpStatus!=401&&f.httpStatus!=403&&f.httpStatus!=402&&(f.kind==InferenceFailure.Kind.RATE_LIMIT||f.kind==InferenceFailure.Kind.NETWORK||f.kind==InferenceFailure.Kind.TIMEOUT||f.kind==InferenceFailure.Kind.INCOMPLETE_STREAM||f.kind==InferenceFailure.Kind.HTTP&&java.util.Set.of(500,502,503,504).contains(f.httpStatus));}
    private static boolean uncertainCompletion(ModelConfig selected,boolean dispatched,InferenceFailure failure){
        if(!dispatched)return false;
        if(failure!=null&&(failure.kind==InferenceFailure.Kind.NETWORK||failure.kind==InferenceFailure.Kind.TIMEOUT))return true;
        return selected.usesBedrock()&&(failure==null||failure.kind==InferenceFailure.Kind.INCOMPLETE_STREAM
                ||failure.kind==InferenceFailure.Kind.PROTOCOL||failure.kind==InferenceFailure.Kind.UNKNOWN);
    }
    private static String requestDigest(JSONArray messages,JSONArray tools)throws Exception {byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(new JSONObject().put("messages",messages==null?new JSONArray():messages).put("tools",tools==null?new JSONArray():tools).toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static Exception controlled(Exception failure,boolean controlled)throws Exception {
        if(failure instanceof ModelRequestPolicy.Rejected)failure=new GatewayException(failure.getMessage(),InferenceFailure.local(InferenceFailure.Kind.LOCAL_REFUSAL,System.currentTimeMillis()));
        if(controlled&&failure instanceof GatewayException gateway)gateway.logicalControlled=true;return failure;
    }
    private static void checkRequest(RunControl control,RunControl.ModelRequest request)throws InterruptedException {
        if(request==null)control.check();else request.check();
    }
    private static AutoCloseable cancelTransport(RunControl control,StreamSession stream,Runnable cancel) {
        if(stream!=null&&stream.request!=null)return stream.request.onCancel(cancel);
        control.onCancel(cancel);return ()->control.removeCancelAction(cancel);
    }

    private static JSONObject completeApi(ModelConfig config,JSONArray messages,JSONArray tools,
                                          RunControl control,StreamSession stream,InferenceCapture capture,InferenceCapture.Target target)throws Exception {
        ProviderAdapter provider=ProviderAdapters.selected(config.provider);
        URL endpoint=endpoint(config);
        checkRequest(control,stream==null?null:stream.request);
        JSONObject request=provider.request(config,messages,tools);
        if(stream!=null){request=ApiTurnStream.request(config.provider,request,config.maxOutputTokens);endpoint=config.usesBedrock()
                ?dev.pocketengine.runtime.providers.bedrock.BedrockEndpoint.endpoint(config.bedrockRegion,config.model,true)
                :ApiTurnStream.endpoint(config.provider,endpoint);}
        try{request=applyRequestPolicy(config,messages,tools,request,target,control);}catch(ModelRequestPolicy.Rejected refused){throw new GatewayException(refused.getMessage(),InferenceFailure.local(InferenceFailure.Kind.LOCAL_REFUSAL,System.currentTimeMillis()));}
        capture.requestProjection(request);
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
        if(payload.length>MAX_REQUEST_BYTES)throw new GatewayException("模型请求序列化后超过24MiB本地传输限制；尚未发送，请缩减图片或上下文。此限制不是模型上下文窗口。");
        if(stream!=null){JSONObject assistant=completeStreamingHttp(config,provider,endpoint,payload,tools,control,stream,capture);checkCapabilities(config,messages,tools,request,target,control);return assistant;}
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false); // Never forward credentials to a redirect target.
        connection.setConnectTimeout((int)Math.min(15_000,capture.remainingMillis(config)));
        connection.setReadTimeout((int)capture.remainingMillis(config));
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        for(java.util.Map.Entry<String,String> header:provider.headers(config,endpoint,payload).entrySet())connection.setRequestProperty(header.getKey(),header.getValue());
        connection.setFixedLengthStreamingMode(payload.length);

        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean expired = new AtomicBoolean();
        CountDownLatch cancellationWake=new CountDownLatch(1);
        Runnable disconnect = cancellationWake::countDown;
        control.onCancel(disconnect);
        long deadline = System.nanoTime() + capture.remainingMillis(config) * 1_000_000L;
        Thread caller = Thread.currentThread();
        Thread cancellation = new Thread(() -> {
            try {
                while (!finished.get()) {
                    if (control.isCancelled() || caller.isInterrupted() || System.nanoTime() >= deadline) {
                        expired.set(System.nanoTime() >= deadline);
                        connection.disconnect();
                        return;
                    }
                    cancellationWake.await(75,TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ignored) { /* Request has already finished. */ }
        }, "pocket-model-cancel");
        cancellation.setDaemon(true);
        cancellation.start();
        try {
            control.check();
            capture.dispatch();control.check();ModelRequestPolicy.resolve(config);
            try (OutputStream output = connection.getOutputStream()) {
                if(config.usesBedrock()){control.check();ModelRequestPolicy.resolve(config);}
                output.write(payload);
            }
            control.check();
            int status = connection.getResponseCode();
            if(config.usesBedrock())capture.observedBedrockRequestId(headers(connection.getHeaderFields(),"x-amzn-requestid"));
            if (status < 200 || status >= 300) throw httpFailure(config,status,headers(connection.getHeaderFields(),"Retry-After"),headers(connection.getHeaderFields(),"Date"),headers(connection.getHeaderFields(),"x-amzn-errortype"),connection.getErrorStream());
            if(config.usesBedrock()&&status!=200)throw localFailure("Bedrock 未返回所选操作的完整响应状态；本次未派发工具。",InferenceFailure.Kind.PROTOCOL,status);
            String body;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    control.check();
                    if (buffer.size() + count > MAX_RESPONSE_BYTES) throw new GatewayException("模型回复过大，请缩短任务或更换模型。");
                    buffer.write(chunk, 0, count);
                }
                try{body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(buffer.toByteArray())).toString();}
                catch(java.nio.charset.CharacterCodingException invalidUtf8){throw localFailure("模型回复包含无效UTF-8，未派发工具。",InferenceFailure.Kind.PROTOCOL,status);}
            }
            control.check();
            if (expired.get()) throw localFailure("模型请求超时，请检查网络或提高请求超时。",InferenceFailure.Kind.TIMEOUT);
            try {
                // HTTP success is not a provider terminal. Every buffered consumer (including
                // SUMMARY and team inference) shares SSE's terminal/tool boundary on this body.
                JSONObject assistant=ApiTurnStream.completeJson(config.provider,provider,body,tools,delta->{},capture.usage);
                checkCapabilities(config,messages,tools,request,target,control);
                return assistant;
            } catch (GatewayException known) {
                if(known.failure!=null)throw known;
                throw new GatewayException(known.getMessage(),InferenceFailure.local(InferenceFailure.Kind.PROTOCOL,status,System.currentTimeMillis()));
            } catch (InterruptedException stopped) {
                throw stopped;
            } catch (Exception malformed) {
                throw localFailure("接口返回的 JSON 或消息结构无法读取，请检查所选模型协议。",InferenceFailure.Kind.PROTOCOL,status);
            }
        } catch (IOException error) {
            control.check();
            if (expired.get() || error instanceof SocketTimeoutException) {
                throw localFailure("模型请求超时，请检查网络或提高请求超时。",InferenceFailure.Kind.TIMEOUT);
            }
            throw localFailure("无法连接模型接口，请检查地址、网络和 HTTPS 证书。",error instanceof javax.net.ssl.SSLException?InferenceFailure.Kind.PROTOCOL:InferenceFailure.Kind.NETWORK);
        } catch (RuntimeException transportFailure) {
            // Some HttpURLConnection implementations throw a runtime failure when the native
            // cancellation watchdog disconnects a concurrent header/body read. Keep cancellation
            // authoritative without retrying or hiding unrelated bugs.
            control.check();
            throw transportFailure;
        } finally {
            finished.set(true);
            control.removeCancelAction(disconnect);
            cancellation.interrupt();
            connection.disconnect();
        }
    }

    private static JSONObject applyRequestPolicy(ModelConfig config,JSONArray messages,JSONArray tools,JSONObject request,
            InferenceCapture.Target target,RunControl original)throws Exception {
        return target.context.purpose==InferenceReceipt.Purpose.MODEL_CAPABILITY_PROBE
                ?ModelRequestPolicy.applyCapabilityProbe(config,messages,tools,request,target,original)
                :ModelRequestPolicy.apply(config,messages,tools,request);
    }

    private static void checkCapabilities(ModelConfig config,JSONArray messages,JSONArray tools,JSONObject request,InferenceCapture.Target target,RunControl control)throws Exception {
        try{applyRequestPolicy(config,messages,tools,request,target,control);}catch(ModelRequestPolicy.Rejected revoked){throw new GatewayException(revoked.getMessage());}
    }

    private static JSONObject completeStreamingHttp(ModelConfig config,ProviderAdapter provider,URL endpoint,
                                                     byte[] payload,JSONArray tools,RunControl control,StreamSession stream,InferenceCapture capture)throws Exception {
        AtomicBoolean sent=new AtomicBoolean();
        OkHttpClient client=STREAM_HTTP.newBuilder().callTimeout(capture.remainingMillis(config),TimeUnit.MILLISECONDS)
                .connectTimeout(Math.min(15,config.requestTimeoutSeconds),TimeUnit.SECONDS)
                .readTimeout(config.requestTimeoutSeconds,TimeUnit.SECONDS).writeTimeout(config.requestTimeoutSeconds,TimeUnit.SECONDS)
                // OkHttp can follow some retryable HTTP statuses even with connection retry off.
                // A second network exchange may connect but can never write this inference again.
                .addNetworkInterceptor(chain->{
                    if(!sent.compareAndSet(false,true))throw new IOException("Inference resubmission refused");
                    if(config.usesBedrock())try{stream.check();ModelRequestPolicy.resolve(config);}
                    catch(Exception stale){throw new IOException("Selected Bedrock session is no longer current");}
                    return chain.proceed(chain.request());
                }).build();
        Request.Builder request=new Request.Builder().url(endpoint)
                .post(RequestBody.create(payload,MediaType.get(config.usesBedrock()?"application/json":"application/json; charset=utf-8")))
                .header("Accept","text/event-stream, application/json");
        for(java.util.Map.Entry<String,String> header:provider.headers(config,endpoint,payload).entrySet())request.header(header.getKey(),header.getValue());
        Call call=client.newCall(request.build());AutoCloseable cancellation=cancelTransport(control,stream,call::cancel);
        long deadline=System.nanoTime()+capture.remainingMillis(config)*1_000_000L;
        try{
            stream.check();
            capture.dispatch();stream.check();ModelRequestPolicy.resolve(config);
            try(Response response=call.execute()){
                stream.check();
                if(config.usesBedrock())capture.observedBedrockRequestId(response.headers().values("x-amzn-requestid"));
                if(!response.isSuccessful())throw httpFailure(config,response.code(),response.headers().values("Retry-After"),response.headers().values("Date"),response.headers().values("x-amzn-errortype"),response.body()==null?null:response.body().byteStream());
                if(config.usesBedrock()&&response.code()!=200)throw localFailure("Bedrock 未返回所选流操作的完整响应状态；本次未派发工具。",InferenceFailure.Kind.PROTOCOL,response.code());
                if(response.body()==null)throw new GatewayException("模型没有返回正文，未派发工具。");
                JSONObject assistant;
                try(InputStream input=response.body().byteStream()){
                    String contentType=response.header("Content-Type","").split(";",2)[0].trim();
                    if(config.usesBedrock()){
                        if(!contentType.equalsIgnoreCase("application/vnd.amazon.eventstream"))throw localFailure("Bedrock 未返回所选流操作的二进制事件流；本次未派发工具。",InferenceFailure.Kind.PROTOCOL,response.code());
                        assistant=ApiTurnStream.readBedrock(input,provider,tools,control,value->{capture.observedVisibleText(value);stream.text(value);},capture.usage);
                    }else if(contentType.equalsIgnoreCase("text/event-stream"))
                        assistant=ApiTurnStream.read(input,config.provider,provider,tools,control,value->{capture.observedVisibleText(value);stream.text(value);},capture.usage);
                    else{
                        ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;
                        while((count=input.read(chunk))!=-1){stream.check();if(buffer.size()+count>MAX_RESPONSE_BYTES)throw new GatewayException("模型回复过大，未派发工具。");buffer.write(chunk,0,count);}
                        String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(buffer.toByteArray())).toString();
                        assistant=ApiTurnStream.completeJson(config.provider,provider,body,tools,stream::text,capture.usage);
                    }
                }catch(BedrockEventStream.Failure invalid){
                    if(invalid.kind==BedrockEventStream.Kind.SERVICE)
                        throw new GatewayException("Bedrock 在原事件流中报告错误；请核对这次调用记录，没有自动重发。",InferenceFailure.bedrockStream(invalid.code,response.code(),System.currentTimeMillis()));
                    throw localFailure(invalid.kind==BedrockEventStream.Kind.INCOMPLETE?"Bedrock 事件流未完整结束；请在原任务核对，没有自动重发或派发工具。":"Bedrock 事件流的完整性或格式无法核对；本次未派发工具。",invalid.kind==BedrockEventStream.Kind.INCOMPLETE?InferenceFailure.Kind.INCOMPLETE_STREAM:InferenceFailure.Kind.PROTOCOL,response.code());
                }catch(GatewayException known){
                    if(known.failure!=null){
                        if(known.failure.kind==InferenceFailure.Kind.INCOMPLETE_STREAM&&known.failure.httpStatus==0)throw new GatewayException(known.getMessage(),InferenceFailure.local(InferenceFailure.Kind.INCOMPLETE_STREAM,response.code(),known.failure.observedAt).withPartialText(known.failure.partialTextReceived));
                        throw known;
                    }
                    throw new GatewayException(known.getMessage(),InferenceFailure.local(InferenceFailure.Kind.PROTOCOL,response.code(),System.currentTimeMillis()));
                }
                catch(InterruptedException stopped){throw stopped;}
                catch(IOException network){throw network;}
                catch(Exception malformed){throw localFailure("模型流的事件、终态、工具参数或显示回调无法核对；未派发工具，没有自动重试。",InferenceFailure.Kind.PROTOCOL,response.code());}
                stream.check();
                if(System.nanoTime()>=deadline)throw localFailure("模型请求超时；本次未派发工具。是否有界恢复由原任务范围决定。",InferenceFailure.Kind.TIMEOUT);
                return assistant;
            }
        }catch(IOException network){
            stream.check();
            if(network instanceof java.io.InterruptedIOException||System.nanoTime()>=deadline)
                throw localFailure("模型请求超时；本次未派发工具。是否有界恢复由原任务范围决定。",InferenceFailure.Kind.TIMEOUT);
            throw localFailure("模型流连接中断；本次未派发工具。",network instanceof javax.net.ssl.SSLException?InferenceFailure.Kind.PROTOCOL:InferenceFailure.Kind.NETWORK);
        }finally{cancellation.close();}
    }

    /** One invocation's ordered presentation channel; no reusable global sink or task authority. */
    private static final class StreamSession {
        final ModelConfig config;
        final RunControl control;
        final RunControl.ModelRequest request;
        final StreamSink sink;
        final String requestId;
        final long pauseRevision;
        final SemanticCommit commit;
        final StringBuilder pending=new StringBuilder();
        long sequence;
        int visibleChars;
        boolean presentationClosed,textReceived;
        StreamSession(ModelConfig config,RunControl control,StreamSink sink,String requestId,SemanticCommit commit,RunControl.ModelRequest request)throws GatewayException {
            this.commit=commit;this.request=request;
            this.config=config;this.control=control;this.sink=sink;this.requestId=requestId;pauseRevision=control.pauseRevision();
            if(config.apiKey.length()>16384)throw new GatewayException("API凭据过长，未发送流式请求，请检查模型设置。");
        }
        void check()throws InterruptedException {checkRequest(control,request);}
        void text(String value)throws Exception {
            if(!value.isEmpty())textReceived=true;
            check();visibleChars+=value.length();if(visibleChars>MAX_RESPONSE_BYTES)throw new GatewayException("模型可见流文本超过本地大小限制，已停止；没有自动重试。");
            pending.append(value);drain(false);
        }
        private void drain(boolean end)throws Exception {
            String held=pending.toString();
            // Retain the original bytes represented by an unfinished credential prefix.
            // Masking a shorter credential first could destroy a longer session-token prefix.
            int keep=end?0:config.sensitiveSuffixLength(held);
            int take=held.length()-keep;
            if(take>0&&Character.isHighSurrogate(held.charAt(take-1)))take--;
            String ready=config.redactSensitive(held.substring(0,take));checkVisibleUnicode(ready);
            pending.setLength(0);pending.append(held.substring(take));
            int offset=0;
            while(offset<ready.length()){
                int next=Math.min(ready.length(),offset+2048);
                if(next<ready.length()&&Character.isHighSurrogate(ready.charAt(next-1)))next--;
                emit(StreamKind.TEXT_DELTA,ready.substring(offset,next),"text_delta",true);offset=next;
            }
            if(end&&pending.length()>0)throw new GatewayException("模型文本包含不完整Unicode，未派发工具。");
        }
        void finish()throws Exception {drain(true);emit(StreamKind.COMPLETED,"","turn_completed",true);}
        void failed(StreamKind kind,String code){pending.setLength(0);try{emit(kind,"",code,false);}catch(Exception ignored){/* Original failure remains authoritative. */}}
        void emit(StreamKind kind,String text,String code,boolean live)throws Exception {
            if(live)check();
            if(sink==null||presentationClosed)return;
            // The only event allowed past a presentation fence clears this exact request's draft.
            // It carries no text, completed result, grant or tool action.
            if(live&&(control.isPaused()||control.pauseRevision()!=pauseRevision)){
                presentationClosed=true;
                sink.event(new StreamEvent(StreamKind.CANCELLED,requestId,++sequence,config.provider,pauseRevision,"","presentation_invalidated"));
                return;
            }
            if(kind==StreamKind.COMPLETED||kind==StreamKind.FAILED||kind==StreamKind.CANCELLED)presentationClosed=true;
            if(kind==StreamKind.TEXT_DELTA||kind==StreamKind.COMPLETED)commit.beforeFlush(requestId,sequence+1);
            sink.event(new StreamEvent(kind,requestId,++sequence,config.provider,pauseRevision,text,code));
        }
        private static void checkVisibleUnicode(String value)throws GatewayException {
            for(int i=0;i<value.length();i++){
                char c=value.charAt(i);
                if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw new GatewayException("模型文本包含无效Unicode，未派发工具。");}
                else if(Character.isLowSurrogate(c))throw new GatewayException("模型文本包含无效Unicode，未派发工具。");
            }
        }
    }

    private static JSONObject completeAccount(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,StreamSession stream,InferenceCapture capture)throws Exception {
        RunControl.ModelRequest requestScope=stream==null?null:stream.request;
        checkRequest(control,requestScope);AccountInferencePort port=accountInference;
        if(port==null)throw new GatewayException("账号推理宿主已关闭，请重新打开应用。");
        try(GatewayCall operation=new GatewayCall(capture.remainingMillis(config))){
            AutoCloseable cancellation=cancelTransport(control,stream,operation::cancel);
            // The account version callback keeps its stable checked GatewayFailure protocol.
            // Only this invocation's exact native source refusal may cross back to the Runner.
            final java.util.concurrent.atomic.AtomicReference<dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable> sourceRefusal=new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<ModelRequestPolicy.Rejected> budgetRefusal=new java.util.concurrent.atomic.AtomicReference<>();
            try{
                AccountBinding binding=binding(control,config.accountModel,port);
                ModelConfig.AccountModelRef expected=binding.current();
                ResponsesRequest request=AccountResponsesAdapter.request(config.withAccountModel(expected),messages,tools,binding.versions(),port.imageInputSupported(expected));
                request.admit(accountModelFacts(expected));
                capture.accountRequestOptions(request.traceOptions());
                final JSONObject[] completed={null};final ModelConfig.AccountModelRef[] admitted={null};checkRequest(control,requestScope);
                port.streamWithVersion(expected,request,operation,actualVersion->{
                    operation.check();
                    if(admitted[0]!=null||actualVersion<expected.accountVersion)throw new GatewayFailure("STALE_ACCOUNT_VERSION");
                    admitted[0]=binding.advance(expected,actualVersion);
                    try{capture.accountVersion(actualVersion);capture.dispatch();}
                    catch(ModelRequestPolicy.Rejected budget){budgetRefusal.compareAndSet(null,budget);throw new GatewayFailure("INFERENCE_RECEIPT_STORE_UNAVAILABLE");}
                    catch(dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable source){sourceRefusal.compareAndSet(null,source);throw new GatewayFailure("INFERENCE_RECEIPT_STORE_UNAVAILABLE");}
                    catch(Exception storage){throw new GatewayFailure("INFERENCE_RECEIPT_STORE_UNAVAILABLE");}
                },new ChatGptProvider.EventSink(){
                  @Override public void usage(JSONObject counters){capture.usage.observe(counters,ProviderUsage.Stage.FINAL);ApiTurnStream.terminalUsage("openai_responses",counters,capture.usage);}
                  @Override public void event(JSONObject event)throws Exception{
                    checkRequest(control,requestScope);operation.check();
                    if(port!=accountInference||admitted[0]==null||!admitted[0].matches(binding.current()))throw new GatewayFailure("STALE_ACCOUNT_VERSION");
                    if(completed[0]!=null)throw new GatewayFailure("INVALID_PROVIDER_STREAM");
                    if("text_delta".equals(event.optString("type"))){
                        String value=event.getString("delta");capture.observedVisibleText(value);if(stream!=null)stream.text(value);
                    }
                    if("completed".equals(event.optString("type"))){
                        capture.usage.terminal("completed");
                        capture.usage.observe(event,ProviderUsage.Stage.FINAL);
                        try{completed[0]=new JSONObject(event.toString());}catch(Exception invalid){throw new GatewayFailure("INVALID_PROVIDER_STREAM");}
                    }
                  }
                });
                checkRequest(control,requestScope);operation.check();
                if(port!=accountInference)throw new GatewayException("账号推理宿主已变化，已丢弃旧回复；请重新确认连接。");
                if(admitted[0]==null||!admitted[0].matches(binding.current()))throw new GatewayFailure("STALE_ACCOUNT_VERSION");
                return AccountResponsesAdapter.response(config.withAccountModel(admitted[0]),completed[0],tools);
            }catch(GatewayFailure failure){
                checkRequest(control,requestScope);
                dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable source=sourceRefusal.get();if(source!=null)throw source;
                if(budgetRefusal.get()!=null)throw budgetRefusal.get();
                if(stream!=null&&"CANCELLED".equals(failure.code))throw new java.util.concurrent.CancellationException(accountFailure(failure.code));
                throw new GatewayException(accountFailure(failure.code),InferenceFailure.account(failure.code,failure.httpStatus,failure.retryAt,System.currentTimeMillis()));
            }finally{cancellation.close();}
        }
    }
    private static AccountBinding binding(RunControl control,ModelConfig.AccountModelRef reference,AccountInferencePort port)throws GatewayException {
        synchronized(accountBindings){
            if(port!=accountInference)throw new GatewayException("账号推理宿主已变化，请重新确认连接。");
            AccountBinding binding=accountBindings.get(control);
            if(binding==null){binding=new AccountBinding(reference);accountBindings.put(control,binding);}
            if(!binding.accepts(reference))throw new GatewayException("同一次任务的账号模型选择已变化，不能自动换号或重放，请先停止并核对原任务。");
            return binding;
        }
    }
    private static final class AccountBinding {
        final ModelConfig.AccountModelRef original;private ModelConfig.AccountModelRef current;
        private final java.util.Set<Long> versions=new java.util.HashSet<>();
        AccountBinding(ModelConfig.AccountModelRef ref){original=ref;current=ref;versions.add(ref.accountVersion);}
        synchronized boolean accepts(ModelConfig.AccountModelRef ref){return original.matches(ref)||current.matches(ref);}
        synchronized ModelConfig.AccountModelRef current(){return current;}
        synchronized java.util.Set<Long> versions(){return new java.util.HashSet<>(versions);}
        synchronized boolean selectionContinues(ModelConfig.AccountModelRef captured,ModelConfig.AccountModelRef selected){return original.sameModel(captured)&&versions.contains(captured.accountVersion)&&current.matches(selected);}
        synchronized ModelConfig.AccountModelRef advance(ModelConfig.AccountModelRef expected,long actual)throws GatewayFailure {
            if(!current.sameModel(expected)||actual<expected.accountVersion||
                    !(current.accountVersion==expected.accountVersion||current.accountVersion==actual))throw new GatewayFailure("STALE_ACCOUNT_VERSION");
            current=expected.withVersion(actual);versions.add(actual);return current;
        }
    }
    private static String accountFailure(String code){
        String recovery=dev.pocketengine.features.proxy.accounts.ChatGptRecovery.message(code);if(!recovery.isEmpty())return recovery;
        switch(code){
            case "ACCOUNT_MODEL_OPTION_UNAVAILABLE":return "当前账号模型目录不再支持所选推理选项，请刷新目录或选服务默认；未发送本次请求。";
            case "ACCOUNT_CONTEXT_BUDGET_EXCEEDED":return "请求超过当前账号模型已知上下文的保守预算，未删历史、未发送；请核对本地输出预留或整理上下文。";
            case "ACCOUNT_IMAGE_BUDGET_REQUIRED":return "该账号模型上下文已知，请先在账号模型选项配置每张图片的本地token预留；未发送图片。";
            case "ACCOUNT_TEXT_CAPABILITY_UNSUPPORTED":return "当前账号模型目录明确不接收文字输入，请选择已声明文字输入的模型；未发送、未自动换模型。";
            case "ACCOUNT_TOOL_CAPABILITY_UNSUPPORTED":return "当前账号模型目录明确不支持工具调用，本次未发送工具或丢弃历史。";
            case "INFERENCE_RECEIPT_STORE_UNAVAILABLE":return "无法保存本次模型调用的原任务用量身份；本次未发送推理请求，请检查存储后再继续。";
            case "CANCELLED":return "账号模型请求已停止；账号可能已停用或退出，旧回复不会继续执行。";
            case "DEADLINE_EXCEEDED":return "账号模型请求超时；没有自动重试，请核对任务后再决定是否继续。";
            case "STALE_ACCOUNT_VERSION":case "STALE_MODEL_CATALOG":return "账号授权版本已变化，请刷新账号模型目录并重新选择；没有切换账号或重放请求。";
            case "ACCOUNT_SELECTION_STORE_UNAVAILABLE":return "账号续期已完成，但无法保存新的模型选择；本次未发送推理请求，请检查存储后重新选择。";
            case "ACCOUNT_REFRESH_REQUIRED":return "账号令牌需要续期，请在账号页刷新后重新选择模型；没有自动发出或重试推理请求。";
            case "ACCOUNT_DISABLED":case "ACCOUNT_NOT_FOUND":case "ACCOUNT_SELECTION_REQUIRED":return "所选账号已停用、移除或未选择，请到账号页检查。";
            case "REAUTHORIZATION_REQUIRED":case "PLAN_USAGE_PERMISSION_REQUIRED":case "PROVIDER_PERMISSION_DENIED":return "所选账号需要重新授权或未允许使用套餐推理，请到账号页检查。";
            case "ACCOUNT_COOLDOWN":case "RATE_LIMITED":case "QUOTA_EXCEEDED":case "PROVIDER_RATE_LIMITED":return "账号请求正在退避，请稍后手动再试；这不是套餐余额或重置时间。";
            case "GATEWAY_BUSY":case "REFRESH_QUEUE_FULL":return "账号连接正在处理其他请求，请稍后再试；本次没有自动重试。";
            case "GATEWAY_CLOSED":return "账号连接已关闭，请重新打开应用。";
            case "UNSUPPORTED_OR_OVERSIZED_INPUT":return "账号模型上下文或工具数量超过当前支持范围；请缩短任务或使用其他明确选择的模型。";
            case "INVALID_FUNCTION_RESULT_LINK":return "历史工具结果与模型调用不匹配，已停止请求；请先核对原任务，不能自动重放。";
            case "UNSUPPORTED_PROVIDER_REQUEST":case "UNSUPPORTED_INPUT_TYPE":case "UNSUPPORTED_TOOL_TYPE":case "UNSUPPORTED_PROTOCOL_FIELD":case "UNSUPPORTED_FUNCTION_MODE":case "UNSUPPORTED_PROVIDER_OUTPUT":case "UNSUPPORTED_REASONING_CONTENT":return "账号模型使用了尚未接入的输入、字段或工具，本次未派发动作。";
            case "PROVIDER_UNAVAILABLE":return "账号模型服务暂时不可用；原任务的有限恢复与每次真实调用以任务记录为准。";
            default:return "账号模型没有返回可验证的完整回复，已停止本次执行；请检查账号与网络，不会自动重放或换号。";
        }
    }

    static URL endpoint(ModelConfig config)throws GatewayException {
        try{URL url=ProviderAdapters.selected(config.provider).endpoint(config);config.requireTransportConsent(url);return url;}catch(GatewayException error){throw error;}catch(IllegalArgumentException consent){throw new GatewayException(consent.getMessage());}catch(Exception invalid){throw new GatewayException("模型地址或协议配置无效。");}
    }
    static URL endpoint(String baseUrl)throws GatewayException{return endpoint(new ModelConfig(baseUrl,"","validation",false,1,10));}

    static String safeMessage(Exception error, ModelConfig config) {
        String message = error instanceof GatewayException ? error.getMessage() : "执行遇到错误，请检查模型设置和手机权限后重试。";
        return config==null?message:config.redactSensitive(message);
    }

    public static String redact(String value, String key) {
        if (value == null) return "";
        return key == null || key.isEmpty() ? value : value.replace(key, "[密钥已隐藏]");
    }

    private static void putFailure(JSONObject object, String message, String code) {
        try { object.put("ok", false).put("message", message).put("code", code); } catch (Exception ignored) { }
    }

    private static GatewayException httpFailure(ModelConfig captured,int status,java.util.List<String> retryAfter,java.util.List<String> date,java.util.List<String> errorTypes,InputStream errorBody)throws Exception {
        String bounded=boundedError(errorBody);
        if(!captured.usesBedrock()&&(status==400||status==413)&&contextLimitCode(bounded))return localFailure("模型明确报告上下文超限；尚未派发工具。",InferenceFailure.Kind.CONTEXT_LIMIT,status);
        var reason=dev.pocketengine.runtime.models.ModelConnectionFailure.reason(status,bounded);
        InferenceFailure observation=captured.usesBedrock()
                ?InferenceFailure.bedrockHttp(status,withoutCapturedSecret(retryAfter,captured),withoutCapturedSecret(date,captured),withoutCapturedSecret(errorTypes,captured),System.currentTimeMillis(),bounded)
                :InferenceFailure.classifiedHttp(status,withoutCapturedSecret(retryAfter,captured),withoutCapturedSecret(date,captured),System.currentTimeMillis(),bounded);
        String explanation=dev.pocketengine.runtime.models.ModelConnectionFailure.message(reason);
        if(captured.usesBedrock()){
            if(status==401||status==403||java.util.Set.of("BEDROCK_SIGNATURE_INVALID","BEDROCK_NOT_AUTHORIZED","BEDROCK_INVALID_IDENTITY").contains(observation.nativeCode))explanation="Bedrock 拒绝了原临时身份或签名，请核对该连接的完整会话、到期时间、区域和权限；没有换连接。";
            if("BEDROCK_REQUEST_EXPIRED".equals(observation.nativeCode))explanation="Bedrock 报告请求时间戳已失效，请核对设备时钟和原会话；没有重发请求。";
        }
        return new GatewayException(explanation+"（HTTP "+status+"）",observation,reason.name());
    }
    /** Only exact protocol error codes authorize one compaction; never match arbitrary provider prose. */
    private static boolean contextLimitCode(InputStream input){return contextLimitCode(boundedError(input));}
    private static boolean contextLimitCode(String text){try{JSONObject error=new JSONObject(text).optJSONObject("error");return error!=null&&java.util.Set.of("context_length_exceeded","context_window_exceeded","prompt_too_long").contains(error.optString("code"));}catch(Exception unavailable){return false;}}
    private static String boundedError(InputStream input){
        if(input==null)return "";
        try(InputStream body=input){
            // readNBytes is Android API 33; retain minSdk 30 using the same hard byte bound.
            byte[] bytes=new byte[16_385];int length=0;
            while(length<bytes.length){int read=body.read(bytes,length,bytes.length-length);if(read<0)break;if(read==0)return "";length+=read;}
            if(length>16_384)return "";
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes,0,length)).toString();
            return text;
        }catch(Exception unavailable){return "";}
    }
    private static java.util.List<String> withoutCapturedSecret(java.util.List<String> values,ModelConfig captured) {
        if(values!=null)for(String value:values)if(captured.containsSensitive(value))return java.util.List.of("invalid_header");
        return values;
    }
    private static GatewayException localFailure(String message,InferenceFailure.Kind kind)throws Exception { return localFailure(message,kind,0); }
    private static GatewayException localFailure(String message,InferenceFailure.Kind kind,int status)throws Exception {
        return new GatewayException(message,InferenceFailure.local(kind,status,System.currentTimeMillis()));
    }
    private static java.util.List<String> headers(java.util.Map<String,java.util.List<String>> fields,String wanted) {
        java.util.List<String> result=new java.util.ArrayList<>();
        if(fields!=null)for(var field:fields.entrySet())if(field.getKey()!=null&&wanted.equalsIgnoreCase(field.getKey())&&field.getValue()!=null)
            for(String value:field.getValue()){result.add(value);if(result.size()==2)return result;}
        return result;
    }
    private static void observeFailure(InferenceCapture capture,Exception original,boolean partialText,boolean cancelled) {
        try {
            InferenceFailure failure=original instanceof GatewayException nativeError?nativeError.failure:capture.failureObservation();
            if(failure==null)failure=InferenceFailure.local(cancelled?InferenceFailure.Kind.CANCELLED:InferenceFailure.Kind.UNKNOWN,System.currentTimeMillis());
            capture.failure(failure.withPartialText(partialText));
        } catch(Exception malformed){original.addSuppressed(malformed);}
    }
    public static final class GatewayException extends Exception {
        /** Content-free native failure classification, never provider text or retry authority. */
        public InferenceFailure observation(){return failure;}
        public static GatewayException incompleteStream()throws Exception{return new GatewayException("模型流在终态前中断；本次没有派发工具。",InferenceFailure.local(InferenceFailure.Kind.INCOMPLETE_STREAM,System.currentTimeMillis()));}
        private final InferenceFailure failure;
        private final String diagnosticReason;
        public String diagnosticReason(){return diagnosticReason;}
        private boolean logicalControlled;
        public boolean logicalControlled(){return logicalControlled;}
        public GatewayException(String message) { this(message,null); }
        private GatewayException(String message,InferenceFailure failure){this(message,failure,"UNKNOWN");}
        private GatewayException(String message,InferenceFailure failure,String diagnosticReason){super(message);this.failure=failure;this.diagnosticReason=diagnosticReason;}
    }
}
