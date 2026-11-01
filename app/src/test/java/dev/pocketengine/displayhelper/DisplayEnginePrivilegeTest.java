package dev.pocketengine.displayhelper;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowProcess;
import static org.junit.Assert.*;

/** Runtime admission guards only. No claim of device hidden-API/focus/input compatibility. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DisplayEnginePrivilegeTest {
    private Context attributed(String name,int uid){return new ContextWrapper(RuntimeEnvironment.getApplication()){
        @Override public String getPackageName(){return name;}
        @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo();info.uid=uid;return info;}
    };}
    private DisplayEngine engine(Context context){return new DisplayEngine(context,new Handler(Looper.getMainLooper()));}
    @Test public void ordinaryAndIsolatedAppProcessesCannotInstantiatePrivilegedEngine(){
        Context shell=attributed("com.android.shell",2000);
        for(int uid:new int[]{10000,10234,99001}){ShadowProcess.setUid(uid);assertThrows(SecurityException.class,()->engine(shell));}
    }
    @Test public void privilegedUidAlsoRequiresActualShellPackageAttribution(){
        ShadowProcess.setUid(2000);
        assertThrows(SecurityException.class,()->engine(attributed("dev.pocketengine",10000)));
        assertThrows(SecurityException.class,()->engine(attributed("com.android.shell",10000)));
        assertThrows(SecurityException.class,()->engine(null));
    }
    @Test public void shellAndRootDedicatedContextsPassOnlyTheLocalGuard(){
        for(int uid:new int[]{2000,0}){ShadowProcess.setUid(uid);assertNotNull(engine(attributed("com.android.shell",2000)));}
    }
}
