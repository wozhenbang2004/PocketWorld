package dev.pocketengine.phone.display;
import dev.pocketengine.phone.AndroidPhonePort;
import dev.pocketengine.phone.PhoneAccessibilityService;

import android.app.Application;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.display.BackgroundPhonePort;
import dev.pocketengine.phone.display.DisplayModule;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Actual original ports/forest/native entry; only Android windows and helper transport are explicit fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,
        shadows={PhoneObservationBoundariesNativeTest.Screen.class,PhoneObservationBoundariesNativeTest.NativeWindows.class,PhoneObservationBoundariesNativeTest.Helper.class,
                PhoneObservationBoundariesNativeTest.Packages.class,PhoneObservationBoundariesNativeTest.Launch.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneObservationBoundariesNativeTest {
    private static final AtomicInteger reads=new AtomicInteger(),actions=new AtomicInteger(),mainReads=new AtomicInteger();
    private static volatile int display,blockAt=-1;
    private static volatile CountDownLatch entered,release;
    private static volatile boolean extraWindow,inlineCover,blockAction,animateEveryRead,unrelatedWindow,extraOverlay,blockGlobalReceipt;
    private static AccessibilityNodeInfo root,target,ticker;
    private static View rootView,targetView,tickerView;
    private static volatile JSONObject topology;
    private static volatile RunControl pauseOnAction;
    private static volatile long frameGeneration;
    private final ExecutorService caller=Executors.newSingleThreadExecutor();
    private PhoneAccessibilityService service;

    @Implements(PhoneAccessibilityService.class) public static final class Screen extends NativeWindows {
        @Implementation public String observationSafetyFailure(){return null;}
        @Implementation protected Rect controlsBounds(){return new Rect();}
    }
    @Implements(android.accessibilityservice.AccessibilityService.class) public static class NativeWindows extends org.robolectric.shadows.ShadowAccessibilityService {
        @Implementation protected boolean performGlobalAction(int action){actions.incrementAndGet();if(pauseOnAction!=null)pauseOnAction.pause();if(blockGlobalReceipt){entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("fixture release deadline");}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}return true;}
        @Implementation protected SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
            if(Looper.myLooper()==Looper.getMainLooper())mainReads.incrementAndGet();
            int invocation=reads.incrementAndGet();if(animateEveryRead)ticker.setText("00:"+invocation);
            if(invocation==blockAt){entered.countDown();boolean interrupted=false;while(true)try{release.await();break;}catch(InterruptedException ignored){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
            SparseArray<List<AccessibilityWindowInfo>> all=new SparseArray<>();List<AccessibilityWindowInfo> windows=new ArrayList<>();
            AccessibilityWindowInfo app=window(7,display,1,new Rect(0,0,320,470),root);windows.add(app);
            if(extraWindow){AccessibilityWindowInfo extra=window(8,display,10,unrelatedWindow?new Rect(200,200,300,240):new Rect(0,20,160,130),null);if(extraOverlay)Shadows.shadowOf(extra).setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY);windows.add(extra);}
            all.put(display,windows);return all;
        }
    }
    @Implements(className="android.app.ApplicationPackageManager") public static final class Packages extends org.robolectric.shadows.ShadowApplicationPackageManager {
        @Implementation protected android.content.Intent getLaunchIntentForPackage(String pkg){return new android.content.Intent(android.content.Intent.ACTION_MAIN).setComponent(new android.content.ComponentName(pkg,pkg+".Main"));}
    }
    @Implements(android.app.Instrumentation.class) public static final class Launch extends org.robolectric.shadows.ShadowInstrumentation {
        @Implementation protected android.app.Instrumentation.ActivityResult execStartActivity(android.content.Context who,android.os.IBinder thread,android.os.IBinder token,android.app.Activity target,android.content.Intent intent,int request,android.os.Bundle options){
            android.app.Instrumentation.ActivityResult result=super.execStartActivity(who,thread,token,target,intent,request,options);
            actions.incrementAndGet();if(pauseOnAction!=null)pauseOnAction.pause();return result;
        }
    }
    @Implements(DisplayModule.class) public static final class Helper {
        @Implementation protected void __constructor__(FeatureContext ignored){}
        @Implementation protected void __constructor__(FeatureContext ignored,DisplayModule.AdbFactory factory,DisplayModule.Configuration configuration,DisplayModule.StatusProbe probe){}
        @Implementation protected boolean hasSession(){return true;}
        @Implementation protected boolean isConnected(){return true;}
        @Implementation protected void connect(RunControl control){}
        @Implementation protected boolean ownedBy(String task){return task.equals("task");}
        @Implementation protected void attachInstalledAppEvidence(JSONObject ignored){}
        @Implementation protected JSONObject session()throws Exception {
            return new JSONObject().put("sessionId","original-bg").put("displayId",9).put("width",320).put("height",470)
                    .put("sessionActive",true).put("backgroundOnly",true).put("focusIsolationRequested",true)
                    .put("displayFlagsConfirmed",true).put("frameMetadataOnly",true).put("imeIsolation","available");
        }
        @Implementation protected JSONObject request(String command,JSONObject args,RunControl control,long revision,boolean owner)throws Exception {
            control.check();assertEquals(control.pauseRevision(),revision);assertEquals(owner,control.isPaused());
            if("ownership".equals(command))return new JSONObject().put("ok",true).put("ownership",new JSONObject(topology.toString()));
            assertEquals("frame",command);assertFalse(args.getBoolean("includeImage"));
            return new JSONObject().put("ok",true).put("sessionId","original-bg").put("displayId",9).put("width",320).put("height",470)
                    .put("frameGeneration",frameGeneration).put("framePending",frameGeneration>1).put("empty",false).put("capturedAt",10);
        }
    }
    @Before public void start()throws Exception {
        reads.set(0);actions.set(0);mainReads.set(0);display=0;blockAt=-1;entered=new CountDownLatch(1);release=new CountDownLatch(1);extraWindow=false;inlineCover=false;blockAction=false;animateEveryRead=false;unrelatedWindow=false;extraOverlay=false;blockGlobalReceipt=false;frameGeneration=1;pauseOnAction=null;
        service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();Field instance=PhoneAccessibilityService.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,service);
        rootView=new View(RuntimeEnvironment.getApplication());targetView=new View(RuntimeEnvironment.getApplication());tickerView=new View(RuntimeEnvironment.getApplication());buildTree(false);
        topology=facts("owner.reader",11,9,false);
    }
    @After public void stop()throws Exception {
        release.countDown();caller.shutdownNow();caller.awaitTermination(3,TimeUnit.SECONDS);
        Field instance=PhoneAccessibilityService.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,null);
    }
    private static AccessibilityWindowInfo window(int id,int display,int layer,Rect bounds,AccessibilityNodeInfo node) {
        AccessibilityWindowInfo value=AccessibilityWindowInfo.obtain();var shadow=Shadows.shadowOf(value);shadow.setId(id);shadow.setDisplayId(display);shadow.setLayer(layer);shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);shadow.setBoundsInScreen(bounds);shadow.setActive(id==7);shadow.setFocused(id==7);if(node!=null)shadow.setRoot(node);return value;
    }
    private static AccessibilityNodeInfo node(View view,String text,Rect bounds,boolean click) {
        AccessibilityNodeInfo value=AccessibilityNodeInfo.obtain(view);value.setPackageName("agent.worker");value.setClassName(click?"android.widget.Button":"android.widget.TextView");value.setText(text);value.setBoundsInScreen(bounds);value.setVisibleToUser(true);value.setEnabled(true);value.setClickable(click);Shadows.shadowOf(value).setRefreshReturnValue(true);
        AccessibilityWindowInfo window=window(7,display,1,new Rect(0,0,320,470),null);Shadows.shadowOf(value).setAccessibilityWindowInfo(window);
        Shadows.shadowOf(value).setOnPerformActionListener((action,args)->{actions.incrementAndGet();if(pauseOnAction!=null)pauseOnAction.pause();if(blockAction){entered.countDown();try{release.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}return true;});return value;
    }
    private static void buildTree(boolean replaceTarget) {
        if(replaceTarget)targetView=new View(RuntimeEnvironment.getApplication());
        root=node(rootView,"",new Rect(0,0,320,470),false);target=node(targetView,"fixed",new Rect(20,40,120,90),true);ticker=node(tickerView,"00:01",new Rect(200,200,300,240),false);
        Shadows.shadowOf(root).addChild(target);Shadows.shadowOf(root).addChild(ticker);
        if(inlineCover)Shadows.shadowOf(root).addChild(node(new View(RuntimeEnvironment.getApplication()),"popup",new Rect(15,35,130,95),true));
    }
    private static RunControl control(){RunControl value=new RunControl();value.bindHostRun("task","run",4);return value;}
    private <T>T complete(Future<T> future)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!future.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}
        return future.get(1,TimeUnit.SECONDS);
    }
    private JSONObject observe(PhonePort port,RunControl control)throws Exception {JSONObject value=complete(caller.submit(()->port.observe(false,control)));assertTrue(value.toString(),value.optBoolean("ok"));return value;}
    private static JSONObject click(JSONObject observed)throws Exception {
        JSONArray nodes=observed.getJSONArray("nodes");String id="";for(int i=0;i<nodes.length();i++)if("fixed".equals(nodes.getJSONObject(i).optString("text")))id=nodes.getJSONObject(i).getString("id");assertFalse(id.isEmpty());
        return new JSONObject().put("observationId",observed.getString("observationId")).put("nodeId",id);
    }
    private PhonePort foreground(){display=0;return new AndroidPhonePort(RuntimeEnvironment.getApplication());}
    private BackgroundPhonePort background()throws Exception {display=9;buildTree(false);return new BackgroundPhonePort(new DisplayModule(null));}
    @Test public void foregroundUnrelatedTickerDoesNotInvalidateExactStableButton()throws Exception {
        PhonePort port=foreground();RunControl run=control();JSONObject observed=observe(port,run);ticker.setText("00:02");
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,actions.get());
    }
    @Test public void backgroundPixelAnimationAndUnrelatedTickerDoNotInvalidateSemanticButton()throws Exception {
        PhonePort port=background();RunControl run=control();JSONObject observed=observe(port,run);ticker.setText("00:02");frameGeneration=99;animateEveryRead=true;
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,actions.get());assertFalse(run.isPaused());
        JSONObject next=observe(port,run);receipt=complete(caller.submit(()->port.execute("phone_click_node",click(next),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(2,actions.get());
    }
    @Test public void exactReplacementMoveAncestorAndNewCoverEachRefuseOldTarget()throws Exception {
        for(int mode=0;mode<6;mode++) {
            extraWindow=false;inlineCover=false;extraOverlay=false;buildTree(false);PhonePort port=foreground();RunControl run=control();JSONObject observed=observe(port,run);
            if(mode==0)buildTree(true);else if(mode==1)target.setBoundsInScreen(new Rect(25,40,125,90));else if(mode==2)root.setContentDescription("new ancestor");else if(mode==3)extraWindow=true;else if(mode==4){inlineCover=true;buildTree(false);}else{extraWindow=true;extraOverlay=true;}
            JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertFalse("mode="+mode+" "+receipt,receipt.optBoolean("accepted"));assertEquals(0,actions.get());
        }
    }
    @Test public void separatelyBoundedUnrelatedWindowDoesNotInvalidateForegroundTarget()throws Exception {
        PhonePort port=foreground();RunControl run=control();JSONObject observed=observe(port,run);extraWindow=true;unrelatedWindow=true;
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,actions.get());
    }
    @Test public void ownerPrimaryReaderToBrowserToHomeDoesNotPauseRetainedBackgroundTask()throws Exception {
        PhonePort port=background();RunControl run=control();observe(port,run);
        for(String app:new String[]{"owner.browser","owner.launcher"}){topology=facts(app,30,9,false);JSONObject observed=observe(port,run);JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertFalse(run.isPaused());}
        assertEquals(2,actions.get());
    }
    @Test public void realOriginalTaskMigrationEvenNotResumedOnPrimaryPausesAndKeepsFacts()throws Exception {
        PhonePort port=background();RunControl run=control();observe(port,run);topology=facts("owner.browser",30,0,true);
        JSONObject refused=complete(caller.submit(()->port.observe(false,run)));assertFalse(refused.optBoolean("ok"));assertTrue(run.isPaused());assertEquals("original_owned_task_migrated",refused.getJSONObject("displayLanding").getString("reason"));assertEquals(0,actions.get());
    }
    @Test public void oldHelperOrSamePackagePrimaryCollisionPausesRatherThanGuessing()throws Exception {
        for(boolean old:new boolean[]{true,false}){PhonePort port=background();RunControl run=control();observe(port,run);if(old)topology.remove("taskTopology");else topology=facts("agent.worker",30,9,false);
            JSONObject refused=complete(caller.submit(()->port.observe(false,run)));assertFalse(refused.optBoolean("ok"));assertTrue(run.isPaused());assertEquals(0,actions.get());topology=facts("owner.reader",11,9,false);}
    }
    @Test public void blockedReadLeavesUiPauseStopResponsiveAndDoesNotPublishLateRunReference()throws Exception {
        AndroidPhonePort port=(AndroidPhonePort)foreground();RunControl original=control();blockAt=reads.get()+1;
        Future<JSONObject> pending=caller.submit(()->port.observe(false,original));assertTrue(entered.await(2,TimeUnit.SECONDS));
        AtomicInteger ui=new AtomicInteger();new Handler(Looper.getMainLooper()).post(()->{original.pause();port.clearReferences();original.cancel();ui.incrementAndGet();});Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,ui.get());
        assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(1,TimeUnit.SECONDS));RunControl successor=new RunControl();successor.bindHostRun("task","next-run",5);
        JSONObject busy=complete(caller.submit(()->port.observe(false,successor)));assertFalse(busy.optBoolean("ok"));assertEquals("tree_read_busy",busy.getString("error"));
        release.countDown();Thread.sleep(100);Field refs=AndroidPhonePort.class.getDeclaredField("reference");refs.setAccessible(true);assertNull(((java.util.concurrent.atomic.AtomicReference<?>)refs.get(port)).get());assertEquals(0,mainReads.get());assertEquals(0,actions.get());
    }
    @Test public void blockedBackgroundObserveStillAllowsOriginalOwnerInspectionAndStopWithoutLateRefs()throws Exception {
        BackgroundPhonePort port=background();RunControl run=control();observe(port,run);blockAt=reads.get()+1;
        Future<JSONObject> pending=caller.submit(()->port.observe(false,run));assertTrue(entered.await(2,TimeUnit.SECONDS));AtomicInteger ui=new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<JSONObject> ownerView=new java.util.concurrent.atomic.AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(()->{try{run.pause();port.clearReferences();ownerView.set(port.cachedObservationForOwner("task"));run.cancel();ui.incrementAndGet();}catch(Exception failed){throw new AssertionError(failed);}});
        long before=System.nanoTime();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(System.nanoTime()-before<TimeUnit.SECONDS.toNanos(1));assertEquals(1,ui.get());assertTrue(ownerView.get().getBoolean("referencesExpired"));assertFalse(ownerView.get().getBoolean("manualAllowed"));
        pending.get(1,TimeUnit.SECONDS);release.countDown();Thread.sleep(100);Field current=BackgroundPhonePort.class.getDeclaredField("current");current.setAccessible(true);assertNull(current.get(port));assertEquals(0,mainReads.get());assertEquals(0,actions.get());
    }
    @Test public void blockedResolveThenCancelCannotDispatchAfterBinderReturns()throws Exception {
        for(boolean background:new boolean[]{false,true}) {
            PhonePort port=background?background():foreground();RunControl run=control();JSONObject observed=observe(port,run);blockAt=reads.get()+2;entered=new CountDownLatch(1);release=new CountDownLatch(1);
            Future<JSONObject> pending=caller.submit(()->port.execute("phone_click_node",click(observed),run));assertTrue(entered.await(2,TimeUnit.SECONDS));run.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(1,TimeUnit.SECONDS));release.countDown();Thread.sleep(100);assertEquals(0,actions.get());assertEquals(0,mainReads.get());blockAt=-1;
        }
    }
    @Test public void acceptedMainGlobalEffectsKeepTheirKnownReceiptWhenPauseFollowsEntry()throws Exception {
        for(String tool:new String[]{"phone_back","phone_home"}){PhonePort port=foreground();RunControl run=control();JSONObject observed=observe(port,run);pauseOnAction=run;int before=actions.get();
            JSONObject receipt=complete(caller.submit(()->port.execute(tool,new JSONObject().put("observationId",observed.getString("observationId")),run)));
            assertTrue(receipt.toString(),receipt.getBoolean("accepted"));assertTrue(run.isPaused());assertEquals(before+1,actions.get());pauseOnAction=null;}
    }
    @Test public void enteredMainCallbackWithNoReceiptYetRemainsUnknownAfterPause()throws Exception {
        PhonePort port=foreground();RunControl run=control();JSONObject observed=observe(port,run);pauseOnAction=run;blockGlobalReceipt=true;
        entered=new CountDownLatch(1);release=new CountDownLatch(1);Future<JSONObject> pending=caller.submit(()->port.execute("phone_back",new JSONObject().put("observationId",observed.getString("observationId")),run));
        ExecutorService controller=Executors.newSingleThreadExecutor();
        Future<JSONObject> checked=controller.submit(()->{try{assertTrue(entered.await(2,TimeUnit.SECONDS));JSONObject receipt=pending.get(2,TimeUnit.SECONDS);
            assertEquals(1,release.getCount());assertTrue(receipt.toString(),receipt.isNull("accepted"));assertEquals("dispatch_unknown",receipt.getString("error"));assertEquals(1,actions.get());return receipt;
        }finally{release.countDown();}});
        try{complete(pending);checked.get(3,TimeUnit.SECONDS);}
        finally{release.countDown();controller.shutdownNow();controller.awaitTermination(1,TimeUnit.SECONDS);blockGlobalReceipt=false;pauseOnAction=null;}
    }
    @Test public void acceptedMainLaunchKeepsItsKnownReceiptWhenPauseFollowsEntry()throws Exception {
        PhonePort port=foreground();RunControl run=control();pauseOnAction=run;
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_launch_app",new JSONObject().put("packageName","agent.worker"),run)));
        assertTrue(receipt.toString(),receipt.getBoolean("accepted"));assertTrue(run.isPaused());assertEquals(1,actions.get());pauseOnAction=null;
    }
    @Test public void pauseAfterNativeEntryNeverTurnsAnEnteredEffectIntoAcceptedFalse()throws Exception {
        for(boolean background:new boolean[]{false,true}) {
            PhonePort port=background?background():foreground();RunControl run=control();JSONObject observed=observe(port,run);pauseOnAction=run;int before=actions.get();
            JSONObject uncertain=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertTrue(uncertain.toString(),uncertain.isNull("accepted"));assertTrue(run.isPaused());assertEquals(before+1,actions.get());pauseOnAction=null;
        }
    }
    @Test public void alreadyEnteredNativeTimeoutStaysUnknownAndNeverReplaysOldReference()throws Exception {
        for(boolean background:new boolean[]{false,true}) {
            PhonePort port=background?background():foreground();RunControl run=control();JSONObject observed=observe(port,run);
            Field gate=port.getClass().getDeclaredField("reads");gate.setAccessible(true);gate.set(port,new dev.pocketengine.phone.observation.PhoneReadGate(120));
            entered=new CountDownLatch(1);release=new CountDownLatch(1);blockAction=true;int before=actions.get();
            Future<JSONObject> pending=caller.submit(()->port.execute("phone_click_node",click(observed),run));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(entered.getCount()>0&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}assertEquals(0,entered.getCount());
            JSONObject uncertain=complete(pending);assertTrue(uncertain.toString(),uncertain.isNull("accepted"));assertFalse(uncertain.optBoolean("automaticReplayAllowed",true));
            // Once native entry has won, its Binder effect itself is not cancellable.
            // Keep it unknown, do not issue a replay, and release this fixture before
            // exercising later explicit requests under the original control monitor.
            assertEquals(before+1,actions.get());release.countDown();blockAction=false;Thread.sleep(100);
            for(int i=0;i<3;i++){JSONObject retry=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertFalse(retry.optBoolean("accepted"));}
            assertEquals(before+1,actions.get());
        }
    }
    private static JSONObject task(int id,int display,String pkg)throws Exception{return new JSONObject().put("taskId",id).put("rootTaskId",id).put("displayId",display).put("androidUserId",0).put("packageName",pkg).put("component",pkg+"/.Main").put("rootIdentity","root-"+id);}
    private static JSONObject resumed(int id,int display,String pkg)throws Exception{return new JSONObject().put("taskId",id).put("displayId",display).put("packageName",pkg);}
    private static JSONObject facts(String primary,int primaryTask,int originalDisplay,boolean replacementOwned)throws Exception {
        JSONArray tasks=new JSONArray().put(task(primaryTask,0,primary)).put(task(22,originalDisplay,"agent.worker"));JSONArray owned=new JSONArray();
        if(originalDisplay==9)owned.put(resumed(22,9,"agent.worker"));else if(replacementOwned){tasks.put(task(44,9,"agent.replacement"));owned.put(resumed(44,9,"agent.replacement"));}
        return new JSONObject().put("status","observed").put("primary",new JSONArray().put(resumed(primaryTask,0,primary))).put("owned",owned)
                .put("taskTopology",new JSONObject().put("schemaVersion",1).put("status","observed").put("complete",true).put("readOnly",true).put("tasks",tasks));
    }
}
