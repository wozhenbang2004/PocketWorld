package dev.pocketengine.features.attachments.creation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Native bounded format creation in the existing attachment Book. Editable source and output
 * have deterministic original-operation identities; partial publication is query-only. */
public final class NativeDocumentCreation {
    public static final class NotPublished extends Exception {
        public final JSONObject receipt;
        private NotPublished(Exception failure,JSONObject source,String operation)throws Exception{super("生成输出尚未进入发布，原可编辑源已保存："+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()),failure);receipt=new JSONObject().put("status","failed").put("accepted",false).put("receiptReceived",true).put("found",false).put("operationId",operation).put("outputNotPublished",true).put("editableSource",reference(source)).put("code","document_output_not_published").put("message",getMessage());}
    }
    public interface SourceRead {void check(String id,String sha,RunControl control)throws Exception;}
    private final AttachmentBook book;private final File cache;private final SourceRead original;
    public NativeDocumentCreation(AttachmentBook book,File privateCache,SourceRead original){this.book=book;cache=privateCache;this.original=original;}
    public JSONObject create(DocumentDraft draft,String operation,String task,String run,RunControl control)throws Exception{
        control.check();checkParents(draft,control);JSONObject prior=book.findNativeResult(operation,"document_output",task,run,draft.identity,control);if(prior.optBoolean("found"))return receipt(prior,draft,operation,task,run);
        byte[] sourceBytes=draft.sourceBytes();JSONObject origin=new JSONObject().put("kind","document_source").put("taskId",task).put("runId",run).put("operationId",operation).put("planDigest",draft.identity).put("documentFormat",draft.format);
        JSONObject editable=book.importNativeResult(new ByteArrayInputStream(sourceBytes),draft.name+".document.json","application/json",origin,operation,"document_source",task,run,draft.identity,control);
        byte[] bytes;JSONObject check;
        try{if(draft.format.equals("docx")){bytes=WordDocumentWriter.write(draft,control);check=WordDocumentWriter.check(bytes,draft,control);}else if(draft.format.equals("xlsx")){bytes=SpreadsheetDocumentWriter.write(draft,control);check=SpreadsheetDocumentWriter.check(bytes,draft,control);}else if(draft.format.equals("pptx")){bytes=PresentationDocumentWriter.write(draft,control);check=PresentationDocumentWriter.check(bytes,draft,control);}else{var generated=AndroidPdfDocumentWriter.write(draft,new File(cache,"document-creation-checks"),control);bytes=generated.bytes;check=generated.check;}checkParents(draft,control);control.check();}
        catch(Exception preparationFailed){throw new NotPublished(preparationFailed,editable,operation);}
        JSONObject source=new JSONObject().put("kind","created_document").put("taskId",task).put("runId",run).put("operationId",operation).put("planDigest",draft.identity).put("documentFormat",draft.format).put("editableSource",reference(editable)).put("formatCheck",check);
        JSONObject input=draft.source();if(input.has("parentArtifactId"))source.put("parentArtifact",new JSONObject().put("id",input.getString("parentArtifactId")).put("sha256",input.getString("parentSha256")));
        JSONObject saved=book.importNativeResult(new ByteArrayInputStream(bytes),draft.fileName(),draft.mime(),source,operation,"document_output",task,run,draft.identity,control);control.check();return receipt(saved,draft,operation,task,run);
    }
    public JSONObject readEditableSource(String parentId,String parentSha,RunControl control)throws Exception{
        original.check(parentId,parentSha,control);JSONObject parent=book.get(parentId);JSONObject source=parent.getJSONObject("source").getJSONObject("editableSource");String id=source.getString("id"),sha=source.getString("sha256");original.check(id,sha,control);byte[] bytes=book.readVerifiedSnapshot(id,sha,control);if(bytes.length>256*1024)throw new IllegalStateException("原可编辑源超过范围");JSONObject draft=DocumentDraft.sourceInput(new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()));draft.put("parentArtifactId",parentId).put("parentSha256",parentSha).put("editableSourceId",id).put("editableSourceSha256",sha);new DocumentDraft(draft);original.check(parentId,parentSha,control);return draft;
    }
    private void checkParents(DocumentDraft draft,RunControl control)throws Exception{
        JSONObject input=draft.source();if(!input.has("parentArtifactId")){if(input.has("editableSourceId"))throw new SecurityException("可编辑源必须与原父成果一起选择");return;}
        String parent=input.getString("parentArtifactId"),sha=input.getString("parentSha256");original.check(parent,sha,control);book.readVerifiedMetadata(parent,sha,control);JSONObject actual=book.get(parent).getJSONObject("source");JSONObject editable=actual.getJSONObject("editableSource");
        if(!input.has("editableSourceId")||!input.getString("editableSourceId").equals(editable.getString("id"))||!input.getString("editableSourceSha256").equals(editable.getString("sha256")))throw new SecurityException("继续生成必须使用这份原成果实际登记的可编辑源版本");original.check(editable.getString("id"),editable.getString("sha256"),control);book.readVerifiedMetadata(editable.getString("id"),editable.getString("sha256"),control);
    }
    private static JSONObject reference(JSONObject actual)throws Exception{return new JSONObject().put("id",actual.getString("id")).put("sha256",actual.getString("sha256")).put("mimeType",actual.getString("mimeType")).put("size",actual.getLong("size"));}
    private static JSONObject receipt(JSONObject actual,DocumentDraft draft,String operation,String task,String run)throws Exception{return new JSONObject(actual.toString()).put("status","completed").put("accepted",true).put("receiptReceived",true).put("operationId",operation).put("taskId",task).put("runId",run).put("documentPlanDigest",draft.identity).put("editableSource",actual.getJSONObject("source").getJSONObject("editableSource")).put("formatCheck",actual.getJSONObject("source").getJSONObject("formatCheck")).put("verifiedBusinessSuccess",false).put("message","已生成实际固定文档并保留原可编辑源；未分享或导出，排版仍可在原件查看中核对");}
}
