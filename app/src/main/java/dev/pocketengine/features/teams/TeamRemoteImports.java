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
        check();if(dev.pocketengine.features.remote.RemoteProjectAssignments.full(host.context().view()))return executeBytes(contribution,preparedOperation);JSONObject draft=local.originalDraft(),manifest=contribution.view();String project=draft.getString("draftProjectId");
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
    /** Full-project transport is read back as exact native bytes/tombstones into the old isolated draft. */
    private JSONObject executeBytes(RemoteAssignmentWorkspaces.Contribution contribution,String operation)throws Exception {
        if(!workspace.byteFilesSupported())throw new SecurityException("原工作区没有字节CAS/tombstone原件能力，不截成文字导入");
        JSONObject draft=local.originalDraft(),manifest=contribution.view(),proof=manifest.getJSONObject("assignmentContribution"),nativeAssignment=proof.getJSONObject("nativeAssignment"),scope=host.context().view();String project=draft.getString("draftProjectId");if(project.equals(binding.getString("targetProjectId")))throw new SecurityException("原远端贡献不得写主项目");
        for(String key:new String[]{"teamRunId","assignmentId","memberId","assignmentRevision"})if(!TeamWorkFiles.canonical(binding.get(key)).equals(TeamWorkFiles.canonical(nativeAssignment.get(key))))throw new SecurityException("完整远端贡献不是本次原assignment修订");for(String key:new String[]{"workItemId","itemRevision","ownerGeneration"})if(binding.has(key)&&!TeamWorkFiles.canonical(binding.get(key)).equals(TeamWorkFiles.canonical(nativeAssignment.get(key))))throw new SecurityException("旧工作项/负责人贡献不能导入新代次");
        JSONArray files=manifest.getJSONArray("files"),allowed=binding.getJSONArray("files"),packageFiles=scope.getJSONObject("packageManifest").getJSONArray("files");if(files.length()>allowed.length()||files.length()>8)throw new SecurityException("原完整贡献超出批准输出路径");if(files.length()==0)return noChange();
        Map<String,byte[]> contents=new LinkedHashMap<>();Map<String,dev.pocketengine.features.workspace.VersionedWorkspace.FileSnapshot> heads=new LinkedHashMap<>();Map<String,JSONObject> bases=new LinkedHashMap<>();java.util.Set<String> seen=new java.util.HashSet<>();long total=0;
        try {
            for(int i=0;i<files.length();i++){
                JSONObject file=files.getJSONObject(i),base=find(allowed,file.getString("path")),change=find(proof.getJSONArray("files"),file.getString("path"));String path=file.getString("path");if(!seen.add(path)||!base.getString("baseHash").equals(change.getString("baseSha256"))||base.getInt("baseVersion")!=change.getInt("appBaseVersion"))throw new SecurityException("远端原件路径/base与本机分工不同");
                JSONObject old=null;for(int f=0;f<packageFiles.length();f++)if(path.equals(packageFiles.getJSONObject(f).getString("path")))old=packageFiles.getJSONObject(f);if(!file.getBoolean("tombstone")&&file.getBoolean("executable")!=(old!=null&&old.getBoolean("executable")))throw new SecurityException("本次字节/删除贡献不允许改变执行位；不能把远端未审结构混入组合包");
                check();try(RemoteAssignmentWorkspaces.ExactFile exact=remote.readExact(host,contribution,path)){
                    JSONObject actual=exact.view();for(String key:new String[]{"path","sha256","bytes","fileVersion","tombstone","artifactKind"})if(!TeamWorkFiles.canonical(file.get(key)).equals(TeamWorkFiles.canonical(actual.get(key))))throw new SecurityException("原远端冻结原件回读身份不同："+key);
                    if(file.getBoolean("tombstone")){if(!file.getString("sha256").equals("absent")||file.getLong("bytes")!=0||base.getString("baseHash").equals("absent"))throw new SecurityException("删除必须对应真实既有文件，不是空文件或从未存在的路径");}
                    else{byte[] bytes=exact.copyBytes();contents.put(path,bytes);total=Math.addExact(total,bytes.length);if(bytes.length!=file.getLong("bytes")||!TeamWorkFiles.hash(bytes).equals(file.getString("sha256"))||total>scope.getJSONObject("limits").getLong("maxContributionBytes"))throw new SecurityException("实际原件字节不符或超过本次明确cap");}
                }
                var head=workspace.nativeFile(project,path,0);if(!head.sha256.equals(base.getString("baseHash")))throw new SecurityException("本机隔离草稿已变，不能覆盖原IMPORT之外的新内容");heads.put(path,head);bases.put(path,new JSONObject(base.toString()));
            }
            boolean changed=false;for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);changed|=!file.getString("sha256").equals(bases.get(file.getString("path")).getString("baseHash"));}if(!changed)return noChange();
            TaskStore.TeamRemoteClaim claim=remote.claimImport(host,contribution,draft,operation,original);if(!claim.canDispatch())return gate(claim);host.beforeDispatch(claim,original);long pause=original.pauseRevision();JSONArray artifacts=new JSONArray();
            try {
                for(int i=0;i<files.length();i++){
                    JSONObject file=files.getJSONObject(i),base=bases.get(file.getString("path"));String path=file.getString("path"),hash=file.getString("sha256");if(hash.equals(base.getString("baseHash")))continue;var before=heads.get(path);dev.pocketengine.features.workspace.VersionedWorkspace.FileSnapshot[] saved={null};check();original.dispatchAtPauseRevision(pause,()->saved[0]=file.getBoolean("tombstone")?workspace.delete(before):workspace.writeBytes(before,contents.get(path)));
                    check();var actual=workspace.nativeFile(project,path,saved[0].version);if(!hash.equals(actual.sha256)||actual.tombstone!=file.getBoolean("tombstone")||!saved[0].revisionId.equals(actual.revisionId)||actual.sizeBytes!=file.getLong("bytes"))throw new SecurityException("原字节/删除CAS回读与实际远端原件不同");
                    dev.pocketengine.features.workspace.WorkspaceArtifacts.ArtifactRef[] frozen={null};check();original.dispatchAtPauseRevision(pause,()->frozen[0]=workspace.freeze(actual,operation,binding.getString("taskId"),binding.getString("runId")));check();var artifact=frozen[0];if(!artifact.sha256.equals(hash)||artifact.version!=actual.version||!artifact.revisionId.equals(actual.revisionId)||artifact.tombstone!=actual.tombstone)throw new SecurityException("原catalog冻结不是实际导入版本");
                    JSONObject ref=new JSONObject(base.toString()).put("draftVersion",actual.version).put("draftRevisionId",actual.revisionId).put("sha256",hash).put("artifactId",artifact.artifactId).put("artifactKind",artifact.kind).put("bytes",artifact.sizeBytes).put("mimeType",artifact.mimeType).put("draftProjectId",project);for(String key:new String[]{"targetProjectId","teamRunId","assignmentId","assignmentRevision","memberId","taskId","runId"})ref.put(key,binding.get(key));for(String key:new String[]{"workItemId","itemRevision","ownerGeneration"})if(binding.has(key))ref.put(key,binding.get(key));
                    ref.put("remoteContribution",new JSONObject().put("contributionId",manifest.getString("contributionId")).put("snapshotVersion",manifest.getString("snapshotVersion")).put("remoteChildTaskId",claim.view().getString("childTaskId")).put("fileVersion",file.getString("fileVersion")).put("jobId",manifest.getString("jobId")).put("jobOperationId",manifest.getString("jobOperationId")).put("checkoutId",proof.getString("checkoutId")).put("checkoutIdentity",proof.getString("checkoutIdentity")).put("gitBaseCommit",proof.getString("gitBaseCommit")).put("packageIdentity",proof.getString("packageIdentity")).put("fullProject",true).put("authorshipVerified",false).put("phoneCombinedChecksVerified",false));artifacts.put(ref);
                }
                JSONObject result=new JSONObject().put("ok",true).put("status","completed").put("assignmentRevision",binding.getInt("assignmentRevision")).put("teamArtifacts",artifacts).put("manifestHash",TaskStore.capabilityFingerprint(manifest)).put("importedToOriginalDraft",true).put("fullProject",true).put("integrated",false).put("requiresOwnerReview",true).put("businessVerified",false).put("phoneCombinedChecksVerified",false).put("message","原完整项目job的真实增改删/二进制已按原base精确回读并冻结到隔离草稿；仍待独审/主人/手机唯一整合者的本版组合检查与包");host.record(claim,result);return result;
            }catch(Exception unknown){JSONObject receipt=new JSONObject().put("ok",false).put("status","unknown").put("accepted",JSONObject.NULL).put("code","team_remote_import_unknown").put("manifestHash",TaskStore.capabilityFingerprint(manifest)).put("message","原字节/删除IMPORT可能已有部分效果；仅核对原operation，不重导入、不退额度");host.record(claim,receipt);return receipt;}
        }finally{for(byte[] bytes:contents.values())Arrays.fill(bytes,(byte)0);}
    }
    private static JSONObject noChange()throws Exception{return new JSONObject().put("ok",false).put("accepted",false).put("code","team_remote_no_change").put("requiresPause",true).put("message","原job确定结算但无批准路径变化；不虚构贡献，不重开job");}
    static JSONObject gate(TaskStore.TeamRemoteClaim claim)throws Exception {JSONObject view=claim.view();if(view.has("actualReceipt")&&view.optString("status").equals("completed"))return new JSONObject(view.getJSONObject("actualReceipt").toString()).put("known",true);boolean approval=view.optString("disposition").equals("awaiting_approval");return new JSONObject().put("ok",false).put("accepted",approval?false:JSONObject.NULL).put("status",approval?"awaiting_approval":"unknown").put("code",approval?"approval_required":"team_remote_operation_unknown").put("approvalId",view.optString("approvalId")).put("message",approval?"请主人在原native范围/原任务核对；尚未派发":"原操作已登记或回执未知，只核对原ID，不重新派发");}
    private JSONObject effect(String command,JSONObject args,long pause)throws Exception {check();JSONObject[] result={null};original.dispatchAtPauseRevision(pause,()->result[0]=workspace.call(command,args));return result[0];}
    private void check()throws Exception{original.check();if(original.isPaused())throw new SecurityException("原成员已暂停");live.check();}
    private static JSONObject find(JSONArray files,String path)throws Exception {for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).optString("path")))return files.getJSONObject(i);throw new SecurityException("文件不属于原assignment冻结范围");}
}
