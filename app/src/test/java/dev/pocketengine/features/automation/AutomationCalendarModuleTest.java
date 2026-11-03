package dev.pocketengine.features.automation;

import android.app.AlarmManager;
import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AutomationCalendarModuleTest {
    @Test public void nativePreviewDoesNotWriteReplanEmitOrBecomeAModelCommand()throws Exception {
        Application app=RuntimeEnvironment.getApplication();AtomicInteger events=new AtomicInteger();AutomationModule module=new AutomationModule(new FeatureContext(app,null,null,event->events.incrementAndGet()));
        JSONObject schedule=new JSONObject().put("type","monthly").put("time","09:00").put("zone","Europe/Berlin").put("dayOfMonth",31).put("shortMonthPolicy","last_day");
        JSONObject before=module.snapshot();int alarms=shadowOf(app.getSystemService(AlarmManager.class)).getScheduledAlarms().size();
        JSONObject preview=module.previewSchedule(schedule,Instant.parse("2028-01-31T09:00:00Z").toEpochMilli(),3);assertEquals(3,preview.getJSONArray("occurrences").length());assertTrue(preview.getBoolean("calendarOnly"));assertFalse(preview.getBoolean("policyEvaluated"));assertFalse(preview.getBoolean("executionAuthorized"));
        JSONObject after=module.snapshot();assertEquals(before.getString("observationGeneration"),after.getString("observationGeneration"));assertTrue(after.getLong("observationSequence")>before.getLong("observationSequence"));
        for(String field:new String[]{"observationGeneration","observationSequence"}){before.remove(field);after.remove(field);}assertEquals(before.toString(),after.toString());assertEquals(alarms,shadowOf(app.getSystemService(AlarmManager.class)).getScheduledAlarms().size());assertEquals(0,events.get());assertFalse(new File(app.getFilesDir(),"automation/rules.json").exists());
        JSONArray commands=module.snapshot().getJSONArray("commands");for(int i=0;i<commands.length();i++)assertNotEquals("previewSchedule",commands.getString(i));assertThrows(IllegalArgumentException.class,()->module.handle("previewSchedule",new JSONObject().put("schedule",schedule)));assertEquals(0,events.get());
    }
}
