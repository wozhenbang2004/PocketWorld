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

/** Actual original repository/SQLite source pins. No phone, model or owner-consent simulation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationPhoneHandoffNativeTest {
    static final class Rig implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();
        final TaskStore tasks;final ConversationRepository repo;final SQLiteDatabase db;
        final String conversation=UUID.randomUUID().toString(),user=UUID.randomUUID().toString();
        Rig()throws Exception{
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);repo=tasks.conversationRepository();if(!repo.active())repo.initializeEmpty();
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","固定原对话").put("createdAt",1L).put("updatedAt",1L));save(user,1,"user","请核对这项手机任务🙂");return null;});
        }
        void save(String id,long revision,String role,String text)throws Exception{
            JSONObject state=repo.source(id);if(state==null)state=new SourceRef("message",id,revision,"owner","private").toJson().put("conversationId",conversation).put("role",role).put("deleted",false);
            state.put("revision",revision);repo.saveSource(state);repo.saveMessage(conversation,new JSONObject().put("id",id).put("revision",revision).put("role",role).put("content",text).put("at",100L));
        }
        JSONObject prepare()throws Exception{return repo.transaction(()->repo.preparePhoneHandoffSource(conversation));}
        void require(JSONObject snapshot)throws Exception{repo.transaction(()->{repo.requirePhoneHandoffSource(snapshot);return null;});}
        public void close(){if(db.isOpen())db.close();}
    }

    @Test public void idleConversationPinsItsActualLatestUserWithoutCreatingATask()throws Exception{
        try(Rig r=new Rig()){
            String assistant=UUID.randomUUID().toString();r.repo.transaction(()->{r.save(assistant,1,"assistant","这里只是建议，不是执行许可");return null;});
            JSONObject pin=r.prepare();assertEquals(8,pin.length());assertEquals("pocketworld.phone-handoff-source.v1",pin.getString("format"));
            assertEquals(r.conversation,pin.getString("conversationId"));assertEquals(r.user,pin.getString("sourceMessageId"));assertEquals(1,pin.getLong("sourceMessageRevision"));assertEquals(1,pin.getLong("sourceSequence"));
            assertEquals("固定原对话",pin.getString("conversationTitle"));assertTrue(pin.getString("bodyFingerprint").matches("sha256:[a-f0-9]{64}"));assertFalse(pin.toString().contains("请核对这项手机任务"));r.require(pin);assertEquals(0,r.tasks.list().length());
        }
    }
    @Test public void sourceValidationRequiresTheOriginalStoreTransaction()throws Exception{
        try(Rig r=new Rig()){JSONObject pin=r.prepare();assertThrows(SecurityException.class,()->r.repo.requirePhoneHandoffSource(pin));synchronized(r.tasks){assertThrows(SecurityException.class,()->r.repo.requirePhoneHandoffSource(pin));}r.require(pin);}
    }
    @Test public void ordinaryTailAndOwnerTitleChangesDoNotInvalidateThePinnedMessage()throws Exception{
        try(Rig r=new Rig()){
            JSONObject pin=r.prepare();r.repo.transaction(()->{r.save(UUID.randomUUID().toString(),1,"assistant","后来尾部答复");r.save(UUID.randomUUID().toString(),1,"user","另一条新消息");return null;});
            r.repo.ownerOrganization("updateOrganization",new JSONObject().put("conversationId",r.conversation).put("expectedRevision",0L).put("changes",new JSONObject().put("title","主人新标题")));
            r.require(pin);assertEquals("固定原对话",pin.getString("conversationTitle"));assertNotEquals(r.user,r.prepare().getString("sourceMessageId"));
        }
    }
    @Test public void editedRevisionOrBodyIntegrityDriftCannotLaunchFromAnOldReview()throws Exception{
        try(Rig r=new Rig()){
            JSONObject pin=r.prepare();r.repo.transaction(()->{r.save(r.user,2,"user","已经改变的要求");return null;});assertThrows(SecurityException.class,()->r.require(pin));
            JSONObject fresh=r.prepare();r.repo.transaction(()->{r.db.execSQL("UPDATE assistant_messages SET body=? WHERE source_id=?",new Object[]{"改正文而没有改hash",r.user});return null;});
            assertThrows(SecurityException.class,()->r.require(fresh));assertThrows(SecurityException.class,r::prepare);
        }
    }
    @Test public void latestWithdrawnSourceDoesNotFallBackToAnOlderOwnerInstruction()throws Exception{
        try(Rig r=new Rig()){
            String latest=UUID.randomUUID().toString();r.repo.transaction(()->{r.save(latest,1,"user","后来撤回的要求");return null;});JSONObject pin=r.prepare();assertEquals(latest,pin.getString("sourceMessageId"));
            r.repo.transaction(()->{JSONObject source=r.repo.source(latest);source.put("memorySourceRetiredThrough",1L).put("memoryContextRetired",true);r.repo.saveSource(source);return null;});
            assertThrows(SecurityException.class,r::prepare);assertThrows(SecurityException.class,()->r.require(pin));
        }
    }
    @Test public void deletedSourceAndConversationTombstoneInvalidateOriginalReview()throws Exception{
        try(Rig r=new Rig()){
            JSONObject pin=r.prepare();r.repo.transaction(()->{JSONObject source=r.repo.source(r.user);source.put("deleted",true).put("revision",2L).put("unavailableReason","user_message_delete");r.repo.saveSource(source);return null;});
            assertThrows(SecurityException.class,()->r.require(pin));assertThrows(SecurityException.class,r::prepare);
        }
        try(Rig r=new Rig()){
            JSONObject pin=r.prepare();r.repo.transaction(()->{r.repo.deleteConversation(r.conversation,"user_conversation_delete");return null;});
            assertThrows(Exception.class,()->r.require(pin));assertFalse(r.repo.exists(r.conversation));assertEquals(0,r.tasks.list().length());
        }
    }
    @Test public void forgedRoleGenerationSequenceOrAdditionalPermissionFieldsAreRejected()throws Exception{
        try(Rig r=new Rig()){
            JSONObject pin=r.prepare();for(JSONObject changed:new JSONObject[]{new JSONObject(pin.toString()).put("generation",UUID.randomUUID().toString()),new JSONObject(pin.toString()).put("sourceSequence",2L),new JSONObject(pin.toString()).put("executionAuthorized",true)})assertThrows(SecurityException.class,()->r.require(changed));
            assertThrows(IllegalArgumentException.class,()->r.require(new JSONObject(pin.toString()).put("sourceMessageRevision",1.0)));
            r.repo.transaction(()->{r.db.execSQL("UPDATE assistant_sources SET role='assistant' WHERE source_id=?",new Object[]{r.user});return null;});assertThrows(SecurityException.class,()->r.require(pin));
        }
    }
}
