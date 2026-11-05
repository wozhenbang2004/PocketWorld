package dev.pocketengine.runtime.context;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.memory.TaskMemoryHistory;
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
        dev.pocketengine.runtime.usage.LogicalInferenceAuthority logical;
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
        public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String member)throws Exception{if(purpose==InferenceReceipt.Purpose.SUMMARY){if(summaryTargets>=summaryLimit)throw new RunJournal.NativeBudgetPaused("task","run",1,"task","run","SUMMARY",summaryTargets,summaryLimit);summaryTargets++;}return new InferenceCapture.Target(new InferenceReceipt.Context("task","run",1,purpose,""),new InferenceCapture.Sink(){public void started(InferenceReceipt r){receipts.add(r);}public void finished(InferenceReceipt r){receipts.add(r);}},()->{},logical);}
    }
    private static JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","read_file").put("description","s".repeat(30000)).put("parameters",new JSONObject().put("type","object"))));}
    private static RunContextBudget.Projection request(ContextCompactor compactor,Journal journal)throws Exception{return compactor.request(journal.raw,new JSONArray(journal.raw.toString()),CompactionPlanTest.indexes(journal.raw),tools(),journal.config,journal.control,new RunContextBudget(journal.budgets()));}
    @Test public void coldSummaryReconstructionPreparesOldPurposeCandidateBeforeReadingBudget()throws Exception {
        var ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId","11111111-1111-1111-1111-111111111111").put("accountVersion",1).put("modelId","fixture-model"));
        ModelConfig old=new ModelConfig("","","",false,3,10,"openai_chat",1000).withAccountModel(ref);
        var summaryPlan=dev.pocketengine.runtime.models.ModelRoutePlan.exact(old,"SUMMARY","APP_OWNER");
        Journal journal=new Journal(old.withAccountModel(ref.withVersion(2)).withPurposePlans(java.util.Map.of("SUMMARY",summaryPlan)));
        coldHuge(journal);
        String raw=journal.raw.toString();AtomicInteger preparations=new AtomicInteger(),responses=new AtomicInteger();List<Long> summaryBudgetVersions=new ArrayList<>();
        var facts=dev.pocketengine.runtime.models.AccountCatalogFacts.parse(new JSONObject().put("context_window",70000).put("max_output_tokens",1000),"fixture-model",System.currentTimeMillis());
        journal.logical=new dev.pocketengine.runtime.usage.LogicalInferenceAuthority(){
            public JSONObject begin(String id,String digest,JSONObject route,long deadline)throws Exception{return new JSONObject().put("deadlineAt",deadline).put("control",new JSONObject().put("maxAttempts",route.getInt("maxAttempts")));}
            public boolean matchesHistoryOrigin(String call,ModelConfig config,JSONObject data){return false;}
            public boolean reserveRetry(String id,String call,long wait){throw new AssertionError("summary must not replay account inference");}
            public void reserve(String id,String call,int attempt,String candidate,String reason){assertEquals(1,attempt);}
            public void commit(String id,String call,long sequence){}public void attemptEnded(String id,String call,boolean dispatched,String state){}public void finish(String id,String state,String completion,String stream){}
        };
        dev.pocketengine.runtime.ModelGateway.installAccountInferencePort(new dev.pocketengine.runtime.ModelGateway.AccountInferencePort(){
            public boolean authorizationCurrent(ModelConfig.AccountModelRef selected){return ref.sameModel(selected)&&selected.accountVersion>=1&&selected.accountVersion<=2;}
            public dev.pocketengine.runtime.ModelGateway.AccountPreparation prepare(ModelConfig.AccountModelRef selected,dev.pocketengine.features.proxy.accounts.GatewayCall operation)throws dev.pocketengine.features.proxy.accounts.GatewayFailure{operation.check();preparations.incrementAndGet();return new dev.pocketengine.runtime.ModelGateway.AccountPreparation(selected.withVersion(2),facts,false);}
            public dev.pocketengine.runtime.models.AccountCatalogFacts modelFacts(ModelConfig.AccountModelRef selected)throws dev.pocketengine.features.proxy.accounts.GatewayFailure{if(selected.accountVersion!=2)throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("STALE_ACCOUNT_VERSION");return facts;}
            public void stream(ModelConfig.AccountModelRef selected,dev.pocketengine.features.proxy.providers.ResponsesRequest request,dev.pocketengine.features.proxy.accounts.GatewayCall operation,dev.pocketengine.features.proxy.providers.ChatGptProvider.EventSink sink){throw new AssertionError("version admission required");}
            public void streamWithVersion(ModelConfig.AccountModelRef selected,dev.pocketengine.features.proxy.providers.ResponsesRequest request,dev.pocketengine.features.proxy.accounts.GatewayCall operation,dev.pocketengine.runtime.ModelGateway.AccountVersionSink versions,dev.pocketengine.features.proxy.providers.ChatGptProvider.EventSink sink)throws dev.pocketengine.features.proxy.accounts.GatewayFailure{
                operation.check();assertEquals(2,selected.accountVersion);versions.authorized(2);responses.incrementAndGet();
                try{sink.event(new JSONObject().put("type","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("status","completed").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","Original source rows remain available; this is a derived summary."))))));}
                catch(Exception failure){throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("FIXTURE_RESPONSE_FAILED");}
            }
        });
        try{
            assertEquals("no_bounded_summary_fit",assertThrows(CompactionPlan.Unavailable.class,()->CompactionPlan.choose(journal.raw,journal.raw,CompactionPlanTest.indexes(journal.raw),tools(),journal.config,facts.budget(journal.config).limits)).code);
            var budgets=ContextBudgetPolicy.forRun(selected->{if(selected.routePlan!=null&&"SUMMARY".equals(selected.routePlan.purpose))summaryBudgetVersions.add(selected.accountModel.accountVersion);return dev.pocketengine.runtime.ModelGateway.accountModelFacts(selected.accountModel).budget(selected);},journal.control);
            ContextCompactor compactor=new ContextCompactor(journal,journal,budgets);
            var result=compactor.request(journal.raw,new JSONArray(journal.raw.toString()),CompactionPlanTest.indexes(journal.raw),tools(),journal.config,journal.control,new RunContextBudget(budgets));
            assertTrue(result.plan().canSend());assertTrue(preparations.get()>0);assertFalse(summaryBudgetVersions.isEmpty());assertTrue(summaryBudgetVersions.stream().allMatch(version->version==2));
            assertEquals(journal.publishes,responses.get());assertTrue(journal.publishes>0);assertEquals(raw,journal.raw.toString());assertEquals(1,summaryPlan.primary().accountModel.accountVersion);
        }finally{dev.pocketengine.runtime.ModelGateway.installAccountInferencePort(null);}
    }
    @Test public void summaryRechecksOriginalSourcesAtBothPhysicalAdmissionBoundaries()throws Exception {
        for(int rejectAt:new int[]{1,2})try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());String raw=journal.raw.toString();AtomicInteger checks=new AtomicInteger();
            var sources=new dev.pocketengine.runtime.AgentRunner.RequestProjection(){
                public JSONArray project(JSONArray rows,ModelConfig config,RunControl control){throw new AssertionError("summary does not rematerialize owner data");}
                public void prepared(JSONArray rows,JSONArray wire,ModelConfig config,RunControl control){throw new AssertionError("summary is not full MAIN owner-body exposure");}
                public void derivedPrepared(JSONArray rows,RunControl original){assertSame(journal.control,original);assertEquals(raw,rows.toString());if(checks.incrementAndGet()==rejectAt)throw new SecurityException("original preview source changed");}
            };
            ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets(),sources);
            assertThrows(Exception.class,()->request(compactor,journal));assertEquals(rejectAt,checks.get());assertEquals(0,provider.calls.get());assertEquals(0,journal.publishes);assertNull(journal.published);assertEquals(raw,journal.raw.toString());
        }
    }
    @Test public void currentSummarySourceGatePreservesOneOriginalCallAndReceipt()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());String raw=journal.raw.toString();AtomicInteger checks=new AtomicInteger();
            var sources=new dev.pocketengine.runtime.AgentRunner.RequestProjection(){
                public JSONArray project(JSONArray rows,ModelConfig config,RunControl control){throw new AssertionError("summary uses original frozen rows");}
                public void prepared(JSONArray rows,JSONArray wire,ModelConfig config,RunControl control){throw new AssertionError("no MAIN proof from a summary");}
                public void derivedPrepared(JSONArray rows,RunControl original){assertSame(journal.control,original);assertEquals(raw,rows.toString());checks.incrementAndGet();}
            };
            request(new ContextCompactor(journal,journal,journal.budgets(),sources),journal);
            assertEquals(2,checks.get());assertEquals(1,provider.calls.get());assertEquals(1,journal.publishes);assertEquals(raw,journal.raw.toString());
            assertEquals(InferenceReceipt.Purpose.SUMMARY,journal.receipts.get(0).context.purpose);
            assertEquals(journal.receipts.get(0).callId,journal.receipts.get(1).callId);
        }
    }
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
    @Test public void incrementalCoverageCanPassOneHundredWithoutIncreasingOneSummaryInputBound()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());
            request(compactor,journal);
            for(int i=0;i<56;i++){
                appendNewOriginalBatch(journal,i);
                assertTrue(request(compactor,journal).plan().canSend());
            }
            JSONObject coverage=journal.published.getJSONObject("coverage");
            assertTrue(coverage.getJSONArray("coveredIndexes").length()>100);
            assertTrue(coverage.getJSONArray("summaryInputOriginalIndexes").length()<=CompactionPlan.MAX_COVERED_ROWS);
            assertTrue(CompactionCheckpoint.sourcesCurrent(journal.published,journal.raw));
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

    @Test public void automaticCompactionUsesOnlyOtherOriginalsAfterNativeArgumentsWereRetired()throws Exception {
        try(Provider provider=new Provider()){
            Journal journal=new Journal(provider.config());JSONObject retired=journal.raw.getJSONObject(2);
            retired.put("memoryContextRetired",true).put("content",TaskMemoryHistory.RETIRED);
            retired.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments","{\"query\":\"RETIRED_ARGUMENT_OLD_SOURCE\"}");
            String original=journal.raw.toString();JSONArray visible=TaskMemoryHistory.wireMessages(journal.raw);
            ContextCompactor compactor=new ContextCompactor(journal,journal,journal.budgets());
            RunContextBudget.Projection result=compactor.request(journal.raw,visible,CompactionPlanTest.indexes(journal.raw),tools(),journal.config,journal.control,new RunContextBudget(journal.budgets()));
            assertEquals(1,provider.calls.get());assertEquals(1,journal.publishes);assertTrue(result.diagnostics().getBoolean("summariesGenerated"));assertEquals(original,journal.raw.toString());
            assertFalse(provider.request.toString().contains("RETIRED_ARGUMENT_OLD_SOURCE"));assertFalse(result.plan().messages().toString().contains("RETIRED_ARGUMENT_OLD_SOURCE"));
            assertTrue(result.plan().messages().toString().contains("call-0"));assertTrue(result.plan().messages().toString().contains("ERR_IMPORT_0"));
            JSONArray covered=journal.published.getJSONObject("coverage").getJSONArray("coveredIndexes");for(int i=0;i<covered.length();i++)assertNotEquals(2,covered.getInt(i));
            assertEquals(InferenceReceipt.Purpose.SUMMARY,journal.receipts.get(1).context.purpose);assertEquals(InferenceReceipt.Outcome.COMPLETED,journal.receipts.get(1).outcome);
            assertFalse(compactor.request(journal.raw,visible,CompactionPlanTest.indexes(journal.raw),tools(),journal.config,journal.control,new RunContextBudget(journal.budgets())).diagnostics().getBoolean("summariesGenerated"));assertEquals(1,provider.calls.get());
        }
    }

}
