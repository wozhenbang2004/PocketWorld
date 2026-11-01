package dev.pocketengine.features.remote;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native explicit private-byte transfer. Not a model tool, transport, runner or persistent task store. */
public final class RemoteInputAttachments {
    private static final class Window {
        final String id,taskId;final Object nativeOwner;
        JSONObject request,environment,scope,actual;JSONArray files=new JSONArray();
        String reviewDigest;long reviewExpiresAt;
        volatile boolean closed,busy;volatile long revision,writtenBytes;volatile int fileIndex=-1;
        volatile String message="请选择要交给此准确目标的私人原件";
        volatile TaskStore.RemoteInputClaim currentClaim;
        Window(String id,String taskId,Object nativeOwner){this.id=id;this.taskId=taskId;this.nativeOwner=nativeOwner;}
    }
    public static final class Approval {
        private final RemoteInputAttachments host;private final TaskStore issuer;private final Window window;
        private final String scope;private final long revision,expires;
        private Approval(RemoteInputAttachments host,Window window,JSONObject scope){
            this.host=host;issuer=host.tasks;this.window=window;this.scope=scope.toString();revision=window.revision;expires=window.reviewExpiresAt;
        }
        public JSONObject view()throws Exception{return new JSONObject(scope);}
        public boolean issuedBy(TaskStore tasks){return issuer==tasks;}
        public long expiresAt(){return expires;}
        /** Deliberately no SQL/Book locks, nested control.check or reconstructed execution authority. */
        public void checkCurrent()throws Exception {
            if(window.closed||host.foregroundOwner!=window.nativeOwner||window.nativeOwner==null||window.revision!=revision||System.currentTimeMillis()>expires)throw new SecurityException("本次原生外传确认已失效，请回前台核对原任务；没有恢复执行权");
        }
    }
    private final FeatureHost features;private final TaskStore tasks;private final EventSink events;
    private final Map<String,Window> windows=new LinkedHashMap<>();
    private volatile Object foregroundOwner;
    public RemoteInputAttachments(FeatureHost features,TaskStore tasks,EventSink events){this.features=features;this.tasks=tasks;this.events=events;}
    public static boolean command(String command){return java.util.Set.of("openInputs","selectInputs","reviewInputs","confirmInputs","inspectInputs","queryInputs","cancelInputs").contains(command);}
    public synchronized void setForeground(Object owner,boolean foreground){
        Object next=foreground?owner:foregroundOwner==owner?null:foregroundOwner;
        if(next!=foregroundOwner){foregroundOwner=next;for(Window window:windows.values()){window.revision++;window.reviewDigest=null;}}
    }
    public synchronized void close(String id){Window window=windows.remove(id);if(window!=null){window.closed=true;window.revision++;window.reviewDigest=null;}}
    private RemoteModule remote(){Object module=features.module("remote");if(!(module instanceof RemoteModule))throw new IllegalStateException("原电脑模块不可用");return (RemoteModule)module;}
    private AttachmentsModule documents(){Object module=features.module("attachments");if(!(module instanceof AttachmentsModule))throw new IllegalStateException("原私人附件模块不可用");return (AttachmentsModule)module;}
    private AttachmentBook book(){return remote().resultAttachmentBook();}
    private void privateFile(String id)throws Exception {
        documents().requireOwnerAttachment(id);JSONObject file=book().get(id),source=file.optJSONObject("source");
        if(!file.optString("principalId").equals("owner")||!file.optString("spaceId").equals("private")||source!=null&&source.has("groupSpaceId"))throw new SecurityException("群技术副本或其他空间不能当作私人原件外传");
        if(source!=null&&!source.optString("taskId").isEmpty()){
            JSONObject task=tasks.get(source.getString("taskId"),false),origin=task==null?null:task.optJSONObject("origin");
            if(task!=null&&(!task.optString("actorId").equals("owner")||!task.optString("spaceId").equals("private")||origin!=null&&origin.optString("kind").matches("group|channel|reviewed_external_group")))throw new SecurityException("外部群/渠道成果须通过其原受众权限，不能改作私人附件");
        }
    }
    private void foreground(Window window)throws Exception {
        if(window.closed||window.nativeOwner==null||foregroundOwner!=window.nativeOwner)throw new SecurityException("请在真实前台原生页面核对私人附件外传");
    }
    private void profile(JSONObject scope)throws Exception {
        JSONObject current=remote().requireProfileBinding(scope.getString("profileId"),scope.getString("profileGeneration"));
        if(!scope.getString("serviceBase").equals(current.getString("serviceBase")))throw new SecurityException("目标地址已改变，未向新地址外传");
    }
    private JSONObject environment(JSONObject request)throws Exception {
        String id=request.getString("profileId"),binding=request.getString("_profileBinding");
        remote().requireProfileBinding(id,binding);
        JSONObject actual=remote().handle("environments",new JSONObject().put("profileId",id).put("_profileBinding",binding));
        JSONObject env=RemoteTaskRequest.environment(actual,request.getString("environmentId"),request.getString("worker"));
        if(!request.getString("environmentBinding").equals(env.optString("bindingHash")))throw new SecurityException("所选环境代次已变化，请重新读取；旧附件不会重传");
        RemoteTaskRequest.requireInputCapability(env);return env;
    }
    private RunControl readControl(Window window,long revision)throws Exception {
        RunControl control=new RunControl();control.retainAuthorityCheck(()->{
            foreground(window);if(window.revision!=revision)throw new SecurityException("本次附件选择或前台确认已变化");
            if(window.scope!=null)profile(window.scope);else remote().requireProfileBinding(window.request.getString("profileId"),window.request.getString("_profileBinding"));
        });return control;
    }
    public JSONObject handle(Object nativeOwner,String command,JSONObject input)throws Exception {
        java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Set.of("windowId","taskId"));
        if(command.equals("openInputs"))allowed.add("request");
        if(command.equals("selectInputs"))allowed.add("files");
        if(command.equals("confirmInputs")){allowed.add("digest");allowed.add("billingAcknowledged");}
        for(java.util.Iterator<String> keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("原生附件流程不接受JSON提供的执行权、凭据或任意目标");
        String id=input.getString("windowId"),taskId=input.getString("taskId");
        if(command.equals("openInputs"))return open(nativeOwner,id,taskId,input.optJSONObject("request"));
        Window window;synchronized(this){window=windows.get(id);if(window==null||window.nativeOwner!=nativeOwner||!window.taskId.equals(taskId))throw new SecurityException("原附件页面已关闭或不属于此任务");foreground(window);}
        if(command.equals("inspectInputs"))return response(window);
        if(command.equals("cancelInputs"))return cancel(window);
        synchronized(this){if(window.busy)throw new IllegalStateException("正在处理原附件请求；可查看进度或停止，不并发另一外传");}
        if(command.equals("selectInputs"))return select(window,input.getJSONArray("files"));
        if(command.equals("reviewInputs"))return review(window);
        if(command.equals("queryInputs")){query(window);return response(window);}
        if(command.equals("confirmInputs"))return confirm(window,input);
        throw new IllegalArgumentException("未知原生附件动作");
    }
    private JSONObject open(Object owner,String id,String taskId,JSONObject supplied)throws Exception {
        Window window=new Window(id,taskId,owner);
        synchronized(this){foreground(window);if(!id.matches("[a-f0-9-]{36}")||!taskId.matches("[a-f0-9-]{36}")||windows.containsKey(id)||windows.size()>=4)throw new SecurityException("请重新打开原附件页面");}
        if(supplied!=null){
            if(tasks.get(taskId,false)!=null)throw new SecurityException("新附件任务不能覆盖已有任务");
            JSONObject request=RemoteTaskRequest.first(supplied);
            if(!request.getString("worker").matches("codex_app_server|claude")||request.getString("prompt").length()>8000)throw new SecurityException("私人附件首片只支持新Codex/Claude目标，最多8000字符");
            window.request=request;window.environment=environment(request);
        }else{
            window.scope=tasks.originalRemoteInputScope(taskId);window.request=window.scope.getJSONObject("request");
            window.files=RemoteTaskRequest.inputManifest(window.scope.getJSONArray("files"));
            try{profile(window.scope);window.environment=environment(window.request);}
            catch(Exception offline){window.environment=new JSONObject().put("workspace",window.scope.getString("workspace")).put("current",false);window.message="原范围只读显示；未能按原配置核对，重新审阅成功前不能外传或启动";}
        }
        synchronized(this){foreground(window);if(windows.containsKey(id)||windows.size()>=4)throw new SecurityException("原页面登记已变化");windows.put(id,window);}
        return response(window);
    }
    private JSONObject select(Window window,JSONArray supplied)throws Exception {
        long revision;synchronized(this){foreground(window);if(tasks.get(window.taskId,false)!=null||window.busy)throw new SecurityException("原任务已登记，所选附件与目标不能改绑");revision=window.revision;}
        if(supplied.length()>8)throw new SecurityException("请明确选择1至8个私人原件");
        JSONArray selected=new JSONArray();RunControl read=readControl(window,revision);
        for(int i=0;i<supplied.length();i++){
            JSONObject ref=supplied.getJSONObject(i);if(ref.length()!=2||!ref.has("id")||!ref.has("sha256"))throw new SecurityException("只接受原附件ID与明确hash，不接受路径或模型正文");
            String original=ref.getString("id"),sha=ref.getString("sha256");privateFile(original);
            AttachmentBook.VerifiedMetadata actual=book().readVerifiedMetadata(original,sha,read);privateFile(original);read.check();
            selected.put(new JSONObject().put("attachmentId",actual.id).put("name",actual.name).put("mimeType",actual.mimeType).put("bytes",actual.size).put("sha256",actual.sha256));
        }
        if(selected.length()>0)selected=RemoteTaskRequest.inputManifest(selected);
        synchronized(this){foreground(window);if(window.revision!=revision||tasks.get(window.taskId,false)!=null||window.busy)throw new SecurityException("附件选择期间页面或原任务已变化");window.files=selected;window.scope=null;window.reviewDigest=null;window.revision++;}
        return response(window);
    }
    private JSONObject scope(Window window)throws Exception {
        if(window.scope!=null)return new JSONObject(window.scope.toString());
        JSONArray files=RemoteTaskRequest.inputManifest(window.files);JSONObject request=new JSONObject(window.request.toString());
        JSONObject selected=remote().requireProfileBinding(request.getString("profileId"),request.getString("_profileBinding"));
        return new JSONObject().put("taskId",window.taskId).put("jobOperationId",window.taskId).put("profileId",request.getString("profileId"))
            .put("profileGeneration",request.getString("_profileBinding")).put("serviceBase",selected.getString("serviceBase"))
            .put("request",request).put("environmentBinding",request.getString("environmentBinding")).put("workspace",window.environment.getString("workspace"))
            .put("files",files).put("manifestHash",RemoteTaskRequest.inputHash(files)).put("inputDirectory",".pocketworld-inputs/"+window.taskId)
            .put("explicitExternalTransfer",true).put("selectedPrivateBytesOnly",true).put("billingAcknowledged",true).put("osIsolationVerified",false);
    }
    private JSONObject review(Window window)throws Exception {
        long revision;synchronized(this){foreground(window);revision=window.revision;}
        JSONObject exact=scope(window);profile(exact);JSONObject actualEnv=environment(window.request);
        if(!window.environment.getString("workspace").equals(actualEnv.optString("workspace")))throw new SecurityException("原工作区改变，旧选择不能外传");
        RunControl read=readControl(window,revision);JSONArray files=exact.getJSONArray("files");
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);String id=file.getString("attachmentId");privateFile(id);AttachmentBook.VerifiedMetadata actual=book().readVerifiedMetadata(id,file.getString("sha256"),read);privateFile(id);
            if(actual.size!=file.getLong("bytes")||!actual.name.equals(file.getString("name"))||!actual.mimeType.equals(file.getString("mimeType")))throw new SecurityException("原件声明或物理内容已变，请重新选择");}
        synchronized(this){foreground(window);if(window.revision!=revision)throw new SecurityException("选择期间前台状态改变");window.scope=exact;window.reviewDigest=TaskStore.capabilityFingerprint(exact);window.reviewExpiresAt=System.currentTimeMillis()+60_000L;}
        return response(window);
    }
    private JSONObject confirm(Window window,JSONObject input)throws Exception {
        Approval approval;synchronized(this){foreground(window);
            if(window.busy||window.reviewDigest==null||!window.reviewDigest.equals(input.getString("digest"))||System.currentTimeMillis()>window.reviewExpiresAt||input.opt("billingAcknowledged")!=Boolean.TRUE)throw new SecurityException("请重新核对准确目标、字节与远端CLI费用边界后确认");
            approval=new Approval(this,window,new JSONObject(window.scope.toString()));window.busy=true;window.message="先登记原任务；ready前不启动worker";
        }
        try{
            profile(approval.view());tasks.beginRemoteInput(approval);emit(window.taskId);
            TaskStore.RemoteInputClaim create=tasks.claimRemoteInputAction(approval,TaskStore.RemoteInputAction.CREATE,-1);window.currentClaim=create;
            if(create.canDispatch()){
                JSONObject actual=remote().submitInputJob(create);tasks.recordRemoteInputReceipt(create,actual);
                if(!actual.has("inputState")){window.message="原创建回执未确认或拒绝；只查询原编号，不再create";return finished(window);}window.actual=actual;
            }else query(window);
            JSONObject task=tasks.get(window.taskId,false),state=task.optJSONObject("remoteInputState");
            if(state==null){window.message="尚无原job/附件接收回执，只查询原编号";return finished(window);}
            JSONArray files=approval.view().getJSONArray("files");
            for(int i=0;i<files.length();i++){
                JSONObject slot=state.getJSONArray("files").getJSONObject(i);if(slot.optString("state").equals("verified"))continue;
                if(!state.optString("phase").matches("awaiting_upload|uploading")||!slot.optString("state").equals("pending")){window.message="原传输效果未确认，只查询或停止原job，不重传此body";return finished(window);}
                TaskStore.RemoteInputClaim claim=tasks.claimRemoteInputAction(approval,TaskStore.RemoteInputAction.UPLOAD,i);window.currentClaim=claim;
                if(!claim.canDispatch()){window.message="原文件已领取，不能再发送body；请查询原job";return finished(window);}
                JSONObject file=files.getJSONObject(i);String id=file.getString("attachmentId");privateFile(id);
                byte[] bytes=book().readVerifiedSnapshot(id,file.getString("sha256"),readControl(window,approval.revision));
                try{
                    approval.checkCurrent();privateFile(id);RunControl transfer=new RunControl();
                    transfer.retainAuthorityCheck(()->{approval.checkCurrent();profile(approval.view());claim.checkCurrent();privateFile(id);});
                    window.fileIndex=i;window.writtenBytes=0;window.message="正在发送准确原件；已写入传输不等于远端hash已核对";
                    JSONObject actual=remote().uploadInputFile(claim,new ByteArrayInputStream(bytes),transfer,count->window.writtenBytes=count);
                    tasks.recordRemoteInputReceipt(claim,actual);window.actual=actual;state=tasks.get(window.taskId,false).getJSONObject("remoteInputState");
                    if(!state.getJSONArray("files").getJSONObject(i).optString("state").equals("verified")){window.message="原文件回执未确认，只查询，不重传";return finished(window);}
                }finally{Arrays.fill(bytes,(byte)0);}
            }
            if(!state.optString("phase").equals("ready")){window.message="原worker已启动/结算或阶段未ready；只核对原job，不再次启动";return finished(window);}
            TaskStore.RemoteInputClaim start=tasks.claimRemoteInputAction(approval,TaskStore.RemoteInputAction.START,-1);window.currentClaim=start;
            JSONObject started=null;if(start.canDispatch()){started=remote().startInputJob(start);tasks.recordRemoteInputReceipt(start,started);}else query(window);
            window.message=started!=null&&!started.has("inputState")?"原worker启动是否被接收仍未确认或已拒绝；只查询原job，不重复启动":"原worker启动阶段已登记，按真实回执核对；工具审批与业务结果仍独立";emit(window.taskId);return finished(window);
        }catch(Exception failure){
            TaskStore.RemoteInputClaim claim=window.currentClaim;if(claim!=null)try{tasks.remoteInputUnconfirmed(claim);}catch(Exception reconcile){failure.addSuppressed(reconcile);}
            window.message="本次没有继续后继派发；若已发送则效果待核对，只查询原job。"+failure.getMessage();emitIfRegistered(window.taskId);return finished(window);
        }finally{window.busy=false;window.currentClaim=null;}
    }
    private void query(Window window)throws Exception {
        foreground(window);JSONObject scope=tasks.originalRemoteInputScope(window.taskId);profile(scope);
        JSONObject task=tasks.get(window.taskId,false),actual=remote().queryInputJob(scope,task.optString("remoteInputJobId"));
        if(actual.has("inputState"))tasks.reconcileRemoteInputs(window.taskId,actual);window.actual=actual;
        window.message=actual.has("inputState")?"已核对原job，不重传/重建；worker与副本状态见真实回执":"原接收未确认；没有重新create或发送body";emitIfRegistered(window.taskId);
    }
    private JSONObject cancel(Window window)throws Exception {
        synchronized(this){foreground(window);window.revision++;window.reviewDigest=null;}
        JSONObject task=tasks.get(window.taskId,false);if(task==null){window.message="尚未登记原任务，没有远端发送或删除声明";return response(window);}
        JSONObject scope=tasks.originalRemoteInputScope(window.taskId);tasks.requestCancel(window.taskId,scope.getString("jobOperationId"));
        TaskStore.RemoteInputClaim claim=window.currentClaim;if(claim!=null)tasks.remoteInputUnconfirmed(claim);
        String job=tasks.get(window.taskId,false).optString("remoteInputJobId");
        if(job.isEmpty()){
            try{tasks.cancelRemoteInputBeforeJob(window.taskId);window.message="原prepared证明未派发，已本机取消；不代表远端文件删除";}
            catch(SecurityException unknown){window.message="创建是否已接收未知，只查询原操作；未声称取消或删除远端副本";}
        }else{
            profile(scope);JSONObject actual=remote().handle("cancelJob",new JSONObject().put("profileId",scope.getString("profileId")).put("serviceBase",scope.getString("serviceBase")).put("_profileBinding",scope.getString("profileGeneration")).put("jobId",job).put("jobOperationId",scope.getString("jobOperationId")));
            tasks.updateExternal(window.taskId,actual);window.message="已请求停止原job；按真实回执判断，已上传或部分副本没有删除保证";
        }
        emit(window.taskId);return response(window);
    }
    private JSONObject finished(Window window)throws Exception {JSONObject result=response(window);result.getJSONObject("snapshot").put("busy",false);return result;}
    private JSONObject response(Window window)throws Exception {
        JSONObject task=tasks.get(window.taskId,false);JSONArray available=new JSONArray();
        JSONArray all=task==null?book().list():new JSONArray();for(int i=0;i<all.length();i++){JSONObject file=all.getJSONObject(i);try{privateFile(file.getString("id"));JSONObject view=new JSONObject();for(String key:new String[]{"id","name","mimeType","size","sha256"})view.put(key,file.get(key));available.put(view);}catch(SecurityException outsidePrivateScope){}}
        JSONObject snapshot=new JSONObject().put("windowId",window.id).put("taskId",window.taskId).put("request",window.request).put("environment",window.environment)
            .put("files",new JSONArray(window.files.toString())).put("availableFiles",available).put("registered",task!=null).put("busy",window.busy)
            .put("message",window.message).put("fileIndex",window.fileIndex).put("writtenBytes",window.writtenBytes).put("writtenBytesAreReceiverVerified",false);
        if(window.scope!=null)snapshot.put("scope",new JSONObject(window.scope.toString()));
        if(window.reviewDigest!=null)snapshot.put("review",new JSONObject().put("digest",window.reviewDigest).put("expiresAt",window.reviewExpiresAt));
        if(task!=null)snapshot.put("task",task).put("jobId",task.optString("remoteInputJobId")).put("inputState",task.optJSONObject("remoteInputState"));
        return new JSONObject(new JSONObject().put("snapshot",snapshot).toString());
    }
    private void emitIfRegistered(String id)throws Exception{if(tasks.get(id,false)!=null)emit(id);}
    private void emit(String id)throws Exception{events.emit(new JSONObject().put("type","task").put("task",tasks.get(id,false)));}
}
