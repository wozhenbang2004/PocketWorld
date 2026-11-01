package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Read-only leaf for original team assignments' actual WorkspaceArtifacts. No AttachmentBook lookup, write or runner. */
public final class TeamWorkspaceOutcomeHost {
    public interface NativeSource {
        JSONObject resolveTeamArtifact(String teamRunId,String assignmentId,String memberId,String taskId,String frozenRunId,String artifactId,RunControl control)throws Exception;
        void checkTeamArtifact(JSONObject exactRef,RunControl control)throws Exception;
    }
    public interface FrozenArtifactRead {JSONObject read(String artifactId)throws Exception;}
    /** Existing native whole-team review; its assignments come from the original TaskStore provider. */
    public interface ReviewRead {JSONObject read(String teamRunId,RunControl control)throws Exception;}
    private static final String[] TEXT_FIELDS={"teamRunId","assignmentId","memberId","taskId","runId","artifactId","draftProjectId","targetProjectId","path","baseHash","draftRevisionId","sha256"};
    private final NativeSource source;private final FrozenArtifactRead artifacts;private final ReviewRead review;
    public TeamWorkspaceOutcomeHost(NativeSource source,FrozenArtifactRead artifacts){this(source,artifacts,null);}
    public TeamWorkspaceOutcomeHost(NativeSource source,FrozenArtifactRead artifacts,ReviewRead review){this.source=java.util.Objects.requireNonNull(source);this.artifacts=java.util.Objects.requireNonNull(artifacts);this.review=review;}
    /** Locator only. NativeSource must resolve the original SQL receipt again for every read/export/return. */
    public static final class Selection {
        private final JSONObject fields;
        public Selection(JSONObject locator)throws Exception {
            if(!"teams".equals(locator.optString("provider"))||!"team_assignment".equals(locator.optString("source")))throw new SecurityException("团队成果必须保留原provider与assignment来源");
            fields=reference(locator);fields.put("provider","teams").put("source","team_assignment");
        }
        public static Selection fromManifest(JSONObject ref)throws Exception{return new Selection(new JSONObject(ref.toString()).put("provider","teams").put("source","team_assignment"));}
        public JSONObject toJson()throws Exception{return new JSONObject(fields.toString());}
        public String sha256(){return fields.optString("sha256");}
        private String value(String key)throws Exception{return fields.getString(key);}
    }
    public JSONObject preview(Selection selected,int offset,int length,RunControl control)throws Exception {
        if(offset<0||offset>65536||length<1||length>12000)throw new IllegalArgumentException("成果读取范围无效，每次最多12000字符");
        JSONObject ref=resolve(selected,control),artifact=read(ref,control);String content=artifact.getString("content");
        if(offset>content.length()||offset>0&&offset<content.length()&&Character.isLowSurrogate(content.charAt(offset))&&Character.isHighSurrogate(content.charAt(offset-1)))throw new IllegalArgumentException("成果分页位置越界或位于同一字符中间");
        int end=Math.min(content.length(),offset+length);if(end<content.length()&&end>offset&&Character.isHighSurrogate(content.charAt(end-1)))end--;
        if(end==offset&&end<content.length())throw new IllegalArgumentException("本次读取长度会截断同一字符，请扩大读取范围");
        JSONObject metadata=new JSONObject();for(String key:new String[]{"id","kind","projectId","path","sourceVersion","sourceRevisionId","mimeType","createdAt","characters","sha256","taskId","runId","source"})if(artifact.has(key))metadata.put(key,artifact.get(key));check(ref,control);
        return new JSONObject().put("provider","teams").put("source","team_assignment").put("selection",Selection.fromManifest(ref).toJson()).put("reference",reference(ref))
            .put("artifact",metadata).put("content",content.substring(offset,end)).put("offset",offset).put("nextOffset",end<content.length()?end:JSONObject.NULL)
            .put("totalCharacters",content.length()).put("untrustedContent",true).put("readOnly",true).put("businessVerified",false);
    }
    /** Native export adapters receive exact bytes, then recheck this source before/after the existing SAF copy. This is no write grant. */
    public byte[] verifiedBytes(Selection selected,RunControl control)throws Exception {
        JSONObject ref=resolve(selected,control),artifact=read(ref,control);byte[] bytes=artifact.getString("content").getBytes(StandardCharsets.UTF_8);
        try{check(ref,control);return bytes;}catch(Exception revoked){java.util.Arrays.fill(bytes,(byte)0);throw revoked;}
    }
    public boolean supportsDifference(){return review!=null;}
    /** Find a later frozen revision for this exact original assignment/path using native review refs, never a client-provided replacement. */
    public JSONObject newerArtifact(Selection selected,RunControl control)throws Exception {
        if(review==null)throw new IllegalStateException("原分工成果查询尚未挂载，请回原团队核对");
        JSONObject original=resolve(selected,control);read(original,control);JSONObject actualReview=review.read(original.getString("teamRunId"),control),plan=actualReview.getJSONObject("plan");
        if(!"pocketworld.team.integration.v1".equals(plan.optString("format"))||!original.getString("teamRunId").equals(plan.optString("teamRunId"))||!original.getString("targetProjectId").equals(plan.optString("targetProjectId")))throw new SecurityException("新成果列表不属于原团队项目");
        org.json.JSONArray files=plan.getJSONArray("files");if(files.length()>32)throw new SecurityException("原团队成果范围超出已接入额度");
        JSONObject newest=null;int priorRevision=original.optInt("assignmentRevision",1),latest=priorRevision;
        for(int i=0;i<files.length();i++){
            JSONObject candidate=files.getJSONObject(i);boolean same=true;for(String key:new String[]{"teamRunId","assignmentId","memberId","taskId","targetProjectId","path"})if(!original.getString(key).equals(candidate.optString(key)))same=false;if(!same||candidate.optInt("assignmentRevision",1)<=priorRevision)continue;
            JSONObject verified=resolve(Selection.fromManifest(candidate),control);read(verified,control);int revision=verified.optInt("assignmentRevision",1);
            if(revision>latest){newest=verified;latest=revision;}
            else if(revision==latest&&newest!=null&&!newest.getString("artifactId").equals(verified.getString("artifactId")))throw new SecurityException("原分工同一修订有多个冲突成果，请回原分工核对");
        }
        check(original,control);if(newest!=null)check(newest,control);
        return new JSONObject().put("provider","teams").put("source","team_assignment").put("from",selected.toJson()).put("newerArtifact",newest==null?JSONObject.NULL:Selection.fromManifest(newest).toJson())
            .put("message",newest==null?"原分工尚无再次冻结的新成果；当前固定版本保留":"原分工已有新的真实冻结成果；旧版本仍然保留").put("readOnly",true).put("businessVerified",false);
    }
    /** Show only this artifact's bounded current-project difference; never expose the review's integration plan or permission fields. */
    public JSONObject difference(Selection selected,int offset,int length,RunControl control)throws Exception {
        if(review==null)throw new IllegalStateException("原团队差异读取尚未挂载，请回原分工核对");
        if(offset<0||offset>65536||length<1||length>12000)throw new IllegalArgumentException("成果差异读取范围无效");
        JSONObject ref=resolve(selected,control);read(ref,control);JSONObject nativeReview=review.read(ref.getString("teamRunId"),control),found=null;
        org.json.JSONArray rows=nativeReview.getJSONArray("diffs");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!ref.getString("artifactId").equals(row.optString("artifactId")))continue;if(found!=null)throw new SecurityException("原成果有多个冲突差异");found=row;}
        if(found==null)throw new SecurityException("原成果未出现在原团队实际差异中");
        for(String key:new String[]{"artifactId","assignmentId","memberId","path"})if(!ref.getString(key).equals(found.getString(key)))throw new SecurityException("差异不属于原成员的固定成果");
        int current=found.getInt("currentVersion");String currentHash=found.getString("currentHash");boolean matches=current==ref.getInt("baseVersion")&&currentHash.equals(ref.getString("baseHash"));
        if(current<0||!currentHash.matches("absent|[a-f0-9]{64}")||!(found.opt("matchesOwnerBase") instanceof Boolean)||found.getBoolean("matchesOwnerBase")!=matches)throw new SecurityException("原项目比较版本或基线说明不可核对");
        String diff=found.getString("diff");if(diff.length()>65536||offset>diff.length()||offset>0&&offset<diff.length()&&Character.isLowSurrogate(diff.charAt(offset))&&Character.isHighSurrogate(diff.charAt(offset-1)))throw new IllegalArgumentException("差异范围越界或位于同一字符中间");int end=Math.min(diff.length(),offset+length);if(end<diff.length()&&end>offset&&Character.isHighSurrogate(diff.charAt(end-1)))end--;if(end==offset&&end<diff.length())throw new IllegalArgumentException("请扩大差异读取范围，避免截断同一字符");
        check(ref,control);return new JSONObject().put("provider","teams").put("source","team_assignment").put("artifactId",ref.getString("artifactId")).put("path",ref.getString("path"))
            .put("diff",diff.substring(offset,end)).put("offset",offset).put("nextOffset",end<diff.length()?end:JSONObject.NULL).put("totalCharacters",diff.length()).put("truncated",found.getBoolean("truncated"))
            .put("currentVersion",current).put("currentHash",currentHash).put("matchesOwnerBase",matches).put("readOnly",true).put("businessVerified",false);
    }
    public void checkRead(Selection selected,RunControl control)throws Exception {JSONObject ref=resolve(selected,control);check(ref,control);}
    /** Return to the original assignment's owner UI. This is no approval and never resumes the frozen historical run. */
    public JSONObject assignmentLocator(Selection selected,RunControl control)throws Exception {
        JSONObject ref=resolve(selected,control);check(ref,control);JSONObject value=new JSONObject();for(String key:new String[]{"teamRunId","memberId","assignmentId","taskId"})value.put(key,ref.getString(key));return value;
    }
    private JSONObject resolve(Selection selected,RunControl control)throws Exception {
        java.util.Objects.requireNonNull(selected);java.util.Objects.requireNonNull(control).checkpoint();
        JSONObject actual=source.resolveTeamArtifact(selected.value("teamRunId"),selected.value("assignmentId"),selected.value("memberId"),selected.value("taskId"),selected.value("runId"),selected.value("artifactId"),control),normalized=reference(actual);
        for(String key:TEXT_FIELDS)if(!selected.fields.getString(key).equals(normalized.getString(key)))throw new SecurityException("成果来源或固定版本已变化，请回原assignment核对");
        for(String key:new String[]{"baseVersion","draftVersion"})if(selected.fields.getInt(key)!=normalized.getInt(key))throw new SecurityException("成果的原基线或草稿版本不一致");
        if(selected.fields.has("assignmentRevision")!=normalized.has("assignmentRevision")||normalized.has("assignmentRevision")&&selected.fields.getInt("assignmentRevision")!=normalized.getInt("assignmentRevision"))throw new SecurityException("原分工修订版本已变化，请回原分工核对");
        check(actual,control);return new JSONObject(actual.toString());
    }
    private void check(JSONObject ref,RunControl control)throws Exception {control.checkpoint();source.checkTeamArtifact(new JSONObject(ref.toString()),control);control.checkpoint();}
    private JSONObject read(JSONObject ref,RunControl control)throws Exception {
        check(ref,control);JSONObject artifact=artifacts.read(ref.getString("artifactId"));String content=artifact.getString("content");
        if(content.length()>65536||!"text_file".equals(artifact.optString("kind"))||!"task_workspace".equals(artifact.optString("source")))throw new SecurityException("原成果不是已冻结的任务文本文件");
        for(String[] field:new String[][]{{"artifactId","id"},{"draftProjectId","projectId"},{"path","path"},{"draftRevisionId","sourceRevisionId"},{"sha256","sha256"},{"taskId","taskId"},{"runId","runId"}})if(!ref.getString(field[0]).equals(artifact.optString(field[1])))throw new SecurityException("实际WorkspaceArtifact与原冻结回执不一致");
        if(ref.getInt("draftVersion")!=artifact.getInt("sourceVersion")||content.length()!=artifact.getInt("characters")||!ref.getString("sha256").equals(hash(content))||!mime(ref.getString("path")).equals(artifact.optString("mimeType")))throw new SecurityException("原成果版本、实际内容、类型或SHA-256校验失败");
        check(ref,control);return artifact;
    }
    private static JSONObject reference(JSONObject input)throws Exception {
        JSONObject value=new JSONObject();for(String key:TEXT_FIELDS)value.put(key,input.getString(key));for(String key:new String[]{"baseVersion","draftVersion"}){Object number=input.get(key);if(!(number instanceof Integer||number instanceof Long)||((Number)number).longValue()<0||((Number)number).longValue()>Integer.MAX_VALUE)throw new SecurityException("原冻结成果版本无效");value.put(key,((Number)number).intValue());}
        if(input.has("assignmentRevision")){Object revision=input.get("assignmentRevision");if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1||((Number)revision).longValue()>Integer.MAX_VALUE)throw new SecurityException("原分工修订版本无效");value.put("assignmentRevision",((Number)revision).intValue());}
        for(String key:new String[]{"teamRunId","assignmentId","taskId","runId","artifactId","draftProjectId","targetProjectId","draftRevisionId"})if(!value.getString(key).matches("[a-f0-9-]{36}"))throw new SecurityException("原成果身份无效");
        if(!value.getString("assignmentId").equals(value.getString("taskId")))throw new SecurityException("原assignment与稳定成员任务身份不一致");
        if(!value.getString("memberId").matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||!value.getString("sha256").matches("[a-f0-9]{64}")||!value.getString("baseHash").matches("absent|[a-f0-9]{64}")||value.getInt("draftVersion")<1)throw new SecurityException("原成员、版本或文件摘要无效");
        value.put("path",VersionedWorkspace.path(value.getString("path")));return value;
    }
    private static String hash(String content)throws Exception {StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)))value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    private static String mime(String path){String lower=path.toLowerCase(java.util.Locale.ROOT);if(lower.endsWith(".json"))return "application/json";if(lower.endsWith(".md"))return "text/markdown";if(lower.endsWith(".html")||lower.endsWith(".htm"))return "text/html";if(lower.endsWith(".csv"))return "text/csv";return "text/plain";}
}
