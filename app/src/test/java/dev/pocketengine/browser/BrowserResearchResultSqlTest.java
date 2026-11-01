package dev.pocketengine.browser;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.os.Looper;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.*;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original TaskStore/source host/download HTTP/canonical attachment host/provider. No external reader or device UI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserResearchResultSqlTest {
    static final class F implements AutoCloseable {
        final BrowserTaskPersistenceTest.Fixture original=new BrowserTaskPersistenceTest.Fixture();final Context context;final AttachmentRepository repository;final AttachmentOutcomeHost canonical;final BrowserModule module;final BrowserTaskHost host;final BrowserDownloads downloads;final HttpServer server;final ExecutorService worker=Executors.newSingleThreadExecutor();final JSONObject completed;final String id,sha,downloadId;final byte[] bytes="真实已下载规格与原字节🙂".getBytes(StandardCharsets.UTF_8);
        F()throws Exception {
            File root=Files.createTempDirectory("research-file-review-").toFile(),files=new File(root,"files"),cache=new File(root,"cache");files.mkdirs();cache.mkdirs();context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}@Override public File getCacheDir(){return cache;}};
            FeatureContext features=new FeatureContext(context,null,null,event->{});repository=AttachmentRepository.get(context);canonical=new AttachmentOutcomeHost(original.tasks,repository,new AttachmentsModule(features),(command,args,control)->{throw new AssertionError("Owner file viewing cannot invoke a media/model action");});module=new BrowserModule(features,canonical);host=new BrowserTaskHost(module,original.tasks,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){public void checkModelConfigured(){throw new AssertionError("No model setup for saved-file viewing");}public void launch(String task){throw new AssertionError("No new task for saved-file viewing");}public RunControl currentControl(String task){return original.control;}},event->{});
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/file",exchange->{try{exchange.getResponseHeaders().add("Content-Type","text/plain; charset=utf-8");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();downloads=BrowserDownloads.get(context);JSONObject started=downloads.start("http://127.0.0.1:"+server.getAddress().getPort()+"/file","Explicit native download fixture",(String)null,"规格.txt","text/plain",new JSONObject().put("taskId",original.binding.taskId).put("runId",original.binding.runId).put("epoch",original.binding.epoch),null);downloadId=started.getString("id");long until=System.nanoTime()+5_000_000_000L;JSONObject status=started;while(status.getString("status").matches("queued|running|stopping")&&System.nanoTime()<until){Thread.sleep(5);status=downloads.status(downloadId);}assertEquals("downloaded",status.getString("status"));completed=status;id=completed.getJSONObject("attachment").getString("id");sha=completed.getJSONObject("attachment").getString("sha256");
        }
        <T>T call(Callable<T> action)throws Exception {Future<T> future=worker.submit(action);long until=System.nanoTime()+5_000_000_000L;while(!future.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(3);}try{return future.get(1,TimeUnit.SECONDS);}catch(ExecutionException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw new AssertionError(failed.getCause());}}
        Intent open(RunControl control)throws Exception{return call(()->host.openDownloadedAttachmentForOwner(original.binding.taskId,downloadId,id,sha,control));}
        AttachmentProvider provider(){AttachmentProvider provider=new AttachmentProvider();ProviderInfo info=new ProviderInfo();info.authority=context.getPackageName()+".attachments";provider.attachInfo(context,info);return provider;}
        int snapshots(){File dir=new File(context.getCacheDir(),"outcome-views");File[] all=dir.listFiles();return all==null?0:all.length;}
        public void close()throws Exception {server.stop(0);module.close();Shadows.shadowOf(Looper.getMainLooper()).idle();worker.shutdownNow();worker.awaitTermination(1,TimeUnit.SECONDS);original.close();}
    }
    @Test public void exactCompletedDownloadOpensVerifiedReadonlySnapshotNotMutableCanonicalPath()throws Exception {try(F f=new F()){
        Intent prepared=f.open(new RunControl());assertEquals(Intent.ACTION_VIEW,prepared.getAction());assertEquals("view",prepared.getData().getPathSegments().get(0));assertNotEquals(f.repository.uri(f.id),prepared.getData());assertEquals(0,prepared.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);assertNotEquals(0,prepared.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);
        File canonical=f.repository.book.file(f.id);Files.write(canonical.toPath(),"替换字节不是原规格".getBytes(StandardCharsets.UTF_8));try(var stream=new android.os.ParcelFileDescriptor.AutoCloseInputStream(f.provider().openFile(prepared.getData(),"r"))){assertArrayEquals(f.bytes,stream.readAllBytes());}
        assertThrows(Exception.class,()->f.open(new RunControl()));f.host.discardResearchViewForOwner(prepared);assertTrue(canonical.exists());assertThrows(java.io.FileNotFoundException.class,()->f.provider().openFile(prepared.getData(),"r"));
    }}
    @Test public void alreadyIssuedViewCannotBeDiscardedAndCancelledUnissuedPreparationCleansItsCopy()throws Exception {try(F f=new F()){
        Intent issued=f.open(new RunControl());issued.putExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,true);assertThrows(SecurityException.class,()->f.host.discardResearchViewForOwner(issued));try(var stream=new android.os.ParcelFileDescriptor.AutoCloseInputStream(f.provider().openFile(issued.getData(),"r"))){assertArrayEquals(f.bytes,stream.readAllBytes());}
        // No external reader was started in this fixture; emulate the native startActivity failure branch.
        issued.putExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,false);f.host.discardResearchViewForOwner(issued);assertEquals(0,f.snapshots());RunControl cancelled=new RunControl();AtomicBoolean sawPrepared=new AtomicBoolean();cancelled.setAuthorityCheck(()->{if(f.snapshots()>0&&sawPrepared.compareAndSet(false,true))cancelled.cancel();});assertThrows(Exception.class,()->f.open(cancelled));assertTrue(sawPrepared.get());assertEquals(0,f.snapshots());assertArrayEquals(f.bytes,Files.readAllBytes(f.repository.book.file(f.id).toPath()));
    }}
    @Test public void crossTaskWrongVersionAndCanonicalSourceRetargetAreRejectedWithoutFallback()throws Exception {try(F f=new F()){
        String foreign=f.original.tasks.createCapabilityTask("其他私人任务","").getString("id");assertThrows(SecurityException.class,()->f.call(()->f.host.openDownloadedAttachmentForOwner(foreign,f.downloadId,f.id,f.sha,new RunControl())));assertThrows(SecurityException.class,()->f.call(()->f.host.openDownloadedAttachmentForOwner(f.original.binding.taskId,f.downloadId,f.id,"b".repeat(64),new RunControl())));assertEquals(0,f.snapshots());
        File record=new File(f.repository.book.file(f.id).getParentFile(),f.id+".json");JSONObject metadata=new JSONObject(Files.readString(record.toPath()));metadata.getJSONObject("source").put("taskId",foreign);Files.writeString(record.toPath(),metadata.toString());assertThrows(SecurityException.class,()->f.open(new RunControl()));assertEquals(0,f.snapshots());assertThrows(SecurityException.class,()->f.module.handle("task.open_attachment",new JSONObject()));
    }}
    @Test public void sourceClickReadsExactPersistedOriginalAndDoesNotSubstituteModelOrCurrentWebPage()throws Exception {try(F f=new F()){
        JSONObject approvalBefore=f.original.tasks.get(f.original.binding.taskId,false);boolean hadApprovals=approvalBefore.has("approvals");String originalApprovals=approvalBefore.optString("approvals","");JSONObject source=f.original.source();String operation=f.original.journal.beginOperation("browser_task_retain_source",f.original.arguments(source));f.original.journal.finishOperation(operation,f.original.sources.retain(f.original.binding,f.original.control,operation,source.getString("sourceId"),source));JSONObject selected=f.call(()->f.host.savedSourceForOwner(f.original.binding.taskId,source.getString("sourceId")));assertEquals(source.getString("excerpt"),selected.getString("excerpt"));assertEquals(source.getString("privateUrl"),selected.getString("privateUrl"));assertEquals(source.getLong("observedAt"),selected.getLong("observedAt"));selected.put("excerpt","model changed quote");assertEquals(source.getString("excerpt"),f.call(()->f.host.savedSourceForOwner(f.original.binding.taskId,source.getString("sourceId"))).getString("excerpt"));
        String foreign=f.original.tasks.createCapabilityTask("另一任务","").getString("id");assertThrows(SecurityException.class,()->f.call(()->f.host.savedSourceForOwner(foreign,source.getString("sourceId"))));JSONObject approvalAfter=f.original.tasks.get(f.original.binding.taskId,false);assertEquals(hadApprovals,approvalAfter.has("approvals"));assertEquals(originalApprovals,approvalAfter.optString("approvals",""));
    }}
}
