package dev.pocketengine.features;
import android.app.Application;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
/** Old native/advanced feature routes must not bypass reviewed destructive cleanup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class LegacyMemoryRouteGuardTest {
    @Test public void oldDeleteForgetAndSourceMutationRoutesRejectWithoutChangingMemoryOrSnapshot()throws Exception{
        Application app=RuntimeEnvironment.getApplication();BlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();
        TaskStore tasks=new TaskStore(app);FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),e->{});
        try(FeatureHost host=new FeatureHost(events::add,tasks,()->false)){
            MemorySourceBridge bridge=new MemorySourceBridge(host,tasks);MemoryModule memory=new MemoryModule(context,bridge::resolve);host.register(memory);host.register(new SkillsModule(context,memory::resolveExperience));host.setMemorySynchronizer(bridge::synchronize);
            JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");String task=tasks.create("retained source").getString("id");tasks.event(task,new JSONObject().put("type","user_verification").put("completed",true));
            SourceRef source=tasks.resolveMemorySource(MemoryBook.Scope.from(scope),"task_evidence","task:"+task+":verified_result").ref;
            JSONObject record=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("text","keep until reviewed").put("sourceRefs",new JSONArray().put(source.toJson())))).getJSONObject("record");
            for(String command:new String[]{"delete","forget","sourceUpdated","sourceDeleted"}){
                host.request(new JSONObject().put("feature","memory").put("command",command).put("requestId",command).put("payload",new JSONObject().put("id",record.getString("id")).put("sourceRef",source.toJson())));
                JSONObject result=events.poll(3,TimeUnit.SECONDS);assertNotNull(result);assertEquals("feature_result",result.getString("type"));assertEquals(command,result.getString("requestId"));assertFalse(result.getBoolean("ok"));assertTrue(result.getString("message").contains("审核"));
                assertEquals(1,memory.handle("list",new JSONObject().put("scope",scope)).getJSONArray("records").length());assertFalse(memory.isSourceForgotten(scope,source));assertNotNull(tasks.readMemorySource(source));assertEquals(0,tasks.memoryCleanupTasks().length());
            }
            NativeOwnerWorkflows nativeReview=new NativeOwnerWorkflows(host,context,tasks,bridge);
            assertEquals(1,nativeReview.memory("forgetReviewed",new JSONObject().put("id",record.getString("id")).put("expectedRevision",record.getLong("revision"))).getInt("deleted"));
            assertTrue(memory.isSourceForgotten(scope,source));assertThrows(IllegalStateException.class,()->tasks.readMemorySource(source));
        }
    }
}
