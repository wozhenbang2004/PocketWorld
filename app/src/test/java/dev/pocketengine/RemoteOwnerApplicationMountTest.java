package dev.pocketengine;
import java.lang.reflect.Field;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class RemoteOwnerApplicationMountTest {
 @Test public void actualApplicationInstallsNativeWorkflowAgainstItsSingleExistingHostAndTaskStore()throws Exception{
  EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();assertNotNull(app.remoteOwnerWorkflows);for(String name:new String[]{"host","tasks"}){Field field=app.remoteOwnerWorkflows.getClass().getDeclaredField(name);field.setAccessible(true);assertSame(name.equals("host")?app.features:app.tasks,field.get(app.remoteOwnerWorkflows));}assertNotNull(app.features.module("remote"));assertEquals(0,app.tasks.list().length());String window=UUID.randomUUID().toString();assertThrows(IllegalArgumentException.class,()->app.remoteOwnerWorkflows.handle("open",new JSONObject().put("windowId",window).put("taskId","missing-native-task")));app.remoteOwnerWorkflows.close(window);assertEquals(0,app.tasks.list().length());
 }
}
