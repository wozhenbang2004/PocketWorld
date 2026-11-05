package dev.pocketengine.features.proxy;

import dev.pocketengine.features.proxy.accounts.Credentials;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.FakeChatGpt;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real native parser and localhost transport; no real account or paid inference. */
public final class LoopbackResponsesUsageTest {
    @Test public void nativeProviderUsageDetailsCompleteBothResponseModes()throws Exception {
        for(boolean streaming:new boolean[]{false,true})try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            JSONObject item=ResponsesFixtures.message("m","ok",false,true);
            fake.stream=new ResponsesFixtures().item(0,ResponsesFixtures.message("m","",false,false),false)
                    .text(0,"m","ok",false).item(0,item,true).complete(new JSONArray().put(item)).toString()
                    .replace("\"total_tokens\":10","\"total_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":3},\"output_tokens_details\":{\"reasoning_tokens\":2}");
            assertTrue(fake.stream.contains("cached_tokens"));Recorder out=new Recorder();
            LoopbackResponses protocol=new LoopbackResponses("fixture-model",streaming,out);
            Credentials credentials=new Credentials("fixture-access","fixture-refresh","fixture-id",ChatGptProvider.SCOPES,System.currentTimeMillis()+3600000);
            provider.streamResponses(credentials,ResponsesFixtures.request(),new GatewayCall(5000),protocol::accept);
            protocol.requireCompleted();assertEquals(1,fake.responses.get());
            JSONObject usage=out.result(streaming).getJSONObject("usage");
            assertEquals(10,usage.getLong("total_tokens"));assertEquals(3,usage.getJSONObject("input_tokens_details").getLong("cached_tokens"));
            assertEquals(2,usage.getJSONObject("output_tokens_details").getLong("reasoning_tokens"));
        }
    }
    @Test public void partialUsageDoesNotInventMissingCountersOrDetails()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",false,out);
        protocol.accept(terminal(new JSONObject().put("input_tokens_details",new JSONObject().put("cached_tokens",0))));
        JSONObject usage=out.complete.getJSONObject("usage");assertEquals(1,usage.length());
        assertEquals(0,usage.getJSONObject("input_tokens_details").getLong("cached_tokens"));
        assertFalse(usage.has("input_tokens"));assertFalse(usage.has("output_tokens_details"));
    }
    @Test public void absentDetailsStayAbsent()throws Exception {
        Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",false,out);
        protocol.accept(terminal(new JSONObject().put("total_tokens",10)));
        assertEquals(1,out.complete.getJSONObject("usage").length());
    }
    @Test public void malformedOrUnknownDetailFieldsCannotProduceCompletion()throws Exception {
        for(Object value:new Object[]{-1,1.5,"3",JSONObject.NULL,new JSONArray()})reject(new JSONObject().put("input_tokens_details",new JSONObject().put("cached_tokens",value)));
        for(Object detail:new Object[]{JSONObject.NULL,"bad",new JSONArray()})reject(new JSONObject().put("output_tokens_details",detail));
        reject(new JSONObject().put("output_tokens_details",new JSONObject().put("reasoning_tokens",2).put("private_field","do not forward")));
    }
    private void reject(JSONObject usage)throws Exception {
        for(boolean streaming:new boolean[]{false,true}){
            Recorder out=new Recorder();LoopbackResponses protocol=new LoopbackResponses("fixture-model",streaming,out);
            assertThrows(GatewayFailure.class,()->protocol.accept(terminal(usage)));
            assertFalse(protocol.isCompleted());assertNull(out.complete);
            assertFalse(out.events.stream().anyMatch(e->"response.completed".equals(e.optString("type"))));
        }
    }
    private JSONObject terminal(JSONObject usage)throws Exception {
        return new JSONObject().put("type","completed").put("response_id","response").put("output",new JSONArray().put(ResponsesFixtures.message("m","ok",false,true))).put("usage",usage);
    }
    private static final class Recorder implements LoopbackResponses.Output {
        final List<JSONObject> events=new ArrayList<>();JSONObject complete;
        public void event(JSONObject value){events.add(value);}public void complete(JSONObject value){complete=value;}
        JSONObject result(boolean streaming)throws Exception {
            if(!streaming)return complete;
            JSONObject last=events.get(events.size()-1);assertEquals("response.completed",last.getString("type"));return last.getJSONObject("response");
        }
    }
}
