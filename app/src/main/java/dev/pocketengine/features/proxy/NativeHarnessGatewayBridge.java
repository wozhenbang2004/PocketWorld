package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import dev.pocketengine.runtime.models.NativeInferenceScope;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original phone gateway only. No Runner, PhonePort, tool dispatcher or provider credential export.
 * A JSON descriptor is transport metadata, never a native authority object. */
public final class NativeHarnessGatewayBridge implements AutoCloseable {
    @FunctionalInterface public interface Transport {JSONObject request(String exactPath,JSONObject body)throws Exception;}
    /** Must durably claim in the original TaskStore before returning. An existing claim is query-only.
     * No in-memory/default implementation exists: without the original issuer, dispatch is impossible. */
    public interface ClaimAuthority {
        Claim claim(String leaseId,String bridgeRequestId,String requestDigest)throws Exception;
        void outcome(String leaseId,String bridgeRequestId,String claimId,boolean complete)throws Exception;
        default void registered()throws Exception{}
        void reconcile()throws Exception;
        void close()throws Exception;
    }
    public record Claim(String claimId,boolean dispatchAllowed) {
        public Claim {if(claimId==null||!claimId.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("native claim required");}
    }
    private static final java.util.concurrent.Semaphore INFERENCE_SLOTS=new java.util.concurrent.Semaphore(2,true);
    @FunctionalInterface public interface StartupFence {void check()throws Exception;}
    private volatile StartupFence startupFence;
    private final TaskStore tasks;
    private final RunControl original;
    private final LoopbackRelay.ConfigSource configurations;
    private final Transport transport;
    private final ClaimAuthority claims;
    private final TaskStore.HarnessGatewayConfigurationOwner configurationOwner;
    private volatile String failureClass="",failureLocation="";
    private final ModelConfig selection;
    private final RelayRouteBinding route;
    private final long selectionRevision,expiresAt;
    private final String leaseId=UUID.randomUUID().toString(),generation=UUID.randomUUID().toString(),clientBinding=UUID.randomUUID().toString();
    private final String environmentId,environmentBinding,bindingId,profileDigest;
    private final InferenceCapture.Target target;
    private final Set<String> observed=new HashSet<>();
    private final Runnable cancelled=this::cancelLocal;
    private volatile boolean closed,registered,registrationAttempted,reconcile,inferenceRunning;
    private volatile Thread worker;
    private volatile JSONObject registration;

