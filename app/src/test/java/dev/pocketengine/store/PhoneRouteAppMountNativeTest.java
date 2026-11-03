package dev.pocketengine.store;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.runtime.routes.PhoneRouteSession;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original App installs the native factory. Package/tree/ack are explicit fixtures, not phone acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneRouteAppMountNativeTest {
    private EngineApplication app;
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static RunControl control(JSONObject task)throws Exception{RunControl live=new RunControl();live.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));return live;}
    @After public void close()throws Exception{
        PhoneRouteSession.installFactory((task,control)->null);
        if(app!=null){ExecutorService workers=(ExecutorService)field(app,"capabilityWorkers");workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);app.features.close();}
    }
    @Test public void originalAppFactoryUsesInstalledVersionNativeConfirmationAndCanonicalForgetWithoutAnAlternativeLedger()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();TaskStore tasks=app.tasks;assertTrue(app.features.module("memory") instanceof MemoryModule);
        PackageInfo installed=new PackageInfo();installed.packageName="app.example";installed.versionCode=42;installed.applicationInfo=new ApplicationInfo();installed.applicationInfo.packageName=installed.packageName;Shadows.shadowOf(app.getPackageManager()).installPackage(installed);
        String goal="打开搜索";JSONObject source=tasks.startAttempt(tasks.create(goal,"main_screen").getString("id"),"main_screen",8);RunControl owner=control(source);
        JSONObject scene=new JSONObject().put("ok",true).put("observationId","mounted-native-tree").put("packageName",installed.packageName).put("treeSchemaVersion",1).put("observationSources",new JSONObject().put("treeAvailable",true)).put("windows",new JSONArray().put(new JSONObject().put("rootAvailable",true))).put("nodes",new JSONArray().put(new JSONObject().put("id","mounted-search").put("resourceId","app.example:id/search").put("role","Button").put("text","搜索").put("visible",true).put("enabled",true)));
        PhonePort phone=new PhonePort(){public boolean isConnected(){return true;}public JSONObject observe(boolean pixels,RunControl live)throws Exception{live.check();return new JSONObject(scene.toString());}public JSONObject execute(String t,JSONObject a,RunControl c){throw new AssertionError("No physical device effect belongs to a mounted source/factory test");}public void clearReferences(){}};
        // No test installFactory call: the original App onCreate installed the production adapter.
        PhoneRouteSession recording=PhoneRouteSession.open(source.getString("id"),goal,phone,owner,false);assertNotNull("real App mount",recording);
        JSONObject arguments=new JSONObject().put("observationId",scene.getString("observationId")).put("nodeId","mounted-search"),receipt=new JSONObject().put("ok",true).put("accepted",true);
        var journal=tasks.journal(source.getString("id"),source.getString("runId"),source.getInt("coordinatorEpoch"),owner);String operation=journal.beginOperation("phone_click_node",arguments);journal.finishOperation(operation,receipt);recording.after("phone_click_node",arguments,receipt,scene,operation,owner);JSONObject candidate=recording.candidate(owner);assertEquals(42,candidate.getLong("packageVersion"));assertFalse(candidate.getBoolean("businessVerified"));recording.close();
        tasks.endAttempt(source.getString("id"),source.getString("runId"),"needs_confirmation","Native fixture receipt, not physical success",new JSONObject().put("nativePhoneRoute",candidate));assertNull(tasks.phoneRouteSourceMetadata(source.getString("id")));JSONObject reviewed=tasks.get(source.getString("id"),false);tasks.confirmReviewedResult(source.getString("id"),reviewed.getString("resultReviewDigest"),true,"");
        JSONObject next=tasks.startAttempt(tasks.create(goal,"main_screen").getString("id"),"main_screen",8);RunControl original=control(next);PhoneRouteSession routes=PhoneRouteSession.open(next.getString("id"),goal,phone,original,false);assertNotNull(routes);JSONObject listed=routes.execute(PhoneRouteSession.LIST,new JSONObject(),scene,"mounted-list",original);assertEquals(1,listed.getJSONArray("routes").length());assertFalse(listed.getJSONArray("routes").getJSONObject(0).getBoolean("executionAuthorized"));assertEquals(1,tasks.phoneRouteDependencies(next.getString("id")).length());
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private"),ref=tasks.phoneRouteSourceMetadata(source.getString("id")).getJSONObject("sourceRef");MemoryModule memory=(MemoryModule)app.features.module("memory");JSONObject record=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("layer","episodic").put("text","Owner-selected original confirmed navigation result").put("source","主人明确保存").put("sourceRefs",new JSONArray().put(ref)))).getJSONObject("record");
        ((MemorySourceBridge)field(app,"memorySources")).forgetReviewed(scope,record.getString("id"),record.getLong("revision"));assertThrows(PhoneRouteSession.SourceUnavailable.class,()->routes.requireCurrentSources(original));assertNotNull(tasks.get(source.getString("id"),false));routes.close();
        JSONObject assistant=tasks.createCapabilityTask("Assistant is not the local phone domain","");tasks.initializeExecutionScope(assistant.getString("id"),4);assistant=tasks.startAttempt(assistant.getString("id"),"capabilities",4);assertNull(PhoneRouteSession.open(assistant.getString("id"),assistant.getString("goal"),phone,control(assistant),false));
    }
}
