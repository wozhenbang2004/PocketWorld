package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.*;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original TaskStore/Capture and a loopback OTLP receiver; no provider bill or device claim. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeDiagnosticsJourneyTest {
    private static SQLiteDatabase db(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final NativeDiagnosticsHost host;final NativeDiagnosticsHost.OwnerWindow window;final File archive;
        Fixture()throws Exception{app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);archive=new File(app.getFilesDir(),"diagnostics-"+UUID.randomUUID()+".json");host=new NativeDiagnosticsHost(archive,tasks);window=host.openOwnerWindow();window.setForeground(true);}
        public void close()throws Exception{window.close();db(tasks).close();archive.delete();}
        Call original(boolean finished)throws Exception{return new Call(tasks,finished);}
        long enable(String endpoint,int cap)throws Exception{return host.configure(window,host.snapshot(window).getLong("generation"),true,cap,endpoint!=null,endpoint==null?"":endpoint).getLong("generation");}
        JSONObject capture(Call call,long generation)throws Exception{JSONObject state=host.capture(window,generation,call.task,new RunControl());return state.getJSONArray("snapshots").getJSONObject(0);}
    }
    private static final class Call implements AutoCloseable {
        final RunControl control=new RunControl();final NativeInferenceWork work;final InferenceCapture capture;final String task;
        Call(TaskStore tasks,boolean finish)throws Exception{
            work=NativeInferenceWork.open(tasks,TaskStore.NativeInferenceKind.CONNECTION_TEST,UUID.randomUUID().toString(),control);task=work.reference().getString("taskId");
            capture=new InferenceCapture(new ModelConfig("https://fixture.invalid/v1","DO-NOT-EXPORT-SECRET","fixture-model",false,8,5),control,work.target());capture.dispatch();capture.dispatch();
            if(finish)finish();
        }
        void finish()throws Exception{capture.usage.observe(new JSONObject().put("usage",new JSONObject().put("prompt_tokens",13).put("completion_tokens",7).put("total_tokens",20).put("prompt_tokens_details",new JSONObject().put("cached_tokens",3))),ProviderUsage.Stage.FINAL);capture.finish(InferenceReceipt.Outcome.COMPLETED);work.completed();}
        public void close()throws Exception{work.close();}
    }
    @Test public void originalSqlTrendMatchesTaskTotalsAndExcludesExternalScope()throws Exception{
        try(Fixture f=new Fixture();Call first=f.original(true);Call second=f.original(true)){
            JSONObject external=f.tasks.get(second.task,false);external.put("actorId","external-member").put("spaceId","group:private-not-assumed");
            ContentValues values=new ContentValues();values.put("ui_header",TaskAuditStore.header(external).toString());db(f.tasks).update("tasks",values,"task_id=?",new String[]{second.task});
            long now=System.currentTimeMillis();JSONObject report=f.host.trends(f.window,new UsageTrendProjection.Query(now-86_400_000,now+86_400_000,"UTC"),new RunControl());
            assertEquals(1,report.getJSONObject("totals").getInt("attempts"));assertEquals(1,report.getInt("scopeExcludedRows"));
            assertEquals(f.tasks.inferenceUsage(first.task).getInt("attempts"),report.getJSONObject("totals").getInt("attempts"));
            assertEquals(13,report.getJSONObject("totals").getJSONObject("counters").getJSONObject("INPUT").getLong("knownSubtotal"));
            assertEquals(3,report.getJSONObject("totals").getJSONObject("counters").getJSONObject("CACHE_READ").getLong("knownSubtotal"));
            assertFalse(f.archive.exists());assertEquals(2,f.tasks.nativeInferenceUsagePage("",40).getInt("headerRowsExamined"));
        }
    }
    @Test public void defaultOffGenerationAndActualSourceRevisionFenceOldCaptureAndExport()throws Exception{
        try(Fixture f=new Fixture();Call original=f.original(false)){
            assertFalse(f.host.snapshot(f.window).getBoolean("enabled"));
            assertThrows(SecurityException.class,()->f.host.capture(f.window,1,original.task,new RunControl()));
            long generation=f.enable("https://collector.example.invalid/v1/traces",NativeDiagnosticsHost.DEFAULT_CAP_BYTES);
            JSONObject trace=f.capture(original,generation);assertEquals(1,trace.getInt("unknownOutcomes"));
            var review=f.host.prepareExport(f.window,generation,trace.getString("snapshotId"),new RunControl());
            original.finish();assertThrows(SecurityException.class,()->f.host.send(f.window,review,new RunControl()));
            long next=f.host.configure(f.window,generation,false,NativeDiagnosticsHost.DEFAULT_CAP_BYTES,false,"").getLong("generation");
            f.host.configure(f.window,next,true,NativeDiagnosticsHost.DEFAULT_CAP_BYTES,false,"");
            assertThrows(SecurityException.class,()->f.host.capture(f.window,generation,original.task,new RunControl()));
            RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->f.host.trends(f.window,new UsageTrendProjection.Query(1,1000,"UTC"),cancelled));
        }
    }
    @Test public void concurrentCapturesStayWithinUtf8CapAndDoNotCreateTaskAuthority()throws Exception{
        try(Fixture f=new Fixture()){
            long generation=f.enable(null,NativeDiagnosticsHost.MIN_CAP_BYTES);java.util.List<Call> originals=new java.util.ArrayList<>();
            for(int i=0;i<18;i++)originals.add(f.original(true));ExecutorService workers=Executors.newFixedThreadPool(3);
            try{java.util.List<Future<?>> jobs=new java.util.ArrayList<>();for(Call call:originals)jobs.add(workers.submit(()->{try{f.capture(call,generation);}catch(Exception e){throw new RuntimeException(e);}}));for(Future<?> job:jobs)job.get(10,TimeUnit.SECONDS);}
            finally{workers.shutdownNow();for(Call call:originals)call.close();}
            JSONObject saved=f.host.snapshot(f.window);assertTrue(f.archive.length()<=NativeDiagnosticsHost.MIN_CAP_BYTES);assertEquals(f.archive.length(),saved.getLong("storedBytes"));
            assertTrue(saved.getJSONArray("snapshots").length()<18);assertEquals(18,f.tasks.list().length());
            String stored=new String(java.nio.file.Files.readAllBytes(f.archive.toPath()),StandardCharsets.UTF_8);assertFalse(stored.contains("DO-NOT-EXPORT-SECRET"));
            NativeDiagnosticsHost reopened=new NativeDiagnosticsHost(f.archive,f.tasks);var window=reopened.openOwnerWindow();window.setForeground(true);assertEquals(saved.getLong("generation"),reopened.snapshot(window).getLong("generation"));window.close();
        }
    }
    @Test public void realOtlpPartialAcceptanceIsNotSuccessOrRetriedByDuplicateClicks()throws Exception{
        AtomicInteger requests=new AtomicInteger();AtomicReference<String> body=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/traces",exchange->{try{requests.incrementAndGet();body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] response="{\"partialSuccess\":{\"rejectedSpans\":\"1\",\"errorMessage\":\"PRIVATE-COLLECTOR-MESSAGE\"}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}finally{exchange.close();}});server.start();
        try(Fixture f=new Fixture();Call original=f.original(true)){
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces";long generation=f.enable(endpoint,NativeDiagnosticsHost.DEFAULT_CAP_BYTES);JSONObject trace=f.capture(original,generation);
            var preview=f.host.prepareExport(f.window,generation,trace.getString("snapshotId"),new RunControl());assertFalse(preview.view().getBoolean("bodyIncluded"));
            JSONObject result=f.host.send(f.window,preview,new RunControl());assertEquals("partial",result.getString("outcome"));assertEquals(1,result.getInt("rejectedSpans"));assertEquals(1,requests.get());
            assertFalse(body.get().contains(original.task));assertFalse(body.get().contains("DO-NOT-EXPORT-SECRET"));assertFalse(result.toString().contains("PRIVATE-COLLECTOR"));
            assertThrows(SecurityException.class,()->f.host.send(f.window,preview,new RunControl()));assertThrows(SecurityException.class,()->f.host.prepareExport(f.window,generation,trace.getString("snapshotId"),new RunControl()));assertEquals(1,requests.get());
        }finally{server.stop(0);}
    }
    @Test public void disablingDuringRealExportCancelsWaitAndLateReceiptCannotRestoreSettings()throws Exception{
        CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService sender=Executors.newSingleThreadExecutor();
        server.createContext("/v1/traces",exchange->{try{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();received.countDown();release.await(5,TimeUnit.SECONDS);byte[] response="{}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}catch(Exception stopped){/* Client cancellation is expected. */}finally{exchange.close();}});server.start();
        try(Fixture f=new Fixture();Call original=f.original(true)){
            long generation=f.enable("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces",NativeDiagnosticsHost.DEFAULT_CAP_BYTES);JSONObject trace=f.capture(original,generation);var preview=f.host.prepareExport(f.window,generation,trace.getString("snapshotId"),new RunControl());
            Future<JSONObject> sent=sender.submit(()->f.host.send(f.window,preview,new RunControl()));assertTrue(received.await(5,TimeUnit.SECONDS));
            JSONObject disabled=f.host.configure(f.window,generation,false,NativeDiagnosticsHost.DEFAULT_CAP_BYTES,false,"");release.countDown();JSONObject result=sent.get(5,TimeUnit.SECONDS);
            assertEquals("unknown_after_cancel",result.getString("outcome"));assertEquals(1,requests.get());JSONObject current=f.host.snapshot(f.window);assertFalse(current.getBoolean("enabled"));assertEquals(disabled.getLong("generation"),current.getLong("generation"));
            assertEquals("unknown_after_cancel",current.getJSONArray("snapshots").getJSONObject(0).getJSONObject("lastExport").getString("outcome"));
        }finally{release.countDown();server.stop(0);sender.shutdownNow();}
    }
    @Test public void inFlightExportReceiptSurvivesConcurrentCapEvictionAndRestart()throws Exception{
        CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService sender=Executors.newSingleThreadExecutor();
        server.createContext("/v1/traces",exchange->{try{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();received.countDown();release.await(15,TimeUnit.SECONDS);
            byte[] response="{}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }finally{exchange.close();}});server.start();
        try(Fixture f=new Fixture();Call original=f.original(true)){
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces";long generation=f.enable(endpoint,NativeDiagnosticsHost.MIN_CAP_BYTES);
            String id=f.capture(original,generation).getString("snapshotId");var preview=f.host.prepareExport(f.window,generation,id,new RunControl());
            Future<JSONObject> sent=sender.submit(()->f.host.send(f.window,preview,new RunControl()));assertTrue(received.await(5,TimeUnit.SECONDS));
            for(int i=0;i<18;i++)try(Call additional=f.original(true)){f.capture(additional,generation);}
            JSONObject during=f.host.snapshot(f.window);assertTrue(f.archive.length()<=NativeDiagnosticsHost.MIN_CAP_BYTES);
            assertTrue(during.getJSONArray("snapshots").length()<19);assertEquals("dispatch_unknown",trace(during,id).getJSONObject("lastExport").getString("outcome"));
            NativeDiagnosticsHost restarted=new NativeDiagnosticsHost(f.archive,f.tasks);var reopened=restarted.openOwnerWindow();reopened.setForeground(true);
            try{assertEquals("dispatch_unknown",trace(restarted.snapshot(reopened),id).getJSONObject("lastExport").getString("outcome"));
                assertThrows(SecurityException.class,()->restarted.prepareExport(reopened,generation,id,new RunControl()));
            }finally{reopened.close();}
            release.countDown();assertEquals("accepted",sent.get(5,TimeUnit.SECONDS).getString("outcome"));assertEquals(1,requests.get());
            assertEquals("accepted",trace(f.host.snapshot(f.window),id).getJSONObject("lastExport").getString("outcome"));
        }finally{release.countDown();server.stop(0);sender.shutdownNow();}
    }
    private static JSONObject trace(JSONObject state,String id)throws Exception{
        JSONArray traces=state.getJSONArray("snapshots");for(int i=0;i<traces.length();i++)if(id.equals(traces.getJSONObject(i).optString("snapshotId")))return traces.getJSONObject(i);
        throw new AssertionError("Original in-flight export receipt was evicted");
    }
    @Test public void lostOwnerWindowAndEndpointValidationPreventExport()throws Exception{
        try(Fixture f=new Fixture();Call original=f.original(true)){
            assertThrows(IllegalArgumentException.class,()->f.enable("https://user:secret@example.invalid/v1/traces",32768));
            assertThrows(IllegalArgumentException.class,()->f.enable("https://example.invalid/v1/traces?token=secret",32768));
            long generation=f.enable(null,32768);f.window.setForeground(false);assertThrows(SecurityException.class,()->f.host.capture(f.window,generation,original.task,new RunControl()));
            assertFalse(f.host.openOwnerWindow().equals(f.window));
        }
    }
}
