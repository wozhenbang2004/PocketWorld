package dev.pocketengine.runtime.context;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.MemoryHostFence;
import dev.pocketengine.features.memory.TaskStoreMemoryReader;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real TaskStore + native SQLite + original SUMMARY capture. Synthetic HTTP is not a live provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskStoreCompactionJournalTest {
    private interface Action {void run()throws Exception;}
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final SQLiteDatabase db;final HttpServer provider;final AtomicInteger posts=new AtomicInteger();
        final RunControl control=new RunControl();final AtomicLong sourceRevision=new AtomicLong();
        final AtomicReference<ModelConfig> selected=new AtomicReference<>();final AtomicReference<String> lastRequest=new AtomicReference<>("");final String id,run;final int epoch;
        final TaskStoreMemoryReader.RawBinding binding;final RunJournal base,journal;final ContextCompactor compactor;
        final ContextBudgetPolicy.Source budgets;volatile boolean initialContextLimit,repeatContextLimit;volatile String finishReason="stop",summaryText="source-0.java: ERR_IMPORT_0。已结算历史摘要，原件保留。";volatile Action during=()->{};volatile Throwable handlerFailure;JSONArray raw;
        Fixture()throws Exception {
            Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);
            JSONObject task=tasks.createCapabilityTask("real fixture original owner task","fixture-conversation");id=task.getString("id");
            JSONObject attempt=tasks.startAttempt(id,"capabilities",40);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);
            tasks.openOwnerInputMailbox(id,run,epoch,"fixture-conversation",control);
            db=SQLiteDatabase.openDatabase(app.getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
            provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);provider.createContext("/v1/chat/completions",exchange->{
                try{int post=posts.incrementAndGet();lastRequest.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));during.run();
                    if(initialContextLimit&&(post==1||repeatContextLimit&&post==3)){byte[] error="{\"error\":{\"code\":\"context_length_exceeded\"}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(400,error.length);exchange.getResponseBody().write(error);return;}
                    byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",finishReason).put("message",new JSONObject().put("role","assistant").put("content",summaryText))))
                            .put("usage",new JSONObject().put("prompt_tokens",20).put("completion_tokens",8).put("total_tokens",28)).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                }catch(Throwable failed){handlerFailure=failed;}finally{exchange.close();}
            });provider.start();
            selected.set(new ModelConfig("http://127.0.0.1:"+provider.getAddress().getPort()+"/v1","fixture-key","fixture",false,40,10,"openai_chat",1000));
            binding=new TaskStoreMemoryReader.RawBinding(id,run,epoch,control,new MemoryHostFence(){
                public void check()throws Exception{control.check();JSONObject current=tasks.get(id,false);if(!run.equals(current.optString("runId"))||epoch!=current.optInt("coordinatorEpoch"))throw new SecurityException("fixture original run changed");}
                public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}
            },sourceRevision::get);
            base=tasks.journal(id,run,epoch,control);journal=base.withCompaction(tasks.compactionJournal(id,run,epoch,control,binding,selected::get));
            raw=CompactionPlanTest.history();journal.checkpoint(raw,0,new JSONObject());
            budgets=config->ContextBudgetPolicy.RouteBudget.forRoute(config,CompactionPlanTest.budget(),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            compactor=new ContextCompactor(journal,journal.compaction(),budgets);
        }
        JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","read_file").put("description","s".repeat(30000)).put("parameters",new JSONObject().put("type","object"))));}
        RunContextBudget.Projection request()throws Exception{return compactor.request(raw,new JSONArray(raw.toString()),CompactionPlanTest.indexes(raw),tools(),selected.get(),control,new RunContextBudget(budgets));}
        JSONObject stored()throws Exception{try(Cursor row=db.rawQuery("SELECT checkpoint FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{id,run,Integer.toString(epoch)})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
        void appendSteer()throws Exception{tasks.appendOwnerInput(id,run,epoch,"fixture-conversation",new JSONObject().put("id",UUID.randomUUID().toString()).put("clientMessageId",UUID.randomUUID().toString()).put("content","owner correction arrived"),"steer",control);}
        void assertHandler(){if(handlerFailure!=null)throw new AssertionError("synthetic provider handler failed",handlerFailure);}
        public void close(){control.cancel();provider.stop(0);db.close();}
    }
    @Test public void publishAppendRestoreUseOneRealCheckpointAndSameExactRawReader()throws Exception {
        try(Fixture fixture=new Fixture()){
            String raw=fixture.raw.toString();fixture.request();fixture.assertHandler();JSONObject stored=fixture.stored(),projection=stored.getJSONObject("contextCompaction");
            assertEquals(raw,stored.getJSONArray("messages").toString());assertEquals(1,fixture.posts.get());assertFalse(projection.getBoolean("formalMemorySaved"));
            JSONObject usage=fixture.tasks.inferenceUsage(fixture.id);assertEquals(1,usage.getInt("attempts"));assertEquals(1,usage.getInt("completed"));assertEquals("SUMMARY",usage.getJSONArray("receipts").getJSONObject(0).getJSONObject("context").getString("purpose"));
            assertEquals(projection.getString("callId"),usage.getJSONArray("receipts").getJSONObject(0).getString("callId"));
            JSONObject source=projection.getJSONArray("sources").getJSONObject(0),citation=new JSONObject().put("sourceId",source.getString("sourceId")).put("revision",source.getString("revision")).put("offset",0).put("maxChars",16000);
            JSONObject page=fixture.tasks.readMemoryRaw(fixture.binding,citation);assertEquals("current",page.getString("status"));assertEquals("sha256_content_fingerprint",page.getString("revisionKind"));
            fixture.raw.put(CompactionPlanTest.message("assistant","appended original settled tail"));fixture.journal.checkpoint(fixture.raw,1,new JSONObject());
            assertEquals(projection.getString("callId"),fixture.stored().getJSONObject("contextCompaction").getString("callId"));assertEquals(fixture.raw.toString(),fixture.journal.restore().getJSONArray("messages").toString());
        }
    }
    @Test public void explicitContextLimitCompactsOnceFromRawWithoutDeletingOrRecursivelySummarizing()throws Exception {
        try(Fixture f=new Fixture()){
            String raw=f.raw.toString();RunContextBudget budget=new RunContextBudget(f.budgets);
            RunContextBudget.Projection projected=f.compactor.afterContextLimit(f.raw,new JSONArray(f.raw.toString()),CompactionPlanTest.indexes(f.raw),f.tools(),f.selected.get(),f.control,budget);
            assertTrue(projected.diagnostics().getBoolean("summaryProjectionUsed"));assertEquals(1,f.posts.get());assertEquals(raw,f.stored().getJSONArray("messages").toString());
            assertThrows(CompactionPlan.Unavailable.class,()->f.compactor.afterContextLimit(f.raw,new JSONArray(f.raw.toString()),CompactionPlanTest.indexes(f.raw),f.tools(),f.selected.get(),f.control,budget));assertEquals(1,f.posts.get());
        }
    }
    @Test public void actualRunnerContextLimitUsesOneSummaryAndOneNewMainWithoutRawDeletion()throws Exception {
        for(boolean rejectedAgain:new boolean[]{false,true})try(Fixture f=new Fixture()){
            f.initialContextLimit=true;f.repeatContextLimit=rejectedAgain;
            ContextBudgetPolicy.Source generous=config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(200000,1000,1000,500000,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            dev.pocketengine.core.CapabilityPort port=new dev.pocketengine.core.CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl c){throw new AssertionError("no tool replay");}};
            var result=new dev.pocketengine.runtime.AgentRunner(null,port,generous).run(f.id,"real fixture original owner task",f.selected.get(),f.control,event->{try{if(!"model_stream".equals(event.optString("type")))f.tasks.event(f.id,f.run,f.epoch,event);}catch(Exception failed){throw new IllegalStateException(failed);}},f.journal);
            f.assertHandler();assertEquals(result.summary,3,f.posts.get());JSONObject usage=f.tasks.inferenceUsage(f.id);assertEquals(3,usage.getInt("attempts"));
            int summaries=0;JSONArray receipts=usage.getJSONArray("receipts");for(int i=0;i<receipts.length();i++)if("SUMMARY".equals(receipts.getJSONObject(i).getJSONObject("context").getString("purpose")))summaries++;assertEquals(1,summaries);
            String stored=f.stored().getJSONArray("messages").toString();assertTrue(stored.contains("Owner goal; do not send without approval."));assertTrue(stored.contains("x".repeat(9000)));assertTrue(stored.contains("Latest correction: do not touch billing."));assertEquals(rejectedAgain?2:1,usage.getInt("failed"));
        }
    }
    @Test public void invalidBufferedSummaryRetainsRawAndFailedUsageWithoutPublishingOrRetrying()throws Exception {
        for(String finish:new String[]{"content_filter","length",null})try(Fixture fixture=new Fixture()){
            fixture.finishReason=finish;String before=fixture.stored().toString();
            assertThrows(dev.pocketengine.runtime.ModelGateway.GatewayException.class,fixture::request);fixture.assertHandler();
            assertEquals(before,fixture.stored().toString());assertFalse(fixture.stored().has("contextCompaction"));
            assertEquals(fixture.raw.toString(),fixture.journal.restore().getJSONArray("messages").toString());assertEquals(1,fixture.posts.get());
            JSONObject usage=fixture.tasks.inferenceUsage(fixture.id);assertEquals(1,usage.getInt("attempts"));assertEquals(0,usage.getInt("completed"));assertEquals(1,usage.getInt("failed"));
            JSONObject receipt=usage.getJSONArray("receipts").getJSONObject(0);assertEquals("SUMMARY",receipt.getJSONObject("context").getString("purpose"));
            assertFalse(receipt.getString("callId").isEmpty());assertEquals(20,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));
            if(finish==null)assertTrue(receipt.getJSONObject("usage").isNull("finishReason"));else assertEquals(finish,receipt.getJSONObject("usage").getString("finishReason"));
        }
    }
    @Test public void steerDuringActualNetworkRejectsSqlPublicationWithoutHoldingTheDbLock()throws Exception {
        try(Fixture fixture=new Fixture()){
            String raw=fixture.raw.toString();fixture.during=fixture::appendSteer;assertThrows(Exception.class,fixture::request);fixture.assertHandler();
            assertEquals(1,fixture.posts.get());assertFalse(fixture.stored().has("contextCompaction"));assertEquals(raw,fixture.stored().getJSONArray("messages").toString());assertEquals(1,fixture.tasks.inferenceUsage(fixture.id).getInt("completed"));
        }
    }
    @Test public void alreadyPendingUnmaterializedSteerRejectsBeforeAnySummaryHttp()throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.appendSteer();assertEquals("owner_input_not_materialized",assertThrows(CompactionPlan.Unavailable.class,fixture::request).code);
            assertEquals(0,fixture.posts.get());assertEquals(0,fixture.tasks.inferenceUsage(fixture.id).getInt("attempts"));assertFalse(fixture.stored().has("contextCompaction"));
        }
    }
    @Test public void acceptedSteerAfterPublishedSummaryRebuildsFromCurrentRawAndContinues()throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.request();assertEquals(1,fixture.posts.get());
            fixture.appendSteer();
            JSONArray incoming=fixture.tasks.pollOwnerInputs(fixture.id,fixture.run,fixture.epoch,new JSONArray(),false,false,fixture.control);
            assertEquals(1,incoming.length());JSONObject input=incoming.getJSONObject(0);
            fixture.raw.put(new JSONObject().put("role","user").put("content",input.getString("content"))
                    .put("ownerMessageId",input.getString("messageId")).put("ownerInputMode",input.getString("mode")));
            fixture.journal.checkpoint(fixture.raw,1,new JSONObject());
            assertFalse(fixture.stored().has("contextCompaction"));
            RunContextBudget.Projection next=fixture.request();
            assertTrue(next.plan().canSend());assertEquals(2,fixture.posts.get());
            assertTrue(next.plan().messages().toString().contains("owner correction arrived"));
            assertEquals(fixture.raw.toString(),fixture.stored().getJSONArray("messages").toString());
        }
    }
    @Test public void steerDuringSummaryReplansOnlyWhileEveryOriginalSourceRemainsCurrent()throws Exception {
        for(boolean afterContextLimit:new boolean[]{false,true})for(boolean sourceChanged:new boolean[]{false,true})try(Fixture f=new Fixture()){
            f.initialContextLimit=afterContextLimit;
            f.during=()->{if(f.posts.get()!=(afterContextLimit?2:1))return;f.appendSteer();if(sourceChanged){
                JSONArray changed=new JSONArray(f.raw.toString());changed.getJSONObject(3).put("content","changed original source");f.base.checkpoint(changed,0,new JSONObject());
            }};
            dev.pocketengine.core.CapabilityPort port=new dev.pocketengine.core.CapabilityPort(){
                public JSONArray tools(){try{return f.tools();}catch(Exception failed){throw new IllegalStateException(failed);}}
                public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}
                public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("no tool replay");}
            };
            dev.pocketengine.runtime.AgentRunner.OwnerInputs inputs=(included,boundary,close,control)->f.tasks.pollOwnerInputs(f.id,f.run,f.epoch,included,boundary,close,control);
            ContextBudgetPolicy.Source budgets=afterContextLimit?config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(200000,1000,1000,500000,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1):f.budgets;
            var outcome=new dev.pocketengine.runtime.AgentRunner(null,port,budgets,null,inputs).run(f.id,"real fixture original owner task",f.selected.get(),f.control,event->{},f.journal);
            f.assertHandler();assertEquals(sourceChanged?"awaiting_user":"needs_confirmation",outcome.status);assertEquals(sourceChanged?(afterContextLimit?2:1):3,f.posts.get());
            if(sourceChanged){assertFalse(f.stored().has("contextCompaction"));assertTrue(f.stored().getJSONArray("messages").toString().contains("changed original source"));}
            else{assertTrue(f.lastRequest.get().contains("owner correction arrived"));assertTrue(f.stored().getJSONArray("messages").toString().contains("owner correction arrived"));}
        }
    }
    @Test public void cumulativeCoverageUsesBoundedNativeSessionsPastOneHundredOriginals()throws Exception {
        for(boolean authorityCharge:new boolean[]{false,true})try(Fixture f=new Fixture()){
            AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();AtomicLong extraReadBytes=new AtomicLong();
            f.binding.bindMessageCurrentness((originals,bytes)->{
                if(!authorityCharge&&bytes+extraReadBytes.get()>1_048_576L)throw new TaskStoreMemoryReader.RawMessageUnavailable("fixture_cumulative_source_byte_budget");
                opened.incrementAndGet();return new TaskStoreMemoryReader.RawMessageCheck(){
                    int rows;boolean ended;
                    public void requireCurrentOriginalMessage(int index,JSONObject original){assertTrue(++rows<=CompactionPlan.MAX_COVERED_ROWS);}
                    public JSONObject readBudget()throws Exception{assertTrue(ended);return new JSONObject().put(authorityCharge?"guardAuthorityUtf8Bytes":"guardSourceUtf8Bytes",extraReadBytes.get());}
                    public void close(){ended=true;closed.incrementAndGet();}
                };
            });
            ContextBudgetPolicy.Source budgets=config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(50000,1000,1000,100000,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            ContextCompactor compactor=new ContextCompactor(f.journal,f.journal.compaction(),budgets);
            f.raw=new JSONArray().put(CompactionPlanTest.message("system","native invariant")).put(CompactionPlanTest.message("user","Owner goal remains original."));
            for(int i=0;i<64;i++){
                if(i==52){
                    f.journal.checkpoint(f.raw,0,new JSONObject());
                    assertTrue(compactor.request(f.raw,new JSONArray(f.raw.toString()),CompactionPlanTest.indexes(f.raw),f.tools(),f.selected.get(),f.control,new RunContextBudget(budgets)).plan().canSend());
                }
                f.raw.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","small-"+i).put("type","function").put("function",new JSONObject().put("name","read_file").put("arguments","{}")))))
                        .put(new JSONObject().put("role","tool").put("tool_call_id","small-"+i).put("content",new JSONObject().put("ok",true).put("text","x".repeat(i<52?500:2000)).toString()));
            }
            f.journal.checkpoint(f.raw,1,new JSONObject());
            assertTrue(compactor.request(f.raw,new JSONArray(f.raw.toString()),CompactionPlanTest.indexes(f.raw),f.tools(),f.selected.get(),f.control,new RunContextBudget(budgets)).plan().canSend());
            assertTrue(f.stored().getJSONObject("contextCompaction").getJSONObject("coverage").getJSONArray("coveredIndexes").length()>100);
            assertEquals(opened.get(),closed.get());assertEquals(f.raw.toString(),f.stored().getJSONArray("messages").toString());
            // Each individual batch fits, but four batches across both passes do not.
            // The close-time read charge must carry into the next batch AND second pass.
            int posts=f.posts.get();extraReadBytes.set(300000);
            assertEquals("published_summary_source_changed",assertThrows(CompactionPlan.Unavailable.class,()->compactor.request(f.raw,new JSONArray(f.raw.toString()),CompactionPlanTest.indexes(f.raw),f.tools(),f.selected.get(),f.control,new RunContextBudget(budgets))).code);
            assertEquals(posts,f.posts.get());assertEquals(opened.get(),closed.get());
        }
    }
    @Test public void changedRealCheckpointDuringNetworkCannotPublishTheOldSourceVersion()throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.during=()->{JSONArray changed=new JSONArray(fixture.raw.toString());changed.getJSONObject(3).put("content","new original source body");fixture.base.checkpoint(changed,0,new JSONObject());};
            assertEquals("summary_publish_stale",assertThrows(CompactionPlan.Unavailable.class,fixture::request).code);fixture.assertHandler();assertFalse(fixture.stored().has("contextCompaction"));assertTrue(fixture.stored().toString().contains("new original source body"));assertEquals(1,fixture.posts.get());
        }
    }
    @Test public void forgetRetiresPublishedProjectionAndOldMemoryCannotWriteItBack()throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.request();assertTrue(fixture.stored().has("contextCompaction"));fixture.sourceRevision.incrementAndGet();
            fixture.tasks.invalidateMemoryContexts(MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","private")));
            assertFalse(fixture.stored().has("contextCompaction"));fixture.journal.checkpoint(fixture.raw,1,new JSONObject());
            JSONObject stored=fixture.stored();assertFalse(stored.has("contextCompaction"));assertEquals("Owner goal; do not send without approval.",stored.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertTrue(stored.getJSONArray("messages").toString().contains("ERR_IMPORT_0"));assertEquals(1,fixture.posts.get());
        }
    }
    @Test public void oldPauseCandidateDoesNotPublishAndAuditRestoreIsNotPermanentlyBlocked()throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.during=()->{fixture.control.pause();fixture.control.resume();};assertThrows(Exception.class,fixture::request);fixture.assertHandler();
            assertFalse(fixture.stored().has("contextCompaction"));assertEquals(fixture.raw.toString(),fixture.journal.restore().getJSONArray("messages").toString());
            fixture.journal.checkpoint(fixture.raw,1,new JSONObject());assertEquals(fixture.raw.toString(),fixture.stored().getJSONArray("messages").toString());assertEquals(1,fixture.posts.get());
        }
    }
    /** Native gate contract simulation only; actual Assistant/TaskStore source projection is C4's separate fixture. */
    private static java.util.concurrent.atomic.AtomicBoolean currentness(Fixture fixture,AtomicInteger opened,AtomicInteger closed){
        var readable=new java.util.concurrent.atomic.AtomicBoolean(true);
        fixture.binding.bindMessageCurrentness((originals,bytes)->{
            assertTrue(Thread.holdsLock(fixture.tasks));assertTrue(bytes<=1_048_576);opened.incrementAndGet();
            return new TaskStoreMemoryReader.RawMessageCheck(){
                public void requireCurrentOriginalMessage(int index,JSONObject original)throws Exception{
                    assertEquals(originals.getJSONObject(index).toString(),original.toString());
                    if(!readable.get())throw new TaskStoreMemoryReader.RawMessageUnavailable("fixture_history_source_retired");
                }
                public void close()throws Exception{closed.incrementAndGet();if(!readable.get())throw new TaskStoreMemoryReader.RawMessageUnavailable("fixture_history_source_retired");}
            };
        });return readable;
    }
    @Test public void retiredNativeOriginalGatePreventsFreezeBeforeAnySummaryHttp()throws Exception {
        try(Fixture fixture=new Fixture()){
            AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();var readable=currentness(fixture,opened,closed);readable.set(false);
            assertEquals("raw_source_currentness_unavailable",assertThrows(CompactionPlan.Unavailable.class,fixture::request).code);
            assertEquals(0,fixture.posts.get());assertFalse(fixture.stored().has("contextCompaction"));assertEquals(opened.get(),closed.get());
        }
    }
    @Test public void dependencyRetiredDuringSummaryNetworkBlocksPublicationEvenWhenAuditBytesDoNotChange()throws Exception {
        try(Fixture fixture=new Fixture()){
            AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();var readable=currentness(fixture,opened,closed);String original=fixture.stored().getJSONArray("messages").toString();fixture.during=()->readable.set(false);
            assertEquals("raw_source_currentness_unavailable",assertThrows(CompactionPlan.Unavailable.class,fixture::request).code);fixture.assertHandler();
            assertEquals(1,fixture.posts.get());assertFalse(fixture.stored().has("contextCompaction"));assertEquals(original,fixture.stored().getJSONArray("messages").toString());assertEquals(opened.get(),closed.get());
        }
    }
    @Test public void publishedSummaryCurrentAppendAndAuditRestoreRecheckTheSameNativeOriginalGate()throws Exception {
        try(Fixture fixture=new Fixture()){
            AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();var readable=currentness(fixture,opened,closed);fixture.request();assertTrue(fixture.stored().has("contextCompaction"));
            readable.set(false);assertEquals("published_summary_source_changed",assertThrows(CompactionPlan.Unavailable.class,fixture::request).code);
            assertFalse(fixture.journal.restore().has("contextCompaction"));fixture.journal.checkpoint(fixture.raw,1,new JSONObject());
            assertFalse(fixture.stored().has("contextCompaction"));assertEquals(1,fixture.posts.get());assertEquals(opened.get(),closed.get());
        }
    }

    @Test public void nativeSessionFinalRecheckFailureCannotCommitEvenAfterAllRowsWereAccepted()throws Exception {
        try(Fixture fixture=new Fixture()){
            AtomicInteger sessions=new AtomicInteger();fixture.binding.bindMessageCurrentness((originals,bytes)->{
                final int session=sessions.incrementAndGet();return new TaskStoreMemoryReader.RawMessageCheck(){
                    public void requireCurrentOriginalMessage(int index,JSONObject original){}
                    public void close()throws Exception{if(session==2)throw new TaskStoreMemoryReader.RawMessageUnavailable("fixture_source_changed_at_session_close");}
                };
            });
            assertEquals("raw_source_currentness_unavailable",assertThrows(CompactionPlan.Unavailable.class,fixture::request).code);fixture.assertHandler();
            assertEquals(1,fixture.posts.get());assertFalse(fixture.stored().has("contextCompaction"));assertEquals(2,sessions.get());
        }
    }

    @Test public void currentnessBudgetIncludesTheWholeAlreadyReadSqlCheckpointEnvelope()throws Exception {
        try(Fixture fixture=new Fixture()){
            fixture.journal.checkpoint(fixture.raw,0,new JSONObject().put("nativeObservation","界".repeat(3000)));
            final long expected;try(Cursor row=fixture.db.rawQuery("SELECT length(CAST(checkpoint AS BLOB)) FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{fixture.id,fixture.run,Integer.toString(fixture.epoch)})){assertTrue(row.moveToFirst());expected=row.getLong(0);}
            AtomicLong observed=new AtomicLong();fixture.binding.bindMessageCurrentness((originals,bytes)->{
                assertEquals(expected,bytes);assertTrue(bytes>originals.toString().getBytes(StandardCharsets.UTF_8).length);observed.set(bytes);
                return new TaskStoreMemoryReader.RawMessageCheck(){public void requireCurrentOriginalMessage(int index,JSONObject original){}public void close(){}};
            });
            fixture.request();assertEquals(expected,observed.get());assertTrue(fixture.stored().has("contextCompaction"));assertEquals(1,fixture.posts.get());
        }
    }

    @Test public void summaryGroupFallbackUsesActualCandidateBudgetAndSecretWhileMainStaysCaptured()throws Exception {
        try(Fixture f=new Fixture()){
            HttpServer failed=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger failedCalls=new AtomicInteger();failed.createContext("/",exchange->{try{failedCalls.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] bytes="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});failed.start();
            try{
                ModelConfig main=f.selected.get(),first=new ModelConfig("http://127.0.0.1:"+failed.getAddress().getPort()+"/v1","first-summary-key","summary-first",false,40,10,"openai_chat",1000),actual=new ModelConfig(main.baseUrl,"actual-summary-key","summary-actual",false,40,10,"openai_chat",1000);
                var preferences=RuntimeEnvironment.getApplication().getSharedPreferences("summary_group_fixture",0);preferences.edit().clear().commit();java.util.Map<String,String> secrets=new java.util.HashMap<>();dev.pocketengine.store.SettingsStore.ApiSecrets vault=new dev.pocketengine.store.SettingsStore.ApiSecrets(){public String get(String slot){return secrets.getOrDefault(slot,"");}public void put(String slot,String value){secrets.put(slot,value);}};
                var constructor=dev.pocketengine.store.SettingsStore.class.getDeclaredConstructor(android.content.SharedPreferences.class,dev.pocketengine.store.SettingsStore.ApiSecrets.class);constructor.setAccessible(true);var settings=constructor.newInstance(preferences,vault);var service=new dev.pocketengine.runtime.models.ModelConnectionService(settings,java.nio.file.Files.createTempDirectory("summary-group-real-store").resolve("catalog"),java.time.Clock.systemUTC());service.install();service.connections();JSONArray candidates=new JSONArray(),accounts=new JSONArray();
                for(ModelConfig candidate:java.util.List.of(first,actual)){JSONObject saved=settings.saveApiConnection("",0,candidate.model,new JSONObject().put("baseUrl",candidate.baseUrl).put("apiKey",candidate.apiKey).put("model",candidate.model).put("provider",candidate.provider).put("maxOutputTokens",1000).put("sendScreenshots",false));accounts.put(saved.getString("id"));candidates.put(new JSONObject().put("candidateId",candidate.model).put("target",dev.pocketengine.runtime.models.ModelTargetRef.exact(saved.getString("id"),candidate.model).toJson()).put("enabled",true).put("allowedPurposes",new JSONArray().put("SUMMARY")).put("allowedAudiences",new JSONArray().put("APP_OWNER")));}
                JSONObject group=settings.saveModelGroup("",0,"Summary native group",new JSONObject().put("allowedAccounts",new JSONObject().put("mode","EXPLICIT").put("refs",accounts)).put("candidates",candidates).put("attemptPolicy",new JSONObject().put("maxAttempts",3).put("maxRouteSwitches",1)));var summary=settings.captureModelTarget(dev.pocketengine.runtime.models.ModelTargetRef.group(group.getString("groupId"),1),"SUMMARY","APP_OWNER",main);
                f.selected.set(main.withPurposePlans(java.util.Map.of("SUMMARY",summary.routePlan)));f.summaryText="actual-summary-key was quoted by fixture; retained useful summary.";java.util.Set<String> budgetModels=new java.util.HashSet<>();ContextBudgetPolicy.Source budgets=config->{budgetModels.add(config.model);return f.budgets.resolve(config);};
                ContextCompactor compactor=new ContextCompactor(f.journal,f.journal.compaction(),budgets);String original=f.raw.toString();compactor.request(f.raw,new JSONArray(original),CompactionPlanTest.indexes(f.raw),f.tools(),f.selected.get(),f.control,new RunContextBudget(budgets));
                assertEquals(1,failedCalls.get());assertEquals(1,f.posts.get());assertTrue(budgetModels.contains("summary-first"));assertTrue(budgetModels.contains("summary-actual"));assertTrue(budgetModels.contains(main.model));assertEquals(main.model,f.selected.get().model);assertEquals(main.apiKey,f.selected.get().apiKey);assertEquals(original,f.stored().getJSONArray("messages").toString());JSONObject published=f.stored().getJSONObject("contextCompaction");assertFalse(published.getString("text").contains("actual-summary-key"));assertTrue(published.getBoolean("hostSecretRedacted"));assertEquals("summary-actual",published.getJSONObject("inferenceReceipt").getJSONObject("route").getString("requestedModel"));assertEquals(2,f.tasks.inferenceUsage(f.id).getInt("attempts"));
            }finally{failed.stop(0);}
        }
    }

}
