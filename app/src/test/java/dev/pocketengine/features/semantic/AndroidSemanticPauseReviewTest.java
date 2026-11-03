package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.lang.reflect.Field;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
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

/** Reproduces pause and pause/resume inside the LAST external safety callback. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class,shadows=AndroidSemanticPauseReviewTest.Safety.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AndroidSemanticPauseReviewTest {
    @Implements(PhoneAccessibilityService.class)
    public static final class Safety {
        static RunControl original;static String mode;static int calls;
        @Implementation public String observationSafetyFailure() {
            if(++calls==3) {
                if(mode.equals("pause")||mode.equals("pause_resume"))original.pause();
                if(mode.equals("pause_resume"))original.resume();
            }
            return null;
        }
    }
    private static Field serviceField() throws Exception {Field f=PhoneAccessibilityService.class.getDeclaredField("instance");f.setAccessible(true);return f;}
    @After public void clear() throws Exception {serviceField().set(null,null);Safety.original=null;}
    private void exercise(String mode,boolean calendar,boolean expectedLaunch) throws Exception {
        RunControl original=new RunControl();original.bindHostRun("original-task","original-run",1);Safety.original=original;Safety.mode=mode;Safety.calls=0;
        PhoneAccessibilityService service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();serviceField().set(null,service);
        SemanticRequest request=SemanticRequest.parse(calendar?SemanticCalendarRequestTest.draft():new JSONObject().put("kind","web").put("url","https://example.org/"));
        SemanticPlatform.Target target=new SemanticPlatform.Target(new JSONObject().put("component","example.editor/example.editor.Main").put("packageName","example.editor")
                .put("userId",0).put("profileSerial",RuntimeEnvironment.getApplication().getSystemService(android.os.UserManager.class).getSerialNumberForUser(android.os.Process.myUserHandle())),"fixture","official_intent");
        AndroidSemanticPlatform platform=new AndroidSemanticPlatform(RuntimeEnvironment.getApplication(),service,()->{});
        FutureTask<JSONObject> result=new FutureTask<>(()->platform.dispatch(request,target,original,original::check));Thread worker=new Thread(result,"semantic-pause-review");worker.start();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!result.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        JSONObject receipt=result.get(1,TimeUnit.SECONDS);worker.join(1000);
        Intent launched=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        System.out.println("PAUSE_REVIEW mode="+mode+" safetyCalls="+Safety.calls+" paused="+original.isPaused()+" pauseRevision="+original.pauseRevision()+" launchRecorded="+(launched!=null)+" receipt="+receipt);
        assertEquals(expectedLaunch,launched!=null);assertEquals(expectedLaunch,receipt.optBoolean("accepted"));
    }
    @Test public void normalControlStillStartsOnce() throws Exception {exercise("normal",false,true);}
    @Test public void pauseInLastSafetyCallbackStopsWebEntry() throws Exception {exercise("pause",false,false);}
    @Test public void pauseResumeInLastSafetyCallbackStopsOldCalendarDecision() throws Exception {exercise("pause_resume",true,false);}
}
