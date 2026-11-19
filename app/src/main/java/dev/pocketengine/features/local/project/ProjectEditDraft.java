package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original task's immutable comparison, never a writeFile plan or an executable planRef. */
public final class ProjectEditDraft {
    private ProjectEditDraft(){}
    public static final String FORMAT="pocketworld.private-project-edit-draft.v1";
    public static final Set<String> TOOLS=Set.of("project_inspect","project_list_files","project_search","project_read_file","project_plan");
    public static boolean selected(JSONObject context){return context!=null&&context.opt("draftOnly")==Boolean.TRUE;}
    private static String text(JSONObject o,String k){Object v=o.opt(k);if(!(v instanceof String s)||s.isBlank()||s.length()>256||s.indexOf(0)>=0)throw new SecurityException("原任务或源码身份不完整");return s;}
    static byte[] bytes(String text,int max)throws Exception {if(text==null||text.indexOf(0)>=0||text.length()>max)throw new IllegalArgumentException("仅支持未截断的小型 UTF-8 文本文件");
        var encoded=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));if(encoded.remaining()>max)throw new IllegalArgumentException("本次文本超过完整差异上限，未保存片段");byte[] b=new byte[encoded.remaining()];encoded.get(b);return b;}
    public static JSONObject receipt(JSONObject draft)throws Exception{
        JSONObject r=new JSONObject();for(String k:new String[]{"format","draftId","taskId","runId","epoch","projectId","checkoutId","sourceDigest","path","beforeSha256","afterSha256","beforeBytes","afterBytes"})r.put(k,draft.get(k));
        if(draft.has(OwnerRevisionSource.FIELD))r.put(OwnerRevisionSource.FIELD,OwnerRevisionSource.pin(draft.getJSONObject(OwnerRevisionSource.FIELD)));
        return r.put("code","project_edit_draft_saved").put("sourceChanged",false).put("executionAuthorized",false).put("createdCheckout",false);
    }
    public static JSONObject freeze(JSONObject snapshot,JSONObject context,String task,String run,int epoch,JSONObject request)throws Exception{
        if(!selected(context)||epoch<0||(request.length()!=4||!request.has("path")||!request.has("expectedHash")||!request.has("content"))||!"draft_edit".equals(request.opt("intent")))throw new SecurityException("本次只交独立改稿；没有写入、构建或执行入口");
        String project=ProjectBuildPlan.identity(text(context,"projectId")),checkout=ProjectBuildPlan.identity(text(context,"checkoutId"));
        if(context.has(OwnerRevisionSource.FIELD)||snapshot.has(OwnerRevisionSource.FIELD))OwnerRevisionSource.requireSnapshot(snapshot,context.getJSONObject(OwnerRevisionSource.FIELD));
        JSONObject facts=ProjectSources.describe(snapshot);
        if(!project.equals(facts.opt("projectId"))||!checkout.equals(facts.opt("checkoutId"))||!text(context,"initialSourceDigest").equals(facts.opt("sourceDigest")))throw new SecurityException("原选工程或已确认源码版本变化，请重新审阅，未写回工程");
        String path=VersionedWorkspace.path(text(request,"path")),expected=text(request,"expectedHash");
        JSONObject original=null;JSONArray files=snapshot.getJSONArray("files");
        for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if(path.equals(f.opt("path"))){if(original!=null)throw new SecurityException("原文件重复");original=f;}}
        if(original==null||!FixedProjectDraftSource.eligible(path)||original.optBoolean("binary")||!(original.opt("content") instanceof String before)||
                !(request.opt("content") instanceof String after))throw new IllegalArgumentException("只接受原项目中能完整读取的一个既有文本文件");
        byte[] old=bytes(before,65536),next=bytes(after,24576);String base=ProjectSources.sha256(old),proposed=ProjectSources.sha256(next);
        if(!base.equals(expected)||!base.equals(original.opt("sha256")))throw new SecurityException("原文件摘要已变化，没有从其他版本制作草稿");
        if(base.equals(proposed))throw new IllegalArgumentException("没有实际改动，未创建空差异");
        JSONObject result=new JSONObject().put("format",FORMAT).put("taskId",ProjectBuildPlan.identity(task)).put("runId",ProjectBuildPlan.identity(run)).put("epoch",epoch)
            .put("projectId",project).put("checkoutId",checkout).put("sourceDigest",facts.getString("sourceDigest")).put("path",path)
            .put("beforeSha256",base).put("afterSha256",proposed).put("beforeBytes",old.length).put("afterBytes",next.length);
        if(context.has(OwnerRevisionSource.FIELD))result.put(OwnerRevisionSource.FIELD,OwnerRevisionSource.pin(context.getJSONObject(OwnerRevisionSource.FIELD)));
        String id=ProjectSources.digest(result);return result.put("draftId",id).put("before",before).put("after",after);
    }
    /** Never turn an assistant's prose into an artifact or a claim that this attempt finished the request. */
    public static dev.pocketengine.core.RunOutcome settlement(String run,int epoch,dev.pocketengine.core.RunOutcome original,JSONObject saved)throws Exception{
        if(!"completed".equals(original.status))return original;
        boolean actual=saved!=null&&saved.opt("available")==Boolean.TRUE&&run.equals(saved.opt("runId"))&&epoch==saved.optInt("epoch",-1);
        String summary=actual?"独立改稿已保存："+saved.getString("path")+"。可在这次原交办查看文件差异；没有写回、构建或运行。":"本轮没有保存可核对的独立改稿；文字答复不等于交付。没有写回、构建或运行。";
        JSONObject evidence=new JSONObject(original.evidence.toString()).put("modelSummary",summary).put("draftOnly",true).put("draftSaved",actual);
        if(actual)evidence.put("draftId",saved.getString("draftId"));return new dev.pocketengine.core.RunOutcome(actual?"completed":"failed",summary,evidence);
    }
    public static JSONObject verified(JSONObject record)throws Exception{
        if(!FixedProjectDraftSource.eligible(VersionedWorkspace.path(text(record,"path")))||!text(record,"sourceDigest").matches("[a-f0-9]{64}")||
            record.getInt("beforeBytes")!=bytes(record.getString("before"),65536).length||record.getInt("afterBytes")!=bytes(record.getString("after"),24576).length||
            text(record,"beforeSha256").equals(record.opt("afterSha256"))||!FORMAT.equals(record.opt("format"))||!text(record,"beforeSha256").equals(ProjectSources.sha256(bytes(record.getString("before"),65536)))||
            !text(record,"afterSha256").equals(ProjectSources.sha256(bytes(record.getString("after"),24576))))throw new SecurityException("原草稿字节不可核对");
        if(record.has(OwnerRevisionSource.FIELD)){JSONObject ref=OwnerRevisionSource.pin(record.getJSONObject(OwnerRevisionSource.FIELD));
            if(ref.getString("taskId").equals(record.getString("taskId"))||!ref.getString("sha256").equals(record.getString("beforeSha256"))||!OwnerRevisionSource.digest(record.getString("path"),ref.getString("sha256"),record.getInt("beforeBytes")).equals(record.getString("sourceDigest")))throw new SecurityException("新差异的起始文字不是所选旧稿");}
        JSONObject identity=new JSONObject(record.toString());identity.remove("before");identity.remove("after");identity.remove("draftId");
        if(!text(record,"draftId").equals(ProjectSources.digest(identity)))throw new SecurityException("草稿不属于原任务或固定版本");return record;
    }
}
