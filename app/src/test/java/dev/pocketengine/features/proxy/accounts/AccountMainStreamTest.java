package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real existing OAuth/Responses loopback fixture and native AccountGateway; no real account. */
public final class AccountMainStreamTest {
    @After public void unbind(){ModelGateway.installAccountInferencePort(null);}
    private ModelConfig route(AccountGatewayTest.Rig rig,String id)throws Exception{
        JSONObject ref=new JSONObject().put("provider","chatgpt_plan").put("accountId",id).put("accountVersion",rig.book.row(id).getLong("version")).put("modelId","fixture-model");
        return new ModelConfig("https://unused.example/v1","never-forward-api-key","unused",false,4,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(ref));
    }
    private void bind(AccountGatewayTest.Rig rig){ModelGateway.installAccountInferencePort((ref,request,operation,sink)->rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,request,operation,sink));}
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","Reply once"));}
    private String textStream()throws Exception{
        JSONObject completed=ResponsesFixtures.message("message1","真实分片",false,true);
        return new ResponsesFixtures().item(0,ResponsesFixtures.message("message1","",false,false),false).text(0,"message1","真实分片",false).item(0,completed,true).complete(new JSONArray().put(completed)).toString();
    }
    @Test public void nativeAccountTextArrivesOnOriginalPresentationChannelBeforeVerifiedReturn()throws Exception{
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);rig.fake.stream=textStream();RunControl original=new RunControl();original.bindHostRun("task","run",1);List<ModelGateway.StreamEvent> events=new ArrayList<>();
            JSONObject assistant=ModelGateway.completeStreaming(route(rig,id),messages(),null,original,event->{events.add(event);if(event.kind==ModelGateway.StreamKind.TEXT_DELTA)assertTrue(events.stream().noneMatch(e->e.kind==ModelGateway.StreamKind.COMPLETED));});
            assertEquals("真实分片",assistant.getString("content"));assertTrue(events.stream().anyMatch(e->e.kind==ModelGateway.StreamKind.TEXT_DELTA));assertEquals(ModelGateway.StreamKind.COMPLETED,events.get(events.size()-1).kind);
            assertEquals(1,rig.fake.responses.get());assertFalse(rig.fake.lastRequest.has("max_output_tokens"));assertFalse(rig.fake.lastRequest.toString().contains("never-forward-api-key"));assertTrue(original.matchesHostRun("task","run",1));
        }
    }
    @Test public void originalRunCancellationDuringAccountDeltaCannotReturnACompletedTurn()throws Exception{
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);rig.fake.stream=textStream();RunControl original=new RunControl();List<ModelGateway.StreamEvent> events=new ArrayList<>();
            try{ModelGateway.completeStreaming(route(rig,id),messages(),null,original,event->{events.add(event);if(event.kind==ModelGateway.StreamKind.TEXT_DELTA)original.cancel();});fail();}catch(InterruptedException expected){}
            assertEquals(ModelGateway.StreamKind.CANCELLED,events.get(events.size()-1).kind);assertFalse(events.stream().anyMatch(e->e.kind==ModelGateway.StreamKind.COMPLETED));assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void disablingExactAccountCancelsOnlyItsRequestWithoutSwitchingOrCancellingTaskAuthority()throws Exception{
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();ModelConfig selected=route(rig,id);String other=rig.connect();rig.gateway.select(other);bind(rig);rig.fake.stream=textStream();RunControl original=new RunControl();original.bindHostRun("task","run",2);List<ModelGateway.StreamEvent> events=new ArrayList<>();
            try{ModelGateway.completeStreaming(selected,messages(),null,original,event->{events.add(event);if(event.kind==ModelGateway.StreamKind.TEXT_DELTA)rig.gateway.setEnabled(id,false);});fail();}catch(java.util.concurrent.CancellationException expected){}
            assertFalse(original.isCancelled());assertTrue(original.matchesHostRun("task","run",2));assertEquals(other,rig.book.selected());assertEquals(1,rig.fake.responses.get());assertEquals(ModelGateway.StreamKind.CANCELLED,events.get(events.size()-1).kind);
        }
    }
    @Test public void nativeAccountRetainsNestedUsageAndExactAuthorizedVersion()throws Exception{
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);String stream=textStream();assertTrue(stream.contains("\"total_tokens\":10"));
            rig.fake.stream=stream.replace("\"total_tokens\":10","\"total_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":3},\"output_tokens_details\":{\"reasoning_tokens\":2}");
            List<InferenceReceipt> captured=new ArrayList<>();RunControl original=new RunControl();original.bindHostRun("task","run",1);
            InferenceCapture.Target target=new InferenceCapture.Target(new InferenceReceipt.Context("task","run",1,InferenceReceipt.Purpose.MAIN,""),new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){captured.add(receipt);}});
            ModelGateway.completeStreaming(route(rig,id),messages(),null,original,event->{},target);
            assertEquals(1,captured.size());JSONObject receipt=captured.get(0).toJson(),counts=receipt.getJSONObject("usage").getJSONObject("counters");
            assertEquals(3,counts.getJSONObject("CACHE_READ").getInt("value"));assertEquals(2,counts.getJSONObject("REASONING").getInt("value"));
            assertEquals(rig.book.row(id).getLong("version"),receipt.getJSONObject("route").getLong("accountVersion"));
            assertEquals(id,receipt.getJSONObject("route").getString("accountId"));assertEquals(1,rig.fake.responses.get());
            assertEquals("provider_text_delta",receipt.getString("timingEvidence"));assertTrue(receipt.getLong("firstVisibleTextAt")>=receipt.getLong("startedAt"));
            assertTrue(receipt.getLong("firstVisibleTextElapsedNanos")<=receipt.getLong("elapsedNanos"));
        }
    }
    @Test public void nativeAccountProviderDeltaHasTimingEvenWithoutAPresentationSink()throws Exception{
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);rig.fake.stream=textStream();List<InferenceReceipt> captured=new ArrayList<>();
            InferenceCapture.Target target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){captured.add(receipt);}});
            assertEquals("真实分片",ModelGateway.complete(route(rig,id),messages(),null,new RunControl(),target).getString("content"));
            assertEquals(1,captured.size());assertEquals("provider_text_delta",captured.get(0).timingEvidence);
            assertNotNull(captured.get(0).firstVisibleTextAt);assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void incompleteNativeAccountReportsUsageThroughTheNumericSideChannel()throws Exception{
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);rig.fake.stream=new ResponsesFixtures().add(new JSONObject().put("type","response.incomplete")
                    .put("response",new JSONObject().put("id","resp_fixture").put("status","incomplete")
                    .put("usage",new JSONObject().put("input_tokens",7).put("output_tokens",8)))).toString();
            List<InferenceReceipt> captured=new ArrayList<>();InferenceCapture.Target target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){captured.add(receipt);}});
            try{ModelGateway.completeStreaming(route(rig,id),messages(),null,new RunControl(),event->{},target);fail();}catch(ModelGateway.GatewayException expected){}
            assertEquals(1,captured.size());assertEquals(InferenceReceipt.Outcome.FAILED,captured.get(0).outcome);
            assertEquals(7,captured.get(0).toJson().getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));
            assertEquals(1,rig.fake.responses.get());
        }
    }
}
