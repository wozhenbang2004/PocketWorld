package dev.pocketengine.phone.display;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import dev.pocketengine.phone.observation.OcrTextTarget;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.vision.ocr.OcrPage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Actual local framing/client/background driver; socket peer and retained frame are explicit fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows={OcrNativeBridgeTest.SocketShadow.class,OcrNativeBridgeTest.Vault.class,OcrNativeBridgeTest.Safety.class})
public final class OcrNativeBridgeTest {
    static int uid=2000,tapRequests,effects;static long frame=4;static String activeSession="original-session";static JSONObject lastTap;
    static RunControl cancelAfterWrite;
    @Implements(SecretVault.class) public static class Vault {@Implementation public String get(String slot){return "display.token".equals(slot)?"fixture-token":"";}}
    @Implements(PhoneAccessibilityService.class) public static class Safety {@Implementation public String observationSafetyFailure(){return null;}}
    @Implements(LocalSocket.class) public static class SocketShadow {
        private boolean closed;private byte[] response;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        @Implementation public void connect(LocalSocketAddress address){ }
        @Implementation public void setSoTimeout(int timeout){ }
        @Implementation public Credentials getPeerCredentials(){return new Credentials(1,uid,2000);}
        @Implementation public OutputStream getOutputStream(){return new OutputStream(){
            @Override public void write(int b)throws java.io.IOException{if(closed)throw new java.io.IOException("closed");bytes.write(b);}
            @Override public void flush()throws java.io.IOException {try{
                JSONObject request=new JSONObject(DisplayWire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),32768));JSONObject reply=status();
                if("tap".equals(request.getString("command"))){tapRequests++;lastTap=request;boolean accepted=request.getLong("frameGeneration")==frame&&activeSession.equals(request.getString("sessionId"))&&request.getInt("displayId")==5;
                    if(accepted)effects++;reply.put("ok",accepted).put("accepted",accepted).put("verified",false);if(!accepted)reply.put("error","stale_frame_or_session");}
                reply.put("requestId",request.getString("requestId"));ByteArrayOutputStream encoded=new ByteArrayOutputStream();DisplayWire.write(new DataOutputStream(encoded),reply.toString(),8*1024*1024);response=encoded.toByteArray();
                if(cancelAfterWrite!=null&&"tap".equals(request.getString("command")))cancelAfterWrite.cancel();
            }catch(Exception e){throw new java.io.IOException(e);}}
        };}
        @Implementation public InputStream getInputStream()throws java.io.IOException{if(closed)throw new java.io.IOException("closed after payload");return new ByteArrayInputStream(response);}
        @Implementation public void close(){closed=true;}
    }
    private static JSONObject status()throws Exception{return new JSONObject().put("ok",true).put("protocol",1).put("uid",2000).put("sdk",35).put("sessionActive",true).put("displayId",5).put("sessionId",activeSession)
            .put("width",101).put("height",101).put("backgroundOnly",true).put("focusIsolationRequested",true).put("displayFlagsConfirmed",true);}
    private static RunControl control(){RunControl c=new RunControl();c.bindHostRun("task","run",1);return c;}
    private static JSONObject payload()throws Exception{return new JSONObject().put("displayId",5).put("sessionId","original-session").put("frameGeneration",4).put("x",195).put("y",195);}
    @After public void reset()throws Exception {uid=2000;tapRequests=0;effects=0;frame=4;activeSession="original-session";lastTap=null;cancelAfterWrite=null;Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);}
    @Test public void actualTypedClientWritesOnePinnedNativeRequestAndReadsItsRealReceipt()throws Exception {
        RunControl c=control();JSONObject result=new ShellBridgeClient("fixture-socket","fixture-token").call("tap",payload(),c,0,false,()->{});
        assertTrue(result.getBoolean("accepted"));assertEquals(1,tapRequests);assertEquals(1,effects);assertEquals("original-session",lastTap.getString("sessionId"));assertEquals(4,lastTap.getLong("frameGeneration"));
    }
    @Test public void lastAdmissionPauseResumeOrCancelDoesNotWriteTheEffectPayload()throws Exception {
        for(int mode=0;mode<3;mode++){RunControl c=control();int selected=mode;
            assertThrows(NativePhoneEntry.Refused.class,()->new ShellBridgeClient("fixture","fixture-token").call("tap",payload(),c,0,false,()->{if(selected==2)c.cancel();else{c.pause();if(selected==1)c.resume();}}));assertEquals(0,tapRequests);assertEquals(0,effects);}
    }
    @Test public void rejectedPeerNeverReceivesPayloadOrPreparedAdmission()throws Exception {
        uid=10000;assertThrows(SecurityException.class,()->new ShellBridgeClient("fixture","fixture-token").call("tap",payload(),control(),0,false,()->fail("untrusted peer reached admission")));assertEquals(0,tapRequests);
    }
    @Test public void cancelAfterPayloadCannotBeReportedAsAnUnenteredRequest()throws Exception {
        RunControl c=control();cancelAfterWrite=c;
        try{new ShellBridgeClient("fixture","fixture-token").call("tap",payload(),c,0,false,()->{});fail("closed peer must not invent a receipt");}
        catch(Exception uncertain){assertFalse(uncertain instanceof NativePhoneEntry.Refused);}
        assertEquals(1,tapRequests);assertEquals(1,effects);assertTrue(c.isCancelled());
    }
    private BackgroundPhonePort driver(RunControl c)throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("display_backend",0).edit().putString("backend","adb").putString("socketName","fixture").commit();
        PhoneAccessibilityService service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,service);
        DisplayModule module=new DisplayModule(new FeatureContext(context,null,new SecretVault(context),event->{}));Field state=DisplayModule.class.getDeclaredField("state");state.setAccessible(true);state.set(module,status());module.acceptStatus(status());module.pinOwner("task");
        BackgroundPhonePort port=new BackgroundPhonePort(module);Class<?> tree=Class.forName(BackgroundPhonePort.class.getName()+"$Tree");Constructor<?> tc=tree.getDeclaredConstructor();tc.setAccessible(true);
        Class<?> observation=Class.forName(BackgroundPhonePort.class.getName()+"$Observation");Constructor<?> oc=observation.getDeclaredConstructor(tree,long.class,long.class,int.class,int.class,RunControl.class,boolean.class);oc.setAccessible(true);
        Object retained=oc.newInstance(tc.newInstance(),4L,0L,101,101,c,true);Field current=BackgroundPhonePort.class.getDeclaredField("current");current.setAccessible(true);current.set(port,retained);return port;
    }
    private OcrTextTarget target(BackgroundPhonePort port,RunControl c)throws Exception {
        Field field=BackgroundPhonePort.class.getDeclaredField("current");field.setAccessible(true);Object observation=field.get(port);Field id=observation.getClass().getDeclaredField("id");id.setAccessible(true);
        OcrPage.Line line=new OcrPage.Line(1,"确定",10,10,30,30);OcrPage page=new OcrPage(101,101,101,101,List.of(line));Constructor<OcrTextTarget> ctor=OcrTextTarget.class.getDeclaredConstructor(String.class,long.class,RunControl.class,OcrPage.class,OcrPage.Line.class);ctor.setAccessible(true);return ctor.newInstance(id.get(observation),0L,c,page,line);
    }
    private JSONObject tap(BackgroundPhonePort port,OcrTextTarget target,RunControl c,NativePhoneEntry.Admission admission)throws Exception {
        FutureTask<JSONObject> task=new FutureTask<>(()->port.tapOcrText(target,c,admission));new Thread(task,"native-background-ocr-review").start();return task.get(5,TimeUnit.SECONDS);
    }
    @Test public void actualBackgroundDriverMapsItsRetainedFrameThenUsesTheTypedNativeGate()throws Exception {
        RunControl c=control();BackgroundPhonePort port=driver(c);JSONObject result=tap(port,target(port,c),c,()->{});
        assertTrue(result.toString(),result.getBoolean("accepted"));assertEquals(1,effects);assertEquals(195,lastTap.getInt("x"));assertEquals(195,lastTap.getInt("y"));assertEquals(4,lastTap.getLong("frameGeneration"));
    }
    @Test public void changedActualHelperFrameIsRefusedWithoutAPhysicalEffect()throws Exception {
        RunControl c=control();BackgroundPhonePort port=driver(c);OcrTextTarget target=target(port,c);frame=5;JSONObject result=tap(port,target,c,()->{});
        assertFalse(result.getBoolean("accepted"));assertEquals(1,tapRequests);assertEquals(0,effects);
    }
    @Test public void replacementSessionDuringPreparedCheckDoesNotRetargetTheOldOcrClick()throws Exception {
        RunControl c=control();BackgroundPhonePort port=driver(c);JSONObject result=tap(port,target(port,c),c,()->activeSession="replacement-session");
        assertFalse(result.getBoolean("accepted"));assertEquals("original-session",lastTap.getString("sessionId"));assertEquals(0,effects);
    }
    @Test public void originalPauseInFinalPreparedCheckStopsTheActualBackgroundDriver()throws Exception {
        RunControl c=control();BackgroundPhonePort port=driver(c);JSONObject result=tap(port,target(port,c),c,c::pause);
        assertFalse(result.getBoolean("accepted"));assertEquals("paused_or_changed",result.getString("error"));assertEquals(0,tapRequests);assertEquals(0,effects);
    }
}
