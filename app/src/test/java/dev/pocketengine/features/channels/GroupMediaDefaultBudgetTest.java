package dev.pocketengine.features.channels;

import android.app.Application;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The ordinary media command must be usable within, never above, the owner's saved policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class GroupMediaDefaultBudgetTest {
    @Before public void keyFixture(){new GroupTaskFlowsTest().keyFixture();}
    @After public void removeFixture(){new GroupTaskFlowsTest().removeFixture();}
    @Test public void defaultAudioReachesExactApprovalAndSameTaskMediaWithoutBudgetReset()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            f.enable("media.audio");long ownerLimit=f.group.getJSONObject("quota").getLong("maxTokensPerTask");
            f.model=request->f.modelCalls.get()<3?GroupTaskFlowsTest.tool("group_media_audio","{\"prompt\":\"Read this public group greeting\"}"):GroupTaskFlowsTest.message("Actual audio produced.");
            f.send("/audio Create one public greeting",false);JSONObject first=f.task();assertEquals(first.toString(),"awaiting_user",first.getString("status"));assertEquals(1,f.modelCalls.get());assertEquals(0,f.mediaCalls.get());assertEquals(32000,first.getJSONObject("origin").getLong("maxTokens"));assertEquals(ownerLimit,f.groups.ownerSnapshot().getJSONArray("groups").getJSONObject(0).getJSONObject("quota").getLong("maxTokensPerTask"));
            String id=first.getString("id"),request=first.getJSONObject("origin").getString("requestId");long spent=first.getLong("groupReservedTokens");assertTrue(spent>0);
            f.tasks.decideCapabilityApproval(id,first.getJSONArray("approvals").getJSONObject(0).getString("id"),true);f.groups.ownerCommand("task.resume",new JSONObject().put("requestId",request));f.drain();JSONObject resumed=f.task();assertEquals(id,resumed.getString("id"));assertEquals(2,resumed.getInt("coordinatorEpoch"));assertEquals(resumed.toString(),"needs_confirmation",resumed.getString("status"));assertEquals(1,f.mediaCalls.get());assertTrue(resumed.getLong("groupReservedTokens")>spent);assertTrue(resumed.getLong("groupReservedTokens")<=32000);
            f.tasks.status(id,"completed","Native owner verified actual audio");f.host.publishTaskState(id);f.drain();assertEquals(1,f.uploads.get());assertEquals(2,f.replies.size());
        }
    }
    @Test public void savedLowOwnerLimitBlocksAtIngressWithoutTaskModelOrPolicyMutation()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            f.enable("media.audio");JSONObject quota=f.group.getJSONObject("quota");quota.put("maxTokensPerTask",4000);f.group=f.groups.ownerCommand("policy.set",new JSONObject().put("id",f.groupId).put("revision",f.group.getLong("revision")).put("enabled",true).put("visibility","requester").put("quota",quota).put("grants",f.group.getJSONArray("grants")));
            f.send("/audio Create one public greeting",false);assertEquals(0,f.tasks.list().length());assertEquals(0,f.modelCalls.get());assertEquals(0,f.mediaCalls.get());JSONObject entry=f.channels.snapshot().getJSONArray("inbox").getJSONObject(0);assertEquals("group_task_blocked",entry.getString("status"));assertTrue(entry.toString().contains("32000"));assertTrue(entry.toString().contains("4000"));assertTrue(entry.toString().contains("未启动模型"));JSONObject saved=f.groups.ownerSnapshot().getJSONArray("groups").getJSONObject(0);assertEquals(4000,saved.getJSONObject("quota").getLong("maxTokensPerTask"));assertEquals(0,saved.getJSONObject("usage").getLong("reservedTokens"));
        }
    }
    @Test public void explicitInsufficientMediaReservationGetsAnIngressExplanation()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            f.enable("media.audio");f.send("/audio --tokens=4000 Create one public greeting",false);assertEquals(0,f.tasks.list().length());assertEquals(0,f.modelCalls.get());assertEquals(0,f.mediaCalls.get());JSONObject entry=f.channels.snapshot().getJSONArray("inbox").getJSONObject(0);assertEquals("group_task_blocked",entry.getString("status"));assertTrue(entry.toString().contains("32000"));assertTrue(entry.toString().contains("4000"));assertTrue(entry.toString().contains("未启动模型"));assertEquals(60000,f.groups.ownerSnapshot().getJSONArray("groups").getJSONObject(0).getJSONObject("quota").getLong("maxTokensPerTask"));
        }
    }
}
