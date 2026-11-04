package dev.pocketengine.features.automation;

import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=AutomationMaintenanceTest.TestApplication.class)
public final class AutomationMaintenanceTest {
    public static final class TestApplication extends Application implements AutomationModule.AlarmHostProvider {
        long maintenanceAt;int maintenancePasses,executionQueries,executionPasses;
        final AutomationModule.AlarmHost host=new AutomationModule.AlarmHost(){
            public long nextAutomationAt(long now){executionQueries++;return now;}
            public void onAutomationDue(long now,int limit){executionPasses++;}
            public long nextMaintenanceAt(long now){return maintenanceAt;}
            public void onMaintenanceDue(long now){maintenancePasses++;maintenanceAt=-1;}
        };
        public AutomationModule.AlarmHost automationAlarmHost(){return host;}
        public boolean executionAllowed(){return false;}
    }
    @Test public void disabledExecutionKeepsOnlyPrivacyExpiryAndNeverRunsTheAutomationPass()throws Exception {
        TestApplication app=RuntimeEnvironment.getApplication();app.maintenanceAt=System.currentTimeMillis()+120_000;
        AutomationModule.reschedule(app);assertEquals(1,shadowOf(app.getSystemService(AlarmManager.class)).getScheduledAlarms().size());assertEquals(0,app.executionQueries);
        assertEquals(0,AutomationModule.deliver(app).length());assertEquals(1,app.maintenancePasses);assertEquals(0,app.executionPasses);assertEquals(0,app.executionQueries);
        assertEquals(0,shadowOf(app.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
        assertEquals(0,app.getSystemService(NotificationManager.class).getActiveNotifications().length);assertNull(shadowOf(app).getNextStartedActivity());
    }
}
