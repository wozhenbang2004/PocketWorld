package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import org.json.JSONObject;

/** The exact originally approved baseline vs. this author's immutable text, never a comparison against a replacement head. */
final class TeamOwnerApprovalFileDiff {
    private TeamOwnerApprovalFileDiff() {}
    static JSONObject page(TeamWorkFiles.Workspace workspace,JSONObject source,String expectedDiff,int offset,RunControl reader)throws Exception {
        reader.check();JSONObject selected=source.getJSONObject("locator"),ref=source.getJSONObject("file");
        String project=selected.getString("projectId"),path=selected.getString("path");
        if(!workspace.byteFilesSupported()||!"text_file".equals(ref.opt("artifactKind"))||!project.equals(ref.opt("targetProjectId"))||
                !path.equals(ref.opt("path"))||selected.getInt("expectedVersion")!=ref.getInt("expectedVersion")||offset<0||offset>65536||
                offset>0&&!expectedDiff.matches("[a-f0-9]{64}"))throw new SecurityException("不是本次可核对的本机原文本");
        JSONObject base=TeamWorkFiles.snapshot(workspace,project,path);
        if(base.getInt("version")!=ref.getInt("expectedVersion")||!ref.getString("expectedHash").equals(TeamWorkFiles.snapshotHash(base)))
            throw new SecurityException("目标文件已经变化，不能拿当前内容冒充批准时的基线；没有更改文件");
        JSONObject artifact=TeamWorkReview.artifact(workspace,ref);reader.check();
        if(!(base.opt("content") instanceof String)||!(artifact.opt("content") instanceof String))throw new SecurityException("未取得完整可读文本，不用摘要代替实际差异");
        JSONObject compared=VersionedWorkspace.compare(path,base.getString("content"),artifact.getString("content"),"原项目 v"+base.getInt("version"),"冻结交付 v"+ref.getInt("draftVersion"));
        String body=compared.getString("diff");boolean truncated=compared.getBoolean("truncated");
        if(truncated&&!body.isEmpty()&&Character.isHighSurrogate(body.charAt(body.length()-1)))body=body.substring(0,body.length()-1);
        String hash=TeamWorkFiles.hash(body);if(!expectedDiff.isEmpty()&&!hash.equals(expectedDiff))throw new SecurityException("上一段与这段不是同一份比较，请从头打开");
        int end=TeamOwnerFileReading.pageEnd(body,offset);
        JSONObject head=TeamWorkFiles.snapshot(workspace,project,path);
        if(base.getInt("version")!=head.getInt("version")||base.getBoolean("exists")!=head.getBoolean("exists")||
                !base.optString("revisionId").equals(head.optString("revisionId"))||!ref.getString("expectedHash").equals(TeamWorkFiles.snapshotHash(head)))
            throw new SecurityException("读取中目标文件已变化，未返回其他版本");
        reader.check();return new JSONObject().put("proof",selected.getString("proof")).put("approvalId",selected.getString("approvalId")).put("operationId",selected.getString("operationId"))
                .put("offset",offset).put("endOffset",end).put("retainedChars",body.length()).put("diffHash",hash).put("diff",body.substring(offset,end))
                .put("baseExists",base.getBoolean("exists")).put("nativeTruncated",truncated).put("readOnly",true).put("executionAuthorized",false);
    }
}
