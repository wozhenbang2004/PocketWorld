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
        JSONArray raw;JSONObject published;int publishes;int summaryLimit=Integer.MAX_VALUE,summaryTargets;boolean revokeAfterFirstPublication;
        Journal(ModelConfig config)throws Exception{this.config=config;raw=CompactionPlanTest.history();control.bindHostRun("task","run",1);}
        ContextBudgetPolicy.Source budgets(){return selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,CompactionPlanTest.budget(),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);}
        JSONObject state(CompactionPlan plan)throws Exception{return new JSONObject().put("taskId","task").put("runId","run").put("epoch",1).put("pauseRevision",control.pauseRevision()).put("sourceBarrier",barrier.get())
                .put("inputsFingerprint",Integer.toString(inputs.get())).put("approvalsFingerprint","none").put("operationsFingerprint","settled")
                .put("routeFingerprint",CompactionPlan.routeFingerprint(config)).put("rawFingerprint",CompactionPlan.fingerprint(raw)).put("tailFingerprint",CompactionPlan.fingerprint(CompactionPlan.slice(raw,plan.endExclusive,raw.length())));}
        public FrozenPrefix freeze(CompactionPlan plan,RunControl original)throws Exception {
            if(plan.incremental())plan.requirePreviousPublication(published);JSONArray sources=new JSONArray();for(int index:plan.coveredIndexes()){int chars=raw.getJSONObject(index).toString(2).length();sources.put(new JSONObject().put("sourceId","task:task:run:run:checkpoint-message:"+index)
                    .put("revision","sha256:"+CompactionPlan.fingerprint(raw.getJSONObject(index))).put("revisionKind","sha256_content_fingerprint")
                    .put("readRange",new JSONObject().put("offset",0).put("chars",chars).put("totalChars",chars).put("complete",true).put("hasMore",false)));}
            return FrozenPrefix.fromHost(plan,state(plan),sources,original);
        }
        public PublishReceipt publish(FrozenPrefix expected,SummaryDraft actual,RunControl original)throws Exception {
            if(expected.plan.incremental())expected.plan.requirePreviousPublication(published);if(!expected.samePublicationState(state(expected.plan)))return new PublishReceipt("stale","");publishes++;published=actual.toJson().put("projectionId","projection-"+publishes);if(revokeAfterFirstPublication&&publishes==1)barrier.incrementAndGet();return new PublishReceipt("published","projection-"+publishes);
        }
        public boolean current(FrozenPrefix prefix,RunControl original)throws Exception{return barrier.get()==prefix.sourceBarrier&&published!=null&&CompactionCheckpoint.sourcesCurrent(published,raw);}
        public boolean scopeCurrent(RunControl original){return original==control&&!control.isPaused()&&barrier.get()==0;}
        public JSONObject restore()throws Exception{return new JSONObject().put("messages",raw);}
        public void checkpoint(JSONArray messages,int steps,JSONObject observation){raw=messages;}
        public String beginOperation(String tool,JSONObject args){throw new AssertionError("summary never executes a tool");}
        public void finishOperation(String id,JSONObject receipt){throw new AssertionError("summary never manufactures a tool receipt");}
        public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String member)throws Exception{if(purpose==InferenceReceipt.Purpose.SUMMARY){if(summaryTargets>=summaryLimit)throw new RunJournal.NativeBudgetPaused("task","run",1,"task","run","SUMMARY",summaryTargets,summaryLimit);summaryTargets++;}return new InferenceCapture.Target(new InferenceReceipt.Context("task","run",1,purpose,""),new InferenceCapture.Sink(){public void started(InferenceReceipt r){receipts.add(r);}public void finished(InferenceReceipt r){receipts.add(r);}});}
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

    private static void appendNewOriginalBatch(Journal journal,int index)throws Exception {
        String id="next-"+index;journal.raw.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name","read_file").put("arguments","{}"))))).put(new JSONObject().put("role","tool").put("tool_call_id",id).put("content",new JSONObject().put("ok",true).put("file","new-original-"+index).put("text","n".repeat(9000)).toString()));
    }
    @Test public void boundedIncrementalSummaryUsesPublishedDerivedInputAndNewOriginalsWithFullRawCoverage()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());request(compactor,journal);JSONObject original=new JSONObject(journal.published.toString());JSONArray covered=original.getJSONObject("coverage").getJSONArray("coveredIndexes");
            for(int i=0;i<7;i++){appendNewOriginalBatch(journal,i);request(compactor,journal);}
            assertTrue(provider.calls.get()>1);JSONObject current=journal.published,coverage=current.getJSONObject("coverage");assertEquals(1,coverage.getInt("summaryLayers"));assertTrue(coverage.getInt("summaryDerivationDepth")>1);assertEquals("published_summary_plus_new_originals",coverage.getString("summaryInputMode"));
            String summaryInput=provider.request.getJSONArray("messages").toString();assertTrue(summaryInput.contains("previousPublishedDerivedSummary"));assertTrue(summaryInput.contains("newOriginalRows"));assertFalse("old raw body must not be blindly resent to every summary",summaryInput.contains("x".repeat(8000)));
            for(int i=0;i<covered.length();i++)assertTrue(coverage.getJSONArray("coveredIndexes").toString().contains(String.valueOf(covered.getInt(i))));assertEquals(CompactionPlanTest.history().length()+14,journal.raw.length());assertTrue(CompactionCheckpoint.sourcesCurrent(current,journal.raw));assertTrue(coverage.getBoolean("rawRetained"));
        }
    }
    @Test public void changedPublishedSummaryCannotBecomeAnIncrementalInputOrPublishAfterNetwork()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());request(compactor,journal);int first=journal.publishes;provider.during=()->{try{journal.published.put("text","modified previous summary");}catch(Exception bad){throw new AssertionError(bad);}};
            CompactionPlan.Unavailable failure=null;for(int i=0;i<7&&failure==null;i++){appendNewOriginalBatch(journal,i);try{request(compactor,journal);}catch(CompactionPlan.Unavailable unavailable){failure=unavailable;}}
            assertNotNull(failure);assertEquals("incremental_summary_original_publication_changed",failure.code);assertEquals(first,journal.publishes);assertEquals(2,provider.calls.get());assertEquals(InferenceReceipt.Outcome.COMPLETED,journal.receipts.get(journal.receipts.size()-1).outcome);
        }
    }

    private static void coldHuge(Journal journal)throws Exception {for(int i=0;i<14;i++)appendNewOriginalBatch(journal,i);}
    @Test public void hugeColdRawPublishesStrictlyAdvancingCurrentNativeChunksBeforeFirstMain()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());coldHuge(journal);String original=journal.raw.toString();assertThrows(CompactionPlan.Unavailable.class,()->CompactionPlan.choose(journal.raw,journal.raw,CompactionPlanTest.indexes(journal.raw),tools(),journal.config,CompactionPlanTest.budget()));ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());RunContextBudget.Projection current=request(compactor,journal);
            assertTrue(provider.calls.get()>=2);assertEquals(provider.calls.get(),journal.publishes);assertEquals(provider.calls.get()*2,journal.receipts.size());assertEquals(original,journal.raw.toString());assertTrue(current.plan().canSend());assertTrue(journal.published.getJSONObject("coverage").getInt("summaryDerivationDepth")>=2);assertTrue(CompactionCheckpoint.sourcesCurrent(journal.published,journal.raw));assertFalse(provider.request.has("tools"));
        }
    }
    @Test public void coldReconstructionPreTargetBudgetPauseKeepsPublishedPrefixAndDoesNotReplayItsSummary()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());coldHuge(journal);journal.summaryLimit=1;String original=journal.raw.toString();ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());RunJournal.NativeBudgetPaused stopped=assertThrows(RunJournal.NativeBudgetPaused.class,()->request(compactor,journal));assertFalse(stopped.diagnostics().getBoolean("thisRequestDispatched"));assertEquals(1,provider.calls.get());assertEquals(1,journal.publishes);String firstCall=journal.published.getString("callId");assertEquals(original,journal.raw.toString());
            journal.summaryLimit=4;assertTrue(request(compactor,journal).plan().canSend());assertNotEquals(firstCall,journal.published.getString("callId"));assertTrue(provider.calls.get()>=2);assertEquals(provider.calls.get(),journal.publishes);assertEquals(original,journal.raw.toString());
        }
    }
    @Test public void revokedIntermediatePublicationStopsBeforeTheNextSummaryOrAnyMain()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());coldHuge(journal);journal.revokeAfterFirstPublication=true;String raw=journal.raw.toString();ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());assertEquals("published_summary_source_changed",assertThrows(CompactionPlan.Unavailable.class,()->request(compactor,journal)).code);assertEquals(1,provider.calls.get());assertEquals(1,journal.publishes);assertEquals(raw,journal.raw.toString());assertEquals(InferenceReceipt.Outcome.COMPLETED,journal.receipts.get(1).outcome);
        }
    }

}
