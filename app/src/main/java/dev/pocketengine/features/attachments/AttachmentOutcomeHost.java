package dev.pocketengine.features.attachments;

import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.media.MediaModule;
import dev.pocketengine.store.TaskStore;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual native owner files/sources/results for the existing task and artifact stores. No task creation or sender. */
public final class AttachmentOutcomeHost {
    public interface OriginalMediaCall {JSONObject call(String command,JSONObject payload,RunControl control)throws Exception;}
    private final TaskStore tasks;
    private final AttachmentRepository repository;
    private final AttachmentsModule documents;
    private final OriginalMediaCall media;
    public AttachmentOutcomeHost(TaskStore tasks,AttachmentRepository repository,AttachmentsModule documents,OriginalMediaCall media) {
        this.tasks=java.util.Objects.requireNonNull(tasks);this.repository=java.util.Objects.requireNonNull(repository);
        this.documents=java.util.Objects.requireNonNull(documents);this.media=java.util.Objects.requireNonNull(media);
    }

    public JSONObject snapshot(String taskId,RunControl control)throws Exception {
        control.check();JSONObject task=ownerTask(taskId);JSONArray selected=task==null?new JSONArray():tasks.ownerAttachmentReferences(taskId);
        JSONObject inventory=documents.ownerAttachmentInventoryForHost();JSONArray files=inventory.getJSONArray("attachments"),sources=new JSONArray(),results=new JSONArray(),jobs=new JSONArray();Set<String> included=new HashSet<>();
        for(int i=0;i<selected.length();i++){
            JSONObject pinned=selected.getJSONObject(i),meta=find(files,pinned.getString("id"));
            if(meta==null||!sameReference(pinned,meta))sources.put(new JSONObject(pinned.toString()).put("availability","input_changed").put("name","原来源文件不可读"));
            else {sources.put(row(meta,"source"));included.add(meta.getString("id"));}
        }
        for(int i=0;i<files.length();i++) {
            control.check();JSONObject meta=files.getJSONObject(i);if(!privateFile(meta))continue;
            JSONObject origin=meta.optJSONObject("source");boolean attached=task==null||origin!=null&&taskId.equals(origin.optString("taskId"));
            if(!attached)continue;
            if(task==null&&origin!=null&&!origin.optString("taskId").isEmpty()){
                JSONObject original=tasks.get(origin.getString("taskId"),false);
                if(original!=null&&original.optJSONObject("origin")!=null&&original.getJSONObject("origin").optString("kind").equals("group"))continue; // Group results use their original scoped viewer, not this technical media cache.
            }
            String kind=origin==null?"":origin.optString("kind");
            boolean output=kind.equals("generated_media")||kind.equals("created_text")||kind.equals("document_extract")||kind.equals("remote_result_snapshot")||kind.equals("created_document")||kind.equals("derived_media")||kind.equals("managed_build_output");
            if(task!=null&&output&&kind.equals("generated_media")) {
                JSONObject actual=tasks.ownerMediaOperationSource(taskId,origin.getString("operationId"));
                if(!actual.getString("runId").equals(origin.optString("runId")))throw new SecurityException("媒体文件不属于原任务的原执行");
            }
            if(output)results.put(row(meta,"result"));
            else if(included.add(meta.getString("id")))sources.put(row(meta,"source"));
        }
        if(task!=null){
            JSONArray operations=task.getJSONArray("operations");
            for(int i=0;i<operations.length();i++){
                JSONObject op=operations.getJSONObject(i);if(!op.optString("tool").matches("media_workflow|media_generate_image|media_generate_video|media_edit_image|media_synthesize_speech"))continue;
                JSONObject source=tasks.ownerMediaOperationSource(taskId,op.getString("operationId")),receipt=mediaReceipt(op.optJSONObject("receipt"));
                JSONObject value=new JSONObject().put("operationId",source.getString("operationId")).put("sourceTaskRunId",source.getString("runId"))
                        .put("sourceCoordinatorEpoch",source.getInt("epoch")).put("tool",source.getString("tool")).put("operationState",source.getString("state"))
                        .put("status",receipt==null?"host_receipt_unavailable":receipt.optString("status","unknown")).put("verified",false);
                if(receipt!=null)for(String field:new String[]{"provider","providerJobId","providerJobIdConfirmed","configurationRevision","providerState","resultStatus","cancelStatus","queryStatus","message"})if(receipt.has(field))value.put(field,receipt.get(field));
                jobs.put(value);
            }
        }
        ownerTask(taskId);control.check();JSONObject result=new JSONObject().put("taskId",taskId==null?"":taskId).put("sources",sources).put("artifacts",results)
                .put("media",jobs).put("documentCapabilities",documents.documentCapabilities()).put("indexDiagnostics",inventory.getJSONObject("indexDiagnostics")).put("verifiedBusinessSuccess",false);
        if(task!=null){result.put("taskRunId",task.optString("runId")).put("coordinatorEpoch",task.optInt("coordinatorEpoch")).put("taskStatus",task.optString("status"));if("attachments".equals(task.optString("feature"))&&"createDocument".equals(task.optString("command")))result.put("originalDocumentActionId",task.getString("operationId"));}
        else result.put("documentActions",documentActions("",control));
        return result;
    }

