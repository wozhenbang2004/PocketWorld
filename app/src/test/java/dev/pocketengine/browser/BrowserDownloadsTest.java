package dev.pocketengine.browser;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real loopback HTTP with actual attachment bytes; does not render WebView or test live site authentication. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class BrowserDownloadsTest {
    private HttpServer first,second;private Context context;
    private Context context()throws Exception{File files=Files.createTempDirectory("pocket-download-").toFile();context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};return context;}
    private HttpServer server()throws Exception{return HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}
    private String url(HttpServer server,String path){return "http://127.0.0.1:"+server.getAddress().getPort()+path;}
    @After public void close(){if(first!=null)first.stop(0);if(second!=null)second.stop(0);if(context!=null)BrowserDownloads.closeFor(context);}
    private JSONObject await(BrowserDownloads downloads,String id)throws Exception{long until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){JSONObject record=downloads.status(id);if(!record.getString("status").matches("queued|running|stopping"))return record;Thread.sleep(10);}throw new AssertionError("Download did not finish");}
    private Field field(String name)throws Exception {Field field=BrowserDownloads.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private File blockReceipts(BrowserDownloads downloads)throws Exception {Field field=field("receipts");File original=(File)field.get(downloads),parent=new File(context.getFilesDir(),"receipt-parent-file");Files.writeString(parent.toPath(),"not a directory");field.set(downloads,new File(parent,"receipts.json"));return original;}
    private ExecutorService worker(BrowserDownloads downloads)throws Exception {return (ExecutorService)field("worker").get(downloads);}
    private JSONObject identity()throws Exception {return new JSONObject().put("taskId","original-task").put("runId","original-run").put("epoch",7).put("operationId",UUID.randomUUID().toString()).put("documentId",UUID.randomUUID().toString()).put("navigation",3);}
    private void assertUnsubmitted(BrowserDownloads downloads,int count,JSONObject identity)throws Exception {
        assertEquals(count,downloads.list().length());
        for(int i=0;i<count;i++){JSONObject record=downloads.list().getJSONObject(i);assertEquals("failed",record.getString("status"));assertFalse(record.getBoolean("verified"));assertFalse(record.has("attachment"));assertEquals(identity.getString("taskId"),record.getString("taskId"));assertEquals(identity.getString("runId"),record.getString("runId"));assertEquals(identity.getString("operationId"),record.getString("operationId"));assertEquals("failed",downloads.cancel(record.getString("id")).getString("status"));}
        for(String name:new String[]{"cancellations","sessions","fences"})assertTrue(name,((java.util.Map<?,?>)field(name).get(downloads)).isEmpty());
    }
    @Test public void failedHttpAdmissionReleasesEveryOriginalSlotAndStorageRecoveryDoesNotReplay()throws Exception {
        Context context=context();AtomicInteger requests=new AtomicInteger();first=server();first.createContext("/file",exchange->{requests.incrementAndGet();byte[] body="only the new request".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});first.start();
        BrowserDownloads downloads=BrowserDownloads.get(context);JSONObject identity=identity();File original=blockReceipts(downloads);
        for(int i=0;i<4;i++)assertThrows(IOException.class,()->downloads.start(url(first,"/file"),"PocketTest",(String)null,"not-started.txt","text/plain",identity,null));
        assertUnsubmitted(downloads,4,identity);assertEquals(0,requests.get());field("receipts").set(downloads,original);
        JSONObject next=downloads.start(url(first,"/file"),"PocketTest",(String)null,"new-request.txt","text/plain",identity(),null);assertEquals("downloaded",await(downloads,next.getString("id")).getString("status"));assertEquals(1,requests.get());
        org.json.JSONArray persisted=new org.json.JSONArray(Files.readString(original.toPath()));for(int i=0;i<4;i++)assertEquals("failed",persisted.getJSONObject(i).getString("status"));
    }
    @Test public void failedDocumentAdmissionReleasesSessionAndFenceWithoutOpeningTheDocument()throws Exception {
        Context context=context();BrowserDownloads downloads=BrowserDownloads.get(context);BrowserProfileRegistry.Session session=new BrowserProfileRegistry(new File(context.getFilesDir(),"profiles.json")).openSession(true);JSONObject identity=identity();AtomicInteger opens=new AtomicInteger();File original=blockReceipts(downloads);
        BrowserDownloads.DocumentSource source=()->{opens.incrementAndGet();throw new java.io.IOException("document fixture");};
        for(int i=0;i<4;i++)assertThrows(IOException.class,()->downloads.startDocument("not-started.txt",identity,session,session,source,null));
        assertUnsubmitted(downloads,4,identity);assertEquals(0,opens.get());field("receipts").set(downloads,original);
        JSONObject next=downloads.startDocument("new-request.txt",identity(),session,session,source,null);assertEquals("failed",await(downloads,next.getString("id")).getString("status"));assertEquals(1,opens.get());
    }
    @Test public void rejectedHttpWorkersKeepFailedOriginalReceiptsWithoutUsingQueueSlots()throws Exception {
        Context context=context();BrowserDownloads downloads=BrowserDownloads.get(context);JSONObject identity=identity();worker(downloads).shutdown();
        for(int i=0;i<4;i++)assertThrows(RejectedExecutionException.class,()->downloads.start("http://127.0.0.1:1/never","PocketTest",(String)null,"not-started.txt","text/plain",identity,null));
        assertUnsubmitted(downloads,4,identity);BrowserDownloads.closeFor(context);assertUnsubmitted(BrowserDownloads.get(context),4,identity);
    }
    @Test public void rejectedDocumentWorkersKeepTheOriginalRejectionAndDoNotOpenTheSource()throws Exception {
        Context context=context();BrowserDownloads downloads=BrowserDownloads.get(context);BrowserProfileRegistry.Session session=new BrowserProfileRegistry(new File(context.getFilesDir(),"profiles.json")).openSession(true);JSONObject identity=identity();AtomicInteger opens=new AtomicInteger();worker(downloads).shutdown();
        for(int i=0;i<4;i++)assertThrows(RejectedExecutionException.class,()->downloads.startDocument("not-started.txt",identity,session,session,()->{opens.incrementAndGet();throw new java.io.IOException("must not open");},null));
        assertUnsubmitted(downloads,4,identity);assertEquals(0,opens.get());
    }
    @Test public void admittedDownloadWithACommittedFileAndReceiptFailureStaysUnknownWithoutReplay()throws Exception {
        Context context=context();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();first=server();first.createContext("/file",exchange->{requests.incrementAndGet();entered.countDown();release.await(4,TimeUnit.SECONDS);byte[] body="original committed bytes".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});first.start();
        BrowserDownloads downloads=BrowserDownloads.get(context);JSONObject identity=identity(),job=downloads.start(url(first,"/file"),"PocketTest",(String)null,"unknown.txt","text/plain",identity,null);assertTrue(entered.await(4,TimeUnit.SECONDS));File original=blockReceipts(downloads);release.countDown();worker(downloads).submit(()->{}).get(5,TimeUnit.SECONDS);
        JSONObject unknown=downloads.status(job.getString("id"));assertEquals("unknown",unknown.getString("status"));assertEquals(identity.getString("operationId"),unknown.getString("operationId"));assertEquals("original-run",unknown.getString("runId"));assertEquals(1,requests.get());assertEquals(1,AttachmentRepository.get(context).book.list().length());assertEquals("original committed bytes",AttachmentRepository.get(context).book.readText(unknown.getJSONObject("attachment").getString("id")).getString("text"));field("receipts").set(downloads,original);
    }
    @Test public void downloadsRealBytesAndNeverPersistsCookieOrSignedQuery()throws Exception{
        Context context=context();AtomicReference<String> cookie=new AtomicReference<>();first=server();first.createContext("/file",exchange->{cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));byte[] body="actual file 字节".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/plain; charset=utf-8");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});first.start();
        BrowserDownloads downloads=BrowserDownloads.get(context);JSONObject started=downloads.start(url(first,"/file?secret=query-value"),"PocketTest","session=private-cookie","sample.txt","text/plain",new JSONObject().put("taskId","task-1").put("runId","run-1"),null);JSONObject done=await(downloads,started.getString("id"));assertEquals("downloaded",done.getString("status"));assertEquals("session=private-cookie",cookie.get());assertEquals("actual file 字节",AttachmentRepository.get(context).book.readText(done.getJSONObject("attachment").getString("id")).getString("text"));String persisted=new String(Files.readAllBytes(new File(context.getFilesDir(),"browser-download-receipts.json").toPath()),StandardCharsets.UTF_8);assertFalse(persisted.contains("private-cookie"));assertFalse(persisted.contains("query-value"));assertFalse(done.getBoolean("verified"));
    }
    @Test public void crossOriginRedirectDoesNotForwardBrowserCredentials()throws Exception{
        Context context=context();AtomicInteger reached=new AtomicInteger();second=server();second.createContext("/target",exchange->{reached.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});second.start();first=server();first.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location",url(second,"/target"));exchange.sendResponseHeaders(302,-1);exchange.close();});first.start();BrowserDownloads downloads=BrowserDownloads.get(context);JSONObject job=downloads.start(url(first,"/redirect"),"PocketTest","session=secret","a.txt","text/plain",null,null);assertEquals("failed",await(downloads,job.getString("id")).getString("status"));assertEquals(0,reached.get());assertEquals(0,AttachmentRepository.get(context).book.list().length());
    }
    @Test public void invalidUrlsCannotReachLocalFilesOrCarryUserInfo()throws Exception{
        assertThrows(IllegalArgumentException.class,()->BrowserDownloads.valid("file:///data/private"));assertThrows(IllegalArgumentException.class,()->BrowserDownloads.valid("https://user:secret@example.com/a"));assertTrue(BrowserDownloads.sameOrigin(BrowserDownloads.valid("https://example.com:443/a"),BrowserDownloads.valid("https://example.com/b")));assertFalse(BrowserDownloads.sameOrigin(BrowserDownloads.valid("http://example.com/a"),BrowserDownloads.valid("https://example.com/a")));
    }
    @Test public void sameOriginRedirectResolvesCookiesForEachExactUrlAndKeepsProfileProvenance()throws Exception {
        Context context=context();BrowserProfileRegistry profiles=new BrowserProfileRegistry(new File(context.getFilesDir(),"profiles.json"));String work=profiles.create("工作",profiles.revision(),true).getString("id");profiles.select(work,profiles.revision(),true);BrowserProfileRegistry.Session session=profiles.openSession(true);AtomicReference<String> startCookie=new AtomicReference<>(),finishCookie=new AtomicReference<>();
        first=server();first.createContext("/one",exchange->{startCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));exchange.getResponseHeaders().add("Location","/two");exchange.sendResponseHeaders(302,-1);exchange.close();});first.createContext("/two",exchange->{finishCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));byte[] bytes="profile scoped".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});first.start();
        BrowserDownloads downloads=BrowserDownloads.get(context);JSONObject job=downloads.start(url(first,"/one"),"PocketTest",target->target.endsWith("/one")?"path=one":"path=two","scoped.txt","text/plain",new JSONObject().put("taskId","task-1"),session,null);JSONObject done=await(downloads,job.getString("id"));assertEquals("downloaded",done.getString("status"));assertEquals("path=one",startCookie.get());assertEquals("path=two",finishCookie.get());assertEquals(work,done.getString("profileId"));assertEquals(session.sessionId,done.getString("browserSessionId"));assertEquals(0,downloads.list(BrowserLease.PROFILE).length());assertThrows(SecurityException.class,()->downloads.status(job.getString("id"),BrowserLease.PROFILE));assertEquals(work,done.getJSONObject("attachment").getJSONObject("source").getString("profileId"));
    }
    @Test public void switchingProfileDisconnectsAnInFlightDownloadAndDoesNotCommitItsBytes()throws Exception {
        Context context=context();BrowserProfileRegistry profiles=new BrowserProfileRegistry(new File(context.getFilesDir(),"profiles.json"));BrowserProfileRegistry.Session session=profiles.openSession(true);java.util.concurrent.CountDownLatch reading=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);AtomicInteger requested=new AtomicInteger();
        first=server();first.createContext("/slow",exchange->{requested.incrementAndGet();exchange.sendResponseHeaders(200,100000);exchange.getResponseBody().write(new byte[32]);exchange.getResponseBody().flush();reading.countDown();release.await(4,java.util.concurrent.TimeUnit.SECONDS);exchange.close();});first.start();
        BrowserDownloads downloads=BrowserDownloads.get(context);JSONObject job=downloads.start(url(first,"/slow"),"PocketTest",target->"session=work","cancelled.txt","text/plain",null,session,null);assertTrue(reading.await(4,java.util.concurrent.TimeUnit.SECONDS));profiles.select(session.profileId,profiles.revision(),true);downloads.cancelSession(session.sessionId);release.countDown();JSONObject stopped=await(downloads,job.getString("id"));assertEquals("cancelled",stopped.getString("status"));assertEquals(1,requested.get());assertFalse(stopped.getBoolean("verified"));assertEquals(0,AttachmentRepository.get(context).book.list().length());assertFalse(stopped.has("attachment"));
    }
}
