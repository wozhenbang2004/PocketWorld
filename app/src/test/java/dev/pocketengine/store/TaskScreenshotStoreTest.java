package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real TaskStore SQL/files with a real AES key; Android Keystore provisioning is a device check. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskScreenshotStoreTest {
    private static final byte[] IMAGE=new byte[]{(byte)255,(byte)216,(byte)255,55,71,99};
    private static TaskScreenshotStorage.Crypto crypto()throws Exception {
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey key=generator.generateKey();
        return new TaskScreenshotStorage.Crypto(){public byte[] seal(byte[] plain,byte[] aad)throws Exception{return TaskScreenshotStorage.seal(key,plain,aad);}public byte[] open(byte[] encrypted,byte[] aad)throws Exception{return TaskScreenshotStorage.open(key,encrypted,aad);}};
    }
    private static JSONObject evidence()throws Exception{return new JSONObject().put("imageBase64",Base64.encodeToString(IMAGE,Base64.NO_WRAP)).put("text","original result");}
    @Test public void frameworkFilesAliasSupportsStartupMaintenanceWithoutReplacingOriginalImages()throws Exception {
        Context original=RuntimeEnvironment.getApplication();java.nio.file.Path parent=Files.createTempDirectory("screenshot-framework-alias-");
        java.nio.file.Path alias=Files.createSymbolicLink(parent.resolve("files"),original.getFilesDir().toPath().toRealPath());
        Context context=new ContextWrapper(original){@Override public File getFilesDir(){return alias.toFile();}};
        TaskStore store=new TaskStore(context,crypto());
        try{
            store.maintainTaskScreenshots();assertEquals(-1,store.nextTaskScreenshotExpiry());
            String id=store.create("aliased framework files").getString("id");store.finish(id,"completed","original result",evidence());
            JSONObject image=store.get(id,true).getJSONObject("evidence");assertTrue(image.has("imageDataUrl"));
            store.maintainTaskScreenshots();assertTrue(store.get(id,true).getJSONObject("evidence").has("imageDataUrl"));
                }finally{
            java.lang.reflect.Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((SQLiteDatabase)db.get(store)).close();
            Files.deleteIfExists(alias);Files.deleteIfExists(parent);
        }
    }
    @Test public void substitutedArtifactLeafIsRejectedBeforeOpeningTheDatabase()throws Exception {
        Context context=RuntimeEnvironment.getApplication();java.nio.file.Path outside=Files.createTempDirectory("screenshot-outside-");
        java.nio.file.Path sentinel=Files.write(outside.resolve("owner-export.jpg"),IMAGE);
        Files.createSymbolicLink(context.getFilesDir().toPath().resolve("artifacts"),outside);
        TaskScreenshotStorage.Crypto key=crypto();
        IllegalStateException denied=assertThrows(IllegalStateException.class,()->new TaskStore(context,key));
        assertTrue(denied.getCause() instanceof java.io.IOException);
        assertArrayEquals(IMAGE,Files.readAllBytes(sentinel));
    }
    @Test public void disablingNewCapturesKeepsTheOriginalImageAndStaleDeleteCannotRemoveReplacement()throws Exception {
        Context context=RuntimeEnvironment.getApplication();TaskStore store=new TaskStore(context,crypto());String id=store.create("review image").getString("id");
        store.finish(id,"needs_confirmation","result",evidence());JSONObject first=store.get(id,true).getJSONObject("evidence");String original=first.getJSONObject("screenshot").getString("imageId");
        assertTrue(first.has("imageDataUrl"));assertFalse(first.has("imageBase64"));store.changeTaskScreenshotPolicyForOwner(1,0);
        store.finish(id,"needs_confirmation","result without capture",evidence());JSONObject disabled=store.get(id,true).getJSONObject("evidence");
        assertEquals(original,disabled.getJSONObject("screenshot").getString("imageId"));assertEquals("disabled",disabled.getJSONObject("screenshotCapture").getString("state"));assertTrue(disabled.has("imageDataUrl"));
        store.changeTaskScreenshotPolicyForOwner(2,TaskScreenshotPolicy.DAY);store.finish(id,"needs_confirmation","replacement",evidence());JSONObject replacement=store.get(id,true);
        String image=replacement.getJSONObject("evidence").getJSONObject("screenshot").getString("imageId");assertNotEquals(original,image);
        assertThrows(SecurityException.class,()->store.deleteTaskScreenshotForOwner(id,replacement.optString("runId"),replacement.optInt("coordinatorEpoch"),original));
        assertTrue(store.get(id,true).getJSONObject("evidence").has("imageDataUrl"));
        store.deleteTaskScreenshotForOwner(id,replacement.optString("runId"),replacement.optInt("coordinatorEpoch"),image);
        JSONObject deleted=store.get(id,true).getJSONObject("evidence");assertEquals("deleted",deleted.getJSONObject("screenshot").getString("state"));assertFalse(deleted.has("imageDataUrl"));
    }
    @Test public void existingAtomicBackupMigratesWithoutExpiryAndExportRemains()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();File artifacts=new File(context.getFilesDir(),"artifacts");assertTrue(artifacts.mkdirs()||artifacts.isDirectory());
        Files.write(new File(artifacts,id+".jpg.bak").toPath(),IMAGE);Files.write(new File(artifacts,"export.jpg").toPath(),IMAGE);
        JSONObject old=new JSONObject().put("id",id).put("kind","phone").put("actorId","owner").put("spaceId","private").put("status","completed").put("evidence",new JSONObject().put("imageFile",id+".jpg"));
        Files.write(new File(context.getFilesDir(),"tasks.json").toPath(),new JSONArray().put(old).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));TaskStore store=new TaskStore(context,crypto());
        JSONObject image=store.get(id,true).getJSONObject("evidence");assertTrue(image.has("imageDataUrl"));assertEquals(0,image.getJSONObject("screenshot").getLong("expiresAt"));assertTrue(image.getJSONObject("screenshot").getBoolean("legacyRetained"));
        assertFalse(new File(artifacts,id+".jpg.bak").exists());assertTrue(new File(artifacts,"export.jpg").exists());
    }
    @Test public void expiryDeletesOnlyTheOriginalImageEvenWhenExecutionIsDisabled()throws Exception {
        Context context=RuntimeEnvironment.getApplication();TaskScreenshotStorage.Crypto crypto=crypto();TaskStore store=new TaskStore(context,crypto);String id=store.create("expired image").getString("id");store.finish(id,"completed","result",evidence());
        java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase database=(SQLiteDatabase)field.get(store);
        JSONObject task=store.get(id,false),record=task.getJSONObject("evidence").getJSONObject("screenshot");record.put("createdAt",1L).put("expiresAt",2L);
        TaskScreenshotStorage files=new TaskScreenshotStorage(new File(context.getFilesDir(),"artifacts").toPath(),crypto);files.deleteTask(id);files.write(id,record.getString("imageId"),IMAGE,TaskScreenshotPolicy.aad(record));
        ContentValues value=new ContentValues();value.put("document",task.toString());assertEquals(1,database.update("tasks",value,"task_id=?",new String[]{id}));
        store.setExecutionEnabledForOwner(1,false);store.maintainTaskScreenshots();JSONObject after=store.get(id,true).getJSONObject("evidence");
        assertEquals("expired",after.getJSONObject("screenshot").getString("state"));assertFalse(after.has("imageDataUrl"));assertFalse(Files.exists(new File(context.getFilesDir(),"artifacts").toPath().resolve(files.fileName(id,record.getString("imageId")))));assertEquals(-1,store.nextTaskScreenshotExpiry());
    }
}
