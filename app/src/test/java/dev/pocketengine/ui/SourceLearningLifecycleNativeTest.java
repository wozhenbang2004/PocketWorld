package dev.pocketengine.ui;

import dev.pocketengine.EngineApplication;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Exact real task source, original native request authority and one no-tool local HTTP call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourceLearningLifecycleNativeTest {
    @Before public void keys(){new TaskMemoryNativeWorkflowTest().keys();}
    private JSONObject draft()throws Exception{return TaskMemoryNativeWorkflowTest.j("layer","procedural","text","备份前后核对目录与文件数量","reason","仅来自当前选中来源","applicability","仅相同项目情境，效果仍未独立验证","confidence",0.5);}
    private JSONObject submit(TaskMemoryNativeWorkflowTest.Fixture f)throws Exception{return new JSONObject().put("sourceRef",f.source.toJson());}
    private JSONArray candidates(TaskMemoryNativeWorkflowTest.Fixture f)throws Exception{return f.memory.handle("listCandidates",TaskMemoryNativeWorkflowTest.j("scope",TaskMemoryNativeWorkflowTest.scope())).getJSONArray("candidates");}
    private JSONObject response(TaskMemoryNativeWorkflowTest.Fixture f,String id)throws Exception{
        TaskMemoryNativeWorkflowTest.await(()->id.equals(f.controller.getState().ownerResponses("learning").getOrDefault("proposeLearning",new JSONObject()).optString("requestId")));
        return f.controller.getState().ownerResponses("learning").get("proposeLearning");
    }
    @Test public void previewDoesNotCallModelAndCandidateUsesTheOriginalSourceTaskAndNoTools()throws Exception{
        try(TaskMemoryNativeWorkflowTest.Fixture f=new TaskMemoryNativeWorkflowTest.Fixture()){
            f.answer=draft().toString();f.controller.enterOwnerDomain("learning");
            JSONObject preview=f.app.nativeOwnerWorkflows.learning("prepareLearningSource",TaskMemoryNativeWorkflowTest.j("kind","task_evidence","sourceId",f.source.sourceId),new dev.pocketengine.core.RunControl());
            assertFalse(preview.getBoolean("modelCalled"));assertTrue(preview.getBoolean("learningAllowed"));assertEquals(f.source.toJson().toString(),preview.getJSONObject("sourceRef").toString());assertTrue(preview.getString("text").contains("主人核对时的原结果"));assertEquals(0,f.calls.get());
            int formal=f.memory.handle("list",TaskMemoryNativeWorkflowTest.j("scope",TaskMemoryNativeWorkflowTest.scope())).getJSONArray("records").length();
            JSONObject envelope=response(f,f.controller.requestOwnerDomain("learning","proposeLearning",submit(f)));assertTrue(envelope.optString("error"),envelope.getBoolean("ok"));JSONObject result=envelope.getJSONObject("result");
            assertEquals("awaiting_review",result.getString("outcome"));assertFalse(result.getBoolean("formalMemorySaved"));assertTrue(result.getBoolean("reviewRequired"));assertEquals("pending",result.getJSONObject("candidate").getString("status"));assertEquals(f.source.toJson().toString(),result.getJSONObject("candidate").getJSONArray("sourceRefs").getJSONObject(0).toString());
            JSONObject task=f.app.tasks.get(result.getString("hostTaskId"),false);assertEquals("completed",task.getString("status"));assertEquals(result.getString("runId"),task.getString("runId"));assertEquals(result.getInt("epoch"),task.getInt("coordinatorEpoch"));assertEquals(0,task.getJSONArray("operations").length());
            assertEquals(1,f.calls.get());JSONObject wire=f.requests.get(0);assertTrue(wire.optJSONArray("tools")==null||wire.getJSONArray("tools").length()==0);assertEquals(2,wire.getJSONArray("messages").length());assertTrue(wire.getJSONArray("messages").getJSONObject(1).getString("content").contains("主人核对时的原结果"));
            assertEquals(formal,f.memory.handle("list",TaskMemoryNativeWorkflowTest.j("scope",TaskMemoryNativeWorkflowTest.scope())).getJSONArray("records").length());
        }
    }
    @Test public void stoppingDuringTheSlowCallSettlesBeforeTheServerIsReleasedAndCannotStageLateCandidate()throws Exception{
        try(TaskMemoryNativeWorkflowTest.Fixture f=new TaskMemoryNativeWorkflowTest.Fixture()){
            f.answer=draft().toString();f.blockCall=1;f.controller.enterOwnerDomain("learning");String id=f.controller.requestOwnerDomain("learning","proposeLearning",submit(f));assertTrue(f.blocked.await(4,TimeUnit.SECONDS));
            String stopped=f.controller.cancelLearning(id);JSONObject envelope=f.controller.getState().ownerResponses("learning").get("cancelLearning");assertEquals(stopped,envelope.getString("requestId"));assertTrue(envelope.getBoolean("ok"));assertTrue(envelope.getJSONObject("result").getBoolean("cancelled"));assertEquals(1,f.release.getCount());
            String taskId=envelope.getJSONObject("result").getString("hostTaskId");f.release.countDown();TaskMemoryNativeWorkflowTest.await(()->"cancelled".equals(f.app.tasks.get(taskId,false).optString("status")));assertEquals(0,candidates(f).length());assertEquals(1,f.calls.get());
        }
    }
    @Test public void forgettingDuringTheCallBlocksTheLateDraftAndFreshPreviewWithoutResendingTheBody()throws Exception{
        try(TaskMemoryNativeWorkflowTest.Fixture f=new TaskMemoryNativeWorkflowTest.Fixture()){
            f.answer=draft().toString();f.blockCall=1;f.controller.enterOwnerDomain("learning");String id=f.controller.requestOwnerDomain("learning","proposeLearning",submit(f));assertTrue(f.blocked.await(4,TimeUnit.SECONDS));
            f.app.nativeOwnerWorkflows.memory("forgetReviewed",TaskMemoryNativeWorkflowTest.j("id",f.old.getString("id"),"expectedRevision",f.old.getLong("revision")));f.release.countDown();assertFalse(response(f,id).getBoolean("ok"));assertEquals(0,candidates(f).length());
            JSONObject preview=f.app.nativeOwnerWorkflows.learning("prepareLearningSource",TaskMemoryNativeWorkflowTest.j("kind","task_evidence","sourceId",f.source.sourceId),new dev.pocketengine.core.RunControl());assertEquals("source_forgotten",preview.getString("status"));assertFalse(preview.getBoolean("learningAllowed"));assertFalse(preview.getBoolean("modelCalled"));assertFalse(preview.has("text"));
            assertFalse(response(f,f.controller.requestOwnerDomain("learning","proposeLearning",submit(f))).getBoolean("ok"));assertEquals(1,f.calls.get());assertEquals(0,candidates(f).length());
        }
    }
    @Test public void revokedConfirmationDuringTheCallCannotBecomeCandidateEvidence()throws Exception{
        try(TaskMemoryNativeWorkflowTest.Fixture f=new TaskMemoryNativeWorkflowTest.Fixture()){
            f.answer=draft().toString();f.blockCall=1;f.controller.enterOwnerDomain("learning");String id=f.controller.requestOwnerDomain("learning","proposeLearning",submit(f));assertTrue(f.blocked.await(4,TimeUnit.SECONDS));
            f.app.dispatchOwnerAction("confirmResult",TaskMemoryNativeWorkflowTest.j("taskId",f.sourceTask,"completed",false));f.release.countDown();assertFalse(response(f,id).getBoolean("ok"));assertEquals(0,candidates(f).length());assertEquals(1,f.calls.get());
        }
    }
}
