package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.store.TaskStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** A private, held comparison of two sets of immutable artifacts. A changing project head
 * is a third source, never substituted for the previous written bytes. Nothing here dispatches work. */
final class TeamOwnerRevisionFiles {
    private static final int WRITTEN_TEXT_BYTES=65536;
    private final JSONObject index;
    private final TeamWorkFiles.Workspace workspace;
    private final Map<String,String> text=new HashMap<>();
    private final Map<String,JSONObject> entries=new HashMap<>();
    private final Map<String,JSONObject> writtenText=new HashMap<>();
    private TeamOwnerRevisionFiles(JSONObject index,TeamWorkFiles.Workspace workspace){this.index=index;this.workspace=workspace;}

    private static boolean same(Object a,Object b)throws Exception{return TeamWorkFiles.canonical(a).equals(TeamWorkFiles.canonical(b));}
    private static JSONObject ref(JSONObject file)throws Exception{
        JSONObject value=new JSONObject(file.toString());value.remove("expectedVersion");value.remove("expectedHash");return value;
    }
    private static Map<String,JSONObject> paths(JSONArray files)throws Exception{
        Map<String,JSONObject> result=new LinkedHashMap<>();
        for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if(result.put(f.getString("path"),f)!=null)throw new SecurityException("同一文件有多个原件，未比较");}
        return result;
    }
    private static boolean grant(JSONObject g,String run,String project,String hash,String review)throws Exception{
        if(g==null||!hash.equals(g.opt("planHash"))||!review.equals(g.opt("reviewId"))||!"owner".equals(g.opt("actorId"))||!"private".equals(g.opt("spaceId")))return false;
        JSONObject p=g.getJSONObject("plan");return run.equals(p.opt("teamRunId"))&&project.equals(p.opt("targetProjectId"))&&hash.equals(TeamWorkFiles.hash(TeamWorkFiles.canonical(p)));
    }
    private static JSONObject written(JSONObject f,JSONObject old,JSONObject context)throws Exception{
        JSONObject found=null;JSONArray receipts=context.getJSONArray("receipts"),retained=old.getJSONObject("plan").optJSONArray("retained");
        for(int i=0;i<receipts.length();i++){JSONObject row=receipts.getJSONObject(i),a=row.optJSONObject("teamIntegrated");
            if(!Boolean.TRUE.equals(row.opt("ok"))||a==null||!TeamWorkReview.sameArtifact(f,a,context.getString("taskId"),context.getString("targetProjectId")))continue;
            boolean included=old.getString("planHash").equals(a.opt("planHash"))&&old.getString("reviewId").equals(a.opt("reviewId"));
            for(int j=0;!included&&retained!=null&&j<retained.length();j++)included=same(a,retained.getJSONObject(j));
            if(!included)continue;
            if(found!=null&&!same(found,a))return null;found=a;
        }
        return found;
    }
    private static boolean bound(JSONObject f,JSONObject item,String team,String project)throws Exception {
        return f!=null&&team.equals(f.opt("teamRunId"))&&project.equals(f.opt("targetProjectId"))&&
                item.getString("assignmentId").equals(f.opt("assignmentId"))&&item.getString("assignmentId").equals(f.opt("taskId"))&&item.getString("memberId").equals(f.opt("memberId"));
    }
    static TeamOwnerRevisionFiles capture(TaskStore tasks,TeamWorkFiles.Workspace workspace,JSONObject run,JSONObject compared,RunControl reader)throws Exception{
        JSONObject saved=run.optJSONObject("workPlan");
        if(!TeamParentRevisionOffer.localFiles(run)||saved==null||!saved.has("lastRevisionRequestId")||!compared.optBoolean("recovery"))return null;
        String team=run.getString("id");JSONObject sql=tasks.teamRevisionFilesForOwner(team,reader);
        if(sql==null)return null;
        JSONObject work=sql.getJSONObject("workPlan");TeamParentRevisedFiles.Round round=TeamParentRevisedFiles.ownerRound(sql.getJSONObject("parent"),run,work);
        if(round==null)return null;
        String project=work.getString("targetProjectId");JSONObject context=sql.getJSONObject("integration"),plan=compared.getJSONObject("plan"),basis=plan.getJSONObject("recovery");
        if(!team.equals(context.opt("teamRunId"))||!project.equals(context.opt("targetProjectId"))||!project.equals(plan.opt("targetProjectId"))||
                !context.getString("taskId").equals(basis.opt("taskId"))||!context.getString("reviewId").equals(basis.opt("reviewId"))||!context.getString("planHash").equals(basis.opt("planHash"))||
                !TeamWorkFiles.hash(TeamWorkFiles.canonical(context.getJSONArray("receipts"))).equals(basis.opt("receiptsHash")))throw new SecurityException("原整合回执在打开时变化，请重读原成果");
        JSONObject active=sql.getJSONObject("approvedReview"),old=null;
        if(grant(active,team,project,round.previousPlan(),round.previousReview()))old=active;
        JSONArray history=sql.optJSONArray("previousReviews");
        for(int i=0;history!=null&&i<history.length();i++){JSONObject g=history.getJSONObject(i);if(grant(g,team,project,round.previousPlan(),round.previousReview())){
            if(old!=null)throw new SecurityException("上版写回许可不唯一，未比较");old=g;
        }}
        if(old==null)throw new SecurityException("没有原修订所指的上版写回许可，未展示其他版本");
        Map<String,JSONObject> prior=paths(old.getJSONObject("plan").getJSONArray("files")),current=paths(plan.getJSONArray("files")),heads=paths(compared.getJSONArray("diffs"));
        Map<String,JSONObject> selected=new LinkedHashMap<>(),settled=new HashMap<>(),archived=new HashMap<>();JSONArray items=work.getJSONArray("items");
        boolean allFrozen=compared.getJSONArray("incompleteAssignments").length()==0&&!current.isEmpty();
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);
            for(String path:paths(item.getJSONArray("files")).keySet())if(selected.put(path,item)!=null||selected.size()>32)throw new SecurityException("原修订文件范围重复或超限");
            Map<String,JSONObject> exact=paths(item.getJSONArray("artifacts"));
            allFrozen&="completed".equals(item.optString("status"))&&!exact.isEmpty();
            for(var e:exact.entrySet())if(!bound(e.getValue(),item,team,project)||settled.put(e.getKey(),e.getValue())!=null)throw new SecurityException("本版冻结成果与原工作项不符");
            JSONArray priorItems=item.optJSONArray("revisionHistory");
            for(int j=0;priorItems!=null&&j<priorItems.length();j++){JSONObject h=priorItems.getJSONObject(j);if(!round.request().equals(h.opt("revisionRequestId")))continue;
                for(var e:paths(h.getJSONArray("artifacts")).entrySet())if(!bound(e.getValue(),item,team,project)||archived.put(e.getKey(),e.getValue())!=null)throw new SecurityException("上版冻结成果与原工作项不符");
            }
        }
        if(!current.keySet().equals(heads.keySet())||!current.keySet().equals(settled.keySet())||!selected.keySet().containsAll(prior.keySet())||!selected.keySet().containsAll(current.keySet()))throw new SecurityException("原目录正在变化，未替换其他工作项");
        for(var e:archived.entrySet()){JSONObject f=prior.get(e.getKey());if(f==null||!same(e.getValue(),ref(f)))throw new SecurityException("修订前保存的原件不属于上版许可");}
        for(var e:current.entrySet())if(!same(ref(e.getValue()),settled.get(e.getKey())))throw new SecurityException("本次回执与所选冻结原件不符");
        String compareHash=compared.getString("planHash"),newHash=compareHash;
        if(old!=active){ // A separately registered plan can precede the newly captured recovery-parent hash.
            if(!round.previousPlan().equals(active.opt("previousPlanHash"))||!round.previousReview().equals(active.opt("previousReviewId"))||
                    !grant(active,team,project,context.getString("planHash"),context.getString("reviewId")))throw new SecurityException("后续许可已换代，未将另一轮作为这次修订");
            Map<String,JSONObject> registered=paths(active.getJSONObject("plan").getJSONArray("files"));
            if(!registered.keySet().equals(current.keySet()))throw new SecurityException("登记后的原件目录已变化");
            for(var e:current.entrySet())if(!same(ref(e.getValue()),ref(registered.get(e.getKey()))))throw new SecurityException("登记后的冻结原件已变化");
            newHash=active.getString("planHash");
        }
        JSONArray rows=new JSONArray();JSONObject index=new JSONObject().put("teamRunId",team).put("projectId",project).put("planHash",compareHash).put("previousPlanHash",round.previousPlan())
                .put("revisedPlanHash",newHash).put("revisionRequestId",round.request()).put("files",rows).put("readOnly",true)
                .put("previousReview",tasks.teamVersionReviewForOwner(team,round.previousPlan(),reader))
                .put("revisedReview",allFrozen?tasks.teamVersionReviewForOwner(team,newHash,reader):new JSONObject().put("planHash",newHash).put("status","not_all_frozen"));
        TeamOwnerRevisionFiles result=new TeamOwnerRevisionFiles(index,workspace);
        for(var s:selected.entrySet()){
            reader.check();String path=s.getKey();JSONObject item=s.getValue(),a=prior.get(path),b=current.get(path);
            if(a!=null&&!bound(a,item,team,project)||b!=null&&!bound(b,item,team,project))throw new SecurityException("文件不属于同一原分工，未比较");
            JSONObject receipt=a==null?null:written(a,old,context);
            JSONObject row=new JSONObject().put("path",path).put("workItemId",item.getString("workItemId")).put("assignmentId",item.getString("assignmentId"))
                    .put("memberId",item.getString("memberId")).put("workStatus",item.getString("status")).put("headState","not_checked");
            if(receipt!=null){
                row.put("priorArtifactId",a.getString("artifactId")).put("writtenVersion",receipt.getInt("version")).put("writtenHash",receipt.getString("sha256"));
                long bytes=a.optLong("bytes",-1);
                if("text_file".equals(a.opt("artifactKind"))&&bytes>=0&&bytes<=WRITTEN_TEXT_BYTES&&a.getString("sha256").equals(receipt.opt("sha256"))){
                    result.writtenText.put(path,new JSONObject(a.toString()));
                    row.put("canReadWrittenOriginal",true);
                }
                JSONObject d=heads.get(path);
                if(d!=null){boolean matches=d.getBoolean("currentExists")!=TeamWorkReview.isDeletion(a)&&d.getInt("currentVersion")==receipt.getInt("version")&&d.getString("currentRevisionId").equals(receipt.getString("revisionId"))&&d.getString("currentHash").equals(receipt.getString("sha256"));row.put("headState",matches?"matches_written":"changed");}
                else try{row.put("headState",TeamWorkReview.sameHead(TeamWorkFiles.snapshot(workspace,project,path),receipt)?"matches_written":"changed");}
                    catch(Exception missingHead){reader.check();/* Absent evidence is not an unchanged head. */}
            }
            if(b!=null)row.put("newArtifactId",b.getString("artifactId")).put("frozenVersion",b.getInt("draftVersion")).put("frozenHash",b.getString("sha256"));
            String state;
            if(a!=null&&receipt==null)state="write_not_verified";
            else if(b==null)state=receipt!=null?"no_new_freeze":"no_either_receipt";
            else if(receipt==null)state="no_prior_write";
            else if(a.getString("artifactId").equals(b.getString("artifactId"))&&same(ref(a),ref(b)))state="retained_artifact";
            else try{
                JSONObject before=TeamWorkReview.artifact(workspace,a),after=TeamWorkReview.artifact(workspace,b);
                if(TeamWorkReview.isDeletion(a)||TeamWorkReview.isDeletion(b))state="deletion_record";
                else if(before.has("content")&&after.has("content")){
                    JSONObject diff=VersionedWorkspace.compare(path,before.getString("content"),after.getString("content"),"上版实际写回","本次冻结原件");String body=diff.getString("diff");
                    if(diff.getBoolean("truncated")&&!body.isEmpty()&&Character.isHighSurrogate(body.charAt(body.length()-1)))body=body.substring(0,body.length()-1);
                    if(body.isEmpty())state="same_bytes";else{state="text_changed";row.put("retainedChars",body.length()).put("truncated",diff.getBoolean("truncated"));result.text.put(path,body);}
                }else state=a.getString("sha256").equals(b.getString("sha256"))?"same_bytes":"non_text_changed";
            }catch(Exception unavailable){reader.check();state="original_unavailable";}
            rows.put(row.put("state",state));result.entries.put(path,row);
        }
        reader.check();return result;
    }
    JSONObject index()throws Exception{return new JSONObject(index.toString());}
    /** Read the already matched prior write artifact, never the live project head
     * or an ID supplied as a new lookup. Each page checks the same immutable bytes. */
    JSONObject writtenPage(String path,String artifactId,int offset,RunControl reader)throws Exception{
        reader.check();JSONObject ref=writtenText.get(path),row=entries.get(path);
        if(ref==null||row==null||!artifactId.equals(row.opt("priorArtifactId"))||!artifactId.equals(ref.opt("artifactId")))
            throw new SecurityException("所选文件没有这次修订所指的上版原文");
        String body;
        try{
            JSONObject actual=TeamWorkReview.artifact(workspace,ref);body=actual.getString("content");
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(!"text_file".equals(actual.opt("kind"))||bytes.length!=ref.getLong("bytes")||bytes.length>WRITTEN_TEXT_BYTES||
                    !TeamWorkFiles.hash(bytes).equals(row.opt("writtenHash")))throw new SecurityException("上版原文字节不匹配");
        }catch(Exception unavailable){
            reader.check();throw new SecurityException("上版原文暂不可读取，没有用当前项目或其他稿件替代");
        }
        int end=TeamOwnerFileReading.pageEnd(body,offset);
        reader.check();return new JSONObject().put("basis","written_original").put("readOnly",true).put("executionAuthorized",false)
                .put("teamRunId",index.getString("teamRunId")).put("planHash",index.getString("planHash"))
                .put("revisionRequestId",index.getString("revisionRequestId")).put("previousPlanHash",index.getString("previousPlanHash"))
                .put("path",path).put("assignmentId",row.getString("assignmentId")).put("priorArtifactId",artifactId)
                .put("writtenVersion",row.getInt("writtenVersion")).put("writtenHash",row.getString("writtenHash"))
                .put("offset",offset).put("endOffset",end).put("retainedChars",body.length()).put("text",body.substring(offset,end));
    }
    JSONObject page(String path,int offset)throws Exception{
        String body=text.get(path);JSONObject row=entries.get(path);if(row==null||body==null)throw new SecurityException("该路径没有可读的上版与本次原件差异");
        int end=TeamOwnerFileReading.pageEnd(body,offset);
        return new JSONObject().put("basis","written_to_frozen").put("readOnly",true).put("teamRunId",index.getString("teamRunId")).put("planHash",index.getString("planHash"))
                .put("revisionRequestId",index.getString("revisionRequestId")).put("previousPlanHash",index.getString("previousPlanHash")).put("revisedPlanHash",index.getString("revisedPlanHash"))
                .put("assignmentId",row.getString("assignmentId")).put("priorArtifactId",row.getString("priorArtifactId")).put("newArtifactId",row.getString("newArtifactId"))
                .put("path",path).put("offset",offset).put("endOffset",end).put("retainedChars",body.length()).put("diff",body.substring(offset,end));
    }
}
