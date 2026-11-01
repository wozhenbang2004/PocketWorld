package dev.pocketengine.runtime.context;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual one-shot ModelGateway HTTP + real receipt capture, against a synthetic provider only. */
public final class ContextCompactorTest {
    private static final class Provider implements AutoCloseable {
        final HttpServer server;final AtomicInteger calls=new AtomicInteger();volatile JSONObject request;int status=200;Runnable during=()->{};
        String replyText="真实历史：source-0.java 出现 ERR_IMPORT_0；仅是低信任摘要，原件仍保留。";
        Provider()throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{
                try{calls.incrementAndGet();request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));during.run();
                    byte[] body=(status==200?new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content",replyText))))
                            .put("usage",new JSONObject().put("prompt_tokens",20).put("completion_tokens",8).put("total_tokens",28)).toString():"{\"error\":{\"message\":\"not a context error\"}}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
                }catch(Exception failure){/* Client cancellation is expected in one test. */}finally{exchange.close();}
            });server.start();
        }
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-key","fixture",false,3,10,"openai_chat",1000);}
        public void close(){server.stop(0);}
    }
    private static final class Journal implements RunJournal,CompactionJournal {
        final ModelConfig config;final RunControl control=new RunControl();final List<InferenceReceipt> receipts=new ArrayList<>();final AtomicInteger inputs=new AtomicInteger(),barrier=new AtomicInteger();
        JSONArray raw;JSONObject published;int publishes;
        Journal(ModelConfig config)throws Exception{this.config=config;raw=CompactionPlanTest.history();control.bindHostRun("task","run",1);}
        ContextBudgetPolicy.Source budgets(){return selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,CompactionPlanTest.budget(),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);}
        JSONObject state(CompactionPlan plan)throws Exception{return new JSONObject().put("taskId","task").put("runId","run").put("epoch",1).put("pauseRevision",control.pauseRevision()).put("sourceBarrier",barrier.get())
                .put("inputsFingerprint",Integer.toString(inputs.get())).put("approvalsFingerprint","none").put("operationsFingerprint","settled")
                .put("routeFingerprint",CompactionPlan.routeFingerprint(config)).put("rawFingerprint",CompactionPlan.fingerprint(raw)).put("tailFingerprint",CompactionPlan.fingerprint(CompactionPlan.slice(raw,plan.endExclusive,raw.length())));}
        public FrozenPrefix freeze(CompactionPlan plan,RunControl original)throws Exception {
            JSONArray sources=new JSONArray();for(int index:plan.coveredIndexes()){int chars=raw.getJSONObject(index).toString(2).length();sources.put(new JSONObject().put("sourceId","task:task:run:run:checkpoint-message:"+index)
                    .put("revision","sha256:"+CompactionPlan.fingerprint(raw.getJSONObject(index))).put("revisionKind","sha256_content_fingerprint")
                    .put("readRange",new JSONObject().put("offset",0).put("chars",chars).put("totalChars",chars).put("complete",true).put("hasMore",false)));}
            return FrozenPrefix.fromHost(plan,state(plan),sources,original);
        }
        public PublishReceipt publish(FrozenPrefix expected,SummaryDraft actual,RunControl original)throws Exception {
            if(!expected.samePublicationState(state(expected.plan)))return new PublishReceipt("stale","");published=actual.toJson();publishes++;return new PublishReceipt("published","projection");
        }
        public boolean current(FrozenPrefix prefix,RunControl original)throws Exception{return barrier.get()==prefix.sourceBarrier&&published!=null&&CompactionCheckpoint.sourcesCurrent(published,raw);}
        public boolean scopeCurrent(RunControl original){return original==control&&!control.isPaused()&&barrier.get()==0;}
        public JSONObject restore()throws Exception{return new JSONObject().put("messages",raw);}
        public void checkpoint(JSONArray messages,int steps,JSONObject observation){raw=messages;}
        public String beginOperation(String tool,JSONObject args){throw new AssertionError("summary never executes a tool");}
        public void finishOperation(String id,JSONObject receipt){throw new AssertionError("summary never manufactures a tool receipt");}
        public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String member){return new InferenceCapture.Target(new InferenceReceipt.Context("task","run",1,purpose,""),new InferenceCapture.Sink(){public void started(InferenceReceipt r){receipts.add(r);}public void finished(InferenceReceipt r){receipts.add(r);}});}
    }
    private static JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","read_file").put("description","s".repeat(30000)).put("parameters",new JSONObject().put("type","object"))));}
    private static RunContextBudget.Projection request(ContextCompactor compactor,Journal journal)throws Exception{return compactor.request(journal.raw,new JSONArray(journal.raw.toString()),CompactionPlanTest.indexes(journal.raw),tools(),journal.config,journal.control,new RunContextBudget(journal.budgets()));}
    @Test public void oneActualSummaryCallKeepsRawAndAttributesUsageThenReusesOnAppend()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());String original=journal.raw.toString();ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());RunContextBudget.Projection result=request(compactor,journal);
            assertEquals(1,provider.calls.get());assertEquals(1,journal.publishes);assertEquals(original,journal.raw.toString());assertTrue(result.diagnostics().getBoolean("summariesGenerated"));assertTrue(result.plan().messages().toString().contains("ERR_IMPORT_0"));
            assertEquals(2,journal.receipts.size());assertEquals(InferenceReceipt.Purpose.SUMMARY,journal.receipts.get(0).context.purpose);assertEquals(InferenceReceipt.Outcome.STARTED,journal.receipts.get(0).outcome);assertEquals(InferenceReceipt.Outcome.COMPLETED,journal.receipts.get(1).outcome);
            assertEquals(journal.receipts.get(0).callId,journal.receipts.get(1).callId);assertEquals(journal.receipts.get(1).callId,journal.published.getString("callId"));assertTrue(journal.published.getJSONObject("inferenceReceipt").getJSONObject("usage").toString().contains("28"));
            assertTrue(provider.request.getJSONArray("messages").toString().contains("settled_history_data"));assertFalse(provider.request.has("tools"));
            journal.raw.put(CompactionPlanTest.message("assistant","new settled tail"));assertFalse(request(compactor,journal).diagnostics().getBoolean("summariesGenerated"));assertEquals(1,provider.calls.get());
            journal.barrier.incrementAndGet();assertEquals("published_summary_source_changed",assertThrows(CompactionPlan.Unavailable.class,()->request(compactor,journal)).code);assertEquals(1,provider.calls.get());
        }
    }
    @Test public void inputCorrectionDuringNetworkRejectsCandidateWithoutRetryOrPublishing()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());provider.during=journal.inputs::incrementAndGet;ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());String original=journal.raw.toString();
            assertEquals("summary_publish_stale",assertThrows(CompactionPlan.Unavailable.class,()->request(compactor,journal)).code);assertEquals(1,provider.calls.get());assertEquals(0,journal.publishes);assertNull(journal.published);assertEquals(original,journal.raw.toString());assertEquals(InferenceReceipt.Outcome.COMPLETED,journal.receipts.get(1).outcome);
        }
    }
    @Test public void nonContext400IsNotRetriedAndItsFailedUsageRemainsOriginal()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());provider.status=400;ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());assertThrows(Exception.class,()->request(compactor,journal));
            assertEquals(1,provider.calls.get());assertEquals(0,journal.publishes);assertNull(journal.published);assertEquals(2,journal.receipts.size());assertEquals(InferenceReceipt.Outcome.FAILED,journal.receipts.get(1).outcome);
        }
    }
    @Test public void cancellationAfterDispatchCannotPublishOrPretendNoUsage()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());provider.during=journal.control::cancel;ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());assertThrows(InterruptedException.class,()->request(compactor,journal));
            assertEquals(1,provider.calls.get());assertEquals(0,journal.publishes);assertEquals(InferenceReceipt.Outcome.CANCELLED,journal.receipts.get(1).outcome);assertEquals(InferenceReceipt.Purpose.SUMMARY,journal.receipts.get(1).context.purpose);
        }
    }
    @Test public void nativeResumeDiscardsOldSummaryAndStartsFromRawWithoutReusingOldPause()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());request(compactor,journal);assertEquals(1,provider.calls.get());
            journal.control.pause();journal.control.resume();request(compactor,journal);assertEquals(2,provider.calls.get());assertEquals(2,journal.publishes);
            assertEquals(journal.control.pauseRevision(),journal.published.getJSONObject("guard").getLong("pauseRevision"));
        }
    }
    @Test public void missingOriginalSummaryAttributionCannotStartBillableHttp()throws Exception {
        try(Provider provider=new Provider()){
            Journal host=new Journal(provider.config());RunJournal missing=new RunJournal(){
                public JSONObject restore()throws Exception{return host.restore();}
                public void checkpoint(JSONArray messages,int steps,JSONObject observation){host.checkpoint(messages,steps,observation);}
                public String beginOperation(String tool,JSONObject args){throw new AssertionError();}
                public void finishOperation(String id,JSONObject receipt){throw new AssertionError();}
            };
            ContextCompactor compactor=new ContextCompactor(missing,host,host.budgets());
            assertEquals("summary_attribution_unavailable",assertThrows(CompactionPlan.Unavailable.class,()->request(compactor,host)).code);
            assertEquals(0,provider.calls.get());assertEquals(0,host.publishes);assertTrue(host.receipts.isEmpty());
        }
    }
    @Test public void providerCredentialEchoCannotEnterTheStoredOrNextRequestSummary()throws Exception {
        try(Provider provider=new Provider()){
            provider.replyText="original failure ERR_IMPORT_0, echoed fixture-key must not persist";Journal journal=new Journal(provider.config());ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());
            RunContextBudget.Projection result=request(compactor,journal);assertEquals(1,provider.calls.get());assertTrue(journal.published.getBoolean("hostSecretRedacted"));
            assertFalse(journal.published.toString().contains("fixture-key"));assertFalse(result.plan().messages().toString().contains("fixture-key"));assertTrue(journal.published.getString("text").contains("密钥已隐藏"));
        }
    }
    @Test public void whitespaceOnlySummaryIsRejectedWithoutPublishingOnMin30CompatibleValidation()throws Exception {
        try(Provider provider=new Provider()){
            provider.replyText=" \t\n\u2003";Journal journal=new Journal(provider.config());ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());
            assertEquals("summary_output_exceeds_bound",assertThrows(CompactionPlan.Unavailable.class,()->request(compactor,journal)).code);
            assertEquals(1,provider.calls.get());assertEquals(0,journal.publishes);assertEquals(InferenceReceipt.Outcome.COMPLETED,journal.receipts.get(1).outcome);
        }
    }

}
