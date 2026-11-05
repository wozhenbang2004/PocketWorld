package dev.pocketengine.ui;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RuntimeDiagnostics;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import dev.pocketengine.ui.web.PocketWebWindowTicket;
import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Production Web bridge -> original model service -> original SQL/Gateway/HTTP.
 * This suite verifies receipts and effects, not rendered controls or device appearance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,
        shadows={ReadyApplicationFixture.NoUnrelatedStartup.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class ModelDiagnosticsMountedNativeTest {
    private static final class Fixture implements AutoCloseable {
        final OwnerWebTestFixture owner=new OwnerWebTestFixture();
        final EngineApplication app=owner.app;
        final AtomicInteger posts=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final HttpServer server;
        String connectionId;
        String window;
        Fixture(boolean block)throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            try {
                server.createContext("/", exchange -> {
                    try {
                        posts.incrementAndGet();
                        JSONObject request = new JSONObject(new String(
                                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        assertEquals(1, request.getJSONArray("messages").length());
                        String prompt = request.getJSONArray("messages").getJSONObject(0)
                                .getString("content");
                        entered.countDown();
                        if (block) release.await(10, TimeUnit.SECONDS);
                        String text = request.has("tools")
                                ? "This provider fixture does not emit an echo tool"
                                : prompt.substring(prompt.lastIndexOf(' ') + 1);
                        byte[] body = new JSONObject().put("choices", new JSONArray().put(
                                new JSONObject().put("finish_reason", "stop").put("message",
                                        new JSONObject().put("role", "assistant").put("content", text))))
                                .put("usage", new JSONObject().put("prompt_tokens", 2)
                                        .put("completion_tokens", 1))
                                .toString().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } catch (Exception stopped) {
                        // The owner's native cancellation may close this fixture socket.
                    } finally {
                        exchange.close();
                    }
                });
                server.start();
                JSONObject connection=app.settings.saveApiConnection("",0,"Original diagnostic source",new JSONObject()
                        .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                        .put("provider", "openai_chat").put("model", "fixture-ui-model")
                        .put("sendScreenshots", false));
                connectionId=connection.getString("id");app.settings.selectApiConnection(connectionId,1);
                owner.call("connections.enter",new JSONObject().put("section","models"));
            } catch(Exception|Error failure){close();throw failure;}
        }
        JSONObject open()throws Exception {
            JSONObject result=owner.call("modelDiagnostics.open",new JSONObject());window=result.getString("windowId");return result;
        }
        JSONObject review(boolean image)throws Exception {
            return owner.call("modelDiagnostics.review",new JSONObject().put("windowId",window).put("includeImage",image));
        }
        JSONObject start(JSONObject review)throws Exception {
            return owner.call("modelDiagnostics.start",new JSONObject().put("windowId",window).put("reviewHandle",review.getString("reviewHandle")).put("acknowledge",true));
        }
        @Override public void close()throws Exception {release.countDown();server.stop(0);owner.close();}
    }
    private static String diagnosticEvent() {
        RuntimeDiagnostics.install(null,null);
        String task=UUID.randomUUID().toString();
        RunControl original=RunControl.forOriginalRecovery(task,UUID.randomUUID().toString(),3);
        RuntimeDiagnostics.event("model_request","failed","PROVIDER_UNAVAILABLE",original,
                new IllegalStateException("Bearer private-token; prompt-private-body"));
        return task;
    }
    @SuppressWarnings("unchecked")
    private static JSONObject replayLogs(Fixture f,JSONObject first,JSONObject args)throws Exception {
        // Read only the fixture's captured transport, then resend the original request through
        // the real bridge. Do not fabricate a receipt or alter native window/command guards.
        Field document=OwnerWebTestFixture.class.getDeclaredField("document");document.setAccessible(true);
        Field captured=OwnerWebTestFixture.class.getDeclaredField("events");captured.setAccessible(true);
        List<JSONObject> events=(List<JSONObject>)captured.get(f.owner);
        int before=events.size();String id=first.getString("id");
        JSONObject request=new JSONObject().put("v",1).put("id",id).put("documentId",document.get(f.owner))
                .put("action","modelDiagnostics.logs").put("args",args);
        PocketWebWindowTicket ticket=(PocketWebWindowTicket)OwnerWebTestFixture.invoke(f.owner.bridge,"captureWindow");
        f.owner.bridge.postMessage(request.toString(),ticket,()->{throw new AssertionError("Replay transport unexpectedly stale");});
        f.owner.await(()->{
            for(int i=before;i<events.size();i++)if(id.equals(events.get(i).optString("id"))&&"result".equals(events.get(i).optString("kind")))return true;
            return false;
        });
        for(int i=before;i<events.size();i++)if(id.equals(events.get(i).optString("id"))&&"result".equals(events.get(i).optString("kind")))return events.get(i);
        throw new AssertionError("No actual replay response");
    }

    @Test public void runtimeLogsReadOriginalMetadataWithoutStartingAProbeOrTask()throws Exception {
        try(Fixture f=new Fixture(false)){
            String task=diagnosticEvent();f.open();
            JSONObject logs=f.owner.call("modelDiagnostics.logs",new JSONObject().put("windowId",f.window));
            assertEquals("pocketworld.runtime-log.v1",logs.getString("format"));
            assertFalse(logs.getBoolean("bodyIncluded"));assertFalse(logs.getBoolean("automaticExport"));
            JSONArray events=logs.getJSONArray("events");assertTrue(events.length()<=RuntimeDiagnostics.MAX_EVENTS);
            JSONObject failure=null;
            for(int i=0;i<events.length();i++)if(task.equals(events.getJSONObject(i).optString("taskId")))failure=events.getJSONObject(i);
            assertNotNull("Original runtime event must reach the actual Web response",failure);
            assertEquals("model_request",failure.getString("stage"));assertEquals("PROVIDER_UNAVAILABLE",failure.getString("code"));
            assertEquals(3,failure.getInt("epoch"));assertFalse(logs.toString().contains("private-token"));assertFalse(logs.toString().contains("prompt-private-body"));
            assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
            assertNull(f.app.modelConnections.snapshot().optJSONObject("capabilityProbe"));
        }
    }

    @Test public void runtimeLogsRejectClosedReplacedAndDepartedOwnerWindows()throws Exception {
        try(Fixture f=new Fixture(false)){
            diagnosticEvent();f.open();String original=f.window;
            f.owner.call("modelDiagnostics.close",new JSONObject().put("windowId",original));
            assertFalse(f.owner.envelope("modelDiagnostics.logs",new JSONObject().put("windowId",original)).getBoolean("ok"));
            f.open();assertNotEquals(original,f.window);
            assertFalse(f.owner.envelope("modelDiagnostics.logs",new JSONObject().put("windowId",original)).getBoolean("ok"));
            assertTrue(f.owner.call("modelDiagnostics.logs",new JSONObject().put("windowId",f.window)).has("events"));
            f.owner.call("connections.leave",new JSONObject().put("section","models"));
            JSONObject departed=f.owner.envelope("modelDiagnostics.logs",new JSONObject().put("windowId",f.window));
            assertFalse(departed.getBoolean("ok"));assertFalse(departed.has("value"));
            assertEquals(0,f.posts.get());
        }
    }

    @Test public void runtimeLogsNeedForegroundAndAReopenedWindowAfterBackgrounding()throws Exception {
        try(Fixture f=new Fixture(false)){
            diagnosticEvent();f.open();String original=f.window;
            Field foreground=PocketWorldController.class.getDeclaredField("voiceForeground");foreground.setAccessible(true);
            try{
                foreground.setBoolean(f.owner.controller,false);f.owner.bridge.invalidatePresentation(false);
                JSONObject hidden=f.owner.envelope("modelDiagnostics.logs",new JSONObject().put("windowId",original));
                assertFalse(hidden.getBoolean("ok"));assertFalse(hidden.has("value"));
            }finally{foreground.setBoolean(f.owner.controller,true);}
            assertFalse(f.owner.envelope("modelDiagnostics.logs",new JSONObject().put("windowId",original)).getBoolean("ok"));
            f.open();assertTrue(f.owner.call("modelDiagnostics.logs",new JSONObject().put("windowId",f.window)).has("events"));
            assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
        }
    }

    @Test public void replayedLogRequestDoesNotRetainOrReturnAnEarlierSnapshot()throws Exception {
        try(Fixture f=new Fixture(false)){
            diagnosticEvent();f.open();JSONObject original=new JSONObject().put("windowId",f.window);
            JSONObject first=f.owner.envelope("modelDiagnostics.logs",original);
            assertTrue(first.getBoolean("ok"));assertTrue(first.getJSONObject("value").getJSONArray("events").length()>0);
            f.open(); // Retire the reader window while preserving the command receipt cache.
            JSONObject repeated=replayLogs(f,first,original);
            assertFalse(repeated.getBoolean("ok"));assertFalse(repeated.has("value"));
            assertFalse(repeated.toString().contains("PROVIDER_UNAVAILABLE"));
            assertTrue(f.owner.call("modelDiagnostics.logs",new JSONObject().put("windowId",f.window)).has("events"));
            assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
        }
    }

    @Test public void reviewDoesNotSendRequestsAndExplicitStartShowsIndependentObservedResults()throws Exception {
        try(Fixture f=new Fixture(false)){
            JSONObject opened=f.open();assertEquals("fixture-ui-model",opened.getString("model"));assertEquals(0,f.posts.get());
            JSONObject reviewed=f.review(false);assertEquals(2,reviewed.getInt("maxRequests"));assertFalse(reviewed.getBoolean("includeImage"));assertEquals(0,f.posts.get());
            JSONObject started=f.start(reviewed);assertTrue(started.getBoolean("started"));assertFalse(started.getBoolean("verified"));
            f.owner.await(()->f.app.modelConnections.snapshot().optJSONObject("capabilityProbe")!=null);
            assertEquals(2,f.posts.get());JSONObject probe=f.app.modelConnections.snapshot().getJSONObject("capabilityProbe");
            assertEquals("passed",probe.getString("text"));assertEquals("unconfirmed",probe.getString("tools"));assertEquals("not_requested",probe.getString("image"));
            String task=probe.getJSONObject("inferenceReference").getString("taskId");
            assertEquals(2,f.app.tasks.inferenceUsage(task).getInt("attempts"));assertEquals(0,f.app.tasks.get(task,false).getJSONArray("operations").length());assertFalse(probe.getBoolean("capabilityMetadataChanged"));
            JSONObject repeated=f.owner.envelope("modelDiagnostics.start",new JSONObject().put("windowId",f.window).put("reviewHandle",reviewed.getString("reviewHandle")).put("acknowledge",true));
            assertFalse(repeated.getBoolean("ok"));assertEquals(2,f.posts.get());
        }
    }
    @Test public void ownerStopDoesNotIssueSecondDiagnosticOrEraseFirstPhysicalUse()throws Exception {
        try(Fixture f=new Fixture(true)){
            f.open();f.start(f.review(false));assertTrue(f.entered.await(5,TimeUnit.SECONDS));
            f.owner.call("modelDiagnostics.stop",new JSONObject().put("windowId",f.window));f.release.countDown();
            f.owner.await(()->{
                JSONArray tasks=f.app.tasks.list();for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i);if("inference".equals(task.optString("kind"))&&"cancelled".equals(task.optString("status")))return true;}return false;
            });
            assertEquals(1,f.posts.get());JSONObject task=null;JSONArray tasks=f.app.tasks.list();
            for(int i=0;i<tasks.length();i++)if("inference".equals(tasks.getJSONObject(i).optString("kind")))task=tasks.getJSONObject(i);
            assertNotNull(task);assertEquals("cancelled",task.getString("status"));assertEquals(1,f.app.tasks.inferenceUsage(task.getString("id")).getInt("attempts"));assertEquals(0,f.app.tasks.get(task.getString("id"),false).getJSONArray("operations").length());assertNull(f.app.modelConnections.snapshot().optJSONObject("capabilityProbe"));
        }
    }
    @Test public void leavingSettingsRetiresUnconsumedReviewWithoutStartingInference()throws Exception {
        try(Fixture f=new Fixture(false)){
            f.open();JSONObject review=f.review(true);assertEquals(3,review.getInt("maxRequests"));
            f.owner.call("connections.leave",new JSONObject().put("section","models"));f.owner.call("connections.enter",new JSONObject().put("section","models"));f.open();
            assertFalse(f.owner.envelope("modelDiagnostics.start",new JSONObject().put("windowId",f.window).put("reviewHandle",review.getString("reviewHandle")).put("acknowledge",true)).getBoolean("ok"));
            assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());
        }
    }
    @Test public void queuedReviewedProbeCannotTestAReplacementModelOrCreateUsageForIt()throws Exception {
        try(Fixture f=new Fixture(false)){
            f.open();JSONObject reviewed=f.review(false);
            Field adapterField=f.owner.bridge.getClass().getDeclaredField("modelDiagnostics");adapterField.setAccessible(true);
            Object adapter=adapterField.get(f.owner.bridge);Field workerField=adapter.getClass().getDeclaredField("worker");workerField.setAccessible(true);
            ExecutorService worker=(ExecutorService)workerField.get(adapter);
            CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);
            worker.submit(()->{waiting.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
            assertTrue(waiting.await(5,TimeUnit.SECONDS));
            try{
                assertTrue(f.start(reviewed).getBoolean("started"));
                f.app.settings.saveApiConnection(f.connectionId,1,"Original diagnostic source",new JSONObject()
                    .put("baseUrl","http://127.0.0.1:"+f.server.getAddress().getPort()+"/v1").put("provider","openai_chat")
                    .put("model","replacement-must-not-be-probed").put("sendScreenshots",false));
                f.app.settings.selectApiConnection(f.connectionId,2);
            }finally{release.countDown();}
            f.owner.await(()->!f.owner.call("modelDiagnostics.read",new JSONObject().put("windowId",f.window)).getBoolean("busy"));
            assertEquals(0,f.posts.get());assertEquals(0,f.app.tasks.list().length());assertNull(f.app.modelConnections.snapshot().optJSONObject("capabilityProbe"));
            assertFalse(f.owner.call("modelDiagnostics.read",new JSONObject().put("windowId",f.window)).optString("error").isEmpty());
        }
    }
}
