package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded document reads and explicit local extracts. The host owns task/run identity, scope and cancellation. */
public final class AttachmentDocuments {
    public static final int MAX_RESPONSE_BYTES=48*1024;
    private final AttachmentBook book;
    private final DocumentReader reader;
    private final Map<String,Preview> previews=new LinkedHashMap<>();
    private static final class Preview {
        final String taskId,fingerprint;final AttachmentBook.VerifiedMetadata source;final DocumentContent.Request range;
        Preview(String taskId,String fingerprint,AttachmentBook.VerifiedMetadata source,DocumentContent.Request range){this.taskId=taskId;this.fingerprint=fingerprint;this.source=source;this.range=range;}
    }
    public AttachmentDocuments(AttachmentBook book,DocumentReader reader) {
        this.book=java.util.Objects.requireNonNull(book);this.reader=java.util.Objects.requireNonNull(reader);
    }
    public JSONObject capabilities()throws Exception {
        return new JSONObject().put("docx",true).put("xlsx",true).put("pptx",true).put("strictUtf8",true)
            .put("pdfText",reader.pdfTextAvailable()).put("imageOcr",reader.imageOcrAvailable())
            .put("pdfOcr",reader.pdfOcrAvailable()).put("legacyOffice",false)
            .put("parserVersion",DocumentContent.PARSER_VERSION).put("maxResponseBytes",MAX_RESPONSE_BYTES);
    }
    public JSONObject read(String attachmentId,String expectedSha256,DocumentContent.Request range,RunControl control)throws Exception {
        java.util.Objects.requireNonNull(range);if(control==null)throw new IllegalArgumentException("Live RunControl required");
        try {
            byte[] bytes=book.verifiedBytes(attachmentId,expectedSha256,control);
            DocumentContent.Result content=reader.read(bytes,range,control);
            // A removed/replaced attachment must not publish content retained by an earlier snapshot.
            book.requireUnchanged(attachmentId,expectedSha256,control);control.checkpoint();
            return projection(attachmentId,expectedSha256,range,content,control);
        }catch(AttachmentBook.InputChangedException changed) {
            control.checkpoint();return envelope(attachmentId,expectedSha256).put("status","input_changed")
                .put("coverage","none").put("chunks",new JSONArray()).put("warnings",new JSONArray().put("Attachment is missing, changed or no longer matches its recorded bytes; reselect its current digest"));
        }
    }
    /** Native owner preview only. This receipt identifies what was read; it is never a scope or write grant. */
    JSONObject preview(String taskId,String attachmentId,String expectedSha256,DocumentContent.Request range,RunControl control)throws Exception {
        JSONObject result=read(attachmentId,expectedSha256,range,control);
        if(savable(result)){
            AttachmentBook.VerifiedMetadata source=book.readVerifiedMetadata(attachmentId,expectedSha256,control);
            Preview preview=new Preview(taskId==null?"":taskId,fingerprint(result),source,range);String token=UUID.randomUUID().toString();control.checkpoint();
            synchronized(previews){while(previews.size()>=8)previews.remove(previews.keySet().iterator().next());previews.put(token,preview);}
            result.put("readReceiptId",token);
        }
        return result;
    }
    /** Re-read the exact native preview range from current verified bytes. No caller-supplied text or new range is accepted. */
    JSONObject savePreview(String taskId,String attachmentId,String expectedSha256,String readReceiptId,RunControl control)throws Exception {
        control.checkpoint();Preview preview;
        synchronized(previews){preview=previews.get(readReceiptId);}
        if(preview==null||!preview.taskId.equals(taskId==null?"":taskId)||!preview.source.id.equals(attachmentId)||!preview.source.sha256.equals(expectedSha256))throw new SecurityException("当前范围没有可核对的原生读取记录，请重新读取后保存");
        verifySource(preview,control);
        JSONObject actual=read(attachmentId,expectedSha256,preview.range,control);
        if(!savable(actual)||!preview.fingerprint.equals(fingerprint(actual)))throw new SecurityException("原文件或提取结果已变化，请重新读取后保存；尚未保存摘录");
        byte[] bytes=extractText(preview,actual).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>128*1024)throw new IllegalArgumentException("当前摘录超过保存限制，请缩小读取范围");
        JSONObject source=new JSONObject().put("kind","document_extract").put("sourceAttachmentId",attachmentId).put("sourceSha256",expectedSha256)
            .put("readReceiptId",readReceiptId).put("readRange",range(preview.range)).put("extraction",actual.getString("extraction"))
            .put("parserVersion",actual.getString("parserVersion")).put("engineVersion",actual.getString("engineVersion")).put("coverage",actual.getString("coverage"))
            .put("nextUnit",actual.getInt("nextUnit")).put("nextChunk",actual.getInt("nextChunk"));
        if(!preview.taskId.isEmpty())source.put("taskId",preview.taskId);
        JSONObject created=null;
        try {
            synchronized(book){
                verifySource(preview,control);String digest=digest(bytes);JSONArray files=book.list();
                for(int i=0;i<files.length();i++){
                    JSONObject file=files.getJSONObject(i),origin=file.optJSONObject("source");
                    if(origin==null||!"document_extract".equals(origin.optString("kind"))||!readReceiptId.equals(origin.optString("readReceiptId")))continue;
                    if(!preview.taskId.equals(origin.optString("taskId"))||!attachmentId.equals(origin.optString("sourceAttachmentId"))||!expectedSha256.equals(origin.optString("sourceSha256"))||!digest.equals(file.optString("sha256")))throw new SecurityException("原摘录记录已变化，请先核对现有成果");
                    book.readVerifiedMetadata(file.getString("id"),digest,control);verifySource(preview,control);return file;
                }
                String name=preview.source.name.replaceFirst("\\.[^.]{1,16}$","");name=name.substring(0,Math.min(130,name.length()))+"-摘录.txt";
                created=book.importBytes(new ByteArrayInputStream(bytes),name,"text/plain",source,control);
                verifySource(preview,control);control.checkpoint();return created;
            }
        }catch(Exception failed){if(created!=null)try{book.remove(created.getString("id"));}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
    private void verifySource(Preview preview,RunControl control)throws Exception {
        AttachmentBook.VerifiedMetadata actual=book.readVerifiedMetadata(preview.source.id,preview.source.sha256,control),expected=preview.source;
        if(!actual.name.equals(expected.name)||!actual.mimeType.equals(expected.mimeType)||actual.size!=expected.size)throw new SecurityException("原文件版本或元数据已变化，请重新读取后保存");
    }
    private static boolean savable(JSONObject result){return result.optString("status").matches("ok|partial|truncated")&&result.optJSONArray("chunks")!=null&&result.optJSONArray("chunks").length()>0;}
    private static JSONObject range(DocumentContent.Request r)throws Exception{return new JSONObject().put("startUnit",r.startUnit).put("startChunk",r.startChunk).put("maxUnits",r.maxUnits).put("maxChars",r.maxChars).put("maxChunks",r.maxChunks).put("extraction",r.extraction);}
    private static String extractText(Preview preview,JSONObject result)throws Exception {
        StringBuilder text=new StringBuilder("文档摘录 · 仅本次已读范围\n来源：").append(preview.source.name).append("\n来源文件：").append(preview.source.id).append("\n来源版本 SHA-256：").append(preview.source.sha256)
            .append("\n格式：").append(result.getString("format")).append("\n读取方式：").append(result.getString("extraction")).append("\n解析版本：").append(result.getString("parserVersion")).append("\n读取引擎：").append(result.getString("engineVersion"))
            .append("\n\n以下内容仅包含本次已查看的范围，不表示全文已读。原文件保持不变。\n");
        if("ocr".equals(result.optString("extraction"))||result.optString("coverage").contains("ocr"))text.append("OCR 识别文字可能有误，请与原图或原页核对。\n");
        if(result.optBoolean("truncated"))text.append("本次读取尚有后续内容；后续内容未包含在此摘录中。\n");
        JSONArray chunks=result.getJSONArray("chunks");
        for(int i=0;i<chunks.length();i++){
            JSONObject chunk=chunks.getJSONObject(i);text.append("\n[来源位置：").append(canonical(chunk.getJSONObject("locator"))).append("]\n").append(chunk.getString("text")).append('\n');
        }
        JSONArray warnings=result.optJSONArray("warnings");if(warnings!=null&&warnings.length()>0){text.append("\n读取说明\n");for(int i=0;i<warnings.length();i++)text.append("- ").append(warnings.getString(i)).append('\n');}
        return text.toString();
    }
    private static String fingerprint(JSONObject value)throws Exception{return digest(canonical(value).getBytes(StandardCharsets.UTF_8));}
    private static String digest(byte[] bytes)throws Exception{StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}return value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    /** Native verified scoped bytes only. Caller owns scope/version checks before and after parsing. No module JSON route. */
    public JSONObject readVerifiedNativeSnapshot(String artifactId,String expectedSha256,byte[] bytes,DocumentContent.Request range,RunControl control)throws Exception {
        if(bytes==null||bytes.length>AttachmentBook.MAX_FILE_BYTES||expectedSha256==null||!expectedSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原文档快照无效");
        StringBuilder hash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!expectedSha256.equals(hash.toString()))throw new SecurityException("原文档快照digest不符");
        control.checkpoint();DocumentContent.Result content=reader.read(bytes,range,control);return projection(artifactId,expectedSha256,range,content,control);
    }
    private JSONObject projection(String attachmentId,String expectedSha256,DocumentContent.Request range,DocumentContent.Result content,RunControl control)throws Exception {
            JSONObject result=envelope(attachmentId,expectedSha256).put("format",content.format)
                .put("status",content.truncated?"truncated":content.status).put("extractionStatus",content.status)
                .put("extraction",range.extraction)
                .put("coverage",content.coverage).put("engineVersion",reader.engineVersion(content.format,range.extraction))
                .put("totalUnits",content.totalUnits).put("truncated",content.truncated)
                .put("nextUnit",content.nextUnit).put("nextChunk",content.nextChunk).put("warnings",new JSONArray(content.warnings));
            JSONArray chunks=new JSONArray();result.put("chunks",chunks);
            int used=result.toString().getBytes(StandardCharsets.UTF_8).length;
            for(DocumentContent.Chunk chunk:content.chunks) {
                JSONObject value=new JSONObject().put("unit",chunk.unit).put("chunk",chunk.chunk)
                    .put("kind",chunk.kind).put("text",chunk.text).put("locator",new JSONObject(chunk.locator));
                int size=value.toString().getBytes(StandardCharsets.UTF_8).length+1;
                if(used+size>MAX_RESPONSE_BYTES-512) {
                    result.put("status","truncated").put("truncated",true).put("nextUnit",chunk.unit).put("nextChunk",chunk.chunk);
                    result.getJSONArray("warnings").put("Response byte budget reached; continue with the returned cursor");break;
                }
                chunks.put(value);used+=size;
            }
            control.checkpoint();return result;
    }
    private static JSONObject envelope(String id,String digest)throws Exception {
        return new JSONObject().put("attachmentId",id).put("sha256",digest).put("parserVersion",DocumentContent.PARSER_VERSION)
            .put("untrustedAttachmentContent",true).put("verifiedBusinessSuccess",false).put("cacheUsed",false);
    }
}
