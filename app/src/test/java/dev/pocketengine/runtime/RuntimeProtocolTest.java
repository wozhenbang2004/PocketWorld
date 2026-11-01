package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.CapabilityPort;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Real loopback HTTP plus a deterministic PhonePort: no model account or Android device needed. */
public final class RuntimeProtocolTest {
    private static final String IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQZkAAAAASUVORK5CYII=";
    private ServerSocket server;
    private ExecutorService threads;
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<JSONObject> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch firstRequest = new CountDownLatch(1);
    private String base;

    @Before public void startServer() throws Exception {
        threads = Executors.newCachedThreadPool();
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        threads.submit(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    threads.submit(() -> serve(socket));
                } catch (IOException stopped) { break; }
            }
        });
        base = "http://127.0.0.1:" + server.getLocalPort();
    }

    @After public void stopServer() {
        try { server.close(); } catch (IOException ignored) { }
        threads.shutdownNow();
    }

    @Test public void connectionTestUsesActualPostAndBothEndpointForms() throws Exception {
        replies.add(new Reply(200, answer("OK"), 0));
        JSONObject result = ModelGateway.testConnection(config(base + "/v1", true, 3));
        assertTrue(result.getBoolean("ok"));
        assertFalse(result.getBoolean("visionVerified"));
        assertEquals("/v1/chat/completions", paths.get(0));
        assertEquals("Bearer unit-test-secret", authHeaders.get(0));
        assertEquals("test-model", requests.get(0).getString("model"));
        assertFalse(requests.get(0).has("tools"));
        replies.add(new Reply(200, answer("OK"), 0));
        assertTrue(ModelGateway.testConnection(config(base + "/custom/chat/completions/", false, 1)).getBoolean("ok"));
        assertEquals("/custom/chat/completions", paths.get(1));
    }

    @Test public void unknownEffectStopsBeforeAnotherModelRequestOrDispatch()throws Exception{
        replies.add(new Reply(200,tool("click","phone_click_node","{\"observationId\":\"obs-1\",\"nodeId\":\"node-1\"}"),0));
        FakePhone phone=new FakePhone();phone.receipt=new JSONObject().put("code","action_unknown").put("accepted",JSONObject.NULL).put("ok",false);
        RunOutcome result=new AgentRunner(phone).run("unknown","点击一次",config(base,true,4),new RunControl(),event->{});
        assertEquals("awaiting_user",result.status);assertTrue(result.evidence.getBoolean("requiresReconciliation"));
        assertEquals(1,phone.executed.size());assertEquals(1,requests.size());
    }
    @Test public void nativeRequiresPauseReceiptStopsTheRemainingBatchBeforeReobserveOrAnotherModel()throws Exception{
        JSONArray batch=new JSONArray().put(call("first","phone_launch_app","{\"packageName\":\"test.first\"}"))
                .put(call("second","phone_launch_app","{\"packageName\":\"test.second\"}"));
        replies.add(new Reply(200,modelMessage(new JSONObject().put("tool_calls",batch)),0));replies.add(new Reply(200,answer("must not request again"),0));
        FakePhone phone=new FakePhone();phone.receipt=new JSONObject().put("ok",false).put("accepted",false).put("error","focus_isolation_lost").put("message","原副屏焦点隔离已失效").put("requiresPause",true).put("nextObservationAction","pause");
        RunOutcome result=new AgentRunner(phone).run("pause-refusal","处理原任务",config(base,false,4),new RunControl(),event->{},new MemoryJournal());
        assertEquals("awaiting_user",result.status);assertEquals("focus_isolation_lost",result.evidence.getString("error"));assertEquals(1,phone.executed.size());assertEquals(1,phone.observations);assertEquals(1,requests.size());
    }
    @Test public void adaptiveMissingTargetHelpSendsFreshRealPixelsWithoutReplayingTheAction()throws Exception{
        replies.add(new Reply(200,tool("help","phone_observation_help","{\"observationId\":\"obs-1\",\"reason\":\"target_missing\"}"),0));
        replies.add(new Reply(200,tool("tap","phone_tap","{\"observationId\":\"obs-3\",\"x\":500,\"y\":500}"),0));replies.add(new Reply(200,answer("已取得实际回执，请核对。"),0));
        FakePhone physical=new FakePhone();physical.adaptiveMetadata=true;RunControl control=new RunControl();control.bindHostRun("adaptive","run-one",1);
        dev.pocketengine.phone.observation.AdaptivePhonePort adaptive=new dev.pocketengine.phone.observation.AdaptivePhonePort(physical,new dev.pocketengine.phone.observation.AdaptivePhonePort.Scope("adaptive","run-one",1,"fixture-service",0),true,true,()->1000L);
        java.util.concurrent.atomic.AtomicInteger operations=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicBoolean persistedPixels=new java.util.concurrent.atomic.AtomicBoolean();
        MemoryJournal journal=new MemoryJournal(){@Override public String beginOperation(String tool,JSONObject args){operations.incrementAndGet();return "tap-operation";}@Override public void checkpoint(JSONArray messages,int steps,JSONObject observation){if(messages.toString().contains(IMAGE)||observation.toString().contains(IMAGE))persistedPixels.set(true);}};
        RunOutcome result=new AgentRunner(adaptive).run("adaptive","定位节点中没有的目标",config(base,true,4).withOwnerImageInput(true),control,event->{},journal);
        assertEquals("needs_confirmation",result.status);assertEquals(0,countImages(requests.get(0)));assertEquals(1,countImages(requests.get(1)));assertEquals(List.of("phone_tap"),physical.executed);assertEquals(1,operations.get());assertFalse(persistedPixels.get());assertTrue(requests.get(0).getJSONArray("tools").toString().contains("phone_observation_help"));
    }
    @Test public void rejectedDurableIntentPreventsThePhoneEffect()throws Exception{
        replies.add(new Reply(200,tool("click","phone_click_node","{\"observationId\":\"obs-1\",\"nodeId\":\"node-1\"}"),0));
        FakePhone phone=new FakePhone();MemoryJournal journal=new MemoryJournal(){@Override public String beginOperation(String tool,JSONObject args)throws Exception{throw new IOException("disk full");}};
        RunOutcome result=new AgentRunner(phone).run("journal","点击一次",config(base,true,4),new RunControl(),event->{},journal);
        assertEquals("failed",result.status);assertTrue(phone.executed.isEmpty());assertEquals(1,requests.size());
    }
    @Test public void restoredToolCallIsHistoryAndReconciliationIsIncludedBeforeFreshPlanning()throws Exception{
        replies.add(new Reply(200,answer("检查当前结果。"),0));FakePhone phone=new FakePhone();MemoryJournal journal=new MemoryJournal();
        JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","原目标"))
                .put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(call("old","phone_click_node","{\"observationId\":\"old-screen\",\"nodeId\":\"old-node\"}"))));
        journal.saved=new JSONObject().put("messages",history).put("steps",1).put("feedback","不要重复发送")
                .put("operationResults",new JSONArray().put(new JSONObject().put("outcome","occurred")));
        RunOutcome result=new AgentRunner(phone).run("restored","原目标",config(base,true,4),new RunControl(),event->{},journal);
        assertEquals("needs_confirmation",result.status);assertTrue(phone.executed.isEmpty());assertTrue(phone.clearCount>0);
        String request=requests.get(0).getJSONArray("messages").toString();assertTrue(request.contains("not_replayed"));assertTrue(request.contains("occurred"));assertTrue(request.contains("不要重复发送"));
    }
    @Test public void takeoverBetweenBatchToolsDiscardsEvenCallsWithoutObservationReferences()throws Exception{
        JSONArray calls=new JSONArray().put(call("first","phone_launch_app","{\"packageName\":\"test.first\"}"))
                .put(call("old-second","phone_launch_app","{\"packageName\":\"test.second\"}"));
        replies.add(new Reply(200,modelMessage(new JSONObject().put("tool_calls",calls)),0));replies.add(new Reply(200,answer("已按接管后的页面重新规划。"),0));
        FakePhone phone=new FakePhone();RunControl control=new RunControl();phone.onExecute=()->{if(phone.executed.size()==1){control.pause();control.resume();}};
        RunOutcome result=new AgentRunner(phone).run("takeover-batch","按顺序处理",config(base,false,4),control,event->{});
        assertEquals("needs_confirmation",result.status);assertEquals(1,phone.executed.size());assertEquals(2,requests.size());
        assertTrue(requests.get(1).toString().contains("stale_observation"));
    }
    @Test public void capabilityOnlyTurnUsesActualToolReceiptWithoutPretendingToObserveAPhone()throws Exception{
        replies.add(new Reply(200,tool("read","workspace_read","{}"),0));replies.add(new Reply(200,answer("读取到真实文件fixture。"),0));
        CapabilityPort port=new CapabilityPort(){
            public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","workspace_read").put("parameters",new JSONObject().put("type","object"))));}
            public boolean supports(String name){return name.equals("workspace_read");}public boolean sideEffecting(String name){return false;}
            public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{control.check();return new JSONObject().put("content","actual-file-fixture").put("version",7);}
        };
        RunOutcome result=new AgentRunner(null,port).run("capability","读取已有文件",config(base,true,4),new RunControl(),event->{},new MemoryJournal());
        assertEquals("needs_confirmation",result.status);assertTrue(result.evidence.getBoolean("noDeviceObservation"));
        assertEquals(0,countImages(requests.get(0)));assertEquals(1,requests.get(0).getJSONArray("tools").length());assertTrue(requests.get(1).toString().contains("actual-file-fixture"));
    }
    @Test public void cancellingAModelWithABlockedResponseDoesNotBlockTheCaller()throws Exception{
        Reply slow=new Reply(200,answer("delayed"),0);slow.partial=true;replies.add(slow);RunControl control=new RunControl();
        Future<?> pending=threads.submit(()->{try{ModelGateway.complete(config(base,false,4),new JSONArray().put(new JSONObject().put("role","user").put("content","hello")),null,control);}catch(Exception expected){}});
        try{
            assertTrue(slow.partialStarted.await(2,TimeUnit.SECONDS));Thread.sleep(60);
            Future<?> cancelled=threads.submit(control::cancel);cancelled.get(250,TimeUnit.MILLISECONDS);assertTrue(control.isCancelled());
        }finally{slow.releasePartial.countDown();pending.get(3,TimeUnit.SECONDS);}
    }
    @Test public void referenceContextRefreshesEachRequestAndNeverEntersTheDurableJournal()throws Exception{
        replies.add(new Reply(200,tool("read","workspace_read","{}"),0));replies.add(new Reply(200,answer("核对完成"),0));
        java.util.concurrent.atomic.AtomicInteger snapshots=new java.util.concurrent.atomic.AtomicInteger();
        CapabilityPort port=new CapabilityPort(){
            public JSONObject contextSnapshot(String query)throws Exception{return new JSONObject().put("context",snapshots.incrementAndGet()==1?"reference-before-correction":"reference-after-correction");}
            public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","workspace_read").put("parameters",new JSONObject().put("type","object"))));}
            public boolean supports(String name){return name.equals("workspace_read");}public boolean sideEffecting(String name){return false;}
            public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{return new JSONObject().put("content","real fixture receipt");}
        };
        java.util.concurrent.atomic.AtomicReference<String> checkpoint=new java.util.concurrent.atomic.AtomicReference<>("");
        MemoryJournal journal=new MemoryJournal(){@Override public void checkpoint(JSONArray messages,int steps,JSONObject observation){checkpoint.set(messages.toString());}};
        assertEquals("needs_confirmation",new AgentRunner(null,port).run("reference-task","读取文件",config(base,true,4),new RunControl(),event->{},journal).status);
        assertTrue(requests.get(0).toString().contains("reference-before-correction"));assertFalse(requests.get(1).toString().contains("reference-before-correction"));assertTrue(requests.get(1).toString().contains("reference-after-correction"));
        assertFalse(checkpoint.get().contains("reference-before-correction"));assertFalse(checkpoint.get().contains("reference-after-correction"));
    }
    @Test public void liveVersionedMemoryReadIsUsableInRequestButNeverPersistsItsBody()throws Exception{
        final String body="live-private-reference-body";
        replies.add(new Reply(200,tool("memory-read","memory_search","{}"),0));replies.add(new Reply(200,answer("已核对当前资料"),0));
        CapabilityPort port=new CapabilityPort(){
            public JSONObject contextSnapshot(String query)throws Exception{return new JSONObject().put("context","").put("referenceValidation",new JSONObject().put("memory",new JSONArray().put(new JSONObject().put("id","memory-record").put("revision",1))).put("skills",new JSONArray()));}
            public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","memory_search").put("parameters",new JSONObject().put("type","object"))));}
            public boolean supports(String name){return name.equals("memory_search");}public boolean sideEffecting(String name){return false;}
            public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{return new JSONObject().put("records",new JSONArray().put(new JSONObject().put("id","memory-record").put("revision",1).put("text",body)));}
        };
        java.util.concurrent.atomic.AtomicReference<String> checkpoint=new java.util.concurrent.atomic.AtomicReference<>("");java.util.concurrent.atomic.AtomicBoolean persisted=new java.util.concurrent.atomic.AtomicBoolean();
        MemoryJournal journal=new MemoryJournal(){@Override public void checkpoint(JSONArray messages,int steps,JSONObject observation){checkpoint.set(messages.toString());if(messages.toString().contains(body))persisted.set(true);}};
        assertEquals("needs_confirmation",new AgentRunner(null,port).run("reference-live","核对资料",config(base,true,4),new RunControl(),event->{},journal).status);
        assertTrue(requests.get(1).getJSONArray("messages").toString().contains(body));assertFalse(persisted.get());assertFalse(checkpoint.get().contains(body));assertTrue(checkpoint.get().contains("memory-read"));
    }

    private static class MemoryJournal implements RunJournal{
        JSONObject saved=new JSONObject();
        public JSONObject restore()throws Exception{return new JSONObject(saved.toString());}
        public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{}
        public String beginOperation(String tool,JSONObject args)throws Exception{return "test-operation";}
        public void finishOperation(String id,JSONObject receipt)throws Exception{}
    }

    @Test public void realVisionToolRoundTripKeepsImagesOutOfTextAndEvents() throws Exception {
        replies.add(new Reply(200, tool("click", "phone_click_node", "{\"observationId\":\"obs-1\",\"nodeId\":\"node-1\"}"), 0));
        replies.add(new Reply(200, answer("已点击，需确认结果。"), 0));
        FakePhone phone = new FakePhone();
        List<JSONObject> events = new ArrayList<>();
        RunOutcome result = new AgentRunner(phone).run("task-1", "点击按钮 unit-test-secret", config(base, true, 3), new RunControl(), events::add);
        assertEquals("needs_confirmation", result.status);
        assertFalse(result.evidence.getBoolean("automaticallyVerified"));
        assertEquals(1, phone.executed.size());
        assertEquals(3, phone.observations); // Initial, post-action, final verification.
        assertEquals(IMAGE, result.evidence.getString("imageBase64"));
        assertEquals(1, countImages(requests.get(0)));
        String dataUrl = requests.get(0).getJSONArray("messages").getJSONObject(2)
                .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url");
        assertEquals("data:image/png;base64," + IMAGE, dataUrl);
        JSONArray history = requests.get(1).getJSONArray("messages");
        boolean toolSeen = false;
        for (int i = 0; i < history.length(); i++) {
            JSONObject message = history.getJSONObject(i);
            if ("tool".equals(message.optString("role"))) {
                toolSeen = true;
                assertEquals("click", message.getString("tool_call_id"));
                assertFalse(message.getString("content").contains("imageBase64"));
                assertFalse(message.getString("content").contains(IMAGE));
            }
        }
        assertTrue(toolSeen);
        assertFalse(requests.get(0).toString().contains("unit-test-secret"));
        for (JSONObject event : events) {
            assertEquals("task-1", event.getString("taskId"));
            assertTrue(event.has("type") && event.has("status") && event.has("message"));
            assertFalse(event.toString().contains(IMAGE));
            assertFalse(event.toString().contains("imageBase64"));
            assertFalse(event.toString().contains("unit-test-secret"));
        }
    }

    @Test public void batchCannotReuseObservationAfterFirstAction() throws Exception {
        JSONArray calls = new JSONArray()
                .put(call("one", "phone_tap", "{\"observationId\":\"obs-1\",\"x\":500,\"y\":500}"))
                .put(call("two", "phone_tap", "{\"observationId\":\"obs-1\",\"x\":200,\"y\":200}"));
        replies.add(new Reply(200, modelMessage(new JSONObject().put("tool_calls", calls)), 0));
        replies.add(new Reply(200, answer("检查结果。"), 0));
        FakePhone phone = new FakePhone();
        RunOutcome result = new AgentRunner(phone).run("stale", "点击两次", config(base, false, 4), new RunControl(), event -> { });
        assertEquals("needs_confirmation", result.status);
        assertEquals(1, phone.executed.size());
        assertTrue(requests.get(1).toString().contains("stale_observation"));
        assertTrue(requests.get(1).toString().contains("obs-2"));
    }

    @Test public void staleBackendReceiptIsRealAndTriggersFreshObservation() throws Exception {
        replies.add(new Reply(200, tool("tap", "phone_tap", "{\"observationId\":\"obs-1\",\"x\":100,\"y\":200}"), 0));
        replies.add(new Reply(200, answer("页面变化，等待确认。"), 0));
        FakePhone phone = new FakePhone();
        phone.receipt = new JSONObject().put("ok", false).put("code", "stale_observation").put("message", "页面已变化");
        new AgentRunner(phone).run("stale-native", "点击", config(base, false, 3), new RunControl(), event -> { });
        assertTrue(requests.get(1).toString().contains("stale_observation"));
        assertEquals(3, phone.observations);
    }

    @Test public void cancellationImmediatelyBeforeDispatchPreventsAction() throws Exception {
        replies.add(new Reply(200, tool("tap", "phone_tap", "{\"observationId\":\"obs-1\",\"x\":100,\"y\":200}"), 0));
        RunControl control = new RunControl();
        FakePhone phone = new FakePhone();
        RunOutcome result = new AgentRunner(phone).run("cancel-action", "点击", config(base, false, 3), control, event -> {
            if ("phone_tap".equals(event.optString("tool")) && "running".equals(event.optString("status"))) control.cancel();
        });
        assertEquals("cancelled", result.status);
        assertTrue(phone.executed.isEmpty());
        assertEquals(1, requests.size());
    }

    @Test public void stoppingDisconnectsBlockedHttpRequest() throws Exception {
        replies.add(new Reply(200, answer("too late"), 5000));
        RunControl control = new RunControl();
        FakePhone phone = new FakePhone();
        Future<RunOutcome> pending = threads.submit(() -> new AgentRunner(phone).run("cancel-http", "观察", config(base, false, 3), control, event -> { }));
        assertTrue(firstRequest.await(2, TimeUnit.SECONDS));
        long start = System.nanoTime();
        control.cancel();
        RunOutcome result = pending.get(2, TimeUnit.SECONDS);
        assertEquals("cancelled", result.status);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800);
        assertTrue(phone.executed.isEmpty());
    }

    @Test public void pauseClearsReferencesAndRejectsOldDecision() throws Exception {
        replies.add(new Reply(200, tool("tap", "phone_tap", "{\"observationId\":\"obs-1\",\"x\":100,\"y\":200}"), 0));
        replies.add(new Reply(200, answer("重新观察后等待确认。"), 0));
        RunControl control = new RunControl();
        AtomicBoolean once = new AtomicBoolean();
        List<JSONObject> events = new ArrayList<>();
        FakePhone phone = new FakePhone();
        RunOutcome result = new AgentRunner(phone).run("pause", "点击", config(base, false, 3), control, event -> {
            events.add(event);
            if ("phone_tap".equals(event.optString("tool")) && "running".equals(event.optString("status")) && once.compareAndSet(false, true)) {
                control.pause();
                threads.submit(() -> { Thread.sleep(100); control.resume(); return null; });
            }
        });
        assertEquals("needs_confirmation", result.status);
        assertTrue(phone.executed.isEmpty());
        assertTrue(phone.clearCount >= 2);
        assertTrue(events.stream().anyMatch(event -> "paused".equals(event.optString("status"))));
        assertTrue(requests.get(1).toString().contains("stale_observation"));
    }

    @Test public void invalidCoordinatesCannotReachPhone() throws Exception {
        replies.add(new Reply(200, tool("tap", "phone_tap", "{\"observationId\":\"obs-1\",\"x\":1001,\"y\":200}"), 0));
        replies.add(new Reply(200, answer("未执行无效点击。"), 0));
        FakePhone phone = new FakePhone();
        new AgentRunner(phone).run("invalid", "点击", config(base, false, 3), new RunControl(), event -> { });
        assertTrue(phone.executed.isEmpty());
        assertTrue(requests.get(1).toString().contains("invalid_arguments"));
    }

    @Test public void quickPauseAndResumeDuringModelCallDiscardsOldDecision() throws Exception {
        replies.add(new Reply(200, tool("old-tap", "phone_tap", "{\"observationId\":\"obs-1\",\"x\":100,\"y\":200}"), 150));
        replies.add(new Reply(200, answer("已根据新页面重新判断。"), 0));
        RunControl control = new RunControl();
        FakePhone phone = new FakePhone();
        Future<RunOutcome> pending = threads.submit(() -> new AgentRunner(phone).run("quick-pause", "点击", config(base, false, 3), control, event -> { }));
        assertTrue(firstRequest.await(2, TimeUnit.SECONDS));
        control.pause();
        control.resume();
        RunOutcome result = pending.get(2, TimeUnit.SECONDS);
        assertEquals("needs_confirmation", result.status);
        assertTrue(phone.executed.isEmpty());
        assertTrue(phone.clearCount >= 2);
        assertTrue(requests.get(1).toString().contains("obs-2"));
        assertFalse(requests.get(1).toString().contains("old-tap"));
    }

    @Test public void malformedJsonArgumentsCannotReachPhone() throws Exception {
        replies.add(new Reply(200, tool("tap", "phone_tap", "not json"), 0));
        replies.add(new Reply(200, answer("未执行。"), 0));
        FakePhone phone = new FakePhone();
        new AgentRunner(phone).run("invalid-json", "点击", config(base, false, 3), new RunControl(), event -> { });
        assertTrue(phone.executed.isEmpty());
    }

    @Test public void emptyTextCanClearAnInput() throws Exception {
        replies.add(new Reply(200, tool("clear", "phone_type", "{\"observationId\":\"obs-1\",\"nodeId\":\"node-1\",\"text\":\"\"}"), 0));
        replies.add(new Reply(200, answer("请确认已清空。"), 0));
        FakePhone phone = new FakePhone();
        new AgentRunner(phone).run("clear", "清空输入框", config(base, false, 3), new RunControl(), event -> { });
        assertEquals(Collections.singletonList("phone_type"), phone.executed);
    }

    @Test public void toolBudgetBoundsRepeatedModelRequests() throws Exception {
        replies.add(new Reply(200, tool("observe-1", "phone_observe", "{}"), 0));
        replies.add(new Reply(200, tool("observe-2", "phone_observe", "{}"), 0));
        FakePhone phone = new FakePhone();
        RunOutcome result = new AgentRunner(phone).run("budget", "持续观察", config(base, false, 1), new RunControl(), event -> { });
        assertEquals("needs_confirmation", result.status);
        assertEquals(1, result.evidence.getInt("steps"));
        assertEquals(2, requests.size());
        assertEquals(2, phone.observations);
    }

    @Test public void missingImageUsesTextNodesWithoutInventingVision() throws Exception {
        replies.add(new Reply(200, answer("仅根据节点文字读取。"), 0));
        FakePhone phone = new FakePhone();
        phone.withImage = false;
        RunOutcome result = new AgentRunner(phone).run("no-image", "读取页面", config(base, true, 2), new RunControl(), event -> { });
        assertEquals("needs_confirmation", result.status);
        assertEquals(0, countImages(requests.get(0)));
        assertTrue(requests.get(0).toString().contains("actualImageAttached\\\":false"));
        assertTrue(requests.get(0).toString().contains("node-1"));
    }

    @Test public void disabledVisionDoesNotSendEvenAvailableImage() throws Exception {
        replies.add(new Reply(200, answer("依据控件树。"), 0));
        new AgentRunner(new FakePhone()).run("vision-off", "读取", config(base, false, 2), new RunControl(), event -> { });
        assertEquals(0, countImages(requests.get(0)));
        assertFalse(requests.get(0).toString().contains(IMAGE));
    }

    @Test public void modelHistoryKeepsAtMostTwoActualImages() throws Exception {
        for (int i = 0; i < 3; i++) replies.add(new Reply(200, tool("observe-" + i, "phone_observe", "{}"), 0));
        replies.add(new Reply(200, answer("等待确认。"), 0));
        RunOutcome result = new AgentRunner(new FakePhone()).run("images", "观察几次", config(base, true, 5), new RunControl(), event -> { });
        assertEquals("needs_confirmation", result.status);
        assertEquals(4, requests.size());
        for (JSONObject request : requests) {
            assertTrue(countImages(request) <= 2);
            JSONArray messages = request.getJSONArray("messages");
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                if ("tool".equals(message.optString("role"))) {
                    assertFalse(message.getString("content").contains("imageBase64"));
                    assertFalse(message.getString("content").contains(IMAGE));
                }
            }
        }
    }

    @Test public void explicitTakeoverReturnsAwaitingUser() throws Exception {
        replies.add(new Reply(200, tool("user", "phone_request_user", "{\"reason\":\"请完成验证码。\"}"), 0));
        FakePhone phone = new FakePhone();
        RunOutcome result = new AgentRunner(phone).run("takeover", "登录", config(base, false, 3), new RunControl(), event -> { });
        assertEquals("awaiting_user", result.status);
        assertTrue(result.summary.contains("验证码"));
        assertTrue(phone.executed.isEmpty());
    }

    @Test public void authenticationErrorDoesNotEchoProviderBodyOrKey() throws Exception {
        replies.add(new Reply(401, "{\"error\":{\"message\":\"bad unit-test-secret bearer\"}}", 0));
        JSONObject result = ModelGateway.testConnection(config(base, false, 1));
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getString("message").contains("认证失败"));
        assertFalse(result.toString().contains("unit-test-secret"));
    }

    @Test public void malformedModelReplyFailsWithoutSuccessClaim() throws Exception {
        replies.add(new Reply(200, "{\"unexpected\":true}", 0));
        RunOutcome result = new AgentRunner(new FakePhone()).run("bad-model", "操作", config(base, false, 2), new RunControl(), event -> { });
        assertEquals("failed", result.status);
        assertTrue(result.summary, result.summary.contains("未派发工具") && result.summary.contains("自动重试"));
    }

    @Test public void readTimeoutHasActionableMessageAndNoLeakedCredentials() throws Exception {
        replies.add(new Reply(200, answer("too late"), 12_000));
        JSONObject result = ModelGateway.testConnection(config(base, false, 1));
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getString("message").contains("超时"));
        assertFalse(result.toString().contains("unit-test-secret"));
    }

    @Test public void disconnectedPhoneDoesNotContactModel() {
        FakePhone phone = new FakePhone();
        phone.connected = false;
        RunOutcome result = new AgentRunner(phone).run("permission", "点击", config(base, false, 2), new RunControl(), event -> { });
        assertEquals("awaiting_user", result.status);
        assertTrue(requests.isEmpty());
    }

    private ModelConfig config(String url, boolean images, int steps) {
        return new ModelConfig(url, "unit-test-secret", "test-model", images, steps, 10);
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            InputStream input = new BufferedInputStream(connection.getInputStream());
            String requestLine = readLine(input);
            String path = requestLine.split(" ")[1];
            int contentLength = 0;
            String authorization = "";
            for (String line; !(line = readLine(input)).isEmpty();) {
                int separator = line.indexOf(':');
                String name = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if ("Content-Length".equalsIgnoreCase(name)) contentLength = Integer.parseInt(value);
                if ("Authorization".equalsIgnoreCase(name)) authorization = value;
            }
            if (contentLength < 0 || contentLength > 24 * 1024 * 1024) throw new IOException("Test request too large");
            String body = new String(input.readNBytes(contentLength), StandardCharsets.UTF_8);
            requests.add(new JSONObject(body));
            paths.add(path);
            authHeaders.add(authorization);
            firstRequest.countDown();
            Reply reply = replies.poll(2, TimeUnit.SECONDS);
            if (reply == null) reply = new Reply(500, "{}", 0);
            if (reply.delay > 0) Thread.sleep(reply.delay);
            byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = connection.getOutputStream();
            output.write(("HTTP/1.1 " + reply.status + " Test\r\nContent-Type: application/json\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            if(reply.partial){output.write(bytes,0,1);output.flush();reply.partialStarted.countDown();reply.releasePartial.await(2,TimeUnit.SECONDS);output.write(bytes,1,bytes.length-1);output.flush();return;}
            output.write(bytes);
            output.flush();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            // A cancellation deliberately closes the socket before the delayed response.
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII.name()).replace("\r", "");
            if (bytes.size() > 8192) throw new IOException("Test header too large");
            bytes.write(value);
        }
        throw new IOException("Unexpected end of request");
    }

    private static String answer(String text) throws Exception { return modelMessage(new JSONObject().put("content", text)); }
    private static String tool(String id, String name, String args) throws Exception {
        return modelMessage(new JSONObject().put("tool_calls", new JSONArray().put(call(id, name, args))));
    }
    private static JSONObject call(String id, String name, String args) throws Exception {
        return new JSONObject().put("id", id).put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args));
    }
    private static String modelMessage(JSONObject message) throws Exception {
        message.put("role", "assistant");
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", message).put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop"))).toString();
    }
    private static int countImages(JSONObject request) throws Exception {
        int count = 0;
        JSONArray messages = request.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONArray content = messages.getJSONObject(i).optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) if ("image_url".equals(content.getJSONObject(j).optString("type"))) count++;
        }
        return count;
    }

    private static final class Reply {
        final int status;
        final String body;
        final long delay;
        boolean partial;
        final CountDownLatch partialStarted=new CountDownLatch(1),releasePartial=new CountDownLatch(1);
        Reply(int status, String body, long delay) { this.status = status; this.body = body; this.delay = delay; }
    }

    private static final class FakePhone implements PhonePort {
        int observations;
        int clearCount;
        boolean withImage = true;
        boolean connected = true;
        boolean adaptiveMetadata;
        JSONObject receipt;
        Runnable onExecute;
        final List<String> executed = new ArrayList<>();
        @Override public boolean isConnected() { return connected; }
        @Override public JSONObject observe(boolean includeImage, RunControl control) throws Exception {
            control.check();
            JSONObject result = new JSONObject().put("ok", true).put("observationId", "obs-" + (++observations))
                    .put("packageName", "test.app").put("screenWidth", 1080).put("screenHeight", 1920)
                    .put("nodes", new JSONArray().put(new JSONObject().put("id", "node-1").put("text", "按钮").put("clickable", true)));
            if (includeImage && withImage) result.put("imageBase64", IMAGE).put("imageMimeType", "image/png").put("imageWidth", 1).put("imageHeight", 1);
            if(adaptiveMetadata){result.put("sessionId","fixture-service").put("displayId",0).put("observedAtUptimeMs",1000L).put("pauseRevision",control.pauseRevision());dev.pocketengine.phone.observation.ObservationReport.attach(result,true,"fixture_tree",includeImage&&withImage,"fixture_pixels");}
            return result;
        }
        @Override public JSONObject execute(String name, JSONObject args, RunControl control) throws Exception {
            control.check();
            executed.add(name);
            if(onExecute!=null)onExecute.run();
            return receipt == null ? new JSONObject().put("ok", true).put("message", "实际工具回执") : receipt;
        }
        @Override public void clearReferences() { clearCount++; }
    }
}
