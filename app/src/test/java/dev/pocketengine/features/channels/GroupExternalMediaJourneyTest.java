package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import android.app.Application;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Real signature ingress/SQLite/Runner/media/artifact/outbox; provider, key store and QQ endpoints are loopback fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class GroupExternalMediaJourneyTest {
    final GroupTaskFlowsTest keys=new GroupTaskFlowsTest();
    @Before public void fixtureKeys(){keys.keyFixture();}
    @After public void removeFixtureKeys(){keys.removeFixture();}
    @Test public void sameExternalTaskShowsNativeApprovalRestartsThenReturnsRealFileWithFiveBoundedReplies()throws Exception {
        try(var f=new GroupTaskFlowsTest.Fixture()){
            f.enable("media.audio");f.group=f.groups.ownerCommand("policy.set",new JSONObject().put("id",f.groupId).put("revision",f.group.getLong("revision")).put("enabled",true).put("visibility","members").put("quota",f.group.getJSONObject("quota")).put("grants",f.group.getJSONArray("grants")));
            f.host.setResultSink((request,receipt)->f.groups.publishTaskState(request.requestId));
            f.model=request->{assertTrue(request.getJSONArray("messages").toString().contains("群内受限任务助手"));return f.modelCalls.get()<3?GroupTaskFlowsTest.tool("group_media_audio","{\"prompt\":\"Read this public group greeting\"}"):GroupTaskFlowsTest.message("Actual group audio produced.");};
            f.send("/audio --share --tokens=60000 Create a public greeting",false);
            JSONObject waiting=f.task();String taskId=waiting.getString("id"),requestId=waiting.getJSONObject("origin").getString("requestId");assertEquals("awaiting_user",waiting.getString("status"));assertEquals(2,f.replies.size());assertTrue(f.replies.get(1).getString("content").contains("等待本机主人审批"));assertEquals(0,f.mediaCalls.get());
            f.tasks.decideCapabilityApproval(taskId,waiting.getJSONArray("approvals").getJSONObject(0).getString("id"),true);f.restart();f.host.setResultSink((request,receipt)->f.groups.publishTaskState(request.requestId));f.groups.ownerCommand("task.resume",new JSONObject().put("requestId",requestId));f.drain();
            JSONObject executed=f.task();assertEquals(taskId,executed.getString("id"));assertEquals(2,executed.getInt("coordinatorEpoch"));assertEquals("needs_confirmation",executed.getString("status"));assertEquals(1,f.mediaCalls.get());assertEquals(3,f.replies.size());assertTrue(f.replies.get(2).getString("content").contains("等待本机主人核验"));assertEquals(0,f.uploads.get());
            f.tasks.status(taskId,"completed","Native fixture owner verified actual audio");f.host.publishTaskState(taskId);f.drain();assertEquals(5,f.replies.size());assertEquals(1,f.uploads.get());assertEquals(3,f.modelCalls.get());JSONObject media=f.replies.get(4);assertEquals(7,media.getInt("msg_type"));assertEquals(" ",media.getString("content"));assertEquals("source-message",media.getString("msg_id"));assertEquals(5,media.getInt("msg_seq"));assertEquals("actual-opaque-file-info",media.getJSONObject("media").getString("file_info"));
            f.send("/status "+requestId,false,true,"fresh-status-message");assertEquals(6,f.replies.size());assertEquals("fresh-status-message",f.replies.get(5).getString("msg_id"));assertTrue(f.replies.get(5).getString("content").contains("原任务已完成"));assertEquals(1,f.uploads.get());assertEquals(3,f.modelCalls.get());assertEquals(1,f.tasks.list().length());
        }
    }
}
