package dev.pocketengine.ui;

import android.app.Application;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.FeatureHost;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import kotlin.Pair;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RealtimeVoiceControlControllerTest {
    private static final class Worker extends ThreadPoolExecutor {
        private final boolean immediate;int submitted;
        Worker(boolean immediate){super(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1));this.immediate=immediate;}
        @Override public void execute(Runnable work){submitted++;if(immediate)work.run();}
    }
    private static void replace(PocketWorldController controller,String name,ExecutorService worker)throws Exception{
        Field field=PocketWorldController.class.getDeclaredField(name);field.setAccessible(true);((ExecutorService)field.get(controller)).shutdownNow();field.set(controller,worker);
    }
    @Test public void stopUsesUrgentWorkerDespiteBlockedOrdinaryAdmissionAndRetainsOnlyItsExactTurnId()throws Exception{
        EngineApplication app=new EngineApplication();app.features=new FeatureHost(event->{});
        PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);Worker ordinary=new Worker(false),controls=new Worker(true);
        try{
            replace(controller,"realtimeIo",ordinary);replace(controller,"controls",controls);
            controller.requestRealtime("startVoiceTurn",new JSONObject());assertEquals(1,ordinary.submitted);
            String request=controller.requestRealtime("stopVoiceTurn",new JSONObject().put("voiceTurnId","voice-one").put("authenticationToken","fixture-not-metadata").put("text","fixture-not-metadata"));
            assertEquals("stop cannot sit behind recognition/model admission",1,controls.submitted);assertEquals(1,ordinary.submitted);
            Field pending=PocketWorldController.class.getDeclaredField("pending");pending.setAccessible(true);
            Pair<?,?> receipt=(Pair<?,?>)((Map<?,?>)pending.get(controller)).get(request);assertNotNull(receipt);
            JSONObject metadata=(JSONObject)receipt.getSecond();assertEquals("voice-one",metadata.getString("voiceTurnId"));assertFalse(metadata.has("authenticationToken"));assertFalse(metadata.has("text"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(controller.getState().getModuleErrors().containsKey("realtime.stopVoiceTurn"));assertFalse(controller.getState().getModuleBusy().contains("realtime.stopVoiceTurn"));
        }finally{controller.detach();app.features.close();ordinary.shutdownNow();controls.shutdownNow();}
    }
}
