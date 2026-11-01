package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** The original SQLite/Runner/host supervisor. Model and owner decisions are explicit fixtures, not a provider/ARM claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectPreciseRunnerJourneyTest {
    private ProjectManagedRunnerJourneyTest factory;private LocalModule local;private TaskStore tasks;private RunControl original;private ProjectCapabilityPort port;private JSONObject task;private String taskId,checkout,project,run;private int epoch;private Path active;private JSONArray names;private final List<String> nativeRuns=new ArrayList<>();
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception{Field f=ProjectManagedRunnerJourneyTest.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(factory);}
    private Object call(String name,Class<?>[] types,Object... values)throws Exception{Method method=ProjectManagedRunnerJourneyTest.class.getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(factory,values);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw (Error)failed.getCause();}}
    @Before public void create()throws Exception {
        factory=new ProjectManagedRunnerJourneyTest();factory.create();local=field("local");tasks=field("tasks");call("selectedProject",new Class<?>[]{String.class},"Actual precise source repair with native proposal references");
        call("file",new Class<?>[]{String.class,String.class},"package.json","{\"type\":\"module\"}\n");call("file",new Class<?>[]{String.class,String.class},"main.mjs","import {amount} from './src/budget.mjs'; console.log('AMOUNT='+amount.toFixed(2));\n");call("file",new Class<?>[]{String.class,String.class},"test/money.test.mjs","import test from 'node:test';import assert from 'node:assert/strict';import{amount}from'../src/budget.mjs';test('real amount must be 45',()=>assert.equal(amount,45));\n");
        call("startTask",new Class<?>[]{String.class},"Find the real amount implementation, precisely patch it, use actual tests to repair errors, demonstrate a conflicting and a valid conditional undo, then finish the same project with amount 45.");taskId=field("taskId");checkout=field("checkout");project=field("project");active=(Path)call("active",new Class<?>[0]);
        // These are actual owner file APIs on the selected active checkout, not a fabricated source manifest.
        local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","src/budget.mjs").put("expectedHash","absent").put("content","/* retained real source padding "+"source ".repeat(14000)+"*/\nexport const amount = 40;\n"));
        for(int i=0;i<240;i++)local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path",String.format(Locale.ROOT,"notes/source-%03d.txt",i)).put("expectedHash","absent").put("content","actual retained project source "+i+"\n"));
    }
    private void attempt()throws Exception {
        task=(JSONObject)call("startFixtureAttempt",new Class<?>[0]);original=((AtomicReference<RunControl>)field("live")).get();run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");nativeRuns.add(run);var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);names=new JSONArray();JSONArray definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,names);
    }
    @After public void close()throws Exception {try{if(port!=null)port.close();}finally{if(factory!=null)factory.close();}}
    @Test public void originalRunnerSearchesLargeActiveTreeCarriesUndispatchedApprovalToFreshRefRepairsAndConditionallyUndoes()throws Exception {
        try(Model model=new Model()){
            for(int tries=0;tries<16;tries++){
                attempt();RunOutcome outcome=new AgentRunner(null,port).run(taskId,task.getString("goal"),model.config(),original,event->{try{tasks.event(taskId,run,epoch,event);}catch(Exception failed){throw new IllegalStateException(failed);}},tasks.journal(taskId,run,epoch,original));
                System.out.println("S01_ACTUAL attempt="+tries+" phase="+model.phase+" status="+outcome.status+" summary="+outcome.summary+" failure="+model.failure);if(model.failure!=null)throw new AssertionError("loopback model fixture failure",model.failure);
                tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);port.close();port=null;assertFalse("unknown effects must never be replayed",tasks.hasUnresolved(taskId));
                JSONArray approvals=tasks.get(taskId,false).getJSONArray("approvals");boolean pending=false;for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if(approval.optString("state").equals("pending")){assertEquals("project_execute",approval.getString("tool"));assertTrue(approval.getJSONObject("arguments").has("planRef"));tasks.decideCapabilityApproval(taskId,approval.getString("id"),true);model.expectContinuation=true;pending=true;}}
                if(!pending){assertTrue(outcome.summary,model.finished);assertEquals(25,model.phase);assertTrue(model.sawRealNonzeroTest);assertTrue(model.sawRealPassingTest);assertTrue(model.sawUndoConflict);assertTrue(model.sawActualUndo);assertTrue(model.sawFinal45);assertTrue(model.continuations>=8);assertTrue(nativeRuns.size()>1);assertTrue(Files.readString(active.resolve("src/budget.mjs")).endsWith("amount = 45;\n"));assertNull(((AtomicReference<Exception>)field("callbackFailure")).get());
                    JSONArray operations=tasks.get(taskId,false).getJSONArray("operations");int effects=0;for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i);if(!operation.optString("tool").equals("project_execute"))continue;JSONObject args=operation.getJSONObject("arguments");assertTrue(args.has("planRef"));assertFalse("full plan must not pass through sanitized journal arguments",args.has("plan"));assertTrue(args.toString().length()<300);if(operation.optJSONObject("receipt")!=null&&operation.getJSONObject("receipt").has("projectExecution"))effects++;}assertEquals(9,effects);
                    System.out.println("S01_ACTUAL originalRunner_SQL_hostJNI=true; activeSourceFiles=244; largeExistingFileOver16k=true; boundedLiteralSearchAndRangedRead=true; immutablePlanRefNotSanitizedBody=true; realOwnerApprovalThenNewRunFreshReference=true; staleRefForeignEpochAndRevokedToolDenied=true; exactNativeCasPatch=true; actualNonzeroTestInSameTaskNextModel=true; conditionalUndoConflictPreservesNewerBytes=true; actualUndoThenRepairAndRealFinal45=true; Android_ARM_realProvider=false");return;
                }
                assertEquals("awaiting_user",outcome.status);
            }
            fail("native owner-approved continuation did not converge");
        }
    }
    private final class Model implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread worker;volatile Throwable failure;int phase,calls,emittedPhase=-99;String emitted="",lastProcessed="",emittedTool="",patchOne="",patchTwo="";JSONObject inspection,read,proposal,oldProposal,oldCursor;boolean expectContinuation,continuationRead,finished,sawRealNonzeroTest,sawRealPassingTest,sawUndoConflict,sawActualUndo,sawFinal45;int continuations;
        Model()throws Exception{server.bind(new InetSocketAddress("127.0.0.1",0));worker=new Thread(this::serve,"s01-original-model-fixture");worker.setDaemon(true);worker.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","fixture-not-real-key","fixture-precise-project",false,40,30).withStepCeiling(120);}
        JSONObject proposed(String intent)throws Exception{return new JSONObject().put("intent",intent).put("expectedSourceDigest",inspection.getString("sourceDigest"));}
        JSONObject executeArgs()throws Exception{return new JSONObject().put("planRef",proposal.getString("planRef")).put("planDigest",proposal.getString("planDigest")).put("stepId",proposal.getJSONArray("steps").getJSONObject(0).getString("id"));}
        JSONObject patch(String from,String to)throws Exception{return proposed("patch").put("edits",new JSONArray().put(new JSONObject().put("path","src/budget.mjs").put("expectedHash",read.getString("sha256")).put("replacements",new JSONArray().put(new JSONObject().put("oldText","amount = "+from).put("newText","amount = "+to)))));}
        void consume(JSONObject request)throws Exception {
            JSONArray history=request.getJSONArray("messages");JSONObject last=null;for(int i=0;i<history.length();i++)if("tool".equals(history.getJSONObject(i).optString("role")))last=history.getJSONObject(i);if(last==null||!emitted.equals(last.optString("tool_call_id"))||emitted.equals(lastProcessed))return;lastProcessed=emitted;JSONObject result=new JSONObject(last.getString("content"));Files.writeString(Path.of(System.getProperty("java.io.tmpdir")).resolve("s01-native-tool-"+calls+"-"+emittedPhase+".json"),result.toString(2));
            if("approval_required".equals(result.optString("code"))){assertEquals("project_execute",emittedTool);oldProposal=proposal;return;}
            if(emittedPhase==-1){JSONArray approved=result.getJSONArray("approvedPlanProposals");assertEquals(1,approved.length());JSONObject next=approved.getJSONObject(0);assertNotEquals(oldProposal.getString("planRef"),next.getString("planRef"));assertEquals(oldProposal.getString("planDigest"),next.getString("planDigest"));JSONObject old=executeArgs();int before=tasks.get(taskId,false).getJSONArray("operations").length();assertThrows(SecurityException.class,()->port.validate("project_execute",old));assertEquals(before,tasks.get(taskId,false).getJSONArray("operations").length());
                JSONObject fresh=new JSONObject().put("planRef",next.getString("planRef")).put("planDigest",next.getString("planDigest")).put("stepId",next.getString("stepId"));assertThrows(SecurityException.class,()->tasks.projectArguments(taskId,run,epoch+1,fresh));
                tasks.authorizeTools(taskId,run,new JSONArray());assertThrows(SecurityException.class,()->port.execute("project_execute",fresh,original));tasks.authorizeTools(taskId,run,names);
                proposal=new JSONObject(oldProposal.toString()).put("planRef",next.getString("planRef"));inspection=result;expectContinuation=false;continuationRead=false;continuations++;return;}
            if(emittedPhase==13){assertFalse(result.toString(),result.optBoolean("ok",true));assertTrue(result.toString(),result.toString().contains("撤销冲突"));assertTrue(Files.readString(active.resolve("src/budget.mjs")).endsWith("amount = 45;\n"));sawUndoConflict=true;phase++;return;}
            assertFalse("actual tool failure must not be concealed: "+result,result.has("error")&&!result.optBoolean("ok"));
            if(result.has("ecosystems"))inspection=result;if(result.has("sha256")&&result.has("content"))read=result;
            if(result.has("planRef")&&result.has("steps")){proposal=result;assertTrue(result.getBoolean("fullPlanStoredInOriginalTaskStore"));assertEquals(244,result.getInt("sourceFileCount"));if(phase==3){JSONObject full=tasks.projectArguments(taskId,run,epoch,executeArgs()).getJSONObject("plan");assertTrue(full.getJSONArray("sourceManifest").length()>200);assertThrows(IllegalArgumentException.class,()->port.validate("project_execute",new JSONObject().put("plan",full).put("stepId","patch")));}}
            if(result.has("updatedSourceDigest")){String current=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");assertEquals(current,result.getString("updatedSourceDigest"));inspection.put("sourceDigest",current);}
            if(phase==4){patchOne=result.getString("operationId");assertTrue(result.getBoolean("allFilesApplied"));assertTrue(Files.readString(active.resolve("src/budget.mjs")).endsWith("amount = 44;\n"));}
            if(phase==6||phase==17){assertTrue(result.toString(),result.getInt("exitCode")!=0);assertEquals("failed",result.getString("status"));assertTrue(result.toString(),result.getString("stdout").contains("45")||result.getString("stderr").contains("45"));sawRealNonzeroTest=true;assertTrue("real failed stdout/exit must be in the next original model request",request.toString().contains("exitCode"));}
            if(phase==10){patchTwo=result.getString("operationId");assertTrue(result.getBoolean("allFilesApplied"));}
            if(phase==12||phase==22){assertEquals(0,result.getInt("exitCode"));assertEquals("completed",result.getString("status"));sawRealPassingTest=true;}
            if(phase==15){assertTrue(result.getBoolean("allFilesApplied"));assertTrue(Files.readString(active.resolve("src/budget.mjs")).endsWith("amount = 44;\n"));sawActualUndo=true;}
            if(phase==24){assertEquals(0,result.getInt("exitCode"));assertTrue(result.getString("stdout"),result.getString("stdout").contains("AMOUNT=45.00"));sawFinal45=true;}
            phase++;
        }
        JSONObject next(JSONObject request)throws Exception {
            consume(request);String name;JSONObject args=new JSONObject();int marker=phase;
            if(expectContinuation){name="project_inspect";marker=-1;continuationRead=true;}
            else switch(phase){
                case 0:name="project_inspect";break;
                case 1:name="project_search";args.put("mode","text").put("query","amount = 40").put("include","src/**/*.mjs");break;
                case 2:case 8:case 18:name="project_read_file";args.put("path","src/budget.mjs").put("rangeMode","lines").put("startLine",2).put("maxLines",1);break;
                case 3:name="project_plan";args=patch("40","44");break;
                case 4:case 6:case 10:case 12:case 15:case 17:case 20:case 22:case 24:name="project_execute";args=executeArgs();break;
                case 5:case 11:case 16:case 21:name="project_plan";args=proposed("test").put("ecosystem","node");break;
                case 7:name="project_search";args.put("mode","text").put("query","amount = 44").put("include","src/**/*.mjs");break;
                case 9:case 19:name="project_plan";args=patch("44","45");break;
                case 13:name="project_plan";args=proposed("revert_operation").put("revertOperationId",patchOne);break;
                case 14:name="project_plan";args=proposed("revert_operation").put("revertOperationId",patchTwo);break;
                case 23:name="project_plan";args=proposed("run").put("ecosystem","node").put("target","main.mjs");break;
                default:finished=true;return new JSONObject().put("content","原项目搜索、精确修改、真实失败修复及有条件撤销已核对；最后原Node进程实际返回AMOUNT=45.00。主机fixture，不是手机或真实模型验收。");
            }
            emittedTool=name;emittedPhase=marker;emitted="s01-real-"+(++calls)+"-"+marker;return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
        }
        void serve(){while(!server.isClosed())try(Socket socket=server.accept()){socket.setSoTimeout(30000);InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int count=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))count=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(count<1||count>3*1024*1024)throw new IllegalArgumentException("bounded fixture model request");JSONObject message=next(new JSONObject(new String(input.readNBytes(count),StandardCharsets.UTF_8))).put("role","assistant");byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();}catch(Throwable error){if(!server.isClosed()){failure=error;try{server.close();}catch(Exception ignored){}}}}
        String line(InputStream input)throws Exception{ByteArrayOutputStream result=new ByteArrayOutputStream();for(int value;(value=input.read())>=0;){if(value=='\n')return result.toString(StandardCharsets.US_ASCII).replace("\r","");if(result.size()>8192)throw new IllegalArgumentException("fixture header limit");result.write(value);}throw new EOFException();}
        @Override public void close()throws Exception{server.close();worker.join(2000);if(failure!=null)throw new AssertionError("actual original model fixture",failure);}
    }
}
