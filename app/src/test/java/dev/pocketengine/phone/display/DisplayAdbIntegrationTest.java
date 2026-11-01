package dev.pocketengine.phone.display;

import android.app.Application;
import android.os.Process;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.display.adb.AdbBootstrap;
import dev.pocketengine.phone.display.adb.AdbBootstrapClient;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Module admission/callback fixture, not real Android Keystore, shell socket or ADB transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DisplayAdbIntegrationTest {
    private DisplayModule module;
    private FakeAdb adb;
    private JSONObject probe;
    @Before public void prepare()throws Exception {
        probe=status(false,"",-1);
        FeatureContext feature=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{});
        module=new DisplayModule(feature,changed->adb=new FakeAdb(changed),()->new JSONObject().put("ownerUid",Process.myUid()).put("socketName","pocketengine.display."+"a".repeat(32)).put("token","b".repeat(64)),()->new JSONObject(probe.toString()));
    }
    @After public void close(){module.close();}
    private static JSONObject status(boolean active,String session,int display)throws Exception{return new JSONObject().put("ok",true).put("protocol",1).put("uid",2000).put("sdk",35).put("sessionActive",active).put("displayId",active?display:JSONObject.NULL).put("sessionId",active?session:JSONObject.NULL);}
    private JSONObject endpoint()throws Exception{return new JSONObject().put("host","127.0.0.1").put("pairingPort",37111).put("connectPort",37112).put("pairingCode","123456");}
    @Test public void defaultPhoneOnlySetupKeepsDiscoveryAndPairingSeparateFromReadiness()throws Exception {
        assertEquals("wireless_adb",module.snapshot().getString("authorizationBackend"));
        module.handle("adb.discover",new JSONObject());assertEquals(1,adb.discoveries);assertFalse(module.snapshot().getBoolean("helperConnected"));
        module.handle("adb.pair",endpoint());assertEquals(1,adb.pairs);assertFalse(module.snapshot().getBoolean("helperConnected"));assertFalse(module.hasSession());
        adb.update("paired",false,false);assertFalse(module.snapshot().getBoolean("helperConnected"));
    }
    @Test public void readingSetupNeverSwitchesAnExistingCompatibilityBackend()throws Exception {
        android.content.SharedPreferences saved=RuntimeEnvironment.getApplication().getSharedPreferences("display_backend",0);
        saved.edit().putString("backend","shizuku").commit();assertEquals("shizuku",module.snapshot().getString("authorizationBackend"));
        module.handle("adb.discover",new JSONObject());assertEquals("shizuku",module.snapshot().getString("authorizationBackend"));
        saved.edit().remove("backend").putString("socketName","legacy-owner-socket").commit();assertEquals("adb",module.snapshot().getString("authorizationBackend"));
    }
    @Test public void socketProofAndFinalReadyReceiptAreBothRequiredAndConsumedOnce()throws Exception {
        module.handle("adb.connect",endpoint());assertFalse(module.snapshot().getBoolean("helperConnected"));assertEquals(1,adb.connects);
        adb.status.check();assertFalse("a pre-ACK socket probe is not final readiness",module.snapshot().getBoolean("helperConnected"));
        adb.update("ready",false,true);assertTrue(module.snapshot().getBoolean("helperConnected"));assertFalse(module.hasSession());
        module.acceptStatus(status(true,"real-session",9));adb.update("ready",false,true);
        assertEquals("real-session",module.session().getString("sessionId"));assertTrue(module.snapshot().getBoolean("recoveryRequired"));
    }
    @Test public void malformedStatusCannotTurnAConnectOrPairedCallbackIntoReady()throws Exception {
        probe=status(true,"wrong-main-screen",0);module.handle("adb.connect",endpoint());
        assertThrows(SecurityException.class,()->adb.status.check());adb.update("ready",false,true);assertFalse(module.snapshot().getBoolean("helperConnected"));
    }
    @Test public void busyBootstrapBlocksTaskAdmissionAndConfigurationUntilCancelledAndReclaimed()throws Exception {
        module.handle("adb.connect",endpoint());assertThrows(IllegalStateException.class,()->module.pinOwner("phone-task"));
        for(String command:new String[]{"setup","reset","create","shizuku.start","adb.pair","adb.forget"})assertThrows(command,IllegalStateException.class,()->module.handle(command,endpoint()));
        module.handle("adb.cancel",new JSONObject());assertEquals(1,adb.cancellations);assertThrows(IllegalStateException.class,()->module.pinOwner("phone-task"));
        adb.update("bootstrap_process_exited",false,false);module.pinOwner("phone-task");
        for(String command:new String[]{"adb.status","adb.discover","adb.pair","adb.connect","adb.cancel","adb.forget"})assertThrows(command,IllegalStateException.class,()->module.handle(command,endpoint()));
    }
    @Test public void lateProofFromBeforeCancellationOrNewOwnerCannotOverwriteSession()throws Exception {
        module.handle("adb.connect",endpoint());AdbBootstrap.AuthenticatedStatus old=adb.status;module.handle("adb.cancel",new JSONObject());adb.update("bootstrap_process_exited",false,false);
        module.pinOwner("new-task");module.acceptStatus(status(true,"owned-new",11));
        assertThrows(IllegalStateException.class,old::check);adb.update("ready",false,true);
        assertEquals("owned-new",module.session().getString("sessionId"));
    }
    @Test public void unknownReleaseBlocksForgetPairAndResetWithoutRotatingIdentity()throws Exception {
        module.acceptStatus(status(true,"old-session",7));module.noteReleasePending("old-session",7);
        for(String command:new String[]{"adb.forget","adb.pair","reset","setup"})assertThrows(command,Exception.class,()->module.handle(command,endpoint()));
        assertEquals(0,adb.forgets);assertEquals(0,adb.pairs);assertTrue(module.snapshot().getBoolean("releasePending"));
        // The same backend may reconnect using the original identity to settle absence.
        module.handle("adb.connect",endpoint());adb.status.check();adb.update("ready",false,true);
        assertFalse(module.snapshot().getBoolean("releasePending"));assertFalse(module.hasSession());
    }
    @Test public void unknownBootstrapBlocksAdmissionAndReconfigurationUntilOwnerConnectionSettlesIt()throws Exception {
        adb.recovery=true;adb.update("pair_outcome_unknown",false,false);module.acceptStatus(status(false,"",-1));
        assertFalse(module.isConnected());assertThrows(IllegalStateException.class,()->module.pinOwner("phone-task"));
        for(String command:new String[]{"setup","reset","create","shizuku.start","adb.pair"})assertThrows(command,IllegalStateException.class,()->module.handle(command,endpoint()));
        assertEquals(0,adb.pairs);module.handle("adb.connect",endpoint());adb.status.check();adb.recovery=false;adb.update("ready",false,true);
        assertTrue(module.isConnected());module.pinOwner("phone-task");
    }
    @Test public void invalidEndpointsAreRejectedBeforeChangingTheSelectedBackend()throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("display_backend",0).edit().putString("backend","shizuku").commit();
        assertThrows(SecurityException.class,()->module.handle("adb.connect",endpoint().put("host","example.com")));
        assertThrows(IllegalArgumentException.class,()->module.handle("adb.pair",endpoint().put("pairingCode","12345")));
        assertEquals("shizuku",module.snapshot().getString("authorizationBackend"));assertEquals(0,adb.connects);assertEquals(0,adb.pairs);
    }
    private static final class FakeAdb implements AdbBootstrapClient {
        final Consumer<JSONObject> changed;String phase="idle";boolean busy,authenticated,recovery;int discoveries,pairs,connects,cancellations,forgets;AdbBootstrap.AuthenticatedStatus status;
        FakeAdb(Consumer<JSONObject> changed){this.changed=changed;}
        public JSONObject snapshot(){try{return new JSONObject().put("phase",phase).put("busy",busy).put("helperAuthenticated",authenticated).put("recoveryRequired",recovery);}catch(Exception impossible){throw new AssertionError(impossible);}}
        public JSONObject discover(){discoveries++;return snapshot();}
        public JSONObject pair(JSONObject input){pairs++;update("pairing",true,false);return snapshot();}
        public JSONObject connect(JSONObject input,JSONObject configuration,AdbBootstrap.AuthenticatedStatus check){connects++;status=check;update("connecting",true,false);return snapshot();}
        public JSONObject cancel(){cancellations++;update("terminating",true,false);return snapshot();}
        public JSONObject forgetIdentity(){forgets++;update("forgetting",true,false);return snapshot();}
        public void close(){}
        void update(String phase,boolean busy,boolean authenticated){this.phase=phase;this.busy=busy;this.authenticated=authenticated;changed.accept(snapshot());}
    }
}
