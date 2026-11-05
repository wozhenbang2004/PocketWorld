package dev.pocketengine.phone;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import dev.pocketengine.core.RunControl;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real AndroidPhonePort enumeration with the platform query supplied by Robolectric. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class)
public final class AndroidPhoneAppListTest {
    private JSONObject list(int count,boolean unique)throws Exception {
        Application app=RuntimeEnvironment.getApplication();List<ResolveInfo> found=new ArrayList<>();
        PackageManager packages=app.getPackageManager();Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        // The AGP test APK contributes its own launcher in addition to the shadow's overrides.
        for(ResolveInfo existing:packages.queryIntentActivities(launcher,0))if(existing.activityInfo!=null&&app.getPackageName().equals(existing.activityInfo.packageName))
            packages.setComponentEnabledSetting(new ComponentName(existing.activityInfo.packageName,existing.activityInfo.name),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
        for(int i=0;i<count;i++){
            ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();
            info.activityInfo.packageName="example.app"+(unique?i:0);info.activityInfo.name="Main"+i;
            info.activityInfo.enabled=true;info.activityInfo.exported=true;info.activityInfo.applicationInfo=new ApplicationInfo();
            info.activityInfo.applicationInfo.packageName=info.activityInfo.packageName;info.nonLocalizedLabel="Fixture "+i;found.add(info);
        }
        Shadows.shadowOf(packages).setResolveInfosForIntent(launcher,found);
        assertEquals("Fixture launcher rows must match the requested boundary",count,packages.queryIntentActivities(launcher,0).size());
        AndroidPhonePort port=new AndroidPhonePort(app);Method read=AndroidPhonePort.class.getDeclaredMethod("listApps",RunControl.class);read.setAccessible(true);
        try{return (JSONObject)read.invoke(port,new RunControl());}finally{port.closeExtensions();}
    }
    @Test public void appLimitCountsOnlyEntriesActuallyVisited()throws Exception {
        JSONObject exact=list(200,true);assertEquals(200,exact.getJSONArray("apps").length());assertFalse(exact.getBoolean("truncated"));
        JSONObject capped=list(201,true);assertEquals(200,capped.getJSONArray("apps").length());assertTrue(capped.getBoolean("truncated"));
    }
    @Test public void scanLimitDoesNotCountTheUnvisitedLastEntry()throws Exception {
        JSONObject exact=list(600,false);assertEquals(1,exact.getJSONArray("apps").length());assertFalse(exact.getBoolean("truncated"));
        JSONObject capped=list(601,false);assertEquals(1,capped.getJSONArray("apps").length());assertTrue(capped.getBoolean("truncated"));
    }
}
