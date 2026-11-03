package dev.pocketengine.phone.display;

import android.app.Application;
import android.content.Context;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.store.SecretVault;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Actual original port/ledger/client/wire. The privileged peer and appops are explicit
 * transport fixtures; this does not claim a physical display, shell grant or appops test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class,shadows={PhoneAudioNativeBridgeTest.Socket.class,PhoneAudioNativeBridgeTest.Vault.class,PhoneAudioNativeBridgeTest.Safety.class})
public final class PhoneAudioNativeBridgeTest {
    static int peerUid,payloads,sets,launches,ownershipReads,releases;static String mode,session;static JSONObject lastSet,lastRelease;static boolean dropSetReceipt;
    static Runnable afterGet;static RunControl cancelAfterSet;
    @Before public void reset(){peerUid=2000;payloads=sets=launches=ownershipReads=releases=0;mode="allow";session="original-audio-session";lastSet=lastRelease=null;afterGet=null;cancelAfterSet=null;dropSetReceipt=false;}
    @After public void clear()throws Exception{afterGet=null;cancelAfterSet=null;Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);}
    @Implements(SecretVault.class) public static class Vault {@Implementation public String get(String slot){return "fixture-token";}}
    @Implements(PhoneAccessibilityService.class) public static class Safety {@Implementation public String observationSafetyFailure(){return null;}}
    @Implements(LocalSocket.class) public static class Socket {
        private boolean closed;private byte[] response;private final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        @Implementation public void connect(LocalSocketAddress ignored){}
        @Implementation public void setSoTimeout(int ignored){}
        @Implementation public Credentials getPeerCredentials(){return new Credentials(1,peerUid,2000);}
        @Implementation public OutputStream getOutputStream(){return new OutputStream(){
            @Override public void write(int b)throws IOException{if(closed)throw new IOException("closed");sent.write(b);}
            @Override public void flush()throws IOException{try{
                JSONObject request=new JSONObject(DisplayWire.read(new DataInputStream(new ByteArrayInputStream(sent.toByteArray())),32768));payloads++;String command=request.getString("command");JSONObject reply=status();
                if(command.equals("ownership")){ownershipReads++;reply.put("ownership",ownership());}
                if(command.equals("audio.get")){reply.put("mode",mode);Runnable callback=afterGet;afterGet=null;if(callback!=null)callback.run();}
                if(command.equals("audio.set")){sets++;lastSet=new JSONObject(request.toString());mode=request.getString("mode");reply.put("accepted",true).put("verified",false);}
                if(command.equals("launch"))launches++;
                if(command.equals("release")){releases++;lastRelease=new JSONObject(request.toString());reply.put("released",true).put("sessionActive",false);}
                reply.put("requestId",request.getString("requestId"));ByteArrayOutputStream encoded=new ByteArrayOutputStream();DisplayWire.write(new DataOutputStream(encoded),reply.toString(),8*1024*1024);response=encoded.toByteArray();
                if(command.equals("audio.set")&&cancelAfterSet!=null){RunControl c=cancelAfterSet;cancelAfterSet=null;c.cancel();}
                if(command.equals("audio.set")&&dropSetReceipt)closed=true;
            }catch(Exception failure){throw new IOException(failure);}}
        };}
        @Implementation public InputStream getInputStream()throws IOException{if(closed)throw new IOException("cancelled after wire");return new ByteArrayInputStream(response);}
        @Implementation public void close(){closed=true;}
    }
    private static JSONObject status()throws Exception{return new JSONObject().put("ok",true).put("protocol",1).put("uid",2000).put("sdk",35).put("sessionActive",true).put("sessionId",session).put("displayId",5).put("width",101).put("height",101).put("imeIsolation","available").put("backgroundOnly",true).put("focusIsolationRequested",true).put("displayFlagsConfirmed",true);}
    /** Explicit read-only peer fixture: the original owned display is empty before launch. */
    private static JSONObject ownership()throws Exception {
        JSONObject primary=new JSONObject().put("taskId",1).put("rootTaskId",1).put("displayId",0).put("androidUserId",0).put("userId",0)
                .put("packageName","example.launcher").put("component","example.launcher/.Home").put("rootIdentity","fixture-primary-root");
        return new JSONObject().put("status","observed").put("primary",new JSONArray().put(primary)).put("owned",new JSONArray())
                .put("taskTopology",new JSONObject().put("schemaVersion",1).put("status","observed").put("complete",true).put("readOnly",true).put("tasks",new JSONArray().put(primary)));
    }
    private static void field(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private final class Fixture implements AutoCloseable {
        final Context app=RuntimeEnvironment.getApplication();final DisplayModule module;final BackgroundPhonePort port;final RunControl original=new RunControl();
        Fixture()throws Exception{
            PhoneAccessibilityService service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();Field singleton=PhoneAccessibilityService.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,service);
            app.getSharedPreferences("phone_audio_policy",0).edit().clear().commit();app.getSharedPreferences("phone_audio_recovery",0).edit().clear().commit();
            app.getSharedPreferences("display_backend",0).edit().putString("backend","adb").putString("socketName","fixture").commit();
            module=new DisplayModule(new FeatureContext(app,null,new SecretVault(app),event->{}));field(module,"state",status());module.acceptStatus(status());module.pinOwner("original-task");original.bindHostRun("original-task","original-run",1);port=new BackgroundPhonePort(module);port.bindLifecycle(app,"original-task","original-run",1,original);
        }
        void quiet()throws Exception {
            var entry=BackgroundPhonePort.class.getDeclaredMethod("beforeOpenAudio",String.class,RunControl.class,long.class);entry.setAccessible(true);
            try{entry.invoke(port,"example.music",original,0L);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception e)throw e;throw failure;}
        }
        int pending()throws Exception{return AndroidPhoneAudioRecovery.get(app).snapshot().getInt("pendingCount");}
        void enable(){AndroidPhoneAudioRecovery.setOwnerSilenceEnabled(app,true);}
        public void close(){module.close();}
    }
    @Test public void defaultOffSendsNoAudioCommandAndExplicitPolicyUsesPinnedNativeWire()throws Exception{try(Fixture f=new Fixture()){
        f.quiet();assertEquals(0,payloads);assertEquals(0,f.pending());f.enable();f.quiet();assertEquals(1,sets);assertEquals("ignore",mode);assertEquals(1,f.pending());
        assertEquals("example.music",lastSet.getString("packageName"));assertEquals(0,lastSet.getInt("userId"));assertEquals("original-audio-session",lastSet.getString("sessionId"));assertEquals(5,lastSet.getInt("displayId"));
        JSONObject restored=f.port.restoreTaskAudio();assertEquals(0,restored.getInt("pending"));assertEquals(1,restored.getInt("restored"));assertEquals("allow",mode);assertEquals(2,sets);
    }}
    @Test public void pauseResumeDuringReadLeavesClaimButCannotWriteAnOldSet()throws Exception{try(Fixture f=new Fixture()){
        f.enable();afterGet=()->{f.original.pause();f.original.resume();};assertThrows(Exception.class,f::quiet);assertEquals(0,sets);assertEquals(1,f.pending());assertEquals("allow",mode);
    }}
    @Test public void policyRevocationDuringReadCannotMutateAfterTheOriginalAdmission()throws Exception{try(Fixture f=new Fixture()){
        f.enable();afterGet=()->AndroidPhoneAudioRecovery.setOwnerSilenceEnabled(f.app,false);assertThrows(Exception.class,f::quiet);assertEquals(0,sets);assertEquals(1,f.pending());
    }}
    @Test public void replacedNativeSessionCannotReceiveTheOldAudioSet()throws Exception{try(Fixture f=new Fixture()){
        f.enable();afterGet=()->{try{session="replacement-session";field(f.module,"state",status());}catch(Exception failure){throw new RuntimeException(failure);}};assertThrows(Exception.class,f::quiet);assertEquals(0,sets);assertEquals(1,f.pending());
    }}
    @Test public void cancellationAfterSetKeepsUnknownUntilTheOriginalPeerIsReconnectedForCleanup()throws Exception{try(Fixture f=new Fixture()){
        f.enable();cancelAfterSet=f.original;assertThrows(Exception.class,f::quiet);assertTrue(f.original.isCancelled());assertEquals(1,sets);assertEquals(1,f.pending());assertEquals("ignore",mode);
        assertThrows(Exception.class,f::quiet);assertEquals(1,sets);assertEquals(1,f.port.restoreTaskAudio().getInt("pending"));assertEquals("ignore",mode);assertEquals(1,sets);
        f.module.acceptStatus(status());assertEquals(0,f.port.restoreTaskAudio().getInt("pending"));assertEquals("allow",mode);assertEquals(2,sets);
    }}
    @Test public void ownerRestoredBaselineIsNotSilencedAgainThroughTheRealPort()throws Exception{try(Fixture f=new Fixture()){
        f.enable();f.quiet();mode="allow";int before=sets;assertThrows(Exception.class,f::quiet);assertEquals(before,sets);assertEquals("allow",mode);assertEquals(0,f.port.restoreTaskAudio().getInt("pending"));assertEquals(before,sets);
    }}
    @Test public void untrustedPeerAndChangedRunCannotAcquireTheNativeAudioAuthority()throws Exception{try(Fixture f=new Fixture()){
        f.enable();peerUid=10000;assertThrows(Exception.class,f::quiet);assertEquals(0,payloads);assertEquals(0,f.pending());RunControl other=new RunControl();other.bindHostRun("original-task","different-run",2);
        assertThrows(SecurityException.class,()->f.port.bindLifecycle(f.app,"original-task","different-run",2,other));assertEquals(0,sets);
    }}
    @Test public void otherTaskOrSessionAndOwnerColdRecoveryCannotBypassTheLivePin()throws Exception{try(Fixture f=new Fixture()){
        JSONObject args=new JSONObject().put("packageName","example.music").put("userId",0).put("mode","ignore");
        assertThrows(SecurityException.class,()->f.module.audioForTask("audio.set",args,"other-task",session,5,f.original,0,()->{}));
        assertThrows(SecurityException.class,()->f.module.audioForTask("audio.set",args,"original-task","other-session",5,f.original,0,()->{}));
        assertThrows(SecurityException.class,()->f.module.audioForTask("launch",args,"original-task",session,5,f.original,0,()->{}));
        assertThrows(SecurityException.class,()->f.module.audioRecovery("audio.set",args));assertEquals(0,payloads);assertEquals(0,sets);
    }}
    @Test public void disconnectedAccessibilityCannotPassTheActualOriginalAdmission()throws Exception{try(Fixture f=new Fixture()){
        f.enable();Field singleton=PhoneAccessibilityService.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);assertThrows(Exception.class,f::quiet);assertEquals(0,payloads);assertEquals(0,sets);assertEquals(0,f.pending());
    }}
    @Test public void actualLaunchDriverRetainsUnknownAudioEffectAndNeverDispatchesTheLaunchTail()throws Exception{try(Fixture f=new Fixture()){
        f.enable();dropSetReceipt=true;var entry=BackgroundPhonePort.class.getDeclaredMethod("executeNative",String.class,JSONObject.class,RunControl.class);entry.setAccessible(true);
        java.util.concurrent.FutureTask<JSONObject> work=new java.util.concurrent.FutureTask<>(()->(JSONObject)entry.invoke(f.port,"phone_launch_app",new JSONObject().put("packageName","example.music"),f.original));new Thread(work,"original-native-audio-launch-fixture").start();JSONObject receipt=work.get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("audio_policy_unconfirmed",receipt.getString("error"));assertTrue(receipt.isNull("accepted"));assertFalse(receipt.getBoolean("replayAllowed"));assertFalse(receipt.getBoolean("launchDispatched"));assertEquals(1,sets);assertEquals(0,launches);assertEquals(1,f.pending());assertFalse(f.original.isCancelled());
        assertEquals(1,ownershipReads);assertFalse(f.module.isConnected());assertEquals("ignore",mode);
        assertEquals(1,f.port.restoreTaskAudio().getInt("pending"));assertEquals(1,sets);assertEquals(0,releases);
        dropSetReceipt=false;f.module.acceptStatus(status());JSONObject cleaned=f.port.closeSession();
        assertTrue(cleaned.getBoolean("releaseConfirmed"));assertEquals(0,cleaned.getJSONObject("audioRestore").getInt("pending"));assertEquals(1,cleaned.getJSONObject("audioRestore").getInt("restored"));
        assertEquals("allow",mode);assertEquals(2,sets);assertEquals(0,launches);assertEquals(0,f.pending());assertEquals(1,releases);
        assertEquals("original-audio-session",lastRelease.getString("sessionId"));assertEquals(5,lastRelease.getInt("displayId"));assertFalse(f.module.hasSession());
    }}
    @Test public void nativeOwnerPolicyDefaultsOffCannotEnableOverALiveTaskAndCanRevokeImmediately()throws Exception{try(Fixture f=new Fixture()){
        assertFalse(f.module.snapshot().getBoolean("audioSilenceEnabled"));assertThrows(SecurityException.class,()->f.module.setAudioPolicyForOwner(true));assertFalse(f.module.setAudioPolicyForOwner(false).getBoolean("enabled"));
        f.module.unpinOwner("original-task");JSONObject saved=f.module.setAudioPolicyForOwner(true);assertTrue(saved.getBoolean("enabled"));assertTrue(saved.getBoolean("persisted"));assertFalse(saved.getBoolean("systemPermissionGranted"));assertFalse(saved.getBoolean("audioMutation"));assertTrue(f.module.snapshot().getBoolean("audioSilenceEnabled"));assertEquals(0,sets);
    }}
    @Test public void modelModuleCommandSurfaceCannotEnableTheNativeOwnerPolicy()throws Exception{try(Fixture f=new Fixture()){
        f.module.unpinOwner("original-task");assertThrows(IllegalArgumentException.class,()->f.module.handle("audio.policy",new JSONObject().put("enabled",true)));assertFalse(AndroidPhoneAudioRecovery.silenceEnabled(f.app));assertEquals(0,sets);
    }}
}