    /** One explicit metadata page, including completed originals whose UI callback was lost. */
    public JSONObject documentActions(String cursor,RunControl control)throws Exception {return tasks.ownerDocumentActionsForOwner(cursor,control);}

    /** The file picker supplies a real imported record. JSON callers cannot create an association here. */
    public JSONObject selectForTask(String taskId,String artifactId,String expectedSha256,RunControl control)throws Exception {
        ownerTaskRequired(taskId);JSONObject actual=privateMetadata(artifactId,expectedSha256);repository.book.requireUnchanged(artifactId,expectedSha256,control);
        JSONObject selected=tasks.linkOwnerAttachment(taskId,actual);control.check();return selected;
    }
    /** Display metadata for exact retained message references. Reading/opening still verifies canonical bytes. */
    public JSONArray messageReferences(JSONArray references)throws Exception {
        JSONArray result=new JSONArray();if(references==null)return result;if(references.length()>4)throw new SecurityException("原消息附件数无效");
        for(int i=0;i<references.length();i++){
            JSONObject pinned=references.getJSONObject(i);
            try{JSONObject meta=privateMetadata(pinned.getString("id"),pinned.getString("sha256"));if(!sameReference(pinned,meta))throw new SecurityException("原消息附件版本变化");result.put(row(meta,"source").put("contentReadConfirmed",false));}
            catch(Exception unavailable){result.put(new JSONObject().put("id",pinned.optString("id")).put("sha256",pinned.optString("sha256")).put("mimeType",pinned.optString("mimeType")).put("size",pinned.optLong("size")).put("name","原消息附件不可用").put("availability","unavailable").put("contentReadConfirmed",false));}
        }
        return result;
    }

    public JSONObject readOwnerDocument(String taskId,String artifactId,String expectedSha256,DocumentContent.Request range,RunControl control)throws Exception {
        requireReference(taskId,artifactId,expectedSha256);control.checkpoint();JSONObject result=documents.previewOwnerDocument(taskId,artifactId,expectedSha256,range,control);
        requireReference(taskId,artifactId,expectedSha256);control.checkpoint();return result.put("taskId",taskId==null?"":taskId).put("readContext","native_owner_preview");
    }
    public JSONObject editableDocument(String task,String id,String sha,RunControl control)throws Exception{requireReference(task,id,sha);JSONObject result=documents.editableDocumentForOwner(id,sha,control);requireReference(task,id,sha);return result;}
    /** Explicit local extract from a real owner preview, never arbitrary client text, a new task or a group delivery. */
    public JSONObject saveOwnerDocumentExtract(String taskId,String artifactId,String expectedSha256,String readReceiptId,RunControl control)throws Exception {
        requireReference(taskId,artifactId,expectedSha256);documents.requireOwnerAttachment(artifactId);control.checkpoint();
        try(AutoCloseable scope=control.retainAuthorityCheck(()->{requireReference(taskId,artifactId,expectedSha256);documents.requireOwnerAttachment(artifactId);})){return documents.saveOwnerDocumentExtract(taskId,artifactId,expectedSha256,readReceiptId,control);}
        catch(AttachmentBook.InputChangedException changed){throw new IllegalStateException("原文件或已保存摘录的版本已变化；本次保存未确认，请核对成果后重新读取",changed);}
    }

