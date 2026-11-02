package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.CompactionJournal;
import dev.pocketengine.runtime.context.CompactionPlan;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.extensions.ExtensionHost;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Real original SQLite/Runner/Extension surface and Assistant retained-history authorities.
 * Controlled HTTP/native SSH data are not a live model, SSH server, UI or device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RawReaderRetirementNativeTest {
    private static final class NativeSsh implements EngineModule {
        final JSONObject profile;int calls;
        NativeSsh()throws Exception{profile=new JSONObject().put("id","original-native-profile").put("bindingHash","b".repeat(64)).put("hostFingerprint","SHA256:fixture-approved").put("username","owner").put("hostKeyTrusted",true).put("credentialsConfigured",true);}
        public String id(){return "ssh";}
        public JSONObject snapshot()throws Exception{return new JSONObject().put("profiles",new JSONArray().put(profile));}
        public JSONObject handle(String name,JSONObject input){throw new AssertionError("not the bound native entry");}
        JSONObject invoke(String name,JSONObject input,RunControl original)throws Exception{original.check();calls++;return new JSONObject().put("status","completed").put("content","actual native safe returned text").put("outputComplete",true).put("outputTruncated",false);}
    }
    private static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final FeatureHost features;final AssistantModule assistant;
        final RunControl control=new RunControl();final String conversation=UUID.randomUUID().toString(),message=UUID.randomUUID().toString(),id,run;final int epoch;
        final NativeSsh ssh=new NativeSsh();final TaskStoreMemoryReader.RawBinding binding;final ToolOutputCapture.Factory factory;
        final TaskStoreMemoryReader.RawCapability raw;final ConversationHistory.Capability history;final ExtensionHost extensions;final CapabilityPort port;
        Fixture()throws Exception{
            context.deleteDatabase("pocket-world.db");tasks=new TaskStore(context);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            ConversationRepository repo=tasks.conversationRepository();repo.initializeEmpty();repo.transaction(()->{
                repo.saveConversation(new JSONObject().put("conversationId",conversation).put("createdAt",1L).put("updatedAt",1L));
                repo.saveSource(new JSONObject().put("kind","message").put("sourceId",message).put("revision",1L).put("ownerId","owner").put("spaceId","private").put("conversationId",conversation).put("role","user").put("deleted",false));
                repo.saveMessage(conversation,new JSONObject().put("id",message).put("revision",1L).put("role","user").put("content","original retained owner statement").put("at",1L));return null;
            });
            JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("原生表面退役而非刷新旧来源",conversation).getString("id"),"capabilities",12);id=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);
            tasks.authorizeTools(id,run,new JSONArray().put("ssh_read_file").put(TaskStoreMemoryReader.RAW_QUERY).put(TaskStoreMemoryReader.RAW_READ).put(ConversationHistory.SEARCH).put(ConversationHistory.READ));
            features=new FeatureHost(event->{},tasks,()->false);assistant=new AssistantModule(new FeatureContext(context,null,null,event->{}),null,null,tasks);
            binding=new TaskStoreMemoryReader.RawBinding(id,run,epoch,control,new MemoryHostFence(){public void check()throws Exception{control.check();tasks.checkExtensionRun(id,run,epoch,control);}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}},features::memoryContextRevision);
            binding.bindOutputSources(tasks.toolOutputSources());tasks.bindRawModelExposure(binding);factory=tasks.toolOutputFactory(id,run,epoch,control,text->text);
            var nativeBinding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,"");var adapter=new ModelCapabilityAdapters(nativeBinding,new TaskStoreCapabilityAuthority(tasks,nativeBinding,event->{}),null,null,new ModelCapabilityAdapters.SshBinding(ssh,ssh.profile,()->{},ssh::invoke),null,null,factory);
            raw=new TaskStoreMemoryReader.RawCapability(adapter,binding,new TaskStoreMemoryReader.RawAccess(){public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject o)throws Exception{return tasks.queryMemoryRaw(b,o);}public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject o)throws Exception{return tasks.readMemoryRaw(b,o);}});
            ConversationHistory.Source source=new ConversationHistory.Source(){public ConversationHistory.Gate open(String cid)throws Exception{return assistant.openHistoryConversation(cid);}public JSONObject query(ConversationHistory.Gate gate,JSONObject o,RunControl c)throws Exception{return assistant.queryRetainedHistory(gate,o,c);}public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> expected)throws Exception{return assistant.inspectHistorySources(gate,expected);}public JSONObject read(ConversationHistory.Gate gate,SourceRef expected,int offset,int max,RunControl c)throws Exception{return assistant.readHistoryMessage(gate,expected,offset,max,c);}};
            history=new ConversationHistory.Capability(raw,new ConversationHistory.HistoryBinding(binding,conversation,source),new ConversationHistory.Tasks(){public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,String kind,JSONObject o)throws Exception{return tasks.queryConversationHistory(b,selected,kind,o);}public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,int offset,int max)throws Exception{return tasks.readConversationHistoryOperation(b,selected,offset,max);}public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> refs)throws Exception{tasks.requireConversationHistorySources(b,refs);}});
            extensions=ExtensionHost.disabled(tasks);port=new ToolOutputSourcePort(extensions.bind(history,new ExtensionRuntime.Binding(id,run,epoch),control),factory,control);
        }
        RunJournal journal(){return tasks.journal(id,run,epoch,control);}
        JSONObject marker(String nativeRun)throws Exception{try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"run.raw_history_exposure."+nativeRun})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
        void forgetUnrelated()throws Exception{MemoryBook other=new MemoryBook(new java.io.File(context.getFilesDir(),"unrelated-formal-"+UUID.randomUUID()+".json"));JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private"),record=other.save(scope,new JSONObject().put("text","unrelated formal memory never prepared by this task"),1);other.forgetReviewed(scope,record.getString("id"),record.getLong("revision"),gone->{features.clearWorkingMemory(scope);tasks.invalidateMemoryContexts(MemoryBook.Scope.from(scope),gone);});}
        void enterSurface()throws Exception{port.beginRun("原native表面",control);port.prepareTurn("原native表面",0,0,control);}
        public void close(){port.close();extensions.close();assistant.close();features.close();db.close();}
    }
    @Test public void realRunnerDiscardsInflightAnswerThenRetiresOnlyUnusedReadersAndContinuesSsh()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger posts=new AtomicInteger();AtomicReference<Throwable> handlerFailure=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/chat/completions",exchange->{try{
                int n=posts.incrementAndGet();String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if(n==2){assertTrue(request.contains("actual native safe returned text"));f.forgetUnrelated();}
                if(n==3){assertFalse(request.contains("\"name\":\"memory_raw_query\""));assertFalse(request.contains("\"name\":\"memory_history_search\""));}
                JSONObject answer=n==1?new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-native-ssh").put("type","function").put("function",new JSONObject().put("name","ssh_read_file").put("arguments","{\"path\":\"fixture.txt\"}")))):new JSONObject().put("role","assistant").put("content",n==2?"STALE_ANSWER_MUST_NOT_BE_PUBLISHED":"fresh native surface continued");
                byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",n==1?"tool_calls":"stop").put("message",answer))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Throwable failure){handlerFailure.set(failure);}finally{exchange.close();}});server.start();
            try{
                ModelConfig selected=config(server);ContextBudgetPolicy.Source budgets=c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(131072,2048,1024,262144,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
                var journal=f.journal().withCompaction(f.tasks.compactionJournal(f.id,f.run,f.epoch,f.control,f.binding,()->selected));
                var outcome=new AgentRunner(null,f.port,budgets).run(f.id,"只读原SSH后继续，不涉及这条正式记忆",selected,f.control,event->{},journal);
                if(handlerFailure.get()!=null)throw new AssertionError(handlerFailure.get());
                assertEquals("needs_confirmation",outcome.status);assertEquals(3,posts.get());assertEquals(1,f.ssh.calls);assertFalse(outcome.summary.contains("STALE_ANSWER"));assertTrue(f.binding.readerRetired());assertEquals(0,f.binding.sourceRevision);assertEquals(1,f.features.memoryContextRevision());assertTrue(f.marker(f.run).getBoolean("trackingComplete"));assertFalse(f.marker(f.run).getBoolean("exposed"));assertFalse(f.control.isCancelled());
                assertFalse(f.port.supports(TaskStoreMemoryReader.RAW_QUERY));assertFalse(f.port.supports(ConversationHistory.SEARCH));assertThrows(SecurityException.class,f.binding::check);
                assertThrows(SecurityException.class,()->f.raw.execute(TaskStoreMemoryReader.RAW_QUERY,new JSONObject().put("section","outputs").put("query",""),f.control));
                assertEquals(1,f.tasks.readMemoryTaskTrace(MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","private")),f.id,f.run,new JSONObject().put("limit",20)).getJSONObject("counts").getInt("operations"));
            }finally{server.stop(0);}
        }
    }
    @Test public void rawQueryTextMarksPreparedBeforeReturnAndNeverRetiresAfterForget()throws Exception{
        try(Fixture f=new Fixture()){
            String operation=f.journal().beginOperation("ssh_read_file",new JSONObject().put("path","fixture.txt"));JSONObject actual=f.raw.execute("ssh_read_file",new JSONObject().put("path","fixture.txt").put("_hostOperationId",operation),f.control);f.journal().finishOperation(operation,actual);assertFalse(f.marker(f.run).getBoolean("exposed"));
            JSONObject page=f.raw.execute(TaskStoreMemoryReader.RAW_QUERY,new JSONObject().put("section","outputs").put("query","native").put("limit",10).put("maxChars",4000),f.control);assertTrue(page.getJSONArray("entries").length()>0);assertTrue(f.marker(f.run).getBoolean("exposed"));f.forgetUnrelated();
            assertThrows(SecurityException.class,()->f.raw.prepareTurnSurface(f.control));assertFalse(f.binding.readerRetired());assertThrows(SecurityException.class,()->f.tasks.queryMemoryRaw(f.binding,new JSONObject().put("section","outputs").put("query","")));
        }
    }
    @Test public void assistantHistoryTextMarksPreparedAndCannotBeRetiredOrReusedAfterForget()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject page=f.history.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","messages").put("query","retained").put("limit",10).put("maxChars",4000),f.control);assertEquals(1,page.getJSONArray("entries").length());assertTrue(f.marker(f.run).getBoolean("exposed"));String selection=page.getJSONArray("entries").getJSONObject(0).getString("selectionId");f.forgetUnrelated();
            assertThrows(SecurityException.class,()->f.history.prepareTurnSurface(f.control));assertFalse(f.binding.readerRetired());assertThrows(SecurityException.class,()->f.history.execute(ConversationHistory.READ,new JSONObject().put("selectionId",selection).put("offset",0).put("maxChars",64),f.control));
        }
    }
    @Test public void actualH4FreezeMarksOriginalPrefixAndCannotRetireItsStaleReader()throws Exception{
        try(Fixture f=new Fixture()){
            JSONArray original=settledHistory();List<Integer> indexes=new ArrayList<>();for(int i=0;i<original.length();i++)indexes.add(i);ModelConfig selected=new ModelConfig("https://fixture.invalid/v1","key","fixture",false,12,10,"openai_chat",1000);
            f.journal().checkpoint(original,0,new JSONObject());CompactionPlan plan=CompactionPlan.choose(original,new JSONArray(original.toString()),indexes,null,selected,new ContextWindow.Budget(70000,1000,1000,100000,64,0));CompactionJournal host=f.tasks.compactionJournal(f.id,f.run,f.epoch,f.control,f.binding,()->selected);CompactionJournal.FrozenPrefix frozen=host.freeze(plan,f.control);
            assertTrue(frozen.sources().length()>0);assertTrue(f.marker(f.run).getBoolean("exposed"));f.forgetUnrelated();assertThrows(SecurityException.class,()->f.raw.prepareTurnSurface(f.control));assertThrows(SecurityException.class,()->host.current(frozen,f.control));assertFalse(f.binding.readerRetired());assertEquals(original.length(),f.journal().restore().getJSONArray("messages").length());
        }
    }
    @Test public void restoredMissingMarkerIsUnknownAndNewAttemptDoesNotResetItToEmpty()throws Exception{
        try(Fixture f=new Fixture()){
            f.db.delete("meta","key=?",new String[]{"run.raw_history_exposure."+f.run});f.tasks.finish(f.id,"awaiting_user","native legacy fixture stop",new JSONObject());JSONObject next=f.tasks.startAttempt(f.id,"capabilities",4);String run=next.getString("runId");JSONObject inherited=f.marker(run);assertFalse(inherited.getBoolean("trackingComplete"));assertTrue(inherited.isNull("exposed"));assertEquals(2,inherited.length()); // no reconstructed evidence
            RunControl control=new RunControl();int epoch=next.getInt("coordinatorEpoch");control.bindHostRun(f.id,run,epoch);var binding=new TaskStoreMemoryReader.RawBinding(f.id,run,epoch,control,new MemoryHostFence(){public void check()throws Exception{control.check();f.tasks.checkExtensionRun(f.id,run,epoch,control);}public JSONObject admit(Admission action)throws Exception{synchronized(f.tasks){return action.run();}}},f.features::memoryContextRevision);binding.bindOutputSources(f.tasks.toolOutputSources());f.tasks.bindRawModelExposure(binding);f.forgetUnrelated();assertThrows(SecurityException.class,binding::prepareNativeTurn);assertFalse(binding.readerRetired());assertFalse(control.isCancelled());
        }
    }
    @Test public void failedDurableExposureWriteReturnsNoHistoryBody()throws Exception{
        try(Fixture f=new Fixture()){
            f.db.execSQL("CREATE TEMP TRIGGER fixture_exposure_write_failure BEFORE UPDATE ON meta WHEN NEW.key LIKE 'run.raw_history_exposure.%' BEGIN SELECT RAISE(ABORT,'controlled exposure write failure'); END");assertThrows(Exception.class,()->f.history.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","messages").put("query","retained"),f.control));assertFalse(f.marker(f.run).getBoolean("exposed"));f.db.execSQL("DROP TRIGGER fixture_exposure_write_failure");
        }
    }
    @Test public void inflightOldSurfaceMustBeAbandonedBeforeReaderCanRetire()throws Exception{
        try(Fixture f=new Fixture()){
            f.enterSurface();f.port.beforeModelRequest("native-inflight",new JSONArray(),f.control);f.forgetUnrelated();assertFalse(f.port.responseCurrent(f.control));assertThrows(SecurityException.class,()->f.port.prepareTurnSurface(f.control));assertFalse(f.binding.readerRetired());f.port.modelRequestAbandoned("native-inflight");f.port.prepareTurn("fresh",1,0,f.control);assertTrue(f.binding.readerRetired());assertFalse(f.port.supports(ConversationHistory.READ));
        }
    }
    private static ModelConfig config(HttpServer server){return new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-key","fixture",false,12,10,"openai_chat",500);}
    private static JSONArray settledHistory()throws Exception{JSONArray rows=new JSONArray().put(new JSONObject().put("role","system").put("content","native invariant")).put(new JSONObject().put("role","user").put("content","original owner goal"));for(int i=0;i<5;i++)rows.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-"+i).put("type","function").put("function",new JSONObject().put("name","read_file").put("arguments","{}"))))).put(new JSONObject().put("role","tool").put("tool_call_id","call-"+i).put("content",new JSONObject().put("ok",true).put("file","source-"+i+".java").put("text","x".repeat(9000)).toString()));return rows.put(new JSONObject().put("role","user").put("content","latest owner correction")).put(new JSONObject().put("role","assistant").put("content","original current tail"));}
}
