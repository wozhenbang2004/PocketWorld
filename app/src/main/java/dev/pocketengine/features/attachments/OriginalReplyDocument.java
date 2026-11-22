package dev.pocketengine.features.attachments;

import dev.pocketengine.core.ToolOutcome;
import dev.pocketengine.features.local.project.OriginalNodeCompilerDelivery;
import dev.pocketengine.features.local.runtime.OwnerPythonOutputText;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** A fixed Word/PDF receipt attached to the actually saved private assistant reply.
 * A pin describes bytes; it is not permission to read them or run the originating tool. */
public record OriginalReplyDocument(String operationId,String planDigest,String id,String sha256,
                                    String name,String mimeType,long size) {
    public static final String TOOL="attachments_create_document";
    private static final String PDF="application/pdf";
    private static final String WORD="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public record Target(String conversation,String generation,String message,long revision,String fingerprint,
                         String reply,String ownerInput,String task,String run,int epoch,String file,String sha) {}

    public static OriginalReplyDocument fromReceipt(JSONObject receipt) {
        JSONObject proof=receipt.optJSONObject("receipt"),data=receipt.optJSONObject("data");
        if(!TOOL.equals(receipt.opt("tool"))||!"created".equals(receipt.opt("status"))
                ||!Boolean.TRUE.equals(receipt.opt("ok"))||!Boolean.TRUE.equals(receipt.opt("accepted"))
                ||!Boolean.FALSE.equals(receipt.opt("automaticReplayAllowed"))||proof==null||data==null
                ||!"created".equals(proof.opt("status"))||!Boolean.TRUE.equals(proof.opt("accepted"))
                ||!"terminal".equals(ToolOutcome.classify(receipt).completion()))return null;
        return checked(receipt.opt("hostOperationId"),data.opt("documentPlanDigest"),data);
    }

    public static OriginalReplyDocument fromPin(JSONObject pin) {
        return pin!=null&&pin.length()==7?checked(pin.opt("operationId"),pin.opt("planDigest"),pin):null;
    }

    public static boolean exactPair(List<OriginalReplyDocument> files) {
        if(files.size()!=2)return false;
        OriginalReplyDocument a=files.get(0),b=files.get(1);
        return a!=null&&b!=null&&(PDF.equals(a.mimeType)&&WORD.equals(b.mimeType)||WORD.equals(a.mimeType)&&PDF.equals(b.mimeType))
                &&!a.operationId.equals(b.operationId)&&!a.id.equals(b.id)&&!a.sha256.equals(b.sha256);
    }

    /** The existing body-free link has either its legacy file or exactly one Word and one PDF. */
    public static List<OriginalReplyDocument> linkedPins(JSONObject link) {
        if(link==null||link.length()!=3||!(link.opt("input") instanceof String input)||!uuid(input)
                ||integer(link.opt("inputRevision"))<1)return List.of();
        if(link.has("file")) {
            OriginalReplyDocument single=fromPin(link.optJSONObject("file"));
            return single==null?List.of():List.of(single);
        }
        JSONArray pins=link.optJSONArray("files");
        if(pins==null||pins.length()!=2)return List.of();
        OriginalReplyDocument a=fromPin(pins.optJSONObject(0)),b=fromPin(pins.optJSONObject(1));
        if(a==null||b==null)return List.of();
        List<OriginalReplyDocument> files=List.of(a,b);
        return exactPair(files)?files:List.of();
    }

    private static OriginalReplyDocument checked(Object op,Object plan,JSONObject data) {
        long size=integer(data.opt("size"));
        if(!(op instanceof String operation)||!uuid(operation)||!(plan instanceof String digest)||!digest.matches("[a-f0-9]{64}")
                ||!(data.opt("id") instanceof String id)||!uuid(id)||!(data.opt("sha256") instanceof String sha)||!sha.matches("[a-f0-9]{64}")
                ||!(data.opt("name") instanceof String name)||name.isBlank()||name.length()>240||size<1||size>AttachmentBook.MAX_FILE_BYTES
                ||!(data.opt("mimeType") instanceof String mime))return null;
        String extension=PDF.equals(mime)?".pdf":WORD.equals(mime)?".docx":"";
        if(extension.isEmpty()||!name.toLowerCase(Locale.ROOT).endsWith(extension))return null;
        return new OriginalReplyDocument(operation,digest,id,sha,name,mime,size);
    }

    public static boolean uuid(String value){return value!=null&&value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    public static long integer(Object value){return value instanceof Integer||value instanceof Long?((Number)value).longValue():-1;}
    public JSONObject view()throws Exception{return new JSONObject().put("id",id).put("sha256",sha256).put("name",name).put("mimeType",mimeType).put("size",size);}
    public JSONObject pin()throws Exception{return view().put("operationId",operationId).put("planDigest",planDigest);}
    public boolean publication(String task,String run)throws Exception{return AttachmentBook.isOriginalPublicationId(id,operationId,"document_output",task,run,planDigest);}

    boolean matchesBook(JSONObject file,String task,String run)throws Exception {
        JSONObject source=file.optJSONObject("source"),fixed=source==null?null:source.optJSONObject("nativePublication");
        return "owner".equals(file.opt("principalId"))&&"private".equals(file.opt("spaceId"))&&!file.optBoolean("outputSourceRetired")
                &&!file.has("groupSource")&&id.equals(file.opt("id"))&&sha256.equals(file.opt("sha256"))&&name.equals(file.opt("name"))
                &&mimeType.equals(file.opt("mimeType"))&&size==integer(file.opt("size"))
                &&source!=null&&"created_document".equals(source.opt("kind"))&&!source.has("groupSpaceId")
                &&!OriginalNodeCompilerDelivery.claims(source)&&!OwnerPythonOutputText.claims(source)
                &&task.equals(source.opt("taskId"))&&run.equals(source.opt("runId"))&&operationId.equals(source.opt("operationId"))
                &&planDigest.equals(source.opt("planDigest"))&&(PDF.equals(mimeType)?"pdf":"docx").equals(source.opt("documentFormat"))
                &&fixed!=null&&"document_output".equals(fixed.opt("role"))&&task.equals(fixed.opt("taskId"))
                &&run.equals(fixed.opt("runId"))&&operationId.equals(fixed.opt("operationId"))&&planDigest.equals(fixed.opt("planDigest"))
                &&publication(task,run);
    }
}
