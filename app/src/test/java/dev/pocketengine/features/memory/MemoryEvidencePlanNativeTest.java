package dev.pocketengine.features.memory;

import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.ConversationRepository;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real native SQLite messages through the existing Agent capability, with synthetic evidence.
 * Suggestions are supplied inputs; these tests do not claim to measure MAIN rewrite quality. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryEvidencePlanNativeTest {
    @FunctionalInterface private interface StepHook {void after(int call)throws Exception;}
    private static final class Fixture implements AutoCloseable {
        final SQLiteDatabase db=SQLiteDatabase.create(null);
        final ConversationRepository repository;
        final String conversation=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        final RunControl control=new RunControl();final AtomicLong revision=new AtomicLong();
        final TaskStoreMemoryReader.RawBinding raw;
        int queryCalls,readCalls,directoryCalls,failQuery;
        StepHook afterQuery=call->{},beforeRead=call->{};
        Fixture()throws Exception{
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            db.execSQL("INSERT INTO meta(key,value) VALUES('assistant_history_new_install','1')");
            ConversationRepository.createSchema(db);repository=new ConversationRepository(db,new Object());repository.initializeEmpty();
            conversation(conversation,1000);control.bindHostRun(task,run,1);
            raw=new TaskStoreMemoryReader.RawBinding(task,run,1,control,new MemoryHostFence(){
                public void check(){}
                public JSONObject admit(Admission action)throws Exception{return action.run();}
            },revision::get);
        }
        void conversation(String id,long at)throws Exception{repository.transaction(()->{repository.saveConversation(new JSONObject().put("conversationId",id).put("title","synthetic fixture").put("createdAt",at).put("updatedAt",at));return null;});}
        SourceRef message(String id,String where,long version,String body,long at)throws Exception{
            SourceRef ref=new SourceRef("message",id,version,"owner","private");
            repository.transaction(()->{repository.saveSource(ref.toJson().put("conversationId",where).put("role","user").put("deleted",false));repository.saveMessage(where,new JSONObject().put("id",id).put("revision",version).put("role","user").put("content",body).put("at",at));return null;});return ref;
        }
        void withdraw(SourceRef ref)throws Exception{repository.transaction(()->repository.retire(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(ref.toJson()))),"owner_forget"));}
        void ownerPrivate()throws Exception{repository.setHistoryReadScope(conversation,repository.historyReadScopeSnapshot(conversation).getLong("revision"),ConversationHistory.ScopeMode.OWNER_PRIVATE);}
        private final class NativeGate implements ConversationHistory.Gate {
            final String conversationId;NativeGate(String id){conversationId=id;}
            public boolean current(){try{return repository.exists(conversationId);}catch(Exception unavailable){return false;}}
        }
        ConversationHistory.Capability capability()throws Exception{
            ConversationHistory.Source originals=new ConversationHistory.Source(){
                public ConversationHistory.Gate open(String id)throws Exception{if(!repository.exists(id))throw new SecurityException("original conversation missing");return new NativeGate(id);}
                public ConversationHistory.ReadScope captureReadScope(String id)throws Exception{return repository.captureHistoryReadScope(id);}
                public String conversationFor(SourceRef ref)throws Exception{JSONObject metadata=repository.metadata(ref.sourceId);return metadata==null?null:metadata.getString("conversationId");}
                public JSONObject conversations(ConversationHistory.ReadScope scope,JSONObject options,RunControl original)throws Exception{directoryCalls++;return repository.queryHistoryConversations(scope,options,original);}
                public JSONObject query(ConversationHistory.Gate gate,JSONObject options,RunControl original)throws Exception{
                    int call=++queryCalls;if(call==failQuery)throw new IOException("synthetic original store interruption");
                    JSONObject page=repository.queryHistory(((NativeGate)gate).conversationId,options,original);afterQuery.after(call);return page;
                }
                public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> refs)throws Exception{JSONArray states=new JSONArray();for(SourceRef ref:refs)states.put(repository.state(((NativeGate)gate).conversationId,ref));return states;}
                public JSONObject read(ConversationHistory.Gate gate,SourceRef expected,int offset,int maxChars,RunControl original)throws Exception{
                    beforeRead.after(++readCalls);original.check();String where=((NativeGate)gate).conversationId;
                    JSONObject state=repository.state(where,expected);if(!"current".equals(state.getString("status")))return state;
                    JSONObject message=repository.historyMessage(where,expected.sourceId,new CancellationSignal());
                    if(message==null)return new JSONObject().put("status","unavailable").put("reason","original_missing");
                    return ConversationHistory.messagePage(where,message,offset,maxChars);
                }
            };
            return new ConversationHistory.Capability(new CapabilityPort(){
                public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}
                public JSONObject execute(String name,JSONObject args,RunControl original){throw new AssertionError("unexpected external operation");}
            },new ConversationHistory.HistoryBinding(raw,conversation,originals),new ConversationHistory.Tasks(){
                public JSONObject query(ConversationHistory.HistoryBinding binding,ConversationHistory.SourceLocator selected,String kind,JSONObject options){throw new AssertionError("message fixture never fabricates task evidence");}
                public JSONObject read(ConversationHistory.HistoryBinding binding,ConversationHistory.SourceLocator selected,int offset,int maxChars){throw new AssertionError("message fixture never fabricates task reads");}
                public void requireCurrent(ConversationHistory.HistoryBinding binding,List<ConversationHistory.SourceLocator> provided){assertTrue(provided.isEmpty());}
            });
        }
        JSONObject plan(String query)throws Exception{return new JSONObject().put("kind","messages").put("query",query).put("maxCalls",4);}
        JSONObject execute(ConversationHistory.Capability capability,JSONObject args)throws Exception{return capability.execute(ConversationHistory.SEARCH,args,control);}
        public void close(){db.close();}
    }

    @Test public void suggestedPhrasesReachExactOriginalWithoutChangingTheExistingToolOrGrantingCurrentTruth()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){
            SourceRef ref=f.message("message-recovery",f.conversation,1,"The gateway stalled; restarting the worker restored traffic.",10);
            JSONObject baseline=f.execute(cap,new JSONObject().put("kind","messages").put("query","服务恢复办法"));
            assertEquals(0,baseline.getJSONArray("entries").length());assertFalse(baseline.has("queryPlan"));
            JSONObject request=f.plan("服务恢复办法").put("queries",new JSONArray().put("restarting the worker").put("restarting the worker")).put("readTop",1);
            JSONObject reply=f.execute(cap,request),row=reply.getJSONArray("entries").getJSONObject(0);
            assertEquals(1,reply.getJSONArray("entries").length());assertEquals(ref,SourceRef.fromJson(row.getJSONObject("sourceRef")));
            assertTrue(row.getBoolean("exactSourceRead"));assertTrue(row.getBoolean("historicalEvidence"));assertFalse(row.getBoolean("currentFactVerified"));
            assertTrue(row.getString("bodyFingerprint").matches("sha256:[0-9a-f]{64}"));assertEquals(0,row.getJSONObject("readRange").getInt("offset"));
            assertEquals(2,reply.getJSONObject("queryPlan").getInt("queryCount"));assertEquals(3,reply.getJSONObject("queryPlan").getInt("producerCalls"));
            assertEquals(0,reply.getJSONObject("queryPlan").getInt("extraNetworkRequests"));assertFalse(reply.getBoolean("executionAuthorized"));
            assertTrue(TaskStoreMemoryReader.isDerivedReferenceTool(ConversationHistory.SEARCH));
            JSONObject again=f.execute(cap,request);assertEquals(1,again.getJSONArray("entries").length());assertEquals(5,f.queryCalls);assertEquals(2,f.readCalls);
            assertEquals(row.getString("bodyFingerprint"),again.getJSONArray("entries").getJSONObject(0).getString("bodyFingerprint"));
            assertNotEquals(row.getString("selectionId"),again.getJSONArray("entries").getJSONObject(0).getString("selectionId"));
            assertTrue(cap.tools().toString().contains("readTop"));assertEquals(2,cap.tools().length());
        }
    }

    @Test public void crossSessionPlanUsesOnlyNativeOwnerScopeAndAppliesTimeAtTheOriginalMessageLayer()throws Exception{
        try(Fixture f=new Fixture()){
            String old=UUID.randomUUID().toString();f.conversation(old,100);
            f.message("historic-name",old,1,"Orion is the project name.",10);SourceRef corrected=f.message("historic-rename",old,1,"Orion was renamed Vega. The old name is historical.",30);
            JSONObject request=f.plan("Vega").put("kind","conversations").put("follow","messages").put("readTop",1).put("maxCalls",5).put("createdAfter",20);
            try(ConversationHistory.Capability current=f.capability()){assertEquals(0,f.execute(current,request).getJSONArray("entries").length());assertThrows(IllegalArgumentException.class,()->f.execute(current,new JSONObject(request.toString()).put("scope","OWNER_PRIVATE")));}
            f.ownerPrivate();
            try(ConversationHistory.Capability privateHistory=f.capability()){
                JSONObject reply=f.execute(privateHistory,request),row=reply.getJSONArray("entries").getJSONObject(0);
                assertEquals(1,reply.getJSONArray("entries").length());assertEquals(old,row.getString("sourceConversationId"));assertEquals(corrected,SourceRef.fromJson(row.getJSONObject("sourceRef")));
                assertEquals("OWNER_PRIVATE",reply.getString("historyScopeMode"));assertFalse(reply.getJSONObject("queryPlan").getBoolean("scopeExpanded"));
                assertFalse(row.getBoolean("currentFactVerified"));assertFalse(reply.toString().contains("Orion is the project name."));
            }
        }
    }

    @Test public void changedRevisionBetweenQueriesIsRemovedAndOnlyAFreshNativeReadCanReturnCorrection()throws Exception{
        try(Fixture f=new Fixture()){
            f.message("changing-message",f.conversation,1,"service cobalt used the old endpoint",10);
            f.afterQuery=call->{if(call==1)f.message("changing-message",f.conversation,2,"service cobalt uses the corrected endpoint",20);};
            try(ConversationHistory.Capability cap=f.capability()){
                JSONObject result=f.execute(cap,f.plan("service").put("queries",new JSONArray().put("cobalt")));
                assertEquals("partial",result.getString("status"));assertEquals(0,result.getJSONArray("entries").length());
                assertTrue(result.getJSONArray("gaps").toString().contains("source_revision_changed_between_queries"));assertFalse(result.toString().contains("old endpoint"));assertFalse(result.toString().contains("corrected endpoint"));
            }
            f.afterQuery=call->{};
            try(ConversationHistory.Capability fresh=f.capability()){
                JSONObject row=f.execute(fresh,f.plan("cobalt").put("readTop",1)).getJSONArray("entries").getJSONObject(0);
                assertEquals(2,row.getJSONObject("sourceRef").getLong("revision"));assertTrue(row.getString("body").contains("corrected endpoint"));
            }
        }
    }

    @Test public void correctionBeforeExactReadDropsTheOldCandidateWithoutSubstitutingTheNewBody()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){
            f.message("changing-exact",f.conversation,1,"initial endpoint",10);
            f.beforeRead=call->f.message("changing-exact",f.conversation,2,"replacement endpoint",20);
            JSONObject reply=f.execute(cap,f.plan("endpoint").put("readTop",1));
            assertEquals("partial",reply.getString("status"));assertEquals(0,reply.getJSONArray("entries").length());assertEquals(1,reply.getJSONArray("gaps").length());
            assertFalse(reply.toString().contains("initial endpoint"));assertFalse(reply.toString().contains("replacement endpoint"));
        }
    }

    @Test public void finalCurrentnessCheckRejectsWithdrawnBodyAndTheNextPlanCannotResurrectIt()throws Exception{
        try(Fixture f=new Fixture()){
            SourceRef withdrawn=f.message("withdrawn-message",f.conversation,1,"revoked internal note",10);
            f.afterQuery=call->f.withdraw(withdrawn);
            try(ConversationHistory.Capability cap=f.capability()){assertThrows(SecurityException.class,()->f.execute(cap,f.plan("internal")));}
            f.afterQuery=call->{};
            try(ConversationHistory.Capability fresh=f.capability()){
                JSONObject reply=f.execute(fresh,f.plan("internal").put("queries",new JSONArray().put("note")));
                assertEquals(0,reply.getJSONArray("entries").length());assertFalse(reply.toString().contains("revoked internal note"));
                assertEquals("retired",f.repository.state(f.conversation,withdrawn).getString("status"));
            }
        }
    }

    @Test public void cancellationAndPauseLateCallbackAbortTheOriginalInvocationInsteadOfBecomingPartialSuccess()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){
            f.message("cancel-message",f.conversation,1,"cancel marker",10);f.afterQuery=call->f.control.cancel();
            assertThrows(InterruptedException.class,()->f.execute(cap,f.plan("marker").put("queries",new JSONArray().put("cancel"))));assertEquals(1,f.queryCalls);assertEquals(0,f.readCalls);
        }
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){
            f.message("pause-message",f.conversation,1,"pause marker",10);
            f.afterQuery=call->{f.control.pause();f.control.resume();f.raw.admitRequest(new CapabilitySnapshot(f.task,f.run,1,f.control,Set.of(ConversationHistory.SEARCH,ConversationHistory.READ),()->{}));};
            assertThrows(SecurityException.class,()->f.execute(cap,f.plan("marker").put("readTop",1)));assertEquals(1,f.queryCalls);assertEquals(0,f.readCalls);
        }
    }

    @Test public void allQueriesShareTheNativeCallCeilingAndLowQualitySuggestionsReturnNoEvidence()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){
            f.message("unrelated-message",f.conversation,1,"orchid rainfall",10);
            JSONObject request=f.plan("engine timeout").put("queries",new JSONArray().put("retry scheduler").put("request backlog").put("run restart")).put("maxCalls",2);
            JSONObject reply=f.execute(cap,request),budget=reply.getJSONObject("queryPlan");
            assertEquals(0,reply.getJSONArray("entries").length());assertEquals("partial",reply.getString("status"));assertEquals(2,f.queryCalls);
            assertEquals(2,budget.getInt("producerCalls"));assertEquals(2L*TaskStoreMemoryQuery.RAW_MAX_BYTES,budget.getLong("sourceUtf8ByteCeiling"));
            assertEquals(2L*TaskStoreMemoryQuery.RAW_MAX_BYTES,budget.getLong("reservedSourceUtf8Bytes"));assertEquals(2,budget.getInt("reportedSourceRows"));
            assertTrue(budget.getString("byteAccounting").contains("not_physical_io"));assertFalse(budget.getBoolean("sharedProducerWindow"));assertFalse(budget.getBoolean("nativeIntegrityChecksMeteredHere"));
            assertFalse(reply.toString().contains("orchid rainfall"));
            JSONObject exhaustive=f.execute(cap,f.plan("absent").put("maxCalls",1));assertEquals("current",exhaustive.getString("status"));assertEquals("no_evidence",exhaustive.getString("reason"));
        }
    }

    @Test public void sourceIoFailureRetainsOnlyOtherFinallyVerifiedOriginalsAndReportsPartialCoverage()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){
            SourceRef ref=f.message("partial-message",f.conversation,1,"stable operation record",10);f.failQuery=2;
            JSONObject reply=f.execute(cap,f.plan("stable").put("queries",new JSONArray().put("record")));
            assertEquals("partial",reply.getString("status"));assertEquals(1,reply.getJSONArray("entries").length());assertEquals(ref,SourceRef.fromJson(reply.getJSONArray("entries").getJSONObject(0).getJSONObject("sourceRef")));
            assertTrue(reply.getJSONArray("gaps").toString().contains("original_source_io_unavailable"));assertEquals(2,reply.getJSONObject("queryPlan").getInt("producerCalls"));
            assertEquals(1,reply.getJSONObject("queryPlan").getInt("callsWithIncompleteCounters"));assertFalse(reply.toString().contains("synthetic original store interruption"));
        }
    }

    @Test public void planCannotGuessOwnersRawIdsOrReuseSinglePageCursorAndHasNoAdditionalSideEffects()throws Exception{
        try(Fixture f=new Fixture();ConversationHistory.Capability cap=f.capability()){
            assertThrows(IllegalArgumentException.class,()->f.execute(cap,f.plan("query").put("ownerId","other")));
            assertThrows(IllegalArgumentException.class,()->f.execute(cap,f.plan("query").put("conversationId",UUID.randomUUID().toString())));
            assertThrows(SecurityException.class,()->f.execute(cap,f.plan("query").put("selectionId",UUID.randomUUID().toString())));
            assertThrows(IllegalArgumentException.class,()->f.execute(cap,f.plan("query").put("cursor",UUID.randomUUID().toString())));
            assertThrows(IllegalArgumentException.class,()->f.execute(cap,f.plan("query").put("follow","operations")));
            assertThrows(IllegalArgumentException.class,()->f.execute(cap,f.plan("query").put("queries",new JSONArray().put("one").put("two").put("three").put("four"))));
            assertThrows(IllegalArgumentException.class,()->f.execute(cap,f.plan("query").put("readTop",3).put("maxCalls",3)));
            assertEquals(0,f.queryCalls);assertEquals(0,f.readCalls);assertFalse(cap.sideEffecting(ConversationHistory.SEARCH));assertFalse(cap.sideEffecting(ConversationHistory.READ));
        }
    }
}
