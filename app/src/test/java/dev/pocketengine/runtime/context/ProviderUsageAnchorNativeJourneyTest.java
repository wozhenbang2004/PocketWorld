package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
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

/** Actual HTTP gateway/collector/original ledger/charge path, not provider billing acceptance. */
public final class ProviderUsageAnchorNativeJourneyTest {
    private static final class Fixture implements AutoCloseable,InferenceCapture.Sink {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger requests=new AtomicInteger();final List<InferenceReceipt> finished=new ArrayList<>();
        final RunControl original=new RunControl();final JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","original native policy and current clock")).put(new JSONObject().put("role","user").put("content","original owner text"));
        final ModelConfig config;final RunContextBudget budget;JSONObject sent;boolean incomplete,differentModel;int started,admissions;
        Fixture()throws Exception{
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            config=new ModelConfig(endpoint,"private-fixture-key","fixture-model",false,8,10,"openai_chat",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));
            budget=new RunContextBudget(selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(100000,512,128,100000,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1));
            original.bindHostRun("original-task","original-run",1);
            server.createContext("/",exchange->{try{
                sent=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.incrementAndGet();
                JSONObject report=new JSONObject().put("model",differentModel?"actual-other-model":"fixture-model").put("usage",new JSONObject().put("prompt_tokens",123).put("completion_tokens",5).put("total_tokens",128));
                String body;
                if(incomplete){report.put("id","original-provider-stream").put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("finish_reason",JSONObject.NULL).put("delta",new JSONObject().put("content","unfinished provider text"))));body="data: "+report+"\n\n";exchange.getResponseHeaders().add("Content-Type","text/event-stream");}
                else{report.put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","actual fixture answer"))));body=report.toString();exchange.getResponseHeaders().add("Content-Type","application/json");}
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        }
        public void started(InferenceReceipt receipt){started++;}
        public void finished(InferenceReceipt receipt){finished.add(receipt);}
        JSONObject complete()throws Exception{
            InferenceCapture.Target target=new InferenceCapture.Target(new InferenceReceipt.Context("original-task","original-run",1,InferenceReceipt.Purpose.MAIN,""),this,()->{assertTrue(original.matchesHostRun("original-task","original-run",1));original.check();admissions++;});
            return ModelGateway.completeStreaming(config,messages,null,original,event->{},budget.inferenceTarget(target,messages,null,config));
        }
        public void close(){server.stop(0);}
    }
    @Test public void finalActualHttpInputCalibratesTheNextUnchangedPrefixWithoutAnotherRequest()throws Exception{
        try(Fixture fixture=new Fixture()){
            String before=fixture.messages.toString();assertEquals("actual fixture answer",fixture.complete().getString("content"));JSONObject diagnostics=fixture.budget.request(new JSONArray(before).put(new JSONObject().put("role","assistant").put("content","actual fixture answer")),null,fixture.config).diagnostics();
            assertTrue(diagnostics.getBoolean("providerUsageUsed"));assertEquals(123,diagnostics.getLong("providerReportedInputTokens"));assertEquals(1,fixture.requests.get());assertEquals(1,fixture.started);assertEquals(1,fixture.finished.size());assertEquals(InferenceReceipt.Outcome.COMPLETED,fixture.finished.get(0).outcome);assertTrue(fixture.admissions>=2);assertEquals(before,fixture.messages.toString());assertEquals(before,fixture.sent.getJSONArray("messages").toString());assertFalse(diagnostics.toString().contains("private-fixture-key"));
        }
    }
    @Test public void incompleteActualHttpUsageIsPreservedInOriginalFailureButCannotSeedBudget()throws Exception{
        try(Fixture fixture=new Fixture()){
            fixture.incomplete=true;try{fixture.complete();fail();}catch(ModelGateway.GatewayException expected){}
            assertEquals(1,fixture.requests.get());assertEquals(1,fixture.finished.size());InferenceReceipt receipt=fixture.finished.get(0);assertEquals(InferenceReceipt.Outcome.FAILED,receipt.outcome);assertEquals(123,receipt.toJson().getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));assertFalse(fixture.budget.request(fixture.messages,null,fixture.config).diagnostics().getBoolean("providerUsageUsed"));
        }
    }
    @Test public void actualAliasModelDoesNotSeedTheRequestedModelsAnchor()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.differentModel=true;fixture.complete();assertEquals(1,fixture.requests.get());assertEquals(1,fixture.finished.size());assertEquals(InferenceReceipt.Outcome.COMPLETED,fixture.finished.get(0).outcome);assertFalse(fixture.budget.request(fixture.messages,null,fixture.config).diagnostics().getBoolean("providerUsageUsed"));}
    }
    @Test public void aFreshNativeClockOrSourceReferenceForcesFallbackWithoutDeletingItsBody()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.complete();fixture.messages.getJSONObject(0).put("content","fresh native clock and changed original source revision");String changed=fixture.messages.toString();JSONObject diagnostics=fixture.budget.request(fixture.messages,null,fixture.config).diagnostics();assertFalse(diagnostics.getBoolean("providerUsageUsed"));assertEquals(changed,fixture.messages.toString());assertTrue(changed.contains("fresh native clock"));assertEquals(1,fixture.requests.get());}
    }
}
