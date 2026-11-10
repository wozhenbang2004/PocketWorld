package dev.pocketengine.phone.display;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Transport is an explicit fixture. Actual BackgroundPhonePort ownership/reference gates run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=dev.pocketengine.EngineApplication.class,shadows={BackgroundOwnerHandoffTest.DisplayFixture.class,BackgroundOwnerHandoffTest.NoStartup.class})
public final class BackgroundOwnerHandoffTest {
    @Implements(dev.pocketengine.EngineApplication.class) public static class NoStartup extends org.robolectric.shadows.ShadowApplication { @Implementation protected void onCreate(){} }

    @Implements(DisplayModule.class) public static class DisplayFixture {
        String state="none",nonce="native-plan";int calls,plans;
        static String expectedTask="original-task";static boolean loseNextMain,failBeforeMain,staleBeforeMain;
        @Implementation protected void __constructor__(FeatureContext ignored){}
        @Implementation protected void __constructor__(FeatureContext ignored,DisplayModule.AdbFactory factory,DisplayModule.Configuration configuration,DisplayModule.StatusProbe probe){}
        @Implementation protected void __constructor__(FeatureContext ignored,DisplayModule.AdbFactory factory,DisplayModule.Configuration configuration,DisplayModule.StatusProbe probe,DisplayModule.Exchange exchange){}
        @Implementation protected boolean hasSession(){return true;}
        @Implementation protected boolean hasSession(String sessionId,int displayId){return "original-session".equals(sessionId)&&displayId==9;}
        @Implementation protected boolean isConnected(){return true;}
        @Implementation protected boolean ownedBy(String task){return task.equals(expectedTask);}
        @Implementation protected boolean ownedBy(String task,String sessionId,int displayId){return ownedBy(task)&&hasSession(sessionId,displayId);}
        @Implementation protected JSONObject session()throws Exception{return new JSONObject().put("sessionId","original-session").put("displayId",9).put("sessionActive",true);}
        @Implementation protected JSONObject session(String sessionId,int displayId)throws Exception {
            if(!hasSession(sessionId,displayId))throw new SecurityException("fixture session changed");
            return session();
        }
        @Implementation protected JSONObject refreshSession(String sessionId,int displayId,RunControl control,long revision,boolean owner)throws Exception {
            control.check();if(control.pauseRevision()!=revision||control.isPaused()!=owner)throw new ShellBridgeClient.StaleRequestException();
            return session(sessionId,displayId);
        }
        @Implementation protected JSONObject request(String command,JSONObject payload,RunControl control,long revision,boolean owner)throws Exception {
            assertTrue(owner);assertTrue(control.isPaused());assertEquals(control.pauseRevision(),revision);control.check();calls++;
            session(payload.getString("sessionId"),payload.getInt("displayId"));
            if(command.equals("owner_handoff_main")&&staleBeforeMain){staleBeforeMain=false;throw new ShellBridgeClient.StaleRequestException();}
            if(command.equals("owner_handoff_main")&&failBeforeMain){failBeforeMain=false;throw new java.io.IOException("transport unknown before fixture received request");}
            if(command.equals("owner_handoff_prepare")){state="prepared";plans++;nonce=plans==1?"native-plan":"native-plan-"+plans;}
            else if(command.equals("owner_handoff_main"))state="main";
            else if(command.equals("owner_handoff_return"))state="returned";
            else assertEquals("owner_handoff_status",command);
            if(command.equals("owner_handoff_main")&&loseNextMain){loseNextMain=false;return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("state","unknown").put("code","owner_handoff_unknown").put("handoffId",nonce).put("sessionId","original-session").put("displayId",9);}
            return new JSONObject().put("notDispatched",command.equals("owner_handoff_status")&&state.equals("prepared")).put("planRevoked",command.equals("owner_handoff_status")&&state.equals("prepared")).put("ok",true).put("state",state).put("handoffId",nonce).put("sessionId","original-session").put("displayId",9);
        }
    }
    @Test public void originalPausedOwnerCanHandOutButNoNewFrameOrManualInputLeaksToMain()throws Exception {
        DisplayFixture.expectedTask="original-task";DisplayFixture.loseNextMain=false;DisplayModule module=new DisplayModule(null);BackgroundPhonePort port=new BackgroundPhonePort(module);RunControl owner=new RunControl();owner.bindHostRun("original-task","original-run",1);
        assertThrows(SecurityException.class,()->port.ownerHandoff("original-task","prepare","",owner));owner.pause();port.ownerHandoff("original-task","prepare","",owner);assertFalse(port.ownerHandoffActive());port.ownerHandoff("original-task","main","native-plan",owner);assertTrue(port.ownerHandoffActive());
        JSONObject observed=port.observeForOwner("original-task",owner);assertFalse(observed.getBoolean("manualAllowed"));assertTrue(observed.getBoolean("referencesExpired"));assertFalse(observed.has("observationId"));assertFalse(observed.has("image"));assertEquals("main",observed.getJSONObject("ownerHandoff").getString("state"));
        assertTrue(port.ownerHandoff("different-task","return","native-plan",owner).getBoolean("notDispatched"));port.ownerHandoff("original-task","return","native-plan",owner);assertFalse(port.ownerHandoffActive());assertEquals("original-session",port.executionIdentity().getString("sessionId"));assertEquals(9,port.executionIdentity().getInt("displayId"));assertTrue(owner.matchesHostRun("original-task","original-run",1));assertTrue(owner.isPaused());
        owner.cancel();assertTrue(port.ownerHandoff("original-task","main","native-plan",owner).getBoolean("notDispatched"));
    }
    @Test public void actualOwnerHostJournalsSameRunAndDoesNotResumeWithoutFreshReturnedFrame()throws Exception {
        dev.pocketengine.EngineApplication app=(dev.pocketengine.EngineApplication)RuntimeEnvironment.getApplication();
        dev.pocketengine.store.TaskStore store=new dev.pocketengine.store.TaskStore(app);app.tasks=store;dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            String id=store.create("亲自操作原应用再交还","background").getString("id");JSONObject task=store.startAttempt(id,"background",10);RunControl original=new RunControl();original.bindHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch"));original.pause();store.status(id,"paused","owner fixture");DisplayFixture.expectedTask=id;
            BackgroundPhonePort port=new BackgroundPhonePort(new DisplayModule(null));org.robolectric.util.ReflectionHelpers.setField(app,"activeTaskId",id);org.robolectric.util.ReflectionHelpers.setField(app,"activeControl",original);org.robolectric.util.ReflectionHelpers.setField(app,"activePort",port);
            JSONObject plan=worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","prepare"))).get();assertEquals("prepared",plan.getJSONObject("receipt").getString("state"));
            DisplayFixture.loseNextMain=true;JSONObject main=worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","main").put("handoffId","native-plan"))).get();assertEquals("unknown",main.getJSONObject("receipt").getString("state"));assertTrue(store.hasUnresolved(id));assertFalse(main.getBoolean("manualAllowed"));JSONObject checked=worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","status"))).get();assertEquals("main",checked.getJSONObject("receipt").getString("state"));assertFalse(store.hasUnresolved(id));assertEquals(1,store.get(id,false).getJSONArray("operations").length());assertThrows(SecurityException.class,()->app.continueTask(id,"",true,"background"));
            JSONObject back=worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","return").put("handoffId","native-plan"))).get();assertEquals("returned",back.getJSONObject("receipt").getString("state"));assertFalse(back.getBoolean("manualAllowed"));assertFalse(back.getJSONObject("observation").optBoolean("ok"));assertTrue(original.isPaused());assertFalse(port.ownerHandoffActive());assertEquals(task.getString("runId"),store.get(id,false).getString("runId"));assertEquals(2,store.get(id,false).getJSONArray("operations").length());assertFalse(store.hasUnresolved(id));
        } finally {worker.shutdownNow();java.lang.reflect.Field db=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(store)).close();}
    }
    @Test public void unknownTransportWithUnexecutedPreparedPlanCanReconcileWithoutReplay()throws Exception {
        dev.pocketengine.EngineApplication app=(dev.pocketengine.EngineApplication)RuntimeEnvironment.getApplication();dev.pocketengine.store.TaskStore store=new dev.pocketengine.store.TaskStore(app);app.tasks=store;dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{String id=store.create("移屏未发出后核对","background").getString("id");JSONObject task=store.startAttempt(id,"background",10);RunControl original=new RunControl();original.bindHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch"));original.pause();DisplayFixture.expectedTask=id;DisplayFixture.loseNextMain=false;BackgroundPhonePort port=new BackgroundPhonePort(new DisplayModule(null));org.robolectric.util.ReflectionHelpers.setField(app,"activeTaskId",id);org.robolectric.util.ReflectionHelpers.setField(app,"activeControl",original);org.robolectric.util.ReflectionHelpers.setField(app,"activePort",port);
            worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","prepare"))).get();DisplayFixture.failBeforeMain=true;
            JSONObject result=worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","main").put("handoffId","native-plan"))).get();assertTrue(result.getJSONObject("receipt").isNull("accepted"));assertTrue(store.hasUnresolved(id));
            JSONObject checked=worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","status"))).get();assertEquals("prepared",checked.getJSONObject("receipt").getString("state"));assertFalse(store.hasUnresolved(id));JSONObject operation=store.get(id,false).getJSONArray("operations").getJSONObject(0);assertFalse(operation.getJSONObject("receipt").getBoolean("accepted"));assertTrue(operation.getJSONObject("receipt").getBoolean("notDispatched"));assertEquals(1,store.get(id,false).getJSONArray("operations").length());assertTrue(original.isPaused());
            assertThrows(java.util.concurrent.ExecutionException.class,()->worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","main").put("handoffId","native-plan"))).get());
            JSONObject refreshed=worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","prepare"))).get();String newPin=refreshed.getJSONObject("receipt").getString("handoffId");assertNotEquals("native-plan",newPin);JSONObject retried=worker.submit(()->app.dispatchOwnerAction("ownerPhoneHandoff",new JSONObject().put("taskId",id).put("handoffAction","main").put("handoffId",newPin))).get();assertEquals("main",retried.getJSONObject("receipt").getString("state"));assertEquals(2,store.get(id,false).getJSONArray("operations").length());assertFalse(store.hasUnresolved(id));
        }finally{worker.shutdownNow();java.lang.reflect.Field db=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(store)).close();}
    }
    @Test public void stalePreDispatchReceiptIsDefiniteAndRetainsPreparedTarget()throws Exception {DisplayFixture.expectedTask="original-task";DisplayFixture.loseNextMain=false;DisplayFixture.failBeforeMain=false;DisplayModule module=new DisplayModule(null);BackgroundPhonePort port=new BackgroundPhonePort(module);RunControl original=new RunControl();original.pause();port.ownerHandoff("original-task","prepare","",original);DisplayFixture.staleBeforeMain=true;JSONObject refused=port.ownerHandoff("original-task","main","native-plan",original);assertFalse(refused.getBoolean("accepted"));assertTrue(refused.getBoolean("notDispatched"));assertEquals("prepared",port.ownerHandoffState().getString("state"));assertFalse(port.ownerHandoffActive());}
    @Test public void realTransportStaleGateReportsNoWriteRatherThanUnknown()throws Exception {RunControl original=new RunControl();original.pause();ShellBridgeClient client=new ShellBridgeClient("unused-test-socket","unused-test-token");ShellBridgeClient.NotDispatchedException rejected=assertThrows(ShellBridgeClient.NotDispatchedException.class,()->client.call("owner_handoff_main",new JSONObject(),original,original.pauseRevision()-1,true));assertTrue(rejected.getCause() instanceof ShellBridgeClient.StaleRequestException);}
}
