package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Actual original Runner/SQLite/managed Git protocol. HTTP responses and owner decisions are
 * explicit fixtures, not a real provider/autonomous repair or phone ARM/UI claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectGitRunnerJourneyTest {
    private ProjectGitNativeJourneyTest.Fixture f;private String taskId;private final List<String> runs=new ArrayList<>();
    @Before public void create()throws Exception {f=new ProjectGitNativeJourneyTest.Fixture();f.seed();f.write("main.py","print('MODEL_SELECTED_STAGED_VALUE')\n");f.write("other.py","print('UNSELECTED_STAGED_VALUE')\n");f.stage("other.py");taskId=f.tasks.createCapabilityTask("Read actual Git layers and diffs, stage only main.py, then commit its reviewed exact index tree locally; preserve the other staged file and do not push or run hooks.","").getString("id");}
    @After public void close()throws Exception {if(f!=null)f.close();}
    private void run(Model model)throws Exception {
        for(int attempt=0;attempt<16;attempt++){
            JSONObject task=f.tasks.startAttempt(taskId,"capabilities",100);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");runs.add(run);RunControl control=new RunControl();control.bindHostRun(taskId,run,epoch);f.modelControl.set(control);
            if(attempt==0)f.tasks.bindDevelopmentContext(taskId,run,epoch,control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",f.project).put("checkoutId",f.checkout).put("runtime",ManagedProjectRuntime.RUNTIME_ID).put("initialSourceDigest",f.digest()).put("clientMessageId",UUID.randomUUID().toString()));
            var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,control,true,f.project);var port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(f.tasks,binding,event->{}),control,null,f.local,f.tasks,f.checkout,ManagedProjectRuntime.RUNTIME_ID);
            JSONArray tools=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)tools.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));f.tasks.authorizeTools(taskId,run,tools);
            RunOutcome outcome;
            try {outcome=new AgentRunner(null,port).run(taskId,task.getString("goal"),model.config(),control,event->{try{f.tasks.event(taskId,run,epoch,event);}catch(Exception failed){throw new IllegalStateException(failed);}},f.tasks.journal(taskId,run,epoch,control));}
            finally {port.close();}
            String diagnostic=diagnostic(model,outcome,"attempt-"+attempt);System.out.println("C07_RUNNER "+diagnostic);f.tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);
            assertFalse("Unknown Git must not be replayed: "+diagnostic,f.tasks.hasUnresolved(taskId));boolean pending=false;JSONArray approvals=f.tasks.get(taskId,false).optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(approval.getString("state").equals("pending")){f.tasks.decideCapabilityApproval(taskId,approval.getString("id"),true);pending=true;}}
            if(!pending){assertTrue(diagnostic,model.finished);assertEquals(diagnostic,"needs_confirmation",outcome.status);return;}assertEquals(diagnostic,"awaiting_user",outcome.status);
        }fail("Original Git approval continuation did not converge: "+diagnostic(model,null,"limit"));
    }
    private String diagnostic(Model model,RunOutcome outcome,String phase)throws Exception {
        JSONObject result=new JSONObject().put("phase",phase).put("stage",model.stage).put("lastTool",model.emittedTool).put("actualHttpRequests",model.requests.size()).put("originalRuns",runs.size()).put("approvedRefMigrations",model.refMigrations).put("taskId",taskId).put("modelFixtureFailureType",model.failure==null?JSONObject.NULL:model.failure.getClass().getSimpleName());
        if(outcome!=null)result.put("outcomeStatus",outcome.status).put("safeSummary",outcome.summary==null?"":outcome.summary.replaceAll("(?i)(token|password|secret|authorization)\\s*[:=]\\s*\\S+","$1=[redacted]").substring(0,Math.min(1200,outcome.summary.length())));
        JSONArray rows=f.tasks.get(taskId,false).getJSONArray("operations"),statuses=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),safe=new JSONObject().put("operationId",row.getString("operationId")).put("runId",row.getString("runId")).put("state",row.getString("state")).put("tool",row.getString("tool")),receipt=row.optJSONObject("receipt");if(receipt!=null){safe.put("code",receipt.optString("code")).put("status",receipt.optString("status"));JSONObject backend=receipt.optJSONObject("executionReceipt");if(backend!=null)for(String key:new String[]{"status","exitCode","receiptReceived","processExited","processGroupAbsent","resourcesReleased"})if(backend.has(key))safe.put("native_"+key,backend.get(key));}statuses.put(safe);}result.put("originalOperationStates",statuses);
        String path=System.getProperty("c2.evidenceDirectory");if(path!=null){Files.createDirectories(Path.of(path));Files.writeString(Path.of(path).resolve("c07-"+phase+".json"),result.toString(2));}return result.toString();
    }
    @Test public void originalModelApprovalsAcrossNewRunsReadRealLayersStageSelectedAndCommitExactTree()throws Exception {
        try(Model model=new Model()){
            run(model);assertEquals(25,model.stage);assertTrue(model.refMigrations>=7);assertTrue(runs.size()>1);assertTrue(model.sawWorkingDiff&&model.sawStagedDiff&&model.sawRealDualStatus&&model.sawOriginalEarlierRunRead);assertNotNull(model.commit);
            assertEquals("print('MODEL_SELECTED_STAGED_VALUE')",f.git("show","HEAD:main.py"));assertEquals("print('other')",f.git("show","HEAD:other.py"));assertTrue(Files.readString(f.root.resolve("other.py")).contains("UNSELECTED_STAGED_VALUE"));assertEquals(model.commit.getString("commitId"),f.git("rev-parse","HEAD"));assertEquals(model.commit.getString("treeId"),f.git("rev-parse","HEAD^{tree}"));assertEquals(1,model.commit.getJSONArray("reviewedEntries").length());assertEquals("main.py",model.commit.getJSONArray("reviewedEntries").getJSONObject(0).getString("path"));assertNull(f.callbackFailure.get());
            JSONObject task=f.tasks.get(taskId,false);assertFalse(task.has("ownerGit"));for(int i=0;i<task.getJSONArray("operations").length();i++){JSONObject row=task.getJSONArray("operations").getJSONObject(i);assertEquals("receipt",row.getString("state"));assertTrue(row.getJSONObject("arguments").has("planRef"));}
            // The new result read is same original task only, not permission inferred from an execution ID.
            RunControl other=new RunControl();String alien=f.tasks.createCapabilityTask("Other project task","").getString("id");JSONObject next=f.tasks.startAttempt(alien,"capabilities",5);other.bindHostRun(alien,next.getString("runId"),next.getInt("coordinatorEpoch"));f.tasks.authorizeTools(alien,next.getString("runId"),new JSONArray().put("project_git_result"));assertThrows(Exception.class,()->f.tasks.originalGitRequestForTask(alien,next.getString("runId"),next.getInt("coordinatorEpoch"),other,model.firstStatusExecution));f.tasks.endAttempt(alien,next.getString("runId"),"paused","Fixture isolated read refused",new JSONObject());
            System.out.println("C07_RUNNER_COMPLETE 25 actual tool phases; original HTTP model -> native immutable Git semantic planRef -> exact owner approval -> startAttempt NEW run/migrated ref -> actual managed JNI Git -> next original model reads true status/diff/stage/selected commit; old-run original result read does not redispatch; parent/tree/blob identities checked against real Git; other staged file preserved. Model is fixture, not phone ARM/visual/provider evidence.");
        }
    }
    private final class Model implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread worker;final List<JSONObject> requests=new CopyOnWriteArrayList<>();volatile Throwable failure;volatile int stage;int calls,refMigrations;String emitted="",emittedTool="",execution="",firstStatusExecution="";boolean refresh,waiting,finished,sawWorkingDiff,sawStagedDiff,sawRealDualStatus,sawOriginalEarlierRunRead;JSONObject inspection,plan,snapshot,commit;
        Model()throws Exception {server.bind(new InetSocketAddress("127.0.0.1",0));worker=new Thread(this::serve,"c07-owned-fixture-http-model");worker.setDaemon(true);worker.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","fixture-not-a-real-key","c07-real-git-fixture",false,40,30).withStepCeiling(100);}
        JSONObject next(JSONObject request)throws Exception {
            JSONArray messages=request.getJSONArray("messages");JSONObject last=null;for(int i=messages.length()-1;i>=0;i--)if("tool".equals(messages.getJSONObject(i).optString("role"))){last=messages.getJSONObject(i);break;}
            if(last!=null&&emitted.equals(last.optString("tool_call_id"))){JSONObject result=new JSONObject(last.getString("content"));emitted="";
                if("approval_required".equals(result.optString("code"))){waiting=true;refresh=true;return emit("project_inspect",new JSONObject());}
                if(refresh){JSONArray proposals=result.getJSONArray("approvedPlanProposals");JSONObject exact=null;String step=plan.getJSONArray("steps").getJSONObject(0).getString("id");for(int i=0;i<proposals.length();i++){JSONObject candidate=proposals.getJSONObject(i);if(step.equals(candidate.getString("stepId"))&&plan.getString("planDigest").equals(candidate.getString("planDigest"))){assertNull("only original approved semantic plan",exact);exact=candidate;}}assertNotNull("new run must read original approved ref",exact);assertNotEquals(plan.getString("planRef"),exact.getString("planRef"));plan.put("planRef",exact.getString("planRef"));inspection=result;refMigrations++;refresh=false;waiting=false;
                }else{accept(result);stage++;}}
            if(waiting){refresh=true;return emit("project_inspect",new JSONObject());}
            String tool;JSONObject args=new JSONObject();switch(stage){
                case 0,10,21:tool="project_inspect";break;
                case 1,11,22:tool="project_plan";args=proposal(new JSONObject().put("gitAction","status"));break;
                case 4:tool="project_plan";args=proposal(new JSONObject().put("gitAction","diff").put("gitPin",snapshot.getJSONObject("pin")).put("path","main.py").put("gitLayer","index_worktree"));break;
                case 7:tool="project_plan";args=proposal(ProjectGit.selectionRequest(snapshot,"stage",new JSONArray().put("main.py")));break;
                case 14:tool="project_plan";args=proposal(new JSONObject().put("gitAction","diff").put("gitPin",snapshot.getJSONObject("pin")).put("path","main.py").put("gitLayer","head_index"));break;
                case 17:tool="project_plan";args=proposal(ProjectGit.selectionRequest(snapshot,"commit",new JSONArray().put("main.py")).put("commitMessage","Commit only reviewed main.py original staged blob").put("authorName","PocketWorld Model Fixture").put("authorEmail","fixture@example.invalid"));break;
                case 2,5,8,12,15,18,23:tool="project_execute";args.put("planRef",plan.getString("planRef")).put("planDigest",plan.getString("planDigest")).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id"));break;
                case 3,6,9,13,16,19,24:tool="project_git_result";args.put("executionId",execution);break;
                case 20:tool="project_git_result";args.put("executionId",firstStatusExecution);break;
                default:finished=true;return new JSONObject().put("content","Real Git confirms the selected staged main.py blob was committed locally while other.py remained staged. No push or Android validation was performed.");
            }return emit(tool,args);
        }
        JSONObject proposal(JSONObject git)throws Exception {return new JSONObject().put("git",new JSONObject(git.toString())).put("intent","git").put("expectedSourceDigest",inspection.getString("sourceDigest"));}
        JSONObject emit(String tool,JSONObject args)throws Exception {emitted="c07-"+stage+"-"+(++calls);emittedTool=tool;return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",args.toString()))));}
        void accept(JSONObject result)throws Exception {
            assertFalse("stage="+stage+" tool="+emittedTool+" code="+result.optString("code"),result.has("ok")&&!result.getBoolean("ok"));assertNotEquals("Original protocol uncertainty must stop stage="+stage,"unknown",result.optString("status"));
            if(emittedTool.equals("project_inspect"))inspection=result;
            if(emittedTool.equals("project_plan")){plan=result;assertFalse(result.getBoolean("executionAuthorized"));JSONObject stored=f.tasks.projectArguments(taskId,runs.get(runs.size()-1),f.tasks.get(taskId,false).getInt("coordinatorEpoch"),new JSONObject().put("planRef",plan.getString("planRef")).put("planDigest",plan.getString("planDigest"))).getJSONObject("plan");assertEquals(plan.getString("planDigest"),ProjectBuildPlan.verified(stored).getString("planDigest"));assertFalse(stored.getJSONArray("steps").getJSONObject(0).getJSONObject("payload").has("argv"));}
            if(emittedTool.equals("project_execute")){execution=result.getString("jobId");assertEquals(0,result.getInt("exitCode"));assertTrue(result.getBoolean("resourcesReleased"));}
            if(emittedTool.equals("project_git_result")){
                assertTrue(result.getBoolean("ok"));JSONObject data=result.getJSONObject("data");String action=result.getString("action");
                if(action.equals("status")&&stage!=20){snapshot=data;if(firstStatusExecution.isEmpty())firstStatusExecution=execution;JSONArray rows=data.getJSONArray("entries");boolean main=false,other=false;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("path").equals("main.py")){main=true;if(stage==3)assertEquals("M",row.getString("worktreeStatus"));if(stage==13)assertEquals("M",row.getString("indexStatus"));}if(row.getString("path").equals("other.py")){other=true;assertEquals("M",row.getString("indexStatus"));}}if(stage==3){assertTrue(main&&other);sawRealDualStatus=true;}if(stage==24){assertFalse(main);assertTrue(other);}}
                if(stage==20){assertEquals(firstStatusExecution,result.getString("executionId"));sawOriginalEarlierRunRead=true;}
                if(action.equals("diff")){assertTrue(data.getString("diff").contains("+print('MODEL_SELECTED_STAGED_VALUE')"));if(data.getString("layer").equals("index_worktree"))sawWorkingDiff=true;else sawStagedDiff=true;}
                if(action.equals("commit")){commit=data;assertTrue(data.getBoolean("unselectedIndexPreserved"));assertFalse(data.getBoolean("worktreeWritten"));}
            }
        }
        void serve(){while(!server.isClosed())try(Socket socket=server.accept()){
            socket.setSoTimeout(20000);InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int length=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(length<1||length>2*1024*1024)throw new IOException("fixture request bound");JSONObject request=new JSONObject(new String(input.readNBytes(length),StandardCharsets.UTF_8));requests.add(request);JSONObject answer=next(request).put("role","assistant");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
        }catch(Throwable error){if(!server.isClosed()){failure=error;System.out.println("C07_MODEL_FIXTURE_FAILURE stage="+stage+" tool="+emittedTool+" type="+error.getClass().getSimpleName()+" safeMessage="+String.valueOf(error.getMessage()).replace("fixture-not-a-real-key","[fixture-key]").substring(0,Math.min(800,String.valueOf(error.getMessage()).length())));try{server.close();}catch(Exception ignored){}}}}
        String line(InputStream input)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(int b;(b=input.read())!=-1;){if(b=='\n')return out.toString(StandardCharsets.US_ASCII).replace("\r","");if(out.size()>8192)throw new IOException("fixture header bound");out.write(b);}throw new EOFException();}
        @Override public void close()throws Exception {server.close();worker.join(2000);assertFalse(worker.isAlive());if(failure!=null)throw new AssertionError("Original Git model fixture failed stage="+stage+" tool="+emittedTool,failure);}
    }
}
