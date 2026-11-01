package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original native SQL/Assistant/History tool surface. No provider, account, or historical control. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class CrossConversationHistoryNativeTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final String current=id(),old=id(),message=id();
        TaskStore tasks;SQLiteDatabase db;AssistantModule assistant;RunControl original,sourceControl;String caller,run,sourceTask,sourceRun;int epoch;
        ConversationHistory.Capability port;
        Fixture()throws Exception{android.deleteDatabase("pocket-world.db");open();var repo=tasks.conversationRepository();repo.transaction(()->{
            for(String cid:List.of(current,old))repo.saveConversation(new JSONObject().put("conversationId",cid).put("title",cid.equals(old)?"HIDDEN_OLD_TITLE":"current").put("updatedAt",1));
            SourceRef ref=new SourceRef("message",message,1,"owner","private");repo.saveSource(ref.toJson().put("conversationId",old).put("role","user").put("deleted",false));
            repo.saveMessage(old,new JSONObject().put("id",message).put("revision",1).put("role","user").put("content","OLD_PRIVATE_ORIGINAL🙂").put("at",1));return null;
        });JSONObject source=tasks.startAttempt(tasks.createCapabilityTask("原旧会话来源",old).getString("id"),"capabilities",8);
            sourceTask=source.getString("id");sourceRun=source.getString("runId");sourceControl=new RunControl();sourceControl.bindHostRun(sourceTask,sourceRun,source.getInt("coordinatorEpoch"));
            tasks.authorizeTools(sourceTask,sourceRun,new JSONArray().put("ssh_read_file"));String operation=tasks.journal(sourceTask,sourceRun,source.getInt("coordinatorEpoch"),sourceControl).beginOperation("ssh_read_file",new JSONObject().put("path","old.txt"));
            JSONObject capture=tasks.toolOutputFactory(sourceTask,sourceRun,source.getInt("coordinatorEpoch"),sourceControl,x->x).begin("ssh_read_file",operation,ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,sourceControl).record(new JSONObject().put("content","A".repeat(600000)+" OLD_CROSS_TAIL🙂"),false).metadata();
            tasks.journal(sourceTask,sourceRun,source.getInt("coordinatorEpoch"),sourceControl).finishOperation(operation,new JSONObject().put("status","completed").put("outputCapture",capture));sourceControl.cancel();tasks.status(sourceTask,"cancelled","原run已停止");
            newCaller();
        }
        void open()throws Exception{tasks=new TaskStore(android);var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);db=(SQLiteDatabase)f.get(tasks);assistant=new AssistantModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{}),null,null,tasks);}
        void newCaller()throws Exception{if(port!=null)port.close();JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("原当前会话只读回查",current).getString("id"),"capabilities",16);caller=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original=new RunControl();original.bindHostRun(caller,run,epoch);tasks.authorizeTools(caller,run,new JSONArray().put(ConversationHistory.SEARCH).put(ConversationHistory.READ));mount();}
        void mount()throws Exception{
            var raw=new TaskStoreMemoryReader.RawBinding(caller,run,epoch,original,new MemoryHostFence(){public void check()throws Exception{tasks.checkExtensionRun(caller,run,epoch,original);}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}},()->0);raw.bindOutputSources(tasks.toolOutputSources());tasks.bindRawModelExposure(raw);
            var binding=new ConversationHistory.HistoryBinding(raw,current,new ConversationHistory.Source(){
                public ConversationHistory.Gate open(String cid)throws Exception{return assistant.openHistoryConversation(cid);}
                public ConversationHistory.ReadScope captureReadScope(String cid)throws Exception{return assistant.captureHistoryReadScope(cid);}
                public String conversationFor(SourceRef ref)throws Exception{return assistant.historyConversationFor(ref);}
                public JSONObject conversations(ConversationHistory.ReadScope scope,JSONObject opts,RunControl c)throws Exception{return assistant.queryHistoryConversations(scope,opts,c);}
                public JSONObject query(ConversationHistory.Gate gate,JSONObject opts,RunControl c)throws Exception{return assistant.queryRetainedHistory(gate,opts,c);}
                public JSONArray inspect(ConversationHistory.Gate gate,List<SourceRef> refs)throws Exception{return assistant.inspectHistorySources(gate,refs);}
                public JSONObject read(ConversationHistory.Gate gate,SourceRef ref,int offset,int chars,RunControl c)throws Exception{return assistant.readHistoryMessage(gate,ref,offset,chars,c);}
            });
            CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError();}};
            port=new ConversationHistory.Capability(existing,binding,new ConversationHistory.Tasks(){public JSONObject query(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,String kind,JSONObject options)throws Exception{return tasks.queryConversationHistory(b,selected,kind,options);}public JSONObject read(ConversationHistory.HistoryBinding b,ConversationHistory.SourceLocator selected,int offset,int chars)throws Exception{return tasks.readConversationHistoryOperation(b,selected,offset,chars);}public void requireCurrent(ConversationHistory.HistoryBinding b,List<ConversationHistory.SourceLocator> refs)throws Exception{tasks.requireConversationHistorySources(b,refs);}});
        }
        JSONObject search(String kind,String selection)throws Exception{JSONObject args=new JSONObject().put("kind",kind).put("maxChars",16000);if(!selection.isEmpty())args.put("selectionId",selection);return port.execute(ConversationHistory.SEARCH,args,original);}
        JSONObject selectedConversation(String cid)throws Exception{JSONArray rows=search("conversations","").getJSONArray("entries");for(int i=0;i<rows.length();i++)if(cid.equals(rows.getJSONObject(i).optString("sourceConversationId")))return rows.getJSONObject(i);return null;}
        void enable()throws Exception{assertEquals("CURRENT",assistant.historyReadScopeSnapshot(current).getString("mode"));assistant.setHistoryReadScope(current,0,"OWNER_PRIVATE",true);port.close();mount();}
        public void close(){if(port!=null)port.close();assistant.close();db.close();}
    }
    @Test public void currentDefaultHasNoForeignDirectoryBodyOrRawModelIdentityOverride()throws Exception{
        try(Fixture f=new Fixture()){assertEquals(0,f.assistant.historyReadScopeSnapshot(f.current).getLong("revision"));JSONObject rows=f.search("conversations","");assertEquals(1,rows.getJSONArray("entries").length());assertFalse(rows.toString().contains("HIDDEN_OLD_TITLE"));assertFalse(rows.toString().contains("OLD_PRIVATE_ORIGINAL"));assertNull(f.selectedConversation(f.old));
            assertThrows(IllegalArgumentException.class,()->f.port.execute(ConversationHistory.SEARCH,new JSONObject().put("kind","messages").put("conversationId",f.old),f.original));
            assertThrows(SecurityException.class,()->f.port.execute(ConversationHistory.READ,new JSONObject().put("selectionId",f.old),f.original));
            assertThrows(SecurityException.class,()->f.assistant.setHistoryReadScope(f.current,0,"OWNER_PRIVATE",false));
        }
    }
    @Test public void explicitOwnerScopeUsesOpaqueConversationThenExactStoppedRunOutputWithoutRevivingControl()throws Exception{
        try(Fixture f=new Fixture()){f.enable();JSONObject conv=f.selectedConversation(f.old);assertNotNull(conv);assertFalse(conv.has("body"));assertNotEquals(f.old,conv.getString("selectionId"));
            JSONObject messages=f.search("messages",conv.getString("selectionId"));JSONObject msg=messages.getJSONArray("entries").getJSONObject(0);assertEquals(f.old,msg.getString("sourceConversationId"));assertTrue(msg.getString("body").contains("OLD_PRIVATE_ORIGINAL"));assertEquals(f.old,msg.getJSONObject("sourceJump").getString("conversationId"));assertEquals(f.current,messages.getString("conversationId"));
            JSONObject task=f.search("runs",conv.getString("selectionId")).getJSONArray("entries").getJSONObject(0);assertEquals(f.sourceTask,task.getString("sourceTaskId"));
            JSONObject run=f.search("runs",task.getString("selectionId")).getJSONArray("entries").getJSONObject(0);JSONObject output=f.search("outputs",run.getString("selectionId")).getJSONArray("entries").getJSONObject(0);assertFalse(output.has("body"));int total=output.getJSONObject("retainedRange").getInt("chars");
            JSONObject page=f.port.execute(ConversationHistory.READ,new JSONObject().put("selectionId",output.getString("selectionId")).put("offset",total-64).put("maxChars",64),f.original);assertTrue(page.getString("body").contains("OLD_CROSS_TAIL🙂"));assertEquals(f.old,page.getJSONArray("taskSourceRefs").getJSONObject(0).getString("sourceConversationId"));assertEquals(f.caller,page.getString("callerTaskId"));assertEquals(f.sourceTask,page.getString("taskId"));assertFalse(page.getBoolean("executionAuthorized"));assertTrue(f.sourceControl.isCancelled());assertFalse(f.original.isCancelled());
        }
    }
    @Test public void disablingRevokesInflightAndRetiresRestoredForeignBodiesButNotOwnerAudit()throws Exception{
        try(Fixture f=new Fixture()){f.enable();JSONObject conv=f.selectedConversation(f.old);JSONObject receipt=f.search("messages",conv.getString("selectionId"));JSONObject message=receipt.getJSONArray("entries").getJSONObject(0);
            JSONArray checkpoint=new JSONArray().put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","history-call").put("function",new JSONObject().put("name",ConversationHistory.SEARCH)))))
                .put(new JSONObject().put("role","tool").put("tool_call_id","history-call").put("content",receipt.toString()));
            assertTrue(f.assistant.projectMemoryHistory(checkpoint,java.util.Set.of(),f.current).toString().contains("OLD_PRIVATE_ORIGINAL"));
            f.tasks.event(f.caller,new JSONObject().put("type","tool").put("tool",ConversationHistory.SEARCH).put("receipt",receipt));
            JSONObject citation=TaskMemoryTrace.historyCitationJumps(f.tasks.get(f.caller,true)).getJSONObject(0);assertEquals(f.old,citation.getJSONObject("sourceJump").getString("conversationId"));assertNotNull(f.tasks.conversationHistoryCitation(citation));
            f.assistant.setHistoryReadScope(f.current,1,"CURRENT",false);assertThrows(SecurityException.class,()->f.port.execute(ConversationHistory.READ,new JSONObject().put("selectionId",message.getString("selectionId")),f.original));assertFalse(f.assistant.projectMemoryHistory(checkpoint,java.util.Set.of(),f.current).toString().contains("OLD_PRIVATE_ORIGINAL"));assertNotNull(f.tasks.conversationHistoryCitation(citation));
            assertThrows(SecurityException.class,()->f.assistant.setHistoryReadScope(f.current,1,"OWNER_PRIVATE",true));
        }
    }
    @Test public void scopeRestoresFromOriginalSqlAndUnknownConfigNeverWidens()throws Exception{
        try(Fixture f=new Fixture()){f.enable();f.port.close();f.port=null;f.assistant.close();f.db.close();f.open();assertEquals("OWNER_PRIVATE",f.assistant.historyReadScopeSnapshot(f.current).getString("mode"));assertEquals(1,f.assistant.historyReadScopeSnapshot(f.current).getLong("revision"));
            f.db.execSQL("UPDATE meta SET value=? WHERE key LIKE ?",new Object[]{"{\"mode\":\"OWNER_PRIVATE\"}","assistant.history_read_scope.%:"+f.current});assertThrows(Exception.class,()->f.assistant.captureHistoryReadScope(f.current));assertEquals("CURRENT",f.assistant.historyReadScopeSnapshot(f.old).getString("mode"));
        }
    }
}
