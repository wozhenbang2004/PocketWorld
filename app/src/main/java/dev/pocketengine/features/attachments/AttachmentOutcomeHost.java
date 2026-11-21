package dev.pocketengine.features.attachments;

import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.OriginalNodeCompilerDelivery;
import dev.pocketengine.features.local.runtime.OwnerPythonOutputText;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.media.MediaModule;
import dev.pocketengine.store.SshWorkHistory;
import dev.pocketengine.store.TaskStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        return snapshot(taskId,control,false);
    }
    /** Extra display facts for the original Work file list, not the general attachment snapshot. */
    public JSONObject workResultFilesSnapshot(String taskId,RunControl control)throws Exception {
        return snapshot(taskId,control,true);
    }
    private record PdfExcerpt(JSONObject result,JSONObject source){}
    private record WordExcerpt(JSONObject result,JSONObject source){}
    private JSONObject snapshot(String taskId,RunControl control,boolean workFileList)throws Exception {
        control.check();JSONObject task=ownerTask(taskId);JSONArray selected=task==null?new JSONArray():tasks.ownerAttachmentReferences(taskId);
        JSONObject inventory=documents.ownerAttachmentInventoryForHost();JSONArray files=inventory.getJSONArray("attachments"),sources=new JSONArray(),results=new JSONArray(),jobs=new JSONArray();Set<String> included=new HashSet<>();
        boolean showPdfStarts=workFileList&&ordinaryCompletedWork(task);
        Map<String,String> pdfInputs=showPdfStarts?new HashMap<>():null;
        List<PdfExcerpt> excerpts=showPdfStarts?new ArrayList<>():null;
        List<WordExcerpt> wordExcerpts=task==null?new ArrayList<>():null;
        Map<String,String> wordInputs=task==null?new HashMap<>():null;
        var captured=new dev.pocketengine.features.remote.RemoteFileCaptureAttempt(task,tasks::remoteOwnerOperation);
        int receivedChecks=0;boolean receivedLimited=false;
        for(int i=0;i<selected.length();i++){
            JSONObject pinned=selected.getJSONObject(i),meta=find(files,pinned.getString("id"));
            if(meta==null||!sameReference(pinned,meta))sources.put(new JSONObject(pinned.toString()).put("availability","input_changed").put("name","原来源文件不可读"));
            else {sources.put(sourceRow(meta,workFileList?task:null));included.add(meta.getString("id"));rememberPdfInput(pdfInputs,meta);}
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
            if(task!=null&&"device_received_file".equals(kind)&&"file".equals(origin.opt("artifactKind"))){
                // A local Book row alone is not a device result. Preserve the ordinary source
                // entry unless the original SQLite receipt and exact local publication agree.
                if(receivedChecks++>=24)receivedLimited=true;
                else {
                    JSONObject received=originalReceivedFile(task,meta,origin,control);
                    if(received!=null){results.put(received);continue;}
                }
            }
            if(OriginalNodeCompilerDelivery.claims(origin)){
                try{
                    // The ordinary attachment inventory has checked the exact published SQL row.
                    JSONObject result=row(meta,"result").put("sourceRunEvidence",OriginalNodeCompilerDelivery.PROVENANCE)
                        .put("sourceCoordinatorEpoch",origin.getJSONObject("originalCompilerWitness").getInt("epoch"));
                    if(task!=null){
                        String currentRun=task.optString("runId");
                        int epoch=result.getInt("sourceCoordinatorEpoch"),currentEpoch=task.optInt("coordinatorEpoch");
                        boolean current=result.getString("sourceTaskRunId").equals(currentRun);
                        if(currentRun.isBlank()||epoch>currentEpoch||current!=(epoch==currentEpoch))continue;
                        result.put("runProvenance",current?"current":"earlier");
                    }
                    results.put(result);
                }catch(org.json.JSONException unverified){ /* An unreadable origin isn't a new Work file. */ }
                continue;
            }
            if(OwnerPythonOutputText.claims(origin)){
                // The shared local inventory already checked this exact saved Work/Book relation.
                JSONObject result=row(meta,"result").put("sourceRunEvidence",OwnerPythonOutputText.EVIDENCE)
                        .put("sourceCoordinatorEpoch",1);
                if(task==null)results.put(result);
                else if(task.getInt("coordinatorEpoch")==1&&task.getString("runId").equals(result.opt("sourceTaskRunId")))
                    results.put(result.put("runProvenance","current"));
                continue;
            }
            boolean output=kind.equals("generated_media")||kind.equals("created_text")||kind.equals("document_extract")||kind.equals("remote_result_snapshot")||kind.equals("created_document")||kind.equals("derived_media")||kind.equals("managed_build_output");
            if(task!=null&&output&&kind.equals("generated_media")) {
                JSONObject actual=tasks.ownerMediaOperationSource(taskId,origin.getString("operationId"));
                if(!actual.getString("runId").equals(origin.optString("runId")))throw new SecurityException("媒体文件不属于原任务的原执行");
            }
            if(output){
                JSONObject result=row(meta,"result");
                if(task!=null){
                    String artifactRun=result.optString("sourceTaskRunId"),currentRun=task.optString("runId");
                    if(kind.equals("remote_result_snapshot")){
                        // Book metadata alone does not establish a capture run; leave it unrecorded without its own proof.
                        result.remove("sourceTaskRunId");result.put("runProvenance","unrecorded");
                        JSONObject proof=captured.forFile(meta);if(proof!=null)for(String k:new String[]{"sourceTaskRunId","sourceCoordinatorEpoch","sourceRunEvidence","runProvenance"})result.put(k,proof.get(k));
                    }else result.put("runProvenance",artifactRun.isBlank()?"unrecorded":artifactRun.equals(currentRun)?"current":"earlier");
                    if("created_document".equals(kind)&&recordedDocumentOriginal(task,meta))result.put("recordedDocumentOriginal",true);
                }
                if(excerpts!=null&&kind.equals("document_extract")&&"text/plain".equals(meta.opt("mimeType"))&&
                        !meta.optBoolean("outputSourceRetired")&&!origin.has("archiveEvidence")&&
                        origin.isNull("runId")&&origin.isNull("operationId"))excerpts.add(new PdfExcerpt(result,origin));
                if(wordExcerpts!=null&&kind.equals("document_extract")&&"text/plain".equals(meta.opt("mimeType"))&&
                        !meta.optBoolean("outputSourceRetired")&&AttachmentDocuments.WORD_EXCERPT.equals(origin.opt("originalWordExcerpt")))
                    wordExcerpts.add(new WordExcerpt(result,origin));
                results.put(result);
            }
            else if(included.add(meta.getString("id"))){
                sources.put(sourceRow(meta,workFileList?task:null));rememberPdfInput(pdfInputs,meta);
                if(wordInputs!=null&&ordinaryWordInput(meta,origin))
                    wordInputs.put(meta.getString("id"),meta.getString("sha256"));
            }
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
        if(receivedLimited)inventory.getJSONObject("indexDiagnostics").put("incomplete",true);
        JSONObject current=ownerTask(taskId);control.check();
        if(showPdfStarts&&ordinaryCompletedWork(current)&&task.getString("runId").equals(current.opt("runId"))&&
                task.getInt("coordinatorEpoch")==current.optInt("coordinatorEpoch")){
            for(PdfExcerpt excerpt:excerpts){
                control.check();
                JSONObject start=pdfExcerptStart(excerpt.source(),pdfInputs);
                if(start!=null)excerpt.result().put("pdfExcerptStart",start);
            }
        }
        if(wordExcerpts!=null)for(WordExcerpt excerpt:wordExcerpts){
            control.check();JSONObject original=wordExcerptStart(excerpt.source(),wordInputs);
            if(original!=null)excerpt.result().put("originalWordExcerpt",original);
        }
        JSONObject result=new JSONObject().put("taskId",taskId==null?"":taskId).put("sources",sources).put("artifacts",results)
                .put("media",jobs).put("documentCapabilities",documents.documentCapabilities()).put("indexDiagnostics",inventory.getJSONObject("indexDiagnostics")).put("verifiedBusinessSuccess",false);
        if(task!=null){result.put("taskRunId",task.optString("runId")).put("coordinatorEpoch",task.optInt("coordinatorEpoch")).put("taskStatus",task.optString("status"));if("attachments".equals(task.optString("feature"))&&"createDocument".equals(task.optString("command")))result.put("originalDocumentActionId",task.getString("operationId"));}
        else result.put("documentActions",documentActions("",control));
        if(workFileList&&task!=null&&"ssh".equals(task.opt("feature")))result.put("taskResultRevision",task.optLong("resultRevision"));
        return result;
    }
    private static JSONObject sourceRow(JSONObject meta,JSONObject work)throws Exception{
        JSONObject source=row(meta,"source");
        if(work!=null&&SshWorkHistory.matchesSavedOwnerDownload(work,meta))source.put("sourceRunEvidence","original_ssh_file_download");
        return source;
    }
    private static boolean ordinaryCompletedWork(JSONObject task){
        return task!=null&&"assistant".equals(task.opt("kind"))&&"completed".equals(task.opt("status"))&&
                task.isNull("origin")&&task.isNull("parentTaskId")&&task.isNull("teamRunId")&&task.isNull("memberId")&&
                task.isNull("developmentContext")&&task.isNull("ownerDeviceWork")&&task.isNull("ownerPhoneControl")&&
                task.isNull("directWorkspace")&&task.isNull("directPhone")&&
                task.optBoolean("contextAvailable",true)&&!task.optBoolean("contextWithdrawn")&&
                !task.optBoolean("sourceCurrentReadRequired")&&!"source_unavailable".equals(task.opt("taskProjection"))&&
                task.opt("runId") instanceof String run&&!run.isBlank()&&boundedInteger(task.opt("coordinatorEpoch"),1,Integer.MAX_VALUE);
    }
    private static void rememberPdfInput(Map<String,String> inputs,JSONObject meta){
        if(inputs!=null&&privateFile(meta)&&"application/pdf".equals(meta.opt("mimeType"))&&
                !meta.optBoolean("outputSourceRetired"))inputs.put(meta.optString("id"),meta.optString("sha256"));
    }
    /** The saved read's start is not a claim about which pages were covered or whether the PDF is currently readable. */
    private static JSONObject pdfExcerptStart(JSONObject source,Map<String,String> inputs)throws Exception{
        if(!(source.opt("sourceAttachmentId") instanceof String id)||id.isBlank()||
                !(source.opt("sourceSha256") instanceof String sha)||!sha.matches("[a-f0-9]{64}")||
                !(source.opt("readReceiptId") instanceof String receipt)||!receipt.matches("[a-f0-9-]{36}"))return null;
        JSONObject range=source.optJSONObject("readRange");
        if(!sha.equals(inputs.get(id))||range==null)return null;
        Object page=range.opt("startUnit"),chunk=range.opt("startChunk"),extraction=range.opt("extraction");
        if(!boundedInteger(page,1,1_000_000)||!boundedInteger(chunk,0,1_000_000)||!java.util.Objects.equals(extraction,source.opt("extraction")))return null;
        String method="rendered_page_ocr".equals(source.opt("coverage"))&&"ocr".equals(extraction)?"ocr":
                "pdf_text_layer".equals(source.opt("coverage"))&&("text".equals(extraction)||"auto".equals(extraction))?"text_layer":null;
        return method==null?null:new JSONObject().put("page",page).put("chunk",chunk).put("method",method);
    }
    private static boolean ordinaryWordInput(JSONObject meta,JSONObject origin){
        return origin!=null&&privateFile(meta)&&"content_uri".equals(origin.opt("kind"))&&
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(meta.opt("mimeType"))&&
                !meta.optBoolean("outputSourceRetired")&&origin.isNull("taskId")&&origin.isNull("projectId")&&
                origin.isNull("groupSpaceId")&&origin.isNull("archiveEvidence");
    }
    /** Persisted native read receipt, only for the private Library. Never a reader or file grant. */
    private static JSONObject wordExcerptStart(JSONObject origin,Map<String,String> inputs)throws Exception{
        if(!(origin.opt("sourceAttachmentId") instanceof String id)||!id.matches("[a-f0-9-]{36}")||
                !(origin.opt("sourceSha256") instanceof String sha)||!sha.matches("[a-f0-9]{64}")||!sha.equals(inputs.get(id))||
                !(origin.opt("readReceiptId") instanceof String receipt)||!receipt.matches("[a-f0-9-]{36}")||
                !"body_paragraphs_and_tables".equals(origin.opt("coverage"))||!"text".equals(origin.opt("extraction"))||
                !DocumentContent.PARSER_VERSION.equals(origin.opt("parserVersion"))||
                !origin.isNull("taskId")||!origin.isNull("runId")||!origin.isNull("operationId")||
                !origin.isNull("groupSpaceId")||!origin.isNull("archiveEvidence"))return null;
        JSONObject range=origin.optJSONObject("readRange");
        if(range==null||!boundedInteger(range.opt("startUnit"),1,1_000_000)||
                !boundedInteger(range.opt("startChunk"),0,1_000_000)||!boundedInteger(range.opt("maxUnits"),1,1)||
                !boundedInteger(range.opt("maxChunks"),1,2)||!boundedInteger(range.opt("maxChars"),2048,2048)||
                !"text".equals(range.opt("extraction")))return null;
        return new JSONObject().put("format",AttachmentDocuments.WORD_EXCERPT).put("fileId",id).put("sha256",sha)
                .put("unit",range.getInt("startUnit")).put("chunk",range.getInt("startChunk")).put("parts",range.getInt("maxChunks"));
    }
    private static boolean boundedInteger(Object value,int min,int max){
        return (value instanceof Long||value instanceof Integer)&&((Number)value).longValue()>=min&&((Number)value).longValue()<=max;
    }
    private JSONObject originalReceivedFile(JSONObject task,JSONObject meta,JSONObject origin,RunControl control)throws InterruptedException{
        if(task.has("origin")||task.optBoolean("contextWithdrawn")||!task.optBoolean("contextAvailable",true)
                ||task.optBoolean("sourceCurrentReadRequired")||"source_unavailable".equals(task.opt("taskProjection")))return null;
        try{
            String id=task.getString("id"),current=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
            var original=tasks.deviceTasks().ownerHistoryEvidence(id,origin.getString("sourceOperationId"));
            JSONObject proof=dev.pocketengine.features.devices.transport.OwnerReceivedFileBook.savedFileProvenance(original,meta,control);
            String run=proof.getString("sourceTaskRunId");int receivedEpoch=proof.getInt("sourceCoordinatorEpoch");
            if(current.isBlank()||receivedEpoch>epoch||run.equals(current)!=(receivedEpoch==epoch))return null;
            JSONObject result=row(meta,"result");
            for(String field:new String[]{"sourceTaskId","sourceTaskRunId","sourceCoordinatorEpoch","sourceOperationId","sourceRunEvidence"})result.put(field,proof.get(field));
            return result.put("runProvenance",receivedEpoch==epoch?"current":"earlier");
        }catch(InterruptedException cancelled){throw cancelled;}catch(Exception unverified){return null;}
    }

    /** One explicit metadata page, including completed originals whose UI callback was lost. */
    public JSONObject documentActions(String cursor,RunControl control)throws Exception {return tasks.ownerDocumentActionsForOwner(cursor,control);}

    /** The file picker supplies a real imported record. JSON callers cannot create an association here. */
    public JSONObject selectForTask(String taskId,String artifactId,String expectedSha256,RunControl control)throws Exception {
        ownerTaskRequired(taskId);JSONObject actual=privateMetadata(artifactId,expectedSha256);
        requireReusableInput(actual);repository.book.requireUnchanged(artifactId,expectedSha256,control);
        JSONObject selected=tasks.linkOwnerAttachment(taskId,actual);control.check();return selected;
    }
    /** Display metadata for exact retained message references. Reading/opening still verifies canonical bytes. */
    public JSONArray messageReferences(JSONArray references)throws Exception {
        JSONArray result=new JSONArray();if(references==null)return result;if(references.length()>4)throw new SecurityException("原消息附件数无效");
        for(int i=0;i<references.length();i++){
            JSONObject pinned=references.getJSONObject(i);
            try{JSONObject meta=privateMetadata(pinned.getString("id"),pinned.getString("sha256"));if(OwnerPythonOutputText.claims(meta.optJSONObject("source"))||!sameReference(pinned,meta))throw new SecurityException("原消息附件版本变化");result.put(row(meta,"source").put("contentReadConfirmed",false));}
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
        requireReusableInput(reference(taskId,artifactId,expectedSha256));
        Intent prepared=repository.verifiedViewIntent(artifactId,expectedSha256,control);
        try{requireReference(taskId,artifactId,expectedSha256);control.checkpoint();return prepared;}
        catch(Exception failure){try{repository.discardVerifiedView(prepared);}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** A positive catalogue hint only; actual opens also check the same live native producer and bytes. */
    private static boolean recordedDocumentOriginal(JSONObject task,JSONObject file)throws Exception {
        String action=task.optString("id"),id=file.optString("id"),sha=file.optString("sha256"),status=task.optString("status");
        if(!action.matches("[a-f0-9-]{36}")||!id.matches("[a-f0-9-]{36}")||!sha.matches("[a-f0-9]{64}")
                ||!action.equals(task.opt("runId"))||!action.equals(task.opt("operationId"))||task.optInt("coordinatorEpoch",-1)!=1
                ||!"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))
                ||!task.isNull("origin")||!task.isNull("conversationId")||!task.isNull("parentTaskId")||!task.isNull("teamRunId")||!task.isNull("memberId")
                ||task.optBoolean("contextWithdrawn")||!task.optBoolean("contextAvailable",true)||task.optBoolean("sourceCurrentReadRequired")||"source_unavailable".equals(task.opt("taskProjection"))
                ||!"attachments".equals(task.opt("feature"))||!"createDocument".equals(task.opt("command"))
                ||!Set.of("needs_confirmation","completed").contains(status)||!"completed".equals(task.opt("executionStatus"))
                ||!id.equals(task.opt("externalId"))||!action.equals(task.opt("externalOperationId"))
                ||!privateFile(file)||file.optBoolean("outputSourceRetired")||file.optLong("size",-1)<=0
                ||file.optLong("size",-1)>AttachmentBook.MAX_FILE_BYTES)return false;
        JSONObject evidence=task.optJSONObject("evidence"),refs=task.optJSONObject("externalRefs"),source=file.optJSONObject("source");
        JSONObject draft=refs==null?null:refs.optJSONObject("documentCreation"),publication=source==null?null:source.optJSONObject("nativePublication");
        if(evidence==null||draft==null||source==null||publication==null||!id.equals(evidence.opt("id"))
                ||!action.equals(evidence.opt("operationId"))||!"completed".equals(evidence.opt("executorStatus")))return false;
        String plan=draft.optString("planDigest"),format=draft.optString("format"),mime=file.optString("mimeType");
        return ("pdf".equals(format)&&"application/pdf".equals(mime)
                ||"docx".equals(format)&&"application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime))
                &&"created_document".equals(source.opt("kind"))&&!source.has("groupSpaceId")&&format.equals(source.opt("documentFormat"))
                &&plan.matches("[a-f0-9]{64}")&&plan.equals(source.opt("planDigest"))&&plan.equals(publication.opt("planDigest"))
                &&action.equals(source.opt("taskId"))&&action.equals(source.opt("runId"))&&action.equals(source.opt("operationId"))
                &&action.equals(publication.opt("taskId"))&&action.equals(publication.opt("runId"))&&action.equals(publication.opt("operationId"))
                &&"document_output".equals(publication.opt("role"))&&AttachmentBook.isOriginalPublicationId(id,action,"document_output",action,action,plan);
    }
    /** Exact private Library relationship plus the ordinary original-file byte verification.
     * The saved TXT's stored native locator, not the display row or name, is authoritative. */
    public OwnerFilePreviewSource originalWordExcerptSource(String excerptId,String expectedExcerptSha,String sourceId,
            String expectedSourceSha,int unit,int chunk,int parts,RunControl control)throws Exception {
        control.checkpoint();
        if(excerptId.equals(sourceId))throw new SecurityException("摘录的原 Word 路径无效");
        String receipt=requirePersistedWordLocator(excerptId,expectedExcerptSha,sourceId,expectedSourceSha,unit,chunk,parts);
        JSONObject selected=privateMetadata(sourceId,expectedSourceSha);
        if(!ordinaryWordInput(selected,selected.optJSONObject("source")))
            throw new SecurityException("摘录不再指向原私人 Word 文件");
        OwnerFilePreviewSource verified=originalFile(null,sourceId,expectedSourceSha,control);
        JSONObject checked=verified.metadata();
        if(!ordinaryWordInput(checked,checked.optJSONObject("source"))||
                !receipt.equals(requirePersistedWordLocator(excerptId,expectedExcerptSha,sourceId,expectedSourceSha,unit,chunk,parts)))
            throw new SecurityException("摘录与原 Word 的位置已变化，未打开其他文件");
        control.checkpoint();return verified;
    }
    private String requirePersistedWordLocator(String excerptId,String excerptSha,String sourceId,String sourceSha,
            int unit,int chunk,int parts)throws Exception {
        JSONObject excerpt=privateMetadata(excerptId,excerptSha),entry=excerpt.optJSONObject("source");
        if(entry==null||!"text/plain".equals(excerpt.opt("mimeType"))||excerpt.optBoolean("outputSourceRetired")||
                !"document_extract".equals(entry.opt("kind"))||!AttachmentDocuments.WORD_EXCERPT.equals(entry.opt("originalWordExcerpt")))
            throw new SecurityException("未核对到这份已保存的 Word 摘录");
        JSONObject actual=wordExcerptStart(entry,Map.of(sourceId,sourceSha));
        if(actual==null||actual.getInt("unit")!=unit||actual.getInt("chunk")!=chunk||actual.getInt("parts")!=parts)
            throw new SecurityException("未核对到这份摘录保存的原 Word 位置");
        return entry.getString("readReceiptId");
    }

    /** Actual original-format viewer; task/audience and canonical byte checks remain this host's. */
    public OwnerFilePreviewSource originalFile(String taskId,String artifactId,String expectedSha256,RunControl control)throws Exception {
        return originalFile(taskId,artifactId,expectedSha256,control,false);
    }
    /** The already-authorized original file, opened directly from its native generation record. */
    public OwnerFilePreviewSource originalRecordedDocument(String taskId,String artifactId,String expectedSha256,RunControl control)throws Exception {
        return originalFile(taskId,artifactId,expectedSha256,control,true);
    }
    private OwnerFilePreviewSource originalFile(String taskId,String artifactId,String expectedSha256,RunControl control,boolean recordedDocument)throws Exception {
        requireReference(taskId,artifactId,expectedSha256);JSONObject metadata=privateMetadata(artifactId,expectedSha256);
        if(recordedDocument){
            if(!recordedDocumentOriginal(ownerTaskRequired(taskId),metadata))throw new SecurityException("没有核对到这次生成的固定原件");
            String plan=metadata.getJSONObject("source").getString("planDigest");
            // The existing Book resolver checks the exact committed slot and bytes; it does not reconcile or republish the task.
            JSONObject read=documents.documentResultForHost(taskId,taskId,taskId,plan,control);
            if(!Boolean.TRUE.equals(read.opt("found"))||!Boolean.TRUE.equals(read.opt("originalPublication"))
                    ||!artifactId.equals(read.opt("id"))||!sameReference(metadata,read)
                    ||!recordedDocumentOriginal(ownerTaskRequired(taskId),read))
                throw new SecurityException("原生成记录已变化，未打开其他文件");
        }else repository.book.requireUnchanged(artifactId,expectedSha256,control);
        boolean localText=OwnerPythonOutputText.claims(metadata.optJSONObject("source"));
        return new OwnerFilePreviewSource("attachments",metadata,c->repository.book.verifiedBytes(artifactId,expectedSha256,c),
                c->{requireReference(taskId,artifactId,expectedSha256);repository.book.requireUnchanged(artifactId,expectedSha256,c);},
                (destination,c)->{
                    if(localText)throw new SecurityException("Python 输出 TXT 仅在原工作或私人文件库内查看");
                    repository.book.exportOwnerArchiveBytes(artifactId,expectedSha256,destination,c);
                });
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
        JSONObject meta=reference(taskId,artifactId,expectedSha256);
        if(OwnerPythonOutputText.claims(meta.optJSONObject("source")))throw new SecurityException("Python 输出 TXT 不使用导出");
        JSONObject result=repository.exportVerifiedToUri(artifactId,expectedSha256,destination,control);
        requireReference(taskId,artifactId,expectedSha256);return result.put("taskId",taskId==null?"":taskId).put("verifiedBusinessSuccess",false);
    }
    /** A compare-only native receipt. Does not read another file or grant an export. */
    public JSONObject originalTaskOutputForExport(String taskId,JSONObject shown,RunControl control)throws Exception{
        control.check();String id=shown.getString("id"),sha=shown.getString("sha256");
        if(OwnerPythonOutputText.claims(reference(taskId,id,sha).optJSONObject("source")))throw new SecurityException("Python 输出 TXT 不使用导出");
        JSONObject pin=OriginalTaskOutputPin.checked(ownerTaskRequired(taskId),privateMetadata(id,sha),shown);control.check();return pin;
    }
    public JSONObject originalMedia(String taskId,String operationId,String command,RunControl control)throws Exception {
        ownerTaskRequired(taskId);JSONObject source=tasks.ownerMediaOperationSource(taskId,operationId);
        if(!command.matches("getOperation|getJob|reconcileOperation|fetchResults|cancelJob"))throw new SecurityException("仅查询、获取或停止原媒体操作");
        JSONObject payload=new JSONObject().put("taskId",taskId).put("operationId",operationId).put("runId",source.getString("runId")).put("epoch",source.getInt("epoch"));
        return media.call(command,payload,control);
    }
    public void requireReference(String taskId,String artifactId,String expectedSha256)throws Exception {reference(taskId,artifactId,expectedSha256);}
    private JSONObject reference(String taskId,String artifactId,String expectedSha256)throws Exception {
        JSONObject meta=privateMetadata(artifactId,expectedSha256);if(taskId==null||taskId.isEmpty())return meta;
        JSONObject provenance=meta.optJSONObject("source");
        if(OriginalNodeCompilerDelivery.claims(provenance)){
            if(!taskId.equals(provenance.opt("taskId")))requireReusableInput(meta);
            return meta;
        }
        if(OwnerPythonOutputText.claims(provenance)){
            if(!taskId.equals(provenance.opt("taskId")))requireReusableInput(meta);
            return meta;
        }
        JSONObject original=ownerTaskRequired(taskId);
        if(provenance!=null&&"remote_result_snapshot".equals(provenance.opt("kind"))&&taskId.equals(provenance.opt("taskId")))
            dev.pocketengine.features.remote.RemoteSavedOriginal.requireLive(original,meta);
        JSONArray selected=tasks.ownerAttachmentReferences(taskId);
        for(int i=0;i<selected.length();i++)if(artifactId.equals(selected.getJSONObject(i).getString("id"))&&sameReference(selected.getJSONObject(i),meta))return meta;
        JSONObject source=meta.optJSONObject("source");if(source!=null&&taskId.equals(source.optString("taskId"))){
            if(source.optString("kind").equals("generated_media")){JSONObject op=tasks.ownerMediaOperationSource(taskId,source.getString("operationId"));if(!op.getString("runId").equals(source.optString("runId")))throw new SecurityException("文件不属于原媒体执行");}
            return meta;
        }
        throw new SecurityException("该文件未被明确选为原任务来源，也不是其真实成果");
    }
    private static void requireReusableInput(JSONObject file){
        if(OriginalNodeCompilerDelivery.claims(file.optJSONObject("source")))
            throw new SecurityException("此固定编译输出仅在本机查看或另存");
        if(OwnerPythonOutputText.claims(file.optJSONObject("source")))
            throw new SecurityException("Python 输出 TXT 仅在原工作或私人文件库内查看");
    }
    private JSONObject privateMetadata(String id,String expected)throws Exception {
        JSONObject meta=repository.book.get(id);if(!privateFile(meta)||expected==null||!expected.equals(meta.optString("sha256")))throw new SecurityException("文件空间或不可变版本不匹配");
        JSONObject source=meta.optJSONObject("source");
        if(OriginalNodeCompilerDelivery.claims(source)){
            if(Thread.holdsLock(repository.book))throw new SecurityException("固定编译输出须在原文件锁外核实");
            tasks.requireSettledNodeCompilerAttachment(meta);
            return meta;
        }
        if(OwnerPythonOutputText.claims(source)){
            if(Thread.holdsLock(repository.book))throw new SecurityException("固定 TXT 须在原文件锁外核实");
            tasks.requireSavedOwnerPythonOutputText(meta);
            return meta;
        }
        JSONObject task=source==null||source.optString("taskId").isEmpty()?null:tasks.get(source.getString("taskId"),false);
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