    /** Same live task/space/version is required for each page requested by the actual model tool. */
    public JSONObject readTaskDocument(String taskId,String runId,int epoch,String artifactId,String expectedSha256,DocumentContent.Request range,RunControl control)throws Exception {
        if(!control.hasHostRun()||!control.matchesHostRun(taskId,runId,epoch))throw new SecurityException("文档分页不是原任务的当前控制");
        tasks.checkCapability(taskId,runId,epoch,"attachments_read_document",control);requireReference(taskId,artifactId,expectedSha256);
        JSONObject result=documents.readDocument(artifactId,expectedSha256,range,control);
        tasks.checkCapability(taskId,runId,epoch,"attachments_read_document",control);requireReference(taskId,artifactId,expectedSha256);
        AttachmentBook.OutputSourceEligibility sourceGeneration=repository.book.outputSourceEligibility(artifactId,expectedSha256);
        JSONArray nativeChunks=result.optJSONArray("chunks");boolean hasNativeBody=false;for(int i=0;nativeChunks!=null&&i<nativeChunks.length();i++){JSONObject chunk=nativeChunks.optJSONObject(i);hasNativeBody|=chunk!=null&&!chunk.optString("text").isEmpty();}
        if(sourceGeneration==null&&hasNativeBody)throw new SecurityException("原附件正文来源已撤回或未完成原字节核验");
        JSONObject fact=new JSONObject().put("type","attachment_document_read").put("attachmentId",artifactId).put("sha256",expectedSha256).put("runId",runId).put("epoch",epoch)
                .put("startUnit",range.startUnit).put("startChunk",range.startChunk).put("maxUnits",range.maxUnits).put("maxChars",range.maxChars).put("maxChunks",range.maxChunks).put("extraction",range.extraction).put("documentFingerprint",TaskStore.capabilityFingerprint(result))
                .put("coverage",result.optString("coverage")).put("status",result.optString("status")).put("parserVersion",result.optString("parserVersion"))
                .put("engineVersion",result.optString("engineVersion")).put("chunkCount",result.optJSONArray("chunks")==null?0:result.getJSONArray("chunks").length())
                .put("nextUnit",result.optInt("nextUnit")).put("nextChunk",result.optInt("nextChunk")).put("readContext","task_tool").put("verifiedBusinessSuccess",false);
        if(sourceGeneration!=null)fact.put("attachmentGeneration",sourceGeneration.generation);
        tasks.noteOwnerDocumentRead(taskId,runId,epoch,fact,control);return result.put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("readContext","task_tool");
    }

