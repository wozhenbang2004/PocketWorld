package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.routes.PhoneRouteSession;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original SQL receipts/owner verification/withdrawal, with a synthetic native tree. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskStorePhoneRoutesTest {
    @Test public void onlyCurrentOwnerConfirmedOriginalReceiptsCanBecomeARoute()throws Exception {
        var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);String goal="打开搜索";JSONObject source=tasks.startAttempt(tasks.createCapabilityTask(goal,"").getString("id"),"capabilities",8);String sourceId=source.getString("id"),sourceRun=source.getString("runId");int epoch=source.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(sourceId,sourceRun,epoch);tasks.authorizeTools(sourceId,sourceRun,new JSONArray().put("phone_click_node"));RunJournal journal=tasks.journal(sourceId,sourceRun,epoch,original);JSONObject args=new JSONObject().put("observationId","o").put("nodeId","n"),receipt=new JSONObject().put("ok",true).put("accepted",true);String op=journal.beginOperation("phone_click_node",args);journal.finishOperation(op,receipt);
        JSONObject tree=new JSONObject().put("ok",true).put("observationId","o").put("packageName","app.example").put("treeSchemaVersion",1).put("observationSources",new JSONObject().put("treeAvailable",true)).put("windows",new JSONArray().put(new JSONObject().put("rootAvailable",true))).put("nodes",new JSONArray().put(new JSONObject().put("id","n").put("resourceId","app.example:id/search").put("role","Button").put("text","搜索").put("visible",true).put("enabled",true)));
        PhonePort phone=new PhonePort(){public boolean isConnected(){return true;}public JSONObject observe(boolean image,RunControl control){return tree;}public JSONObject execute(String name,JSONObject a,RunControl c){throw new AssertionError("No device action in route eligibility test");}public void clearReferences(){}};PhoneRouteSession recording=new PhoneRouteSession(sourceId,goal,phone,original,new TaskStorePhoneRoutes(tasks,sourceId,original,pkg->1),false);recording.after("phone_click_node",args,receipt,tree,op,original);JSONObject candidate=recording.candidate(original);assertNotNull(candidate);tasks.finish(sourceId,"needs_confirmation","待主人核对",new JSONObject().put("nativePhoneRoute",candidate));assertNull(TaskStorePhoneRoutes.eligible(tasks.get(sourceId,false)));
        tasks.event(sourceId,new JSONObject().put("type","user_verification").put("completed",true));assertNotNull(TaskStorePhoneRoutes.eligible(tasks.get(sourceId,false)));
        JSONObject active=tasks.startAttempt(tasks.createCapabilityTask(goal,"").getString("id"),"capabilities",8);RunControl current=new RunControl();current.bindHostRun(active.getString("id"),active.getString("runId"),active.getInt("coordinatorEpoch"));TaskStorePhoneRoutes routes=new TaskStorePhoneRoutes(tasks,active.getString("id"),current,pkg->1);assertEquals(1,routes.routes(goal,current).length());assertEquals(0,routes.routes("不同目标",current).length());
        tasks.event(sourceId,new JSONObject().put("type","user_verification").put("completed",false));assertEquals(0,routes.routes(goal,current).length());tasks.event(sourceId,new JSONObject().put("type","user_verification").put("completed",true));routes.withdraw(candidate.getString("routeId"),candidate.getString("digest"),current);assertEquals(0,routes.routes(goal,current).length());assertThrows(SecurityException.class,()->routes.route(candidate.getString("routeId"),candidate.getString("digest"),goal,current));
    }
}
