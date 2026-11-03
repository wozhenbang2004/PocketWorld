package dev.pocketengine.phone.display;

import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Actual Service/TaskStore/port cleanup, using native status fixtures; no physical device result is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneResourceReleaseJourneyTest {
    private static void field(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static JSONObject status(String session,int display)throws Exception {return new JSONObject().put("ok",true).put("protocol",1).put("uid",2000).put("sdk",35).put("sessionActive",true).put("sessionId",session).put("displayId",display);}
    @Test public void stoppingHeldOldPortKeepsUnknownReceiptAndNeverClaimsOrReleasesAnotherDisplay()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        app.getSharedPreferences("display_backend",0).edit().putString("backend","adb").commit();
        DisplayModule display=new DisplayModule(new FeatureContext(app,null,null,event->{}));
        var service=Robolectric.buildService(PhoneRunService.class).create();
        try {
            String id=app.tasks.create("核对原会话清理","background").getString("id");JSONObject task=app.tasks.startAttempt(id,"background",8);
            String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);original.pause();
            field(display,"state",status("original-display",7));display.acceptStatus(status("original-display",7));display.pinOwner(id);
            BackgroundPhonePort held=new BackgroundPhonePort(display);
            // A newer physical display must not be touched by this older captured port's release.
            field(display,"state",status("different-display",9));display.acceptStatus(status("different-display",9));
            field(app,"activeTaskId",id);field(app,"activeControl",original);field(app,"activePort",held);field(app,"activeMode","background");
            field(service.get(),"runningId",id);field(service.get(),"runningRunId",run);field(service.get(),"runningEpoch",epoch);field(service.get(),"ownerControl",original);
            field(service.get(),"heldPort",held);field(service.get(),"waitingForUser",true);
            app.tasks.finish(id,"awaiting_user","原等待",new JSONObject().put("existingReceipt","must-remain"));
            app.stop(id);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(app.activeId()!=null&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}
            JSONObject ended=app.tasks.get(id,false),evidence=ended.getJSONObject("evidence");
            assertTrue(original.isCancelled());assertNull(app.activeId());assertEquals("cancelled",ended.getString("status"));
            assertEquals("must-remain",evidence.getString("existingReceipt"));assertFalse(evidence.getJSONObject("displayRelease").getBoolean("releaseConfirmed"));
            assertEquals("release_unknown",evidence.getJSONObject("phoneResources").getString("state"));
            assertFalse(ended.getString("summary").contains("已确认释放"));assertEquals("different-display",display.session().getString("sessionId"));assertEquals(9,display.session().getInt("displayId"));
        } finally {
            service.destroy();field(display,"state",new JSONObject());display.close();
        }
    }
}
