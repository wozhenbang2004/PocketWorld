package dev.pocketengine.ui;
import android.app.Application;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteOwnerWindowControllerTest {
 @Test public void delayedOldPageDisposeCannotCloseOrRetargetNewNativeWindow()throws Exception{
  EngineApplication app=new EngineApplication();app.tasks=new TaskStore(RuntimeEnvironment.getApplication());app.features=new FeatureHost(event->{});app.remoteOwnerWorkflows=new RemoteOwnerWorkflows(app.features,app.tasks,event->{});PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);
  try{String old=controller.enterRemoteTask("old-task"),current=controller.enterRemoteTask("current-task");controller.leaveRemoteTask(old);Field field=PocketWorldController.class.getDeclaredField("remoteOwnerWindow");field.setAccessible(true);assertEquals(current,field.get(controller));Field task=PocketWorldController.class.getDeclaredField("remoteOwnerTask");task.setAccessible(true);assertEquals("current-task",task.get(controller));controller.leaveRemoteTask(current);assertNull(field.get(controller));assertThrows(IllegalStateException.class,()->controller.requestRemoteTask("refresh",new org.json.JSONObject()));}finally{controller.detach();app.features.close();}
 }
}
