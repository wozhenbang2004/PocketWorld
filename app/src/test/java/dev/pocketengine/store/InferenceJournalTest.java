package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.ProviderUsage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class InferenceJournalTest {
    private static final class Fixture {
        final TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());final String id,run;final int epoch;
        final RunControl control=new RunControl();final RunJournal journal;
        Fixture()throws Exception{JSONObject task=store.startAttempt(store.createCapabilityTask("original task","conversation").getString("id"),"capabilities",12);
            id=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);journal=store.journal(id,run,epoch,control);}
        InferenceCapture capture(InferenceReceipt.Purpose purpose,String member)throws Exception{
            return new InferenceCapture(new ModelConfig("https://fixture.example","fixture-secret","selected-model",false,4,10),control,journal.inferenceTarget(purpose,member));
        }
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http;final AtomicInteger requests=new AtomicInteger();
        Server()throws Exception{http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{requests.incrementAndGet();try{
            exchange.getRequestBody().readAllBytes();String body="{\"model\":\"served-model\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"real loopback answer\"}}],\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":4,\"total_tokens\":35}}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});http.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort(),"fixture-secret","selected-model",false,4,10);}
        public void close(){http.stop(0);}
    }
    private static JSONObject reported(long input)throws Exception{return new JSONObject().put("usage",new JSONObject().put("prompt_tokens",input).put("completion_tokens",2));}
    @Test public void originalRunnerAndRealHttpWriteOneReceiptWithoutPuttingUsageIntoHistory()throws Exception{
        Fixture f=new Fixture();try(Server server=new Server()){
            CapabilityPort tools=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}
                public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("no tool was offered");}};
            RunOutcome outcome=new AgentRunner(null,tools).run(f.id,"original task",server.config(),f.control,event->{try{f.store.event(f.id,f.run,f.epoch,event);}catch(Exception failure){throw new RuntimeException(failure);}},f.journal);
            assertNotEquals(outcome.summary,"failed",outcome.status);assertEquals(1,server.requests.get());JSONObject projection=f.store.inferenceUsage(f.id);
            assertEquals(1,projection.getInt("attempts"));assertEquals(1,projection.getInt("completed"));
            JSONObject receipt=projection.getJSONArray("receipts").getJSONObject(0);
            assertEquals(f.id,receipt.getJSONObject("context").getString("taskId"));assertEquals(f.run,receipt.getJSONObject("context").getString("runId"));
            assertEquals(f.epoch,receipt.getJSONObject("context").getInt("epoch"));assertEquals("MAIN",receipt.getJSONObject("context").getString("purpose"));
            assertEquals("served-model",receipt.getJSONObject("route").getString("responseModel"));
            assertEquals(31,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));
            String history=f.journal.restore().getJSONArray("messages").toString();assertTrue(history.contains("real loopback answer"));assertFalse(history.contains("inference_receipt"));assertFalse(history.contains("prompt_tokens"));
            assertEquals(0,f.store.get(f.id,false).getJSONArray("operations").length());
        }
    }
    @Test public void lateUsageAfterCancelAndNewEpochCannotChangeTheNewRunOrGrant()throws Exception{
        Fixture f=new Fixture();InferenceCapture old=f.capture(InferenceReceipt.Purpose.MAIN,"");old.dispatch();
        f.store.requestCancel(f.id,f.run);f.control.cancel();f.store.endAttempt(f.id,f.run,"cancelled","owner stopped",new JSONObject());
        JSONObject next=f.store.startAttempt(f.id,"capabilities",12);String newer=next.getString("runId");
        old.usage.observe(reported(44),ProviderUsage.Stage.FINAL);old.finish(InferenceReceipt.Outcome.CANCELLED);
        JSONObject current=f.store.get(f.id,false);assertEquals(newer,current.getString("runId"));assertEquals(next.getInt("coordinatorEpoch"),current.getInt("coordinatorEpoch"));assertEquals("preparing",current.getString("status"));
        JSONObject projection=f.store.inferenceUsage(f.id);assertEquals(1,projection.getInt("attempts"));assertEquals(1,projection.getInt("cancelled"));
        assertEquals(f.run,projection.getJSONArray("receipts").getJSONObject(0).getJSONObject("context").getString("runId"));
        assertEquals(0,current.getJSONArray("operations").length());
    }
    @Test public void restartPreservesUnknownPendingAccountingWithoutInventingZeroOrReplaying()throws Exception{
        Fixture f=new Fixture();InferenceCapture capture=f.capture(InferenceReceipt.Purpose.MAIN,"");capture.dispatch();
        TaskStore restarted=new TaskStore(RuntimeEnvironment.getApplication());JSONObject before=restarted.get(f.id,false),projection=restarted.inferenceUsage(f.id);
        assertEquals(1,projection.getInt("attempts"));assertEquals(1,projection.getInt("unfinished"));
        JSONObject input=projection.getJSONArray("groups").getJSONObject(0).getJSONObject("counters").getJSONObject("INPUT");
        assertTrue(input.isNull("knownSubtotal"));assertEquals(1,input.getInt("unknownCalls"));
        restarted.inferenceUsage(f.id);assertEquals(before.toString(),restarted.get(f.id,false).toString());
    }
    @Test public void pausedOriginalRunCannotRegisterANewModelRequest()throws Exception{
        Fixture f=new Fixture();InferenceCapture capture=f.capture(InferenceReceipt.Purpose.MAIN,"");f.control.pause();
        assertThrows(Exception.class,capture::dispatch);assertEquals(0,f.store.inferenceUsage(f.id).getInt("attempts"));
    }
    @Test public void nativeSummaryAndMemberPurposesHaveSeparateOriginalTaskAttribution()throws Exception{
        Fixture f=new Fixture();InferenceCapture member=f.capture(InferenceReceipt.Purpose.MEMBER,"member-a");member.dispatch();member.usage.observe(reported(10),ProviderUsage.Stage.FINAL);member.finish(InferenceReceipt.Outcome.COMPLETED);
        InferenceCapture summary=f.capture(InferenceReceipt.Purpose.SUMMARY,"");summary.dispatch();summary.usage.observe(reported(20),ProviderUsage.Stage.FINAL);summary.finish(InferenceReceipt.Outcome.COMPLETED);
        JSONObject projection=f.store.inferenceUsage(f.id);assertEquals(2,projection.getInt("attempts"));assertEquals(2,projection.getJSONArray("groups").length());
        JSONArray receipts=projection.getJSONArray("receipts");assertEquals("member-a",receipts.getJSONObject(0).getJSONObject("context").getString("memberId"));
        assertEquals("SUMMARY",receipts.getJSONObject(1).getJSONObject("context").getString("purpose"));
    }
    @Test public void genericModuleEventsCannotForgeNativeUsage()throws Exception{
        Fixture f=new Fixture();String status=f.store.get(f.id,false).getString("status");
        assertThrows(SecurityException.class,()->f.store.event(f.id,f.run,f.epoch,new JSONObject().put("type","inference_receipt").put("status","completed")));
        assertEquals(0,f.store.inferenceUsage(f.id).getInt("attempts"));assertEquals(status,f.store.get(f.id,false).getString("status"));
    }
    @Test public void unavailableOriginalDatabasePreventsTheNetworkAttempt()throws Exception{
        Fixture f=new Fixture();InferenceCapture.Target target=f.journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,"");
        java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(f.store)).close();
        try(Server server=new Server()){
            assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.completeStreaming(server.config(),new JSONArray().put(new JSONObject().put("role","user").put("content","fixture")),null,f.control,event->{},target));
            assertEquals(0,server.requests.get());
        }
    }
}
