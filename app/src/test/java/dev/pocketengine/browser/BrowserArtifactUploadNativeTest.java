package dev.pocketengine.browser;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.webkit.WebChromeClient;
import dev.pocketengine.features.attachments.AttachmentProvider;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original journal/SQLite/Book/snapshot/provider. Chooser and UI scheduling are explicit platform-interface fixtures. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserArtifactUploadNativeTest {
    static JSONObject j(Object... values)throws Exception{return BrowserTaskPersistenceTest.obj(values);}
    static final class Params extends WebChromeClient.FileChooserParams {
        public int getMode(){return MODE_OPEN;}public String[] getAcceptTypes(){return new String[]{".csv"};}public boolean isCaptureEnabled(){return false;}public CharSequence getTitle(){return "fixture file chooser";}public String getFilenameHint(){return "";}public Intent createIntent(){return new Intent(Intent.ACTION_OPEN_DOCUMENT);}
    }
    static final class F implements AutoCloseable {
        final BrowserTaskPersistenceTest.Fixture original=new BrowserTaskPersistenceTest.Fixture();final android.content.Context context=RuntimeEnvironment.getApplication();final AttachmentRepository repository=AttachmentRepository.get(context);final ExecutorService worker=Executors.newSingleThreadExecutor();final BlockingQueue<Runnable> ui=new LinkedBlockingQueue<>();final List<Runnable> timers=new ArrayList<>();final AtomicInteger opened=new AtomicInteger(),callbacks=new AtomicInteger(),discarded=new AtomicInteger();final AtomicReference<Uri> received=new AtomicReference<>();final AtomicBoolean live=new AtomicBoolean(true);final JSONObject artifact;Runnable beforeChooser=()->{};boolean throwCallback;
        F()throws Exception {original.tasks.authorizeTools(original.binding.taskId,original.binding.runId,new JSONArray().put("browser_task_open").put("browser_task_retain_source").put("browser_task_upload_attachment"));artifact=repository.book.importBytes(new ByteArrayInputStream("列,值\n真实,17\n".getBytes(StandardCharsets.UTF_8)),"result.csv","text/csv",j("kind","fixture_task_artifact","taskId",original.binding.taskId));}
        JSONObject identity(String operation)throws Exception {JSONObject identity=original.source().put("documentId",UUID.randomUUID().toString()).put("chooserId",UUID.randomUUID().toString()).put("operationId",operation).put("fileInput",j("observationId",UUID.randomUUID().toString(),"ref","n0","nodeKey","k1"));JSONObject destination=BrowserUploads.destinationIdentity("https://upload.example/input?csrf=private");for(var keys=destination.keys();keys.hasNext();){String key=keys.next();identity.put(key,destination.get(key));}return identity;}
        JSONObject run()throws Exception {
            JSONObject args=j("attachmentId",artifact.getString("id"),"sha256",artifact.getString("sha256"));String operation=original.journal.beginOperation("browser_task_upload_attachment",args);JSONObject pin=BrowserUploads.artifactPin(context,original.binding.taskId,artifact.getString("id"),artifact.getString("sha256"));CompletableFuture<JSONObject> result=new CompletableFuture<>();
            BrowserArtifactUpload.Host host=new BrowserArtifactUpload.Host(){
                public void post(Runnable work){ui.add(work);}public void later(Runnable work,long delay){assertEquals(5000,delay);timers.add(work);}public void check()throws Exception{original.control.check();original.authority.requirePrepared(operation,"browser_task_upload_attachment",args);if(!live.get())throw new SecurityException("original page changed");}
                public void openInput(BrowserArtifactUpload delivery)throws Exception{opened.incrementAndGet();beforeChooser.run();assertTrue(delivery.chooser(values->{callbacks.incrementAndGet();received.set(values==null?null:values[0]);if(throwCallback)throw new IllegalStateException("native callback entered but result lost");},new Params()));}
                public void discardUnsent(Uri stage)throws Exception{discarded.incrementAndGet();java.io.File root=new java.io.File(context.getCacheDir(),"outcome-views");Files.deleteIfExists(new java.io.File(root,stage.getLastPathSegment()+".blob").toPath());Files.deleteIfExists(new java.io.File(root,stage.getLastPathSegment()+".json").toPath());}
            };
            try(BrowserArtifactUpload upload=new BrowserArtifactUpload(context,original.tasks,original.control,identity(operation),pin,worker,host,result)){
                upload.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!result.isDone()&&System.nanoTime()<deadline){Runnable event=ui.poll(20,TimeUnit.MILLISECONDS);if(event!=null)event.run();}JSONObject receipt=result.get(1,TimeUnit.SECONDS);Runnable event;while((event=ui.poll())!=null)event.run();original.journal.finishOperation(operation,receipt);return receipt;
            }
        }
        public void close()throws Exception {worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);original.close();}
    }
    @Test public void exactBytesReachOneChooserAndSameUnknownSiteVersionCannotAutomaticallyUploadAgain()throws Exception {try(F f=new F()){
        JSONObject receipt=f.run();assertEquals("local_delivered",receipt.getString("status"));assertTrue(receipt.getBoolean("requiresPause"));assertFalse(receipt.getBoolean("siteConfirmed"));assertEquals(1,f.callbacks.get());assertNotNull(f.received.get());
        AttachmentProvider provider=new AttachmentProvider();ProviderInfo info=new ProviderInfo();info.authority=f.context.getPackageName()+".attachments";provider.attachInfo(f.context,info);try(var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(f.received.get(),"r"))){assertArrayEquals("列,值\n真实,17\n".getBytes(StandardCharsets.UTF_8),input.readAllBytes());}
        JSONObject saved=f.original.tasks.get(f.original.binding.taskId,false).getJSONArray("browserUploadAttempts").getJSONObject(0);assertEquals("local_delivered",saved.getString("deliveryState"));assertEquals("unknown",saved.getString("siteState"));assertTrue(saved.getJSONObject("pin").has("operationId"));assertEquals("k1",saved.getJSONObject("pin").getJSONObject("fileInput").getString("nodeKey"));
        assertEquals("unavailable",f.run().getString("status"));assertEquals(1,f.opened.get());assertEquals(1,f.callbacks.get());assertEquals(1,f.original.tasks.get(f.original.binding.taskId,false).getJSONArray("browserUploadAttempts").length());assertTrue(f.discarded.get()>0);
    }}
    @Test public void sourceWithdrawalAfterStagingReturnsNullToChooserAndDeletesUnsentCopy()throws Exception {try(F f=new F()){
        f.beforeChooser=()->{try{f.repository.remove(f.artifact.getString("id"));}catch(Exception error){throw new AssertionError(error);}};JSONObject receipt=f.run();assertEquals("unavailable",receipt.getString("status"));assertFalse(receipt.getBoolean("accepted"));assertNull(f.received.get());assertEquals(1,f.callbacks.get());assertEquals(1,f.discarded.get());JSONObject attempt=f.original.tasks.get(f.original.binding.taskId,false).getJSONArray("browserUploadAttempts").getJSONObject(0);assertEquals("not_delivered",attempt.getString("deliveryState"));
    }}
    @Test public void callbackFailurePreservesDeliveryUnknownAndNeverClaimsServerAcceptance()throws Exception {try(F f=new F()){
        f.throwCallback=true;JSONObject receipt=f.run();assertEquals("unknown",receipt.getString("status"));assertTrue(receipt.isNull("accepted"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));assertEquals(1,f.callbacks.get());JSONObject attempt=f.original.tasks.get(f.original.binding.taskId,false).getJSONArray("browserUploadAttempts").getJSONObject(0);assertEquals("delivery_unknown",attempt.getString("deliveryState"));assertEquals("unknown",attempt.getString("siteState"));assertTrue(f.original.tasks.hasUnresolved(f.original.binding.taskId));
    }}
    @Test public void foreignTaskAndIncorrectByteVersionCannotBecomeAnArtifactPin()throws Exception {try(F f=new F()){
        assertThrows(SecurityException.class,()->BrowserUploads.artifactPin(f.context,UUID.randomUUID().toString(),f.artifact.getString("id"),f.artifact.getString("sha256")));assertThrows(SecurityException.class,()->BrowserUploads.artifactPin(f.context,f.original.binding.taskId,f.artifact.getString("id"),"a".repeat(64)));assertEquals(0,f.callbacks.get());
    }}
}
