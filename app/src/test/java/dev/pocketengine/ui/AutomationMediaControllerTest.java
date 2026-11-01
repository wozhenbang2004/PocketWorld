package dev.pocketengine.ui;

import dev.pocketengine.EngineApplication;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual native Controller -> FeatureHost -> SQLite preparation, with zero generation/network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class AutomationMediaControllerTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("MediaUiKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    interface Check{boolean done()throws Exception;}
    static void await(Check check)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!check.done()&&System.nanoTime()<until){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10);}Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertTrue("actual native controller condition missing",check.done());}
    @Test public void nativeOwnerPreparationReturnsTheOriginalTaskWithExactInputAndPlanBinding()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app);
        try{controller.attach();controller.requestFeature("media","configure",new JSONObject().put("provider","comfyui").put("baseUrl","http://127.0.0.1:9"));await(()->controller.getState().getModuleResults().containsKey("media.configure"));String revision=controller.getState().getModuleResults().get("media.configure").getString("configurationRevision");
            JSONObject args=new JSONObject().put("configurationRevision",revision).put("workflow",new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text","native UI fixture"))));String request=controller.requestFeature("media","prepareSubmission",new JSONObject().put("submissionCommand","submitWorkflow").put("arguments",args));await(()->request.equals(controller.getState().getModuleResultInputs().getOrDefault("media.prepareSubmission",new JSONObject()).optString("_requestId")));
            JSONObject prepared=MediaOwnerUi.INSTANCE.prepared(controller.getState().getModuleResults().get("media.prepareSubmission"),controller.getState().getModuleResultInputs().get("media.prepareSubmission"),request,"submitWorkflow",args);assertNotNull(prepared);String id=prepared.getString("taskId");assertEquals(1,app.tasks.list().length());assertEquals(0,app.tasks.get(id,false).getJSONArray("operations").length());assertTrue(MediaOwnerUi.INSTANCE.isTask(app.tasks.get(id,false)));
            controller.focusTask(id);await(()->id.equals(controller.getState().getRequestedTaskId())&&controller.getState().getTasks().stream().anyMatch(row->row.getId().equals(id)));assertNull(MediaOwnerUi.INSTANCE.prepared(controller.getState().getModuleResults().get("media.prepareSubmission"),controller.getState().getModuleResultInputs().get("media.prepareSubmission"),"stale-page-request","submitWorkflow",args));
            String original=app.tasks.createCapabilityTask("已有真实任务","").getString("id");controller.requestFeature("automation","createPlan",new JSONObject().put("plan",new JSONObject().put("kind","explicit_user").put("title","当前原生计划").put("steps",new JSONArray().put(new JSONObject().put("title","核对原任务").put("taskId",original).put("dueAt",System.currentTimeMillis()+60_000)))));await(()->controller.getState().getModuleResults().containsKey("automation.createPlan"));JSONObject step=controller.getState().getModuleResults().get("automation.createPlan").getJSONObject("plan").getJSONArray("steps").getJSONObject(0);assertEquals(original,step.getString("taskId"));assertEquals(1,app.tasks.get(original,false).getJSONArray("commitmentBindings").length());
        }finally{controller.detach();app.features.close();java.security.Security.removeProvider("MediaUiKeyStore");}
    }
}
