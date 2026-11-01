package dev.pocketengine.features.groups.host;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.media.MediaModule;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original TaskStore operation → existing MediaJobs → the original group's canonical artifacts. Never uploads or sends. */
public final class GroupMediaService {
    private final MediaModule media;
    private final AttachmentBook imports;
    private final GroupArtifactStore artifacts;
    private final TaskStore tasks;
    public GroupMediaService(MediaModule media,AttachmentBook book,GroupArtifactStore artifacts){this(media,book,artifacts,null);}
    public GroupMediaService(MediaModule media,AttachmentBook book,GroupArtifactStore artifacts,TaskStore tasks){
        this.media=java.util.Objects.requireNonNull(media);imports=java.util.Objects.requireNonNull(book);this.artifacts=java.util.Objects.requireNonNull(artifacts);this.tasks=tasks;
    }
    public String currentResource()throws Exception {return "media:"+media.snapshot().getString("configurationRevision");}
    public boolean available(GroupHostPort.Request request)throws Exception {
        JSONObject snapshot=media.snapshot(),config=snapshot.getJSONObject("configuration");
        if(!snapshot.getBoolean("configured")||!request.resource.equals("media:"+snapshot.getString("configurationRevision")))return false;
        if(config.optString("provider").equals("comfyui"))return request.capability.equals("media.image")&&config.optBoolean("hasWorkflow");
        if(!config.optString("provider").equals("openai_compatible"))return false;
        return request.capability.equals("media.image")&&!config.optString("imageModel").isEmpty()||request.capability.equals("media.audio")&&!config.optString("ttsModel").isEmpty();
    }
    public JSONObject execute(GroupHostPort.Request request,String run,int epoch,String operation,String prompt,RunControl control,GroupHostPort.Authority authority)throws Exception {
        authority.check();control.check();if(!control.matchesHostRun(request.taskId,run,epoch)||!available(request))throw new SecurityException("原群媒体服务、任务控制或授权配置已变化");
        JSONObject source=source(request,operation,authority);if(!run.equals(source.getString("runId"))||epoch!=source.getInt("epoch"))throw new SecurityException("媒体不是原群操作的执行代次");
        requireDispatchApproval(request,source,authority);
        String command=request.capability.equals("media.image")?"generateImage":"synthesizeSpeech";
        JSONObject input=binding(request,source).put(command.equals("generateImage")?"prompt":"text",prompt);
        // Already journalled by the original AgentRunner. MediaJobs returns a known operation without POSTing again.
        JSONObject actual=media.handleForHost(command,input,control);validate(request,source,actual);
        return project(request,source,actual,control,authority);
    }
    /** Local durable receipt only, even when the provider/configuration is currently unavailable. */
    public JSONObject originalReceipt(GroupHostPort.Request request,String operation,RunControl parent,GroupHostPort.Authority authority)throws Exception {
        JSONObject source=source(request,operation,authority);RunControl read=readControl(request,source,parent,authority);
        Runnable stop=read::cancel;parent.onCancel(stop);
        try {
            JSONObject actual;
            try {actual=media.handleForHost("getOperation",binding(request,source),read);}
            catch(IllegalArgumentException absent){read.check();return null;}
            validate(request,source,actual);read.check();return actual;
        } finally {parent.removeCancelAction(stop);read.cancel();}
    }
    /** Explicit query/fetch of that original job; no submit, retry, global interrupt, upload or send. */
    public JSONObject refreshOriginal(GroupHostPort.Request request,String operation,String command,RunControl parent,GroupHostPort.Authority authority)throws Exception {
        if(!command.matches("getJob|reconcileOperation|fetchResults"))throw new SecurityException("只可查询或获取原群媒体操作");
        JSONObject source=source(request,operation,authority);RunControl read=readControl(request,source,parent,authority);Runnable stop=read::cancel;parent.onCancel(stop);
        try {
            JSONObject actual=media.handleForHost(command,binding(request,source),read);validate(request,source,actual);
            JSONObject projected=project(request,source,actual,read,authority);
            tasks.reconcileGroupMediaReceipt(request,authority,source,projected);read.check();return projected;
        } finally {parent.removeCancelAction(stop);read.cancel();}
    }
    /** Bounded explicit observer; closing observation has no remote cancellation or generation effect. */
    public JSONObject cancelOriginal(GroupHostPort.Request request,String operation,RunControl cancelWait,GroupHostPort.Authority cancellationAuthority)throws Exception {
        cancellationAuthority.check();cancelWait.check();JSONObject source=source(request,operation,cancellationAuthority);
        RunControl control=new RunControl();control.bindHostRun(request.taskId,source.getString("runId"),source.getInt("epoch"));
        control.setAuthorityCheck(()->{cancelWait.check();cancellationAuthority.check();sameSource(source,source(request,operation,cancellationAuthority));});Runnable stop=control::cancel;cancelWait.onCancel(stop);
        try{
            JSONObject actual=media.handleForHost("cancelJob",binding(request,source),control);
            if(!actual.has("taskId"))return new JSONObject().put("operationId",operation).put("taskId",request.taskId).put("sourceTaskRunId",source.getString("runId"))
                    .put("status","stopping").put("localWaitCancelled",true).put("providerStateConfirmed",false).put("cancelStatus","local_wait_stopped_remote_unconfirmed").put("verified",false)
                    .put("message","已停止原媒体操作的本地等待；原服务可能继续，尚无远端取消回执");
            validate(request,source,actual);JSONObject result=project(request,source,actual,control,cancellationAuthority);tasks.reconcileGroupMediaReceipt(request,cancellationAuthority,source,result);return result;
        }finally{cancelWait.removeCancelAction(stop);control.cancel();}
    }
    /** Bounded explicit observer; closing observation has no remote cancellation or generation effect. */
    public JSONObject observeOriginal(GroupHostPort.Request request,String operation,RunControl parent,GroupHostPort.Authority authority,
            dev.pocketengine.media.jobs.ComfyUiProgressObserver.Listener listener)throws Exception {
        JSONObject source=source(request,operation,authority);RunControl read=readControl(request,source,parent,authority);Runnable stop=read::cancel;parent.onCancel(stop);
        try{return media.observeProgress(binding(request,source).put("maxDurationMs",30000),read,listener);}
        finally{parent.removeCancelAction(stop);read.cancel();}
    }
    private JSONObject project(GroupHostPort.Request request,JSONObject source,JSONObject actual,RunControl control,GroupHostPort.Authority authority)throws Exception {
        JSONArray saved=actual.optJSONArray("attachments"),refs=new JSONArray();
        for(int i=0;saved!=null&&i<saved.length();i++){
            if(i>=request.maxMediaItems)throw new SecurityException("原群媒体产物数量超过原生授权");
            control.check();authority.checkRead();JSONObject meta=imports.get(saved.getJSONObject(i).getString("id")),origin=meta.getJSONObject("source");
            if(!source.getString("operationId").equals(origin.optString("operationId"))||!request.taskId.equals(origin.optString("taskId"))||!source.getString("runId").equals(origin.optString("runId"))
                    ||!actual.optString("providerJobId").equals(origin.optString("providerJobId"))||!actual.getString("provider").equals(origin.optString("provider")))throw new SecurityException("媒体附件不是原任务、服务与job的产物");
            imports.retainGroupOrigin(meta.getString("id"),request,source.getString("operationId"),source.getString("runId"),authority);
            byte[] bytes=imports.readVerifiedSnapshot(meta.getString("id"),meta.getString("sha256"),control);
            try{
                OriginalArtifact proof=new OriginalArtifact(request,source,actual,meta,bytes,()->{control.check();authority.checkRead();JSONObject current=source(request,source.getString("operationId"),authority);sameSource(source,current);});
                refs.put(artifacts.retainOriginalMedia(proof));
            } finally {Arrays.fill(bytes,(byte)0);}
        }
        // Only opaque source identity and canonical scoped references cross the group tool boundary.
        JSONObject out=new JSONObject().put("ok",actual.optString("status").equals("response_received")&&refs.length()>0)
                .put("operationId",source.getString("operationId")).put("taskId",request.taskId).put("runId",source.getString("runId"))
                .put("configurationRevision",revision(request)).put("status",actual.optString("status","unknown")).put("groupArtifacts",refs).put("verified",false);
        for(String key:new String[]{"provider","providerJobId","providerJobIdConfirmed","providerState","resultStatus","cancelStatus","queryStatus","message","querySupported"})if(actual.has(key))out.put(key,actual.get(key));
        control.check();authority.checkRead();return out;
    }
    private JSONObject source(GroupHostPort.Request request,String operation,GroupHostPort.Authority authority)throws Exception {
        if(tasks==null)throw new IllegalStateException("原群媒体TaskStore宿主尚未安装");
        JSONObject source=tasks.groupMediaOperationSource(request,operation,authority);String expected=request.capability.equals("media.image")?"group_media_image":request.capability.equals("media.audio")?"group_media_audio":"group_media_video";
        if(!expected.equals(source.getString("tool")))throw new SecurityException("媒体操作不属于原群授权能力");revision(request);return source;
    }
    private void requireDispatchApproval(GroupHostPort.Request request,JSONObject source,GroupHostPort.Authority authority)throws Exception {
        JSONObject task=tasks.groupTask(request,authority);JSONArray approvals=task.optJSONArray("approvals");
        for(int i=0;approvals!=null&&i<approvals.length();i++){
            JSONObject approval=approvals.getJSONObject(i),capability=approval.optJSONObject("capability");
            if(!source.getString("runId").equals(approval.optString("runId"))||!source.getString("tool").equals(approval.optString("tool"))||!source.getString("argumentsHash").equals(approval.optString("argumentsHash"))||!approval.optString("state").equals("consumed")||approval.optLong("expiresAt")<=System.currentTimeMillis()||capability==null)continue;
            if(request.requestId.equals(capability.optString("requestId"))&&request.spaceId.equals(capability.optString("spaceId"))&&request.actorId.equals(capability.optString("actorId"))&&request.resource.equals(capability.optString("resource"))&&request.policyEpoch.equals(capability.optString("policyEpoch"))&&request.audience.equals(capability.optString("audience")))return;
        }
        throw new SecurityException("原群媒体参数尚未取得当前原生审批，未提交服务");
    }
    private RunControl readControl(GroupHostPort.Request request,JSONObject source,RunControl parent,GroupHostPort.Authority authority)throws Exception {
        parent.check();authority.checkRead();RunControl read=new RunControl();read.bindHostRun(request.taskId,source.getString("runId"),source.getInt("epoch"));
        read.setAuthorityCheck(()->{parent.check();authority.checkRead();sameSource(source,source(request,source.getString("operationId"),authority));});return read;
    }
    private static void sameSource(JSONObject expected,JSONObject actual)throws Exception {
        for(String key:new String[]{"taskId","operationId","runId","tool","argumentsHash"})if(!expected.getString(key).equals(actual.getString(key)))throw new SecurityException("原媒体来源已变化");
        if(expected.getInt("epoch")!=actual.getInt("epoch"))throw new SecurityException("原媒体执行代次已变化");
    }
    private static JSONObject binding(GroupHostPort.Request request,JSONObject source)throws Exception {
        return new JSONObject().put("operationId",source.getString("operationId")).put("_operationId",source.getString("operationId"))
                .put("_taskId",request.taskId).put("_runId",source.getString("runId")).put("_epoch",source.getInt("epoch")).put("_configurationRevision",revision(request));
    }
    private static String revision(GroupHostPort.Request request){if(!request.resource.startsWith("media:")||request.resource.length()<7)throw new SecurityException("群媒体没有原始配置版本");return request.resource.substring(6);}
    private static void validate(GroupHostPort.Request request,JSONObject source,JSONObject actual)throws Exception {
        if(!request.taskId.equals(actual.optString("taskId"))||!source.getString("operationId").equals(actual.optString("operationId"))||!source.getString("runId").equals(actual.optString("runId"))||!revision(request).equals(actual.optString("configurationRevision")))throw new SecurityException("媒体回执不属于原群任务、操作、run或配置");
        JSONObject old=source.optJSONObject("receipt");if(old!=null&&old.optBoolean("providerJobIdConfirmed")&&(!actual.optBoolean("providerJobIdConfirmed")||!old.optString("providerJobId").equals(actual.optString("providerJobId"))))throw new SecurityException("媒体回执替换了已确认的原job");
    }
    private interface Check {void run()throws Exception;}
    /** Only this native adapter can mint a scoped, exact, verified artifact copy. It is not an execution/send grant. */
    static final class OriginalArtifact {
        final GroupHostPort.Request request;final String operation,mime,sha256;final long size;private final JSONObject origin;private final byte[] bytes;private final Check guard;
        private OriginalArtifact(GroupHostPort.Request request,JSONObject source,JSONObject receipt,JSONObject meta,byte[] bytes,Check guard)throws Exception {
            this.request=request;operation=source.getString("operationId");mime=meta.getString("mimeType");sha256=meta.getString("sha256");size=meta.getLong("size");this.bytes=bytes;this.guard=guard;
            origin=new JSONObject().put("runId",source.getString("runId")).put("epoch",source.getInt("epoch")).put("configurationRevision",revision(request))
                    .put("provider",receipt.getString("provider")).put("providerJobId",receipt.optString("providerJobId")).put("artifactKey",meta.getJSONObject("source").getString("artifactKey"));
            if(size<0||size>request.maxMediaBytes||size!=bytes.length)throw new SecurityException("原媒体产物大小超出授权");
        }
        void check()throws Exception{guard.run();}
        JSONObject origin()throws Exception{return new JSONObject(origin.toString());}
        InputStream open()throws Exception{check();return new ByteArrayInputStream(bytes);}
    }
}
