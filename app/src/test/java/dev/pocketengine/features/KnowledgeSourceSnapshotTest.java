package dev.pocketengine.features;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class KnowledgeSourceSnapshotTest {
    private Application app(){return RuntimeEnvironment.getApplication();}
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private SourceRef confirmed(TaskStore store,String id)throws Exception{return store.resolveMemorySource(MemoryBook.Scope.from(scope()),"task_evidence","task:"+id+":verified_result").ref;}
    private void confirm(TaskStore store,String id,boolean completed)throws Exception{store.event(id,new JSONObject().put("type","user_verification").put("completed",completed).put("feedback","主人实际核对了文件数量"));}
    private interface Checked {void run()throws Exception;}
    private static void rejected(Checked action)throws Exception{try{action.run();fail("must fail closed");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}}

    @Test public void taskReadUsesConfirmationTimeBodyNotCurrentSummary()throws Exception{
        TaskStore store=new TaskStore(app());String id=store.create("把目录备份到目标位置").getString("id");store.status(id,"needs_confirmation","已复制10份文件，请主人核对");confirm(store,id,true);SourceRef ref=confirmed(store,id);
        String exact=store.readMemorySource(ref).text;assertTrue(exact.contains("已复制10份文件"));assertFalse(exact.contains("系统提示"));
        store.status(id,"completed","当前摘要后来改变，不属于旧证据");assertEquals(exact,store.readMemorySource(ref).text);
        assertEquals(exact,new TaskStore(app()).readMemorySource(ref).text);
    }
    @Test public void taskRevocationAndNewConfirmationInvalidatePreviousBody()throws Exception{
        TaskStore store=new TaskStore(app());String id=store.create("备份任务").getString("id");store.status(id,"needs_confirmation","旧结果快照");confirm(store,id,true);SourceRef old=confirmed(store,id);
        confirm(store,id,false);rejected(()->store.readMemorySource(old));assertFalse(store.get(id,false).toString().contains("\"summary\":\"旧结果快照\",\"ownerFeedback\""));
        JSONArray events=store.get(id,false).getJSONArray("events");assertFalse(events.getJSONObject(0).has("memorySourceSnapshot"));
        store.status(id,"needs_confirmation","新的核对结果");confirm(store,id,true);SourceRef fresh=confirmed(store,id);
        assertTrue(fresh.revision>old.revision);assertTrue(store.readMemorySource(fresh).text.contains("新的核对结果"));rejected(()->store.readMemorySource(old));
    }
    @Test public void suppliedSnapshotCannotReplaceHostStoredEvidence()throws Exception{
        TaskStore store=new TaskStore(app());String id=store.create("真实目标").getString("id");
        store.event(id,new JSONObject().put("type","user_verification").put("completed",true).put("memorySourceSnapshot",new JSONObject().put("goal","伪造正文")));
        String body=store.readMemorySource(confirmed(store,id)).text;assertTrue(body.contains("真实目标"));assertFalse(body.contains("伪造正文"));
    }
    @Test public void legacyConfirmationWithoutSnapshotFailsClosed()throws Exception{
        TaskStore store=new TaskStore(app());String id=store.create("旧任务").getString("id");confirm(store,id,true);SourceRef ref=confirmed(store,id);JSONObject row=store.get(id,false);row.remove("operations");row.getJSONArray("events").getJSONObject(0).remove("memorySourceSnapshot");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(app().getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            ContentValues values=new ContentValues();values.put("document",row.toString());db.update("tasks",values,"task_id=?",new String[]{id});
        }
        store.status(id,"completed","不能用这个当前摘要伪造旧证据");rejected(()->store.readMemorySource(ref));
    }
    @Test public void forgottenSnapshotsAreScrubbedDurablyAndRetryDoesNotEraseNewerConfirmation()throws Exception{
        TaskStore store=new TaskStore(app());String id=store.create("备份任务").getString("id");store.status(id,"needs_confirmation","额外学习快照正文");confirm(store,id,true);SourceRef old=confirmed(store,id);
        assertEquals(1,store.forgetMemorySourceSnapshot(old).getInt("removedSnapshots"));assertEquals(0,store.forgetMemorySourceSnapshot(old).getInt("removedSnapshots"));
        rejected(()->new TaskStore(app()).readMemorySource(old));assertFalse(store.get(id,false).getJSONArray("events").getJSONObject(0).has("memorySourceSnapshot"));
        confirm(store,id,true);SourceRef fresh=confirmed(store,id);store.forgetMemorySourceSnapshot(old);assertNotNull(store.readMemorySource(fresh));
    }
    @Test public void taskSnapshotReadsAndCleanupRefuseCrossScope()throws Exception{
        TaskStore store=new TaskStore(app());String id=store.create("备份任务").getString("id");confirm(store,id,true);SourceRef real=confirmed(store,id);
        SourceRef other=new SourceRef(real.kind,real.sourceId,real.revision,"other","private");rejected(()->store.readMemorySource(other));rejected(()->store.forgetMemorySourceSnapshot(other));assertNotNull(store.readMemorySource(real));
    }
    @Test public void assistantSourceBodyIsVersionPinnedAndDeletedMessageCannotBeRead()throws Exception{
        FeatureContext context=new FeatureContext(app(),new SettingsStore(app()),new SecretVault(app()),event->{});
        AssistantModule module=new AssistantModule(context,null,(c,g,h,m,r)->new JSONObject().put("content","合成回复"));
        String conversation=module.handle("send",new JSONObject().put("message","原始用户消息")).getString("conversationId");JSONObject message=module.handle("get",new JSONObject().put("conversationId",conversation)).getJSONArray("messages").getJSONObject(0);
        SourceRef old=module.resolveSource(MemoryBook.Scope.from(scope()),message.getString("id")).ref;assertEquals("原始用户消息",module.readMemorySource(old).text);
        module.handle("editMessage",new JSONObject().put("conversationId",conversation).put("messageId",old.sourceId).put("revision",old.revision).put("content","修改后的用户消息"));rejected(()->module.readMemorySource(old));
        SourceRef fresh=module.resolveSource(MemoryBook.Scope.from(scope()),old.sourceId).ref;assertEquals("修改后的用户消息",module.readMemorySource(fresh).text);
        module.handle("deleteMessage",new JSONObject().put("conversationId",conversation).put("messageId",fresh.sourceId).put("revision",fresh.revision));rejected(()->module.readMemorySource(fresh));
    }
}
