package dev.pocketengine.features.capabilities;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.ToolOutputCapture;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.ssh.SshProfile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Additive tools for one native task attempt. No task creation, grant creation, planner or retry. */
public final class ModelCapabilityAdapters implements CapabilityPort {
    public interface Authority {
        void check(String tool,RunControl control)throws Exception;
        /** Must consume exact TaskStore approval; false is a definite non-dispatch receipt. */
        JSONObject approve(CapabilityDescriptor tool,JSONObject normalizedArgs,JSONObject capability)throws Exception;
        /** Verify the operation already prepared by AgentRunner, including exact original model arguments. */
        void requirePrepared(String operationId,String tool,JSONObject originalArgs)throws Exception;
    }
    public interface ResourceCheck {void check()throws Exception;}
    public interface DocumentRead {JSONObject readDocument(JSONObject input,RunControl liveControl)throws Exception;}
    public interface LiveBrowserCall {JSONObject invokeForHost(String command,JSONObject input,RunControl liveControl,ResourceCheck admission)throws Exception;}
    /**
     * Native SSH entry must retain this same control before registering/connecting and through
     * every transport effect. A lambda to SshModule.handle is NOT an implementation of this contract.
     * Baseline SshModule lacks this API, so without a host implementation no SSH tool is mounted.
     */
    public interface LiveSshCall {JSONObject invokeForHost(String command,JSONObject input,RunControl liveControl)throws Exception;}
    public static final class Binding {
        public final String taskId,runId,projectId;
        public final int epoch;
        public final boolean allowWrites;
        private final RunControl control;
        public Binding(String taskId,String runId,int epoch,RunControl control,boolean allowWrites,String projectId){
            CapabilityDescriptor.uuid(taskId);CapabilityDescriptor.uuid(runId);if(epoch<1)throw new IllegalArgumentException("Invalid host epoch");
            this.taskId=taskId;this.runId=runId;this.epoch=epoch;this.control=Objects.requireNonNull(control);this.allowWrites=allowWrites;
            this.projectId=projectId==null?"":projectId;if(!this.projectId.isEmpty())CapabilityDescriptor.uuid(this.projectId);
            if(!control.hasHostRun()||!control.matchesHostRun(taskId,runId,epoch))throw new SecurityException("必须提供原任务的live RunControl");
        }
        void check(RunControl supplied)throws Exception {
            if(supplied!=control||!control.matchesHostRun(taskId,runId,epoch))throw new SecurityException("不能替换宿主执行控制");
            control.check();if(control.isPaused())throw new SecurityException("任务已暂停，不能派发新能力调用");
        }
    }
    /** The owner has already acquired this exact lease through the existing native host. */
    public static final class BrowserBinding {
        final EngineModule module;final JSONObject preset;final long leaseEpoch,expiresAt;final ResourceCheck resource;final LiveBrowserCall liveCall;
        public BrowserBinding(Binding binding,EngineModule browser,JSONObject acquiredLease,ResourceCheck liveResource)throws Exception {
            this(binding,browser,acquiredLease,liveResource,new AndroidBrowserCall());
        }
        public BrowserBinding(Binding binding,EngineModule browser,JSONObject acquiredLease,ResourceCheck liveResource,LiveBrowserCall liveCall)throws Exception {
            module=requireModule(browser,"browser");resource=Objects.requireNonNull(liveResource);JSONObject lease=new JSONObject(acquiredLease.toString());
            this.liveCall=Objects.requireNonNull(liveCall);
            if(!lease.optBoolean("active")||!binding.taskId.equals(lease.optString("taskId"))||!binding.runId.equals(lease.optString("runId")))throw new SecurityException("浏览器租约不属于本任务与执行");
            String profile=lease.getString("profileId"),session=lease.getString("browserSessionId"),isolation=lease.getString("profileIsolation");long revision=lease.getLong("profileRevision");
            if(!profile.matches("[A-Za-z0-9_-]{1,80}")||session.isEmpty()||session.length()>80||revision<1||!Set.of("androidx-multi-profile","default-webview-only").contains(isolation))throw new SecurityException("浏览器需要完整原生Profile与会话身份");
            if(!session.equals("legacy-single-view"))CapabilityDescriptor.uuid(session);else if(!profile.equals("owner-default")||!isolation.equals("default-webview-only"))throw new SecurityException("命名Profile不能退到默认会话");
            String id=lease.getString("leaseId");CapabilityDescriptor.uuid(id);leaseEpoch=lease.getLong("epoch");expiresAt=lease.getLong("expiresAt");
            preset=new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId).put("leaseId",id).put("profileId",profile).put("profileRevision",revision).put("browserSessionId",session).put("profileIsolation",isolation);check();
        }
        void check()throws Exception {
            resource.check();JSONObject lease=module.snapshot().getJSONObject("lease");
            if(!lease.optBoolean("active")||lease.optLong("epoch")!=leaseEpoch||lease.optLong("expiresAt")!=expiresAt||expiresAt<=System.currentTimeMillis())throw new SecurityException("浏览器租约已到期或失效");
            for(String key:new String[]{"taskId","runId","leaseId","profileId","profileRevision","browserSessionId","profileIsolation"})if(!String.valueOf(preset.get(key)).equals(String.valueOf(lease.opt(key))))throw new SecurityException("浏览器租约或Profile会话已替换");
        }
        JSONObject capability()throws Exception {return new JSONObject().put("profileId",preset.getString("profileId")).put("profileRevision",preset.getLong("profileRevision")).put("browserSessionId",preset.getString("browserSessionId")).put("profileIsolation",preset.getString("profileIsolation")).put("leaseEpoch",leaseEpoch).put("leaseDigest",SshProfile.hash(preset.toString().getBytes(StandardCharsets.UTF_8)));}
    }
    /** No profile selection, credential or host-key approval can come from the model. */
    public static final class SshBinding {
        final EngineModule module;final String profileId,bindingHash,hostFingerprint,username;final ResourceCheck resource;final LiveSshCall liveCall;
        public SshBinding(EngineModule ssh,JSONObject nativeProfile,ResourceCheck liveResource,LiveSshCall liveCall)throws Exception {
            module=requireModule(ssh,"ssh");resource=Objects.requireNonNull(liveResource);
            this.liveCall=Objects.requireNonNull(liveCall,"SSH requires a native entry retaining the host control; raw handle is unavailable");
            profileId=nativeProfile.getString("id");bindingHash=nativeProfile.getString("bindingHash");hostFingerprint=nativeProfile.getString("hostFingerprint");username=nativeProfile.getString("username");
            if(!profileId.matches("[A-Za-z0-9_-]{1,80}")||!bindingHash.matches("[a-f0-9]{64}")||hostFingerprint.isEmpty()||!nativeProfile.optBoolean("hostKeyTrusted")||!nativeProfile.optBoolean("credentialsConfigured"))throw new SecurityException("SSH连接尚未由主人配置并核对指纹");check();
        }
        void check()throws Exception {
            resource.check();JSONArray profiles=module.snapshot().getJSONArray("profiles");for(int i=0;i<profiles.length();i++){
                JSONObject current=profiles.getJSONObject(i);if(!profileId.equals(current.optString("id")))continue;
                if(bindingHash.equals(current.optString("bindingHash"))&&hostFingerprint.equals(current.optString("hostFingerprint"))&&username.equals(current.optString("username"))&&current.optBoolean("hostKeyTrusted")&&current.optBoolean("credentialsConfigured"))return;
                throw new SecurityException("SSH连接身份或信任已改变");
            }throw new SecurityException("SSH连接已移除");
        }
        JSONObject capability()throws Exception {return new JSONObject().put("profileId",profileId).put("bindingHash",bindingHash).put("accountResource",SshProfile.hash((hostFingerprint+"\n"+username).getBytes(StandardCharsets.UTF_8)));}
    }
    private final Binding binding;private final Authority authority;private final CapabilityPort existing;
    private final BrowserBinding browser;private final SshBinding ssh;private final EngineModule attachments;
    private final DocumentRead documents;
    private final ToolOutputCapture.Factory outputCapture;
    private final Map<String,CapabilityDescriptor> routes=new LinkedHashMap<>();
    // Process-local re-entry fencing only. TaskStore is the persistent operation authority.
    private final Set<String> entered=ConcurrentHashMap.newKeySet();
    private volatile boolean reconciliationRequired;
    private volatile boolean ownerReviewRequired;

    public ModelCapabilityAdapters(Binding binding,Authority authority,CapabilityPort existing,BrowserBinding browser,
            SshBinding ssh,EngineModule attachments,DocumentRead documents)throws Exception {this(binding,authority,existing,browser,ssh,attachments,documents,null);}
    public ModelCapabilityAdapters(Binding binding,Authority authority,CapabilityPort existing,BrowserBinding browser,
            SshBinding ssh,EngineModule attachments,DocumentRead documents,ToolOutputCapture.Factory capture)throws Exception {
        outputCapture=capture;this.binding=Objects.requireNonNull(binding);this.authority=Objects.requireNonNull(authority);this.existing=existing;
        this.browser=browser;this.ssh=ssh;this.attachments=attachments==null?null:requireModule(attachments,"attachments");this.documents=documents;
        if(browser!=null)for(String command:new String[]{"observe","navigate","click","type","scroll","back","forward","reload"})add(CapabilityDescriptor.browser(command));
        if(ssh!=null)for(String command:new String[]{"listFiles","readFile","exec","writeFile"})add(CapabilityDescriptor.ssh(command));
        if(attachments!=null)add(CapabilityDescriptor.createText());if(documents!=null)add(CapabilityDescriptor.document(attachments instanceof dev.pocketengine.features.attachments.AttachmentsModule?((dev.pocketengine.features.attachments.AttachmentsModule)attachments).documentCapabilities():new JSONObject()));
        if(existing!=null)for(CapabilityDescriptor route:routes.values())if(existing.supports(route.name))throw new IllegalArgumentException("既存能力工具与leaf名称冲突："+route.name);
        Set<String> names=new java.util.HashSet<>();JSONArray tools=tools();for(int i=0;i<tools.length();i++)if(!names.add(tools.getJSONObject(i).getJSONObject("function").getString("name")))throw new IllegalArgumentException("工具名称重复");
    }
    private void add(CapabilityDescriptor descriptor){if(!descriptor.approvalRequired||binding.allowWrites)routes.put(descriptor.name,descriptor);}
    public JSONArray descriptors()throws Exception {JSONArray out=new JSONArray();for(CapabilityDescriptor route:routes.values())out.put(route.metadata());return out;}
    @Override public JSONArray tools()throws Exception {JSONArray out=new JSONArray();if(existing!=null){JSONArray previous=existing.tools();for(int i=0;i<previous.length();i++)out.put(new JSONObject(previous.getJSONObject(i).toString()));}for(CapabilityDescriptor route:routes.values())out.put(route.definition());return out;}
    @Override public void beginRun(String goal,RunControl control)throws Exception{binding.check(control);if(existing!=null)existing.beginRun(goal,control);}
    @Override public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control)throws Exception{binding.check(control);if(existing!=null)existing.admitCapabilities(snapshot,control);}
    @Override public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl control)throws Exception{binding.check(control);if(existing!=null)existing.afterTool(name,arguments,receipt,control);}
    @Override public boolean responseCurrent(RunControl control)throws Exception{binding.check(control);return existing==null||existing.responseCurrent(control);}
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{binding.check(control);if(existing!=null)existing.beforeModelRequest(requestId,messages,control);}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{binding.check(control);return existing==null?null:existing.afterModelResponse(requestId,answerId,control);}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{if(existing!=null)existing.modelRequestAbandoned(requestId);}
    @Override public void endRun(RunControl control)throws Exception{if(existing!=null)existing.endRun(control);}
    @Override public void close(){if(existing!=null)existing.close();}
    @Override public JSONObject contextSnapshot(String query)throws Exception {return existing==null?new JSONObject():existing.contextSnapshot(query);}
    @Override public boolean supports(String name){return routes.containsKey(name)||existing!=null&&existing.supports(name);}
    @Override public boolean sideEffecting(String name){CapabilityDescriptor route=routes.get(name);return route==null?existing==null||existing.sideEffecting(name):route.journalRequired;}
    @Override public void validate(String name,JSONObject args)throws Exception {CapabilityDescriptor route=routes.get(name);if(route==null){if(existing==null)throw new SecurityException("工具未挂载");existing.validate(name,args);}else route.normalize(args);}

    @Override public JSONObject execute(String name,JSONObject input,RunControl control)throws Exception {
        CapabilityDescriptor route=routes.get(name);if(route==null){if(existing==null)throw new SecurityException("工具未挂载");return existing.execute(name,input,control);}
        binding.check(control);authority.check(name,control);
        if(ownerReviewRequired)return new JSONObject().put("ok",false).put("accepted",false).put("code","needs_user")
                .put("requiresPause",true).put("message","上次创建后执行状态已变化，请由主人核对后继续原任务。");
        JSONObject original=new JSONObject((input==null?new JSONObject():input).toString());Object supplied=original.opt("_hostOperationId");original.remove("_hostOperationId");
        String operationId="";if(supplied!=null){if(!(supplied instanceof String))throw new SecurityException("宿主operation无效");operationId=(String)supplied;CapabilityDescriptor.uuid(operationId);}
        JSONObject args=route.normalize(original);
        if(route.journalRequired){if(operationId.isEmpty())throw new SecurityException("需要原AgentRunner登记的operation");authority.requirePrepared(operationId,name,original);if(reconciliationRequired||entered.contains(operationId))return CapabilityResults.uncertain(route,operationId,null);}
        else if(!operationId.isEmpty())throw new SecurityException("读取不能携带另一个operation");
        checkResource(route);
        JSONObject capability=new JSONObject().put("feature",route.feature).put("command",route.command);
        if(route.feature.equals("browser"))capability.put("binding",browser.capability());
        if(route.feature.equals("ssh"))capability.put("binding",ssh.capability());
        if(route.name.equals("attachments_create_text"))capability.put("hostTaskId",binding.taskId).put("projectId",binding.projectId);
        if(route.approvalRequired){
            if(!binding.allowWrites)throw new SecurityException("本任务仅允许读取");JSONObject approval=authority.approve(route,new JSONObject(args.toString()),capability);
            if(!approval.optBoolean("approved"))return new JSONObject().put("ok",false).put("accepted",false).put("code","approval_required")
                    .put("approvalId",approval.optString("approvalId")).put("message","需要主人确认本次具体参数与能力身份，尚未执行。");
        }
        binding.check(control);authority.check(name,control);checkResource(route);
        if(route.journalRequired){authority.requirePrepared(operationId,name,original);synchronized(entered){if(entered.contains(operationId))return CapabilityResults.uncertain(route,operationId,null);if(entered.size()>=1000)throw new IllegalStateException("本执行调用数量超限");entered.add(operationId);}}
        JSONObject moduleInput=new JSONObject(args.toString());EngineModule module=null;
        if(route.feature.equals("browser")){module=browser.module;for(java.util.Iterator<String> keys=browser.preset.keys();keys.hasNext();){String key=keys.next();moduleInput.put(key,browser.preset.get(key));}}
        else if(route.feature.equals("ssh")){module=ssh.module;moduleInput.put("profileId",ssh.profileId).put("_profileBinding",ssh.bindingHash).put("hostTaskId",binding.taskId)
                .put("principalId","owner").put("spaceId","private").put("_operationId",operationId).put("_hostRunId",binding.runId).put("_hostEpoch",binding.epoch);}
        else if(route.name.equals("attachments_create_text")){module=attachments;moduleInput.put("taskId",binding.taskId);if(!binding.projectId.isEmpty())moduleInput.put("projectId",binding.projectId);}
        ToolOutputCapture.Claim outputClaim=null;
        if(outputCapture!=null&&!route.feature.equals("browser"))try{outputClaim=outputCapture.begin(name,operationId,ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,control);if(outputClaim==null&&route.name.equals("attachments_read_document"))throw new ToolOutputCapture.OutputSourceUnavailable(null);}
        catch(ToolOutputCapture.OutputSourceUnavailable unavailable){control.check();throw unavailable;}
        catch(Exception unavailable){control.check();if(route.name.equals("attachments_read_document"))throw new ToolOutputCapture.OutputSourceUnavailable(unavailable);}
        JSONObject result=null,captureMetadata=null;boolean nativeEntered=false;
        try{
            binding.check(control);authority.check(name,control);checkResource(route);
            nativeEntered=true;
            if(route.name.equals("attachments_read_document")){
                result=documents.readDocument(moduleInput,control);
                if(result==null||!args.getString("attachmentId").equals(result.optString("attachmentId"))
                        ||!args.getString("expectedSha256").equals(result.optString("sha256")))throw new SecurityException("文档回执不属于当前附件与摘要");
            }
            else if(route.feature.equals("browser")){
                result=browser.liveCall.invokeForHost(route.command,moduleInput,control,()->{binding.check(control);authority.check(name,control);browser.check();});
                if(result!=null)for(String key:new String[]{"profileId","profileRevision","browserSessionId","profileIsolation"}){Object pinned=browser.preset.get(key);if(result.has(key)&&!String.valueOf(pinned).equals(String.valueOf(result.get(key))))throw new SecurityException("浏览回执不属于原生选定的Profile会话");result.put(key,pinned);}
            }
            else if(route.feature.equals("ssh"))result=ssh.liveCall.invokeForHost(route.command,moduleInput,control);
            else result=module.handle(route.command,moduleInput);
            captureMetadata=recordOutput(route,outputClaim,result);
            try{binding.check(control);authority.check(name,control);checkResource(route);}
            catch(Exception invalidated){
                // createText returns committed UUID/digest metadata. Preserve that known local
                // receipt for the original journal even if cancellation arrived before delivery.
                if(route.name.equals("attachments_create_text")&&result!=null){
                    JSONObject actual=CapabilityResults.withCapture(CapabilityResults.creationAfterStop(route,result,operationId,control.isCancelled()||Thread.currentThread().isInterrupted()),captureMetadata);
                    ownerReviewRequired=true;return actual;
                }
                if(route.journalRequired){reconciliationRequired=true;return CapabilityResults.withCapture(CapabilityResults.uncertain(route,operationId,result),captureMetadata);}throw invalidated;
            }
            JSONObject adapted=CapabilityResults.adapt(route,result,operationId,captureMetadata);if(route.journalRequired&&adapted.optString("status").equals("unknown"))reconciliationRequired=true;return adapted;
        }catch(Exception failure){
            if(!nativeEntered&&outputClaim!=null)try{outputClaim.notDispatched();}catch(Exception cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            // The call crossed the native boundary. Without a definite receipt a journaled call
            // is uncertain, including local text creation; no automatic retry is invented here.
            if(route.journalRequired&&nativeEntered){reconciliationRequired=true;return CapabilityResults.withCapture(CapabilityResults.uncertain(route,operationId,result),captureMetadata);}throw failure;
        }
    }
    private JSONObject recordOutput(CapabilityDescriptor route,ToolOutputCapture.Claim claim,JSONObject nativeResult)throws Exception{
        if(claim==null)return ToolOutputCapture.unavailable(outputCapture==null||route.feature.equals("browser")?"native_output_not_configured":"native_output_claim_unavailable").metadata();
        try{
            JSONObject data=CapabilityResults.captureData(route,nativeResult);Boolean truncated=null;
            if(nativeResult.opt("outputTruncated") instanceof Boolean)truncated=nativeResult.getBoolean("outputTruncated");
            else if(nativeResult.opt("truncated") instanceof Boolean)truncated=nativeResult.getBoolean("truncated");
            else if(Boolean.TRUE.equals(nativeResult.opt("outputComplete")))truncated=false;
            ToolOutputCapture.Result actual=claim.record(data,truncated);return actual==null?ToolOutputCapture.unavailable("native_output_capture_unavailable").metadata():actual.metadata();
        }catch(Exception unavailable){return ToolOutputCapture.unavailable("native_output_capture_unavailable").metadata();}
    }
    private void checkResource(CapabilityDescriptor descriptor)throws Exception {if(descriptor.feature.equals("browser"))browser.check();else if(descriptor.feature.equals("ssh"))ssh.check();}
    private static EngineModule requireModule(EngineModule module,String id){Objects.requireNonNull(module);if(!id.equals(module.id()))throw new IllegalArgumentException("原生模块身份不符");return module;}
}
