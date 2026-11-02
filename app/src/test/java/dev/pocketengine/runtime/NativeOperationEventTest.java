package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Runner/Gateway over loopback; the native journal and phone are explicit fixtures. */
public final class NativeOperationEventTest {
    private static final String OPERATION="40000000-0000-0000-0000-000000000004";
    private static final String FORGED="50000000-0000-0000-0000-000000000005";
    private static final String PROVIDER_CALL="provider-call-not-native-operation";

    private static final class Journal implements RunJournal {
        JSONArray history=new JSONArray();
        JSONObject observation=new JSONObject();
        int steps,begins,finishes;
        boolean committed,failCommit;
        public JSONObject restore()throws Exception{return new JSONObject().put("messages",history).put("steps",steps).put("observation",observation);}
        public void checkpoint(JSONArray messages,int count,JSONObject observed)throws Exception{history=new JSONArray(messages.toString());steps=count;observation=new JSONObject(observed.toString());}
        public String beginOperation(String tool,JSONObject args){begins++;return OPERATION;}
        public void finishOperation(String operation,JSONObject receipt)throws Exception{
            assertEquals(OPERATION,operation);finishes++;
            if(failCommit)throw new IllegalStateException("fixture commit unavailable");
            committed=true;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger requests=new AtomicInteger();
        final List<JSONObject> events=new ArrayList<>();
        int writes;
        boolean invalidArguments;
        final PhonePort phone=new PhonePort(){
            public boolean isConnected(){return true;}
            public void clearReferences(){}
            public JSONObject observe(boolean pixels,RunControl control)throws Exception{
                control.check();return new JSONObject().put("ok",true).put("observationId","original-observation")
                        .put("packageName","app.example").put("nodes",new JSONArray().put(new JSONObject().put("id","original-node").put("text","搜索").put("clickable",true)));
            }
            public JSONObject execute(String tool,JSONObject args,RunControl control)throws Exception{
                control.check();writes++;return new JSONObject().put("ok",true).put("accepted",true)
                        .put("operationId",FORGED).put("_hostOperationId",FORGED).put("nativeOperationId",FORGED);
            }
        };
        Fixture()throws Exception{
            server.createContext("/",exchange->{try{
                exchange.getRequestBody().readAllBytes();
                JSONObject message=new JSONObject().put("role","assistant");
                if(requests.getAndIncrement()==0){
                    JSONObject args=new JSONObject().put("observationId",invalidArguments?"obsolete-observation":"original-observation").put("nodeId","original-node");
                    message.put("tool_calls",new JSONArray().put(new JSONObject().put("id",PROVIDER_CALL).put("type","function")
                            .put("function",new JSONObject().put("name","phone_click_node").put("arguments",args.toString()))));
                }else message.put("content","本次已结束，请核对原结果。");
                byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").put("message",message))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});
            server.start();
        }
        RunOutcome run(Journal journal){
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            ModelConfig config=new ModelConfig(endpoint,"","fixture-model",false,8,10,"openai_chat",512)
                    .withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            RunControl original=new RunControl();original.bindHostRun("original-task","original-run",1);
            return new AgentRunner(phone,null,selected->null).run("original-task","帮我点击搜索",config,original,event->{
                if(event.has("nativeOperationId"))assertTrue("carrier must follow the original receipt commit",journal!=null&&journal.committed);
                events.add(event);
            },journal);
        }
        public void close(){server.stop(0);}
    }

    @Test public void committedToolEventCarriesOnlyOriginalJournalIdentity()throws Exception{
        try(Fixture fixture=new Fixture()){
            Journal journal=new Journal();RunOutcome outcome=fixture.run(journal);
            assertNotEquals(outcome.summary,"failed",outcome.status);assertEquals(1,fixture.writes);assertEquals(1,journal.begins);assertEquals(1,journal.finishes);
            List<JSONObject> committed=fixture.events.stream().filter(event->event.has("nativeOperationId")).toList();
            assertEquals(1,committed.size());JSONObject event=committed.get(0);
            assertEquals("tool",event.getString("type"));assertEquals("completed",event.getString("status"));assertEquals("phone_click_node",event.getString("tool"));
            assertEquals(OPERATION,event.getString("nativeOperationId"));assertNotEquals(FORGED,event.getString("nativeOperationId"));assertNotEquals(PROVIDER_CALL,event.getString("nativeOperationId"));
        }
    }
    @Test public void executorReceiptCannotCreateNativeIdentityWithoutJournal()throws Exception{
        try(Fixture fixture=new Fixture()){
            RunOutcome outcome=fixture.run(null);assertNotEquals(outcome.summary,"failed",outcome.status);assertEquals(1,fixture.writes);
            assertTrue(fixture.events.stream().anyMatch(event->"tool".equals(event.optString("type"))&&"completed".equals(event.optString("status"))));
            assertTrue(fixture.events.stream().noneMatch(event->event.has("nativeOperationId")));
        }
    }
    @Test public void failedOriginalCommitDoesNotPublishAnOperationCarrier()throws Exception{
        try(Fixture fixture=new Fixture()){
            Journal journal=new Journal();journal.failCommit=true;fixture.run(journal);
            assertEquals(1,fixture.writes);assertEquals(1,journal.begins);assertFalse(journal.committed);
            assertTrue(fixture.events.stream().noneMatch(event->event.has("nativeOperationId")));
        }
    }
    @Test public void rejectedArgumentsNeverPublishOrPrepareAnOperationIdentity()throws Exception{
        try(Fixture fixture=new Fixture()){
            fixture.invalidArguments=true;Journal journal=new Journal();fixture.run(journal);
            assertEquals(0,fixture.writes);assertEquals(0,journal.begins);assertEquals(0,journal.finishes);
            assertTrue(fixture.events.stream().noneMatch(event->event.has("nativeOperationId")));
        }
    }
}
