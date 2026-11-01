package dev.pocketengine.browser;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real attachment copies with a fixture content stream, not an Android document-provider/device test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class BrowserUploadsTest {
    private Context context()throws Exception {File files=Files.createTempDirectory("browser-upload-").toFile();return new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};}
    @Test public void pickedBytesRetainOriginalProfileAndSessionWithoutPersistingUriGrants()throws Exception {
        Context context=context();BrowserProfileRegistry profiles=new BrowserProfileRegistry(new File(context.getFilesDir(),"profiles.json"));BrowserProfileRegistry.Session session=profiles.openSession(true);Uri uri=Uri.parse("content://fixture/upload.txt");Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri,new ByteArrayInputStream("上传内容".getBytes(StandardCharsets.UTF_8)));
        JSONObject result=BrowserUploads.importPicked(context,new Intent().setData(uri),session,"task-upload",()->true);JSONObject item=result.getJSONArray("attachments").getJSONObject(0),source=item.getJSONObject("source");assertEquals(session.profileId,source.getString("profileId"));assertEquals(session.sessionId,source.getString("browserSessionId"));assertEquals("task-upload",source.getString("taskId"));assertFalse(source.has("uri"));assertFalse(source.has("persistentReadPermission"));assertEquals("上传内容",AttachmentRepository.get(context).book.readText(item.getString("id")).getString("text"));
    }
    @Test public void profileSwitchActivelyClosesABlockedUploadAndRemovesPartialBytes()throws Exception {
        Context context=context();BrowserProfileRegistry profiles=new BrowserProfileRegistry(new File(context.getFilesDir(),"profiles.json"));BrowserProfileRegistry.Session session=profiles.openSession(true);Uri uri=Uri.parse("content://fixture/blocked");CountDownLatch reading=new CountDownLatch(1),closed=new CountDownLatch(1);InputStream stream=new InputStream(){@Override public int read()throws java.io.IOException{reading.countDown();try{if(!closed.await(4,TimeUnit.SECONDS))throw new java.io.IOException("close timed out");}catch(InterruptedException stopped){Thread.currentThread().interrupt();}throw new java.io.IOException("closed");}@Override public void close(){closed.countDown();}};Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri,stream);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();try{var imported=worker.submit(()->BrowserUploads.importPicked(context,new Intent().setData(uri),session,"task-upload",()->true));assertTrue(reading.await(4,TimeUnit.SECONDS));profiles.select(session.profileId,profiles.revision(),true);assertTrue(closed.await(1,TimeUnit.SECONDS));assertThrows(java.util.concurrent.ExecutionException.class,()->imported.get(4,TimeUnit.SECONDS));assertEquals(0,AttachmentRepository.get(context).book.list().length());}finally{worker.shutdownNow();}
    }
    @Test public void stoppedOriginalTaskClosesBlockedPickedBytesAndSinglePickerRejectsExtraFiles()throws Exception {
        Context context=context();BrowserProfileRegistry profiles=new BrowserProfileRegistry(new File(context.getFilesDir(),"profiles.json"));BrowserProfileRegistry.Session session=profiles.openSession(true);dev.pocketengine.core.RunControl original=new dev.pocketengine.core.RunControl();String task=java.util.UUID.randomUUID().toString(),run=java.util.UUID.randomUUID().toString();original.bindHostRun(task,run,2);original.pause();JSONObject identity=session.identity().put("taskId",task).put("runId",run).put("epoch",2).put("browserSpaceId",java.util.UUID.randomUUID().toString()).put("tabId",java.util.UUID.randomUUID().toString()).put("tabGeneration",1L).put("chooserId",java.util.UUID.randomUUID().toString()).put("navigation",4L);JSONObject target=BrowserUploads.destinationIdentity("https://upload.example/original?document=one");for(var keys=target.keys();keys.hasNext();){String key=keys.next();identity.put(key,target.get(key));}
        try(BrowserUploads.Destination destination=new BrowserUploads.Destination(session,identity,false,()->true,original)){
            Uri first=Uri.parse("content://fixture/one"),second=Uri.parse("content://fixture/two");android.content.ClipData clip=new android.content.ClipData("files",new String[]{"text/plain"},new android.content.ClipData.Item(first));clip.addItem(new android.content.ClipData.Item(second));Intent selection=new Intent();selection.setClipData(clip);assertThrows(IllegalArgumentException.class,()->BrowserUploads.importPicked(context,selection,destination));assertEquals(0,AttachmentRepository.get(context).book.list().length());
            CountDownLatch reading=new CountDownLatch(1),closed=new CountDownLatch(1);InputStream stream=new InputStream(){public int read()throws java.io.IOException{reading.countDown();try{if(!closed.await(3,TimeUnit.SECONDS))throw new java.io.IOException("original task did not close its upload");}catch(InterruptedException stopped){Thread.currentThread().interrupt();}throw new java.io.IOException("closed");}public void close(){closed.countDown();}};Shadows.shadowOf(context.getContentResolver()).registerInputStream(first,stream);var worker=java.util.concurrent.Executors.newSingleThreadExecutor();try{var copied=worker.submit(()->BrowserUploads.importPicked(context,new Intent().setData(first),destination));assertTrue(reading.await(3,TimeUnit.SECONDS));original.cancel();assertTrue(closed.await(1,TimeUnit.SECONDS));assertThrows(java.util.concurrent.ExecutionException.class,()->copied.get(3,TimeUnit.SECONDS));assertEquals(0,AttachmentRepository.get(context).book.list().length());}finally{worker.shutdownNow();}
        }
    }
}
