package dev.pocketengine.media;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original SQLite + shared AttachmentRepository; no competing draft task DB or HTTP. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class MediaPreparedInputRecoveryTest {
    private static MediaModule module(Application app){return new MediaModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));}
    private static void close(TaskStore tasks)throws Exception{java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    @Test public void exactUnsentInputSurvivesClosedHostsWithoutCreatingRunOrTruncatingGraph()throws Exception{
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app);MediaModule media=module(app);MediaWorkflowHost host=new MediaWorkflowHost(tasks);
        try{media.handle("configure",new JSONObject().put("provider","comfyui").put("baseUrl","http://127.0.0.1:9"));String text="PRIVATE-NATIVE-DRAFT-"+"x".repeat(80000);JSONObject args=new JSONObject().put("configurationRevision",media.snapshot().getString("configurationRevision")).put("workflow",new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text",text))));JSONObject original=host.prepareFromOwner(media,"submitWorkflow",args);String id=original.getString("taskId");
            assertFalse("draft pixels/text must not enter generic TaskStore events",tasks.get(id,false).toString().contains("PRIVATE-NATIVE-DRAFT-"));host.close();media.close();close(tasks);tasks=new TaskStore(app);media=module(app);host=new MediaWorkflowHost(tasks);JSONObject restored=host.preparedFromOwner(media,id);assertEquals(id,restored.getString("taskId"));assertEquals(TaskStore.capabilityFingerprint(args),TaskStore.capabilityFingerprint(restored.getJSONObject("arguments")));assertEquals(text,restored.getJSONObject("arguments").getJSONObject("workflow").getJSONObject("6").getJSONObject("inputs").getString("text"));assertFalse(restored.getBoolean("dispatched"));assertEquals(1,tasks.list().length());assertEquals(0,tasks.get(id,false).getJSONArray("operations").length());assertEquals("",tasks.get(id,false).optString("runId"));
            JSONObject attachment=restored.getJSONObject("inputAttachment");java.io.File file=AttachmentRepository.get(app).book.file(attachment.getString("id"));byte[] changed=Files.readAllBytes(file.toPath());changed[changed.length-3]^=1;Files.write(file.toPath(),changed);final MediaWorkflowHost current=host;final MediaModule actual=media;assertThrows(SecurityException.class,()->current.preparedFromOwner(actual,id));assertEquals(0,tasks.get(id,false).getJSONArray("operations").length());
        }finally{host.close();media.close();close(tasks);}
    }
}
