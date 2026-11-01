package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.remote.RemoteAssignmentWorkspaces;
import dev.pocketengine.store.TaskStore;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact remote bytes become ordinary original team artifacts, never direct original-project writes. */
final class TeamRemoteImports {
    @FunctionalInterface interface Live {void check()throws Exception;}
    private final RemoteAssignmentWorkspaces remote;private final RemoteAssignmentWorkspaces.NativeHost host;
    private final TeamWorkFiles.Workspace workspace;private final TeamWorkFiles local;private final JSONObject binding;private final RunControl original;private final Live live;
    TeamRemoteImports(RemoteAssignmentWorkspaces remote,RemoteAssignmentWorkspaces.NativeHost host,TeamWorkFiles.Workspace workspace,TeamWorkFiles local,JSONObject binding,RunControl original,Live live)throws Exception {
        this.remote=remote;this.host=host;this.workspace=workspace;this.local=local;this.binding=new JSONObject(binding.toString());this.original=original;this.live=live;
    }
    JSONObject execute(RemoteAssignmentWorkspaces.Contribution contribution,String preparedOperation)throws Exception {
        check();JSONObject draft=local.originalDraft(),manifest=contribution.view();String project=draft.getString("draftProjectId");
        if(project.equals(binding.getString("targetProjectId")))throw new SecurityException("远端贡献不能写原项目");
        JSONObject proof=manifest.getJSONObject("assignmentContribution"),nativeAssignment=proof.getJSONObject("nativeAssignment");
        for(String key:new String[]{"teamRunId","assignmentId","memberId"})if(!binding.getString(key).equals(nativeAssignment.getString(key)))throw new SecurityException("冻结贡献不属于原团队分工");
        if(binding.getInt("assignmentRevision")!=nativeAssignment.getInt("assignmentRevision"))throw new SecurityException("旧修订贡献不能导入本修订");
        JSONArray files=manifest.getJSONArray("files"),allowed=binding.getJSONArray("files");if(files.length()!=allowed.length()||files.length()<1||files.length()>8)throw new SecurityException("冻结贡献文件范围不完整");
        Map<String,String> content=new LinkedHashMap<>();Map<String,JSONObject> bases=new LinkedHashMap<>(),heads=new LinkedHashMap<>();long bytes=0;
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i),base=find(allowed,file.getString("path")),change=find(proof.getJSONArray("files"),file.getString("path"));String path=base.getString("path");
            if(content.containsKey(path)||!base.getString("baseHash").equals(change.getString("baseSha256"))||base.getInt("baseVersion")!=change.getInt("appBaseVersion"))throw new SecurityException("贡献基线或路径与原项目范围不符");
            check();String text;try(RemoteAssignmentWorkspaces.ExactFile exact=remote.readExact(host,contribution,path)){
                JSONObject actual=exact.view();for(String key:new String[]{"path","sha256","fileVersion"})if(!file.getString(key).equals(actual.getString(key)))throw new SecurityException("原不可变文件回读身份改变");if(file.getLong("bytes")!=actual.getLong("bytes"))throw new SecurityException("原不可变文件字节数改变");
                byte[] raw=exact.copyBytes();try{bytes=Math.addExact(bytes,raw.length);if(raw.length!=file.getLong("bytes")||bytes>host.context().view().getJSONObject("limits").getLong("maxContributionBytes"))throw new SecurityException("原贡献回读字节超出明确批准cap");text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}finally{Arrays.fill(raw,(byte)0);}
            }
            if(text.length()>65536||text.indexOf('\0')>=0||!file.getString("sha256").equals(TeamWorkFiles.hash(text)))throw new SecurityException("贡献不是完整可导入UTF-8文字，未截断导入");
            JSONObject current=workspace.call("read",new JSONObject().put("projectId",project).put("path",path));if(!TeamWorkFiles.hash(current.getString("content")).equals(base.getString("baseHash")))throw new SecurityException("原隔离草稿已变化，不能用远端旧base覆盖");
            content.put(path,text);bases.put(path,new JSONObject(base.toString()));heads.put(path,current);
        }
        boolean changed=false;for(String path:content.keySet())changed|=!bases.get(path).getString("baseHash").equals(TeamWorkFiles.hash(content.get(path)));
        if(!changed)return new JSONObject().put("ok",false).put("accepted",false).put("code","team_remote_no_change").put("requiresPause",true).put("message","原远端job已结算，但未产生所选文件变化；没有虚构贡献或重开job");
        TaskStore.TeamRemoteClaim claim=remote.claimImport(host,contribution,draft,preparedOperation,original);if(!claim.canDispatch())return gate(claim);
        host.beforeDispatch(claim,original);long pause=original.pauseRevision();JSONArray artifacts=new JSONArray();
        try{
            for(String path:content.keySet()){
                String text=content.get(path),hash=TeamWorkFiles.hash(text);JSONObject base=bases.get(path);if(hash.equals(base.getString("baseHash")))continue;
                JSONObject saved=effect("write",new JSONObject().put("projectId",project).put("path",path).put("content",text).put("expectedVersion",heads.get(path).getInt("version")),pause);
                check();JSONObject actual=workspace.call("read",new JSONObject().put("projectId",project).put("path",path).put("version",saved.getInt("version")));if(!hash.equals(TeamWorkFiles.hash(actual.getString("content")))||!saved.getString("revisionId").equals(actual.getString("revisionId")))throw new SecurityException("真实草稿写入回读不一致");
                JSONObject frozen=effect("createArtifact",new JSONObject().put("projectId",project).put("path",path).put("version",saved.getInt("version")).put("_taskId",binding.getString("taskId")).put("_runId",binding.getString("runId")),pause);
                if(!hash.equals(frozen.getString("sha256"))||!saved.getString("revisionId").equals(frozen.getString("sourceRevisionId")))throw new SecurityException("本机冻结成果与真实远端字节不一致");
                JSONObject reference=new JSONObject(base.toString()).put("draftVersion",saved.getInt("version")).put("draftRevisionId",saved.getString("revisionId")).put("sha256",hash).put("artifactId",frozen.getString("id")).put("draftProjectId",project);
                for(String key:new String[]{"targetProjectId","teamRunId","assignmentId","assignmentRevision","memberId","taskId","runId"})reference.put(key,binding.get(key));
                reference.put("remoteContribution",new JSONObject().put("contributionId",manifest.getString("contributionId")).put("snapshotVersion",manifest.getString("snapshotVersion")).put("remoteChildTaskId",claim.view().getString("childTaskId")).put("fileVersion",find(files,path).getString("fileVersion")).put("jobId",manifest.getString("jobId")).put("jobOperationId",manifest.getString("jobOperationId")).put("checkoutId",proof.getString("checkoutId")).put("checkoutIdentity",proof.getString("checkoutIdentity")).put("baseSnapshotId",proof.getString("baseSnapshotId")).put("baseSnapshotVersion",proof.getString("baseSnapshotVersion")).put("fullProject",false).put("authorshipVerified",false));artifacts.put(reference);
            }
            JSONObject result=new JSONObject().put("ok",true).put("status","completed").put("assignmentRevision",binding.getInt("assignmentRevision")).put("teamArtifacts",artifacts).put("manifestHash",TaskStore.capabilityFingerprint(manifest)).put("importedToOriginalDraft",true).put("integrated",false).put("requiresOwnerReview",true).put("businessVerified",false).put("commandChecksAvailable",false).put("message","原远端所选文字文件已精确回读并冻结到本成员隔离草稿；原项目未改变，仍待独审与唯一整合");host.record(claim,result);return result;
        }catch(Exception uncertain){JSONObject result=new JSONObject().put("ok",false).put("status","unknown").put("accepted",JSONObject.NULL).put("code","team_remote_import_unknown").put("manifestHash",TaskStore.capabilityFingerprint(manifest)).put("message","原贡献导入没有完整回执，可能已有部分草稿/产物保存；保留原prepared操作，仅核对，不重新导入");host.record(claim,result);return result;}
    }
    static JSONObject gate(TaskStore.TeamRemoteClaim claim)throws Exception {JSONObject view=claim.view();if(view.has("actualReceipt")&&view.optString("status").equals("completed"))return new JSONObject(view.getJSONObject("actualReceipt").toString()).put("known",true);boolean approval=view.optString("disposition").equals("awaiting_approval");return new JSONObject().put("ok",false).put("accepted",approval?false:JSONObject.NULL).put("status",approval?"awaiting_approval":"unknown").put("code",approval?"approval_required":"team_remote_operation_unknown").put("approvalId",view.optString("approvalId")).put("message",approval?"请主人在原native范围/原任务核对；尚未派发":"原操作已登记或回执未知，只核对原ID，不重新派发");}
    private JSONObject effect(String command,JSONObject args,long pause)throws Exception {check();JSONObject[] result={null};original.dispatchAtPauseRevision(pause,()->result[0]=workspace.call(command,args));return result[0];}
    private void check()throws Exception{original.check();if(original.isPaused())throw new SecurityException("原成员已暂停");live.check();}
    private static JSONObject find(JSONArray files,String path)throws Exception {for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).optString("path")))return files.getJSONObject(i);throw new SecurityException("文件不属于原assignment冻结范围");}
}
