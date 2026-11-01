package dev.pocketengine.features.attachments.processing;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import java.io.*;
import org.json.JSONObject;

/** Processing and publication remain separate: this publisher accepts only an original private
 * AttachmentBook source, never a group/team read capability or a model-supplied file URI. */
public final class NativePrivateFileProcessing {
    private final AttachmentBook book;private final File cache;
    public NativePrivateFileProcessing(AttachmentBook book,File cache){this.book=book;this.cache=cache;}
    public JSONObject execute(NativeFileProcess.Review review,String operation,String task,String run,RunControl control)throws Exception{
        var source=review.source();source.requireCurrent(control);JSONObject parent=source.metadata();
        if(parent.has("groupSource")||!"owner".equals(parent.optString("principalId"))||!"private".equals(parent.optString("spaceId")))throw new SecurityException("原群或团队只读来源不能转为私人加工写入授权");
        String id=parent.getString("id");JSONObject original=book.get(id),origin=original.optJSONObject("source");if(origin!=null&&origin.has("groupSpaceId")||!source.sha256.equals(original.getString("sha256")))throw new SecurityException("原件不是本私人附件库的选定版本");book.readVerifiedMetadata(id,source.sha256,control);
        JSONObject prior=book.findNativeResult(operation,"media_output",task,run,review.planDigest,control);if(prior.optBoolean("found"))return receipt(prior,operation);
        ProcessedOriginalFile result;
        try{result=review.execute(cache,control);source.requireCurrent(control);book.readVerifiedMetadata(id,source.sha256,control);}catch(Exception preparationFailed){return new JSONObject().put("status","failed").put("accepted",false).put("receiptReceived",true).put("found",false).put("outputNotPublished",true).put("operationId",operation).put("code","original_media_not_published").put("message","加工尚未进入原件发布，原文件保留："+(preparationFailed.getMessage()==null?preparationFailed.getClass().getSimpleName():preparationFailed.getMessage()));}
        if(!result.sourceIdentity.equals(source.identity)||!result.sourceSha256.equals(source.sha256)||!result.planDigest.equals(review.planDigest))throw new SecurityException("真实加工回执不是这次原件和精确方案");
        JSONObject from=new JSONObject().put("kind","derived_media").put("taskId",task).put("runId",run).put("operationId",operation).put("sourceAttachmentId",id).put("sourceSha256",source.sha256).put("parentArtifact",new JSONObject().put("id",id).put("sha256",source.sha256).put("mimeType",source.mimeType).put("size",source.sizeBytes)).put("processing",result.evidence());
        String name=source.name.replaceFirst("\\.[A-Za-z0-9]{1,8}$","")+"-edited."+result.extension;byte[] bytes=result.bytes();try{JSONObject saved=book.importNativeResult(new ByteArrayInputStream(bytes),name,result.mimeType,from,operation,"media_output",task,run,review.planDigest,control);return receipt(saved,operation);}finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
    private static JSONObject receipt(JSONObject actual,String operation)throws Exception{return new JSONObject(actual.toString()).put("status","completed").put("accepted",true).put("receiptReceived",true).put("found",true).put("operationId",operation).put("parentArtifact",actual.getJSONObject("source").getJSONObject("parentArtifact")).put("processing",actual.getJSONObject("source").getJSONObject("processing")).put("verifiedBusinessSuccess",false).put("message","已保存新的实际加工原件与精确父版本，原件未覆盖；没有自动导出或分享");}
}
