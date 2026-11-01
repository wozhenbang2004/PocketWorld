package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real file/artifact persistence. PreparedHost is an explicit journal/approval fixture, not TaskStore SQL. */
public final class TeamWorkFilesTest {
    static String id(){return UUID.randomUUID().toString();}
    static final class Fixture {
        final File root=Files.createTempDirectory("team-work-files-").toFile();
        final VersionedWorkspace files=new VersionedWorkspace(new File(root,"workspace"));
        final WorkspaceArtifacts artifacts=new WorkspaceArtifacts(new File(root,"artifacts"),files);
        final String project=files.create("原项目").getString("id");
        final JSONObject binding;final PreparedHost host=new PreparedHost();final RunControl control=new RunControl();
        TeamWorkFiles.Workspace workspace=this::call;
        Fixture()throws Exception {files.write(project,"src/a.txt","原文件\n",0,false);files.write(project,"src/b.txt","其他成员文件\n",0,false);
            binding=new JSONObject().put("taskId",id()).put("runId",id()).put("coordinatorEpoch",1).put("teamRunId",id()).put("assignmentId",id()).put("memberId","writer").put("targetProjectId",project)
                    .put("files",TeamWorkFiles.captureFiles(workspace,project,new JSONArray().put("src/a.txt").put("new.txt")));
            control.bindHostRun(binding.getString("taskId"),binding.getString("runId"),1);
        }
        JSONObject call(String command,JSONObject args)throws Exception {String p=args.optString("projectId"),path=args.optString("path");return switch(command){
            case "create" -> files.create(args.getString("name"));case "list" -> new JSONObject().put("files",files.list(p));
            case "read" -> files.read(p,path,args.optInt("version",0));case "write" -> files.write(p,path,args.getString("content"),args.getInt("expectedVersion"),false);
            case "history" -> new JSONObject().put("history",files.history(p,path));case "export" -> files.export(p);
            case "createArtifact" -> artifacts.create(p,path,args.getInt("version"),args);case "readArtifact" -> artifacts.read(args.getString("artifactId"));
            default -> throw new IllegalArgumentException(command);
        };}
        TeamWorkFiles port()throws Exception {return new TeamWorkFiles(workspace,host,control,binding);}
        JSONObject execute(TeamWorkFiles port,String tool,JSONObject args,boolean approved)throws Exception {String op=host.prepare(tool,args,approved);JSONObject receipt=port.execute(tool,new JSONObject(args.toString()).put("_hostOperationId",op),control);host.settle(op,receipt);return receipt;}
        JSONObject prepare(TeamWorkFiles port)throws Exception {return execute(port,"team_prepare_draft",new JSONObject(),true);}
    }
    static final class PreparedHost implements TeamWorkFiles.Host {
        final Map<String,JSONObject> operations=new LinkedHashMap<>();boolean live=true;Runnable onClaim;
        String prepare(String tool,JSONObject args,boolean approved)throws Exception {String op=id();operations.put(op,new JSONObject().put("tool",tool).put("arguments",new JSONObject(args.toString())).put("approved",approved).put("state","prepared"));return op;}
        void settle(String op,JSONObject receipt)throws Exception {operations.get(op).put("state",receipt.optString("status").equals("unknown")?"unknown":"receipt").put("receipt",new JSONObject(receipt.toString()));}
        @Override public void check(String tool,RunControl original)throws Exception {original.check();if(!live||original.isPaused())throw new SecurityException("original assignment revoked");}
        @Override public JSONObject claim(String op,String tool,JSONObject args,JSONObject capability,RunControl original)throws Exception {
            check(tool,original);JSONObject row=operations.get(op);if(row==null||!tool.equals(row.getString("tool"))||!TeamWorkFiles.canonical(args).equals(TeamWorkFiles.canonical(row.getJSONObject("arguments"))))throw new SecurityException("not original prepared operation");
            if(!row.getString("state").equals("prepared"))return new JSONObject().put("dispatch",false).put("code","team_operation_unknown").put("status","unknown").put("accepted",JSONObject.NULL);
            if(capability.optBoolean("requiresOwnerApproval",true)&&!row.getBoolean("approved"))return new JSONObject().put("dispatch",false).put("ok",false).put("accepted",false).put("code","approval_required").put("message","等待主人审批");
            row.put("state","unknown").put("capability",new JSONObject(capability.toString()));if(onClaim!=null)onClaim.run();return new JSONObject().put("dispatch",true);
        }
        @Override public JSONArray receipts(String tool)throws Exception {JSONArray values=new JSONArray();for(JSONObject row:operations.values())if(tool.equals(row.optString("tool"))&&row.optString("state").equals("receipt")&&row.optJSONObject("receipt")!=null)values.put(new JSONObject(row.getJSONObject("receipt").toString()));return values;}
        @Override public JSONObject question(String op,String text,RunControl original)throws Exception {check("team_request_input",original);return new JSONObject().put("ok",false).put("accepted",false).put("code","needs_user").put("questionId",op).put("message",text);}
    }
    @Test public void realDraftRequiresPreparedApprovalAndFreezesActualFiles()throws Exception {
        Fixture f=new Fixture();try(TeamWorkFiles port=f.port()){
            assertEquals("approval_required",f.execute(port,"team_prepare_draft",new JSONObject(),false).getString("code"));assertEquals(1,f.files.projects().length());
            String draft=f.prepare(port).getJSONObject("teamDraft").getString("draftProjectId");assertEquals("原文件\n",f.files.read(draft,"src/a.txt",0).getString("content"));assertEquals(1,f.files.list(draft).length());
            JSONObject write=new JSONObject().put("path","src/a.txt").put("content","成员实际产出\n").put("expectedVersion",1);
            assertEquals("approval_required",f.execute(port,"team_write_file",write,false).getString("code"));assertEquals(1,f.files.read(draft,"src/a.txt",0).getInt("version"));
            assertTrue(f.execute(port,"team_write_file",write,true).getBoolean("ok"));assertEquals("原文件\n",f.files.read(f.project,"src/a.txt",0).getString("content"));
            JSONObject frozen=f.execute(port,"team_freeze_outputs",new JSONObject(),true);JSONObject artifact=frozen.getJSONArray("teamArtifacts").getJSONObject(0);assertEquals(1,frozen.getJSONArray("teamArtifacts").length());
            JSONObject original=f.artifacts.read(artifact.getString("artifactId"));assertEquals("成员实际产出\n",original.getString("content"));assertEquals(f.binding.getString("taskId"),original.getString("taskId"));assertEquals(TeamWorkFiles.hash(original.getString("content")),artifact.getString("sha256"));
            assertTrue(f.execute(port,"team_freeze_outputs",new JSONObject(),true).getBoolean("known"));assertEquals(1,f.artifacts.list(null).length());
            assertThrows(SecurityException.class,()->f.execute(port,"team_write_file",new JSONObject(write.toString()).put("expectedVersion",2),true));
        }
    }
    @Test public void noScopeOrPreparedIdentityCanBeAddedThroughToolArguments()throws Exception {
        Fixture f=new Fixture();try(TeamWorkFiles port=f.port()){
            assertThrows(SecurityException.class,()->port.validate("team_read_file",new JSONObject().put("path","src/b.txt")));
            assertThrows(SecurityException.class,()->port.validate("team_prepare_draft",new JSONObject().put("projectId",f.project)));
            assertThrows(SecurityException.class,()->port.execute("team_prepare_draft",new JSONObject().put("_hostOperationId",id()),f.control));
            assertThrows(SecurityException.class,()->port.execute("team_prepare_draft",new JSONObject(),f.control));
            RunControl foreign=new RunControl();foreign.bindHostRun(f.binding.getString("taskId"),f.binding.getString("runId"),1);assertThrows(SecurityException.class,()->port.execute("team_list_files",new JSONObject(),foreign));
        }
    }
    @Test public void lostActualWriteReceiptIsUnknownAndNeverDispatchedAgain()throws Exception {
        Fixture f=new Fixture();TeamWorkFiles.Workspace actual=f.workspace;String[] draft={null};f.workspace=(command,args)->{JSONObject result=actual.call(command,args);if(command.equals("write")&&args.optString("projectId").equals(draft[0]))throw new java.io.IOException("fixture lost reply after actual commit");return result;};
        try(TeamWorkFiles port=f.port()){
            draft[0]=f.prepare(port).getJSONObject("teamDraft").getString("draftProjectId");JSONObject args=new JSONObject().put("path","src/a.txt").put("content","已实际写入但回执丢失").put("expectedVersion",1);String op=f.host.prepare("team_write_file",args,true);
            JSONObject receipt=port.execute("team_write_file",new JSONObject(args.toString()).put("_hostOperationId",op),f.control);f.host.settle(op,receipt);assertEquals("unknown",receipt.getString("status"));assertEquals(2,f.files.read(draft[0],"src/a.txt",0).getInt("version"));
            assertThrows(IllegalStateException.class,()->port.execute("team_write_file",new JSONObject(args.toString()).put("_hostOperationId",op),f.control));assertEquals(2,f.files.read(draft[0],"src/a.txt",0).getInt("version"));
        }
    }
    @Test public void cancellationAtClaimPreventsFileEntry()throws Exception {
        Fixture f=new Fixture();try(TeamWorkFiles port=f.port()){f.host.onClaim=f.control::cancel;assertThrows(InterruptedException.class,()->f.prepare(port));assertEquals(1,f.files.projects().length());}
    }
    @Test public void capturedAbsentFileRetainsRealDeletedVersionForIntegrationCas()throws Exception {
        Fixture f=new Fixture();f.files.write(f.project,"deleted.txt","old",0,false);f.files.write(f.project,"deleted.txt","",1,true);
        JSONObject selected=TeamWorkFiles.captureFiles(f.workspace,f.project,new JSONArray().put("deleted.txt")).getJSONObject(0);assertEquals(2,selected.getInt("baseVersion"));assertEquals("absent",selected.getString("baseHash"));
    }
    @Test public void nativeNewRevisionKeepsOriginalAssignmentAndOldFrozenBytesWhileUsingANewDraft()throws Exception {
        Fixture f=new Fixture();JSONObject first;String oldDraft;try(TeamWorkFiles port=f.port()){oldDraft=f.prepare(port).getJSONObject("teamDraft").getString("draftProjectId");f.execute(port,"team_write_file",new JSONObject().put("path","src/a.txt").put("content","首次真实成果").put("expectedVersion",1),true);first=f.execute(port,"team_freeze_outputs",new JSONObject(),true).getJSONArray("teamArtifacts").getJSONObject(0);}
        JSONObject revised=new JSONObject(f.binding.toString()).put("assignmentRevision",2).put("seedArtifacts",new JSONArray().put(first)).put("runId",id()).put("coordinatorEpoch",2);RunControl live=new RunControl();live.bindHostRun(revised.getString("taskId"),revised.getString("runId"),2);
        try(TeamWorkFiles port=new TeamWorkFiles(f.workspace,f.host,live,revised)){
            String prepare=f.host.prepare("team_prepare_draft",new JSONObject(),true);JSONObject receipt=port.execute("team_prepare_draft",new JSONObject().put("_hostOperationId",prepare),live);f.host.settle(prepare,receipt);String next=receipt.getJSONObject("teamDraft").getString("draftProjectId");assertNotEquals(oldDraft,next);assertEquals("首次真实成果",f.files.read(next,"src/a.txt",0).getString("content"));assertEquals(2,receipt.getJSONObject("teamDraft").getInt("assignmentRevision"));
            JSONObject args=new JSONObject().put("path","src/a.txt").put("content","主人审查后新修订").put("expectedVersion",1);String write=f.host.prepare("team_write_file",args,true);JSONObject actual=port.execute("team_write_file",new JSONObject(args.toString()).put("_hostOperationId",write),live);f.host.settle(write,actual);assertTrue(actual.getBoolean("ok"));
            String freeze=f.host.prepare("team_freeze_outputs",new JSONObject(),true);JSONObject frozen=port.execute("team_freeze_outputs",new JSONObject().put("_hostOperationId",freeze),live);f.host.settle(freeze,frozen);JSONObject second=frozen.getJSONArray("teamArtifacts").getJSONObject(0);assertEquals(first.getString("assignmentId"),second.getString("assignmentId"));assertEquals(2,second.getInt("assignmentRevision"));assertNotEquals(first.getString("runId"),second.getString("runId"));assertEquals("首次真实成果",f.artifacts.read(first.getString("artifactId")).getString("content"));assertEquals("主人审查后新修订",f.artifacts.read(second.getString("artifactId")).getString("content"));assertEquals("首次真实成果",f.files.read(oldDraft,"src/a.txt",0).getString("content"));assertEquals("原文件\n",f.files.read(f.project,"src/a.txt",0).getString("content"));
        }
    }
    @Test public void dependencyReadUsesOnlyNativeFrozenReferenceWithoutAddingDraftFileRights()throws Exception {
        Fixture f=new Fixture();JSONObject source;try(TeamWorkFiles port=f.port()){f.prepare(port);f.execute(port,"team_write_file",new JSONObject().put("path","src/a.txt").put("content","前置分工真实原文与末尾反证").put("expectedVersion",1),true);source=f.execute(port,"team_freeze_outputs",new JSONObject(),true).getJSONArray("teamArtifacts").getJSONObject(0);}
        JSONObject recipient=new JSONObject(f.binding.toString()).put("taskId",id()).put("runId",id()).put("assignmentId",id()).put("memberId","reader").put("files",TeamWorkFiles.captureFiles(f.workspace,f.project,new JSONArray().put("src/b.txt"))).put("dependencyArtifacts",new JSONArray().put(source));RunControl live=new RunControl();live.bindHostRun(recipient.getString("taskId"),recipient.getString("runId"),1);
        try(TeamWorkFiles port=new TeamWorkFiles(f.workspace,f.host,live,recipient)){JSONObject text=port.execute("team_read_dependency",new JSONObject().put("artifactId",source.getString("artifactId")).put("offset",4).put("length",100),live);assertEquals("真实原文与末尾反证",text.getString("content"));assertEquals(source.getString("sha256"),text.getString("sha256"));assertThrows(SecurityException.class,()->port.validate("team_read_dependency",new JSONObject().put("artifactId",id())));assertThrows(SecurityException.class,()->port.validate("team_write_file",new JSONObject().put("path","src/a.txt").put("content","不能扩大权限").put("expectedVersion",1)));}
    }
}
