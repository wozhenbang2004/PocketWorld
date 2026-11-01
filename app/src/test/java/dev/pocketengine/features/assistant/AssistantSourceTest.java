package dev.pocketengine.features.assistant;

import android.app.Application;
import android.content.Context;
import android.util.AtomicFile;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real AtomicFile/native-store/source-bridge checks; the explicitly supplied TurnExecutor is a test fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AssistantSourceTest {
    private Context android(){return RuntimeEnvironment.getApplication();}
    private FeatureContext context(){Context app=android();return new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});}
    private File file(){return new File(android().getFilesDir(),"private_conversations.json");}
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private MemoryBook.Scope identity()throws Exception{return MemoryBook.Scope.from(scope());}
    private AssistantModule module(){return new AssistantModule(context(),null,(conversation,goal,history,config,control)->new JSONObject().put("content","测试执行器返回的答复"));}
    private JSONObject send(AssistantModule module,String conversation)throws Exception{JSONObject input=new JSONObject().put("message","真实保存的用户消息");if(conversation!=null)input.put("conversationId",conversation);return module.handle("send",input);}
    private JSONObject conversation(AssistantModule module,String id)throws Exception{return module.handle("get",new JSONObject().put("conversationId",id));}
    private JSONArray legacy(String id,int count)throws Exception{JSONArray messages=new JSONArray();for(int i=0;i<count;i++)messages.put(new JSONObject().put("role",i%2==0?"user":"assistant").put("content","历史消息正文-"+i).put("at",1000+i));return new JSONArray().put(new JSONObject().put("conversationId",id).put("title","历史对话").put("createdAt",1).put("messages",messages));}
    @Test public void legacyMigrationIsDeterministicAndReloadDoesNotAdvanceRevisions()throws Exception{
        byte[] original=legacy("legacy-a",2).toString().getBytes(StandardCharsets.UTF_8);Files.write(file().toPath(),original);AssistantModule first=module();
        JSONArray messages=conversation(first,"legacy-a").getJSONArray("messages");assertEquals(1,messages.getJSONObject(0).getLong("revision"));String id=messages.getJSONObject(0).getString("id");
        JSONObject persisted=new JSONObject(new String(Files.readAllBytes(file().toPath()),StandardCharsets.UTF_8));assertEquals(2,persisted.getInt("version"));assertEquals(2,persisted.getJSONArray("sourceStates").length());byte[] after=Files.readAllBytes(file().toPath());
        AssistantModule reloaded=module();assertArrayEquals(after,Files.readAllBytes(file().toPath()));assertEquals(id,conversation(reloaded,"legacy-a").getJSONArray("messages").getJSONObject(0).getString("id"));assertEquals(1,reloaded.resolveSource(identity(),id).ref.revision);
        File second=new File(android().getFilesDir(),"legacy-independent-copy.json");Files.write(second.toPath(),original);AssistantModule independent=new AssistantModule(context(),null,null,new AtomicFile(second));assertEquals(id,conversation(independent,"legacy-a").getJSONArray("messages").getJSONObject(0).getString("id"));
        assertFalse(first.sourceStates(scope()).toString().contains("历史消息正文"));assertEquals(0,first.sourceStates(new JSONObject().put("ownerId","owner").put("spaceId","qq:group:1")).length());assertNull(first.resolveSource(MemoryBook.Scope.from(new JSONObject().put("ownerId","someone").put("spaceId","private")),id));
    }
    @Test public void editAndDeleteRequireCurrentRevisionAndKeepPersistentTombstones()throws Exception{
        AssistantModule module=module();String conversation=send(module,null).getString("conversationId");JSONObject message=conversation(module,conversation).getJSONArray("messages").getJSONObject(0);String id=message.getString("id");
        JSONObject edit=new JSONObject().put("conversationId",conversation).put("messageId",id).put("revision",1L).put("content","修订后的实际消息");JSONObject edited=module.handle("editMessage",edit).getJSONObject("message");assertEquals(id,edited.getString("id"));assertEquals(2,edited.getLong("revision"));assertEquals(2,module.resolveSource(identity(),id).ref.revision);
        assertThrows(IllegalStateException.class,()->module.handle("editMessage",edit));assertThrows(IllegalArgumentException.class,()->module.handle("deleteMessage",new JSONObject().put("conversationId",conversation).put("messageId",id)));
        module.handle("deleteMessage",new JSONObject().put("conversationId",conversation).put("messageId",id).put("revision",2L));SourceRef.State deleted=module.resolveSource(identity(),id);assertTrue(deleted.deleted);assertEquals(3,deleted.ref.revision);
        AssistantModule reloaded=module();assertTrue(reloaded.resolveSource(identity(),id).deleted);assertEquals(3,reloaded.resolveSource(identity(),id).ref.revision);assertFalse(reloaded.sourceStates(scope()).toString().contains("修订后的实际消息"));
        String newConversation=send(reloaded,null).getString("conversationId");assertNotEquals(id,conversation(reloaded,newConversation).getJSONArray("messages").getJSONObject(0).getString("id"));
    }
    @Test public void trimmingMessagesAndConversationsRetainsDeletionEvidence()throws Exception{
        Files.write(file().toPath(),legacy("eighty",80).toString().getBytes(StandardCharsets.UTF_8));AssistantModule module=module();JSONArray before=conversation(module,"eighty").getJSONArray("messages");String oldest=before.getJSONObject(0).getString("id"),second=before.getJSONObject(1).getString("id");
        send(module,"eighty");assertEquals(80,conversation(module,"eighty").getJSONArray("messages").length());assertTrue(module.resolveSource(identity(),oldest).deleted);assertTrue(module.resolveSource(identity(),second).deleted);assertEquals(82,module.sourceStates(scope()).length());assertTrue(module().resolveSource(identity(),oldest).deleted);
        JSONArray twenty=new JSONArray();for(int i=0;i<20;i++)twenty.put(legacy("conversation-"+i,1).getJSONObject(0));Files.write(file().toPath(),twenty.toString().getBytes(StandardCharsets.UTF_8));AssistantModule twentyModule=module();String evicted=conversation(twentyModule,"conversation-19").getJSONArray("messages").getJSONObject(0).getString("id");send(twentyModule,null);
        assertEquals(20,twentyModule.handle("list",new JSONObject()).getJSONArray("conversations").length());assertTrue(twentyModule.resolveSource(identity(),evicted).deleted);assertEquals(22,twentyModule.sourceStates(scope()).length());
    }
    @Test public void failedAtomicWriteRestoresMemoryAndDoesNotClaimDeletion()throws Exception{
        FailingFile disk=new FailingFile(file());AssistantModule module=new AssistantModule(context(),null,(c,g,h,m,r)->new JSONObject().put("content","fixture reply"),disk);String conversation=send(module,null).getString("conversationId");String id=conversation(module,conversation).getJSONArray("messages").getJSONObject(0).getString("id");byte[] before=Files.readAllBytes(file().toPath());AtomicInteger sync=new AtomicInteger();module.setSourceSynchronizer(sync::incrementAndGet);disk.fail=true;
        assertThrows(IOException.class,()->module.handle("delete",new JSONObject().put("conversationId",conversation)));assertEquals(0,sync.get());assertArrayEquals(before,Files.readAllBytes(file().toPath()));assertEquals(2,conversation(module,conversation).getJSONArray("messages").length());assertFalse(module.resolveSource(identity(),id).deleted);assertEquals(1,module.resolveSource(identity(),id).ref.revision);
        disk.fail=false;module.handle("delete",new JSONObject().put("conversationId",conversation));assertTrue(module.resolveSource(identity(),id).deleted);assertEquals(1,sync.get());assertEquals(0,module().handle("list",new JSONObject()).getJSONArray("conversations").length());
    }
    @Test public void synchronizationRunsOutsideStorageLockAndBeforeModelOrAcknowledgement()throws Exception{
        AtomicInteger calls=new AtomicInteger(),models=new AtomicInteger();AssistantModule module=new AssistantModule(context(),null,(c,g,h,m,r)->{assertEquals(1,calls.get());models.incrementAndGet();return new JSONObject().put("content","fixture reply");});ExecutorService independent=Executors.newSingleThreadExecutor();
        try{module.setSourceSynchronizer(()->{Future<JSONArray> view=independent.submit(()->module.sourceStates(scope()));assertTrue(view.get(2,TimeUnit.SECONDS).length()>0);calls.incrementAndGet();});String id=send(module,null).getString("conversationId");assertEquals(1,models.get());assertEquals(2,calls.get());assertFalse(module.snapshot().getBoolean("sourceSynchronizationPending"));module.handle("delete",new JSONObject().put("conversationId",id));assertEquals(3,calls.get());}
        finally{independent.shutdownNow();}
    }
    @Test public void synchronizationFailureKeepsCommittedSourceButNeverStartsTheModel()throws Exception{
        AtomicInteger models=new AtomicInteger();AssistantModule module=new AssistantModule(context(),null,(c,g,h,m,r)->{models.incrementAndGet();return new JSONObject().put("content","should not run");});module.setSourceSynchronizer(()->{throw new IOException("fixture sync failure");});
        assertThrows(IllegalStateException.class,()->send(module,null));assertEquals(0,models.get());assertEquals(1,module.sourceStates(scope()).length());assertTrue(module.snapshot().getBoolean("sourceSynchronizationPending"));AssistantModule reloaded=module();assertEquals(1,reloaded.sourceStates(scope()).length());
    }
    @Test public void actualSourceBridgeInvalidatesDerivedMemoryAfterEditAndDelete()throws Exception{
        FeatureContext context=context();AssistantModule[] authority=new AssistantModule[1];MemoryModule memory=new MemoryModule(context,(scope,kind,id)->kind.equals("message")&&authority[0]!=null?authority[0].resolveSource(scope,id):null);
        AssistantModule assistant=new AssistantModule(context,memory,(c,g,h,m,r)->new JSONObject().put("content","来源闭环测试答复"));authority[0]=assistant;
        try(FeatureHost host=new FeatureHost(event->{})){host.register(memory);host.register(new dev.pocketengine.features.skills.SkillsModule(new dev.pocketengine.features.FeatureContext(android(),null,null,event->{}),memory::resolveExperience));host.register(assistant);MemorySourceBridge bridge=new MemorySourceBridge(host,new TaskStore(android()));assistant.setSourceSynchronizer(()->bridge.synchronize(scope()));
            String conversation=send(assistant,null).getString("conversationId");JSONObject message=conversation(assistant,conversation).getJSONArray("messages").getJSONObject(0);String id=message.getString("id");SourceRef original=assistant.resolveSource(identity(),id).ref;
            saveSourced(memory,original,"源消息支持的旧事实");assertEquals(1,memory.handle("contextSnapshot",new JSONObject().put("scope",scope())).getJSONArray("records").length());
            assistant.handle("editMessage",new JSONObject().put("conversationId",conversation).put("messageId",id).put("revision",1L).put("content","修正后的源消息"));assertEquals(0,memory.handle("contextSnapshot",new JSONObject().put("scope",scope())).getJSONArray("records").length());
            SourceRef corrected=assistant.resolveSource(identity(),id).ref;assertEquals(2,corrected.revision);saveSourced(memory,corrected,"修正来源的新事实");assistant.handle("deleteMessage",new JSONObject().put("conversationId",conversation).put("messageId",id).put("revision",2L));assertTrue(assistant.resolveSource(identity(),id).deleted);assertEquals(0,memory.handle("contextSnapshot",new JSONObject().put("scope",scope())).getJSONArray("records").length());
            assertThrows(IllegalArgumentException.class,()->saveSourced(memory,original,"不能用旧材料复活"));
        }
    }
    @Test public void restartReplaysCommittedDeletionWhenTheOriginalSynchronizationFailed()throws Exception{
        FeatureContext context=context();AssistantModule[] authority=new AssistantModule[1];MemoryModule memory=new MemoryModule(context,(scope,kind,id)->kind.equals("message")&&authority[0]!=null?authority[0].resolveSource(scope,id):null);
        AssistantModule assistant=new AssistantModule(context,memory,(c,g,h,m,r)->new JSONObject().put("content","保存后重启测试答复"));authority[0]=assistant;
        String conversation=send(assistant,null).getString("conversationId"),id=conversation(assistant,conversation).getJSONArray("messages").getJSONObject(0).getString("id");SourceRef original=assistant.resolveSource(identity(),id).ref;saveSourced(memory,original,"删除前尚未对账的来源事实");
        assistant.setSourceSynchronizer(()->{throw new IOException("fixture process-boundary failure");});assertThrows(IllegalStateException.class,()->assistant.handle("delete",new JSONObject().put("conversationId",conversation)));
        assertTrue(assistant.resolveSource(identity(),id).deleted);assertEquals(1,memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());
        AssistantModule restored=module();authority[0]=restored;MemoryModule restoredMemory=new MemoryModule(context,(scope,kind,sourceId)->kind.equals("message")?restored.resolveSource(scope,sourceId):null);
        try(FeatureHost host=new FeatureHost(event->{})){host.register(restored);host.register(restoredMemory);host.register(new dev.pocketengine.features.skills.SkillsModule(new dev.pocketengine.features.FeatureContext(android(),null,null,event->{}),restoredMemory::resolveExperience));MemorySourceBridge bridge=new MemorySourceBridge(host,new TaskStore(android()));bridge.synchronize(scope());
            assertEquals(0,restoredMemory.handle("contextSnapshot",new JSONObject().put("scope",scope())).getJSONArray("records").length());assertTrue(restored.resolveSource(identity(),id).deleted);
            bridge.synchronize(scope());assertEquals(0,restoredMemory.handle("contextSnapshot",new JSONObject().put("scope",scope())).getJSONArray("records").length());assertEquals(2,restored.resolveSource(identity(),id).ref.revision);
        }
    }
    private void saveSourced(MemoryModule memory,SourceRef source,String text)throws Exception{memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("layer","fact").put("core",true).put("text",text).put("source","当前真实私人消息").put("sourceRefs",new JSONArray().put(source.toJson()))));}
    private static final class FailingFile extends AtomicFile{
        boolean fail;FailingFile(File base){super(base);}
        @Override public FileOutputStream startWrite()throws IOException{if(fail)throw new IOException("fixture disk failure");return super.startWrite();}
    }
}
