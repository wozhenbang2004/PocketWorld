package dev.pocketengine.features;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.features.remote.RemoteModule;
import dev.pocketengine.features.teams.TeamsModule;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner page only. Transport modules and model JSON cannot create these ephemeral reviews. */
public final class RemoteOwnerWorkflows {
    private static final class Window {final String id,task;final Object nativeOwner;JSONObject source;String review,profileBinding;JSONObject result,artifact,snapshotFile;long reviewedAt,readGeneration,lastReadAttemptAt,lastSuccessfulReadAt;String queryStatus="not_queried";volatile long lifecycleRevision;volatile boolean closed;boolean fresh,savingResult;JSONObject directory;final Map<String,JSONObject> selectedFiles=new LinkedHashMap<>();TaskStore.TeamRemoteOwnerWindow teamWindow;final Map<String,TaskStore.TeamRemoteOwnerReview> teamReviews=new LinkedHashMap<>();Window(String id,String task,Object nativeOwner){this.id=id;this.task=task;this.nativeOwner=nativeOwner;}}
    private final FeatureHost host;private final TaskStore tasks;private final EventSink events;
    private final dev.pocketengine.features.remote.RemoteInputAttachments inputs;
    private final Map<String,Window> windows=new LinkedHashMap<>();
    private volatile Object foregroundOwner;
    /** Actual Activity resume/pause only; foregrounding never restores an old review. */
    public void setForeground(Object nativeOwner,boolean value){
        java.util.Objects.requireNonNull(nativeOwner);synchronized(this){Object next=value?nativeOwner:foregroundOwner==nativeOwner?null:foregroundOwner;if(next!=foregroundOwner){foregroundOwner=next;for(Window window:windows.values()){window.lifecycleRevision++;window.readGeneration++;window.queryStatus="paused";window.fresh=false;window.review=null;window.teamReviews.clear();if(window.teamWindow!=null)window.teamWindow.setForeground(window.nativeOwner==foregroundOwner);}}}
        inputs.setForeground(nativeOwner,value);
        if(value)try{module().requestReadRefresh();}catch(Exception unavailable){/* Read recovery only; explicit original query remains available. */}
    }

