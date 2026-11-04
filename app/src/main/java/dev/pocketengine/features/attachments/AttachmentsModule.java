package dev.pocketengine.features.attachments;

import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local owner attachment actions. Incoming Android intents are passed by the native host, never by a web page. */
public final class AttachmentsModule implements EngineModule {
    private final FeatureContext context;
    private final AttachmentRepository repository;
    private final AttachmentDocuments documents;
    public interface NativeRecordRead {void check(JSONObject record)throws Exception;}
    private NativeRecordRead nativeRecordRead=record->{};
    public void setNativeRecordRead(NativeRecordRead check){nativeRecordRead=java.util.Objects.requireNonNull(check);}
    private void ownerRecord(JSONObject record)throws Exception{JSONObject source=record.optJSONObject("source");if(source!=null&&source.has("groupSpaceId"))throw new SecurityException("群媒体技术副本请通过原群成果读取");nativeRecordRead.check(record);}
    private JSONArray ownerFiles()throws Exception{JSONArray result=new JSONArray(),all=repository.book.list();for(int i=0;i<all.length();i++)try{JSONObject row=all.getJSONObject(i);ownerRecord(row);result.put(row);}catch(SecurityException hidden){}return result;}
    public AttachmentsModule(FeatureContext context){this(context,new DocumentReader(null,null));}
    public AttachmentsModule(FeatureContext context,DocumentReader reader){this.context=context;repository=AttachmentRepository.get(context.android);documents=new AttachmentDocuments(repository.book,reader);}
    @Override public String id(){return "attachments";}
    @Override public JSONObject snapshot()throws Exception{return new JSONObject().put("attachments",ownerFiles()).put("maxFileBytes",AttachmentBook.MAX_FILE_BYTES).put("maxTotalBytes",AttachmentBook.MAX_TOTAL_BYTES).put("commands",new JSONArray("[\"list\",\"get\",\"readText\",\"importUri\",\"createText\",\"exportToUri\",\"view\",\"share\",\"remove\"]")).put("scope","owner/private");}
    public JSONObject importIntent(Intent intent,String taskId,String projectId)throws Exception{JSONObject result=repository.importIntent(intent,taskId,projectId);context.emit(id(),new JSONObject().put("status",result.getString("status")).put("attachments",result.getJSONArray("attachments")));return result;}
    /** Native capability host only; handle() has no run authority and intentionally cannot dispatch this. */
    public JSONObject readDocument(String attachmentId,String expectedSha256,DocumentContent.Request range,RunControl control)throws Exception{ownerRecord(repository.book.get(attachmentId));return documents.read(attachmentId,expectedSha256,range,control);}
    /** Native owner preview/save only; generic feature JSON cannot mint or use read receipts. */
    public JSONObject previewOwnerDocument(String taskId,String attachmentId,String expectedSha256,DocumentContent.Request range,RunControl control)throws Exception{ownerRecord(repository.book.get(attachmentId));return documents.preview(taskId,attachmentId,expectedSha256,range,control);}
    public JSONObject saveOwnerDocumentExtract(String taskId,String attachmentId,String expectedSha256,String readReceiptId,RunControl control)throws Exception{ownerRecord(repository.book.get(attachmentId));return documents.savePreview(taskId,attachmentId,expectedSha256,readReceiptId,control);}
    public JSONObject documentCapabilities()throws Exception{return documents.capabilities().put("creationFormats",new JSONArray(new String[]{"docx","pdf","xlsx","pptx"})).put("editableSourcePreserved",true).put("creationMaxBodyChars",16000).put("creationMaxSourceBytes",dev.pocketengine.features.attachments.creation.StructuredOfficeDraft.MAX_SOURCE_BYTES);}
    public void requireOwnerAttachment(String id)throws Exception{ownerRecord(repository.book.get(id));}
    /** Native scoped document consumer; this method is intentionally absent from generic feature commands. */
    public JSONObject readVerifiedNativeDocument(String id,String digest,byte[] bytes,dev.pocketengine.features.attachments.documents.DocumentContent.Request range,dev.pocketengine.core.RunControl control)throws Exception {
        return documents.readVerifiedNativeSnapshot(id,digest,bytes,range,control);
    }
    public JSONArray ownerArchiveAttachmentsForHost()throws Exception{return ownerFiles();}
    public long exportOwnerArchiveAttachmentForHost(String id,String sha,java.io.OutputStream target,RunControl control)throws Exception{ownerRecord(repository.book.get(id));long count=repository.book.exportOwnerArchiveBytes(id,sha,target,control);ownerRecord(repository.book.get(id));return count;}
    public JSONObject restoreOwnerArchiveAttachmentForHost(JSONObject metadata,java.io.InputStream bytes,String nativeOperation,String phaseTask,String sectionDigest,JSONObject remappedSource,RunControl control)throws Exception{
        if(!"owner".equals(metadata.optString("principalId"))||!"private".equals(metadata.optString("spaceId"))||metadata.optBoolean("outputSourceRetired"))throw new SecurityException("归档来源不是可恢复的私人原件");
        JSONObject source=new JSONObject(remappedSource.toString()).put("archiveEvidence",new JSONObject().put("originalId",metadata.getString("id")).put("originalSource",metadata.optJSONObject("source")));String expected=metadata.getString("sha256");if(!expected.matches("[a-f0-9]{64}")||metadata.getLong("size")<0||metadata.getLong("size")>AttachmentBook.MAX_FILE_BYTES)throw new SecurityException("原件归档SHA或大小无效");
        java.security.MessageDigest checksum=java.security.MessageDigest.getInstance("SHA-256");java.io.InputStream checked=new java.io.FilterInputStream(bytes){long count;boolean ended;private int observed(int n,byte[] data,int offset)throws java.io.IOException{if(n>0){count+=n;if(count>metadata.optLong("size",-1))throw new java.io.IOException("归档原件超过原长度");checksum.update(data,offset,n);}else if(n==-1&&!ended){ended=true;StringBuilder hash=new StringBuilder();for(byte value:checksum.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));if(count!=metadata.optLong("size",-1)||!expected.contentEquals(hash))throw new java.io.IOException("归档原件实际SHA或长度不一致，未发布");}return n;}@Override public int read(byte[] data,int offset,int length)throws java.io.IOException{return observed(in.read(data,offset,length),data,offset);}@Override public int read()throws java.io.IOException{byte[] one=new byte[1];return read(one,0,1)==-1?-1:one[0]&255;}};
        JSONObject saved=repository.book.importNativeResult(checked,metadata.getString("name"),metadata.getString("mimeType"),source,nativeOperation,"backup_restored_file",phaseTask,phaseTask,sectionDigest,control);if(!expected.equals(saved.getString("sha256"))||metadata.getLong("size")!=saved.getLong("size"))throw new SecurityException("已保存原件与固定归档字节不同；只核对原发布，不重复保存");return saved;
    }
    public JSONObject queryOwnerArchiveAttachmentForHost(String nativeOperation,String phaseTask,String sectionDigest,RunControl original)throws Exception{return repository.book.findNativeResult(nativeOperation,"backup_restored_file",phaseTask,phaseTask,sectionDigest,original);}
    public JSONObject documentResultForHost(String operation,String task,String run,String digest,RunControl control)throws Exception{JSONObject found=repository.book.findNativeResult(operation,"document_output",task,run,digest,control);if(found.optBoolean("found"))ownerRecord(found);return found;}
    public JSONObject inspectProcessingForOwner(OwnerFilePreviewSource source,RunControl control)throws Exception{
        requirePrivateProcessingSource(source,control);return source.mimeType.startsWith("image/")?dev.pocketengine.features.attachments.processing.NativeImageTransform.inspect(source,control):dev.pocketengine.features.attachments.processing.NativeMediaTrim.inspect(source,context.android.getCacheDir(),control);
    }
    public dev.pocketengine.features.attachments.processing.NativeFileProcess.Review reviewProcessingForOwner(OwnerFilePreviewSource source,JSONObject args,RunControl control)throws Exception{
        requirePrivateProcessingSource(source,control);return source.mimeType.startsWith("image/")?dev.pocketengine.features.attachments.processing.NativeFileProcess.image(source,args,control):dev.pocketengine.features.attachments.processing.NativeFileProcess.trim(source,args.getLong("startMillis"),args.getLong("endMillis"),context.android.getCacheDir(),control);
    }
    private void requirePrivateProcessingSource(OwnerFilePreviewSource source,RunControl control)throws Exception{
        source.requireCurrent(control);JSONObject meta=source.metadata();if(meta.has("groupSource")||!"owner".equals(meta.optString("principalId"))||!"private".equals(meta.optString("spaceId")))throw new SecurityException("此原群/团队只读来源不能变为私人加工授权");ownerRecord(repository.book.get(meta.getString("id")));repository.book.readVerifiedMetadata(meta.getString("id"),source.sha256,control);
    }
    public JSONObject executeProcessingForHost(dev.pocketengine.features.attachments.processing.NativeFileProcess.Review review,String operation,String task,String run,int epoch,RunControl control)throws Exception{
        if(!control.hasHostRun()||!control.matchesHostRun(task,run,epoch))throw new SecurityException("加工没有原任务控制");requirePrivateProcessingSource(review.source(),control);return new dev.pocketengine.features.attachments.processing.NativePrivateFileProcessing(repository.book,context.android.getCacheDir()).execute(review,operation,task,run,control);
    }
    public JSONObject processFileForHost(String command,JSONObject input,RunControl control)throws Exception{
        String task=input.getString("_taskId"),run=input.getString("_runId"),operation=input.getString("_operationId"),id=input.getString("attachmentId"),sha=input.getString("expectedSha256");int epoch=input.getInt("_epoch");if(!control.hasHostRun()||!control.matchesHostRun(task,run,epoch))throw new SecurityException("加工没有原宿主任务控制");
        JSONObject original=repository.book.get(id);ownerRecord(original);repository.book.readVerifiedMetadata(id,sha,control);OwnerFilePreviewSource source=new OwnerFilePreviewSource("attachments",original,c->repository.book.readVerifiedSnapshot(id,sha,c),c->{ownerRecord(repository.book.get(id));repository.book.readVerifiedMetadata(id,sha,c);});JSONObject args=new JSONObject(input.toString());for(String key:new String[]{"_taskId","_runId","_operationId","_epoch","attachmentId","expectedSha256"})args.remove(key);
        var review=command.equals("transformImage")?dev.pocketengine.features.attachments.processing.NativeFileProcess.image(source,args,control):command.equals("trimMedia")?dev.pocketengine.features.attachments.processing.NativeFileProcess.trim(source,args.getLong("startMillis"),args.getLong("endMillis"),context.android.getCacheDir(),control):null;if(review==null)throw new SecurityException("未声明的原件加工动作");return executeProcessingForHost(review,operation,task,run,epoch,control);
    }
    public JSONObject processingResultForHost(String operation,String task,String run,String digest,RunControl control)throws Exception{JSONObject found=repository.book.findNativeResult(operation,"media_output",task,run,digest,control);if(found.optBoolean("found"))ownerRecord(found);return found;}
    private dev.pocketengine.features.attachments.creation.NativeDocumentCreation documentCreation(){return new dev.pocketengine.features.attachments.creation.NativeDocumentCreation(repository.book,context.android.getCacheDir(),(id,sha,control)->{ownerRecord(repository.book.get(id));repository.book.readVerifiedMetadata(id,sha,control);});}
    /** Original model journal or the native owner FeatureHost supplies this binding, never draft JSON. */
    public JSONObject createDocumentForHost(JSONObject input,RunControl control)throws Exception{
        String task=input.getString("_taskId"),run=input.getString("_runId"),operation=input.getString("_operationId");int epoch=input.getInt("_epoch");
        if(!control.hasHostRun()||!control.matchesHostRun(task,run,epoch))throw new SecurityException("文档生成缺少原宿主执行控制");
        JSONObject draft=new JSONObject(input.toString());for(String key:new String[]{"_taskId","_runId","_operationId","_epoch","_nativeDocumentPlanDigest"})draft.remove(key);
        try{return documentCreation().create(new dev.pocketengine.features.attachments.creation.DocumentDraft(draft),operation,task,run,control);}
        catch(dev.pocketengine.features.attachments.creation.NativeDocumentCreation.NotPublished failed){return new JSONObject(failed.receipt.toString()).put("taskId",task).put("runId",run);}
    }
    public JSONObject editableDocumentForOwner(String parent,String sha,RunControl control)throws Exception{return documentCreation().readEditableSource(parent,sha,control);}
    @Override public JSONObject handle(String command,JSONObject input)throws Exception{
        if(command.equals("list"))return new JSONObject().put("attachments",ownerFiles());
        if(command.equals("importUri"))return repository.importUri(Uri.parse(input.getString("uri")),0,input.optString("taskId"),input.optString("projectId"));
        if(command.equals("createText")){String name=input.optString("name","note.txt"),mime=input.optString("mimeType","text/plain");if(!java.util.Set.of("text/plain","text/markdown","text/csv","text/tab-separated-values").contains(mime)||name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(docx?|xlsx?|pptx?|pdf|png|jpe?g|webp|mp[34]|wav|apk)$"))throw new IllegalArgumentException("文字保存只能创建真实文本格式；不能将文字命名为Office、PDF或媒体文件");String text=input.getString("content");if(text.length()>65536)throw new IllegalArgumentException("新文本附件最多65536字符");JSONObject source=new JSONObject().put("kind","created_text");if(input.has("taskId"))source.put("taskId",input.getString("taskId"));if(input.has("projectId"))source.put("projectId",input.getString("projectId"));return repository.book.importBytes(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),input.optString("name","note.txt"),input.optString("mimeType","text/plain"),source);}
        if(command.matches("createDocument|transformImage|trimMedia"))throw new SecurityException("文档生成必须沿原宿主动作和原执行控制；普通JSON不能提供身份");
        String id=input.getString("id");JSONObject selected=repository.book.get(id);ownerRecord(selected);String expectedSha256=selected.getString("sha256");
        switch(command){
            case "get":return repository.book.get(id);
            case "readText":return repository.book.readText(id,expectedSha256);
            case "exportToUri":return repository.exportVerifiedToUri(id,expectedSha256,Uri.parse(input.getString("uri")),new RunControl());
            case "remove":repository.remove(id);return new JSONObject().put("id",id).put("status","removed");
            case "view":context.android.startActivity(repository.viewIntent(id,expectedSha256).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return new JSONObject().put("id",id).put("status","viewer_requested").put("verified",false);
            case "share":context.android.startActivity(Intent.createChooser(repository.shareIntent(id,expectedSha256),"分享附件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return new JSONObject().put("id",id).put("status","share_sheet_requested").put("verified",false);
            default:throw new IllegalArgumentException("不支持的附件操作");
        }
    }
}
