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
import java.util.concurrent.ExecutionException;
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
    private static volatile boolean noTree;
    private static final AtomicInteger helperMutations=new AtomicInteger();
    private static volatile CountDownLatch entered,release;
    private static volatile boolean extraWindow,inlineCover,blockAction,animateEveryRead,unrelatedWindow,extraOverlay,blockGlobalReceipt,windowAppearsDuringFrame;
    private static volatile int screenshotFailure;
    private static AccessibilityNodeInfo root,target,ticker;
    private static View rootView,targetView,tickerView;
    private static volatile JSONObject topology;
    private static volatile RunControl pauseOnAction;
    private static volatile long frameGeneration;
    private static volatile int helperDisplay=9;
    private static volatile dev.pocketengine.displayhelper.DisplayGeometry helperGeometry;
    private final ExecutorService caller=Executors.newSingleThreadExecutor();
    private final List<BackgroundPhonePort> backgroundPorts=new ArrayList<>();
    private PhoneAccessibilityService service;

    @Implements(PhoneAccessibilityService.class) public static final class Screen extends NativeWindows {
        @Implementation public String observationSafetyFailure(){return null;}
        @Implementation protected Rect controlsBounds(){return new Rect();}
    }
    @Implements(android.accessibilityservice.AccessibilityService.class) public static class NativeWindows extends org.robolectric.shadows.ShadowAccessibilityService {
        @Implementation protected void takeScreenshot(int displayId,java.util.concurrent.Executor executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback callback){executor.execute(()->callback.onFailure(screenshotFailure));}
        @Implementation protected boolean performGlobalAction(int action){actions.incrementAndGet();if(pauseOnAction!=null)pauseOnAction.pause();if(blockGlobalReceipt){entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("fixture release deadline");}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}return true;}
        @Implementation protected SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
            if(Looper.myLooper()==Looper.getMainLooper())mainReads.incrementAndGet();
            int invocation=reads.incrementAndGet();if(animateEveryRead)ticker.setText("00:"+invocation);
            if(invocation==blockAt){entered.countDown();boolean interrupted=false;while(true)try{release.await();break;}catch(InterruptedException ignored){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
            SparseArray<List<AccessibilityWindowInfo>> all=new SparseArray<>();List<AccessibilityWindowInfo> windows=new ArrayList<>();
            AccessibilityWindowInfo app=window(7,display,1,new Rect(0,0,320,470),noTree?null:root);windows.add(app);
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
        @Implementation protected void __constructor__(FeatureContext ignored,DisplayModule.AdbFactory factory,DisplayModule.Configuration configuration,DisplayModule.StatusProbe probe,DisplayModule.Exchange exchange){}
        @Implementation protected boolean hasSession(){return true;}
        @Implementation protected boolean hasSession(String sessionId,int displayId){return "original-bg".equals(sessionId)&&displayId==helperDisplay;}
        @Implementation protected boolean isConnected(){return true;}
        @Implementation protected void connect(RunControl control){}
        @Implementation protected boolean ownedBy(String task){return task.equals("task");}
        @Implementation protected boolean ownedBy(String task,String sessionId,int displayId){return ownedBy(task)&&hasSession(sessionId,displayId);}
        @Implementation protected void attachInstalledAppEvidence(JSONObject ignored){}
        @Implementation protected android.content.Context hostContext(){return RuntimeEnvironment.getApplication();}
        @Implementation protected JSONObject session()throws Exception {
            JSONObject session=new JSONObject().put("sessionId","original-bg").put("displayId",helperDisplay).put("width",320).put("height",470)
                    .put("sessionActive",true).put("backgroundOnly",true).put("focusIsolationRequested",true)
                    .put("displayFlagsConfirmed",true).put("frameMetadataOnly",true).put("imeIsolation","available");
            return helperGeometry==null?session:helperGeometry.writeTo(session,true);
        }
        @Implementation protected JSONObject session(String sessionId,int displayId)throws Exception {
            if(!hasSession(sessionId,displayId))throw new SecurityException("fixture session changed");
            return session();
        }
        @Implementation protected JSONObject refreshSession(String sessionId,int displayId,RunControl control,long revision,boolean owner)throws Exception {
            control.check();if(control.pauseRevision()!=revision||control.isPaused()!=owner)throw new ShellBridgeClient.StaleRequestException();
            return session(sessionId,displayId);
        }
        @Implementation protected JSONObject requestAdmitted(String command,JSONObject args,RunControl control,long revision,String sessionId,int displayId,dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
            session(sessionId,displayId);admission.check();
            return request(command,new JSONObject(args.toString()).put("sessionId",sessionId).put("displayId",displayId),control,revision,false);
        }
        @Implementation protected JSONObject requestOcrTap(JSONObject args,RunControl control,long revision,String sessionId,int displayId,dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
            return requestAdmitted("tap",args,control,revision,sessionId,displayId,admission);
        }
        @Implementation protected JSONObject request(String command,JSONObject args,RunControl control,long revision,boolean owner)throws Exception {
            control.check();assertEquals(control.pauseRevision(),revision);assertEquals(owner,control.isPaused());
            session(args.getString("sessionId"),args.getInt("displayId"));
            if("ownership".equals(command))return new JSONObject().put("ok",true).put("ownership",new JSONObject(topology.toString()));
            if(command.equals("tap")||command.equals("swipe")){helperMutations.incrementAndGet();actions.incrementAndGet();return new JSONObject().put("ok",true).put("accepted",true).put("verified",false);}
            assertEquals("frame",command);
            if(windowAppearsDuringFrame)extraWindow=true;
            JSONObject frame=new JSONObject().put("ok",true).put("sessionId","original-bg").put("displayId",helperDisplay).put("width",320).put("height",470)
                    .put("frameGeneration",frameGeneration).put("framePending",frameGeneration>1).put("empty",false).put("capturedAt",10);
            if(helperGeometry!=null)helperGeometry.writeTo(frame,true);
            if(args.getBoolean("includeImage"))frame.put("imageMimeType","image/png").put("imageBase64",fixtureImage());return frame;
        }
    }
    @Before public void start()throws Exception {
        reads.set(0);actions.set(0);mainReads.set(0);helperMutations.set(0);noTree=false;display=0;blockAt=-1;entered=new CountDownLatch(1);release=new CountDownLatch(1);extraWindow=false;inlineCover=false;blockAction=false;animateEveryRead=false;unrelatedWindow=false;extraOverlay=false;blockGlobalReceipt=false;windowAppearsDuringFrame=false;screenshotFailure=android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR;frameGeneration=1;pauseOnAction=null;helperDisplay=9;helperGeometry=null;
        service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();Field instance=PhoneAccessibilityService.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,service);
        rootView=new View(RuntimeEnvironment.getApplication());targetView=new View(RuntimeEnvironment.getApplication());tickerView=new View(RuntimeEnvironment.getApplication());buildTree(false);
        topology=facts("owner.reader",11,9,false);
    }
    @After public void stop()throws Exception {
        release.countDown();caller.shutdownNow();caller.awaitTermination(3,TimeUnit.SECONDS);
        java.lang.reflect.Method stopWatch=BackgroundPhonePort.class.getDeclaredMethod("stopGeometryWatch");stopWatch.setAccessible(true);for(BackgroundPhonePort port:backgroundPorts)stopWatch.invoke(port);
        if(helperGeometry!=null)org.robolectric.shadows.ShadowDisplayManager.removeDisplay(helperDisplay);
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
    private JSONObject capture(PhonePort port,RunControl control)throws Exception {
        Future<JSONObject> pending=caller.submit(()->port.observe(true,control));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!pending.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idleFor(20,TimeUnit.MILLISECONDS);Thread.sleep(2);}
        return pending.get(1,TimeUnit.SECONDS);
    }
    private static JSONObject click(JSONObject observed)throws Exception {
        JSONArray nodes=observed.getJSONArray("nodes");String id="";for(int i=0;i<nodes.length();i++)if("fixed".equals(nodes.getJSONObject(i).optString("text")))id=nodes.getJSONObject(i).getString("id");assertFalse(id.isEmpty());
        return new JSONObject().put("observationId",observed.getString("observationId")).put("nodeId",id);
    }
    private PhonePort foreground(){display=0;return new AndroidPhonePort(RuntimeEnvironment.getApplication());}
    private BackgroundPhonePort background()throws Exception {display=helperDisplay;buildTree(false);BackgroundPhonePort port=new BackgroundPhonePort(new DisplayModule(null));backgroundPorts.add(port);return port;}
    private BackgroundPhonePort geometryBackground()throws Exception {
        helperDisplay=org.robolectric.shadows.ShadowDisplayManager.addDisplay("w320dp-h470dp-mdpi");
        helperGeometry=new dev.pocketengine.displayhelper.DisplayGeometry(1,320,470,160,0);
        topology=facts("owner.reader",11,helperDisplay,false);return background();
    }
    @Test public void sameSizeGeometryRoundTripRejectsOldNodeButFreshObservationKeepsSameSession()throws Exception {
        BackgroundPhonePort port=geometryBackground();RunControl run=control();JSONObject first=observe(port,run);
        helperGeometry=new dev.pocketengine.displayhelper.DisplayGeometry(3,320,470,160,0);frameGeneration=99;
        JSONObject refused=complete(caller.submit(()->port.execute("phone_click_node",click(first),run)));
        assertFalse(refused.toString(),refused.optBoolean("accepted"));assertEquals(0,actions.get());assertFalse(run.isPaused());
        JSONObject fresh=observe(port,run);assertEquals(3,fresh.getLong("geometryGeneration"));assertEquals(first.getString("sessionId"),fresh.getString("sessionId"));assertEquals(first.getInt("displayId"),fresh.getInt("displayId"));
        JSONObject accepted=complete(caller.submit(()->port.execute("phone_click_node",click(fresh),run)));assertTrue(accepted.toString(),accepted.optBoolean("accepted"));assertEquals(1,actions.get());
    }
    @Test public void geometryChangeWhileNativeTreeWorkerIsBlockedCannotDispatchOldNodeAfterReturn()throws Exception {
        BackgroundPhonePort port=geometryBackground();RunControl run=control();JSONObject observed=observe(port,run);blockAt=reads.get()+2;
        Future<JSONObject> pending=caller.submit(()->port.execute("phone_click_node",click(observed),run));assertTrue(entered.await(2,TimeUnit.SECONDS));
        helperGeometry=new dev.pocketengine.displayhelper.DisplayGeometry(2,320,470,160,0);release.countDown();
        JSONObject refused=complete(pending);assertFalse(refused.toString(),refused.optBoolean("accepted"));assertEquals(0,actions.get());assertEquals(0,helperMutations.get());assertEquals(0,mainReads.get());
    }
    @Test public void foregroundUnrelatedTickerDoesNotInvalidateExactStableButton()throws Exception {
        PhonePort port=foreground();RunControl run=control();JSONObject observed=observe(port,run);ticker.setText("00:02");
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,actions.get());
    }
    @Test public void backgroundPixelAnimationAndUnrelatedTickerDoNotInvalidateSemanticButton()throws Exception {
        PhonePort port=background();RunControl run=control();JSONObject observed=observe(port,run);ticker.setText("00:02");frameGeneration=99;animateEveryRead=true;
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,actions.get());assertFalse(run.isPaused());
        JSONObject next=observe(port,run);receipt=complete(caller.submit(()->port.execute("phone_click_node",click(next),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(2,actions.get());
    }
    @Test public void backgroundCaptureRetainsChangingTextButRequiresFreshPixelsForCoordinates()throws Exception {
        BackgroundPhonePort port=background();RunControl run=control();animateEveryRead=true;
        JSONObject observed=capture(port,run);
        assertTrue(observed.toString(),observed.optBoolean("ok"));assertTrue(observed.getBoolean("changedDuringRead"));
        assertTrue(dev.pocketengine.phone.observation.ObservationReport.hasImage(observed));assertFalse(observed.getBoolean("coordinateActionsAvailable"));
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));
        assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,actions.get());
        JSONObject next=capture(port,run);
        JSONObject point=new JSONObject().put("observationId",next.getString("observationId")).put("x",200).put("y",140);
        JSONObject refused=complete(caller.submit(()->port.execute("phone_tap",point,run)));
        assertFalse(refused.toString(),refused.optBoolean("accepted"));assertEquals(0,helperMutations.get());assertEquals(1,actions.get());assertFalse(run.isPaused());
    }
    @Test public void backgroundPendingFrameKeepsCurrentTreeAndDoesNotPublishStalePixels()throws Exception {
        BackgroundPhonePort port=background();RunControl run=control();frameGeneration=2;
        JSONObject observed=capture(port,run);
        assertTrue(observed.toString(),observed.optBoolean("ok"));assertFalse(observed.has("imageBase64"));
        assertEquals("frame_pending",observed.getString("imageUnavailableReason"));assertFalse(observed.getBoolean("coordinateActionsAvailable"));
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));
        assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,actions.get());assertEquals(0,helperMutations.get());
    }
    @Test public void aNewWindowDuringBackgroundCaptureStillRejectsTheMixedObservation()throws Exception {
        BackgroundPhonePort port=background();RunControl run=control();windowAppearsDuringFrame=true;
        JSONObject observed=capture(port,run);
        assertFalse(observed.toString(),observed.optBoolean("ok"));assertEquals("screen_changed",observed.getString("error"));assertEquals(0,actions.get());
    }
    @Test public void foregroundScreenshotFailureKeepsFreshNodesWithoutPretendingAnImageExists()throws Exception {
        PhonePort port=foreground();RunControl run=control();
        JSONObject observed=capture(port,run);
        assertTrue(observed.toString(),observed.optBoolean("ok"));assertFalse(observed.has("imageBase64"));
        assertEquals("screenshot_unavailable",observed.getString("imageUnavailableReason"));assertFalse(observed.getBoolean("coordinateActionsAvailable"));
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_click_node",click(observed),run)));
        assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,actions.get());
    }
    @Test public void foregroundCapturePermissionLossNeverBecomesANodeOnlySuccess()throws Exception {
        PhonePort port=foreground();RunControl run=control();screenshotFailure=android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS;
        JSONObject observed=capture(port,run);
        assertFalse(observed.toString(),observed.optBoolean("ok"));assertEquals("permission_denied",observed.getString("error"));assertEquals(0,actions.get());
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
    private static String fixtureImage()throws Exception {
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(320,470,android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());}finally{image.recycle();}
    }
    private JSONObject arguments(JSONObject observed)throws Exception{return new JSONObject().put("observationId",observed.getString("observationId")).put("nodeId","n1").put("text","synthetic-only-123456");}
    private void sensitive(boolean background,String text,String hint,String resource,boolean editable,dev.pocketengine.phone.observation.NativeSensitiveStep.Reason reason)throws Exception {
        PhonePort port=background?background():foreground();target.setText(text);target.setHintText(hint);target.setViewIdResourceName(resource);target.setEditable(editable);
        RunControl run=control();JSONObject observed=observe(port,run);int before=actions.get();
        try{complete(caller.submit(()->port.execute(editable?"phone_type":"phone_click_node",arguments(observed),run)));fail("sensitive native step dispatched");}
        catch(ExecutionException stopped){assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired);var owner=(dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired)stopped.getCause();assertEquals(reason,owner.reason);assertTrue(owner.belongsTo(run,run.pauseRevision()));assertFalse(owner.notDispatchedReceipt().toString().contains("123456"));}
        assertEquals(before,actions.get());assertEquals(0,helperMutations.get());
        JSONObject stale=complete(caller.submit(()->port.execute("phone_click_node",arguments(observed),run)));assertFalse(stale.toString(),stale.optBoolean("accepted"));assertEquals(before,actions.get());
    }
    @Test public void sensitivePaymentAndTransferReachNeitherMainNorBackgroundNativeMutation()throws Exception {
        for(boolean bg:new boolean[]{false,true}){sensitive(bg,"Pay now","","agent.worker:id/pay",false,dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.PAYMENT_CONFIRMATION);sensitive(bg,"确认转账","","agent.worker:id/confirm_transfer",false,dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.TRANSFER_CONFIRMATION);}
    }
    @Test public void cleartextOtpHintAndSystemPermissionGrantAreHumanOnlyOnBothDrivers()throws Exception {
        for(boolean bg:new boolean[]{false,true}){sensitive(bg,"","SMS verification code","agent.worker:id/otp_input",true,dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.LOGIN_CODE);sensitive(bg,"Allow","","com.android.permissioncontroller:id/permission_allow_button",false,dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.PERMISSION_ELEVATION);}
    }
    @Test public void ordinaryMessageSearchAndCodeFieldsStillRunWithoutPerStepApproval()throws Exception {
        for(boolean bg:new boolean[]{false,true})for(String hint:new String[]{"Search","Message","Source code editor","ordinary code"}){
            PhonePort port=bg?background():foreground();target.setText("");target.setHintText(hint);target.setViewIdResourceName("agent.worker:id/ordinary_field");target.setEditable(true);ticker.setText("Payment confirmation 支付金额");RunControl run=control();JSONObject observed=observe(port,run);int before=actions.get();JSONObject receipt=complete(caller.submit(()->port.execute("phone_type",arguments(observed),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(before+1,actions.get());assertFalse(run.isPaused());
        }
    }
    @Test public void changedOtpHintCannotReuseAnOrdinaryFieldReference()throws Exception {
        for(boolean bg:new boolean[]{false,true}){PhonePort port=bg?background():foreground();target.setText("");target.setHintText("Search");target.setEditable(true);RunControl run=control();JSONObject observed=observe(port,run);target.setHintText("SMS verification code");JSONObject refused=complete(caller.submit(()->port.execute("phone_type",arguments(observed),run)));assertFalse(refused.toString(),refused.optBoolean("accepted"));assertEquals(0,actions.get());}
    }
    @Test public void actualBackgroundCoordinatesCannotBypassSensitiveNativeTarget()throws Exception {
        BackgroundPhonePort port=background();target.setText("Confirm payment");RunControl run=control();JSONObject observed=complete(caller.submit(()->port.observe(true,run)));assertTrue(observed.toString(),observed.optBoolean("ok"));
        JSONObject point=new JSONObject().put("observationId",observed.getString("observationId")).put("x",200).put("y",140);
        try{complete(caller.submit(()->port.execute("phone_tap",point,run)));fail("coordinate escaped sensitive native target");}catch(ExecutionException stopped){assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired);}
        assertEquals(0,helperMutations.get());assertEquals(0,actions.get());
    }
    @Test public void recognizedSensitiveSheetWithUnnamedTargetHandsOwnerRatherThanGuessing()throws Exception {
        for(boolean bg:new boolean[]{false,true})for(boolean input:new boolean[]{false,true}){PhonePort port=bg?background():foreground();target.setText("");target.setEditable(input);root.setContentDescription("Payment confirmation");ticker.setText("unrelated ordinary article");RunControl run=control();JSONObject observed=observe(port,run);
            try{complete(caller.submit(()->port.execute(input?"phone_type":"phone_click_node",arguments(observed),run)));fail("unknown target on recognized sensitive sheet dispatched");}catch(ExecutionException stopped){assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired);assertEquals(dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.SENSITIVE_TARGET_UNCERTAIN,((dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired)stopped.getCause()).reason);}assertEquals(0,actions.get());}
    }
    @Test public void originalBackgroundOcrTargetCannotBypassTheHumanOnlyStep()throws Exception {
        BackgroundPhonePort port=background();RunControl run=control();JSONObject observed=complete(caller.submit(()->port.observe(true,run)));assertTrue(observed.toString(),observed.optBoolean("ok"));
        var line=new dev.pocketengine.vision.ocr.OcrPage.Line(1,"Confirm payment",20,40,120,90);var page=new dev.pocketengine.vision.ocr.OcrPage(320,470,320,470,List.of(line));var ctor=dev.pocketengine.phone.observation.OcrTextTarget.class.getDeclaredConstructor(String.class,long.class,RunControl.class,dev.pocketengine.vision.ocr.OcrPage.class,dev.pocketengine.vision.ocr.OcrPage.Line.class);ctor.setAccessible(true);var textTarget=ctor.newInstance(observed.getString("observationId"),run.pauseRevision(),run,page,line);
        try{complete(caller.submit(()->port.tapOcrText(textTarget,run,()->{})));fail("OCR target escaped");}catch(ExecutionException stopped){assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired);}assertEquals(0,actions.get());assertEquals(0,helperMutations.get());
    }
    @Test public void pureImageWithoutRiskEvidenceKeepsItsExistingCoordinatePath()throws Exception {
        BackgroundPhonePort port=background();noTree=true;RunControl run=control();JSONObject observed=complete(caller.submit(()->port.observe(true,run)));assertTrue(observed.toString(),observed.optBoolean("ok"));assertEquals(0,observed.getJSONArray("nodes").length());
        JSONObject receipt=complete(caller.submit(()->port.execute("phone_tap",new JSONObject().put("observationId",observed.getString("observationId")).put("x",500).put("y",500),run)));assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertEquals(1,helperMutations.get());assertFalse(run.isPaused());
    }
    @Test public void unrelatedContainerBodyDoesNotTurnOrdinaryEmptyInputOrConfirmIntoSensitiveSteps()throws Exception {
        for(boolean bg:new boolean[]{false,true})for(boolean input:new boolean[]{true,false}){PhonePort port=bg?background():foreground();target.setText(input?"":"Confirm");target.setEditable(input);target.setViewIdResourceName(input?"app:id/code":"app:id/ordinary_confirm");ticker.setText(input?"SMS verification code documentation":"Pay now");
            AccessibilityNodeInfo note=node(new View(RuntimeEnvironment.getApplication()),"",new Rect(180,180,315,300),false),form=node(new View(RuntimeEnvironment.getApplication()),"",new Rect(10,30,130,100),false);Shadows.shadowOf(note).addChild(ticker);Shadows.shadowOf(form).addChild(target);root=node(rootView,"",new Rect(0,0,320,470),false);Shadows.shadowOf(root).addChild(note);Shadows.shadowOf(root).addChild(form);
            RunControl run=control();JSONObject observed=observe(port,run);String id="";for(int i=0;i<observed.getJSONArray("nodes").length();i++){JSONObject n=observed.getJSONArray("nodes").getJSONObject(i);if(target.getViewIdResourceName().equals(n.optString("resourceId")))id=n.getString("id");}assertFalse(id.isEmpty());JSONObject args=new JSONObject().put("observationId",observed.getString("observationId")).put("nodeId",id).put("text","ordinary source code");int before=actions.get();JSONObject receipt=complete(caller.submit(()->port.execute(input?"phone_type":"phone_click_node",args,run)));assertTrue(receipt.toString(),receipt.getBoolean("accepted"));assertEquals(before+1,actions.get());assertFalse(run.isPaused());
        }
    }
    @Test public void directlyAssociatedOtpLabelStillHandsTheFieldToItsOwner()throws Exception {
        for(boolean bg:new boolean[]{false,true}){PhonePort port=bg?background():foreground();target.setText("");target.setEditable(true);target.setViewIdResourceName("app:id/code");ticker.setText("SMS verification code");ticker.setBoundsInScreen(new Rect(20,5,120,30));RunControl run=control();JSONObject observed=observe(port,run);
            try{complete(caller.submit(()->port.execute("phone_type",arguments(observed),run)));fail("local OTP label was ignored");}catch(ExecutionException stopped){assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired);assertEquals(dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.LOGIN_CODE,((dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired)stopped.getCause()).reason);}assertEquals(0,actions.get());}
    }
    @Test public void backgroundSwipeEndingAcrossRecognizedPaymentSliderHasNoHelperMutation()throws Exception {
        BackgroundPhonePort port=background();ticker.setText("Slide to pay");RunControl run=control();JSONObject observed=complete(caller.submit(()->port.observe(true,run)));assertTrue(observed.toString(),observed.getBoolean("ok"));JSONObject stroke=new JSONObject().put("observationId",observed.getString("observationId")).put("x1",100).put("y1",470).put("x2",980).put("y2",470).put("durationMs",500);
        try{complete(caller.submit(()->port.execute("phone_swipe",stroke,run)));fail("late sensitive slider region escaped");}catch(ExecutionException stopped){assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired);}assertEquals(0,helperMutations.get());assertEquals(0,actions.get());
        JSONObject fresh=complete(caller.submit(()->port.observe(true,run)));stroke.put("observationId",fresh.getString("observationId")).put("y1",850).put("y2",850);JSONObject ordinary=complete(caller.submit(()->port.execute("phone_swipe",stroke,run)));assertTrue(ordinary.toString(),ordinary.getBoolean("accepted"));assertEquals(1,helperMutations.get());
    }
    private static JSONObject nativeArguments(JSONObject observation,String resource)throws Exception {
        JSONArray nodes=observation.getJSONArray("nodes");String id="";for(int i=0;i<nodes.length();i++)if(resource.equals(nodes.getJSONObject(i).optString("resourceId")))id=nodes.getJSONObject(i).getString("id");assertFalse(id.isEmpty());return new JSONObject().put("observationId",observation.getString("observationId")).put("nodeId",id).put("text","ordinary synthetic input");
    }
    @Test public void nativeOtpPurposeSurvivesPublicBodyClippingWithoutRetainingPrivateText()throws Exception {
        for(boolean bg:new boolean[]{false,true}){PhonePort port=bg?background():foreground();target.setText("");target.setHintText("SMS verification code");target.setEditable(true);target.setViewIdResourceName("app:id/code");root=node(rootView,"",new Rect(0,0,320,470),false);
            for(int group=0;group<2;group++){AccessibilityNodeInfo container=node(new View(RuntimeEnvironment.getApplication()),"",new Rect(180,180,315,450),false);for(int i=0;i<40;i++)Shadows.shadowOf(container).addChild(node(new View(RuntimeEnvironment.getApplication()),"ordinary article ".repeat(30),new Rect(200,200,300,240),false));Shadows.shadowOf(root).addChild(container);}Shadows.shadowOf(root).addChild(target);
            RunControl run=control();JSONObject observed=observe(port,run);assertTrue(observed.getJSONObject("treeCoverage").getBoolean("bodyTruncated"));JSONObject args=nativeArguments(observed,"app:id/code");JSONObject shown=null;for(int i=0;i<observed.getJSONArray("nodes").length();i++)if(args.getString("nodeId").equals(observed.getJSONArray("nodes").getJSONObject(i).getString("id")))shown=observed.getJSONArray("nodes").getJSONObject(i);assertNotNull(shown);assertEquals("",shown.getString("hint"));
            try{complete(caller.submit(()->port.execute("phone_type",args,run)));fail("public body budget erased native OTP purpose");}catch(ExecutionException stopped){assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired);}assertEquals(0,actions.get());
        }
    }
    @Test public void linkedPlatformLabelIsFreshnessBoundAndStillGatesAnOtherwiseEmptyField()throws Exception {
        for(boolean bg:new boolean[]{false,true}){PhonePort port=bg?background():foreground();target.setText("");target.setEditable(true);target.setViewIdResourceName("app:id/code");ticker.setText("Ordinary field");Shadows.shadowOf(target).setLabeledBy(ticker);RunControl run=control();JSONObject observed=observe(port,run);ticker.setText("SMS verification code");/* Robo stores a copy; expose the provider's NEW label snapshot explicitly. */Shadows.shadowOf(target).setLabeledBy(ticker);assertEquals("SMS verification code",target.getLabeledBy().getText().toString());JSONObject old=complete(caller.submit(()->port.execute("phone_type",arguments(observed),run)));assertFalse(old.toString(),old.optBoolean("accepted"));assertEquals(0,actions.get());
            JSONObject fresh=observe(port,run);try{complete(caller.submit(()->port.execute("phone_type",arguments(fresh),run)));fail("platform labelFor purpose ignored");}catch(ExecutionException stopped){assertTrue(stopped.getCause().toString(),stopped.getCause() instanceof dev.pocketengine.phone.observation.NativeSensitiveStep.OwnerRequired);}assertEquals(0,actions.get());}
    }
    @Test public void existingOrdinaryMessageValueTalkingAboutOtpIsNotTheFieldPurpose()throws Exception {
        for(boolean bg:new boolean[]{false,true})for(String hint:new String[]{"Message","Source code editor"}){PhonePort port=bg?background():foreground();target.setText("SMS verification code / Pay now are ordinary quoted strings");target.setHintText(hint);target.setEditable(true);target.setViewIdResourceName("app:id/ordinary");RunControl run=control();JSONObject observed=observe(port,run);int before=actions.get();JSONObject receipt=complete(caller.submit(()->port.execute("phone_type",arguments(observed),run)));assertTrue(receipt.toString(),receipt.getBoolean("accepted"));assertEquals(before+1,actions.get());}
    }
    @Test public void ordinarySearchAndEditorIdentityKeepsItsMeaningBesideOtpDocumentation()throws Exception {
        for(boolean bg:new boolean[]{false,true}){for(String id:new String[]{"app:id/search","app:id/code_editor"}){PhonePort port=bg?background():foreground();target.setText("");target.setHintText("");target.setEditable(true);target.setViewIdResourceName(id);ticker.setText("SMS verification code documentation");ticker.setBoundsInScreen(new Rect(20,5,120,30));RunControl run=control();JSONObject observed=observe(port,run);int before=actions.get();JSONObject receipt=complete(caller.submit(()->port.execute("phone_type",arguments(observed),run)));assertTrue(receipt.toString(),receipt.getBoolean("accepted"));assertEquals(before+1,actions.get());}
            sensitive(bg,"","SMS verification code","app:id/code_editor",true,dev.pocketengine.phone.observation.NativeSensitiveStep.Reason.LOGIN_CODE);
        }
    }
    private static JSONObject task(int id,int display,String pkg)throws Exception{return new JSONObject().put("taskId",id).put("rootTaskId",id).put("displayId",display).put("androidUserId",0).put("packageName",pkg).put("component",pkg+"/.Main").put("rootIdentity","root-"+id);}
    private static JSONObject resumed(int id,int display,String pkg)throws Exception{return new JSONObject().put("taskId",id).put("displayId",display).put("packageName",pkg);}
    private static JSONObject facts(String primary,int primaryTask,int originalDisplay,boolean replacementOwned)throws Exception {
        JSONArray tasks=new JSONArray().put(task(primaryTask,0,primary)).put(task(22,originalDisplay,"agent.worker"));JSONArray owned=new JSONArray();
        if(originalDisplay==helperDisplay)owned.put(resumed(22,helperDisplay,"agent.worker"));else if(replacementOwned){tasks.put(task(44,helperDisplay,"agent.replacement"));owned.put(resumed(44,helperDisplay,"agent.replacement"));}
        return new JSONObject().put("status","observed").put("primary",new JSONArray().put(resumed(primaryTask,0,primary))).put("owned",owned)
                .put("taskTopology",new JSONObject().put("schemaVersion",1).put("status","observed").put("complete",true).put("readOnly",true).put("tasks",tasks));
    }
}
