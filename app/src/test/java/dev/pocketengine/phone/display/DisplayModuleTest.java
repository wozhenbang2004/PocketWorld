package dev.pocketengine.phone.display;

import android.app.Application;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.core.RunControl;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Recovery tests do not create a real Android display or grant shell authorization. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class DisplayModuleTest {
    private final java.util.List<DisplayModule> modules=new java.util.ArrayList<>();
    private DisplayModule module(){
        // These recovery fixtures represent the existing ADB backend, independently
        // of whether a Shizuku server is installed/running on a real device.
        RuntimeEnvironment.getApplication().getSharedPreferences("display_backend",0).edit().putString("backend","adb").commit();
        DisplayModule module=new DisplayModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));modules.add(module);return module;
    }
    @After public void closeModules(){for(DisplayModule module:modules)module.close();}
    private JSONObject status(boolean active,String session,int display)throws Exception {
        return new JSONObject().put("ok",true).put("protocol",1).put("uid",2000).put("sdk",35)
                .put("sessionActive",active).put("sessionId",active?session:JSONObject.NULL)
                .put("displayId",active?display:JSONObject.NULL);
    }
    private void field(DisplayModule module,String name,Object value)throws Exception {
        Field field=DisplayModule.class.getDeclaredField(name);field.setAccessible(true);field.set(module,value);
    }
    private JSONObject geometryStatus(long generation,int rotation)throws Exception {
        return new dev.pocketengine.displayhelper.DisplayGeometry(generation,(rotation&1)==0?720:1280,(rotation&1)==0?1280:720,240,rotation)
                .writeTo(status(true,"original",7),true);
    }
    @Test public void authenticatedGeometryAdvancesOriginalSessionWithoutCreatingAnotherDisplay()throws Exception {
        DisplayModule module=module();field(module,"state",geometryStatus(1,0));module.acceptStatus(geometryStatus(2,1));
        JSONObject current=module.session();assertEquals("original",current.getString("sessionId"));assertEquals(7,current.getInt("displayId"));
        assertEquals(2,current.getLong("geometryGeneration"));assertEquals(1280,current.getInt("width"));assertEquals(720,current.getInt("height"));
        assertEquals(720,current.getInt("captureWidth"));assertEquals(1280,current.getInt("captureHeight"));assertTrue(module.hasSession());
    }
    @Test public void delayedOrDowngradedStatusCannotRestoreAnOldGeometry()throws Exception {
        DisplayModule module=module();field(module,"state",geometryStatus(3,0));
        assertThrows(SecurityException.class,()->module.acceptStatus(geometryStatus(1,0)));
        assertThrows(SecurityException.class,()->module.acceptStatus(geometryStatus(3,2)));
        assertThrows(SecurityException.class,()->module.acceptStatus(status(true,"original",7)));
        assertEquals(3,module.session().getLong("geometryGeneration"));assertEquals(0,module.session().getInt("rotation"));
    }
    @Test public void delayedFrameCannotRollBackOrWriteIntoAReplacementSession()throws Exception {
        DisplayModule module=module();field(module,"state",geometryStatus(3,0));
        java.lang.reflect.Method adopt=DisplayModule.class.getDeclaredMethod("adoptFrameGeometry",JSONObject.class,String.class,int.class);adopt.setAccessible(true);
        java.lang.reflect.InvocationTargetException stale=assertThrows(java.lang.reflect.InvocationTargetException.class,()->adopt.invoke(module,geometryStatus(1,0),"original",7));assertTrue(stale.getCause() instanceof SecurityException);
        field(module,"state",status(true,"replacement",9));
        java.lang.reflect.InvocationTargetException replaced=assertThrows(java.lang.reflect.InvocationTargetException.class,()->adopt.invoke(module,geometryStatus(4,1),"original",7));assertTrue(replaced.getCause() instanceof SecurityException);
        assertEquals("replacement",module.session().getString("sessionId"));assertEquals(9,module.session().getInt("displayId"));
    }
    @Test public void authenticatedAbsenceSettlesLostReleaseAndAllowsNewAdmission()throws Exception {
        DisplayModule module=module();field(module,"state",status(true,"previous",7));field(module,"releasePending",true);
        module.acceptStatus(status(false,"",-1));
        assertFalse(module.snapshot().getBoolean("releasePending"));assertFalse(module.hasSession());
        assertTrue(module.releaseSession("previous",7).getBoolean("releaseConfirmed"));
        // A display created by the task has been recorded locally before its next heartbeat.
        field(module,"state",status(true,"new-task",9));module.acceptStatus(status(true,"new-task",9));
        assertTrue(module.hasSession());
    }
    @Test public void activeOrphanRemainsBlockedAndOldOwnerCannotReleaseIt()throws Exception {
        DisplayModule module=module();module.acceptStatus(status(true,"orphan",8));
        assertTrue(module.snapshot().getBoolean("recoveryRequired"));assertFalse(module.hasSession());
        JSONObject old=module.releaseSession("old-task",7);assertFalse(old.getBoolean("releaseConfirmed"));
        assertEquals("orphan",module.session().getString("sessionId"));
    }
    @Test public void helperCannotClaimAnActivePrimaryOrNamelessDisplay()throws Exception {
        for(JSONObject invalid:new JSONObject[]{status(true,"bad",0),status(true,"",7)}){
            try{module().acceptStatus(invalid);fail("An invalid active display must be refused");}
            catch(SecurityException expected){}
        }
    }
    @Test public void taskPinAtomicallyBlocksManualSessionChangesAndOnlyOwnerCanUnpin()throws Exception {
        DisplayModule module=module();module.pinOwner("task-a");module.pinOwner("task-a");
        for(String command:new String[]{"setup","connect","status","create","release","reset","observe","shizuku.status","shizuku.permission","shizuku.start","shizuku.stop","adb.status","adb.discover","adb.pair","adb.connect","adb.cancel","adb.forget"}){
            try{module.handle(command,new JSONObject());fail("Task ownership must block manual "+command);}
            catch(IllegalStateException expected){}
        }
        try{module.pinOwner("task-b");fail("A second task cannot acquire the same display module");}catch(IllegalStateException expected){}
        module.unpinOwner("task-b");assertTrue(module.snapshot().getBoolean("taskOwned"));
        module.unpinOwner("task-a");assertFalse(module.snapshot().getBoolean("taskOwned"));
    }
    @Test public void pausedOrResumedOldRequestIsRefusedBeforeConnectingToHelper()throws Exception {
        RunControl control=new RunControl();long observed=control.pauseRevision();control.pause();
        ShellBridgeClient client=new ShellBridgeClient("no-such-helper","no-token");
        try{client.call("tap",new JSONObject(),control,observed);fail("A paused action must not connect");}
        catch(ShellBridgeClient.StaleRequestException expected){}
        control.resume();
        try{client.call("tap",new JSONObject(),control,observed);fail("A decision from before the pause must not connect after resume");}
        catch(ShellBridgeClient.StaleRequestException expected){}
    }
    @Test public void missingShizukuNeverClaimsAConnectedHelperOrRequestsCredentials()throws Exception {
        DisplayModule module=module();
        JSONObject authorization=module.handle("shizuku.permission",new JSONObject());
        assertFalse(authorization.getBoolean("binderAvailable"));assertFalse(authorization.getBoolean("permissionGranted"));
        assertFalse(authorization.getBoolean("starting"));
        // No SecretVault exists in this isolated fixture; an unavailable service must
        // return before attempting to create or rotate privileged connection secrets.
        JSONObject started=module.handle("shizuku.start",new JSONObject());
        assertFalse(started.getBoolean("userServiceBound"));assertFalse(module.snapshot().getBoolean("helperConnected"));
        assertEquals("adb",module.snapshot().getString("authorizationBackend"));
    }
    @Test public void newInstallationOffersOnDeviceStartupWithoutClaimingAuthorization()throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("display_backend",0).edit().clear().commit();
        DisplayModule module=new DisplayModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));modules.add(module);
        JSONObject snapshot=module.snapshot();assertEquals("wireless_adb",snapshot.getString("authorizationBackend"));
        assertFalse(snapshot.getBoolean("requiresAdbBootstrap"));assertFalse(snapshot.getBoolean("helperConnected"));
    }
    private BackgroundPhonePort ownedPort(DisplayModule module)throws Exception {
        field(module,"state",status(true,"owned-display",7));module.acceptStatus(status(true,"owned-display",7));module.pinOwner("task-a");
        return new BackgroundPhonePort(module);
    }
    @Test public void cachedOwnerViewIsIsolatedAndCannotAuthorizeManualInput()throws Exception {
        DisplayModule module=module();BackgroundPhonePort port=ownedPort(module);
        Field cache=BackgroundPhonePort.class.getDeclaredField("cached");cache.setAccessible(true);
        cache.set(port,new JSONObject().put("ok",true).put("observationId","fixture-observation").put("imageBase64","fixture-only"));
        JSONObject first=port.cachedObservationForOwner("task-a");assertFalse(first.getBoolean("manualAllowed"));assertTrue(first.getBoolean("referencesExpired"));
        first.put("imageBase64","changed-by-viewer");assertEquals("fixture-only",port.cachedObservationForOwner("task-a").getString("imageBase64"));
        assertThrows(SecurityException.class,()->port.cachedObservationForOwner("other-task"));
    }
    @Test public void inlineOwnerPreviewRequiresTheLifecycleBoundOriginalRunAndOnlyLabelsItsCache()throws Exception {
        DisplayModule module=module();BackgroundPhonePort port=ownedPort(module);RunControl original=new RunControl();original.bindHostRun("task-a","original-run",2);
        assertThrows(SecurityException.class,()->port.requireOwnerObservationRun("task-a","original-run",2,original));
        port.bindLifecycle(RuntimeEnvironment.getApplication(),"task-a","original-run",2,original);
        port.requireOwnerObservationRun("task-a","original-run",2,original);
        assertThrows(SecurityException.class,()->port.requireOwnerObservationRun("task-a","new-run",2,original));
        assertThrows(SecurityException.class,()->port.requireOwnerObservationRun("task-a","original-run",3,original));
        RunControl substitute=new RunControl();substitute.bindHostRun("task-a","original-run",2);
        assertThrows(SecurityException.class,()->port.requireOwnerObservationRun("task-a","original-run",2,substitute));
        JSONObject noFrame=port.cachedObservationForOwner("task-a");assertEquals("original-run",noFrame.getString("runId"));assertEquals(2,noFrame.getInt("coordinatorEpoch"));assertEquals("no_observation",noFrame.getString("error"));
        original.cancel();assertThrows(SecurityException.class,()->port.requireOwnerObservationRun("task-a","original-run",2,original));
    }
    @Test public void manualInputRequiresPausedCurrentTaskAndPinnedDisplayIdentity()throws Exception {
        DisplayModule module=module();BackgroundPhonePort port=ownedPort(module);RunControl control=new RunControl();
        JSONObject target=new JSONObject().put("displayId",7).put("sessionId","owned-display");
        assertThrows(SecurityException.class,()->port.manualActionForOwner("task-a","phone_back",target,control));
        control.pause();
        assertThrows(SecurityException.class,()->port.observeForOwner("other-task",control));
        assertThrows(SecurityException.class,()->port.manualActionForOwner("task-a","phone_back",new JSONObject().put("displayId",0).put("sessionId","owned-display"),control));
        assertThrows(SecurityException.class,()->port.manualActionForOwner("task-a","phone_back",new JSONObject().put("displayId",7).put("sessionId","old-session"),control));
        assertThrows(SecurityException.class,()->port.manualActionForOwner("task-a","phone_home",target,control));
    }
    @Test public void secretInputRequiresTakeoverAndNeverEchoesValueInAStaleReceipt()throws Exception {
        DisplayModule module=module();BackgroundPhonePort port=ownedPort(module);RunControl control=new RunControl();
        JSONObject target=new JSONObject().put("displayId",7).put("sessionId","owned-display").put("nodeId","n1").put("observationId","expired");
        String secret="private-test-value-do-not-echo";
        assertThrows(SecurityException.class,()->port.manualSecretInputForOwner("task-a",target,secret,control));
        control.pause();
        java.util.concurrent.FutureTask<JSONObject> call=new java.util.concurrent.FutureTask<>(()->port.manualSecretInputForOwner("task-a",target,secret,control));
        Thread worker=new Thread(call);worker.start();JSONObject receipt=call.get(2,java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(receipt.getBoolean("ok"));assertEquals("stale_observation",receipt.getString("error"));
        assertFalse(receipt.toString().contains(secret));assertFalse(receipt.has("text"));assertFalse(receipt.has("length"));
    }
    @Test public void secretInputGateRejectsPlaintextAndNoneditableTargetsBeforeDispatch()throws Exception {
        AccessibilityNodeInfo target=AccessibilityNodeInfo.obtain();
        try{
            target.setEditable(true);target.setPassword(false);JSONObject plain=BackgroundPhonePort.privateInputRejection(target);
            assertNotNull(plain);assertEquals("requires_user",plain.getString("error"));assertFalse(plain.getBoolean("accepted"));
            target.setPassword(true);target.setEditable(false);assertNotNull(BackgroundPhonePort.privateInputRejection(target));
            target.setEditable(true);assertNull(BackgroundPhonePort.privateInputRejection(target));
        }finally{target.recycle();}
    }
    @Test public void pendingReleaseBarrierDoesNotWaitForTheNetworkOperationMonitor()throws Exception {
        DisplayModule module=module();BackgroundPhonePort port=ownedPort(module);
        java.util.concurrent.CountDownLatch held=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        Thread networkOwner=new Thread(()->{
            synchronized(module){held.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
        });
        java.util.concurrent.FutureTask<Void> call=new java.util.concurrent.FutureTask<>(()->{port.noteReleasePending();return null;});
        Thread cleanupAdmission=new Thread(call);
        networkOwner.start();
        try{
            assertTrue(held.await(2,java.util.concurrent.TimeUnit.SECONDS));cleanupAdmission.start();
            call.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(module.snapshot().getBoolean("releasePending"));assertFalse(module.hasSession());
        }finally{release.countDown();networkOwner.join(2000);cleanupAdmission.join(2000);}
        module.acceptStatus(status(false,"",-1));assertFalse(module.snapshot().getBoolean("releasePending"));
    }
    @Test public void lateOldPortCannotMarkAReplacementSessionForRelease()throws Exception {
        DisplayModule module=module();BackgroundPhonePort old=ownedPort(module);
        field(module,"state",status(true,"replacement",9));module.acceptStatus(status(true,"replacement",9));
        old.noteReleasePending();assertFalse(module.snapshot().getBoolean("releasePending"));assertTrue(module.hasSession());
        assertEquals("replacement",module.session().getString("sessionId"));
    }
}
