package dev.pocketengine.features.remote;

import android.util.AtomicFile;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner's paired computers. SSH and coding CLIs execute on the configured Companion. */
public final class RemoteModule implements EngineModule {
    interface CredentialReader {String get(String profileId,String baseUrl)throws Exception;}
    interface DispatchGate {void beforeDispatch(String path)throws Exception;}
    private final FeatureContext context;
    private final AtomicFile file;
    private final AtomicFile operationFile;
    private final JSONArray profiles=new JSONArray();
    private final JSONArray operations=new JSONArray();
    private final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Watch> watches=new ConcurrentHashMap<>();
    private final Map<String,JSONObject> recent=new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean readRefreshQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final CredentialReader credentials;
    private final DispatchGate dispatchGate;
    public RemoteModule(FeatureContext context) {
        this(context,(id,base)->context.secrets.get(slot(id,base)),path->{});
    }
    RemoteModule(FeatureContext context,CredentialReader credentials,DispatchGate dispatchGate) {
        this.credentials=credentials;this.dispatchGate=dispatchGate;
        this.context=context;file=new AtomicFile(new File(context.android.getFilesDir(),"remote-profiles.json"));operationFile=new AtomicFile(new File(context.android.getFilesDir(),"remote-operations.json"));
        try{JSONArray stored=new JSONArray(new String(file.readFully(),StandardCharsets.UTF_8));for(int i=0;i<stored.length()&&profiles.length()<20;i++){JSONObject p=stored.optJSONObject(i);if(p!=null&&p.optString("id").matches("[A-Za-z0-9_-]{1,80}"))profiles.put(p);}}catch(Exception ignored){}
        try{JSONArray stored=new JSONArray(new String(operationFile.readFully(),StandardCharsets.UTF_8));for(int i=0;i<stored.length()&&operations.length()<100;i++){JSONObject item=stored.optJSONObject(i);if(item!=null){if("submitting".equals(item.optString("status")))item.put("status","unknown").put("message","手机进程中断，需在电脑任务列表核对操作编号");operations.put(item);}}}catch(Exception ignored){}
        // Restore only read-side watches. Never resubmit a persisted operation.
        for(int i=0;i<operations.length();i++)try{JSONObject op=operations.getJSONObject(i);JSONObject selected=find(op.optString("profileId"));if(selected!=null&&!op.optString("jobId").isEmpty()&&"accepted".equals(op.optString("status"))&&endpoint(selected.optString("baseUrl")).equals(op.optString("serviceBase")))watch(op.optString("profileId"),op.optString("jobId"),op.optString("operationId"),op.optString("serviceBase"));}catch(Exception ignored){}
        poller.scheduleWithFixedDelay(this::poll,2,2,TimeUnit.SECONDS);
    }
    @Override public String id(){return "remote";}
    @Override public synchronized JSONObject snapshot()throws Exception {
        JSONArray listed=new JSONArray();for(int i=0;i<profiles.length();i++)listed.put(publicProfile(profiles.getJSONObject(i)));
        JSONArray jobs=new JSONArray();for(JSONObject job:recent.values()){JSONObject selected=find(job.optString("profileId"));if(jobs.length()<30&&selected!=null&&endpoint(selected.optString("baseUrl")).equals(job.optString("serviceBase")))jobs.put(metadata(job));}
        return new JSONObject().put("profiles",listed).put("jobs",jobs).put("operations",new JSONArray(operations.toString())).put("transport","companion_https").put("directSsh",false).put("configured",profiles.length()>0).put("operationReconciliation",true);
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        if(closed)throw new IllegalStateException("电脑连接模块已关闭");
        if(input==null)input=new JSONObject();
        switch(command){
            case "saveProfile":return saveProfile(input);
            case "removeProfile":return removeProfile(required(input,"profileId",80));
            case "probe":return request(input,"/v1/status",null,15);
            case "environments":return request(input,"/v1/environments",null,15);
            case "probeWorker":{
                JSONObject body=new JSONObject().put("environmentId",identifier(input,"environmentId")).put("worker",required(input,"worker",80));
                if(input.has("model"))body.put("model",required(input,"model",200));
                if(input.has("effort"))body.put("effort",required(input,"effort",80));
                if(input.has("environmentBinding"))body.put("environmentBinding",required(input,"environmentBinding",64));
                return request(input,"/v1/workers/probe",body,15);
            }
            case "listJobs":return request(input,"/v1/jobs",null,15);
            case "reconnect":return reconnect(input);
            case "reconcileOperation":return reconcile(input);
            case "getJob":{
                JSONObject result=requestJob(input,"/v1/jobs/"+identifier(input,"jobId"),null,15);
                result.put("profileId",required(input,"profileId",80));acceptReceipt(result);return result;
            }
            case "readSessionRecovery":{
                JSONObject result=requestJob(input,"/v1/jobs/"+identifier(input,"jobId")+"/session-recovery",null,15);
                result.put("profileId",required(input,"profileId",80));acceptReceipt(result);return result;
            }
            case "submitJob":return submitJob(input,null);
            case "cancelJob":return cancelOriginalJob(input,()->{},()->{},true);
            case "approveJob":{
                String job=identifier(input,"jobId"),profile=identifier(input,"profileId");JSONObject result;
                try{result=requestJob(input,"/v1/jobs/"+job+"/approval",new JSONObject().put("requestId",required(input,"approvalRequestId",200)).put("decision",required(input,"decision",20)),20);}
                catch(java.io.IOException unknown){result=new JSONObject().put("id",job).put("profileId",profile).put("status","unknown").put("message","审批请求是否被电脑接收尚未确认，请查询原任务；不会自动再次发送");context.emit(id(),new JSONObject().put("kind","connection_lost").put("profileId",profile).put("jobId",job).put("status","unknown"));return result;}
                result.put("profileId",profile);acceptReceipt(result);return result;
            }
            case "steerJob":return steerJob(input);
            case "getSteerMessage":return reconcileSteer(input);
            case "listFiles":return request(input,workspacePath(input,"files")+"?path="+URLEncoder.encode(input.optString("path",""),"UTF-8"),null,20);
            case "readFile":return request(input,workspacePath(input,"file")+"?path="+URLEncoder.encode(required(input,"path",2048),"UTF-8"),null,20);
            case "diffFile":return request(input,workspacePath(input,"diff")+"?path="+URLEncoder.encode(required(input,"path",2048),"UTF-8")+"&fromVersion="+URLEncoder.encode(required(input,"fromVersion",80),"UTF-8"),null,20);
            case "writeFile":return writeRemoteFile(input);
            case "reconcileFileWrite":return reconcileFile(input);
            case "readArtifact":return requestJob(input,"/v1/jobs/"+identifier(input,"jobId")+"/artifact?path="+URLEncoder.encode(required(input,"path",2048),"UTF-8"),null,20);
            default:throw new IllegalArgumentException("未知电脑连接操作");
        }
    }
    /** The existing AgentRunner read routes retain their original RunControl through network IO.
     * This admits no owner write, new command, profile, task or approval. */
    public JSONObject handleForTask(String command,JSONObject input,dev.pocketengine.core.RunControl original)throws Exception {
        java.util.Objects.requireNonNull(original).check();if(closed)throw new IllegalStateException("电脑连接模块已关闭");
        switch(command){
            case "environments":return request(input,"/v1/environments",null,15,original);
            case "listJobs":return request(input,"/v1/jobs",null,15,original);
            case "readFile":return request(input,workspacePath(input,"file")+"?path="+URLEncoder.encode(required(input,"path",2048),"UTF-8"),null,20,original);
            case "getJob":{
                JSONObject result=requestJob(input,"/v1/jobs/"+identifier(input,"jobId"),null,15,original);
                original.check();result.put("profileId",required(input,"profileId",80));acceptReceipt(result);return result;
            }
            default:throw new SecurityException("此命令不是原任务已登记的只读电脑工具");
        }
    }
    /** Native owner opaque approval only. EngineModule.handle does not expose project execution/restore. */
    public JSONObject dispatchProjectAction(dev.pocketengine.features.RemoteOwnerWorkflows.ProjectApproval approval,AssignmentDispatch before)throws Exception {
        if(closed||approval==null||before==null)throw new SecurityException("原连接已关闭或没有本次原生项目操作批准");approval.checkCurrent();JSONObject scope=approval.view();String action=scope.getString("action"),profile=scope.getString("profileId"),operation=scope.getString("operationId");requireProfileBinding(profile,scope.getString("profileGeneration"));
        JSONObject request=scope.getJSONObject("request"),body=new JSONObject().put("operationId",operation);String path;JSONObject details=new JSONObject().put("profileBinding",scope.getString("profileGeneration")).put("sourceServiceBase",scope.getString("serviceBase"));
        if(action.equals("RESTORE")){
            for(String key:new String[]{"jobOperationId","snapshotId","snapshotVersion","path","expectedVersion","expectedFileVersion","environmentBinding"})body.put(key,request.get(key));body.put("approvalExpiresAt",scope.getLong("approvalExpiresAt"));path="/v1/jobs/"+identifier(scope.optJSONObject("versionSource")==null?scope.getJSONObject("source"):scope.getJSONObject("versionSource"),"jobId")+"/restore-project-file";details.put("jobId",(scope.optJSONObject("versionSource")==null?scope.getJSONObject("source"):scope.getJSONObject("versionSource")).getString("jobId")).put("path",body.getString("path"));
        }else if(action.equals("START")||action.equals("FORK")){
            for(String key:new String[]{"environmentId","environmentBinding","worker","prompt","timeoutSeconds","model","readOnly","projectHistory","forkSource"})if(request.has(key))body.put(key,request.get(key));path="/v1/jobs";
            if(action.equals("START")&&!body.has("projectHistory")||action.equals("FORK")&&!body.has("forkSource"))throw new SecurityException("原项目/会话动作类型缺失");
        }else throw new SecurityException("未知原项目副作用类型");
        Reservation reservation=reserve(profile,operation,action.equals("RESTORE")?"file_restore":"job_submit",body,details);
        if(reservation.known)return action.equals("RESTORE")?queryProjectFileRestore(scope,scope.getString("profileGeneration")):reconcile(new JSONObject().put("profileId",profile).put("operationId",operation));
        approval.checkCurrent();requireProfileBinding(profile,scope.getString("profileGeneration"));
        JSONObject nativeTarget=new JSONObject().put("profileId",profile).put("_profileBinding",scope.getString("profileGeneration")).put("serviceBase",scope.getString("serviceBase"));JSONObject result;
        try{result=assignmentSend(nativeTarget,reservation.target,path,body,()->{approval.checkCurrent();before.beforeDispatch();approval.checkCurrent();});}catch(RemoteTransport.HttpFailure refusal){if(!action.equals("RESTORE")&&refusal.status>=400&&refusal.status<500){reservation.intent.put("status","rejected").put("message",refusal.getMessage());recordOperation(reservation.intent);return new JSONObject().put("operationId",operation).put("profileId",profile).put("serviceBase",scope.getString("serviceBase")).put("status","rejected").put("environmentId",request.getString("environmentId")).put("worker",request.getString("worker")).put("message",refusal.getMessage());}return unknownOperation(reservation.intent);}catch(java.io.IOException unknown){return unknownOperation(reservation.intent);}
        result.put("profileId",profile).put("serviceBase",scope.getString("serviceBase"));if(!operation.equals(result.optString("operationId")))return unknownOperation(reservation.intent);
        if(action.equals("RESTORE")){
            reservation.intent.put("status",result.optString("status").matches("completed|failed")?"settled":"unknown");recordOperation(reservation.intent);return result;
        }
        if(!result.optString("id").matches("[A-Za-z0-9_-]{1,80}")||!body.getString("environmentId").equals(result.optString("environmentId"))||!body.getString("worker").equals(result.optString("worker")))return unknownOperation(reservation.intent);
        if(action.equals("START")&&(!RemoteProjectHistory.PROTOCOL.equals(result.optJSONObject("projectHistoryScope")==null?"":result.getJSONObject("projectHistoryScope").optString("protocol"))||!dev.pocketengine.store.TaskStore.capabilityFingerprint(body.getJSONObject("projectHistory")).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(result.getJSONObject("projectHistoryScope")))))return unknownOperation(reservation.intent);
        if(action.equals("FORK")&&(result.optJSONObject("sessionBranchSource")==null||!dev.pocketengine.store.TaskStore.capabilityFingerprint(body.getJSONObject("forkSource")).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(result.getJSONObject("sessionBranchSource")))))return unknownOperation(reservation.intent);
        reservation.intent.put("status","accepted").put("jobId",result.getString("id"));recordOperation(reservation.intent);acceptReceipt(result);return result;
    }
    /** Local original-ledger cancellation only, after original SQL proves its native claim unused. */
    public void settleProjectCancellation(dev.pocketengine.features.RemoteOwnerWorkflows.ProjectApproval approval)throws Exception {
        JSONObject receipt=approval.originalLocalCancellation();if(receipt==null)return;JSONObject scope=approval.view();synchronized(this){JSONObject intent=operation(scope.getString("operationId"));if(intent==null)return;if(!scope.getString("profileId").equals(intent.getString("profileId"))||!scope.getString("serviceBase").equals(intent.getString("serviceBase"))||!intent.optString("kind").matches("job_submit|file_restore"))throw new SecurityException("本机未派发证明不是此原模块操作");intent.put("status","rejected").put("nativeCancelledBeforeDispatch",true).put("localCancellationReceipt",receipt);recordOperation(intent);}
    }
    public JSONObject readProjectHistory(JSONObject original,String generation,JSONObject query,AssignmentDispatch current)throws Exception {
        if(closed)throw new SecurityException("原连接已关闭");java.util.Set<String> allowed=java.util.Set.of("offset","limit","snapshotId","snapshotVersion","path","currentPath","diffPath","pageVersion");StringBuilder path=new StringBuilder("/v1/jobs/"+identifier(original,"jobId")+"/project-history");for(java.util.Iterator<String> keys=query.keys();keys.hasNext();){String key=keys.next();if(!allowed.contains(key))throw new SecurityException("未知原项目只读参数");path.append(path.indexOf("?")<0?'?':'&').append(key).append('=').append(URLEncoder.encode(String.valueOf(query.get(key)),"UTF-8"));}
        current.beforeDispatch();requireProfileBinding(original.getString("profileId"),generation);JSONObject result=snapshotRead(new JSONObject(original.toString()).put("_profileBinding",generation),path.toString());if(closed)throw new SecurityException("原连接已关闭");requireProfileBinding(original.getString("profileId"),generation);current.beforeDispatch();RemoteProjectHistory.original(result,original);return result;
    }
    public JSONObject queryProjectFileRestore(JSONObject scope,String currentGeneration)throws Exception {
        if(closed)throw new SecurityException("原连接已关闭");String profile=scope.getString("profileId"),operation=scope.getString("operationId");requireProfileBinding(profile,currentGeneration);JSONObject original; synchronized(this){original=operation(operation);if(original==null||!"file_restore".equals(original.optString("kind"))||!scope.getString("serviceBase").equals(original.optString("serviceBase")))throw new SecurityException("没有此原生文件恢复登记；不会创建新操作");}
        if(original.optBoolean("nativeCancelledBeforeDispatch"))return new JSONObject(original.getJSONObject("localCancellationReceipt").toString());BoundRequest target=bind(profile,scope.getString("serviceBase"));JSONObject result=send(target,"/v1/file-operations/"+operation,null,20);return result.put("profileId",profile).put("serviceBase",scope.getString("serviceBase"));
    }
    private JSONObject steerJob(JSONObject input)throws Exception {
        if(input.has("_profileBinding"))requireProfileBinding(input.getString("profileId"),input.getString("_profileBinding"));
        String profileId=identifier(input,"profileId"),jobId=identifier(input,"jobId"),messageId=identifier(input,"messageId");
        String content=required(input,"text",12000);
        JSONObject body=RemoteMessageTarget.copy(input,new JSONObject().put("messageId",messageId).put("text",content));
        JSONObject details=RemoteMessageTarget.copy(input,new JSONObject().put("messageId",messageId).put("jobId",jobId));
        if(RemoteMessageTarget.pi(input)){
            if(!required(input,"sessionId",200).equals(required(input,"threadId",200)))throw new SecurityException("Pi补充不是原session/thread");
            required(input,"jobOperationId",200);
        }
        if(input.has("_profileBinding"))details.put("profileBinding",required(input,"_profileBinding",64)).put("sourceServiceBase",required(input,"serviceBase",2048));
        for(String key:new String[]{"threadId","jobOperationId"})if(input.has(key)){String value=required(input,key,200);body.put(key,value);details.put(key,value);}
        Reservation reserved=reserve(profileId,messageId,"steer_message",body,details);
        if(reserved.known)return reconcileSteer(input);
        JSONObject intent=reserved.intent;
        try{String path="/v1/jobs/"+jobId+"/steer";dispatchGate.beforeDispatch(path);if(input.has("_profileBinding"))requireProfileBinding(profileId,input.getString("_profileBinding"));return acceptSteer(intent,reserved.target.request(path,body,15));}
        catch(RemoteTransport.HttpFailure rejected){if(rejected.status>=400&&rejected.status<500)return acceptSteer(intent,new JSONObject(intent.toString()).put("status","rejected").put("dispatched",false).put("errorCode",rejected.status).put("message","电脑拒绝本次补充；未创建新turn，也未自动重发"));return unknownSteer(intent);}
        catch(java.io.IOException unknown){return unknownSteer(intent);}
    }
    private JSONObject reconcileSteer(JSONObject input)throws Exception {
        String profileId=identifier(input,"profileId"),jobId=identifier(input,"jobId"),messageId=identifier(input,"messageId");JSONObject intent;BoundRequest target;
        synchronized(this){
            intent=operation(messageId);
            if(intent!=null&&(!"steer_message".equals(intent.optString("kind"))||!profileId.equals(intent.optString("profileId"))||!jobId.equals(intent.optString("jobId"))))throw new IllegalStateException("消息与原电脑或任务不一致，未向新目标发送");
            target=bind(profileId,intent==null?null:intent.optString("serviceBase"));
        }
        JSONObject result;
        try{result=send(target,"/v1/jobs/"+jobId+"/messages/"+messageId,null,15);}
        catch(java.io.IOException unavailable){return intent==null?new JSONObject().put("messageId",messageId).put("jobId",jobId).put("status","unknown").put("message","无法取得消息接收回执；没有重新发送"):unknownSteer(intent);}
        if(intent!=null)return acceptSteer(intent,result);
        return result.put("profileId",profileId);
    }
    private JSONObject acceptSteer(JSONObject intent,JSONObject result)throws Exception {
        if(!intent.getString("serviceBase").equals(result.optString("serviceBase"))||!intent.getString("serviceBase").equals(endpoint(profile(intent.getString("profileId")).getString("baseUrl"))))throw new IllegalStateException("电脑连接已改变，旧消息回执未接入新地址");
        String status=result.optString("status");
        if(!status.matches("queued|sending|accepted|unknown|rejected|not_found"))return unknownSteer(intent);
        if(!intent.getString("operationId").equals(result.optString("operationId",result.optString("messageId")))
                ||!intent.getString("messageId").equals(result.optString("messageId"))||!intent.getString("jobId").equals(result.optString("jobId")))return unknownSteer(intent);
        if(!status.equals("not_found")){
            try{if(!RemoteMessageTarget.receiptMatches(intent,result))return unknownSteer(intent);}
            catch(SecurityException|org.json.JSONException malformed){return unknownSteer(intent);}
        }
        for(String key:new String[]{"threadId","jobOperationId"}){
            if(intent.has(key)&&(RemoteMessageTarget.pi(intent)&&!status.equals("not_found")||status.equals("accepted")||result.has(key))&&!intent.getString(key).equals(result.optString(key)))return unknownSteer(intent);
            if(intent.has(key)&&!result.has(key))result.put(key,intent.get(key));
        }
        if(status.equals("not_found"))RemoteMessageTarget.copy(intent,result);
        intent.put("status",status.equals("accepted")?"settled":status.equals("rejected")?"rejected":status.equals("queued")||status.equals("sending")?"pending":"unknown").put("executorStatus",status);
        recordOperation(intent);result.put("profileId",intent.getString("profileId"));result.remove("fingerprint");
        context.emit(id(),new JSONObject().put("kind","steer_message").put("receipt",metadata(result)));return result;
    }
    private JSONObject unknownSteer(JSONObject intent)throws Exception {
        intent.put("status","unknown").put("message",RemoteMessageTarget.pi(intent)?"补充是否进入原Pi队列尚未确认；仅查询原消息，不重发、不另开prompt。此回执不证明模型已处理或任务已停止。":"补充要求是否被原turn接收尚未确认；仅查询消息编号，不会重发或开启新turn。原任务未被此回执停止。");recordOperation(intent);
        JSONObject result=new JSONObject(intent.toString());result.remove("fingerprint");
        context.emit(id(),new JSONObject().put("kind","steer_message").put("receipt",metadata(result)));return result;
    }
    private String workspacePath(JSONObject input,String leaf)throws Exception{return "/v1/workspaces/"+identifier(input,"environmentId")+"/"+leaf;}
    private JSONObject writeRemoteFile(JSONObject input)throws Exception {
        String profileId=identifier(input,"profileId"),environmentId=identifier(input,"environmentId"),path=required(input,"path",2048);
        if(!(input.opt("content") instanceof String)||input.getString("content").getBytes(StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException("文件内容需为256KiB以内UTF-8文字");
        String operationId=input.optString("operationId",UUID.randomUUID().toString());identifier(new JSONObject().put("id",operationId),"id");
        String expectedVersion=required(input,"expectedVersion",80);
        JSONObject body=new JSONObject().put("path",path).put("content",input.getString("content")).put("expectedVersion",expectedVersion).put("operationId",operationId);
        Reservation reserved=reserve(profileId,operationId,"file_write",body,new JSONObject().put("environmentId",environmentId).put("path",path));
        if(reserved.known)return reconcileFile(new JSONObject().put("profileId",profileId).put("operationId",operationId));
        JSONObject intent=reserved.intent;
        try{JSONObject result=send(reserved.target,"/v1/workspaces/"+environmentId+"/file",body,20).put("profileId",profileId);if(!fileReceiptMatches(operationId,intent,result)||!result.optString("status").matches("completed|failed"))return unknownOperation(intent);intent.put("status","settled").put("executorStatus",result.optString("status"));recordOperation(intent);context.emit(id(),new JSONObject().put("kind","file_receipt").put("operation",metadata(result)));return result;}
        catch(RemoteTransport.HttpFailure rejected){if(rejected.status>=400&&rejected.status<500){intent.put("status","rejected");recordOperation(intent);throw rejected;}return unknownOperation(intent);}
        catch(java.io.IOException unavailable){return unknownOperation(intent);}
    }
    private JSONObject reconcileFile(JSONObject input)throws Exception {
        String profileId=identifier(input,"profileId"),operationId=identifier(input,"operationId");JSONObject old;BoundRequest target;
        synchronized(this){
            old=operation(operationId);if(old!=null&&!"file_write".equals(old.optString("kind","job_submit")))throw new SecurityException("原操作不是文件写入，未用文件回执替换原任务");
            if(old!=null&&!profileId.equals(old.optString("profileId")))throw new IllegalStateException("电脑地址与原文件写入不一致，请恢复原连接核对");
            String expected=old==null?null:old.optString("serviceBase");
            if(input.has("serviceBase")){String reviewed=endpoint(required(input,"serviceBase",2048));RemoteTransport.validateBase(reviewed);if(expected!=null&&!expected.equals(reviewed))throw new SecurityException("审阅地址与原文件写入不一致，未向另一地址查询");expected=reviewed;}
            target=bind(profileId,expected);
        }
        JSONObject result;try{result=send(target,"/v1/file-operations/"+operationId,null,15).put("profileId",profileId);}catch(java.io.IOException unavailable){return new JSONObject().put("operationId",operationId).put("status","unknown").put("message","未取得文件写入回执，未重试写入");}
        if(!fileReceiptMatches(operationId,old,result))throw new SecurityException("文件回执不属于原操作、工作区或路径，原记录未被替换；请核对原写入");
        if(old!=null){old.put("status",result.optString("status").equals("completed")||result.optString("status").equals("failed")?"settled":"unknown").put("executorStatus",result.optString("status"));recordOperation(old);}
        context.emit(id(),new JSONObject().put("kind","file_receipt").put("operation",metadata(result)));return result;
    }
    private static boolean fileReceiptMatches(String operationId,JSONObject intent,JSONObject receipt){
        if(!operationId.equals(receipt.optString("operationId"))||!java.util.Set.of("completed","failed","unknown","not_found").contains(receipt.optString("status")))return false;
        if(intent!=null)for(String key:new String[]{"environmentId","path"})if((receipt.has(key)||receipt.optString("status").matches("completed|failed"))&&!intent.optString(key).equals(receipt.optString(key)))return false;
        return true;
    }
    private JSONObject unknownOperation(JSONObject intent)throws Exception {
        intent.put("status","unknown").put("message","电脑是否接收本次任务尚未确认。请查看电脑任务列表并核对operationId；不要直接重复提交。");recordOperation(intent);return new JSONObject(intent.toString());
    }
    private synchronized JSONObject operation(String id)throws Exception {for(int i=0;i<operations.length();i++)if(id.equals(operations.getJSONObject(i).optString("operationId")))return new JSONObject(operations.getJSONObject(i).toString());return null;}
    private static String endpoint(String base){return base.replaceAll("/+$","");}
    private static boolean active(String status){return status.equals("queued")||status.equals("running")||status.equals("stopping")||status.equals("awaiting_approval")||status.equals("awaiting_input");}
    // Freeze the accepted/persisted identity, never learn it from a later poll response.
    private void watch(String profileId,String jobId,String operationId,String serviceBase){if(jobId.matches("[A-Za-z0-9_-]{1,80}")&&operationId.matches("[A-Za-z0-9_-]{1,80}")&&watches.size()<12)watches.putIfAbsent(profileId+":"+jobId,new Watch(profileId,jobId,operationId,serviceBase));}
    private void acceptReceipt(JSONObject job)throws Exception {
        String profileId=job.optString("profileId"),jobId=job.optString("id"),service=job.optString("serviceBase");
        if(!service.equals(endpoint(profile(profileId).getString("baseUrl"))))throw new IllegalStateException("电脑连接已更改，旧地址回执没有接入新连接；请恢复原地址再核对");
        Watch original=watches.get(profileId+":"+jobId);if(original!=null)original.requireReceipt(job);
        JSONObject op=operation(job.optString("operationId"));
        if(op!=null){
            if(!"job_submit".equals(op.optString("kind","job_submit"))||!profileId.equals(op.optString("profileId"))||!service.equals(op.optString("serviceBase"))
                    ||!op.optString("jobId").isEmpty()&&!jobId.equals(op.optString("jobId")))throw new SecurityException("电脑回执不属于原连接、任务或操作");
            if(op.has("resumeJobId")){
                boolean archived=job.optBoolean("archived")&&"unknown".equals(job.optString("status"));
                if((job.has("resumeJobId")?!op.getString("resumeJobId").equals(job.optString("resumeJobId")):!archived)
                        ||(job.has("threadId")?!op.getString("threadId").equals(job.optString("threadId")):!archived))throw new SecurityException("续接回执不属于原会话和来源任务");
                job.put("jobOperationId",op.getString("jobOperationId")).put("sourceServiceBase",op.getString("sourceServiceBase"));
            }
            op.put("jobId",jobId).put("status",active(job.optString("status"))?"accepted":"settled").put("executorStatus",job.optString("status"));recordOperation(op);
        }
        if(job.optString("status").matches("completed|failed|cancelled")&&Boolean.TRUE.equals(job.opt("dispatchFenceFinal"))&&Boolean.FALSE.equals(job.opt("resourcesHeld")))for(int i=0;i<operations.length();i++){
            JSONObject stop=operations.getJSONObject(i);if("job_cancel".equals(stop.optString("kind"))&&profileId.equals(stop.optString("profileId"))&&service.equals(stop.optString("serviceBase"))&&jobId.equals(stop.optString("jobId"))&&job.optString("operationId").equals(stop.optString("jobOperationId"))){stop.put("status","settled");recordOperation(stop);}
        }
        remember(job);
        if(active(job.optString("status")))watch(profileId,jobId,job.optString("operationId"),service);else watches.remove(profileId+":"+jobId);
        context.emit(id(),new JSONObject().put("kind","job").put("job",metadata(job)));
        // Reuse the existing read-side job watch; a message ACK is not a job terminal event.
        JSONObject message=job.optJSONObject("lastSteerMessage");
        if(message!=null&&jobId.equals(message.optString("jobId"))&&job.optString("operationId").equals(message.optString("jobOperationId"))&&job.optString("threadId").equals(message.optString("threadId"))){
            JSONObject intent=operation(message.optString("messageId"));
            if(intent!=null&&intent.optString("kind").equals("steer_message")&&profileId.equals(intent.optString("profileId"))&&jobId.equals(intent.optString("jobId"))&&service.equals(intent.optString("serviceBase"))
                    &&!(intent.optString("executorStatus").matches("accepted|rejected")&&intent.optString("executorStatus").equals(message.optString("status")))){
                acceptSteer(intent,new JSONObject(message.toString()).put("serviceBase",service));
            }
        }
    }
    private JSONObject reconcile(JSONObject input)throws Exception {
        String profileId=identifier(input,"profileId"),operationId=identifier(input,"operationId");
        JSONObject op;BoundRequest target;
        JSONObject messageOperation=operation(operationId);if(messageOperation!=null&&messageOperation.optBoolean("nativeCancelledBeforeDispatch")){if(!profileId.equals(messageOperation.getString("profileId")))throw new SecurityException("本机取消记录不是此原电脑操作");return new JSONObject(messageOperation.getJSONObject("localCancellationReceipt").toString());}if(messageOperation!=null&&"assignment_prepare".equals(messageOperation.optString("kind")))return reconcileAssignmentPreparation(new JSONObject(input.toString()).put("sourceJobId",messageOperation.getString("sourceJobId")).put("_profileBinding",messageOperation.getString("profileBinding")));
        if(messageOperation!=null&&"file_write".equals(messageOperation.optString("kind")))return reconcileFile(new JSONObject(input.toString()));
        if(messageOperation!=null&&"job_cancel".equals(messageOperation.optString("kind"))){if(!profileId.equals(messageOperation.getString("profileId"))||input.has("serviceBase")&&!endpoint(input.getString("serviceBase")).equals(messageOperation.getString("serviceBase")))throw new SecurityException("停止核对不是此原电脑连接");return reconcileCancellation(messageOperation);}
        if(messageOperation!=null&&"steer_message".equals(messageOperation.optString("kind")))return reconcileSteer(new JSONObject().put("profileId",profileId).put("jobId",messageOperation.getString("jobId")).put("messageId",operationId));
        synchronized(this){
            op=operation(operationId);if(op!=null&&!profileId.equals(op.optString("profileId")))throw new IllegalStateException("电脑地址与原操作不一致；请恢复原连接再核对，未向新地址提交任务");
            String expected=op==null?null:op.optString("serviceBase");
            if(input.has("serviceBase")){
                String reviewed=endpoint(required(input,"serviceBase",2048));RemoteTransport.validateBase(reviewed);
                if(expected!=null&&!expected.equals(reviewed))throw new SecurityException("查询地址与原操作不匹配，未向另一地址查询");
                expected=reviewed;
            }
            // The native TaskStore may have registered an operation before this module reserved it.
            // Its reviewed original endpoint remains mandatory even when no transport cache entry exists.
            target=bind(profileId,expected);
        }
        JSONObject result;
        try{result=send(target,"/v1/operations/"+operationId,null,15);}
        catch(java.io.IOException unavailable){return new JSONObject().put("operationId",operationId).put("profileId",profileId).put("serviceBase",target.serviceBase).put("status","unknown").put("message","无法取得电脑的操作回执；未重提任务，请恢复连接后再次核对");}
        if(result.optString("operationId").isEmpty()&&result.optString("id").isEmpty()&&result.optString("status").matches("unknown|not_found"))result.put("operationId",operationId);
        if(!operationId.equals(result.optString("operationId")))throw new SecurityException("查询返回了另一操作或缺少原操作身份");
        result.put("profileId",profileId);
        if(!result.optString("id").isEmpty())acceptReceipt(result);
        else if(op!=null){op.put("status","unknown").put("message",result.optString("message","未找到接收回执，请核对实际结果；没有自动重试"));recordOperation(op);}
        context.emit(id(),new JSONObject().put("kind","operation_reconciled").put("operation",metadata(result)));
        return result;
    }
    private JSONObject reconnect(JSONObject input)throws Exception {
        String profileId=identifier(input,"profileId");JSONObject selected=profile(profileId);
        JSONObject result=request(input,"/v1/jobs",null,15);JSONArray jobs=result.optJSONArray("jobs");
        if(jobs!=null)for(int i=0;i<jobs.length();i++){JSONObject job=jobs.optJSONObject(i);if(job!=null){job.put("profileId",profileId).put("serviceBase",result.getString("serviceBase"));acceptReceipt(job);}}
        JSONArray pending=new JSONArray();
        synchronized(this){for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(profileId.equals(op.optString("profileId"))&&endpoint(selected.getString("baseUrl")).equals(op.optString("serviceBase"))&&(op.optString("status").equals("unknown")||op.optString("status").equals("submitting")||op.optString("status").equals("pending")))pending.put(op.optString("operationId"));}}
        JSONArray reconciled=new JSONArray();for(int i=0;i<pending.length();i++){JSONObject request=new JSONObject().put("profileId",profileId).put("operationId",pending.getString(i));JSONObject op=operation(pending.getString(i));reconciled.put(op!=null&&"job_cancel".equals(op.optString("kind"))?reconcileCancellation(op):op!=null&&"file_write".equals(op.optString("kind"))?reconcileFile(request):reconcile(request));}
        return result.put("reconciledOperations",reconciled).put("resubmitted",false);
    }
    private synchronized void recordOperation(JSONObject intent)throws Exception {
        boolean found=false;for(int i=0;i<operations.length();i++)if(operations.getJSONObject(i).optString("operationId").equals(intent.optString("operationId"))){operations.put(i,new JSONObject(intent.toString()));found=true;break;}
        if(!found){if(operations.length()>=100){int removable=-1;for(int i=0;i<operations.length();i++){String state=operations.getJSONObject(i).optString("status");if(state.equals("settled")||state.equals("rejected")){removable=i;break;}}if(removable<0)throw new IllegalStateException("待核对的电脑操作记录已达上限，请先核对原操作");operations.remove(removable);}operations.put(new JSONObject(intent.toString()));}
        write(operationFile,operations);
    }
    /** Native private Settings workflow only; never exposed by EngineModule.handle. */
    public static final class ConnectionBackupReview {
        private final RemoteModule issuer;private final String section,action;private final dev.pocketengine.core.RunControl original;private final long expires;private ConnectionBackupAdmission admission;
        private ConnectionBackupReview(RemoteModule issuer,JSONObject section,String action,dev.pocketengine.core.RunControl original){this.issuer=issuer;this.section=section.toString();this.action=action;this.original=original;expires=System.currentTimeMillis()+600_000L;}
        public JSONObject metadata()throws Exception {current();return dev.pocketengine.features.ConnectionBackupSections.reviewMetadata("remote",action,new JSONObject(section));}
        private void current()throws Exception {original.check();if(issuer.closed||System.currentTimeMillis()>expires)throw new SecurityException("原私人设置恢复审阅已关闭或到期");}
        public synchronized ConnectionBackupAdmission confirm()throws Exception {current();if(admission==null)admission=new ConnectionBackupAdmission(this);return admission;}
    }
    public static final class ConnectionBackupAdmission {
        private final ConnectionBackupReview review;private boolean attempted;
        private ConnectionBackupAdmission(ConnectionBackupReview review){this.review=review;}
        public JSONObject metadata()throws Exception{return review.metadata();}
    }
    public JSONObject exportConnectionBackupForOwner(dev.pocketengine.core.RunControl original)throws Exception {
        java.util.Objects.requireNonNull(original).check();JSONArray rows; synchronized(this){if(closed)throw new SecurityException("原连接模块已关闭");rows=connectionBackupRows();}original.check();return dev.pocketengine.features.ConnectionBackupSections.section("remote",rows);
    }
    public ConnectionBackupReview reviewConnectionBackupForOwner(JSONObject exactSection,String ownerActionId,dev.pocketengine.core.RunControl original)throws Exception {
        java.util.Objects.requireNonNull(original).check();if(closed)throw new SecurityException("原连接模块已关闭");JSONObject section=dev.pocketengine.features.ConnectionBackupSections.checked("remote",exactSection);ConnectionBackupReview review=new ConnectionBackupReview(this,section,dev.pocketengine.features.ConnectionBackupSections.action(ownerActionId),original);review.current();return review;
    }
    /** Cold unknown recovery: original action/digest/id lookup only, never mint an admission or write. */
    public JSONObject queryConnectionBackupForOwner(JSONObject exactSection,String originalOwnerActionId,dev.pocketengine.core.RunControl original)throws Exception {
        java.util.Objects.requireNonNull(original).check();JSONObject section=dev.pocketengine.features.ConnectionBackupSections.checked("remote",exactSection);String action=dev.pocketengine.features.ConnectionBackupSections.action(originalOwnerActionId),digest=dev.pocketengine.features.ConnectionBackupSections.digest(section);JSONArray rows=section.getJSONArray("profiles"),results=new JSONArray();
        for(int n=0;n<rows.length();n++){original.check();JSONObject row=rows.getJSONObject(n);String id=dev.pocketengine.features.ConnectionBackupSections.restoredId("remote",action,digest,row.getString("sourceProfileId"));String actual="unconfirmed";try{synchronized(this){if(closed)throw new SecurityException("原连接模块已关闭");original.check();actual=restoreConnectionBackupRow(row,id,action,digest,true);}}catch(Exception unconfirmed){original.check();}results.put(new JSONObject().put("originalProfileId",row.getString("sourceProfileId")).put("newProfileId",id).put("state",actual.equals("already_restored")?"found":"unknown").put("observation",actual));}
        original.check();return new JSONObject().put("module","remote").put("ownerActionId",action).put("sectionDigest",digest).put("profiles",results).put("queryOnly",true).put("configurationWritten",false).put("automaticConnection",false);
    }
    public JSONObject restoreConnectionBackupForOwner(ConnectionBackupAdmission admission,dev.pocketengine.core.RunControl original)throws Exception {
        if(admission==null||admission.review.issuer!=this||admission.review.original!=original)throw new SecurityException("没有此模块/原私人设置操作的native恢复批准");ConnectionBackupReview review=admission.review;review.current();boolean queryOnly;synchronized(admission){queryOnly=admission.attempted;admission.attempted=true;}
        JSONObject section=new JSONObject(review.section);String digest=dev.pocketengine.features.ConnectionBackupSections.digest(section);JSONArray rows=section.getJSONArray("profiles"),results=new JSONArray();boolean stopped=false;
        for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);String old=row.getString("sourceProfileId"),id=dev.pocketengine.features.ConnectionBackupSections.restoredId("remote",review.action,digest,old);JSONObject receipt=new JSONObject().put("originalProfileId",old).put("newProfileId",id);
            if(stopped){results.put(receipt.put("state","not_attempted"));continue;}
            review.current();try{synchronized(this){review.current();receipt.put("state",restoreConnectionBackupRow(row,id,review.action,digest,queryOnly));}}catch(Exception uncertain){receipt.put("state","unknown").put("reason","原配置提交未确认；仅核对这次固定编号，不重建或自动连接");stopped=true;}results.put(receipt);
        }
        return new JSONObject().put("format","pocketworld.native-connection-restore-receipt.v1").put("module","remote").put("ownerActionId",review.action).put("sectionDigest",digest).put("queryOnly",queryOnly).put("profiles",results).put("credentialsRestored",false).put("hostKeyTrustRestored",false).put("executionGrantsRestored",false).put("automaticConnection",false);
    }

    private JSONArray connectionBackupRows()throws Exception {
        JSONArray rows=new JSONArray();for(int n=0;n<profiles.length();n++){JSONObject profile=profiles.getJSONObject(n);rows.put(new JSONObject().put("sourceProfileId",profile.getString("id")).put("name",profile.getString("name")).put("baseUrl",profile.getString("baseUrl")));}return rows;
    }
    private JSONObject backupConfiguration(JSONObject row,String id)throws Exception {return new JSONObject().put("id",id).put("name",row.getString("name")).put("baseUrl",row.getString("baseUrl"));}
    private String restoreConnectionBackupRow(JSONObject row,String id,String action,String sectionDigest,boolean queryOnly)throws Exception {
        JSONObject desired=backupConfiguration(row,id),origin=dev.pocketengine.features.ConnectionBackupSections.origin(action,sectionDigest,row.getString("sourceProfileId"),dev.pocketengine.features.ConnectionBackupSections.digest(desired));
        // Refresh only from the original AtomicFile; an unreadable disk is not an empty catalog.
        JSONArray actual;try{actual=new JSONArray(new String(file.readFully(),StandardCharsets.UTF_8));}catch(java.io.FileNotFoundException missing){if(file.getBaseFile().exists()||profiles.length()!=0)throw missing;actual=new JSONArray();}
        JSONObject known=null;for(int n=0;n<actual.length();n++)if(id.equals(actual.getJSONObject(n).optString("id")))known=actual.getJSONObject(n);
        if(known!=null){JSONObject original=known.optJSONObject("nativeBackupOrigin");if(original==null||!dev.pocketengine.features.ConnectionBackupSections.digest(origin).equals(dev.pocketengine.features.ConnectionBackupSections.digest(original))||!dev.pocketengine.features.ConnectionBackupSections.digest(desired).equals(dev.pocketengine.features.ConnectionBackupSections.digest(backupConfiguration(known,id))))return "configuration_changed_not_overwritten";if(!queryOnly)publishConnectionBackupProfiles(actual);return "already_restored";}
        if(queryOnly)return "not_confirmed_query_only";if(actual.length()>=20)throw new IllegalStateException("原电脑连接已满");if(context.secrets.contains(slot(id,row.getString("baseUrl"))))throw new SecurityException("固定新编号已有残留凭据，未重用");
        JSONObject restored=new JSONObject(desired.toString()).put("bindingRevision",UUID.randomUUID().toString()).put("nativeBackupOrigin",origin);JSONArray proposed=new JSONArray(actual.toString());proposed.put(restored);byte[] expected=proposed.toString().getBytes(StandardCharsets.UTF_8);
        try{write(file,proposed);}catch(Exception failed){if(!connectionBackupStoredEquals(expected))throw failed;}if(!connectionBackupStoredEquals(expected))throw new java.io.IOException("原电脑配置没有准确读回回执");
        publishConnectionBackupProfiles(proposed);return "restored_needs_credentials";
    }
    private void publishConnectionBackupProfiles(JSONArray durable)throws Exception {while(profiles.length()>0)profiles.remove(profiles.length()-1);for(int n=0;n<durable.length();n++)profiles.put(durable.getJSONObject(n));}
    private boolean connectionBackupStoredEquals(byte[] expected){try{return java.util.Arrays.equals(expected,file.readFully());}catch(Exception unreadable){return false;}}

    private synchronized JSONObject saveProfile(JSONObject input)throws Exception {
        String name=required(input,"name",80),base=required(input,"baseUrl",2048);RemoteTransport.validateBase(base);
        String id=input.optString("id");if(id.isEmpty())id=UUID.randomUUID().toString();if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("电脑连接编号无效");
        JSONObject old=find(id);if(old==null&&profiles.length()>=20)throw new IllegalStateException("最多保存20台电脑连接");
        String token=input.optString("token").trim();
        if(!token.isEmpty()&&(token.length()<32||token.length()>4096||token.contains("\r")||token.contains("\n")))throw new IllegalArgumentException("电脑令牌需为32–4096个字符");
        JSONObject profile=new JSONObject().put("id",id).put("name",name).put("baseUrl",base).put("bindingRevision",UUID.randomUUID().toString());
        if(old==null)profiles.put(profile);else for(int i=0;i<profiles.length();i++)if(profiles.getJSONObject(i).optString("id").equals(id))profiles.put(i,profile);
        // Invalidate reviews durably before touching the existing vault. A failed key save
        // may leave configuration incomplete, but cannot retain an old review over new credentials.
        save();
        if(input.optBoolean("clearToken",false))context.secrets.remove(slot(id,base));
        else if(!token.isEmpty())context.secrets.put(slot(id,base),token);
        else if(old==null||!RemoteTransport.sameOrigin(old.optString("baseUrl"),base))context.secrets.remove(slot(id,base));
        if(old!=null&&!endpoint(old.optString("baseUrl")).equals(endpoint(base))){final String changedId=id;watches.entrySet().removeIf(entry->entry.getValue().profileId.equals(changedId));}if(old!=null&&!RemoteTransport.sameOrigin(old.optString("baseUrl"),base))context.secrets.remove(slot(id,old.optString("baseUrl")));return publicProfile(profile);
    }
    private synchronized JSONObject removeProfile(String id)throws Exception {
        identifier(new JSONObject().put("id",id),"id");
        JSONObject old=find(id);
        for(int i=profiles.length()-1;i>=0;i--)if(profiles.getJSONObject(i).optString("id").equals(id))profiles.remove(i);
        save();if(old!=null)context.secrets.remove(slot(id,old.optString("baseUrl")));watches.entrySet().removeIf(entry->entry.getValue().profileId.equals(id));recent.entrySet().removeIf(entry->id.equals(entry.getValue().optString("profileId")));
        return new JSONObject().put("removed",true).put("message","连接已移除；已提交到电脑的任务请在电脑上检查");
    }
    private synchronized JSONObject profile(String id)throws Exception {JSONObject found=find(id);if(found==null)throw new IllegalArgumentException("电脑连接不存在");return new JSONObject(found.toString());}
    private JSONObject find(String id)throws Exception {for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);if(id.equals(p.optString("id")))return p;}return null;}
    private JSONObject request(JSONObject input,String path,JSONObject body,int timeout)throws Exception {return request(input,path,body,timeout,null);}
    private JSONObject request(JSONObject input,String path,JSONObject body,int timeout,dev.pocketengine.core.RunControl original)throws Exception {
        BoundRequest target;synchronized(this){String id=identifier(input,"profileId");if(input.has("_profileBinding"))requireProfileRevision(id,required(input,"_profileBinding",64));target=bind(id,null);}return send(target,path,body,timeout,original);
    }
    /** Native owner workflow only: not exposed by handle/model tools. One persisted exact operation. */
    public JSONObject captureResultSnapshot(JSONObject input)throws Exception {return captureResultSnapshot(input,()->{});}
    public JSONObject captureResultSnapshot(JSONObject input,AssignmentDispatch authority)throws Exception {
        String profile=identifier(input,"profileId"),job=identifier(input,"jobId"),operation=identifier(input,"operationId");
        JSONObject body=new JSONObject().put("operationId",operation).put("jobOperationId",identifier(input,"jobOperationId")).put("environmentId",identifier(input,"environmentId")).put("worker",required(input,"worker",80)).put("paths",RemoteResultSnapshots.paths(input.getJSONArray("paths")));
        if(input.has("expectedFiles"))body.put("expectedFiles",RemoteResultSnapshots.expectedFiles(input.getJSONArray("expectedFiles"),body.getJSONArray("paths")));
        for(String key:new String[]{"threadId","turnId"})if(input.has(key))body.put(key,input.get(key));
        JSONObject details=new JSONObject().put("profileBinding",required(input,"_profileBinding",64)).put("sourceServiceBase",required(input,"serviceBase",2048)).put("jobId",job).put("jobOperationId",body.getString("jobOperationId"));
        Reservation reserved=reserve(profile,operation,"result_snapshot",body,details);if(reserved.known)return reconcileResultSnapshot(input);
        try {JSONObject result=assignmentSend(input,reserved.target,"/v1/jobs/"+job+"/snapshots",body,authority).put("profileId",profile);RemoteResultSnapshots.metadata(result,input,operation,body.getJSONArray("paths"));RemoteResultSnapshots.requireExpected(result,body.optJSONArray("expectedFiles"));reserved.intent.put("status","settled");recordOperation(reserved.intent);return result;}
        catch(RemoteTransport.HttpFailure rejected){if(rejected.status>=400&&rejected.status<500){reserved.intent.put("status","rejected").put("message",rejected.getMessage());recordOperation(reserved.intent);return snapshotNotice(input,"rejected",rejected.getMessage());}return snapshotNotice(input,"unknown","快照接收尚未确认，只查询原操作，不重新采集");}
        catch(java.io.IOException unavailable){return snapshotNotice(input,"unknown","快照接收尚未确认，只查询原操作，不重新采集");}
    }
    public JSONObject reconcileResultSnapshot(JSONObject input)throws Exception {
        String operation=identifier(input,"operationId");JSONObject old=operation(operation);if(old!=null&&(!"result_snapshot".equals(old.optString("kind"))||!input.getString("jobId").equals(old.optString("jobId"))))throw new SecurityException("原快照操作属于另一类型或job");
        if(old!=null&&old.optString("status").equals("rejected"))return snapshotNotice(input,"rejected",old.optString("message","原采集已被执行端拒绝，没有重试"));
        try {JSONObject result=snapshotRead(input,"/v1/jobs/"+identifier(input,"jobId")+"/snapshot-operations/"+operation);if(old!=null){old.put("status",result.optString("status").equals("completed")?"settled":"unknown");recordOperation(old);}return result;}
        catch(java.io.IOException unavailable){return snapshotNotice(input,"unknown","未取得原快照回执；未重新采集");}
    }
    public JSONObject readResultSnapshot(JSONObject input)throws Exception {
        String version=required(input,"snapshotVersion",64);if(!version.matches("[a-f0-9]{64}"))throw new SecurityException("原快照版本无效");
        String path="/v1/jobs/"+identifier(input,"jobId")+"/snapshots/"+identifier(input,"snapshotId")+"?version="+version;
        if(input.has("path"))path+="&path="+URLEncoder.encode(required(input,"path",240),"UTF-8");
        if(input.optBoolean("verifyCurrent"))path+="&current=true";
        return snapshotRead(input,path);
    }
    /** Native original-task directory read, intentionally absent from the generic/model handle. */
    public JSONObject readResultDirectory(JSONObject input,String relative,int offset,String directoryVersion)throws Exception {
        if(relative==null||relative.length()>2048||offset<0||offset>=10000)throw new IllegalArgumentException("请选择有界工作区目录");
        if(!relative.isEmpty())RemoteResultSnapshots.paths(new JSONArray().put(relative+"/checked-file"));
        String path="/v1/jobs/"+identifier(input,"jobId")+"/result-files?path="+URLEncoder.encode(relative,"UTF-8")+"&offset="+offset;
        if(directoryVersion!=null&&!directoryVersion.isEmpty()){
            if(!directoryVersion.matches("[a-f0-9]{64}"))throw new SecurityException("目录版本无效");
            path+="&directoryVersion="+directoryVersion;
        }else if(offset>0)throw new SecurityException("下一页必须沿原目录版本");
        JSONObject actual=snapshotRead(input,path);
        for(String key:new String[]{"jobId","jobOperationId","environmentId","worker","workspaceIdentity"})if(!input.getString(key).equals(actual.optString(key)))throw new SecurityException("目录回执不是原job/物理工作区");
        if(!relative.equals(actual.optString("path"))||offset!=actual.getInt("offset")||!actual.optString("directoryVersion").matches("[a-f0-9]{64}")||actual.optBoolean("recursive")||actual.optBoolean("fileContentsRead")||actual.opt("authorshipVerified")!=Boolean.FALSE||actual.opt("executionAuthorized")!=Boolean.FALSE)throw new SecurityException("目录缺少有界只读来源");
        JSONArray entries=actual.getJSONArray("files");if(entries.length()>100)throw new SecurityException("目录页超过100项");
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i);String name=entry.getString("name"),child=relative.isEmpty()?name:relative+"/"+name;
            if(name.contains("/")||name.contains("\\")||!child.equals(entry.getString("path")))throw new SecurityException("目录项越过原路径");
            RemoteResultSnapshots.paths(new JSONArray().put(child));
            if(entry.optBoolean("selectable")&&(!entry.optString("kind").equals("file")||entry.getLong("bytes")<0||entry.getLong("bytes")>262144))throw new SecurityException("目录项不能作为有界成果");
        }
        return actual;
    }
    public dev.pocketengine.features.attachments.AttachmentBook resultAttachmentBook(){
        return dev.pocketengine.features.attachments.AttachmentRepository.get(context.android).book;
    }
    private JSONObject inputScope(dev.pocketengine.store.TaskStore.RemoteInputClaim claim)throws Exception {
        claim.checkCurrent();JSONObject view=claim.view(),scope=view.getJSONObject("scope");
        if(!view.optString("jobId").isEmpty())scope.put("jobId",view.getString("jobId"));return scope;
    }
    private static JSONObject inputTarget(JSONObject scope)throws Exception {
        return new JSONObject().put("profileId",scope.getString("profileId")).put("serviceBase",scope.getString("serviceBase")).put("_profileBinding",scope.getString("profileGeneration"));
    }
    /** These three effects require the original TaskStore's non-restorable opaque claim. */
    /** Native group input review reads the actual CLI mode, not accountConfigured. No model turn. */
    public JSONObject probeGroupInputApiMode(JSONObject selected)throws Exception {
        requireProfileBinding(selected.getString("profileId"),selected.getString("_profileBinding"));JSONObject body=new JSONObject().put("environmentId",selected.getString("environmentId")).put("environmentBinding",selected.getString("environmentBinding")).put("worker",selected.getString("worker")).put("inferenceAudience","EXTERNAL_GROUP");if(selected.has("model"))body.put("model",selected.getString("model"));JSONObject actual=request(selected,"/v1/workers/probe",body,15);requireProfileBinding(selected.getString("profileId"),selected.getString("_profileBinding"));if(!actual.optBoolean("available")||!actual.optBoolean("protocolVerified")||actual.optJSONObject("externalGroupApiMode")==null)throw new SecurityException("此原app-server未确认API-key模式/凭据代次，未派发群资料；普通确认不能替代此证明");RemoteTaskRequest.requireGroupApiMode(actual.getJSONObject("externalGroupApiMode"),selected);return actual;
    }
    public JSONObject submitInputJob(dev.pocketengine.store.TaskStore.RemoteInputClaim claim)throws Exception {
        JSONObject scope=inputScope(claim);
        if(!claim.view().getString("action").equals("CREATE"))throw new SecurityException("不是原附件创建领取");
        if(!claim.canDispatch())return queryInputJob(scope,scope.optString("jobId"));
        JSONObject selected=scope.getJSONObject("request"),body=new JSONObject();
        for(String key:new String[]{"environmentId","environmentBinding","worker","prompt","model","readOnly","timeoutSeconds","inputContinuationProtocol","threadId","resumeJobId","sourceJobOperationId","recoveryProofId","recoveryOwnerAcknowledged","inferenceAudience","groupInputSource","externalGroupApiMode"})if(selected.has(key))body.put(key,selected.get(key));
        body.put("operationId",scope.getString("jobOperationId")).put("inputProtocol",RemoteTaskRequest.INPUT_PROTOCOL).put("inputFiles",RemoteTaskRequest.inputManifest(scope.getJSONArray("files")));
        return inputSend(claim,scope,"/v1/jobs",body,"job_submit",scope.getString("jobOperationId"));
    }
    public JSONObject startInputJob(dev.pocketengine.store.TaskStore.RemoteInputClaim claim)throws Exception {
        JSONObject scope=inputScope(claim);String operation=claim.view().getString("operationId"),job=scope.getString("jobId");
        if(!claim.view().getString("action").equals("START"))throw new SecurityException("不是原worker启动领取");
        if(!claim.canDispatch())return queryInputJob(scope,job);
        JSONObject body=new JSONObject().put("jobOperationId",scope.getString("jobOperationId")).put("startOperationId",operation).put("manifestHash",scope.getString("manifestHash"));
        return inputSend(claim,scope,"/v1/jobs/"+job+"/start-with-inputs",body,"input_start",operation);
    }
    private JSONObject inputSend(dev.pocketengine.store.TaskStore.RemoteInputClaim claim,JSONObject scope,String path,JSONObject body,String kind,String operation)throws Exception {
        dispatchGate.beforeDispatch(path); // a late queued native call must still prove the original live approval
        JSONObject targetInput=inputTarget(scope);assignmentTarget(targetInput);claim.beforeDispatch();
        JSONObject details=new JSONObject().put("profileBinding",scope.getString("profileGeneration")).put("sourceServiceBase",scope.getString("serviceBase"));
        if(scope.has("jobId"))details.put("jobId",scope.getString("jobId"));
        if(scope.has("continuationSource"))details.put("jobOperationId",scope.getJSONObject("continuationSource").getString("jobOperationId"));
        Reservation reserved=reserve(scope.getString("profileId"),operation,kind,body,details);
        if(reserved.known)return queryInputJob(scope,scope.optString("jobId"));
        try{
            assignmentTarget(targetInput);claim.checkCurrent();
            JSONObject actual=reserved.target.request(path,body,30).put("profileId",scope.getString("profileId"));
            RemoteTaskRequest.inputReceipt(actual,scope);reserved.intent.put("status",kind.equals("job_submit")?"accepted":"settled").put("jobId",actual.getString("id"));recordOperation(reserved.intent);
            acceptReceipt(actual);return actual;
        }catch(RemoteTransport.HttpFailure rejected){
            if(rejected.status>=400&&rejected.status<500){reserved.intent.put("status","rejected").put("message",rejected.getMessage());recordOperation(reserved.intent);return inputNotice(scope,"rejected",rejected.getMessage());}
            return inputNotice(scope,"unknown","原附件请求回执未确认，只查询原编号，不重发");
        }catch(java.io.IOException lost){return inputNotice(scope,"unknown","原附件请求回执未确认，只查询原编号，不重发");}
    }
    public JSONObject uploadInputFile(dev.pocketengine.store.TaskStore.RemoteInputClaim claim,java.io.InputStream input,
                                      dev.pocketengine.core.RunControl control,java.util.function.LongConsumer written)throws Exception {
        JSONObject scope=inputScope(claim),view=claim.view();int index=view.getInt("index");
        if(!view.getString("action").equals("UPLOAD")||!claim.canDispatch())throw new SecurityException("原文件已领取或未知，只查询原job，不能再发送body");
        JSONObject file=scope.getJSONArray("files").getJSONObject(index),targetInput=inputTarget(scope);
        BoundRequest target=assignmentTarget(targetInput);String job=scope.getString("jobId"),path="/v1/jobs/"+job+"/inputs/"+index;
        JSONObject actual=RemoteTransport.uploadInput(target.base,target.token,path,scope.getString("jobOperationId"),input,file.getLong("bytes"),control,()->{
            dispatchGate.beforeDispatch(path);assignmentTarget(targetInput);claim.beforeDispatch();assignmentTarget(targetInput);
        },written).put("serviceBase",target.serviceBase).put("profileId",scope.getString("profileId"));
        RemoteTaskRequest.inputReceipt(actual,scope);acceptReceipt(actual);return actual;
    }
    /** Original GET only; never interprets not_found/unknown as permission for another body/create. */
    public JSONObject queryInputJob(JSONObject originalScope,String job)throws Exception {
        JSONObject input=inputTarget(originalScope).put("jobOperationId",originalScope.getString("jobOperationId"));
        JSONObject actual=job==null||job.isEmpty()?reconcile(input.put("operationId",originalScope.getString("jobOperationId"))):handle("getJob",input.put("jobId",job));
        if(!actual.optString("status").matches("not_found|unknown|rejected")||actual.has("inputState"))RemoteTaskRequest.inputReceipt(actual,originalScope);
        return actual;
    }
    private static JSONObject inputNotice(JSONObject scope,String status,String message)throws Exception {
        JSONObject result=new JSONObject().put("operationId",scope.getString("jobOperationId")).put("status",status).put("message",message).put("resubmitted",false);
        for(String key:new String[]{"profileId","serviceBase"})result.put(key,scope.get(key));
        if(scope.has("jobId"))result.put("id",scope.get("jobId"));
        return result;
    }
    /** Native assignment host only. Model handle() deliberately has no selected-copy submit route. */
    /** Native owner only. These methods are intentionally absent from EngineModule.handle/model tools. */
    /** Paired native gateway transport only. Deliberately absent from handle()/model tools. */
    public dev.pocketengine.features.proxy.NativeHarnessGatewayBridge.Transport harnessGatewayTransport(JSONObject nativeTarget)throws Exception {
        JSONObject bound=new JSONObject(nativeTarget.toString());String profile=identifier(bound,"profileId");requireProfileRevision(profile,required(bound,"_profileBinding",64));required(bound,"serviceBase",2048);
        return (path,body)->{if(!path.matches("/v1/gateway/(?:leases(?:/[A-Za-z0-9_-]{1,80}(?:/(?:requests|cancel))?)?|requests/[A-Za-z0-9_-]{1,80}(?:/(?:body|claim|frames|failure))?)(?:\\?.*)?"))throw new SecurityException("不是原gateway协议路径");return harnessReadWrite(bound,path,body);};
    }
    public JSONObject harnessConfigProbe(JSONObject input)throws Exception {return harnessReadWrite(input,"/v1/harnesses/probe",input.getJSONObject("request"));}
    public JSONObject harnessConfigPlan(JSONObject input)throws Exception {
        JSONObject request=new JSONObject(input.getJSONObject("request").toString());String path="/v1/harness-config/plans";
        if(input.has("restoreOf")){path="/v1/harness-config/operations/"+identifier(input,"restoreOf")+"/restore-plan";JSONObject reduced=new JSONObject();for(String key:new String[]{"operationId","environmentId","environmentBinding","bindingId","identityDigest"})reduced.put(key,request.get(key));request=reduced;}
        return harnessReadWrite(input,path,request);
    }
    public JSONObject harnessConfigOperation(JSONObject input)throws Exception {return harnessReadWrite(input,"/v1/harness-config/operations/"+identifier(input,"operationId"),null);}
    private JSONObject harnessReadWrite(JSONObject input,String path,JSONObject body)throws Exception {
        BoundRequest target;synchronized(this){String profile=identifier(input,"profileId");requireProfileRevision(profile,required(input,"_profileBinding",64));target=bind(profile,required(input,"serviceBase",2048));}
        JSONObject actual=send(target,path,body,20);synchronized(this){requireProfileRevision(input.getString("profileId"),input.getString("_profileBinding"));}return actual;
    }
    public JSONObject harnessConfigApply(JSONObject input,AssignmentDispatch originalOwnerFence)throws Exception {
        JSONObject body=new JSONObject(input.getJSONObject("request").toString());String operation=identifier(body,"operationId"),profile=identifier(input,"profileId"),plan=identifier(input,"planId");
        JSONObject details=new JSONObject().put("profileBinding",required(input,"_profileBinding",64)).put("sourceServiceBase",required(input,"serviceBase",2048)).put("planId",plan);
        Reservation reserved=reserve(profile,operation,"harness_config",body,details);if(reserved.known)return harnessConfigOperation(new JSONObject(input.toString()).put("operationId",operation));
        JSONObject actual;try{actual=assignmentSend(input,reserved.target,"/v1/harness-config/plans/"+plan+"/apply",body,originalOwnerFence);}
        catch(RemoteTransport.HttpFailure rejected){if(rejected.status>=400&&rejected.status<500){actual=harnessNotice(body,"CONFLICT").put("rejected",true).put("httpStatus",rejected.status);reserved.intent.put("status","rejected");recordOperation(reserved.intent);return actual;}return harnessNotice(body,"unknown");}
        catch(java.io.IOException unavailable){return harnessNotice(body,"unknown");}
        if(!operation.equals(actual.optString("operationId"))||!body.getString("planDigest").equals(actual.optString("planDigest")))return harnessNotice(body,"unknown");
        reserved.intent.put("status",actual.optString("state").matches("COMMITTED|CONFLICT")?"settled":"unknown");recordOperation(reserved.intent);return actual;
    }
    private static JSONObject harnessNotice(JSONObject request,String state)throws Exception {JSONObject value=new JSONObject().put("state",state).put("configApplied",false).put("runtimeVerified",false).put("resubmitted",false);for(String key:new String[]{"operationId","planDigest","environmentId","environmentBinding","bindingId"})value.put(key,request.get(key));return value;}
    /** Same original JobManager/operation reservation. No model/generic JSON route issues this claim. */
    public JSONObject submitGatewayContinuation(dev.pocketengine.store.TaskStore.GatewayContinuationDispatch claim)throws Exception {if(claim==null||!claim.issuedBy(this))throw new SecurityException("不是原任务本轮native最终claim");return submitJob(claim.payload(),claim::beforeDispatch);}
    private JSONObject submitJob(JSONObject input,AssignmentDispatch nativeGatewayFence)throws Exception {
        if(nativeGatewayFence==null&&(input.has("harnessConfigOperationId")||input.has("harnessOwnerBinding")))throw new SecurityException("Harness覆盖只接受原生本轮claim，不从通用JSON恢复");

                if(input.has("inputFiles")||input.has("inputProtocol"))throw new SecurityException("私人附件只能经原生精确外传确认，不接受通用JSON提交或静默丢附件");
                if(input.has("projectHistory")||input.has("forkSource"))throw new SecurityException("项目基线/会话分支须经原生单次审阅；通用JSON不会静默丢范围再创建工作");
                JSONObject body=new JSONObject().put("environmentId",required(input,"environmentId",80)).put("worker",input.optString("worker","command")).put("timeoutSeconds",input.optInt("timeoutSeconds",300));
                String operation=input.optString("operationId");if(operation.isEmpty())operation=UUID.randomUUID().toString();
                identifier(new JSONObject().put("operationId",operation),"operationId");
                body.put("operationId",operation);
                if(input.has("environmentBinding")){String binding=required(input,"environmentBinding",64);if(!binding.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("环境绑定无效");body.put("environmentBinding",binding);}
                if(input.has("argv"))body.put("argv",new JSONArray(input.getJSONArray("argv").toString()));
                if(input.has("prompt"))body.put("prompt",required(input,"prompt",12000));
                if(input.has("threadId"))body.put("threadId",required(input,"threadId",200));
                if(input.has("resumeJobId"))body.put("resumeJobId",identifier(input,"resumeJobId"));
                if(input.has("model"))body.put("model",required(input,"model",200));
                if(input.has("effort"))body.put("effort",required(input,"effort",80));
                if(input.has("modelOptionsDigest"))body.put("modelOptionsDigest",required(input,"modelOptionsDigest",64));
                if(input.has("teamReadOnlyTools")){if(!Boolean.TRUE.equals(input.opt("teamReadOnlyTools"))||!"claude".equals(body.getString("worker")))throw new SecurityException("团队工具限制仅支持原Claude协议");body.put("teamReadOnlyTools",true);}
                if(input.has("readOnly")){if(!(input.opt("readOnly") instanceof Boolean))throw new IllegalArgumentException("readOnly需为boolean");body.put("readOnly",input.getBoolean("readOnly"));}
                if(input.has("recoveryProofId")||input.has("recoveryOwnerAcknowledged")){
                    String proof=required(input,"recoveryProofId",36);if(!proof.matches("[a-f0-9-]{36}")||input.opt("recoveryOwnerAcknowledged")!=Boolean.TRUE)throw new SecurityException("冷恢复必须是原proof的明确新一次主人授权");
                    body.put("recoveryProofId",proof).put("recoveryOwnerAcknowledged",true);
                }
                if(input.has("resultSnapshotId")||input.has("resultSnapshotVersion")){
                    if(!body.has("resumeJobId"))throw new SecurityException("成果版本只可绑定原job明确续接");
                    body.put("resultSnapshotId",identifier(input,"resultSnapshotId")).put("resultSnapshotVersion",required(input,"resultSnapshotVersion",64));
                }
                if(nativeGatewayFence!=null){body.put("harnessConfigOperationId",identifier(input,"harnessConfigOperationId")).put("harnessOwnerBinding",new JSONObject(input.getJSONObject("harnessOwnerBinding").toString()));}
                String profileId=required(input,"profileId",80);
                JSONObject details=new JSONObject();
                if(input.has("_profileBinding"))details.put("profileBinding",required(input,"_profileBinding",64));
                if(body.has("threadId")||body.has("resumeJobId")){
                    // Native owner continuation, not import-by-thread or an automatic retry.
                    if(!body.has("threadId")||!body.has("resumeJobId"))throw new IllegalArgumentException("续接需同时绑定原threadId和resumeJobId");
                    details.put("jobOperationId",identifier(input,"jobOperationId")).put("sourceServiceBase",required(input,"serviceBase",2048));
                }
                Reservation reserved=reserve(profileId,operation,"job_submit",body,details);
                if(reserved.known)return reconcile(new JSONObject().put("profileId",profileId).put("operationId",operation));
                JSONObject intent=reserved.intent;
                JSONObject result;
                try{result=nativeGatewayFence==null?send(reserved.target,"/v1/jobs",body,20):assignmentSend(input,reserved.target,"/v1/jobs",body,nativeGatewayFence);}
                catch(RemoteTransport.HttpFailure rejected){
                    if(rejected.status>=400&&rejected.status<500){intent.put("status","rejected").put("message",rejected.getMessage());recordOperation(intent);throw rejected;}
                    return unknownOperation(intent);
                }
                catch(java.io.IOException disconnected){return unknownOperation(intent);}
                if(!result.optString("id").matches("[A-Za-z0-9_-]{1,80}")||!operation.equals(result.optString("operationId"))
                        ||!body.getString("environmentId").equals(result.optString("environmentId"))||!body.getString("worker").equals(result.optString("worker"))
                        ||body.has("resumeJobId")&&(!body.getString("resumeJobId").equals(result.optString("resumeJobId"))||!body.getString("threadId").equals(result.optString("threadId")))
                        ||body.has("resultSnapshotId")&&(!body.getString("resultSnapshotId").equals(result.optString("resultSnapshotId"))||!body.getString("resultSnapshotVersion").equals(result.optString("resultSnapshotVersion"))))return unknownOperation(intent);
                intent.put("status","accepted").put("jobId",result.getString("id"));recordOperation(intent);
                result.put("profileId",profileId);acceptReceipt(result);return result;
                }
    public interface AssignmentDispatch { void beforeDispatch()throws Exception; }
    /** Native original-owner observation only. Never registered as a mutating model command. */
    public RemoteJobEvents.Page readOriginalJobEvents(JSONObject source,String binding,JSONObject previous,AssignmentDispatch pageFence)throws Exception {
        JSONObject input=new JSONObject(source.toString()).put("_profileBinding",binding),scope=RemoteJobEvents.scope(source,binding),cursor=RemoteJobEvents.request(previous,scope);
        BoundRequest target=assignmentTarget(input);String job=identifier(input,"jobId");
        String path="/v1/jobs/"+job+"/events?filter="+RemoteJobEvents.FILTER+"&jobOperationId="+java.net.URLEncoder.encode(input.getString("jobOperationId"),"UTF-8")
                +"&generation="+cursor.getString("generation")+"&after="+cursor.getLong("after")+"&head="+cursor.getLong("head")+"&limit=32";
        pageFence.beforeDispatch();assignmentTarget(input);JSONObject actual=send(target,path,null,15);pageFence.beforeDispatch();assignmentTarget(input);
        return RemoteJobEvents.received(scope,cursor,actual);
    }
    /** Native original-owner input only. It is deliberately absent from EngineModule.handle/model commands. */
    public JSONObject answerQuestionJob(JSONObject input,AssignmentDispatch originalOwnerFence)throws Exception {
        BoundRequest target=assignmentTarget(input);String job=identifier(input,"jobId");
        JSONObject body=new JSONObject().put("answerId",required(input,"answerId",80)).put("jobOperationId",required(input,"jobOperationId",80)).put("requestId",required(input,"requestId",200)).put("requestHash",required(input,"requestHash",64)).put("answers",new JSONObject(input.getJSONObject("answers").toString()));
        JSONObject actual=assignmentSend(input,target,"/v1/jobs/"+job+"/question-answer",body,originalOwnerFence);
        if(!job.equals(actual.optString("id"))||!input.getString("jobOperationId").equals(actual.optString("operationId")))throw new SecurityException("正常回答回执不是原job/operation");actual.put("profileId",input.getString("profileId"));acceptReceipt(actual);return actual;
    }
    /** Foreground recovery schedules only original GETs on the existing bounded poll executor. */
    public void requestReadRefresh(){
        if(closed||!readRefreshQueued.compareAndSet(false,true))return;try{poller.execute(()->{try{synchronized(RemoteModule.this){for(int i=0;i<operations.length();i++)try{JSONObject op=operations.getJSONObject(i),selected=find(op.optString("profileId"));if(selected!=null&&!op.optString("jobId").isEmpty()&&op.optString("status").equals("accepted")&&endpoint(selected.optString("baseUrl")).equals(op.optString("serviceBase")))watch(op.getString("profileId"),op.getString("jobId"),op.getString("operationId"),op.getString("serviceBase"));}catch(Exception originalUnavailable){/* Never rebind or create. */}}poll();}finally{readRefreshQueued.set(false);}});}catch(java.util.concurrent.RejectedExecutionException closedExecutor){readRefreshQueued.set(false);}
    }
    /** One original prepared package operation. Existing ledger retains every slot before raw IO. */
    public JSONObject prepareProjectAssignment(JSONObject input,dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen original,
                                               dev.pocketengine.core.RunControl control,AssignmentDispatch authority)throws Exception {
        RemoteProjectAssignments.validateSelection(input);if(!original.identity.equals(input.getString("packageIdentity")))throw new SecurityException("原Frozen不是已批准项目包");
        JSONObject body=new JSONObject();for(String key:new String[]{"operationId","nativeAssignment","environmentId","environmentBinding","packageIdentity","packageIdentityEncoding","packageManifest","packageReference","files","limits","commandScope","approvalRevision","approvalExpiresAt"})body.put(key,input.get(key));
        String profile=identifier(input,"profileId"),operation=identifier(input,"operationId");JSONObject details=assignmentDetails(input).put("nativeAssignment",input.getJSONObject("nativeAssignment")).put("packageIdentity",input.getString("packageIdentity"));
        Reservation reserved=reserve(profile,operation,"assignment_project_prepare",body,details);if(reserved.known)return reconcileProjectAssignment(input);
        JSONObject actual;
        try{actual=projectSend(input,reserved.target,"/v1/assignment-projects",body,()->{control.check();authority.beforeDispatch();control.check();},control);}
        catch(java.io.IOException uncertain){actual=reconcileProjectAssignment(input);if(!actual.has("inputFiles"))return actual;}
        actual=acceptProjectPreparation(input,reserved.intent,actual);
        JSONArray files=input.getJSONObject("packageManifest").getJSONArray("files");
        for(int i=0;i<files.length();i++){
            control.check();JSONObject slot=actual.getJSONArray("inputFiles").getJSONObject(i);String state=slot.getString("state");if(state.equals("verified"))continue;
            if(!state.equals("pending"))return projectUnknown(reserved.intent,"原slot已派发或未知；只查原ACK，不发送第二个body");
            final int index=i;byte[] raw=original.file(files.getJSONObject(i).getString("path"));
            try{
                if(raw.length!=files.getJSONObject(i).getLong("bytes")||!dev.pocketengine.features.local.project.ProjectSources.sha256(raw).equals(files.getJSONObject(i).getString("sha256")))throw new SecurityException("原Frozen字节与批准manifest不符");
                reserveProjectSlot(reserved.intent,index);control.check();BoundRequest target=assignmentTarget(input);
                try{actual=RemoteTransport.uploadProjectFile(target.base,target.token,"/v1/assignment-projects/"+operation+"/files/"+index,operation,new java.io.ByteArrayInputStream(raw),raw.length,control,()->{control.check();dispatchGate.beforeDispatch("/v1/assignment-projects/"+operation+"/files/"+index);assignmentTarget(input);control.check();},null);}
                catch(java.io.IOException uncertain){actual=reconcileProjectAssignment(input);if(!actual.has("inputFiles")||!actual.getJSONArray("inputFiles").getJSONObject(i).optString("state").equals("verified"))return projectUnknown(reserved.intent,"原上传结果未确认；不自动重传");}
                control.check();actual=acceptProjectPreparation(input,reserved.intent,actual);
            }finally{java.util.Arrays.fill(raw,(byte)0);}
        }
        control.check();String finalize=UUID.nameUUIDFromBytes(("pocketworld/project-finalize/v1/"+operation).getBytes(StandardCharsets.UTF_8)).toString();
        boolean alreadyFinalizing;synchronized(this){JSONObject current=currentProjectIntent(reserved.intent);alreadyFinalizing=current.optBoolean("finalizeDispatched");if(!alreadyFinalizing){current.put("finalizeDispatched",true).put("finalizeOperationId",finalize);recordOperation(current);}}if(alreadyFinalizing)return reconcileProjectAssignment(input);
        try{actual=projectSend(input,assignmentTarget(input),"/v1/assignment-projects/"+operation+"/finalize",new JSONObject().put("finalizeOperationId",finalize).put("packageIdentity",input.getString("packageIdentity")),control::check,control);}
        catch(java.io.IOException uncertain){return reconcileProjectAssignment(input);}
        control.check();return acceptProjectPreparation(input,reserved.intent,actual);
    }
    public JSONObject reconcileProjectAssignment(JSONObject input)throws Exception {
        String operation=identifier(input,"operationId");JSONObject intent=operation(operation);if(intent!=null&&(!intent.optString("kind").equals("assignment_project_prepare")||!intent.getString("profileId").equals(input.getString("profileId"))||!intent.getString("packageIdentity").equals(input.getString("packageIdentity"))))throw new SecurityException("原prepare属于其他项目/连接");
        BoundRequest target=assignmentTarget(input);JSONObject actual;try{actual=RemoteTransport.requestProject(target.base,target.token,"/v1/assignment-projects/"+operation,null,20);}catch(java.io.IOException unavailable){return intent==null?assignmentNotice(input,"unknown","只查原项目准备；没有重体/重建"):projectUnknown(intent,"原项目准备不可读；不重体/重建");}
        assignmentTarget(input);return acceptProjectPreparation(input,intent,actual);
    }
    private synchronized JSONObject acceptProjectPreparation(JSONObject input,JSONObject intent,JSONObject actual)throws Exception {
        JSONObject checked=RemoteProjectAssignments.preparation(actual,input,false);if(checked.has("profileId")&&!input.getString("profileId").equals(checked.getString("profileId")))throw new SecurityException("准备回执属于别的连接");checked.remove("profileId");if(intent==null)return checked.put("profileId",input.getString("profileId"));intent=currentProjectIntent(intent);JSONObject previous=intent.optJSONObject("projectState");
        if(previous!=null){long before=previous.getLong("revision"),after=checked.getLong("revision");if(after<before)return new JSONObject(previous.toString()).put("profileId",input.getString("profileId"));if(after==before&&!canonical(previous).equals(canonical(checked)))throw new SecurityException("原项目同revision出现不同准备状态，不能按到达先后覆盖");}
        intent.put("projectState",new JSONObject(checked.toString())).put("status",checked.optString("status").equals("ready")?"settled":"unknown");recordOperation(intent);return checked.put("profileId",input.getString("profileId"));
    }
    private synchronized void reserveProjectSlot(JSONObject intent,int index)throws Exception {intent=currentProjectIntent(intent);JSONObject slots=intent.optJSONObject("projectSlotDispatch");if(slots==null){slots=new JSONObject();intent.put("projectSlotDispatch",slots);}String key=String.valueOf(index);if(slots.has(key))throw new SecurityException("原slot已经在本机登记，不能发送第二个body");slots.put(key,new JSONObject().put("status","sending").put("preparedAt",System.currentTimeMillis()));recordOperation(intent);}
    private synchronized JSONObject projectUnknown(JSONObject intent,String message)throws Exception {intent=currentProjectIntent(intent);intent.put("status","unknown").put("message",message);recordOperation(intent);return new JSONObject(intent.toString()).put("resubmitted",false);}
    private JSONObject currentProjectIntent(JSONObject selected)throws Exception {JSONObject actual=operation(selected.getString("operationId"));if(actual==null||!actual.optString("kind").equals("assignment_project_prepare")||!actual.optString("fingerprint").equals(selected.optString("fingerprint")))throw new SecurityException("原项目准备本机记录已替换");return actual;}
    private static String projectEncode(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private JSONObject projectSend(JSONObject input,BoundRequest target,String path,JSONObject body,AssignmentDispatch authority)throws Exception {return projectSend(input,target,path,body,authority,null);}
    private JSONObject projectSend(JSONObject input,BoundRequest target,String path,JSONObject body,AssignmentDispatch authority,dev.pocketengine.core.RunControl original)throws Exception {dispatchGate.beforeDispatch(path);assignmentTarget(input);authority.beforeDispatch();assignmentTarget(input);JSONObject actual=RemoteTransport.requestProject(target.base,target.token,path,body,30,original);assignmentTarget(input);return actual;}
    public JSONObject cancelProjectPreparation(JSONObject input,AssignmentDispatch authority)throws Exception {
        JSONObject actual=projectSend(input,assignmentTarget(input),"/v1/assignment-projects/"+identifier(input,"operationId")+"/cancel",new JSONObject(),authority);
        return acceptProjectPreparation(input,operation(identifier(input,"operationId")),actual);
    }
    public JSONObject captureProjectContribution(JSONObject input,AssignmentDispatch authority)throws Exception {
        JSONObject reference=input.getJSONObject("assignmentWorkspace");String operation=identifier(input,"operationId"),job=identifier(input,"jobId");JSONObject body=new JSONObject().put("operationId",operation).put("jobOperationId",identifier(input,"jobOperationId")).put("prepareOperationId",reference.getString("prepareOperationId")).put("checkoutVersion",reference.getString("checkoutVersion"));
        Reservation reserved=reserve(identifier(input,"profileId"),operation,"project_contribution",body,assignmentDetails(input).put("jobId",job).put("jobOperationId",body.getString("jobOperationId")));if(reserved.known)return reconcileProjectContribution(input);
        try{JSONObject actual=projectSend(input,reserved.target,"/v1/jobs/"+job+"/project-contributions",body,authority);reserved.intent.put("status",actual.optString("status").equals("completed")?"settled":"unknown");recordOperation(reserved.intent);return actual;}
        catch(java.io.IOException unconfirmed){return reconcileProjectContribution(input);}
    }
    public JSONObject reconcileProjectContribution(JSONObject input)throws Exception {
        String operation=identifier(input,"operationId"),job=identifier(input,"jobId");JSONObject intent=operation(operation);if(intent!=null&&(!intent.optString("kind").equals("project_contribution")||!job.equals(intent.optString("jobId"))))throw new SecurityException("原贡献操作属于另一job");BoundRequest target=assignmentTarget(input);JSONObject actual;
        try{actual=RemoteTransport.requestProject(target.base,target.token,"/v1/jobs/"+job+"/snapshot-operations/"+operation,null,20);}catch(java.io.IOException unavailable){return assignmentNotice(input,"unknown","只查原贡献操作，不重新冻结/执行");}assignmentTarget(input);if(intent!=null){intent.put("status",actual.optString("status").equals("completed")?"settled":"unknown");recordOperation(intent);}return actual;
    }
    public byte[] readProjectContributionFile(JSONObject input,JSONObject file,AssignmentDispatch originalRead)throws Exception {
        String path="/v1/jobs/"+identifier(input,"jobId")+"/project-contributions/"+identifier(input,"snapshotId")+"/file?version="+projectEncode(required(input,"snapshotVersion",64))+"&path="+projectEncode(file.getString("path"))+"&sha256="+projectEncode(file.getString("sha256"))+"&bytes="+file.getLong("bytes")+"&jobOperationId="+projectEncode(identifier(input,"jobOperationId"));BoundRequest target=assignmentTarget(input);
        return RemoteTransport.readProjectFile(target.base,target.token,path,file.getLong("bytes"),file.getString("sha256"),()->{originalRead.beforeDispatch();assignmentTarget(input);});
    }
    public JSONObject prepareAssignmentWorkspace(JSONObject input,AssignmentDispatch authority)throws Exception {
        String profile=identifier(input,"profileId"),source=identifier(input,"sourceJobId"),operation=identifier(input,"operationId");
        JSONObject body=new JSONObject();for(String key:new String[]{"operationId","nativeAssignment","snapshotId","snapshotVersion","files","environmentBinding","limits","approvalRevision","approvalExpiresAt"})body.put(key,input.get(key));
        JSONObject details=assignmentDetails(input).put("sourceJobId",source).put("nativeAssignment",new JSONObject(input.getJSONObject("nativeAssignment").toString()));
        Reservation reserved=reserve(profile,operation,"assignment_prepare",body,details);if(reserved.known)return reconcileAssignmentPreparation(input);
        try{JSONObject actual=assignmentSend(input,reserved.target,"/v1/jobs/"+source+"/assignment-workspaces",body,authority);return acceptAssignmentPreparation(reserved.intent,actual);}
        catch(RemoteTransport.HttpFailure rejected){if(rejected.status>=400&&rejected.status<500){reserved.intent.put("status","rejected").put("message",rejected.getMessage());recordOperation(reserved.intent);return assignmentNotice(input,"rejected",rejected.getMessage());}return unknownOperation(reserved.intent);}
        catch(java.io.IOException unavailable){return unknownOperation(reserved.intent);}
    }
    public JSONObject reconcileAssignmentPreparation(JSONObject input)throws Exception {
        String operation=identifier(input,"operationId"),source=identifier(input,"sourceJobId"),profile=identifier(input,"profileId");JSONObject intent=operation(operation);
        if(intent!=null&&(!"assignment_prepare".equals(intent.optString("kind"))||!source.equals(intent.optString("sourceJobId"))||!profile.equals(intent.optString("profileId"))))throw new SecurityException("所查原prepare不属于这次assignment/source job");
        if(intent!=null&&intent.optString("status").equals("rejected"))return assignmentNotice(input,"rejected",intent.optString("message"));
        BoundRequest target=assignmentTarget(input);JSONObject result;
        try{result=send(target,"/v1/jobs/"+source+"/assignment-operations/"+operation,null,15);}
        catch(java.io.IOException unavailable){return assignmentNotice(input,"unknown","只查询原prepare；未重新复制文件或发送制作任务");}
        if(!source.equals(result.optString("sourceJobId"))||!operation.equals(result.optString("operationId")))throw new SecurityException("远端返回另一原prepare/job");
        return intent==null?result.put("profileId",profile):acceptAssignmentPreparation(intent,result);
    }
    private JSONObject acceptAssignmentPreparation(JSONObject intent,JSONObject actual)throws Exception {
        if(!intent.getString("operationId").equals(actual.optString("operationId"))||!intent.getString("sourceJobId").equals(actual.optString("sourceJobId"))
                ||actual.has("nativeAssignment")&&!canonical(intent.getJSONObject("nativeAssignment")).equals(canonical(actual.getJSONObject("nativeAssignment"))))throw new SecurityException("远端prepare回执原身份不符");
        if(actual.optString("status").equals("ready")&&(!actual.optString("checkoutId").matches("[a-f0-9-]{36}")||!actual.optString("checkoutVersion").matches("[a-f0-9]{64}")||!actual.optString("checkoutIdentity").matches("[a-f0-9]{64}")))throw new SecurityException("远端没有独立物理所选文件副本证明");
        intent.put("status",actual.optString("status").equals("ready")?"settled":"unknown");recordOperation(intent);return actual.put("profileId",intent.getString("profileId"));
    }
    public JSONObject submitAssignmentJob(JSONObject input,AssignmentDispatch authority)throws Exception {
        String profile=identifier(input,"profileId"),operation=identifier(input,"operationId");
        JSONObject body=new JSONObject().put("operationId",operation).put("environmentId",identifier(input,"environmentId")).put("environmentBinding",required(input,"environmentBinding",64))
                .put("worker","codex_app_server").put("readOnly",false).put("prompt",required(input,"prompt",12000)).put("timeoutSeconds",input.getJSONObject("limits").getInt("maxWallTimeSeconds"))
                .put("assignmentWorkspace",new JSONObject(input.getJSONObject("assignmentWorkspace").toString()));if(input.has("model"))body.put("model",required(input,"model",200));
        Reservation reserved=reserve(profile,operation,"job_submit",body,assignmentDetails(input).put("assignmentWorkspace",body.getJSONObject("assignmentWorkspace")));
        if(reserved.known)return reconcile(new JSONObject().put("profileId",profile).put("operationId",operation).put("serviceBase",input.getString("serviceBase")));
        JSONObject actual;try{actual=assignmentSend(input,reserved.target,"/v1/jobs",body,authority);}
        catch(RemoteTransport.HttpFailure rejected){if(rejected.status>=400&&rejected.status<500){reserved.intent.put("status","rejected").put("message",rejected.getMessage());recordOperation(reserved.intent);throw rejected;}return unknownOperation(reserved.intent);}
        catch(java.io.IOException unavailable){return unknownOperation(reserved.intent);}
        if(!operation.equals(actual.optString("operationId"))||!actual.optString("id").matches("[A-Za-z0-9_-]{1,80}")||!body.getString("environmentId").equals(actual.optString("environmentId"))||!"codex_app_server".equals(actual.optString("worker"))
                ||!canonical(body.getJSONObject("assignmentWorkspace")).equals(canonical(actual.optJSONObject("assignmentWorkspace"))))return unknownOperation(reserved.intent);
        reserved.intent.put("status","accepted").put("jobId",actual.getString("id"));recordOperation(reserved.intent);actual.put("profileId",profile);acceptReceipt(actual);return actual;
    }
    /** Original owner approval has its own SQL-issued foreground authority, never an execution RunControl. */
    public JSONObject approveAssignmentJob(JSONObject input,AssignmentDispatch authority)throws Exception {
        BoundRequest target=assignmentTarget(input);String job=identifier(input,"jobId");
        JSONObject body=new JSONObject().put("requestId",required(input,"approvalRequestId",200)).put("decision",required(input,"decision",20));
        JSONObject actual=assignmentSend(input,target,"/v1/jobs/"+job+"/approval",body,authority);
        if(!job.equals(actual.optString("id"))||!input.getString("jobOperationId").equals(actual.optString("operationId")))throw new SecurityException("原审批回执不是已审的原job");actual.put("profileId",input.getString("profileId"));acceptReceipt(actual);return actual;
    }
    public JSONObject cancelAssignmentJob(dev.pocketengine.store.TaskStore.TeamRemoteClaim original,AssignmentDispatch authority)throws Exception {
        JSONObject view=java.util.Objects.requireNonNull(original).view();if(!original.canDispatch()||!"CANCEL".equals(view.optString("action")))throw new SecurityException("没有原团队单次停止票");
        JSONObject scope=view.getJSONObject("scope"),input=new JSONObject(scope.toString()).put("_profileBinding",scope.getString("profileGeneration")).put("jobId",view.getString("jobId")).put("jobOperationId",view.getString("jobOperationId"));
        return cancelOriginalJob(input,()->{},java.util.Objects.requireNonNull(authority),true);
    }
    /** Original task/run/epoch authority stays a native object, never a field in feature JSON. */
    public JSONObject cancelOriginalPinnedJob(dev.pocketengine.store.OriginalRemoteStopClaim original)throws Exception {
        java.util.Objects.requireNonNull(original).beginDispatch();
        JSONObject actual=cancelOriginalJob(original.target(),original::beforeDispatch,original::beginPost,original.canDispatch());original.record(actual);return actual;
    }
    private static final String ORIGINAL_CANCELLATION="pocketworld.original-job-cancel.v1";
    private JSONObject cancelOriginalJob(JSONObject requested,AssignmentDispatch current,AssignmentDispatch beforePost,boolean dispatch)throws Exception {
        if(closed)throw new IllegalStateException("电脑连接模块已关闭");
        JSONObject input=cancellationTarget(requested);BoundRequest target=assignmentTarget(input);String job=input.getString("jobId"),operation=input.getString("jobOperationId");
        current.beforeDispatch();
        if(!dispatch)return readCancellation(input,target,current,null);
        JSONObject capability;
        try{
            dispatchGate.beforeDispatch("/v1/status");current.beforeDispatch();assignmentTarget(input);
            capability=target.request("/v1/status",null,15).optJSONObject("originalJobCancellation");
        }catch(java.io.IOException unavailable){return cancellationUnknown(input,false,"无法核对原操作停止能力；未发送取消，只查询原任务");}
        current.beforeDispatch();assignmentTarget(input);
        if(!originalCancellationSupported(capability))return cancellationUnknown(input,false,"电脑未声明锁内原operation停止能力；未发送取消，不降级到旧协议");
        JSONObject body=new JSONObject().put("jobOperationId",operation),details=new JSONObject().put("jobId",job).put("jobOperationId",operation).put("sourceServiceBase",input.getString("serviceBase")).put("profileBinding",input.getString("_profileBinding"));
        String stop="osc_"+digest(canonical(new JSONObject().put("profileId",input.getString("profileId")).put("serviceBase",input.getString("serviceBase")).put("jobId",job).put("jobOperationId",operation)));
        Reservation reserved=reserve(input.getString("profileId"),stop,"job_cancel",body,details);
        if(reserved.known)return readCancellation(input,target,current,reserved.intent);
        String path="/v1/jobs/"+job+"/cancel-original";JSONObject actual;
        try{
            dispatchGate.beforeDispatch(path);current.beforeDispatch();assignmentTarget(input);
            beforePost.beforeDispatch();assignmentTarget(input);
            actual=target.request(path,body,20);
        }catch(java.io.IOException unavailable){reserved.intent.put("status","unknown");recordOperation(reserved.intent);return cancellationUnknown(input,true,"停止请求结果尚未确认；只查询原任务，不再次发送；已发生效果不会撤销");}
        current.beforeDispatch();assignmentTarget(input);
        JSONObject proof=actual.optJSONObject("originalCancellation");
        if(!job.equals(actual.optString("id"))||!operation.equals(actual.optString("operationId"))||!actual.optString("status").matches("queued|running|stopping|awaiting_approval|awaiting_input|completed|failed|cancelled")||proof==null||!ORIGINAL_CANCELLATION.equals(proof.optString("protocol"))||!job.equals(proof.optString("jobId"))||!operation.equals(proof.optString("jobOperationId"))||!Boolean.TRUE.equals(proof.opt("identityMatched"))||!Boolean.FALSE.equals(proof.opt("effectsReversed"))){
            reserved.intent.put("status","unknown");recordOperation(reserved.intent);return cancellationUnknown(input,true,"原停止回执身份未确认；只查询原job，不把其他回执当作已停止");
        }
        actual.put("profileId",input.getString("profileId"));settleCancellation(reserved.intent,actual);acceptReceipt(actual);return actual;
    }
    /** Snapshot an already known original operation, never learn it from the cancellation response. */
    private synchronized JSONObject cancellationTarget(JSONObject requested)throws Exception {
        JSONObject input=new JSONObject(requested.toString());String profile=identifier(input,"profileId"),job=identifier(input,"jobId"),original=input.has("jobOperationId")?identifier(input,"jobOperationId"):"",service=jobOrigin(profile,job);
        for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);if(!"job_submit".equals(row.optString("kind","job_submit"))||!profile.equals(row.optString("profileId"))||!job.equals(row.optString("jobId")))continue;if(!original.isEmpty()&&!original.equals(row.optString("operationId")))throw new SecurityException("停止operation不是原job");original=row.getString("operationId");}
        JSONObject known=recent.get(profile+":"+job);if(known!=null){if(!original.isEmpty()&&!original.equals(known.optString("operationId")))throw new SecurityException("停止operation不是原job");original=identifier(known,"operationId");}
        if(input.has("serviceBase")){String expected=required(input,"serviceBase",2048);if(service!=null&&!service.equals(expected))throw new SecurityException("停止地址不是原job地址");service=expected;}
        // Preserve the original-origin failure before any capability request or credential delivery.
        BoundRequest target=bind(profile,service);
        if(original.isEmpty()||service==null||service.isEmpty())throw new SecurityException("没有可证明的原job/operation/地址，未发送停止");
        String generation=input.has("_profileBinding")?required(input,"_profileBinding",64):profileBindingHash(profile);requireProfileRevision(profile,generation);
        return new JSONObject().put("profileId",profile).put("serviceBase",target.serviceBase).put("jobId",job).put("jobOperationId",original).put("_profileBinding",generation);
    }
    private static boolean originalCancellationSupported(JSONObject capability)throws Exception {
        JSONArray fields=capability==null?null:capability.optJSONArray("requiredFields");
        return capability!=null&&ORIGINAL_CANCELLATION.equals(capability.optString("protocol"))&&Boolean.TRUE.equals(capability.opt("supported"))&&"/v1/jobs/{jobId}/cancel-original".equals(capability.optString("endpoint"))&&fields!=null&&fields.length()==1&&"jobOperationId".equals(fields.optString(0))&&"locked_job_operation_before_mutation".equals(capability.optString("identityCheck"))&&"job_manager_cli_jobs_only".equals(capability.optString("scope"));
    }
    private JSONObject reconcileCancellation(JSONObject intent)throws Exception {
        JSONObject input=new JSONObject().put("profileId",intent.getString("profileId")).put("serviceBase",intent.getString("serviceBase")).put("jobId",intent.getString("jobId")).put("jobOperationId",intent.getString("jobOperationId")).put("_profileBinding",intent.getString("profileBinding"));
        input=cancellationTarget(input);return readCancellation(input,assignmentTarget(input),()->{},intent);
    }
    private JSONObject readCancellation(JSONObject input,BoundRequest target,AssignmentDispatch current,JSONObject intent)throws Exception {
        JSONObject actual;try{String path="/v1/jobs/"+input.getString("jobId");dispatchGate.beforeDispatch(path);current.beforeDispatch();assignmentTarget(input);actual=target.request(path,null,15);}
        catch(java.io.IOException unavailable){return cancellationUnknown(input,false,"原停止结果尚未确认；只查询，不重复取消").put("queryOnly",true);}
        current.beforeDispatch();assignmentTarget(input);
        if(!input.getString("jobId").equals(actual.optString("id"))||!input.getString("jobOperationId").equals(actual.optString("operationId")))return cancellationUnknown(input,false,"查询未返回原job/operation；保留未知，不重复取消").put("queryOnly",true);
        actual.put("profileId",input.getString("profileId")).put("queryOnly",true).put("cancellationDispatched",false);if(intent!=null)settleCancellation(intent,actual);if(actual.optString("status").matches("queued|running|stopping|awaiting_approval|awaiting_input|completed|failed|cancelled"))acceptReceipt(actual);return actual;
    }
    private void settleCancellation(JSONObject intent,JSONObject actual)throws Exception {
        boolean settled=actual.optString("status").matches("completed|failed|cancelled")&&Boolean.TRUE.equals(actual.opt("dispatchFenceFinal"))&&Boolean.FALSE.equals(actual.opt("resourcesHeld"));intent.put("status",settled?"settled":"unknown");recordOperation(intent);
    }
    private static JSONObject cancellationUnknown(JSONObject input,boolean dispatched,String message)throws Exception {
        return new JSONObject().put("id",input.getString("jobId")).put("operationId",input.getString("jobOperationId")).put("profileId",input.getString("profileId")).put("serviceBase",input.getString("serviceBase")).put("status","unknown").put("message",message).put("cancellationDispatched",dispatched).put("effectsReversed",false).put("remoteEffectsVerified",false);
    }
    private JSONObject assignmentDetails(JSONObject input)throws Exception {return new JSONObject().put("profileBinding",required(input,"_profileBinding",64)).put("sourceServiceBase",required(input,"serviceBase",2048));}
    private synchronized BoundRequest assignmentTarget(JSONObject input)throws Exception {String profile=identifier(input,"profileId");requireProfileRevision(profile,required(input,"_profileBinding",64));return bind(profile,required(input,"serviceBase",2048));}
    private JSONObject assignmentSend(JSONObject input,BoundRequest target,String path,JSONObject body,AssignmentDispatch authority)throws Exception {
        dispatchGate.beforeDispatch(path);
        assignmentTarget(input);
        java.util.Objects.requireNonNull(authority).beforeDispatch();
        assignmentTarget(input);
        return target.request(path,body,20);
    }
    private static JSONObject assignmentNotice(JSONObject input,String status,String message)throws Exception {JSONObject value=new JSONObject().put("status",status).put("message",message).put("resubmitted",false);for(String key:new String[]{"profileId","sourceJobId","operationId","serviceBase"})if(input.has(key))value.put(key,input.get(key));return value;}

    private JSONObject snapshotRead(JSONObject input,String path)throws Exception {
        BoundRequest target;synchronized(this){String profile=identifier(input,"profileId");requireProfileRevision(profile,required(input,"_profileBinding",64));String service=required(input,"serviceBase",2048),known=jobOrigin(profile,identifier(input,"jobId"));if(known!=null&&!service.equals(known))throw new SecurityException("原快照地址与原job不一致");target=bind(profile,service);}
        return send(target,path,null,15).put("profileId",input.getString("profileId"));
    }
    private static JSONObject snapshotNotice(JSONObject input,String status,String message)throws Exception {
        JSONObject result=new JSONObject().put("status",status).put("message",message);for(String key:new String[]{"operationId","jobId","jobOperationId","profileId","serviceBase","environmentId","worker"})if(input.has(key))result.put(key,input.get(key));return result;
    }
    private JSONObject requestJob(JSONObject input,String path,JSONObject body,int timeout)throws Exception {return requestJob(input,path,body,timeout,null);}
    private JSONObject requestJob(JSONObject input,String path,JSONObject body,int timeout,dev.pocketengine.core.RunControl originalControl)throws Exception {
        BoundRequest target;String profileId=identifier(input,"profileId"),jobId=identifier(input,"jobId");
        String expectedOperation=input.has("jobOperationId")?identifier(input,"jobOperationId"):"";
        synchronized(this){
            if(input.has("_profileBinding"))requireProfileRevision(profileId,required(input,"_profileBinding",64));
            String origin=jobOrigin(profileId,jobId);
            if(input.has("serviceBase")){
                String reviewed=required(input,"serviceBase",2048);
                if(origin!=null&&!origin.equals(reviewed))throw new SecurityException("审阅的电脑地址与原任务不一致");
                origin=reviewed;
            }
            for(int i=0;i<operations.length();i++){
                JSONObject op=operations.getJSONObject(i);
                if("job_submit".equals(op.optString("kind","job_submit"))&&profileId.equals(op.optString("profileId"))&&jobId.equals(op.optString("jobId"))){
                    if(!expectedOperation.isEmpty()&&!expectedOperation.equals(op.optString("operationId")))throw new SecurityException("审阅操作不属于原电脑任务");
                    expectedOperation=op.getString("operationId");break;
                }
            }
            if(!expectedOperation.isEmpty()){
                String original=expectedOperation;JSONObject op=operation(original),receipt=recent.get(profileId+":"+jobId);
                if(op!=null&&(!"job_submit".equals(op.optString("kind","job_submit"))||!profileId.equals(op.optString("profileId"))||!jobId.equals(op.optString("jobId"))))throw new SecurityException("审阅操作不属于原电脑任务");
                if(receipt!=null&&!original.equals(receipt.optString("operationId")))throw new SecurityException("审阅操作不属于原电脑任务");
            }
            target=bind(profileId,origin);
        }
        JSONObject result=send(target,path,body,timeout,originalControl);
        // Artifact payloads are bytes/path/hash, not a job receipt; keep their original-origin fence.
        if(!path.startsWith("/v1/jobs/"+jobId+"/artifact?")&&(!jobId.equals(result.optString("id"))||!expectedOperation.isEmpty()&&!expectedOperation.equals(result.optString("operationId"))))throw new SecurityException("电脑返回了另一任务或操作的回执");
        return result;
    }
    private String jobOrigin(String profileId,String jobId)throws Exception {
        for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(profileId.equals(op.optString("profileId"))&&jobId.equals(op.optString("jobId")))return op.optString("serviceBase");}
        JSONObject receipt=recent.get(profileId+":"+jobId);return receipt==null?null:receipt.optString("serviceBase");
    }
    /** Caller holds the same monitor used by profile mutation and operation reservation. */
    private BoundRequest bind(String profileId,String expectedService)throws Exception {
        String base=profile(profileId).getString("baseUrl");
        if(expectedService!=null&&!endpoint(base).equals(expectedService))throw new IllegalStateException("电脑地址与原操作不一致；请恢复原连接核对，未向新地址发送");
        return new BoundRequest(profileId,base,credentials.get(profileId,base));
    }
    private JSONObject send(BoundRequest target,String path,JSONObject body,int timeout)throws Exception {return send(target,path,body,timeout,null);}
    private JSONObject send(BoundRequest target,String path,JSONObject body,int timeout,dev.pocketengine.core.RunControl original)throws Exception {
        if(original!=null)original.check();dispatchGate.beforeDispatch(path);return target.request(path,body,timeout,original);
    }
    private synchronized Reservation reserve(String profileId,String operationId,String kind,JSONObject body,JSONObject details)throws Exception {
        if(details.has("profileBinding"))requireProfileRevision(profileId,details.getString("profileBinding"));
        String fingerprint=digest(canonical(new JSONObject().put("body",body).put("details",details)));
        JSONObject prior=operation(operationId);
        if(prior!=null){
            String previousKind=prior.optString("kind","job_submit");
            if(!kind.equals(previousKind)||!profileId.equals(prior.optString("profileId")))throw new IllegalArgumentException("该操作编号已绑定另一类型或电脑");
            if(prior.has("fingerprint")&&!fingerprint.equals(prior.optString("fingerprint")))throw new IllegalArgumentException("该操作编号已绑定另一组参数");
            bind(profileId,prior.optString("serviceBase"));return new Reservation(prior,null,true);
        }
        String expectedService=details.has("sourceServiceBase")?details.getString("sourceServiceBase"):kind.equals("steer_message")?jobOrigin(profileId,details.getString("jobId")):kind.equals("result_snapshot")?details.getString("sourceServiceBase"):null;
        if(kind.equals("job_submit")&&body.has("resumeJobId")){
            expectedService=requireContinuation(profileId,body,details);
            details.put("resumeJobId",body.getString("resumeJobId")).put("threadId",body.getString("threadId"));
        }
        BoundRequest target=bind(profileId,expectedService);
        JSONObject intent=new JSONObject(details.toString()).put("operationId",operationId).put("profileId",profileId).put("serviceBase",target.serviceBase)
                .put("kind",kind).put("fingerprint",fingerprint).put("status","submitting").put("createdAt",System.currentTimeMillis());
        recordOperation(intent);return new Reservation(intent,target,false);
    }
    /** Same monitor as profile mutation/reservation. TaskStore remains the native owner authority. */
    private String requireContinuation(String profileId,JSONObject body,JSONObject details)throws Exception {
        String jobId=body.getString("resumeJobId"),original=details.getString("jobOperationId"),service=details.getString("sourceServiceBase");
        JSONObject source=recent.get(profileId+":"+jobId),prior=operation(original);
        if(source==null)throw new IllegalStateException("请先查询原电脑任务，核对最新续接控制；没有提交新任务");
        if(!service.equals(source.optString("serviceBase"))||!original.equals(source.optString("operationId"))
                ||prior!=null&&(!"job_submit".equals(prior.optString("kind","job_submit"))||!profileId.equals(prior.optString("profileId"))||!service.equals(prior.optString("serviceBase"))||!jobId.equals(prior.optString("jobId"))))throw new SecurityException("续接来源与原电脑地址、任务或操作不一致");
        String worker=body.getString("worker"),protocol=worker.equals("claude")?"claude_stream_json":worker.equals("pi")?"pi_rpc_v1":"codex_app_server";
        if(!worker.matches("claude|codex_app_server|pi")||!worker.equals(source.optString("worker"))||!protocol.equals(source.optString("protocol"))
                ||!body.getString("environmentId").equals(source.optString("environmentId"))||!body.getString("threadId").equals(source.optString("threadId")))throw new SecurityException("续接执行环境、协议或原会话身份已变化");
        if(worker.equals("claude")&&(!source.optBoolean("protocolVerified")||!body.getString("threadId").equals(source.optString("sessionId"))))throw new SecurityException("Claude尚未确认原会话身份，不能按线程别名续接");
        if(worker.equals("pi")&&(!source.optBoolean("protocolVerified")||source.opt("noTools")!=Boolean.TRUE||source.opt("nativeSettled")!=Boolean.TRUE||source.opt("resourcesHeld")!=Boolean.FALSE||!body.getString("threadId").equals(source.optString("sessionId"))||source.optString("promptRequestId").isEmpty()||source.optJSONObject("processCleanupProof")==null||source.getJSONObject("processCleanupProof").opt("processGroupStoppedVerified")!=Boolean.TRUE))throw new SecurityException("Pi须原无工具会话已native settled且真实原进程树已清理；未知不续接");
        if(body.has("modelOptionsDigest")){JSONObject selection=new JSONObject().put("model",body.get("model")).put("modelOptionsDigest",body.get("modelOptionsDigest"));if(body.has("effort"))selection.put("effort",body.get("effort"));RemoteModelOptions.select(source,selection);}
        JSONObject controls=source.optJSONObject("controls");
        boolean recovered=RemoteColdRecovery.receipt(source);
        if(!source.optString("status").matches("completed|failed|cancelled")&&!recovered||controls==null||!controls.optBoolean("threadResume")||source.optBoolean("steeringUnknown"))throw new IllegalStateException("原任务尚未核对终止或不允许续接；未知效果只能查询，不会重新提交");
        if(recovered&&(!source.getJSONObject("recoveryProof").getString("proofId").equals(body.optString("recoveryProofId"))||body.opt("recoveryOwnerAcknowledged")!=Boolean.TRUE||body.optInt("timeoutSeconds")<10||body.optInt("timeoutSeconds")>900))throw new SecurityException("冷中断续接缺少原proof及本次新时限/费用风险确认");
        if(body.has("readOnly")&&((worker.equals("claude")||worker.equals("pi"))&&body.getBoolean("readOnly")||worker.equals("codex_app_server")&&body.getBoolean("readOnly")!=source.optBoolean("readOnly")))throw new SecurityException("不能改变原工具访问边界；Claude不提供强制只读沙箱");
        for(int i=0;i<operations.length();i++){
            JSONObject op=operations.getJSONObject(i);
            if(!profileId.equals(op.optString("profileId"))||!service.equals(op.optString("serviceBase")))continue;
            if("steer_message".equals(op.optString("kind"))&&jobId.equals(op.optString("jobId"))&&!op.optString("status").matches("settled|rejected"))throw new IllegalStateException("原turn补充回执尚未确认，只能核对原消息，不能开下一turn");
            if("job_submit".equals(op.optString("kind","job_submit"))&&jobId.equals(op.optString("resumeJobId"))&&!"rejected".equals(op.optString("status")))throw new IllegalStateException("该来源已有续接操作，只能查询原操作，不能重复开启下一turn");
        }
        return service;
    }
    private static String digest(String text)throws Exception {StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}
        return value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private static final class BoundRequest {
        final String profileId,base,token,serviceBase;
        BoundRequest(String profileId,String base,String token){this.profileId=profileId;this.base=base;this.token=token;serviceBase=endpoint(base);}
        JSONObject request(String path,JSONObject body,int timeout)throws Exception{return request(path,body,timeout,null);}
        JSONObject request(String path,JSONObject body,int timeout,dev.pocketengine.core.RunControl original)throws Exception{return RemoteTransport.request(base,token,path,body,timeout,original).put("serviceBase",serviceBase);}
    }
    private static final class Reservation {
        final JSONObject intent;final BoundRequest target;final boolean known;
        Reservation(JSONObject intent,BoundRequest target,boolean known){this.intent=intent;this.target=target;this.known=known;}
    }
    private void poll() {
        for(Map.Entry<String,Watch> entry:watches.entrySet()){
            if(closed)return;Watch watch=entry.getValue();
            try{
                BoundRequest target;synchronized(this){target=bind(watch.profileId,watch.serviceBase);}
                JSONObject result=send(target,"/v1/jobs/"+watch.jobId,null,5);
                if(!watch.serviceBase.equals(result.optString("serviceBase"))){watches.remove(entry.getKey(),watch);continue;}
                watch.requireReceipt(result);
                result.put("profileId",watch.profileId);acceptReceipt(result);watch.failures=0;
                String status=result.optString("status");if(status.equals("completed")||status.equals("failed")||status.equals("cancelled")||status.equals("interrupted")||status.equals("unknown"))watches.remove(entry.getKey(),watch);
            }catch(Exception unavailable){if(++watch.failures>=3){watches.remove(entry.getKey(),watch);try{context.emit(id(),new JSONObject().put("kind","connection_lost").put("profileId",watch.profileId).put("jobId",watch.jobId).put("status","unknown").put("message","电脑状态暂时无法读取；任务是否继续请重新连接后核对"));}catch(Exception ignored){}}}
        }
    }
    private void remember(JSONObject job)throws Exception {
        if(recent.size()>=30&&!recent.containsKey(job.optString("profileId")+":"+job.optString("id"))){String oldest=null;long time=Long.MAX_VALUE;for(Map.Entry<String,JSONObject> item:recent.entrySet()){long at=item.getValue().optLong("updatedAt");if(at<time){time=at;oldest=item.getKey();}}if(oldest!=null)recent.remove(oldest);}
        recent.put(job.optString("profileId")+":"+job.getString("id"),metadata(job).put("serviceBase",job.optString("serviceBase")));
    }
    private static JSONObject metadata(JSONObject job)throws Exception {JSONObject value=new JSONObject();for(String key:new String[]{"id","profileId","serviceBase","operationId","environmentId","worker","status","message","createdAt","updatedAt","exitCode","outputTruncated","timedOut","archived","lastKnownStatus","path","version","previousVersion","desiredVersion","verified","protocol","protocolVerified","controlProtocolVerified","sessionIdentityConfirmed","sessionId","promptRequestId","threadId","turnId","pendingApprovals","approvalReceipts","pendingQuestions","questionReceipts","progress","promptResultIdentity","diffTruncated","model","reportedModel","readOnly","readOnlyConfirmed","externalToolsDisabled","teamReadOnlyTools","capabilities","controls","inputSteeringVerified","steeringUnknown","lastSteerMessage","interruptAcknowledged","turnStartAcknowledged","resumeJobId","sourceServiceBase","remoteEffectsVerified","eventCount","resultSubtype","permissionDenialCount","messageId","jobId","jobOperationId","expectedTurnId","expectedPromptRequestId","disposition","delivery","consumed","dispatched","errorCode","resultSnapshotId","resultSnapshotVersion","snapshotPrelaunchVerified","snapshotBaseVerifiedAt","workspaceIdentity","assignmentWorkspace","externalJobLimits","inferenceUsage","modelCallHardCapSupported","tokenHardCapSupported","costHardCapSupported","wallTimeHardCapSupported","wallTimeEnforcement","detachedDescendantIsolationVerified","assignmentWriteSandboxVerified","osReadIsolationVerified","modelOptions","modelRuntime","effort","noTools","nativeSettled","nativeQueueEmptyConfirmed","resourcesHeld","commandAllowlistEnforced","processStarted","dispatchFenceFinal","notStartedConfirmed","processCleanupProof","outputLimitExceeded","inputState","recoveryProof","coldRecovery","projectHistory","sessionBranchSource","sessionBranch","inferenceAudience","groupInputSource","externalGroupApiMode","externalGroupApiRead","originalCancellation","cancellationDispatched","queryOnly","effectsReversed"})if(job.has(key))value.put(key,job.get(key));return new JSONObject(value.toString());}
    /** Native WorkProfile source inspection only. Not reachable through handle or model commands. */
    public JSONObject workProfileTargetFacts(JSONObject reference,dev.pocketengine.core.RunControl observation)throws Exception {
        workProfileReference(reference);java.util.Objects.requireNonNull(observation).check();
        String worker=reference.getString("worker");
        if(!worker.matches("codex_app_server|claude|pi"))return new JSONObject().put("supported",false).put("sourceEvidence",new JSONObject().put("sourceState","unsupported").put("reasonCode","worker_adapter_unimplemented"));
        JSONObject binding=workProfileCurrentBinding(reference,observation),entrypoint=(JSONObject)binding.remove("_entrypointObservation");
        JSONObject query=new JSONObject().put("profileId",binding.getString("profileId")).put("_profileBinding",binding.getString("profileBinding"));
        JSONObject body=new JSONObject().put("environmentId",binding.getString("environmentId")).put("environmentBinding",binding.getString("environmentBinding")).put("worker",worker);
        JSONObject probe=request(query,"/v1/workers/probe",body,15,observation);workProfileProfileCurrent(reference,observation);
        if(!binding.getString("environmentId").equals(probe.optString("environmentId"))||!worker.equals(probe.optString("worker")))throw new SecurityException("原Harness探测返回了另一环境或执行器");
        JSONObject after=workProfileExecutableObservation(probe);boolean identityCurrent=Boolean.TRUE.equals(entrypoint.opt("executableIdentityVerified"))&&Boolean.TRUE.equals(after.opt("executableIdentityVerified"))&&entrypoint.getString("executableIdentity").equals(after.getString("executableIdentity"));
        boolean codex=worker.equals("codex_app_server"),pi=worker.equals("pi"),available=Boolean.TRUE.equals(probe.opt("available"));
        boolean protocol=(codex||pi)&&Boolean.TRUE.equals(probe.opt("protocolVerified")),config=codex&&protocol&&Boolean.TRUE.equals(probe.opt("configReadVerified"));
        boolean cliFlags=Boolean.TRUE.equals(probe.opt("cliFlagsVerified")),noTools=Boolean.TRUE.equals(probe.opt("noTools"));
        String version=probe.optString("cliVersion");if(!version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9._-]+)?")||version.length()>80)version="unknown";
        boolean pinnedPi="1.4.0".equals(version);
        boolean supported=identityCurrent&&available&&(codex?protocol&&config&&Boolean.TRUE.equals(probe.opt("accountConfigured")):pi?pinnedPi&&cliFlags&&protocol&&noTools:cliFlags);
        String mode=probe.optString("authMode");if(!java.util.Set.of("apiKey","chatgpt","amazonBedrock","gcp").contains(mode)||!codex||!protocol)mode="unknown";
        String unverified=codex?"worker_protocol_account_or_config_unverified":"worker_cli_flags_unverified";
        if(pi)unverified=!pinnedPi?"worker_version_unsupported":!cliFlags?"worker_cli_flags_unverified":!protocol?"worker_protocol_unverified":"worker_no_tools_unverified";
        JSONObject evidence=new JSONObject().put("sourceState",supported?"source_ready":"unavailable").put("reasonCode",supported?"original_binding_and_probe_observed":!identityCurrent?"worker_entrypoint_identity_unverified_or_changed":!available?"worker_unavailable":unverified)
                .put("workerAvailable",available).put("protocolSupported",true).put("protocolVerified",codex||pi?workProfileBoolean(probe,"protocolVerified"):JSONObject.NULL)
                .put("accountConfigured",codex&&protocol?workProfileBoolean(probe,"accountConfigured"):JSONObject.NULL).put("accountMode",mode).put("configReadVerified",codex&&protocol?workProfileBoolean(probe,"configReadVerified"):JSONObject.NULL)
                .put("workerVersion",version).put("connectionVerified",false).put("configurationApplied",false).put("runtimeVerified",false);
        // The original Pi probe only observes its pinned no-tools RPC. It proves
        // neither configured account/billing nor an applied Harness configuration.
        if(pi)evidence.put("cliFlagsVerified",workProfileBoolean(probe,"cliFlagsVerified")).put("noTools",workProfileBoolean(probe,"noTools"));
        JSONObject identity=identityCurrent?after:workProfileExecutableObservation(new JSONObject());for(var keys=identity.keys();keys.hasNext();){String key=keys.next();evidence.put(key,identity.get(key));}
        observation.check();return binding.put("supported",supported).put("sourceEvidence",evidence);
    }
    /** Revalidate native identity and current remote metadata without spawning another CLI probe. */
    public void checkWorkProfileTargetCurrent(JSONObject reference,JSONObject capturedPublicBinding,dev.pocketengine.core.RunControl observation)throws Exception {
        workProfileReference(reference);java.util.Objects.requireNonNull(capturedPublicBinding);
        for(String key:new String[]{"kind","profileId","profileBinding","environmentId","environmentBinding","worker"})if(!reference.get(key).equals(capturedPublicBinding.opt(key)))throw new SecurityException("工作Profile不是原冻结电脑目标");
        // Frozen Resolver bindings retain exact tuple/evidence, not the initial support decision.
        JSONObject before=workProfileExecutableObservation(capturedPublicBinding.optJSONObject("sourceEvidence")),after=workProfileCurrentBinding(reference,observation).getJSONObject("_entrypointObservation");
        if(capturedPublicBinding.has("supported")&&!Boolean.TRUE.equals(capturedPublicBinding.opt("supported"))||!Boolean.TRUE.equals(before.opt("executableIdentityVerified"))||!Boolean.TRUE.equals(after.opt("executableIdentityVerified"))||!before.getString("executableIdentity").equals(after.getString("executableIdentity")))throw new SecurityException("原CLI入口身份未知或已变化；旧探测不再可用，请重新审阅");
        observation.check();
    }
    /** Entry-point stat only: never binary content, installed version or a launcher-chain proof. */
    private static JSONObject workProfileExecutableObservation(JSONObject actual)throws Exception {
        String digest=actual==null?"":actual.optString("executableIdentity");
        boolean observed=actual!=null&&"native_file_stat_v1".equals(actual.optString("executableIdentityKind"))&&digest.matches("[a-f0-9]{64}")&&Boolean.TRUE.equals(actual.opt("executableIdentityVerified"))&&Boolean.FALSE.equals(actual.opt("versionContentVerified"))&&Boolean.FALSE.equals(actual.opt("executionChainVerified"));
        return new JSONObject().put("executableIdentity",observed?digest:JSONObject.NULL).put("executableIdentityKind",observed?"native_file_stat_v1":"unverified").put("executableIdentityVerified",observed).put("versionContentVerified",false).put("executionChainVerified",false);
    }
    private static Object workProfileBoolean(JSONObject value,String key){Object actual=value.opt(key);return actual instanceof Boolean?actual:JSONObject.NULL;}
    private static void workProfileReference(JSONObject reference)throws Exception {
        if(reference==null)throw new SecurityException("缺少原电脑目标引用");
        for(var keys=reference.keys();keys.hasNext();)if(!java.util.Set.of("kind","profileId","profileBinding","environmentId","environmentBinding","worker","optional").contains(keys.next()))throw new SecurityException("电脑目标不能携带任务、权限、凭据或任意路径");
        if(!"remote_harness".equals(reference.optString("kind")))throw new SecurityException("不是原电脑Harness目标");
        identifier(reference,"profileId");identifier(reference,"environmentId");identifier(reference,"worker");
        for(String key:new String[]{"profileBinding","environmentBinding"})if(!(reference.opt(key) instanceof String)||!reference.getString(key).matches("[a-f0-9]{64}"))throw new SecurityException("电脑目标缺少原绑定版本");
        if(reference.has("optional")&&!(reference.opt("optional") instanceof Boolean))throw new SecurityException("目标optional必须为布尔值");
    }
    private synchronized JSONObject workProfileProfileCurrent(JSONObject reference,dev.pocketengine.core.RunControl observation)throws Exception {
        java.util.Objects.requireNonNull(observation).check();if(closed)throw new SecurityException("原电脑模块已关闭");return requireProfileBinding(reference.getString("profileId"),reference.getString("profileBinding"));
    }
    private JSONObject workProfileCurrentBinding(JSONObject reference,dev.pocketengine.core.RunControl observation)throws Exception {
        JSONObject profile=workProfileProfileCurrent(reference,observation);String worker=reference.getString("worker");if(!worker.matches("codex_app_server|claude|pi"))throw new SecurityException("此Harness适配器尚未实现");
        JSONObject query=new JSONObject().put("profileId",profile.getString("id")).put("_profileBinding",profile.getString("bindingHash"));
        JSONObject environment=RemoteTaskRequest.environment(handleForTask("environments",query,observation),reference.getString("environmentId"),worker);profile=workProfileProfileCurrent(reference,observation);
        String environmentBinding=environment.optString("bindingHash");if(!environmentBinding.matches("[a-f0-9]{64}")||!reference.getString("environmentBinding").equals(environmentBinding))throw new SecurityException("原环境绑定已变化，未应用旧工作Profile");
        JSONObject installed=null;JSONArray workers=environment.getJSONArray("workers");for(int i=0;i<workers.length();i++){JSONObject item=workers.getJSONObject(i);if(worker.equals(item.optString("id")))installed=item;}
        return new JSONObject().put("kind","remote_harness").put("profileId",profile.getString("id")).put("profileBinding",profile.getString("bindingHash")).put("environmentId",environment.getString("id")).put("environmentBinding",environmentBinding).put("worker",worker).put("_entrypointObservation",workProfileExecutableObservation(installed));
    }
    /** Opaque native config/credential generation; contains no token or token-derived digest. */
    public synchronized JSONObject requireProfileBinding(String id,String expected)throws Exception {
        JSONObject current=requireProfileRevision(id,expected);if(!current.optBoolean("hasToken"))throw new SecurityException("电脑访问密钥尚未配置");return current;
    }
    public synchronized String profileBindingHash(String id)throws Exception{return publicProfile(profile(id)).getString("bindingHash");}
    private JSONObject requireProfileRevision(String id,String expected)throws Exception {
        JSONObject current=publicProfile(profile(id));if(!current.getString("bindingHash").equals(expected))throw new SecurityException("电脑连接或访问密钥已变化，请重新选择并核对；没有向新配置派发");return current;
    }
    private JSONObject publicProfile(JSONObject profile)throws Exception {
        JSONObject identity=new JSONObject().put("id",profile.getString("id")).put("baseUrl",endpoint(profile.getString("baseUrl"))).put("bindingRevision",profile.optString("bindingRevision","legacy"));
        return new JSONObject(profile.toString()).put("serviceBase",endpoint(profile.getString("baseUrl"))).put("bindingHash",digest(canonical(identity))).put("hasToken",context.secrets.contains(slot(profile.getString("id"),profile.getString("baseUrl"))));
    }
    private void save()throws Exception {write(file,profiles);}
    private static void write(AtomicFile selected,JSONArray value)throws Exception {FileOutputStream out=null;try{out=selected.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));selected.finishWrite(out);}catch(Exception error){if(out!=null)selected.failWrite(out);throw error;}}
    private static String slot(String id,String base)throws Exception {URI uri=RemoteTransport.validateBase(base);int port=uri.getPort()>=0?uri.getPort():"https".equalsIgnoreCase(uri.getScheme())?443:80;String origin=uri.getScheme().toLowerCase(java.util.Locale.ROOT)+"://"+uri.getHost().toLowerCase(java.util.Locale.ROOT)+":"+port;byte[] hash=MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8));StringBuilder suffix=new StringBuilder();for(int i=0;i<16;i++)suffix.append(String.format(java.util.Locale.ROOT,"%02x",hash[i]));return "remote.token."+id+"."+suffix;}
    private static String required(JSONObject input,String key,int limit)throws Exception {Object value=input.opt(key);if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>limit)throw new IllegalArgumentException("请填写有效的"+key);return ((String)value).trim();}
    private static String identifier(JSONObject input,String key)throws Exception {String id=required(input,key,80);if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("编号无效："+key);return id;}
    @Override public void close(){closed=true;watches.clear();poller.shutdownNow();}
    private static final class Watch{
        final String profileId,jobId,operationId,serviceBase;int failures;
        Watch(String profileId,String jobId,String operationId,String serviceBase){this.profileId=profileId;this.jobId=jobId;this.operationId=operationId;this.serviceBase=serviceBase;}
        void requireReceipt(JSONObject receipt){if(!jobId.equals(receipt.optString("id"))||!operationId.equals(receipt.optString("operationId")))throw new SecurityException("电脑轮询回执不属于原任务或操作");}
    }
}
