package dev.pocketengine.ui;

import android.app.Application;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.FeatureHost;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic tokens stay inside the bounded native worker, never request/UI metadata. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RealtimeCredentialControllerTest {
    private static final class CapturingExecutor extends ThreadPoolExecutor {
        private final boolean reject;
        JSONObject input;
        CapturingExecutor(boolean reject){super(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1));this.reject=reject;}
        @Override public void execute(Runnable work){
            try{
                for(Field field:work.getClass().getDeclaredFields()){
                    if(field.getType()!=JSONObject.class)continue;
                    field.setAccessible(true);JSONObject value=(JSONObject)field.get(work);
                    if(value!=null&&value.has("participantToken"))input=value;
                }
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            if(reject)throw new RejectedExecutionException("fixture queue closed");
            work.run();
        }
    }
    private void check(boolean rejected)throws Exception{
        EngineApplication app=new EngineApplication();app.features=new FeatureHost(event->{});
        PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);
        CapturingExecutor worker=new CapturingExecutor(rejected);
        try{
            Field executor=PocketWorldController.class.getDeclaredField("realtimeIo");executor.setAccessible(true);((ThreadPoolExecutor)executor.get(controller)).shutdownNow();executor.set(controller,worker);
            JSONObject caller=new JSONObject().put("participantToken","fixture-room-token").put("authenticationToken","fixture-service-token");
            controller.requestRealtime("configureEndpointProvider",caller);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("fixture must inspect the actual submitted worker input",worker.input);
            assertFalse("participant credential must be removed",worker.input.has("participantToken"));
            assertFalse("dedicated service credential must also be removed",worker.input.has("authenticationToken"));
            assertTrue("caller payload is not worker storage",caller.has("authenticationToken"));
            Field pending=PocketWorldController.class.getDeclaredField("pending");pending.setAccessible(true);
            for(Object value:((Map<?,?>)pending.get(controller)).values()){
                String metadata=value.toString();assertFalse(metadata.contains("fixture-room-token"));assertFalse(metadata.contains("fixture-service-token"));
            }
            assertFalse(controller.getState().getModuleResultInputs().toString().contains("fixture-service-token"));
            assertFalse(controller.getState().getModuleErrors().toString().contains("fixture-service-token"));
        }finally{controller.detach();app.features.close();worker.shutdownNow();}
    }
    @Test public void returnedNativeFailureClearsBothCredentialKinds()throws Exception{check(false);}
    @Test public void rejectedWorkerClearsBothCredentialKinds()throws Exception{check(true);}
}
