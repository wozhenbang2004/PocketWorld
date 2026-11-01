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

/** Original SQLite/Runner/managed JNI. HTTP model and explicit owner decisions are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectCommandRunnerJourneyTest {
    private ProjectManagedRunnerJourneyTest factory;private LocalModule local;private TaskStore tasks;private RunControl original;private ProjectCapabilityPort port;private JSONObject task;private String taskId,checkout,project,run;private int epoch;private Path active;private JSONArray names;private final List<String> runs=new ArrayList<>();
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception{Field f=ProjectManagedRunnerJourneyTest.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(factory);}
    private Object call(String name,Class<?>[] types,Object... values)throws Exception{Method m=ProjectManagedRunnerJourneyTest.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(factory,values);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw (Error)failed.getCause();}}
    @Before public void create()throws Exception {
        factory=new ProjectManagedRunnerJourneyTest();factory.create();local=field("local");tasks=field("tasks");call("selectedProject",new Class<?>[]{String.class},"Actual exact command and retained interactive execution");
        call("file",new Class<?>[]{String.class,String.class},"scripts/amount.py","amount = 40\n");
        call("file",new Class<?>[]{String.class,String.class},"scripts/check.py","import os,sys\nfrom amount import amount\nassert sys.argv[1] == 'a b; $(literal-not-shell)'\nassert os.environ['APP_LABEL'] == 'ordinary-label'\nprint('CWD='+os.path.basename(os.getcwd()),flush=True)\nprint('AMOUNT='+format(amount,'.2f'),flush=True)\nif amount != 45:\n print('EXPECTED45 ACTUAL'+str(amount),file=sys.stderr,flush=True)\n sys.exit(13)\nprint('EXACT_COMMAND_OK',flush=True)\n");
        call("file",new Class<?>[]{String.class,String.class},"scripts/terminal.py","import os,sys,signal,time\nsignal.signal(signal.SIGTERM,lambda *_:sys.exit(0))\nprint('READY TTY='+str(os.isatty(0))+' '+str(os.isatty(1))+' '+str(os.isatty(2)),flush=True)\nprint('MERGED_STDERR',file=sys.stderr,flush=True)\nline=sys.stdin.readline().rstrip()\nwith open('result.txt','w',encoding='utf8') as f: f.write(line+'\\n')\nprint('VALUE='+line,flush=True)\nprint('SIZE='+str(os.get_terminal_size(0)),flush=True)\nprint('EOF_BYTES='+str(len(sys.stdin.read())),flush=True)\ntime.sleep(60)\n");
        call("startTask",new Class<?>[]{String.class},"Use the registered Python tool with exact argv/cwd/ordinary env; repair its real nonzero result. Then use one approved same-run PTY, inspect logs, resize and send ordinary Unicode text/EOF, stop the original session, modify the selected project, and restart. Do not replay an uncertain input or claim host fixture as Android/provider evidence.");taskId=field("taskId");checkout=field("checkout");project=field("project");active=(Path)call("active",new Class<?>[0]);
    }
    @SuppressWarnings("unchecked") private void attempt()throws Exception {
        task=(JSONObject)call("startFixtureAttempt",new Class<?>[0]);original=((AtomicReference<RunControl>)field("live")).get();run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");runs.add(run);var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);names=new JSONArray();JSONArray definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));assertTrue(names.toString().contains("project_process_input"));tasks.authorizeTools(taskId,run,names);
    }
    @After public void close()throws Exception{try{if(port!=null)port.close();}finally{if(factory!=null)factory.close();}}
    @Test public void originalRunnerRepairsExactCommandThenUsesApprovedPtyInputAndDrainsBeforeContinuation()throws Exception {
        try(Model model=new Model()){
            for(int tries=0;tries<14;tries++){
                attempt();CapabilityPort traced=(CapabilityPort)java.lang.reflect.Proxy.newProxyInstance(CapabilityPort.class.getClassLoader(),new Class<?>[]{CapabilityPort.class},(proxy,method,args)->{try{return method.invoke(port,args);}catch(InvocationTargetException failed){System.out.println("PTY_ORIGINAL_PORT_EXCEPTION method="+method.getName());failed.getCause().printStackTrace(System.out);throw failed.getCause();}});RunOutcome outcome=new AgentRunner(null,traced).run(taskId,task.getString("goal"),model.config(),original,event->{try{tasks.event(taskId,run,epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},tasks.journal(taskId,run,epoch,original));Files.writeString(Path.of(System.getProperty("java.io.tmpdir")).resolve("pty-original-ledger-attempt-"+tries+".json"),tasks.get(taskId,false).toString(2));
                System.out.println("PTY_ORIGINAL attempt="+tries+" phase="+model.phase+" status="+outcome.status+" summary="+outcome.summary+" fixtureFailure="+model.failure);if(model.failure!=null)throw new AssertionError("original HTTP fixture phase "+model.phase,model.failure);
                tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);port.close();port=null;assertFalse("input/launch uncertainty cannot be silently settled or replayed",tasks.hasUnresolved(taskId));
                boolean pending=false;JSONArray approvals=tasks.get(taskId,false).getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);if("pending".equals(approval.optString("state"))){assertEquals("project_execute",approval.getString("tool"));assertTrue(approval.getJSONObject("arguments").has("planRef"));tasks.decideCapabilityApproval(taskId,approval.getString("id"),true);model.expectContinuation=true;pending=true;}}
                if(!pending){assertTrue(outcome.summary,model.finished);assertEquals(28,model.phase);assertTrue(model.nonzero);assertTrue(model.repaired);assertTrue(model.nativeInputs);assertTrue(model.explicitStop);assertTrue(model.guardNegative);assertTrue(model.continuations>=5);assertEquals(2,model.launches.length());assertTrue(runs.size()>1);assertEquals("continued\n",Files.readString(active.resolve("scripts/result.txt")));assertEquals("amount = 45\n# changed only after original PTY release\n",Files.readString(active.resolve("scripts/amount.py")));assertFalse(original.isCancelled());
                    JSONObject exits=tasks.journal(taskId,run,epoch,original).projectServiceExitEvidence(new JSONArray().put(model.launches.getString(1)));assertNotNull(exits);assertTrue(exits.toString(),exits.getBoolean("allReleased"));JSONObject exit=exits.getJSONArray("executions").getJSONObject(0);assertTrue(exit.getBoolean("processExited"));assertTrue(exit.getBoolean("processGroupAbsent"));assertTrue(exit.getBoolean("resourcesReleased"));
                    JSONObject row=operation(model.launches.getString(1)),backend=row.getJSONObject("receipt").getJSONObject("executionReceipt");assertTrue(backend.toString(),backend.getBoolean("processSessionAbsent"));assertEquals("failed",backend.getString("status"));assertTrue(backend.getBoolean("cancelRequested"));assertEquals(0,backend.getInt("exitCode"));assertFalse("exit0 after stop must not become business completion",outcome.evidence.optBoolean("businessVerified"));assertNotEquals("reconciling",tasks.get(taskId,false).getString("status"));assertNull(((AtomicReference<Exception>)field("callbackFailure")).get());
                    System.out.println("PTY_ORIGINAL originalRunner_SQL_managedJNI=true; exactArgvCwdOrdinaryEnv=true; realExit13ThenSameTaskCASRepairAndExit0=true; actualApprovalNewRunFreshPlanRef=true; nativePtyStartAckNotReadiness=true; sameRunResizeUnicodeEofWithTypedLedger=true; inputByteAckNotBusinessComplete=true; unknownForeignEpochRevocationAndDirectWriteDenied=true; explicitStopSessionGroupLeaseThenEdit=true; secondLaunchNormalHostRunDrain=true; fixtureModel=true; Android_ARM_realProvider_TUIrenderer=false");return;
                }
                assertEquals("awaiting_user",outcome.status);
            }
            fail("original owner approval/new-run continuation did not converge");
        }
    }
    private JSONObject operation(String id)throws Exception{JSONArray rows=tasks.get(taskId,false).getJSONArray("operations");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).optString("operationId")))return rows.getJSONObject(i);throw new AssertionError("missing original operation "+id);}
    private String actualLog(JSONObject result)throws Exception{StringBuilder text=new StringBuilder();JSONArray chunks=result.getJSONArray("chunks");for(int i=0;i<chunks.length();i++){JSONObject chunk=chunks.getJSONObject(i);assertEquals(1,chunk.getInt("stream"));text.append(new String(Base64.getDecoder().decode(chunk.getString("dataBase64")),StandardCharsets.UTF_8));}return text.toString();}
    private void negativeGuards(String execution)throws Exception {
        JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))),command=new JSONObject().put("checkoutId",checkout).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("intent","command").put("expectedSourceDigest",source.getString("sourceDigest")).put("executable","not_registered").put("argv",new JSONArray());int priorOperations=tasks.get(taskId,false).getJSONArray("operations").length();JSONObject unavailable=ProjectBuildPlan.create(local,command);assertFalse(unavailable.getBoolean("available"));ProjectDevelopment.NotDispatched missing=assertThrows(ProjectDevelopment.NotDispatched.class,()->local.projectCapabilityForHost(ProjectDevelopment.exact(unavailable,ProjectBuildPlan.step(unavailable,"command"))));assertEquals("project_command_tool_unavailable",missing.receipt.getString("code"));JSONObject invalidCwd=ProjectBuildPlan.create(local,new JSONObject(command.toString()).put("executable","python3").put("cwd","missing-directory"));ProjectDevelopment.NotDispatched cwd=assertThrows(ProjectDevelopment.NotDispatched.class,()->local.projectCapabilityForHost(ProjectDevelopment.exact(invalidCwd,ProjectBuildPlan.step(invalidCwd,"command"))));assertEquals("project_command_cwd_unavailable",cwd.receipt.getString("code"));assertEquals(priorOperations,tasks.get(taskId,false).getJSONArray("operations").length());
        assertThrows(SecurityException.class,()->tasks.projectProcessInputAllowed(taskId,run,epoch+1,new JSONObject().put("executionId",execution).put("action","text").put("text","foreign")));
        assertThrows(SecurityException.class,()->tasks.projectProcessInputAllowed(taskId,run,epoch,new JSONObject().put("executionId","unrelated").put("action","text").put("text","foreign")));
        JSONArray readonly=new JSONArray();for(int i=0;i<names.length();i++)if(!"project_process_input".equals(names.getString(i)))readonly.put(names.getString(i));tasks.authorizeTools(taskId,run,readonly);try{assertThrows(SecurityException.class,()->port.beforeTool("project_process_input",new JSONObject().put("executionId",execution).put("action","text").put("text","revoked"),original));}finally{tasks.authorizeTools(taskId,run,names);}
        assertThrows(IllegalStateException.class,()->local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","scripts/amount.py").put("expectedHash",ProjectSources.sha256(Files.readAllBytes(active.resolve("scripts/amount.py")))).put("content","amount = 999\n")));
        synchronized(tasks){var journal=tasks.journal(taskId,run,epoch,original);String unclaimed=journal.beginOperation("project_process_input",new JSONObject().put("executionId",execution).put("action","text").put("text","NOT_NATIVE_DELIVERY"));journal.finishOperation(unclaimed,new JSONObject().put("code","project_input_delivered").put("status","observed").put("ok",true).put("accepted",true).put("nativeInputSettled",true));JSONObject rejected=operation(unclaimed);assertEquals("receipt",rejected.getString("state"));assertEquals("project_input_not_dispatched",rejected.getJSONObject("receipt").getString("code"));assertFalse(rejected.getJSONObject("receipt").getBoolean("ok"));assertEquals("original_prepared_input_not_claimed",rejected.getJSONObject("receipt").getString("reason"));}
        synchronized(tasks){Field f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)f.get(tasks);String unknown=UUID.randomUUID().toString();var values=new android.content.ContentValues();values.put("operation_id",unknown);values.put("task_id",taskId);values.put("run_id",run);values.put("tool","controlled_unrelated_effect");values.put("arguments","{}");values.put("state","unknown");values.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,values);try{assertThrows(IllegalStateException.class,()->tasks.journal(taskId,run,epoch,original).restore());}finally{db.delete("operations","operation_id=?",new String[]{unknown});}}
        tasks.journal(taskId,run,epoch,original).restore();
    }
    private final class Model implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread worker;volatile Throwable failure;int phase,calls,emittedPhase=-99,continuations,logRetries;String emitted="",processed="",emittedTool="",execution="";JSONObject inspection,read,proposal,oldProposal;boolean expectContinuation,finished,nonzero,repaired,nativeInputs,explicitStop,guardNegative;final JSONArray launches=new JSONArray();
        Model()throws Exception{server.bind(new InetSocketAddress("127.0.0.1",0));worker=new Thread(this::serve,"c2-original-command-model");worker.setDaemon(true);worker.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","fixture-not-real-key","fixture-command-pty",false,40,30).withStepCeiling(120);}
        JSONObject plan(String intent)throws Exception{return new JSONObject().put("intent",intent).put("expectedSourceDigest",inspection.getString("sourceDigest"));}
        JSONObject command(boolean pty)throws Exception{return plan("command").put("executable","python3").put("argv",pty?new JSONArray().put("-u").put("terminal.py"):new JSONArray().put("check.py").put("a b; $(literal-not-shell)")).put("cwd","scripts").put("env",new JSONObject().put("APP_LABEL","ordinary-label")).put("timeoutMillis",15000).put("terminal",pty?"pty":"pipes").put("runMode",pty?"owned_service":"foreground");}
        JSONObject execute()throws Exception{return new JSONObject().put("planRef",proposal.getString("planRef")).put("planDigest",proposal.getString("planDigest")).put("stepId",proposal.getJSONArray("steps").getJSONObject(0).getString("id"));}
        void consume(JSONObject request)throws Exception {
            JSONObject last=null;JSONArray history=request.getJSONArray("messages");for(int i=0;i<history.length();i++)if("tool".equals(history.getJSONObject(i).optString("role")))last=history.getJSONObject(i);if(last==null||!emitted.equals(last.optString("tool_call_id"))||emitted.equals(processed))return;processed=emitted;JSONObject result=new JSONObject(last.getString("content"));Files.writeString(Path.of(System.getProperty("java.io.tmpdir")).resolve("pty-original-tool-"+calls+"-"+emittedPhase+".json"),result.toString(2));
            if("approval_required".equals(result.optString("code"))){assertEquals("project_execute",emittedTool);oldProposal=proposal;return;}
            if(emittedPhase==-1){JSONArray approved=result.getJSONArray("approvedPlanProposals");assertEquals(1,approved.length());JSONObject next=approved.getJSONObject(0);assertNotEquals(oldProposal.getString("planRef"),next.getString("planRef"));assertEquals(oldProposal.getString("planDigest"),next.getString("planDigest"));assertThrows(SecurityException.class,()->port.validate("project_execute",execute()));proposal=new JSONObject(oldProposal.toString()).put("planRef",next.getString("planRef"));inspection=result;expectContinuation=false;continuations++;return;}
            assertFalse("tool exception cannot be replaced by a later fixture NPE: "+result,result.has("error")&&!result.optBoolean("ok"));
            if(result.has("ecosystems"))inspection=result;if(result.has("sha256")&&result.has("content"))read=result;if(result.has("planRef")&&result.has("steps"))proposal=result;if(result.has("updatedSourceDigest"))inspection.put("sourceDigest",result.getString("updatedSourceDigest"));
            switch(phase){
                case 2:assertEquals(13,result.getInt("exitCode"));assertEquals("failed",result.getString("status"));assertTrue(result.getString("stdout").contains("CWD=scripts"));assertTrue(result.getString("stderr").contains("EXPECTED45 ACTUAL40"));assertTrue(request.toString().contains("EXPECTED45 ACTUAL40"));nonzero=true;break;
                case 8:assertEquals(0,result.getInt("exitCode"));assertTrue(result.getString("stdout").contains("AMOUNT=45.00"));assertTrue(result.getString("stdout").contains("EXACT_COMMAND_OK"));repaired=true;break;
                case 10:case 24:assertEquals("project_running",result.getString("code"));assertFalse(result.getBoolean("terminal"));assertFalse(result.getBoolean("receiptReceived"));execution=result.getString("executionId");launches.put(execution);if(phase==10){negativeGuards(execution);guardNegative=true;}break;
                case 11:case 14:case 17:case 26:{String log=actualLog(result),expected=phase==11?"READY TTY=True True True":phase==14?"VALUE=invoice 45 😀":phase==17?"EOF_BYTES=0":"VALUE=continued";if(!log.contains(expected)){assertTrue("real PTY output never reached "+expected+": "+log,++logRetries<=30);return;}logRetries=0;if(phase==14){assertTrue(log,log.contains("columns=101, lines=32"));assertTrue(log.contains("MERGED_STDERR"));}break;}
                case 12:case 13:case 16:case 25:assertEquals("project_input_delivered",result.getString("code"));assertTrue(result.getBoolean("nativeInputSettled"));assertFalse(result.getBoolean("processTerminal"));JSONObject io=result.getJSONObject("inputDelivery");assertTrue(io.getBoolean("nativeAcknowledged"));assertFalse(io.getBoolean("inputTextIncluded"));assertFalse(io.has("text"));assertEquals("receipt",operation(io.getString("inputOperationId")).getString("state"));nativeInputs=true;break;
                case 15:assertEquals("project_input_observation",result.getString("code"));assertTrue(result.getBoolean("timedOut"));assertFalse(result.getBoolean("consumed"));break;
                case 18:assertEquals("project_stop_confirmed",result.getString("code"));assertTrue(result.getBoolean("resourcesReleased"));assertTrue(operation(execution).getJSONObject("receipt").getJSONObject("executionReceipt").getBoolean("processSessionAbsent"));assertFalse(original.isCancelled());explicitStop=true;break;
            }
            phase++;
        }
        JSONObject next(JSONObject request)throws Exception {
            consume(request);String name;JSONObject args=new JSONObject();int marker=phase;
            if(expectContinuation){name="project_inspect";marker=-1;}
            else switch(phase){
                case 0:case 6:case 19:case 27:name="project_inspect";break;
                case 1:case 7:name="project_plan";args=command(false);break;
                case 2:case 5:case 8:case 10:case 22:case 24:name="project_execute";args=execute();break;
                case 3:case 20:name="project_read_file";args.put("path","scripts/amount.py");break;
                case 4:case 21:name="project_plan";args=plan("edit").put("path","scripts/amount.py").put("expectedHash",read.getString("sha256")).put("content",phase==4?"amount = 45\n":"amount = 45\n# changed only after original PTY release\n");break;
                case 9:case 23:name="project_plan";args=command(true).put("inputByteLimit",100);break;
                case 11:case 14:case 17:case 26:name="project_log";args.put("executionId",execution).put("cursor",0);break;
                case 12:name="project_process_input";args.put("executionId",execution).put("action","resize").put("rows",32).put("columns",101);break;
                case 13:case 25:name="project_process_input";args.put("executionId",execution).put("action","text").put("text",phase==13?"invoice 45 😀\n":"continued\n");break;
                case 15:name="project_wait_input";args.put("executionId",execution).put("maxSeconds",1);break;
                case 16:name="project_process_input";args.put("executionId",execution).put("action","eof");break;
                case 18:name="project_stop";args.put("executionId",execution);break;
                default:finished=true;return new JSONObject().put("content","已核对精确命令与真实失败修复，原PTY输入及结果。第二个交互终端仍在运行；本句发生于原HostRun收尾前，最终状态以原退出和资源回执为准。");
            }
            emittedTool=name;emittedPhase=marker;emitted="pty-original-"+(++calls)+"-"+marker;return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
        }
        void serve(){while(!server.isClosed())try(Socket socket=server.accept()){socket.setSoTimeout(30000);InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int count=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))count=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(count<1||count>3*1024*1024)throw new IllegalArgumentException("bounded fixture request");JSONObject response=next(new JSONObject(new String(input.readNBytes(count),StandardCharsets.UTF_8))).put("role","assistant");byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",response).put("finish_reason",response.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();}catch(Throwable error){if(!server.isClosed()){failure=error;try{server.close();}catch(Exception ignored){}}}}
        String line(InputStream input)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int value;(value=input.read())>=0;){if(value=='\n')return bytes.toString(StandardCharsets.US_ASCII).replace("\r","");if(bytes.size()>8192)throw new IllegalArgumentException("fixture header limit");bytes.write(value);}throw new EOFException();}
        public void close()throws Exception{server.close();worker.join(2000);if(failure!=null)throw new AssertionError("actual original HTTP model fixture",failure);}
    }
}
