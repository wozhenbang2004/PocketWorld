package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.features.groups.GroupsModule;
import dev.pocketengine.features.groups.host.GroupArtifactStore;
import dev.pocketengine.features.groups.host.GroupMediaService;
import dev.pocketengine.media.jobs.results.GroupResultAccess;
import dev.pocketengine.store.TaskStore;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** The native v13 stores implement the result leaf. Every call must remain inside the existing native owner window. */
public final class GroupAttachmentOutcomeAccess implements GroupResultAccess {
    private final GroupsModule groups;private final TaskStore tasks;private final GroupArtifactStore artifacts;private final GroupMediaService media;private final dev.pocketengine.features.channels.GroupChannelBridge delivery;
    public GroupAttachmentOutcomeAccess(GroupsModule groups,TaskStore tasks,GroupArtifactStore artifacts,GroupMediaService media,dev.pocketengine.features.channels.GroupChannelBridge delivery){
        this.groups=java.util.Objects.requireNonNull(groups);this.tasks=java.util.Objects.requireNonNull(tasks);this.artifacts=java.util.Objects.requireNonNull(artifacts);this.media=java.util.Objects.requireNonNull(media);this.delivery=java.util.Objects.requireNonNull(delivery);
    }
    @Override public Context inspect(Selection selected,RunControl control)throws Exception {
        return groups.withOwnerTaskRead(selected.requestId,(request,authority)->resolve(selected,request,authority,control));
    }
    @Override public void checkRead(Context expected,RunControl control)throws Exception {
        groups.withOwnerTaskRead(expected.actual.requestId,(request,authority)->{
            Context actual=resolve(expected.actual,request,authority,control);
            if(!expected.taskRunId.equals(actual.taskRunId)||expected.coordinatorEpoch!=actual.coordinatorEpoch||expected.receiptRevision>actual.receiptRevision
                    ||!expected.sourceOperationId.equals(actual.sourceOperationId)||!expected.sourceTaskRunId.equals(actual.sourceTaskRunId)||expected.sourceCoordinatorEpoch!=actual.sourceCoordinatorEpoch
                    ||expected.sourceJobIdConfirmed&&(!actual.sourceJobIdConfirmed||!expected.sourceJobId.equals(actual.sourceJobId)))throw new SecurityException("原任务展示版本或媒体来源已变化，请刷新原成果");
            control.check();authority.checkRead();return null;
        });
    }
    @Override public JSONObject mediaReceipt(Context expected,RunControl control)throws Exception {
        if(!expected.mediaSource||expected.sourceOperationId.isEmpty())return null;
        return groups.withOwnerTaskRead(expected.actual.requestId,(request,authority)->{match(request,expected.actual);return media.originalReceipt(request,expected.sourceOperationId,control,authority);});
    }
    @Override public Artifact artifact(Context expected,RunControl control)throws Exception {
        if(expected.actual.artifactId.isEmpty())return null;
        return groups.withOwnerTaskRead(expected.actual.requestId,(request,authority)->{
            match(request,expected.actual);JSONObject record=artifacts.readOutputMetadata(request,expected.actual.artifactId,authority,control);
            JSONObject origin=record.optJSONObject("mediaSource");String job=origin==null?"":origin.optString("providerJobId");
            if(!record.getString("operationId").equals(expected.sourceOperationId))throw new SecurityException("群产物不是原操作输出");
            if(origin!=null&&(!origin.getString("runId").equals(expected.sourceTaskRunId)||origin.getInt("epoch")!=expected.sourceCoordinatorEpoch))throw new SecurityException("群产物不是原执行代次");
            if(origin==null&&expected.mediaSource){JSONObject receipt=media.originalReceipt(request,expected.sourceOperationId,control,authority);job=receipt==null?"":receipt.optString("providerJobId");}
            return new Artifact(record.getString("artifactId"),request.spaceId,request.taskId,record.getString("artifactId"),request.audience,record.getString("mimeType"),record.getLong("bytes"),record.getString("sha256"),"",expected.sourceOperationId,expected.sourceTaskRunId,job);
        });
    }
    @Override public InputStream openArtifact(Context expected,Artifact selected,RunControl control)throws Exception {
        return groups.withOwnerTaskRead(expected.actual.requestId,(request,authority)->{
            match(request,expected.actual);GroupArtifactStore.ReadSnapshot snapshot=artifacts.readOutputSnapshot(request,selected.artifactId,authority,control);JSONObject meta=snapshot.metadata();
            if(!selected.sha256.equals(meta.getString("sha256"))||selected.bytes!=meta.getLong("bytes")||!selected.mimeType.equals(meta.getString("mimeType"))||!expected.sourceOperationId.equals(meta.getString("operationId")))throw new SecurityException("原产物版本已变化");
            // Materialize and verify the complete bounded snapshot while the callback's authority is live.
            // The returned stream contains immutable already-read bytes and retains no authority or file handle.
            return snapshot.openStream();
        });
    }
    @Override public Delivery delivery(Context expected,RunControl control)throws Exception {
        JSONObject proof=deliveryEvidence(expected.actual,control);if(proof==null)return null;
        String state=proof.optString("state","unknown");boolean ownerConfirmed=state.equals("delivered");if(ownerConfirmed)state="accepted"; // This bridge's delivered branch is local owner verification, not a platform delivery-ACK protocol.
        if(!state.matches("prepared|sending|accepted|unknown|failed"))state="unknown";
        String upload=proof.optString("uploadState","unreported");if(upload.equals("not_started"))upload="not_uploaded";if(upload.equals("not_required"))upload="not_applicable";
        if(!upload.matches("unreported|not_uploaded|uploading|uploaded|unknown|failed|not_applicable"))upload="unreported";
        JSONObject error=proof.optJSONObject("error");String code=error==null?"":error.optString("code").equals("delivery_unknown")?"send_unknown":"other";
        return new Delivery(proof.getString("deliveryId"),expected.actual.taskId,expected.actual.spaceId,expected.actual.outputId,expected.actual.artifactId,expected.actual.audience,
                state,upload,proof.optString("platformMessageId"),proof.optBoolean("messageAcknowledged"),true,code);
    }
    /** The existing B3 bridge exposes only native Outbox metadata, never upload tokens or another send route. */
    public JSONObject deliveryEvidence(Selection selected,RunControl control)throws Exception {
        if(selected.artifactId.isEmpty())return null;
        return groups.withOwnerTaskRead(selected.requestId,(request,authority)->{
            match(request,selected);control.check();GroupHostPort.Output output=artifacts.output(request,selected.artifactId,authority);JSONObject found=null;JSONArray rows=groups.ownerSnapshot().getJSONArray("deliveries");
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),stored=row.optJSONObject("output");if(stored==null||!request.requestId.equals(row.optString("requestId"))||!output.outputId.equals(stored.optString("outputId")))continue;
                if(!request.taskId.equals(row.optString("taskId"))||!output.spaceId.equals(stored.optString("spaceId"))||!output.artifactId.equals(stored.optString("artifactId"))||!request.audience.equals(row.optString("audience")))throw new SecurityException("原registry投递与真实产物不一致");if(found!=null)throw new SecurityException("原产物有冲突的投递身份");found=row;}
            if(found==null)return null;JSONObject proof=groups.withOwnerDeliveryRead(found.getString("deliveryId"),(original,actualDelivery,scopedRead)->{
                match(original,selected);control.check();scopedRead.checkRead();
                JSONObject actual=delivery.inspectDeliveryEvidence(original,actualDelivery,output,scopedRead);
                control.check();scopedRead.checkRead();return actual;
            });
            if(!proof.optBoolean("known"))proof.put("state",found.optString("state").equals("prepared")?"prepared":"unknown");
            proof.put("ownerConfirmedDelivery",proof.optString("state").equals("delivered")).put("platformDeliveryAcknowledged",false);
            control.check();authority.checkRead();return proof;
        });
    }
    @Override public void refreshOriginalMedia(Context expected,Refresh refresh,RunControl control)throws Exception {
        groups.withOwnerTaskRead(expected.actual.requestId,(request,authority)->{match(request,expected.actual);media.refreshOriginal(request,expected.sourceOperationId,refresh==Refresh.ORIGINAL_ARTIFACTS?"fetchResults":"getJob",control,authority);return null;});
    }
    /** Native owner preview retains group provenance and never imports the file into the private library. */
    public JSONArray outputs(Selection selected,RunControl control)throws Exception {
        return groups.withOwnerTaskRead(selected.requestId,(request,authority)->{
            match(request,selected);JSONObject task=tasks.groupTask(request,authority);JSONArray result=new JSONArray(),operations=task.getJSONArray("operations");java.util.Set<String> seen=new java.util.HashSet<>();
            for(int i=0;i<operations.length()&&result.length()<8;i++){
                JSONObject op=operations.getJSONObject(i);if(!selected.sourceOperationHint.isEmpty()&&!selected.sourceOperationHint.equals(op.optString("operationId")))continue;
                JSONObject receipt=op.optJSONObject("receipt");JSONArray refs=receipt==null?null:receipt.optJSONArray("groupArtifacts");
                for(int j=0;refs!=null&&j<refs.length()&&result.length()<8;j++){
                    String id=refs.getJSONObject(j).getString("artifactId");if(!seen.add(id))continue;JSONObject meta=artifacts.readOutputMetadata(request,id,authority,control);
                    if(!op.getString("operationId").equals(meta.getString("operationId")))throw new SecurityException("原群文件与TaskStore操作关联冲突");
                    JSONObject source=tasks.groupMediaOperationSource(request,op.getString("operationId"),authority);
                    result.put(new JSONObject().put("id",id).put("name","群任务成果").put("mimeType",meta.getString("mimeType")).put("size",meta.getLong("bytes")).put("sha256",meta.getString("sha256"))
                            .put("sourceOperationId",source.getString("operationId")).put("sourceTaskRunId",source.getString("runId")).put("availability","available"));
                }
            }
            control.check();authority.checkRead();return result;
        });
    }
    /** Native owner preview retains group provenance and never imports the file into the private library. */
    public JSONObject readDocument(Selection selected,dev.pocketengine.features.attachments.documents.DocumentContent.Request range,RunControl control,AttachmentsModule documents)throws Exception {
        return groups.withOwnerTaskRead(selected.requestId,(request,authority)->{
            Context context=resolve(selected,request,authority,control);
            GroupArtifactStore.ReadSnapshot snapshot=artifacts.readOutputSnapshot(request,selected.artifactId,authority,control);JSONObject meta=snapshot.metadata();byte[] bytes=snapshot.copyBytes();
            try {
                JSONObject result=documents.readVerifiedNativeDocument(selected.artifactId,meta.getString("sha256"),bytes,range,control);
                JSONObject current=artifacts.readOutputMetadata(request,selected.artifactId,authority,control);
                if(!meta.getString("sha256").equals(current.getString("sha256")))throw new SecurityException("原群文档版本已变化");
                control.check();authority.checkRead();return result.put("taskId",request.taskId).put("spaceId",request.spaceId).put("audience",request.audience)
                        .put("readContext","native_owner_preview").put("sourceOperationId",context.sourceOperationId).put("sourceTaskRunId",context.sourceTaskRunId);
            } finally {java.util.Arrays.fill(bytes,(byte)0);}
        });
    }
    private Context resolve(Selection selected,GroupHostPort.Request request,GroupHostPort.Authority authority,RunControl control)throws Exception {
        control.check();authority.checkRead();match(request,selected);JSONObject task=tasks.groupTask(request,authority);if(task==null)throw new IllegalStateException("原群任务尚未保存");
        String operation=selected.sourceOperationHint;
        if(!selected.artifactId.isEmpty()){
            if(!selected.outputId.equals(selected.artifactId))throw new SecurityException("群产物不属于原输出");JSONObject record=artifacts.readOutputMetadata(request,selected.artifactId,authority,control);
            if(!operation.isEmpty()&&!operation.equals(record.getString("operationId")))throw new SecurityException("原产物与媒体操作定位冲突");operation=record.getString("operationId");
            boolean recorded=false;JSONArray ops=task.getJSONArray("operations");
            for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);if(!operation.equals(op.optString("operationId")))continue;JSONObject receipt=op.optJSONObject("receipt");JSONArray refs=receipt==null?null:receipt.optJSONArray("groupArtifacts");for(int j=0;refs!=null&&j<refs.length();j++)if(selected.artifactId.equals(refs.getJSONObject(j).optString("artifactId")))recorded=true;}
            if(!recorded)throw new SecurityException("产物尚未回到原TaskStore收据，请先查询或获取原操作");
        }
        if(operation.isEmpty()){
            JSONArray ops=task.getJSONArray("operations");for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);if(!op.optString("tool").matches("group_media_image|group_media_audio|group_media_video"))continue;if(!operation.isEmpty())throw new IllegalArgumentException("原任务有多个媒体操作，请选定实际产物或操作");operation=op.getString("operationId");}
        }
        JSONObject source=operation.isEmpty()?null:tasks.groupMediaOperationSource(request,operation,authority),receipt=source==null?null:media.originalReceipt(request,operation,control,authority);
        String run=source==null?"":source.getString("runId");if(!selected.sourceRunHint.isEmpty()&&!selected.sourceRunHint.equals(run))throw new SecurityException("来源run定位不是原操作");
        Selection actual=new Selection(request.requestId,request.taskId,request.spaceId,selected.outputId,selected.artifactId,request.audience,request.visibility,operation,run);
        control.check();authority.checkRead();return new Context(actual,task.optString("runId"),task.optInt("coordinatorEpoch"),task.getLong("groupStateRevision"),task.getString("status"),approval(task,source),operation,run,source==null?0:source.getInt("epoch"),receipt==null?"":receipt.optString("providerJobId"),receipt!=null&&receipt.optBoolean("providerJobIdConfirmed"),source!=null);
    }
    private static String approval(JSONObject task,JSONObject source)throws Exception {
        if(source==null)return "not_required";JSONArray rows=task.optJSONArray("approvals");String status="not_required";
        for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);if(!source.getString("runId").equals(row.optString("runId"))||!source.getString("tool").equals(row.optString("tool")))continue;
            if(!source.getString("argumentsHash").equals(row.optString("argumentsHash")))continue;
            String raw=row.optString("state");if(raw.equals("pending"))return row.optLong("expiresAt")>System.currentTimeMillis()?"pending":"required";
            if(raw.equals("approved")&&row.optLong("expiresAt")<=System.currentTimeMillis())status="required";else if(raw.matches("approved|consumed"))status="approved";else if(raw.equals("denied"))status="rejected";
        }
        return status;
    }
    private static void match(GroupHostPort.Request request,Selection selected){
        if(!request.requestId.equals(selected.requestId)||!request.taskId.equals(selected.taskId)||!request.spaceId.equals(selected.spaceId)||!request.audience.equals(selected.audience)||!request.visibility.equals(selected.visibility))throw new SecurityException("群产物选择不属于原认证任务、空间或受众");
    }
}
