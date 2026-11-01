package dev.pocketengine.ui;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.memory.MemoryModule;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Preserve unchanged: a stopped native request cannot later claim a newly staged candidate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourceLearningCommitNativeTest {
    @Before public void keys(){new TaskMemoryNativeWorkflowTest().keys();}
    private JSONObject draft()throws Exception{return TaskMemoryNativeWorkflowTest.j("layer","fact","text","SOURCE_LEARNING_NATIVE_681 有待核对的项目习惯","reason","仅由主人选择的原任务来源支持","applicability","仅这个项目情境，未推广为稳定偏好","confidence",0.6);}
    private JSONObject result(TaskMemoryNativeWorkflowTest.Fixture f,String id)throws Exception{
        TaskMemoryNativeWorkflowTest.await(()->id.equals(f.controller.getState().ownerResponses("learning").getOrDefault("proposeLearning",new JSONObject()).optString("requestId")));
        JSONObject envelope=f.controller.getState().ownerResponses("learning").get("proposeLearning");assertTrue(envelope.optString("error"),envelope.getBoolean("ok"));return envelope.getJSONObject("result");
    }
    private JSONArray candidates(TaskMemoryNativeWorkflowTest.Fixture f)throws Exception{return f.memory.handle("listCandidates",TaskMemoryNativeWorkflowTest.j("scope",TaskMemoryNativeWorkflowTest.scope())).getJSONArray("candidates");}
    @Test public void aRejectedSourceCandidateCannotBeReportedAsFreshlyProposed()throws Exception{
        try(TaskMemoryNativeWorkflowTest.Fixture f=new TaskMemoryNativeWorkflowTest.Fixture()){
            f.answer=draft().toString();f.controller.enterOwnerDomain("learning");JSONObject payload=new JSONObject().put("sourceRef",f.source.toJson());
            JSONObject first=result(f,f.controller.requestOwnerDomain("learning","proposeLearning",payload)),candidate=first.getJSONObject("candidate");assertEquals("pending",candidate.getString("status"));
            f.memory.handle("rejectCandidate",TaskMemoryNativeWorkflowTest.j("scope",TaskMemoryNativeWorkflowTest.scope(),"id",candidate.getString("id"),"revision",candidate.getLong("revision"),"reason","主人不接受这个推断"));
            JSONObject second=result(f,f.controller.requestOwnerDomain("learning","proposeLearning",payload));
            assertEquals("rejected",second.getJSONObject("candidate").getString("status"));
            assertFalse("existing rejection was falsely reported as a new proposed candidate",second.getBoolean("proposed"));
            assertFalse(second.getBoolean("reviewRequired"));assertEquals("existing_candidate_barrier",second.getString("outcome"));assertEquals(1,candidates(f).length());
        }
    }
    @Test public void successfulNativeStopCannotLeaveACandidateAfterWaitingForTheBookCommit()throws Exception{
        try(TaskMemoryNativeWorkflowTest.Fixture f=new TaskMemoryNativeWorkflowTest.Fixture()){
            f.answer=draft().toString();f.blockCall=1;f.controller.enterOwnerDomain("learning");String request=f.controller.requestOwnerDomain("learning","proposeLearning",new JSONObject().put("sourceRef",f.source.toJson()));assertTrue(f.blocked.await(4,TimeUnit.SECONDS));
            var field=MemoryModule.class.getDeclaredField("book");field.setAccessible(true);Object book=field.get(f.memory);
            AtomicReference<JSONObject> cancel=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();Thread cancelling;Thread worker;
            synchronized(book){
                f.release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);Thread found=null;
                while(found==null&&System.nanoTime()<until){for(var e:Thread.getAllStackTraces().entrySet())if(e.getKey().getState()==Thread.State.BLOCKED&&java.util.Arrays.stream(e.getValue()).anyMatch(s->s.getClassName().equals("dev.pocketengine.features.memory.MemoryBook")&&s.getMethodName().equals("proposeLearning"))){found=e.getKey();break;}if(found==null)Thread.sleep(5);}
                assertNotNull("native worker never reached its final Book submission",found);worker=found;
                cancelling=new Thread(()->{try{String id=f.controller.cancelLearning(request);cancel.set(f.controller.getState().ownerResponses("learning").get("cancelLearning"));assertEquals(id,cancel.get().getString("requestId"));}catch(Throwable failure){error.set(failure);}},"source-learning-owner-stop");cancelling.start();
                until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(cancelling.isAlive()&&cancelling.getState()!=Thread.State.BLOCKED&&System.nanoTime()<until)Thread.sleep(5);
                assertTrue("native stop neither settled nor reached the shared admission boundary",!cancelling.isAlive()||cancelling.getState()==Thread.State.BLOCKED);
            }
            cancelling.join(4000);assertFalse("native stop remained blocked after the short Book commit",cancelling.isAlive());if(error.get()!=null)throw new AssertionError(error.get());
            TaskMemoryNativeWorkflowTest.await(()->java.util.Arrays.stream(worker.getStackTrace()).noneMatch(s->s.getClassName().contains("KnowledgeLearningWorkflow")));
            JSONObject receipt=cancel.get().getJSONObject("result");
            if(receipt.getBoolean("cancelled"))assertEquals("UI acknowledged stopped, but the late model candidate was persisted",0,candidates(f).length());
            else{assertEquals("already_settled",receipt.getString("outcome"));assertEquals(1,candidates(f).length());}
        }
    }
}
