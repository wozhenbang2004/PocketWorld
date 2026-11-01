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
    public JSONObject documentCapabilities()throws Exception{return documents.capabilities();}
    public void requireOwnerAttachment(String id)throws Exception{ownerRecord(repository.book.get(id));}
    /** Native scoped document consumer; this method is intentionally absent from generic feature commands. */
    public JSONObject readVerifiedNativeDocument(String id,String digest,byte[] bytes,dev.pocketengine.features.attachments.documents.DocumentContent.Request range,dev.pocketengine.core.RunControl control)throws Exception {
        return documents.readVerifiedNativeSnapshot(id,digest,bytes,range,control);
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception{
        if(command.equals("list"))return new JSONObject().put("attachments",ownerFiles());
        if(command.equals("importUri"))return repository.importUri(Uri.parse(input.getString("uri")),0,input.optString("taskId"),input.optString("projectId"));
        if(command.equals("createText")){String name=input.optString("name","note.txt"),mime=input.optString("mimeType","text/plain");if(!java.util.Set.of("text/plain","text/markdown","text/csv","text/tab-separated-values").contains(mime)||name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(docx?|xlsx?|pptx?|pdf|png|jpe?g|webp|mp[34]|wav|apk)$"))throw new IllegalArgumentException("文字保存只能创建真实文本格式；不能将文字命名为Office、PDF或媒体文件");String text=input.getString("content");if(text.length()>65536)throw new IllegalArgumentException("新文本附件最多65536字符");JSONObject source=new JSONObject().put("kind","created_text");if(input.has("taskId"))source.put("taskId",input.getString("taskId"));if(input.has("projectId"))source.put("projectId",input.getString("projectId"));return repository.book.importBytes(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),input.optString("name","note.txt"),input.optString("mimeType","text/plain"),source);}
        String id=input.getString("id");ownerRecord(repository.book.get(id));
        switch(command){
            case "get":return repository.book.get(id);
            case "readText":return repository.book.readText(id);
            case "exportToUri":return repository.exportToUri(id,Uri.parse(input.getString("uri")));
            case "remove":repository.remove(id);return new JSONObject().put("id",id).put("status","removed");
            case "view":context.android.startActivity(repository.viewIntent(id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return new JSONObject().put("id",id).put("status","viewer_requested").put("verified",false);
            case "share":context.android.startActivity(Intent.createChooser(repository.shareIntent(id),"分享附件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return new JSONObject().put("id",id).put("status","share_sheet_requested").put("verified",false);
            default:throw new IllegalArgumentException("不支持的附件操作");
        }
    }
}
