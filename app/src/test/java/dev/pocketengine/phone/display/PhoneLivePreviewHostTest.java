package dev.pocketengine.phone.display;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.display.adb.AdbBootstrap;
import dev.pocketengine.phone.display.adb.AdbBootstrapClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real App/TaskStore/port/module path. Startup and helper replies are explicit boundaries;
 * helper bitmap encoding and actual socket framing have their own tests, not device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=PhoneLivePreviewHostTest.StartupBoundary.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneLivePreviewHostTest {
    @Implements(EngineApplication.class)
    public static final class StartupBoundary extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate() { }
    }
    private EngineApplication app;
    private DisplayModule module;
    private final Helper helper=new Helper();
    private final List<Run> runs=new ArrayList<>();
    private final AtomicBoolean ownerCurrent=new AtomicBoolean(true);

    private static final class Helper implements DisplayModule.Exchange {
        final Map<String,JSONObject> sessions=new java.util.concurrent.ConcurrentHashMap<>();
        final List<JSONObject> previews=new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile String slow="";
        volatile boolean unavailable;
        volatile CountDownLatch entered,proceed;
        int next=10;
        public JSONObject call(String command,JSONObject payload,RunControl original,long revision,boolean paused,
                dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
            if(admission!=null)admission.check();
            String run=payload.getString("ownerRunId");JSONObject state=sessions.get(run);
            if("status".equals(command))return owner(payload,state==null?status(false,"",-1):new JSONObject(state.toString()));
            if("create".equals(command)){
                synchronized(this){state=owner(payload,status(true,"native-"+next,next++));sessions.put(run,state);}
                return new JSONObject(state.toString());
            }
            if("release".equals(command)){sessions.remove(run);return new JSONObject(state.toString()).put("released",true);}
            if(!"preview_frame".equals(command))throw new AssertionError("Preview must not observe, refresh, renew or act: "+command);
            assertEquals(state.getString("sessionId"),payload.getString("sessionId"));assertEquals(state.getInt("displayId"),payload.getInt("displayId"));
            previews.add(new JSONObject(payload.toString()));
            if(run.equals(slow)){entered.countDown();assertTrue(proceed.await(5,TimeUnit.SECONDS));}
            if(unavailable)throw new java.io.IOException("controlled viewer transport loss");
            boolean unchanged=payload.optLong("afterFrameGeneration",-1)==7;
            JSONObject frame=owner(payload,new JSONObject(state.toString())).put("state",unchanged?"unchanged":"ready")
                    .put("frameGeneration",7L).put("capturedAt",123L).put("sourceWidth",320).put("sourceHeight",480)
                    .put("previewWidth",320).put("previewHeight",480).put("mime","image/jpeg");
            if(!unchanged)frame.put("imageBase64","controlled-helper-image");
            return frame;
        }
        private static JSONObject status(boolean active,String session,int display)throws Exception {
            return new JSONObject().put("ok",true).put("protocol",1).put("uid",2000).put("sdk",35).put("sessionRouting",2)
                    .put("sessionActive",active).put("sessionId",active?session:JSONObject.NULL).put("displayId",active?display:JSONObject.NULL)
                    .put("backgroundOnly",true).put("focusIsolationRequested",active).put("displayFlagsConfirmed",active)
                    .put("imeIsolation","available").put("frameMetadataOnly",true);
        }
        private static JSONObject owner(JSONObject input,JSONObject answer)throws Exception {
            return answer.put("ownerTaskId",input.getString("ownerTaskId")).put("ownerRunId",input.getString("ownerRunId")).put("ownerEpoch",input.getInt("ownerEpoch"));
        }
    }
    private static AdbBootstrapClient adb(){return new AdbBootstrapClient(){
        public JSONObject snapshot(){return new JSONObject();}
        public JSONObject discover(){throw new AssertionError("no discovery");}
        public JSONObject pair(JSONObject input){throw new AssertionError("no pairing");}
        public JSONObject connect(JSONObject input,JSONObject bootstrap,AdbBootstrap.AuthenticatedStatus status){throw new AssertionError("no bootstrap");}
        public JSONObject cancel(){return new JSONObject();}
        public JSONObject forgetIdentity(){throw new AssertionError("no identity change");}
        public void close(){ }
    };}
    @Before public void setup()throws Exception {
        java.security.Security.addProvider(new java.security.Provider("PhonePreviewFixtureKeys",1.0d,"Test AndroidKeyStore"){{
            put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());
        }});
        app=(EngineApplication)RuntimeEnvironment.getApplication();
        app.deleteDatabase(dev.pocketengine.store.TaskStore.DATABASE_NAME);
        app.tasks=new dev.pocketengine.store.TaskStore(app);
        dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);
        app.features=new dev.pocketengine.features.FeatureHost(event->{},app.tasks,()->false,app.resources);
        RunControl.installHostAdmission((original,id,run,epoch)->{
            if(!app.businessReady())throw new SecurityException("fixture business host unavailable");
            return app.tasks.admitExecutionControl(original,id,run,epoch);
        });
        app.getSharedPreferences("display_backend",0).edit().putString("backend","adb").commit();
        module=new DisplayModule(new FeatureContext(app,null,null,event->{}),changed->adb(),null,null,helper);
    }
    @After public void cleanup()throws Exception {
        if(helper.proceed!=null)helper.proceed.countDown();
        for(Run run:runs)if(app.control(run.id)==run.control){run.control.cancel();app.tasks.endAttempt(run.id,run.run,"cancelled","fixture ended",new JSONObject());app.released(run.id,run.control);}
        module.close();app.features.close();((android.database.sqlite.SQLiteDatabase)get(app.tasks,"db")).close();
        java.security.Security.removeProvider("PhonePreviewFixtureKeys");
    }
    private record Run(String id,String run,int epoch,RunControl control,BackgroundPhonePort port,String token) {
        JSONObject request()throws Exception{return new JSONObject().put("taskId",id).put("expectedRunId",run).put("expectedEpoch",epoch);}
    }
    private Run admit(String existing)throws Exception {return admit(existing,true);}
    @SuppressWarnings("unchecked") private Run admit(String existing,boolean attach)throws Exception {
        String id=existing==null?app.tasks.create("独立屏只读观看回归","background").getString("id"):existing;
        if(existing==null)app.tasks.initializeExecutionScope(id,4);
        JSONObject task=app.tasks.startAttempt(id,"background",4);RunControl original=new RunControl();
        original.bindHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch"));
        String token=java.util.UUID.randomUUID().toString();assertTrue(app.resources.acquireBackgroundPhone(token));
        Class<?> type=Class.forName("dev.pocketengine.EngineApplication$BackgroundRun");Constructor<?> constructor=type.getDeclaredConstructor(RunControl.class,ModelConfig.class,String.class);constructor.setAccessible(true);
        ((Map<String,Object>)get(app,"backgroundRuns")).put(id,constructor.newInstance(original,new ModelConfig("https://model.invalid","fixture","fixture",false,4,5),token));
        BackgroundPhonePort port=null;
        if(attach){module.prepare(original);port=new BackgroundPhonePort(module,original);assertTrue(app.attachPort(id,port,original));}
        Run run=new Run(id,task.getString("runId"),task.getInt("coordinatorEpoch"),original,port,token);runs.add(run);return run;
    }
    private FutureTask<JSONObject> start(JSONObject input){
        FutureTask<JSONObject> task=new FutureTask<>(()->app.dispatchReviewedOwnerAction("readPhonePreview",input,ownerCurrent::get));
        new Thread(task,"owner-phone-preview-fixture").start();return task;
    }
    private JSONObject read(JSONObject input)throws Exception {
        try{return start(input).get(5,TimeUnit.SECONDS);}
        catch(java.util.concurrent.ExecutionException failed){if(failed.getCause() instanceof Exception e)throw e;throw failed;}
    }
    private static Object get(Object object,String key)throws Exception {Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(object);}
    private static void set(Object object,String key,Object value)throws Exception {Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);f.set(object,value);}
    private static Object retainedObservation(RunControl control)throws Exception {
        Class<?> tree=Class.forName(BackgroundPhonePort.class.getName()+"$Tree");
        Constructor<?> forest=dev.pocketengine.phone.observation.AccessibilityForest.class.getDeclaredConstructor();forest.setAccessible(true);
        Constructor<?> treeConstructor=tree.getDeclaredConstructor(dev.pocketengine.phone.observation.AccessibilityForest.class,dev.pocketengine.displayhelper.DisplayGeometry.class,long.class,int.class,int.class);treeConstructor.setAccessible(true);
        Class<?> observation=Class.forName(BackgroundPhonePort.class.getName()+"$Observation");
        Constructor<?> constructor=observation.getDeclaredConstructor(tree,long.class,long.class,int.class,int.class,RunControl.class,boolean.class);constructor.setAccessible(true);
        return constructor.newInstance(treeConstructor.newInstance(forest.newInstance(),null,0L,320,480),4L,0L,320,480,control,true);
    }
    private JSONObject core(Run run)throws Exception {Method read=app.tasks.getClass().getDeclaredMethod("read",String.class);read.setAccessible(true);return (JSONObject)read.invoke(app.tasks,run.id);}
    private void writeCore(JSONObject core)throws Exception {Method write=app.tasks.getClass().getDeclaredMethod("write",JSONObject.class);write.setAccessible(true);write.invoke(app.tasks,core);}

    @Test public void runningAndPausedFramesLeaveModelCacheRunLedgerAndPauseUntouched()throws Exception {
        Run run=admit(null);JSONObject cached=new JSONObject().put("observationId","model-original");set(run.port,"cached",cached);
        Object observation=retainedObservation(run.control);set(run.port,"current",observation);
        run.control.setAuthorityCheck(()->{throw new AssertionError("viewer must not invoke execution authority");});
        String before=app.tasks.get(run.id,false).toString(),session=module.session(run.control).toString();
        JSONObject first=read(run.request());assertEquals("ready",first.getString("state"));assertEquals(run.run,first.getString("runId"));
        assertFalse(first.has("observationId"));assertFalse(first.has("manualAllowed"));assertSame(cached,get(run.port,"cached"));assertSame(observation,get(run.port,"current"));
        assertFalse(run.control.isPaused());assertFalse(run.control.isCancelled());
        run.control.pause();long revision=run.control.pauseRevision();
        JSONObject pinned=run.request().put("expectedSessionId",first.getString("sessionId")).put("expectedDisplayId",first.getInt("displayId")).put("afterFrameGeneration",7);
        JSONObject next=read(pinned);assertEquals("unchanged",next.getString("state"));assertFalse(next.has("imageBase64"));
        assertTrue(run.control.isPaused());assertEquals(revision,run.control.pauseRevision());assertSame(cached,get(run.port,"cached"));assertSame(observation,get(run.port,"current"));
        assertEquals(before,app.tasks.get(run.id,false).toString());assertEquals(session,module.session(run.control).toString());
    }
    @Test public void admittedRunWaitsForItsFirstPortWithoutEndingViewerAndCancellationStillWins()throws Exception {
        Run starting=admit(null,false);String original=core(starting).toString();
        assertThrows(PhoneLivePreview.NotReady.class,()->read(starting.request()));
        assertEquals(0,helper.previews.size());assertEquals(0,helper.sessions.size());assertEquals(original,core(starting).toString());
        assertFalse(starting.control.isPaused());assertFalse(starting.control.isCancelled());
        assertThrows(SecurityException.class,()->read(starting.request().put("expectedSessionId","previous-screen").put("expectedDisplayId",11)));
        ownerCurrent.set(false);assertThrows(SecurityException.class,()->read(starting.request()));ownerCurrent.set(true);
        module.prepare(starting.control);BackgroundPhonePort attached=new BackgroundPhonePort(module,starting.control);
        assertTrue(app.attachPort(starting.id,attached,starting.control));assertEquals("ready",read(starting.request()).getString("state"));
        Run stopped=admit(null,false);stopped.control.cancel();
        assertThrows(SecurityException.class,()->read(stopped.request()));assertEquals(1,helper.previews.size());
    }
    @Test public void viewerTransportLossDoesNotInstallExecutionRecoveryOrClearObservations()throws Exception {
        Run run=admit(null);JSONObject cached=new JSONObject().put("observationId","retained");set(run.port,"cached",cached);
        String before=module.session(run.control).toString();helper.unavailable=true;
        assertThrows(java.io.IOException.class,()->read(run.request()));
        assertEquals(before,module.session(run.control).toString());assertTrue(module.hasSession(run.control));assertSame(cached,get(run.port,"cached"));
        assertFalse(run.control.isCancelled());assertFalse(run.control.isPaused());helper.unavailable=false;assertEquals("ready",read(run.request()).getString("state"));
    }
    @Test public void browserExecutionRetainsReadOnlyPhoneViewButMovingAppToMainScreenRejectsIt()throws Exception {
        Run run=admit(null);JSONObject first=read(run.request());
        app.resources.releasePhone(run.token);assertThrows(SecurityException.class,()->read(run.request()));
        assertTrue(app.resources.acquireBackgroundPhone(run.token));
        run.control.changeExecutionSurface("phone","transition");
        app.resources.releasePhone(run.token);assertFalse(app.resources.ownsBackgroundPhone(run.token));
        run.control.changeExecutionSurface("transition","browser");
        JSONObject pinned=run.request().put("expectedSessionId",first.getString("sessionId")).put("expectedDisplayId",first.getInt("displayId"));
        JSONObject browser=read(pinned);assertEquals(first.getString("sessionId"),browser.getString("sessionId"));assertEquals(first.getInt("displayId"),browser.getInt("displayId"));
        assertEquals("browser",run.control.executionSurface());assertFalse(run.control.isPaused());assertFalse(browser.has("manualAllowed"));
        set(run.port,"ownerHandoff",new JSONObject().put("state","main"));
        assertThrows(SecurityException.class,()->read(pinned));assertEquals(2,helper.previews.size());assertEquals("browser",run.control.executionSurface());
    }
    @Test public void exactRunAndSessionSelectionNeverUseAnotherActiveRunOrTheLegacyBridge()throws Exception {
        Run a=admit(null),b=admit(null);JSONObject other=module.session(b.control);
        assertThrows(SecurityException.class,()->read(a.request().put("expectedSessionId",other.getString("sessionId")).put("expectedDisplayId",other.getInt("displayId"))));
        assertThrows(SecurityException.class,()->read(a.request().put("expectedRunId",b.run)));
        assertThrows(SecurityException.class,()->read(new JSONObject().put("taskId",a.id)));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("readPhonePreview",a.request()));
        assertEquals(0,helper.previews.size());assertEquals(b.run,read(b.request()).getString("runId"));
        assertEquals(b.run,helper.previews.get(0).getString("ownerRunId"));
    }
    @Test public void slowPreviewDoesNotHoldApplicationPortOrOtherRunsAndClosedWindowDiscardsPixels()throws Exception {
        Run a=admit(null),b=admit(null);helper.slow=a.run;helper.entered=new CountDownLatch(1);helper.proceed=new CountDownLatch(1);
        FutureTask<JSONObject> pending;
        synchronized(a.port){pending=start(a.request());assertTrue(helper.entered.await(2,TimeUnit.SECONDS));}
        FutureTask<RunControl> controls=new FutureTask<>(()->app.control(b.id));new Thread(controls).start();assertSame(b.control,controls.get(1,TimeUnit.SECONDS));
        assertEquals(b.run,read(b.request()).getString("runId"));ownerCurrent.set(false);helper.proceed.countDown();
        java.util.concurrent.ExecutionException stale=assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));assertTrue(stale.getCause() instanceof SecurityException);
        assertFalse(a.control.isCancelled());assertFalse(a.control.isPaused());assertTrue(module.hasSession(a.control));
    }
    @Test public void staleReplyCannotFollowSameTaskReplacementOrCanonicalCancellation()throws Exception {
        Run old=admit(null);helper.slow=old.run;helper.entered=new CountDownLatch(1);helper.proceed=new CountDownLatch(1);
        FutureTask<JSONObject> pending=start(old.request());assertTrue(helper.entered.await(2,TimeUnit.SECONDS));
        JSONObject original=module.session(old.control);old.control.cancel();app.tasks.endAttempt(old.id,old.run,"interrupted","fixture replacement",new JSONObject());app.released(old.id,old.control);
        module.releaseSession(original.getString("sessionId"),original.getInt("displayId"));module.unpinOwner(old.control);
        Run next=admit(old.id);helper.proceed.countDown();
        java.util.concurrent.ExecutionException stale=assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));assertTrue(stale.getCause() instanceof SecurityException);
        assertSame(next.control,app.control(next.id));assertEquals(next.run,read(next.request()).getString("runId"));
        JSONObject core=core(next);core.put("cancelRequested",true);writeCore(core);
        assertThrows(SecurityException.class,()->read(next.request()));assertFalse(next.control.isCancelled());
    }
    @Test public void canonicalOwnerSourceAllowsLocalTeamPhoneAndRejectsSharedRemoteAndPairedSources()throws Exception {
        Run run=admit(null);JSONObject original=core(run);
        JSONObject team=new JSONObject(original.toString()).put("origin",new JSONObject().put("kind","team_phone").put("teamRunId","native-team"));
        team.put("parentTaskId","native-parent");writeCore(team);assertEquals("ready",read(run.request()).getString("state"));
        for(String kind:new String[]{"paired_device","group","channel","team_remote_assignment","remote"}){
            JSONObject shared=new JSONObject(original.toString()).put("origin",new JSONObject().put("kind",kind));writeCore(shared);
            assertThrows(SecurityException.class,()->read(run.request()));
        }
        writeCore(new JSONObject(original.toString()).put("spaceId","group:shared"));assertThrows(SecurityException.class,()->read(run.request()));
        writeCore(new JSONObject(original.toString()).put("actorId","other-owner"));assertThrows(SecurityException.class,()->read(run.request()));
        writeCore(new JSONObject(original.toString()).put("origin","invalid"));assertThrows(SecurityException.class,()->read(run.request()));
        writeCore(original);assertEquals(1,helper.previews.size());
    }
}
