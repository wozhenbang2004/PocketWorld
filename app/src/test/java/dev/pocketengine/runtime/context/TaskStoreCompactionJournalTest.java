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
        final AtomicReference<ModelConfig> selected=new AtomicReference<>();final String id,run;final int epoch;
        final TaskStoreMemoryReader.RawBinding binding;final RunJournal base,journal;final ContextCompactor compactor;
        final ContextBudgetPolicy.Source budgets;volatile boolean initialContextLimit,repeatContextLimit;volatile String finishReason="stop";volatile Action during=()->{};volatile Throwable handlerFailure;JSONArray raw;
        Fixture()throws Exception {
            Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);
            JSONObject task=tasks.createCapabilityTask("real fixture original owner task","fixture-conversation");id=task.getString("id");
            JSONObject attempt=tasks.startAttempt(id,"capabilities",40);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);
            tasks.openOwnerInputMailbox(id,run,epoch,"fixture-conversation",control);
            db=SQLiteDatabase.openDatabase(app.getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);
            provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);provider.createContext("/v1/chat/completions",exchange->{
                try{int post=posts.incrementAndGet();exchange.getRequestBody().readAllBytes();during.run();
                    if(initialContextLimit&&(post==1||repeatContextLimit&&post==3)){byte[] error="{\"error\":{\"code\":\"context_length_exceeded\"}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(400,error.length);exchange.getResponseBody().write(error);return;}
                    byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",finishReason).put("message",new JSONObject().put("role","assistant").put("content","source-0.java: ERR_IMPORT_0。已结算历史摘要，原件保留。"))))
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

}