    public RemoteOwnerWorkflows(FeatureHost host,TaskStore tasks,EventSink events){this.host=host;this.tasks=tasks;this.events=events;inputs=new dev.pocketengine.features.remote.RemoteInputAttachments(host,tasks,events);}
    /** In-memory invalidation only; safe at native page disposal, before any queued worker can claim. */
    public synchronized void close(String id){inputs.close(id);Window window=windows.remove(id);if(window!=null){window.closed=true;window.lifecycleRevision++;if(window.teamWindow!=null)window.teamWindow.close();}}
    public JSONObject handle(String command,JSONObject input)throws Exception{return handle(null,command,input);}
    public JSONObject handle(Object nativeOwner,String command,JSONObject input)throws Exception{
        if(dev.pocketengine.features.remote.RemoteInputAttachments.command(command))return inputs.handle(nativeOwner,command,input);
        java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Set.of("windowId","taskId","reviewId"));
        if(command.equals("continue")){allowed.add("prompt");allowed.add("snapshotId");allowed.add("snapshotVersion");}if(command.equals("captureSnapshot"))allowed.add("paths");if(command.equals("readSnapshot")){allowed.add("snapshotId");allowed.add("path");}if(command.equals("readArtifact"))allowed.add("path");if(command.equals("approve")){allowed.add("requestId");allowed.add("decision");}if(command.equals("answerQuestion")){allowed.add("requestId");allowed.add("requestHash");allowed.add("answers");}
        if(command.equals("listResultFiles")){allowed.add("path");allowed.add("offset");}if(command.equals("selectResultFile"))allowed.add("path");if(command.equals("saveSnapshot")){allowed.add("snapshotId");allowed.add("path");}
        java.util.Iterator<String> fields=input.keys();while(fields.hasNext())if(!allowed.contains(fields.next()))throw new SecurityException("原生审阅不接受外部提供的来源或执行身份");
        String id=input.getString("windowId");
        if(command.equals("open"))synchronized(this){if(!id.matches("[a-f0-9-]{36}")||windows.containsKey(id)||windows.size()>=4)throw new SecurityException("请重新打开原任务审阅页");Window opened=new Window(id,input.getString("taskId"),nativeOwner);JSONObject task=tasks.get(opened.task,false);if(task!=null&&task.optJSONObject("origin")!=null&&task.getJSONObject("origin").optString("kind").equals("team_remote_assignment")){if(nativeOwner==null||foregroundOwner!=nativeOwner)throw new SecurityException("原team远端审批只允许真实前台Activity创建窗口");opened.teamWindow=tasks.openTeamRemoteOwnerWindow();opened.teamWindow.setForeground(true);}windows.put(id,opened);}
        Window window; synchronized(this){window=required(id);if(!window.task.equals(input.getString("taskId"))||window.nativeOwner!=nativeOwner)throw new SecurityException("页面不属于该原任务/Activity");}
        if(command.equals("open")||command.equals("refresh"))return refresh(window);
        if(window.teamWindow!=null&&(command.equals("continue")||command.equals("captureSnapshot")))throw new SecurityException("团队远端制作必须回原assignment宿主处理续接/贡献冻结；此页只查原job与单次请求");
        if(command.equals("continue"))return continueSession(window,input);
        if(command.equals("approve"))return approve(window,input);
        if(command.equals("answerQuestion"))return answerQuestion(window,input);
        if(command.equals("readArtifact"))return readArtifact(window,input);
        if(command.equals("captureSnapshot"))return captureSnapshot(window,input);
        if(command.equals("readSnapshot"))return readSnapshot(window,input);
        if(command.equals("listResultFiles"))return listResultFiles(window,input);
        if(command.equals("selectResultFile"))return selectResultFile(window,input);
        if(command.equals("captureSelectedSnapshot"))return captureSelectedSnapshot(window,input);
        if(command.equals("saveSnapshot"))return saveSnapshot(window,input);
        throw new IllegalArgumentException("未知原生电脑任务操作");
    }
    private RemoteModule module(){EngineModule module=host.module("remote");if(!(module instanceof RemoteModule))throw new IllegalStateException("原电脑执行模块不可用");return (RemoteModule)module;}
    /** Page observation only. No execution RunControl, new task, or retry authority is created. */
    private synchronized void checkReadCurrent(Window window,long lifecycle,long generation)throws Exception {
        if(required(window.id)!=window||window.closed||window.lifecycleRevision!=lifecycle||window.readGeneration!=generation
                ||window.nativeOwner!=null&&foregroundOwner!=window.nativeOwner)throw new SecurityException("原读取页面已关闭、切后台或被新查询替代；迟到GET没有成为新审阅");
    }
    private static boolean sameReadTarget(JSONObject before,JSONObject after,boolean acceptedJob)throws Exception {
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobOperationId"})if(!before.get(key).equals(after.opt(key)))return false;
        return before.optString("jobId").equals(after.optString("jobId"))||acceptedJob&&before.optString("jobId").isEmpty();
    }
    private JSONObject refresh(Window window)throws Exception{
        long lifecycle,generation;synchronized(this){lifecycle=window.lifecycleRevision;generation=++window.readGeneration;checkReadCurrent(window,lifecycle,generation);window.fresh=false;window.review=null;window.queryStatus="querying";window.lastReadAttemptAt=System.currentTimeMillis();}
        JSONObject target=tasks.remoteOwnerTarget(window.task),payload=new JSONObject().put("profileId",target.getString("profileId")).put("serviceBase",target.getString("serviceBase"));String job=target.optString("jobId");
        String capturedBinding;JSONObject result;try{
            capturedBinding=module().profileBindingHash(target.getString("profileId"));payload.put("_profileBinding",capturedBinding);
            checkReadCurrent(window,lifecycle,generation);
            if(!sameReadTarget(target,tasks.remoteOwnerTarget(window.task),false))throw new SecurityException("读取前原task/run/job已变化");
            if(job.isEmpty())result=module().handle("reconcileOperation",payload.put("operationId",target.getString("jobOperationId")));
            else result=module().handle("getJob",payload.put("jobId",job).put("jobOperationId",target.getString("jobOperationId")));
            // The original HTTP timeout remains bounded. A dispatched GET may still finish;
            // leaving this page invalidates its observation, not the remote execution.
            checkReadCurrent(window,lifecycle,generation);
            if(!capturedBinding.equals(module().profileBindingHash(target.getString("profileId"))))throw new SecurityException("查询期间电脑配置或凭据已变化");
            if(!sameReadTarget(target,tasks.remoteOwnerTarget(window.task),true))throw new SecurityException("读取期间原task/run/job已变化");
        }catch(Exception unavailable){
            synchronized(this){checkReadCurrent(window,lifecycle,generation);window.queryStatus="unconfirmed";}
            JSONObject unchanged=response(window,false,null,lifecycle,generation);
            unchanged.getJSONObject("snapshot").put("queryStatus","unconfirmed").put("reason","未能按原电脑地址核对；没有改用新地址、重提任务或授予续接。请恢复原连接后查询");
            return unchanged;
        }
        checkReadCurrent(window,lifecycle,generation);
        tasks.updateExternal(window.task,result);if(result.has("inputState")&&tasks.get(window.task,false).has("remoteInputScope"))tasks.reconcileRemoteInputs(window.task,result);emitTask(window.task);
        JSONArray pendingSnapshots=tasks.pendingRemoteResultSnapshots(window.task);for(int i=0;i<pendingSnapshots.length();i++){
            checkReadCurrent(window,lifecycle,generation);
            JSONObject pending=pendingSnapshots.getJSONObject(i),source=pending.getJSONObject("source");JSONObject query=new JSONObject(source.toString()).put("operationId",pending.getString("operationId")).put("_profileBinding",capturedBinding);
            try{JSONObject actual=module().reconcileResultSnapshot(query);checkReadCurrent(window,lifecycle,generation);tasks.recordRemoteResultSnapshot(window.task,pending.getString("operationId"),actual);}catch(Exception unavailable){checkReadCurrent(window,lifecycle,generation);/* Original unknown stays query-only, never capture again. */}
        }
        if(pendingSnapshots.length()>0)emitTask(window.task);
        JSONObject current=tasks.remoteOwnerTarget(window.task);boolean same=true;
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobOperationId"})if(!target.get(key).equals(current.opt(key)))same=false;
        if(!job.isEmpty()&&!job.equals(current.optString("jobId")))same=false;
        if(!result.optString("status").equals(current.optString("executorStatus")))same=false;
        synchronized(this){checkReadCurrent(window,lifecycle,generation);if(!capturedBinding.equals(module().profileBindingHash(target.getString("profileId")))){window.queryStatus="changed";window.result=null;window.artifact=null;window.snapshotFile=null;window.lastSuccessfulReadAt=0;return response(window,false,null,lifecycle,generation);}window.result=same?dev.pocketengine.features.remote.RemoteResultSnapshot.job(result,current):null;window.artifact=null;window.snapshotFile=null;if(window.profileBinding!=null&&!window.profileBinding.equals(capturedBinding)){window.directory=null;window.selectedFiles.clear();}window.profileBinding=capturedBinding;window.queryStatus=same?"confirmed":"changed";if(same){window.lastSuccessfulReadAt=System.currentTimeMillis();window.source=new JSONObject(current.toString());}}
        if(window.teamWindow!=null)prepareTeamReviews(window,current);
        return response(window,same,null,lifecycle,generation);
    }
    private synchronized Window required(String id){Window window=windows.get(id);if(window==null)throw new SecurityException("原生审阅页已关闭，本次没有派发；请重新打开核对");return window;}
    private void checkReview(Window window,String review)throws Exception{
        if(required(window.id)!=window||!window.fresh||window.source==null||!review.equals(window.review)||android.os.SystemClock.uptimeMillis()-window.reviewedAt>60_000L)throw new SecurityException("本次原生审阅已过期，请查询原任务后重新确认");
        if(window.teamWindow!=null&&foregroundOwner!=window.nativeOwner)throw new SecurityException("原审批Activity已不在前台");
        if(window.profileBinding==null||!window.profileBinding.equals(module().profileBindingHash(window.source.getString("profileId"))))throw new SecurityException("审阅后的电脑配置或凭据已变化，请查询原任务后重新确认；未派发旧决定");
    }
    private JSONObject continueSession(Window window,JSONObject input)throws Exception{
        JSONObject selected=null;String capturedBinding;
        synchronized(this){checkReview(window,input.getString("reviewId"));capturedBinding=window.profileBinding;if(input.has("snapshotId")||input.has("snapshotVersion")){selected=tasks.remoteResultSnapshot(window.task,input.getString("snapshotId"));if(!selected.getString("snapshotVersion").equals(input.getString("snapshotVersion"))||!window.source.getString("jobId").equals(selected.getString("jobId")))throw new SecurityException("选择的不是原任务当前job的同一快照版本");}}
        if(selected!=null){JSONObject source=selected.getJSONObject("source"),check=module().readResultSnapshot(new JSONObject(source.toString()).put("snapshotId",selected.getString("snapshotId")).put("snapshotVersion",selected.getString("snapshotVersion")).put("verifyCurrent",true).put("_profileBinding",capturedBinding));if(!check.optBoolean("matchesCurrent")||!selected.getString("snapshotId").equals(check.optString("snapshotId"))||!selected.getString("snapshotVersion").equals(check.optString("snapshotVersion")))throw new SecurityException("工作区已不是所审查快照版本，未登记或提交新续接");}
        JSONObject operation,payload; synchronized(this){checkReview(window,input.getString("reviewId"));window.result=null;window.artifact=null;window.snapshotFile=null;window.fresh=false;window.review=null;synchronized(tasks){operation=tasks.prepareRemoteContinuation(window.source,input.getString("prompt"),selected);payload=tasks.claimRemoteContinuation(window.task,operation.getString("operationId")).put("_profileBinding",capturedBinding);}}
        emitTask(window.task);JSONObject result;
        try{result=module().handle("submitJob",payload);}catch(Exception unknown){result=new JSONObject().put("operationId",payload.getString("operationId")).put("profileId",payload.getString("profileId")).put("serviceBase",payload.getString("serviceBase")).put("status","unknown").put("message","续接回执未确认；只查询原登记操作，未再次提交");}
        tasks.updateExternal(window.task,result);emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,operation.getString("operationId")));
    }
    private JSONObject approve(Window window,JSONObject input)throws Exception{
        if(window.teamWindow!=null)return approveTeam(window,input);
        JSONObject claim,source;String capturedBinding;String request=input.getString("requestId"),decision=input.getString("decision");
        synchronized(this){checkReview(window,input.getString("reviewId"));source=new JSONObject(window.source.toString());capturedBinding=window.profileBinding;checkApprovalContext(source,request);window.fresh=false;window.review=null;claim=tasks.claimRemoteApproval(source,request,decision);}
        if(claim.optBoolean("knownDecision"))return refresh(window); // Original GET only; never send a second decision.
        JSONObject payload=new JSONObject().put("profileId",source.getString("profileId")).put("serviceBase",source.getString("serviceBase")).put("jobId",source.getString("jobId")).put("jobOperationId",source.getString("jobOperationId")).put("approvalRequestId",request).put("decision",decision).put("_profileBinding",capturedBinding);
        try{JSONObject result=module().handle("approveJob",payload);tasks.recordRemoteApprovalResult(window.task,claim.getString("operationId"),result);}catch(Exception unknown){/* Durable sending/unknown claim is query-only, including after page exit/restart. */}
        emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,claim.getString("operationId")));
    }
    private JSONObject answerQuestion(Window window,JSONObject input)throws Exception {
        JSONObject claim,source;String binding;long revision;
        synchronized(this){checkReview(window,input.getString("reviewId"));if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner||window.teamWindow!=null)throw new SecurityException("正常回答只允许此原生前台私人会话窗口；不能借私人入口继续团队");source=new JSONObject(window.source.toString());binding=window.profileBinding;revision=window.lifecycleRevision;claim=tasks.claimRemoteQuestionAnswer(source,input.getString("requestId"),input.getString("requestHash"),input.getJSONObject("answers"));window.fresh=false;window.review=null;}
        if(claim.optBoolean("knownAnswer"))return refresh(window);String operation=claim.getString("operationId");JSONObject payload=new JSONObject(source.toString());JSONObject body=claim.getJSONObject("request");for(java.util.Iterator<String> keys=body.keys();keys.hasNext();){String key=keys.next();payload.put(key,body.get(key));}payload.put("_profileBinding",binding);
        try{JSONObject actual=module().answerQuestionJob(payload,()->{synchronized(RemoteOwnerWorkflows.this){if(windows.get(window.id)!=window||foregroundOwner!=window.nativeOwner||revision!=window.lifecycleRevision)throw new SecurityException("原正常问答窗口已关闭或切后台");tasks.beforeRemoteQuestionAnswerDispatch(window.task,operation);}});tasks.recordRemoteQuestionAnswerResult(window.task,operation,actual);}catch(Exception unconfirmed){tasks.remoteQuestionAnswerUnconfirmed(window.task,operation);}
        emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,operation));
    }
    private void prepareTeamReviews(Window window,JSONObject source)throws Exception {
        synchronized(this){window.teamReviews.clear();JSONArray pending=source.optJSONArray("pendingApprovals");for(int i=0;pending!=null&&i<pending.length();i++){JSONObject request=pending.getJSONObject(i);try{TaskStore.TeamRemoteOwnerReview review=tasks.reviewTeamRemotePending(window.teamWindow,window.task,source.getString("runId"),source.getInt("coordinatorEpoch"),request.getString("requestId"),TaskStore.capabilityFingerprint(request));window.teamReviews.put(request.getString("requestId"),review);}catch(SecurityException stale){/* Data remains visible; no owner authority is silently reconstructed. */}}}
    }
    private JSONObject approveTeam(Window window,JSONObject input)throws Exception {
        TaskStore.TeamRemoteClaim claim;JSONObject source;String binding;String decision=input.getString("decision");if(!decision.matches("accept|decline"))throw new SecurityException("此团队scope只提供单次允许或拒绝；停止原job请回原团队");
        synchronized(this){checkReview(window,input.getString("reviewId"));TaskStore.TeamRemoteOwnerReview review=window.teamReviews.get(input.getString("requestId"));if(review==null)throw new SecurityException("原team/parent/请求scope已变化或不在前台，请重新查询原任务");JSONObject reviewed=review.view();source=new JSONObject(window.source.toString());binding=window.profileBinding;if(!binding.equals(reviewed.getJSONObject("scope").getString("profileGeneration")))throw new SecurityException("原制作的电脑凭据generation已变，旧scope不能授予新审批；回原团队重新审阅");claim=tasks.claimTeamRemoteOwnerApproval(review,TaskStore.capabilityFingerprint(reviewed),decision.equals("accept")?TaskStore.TeamRemoteOwnerDecision.APPROVE_ONCE:TaskStore.TeamRemoteOwnerDecision.REJECT);window.fresh=false;window.review=null;window.teamReviews.clear();}
        if(!claim.canDispatch())return refresh(window);JSONObject payload=new JSONObject(source.toString()).put("approvalRequestId",input.getString("requestId")).put("decision",claim.view().getString("decision")).put("_profileBinding",binding);
        try{JSONObject actual=module().approveAssignmentJob(payload,()->tasks.beforeTeamRemoteOwnerDispatch(claim));tasks.recordTeamRemoteReceipt(claim,actual);}catch(Exception unknown){/* Original durable claim is query-only; never resend or wake a model. */}emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,claim.view().getString("operationId")));
    }
    private JSONObject readArtifact(Window window,JSONObject input)throws Exception {
        JSONObject source;String capturedBinding;synchronized(this){checkReview(window,input.getString("reviewId"));source=new JSONObject(window.source.toString());capturedBinding=window.profileBinding;}
        String path=input.getString("path");if(window.teamWindow!=null){JSONArray files=tasks.originalTeamRemoteContext(window.task).view().getJSONArray("files");boolean allowed=false;for(int n=0;n<files.length();n++)if(path.equals(files.getJSONObject(n).getString("path")))allowed=true;if(!allowed)throw new SecurityException("只能查看原selected-file scope内文件");}if(path.trim().isEmpty()||path.length()>2048||path.indexOf('\0')>=0)throw new IllegalArgumentException("请选择原工作区内的相对文件路径");
        JSONObject bytes=module().handle("readArtifact",new JSONObject().put("profileId",source.getString("profileId")).put("serviceBase",source.getString("serviceBase")).put("jobId",source.getString("jobId")).put("jobOperationId",source.getString("jobOperationId")).put("path",path).put("_profileBinding",capturedBinding));
        JSONObject actual=dev.pocketengine.features.remote.RemoteResultSnapshot.artifact(bytes,source,path);
        synchronized(this){checkReview(window,input.getString("reviewId"));JSONObject current=tasks.remoteOwnerTarget(window.task);for(String key:new String[]{"jobId","jobOperationId","serviceBase","environmentId"})if(!source.optString(key).equals(current.optString(key)))throw new SecurityException("文件读取时原任务已改变，请重新核对");window.artifact=actual;}
        return response(window,true,null);
    }
    private JSONObject captureSnapshot(Window window,JSONObject input)throws Exception {
        JSONObject operation,source;String capturedBinding;JSONArray paths=dev.pocketengine.features.remote.RemoteResultSnapshots.paths(input.getJSONArray("paths"));
        synchronized(this){checkReview(window,input.getString("reviewId"));source=new JSONObject(window.source.toString());capturedBinding=window.profileBinding;operation=tasks.claimRemoteResultSnapshot(source,paths);window.fresh=false;window.review=null;window.snapshotFile=null;}
        emitTask(window.task);JSONObject request=new JSONObject(source.toString()).put("operationId",operation.getString("operationId")).put("paths",paths).put("_profileBinding",capturedBinding),result;
        try{result=module().captureResultSnapshot(request);}catch(Exception unavailable){result=new JSONObject().put("status","unknown").put("operationId",operation.getString("operationId"));for(String key:new String[]{"profileId","serviceBase","jobId","jobOperationId","environmentId","worker"})result.put(key,source.get(key));}
        tasks.recordRemoteResultSnapshot(window.task,operation.getString("operationId"),result);emitTask(window.task);return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,operation.getString("operationId")));
    }
    private JSONObject readSnapshot(Window window,JSONObject input)throws Exception {
        JSONObject selected;String capturedBinding;synchronized(this){checkReview(window,input.getString("reviewId"));selected=tasks.remoteResultSnapshot(window.task,input.getString("snapshotId"));capturedBinding=window.profileBinding;}
        String path=input.getString("path");boolean allowed=false;JSONArray manifest=selected.getJSONArray("files");for(int i=0;i<manifest.length();i++)if(path.equals(manifest.getJSONObject(i).getString("path")))allowed=true;if(!allowed)throw new SecurityException("文件不在原快照明确选择范围，未向远端查询");JSONObject source=selected.getJSONObject("source"),bytes=module().readResultSnapshot(new JSONObject(source.toString()).put("snapshotId",selected.getString("snapshotId")).put("snapshotVersion",selected.getString("snapshotVersion")).put("path",path).put("_profileBinding",capturedBinding));
        JSONObject file=dev.pocketengine.features.remote.RemoteResultSnapshots.file(bytes,selected,path);synchronized(this){checkReview(window,input.getString("reviewId"));window.snapshotFile=file;}return response(window,true,null);
    }
    private void checkResultRead(Window window,String review)throws Exception {
        checkReview(window,review);
        if(window.nativeOwner==null||foregroundOwner!=window.nativeOwner||window.closed||window.teamWindow!=null)throw new SecurityException("请从真实前台私人任务页面核对成果");
    }
    private dev.pocketengine.core.RunControl resultReadControl(Window window,String profile,String binding,long revision)throws Exception {
        dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();
        control.retainAuthorityCheck(()->{
            // No SQL or nested control.check: AttachmentBook may hold its own monitor here.
            if(window.closed||window.lifecycleRevision!=revision||foregroundOwner!=window.nativeOwner||window.nativeOwner==null)throw new SecurityException("成果页面已关闭或离开前台");
            if(!binding.equals(module().profileBindingHash(profile)))throw new SecurityException("成果读取期间电脑配置已改变");
        });return control;
    }
    private JSONObject listResultFiles(Window window,JSONObject input)throws Exception {
        JSONObject source;String binding,version="";int offset=input.optInt("offset",0);String path=input.optString("path","");
        synchronized(this){checkResultRead(window,input.getString("reviewId"));source=new JSONObject(window.source.toString());binding=window.profileBinding;
            if(offset>0){JSONObject directory=window.directory;if(directory==null||!path.equals(directory.optString("path"))||directory.isNull("nextOffset")||offset!=directory.optInt("nextOffset",-1))throw new SecurityException("只能沿刚核对的目录页继续");version=directory.getString("directoryVersion");}}
        JSONObject actual=module().readResultDirectory(new JSONObject(source.toString()).put("_profileBinding",binding),path,offset,version);
        synchronized(this){checkResultRead(window,input.getString("reviewId"));window.directory=actual;}
        return response(window,true,null);
    }
    private JSONObject selectResultFile(Window window,JSONObject input)throws Exception {
        JSONObject source;String binding,path=input.getString("path"),review=input.getString("reviewId");
        synchronized(this){checkResultRead(window,review);if(window.selectedFiles.remove(path)!=null)return response(window,true,null);
            boolean selectable=false;JSONArray entries=window.directory==null?null:window.directory.optJSONArray("files");for(int i=0;entries!=null&&i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);if(path.equals(entry.optString("path"))&&entry.optBoolean("selectable"))selectable=true;}
            if(!selectable||window.selectedFiles.size()>=8)throw new SecurityException("请在当前目录明确选择最多8个可保存文件");source=new JSONObject(window.source.toString());for(String key:new String[]{"jobId","jobOperationId","environmentId","worker","workspaceIdentity"})if(!source.getString(key).equals(window.directory.optString(key)))throw new SecurityException("目录不属于当前原job，请重新核对");binding=window.profileBinding;}
        JSONObject bytes=module().handle("readArtifact",new JSONObject(source.toString()).put("path",path).put("_profileBinding",binding));
        dev.pocketengine.features.remote.RemoteResultSnapshot.artifact(bytes,source,path);
        if(!bytes.optString("fileVersion").matches("[a-f0-9]{64}"))throw new SecurityException("原文件没有真实版本回执");
        JSONObject selected=new JSONObject().put("path",path).put("bytes",bytes.getLong("bytes")).put("sha256",bytes.getString("sha256")).put("fileVersion",bytes.getString("fileVersion"));
        synchronized(this){checkResultRead(window,review);long total=selected.getLong("bytes");for(JSONObject file:window.selectedFiles.values())total=Math.addExact(total,file.getLong("bytes"));if(window.selectedFiles.size()>=8||total>524288)throw new SecurityException("本次所选文件超过8个或512KiB");window.selectedFiles.put(path,selected);}
        return response(window,true,null);
    }
    private JSONObject captureSelectedSnapshot(Window window,JSONObject input)throws Exception {
        JSONObject operation,source;String binding;JSONArray paths=new JSONArray(),expected=new JSONArray();long revision;
        synchronized(this){checkResultRead(window,input.getString("reviewId"));for(JSONObject file:window.selectedFiles.values()){paths.put(file.getString("path"));expected.put(new JSONObject(file.toString()));}
            source=new JSONObject(window.source.toString());binding=window.profileBinding;revision=window.lifecycleRevision;operation=tasks.claimRemoteResultSnapshot(source,paths,expected);window.fresh=false;window.review=null;}
        JSONObject payload=new JSONObject(source.toString()).put("operationId",operation.getString("operationId")).put("paths",paths).put("expectedFiles",expected).put("_profileBinding",binding),actual;
        dev.pocketengine.core.RunControl current=resultReadControl(window,source.getString("profileId"),binding,revision);
        try{actual=module().captureResultSnapshot(payload,()->current.check());}
        catch(Exception unavailable){actual=new JSONObject().put("status","unknown").put("operationId",operation.getString("operationId"));for(String key:new String[]{"profileId","serviceBase","jobId","jobOperationId","environmentId","worker"})actual.put(key,source.get(key));}
        tasks.recordRemoteResultSnapshot(window.task,operation.getString("operationId"),actual);emitTask(window.task);
        synchronized(this){window.selectedFiles.clear();}return responseIfOpen(window,tasks.remoteOwnerOperation(window.task,operation.getString("operationId")));
    }
    private JSONObject saveSnapshot(Window window,JSONObject input)throws Exception {
        String binding,profile;long revision;String snapshot=input.getString("snapshotId"),path=input.getString("path");
        synchronized(this){checkResultRead(window,input.getString("reviewId"));if(window.savingResult)throw new IllegalStateException("正在保存本次原件，请等待或离开页面停止");window.savingResult=true;binding=window.profileBinding;profile=window.source.getString("profileId");revision=window.lifecycleRevision;}
        try{dev.pocketengine.core.RunControl current=resultReadControl(window,profile,binding,revision);
        dev.pocketengine.features.remote.RemoteResultFiles.Saved saved=dev.pocketengine.features.remote.RemoteResultFiles.save(tasks,module().resultAttachmentBook(),window.task,snapshot,path,
            (original,selected)->{JSONObject source=original.getJSONObject("source");return module().readResultSnapshot(new JSONObject(source.toString()).put("snapshotId",original.getString("snapshotId")).put("snapshotVersion",original.getString("snapshotVersion")).put("path",selected).put("_profileBinding",binding));},current);
        current.check();tasks.recordRemoteSnapshotAttachment(window.task,saved);emitTask(window.task);return response(window,true,null);
        }finally{synchronized(this){window.savingResult=false;}}
    }
    private void checkApprovalContext(JSONObject source,String request)throws Exception{
        JSONObject approval=null;JSONArray entries=source.optJSONArray("pendingApprovals");for(int i=0;entries!=null&&i<entries.length();i++)if(request.equals(entries.getJSONObject(i).optString("requestId")))approval=entries.getJSONObject(i);
        if(approval==null||!source.optBoolean("protocolVerified"))throw new SecurityException("未取得原协议的当前审批请求");
        if(source.optString("protocol").equals("claude_stream_json")){
            if(source.optString("sessionId").isEmpty()||source.optString("promptRequestId").isEmpty()||!source.optBoolean("controlProtocolVerified")||!source.optBoolean("sessionIdentityConfirmed")||!source.optString("sessionId").equals(approval.optString("sessionId"))||!source.optString("promptRequestId").equals(approval.optString("promptRequestId"))||approval.optString("toolUseId").isEmpty())throw new SecurityException("Claude本次工具请求身份未确认");
        }else if(source.optString("protocol").equals("codex_app_server")){
            if(source.optString("threadId").isEmpty())throw new SecurityException("Codex原会话身份缺失");
            for(String key:new String[]{"threadId","turnId"})if(approval.has(key)&&!source.optString(key).equals(approval.optString(key)))throw new SecurityException("Codex审批已不属于原thread/turn");
        }else throw new SecurityException("此执行协议未接入原生审批");
        JSONObject task=tasks.get(source.getString("taskId"),false);if(task!=null&&task.has("parentTaskId")){
            JSONObject parent=tasks.get(task.getString("parentTaskId"),false);if(parent==null)throw new SecurityException("原父任务不存在");
            boolean teamChild="teams".equals(parent.optString("feature"));for(String key:new String[]{"teamRunId","memberId","round","generation"})teamChild|=task.has(key);
            if(teamChild){
                EngineModule team=host.module("teams");if(!(team instanceof TeamsModule)||!task.optBoolean("readOnly")||!source.optString("protocol").equals("codex_app_server")||!source.optBoolean("readOnlyConfirmed")||!source.optBoolean("externalToolsDisabled")||!((TeamsModule)team).ownsMemberExecution(task.getString("teamRunId"),task.getString("memberId"),task.getInt("round"),task.getInt("generation"),task.getString("operationId")))throw new SecurityException("原团队成员执行已失效，不能绕过协调器授权");
            }else {tasks.requirePrivateRemoteChild(task.getString("id"));if(parent.optBoolean("cancelRequested"))throw new SecurityException("原私人父任务已停止，不能批准旧子任务");}
        }
    }
    private JSONObject responseIfOpen(Window window,JSONObject operation)throws Exception{ synchronized(this){if(windows.get(window.id)!=window)return new JSONObject().put("operation",operation).put("pageClosed",true);}return response(window,false,operation);}
    private JSONObject response(Window window,boolean fresh,JSONObject operation)throws Exception{
        return response(window,fresh,operation,-1,-1);
    }
    private JSONObject response(Window window,boolean fresh,JSONObject operation,long lifecycle,long generation)throws Exception{
        JSONObject source=tasks.remoteOwnerTarget(window.task),snapshot; synchronized(this){if(lifecycle>=0)checkReadCurrent(window,lifecycle,generation);if(required(window.id)!=window)throw new SecurityException("页面已经关闭，迟到读取未成为审阅凭据");if(window.source!=null&&!sameReadTarget(window.source,source,false)){window.directory=null;window.selectedFiles.clear();window.result=null;window.artifact=null;window.snapshotFile=null;window.lastSuccessfulReadAt=0;window.queryStatus="changed";fresh=false;}window.source=new JSONObject(source.toString());window.review=UUID.randomUUID().toString();window.reviewedAt=android.os.SystemClock.uptimeMillis();window.fresh=fresh;
            boolean canContinue=false;String reason="请先查询原电脑任务，核对当前会话与控制";if(fresh)try{tasks.checkRemoteContinuationSource(source);canContinue=true;reason="";}catch(Exception blocked){reason=blocked.getMessage();}
            snapshot=new JSONObject().put("windowId",window.id).put("taskId",window.task).put("reviewId",window.review).put("fresh",fresh).put("canContinue",canContinue).put("reason",reason).put("source",source).put("task",tasks.get(window.task,false)).put("profileBindingHash",window.profileBinding)
                    .put("queryStatus",window.queryStatus).put("lastReadAttemptAt",window.lastReadAttemptAt).put("lastSuccessfulReadAt",window.lastSuccessfulReadAt).put("readObservationOnly",true);
            JSONObject task=snapshot.getJSONObject("task"),origin=task.optJSONObject("origin");boolean teamQuestion=window.teamWindow!=null||task.has("teamRunId")||task.has("memberId")||origin!=null&&origin.optString("kind").startsWith("team");
            snapshot.put("canAnswerQuestions",fresh&&window.nativeOwner!=null&&foregroundOwner==window.nativeOwner&&!teamQuestion&&!task.optBoolean("cancelRequested")&&!tasks.hasUnresolved(window.task)&&source.optJSONObject("controls")!=null&&source.getJSONObject("controls").optBoolean("answerQuestion")).put("questionAnswerReason",teamQuestion?"此入口不放宽原团队正常问答/执行权限，请回原协调器；已批准的fileChange仍走其原单次审批":"只向原问题发送一次正常回答，不授予文件/命令权限；未知只查询原job");
            if(window.teamWindow!=null)snapshot.put("teamRemoteWriter",true).put("teamRemoteScope",tasks.originalTeamRemoteContext(window.task).view()).put("canContinue",false).put("reason","所选文件副本制作：不代表Git全项目或OS隔离。需要命令的完整build/test仍未闭合；继续模型/冻结贡献请回原团队宿主，不能从此私人会话页续接。");
            snapshot.put("canCaptureSnapshot",window.teamWindow==null&&fresh&&!source.optString("jobId").isEmpty()&&source.optString("executorStatus").matches("completed|failed|cancelled")&&!tasks.hasUnresolved(window.task));
            if(window.directory!=null)snapshot.put("resultDirectory",new JSONObject(window.directory.toString()));JSONArray selectedFiles=new JSONArray();for(JSONObject selected:window.selectedFiles.values())selectedFiles.put(new JSONObject(selected.toString()));snapshot.put("selectedResultFiles",selectedFiles).put("snapshotAttachments",tasks.remoteSnapshotAttachments(window.task));
        }
        synchronized(this){if(window.result!=null)snapshot.put("result",new JSONObject(window.result.toString()));if(window.artifact!=null)snapshot.put("artifact",new JSONObject(window.artifact.toString()));}
        synchronized(this){if(window.snapshotFile!=null)snapshot.put("snapshotFile",new JSONObject(window.snapshotFile.toString()));}
        synchronized(this){if(lifecycle>=0)checkReadCurrent(window,lifecycle,generation);}
        JSONObject result=new JSONObject().put("snapshot",snapshot);if(operation!=null)result.put("operation",operation);return result;
    }
    private void emitTask(String id)throws Exception{events.emit(new JSONObject().put("type","task").put("task",tasks.get(id,false)));}
}
