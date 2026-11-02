package dev.pocketengine.features.teams;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.features.remote.RemoteModule;
import dev.pocketengine.features.remote.RemoteAssignmentWorkspaces;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original native Coordinator call/SQL/owner authority -> production Companion/real file copies.
 * CLI and native selected-scope callback are controlled fixtures; this does not claim live Codex,
 * C6's phone-model port/UI, Git project build/test, independent review or device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteAssignmentNativeJourneyTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static final class Host implements RemoteAssignmentWorkspaces.NativeHost {
        final TaskStore tasks;final TaskStore.TeamRemoteContext context;
        Host(TaskStore tasks,TaskStore.TeamRemoteContext context){this.tasks=tasks;this.context=context;}
        public TaskStore.TeamRemoteContext context(){return context;}
        public JSONObject originalSnapshot(TaskStore.TeamRemoteContext context)throws Exception{return tasks.originalTeamRemoteSnapshot(context);}
        public TaskStore.TeamRemoteClaim originalClaim(TaskStore.TeamRemoteContext context,String op)throws Exception{return tasks.originalTeamRemoteClaim(context,op);}
        public TaskStore.TeamRemoteClaim claim(TaskStore.TeamRemoteAction action,String op,JSONObject scope,RunControl control)throws Exception{return tasks.claimTeamRemoteAction(context,action,op,scope,control);}
        public void beforeDispatch(TaskStore.TeamRemoteClaim claim,RunControl original)throws Exception{tasks.beforeTeamRemoteDispatch(claim,original);}
        public void record(TaskStore.TeamRemoteClaim claim,JSONObject actual)throws Exception{tasks.recordTeamRemoteReceipt(claim,actual);}
        public void checkOriginalRead(TaskStore.TeamRemoteContext context)throws Exception{tasks.checkOriginalTeamRemoteRead(context);}
    }
    private static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("native-assignment-contribution-");final AtomicInteger stage=new AtomicInteger(),done=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();
        Process server;BufferedReader output;String base,sourceTask,teamRun,parent,parentRun,assignment,prepareOp,submitOp,freezeOp,originalExecutionRun;Path originalWorkspace;
        TaskStore tasks;FeatureHost featureHost;RemoteModule module;RemoteOwnerWorkflows owner;RemoteAssignmentWorkspaces adapter;TeamCoordinator coordinator;ModelConfig model;
        RunControl parentControl;TeamWorkFiles.Workspace workspace;RemoteAssignmentWorkspaces.Execution execution;RemoteAssignmentWorkspaces.Contribution contribution;Host executionHost;JSONObject latestBinding;final boolean dropPrepare,deferSubmit;
        Fixture(boolean dropPrepare)throws Exception {this(dropPrepare,false);}
        Fixture(boolean dropPrepare,boolean deferSubmit)throws Exception {
            this.dropPrepare=dropPrepare;this.deferSubmit=deferSubmit;
            try{
                Path repo=Path.of(System.getProperty("user.dir"));server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/assignment_fixture_server.py").toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());JSONObject start=new JSONObject(output.readLine());base=start.getString("baseUrl");originalWorkspace=Path.of(start.getString("workspace"));
                var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");tasks=new TaskStore(app);featureHost=new FeatureHost(event->{},tasks,()->false);module=dev.pocketengine.features.remote.RemoteAssignmentFixtureModule.open(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{featureHost.recordEvent(event);}));
                module.handle("saveProfile",new JSONObject().put("id","computer").put("name","explicit controlled native fixture").put("baseUrl",base));dev.pocketengine.features.remote.RemoteAssignmentFixtureModule.markConfigured(app,"computer",base);featureHost.register(module);owner=new RemoteOwnerWorkflows(featureHost,tasks,event->{});adapter=new RemoteAssignmentWorkspaces(module);
                JSONObject actual=start.getJSONObject("source").put("profileId","computer").put("serviceBase",base);sourceTask=actual.getString("operationId");tasks.beginExternal("remote","submitJob",sourceTask,new JSONObject().put("profileId","computer").put("environmentId","workspace").put("worker","command"));tasks.updateExternal(sourceTask,actual);
                JSONObject source=tasks.remoteOwnerTarget(sourceTask),capture=tasks.claimRemoteResultSnapshot(source,new JSONArray().put("proof.txt"));JSONObject snapshot=module.captureResultSnapshot(new JSONObject(source.toString()).put("paths",new JSONArray().put("proof.txt")).put("operationId",capture.getString("operationId")).put("_profileBinding",module.profileBindingHash("computer")));tasks.recordRemoteResultSnapshot(sourceTask,capture.getString("operationId"),snapshot);
                VersionedWorkspace files=new VersionedWorkspace(root.resolve("native-project").toFile());String project=files.create("原项目").getString("id");files.write(project,"proof.txt","ORIGINAL SOURCE BYTES\n",0,false);files.write(project,"other.txt","other member",0,false);workspace=(command,args)->{switch(command){case "list":return new JSONObject().put("files",files.list(args.getString("projectId")));case "read":return files.read(args.getString("projectId"),args.getString("path"),args.optInt("version",0));case "history":return new JSONObject().put("history",files.history(args.getString("projectId"),args.getString("path")));case "create":return files.create(args.getString("name"));case "write":return files.write(args.getString("projectId"),args.getString("path"),args.getString("content"),args.getInt("expectedVersion"),false);default:throw new AssertionError("fixture never uses a substitute writer: "+command);}};
                JSONObject admitted=tasks.startAttempt(tasks.createCapabilityTask("真实远端所选文件贡献","").getString("id"),"capabilities",40);parent=admitted.getString("id");parentRun=admitted.getString("runId");RunControl original=new RunControl();original.bindHostRun(parent,parentRun,admitted.getInt("coordinatorEpoch"));parentControl=original;tasks.authorizeTools(parent,parentRun,new JSONArray().put("teams_get"));tasks.bindDevelopmentContext(parent,parentRun,admitted.getInt("coordinatorEpoch"),original,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",id()).put("runtime","managed_process_v1").put("initialSourceDigest","a".repeat(64)).put("clientMessageId",id()));
                model=new ModelConfig("https://example.invalid/v1","never-used-fixture-secret","phone-scheduler",false,24,10);teamRun=id();coordinator=new TeamCoordinator(root.resolve("native-team.json").toFile(),(m,h,c)->{throw new AssertionError("No model/provider is invoked by the native authority fixture");},event->{try{if(event.optJSONObject("run")!=null)tasks.updateExternal(teamRun,event.getJSONObject("run"));}catch(Exception error){failure.compareAndSet(null,error);}});coordinator.setAssignmentFactory(this::execute);
                JSONObject team=new JSONObject().put("id",id()).put("name","原native call").put("mode","produce").put("members",new JSONArray().put(new JSONObject().put("id","writer").put("name","制作").put("role","修改原文件").put("runner","model").put("device","phone").put("writeFiles",new JSONArray().put("proof.txt"))).put(new JSONObject().put("id","other").put("name","其他").put("role","只保留原分工").put("runner","model").put("device","phone").put("writeFiles",new JSONArray().put("other.txt"))));
                // Native callback fixture injects only the production captureSelection result. Not model JSON.
                JSONObject scope=TeamWriteScope.capture(workspace,parent,parentRun,project,team).snapshot();JSONArray selectedFiles=scope.getJSONArray("assignments").getJSONObject(0).getJSONArray("files");RemoteAssignmentWorkspaces.Selection selected=adapter.captureSelection(tasks,sourceTask,snapshot.getString("snapshotId"),"computer","workspace",selectedFiles,new JSONObject().put("maxWallTimeSeconds",30).put("maxOutputBytes",4096).put("maxContributionBytes",8192),"fixture-model",true);scope.getJSONArray("assignments").getJSONObject(0).put("remoteSelection",selected.view());scope.put("externalBudget",new JSONObject().put("externalTurnLimit",6).put("externalTurnsUsed",0));TeamWriteScope nativeScope=TeamWriteScope.restored(scope);
                tasks.beginRelatedExternal(parent,parentRun,"teams","start",teamRun,new JSONObject().put("goal","真实所选文件贡献").put("teamId",team.getString("id")));tasks.bindTeamWriteScope(teamRun,nativeScope);coordinator.startWithScope(team,"真实所选文件贡献",1,model,teamRun,null,null,nativeScope);awaitDone(1);
            }catch(Exception|Error error){try{close();}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
        }
        JSONObject execute(TeamCoordinator.AssignmentCall call,ModelConfig selected,RunControl original,java.util.function.Consumer<JSONObject> progress)throws Exception {
            if(!call.memberId.equals("writer"))return new JSONObject().put("assignmentId",call.assignmentId).put("taskId",call.assignmentId).put("status","awaiting_user").put("artifacts",new JSONArray());
            JSONObject binding=tasks.startTeamAssignment(call,original,24);assignment=call.assignmentId;latestBinding=binding;try{
                if(stage.get()==0){assertThrows(TaskStore.TeamRemoteScopeRequired.class,()->tasks.teamRemoteContext(call,binding,original));}
                else{
                    TaskStore.TeamRemoteContext context=tasks.teamRemoteContext(call,binding,original);Host host=new Host(tasks,context);RunJournal journal=tasks.journal(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),original);
                    if(stage.get()==1){
                        prepareOp=journal.beginOperation("team_prepare_remote_workspace",new JSONObject());if(dropPrepare)Files.writeString(root.resolve("drop-next-prepare-ack"),"controlled lost ack");RemoteAssignmentWorkspaces.Preparation preparation=adapter.prepare(host,prepareOp,original);journal.finishOperation(prepareOp,new JSONObject().put("status","completed").put("modelClaimsDone",true));assertNotNull(tasks.originalTeamRemoteClaim(context,prepareOp));
                        if(!dropPrepare&&!deferSubmit){assertTrue(preparation.ready());submitOp=journal.beginOperation("team_submit_remote_job",new JSONObject().put("prompt","Update only proof.txt in this original selected-file copy"));execution=adapter.submit(host,preparation,"Update only proof.txt in this original selected-file copy",submitOp,original);executionHost=host;originalExecutionRun=binding.getString("runId");journal.finishOperation(submitOp,new JSONObject().put("status","completed"));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);JSONObject actual;do{actual=adapter.inspect(host,execution);if(actual.optJSONArray("pendingApprovals")!=null&&actual.getJSONArray("pendingApprovals").length()>0)break;Thread.sleep(10);}while(System.nanoTime()<until);assertTrue(actual.toString(),actual.getJSONArray("pendingApprovals").getJSONObject(0).getBoolean("canAccept"));}
                    }else if(stage.get()==2&&deferSubmit){TaskStoreTeamAssignments draftHost=new TaskStoreTeamAssignments(tasks,event->{});try(TeamWorkFiles draft=new TeamWorkFiles(workspace,draftHost.files(call,binding,original),original,binding)){String draftOp=journal.beginOperation("team_prepare_draft",new JSONObject());JSONObject preparedDraft=draft.execute("team_prepare_draft",new JSONObject().put("_hostOperationId",draftOp),original);assertEquals(preparedDraft.toString(),"completed",preparedDraft.getString("status"));assertTrue(preparedDraft.has("teamDraft"));journal.finishOperation(draftOp,preparedDraft);}Host old=new Host(tasks,tasks.originalTeamRemoteContextForOperation(assignment,prepareOp));RemoteAssignmentWorkspaces.Preparation originalPrepare=adapter.restorePreparation(old,prepareOp);submitOp=journal.beginOperation("team_submit_remote_job",new JSONObject().put("prompt","Use the same original ready copy after legitimate native continuation"));execution=adapter.submit(host,originalPrepare,"Use the same original ready copy after legitimate native continuation",submitOp,original);executionHost=host;journal.finishOperation(submitOp,new JSONObject().put("status","completed"));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);JSONObject actual;do{actual=adapter.inspect(host,execution);if(actual.optJSONArray("pendingApprovals")!=null&&actual.getJSONArray("pendingApprovals").length()>0)break;Thread.sleep(10);}while(System.nanoTime()<until);assertTrue(actual.toString(),actual.getJSONArray("pendingApprovals").getJSONObject(0).getBoolean("canAccept"));
                    }else if(stage.get()==2){freezeOp=journal.beginOperation("team_freeze_remote_contribution",new JSONObject());contribution=adapter.freeze(host,execution,freezeOp,original);journal.finishOperation(freezeOp,new JSONObject().put("status","completed"));try(RemoteAssignmentWorkspaces.ExactFile file=adapter.readExact(host,contribution,"proof.txt")){assertEquals("REAL BYTES CHANGED ONLY IN THIS COPY\n",new String(file.copyBytes(),StandardCharsets.UTF_8));}assertNotEquals(originalExecutionRun,binding.getString("runId"));}
                }
                tasks.endAttempt(assignment,binding.getString("runId"),"awaiting_user","fixture native boundary",new JSONObject());return new JSONObject().put("assignmentId",assignment).put("taskId",assignment).put("status","awaiting_user").put("artifacts",new JSONArray());
            }catch(Throwable error){failure.compareAndSet(null,error);try{tasks.endAttempt(assignment,binding.getString("runId"),"interrupted","fixture failure",new JSONObject());}catch(Exception cleanup){error.addSuppressed(cleanup);}return new JSONObject().put("assignmentId",assignment).put("taskId",assignment).put("status","failed").put("artifacts",new JSONArray());}finally{done.incrementAndGet();}
        }
        void awaitDone(int count)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(done.get()<count&&failure.get()==null&&System.nanoTime()<until)Thread.sleep(10);if(failure.get()!=null)throw new AssertionError(failure.get());assertTrue("native original member did not return",done.get()>=count);while(coordinator.get(teamRun).optString("status").matches("queued|running")&&System.nanoTime()<until)Thread.sleep(5);}
        void approveScope()throws Exception {try(TaskStore.TeamRemoteOwnerWindow window=tasks.openTeamRemoteOwnerWindow()){window.setForeground(true);TaskStore.TeamRemoteScopeReview review=tasks.reviewTeamRemoteScope(window,assignment);JSONObject view=review.view();tasks.approveTeamRemoteScope(review,view.getString("digest"),1,view.getInt("proposedParentToolLimit"));}}
        void resume(int phase)throws Exception {stage.set(phase);coordinator.resume(teamRun,"writer",model,null,null);awaitDone(phase+1);}
        JSONObject queryToTerminal()throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JSONObject actual;do{actual=adapter.inspect(executionHost,execution);if(actual.optBoolean("dispatchFenceFinal")&&actual.optString("status").equals("completed"))return actual;Thread.sleep(10);}while(System.nanoTime()<until);throw new AssertionError(actual);}
        public void close()throws Exception {if(coordinator!=null)coordinator.close();if(module!=null)module.close();if(featureHost!=null)featureHost.close();if(server!=null){server.destroy();if(!server.waitFor(3,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}}if(output!=null)output.close();}
    }
    private static JSONObject parentResources(Fixture f)throws Exception {
        JSONObject parent=f.tasks.get(f.parent,false);RunControl read=f.parentControl;
        TaskStore.TeamParentOrigin origin=f.tasks.teamParentOrigin(f.parent,f.parentRun,parent.getInt("coordinatorEpoch"),"teams_get","",new JSONObject().put("teamRunId",f.teamRun),read);
        return f.tasks.teamParentExecutionState(origin,f.teamRun);
    }
    private static TeamRemoteProduction restoreStopHost(Fixture f,java.util.List<Runnable> pending)throws Exception {
        f.coordinator.close();f.coordinator=new TeamCoordinator(f.root.resolve("native-team.json").toFile(),(m,h,c)->{throw new AssertionError("Stopping must not restart inference");},event->{if(event.optJSONObject("run")!=null)try{f.tasks.updateExternal(f.teamRun,event.getJSONObject("run"));}catch(Exception failure){throw new IllegalStateException(failure);}});
        TeamRemoteProduction production=new TeamRemoteProduction(f.tasks,f.module,f.workspace,pending::add,id->{});
        f.coordinator.setAssignmentFactory(new TeamCoordinator.AssignmentFactory(){
            public JSONObject execute(TeamCoordinator.AssignmentCall call,ModelConfig model,RunControl control,java.util.function.Consumer<JSONObject> progress){throw new AssertionError("Stopping must not restart a member");}
            public void stopAssignments(String team,String member)throws Exception{production.stop(team,member);}
            public boolean hasActiveWork(String team){return production.hasActiveWork(team);}
        });return production;
    }
    @Test public void coldTeamStopFindsOriginalRemoteChildBeyondGlobalRecentWindow()throws Exception {
        try(Fixture f=new Fixture(false)){
            f.approveScope();f.resume(1);String child=f.execution.view().getString("childTaskId");
            for(int i=0;i<201;i++)f.tasks.createCapabilityTask("unrelated recent task "+i,"");
            assertFalse(f.tasks.list().toString().contains(child));assertFalse(f.tasks.list().toString().contains(f.assignment));
            java.util.List<Runnable> pending=new java.util.ArrayList<>();restoreStopHost(f,pending);
            f.coordinator.pause(f.teamRun,"other");assertEquals("Other member must not stop writer's original job",0,pending.size());
            f.coordinator.pause(f.teamRun,"writer");assertEquals("Original remote child must be enqueued outside the UI recent window",1,pending.size());
            pending.remove(0).run();JSONObject actual=f.adapter.inspect(f.executionHost,f.execution);assertEquals("cancelled",actual.getString("status"));assertTrue(actual.getJSONObject("processCleanupProof").getBoolean("processGroupStoppedVerified"));assertEquals("1",Files.readString(f.root.resolve("submit-count")));assertEquals(1,f.tasks.teamBudgetSnapshot(f.teamRun).getInt("externalJobsReservedOrUsed"));
        }
    }
    @Test public void coldResourcesStayHeldUntilOriginalJobHasPhysicalStopProof()throws Exception {
        try(Fixture f=new Fixture(false)){
            f.approveScope();f.resume(1);String child=f.execution.view().getString("childTaskId");java.util.List<Runnable> pending=new java.util.ArrayList<>();restoreStopHost(f,pending);
            assertFalse("Empty native worker maps are not remote termination proof",parentResources(f).getBoolean("resourcesReleased"));
            JSONObject actual=f.adapter.inspect(f.executionHost,f.execution);JSONObject unproved=new JSONObject(actual.toString()).put("status","cancelled").put("dispatchFenceFinal",true).put("processStarted",true);unproved.remove("processCleanupProof");f.tasks.updateExternal(child,unproved);
            assertFalse("A terminal label without physical cleanup must remain retained",parentResources(f).getBoolean("resourcesReleased"));
            f.coordinator.cancel(f.teamRun);assertEquals(1,pending.size());pending.remove(0).run();actual=f.adapter.inspect(f.executionHost,f.execution);assertTrue(actual.getJSONObject("processCleanupProof").getBoolean("processGroupStoppedVerified"));
            assertTrue("Exact original physical stop proof releases resources",parentResources(f).getBoolean("resourcesReleased"));assertEquals(1,f.tasks.teamBudgetSnapshot(f.teamRun).getInt("externalJobsReservedOrUsed"));
        }
    }
    @Test public void ownerScopeOriginalJobSingleFileApprovalActualContributionAndSafeNewAttemptStayOnOriginalSql()throws Exception {
        try(Fixture f=new Fixture(false)){
            f.approveScope();f.resume(1);JSONObject execution=f.execution.view();String child=execution.getString("childTaskId");assertNotEquals(child,execution.getString("jobOperationId"));assertNotEquals(f.submitOp,execution.getString("jobOperationId"));assertEquals("1",Files.readString(f.root.resolve("submit-count")));Object activity=new Object();f.owner.setForeground(activity,true);JSONObject review=f.owner.handle(activity,"open",new JSONObject().put("taskId",child).put("windowId",id())).getJSONObject("snapshot");String request=review.getJSONObject("source").getJSONArray("pendingApprovals").getJSONObject(0).getString("requestId");JSONObject input=new JSONObject().put("taskId",child).put("windowId",review.getString("windowId")).put("reviewId",review.getString("reviewId")).put("requestId",request).put("decision","accept");
            f.owner.setForeground(activity,false);assertThrows(SecurityException.class,()->f.owner.handle(activity,"approve",input));f.owner.setForeground(new Object(),true);assertThrows(SecurityException.class,()->f.owner.handle(activity,"approve",input));f.owner.setForeground(activity,true);review=f.owner.handle(activity,"refresh",new JSONObject().put("taskId",child).put("windowId",review.getString("windowId"))).getJSONObject("snapshot");input.put("reviewId",review.getString("reviewId"));f.owner.handle(activity,"approve",input);JSONObject terminal=f.queryToTerminal();assertTrue(terminal.getBoolean("assignmentWriteSandboxVerified"));assertTrue(terminal.getJSONObject("inferenceUsage").isNull("cost"));assertEquals("ORIGINAL SOURCE BYTES\n",Files.readString(f.originalWorkspace.resolve("proof.txt")));
            f.resume(2);JSONObject frozen=f.contribution.view();assertEquals("actual_selected_file_base_to_frozen_bytes",frozen.getJSONObject("assignmentContribution").getString("source"));assertFalse(frozen.getBoolean("authorshipVerified"));assertFalse(frozen.getJSONObject("assignmentContribution").getBoolean("integrated"));assertEquals("1",Files.readString(f.root.resolve("prepare-count")));assertEquals("1",Files.readString(f.root.resolve("submit-count")));assertEquals(1,f.tasks.get(f.assignment,false).getJSONArray("relatedTasks").length());
        }
    }
    @Test public void readyOriginalPrepareCanSubmitOnceFromNewActualAttemptWithoutRecopyOrRewritingItsPhysicalOrigin()throws Exception {
        try(Fixture f=new Fixture(false,true)){
            f.approveScope();f.resume(1);TaskStore.TeamRemoteContext old=f.tasks.originalTeamRemoteContextForOperation(f.assignment,f.prepareOp);String oldRun=old.view().getJSONObject("nativeAssignment").getString("runId");assertFalse(Files.exists(f.root.resolve("submit-count")));f.resume(2);JSONObject original=f.tasks.originalTeamRemoteClaim(f.tasks.originalTeamRemoteContextForOperation(f.assignment,f.submitOp),f.submitOp).view(),reference=original.getJSONObject("scope").getJSONObject("assignmentWorkspace");assertEquals(oldRun,reference.getJSONObject("preparationNativeAssignment").getString("runId"));assertNotEquals(oldRun,reference.getJSONObject("nativeAssignment").getString("runId"));assertEquals("1",Files.readString(f.root.resolve("prepare-count")));assertEquals("1",Files.readString(f.root.resolve("submit-count")));assertEquals(1,f.tasks.get(f.assignment,false).getJSONArray("relatedTasks").length());Host read=new Host(f.tasks,f.tasks.originalTeamRemoteContext(original.getString("childTaskId")));f.adapter.cancel(read,f.adapter.restoreExecution(read,f.submitOp),null,null);
        }
    }
    @Test public void retiredOrPausedMemberNeverApprovesOldPendingButNativeOriginalCleanupReallySettlesWithoutRefund()throws Exception {
        try(Fixture f=new Fixture(false)){
            f.approveScope();f.resume(1);String child=f.execution.view().getString("childTaskId");f.coordinator.pause(f.teamRun,"writer");JSONObject actual=f.adapter.inspect(f.executionHost,f.execution);JSONObject source=f.tasks.remoteOwnerTarget(child),request=source.getJSONArray("pendingApprovals").getJSONObject(0);try(TaskStore.TeamRemoteOwnerWindow window=f.tasks.openTeamRemoteOwnerWindow()){window.setForeground(true);assertThrows(SecurityException.class,()->f.tasks.reviewTeamRemotePending(window,child,source.getString("runId"),source.getInt("coordinatorEpoch"),request.getString("requestId"),TaskStore.capabilityFingerprint(request)));}
            TaskStore.TeamRemoteContext read=f.tasks.originalTeamRemoteContext(child);Host readHost=new Host(f.tasks,read);RemoteAssignmentWorkspaces.Execution original=f.adapter.restoreExecution(readHost,f.submitOp);f.adapter.cancel(readHost,original,null,null);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);do{actual=f.adapter.inspect(readHost,original);if(actual.optBoolean("dispatchFenceFinal"))break;Thread.sleep(10);}while(System.nanoTime()<until);assertEquals("cancelled",actual.getString("status"));assertTrue(actual.getJSONObject("processCleanupProof").getBoolean("processGroupStoppedVerified"));assertTrue(actual.getJSONObject("processCleanupProof").getBoolean("leaderReaped"));assertFalse(actual.getBoolean("notStartedConfirmed"));assertEquals(1,f.tasks.teamBudgetSnapshot(f.teamRun).getInt("externalJobsReservedOrUsed"));assertEquals("1",Files.readString(f.root.resolve("submit-count")));assertEquals("ORIGINAL SOURCE BYTES\n",Files.readString(f.originalWorkspace.resolve("proof.txt")));
        }
    }
    @Test public void lostPrepareAckJournalCannotInventSuccessAndRestartOnlyReadsOriginalCopy()throws Exception {
        try(Fixture f=new Fixture(true)){
            f.approveScope();f.resume(1);assertTrue(f.tasks.hasUnresolved(f.assignment));assertEquals("1",Files.readString(f.root.resolve("prepare-count")));TaskStore restarted=new TaskStore(RuntimeEnvironment.getApplication());TaskStore.TeamRemoteContext read=restarted.originalTeamRemoteContextForOperation(f.assignment,f.prepareOp);Host restored=new Host(restarted,read);RemoteAssignmentWorkspaces.Preparation known=f.adapter.restorePreparation(restored,f.prepareOp);assertTrue(known.ready());assertFalse(restarted.hasUnresolved(f.assignment));assertEquals("1",Files.readString(f.root.resolve("prepare-count")));assertFalse(Files.exists(f.root.resolve("submit-count")));assertEquals(0,restarted.teamBudgetSnapshot(f.teamRun).getInt("externalJobsReservedOrUsed"));assertThrows(SecurityException.class,()->restarted.checkTeamRemoteScopeCurrent(read));
        }
    }
}