    /** Existing native owner/run only. New RunControl or caller-supplied task JSON cannot enter.
     * The caller retains its original scope check (foreground owner or task execution admission). */
    public static NativeHarnessGatewayBridge forTask(TaskStore tasks,RunControl original,LoopbackRelay.ConfigSource configurations,
            Transport transport,String environmentId,String environmentBinding,String bindingId,String profileDigest,long expiresAt)throws Exception {
        return forTask(tasks,original,configurations,transport,environmentId,environmentBinding,bindingId,profileDigest,expiresAt,null);
    }
    public static NativeHarnessGatewayBridge forTask(TaskStore tasks,RunControl original,LoopbackRelay.ConfigSource configurations,Transport transport,String environmentId,String environmentBinding,String bindingId,String profileDigest,long expiresAt,TaskStore.HarnessGatewayConfigurationOwner owner)throws Exception {return forTask(tasks,original,configurations,transport,environmentId,environmentBinding,bindingId,profileDigest,expiresAt,owner,null);}
    public static NativeHarnessGatewayBridge forTask(TaskStore tasks,RunControl original,LoopbackRelay.ConfigSource configurations,Transport transport,String environmentId,String environmentBinding,String bindingId,String profileDigest,long expiresAt,TaskStore.HarnessGatewayConfigurationOwner owner,StartupFence startup)throws Exception {return new NativeHarnessGatewayBridge(tasks,original,configurations,transport,environmentId,environmentBinding,bindingId,profileDigest,expiresAt,owner,startup);}
    private NativeHarnessGatewayBridge(TaskStore tasks,RunControl original,LoopbackRelay.ConfigSource configurations,
            Transport transport,String environmentId,String environmentBinding,String bindingId,String profileDigest,long expiresAt,TaskStore.HarnessGatewayConfigurationOwner owner,StartupFence startup)throws Exception {
        configurationOwner=owner;startupFence=startup;
        this.tasks=java.util.Objects.requireNonNull(tasks);this.original=java.util.Objects.requireNonNull(original);
        this.configurations=java.util.Objects.requireNonNull(configurations);this.transport=java.util.Objects.requireNonNull(transport);
        RunControl.HostRunIdentity id=original.hostRunIdentity();if(id==null)throw new SecurityException("原任务控制器与持久执行代次必需；网关不创建手机任务");
        long deadline=tasks.taskExecutionDeadline(id.taskId(),id.runId(),id.epoch());if(owner!=null)deadline=Math.min(deadline,owner.deadline());
        if(expiresAt<=System.currentTimeMillis()||expiresAt>deadline||expiresAt>System.currentTimeMillis()+600_000L)throw new SecurityException("网关期限必须包含在原执行期限内且首版不超过十分钟");
        this.expiresAt=expiresAt;this.environmentId=id(environmentId);this.environmentBinding=bounded(environmentBinding,256);this.bindingId=id(bindingId);
        if(profileDigest==null||!profileDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("固定Profile摘要必需");this.profileDigest=profileDigest;
        selection=configurations.get();InferenceAudiencePolicy.require(selection,NativeInferenceScope.externalRelay(),original);
        if(selection.usesAccount())throw new SecurityException("CHATGPT_PLAN_APP_ONLY");
        route=new RelayRouteBinding(selection);selectionRevision=configurations.selectionRevision();
        target=tasks.journal(id.taskId(),id.runId(),id.epoch(),original).inferenceTarget(InferenceReceipt.Purpose.RELAY,"");
        if(target.logical==null)throw new SecurityException("原SQL逻辑推理/物理回执权威必需");
        claims=tasks.createHarnessGatewayClaims(original,descriptor(),configurationOwner);
        check();original.onCancel(cancelled);
    }
    private static String id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("准确原生引用必需");return value;}
    private static String bounded(String value,int maximum){if(value==null||value.isEmpty()||value.length()>maximum)throw new IllegalArgumentException("有界引用必需");return value;}
    private void check()throws Exception {
        original.check();if(configurationOwner!=null)configurationOwner.checkCurrent();if(closed||reconcile||original.isPaused()||System.currentTimeMillis()>=expiresAt)throw new InterruptedException("原网关租约已结束或等待核对");
        var id=original.hostRunIdentity();if(id==null||!target.context.taskId.equals(id.taskId())||!target.context.runId.equals(id.runId())||target.context.epoch!=id.epoch())throw new SecurityException("网关所属原执行已变化");
        tasks.taskExecutionDeadline(id.taskId(),id.runId(),id.epoch());
        ModelConfig current=configurations.get();InferenceAudiencePolicy.require(current,NativeInferenceScope.externalRelay(),original);
        if(!route.matches(current)||selectionRevision!=configurations.selectionRevision())throw new SecurityException("原RELAY模型选择或凭据代次已变化；停止而不自动换路由");
    }
    /** Called only after the actual native owner elects this original target. Token is ephemeral
     * GATEWAY_ACCESS and travels only over the paired transport, never in status/Profile/logs. */
    public synchronized JSONObject start()throws Exception {
        check();if(startupFence!=null)startupFence.check();if(registered)throw new IllegalStateException("原网关已登记，不能重启旧HTTP会话");
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);java.util.Arrays.fill(random,(byte)0);
        JSONObject descriptor=descriptor().put("accessToken",token).put("protocols",protocols());
        try{registrationAttempted=true;registration=transport.request("/v1/gateway/leases",descriptor);validateLease(registration);if(startupFence!=null)startupFence.check();claims.registered();registered=true;startupFence=null;}
        catch(Exception unknown){reconcile=true;throw unknown;}
        finally{descriptor.remove("accessToken");}
        worker=new Thread(this::poll,"pocket-harness-gateway");worker.setDaemon(true);worker.start();return snapshot();
    }
    private JSONObject descriptor()throws Exception {JSONObject result=new JSONObject().put("leaseId",leaseId).put("clientBindingId",clientBinding).put("generation",generation).put("environmentId",environmentId).put("environmentBinding",environmentBinding).put("bindingId",bindingId).put("profileDigest",profileDigest).put("taskId",target.context.taskId).put("runId",target.context.runId).put("epoch",target.context.epoch).put("audience","OWNER_PRIVATE").put("purpose","EXTERNAL_RELAY").put("expiresAt",expiresAt);if(configurationOwner!=null)result.put("configurationOwner",configurationOwner.view());return result;}
    private JSONArray protocols()throws Exception {JSONArray result=new JSONArray();for(String path:new String[]{"/v1/responses","/v1/messages","/v1/chat/completions"}){JSONArray paths=NativeRelayPolicy.paths(selection);for(int i=0;i<paths.length();i++)if(paths.getString(i).equals(path))result.put(dev.pocketengine.runtime.providers.ProtocolCodec.ingress(path));}return result;}
    private void validateLease(JSONObject value)throws Exception {if(!leaseId.equals(value.optString("leaseId"))||!generation.equals(value.optString("generation"))||!clientBinding.equals(value.optString("clientBindingId"))||!"ACTIVE".equals(value.optString("state"))||value.optLong("expiresAt")!=expiresAt||value.getJSONObject("gatewayBridge").getInt("version")!=1)throw new SecurityException("不是原生登记的准确网关租约");}
    public String leaseId(){return leaseId;}
    public RunControl originalControl(){return original;}
    public boolean running(){return registered&&!closed&&!reconcile&&System.currentTimeMillis()<expiresAt;}
    public JSONObject snapshot()throws Exception {JSONObject result=new JSONObject().put("leaseId",leaseId).put("generation",generation).put("hostMode","GATEWAY_ONLY").put("state",reconcile?"UNKNOWN":closed?"REVOKED":System.currentTimeMillis()>=expiresAt?"EXPIRED":registered?"ACTIVE":"PREPARED").put("running",running()).put("requiresReconcile",reconcile).put("expiresAt",expiresAt).put("taskId",target.context.taskId).put("runId",target.context.runId).put("epoch",target.context.epoch).put("purpose","RELAY").put("audience","EXTERNAL_RELAY").put("phoneExecution",false).put("toolExecution",false).put("socketResume",false);if(configurationOwner!=null)result.put("configurationOwner",configurationOwner.view());if(!failureClass.isEmpty())result.put("failureClass",failureClass).put("failureLocation",failureLocation);return result;}
    public TaskStore.HarnessGatewayConfiguration configurationFor(String child)throws Exception {if(configurationOwner==null||!child.equals(configurationOwner.view().getString("taskId")))throw new SecurityException("原租约未绑定此配置子任务");check();return configurationOwner.configuration(leaseId,selection.model);}
    public boolean matchesReview(String digest,long revision)throws Exception {return selectionRevision==revision&&dev.pocketengine.runtime.models.ModelRoutePlan.configurationDigest(selection).equals(digest);}
    /** Original identifiers only. No claims, bodies, model requests, or automatic reconciliation. */
    public JSONObject queryOriginal()throws Exception {
        JSONObject result=snapshot().put("readOnly",true).put("resubmitted",false);JSONArray records=tasks.originalHarnessGatewayRecords(target.context.taskId,target.context.runId),requests=new JSONArray();
        try{JSONObject actual=transport.request("/v1/gateway/leases/"+leaseId+"?generation="+generation,null);if(!leaseId.equals(actual.optString("leaseId"))||!generation.equals(actual.optString("generation")))throw new SecurityException("不是原租约查询");result.put("transport",actual).put("transportRead","confirmed");}catch(Exception unavailable){result.put("transportRead","unavailable_unknown_not_retried");}
        for(int i=0;i<records.length();i++){JSONObject record=records.getJSONObject(i);if(!leaseId.equals(record.getJSONObject("descriptor").getString("leaseId")))continue;JSONArray original=record.getJSONArray("requests");int index=original.length()-1;for(int n=original.length()-1;n>=0;n--)if(original.getJSONObject(n).optString("state").matches("UNKNOWN|MAY_HAVE_DISPATCHED")){index=n;break;}if(index>=0){JSONObject row=original.getJSONObject(index),read=new JSONObject(row.toString());try{JSONObject bound=new JSONObject().put("leaseId",leaseId).put("generation",generation).put("requestDigest",row.getString("requestDigest"));JSONObject actual=transport.request("/v1/gateway/requests/"+row.getString("requestId")+"?"+query(bound),null);validateRequest(actual);if(!row.getString("claimId").equals(actual.optString("claimId")))throw new SecurityException("另一原claim");read.put("transport",actual).put("readStatus","confirmed");}catch(Exception unavailable){read.put("readStatus","unavailable_unknown_not_retried");}requests.put(read);}}
        return result.put("originalRequests",requests).put("observationLimit",1).put("bodyRecovery",false).put("socketResume",false);
    }
    private void poll(){
        try{while(true){check();JSONObject page=transport.request("/v1/gateway/leases/"+leaseId+"/requests?generation="+generation+"&waitMs=10000",null);check();if(!leaseId.equals(page.optString("leaseId"))||!generation.equals(page.optString("generation")))throw new SecurityException("另一网关poll返回");JSONArray requests=page.getJSONArray("requests");if(requests.length()>1)throw new SecurityException("目标推理并发超界");for(int i=0;i<requests.length();i++)serve(requests.getJSONObject(i));}}
        catch(Exception stopped){if(!closed){reconcile=true;try{JSONObject state=transport.request("/v1/gateway/leases/"+leaseId+"?generation="+generation,null);if(leaseId.equals(state.optString("leaseId"))&&generation.equals(state.optString("generation"))&&Set.of("REVOKED","EXPIRED").contains(state.optString("state")))reconcile=false;}catch(Exception unavailable){/* Unknown remains conservative; never a replay. */}}}
        finally{cancelRemote();closed=true;try{if(reconcile)claims.reconcile();claims.close();}catch(Exception storage){reconcile=true;}}
    }
    private JSONObject binding(JSONObject request)throws Exception {return new JSONObject().put("leaseId",leaseId).put("generation",generation).put("requestDigest",request.getString("requestDigest"));}
    private String query(JSONObject body)throws Exception {return "leaseId="+leaseId+"&generation="+generation+"&requestDigest="+body.getString("requestDigest");}
    private void validateRequest(JSONObject request)throws Exception {if(!leaseId.equals(request.optString("leaseId"))||!generation.equals(request.optString("generation"))||!clientBinding.equals(request.optString("clientBindingId"))||!request.optString("requestDigest").matches("[a-f0-9]{64}")||request.optInt("bodyBytes")<1||request.optInt("bodyBytes")>2*1024*1024)throw new SecurityException("不是原lease的有界请求");id(request.getString("bridgeRequestId"));}
    private void serve(JSONObject request)throws Exception {
        check();validateRequest(request);String requestId=request.getString("bridgeRequestId");
        if(!observed.add(requestId))throw new SecurityException("原请求已观察；只查询原状态不重新推理");
        if(observed.size()>128)throw new SecurityException("原bridge观察上限已到");
        if(!INFERENCE_SLOTS.tryAcquire())throw new IOException("全局两路原生桥接推理已占用；没有新增派发");
        try{serveAdmitted(request,requestId);}finally{INFERENCE_SLOTS.release();}
    }
    private void serveAdmitted(JSONObject request,String requestId)throws Exception {
        JSONObject bound=binding(request);Claim claim=claims.claim(leaseId,requestId,request.getString("requestDigest"));if(!claim.dispatchAllowed()){reconcile=true;return;}
        bound.put("claimId",claim.claimId());String path="/v1/gateway/requests/"+requestId;
        try{
            JSONObject claimed;
            try{claimed=transport.request(path+"/claim",bound);}catch(IOException unknown){claimed=transport.request(path+"?"+query(bound),null);}
            validateRequest(claimed);if(!"CLAIMED".equals(claimed.optString("state"))||!claim.claimId().equals(claimed.optString("claimId"))||!claimed.optBoolean("clientAlive"))throw new SecurityException("原claim与等待客户端未确认；没有模型发送");
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            while(body.size()<request.getInt("bodyBytes")){check();JSONObject fragment=transport.request(path+"/body?"+query(bound)+"&offset="+body.size(),null);validateRequest(fragment);if(fragment.getInt("offset")!=body.size())throw new SecurityException("原body偏移不连续");byte[] raw=Base64.getDecoder().decode(fragment.getString("body"));if(raw.length<1||raw.length>16384||body.size()+raw.length>request.getInt("bodyBytes"))throw new SecurityException("原body片段超界");body.write(raw);}
            byte[] raw=body.toByteArray();String digest=dev.pocketengine.features.local.project.ProjectSources.sha256(raw);if(!digest.equals(request.getString("requestDigest")))throw new SecurityException("原请求字节digest不同");
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
            var prepared=NativeRelayPolicy.prepare(selection,request.getString("path"),ChatCompletionStream.parseObject(json));
            Wire wire=new Wire(path,bound,prepared.gateway.stream);check();
            var admitted=InferenceCapture.withAdmission(target,()->{check();JSONObject current=transport.request(path+"?"+query(bound),null);validateRequest(current);if(!"CLAIMED".equals(current.optString("state"))||!claim.claimId().equals(current.optString("claimId"))||!current.optBoolean("clientAlive"))throw new SecurityException("原客户端/claim已停止，未新增派发");});
            inferenceRunning=true;
            JSONObject result;try{result=ModelGateway.completeNative(selection,prepared.gateway,original,admitted,(call,seq)->check(),(event,data)->wire.event(((event.isEmpty()?"":"event: "+event+"\n")+"data: "+data+"\n\n").getBytes(StandardCharsets.UTF_8)));}finally{inferenceRunning=false;}
            if(!prepared.gateway.stream)wire.event(result.toString().getBytes(StandardCharsets.UTF_8));wire.eof();claims.outcome(leaseId,requestId,claim.claimId(),true);
        }catch(Exception failed){failureClass=failed.getClass().getSimpleName();for(StackTraceElement location:failed.getStackTrace())if(location.getClassName().startsWith("dev.pocketengine.")){failureLocation=location.getClassName()+"#"+location.getMethodName()+":"+location.getLineNumber();break;}reconcile=true;try{claims.outcome(leaseId,requestId,claim.claimId(),false);}catch(Exception storage){failed.addSuppressed(storage);}try{transport.request(path+"/failure",bound);}catch(Exception unavailable){failed.addSuppressed(unavailable);}throw failed;}
    }
    private final class Wire {
        final String path;final JSONObject binding;final boolean stream;final long deadlineAt;long sequence;
        Wire(String path,JSONObject binding,boolean stream)throws Exception {this.path=path;this.binding=new JSONObject(binding.toString());this.stream=stream;deadlineAt=Math.min(expiresAt,System.currentTimeMillis()+selection.requestTimeoutSeconds*1000L);}
        private void current()throws Exception {check();if(System.currentTimeMillis()>=deadlineAt)throw new java.net.SocketTimeoutException("原推理绝对期限已到；反压与ACK重读没有重置期限");}
        void event(byte[] bytes)throws Exception {long seq=++sequence;if(bytes.length>8*1024*1024)throw new IOException("单事件超过有界重组限制");for(int offset=0;offset<bytes.length;offset+=16384){int end=Math.min(bytes.length,offset+16384);send(seq,offset,java.util.Arrays.copyOfRange(bytes,offset,end),end==bytes.length,false);}}
        void eof()throws Exception {send(++sequence,0,new byte[0],true,true);}
        void send(long seq,int offset,byte[] bytes,boolean last,boolean eof)throws Exception {
            JSONObject frame=new JSONObject(binding.toString()).put("eventSeq",seq).put("offset",offset).put("payload",Base64.getEncoder().encodeToString(bytes)).put("isLastFragment",last).put("eof",eof).put("status",200).put("contentType",stream?"text/event-stream; charset=utf-8":"application/json; charset=utf-8");
            // Retry only these exact bytes/identity. No path here re-enters completeNative.
            while(true){current();try{JSONObject ack=transport.request(path+"/frames",frame);current();JSONObject at=ack.getJSONObject("acceptedThrough");if(at.getLong("eventSeq")!=seq||at.getInt("offset")!=offset)throw new SecurityException("ACK不是原完整片段身份");return;}
                catch(dev.pocketengine.features.remote.RemoteTransport.HttpFailure failure){if(failure.status!=429)throw failure;Thread.sleep(50);}
                catch(IOException unavailable){current();JSONObject state=transport.request(path+"?"+query(binding),null);JSONObject at=state.optJSONObject("acceptedThrough");if(at!=null&&at.optLong("eventSeq")==seq&&at.optInt("offset")==offset)return;throw unavailable;}}
        }
    }
    private void cancelLocal(){closed=true;if(inferenceRunning)original.cancel();Thread running=worker;if(running!=null)running.interrupt();}
    private void cancelRemote(){if(!registrationAttempted)return;try{transport.request("/v1/gateway/leases/"+leaseId+"/cancel",new JSONObject().put("generation",generation).put("operationId",leaseId));}catch(Exception unknown){/* Original expiry still bounds new dispatch; no credential or old HTTP is revived. */}}
    @Override public void close(){cancelLocal();cancelRemote();try{claims.close();}catch(Exception storage){reconcile=true;}original.removeCancelAction(cancelled);}
}
