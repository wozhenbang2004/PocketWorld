package dev.pocketengine.ui;

import dev.pocketengine.EngineApplication;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual native owner dispatcher and original SQL store; no alternate result registry. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskResultConfirmationVersionTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("ResultReviewKeyStore","1","Test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    private EngineApplication app(){return (EngineApplication)RuntimeEnvironment.getApplication();}
    private String task()throws Exception{return app().tasks.createCapabilityTask("核对原成果","").getString("id");}
    private JSONObject finish(String id,String text)throws Exception{app().tasks.finish(id,"needs_confirmation",text,new JSONObject().put("modelSummary",text));return app().tasks.get(id,false);}
    private JSONObject review(JSONObject task,boolean done)throws Exception{return new JSONObject().put("taskId",task.getString("id")).put("resultReviewDigest",task.optString("resultReviewDigest")).put("completed",done).put("feedback",done?"":"还需修改");}
    private void confirm(JSONObject input)throws Exception{app().dispatchOwnerAction("confirmResult",input);}
    private int confirmations(String id)throws Exception{JSONArray events=app().tasks.get(id,false).getJSONArray("events");int n=0;for(int i=0;i<events.length();i++)if("user_verification".equals(events.getJSONObject(i).optString("type")))n++;return n;}
    @Test public void oldPositiveAndNegativeReviewCannotConfirmFinishedSuccessor()throws Exception{
        for(boolean done:new boolean[]{true,false}){
            String id=task();app().tasks.startAttempt(id,"capabilities",4);JSONObject r1=finish(id,"R1");JSONObject old=review(r1,done);
            app().tasks.startAttempt(id,"capabilities",4);JSONObject r2=finish(id,"R2");
            assertThrows(SecurityException.class,()->confirm(old));assertEquals("needs_confirmation",app().tasks.get(id,false).getString("status"));assertEquals("R2",app().tasks.get(id,false).getString("summary"));assertEquals(0,confirmations(id));
            confirm(review(r2,true));JSONObject current=app().tasks.get(id,false);assertEquals("completed",current.getString("status"));JSONArray events=current.getJSONArray("events");JSONObject verified=events.getJSONObject(events.length()-1);assertEquals(r2.getString("runId"),verified.getString("runId"));assertEquals(r2.getInt("coordinatorEpoch"),verified.getInt("coordinatorEpoch"));
        }
    }
    @Test public void currentConfirmationIsIdempotentAndCannotCrossTasks()throws Exception{
        String a=task(),b=task();JSONObject current=finish(a,"same"),other=finish(b,"same"),input=review(current,true);
        assertThrows(SecurityException.class,()->confirm(new JSONObject(input.toString()).put("taskId",b)));assertEquals(0,confirmations(b));
        confirm(input);confirm(input);assertEquals(1,confirmations(a));assertEquals("completed",app().tasks.get(a,false).getString("status"));
        assertThrows(SecurityException.class,()->confirm(new JSONObject(input.toString()).put("completed",false)));assertEquals(1,confirmations(a));
        confirm(review(other,false));assertEquals("awaiting_user",app().tasks.get(b,false).getString("status"));assertEquals("还需修改",app().tasks.get(b,false).getString("feedback"));
    }
    @Test public void activeUnknownAndOversizedFeedbackCannotWriteConfirmation()throws Exception{
        String id=task();JSONObject attempt=app().tasks.startAttempt(id,"capabilities",4);dev.pocketengine.core.RunControl original=new dev.pocketengine.core.RunControl();original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
        app().tasks.status(id,"running","执行中");app().tasks.authorizeTools(id,attempt.getString("runId"),new JSONArray().put("fixture"));
        var journal=app().tasks.journal(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),original);String operation=journal.beginOperation("fixture",new JSONObject());journal.finishOperation(operation,new JSONObject().put("status","unknown"));
        JSONObject shown=finish(id,"等待核对");assertThrows(IllegalStateException.class,()->confirm(review(shown,true)));assertEquals(0,confirmations(id));
        String clean=task();JSONObject visible=finish(clean,"成果");var field=EngineApplication.class.getDeclaredField("capabilityControls");field.setAccessible(true);@SuppressWarnings("unchecked") java.util.Map<String,dev.pocketengine.core.RunControl> controls=(java.util.Map<String,dev.pocketengine.core.RunControl>)field.get(app());controls.put(clean,new dev.pocketengine.core.RunControl());
        try{assertThrows(IllegalStateException.class,()->confirm(review(visible,true)));}finally{controls.remove(clean);}
        assertThrows(IllegalArgumentException.class,()->confirm(review(visible,true).put("feedback","x".repeat(8001))));assertEquals(0,confirmations(clean));assertEquals("needs_confirmation",app().tasks.get(clean,false).getString("status"));
        confirm(review(visible,true));assertEquals(1,confirmations(clean));
    }
    @Test public void queuedNativeUiClickRetainsDisplayedVersionUntilDispatch()throws Exception{
        String id=task();app().tasks.startAttempt(id,"capabilities",4);JSONObject shown=finish(id,"R1");
        PocketWorldController controller=new PocketWorldController(new ComposeActivity(),app());
        var field=PocketWorldController.class.getDeclaredField("io");field.setAccessible(true);java.util.concurrent.ExecutorService io=(java.util.concurrent.ExecutorService)field.get(controller);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        try{
            io.execute(()->{entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            controller.confirmReviewedTask(new UiTask(id,shown.getString("goal"),shown.getString("status"),shown.getString("summary"),shown.getString("executionMode"),shown.getLong("updatedAt"),shown),true,"");
            app().tasks.startAttempt(id,"capabilities",4);finish(id,"R2");release.countDown();io.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(0,confirmations(id));assertEquals("needs_confirmation",app().tasks.get(id,false).getString("status"));assertTrue(controller.getState().getTaskActionErrors().get(id).contains("成果版本已变化"));
        }finally{release.countDown();controller.detach();}
    }
    @Test public void nativeUiDetailAndSummaryCarrySameCapturedDigest()throws Exception{
        String id=task();JSONObject shown=finish(id,"原成果");String digest=shown.getString("resultReviewDigest");assertEquals(digest,app().tasks.getForUi(id,false).getString("resultReviewDigest"));
        JSONArray summaries=app().tasks.taskSummaryPage("",40).getJSONArray("tasks");boolean found=false;for(int i=0;i<summaries.length();i++)if(id.equals(summaries.getJSONObject(i).getString("id"))){found=true;assertEquals(digest,summaries.getJSONObject(i).getString("resultReviewDigest"));}assertTrue(found);
        confirm(review(app().tasks.getForUi(id,false),true));
        dev.pocketengine.store.TaskStore reopened=new dev.pocketengine.store.TaskStore(app());try{reopened.confirmReviewedResult(id,digest,true,"");assertEquals(1,confirmations(id));}finally{var field=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(reopened)).close();}
    }
    @Test public void sameRunResultChangesAndMissingReviewAreRejected()throws Exception{
        String id=task();JSONObject old=finish(id,"R1");finish(id,"R2");finish(id,"R1");
        assertThrows(SecurityException.class,()->confirm(review(old,true)));
        assertThrows(SecurityException.class,()->confirm(new JSONObject().put("taskId",id).put("completed",true)));
        JSONObject current=app().tasks.get(id,false);app().tasks.status(id,"running","正在运行");assertThrows(SecurityException.class,()->confirm(review(current,true)));assertEquals(0,confirmations(id));
    }
}
