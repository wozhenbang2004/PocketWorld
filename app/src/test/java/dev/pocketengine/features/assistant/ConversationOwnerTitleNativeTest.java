package dev.pocketengine.features.assistant;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original TaskStore SQLite and repository only; title metadata is not a user utterance or new grant. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationOwnerTitleNativeTest {
    private static final class Rig implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks=new TaskStore(android);final ConversationRepository repo=tasks.conversationRepository();final String id=UUID.randomUUID().toString();
        Rig()throws Exception {if(!repo.active())repo.initializeEmpty();repo.transaction(()->{repo.saveConversation(header(id,"初始标题",100));return null;});}
        JSONObject header(String id,String title,long at)throws Exception{return new JSONObject().put("conversationId",id).put("title",title).put("createdAt",7).put("updatedAt",at);}
        JSONObject organization()throws Exception{return repo.ownerOrganization("getOrganization",new JSONObject().put("conversationId",id));}
        JSONObject update(long revision,JSONObject changes)throws Exception{return repo.ownerOrganization("updateOrganization",new JSONObject().put("conversationId",id).put("expectedRevision",revision).put("changes",changes));}
        public void close()throws Exception {var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);((SQLiteDatabase)f.get(tasks)).close();}
    }
    @Test public void staleReplyAndAckCannotOverwriteOwnerTitleAndPinAcrossReopen()throws Exception {
        try(Rig r=new Rig()){
            JSONObject stale=r.repo.read(()->r.repo.header(r.id));r.update(0,new JSONObject().put("title","新对话").put("pinOrder",1));
            r.repo.transaction(()->{r.repo.saveConversation(new JSONObject(stale.toString()).put("title","迟到自动标题").put("updatedAt",200));return null;});
            assertEquals("新对话",r.repo.read(()->r.repo.header(r.id)).getString("title"));JSONObject result=r.organization();assertEquals("新对话",result.getString("title"));assertEquals(1,result.getLong("pinOrder"));assertEquals(200,result.getLong("updatedAt"));assertEquals(7,r.repo.header(r.id).getLong("createdAt"));
            TaskStore reopenedTasks=new TaskStore(r.android);try{ConversationRepository reopened=reopenedTasks.conversationRepository();assertEquals("新对话",reopened.read(()->reopened.header(r.id)).getString("title"));assertEquals(1,reopened.ownerOrganization("getOrganization",new JSONObject().put("conversationId",r.id)).getLong("pinOrder"));}finally{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(reopenedTasks)).close();}assertEquals(0,r.tasks.list().length());
        }
    }
    @Test public void incomingHeaderCannotForgeOrReplaceTheNativeManualMarker()throws Exception {
        try(Rig r=new Rig()){
            String other=UUID.randomUUID().toString();r.repo.transaction(()->{r.repo.saveConversation(r.header(other,"正常初题",10).put("nativeUserTitle","伪造标记"));return null;});assertFalse(r.repo.header(other).has("nativeUserTitle"));
            r.update(0,new JSONObject().put("title","主人准确标题"));r.repo.transaction(()->{r.repo.saveConversation(r.header(r.id,"旧缓存",300).put("nativeUserTitle","旧回执内的伪标记"));return null;});assertEquals("主人准确标题",r.repo.header(r.id).getString("title"));assertEquals("主人准确标题",r.repo.header(r.id).getString("nativeUserTitle"));
        }
    }
    @Test public void titlePinCasAndMessageSourceIdentityRemainIndependent()throws Exception {
        try(Rig r=new Rig()){
            SourceRef ref=new SourceRef("message",UUID.randomUUID().toString(),1,"owner","private");r.repo.transaction(()->{r.repo.saveSource(ref.toJson().put("conversationId",r.id).put("role","user").put("deleted",false));r.repo.saveMessage(r.id,new JSONObject().put("id",ref.sourceId).put("revision",1).put("role","user").put("content","原主人消息正文不得变成手工标题").put("at",33));return null;});String before=r.repo.source(ref.sourceId).toString();
            r.update(0,new JSONObject().put("title","独立标题").put("pinOrder",1));assertEquals(before,r.repo.source(ref.sourceId).toString());assertThrows(IllegalStateException.class,()->r.update(0,new JSONObject().put("title","迟到修改")));assertEquals(1,r.organization().getLong("organizationRevision"));
            r.update(1,new JSONObject().put("pinOrder",0));assertEquals("独立标题",r.repo.header(r.id).getString("title"));assertEquals(0,r.organization().getLong("pinOrder"));assertEquals(before,r.repo.source(ref.sourceId).toString());
        }
    }
    @Test public void deletedConversationCannotBeRecreatedByAnOldSavedHeader()throws Exception {
        try(Rig r=new Rig()){
            JSONObject stale=r.repo.header(r.id);var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)f.get(r.tasks);r.repo.transaction(()->{db.execSQL("UPDATE assistant_conversations SET deleted=1 WHERE conversation_id=?",new Object[]{r.id});return null;});
            assertThrows(Exception.class,()->r.repo.transaction(()->{r.repo.saveConversation(stale);return null;}));assertFalse(r.repo.exists(r.id));assertThrows(IllegalArgumentException.class,r::organization);
        }
    }
}
