package dev.pocketengine;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=BootstrapAdmissionNativeTest.UnfinishedInitialization.class)
public final class BootstrapAdmissionNativeTest {
    @Implements(EngineApplication.class)
    public static final class UnfinishedInitialization extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){/* Model a DB opened before the original recoverers complete. */}
    }
    @Test public void partialApplicationNeverDispatchesOwnerFeatureOrPhoneService()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();app.tasks=new TaskStore(app,app::businessReady,(stage,schema)->{});app.features=new FeatureHost(event->{},app.tasks,()->false);AtomicInteger entered=new AtomicInteger();
        app.features.register(new EngineModule(){public String id(){return "bootstrap_probe";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input){entered.incrementAndGet();return new JSONObject();}});
        try{
            assertNotNull(app.tasks);assertFalse(app.businessReady());assertFalse(app.executionAllowed());
            try{app.dispatchOwnerAction("featureRequest",new JSONObject().put("feature","bootstrap_probe").put("command","start"));fail();}catch(SecurityException expected){}
            try{app.tasks.requireExecutionEnabled();fail();}catch(SecurityException expected){}
            var service=Robolectric.buildService(PhoneRunService.class).create();try{
                assertEquals(android.app.Service.START_NOT_STICKY,service.get().onStartCommand(new android.content.Intent(app,PhoneRunService.class).setAction(PhoneRunService.START).putExtra("taskId","stale-notification"),0,1));
                assertNull(app.activeId());assertEquals(0,app.tasks.list().length());assertEquals(0,entered.get());
            }finally{service.destroy();}
        }finally{app.features.close();app.tasks.closeForMaintenance();}
    }
}
