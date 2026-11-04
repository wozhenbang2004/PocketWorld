package dev.pocketengine.displayhelper;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowProcess;
import static org.junit.Assert.*;

/** Actual helper JPEG/metadata reads. The shell context, captured pixels, display flags
 * and input receiver are controlled boundaries; physical-device compatibility is not claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DisplayLivePreviewTest {
    private DisplayEngine engine;
    private VirtualDisplay display;
    private LeasePolicy lease;
    private InputReceiver input;
    public static final class InputReceiver {
        int calls;
        public boolean inject(InputEvent event,int sync){calls++;return true;}
    }
    private static void set(Object object,String key,Object value)throws Exception {Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);f.set(object,value);}
    private static Object get(Object object,String key)throws Exception {Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(object);}
    private static int flags()throws Exception {return Display.class.getDeclaredField("FLAG_TRUSTED").getInt(null)|Display.class.getDeclaredField("FLAG_OWN_FOCUS").getInt(null)|Display.class.getDeclaredField("FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null);}
    private Object displayInfo()throws Exception {
        Class<?> api=Class.forName("android.hardware.display.DisplayManagerGlobal");Object global=api.getDeclaredMethod("getInstance").invoke(null);
        return api.getDeclaredMethod("getDisplayInfo",int.class).invoke(global,display.getDisplay().getDisplayId());
    }
    private void changeDisplay(Object info)throws Exception {
        Class<?> api=Class.forName("android.hardware.display.DisplayManagerGlobal");Object global=api.getDeclaredMethod("getInstance").invoke(null);
        Object shadow=org.robolectric.shadow.api.Shadow.extract(global);Method change=shadow.getClass().getDeclaredMethod("changeDisplay",int.class,info.getClass());change.setAccessible(true);change.invoke(shadow,display.getDisplay().getDisplayId(),info);
    }
    private void fixture(int width,int height)throws Exception {
        ShadowProcess.setUid(2000);
        Context shell=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public String getPackageName(){return "com.android.shell";}
            @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo();info.uid=2000;return info;}
        };
        assertFalse(shell.getSystemService(KeyguardManager.class).isDeviceLocked());assertFalse(shell.getSystemService(KeyguardManager.class).isKeyguardLocked());
        display=shell.getSystemService(DisplayManager.class).createVirtualDisplay("controlled-preview-display",width,height,160,null,DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        assertNotNull(display);assertTrue(display.getDisplay().getDisplayId()>0);Shadows.shadowOf(display.getDisplay()).setFlags(flags());
        Object info=displayInfo();info.getClass().getDeclaredField("removeMode").setInt(info,Display.class.getDeclaredField("REMOVE_MODE_DESTROY_CONTENT").getInt(null));changeDisplay(info);
        engine=new DisplayEngine(shell,new Handler(Looper.getMainLooper()));lease=new LeasePolicy("original-preview",display.getDisplay().getDisplayId(),SystemClock.uptimeMillis(),600000);
        set(engine,"display",display);set(engine,"lease",lease);set(engine,"width",width);set(engine,"height",height);set(engine,"density",160);set(engine,"requiredDisplayFlags",flags());
        set(engine,"geometry",new DisplayGeometry(1,width,height,160,0));set(engine,"geometryReady",true);
        ((java.util.concurrent.atomic.AtomicLong)get(engine,"geometryGeneration")).set(1);((java.util.concurrent.atomic.AtomicLong)get(engine,"frameGeneration")).set(7);
        input=new InputReceiver();set(engine,"inputManager",input);set(engine,"inject",InputReceiver.class.getMethod("inject",InputEvent.class,int.class));
        Method setter=InputEvent.class.getDeclaredMethod("setDisplayId",int.class);setter.setAccessible(true);set(engine,"setDisplay",setter);
    }
    private JSONObject preview(long after)throws Exception {
        return new JSONObject().put("command","preview_frame").put("sessionId",lease.session()).put("displayId",lease.display()).put("afterFrameGeneration",after);
    }
    @After public void close(){if(engine!=null)engine.close();if(display!=null)try{display.release();}catch(Exception ignored){}ShadowProcess.setUid(10000);}

    @Test public void encodesBoundedCurrentPixelsDuringAnIncomingFrameWithoutChangingCaptureOrInput()throws Exception {
        fixture(1600,800);byte[] pixels=new byte[1600*800*4];java.util.Arrays.fill(pixels,(byte)127);
        set(engine,"latestPixels",pixels);set(engine,"frameAt",123L);set(engine,"framePending",true);
        Object geometry=get(engine,"geometry");long expires=lease.expiresAt();
        JSONObject result=engine.handle(preview(-1));assertEquals("ready",result.getString("state"));
        assertEquals(1600,result.getInt("sourceWidth"));assertEquals(800,result.getInt("sourceHeight"));
        assertEquals(960,result.getInt("previewWidth"));assertEquals(480,result.getInt("previewHeight"));
        byte[] jpeg=android.util.Base64.decode(result.getString("imageBase64"),android.util.Base64.DEFAULT);
        android.graphics.Bitmap image=android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
        assertNotNull(image);assertEquals(960,image.getWidth());assertEquals(480,image.getHeight());image.recycle();
        assertSame(geometry,get(engine,"geometry"));assertSame(pixels,get(engine,"latestPixels"));assertNull(get(engine,"reader"));
        assertTrue((Boolean)get(engine,"framePending"));assertEquals(expires,lease.expiresAt());assertEquals(0,input.calls);
        JSONObject unchanged=engine.handle(preview(7));assertEquals("unchanged",unchanged.getString("state"));assertFalse(unchanged.has("imageBase64"));
        assertEquals(7,((java.util.concurrent.atomic.AtomicLong)get(engine,"frameGeneration")).get());
    }
    @Test public void pendingDoesNotWaitRecreateGeometryOrCaptureTheMainScreen()throws Exception {
        fixture(320,480);set(engine,"framePending",true);
        JSONObject empty=engine.handle(preview(-1));assertEquals("pending",empty.getString("state"));assertFalse(empty.has("imageBase64"));assertNull(get(engine,"reader"));
        Object geometry=get(engine,"geometry");set(engine,"latestPixels",new byte[320*480*4]);
        Object info=displayInfo();info.getClass().getDeclaredField("rotation").setInt(info,1);info.getClass().getDeclaredField("logicalWidth").setInt(info,480);info.getClass().getDeclaredField("logicalHeight").setInt(info,320);changeDisplay(info);
        JSONObject rotated=engine.handle(preview(-1));assertEquals("pending",rotated.getString("state"));assertFalse(rotated.has("imageBase64"));
        assertSame(geometry,get(engine,"geometry"));assertNull(get(engine,"reader"));assertEquals(0,input.calls);
        DisplayEngine.Failure refused=assertThrows(DisplayEngine.Failure.class,()->engine.handle(preview(-1).put("displayId",0)));
        assertEquals("invalid_lease",refused.code);assertSame(display,get(engine,"display"));assertSame(lease,get(engine,"lease"));
    }
}
