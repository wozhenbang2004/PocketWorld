package dev.pocketengine.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual existing Controller/App slots/native owner/Book; fixture helper does not fake replies. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class OwnerMemoryHistoryControllerNativeTest {
    private static JSONObject owner()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Test public void repeatedHistoryReadsUseTheOriginalReadReplacementAndIgnoreTheCancelledQuery()throws Exception {
        try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            f.memory.handle("save",new JSONObject().put("scope",owner()).put("record",new JSONObject().put("text","乙城旧事")));f.enter();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            f.io.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));String second;
            try{String first=f.controller.requestOwnerDomain("memoryReview","searchHistory",new JSONObject().put("query","甲城").put("limit",12));second=f.controller.requestOwnerDomain("memoryReview","searchHistory",new JSONObject().put("query","乙城").put("limit",12));assertNotEquals(first,second);}finally{release.countDown();}
            f.settle();JSONObject reply=f.response("searchHistory");assertNotNull(reply);assertEquals(second,reply.getString("requestId"));assertTrue(reply.toString(),reply.getBoolean("ok"));assertEquals("乙城",reply.getJSONObject("result").getString("query"));assertEquals(1,reply.getJSONObject("result").getJSONArray("matches").length());
        }
    }
    @Test public void switchingToAnOpaqueProjectScopeDiscardsQueuedPrivateHistoryAndNeverFallsBackGlobal()throws Exception {
        try(var f=new ProjectMemoryReviewUiNativeTest.Fixture()){
            JSONObject project=f.create("历史作用域测试");f.memory.handle("save",new JSONObject().put("scope",owner()).put("record",new JSONObject().put("text","私人旧事不能跟进项目")));f.enter();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            f.io.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{f.controller.requestOwnerDomain("memoryReview","searchHistory",new JSONObject().put("query","私人旧事"));f.controller.selectMemoryNamespace(project);}finally{release.countDown();}
            f.settle();assertEquals("project",f.snapshot().getString("namespaceStatus"));assertFalse(f.controller.getState().ownerResponses("memoryReview").containsKey("searchHistory"));JSONObject reply=f.request("searchHistory",new JSONObject().put("query","私人旧事"));assertNotNull(reply);assertTrue(reply.toString(),reply.getBoolean("ok"));JSONObject result=reply.getJSONObject("result");assertEquals(0,result.getJSONArray("matches").length());assertEquals(project.getString("spaceId"),result.getJSONObject("scope").getString("spaceId"));assertFalse(result.getBoolean("activeRecall"));assertFalse(result.getBoolean("originalEvidenceRead"));
        }
    }
}
