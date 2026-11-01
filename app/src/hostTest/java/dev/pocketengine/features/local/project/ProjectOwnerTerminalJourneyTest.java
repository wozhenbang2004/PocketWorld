package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Actual original approved host JNI PTY, not an Android/WebView rendering claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectOwnerTerminalJourneyTest {
    private ProjectManagedRunnerJourneyTest factory;private TaskStore tasks;private LocalModule local;private ProjectCapabilityPort port;private RunControl original;private RunJournal journal;private String task,run,project,checkout;private int epoch;
    private Object invoke(String method,Class<?>[] types,Object...args)throws Exception{var selected=factory.getClass().getDeclaredMethod(method,types);selected.setAccessible(true);try{return selected.invoke(factory,args);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception)throw (Exception)error.getCause();throw error;}}
    @SuppressWarnings("unchecked")private <T>T field(String name)throws Exception{var selected=factory.getClass().getDeclaredField(name);selected.setAccessible(true);return (T)selected.get(factory);}
    @Before public void setup()throws Exception{
        factory=new ProjectManagedRunnerJourneyTest();factory.create();invoke("selectedProject",new Class<?>[]{String.class},"Own native terminal");invoke("file",new Class<?>[]{String.class,String.class},"README.md","Own native PTY fixture; no external service or repository.\n");invoke("startTask",new Class<?>[]{String.class},"Use one exact bounded native terminal, preserving original run/input journal");JSONObject admitted=(JSONObject)invoke("startFixtureAttempt",new Class<?>[0]);tasks=field("tasks");local=field("local");task=field("taskId");checkout=field("checkout");project=field("project");run=admitted.getString("runId");epoch=admitted.getInt("coordinatorEpoch");original=this.<AtomicReference<RunControl>>field("live").get();var binding=new ModelCapabilityAdapters.Binding(task,run,epoch,original,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray tools=new JSONArray(),available=port.tools();for(int i=0;i<available.length();i++)tools.put(available.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(task,run,tools);journal=tasks.journal(task,run,epoch,original);
    }
    private JSONObject tool(String name,JSONObject args)throws Exception{String operation=port.sideEffecting(name)?journal.beginOperation(name,args):null;JSONObject value=new JSONObject(args.toString());if(operation!=null)value.put("_hostOperationId",operation);JSONObject actual=port.execute(name,value,original);if(operation!=null)journal.finishOperation(operation,actual);return actual;}
    @After public void close()throws Exception{try{if(port!=null)port.close();}finally{if(factory!=null)factory.close();}}
    @Test public void ownerTerminalReadsActualAnsiAndInputResizeAckUsesSameOriginalRunWhileClosingViewDoesNotKillIt()throws Exception{
        String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");String program="process.stdout.write('\\x1b[2J\\x1b[HREADY\\r\\n');process.stdin.setEncoding('utf8');process.stdin.on('data',s=>process.stdout.write('ECHO:'+s));setInterval(()=>{},1000);";
        JSONObject ref=tool("project_plan",new JSONObject().put("intent","command").put("executable","node").put("argv",new JSONArray().put("-e").put(program)).put("cwd",".").put("runMode","owned_service").put("terminal","pty").put("inputByteLimit",4096).put("timeoutMillis",20000).put("columns",80).put("rows",24).put("expectedSourceDigest",digest)),args=new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId","command"),launch=tool("project_execute",args);
        assertEquals(launch.toString(),"approval_required",launch.getString("code"));tasks.decideCapabilityApproval(task,launch.getString("approvalId"),true);launch=tool("project_execute",args);assertEquals(launch.toString(),"project_running",launch.getString("code"));String execution=launch.getString("operationId");assertTrue(journal.hasNativeProjectRunningAck(execution,launch));var host=this.<TaskStoreProjectHost>field("host");var observer=new RunControl();var session=host.ownerTerminals().open(task,execution,observer);
        JSONObject meta=session.metadata();assertEquals(run,meta.getString("runId"));assertEquals(epoch,meta.getInt("epoch"));assertFalse(meta.getBoolean("secretInputSupported"));StringBuilder bytes=new StringBuilder();long cursor=0,until=System.nanoTime()+5_000_000_000L;
        while(!bytes.toString().contains("READY")&&System.nanoTime()<until){JSONObject page=session.read(cursor);for(int i=0;i<page.getJSONArray("chunks").length();i++)bytes.append(new String(Base64.getDecoder().decode(page.getJSONArray("chunks").getJSONObject(i).getString("dataBase64")),StandardCharsets.UTF_8));cursor=page.getLong("nextCursor");if(!bytes.toString().contains("READY"))Thread.sleep(30);}
        assertTrue(bytes.toString(),bytes.toString().contains("\u001b[2J\u001b[HREADY"));int before=tasks.get(task,false).getJSONArray("operations").length();JSONObject sent=session.input(new JSONObject().put("action","text").put("text","原终端\n"));assertTrue(sent.toString(),sent.getBoolean("nativeInputSettled"));assertEquals("project_input_delivered",sent.getString("code"));assertEquals(before+1,tasks.get(task,false).getJSONArray("operations").length());assertTrue(session.originalInput().getBoolean("nativeInputSettled"));JSONObject resized=session.input(new JSONObject().put("action","resize").put("columns",96).put("rows",30));assertEquals(resized.toString(),"project_input_delivered",resized.getString("code"));assertFalse(original.isCancelled());assertEquals(run,tasks.get(task,false).getString("runId"));
        until=System.nanoTime()+5_000_000_000L;while(!bytes.toString().contains("ECHO:原终端")&&System.nanoTime()<until){JSONObject page=session.read(cursor);for(int i=0;i<page.getJSONArray("chunks").length();i++)bytes.append(new String(Base64.getDecoder().decode(page.getJSONArray("chunks").getJSONObject(i).getString("dataBase64")),StandardCharsets.UTF_8));cursor=page.getLong("nextCursor");Thread.sleep(30);}assertTrue(bytes.toString(),bytes.toString().contains("ECHO:原终端"));session.close();assertTrue(observer.isCancelled());assertFalse(original.isCancelled());assertThrows(IllegalStateException.class,()->session.input(new JSONObject().put("action","text").put("text","must-not-send\n")));JSONObject running=tool("project_status",new JSONObject().put("executionId",execution));assertEquals("running",running.getString("serviceStatus"));assertFalse(running.getBoolean("resourcesReleased"));
        try(var reopened=host.ownerTerminals().open(task,execution,new RunControl())){JSONObject stopped=reopened.stop();assertFalse(original.isCancelled());assertNotNull(stopped);}
        port.close();assertNotNull(journal.projectServiceExitEvidence(new JSONArray().put(execution)));assertFalse(tasks.hasUnresolved(task));System.out.println("C2_OWNER_TERMINAL actual PTY ANSI/UTF8 + same-run prepared input and native resize ACK; view close does not cancel original; original stop/group/lease drain. WebView/native VT pixels not claimed.");
    }
}
