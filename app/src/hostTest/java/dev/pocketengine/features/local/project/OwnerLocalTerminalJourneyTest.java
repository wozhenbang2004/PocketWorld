package dev.pocketengine.features.local.project;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Real TaskStore and original JNI PTY fixture. No model, transport substitute or fake process ACK. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class OwnerLocalTerminalJourneyTest {
    private ProjectManagedRunnerJourneyTest factory;private TaskStore tasks;private LocalModule local;private TaskStoreProjectHost host;private String project,checkout,id;private final AtomicReference<RunControl> control=new AtomicReference<>();
    private Object invoke(String name,Class<?>[] types,Object...args)throws Exception {var method=factory.getClass().getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(factory,args);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception)throw (Exception)error.getCause();throw error;}}
    @SuppressWarnings("unchecked")private <T>T field(String name)throws Exception {var field=factory.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(factory);}
    @Before public void setup()throws Exception {
        factory=new ProjectManagedRunnerJourneyTest();factory.createTerminalFixture();invoke("selectedProject",new Class<?>[]{String.class},"Manual shell original fixture");invoke("file",new Class<?>[]{String.class,String.class},"README.md","Original local manual terminal fixture.\n");tasks=field("tasks");local=field("local");project=field("project");checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
        host=new TaskStoreProjectHost(tasks,local,task->task.equals(id)?control.get():null,event->fail("Manual review must already match exact original launcher capability"));local.setProjectHost(host);local.setProjectReceiptSink(receipt->{try{host.recordBackendReceipt(receipt);}catch(Exception failure){throw new IllegalStateException(failure);}});
    }
    @After public void close()throws Exception {if(control.get()!=null)control.get().cancel();if(factory!=null)factory.close();}
    private OwnerLocalTerminal.Review review()throws Exception {return OwnerLocalTerminal.review(local,new JSONObject().put("projectId",project).put("checkoutId",checkout).put("expectedSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("runtimeId","managed_process_v1").put("cwd",".").put("env",new JSONObject()).put("timeoutMillis",20000).put("columns",80).put("rows",24).put("inputByteLimit",4096));}
    private JSONObject binding()throws Exception{return new JSONObject().put("taskId",id).put("runId",id).put("coordinatorEpoch",1).put("operationId",id);}
    private void dispatch(OwnerLocalTerminal.Confirmed confirmed)throws Exception {id=confirmed.requestId();tasks.beginOwnerLocalTerminal(confirmed);RunControl original=new RunControl();original.bindHostRun(id,id,1);original.setAuthorityCheck(()->tasks.requireOwnerLocalTerminalCurrent(id,original));control.set(original);JSONObject plan=confirmed.plan();host.dispatch(binding(),ProjectDevelopment.exact(plan,OwnerLocalTerminal.step(plan)));}
    @Test public void noModelManualShellHasOneLaunchOriginalInputCloseAndReopen()throws Exception {
        var review=review();var confirmed=review.confirm();dispatch(confirmed);JSONObject task=tasks.get(id,false);assertEquals("tool",task.getString("kind"));assertEquals("OWNER_LOCAL_TERMINAL",task.getString("nativeExecutionMode"));assertFalse(task.has("conversationId"));assertEquals("project_running",tasks.ownerLocalTerminalOriginalForOwner(id).getJSONObject("operation").getString("state"));
        assertTrue(tasks.beginOwnerLocalTerminal(confirmed).getBoolean("knownOperation"));JSONObject plan=confirmed.plan();host.dispatch(binding(),ProjectDevelopment.exact(plan,OwnerLocalTerminal.step(plan)));assertEquals(1,tasks.get(id,false).getJSONArray("operations").length());
        var observer=new RunControl();var view=host.ownerTerminals().open(id,id,observer);view.readScreen();JSONObject sent=view.input(new JSONObject().put("action","text").put("text","printf 'MANUAL_ORIGINAL_OK\\n'\n"));assertTrue(sent.toString(),sent.getBoolean("nativeInputSettled"));assertTrue(sent.getBoolean("accepted"));JSONObject resized=view.input(new JSONObject().put("action","resize").put("columns",96).put("rows",30));assertTrue(resized.toString(),resized.getBoolean("accepted"));
        String output="";long until=System.nanoTime()+5_000_000_000L;while(!output.contains("MANUAL_ORIGINAL_OK")&&System.nanoTime()<until){JSONArray packets=view.readScreen().getJSONObject("screen").getJSONArray("packets");var bytes=new java.io.ByteArrayOutputStream();for(int i=0;i<packets.length();i++)bytes.write(Base64.getDecoder().decode(packets.getString(i)));output=bytes.toString(StandardCharsets.UTF_8);if(!output.contains("MANUAL_ORIGINAL_OK"))Thread.sleep(30);}assertTrue(output,output.contains("MANUAL_ORIGINAL_OK"));
        view.close();assertTrue(observer.isCancelled());assertFalse(control.get().isCancelled());try(var reopened=host.ownerTerminals().open(id,id,new RunControl())){assertEquals(96,reopened.metadata().getInt("columns"));assertEquals(30,reopened.readScreen().getJSONObject("screen").getInt("rows"));reopened.stop();}
        until=System.nanoTime()+5_000_000_000L;while(!"receipt".equals(tasks.ownerLocalTerminalOriginalForOwner(id).getJSONObject("operation").getString("state"))&&System.nanoTime()<until){host.operation(binding());Thread.sleep(30);}JSONObject ended=tasks.ownerLocalTerminalOriginalForOwner(id).getJSONObject("operation");assertEquals(ended.toString(),"receipt",ended.getString("state"));assertTrue(ended.getJSONObject("receipt").getJSONObject("executionReceipt").getBoolean("resourcesReleased"));assertFalse(tasks.hasUnresolved(id));
    }
    @Test public void nativeManualGrantHasNoInferenceOrGenericRestartAuthority()throws Exception {
        var confirmed=review().confirm();id=confirmed.requestId();tasks.beginOwnerLocalTerminal(confirmed);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);try(var row=db.rawQuery("SELECT grant_json FROM runs WHERE task_id=? AND run_id=? AND epoch=1",new String[]{id,id})){assertTrue(row.moveToFirst());JSONObject grant=new JSONObject(row.getString(0));assertEquals(0,grant.getJSONArray("allowedInferencePurposes").length());assertEquals(129,grant.getInt("remainingOperations"));assertEquals(id,grant.getString("nativeOwnerTerminalRequestId"));}
        assertThrows(SecurityException.class,()->tasks.startAttempt(id,"capabilities",100));assertThrows(SecurityException.class,()->tasks.resolveOperation(id,id,"not_occurred"));
        tasks.ownerLocalTerminalPreflightRejected(id,new JSONObject().put("code","fixture_preflight_refused"));assertEquals("failed",tasks.get(id,false).getString("status"));assertTrue(tasks.beginOwnerLocalTerminal(confirmed).getBoolean("knownOperation"));assertEquals(1,tasks.get(id,false).getJSONArray("operations").length());
    }
    @Test public void closedOrChangedNativeReviewNeverCreatesAReplacementLaunch()throws Exception {
        var closed=review();String request=closed.view().getString("requestId");closed.close();assertThrows(SecurityException.class,closed::confirm);assertNull(tasks.get(request,false));
        var changed=review();JSONObject original=local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","README.md"));local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","README.md").put("expectedHash",original.getString("sha256")).put("content","Changed after native owner review.\n"));assertThrows(SecurityException.class,changed::confirm);assertNull(tasks.get(changed.view().getString("requestId"),false));
    }
}
