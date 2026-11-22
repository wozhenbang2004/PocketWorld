package dev.pocketengine.features.attachments;

import dev.pocketengine.core.ToolOutcome;
import java.util.Locale;
import org.json.JSONObject;

/** One committed foreground Chat TXT. This is a locator, not a grant or a completed Work verdict. */
public record OriginalReplyText(String operationId,String id,String sha256,String name,long size) {
    public static final String TOOL="attachments_create_text", MIME="text/plain";

    public static OriginalReplyText fromReceipt(JSONObject receipt) {
        JSONObject proof=receipt.optJSONObject("receipt"),data=receipt.optJSONObject("data");
        if(!TOOL.equals(receipt.opt("tool"))||!"created".equals(receipt.opt("status"))
                ||!Boolean.TRUE.equals(receipt.opt("ok"))||!Boolean.TRUE.equals(receipt.opt("accepted"))
                ||!Boolean.FALSE.equals(receipt.opt("automaticReplayAllowed"))||proof==null||data==null
                ||!"created".equals(proof.opt("status"))||!Boolean.TRUE.equals(proof.opt("accepted"))
                ||!"terminal".equals(ToolOutcome.classify(receipt).completion()))return null;
        return checked(receipt.opt("hostOperationId"),data);
    }

    public static OriginalReplyText fromLink(JSONObject link) {
        if(link==null||link.length()!=3||!(link.opt("input") instanceof String input)||!OriginalReplyDocument.uuid(input)
                ||OriginalReplyDocument.integer(link.opt("inputRevision"))<1)return null;
        JSONObject file=link.optJSONObject("file");
        return file!=null&&file.length()==6?checked(file.opt("operationId"),file):null;
    }

    private static OriginalReplyText checked(Object op,JSONObject data) {
        long size=OriginalReplyDocument.integer(data.opt("size"));
        // createTextForHost accepts at most 65,536 UTF-16 code units; UTF-8 needs at most 3 bytes per unit.
        if(!(op instanceof String operation)||!OriginalReplyDocument.uuid(operation)
                ||!(data.opt("id") instanceof String id)||!OriginalReplyDocument.uuid(id)
                ||!(data.opt("sha256") instanceof String sha)||!sha.matches("[a-f0-9]{64}")
                ||!(data.opt("name") instanceof String name)||name.length()>160||!name.toLowerCase(Locale.ROOT).endsWith(".txt")
                ||!MIME.equals(data.opt("mimeType"))||size<1||size>196608)return null;
        return new OriginalReplyText(operation,id,sha,name,size);
    }

    public JSONObject view()throws Exception{return new JSONObject().put("id",id).put("sha256",sha256).put("name",name).put("mimeType",MIME).put("size",size);}
    public JSONObject pin()throws Exception{return view().put("operationId",operationId);}

    boolean matchesBook(JSONObject row,String task,String run) {
        JSONObject source=row.optJSONObject("source");
        return "owner".equals(row.opt("principalId"))&&"private".equals(row.opt("spaceId"))&&!row.optBoolean("outputSourceRetired")
                &&!row.has("groupSource")&&id.equals(row.opt("id"))&&sha256.equals(row.opt("sha256"))&&name.equals(row.opt("name"))
                &&MIME.equals(row.opt("mimeType"))&&size==OriginalReplyDocument.integer(row.opt("size"))
                &&source!=null&&source.length()==3&&"created_text".equals(source.opt("kind"))
                &&task.equals(source.opt("taskId"))&&run.equals(source.opt("runId"));
    }
}