    public Intent open(String taskId,String artifactId,String expectedSha256,RunControl control)throws Exception {
        requireReference(taskId,artifactId,expectedSha256);Intent prepared=repository.verifiedViewIntent(artifactId,expectedSha256,control);
        try{requireReference(taskId,artifactId,expectedSha256);control.checkpoint();return prepared;}
        catch(Exception failure){try{repository.discardVerifiedView(prepared);}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Actual original-format viewer; task/audience and canonical byte checks remain this host's. */
    public OwnerFilePreviewSource originalFile(String taskId,String artifactId,String expectedSha256,RunControl control)throws Exception {
        requireReference(taskId,artifactId,expectedSha256);JSONObject metadata=privateMetadata(artifactId,expectedSha256);repository.book.requireUnchanged(artifactId,expectedSha256,control);
        return new OwnerFilePreviewSource("attachments",metadata,c->repository.book.verifiedBytes(artifactId,expectedSha256,c),c->{requireReference(taskId,artifactId,expectedSha256);repository.book.requireUnchanged(artifactId,expectedSha256,c);});
    }
    /** Explicit system chooser with a pinned original copy; a requested share is not delivery. */
    public Intent share(String taskId,String artifactId,String expectedSha256,RunControl control)throws Exception {
        Intent view=open(taskId,artifactId,expectedSha256,control);Uri pinned=view.getData();if(pinned==null){repository.discardVerifiedView(view);throw new IllegalStateException("原文件分享副本没有真实URI");}
        Intent share=new Intent(Intent.ACTION_SEND).setDataAndType(pinned,view.getType()).putExtra(Intent.EXTRA_STREAM,pinned).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(android.content.ClipData.newRawUri("Pocket World original file",pinned));return share;
    }
    /** Native UI marks this only immediately before handing the prepared view to an external reader. */
    public static final String NATIVE_VIEW_ISSUED="dev.pocketengine.native_view_issued";
    /** Native failed/late preparation only. Never a feature/model route, never a canonical delete. */
    public void discardUnissuedView(Intent prepared)throws Exception {
        if(prepared!=null&&prepared.getBooleanExtra(NATIVE_VIEW_ISSUED,false))throw new SecurityException("已交给外部reader的查看副本不能作为未交付请求清理");
        repository.discardVerifiedView(prepared);
    }
    public android.graphics.Bitmap thumbnail(String taskId,String artifactId,String sha,RunControl control)throws Exception {
        requireReference(taskId,artifactId,sha);byte[] bytes=repository.book.verifiedBytes(artifactId,sha,control);android.graphics.Bitmap image=null;
        try{image=AttachmentImagePreview.decode(bytes,control);repository.book.requireUnchanged(artifactId,sha,control);requireReference(taskId,artifactId,sha);return image;}
        catch(Exception stopped){if(image!=null)image.recycle();throw stopped;}finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
    public JSONObject export(String taskId,String artifactId,String expectedSha256,Uri destination,RunControl control)throws Exception {
        requireReference(taskId,artifactId,expectedSha256);JSONObject result=repository.exportVerifiedToUri(artifactId,expectedSha256,destination,control);
        requireReference(taskId,artifactId,expectedSha256);return result.put("taskId",taskId==null?"":taskId).put("verifiedBusinessSuccess",false);
    }
    public JSONObject originalMedia(String taskId,String operationId,String command,RunControl control)throws Exception {
        ownerTaskRequired(taskId);JSONObject source=tasks.ownerMediaOperationSource(taskId,operationId);
        if(!command.matches("getOperation|getJob|reconcileOperation|fetchResults|cancelJob"))throw new SecurityException("仅查询、获取或停止原媒体操作");
        JSONObject payload=new JSONObject().put("taskId",taskId).put("operationId",operationId).put("runId",source.getString("runId")).put("epoch",source.getInt("epoch"));
        return media.call(command,payload,control);
    }
    public void requireReference(String taskId,String artifactId,String expectedSha256)throws Exception {
        JSONObject meta=privateMetadata(artifactId,expectedSha256);if(taskId==null||taskId.isEmpty())return;
        ownerTaskRequired(taskId);JSONArray selected=tasks.ownerAttachmentReferences(taskId);
        for(int i=0;i<selected.length();i++)if(artifactId.equals(selected.getJSONObject(i).getString("id"))&&sameReference(selected.getJSONObject(i),meta))return;
        JSONObject source=meta.optJSONObject("source");if(source!=null&&taskId.equals(source.optString("taskId"))){
            if(source.optString("kind").equals("generated_media")){JSONObject op=tasks.ownerMediaOperationSource(taskId,source.getString("operationId"));if(!op.getString("runId").equals(source.optString("runId")))throw new SecurityException("文件不属于原媒体执行");}
            return;
        }
        throw new SecurityException("该文件未被明确选为原任务来源，也不是其真实成果");
    }
    private JSONObject privateMetadata(String id,String expected)throws Exception {
        JSONObject meta=repository.book.get(id);if(!privateFile(meta)||expected==null||!expected.equals(meta.optString("sha256")))throw new SecurityException("文件空间或不可变版本不匹配");
        JSONObject source=meta.optJSONObject("source"),task=source==null||source.optString("taskId").isEmpty()?null:tasks.get(source.getString("taskId"),false);
        if(source!=null&&source.has("groupSpaceId")||task!=null&&task.optJSONObject("origin")!=null&&task.getJSONObject("origin").optString("kind").equals("group"))throw new SecurityException("群媒体技术副本不能作为私人附件；请从原群成果读取");return meta;
    }
    private JSONObject ownerTask(String taskId)throws Exception {return taskId==null||taskId.isEmpty()?null:ownerTaskRequired(taskId);}
    private JSONObject ownerTaskRequired(String taskId)throws Exception {JSONObject task=tasks.get(taskId,false);if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("群或其他空间成果须通过其原生来源读取");return task;}
    private static boolean privateFile(JSONObject meta){return "owner".equals(meta.optString("principalId"))&&"private".equals(meta.optString("spaceId"));}
    private static boolean sameReference(JSONObject a,JSONObject b){return a.optString("sha256").equals(b.optString("sha256"))&&a.optString("mimeType").equals(b.optString("mimeType"))&&a.optLong("size",-1)==b.optLong("size",-2);}
    private static JSONObject find(JSONArray files,String id)throws Exception {for(int i=0;i<files.length();i++)if(id.equals(files.getJSONObject(i).optString("id")))return files.getJSONObject(i);return null;}
    private static JSONObject row(JSONObject meta,String role)throws Exception {JSONObject result=new JSONObject();for(String field:new String[]{"id","name","mimeType","size","sha256","createdAt"})if(meta.has(field))result.put(field,meta.get(field));JSONObject source=meta.optJSONObject("source");if(source!=null){result.put("sourceKind",source.optString("kind"));for(String key:new String[]{"editableSource","parentArtifact","formatCheck","processing"})if(source.optJSONObject(key)!=null)result.put(key,new JSONObject(source.getJSONObject(key).toString()));for(String[] field:new String[][]{{"taskId","sourceTaskId"},{"runId","sourceTaskRunId"},{"operationId","sourceOperationId"},{"providerJobId","sourceJobId"},{"sourceAttachmentId","sourceAttachmentId"},{"sourceSha256","sourceSha256"}})if(!source.optString(field[0]).isEmpty())result.put(field[1],source.getString(field[0]));}return result.put("role",role).put("availability","available");}
    private static JSONObject mediaReceipt(JSONObject receipt)throws Exception {for(int i=0;receipt!=null&&i<4;i++){if(receipt.has("operationId")&&receipt.has("configurationRevision"))return receipt;JSONObject next=receipt.optJSONObject("data");if(next==null)next=receipt.optJSONObject("receipt");receipt=next;}return null;}
}
