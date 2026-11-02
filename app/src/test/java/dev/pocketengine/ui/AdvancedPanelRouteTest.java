package dev.pocketengine.ui;

import android.content.Intent;
import android.webkit.WebView;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.MainActivity;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class AdvancedPanelRouteTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("PanelRouteKeyStore","1","Test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    private void ready(MainActivity activity)throws Exception{var method=MainActivity.class.getDeclaredMethod("handle",JSONObject.class);method.setAccessible(true);method.invoke(activity,new JSONObject().put("action","ready"));}
    private JSONObject event(MainActivity activity)throws Exception{var field=MainActivity.class.getDeclaredField("web");field.setAccessible(true);String script=Shadows.shadowOf((WebView)field.get(activity)).getLastEvaluatedJavascript();String quoted=script.substring(script.indexOf("onNativeEvent(")+14,script.length()-1);return new JSONObject(new org.json.JSONTokener(quoted).nextValue().toString());}
    @Test public void coldReadyDeliversEachExactTargetAndConsumesItOnce()throws Exception{
        for(String target:new String[]{"channels","plugins","proxy","settings"}){
            var controller=Robolectric.buildActivity(MainActivity.class,new Intent().putExtra("openPanel",target)).create();try{MainActivity activity=controller.get();ready(activity);JSONObject e=event(activity);assertEquals(target.equals("settings")?"open_settings":"open_features",e.getString("type"));if(!target.equals("settings"))assertEquals(target,e.getString("feature"));assertFalse(activity.getIntent().hasExtra("openPanel"));ready(activity);assertFalse(event(activity).optString("type").startsWith("open_"));}finally{controller.destroy();}
        }
    }
    @Test public void reusedActivityDeliversNewTargetAndRejectsUnknownWithoutEchoingInput()throws Exception{
        var controller=Robolectric.buildActivity(MainActivity.class,new Intent().putExtra("openPanel","channels")).create();try{MainActivity activity=controller.get();ready(activity);var method=MainActivity.class.getDeclaredMethod("onNewIntent",Intent.class);method.setAccessible(true);method.invoke(activity,new Intent().putExtra("openPanel","proxy"));assertEquals("proxy",event(activity).getString("feature"));assertFalse(activity.getIntent().hasExtra("openPanel"));method.invoke(activity,new Intent().putExtra("openPanel","SECRET-invalid-target"));JSONObject e=event(activity);assertEquals("error",e.getString("type"));assertFalse(e.toString().contains("SECRET"));assertFalse(activity.getIntent().hasExtra("openPanel"));}finally{controller.destroy();}
    }
    @Test public void composeControllerLaunchesOnlyKnownNativeTargets()throws Exception{
        var activityController=Robolectric.buildActivity(ComposeActivity.class);PocketWorldController controller=new PocketWorldController(activityController.get(),(EngineApplication)RuntimeEnvironment.getApplication());
        try{for(String target:new String[]{"channels","plugins","proxy","settings"}){controller.openAdvanced(target);Intent intent=Shadows.shadowOf(activityController.get()).getNextStartedActivity();assertEquals(MainActivity.class.getName(),intent.getComponent().getClassName());assertEquals(target,intent.getStringExtra("openPanel"));assertEquals(1,intent.getExtras().size());}assertThrows(IllegalArgumentException.class,()->controller.openAdvanced("SECRET-invalid-target"));assertNull(Shadows.shadowOf(activityController.get()).getNextStartedActivity());}finally{controller.detach();}
    }
}
