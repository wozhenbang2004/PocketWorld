package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.runtime.routes.PhoneRouteSession;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real phone-domain TaskStore/SQLite/owner-confirmation/Book source boundaries. No device effect. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneRouteSourceNativeTest {
    private static final String GOAL="打开搜索";
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final FeatureHost host;final MemorySourceBridge bridge;final MemoryModule memory;
        boolean sourceUnavailable;final java.util.List<TaskStore> opened=new java.util.ArrayList<>();
        Fixture()throws Exception{
            app.deleteDatabase("pocket-world.db");new java.io.File(app.getFilesDir(),"memory/records.json").delete();
            tasks=new TaskStore(app);opened.add(tasks);host=new FeatureHost(event->{},tasks,()->false);bridge=new MemorySourceBridge(host,tasks);memory=new MemoryModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),bridge::resolve);host.register(memory);host.register(new dev.pocketengine.features.skills.SkillsModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),memory::resolveExperience));
        }
        JSONObject start(String goal)throws Exception{return tasks.startAttempt(tasks.create(goal,"main_screen").getString("id"),"main_screen",8);}
        RunControl control(JSONObject task)throws Exception{RunControl original=new RunControl();original.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));return original;}
        TaskStorePhoneRoutes repository(JSONObject task,RunControl original)throws Exception{return repository(tasks,task,original);}
        TaskStorePhoneRoutes repository(TaskStore store,JSONObject task,RunControl original)throws Exception{
            if(!opened.contains(store))opened.add(store);return new TaskStorePhoneRoutes(store,task.getString("id"),original,pkg->1,new TaskStorePhoneRoutes.Sources(){public <T>T current(JSONArray proofs,Callable<T> action)throws Exception{if(sourceUnavailable)throw new android.database.sqlite.SQLiteDiskIOException("fixture source metadata temporarily unavailable");return bridge.withCurrentPhoneRouteResults(proofs,action);}});
        }
        JSONObject source(boolean confirm)throws Exception{return source(confirm,1);}
        JSONObject source(boolean confirm,int stepCount)throws Exception{return source(confirm,stepCount,"phone_click_node");}
        JSONObject source(boolean confirm,int stepCount,String tool)throws Exception{
            JSONObject task=start(GOAL);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=control(task);
            tasks.authorizeTools(id,run,new JSONArray().put("phone_click_node").put("phone_scroll_node"));RunJournal journal=tasks.journal(id,run,epoch,original);
            JSONObject args=new JSONObject().put("observationId","native-scene").put("nodeId","native-search");if(tool.equals("phone_scroll_node"))args.put("direction","forward");JSONObject receipt=new JSONObject().put("ok",true).put("accepted",true);

            JSONObject tree=new JSONObject().put("ok",true).put("observationId","native-scene").put("packageName","app.example").put("treeSchemaVersion",1).put("observationSources",new JSONObject().put("treeAvailable",true)).put("windows",new JSONArray().put(new JSONObject().put("rootAvailable",true))).put("nodes",new JSONArray().put(new JSONObject().put("id","native-search").put("resourceId","app.example:id/search").put("role","Button").put("text","搜索").put("visible",true).put("enabled",true)));
            PhonePort phone=new PhonePort(){public boolean isConnected(){return true;}public JSONObject observe(boolean pixels,RunControl control){return tree;}public JSONObject execute(String tool,JSONObject arguments,RunControl control){throw new AssertionError("No device effect is performed in source eligibility tests");}public void clearReferences(){}};
            PhoneRouteSession recording=new PhoneRouteSession(id,GOAL,phone,original,repository(task,original),false);for(int i=0;i<stepCount;i++){String operation=journal.beginOperation(tool,args);journal.finishOperation(operation,receipt);recording.after(tool,args,receipt,tree,operation,original);}JSONObject candidate=recording.candidate(original);assertNotNull(candidate);
            tasks.endAttempt(id,run,"needs_confirmation","实际SQL回执已保留；设备效果由fixture显式模拟",new JSONObject().put("nativePhoneRoute",candidate));
            if(confirm){JSONObject current=tasks.get(id,false);tasks.confirmReviewedResult(id,current.getString("resultReviewDigest"),true,"");}
            return new JSONObject().put("id",id).put("candidate",candidate);
        }
        public void close()throws Exception{host.close();java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);for(TaskStore store:opened)((android.database.sqlite.SQLiteDatabase)field.get(store)).close();}
    }
    @Test public void normalPhoneOwnerConfirmationPublishesOnlyCurrentMetadataAndRecordsTheRealNativeSeq()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject source=fixture.source(true),active=fixture.start(GOAL);RunControl original=fixture.control(active);TaskStorePhoneRoutes routes=fixture.repository(active,original);
            JSONArray list=routes.routes(GOAL,original);assertEquals(1,list.length());assertFalse(list.getJSONObject(0).getBoolean("executionAuthorized"));assertFalse(list.toString().contains("beforeAnchors"));
            JSONObject metadata=fixture.tasks.phoneRouteSourceMetadata(source.getString("id"));assertNotNull(metadata);SourceRef ref=SourceRef.fromJson(metadata.getJSONObject("sourceRef"));assertEquals("task_evidence",ref.kind);assertEquals("task:"+source.getString("id")+":verified_result",ref.sourceId);assertTrue(ref.revision>0);
            JSONArray dependencies=fixture.tasks.phoneRouteDependencies(active.getString("id"));assertEquals(1,dependencies.length());assertEquals(ref.toJson().toString(),dependencies.getJSONObject(0).getJSONObject("sourceRef").toString());routes.requireCurrentSources(original);assertEquals(0,routes.routes("不同目标",original).length());
        }
    }
    @Test public void modelLikeVerificationEventWithoutTheNativeResultReviewCannotForgeACacheSource()throws Exception{
        try(Fixture fixture=new Fixture()){JSONObject source=fixture.source(false);fixture.tasks.event(source.getString("id"),new JSONObject().put("type","user_verification").put("completed",true));assertNull(fixture.tasks.phoneRouteSourceMetadata(source.getString("id")));}
    }
    @Test public void canonicalBookForgetBlocksAnAlreadyUsedRouteWithoutDeletingTheOriginalTask()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject source=fixture.source(true),active=fixture.start(GOAL);RunControl original=fixture.control(active);TaskStorePhoneRoutes routes=fixture.repository(active,original);assertEquals(1,routes.routes(GOAL,original).length());SourceRef ref=SourceRef.fromJson(fixture.tasks.phoneRouteSourceMetadata(source.getString("id")).getJSONObject("sourceRef"));
            JSONObject record=fixture.memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("layer","episodic").put("text","原已确认手机导航结果").put("source","主人明确保存的已确认结果").put("sourceRefs",new JSONArray().put(ref.toJson())))).getJSONObject("record");fixture.bridge.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));
            assertTrue(fixture.memory.isSourceForgotten(scope(),ref));assertThrows(PhoneRouteSession.SourceUnavailable.class,()->routes.requireCurrentSources(original));assertNotNull(fixture.tasks.get(source.getString("id"),false));assertEquals(1,fixture.tasks.phoneRouteDependencies(active.getString("id")).length());
        }
    }
    @Test public void retiredNativeConfirmationSnapshotCannotBeRecreatedFromRetainedGoalOrRawAudit()throws Exception{
        try(Fixture fixture=new Fixture()){JSONObject source=fixture.source(true),active=fixture.start(GOAL);RunControl original=fixture.control(active);TaskStorePhoneRoutes routes=fixture.repository(active,original);assertEquals(1,routes.routes(GOAL,original).length());SourceRef ref=SourceRef.fromJson(fixture.tasks.phoneRouteSourceMetadata(source.getString("id")).getJSONObject("sourceRef"));fixture.tasks.forgetMemorySourceSnapshot(ref);assertThrows(PhoneRouteSession.SourceUnavailable.class,()->routes.requireCurrentSources(original));assertNotNull(fixture.tasks.get(source.getString("id"),false));}
    }
    @Test public void temporarySourceIoSuspendsUseButPreservesDataAndRecoversWithoutANewSourceOrGrant()throws Exception{
        try(Fixture fixture=new Fixture()){JSONObject source=fixture.source(true),active=fixture.start(GOAL);RunControl original=fixture.control(active);TaskStorePhoneRoutes routes=fixture.repository(active,original);assertEquals(1,routes.routes(GOAL,original).length());String metadata=fixture.tasks.phoneRouteSourceMetadata(source.getString("id")).toString();fixture.sourceUnavailable=true;assertThrows(PhoneRouteSession.SourceUnavailable.class,()->routes.requireCurrentSources(original));assertEquals(metadata,fixture.tasks.phoneRouteSourceMetadata(source.getString("id")).toString());fixture.sourceUnavailable=false;routes.requireCurrentSources(original);assertEquals(1,routes.routes(GOAL,original).length());assertEquals(1,fixture.tasks.phoneRouteDependencies(active.getString("id")).length());}
    }
    @Test public void withdrawalStopsReuseButIsNotAnUnderlyingSourceForget()throws Exception{
        try(Fixture fixture=new Fixture()){JSONObject source=fixture.source(true),active=fixture.start(GOAL);RunControl original=fixture.control(active);TaskStorePhoneRoutes routes=fixture.repository(active,original);JSONObject candidate=source.getJSONObject("candidate");assertEquals(1,routes.routes(GOAL,original).length());routes.withdraw(candidate.getString("routeId"),candidate.getString("digest"),original);routes.requireCurrentSources(original);assertEquals(0,routes.routes(GOAL,original).length());assertNotNull(fixture.tasks.phoneRouteSourceMetadata(source.getString("id")));}
    }
    @Test public void sameTaskColdRepositoryStillChecksTheOriginalStickySourceReferences()throws Exception{
        try(Fixture fixture=new Fixture()){JSONObject source=fixture.source(true),active=fixture.start(GOAL);RunControl original=fixture.control(active);assertEquals(1,fixture.repository(active,original).routes(GOAL,original).length());TaskStore reopened=new TaskStore(fixture.app);TaskStorePhoneRoutes restored=fixture.repository(reopened,active,original);restored.requireCurrentSources(original);fixture.tasks.forgetMemorySourceSnapshot(SourceRef.fromJson(fixture.tasks.phoneRouteSourceMetadata(source.getString("id")).getJSONObject("sourceRef")));assertThrows(PhoneRouteSession.SourceUnavailable.class,()->restored.requireCurrentSources(original));}
    }
}
