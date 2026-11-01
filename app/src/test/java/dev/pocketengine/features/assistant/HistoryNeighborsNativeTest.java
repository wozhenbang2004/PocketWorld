package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.CapabilitySnapshot;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.memory.ConversationHistory;
import dev.pocketengine.features.memory.MemoryHostFence;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import dev.pocketengine.features.memory.TaskStoreMemoryReader;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original SQLite + native Assistant/provider. Controls are synthetic; no live model/device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class HistoryNeighborsNativeTest {
    private static SourceRef ref(String id,long revision){return new SourceRef("message",id,revision,"owner","private");}
    private static JSONObject budgets(int limit,int chars)throws Exception{return new JSONObject().put("limit",limit).put("maxChars",chars);}
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final File file=new File(android.getFilesDir(),"neighbors-"+UUID.randomUUID()+".json");
        final TaskStore tasks;final AssistantModule assistant;final ConversationRepository repo;final SQLiteDatabase db;
        Fixture()throws Exception{this(null);}
        Fixture(byte[] legacy)throws Exception{
            android.deleteDatabase("pocket-world.db");if(legacy!=null)Files.write(file.toPath(),legacy);tasks=new TaskStore(android);
            assistant=new AssistantModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{}),null,null,new AtomicFile(file),tasks);repo=tasks.conversationRepository();
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
        }
        void seed(String conversation,String... bodies)throws Exception{repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","前因后果").put("messages",new JSONArray()));for(int i=0;i<bodies.length;i++)save(conversation,conversation+"-"+i,1,bodies[i]);return null;});}
        void save(String conversation,String id,long revision,String body)throws Exception{repo.saveSource(ref(id,revision).toJson().put("conversationId",conversation).put("role","user").put("deleted",false));repo.saveMessage(conversation,new JSONObject().put("id",id).put("revision",revision).put("role","user").put("content",body));}
        JSONObject hit(String conversation,String query)throws Exception{String cursor="";for(int page=0;page<4;page++){JSONObject result=assistant.queryRetainedHistory(assistant.openHistoryConversation(conversation),new JSONObject().put("query",query).put("cursor",cursor).put("limit",20).put("maxChars",16000),new RunControl());if(result.getJSONArray("entries").length()>0)return result.getJSONArray("entries").getJSONObject(0);cursor=result.getString("nextCursor");if(cursor.isEmpty())break;}throw new AssertionError("fixture source was not discovered through bounded native pages");}
        JSONObject neighbors(String conversation,JSONObject anchor,String direction,int limit,int chars,RunControl control)throws Exception{return assistant.historyNeighbors(assistant.openHistoryConversation(conversation),SourceRef.fromJson(anchor.getJSONObject("sourceRef")),anchor.getString("bodyFingerprint"),direction,budgets(limit,chars),control);}
        ConversationHistory.Source source(){return new ConversationHistory.Source(){
            public ConversationHistory.Gate open(String id)throws Exception{return assistant.openHistoryConversation(id);}
            public JSONObject query(ConversationHistory.Gate gate,JSONObject options,RunControl original)throws Exception{return assistant.queryRetainedHistory(gate,options,original);}
            public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> expected)throws Exception{return assistant.inspectHistorySources(gate,expected);}
            public JSONObject read(ConversationHistory.Gate gate,SourceRef expected,int offset,int chars,RunControl original)throws Exception{return assistant.readHistoryMessage(gate,expected,offset,chars,original);}
            public JSONObject neighbors(ConversationHistory.Gate gate,SourceRef expected,String fingerprint,String direction,JSONObject options,RunControl original)throws Exception{return assistant.historyNeighbors(gate,expected,fingerprint,direction,options,original);}
        };}
        public void close(){assistant.close();db.close();}
    }
    private static ConversationHistory.Tasks noHistoricalTasks(){return new ConversationHistory.Tasks(){public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator s,String k,JSONObject o){throw new AssertionError("neighbor must not expand task/run scope");}public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator s,int o,int n){throw new AssertionError();}public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> refs){assertTrue(refs.isEmpty());}};}
    private static CapabilityPort existing(){return new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl original){throw new AssertionError();}};}
    private static TaskStoreMemoryReader.RawBinding binding(RunControl control)throws Exception{control.bindHostRun("caller","current-run",1);return new TaskStoreMemoryReader.RawBinding("caller","current-run",1,control,new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}},()->0);}
    private static CapabilityPort rawCapability(TaskStoreMemoryReader.RawBinding binding)throws Exception{return new TaskStoreMemoryReader.RawCapability(existing(),binding,new TaskStoreMemoryReader.RawAccess(){public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject options){throw new AssertionError();}public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject citation){throw new AssertionError();}});}

    @Test public void discoveredHitExpandsBeforeAndAfterWithoutRequiringTheCauseToMatchKeyword()throws Exception{
        String own=HistoryNeighborsNativeTest.class.getResource("/"+HistoryNeighborsNativeTest.class.getName().replace('.','/')+".class").toString();String root=own.substring(0,own.indexOf("dev/pocketengine/"));for(Class<?> source:List.of(TaskStore.class,AssistantModule.class,ConversationRepository.class,ConversationHistory.class))assertTrue(source.getName()+" is not this exact fresh producer",source.getResource("/"+source.getName().replace('.','/')+".class").toString().startsWith(root));
        try(Fixture f=new Fixture()){f.seed("c","原决定：不要跨组读取","关键词 NEEDLE","因此改为同会话核原件");f.seed("foreign","不能跨会话读取");JSONObject anchor=f.hit("c","NEEDLE");JSONObject before=f.neighbors("c",anchor,"before",10,4000,new RunControl()),after=f.neighbors("c",anchor,"after",10,4000,new RunControl());
            assertEquals("原决定：不要跨组读取",before.getJSONArray("entries").getJSONObject(0).getString("body"));assertEquals("因此改为同会话核原件",after.getJSONArray("entries").getJSONObject(0).getString("body"));assertFalse(before.toString().contains("不能跨会话读取"));assertEquals("nearest_first",before.getJSONObject("adjacency").getString("order"));assertFalse(before.getJSONObject("adjacency").getBoolean("parentChildAncestry"));assertFalse(before.getBoolean("completeOriginalTrace"));assertFalse(before.getBoolean("executionAuthorized"));
            JSONObject row=before.getJSONArray("entries").getJSONObject(0);assertEquals(1L,SourceRef.fromJson(row.getJSONObject("sourceRef")).revision);assertEquals("c",row.getJSONObject("sourceJump").getString("conversationId"));assertEquals(row.getString("body").length(),row.getJSONObject("sourceJump").getInt("chars"));
        }
    }
    @Test public void actualNativeProviderUsesOpaqueSelectionsAndReceiptsRemainExactSourceJumps()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c","真实前因","唯一锚点");RunControl control=new RunControl();TaskStoreMemoryReader.RawBinding raw=binding(control);ConversationHistory.HistoryBinding history=new ConversationHistory.HistoryBinding(raw,"c",f.source());
            try(ConversationHistory.Capability capability=new ConversationHistory.Capability(rawCapability(raw),history,noHistoricalTasks())){
                JSONObject search=capability.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","messages").put("query","唯一锚点"),control);String selection=search.getJSONArray("entries").getJSONObject(0).getString("selectionId");
                assertThrows(SecurityException.class,()->capability.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","neighbors").put("selectionId","forged-source").put("direction","before"),control));
                JSONObject result=capability.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","neighbors").put("selectionId",selection).put("direction","before"),control);assertEquals("真实前因",result.getJSONArray("entries").getJSONObject(0).getString("body"));assertEquals(1,result.getJSONArray("messageSourceRefs").length());assertEquals(36,result.getJSONArray("entries").getJSONObject(0).getString("selectionId").length());
                JSONObject task=new JSONObject().put("id","caller").put("kind","assistant").put("actorId","owner").put("spaceId","private").put("conversationId","c").put("runId","current-run").put("events",new JSONArray().put(new JSONObject().put("type","tool").put("tool",ConversationHistory.SEARCH).put("receipt",result)));JSONArray citations=TaskMemoryTrace.historyCitationJumps(task);assertEquals(1,citations.length());ConversationHistory.checkedCitation(citations.getJSONObject(0));
                control.pause();control.resume();CapabilitySnapshot fresh=new CapabilitySnapshot("caller","current-run",1,control,Set.of(ConversationHistory.SEARCH,ConversationHistory.READ),()->{});capability.admitCapabilities(fresh,control);
                assertThrows(SecurityException.class,()->capability.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","neighbors").put("selectionId",selection).put("direction","before"),control));
            }
        }
    }
    @Test public void anchorScopeRevisionAndFingerprintCannotBeSubstitutedOrRevived()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c","可读前因","原锚点");f.seed("other","其他内容");JSONObject anchor=f.hit("c","原锚点");JSONObject wrong=new JSONObject(anchor.toString()).put("bodyFingerprint","sha256:"+"0".repeat(64));assertEquals("source_changed",f.neighbors("c",wrong,"before",10,4000,new RunControl()).getString("status"));assertEquals(0,f.neighbors("other",anchor,"before",10,4000,new RunControl()).getJSONArray("entries").length());
            f.repo.transaction(()->{f.save("c","c-1",2,"修订后的不同锚点");return null;});JSONObject changed=f.neighbors("c",anchor,"before",10,4000,new RunControl());assertEquals("source_changed",changed.getString("status"));assertFalse(changed.toString().contains("可读前因"));assertFalse(changed.toString().contains("修订后的不同锚点"));
            SourceRef foreign=new SourceRef("message","c-1",2,"another-owner","private");assertThrows(SecurityException.class,()->f.repo.neighbors("c",foreign,anchor.getString("bodyFingerprint"),"before",budgets(10,4000),new RunControl()));
        }
    }
    @Test public void deletedAndForgottenNeighborBodiesAreGapsButOwnerOriginalRemainsReadable()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c","仍有效原文","已删除正文不可再注入","遗忘正文不可再注入","锚点");JSONObject anchor=f.hit("c","锚点");f.assistant.handle("deleteMessage",new JSONObject().put("conversationId","c").put("messageId","c-1").put("revision",1));f.assistant.retireMemoryContexts(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(ref("c-2",1).toJson()))),"owner_forget");
            JSONObject result=f.neighbors("c",anchor,"before",10,4000,new RunControl());assertEquals(1,result.getJSONArray("entries").length());assertEquals("仍有效原文",result.getJSONArray("entries").getJSONObject(0).getString("body"));assertEquals(2,result.getJSONArray("gaps").length());assertEquals("retired",result.getJSONArray("gaps").getJSONObject(0).getString("status"));assertEquals("deleted",result.getJSONArray("gaps").getJSONObject(1).getString("status"));assertFalse(result.toString().contains("正文不可再注入"));assertTrue(f.assistant.readOriginalConversation("c","",80).toString().contains("遗忘正文不可再注入"));
            f.assistant.retireMemoryContexts(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(ref("c-3",1).toJson()))),"owner_forget");assertEquals("retired",f.neighbors("c",anchor,"before",10,4000,new RunControl()).getString("status"));
        }
    }
    @Test public void normalTailAppendDoesNotInvalidateAnUnchangedDiscoveredAnchor()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c","旧原因","不变锚点");JSONObject anchor=f.hit("c","不变锚点");f.repo.transaction(()->{f.save("c","new-tail",1,"合法新增尾部");return null;});JSONObject result=f.neighbors("c",anchor,"after",10,4000,new RunControl());assertEquals("current",result.getString("status"));assertEquals("合法新增尾部",result.getJSONArray("entries").getJSONObject(0).getString("body"));assertEquals("旧原因",f.neighbors("c",anchor,"before",10,4000,new RunControl()).getJSONArray("entries").getJSONObject(0).getString("body"));}
    }
    @Test public void bodyAndOutputLimitsPreflightOversizeAndReportRowCoverageRatherThanFullTrace()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c","🙂".repeat(200),"锚点");JSONObject anchor=f.hit("c","锚点");JSONObject bounded=f.neighbors("c",anchor,"before",1,65,new RunControl());String body=bounded.getJSONArray("entries").getJSONObject(0).getString("body");assertEquals(64,body.length());assertEquals(64,bounded.getInt("charsReturned"));assertTrue(bounded.getJSONArray("entries").getJSONObject(0).getJSONObject("readRange").getBoolean("hasMore"));assertEquals(800,bounded.getJSONObject("pageCoverage").getInt("scannedUtf8Bytes"));assertFalse(bounded.getJSONObject("pageCoverage").getBoolean("allAdjacentBodiesRead"));
            f.repo.transaction(()->{f.save("c","huge",1,"字".repeat(400000));f.save("c","after-huge",1,"后锚点");return null;});JSONObject hugeAnchor=f.hit("c","后锚点"),limited=f.neighbors("c",hugeAnchor,"before",1,4000,new RunControl());assertEquals("read_limited",limited.getJSONArray("gaps").getJSONObject(0).getString("status"));assertTrue(limited.getJSONObject("pageCoverage").getLong("scannedUtf8Bytes")<10000);assertFalse(limited.toString().contains("字字字字字"));
            f.repo.transaction(()->{for(int i=0;i<101;i++){String id="gap-"+i;f.save("c",id,1,"已撤回");f.repo.retire(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(ref(id,1).toJson()))),"owner_forget");}f.save("c","row-anchor",1,"行预算锚点");return null;});JSONObject rows=f.neighbors("c",f.hit("c","行预算锚点"),"before",20,16000,new RunControl());assertEquals(100,rows.getJSONObject("pageCoverage").getInt("scannedRows"));assertEquals("source_row_budget",rows.getString("reason"));assertTrue(rows.getBoolean("hasMore"));assertEquals(0,rows.getJSONArray("entries").length());
        }
    }
    @Test public void pauseResumeOrCancellationBeforeReturnCannotPublishLateNeighborhood()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c","不应迟回的前因","锚点");JSONObject anchor=f.hit("c","锚点");RunControl cancelled=new RunControl();AtomicInteger cancelTicks=new AtomicInteger();ConversationRepository late=new ConversationRepository(f.db,f.tasks,()->{if(cancelTicks.incrementAndGet()>=3)cancelled.cancel();return 0L;});assertThrows(InterruptedException.class,()->late.neighbors("c",ref("c-1",1),anchor.getString("bodyFingerprint"),"before",budgets(10,4000),cancelled));
            RunControl resumed=new RunControl();AtomicInteger pauseTicks=new AtomicInteger();ConversationRepository paused=new ConversationRepository(f.db,f.tasks,()->{if(pauseTicks.incrementAndGet()==3){resumed.pause();resumed.resume();}return 0L;});assertThrows(InterruptedException.class,()->paused.neighbors("c",ref("c-1",1),anchor.getString("bodyFingerprint"),"before",budgets(10,4000),resumed));
        }
    }
    @Test public void importedUnknownIdentityProducesMetadataGapAndOldProviderIsExplicitlyUnavailable()throws Exception{
        byte[] legacy="[{\"conversationId\":\"c\",\"messages\":[{\"role\":\"user\",\"content\":\"旧未知正文不能提升\"}]}]".getBytes(StandardCharsets.UTF_8);
        try(Fixture f=new Fixture(legacy)){JSONObject review=f.assistant.reviewOriginalHistoryMigration();f.assistant.activateOriginalHistoryMigration(review.getString("migrationTaskId"),review.getString("sourceSha256"),true);f.repo.transaction(()->{f.save("c","new-anchor",1,"新原生锚点");return null;});JSONObject result=f.neighbors("c",f.hit("c","新原生锚点"),"before",10,4000,new RunControl());assertEquals(0,result.getJSONArray("entries").length());assertEquals("provenance_unavailable",result.getJSONArray("gaps").getJSONObject(0).getString("status"));assertFalse(result.toString().contains("旧未知正文不能提升"));assertTrue(f.assistant.readOriginalConversation("c","",80).toString().contains("旧未知正文不能提升"));
            ConversationHistory.Source old=new ConversationHistory.Source(){public ConversationHistory.Gate open(String id){return ()->true;}public JSONObject query(ConversationHistory.Gate g,JSONObject o,RunControl c){throw new AssertionError();}public JSONArray inspect(ConversationHistory.Gate g,List<SourceRef> e){throw new AssertionError();}public JSONObject read(ConversationHistory.Gate g,SourceRef e,int o,int n,RunControl c){throw new AssertionError();}};JSONObject absent=old.neighbors(()->true,ref("new-anchor",1),"sha256:"+"0".repeat(64),"before",budgets(10,4000),new RunControl());assertEquals("unavailable",absent.getString("status"));assertEquals(0,absent.getJSONArray("entries").length());
            for(JSONObject invalid:List.of(new JSONObject().put("kind","neighbors").put("selectionId","s").put("direction","before").put("query","keyword"),new JSONObject().put("kind","neighbors").put("selectionId","s").put("direction","before").put("createdAfter",0),new JSONObject().put("kind","messages").put("direction","before")))assertThrows(IllegalArgumentException.class,()->new ConversationHistory.Request(invalid));
        }
    }
}
