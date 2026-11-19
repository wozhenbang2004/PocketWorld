package dev.pocketengine.features.local.project;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native private draft -> a different project and a different checkout. No grant, model input or command. */
public final class OwnerDraftWorkCopy {
    private OwnerDraftWorkCopy(){}
    @FunctionalInterface public interface Current {void check()throws Exception;}
    public static final String FIELD="ownerDraftWorkCopy";
    private static final String KIND="pocketworld.private-draft-work-copy.v1";
    private static String uuid(String s){return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)).toString();}
    private static String hash(JSONObject r,String k)throws Exception{String s=r.getString(k);if(!s.matches("[a-f0-9]{64}"))throw new SecurityException("稿本的版本证据不完整");return s;}
    public static final class Seed {
        public final String project,checkout,path,originalDigest,originalFileSha,action,projectId,checkoutId,identity;
        private final String record;private final byte[] replacement;
        private Seed(JSONObject fact,byte[] bytes)throws Exception{
            record=fact.toString();identity=ProjectSources.digest(fact);replacement=bytes.clone();project=fact.getString("sourceProjectId");checkout=fact.getString("sourceCheckoutId");
            path=fact.getString("path");originalDigest=fact.getString("originalSourceDigest");originalFileSha=fact.getString("originalFileSha256");
            action=uuid("private-draft-working-copy-v1:"+identity);projectId=uuid("project-package-import:"+action);checkoutId=uuid("project-package-checkout:"+action);
        }
        public JSONObject view()throws Exception{return new JSONObject(record).put("workIdentity",identity).put("projectId",projectId).put("checkoutId",checkoutId).put("executionAuthorized",false);}
        byte[] bytes(){return replacement.clone();}
    }
    /** Called with verified private TaskStore rows. A follow-up's one-file digest is never treated as the checkout digest. */
    public static Seed fromRecords(JSONObject root,JSONObject chosen,JSONObject oldest)throws Exception{
        if(root.opt("available")!=Boolean.TRUE||oldest.opt("available")!=Boolean.TRUE||oldest.has(OwnerRevisionSource.FIELD)
            ||!root.getString("projectId").equals(oldest.opt("projectId"))||!root.getString("checkoutId").equals(oldest.opt("checkoutId"))
            ||!root.getString("taskId").equals(chosen.opt("taskId"))||!root.getString("draftId").equals(chosen.opt("draftId"))
            ||!root.getString("path").equals(chosen.opt("path"))||!root.getString("path").equals(oldest.opt("path"))||!FixedProjectDraftSource.eligible(root.getString("path")))throw new SecurityException("这份稿与最初工程的文件不能核对，未借用别的工程");
        byte[] bytes=ProjectEditDraft.bytes(chosen.getString("text"),24576);int n=chosen.getInt("ordinal");
        if(n<0||n>8||bytes.length!=chosen.getInt("bytes")||!ProjectSources.sha256(bytes).equals(hash(chosen,"sha256"))||
            n==0&&(!chosen.getString("revisionId").equals(root.getString("draftId"))||!root.getString("afterSha256").equals(chosen.getString("sha256"))))throw new SecurityException("正在看的稿本已经变化");
        JSONObject fact=new JSONObject().put("format",KIND).put("taskId",root.getString("taskId")).put("draftId",hash(root,"draftId")).put("revisionId",hash(chosen,"revisionId")).put("sha256",hash(chosen,"sha256"))
            .put("runId",root.getString("runId")).put("epoch",root.getInt("epoch")).put("ordinal",n).put("bytes",bytes.length).put("path",root.getString("path"))
            .put("baseTaskId",oldest.getString("taskId")).put("baseDraftId",hash(oldest,"draftId")).put("originalSourceDigest",hash(oldest,"sourceDigest")).put("originalFileSha256",hash(oldest,"beforeSha256"))
            .put("sourceProjectId",oldest.getString("projectId")).put("sourceCheckoutId",oldest.getString("checkoutId"));
        return new Seed(fact,bytes);
    }
    public static final class Review {
        public final Seed seed;public final ProjectPackageArchive.Frozen frozen;public final String name,outputDigest,originalManifest,planDigest;private final String plan,meta;
        private Review(Seed s,ProjectPackageArchive.Frozen base,ProjectPackageArchive.Frozen draft,JSONObject p,String label)throws Exception{
            seed=s;frozen=draft;name=label;plan=p.toString();planDigest=p.getString("planDigest");originalManifest=base.identity;JSONObject m=draft.manifest();outputDigest=m.getJSONObject("source").getString("sourceDigest");
            meta=s.view().put("name",label).put("initialCopyDigest",outputDigest).put("originalPackageManifest",base.identity).put("packageSha256",p.getString("packageSha256"))
                .put("manifestIdentity",draft.identity).put("fileCount",m.getJSONArray("files").length()).put("sourceBytes",m.getLong("bytes")).put("omitted",base.manifest().getJSONArray("omitted").length()).toString();
        }
        public JSONObject plan()throws Exception{return new JSONObject(plan);}
        public JSONObject view()throws Exception{return new JSONObject(meta);}
    }
    public static void requireOriginal(Seed s,JSONObject actual)throws Exception{
        if(!s.project.equals(actual.opt("projectId"))||!s.checkout.equals(actual.opt("checkoutId"))||!s.originalDigest.equals(actual.opt("sourceDigest")))
            throw new SecurityException("最初工程的源码版本已变化。这份稿不能套用到现在的工程；没有创建副本或改动原件。");
    }
    /** Capture uses the package policy and compares every copied file with the actual whole checkout baseline. */
    public static Review review(Seed s,JSONObject original,ProjectPackageArchive.Frozen base,String originalName)throws Exception{
        requireOriginal(s,original);JSONObject m=base.manifest(),origin=m.getJSONObject("source");requireOriginal(s,origin);
        JSONArray a=original.getJSONArray("files");Map<String,String> expected=new HashMap<>();for(int i=0;i<a.length();i++){JSONObject f=a.getJSONObject(i);expected.put(f.getString("path"),f.getString("sha256"));}
        JSONArray fs=m.getJSONArray("files");for(int i=0;i<fs.length();i++){JSONObject f=fs.getJSONObject(i);if(!f.getString("sha256").equals(expected.get(f.getString("path"))))throw new SecurityException("工程在准备过程中变化，没有拼接两个版本");}
        String title=originalName==null||originalName.isBlank()?"项目":originalName.strip();int keep=Math.min(title.codePointCount(0,title.length()),28);
        String label=title.substring(0,title.offsetByCodePoints(0,keep))+" · 改稿 "+s.identity.substring(0,6);
        var derived=ProjectPackageArchive.forPrivateDraft(base,s,label);String archive=ProjectPackageDelivery.archiveIdentity(derived).getString("archiveSha256");
        JSONObject plan=new JSONObject().put("format","pocketworld.project-package-import-plan.v1").put("selectionId",s.action).put("clientImportId",s.action)
            .put("packageSha256",archive).put("manifestIdentity",derived.identity).put("projectName",label).put("mode","new_project");
        plan.put("planDigest",ProjectSources.digest(plan));return new Review(s,base,derived,plan,label);
    }
}
