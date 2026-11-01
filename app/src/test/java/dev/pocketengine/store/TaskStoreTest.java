package dev.pocketengine.store;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskStoreTest {
    @Test public void successfulStepsDoNotFinishTheTaskAndRestartRecoversIt() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        TaskStore store=new TaskStore(context);
        String id=store.create("打开计算器并核对结果").getString("id");
        store.event(id,new JSONObject().put("type","run").put("status","running"));
        store.event(id,new JSONObject().put("type","observation").put("status","completed"));
        store.event(id,new JSONObject().put("type","tool").put("status","completed"));
        assertEquals("running",store.get(id,false).getString("status"));
        assertEquals(3,store.get(id,false).getJSONArray("events").length());
        assertEquals("interrupted",new TaskStore(context).get(id,false).getString("status"));
    }
    @Test public void finalResultAndOwnerConfirmationRemainDistinct() throws Exception {
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());
        String id=store.create("检查结果").getString("id");
        store.finish(id,"needs_confirmation","请核对结果",new JSONObject().put("automaticallyVerified",false));
        assertEquals("needs_confirmation",store.get(id,false).getString("status"));
        store.event(id,new JSONObject().put("type","user_verification").put("completed",true));
        store.status(id,"completed","主人已确认");
        JSONObject result=store.get(id,false);
        assertFalse(result.getJSONObject("evidence").getBoolean("automaticallyVerified"));
        assertTrue(result.getJSONArray("events").getJSONObject(0).getBoolean("completed"));
        assertEquals("completed",result.getString("status"));
    }
    @Test public void invalidRecordsDoNotPreventReadingValidTasks() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String valid="a1234567-1234-1234-1234-123456789abc";
        File file=new File(context.getFilesDir(),"tasks.json");
        Files.write(file.toPath(),("[null,5,{\"id\":\"bad\"},{\"id\":\""+valid+"\",\"status\":\"running\"}]").getBytes(StandardCharsets.UTF_8));
        TaskStore store=new TaskStore(context);
        assertEquals(1,store.list().length());
        assertEquals("interrupted",store.get(valid,false).getString("status"));
        assertNotNull(store.get(valid,false).getJSONArray("events"));
    }
}
