package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
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

/** Original TaskStore/FeatureHost/Assistant facade. Requires the actual application Java dependency closure. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationWorkbenchNativeJourneyTest {
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final File legacy=new File(android.getFilesDir(),"workbench-"+UUID.randomUUID()+".json");
        final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
        TaskStore tasks;AssistantModule assistant;FeatureHost host;
        Fixture()throws Exception{android.deleteDatabase("pocket-world.db");open();}
        void open()throws Exception{tasks=new TaskStore(android);assistant=new AssistantModule(context,null,null,new AtomicFile(legacy),tasks);host=new FeatureHost(event->{},tasks,()->false);host.register(assistant);}
        SQLiteDatabase db()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
        ConversationRepository repo(){return tasks.conversationRepository();}
        JSONObject call(String command,JSONObject input)throws Exception{return host.ownerConversationWorkbench(command,input);}
        void seed(String conversation,int messages)throws Exception{repo().transaction(()->{repo().saveConversation(new JSONObject().put("conversationId",conversation).put("title","原始🙂").put("createdAt",1).put("updatedAt",1));for(int i=1;i<=messages;i++){String id=conversation+"-"+i,role=i%2==0?"assistant":"user";repo().saveSource(new SourceRef("message",id,1,"owner","private").toJson().put("conversationId",conversation).put("role",role).put("deleted",false));repo().saveMessage(conversation,new JSONObject().put("id",id).put("revision",1).put("role",role).put("content","原文 "+i+"\n代码🙂").put("at",100+i));}return null;});}
        long count(String table)throws Exception{try(Cursor row=db().rawQuery("SELECT count(*) FROM "+table,null)){row.moveToFirst();return row.getLong(0);}}
        void reopen()throws Exception{host.close();assistant.close();db().close();open();}
        public void close()throws Exception{host.close();assistant.close();db().close();}
    }
    @Test public void fullExportIsFixedAcrossNewTailAndImportHasNewIdsWithoutSourceOrAckAuthority()throws Exception{
        try(Fixture f=new Fixture()){f.seed("parent",130);JSONObject export=f.call("export",new JSONObject().put("conversationId","parent").put("format","json"));assertEquals(130,export.getJSONArray("includedMessages").length());String bytes=export.getString("content");f.assistant.appendTaskResult("parent","original-task","导出之后的新尾部");assertFalse(bytes.contains("导出之后的新尾部"));
            long sources=f.count("assistant_sources");String action=UUID.randomUUID().toString();JSONObject input=new JSONObject().put("actionId",action).put("exportJson",bytes).put("sha256",export.getString("sha256"));JSONObject imported=f.call("import",input);String child=imported.getString("conversationId");assertNotEquals("parent",child);assertEquals(sources,f.count("assistant_sources"));assertFalse(imported.getBoolean("executionAuthorized"));assertTrue(f.call("import",input).getBoolean("duplicate"));
            JSONObject page=f.assistant.readOriginalConversation(child,"",80);assertEquals(80,page.getJSONArray("messages").length());JSONObject row=page.getJSONArray("messages").getJSONObject(0);assertTrue(row.getBoolean("importAssignedIdentity"));assertFalse(row.getBoolean("formalSourceRefAvailable"));assertNull(f.repo().read(()->f.repo().metadata(row.getString("recordId"))));assertFalse(row.has("submission"));assertFalse(row.has("clientMessageId"));assertFalse(row.has("attachments"));
            JSONObject older=f.assistant.readOriginalConversation(child,page.getJSONObject("messagePage").getString("nextCursor"),80);assertEquals(50,older.getJSONArray("messages").length());f.reopen();assertTrue(f.call("import",input).getBoolean("duplicate"));
        }
    }
    @Test public void originalReplyAndColdReopenKeepIndependentOrganizationMetadata()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c",2);JSONObject changed=f.call("updateOrganization",new JSONObject().put("conversationId","c").put("expectedRevision",0).put("changes",new JSONObject().put("title","主人命名").put("pinOrder",3).put("archived",true).put("manualUnread",true)));assertEquals(1,changed.getLong("organizationRevision"));
            f.assistant.appendTaskResult("c","task","确实保存的原答复");f.reopen();JSONObject current=f.call("getOrganization",new JSONObject().put("conversationId","c"));assertEquals("主人命名",current.getString("title"));assertEquals(3,current.getLong("pinOrder"));assertTrue(current.getBoolean("archived"));assertTrue(current.getBoolean("manualUnread"));assertEquals(3,f.assistant.readOriginalConversation("c","",80).getJSONArray("messages").length());
        }
    }
    @Test public void retiredSourcesAreOmittedAndCopiedImportRecordsNeverAutomaticallyBecomeModelHistory()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c",3);JSONObject before=f.call("export",new JSONObject().put("conversationId","c"));f.repo().transaction(()->{f.repo().retire(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(new SourceRef("message","c-1",1,"owner","private").toJson()))),"owner_forget");return null;});
            JSONObject after=f.call("export",new JSONObject().put("conversationId","c"));assertEquals(1,after.getJSONArray("includedMessages").length());assertEquals(2,after.getJSONArray("omitted").length());
            JSONObject imported=f.call("import",new JSONObject().put("actionId",UUID.randomUUID().toString()).put("exportJson",before.getString("content")).put("sha256",before.getString("sha256")));JSONArray historical=f.assistant.readOriginalConversation(imported.getString("conversationId"),"",80).getJSONArray("messages");
            var request=AssistantModule.class.getDeclaredMethod("conversationRequest",JSONArray.class,String.class);request.setAccessible(true);assertEquals(1,((JSONArray)request.invoke(null,historical,"no-admitted-native-message")).length());
        }
    }
    @Test public void historyDerivedForkKeepsExactPrefixAndActionResultWithoutPretendingBackendFork()throws Exception{
        try(Fixture f=new Fixture()){f.seed("parent",6);JSONObject intent=new JSONObject().put("actionId",UUID.randomUUID().toString()).put("parentConversationId","parent").put("messageId","parent-3").put("messageRevision",1).put("historyWatermark",6).put("mode","history_derived").put("workspacePolicy","no_project");JSONObject fork=f.call("fork",intent);assertFalse(fork.getJSONObject("forkProvenance").getBoolean("backendForkPerformed"));assertFalse(fork.getBoolean("contextAppliedToModel"));assertEquals(3,fork.getJSONArray("ownerSourceRefs").length());assertEquals(3,f.assistant.readOriginalConversation(fork.getString("conversationId"),"",80).getJSONArray("messages").length());assertEquals(6,f.assistant.readOriginalConversation("parent","",80).getJSONArray("messages").length());
            f.assistant.appendTaskResult("parent","original-task","后来的尾部");assertTrue(f.call("fork",intent).getBoolean("duplicate"));JSONObject stale=new JSONObject(intent.toString()).put("actionId",UUID.randomUUID().toString());assertThrows(IllegalStateException.class,()->f.call("fork",stale));
            assertEquals("unsupported",f.call("fork",new JSONObject(intent.toString()).put("mode","backend_fork")).getString("status"));
        }
    }
    @Test public void sourceIntegrityMismatchIsOmittedInsteadOfPublishedWithANewInventedFingerprint()throws Exception{
        try(Fixture f=new Fixture()){f.seed("c",1);f.db().execSQL("UPDATE assistant_messages SET body='未按原来源修订写入的替代正文' WHERE source_id='c-1'");JSONObject exported=f.call("export",new JSONObject().put("conversationId","c"));assertEquals(0,exported.getJSONArray("includedMessages").length());assertEquals("source_integrity_unavailable",exported.getJSONArray("omitted").getJSONObject(0).getString("reason"));assertFalse(exported.getString("content").contains("替代正文"));}
    }
}
