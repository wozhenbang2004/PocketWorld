package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Original JSON inbox/outbox only; protocol fixtures, not qualified platform-account evidence. */
public final class ChannelControlReplyTest {
    private File directory;private ChannelCore core;private String profile;
    @Before public void open()throws Exception {directory=Files.createTempDirectory("c3-native-channel-control-").toFile();core=new ChannelCore(directory);profile=core.saveProfile(new JSONObject().put("name","native control").put("provider","webhook").put("enabled",true)).getString("id");}
    @After public void close()throws Exception {try(var paths=Files.walk(directory.toPath())){for(var p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private JSONObject event(String message,String sender,String kind,String text)throws Exception {return core.accept(profile,new JSONObject().put("messageId",message).put("senderId",sender).put("kind",kind).put("roomId",kind.equals("group")?"room-a":"").put("text",text).put("sentAt",System.currentTimeMillis()),"trusted_relay").getJSONObject("event");}
    private JSONObject task()throws Exception {JSONObject bind=event("bind","owner","dm","binding");core.bindOwner(profile,bind.getString("id"),true);core.setOwnerTasks(profile,true,true);JSONObject command=event("task","owner","dm","/task Original private task");return core.prepareHostTask(command.getString("id")).getJSONObject("request");}
    @Test public void fixedNativeFeedbackIsOnceFrozenOnOriginalOutboxAndRestartCannotRetryUnknown()throws Exception {
        JSONObject event=event("file","member-a","dm","/task cannot read");JSONObject first=core.queueControlFeedback(event.getString("id"),"owner_task_unconfirmed");assertFalse(first.getBoolean("known"));assertTrue(first.getString("text").contains("可能已受理"));assertFalse(first.getString("text").contains("cannot read"));
        assertEquals(event.getString("messageId"),core.deliveryEvent(first).getString("messageId"));core.prepareSend(first.getString("id"));core=new ChannelCore(directory);
        JSONObject duplicate=core.queueControlFeedback(event.getString("id"),"owner_task_unconfirmed");assertTrue(duplicate.getBoolean("known"));assertEquals(first.getString("id"),duplicate.getString("id"));assertEquals("unknown",duplicate.getString("status"));assertEquals(1,core.snapshot().getJSONArray("outbox").length());assertThrows(IllegalStateException.class,()->core.prepareSend(first.getString("id")));
        assertThrows(SecurityException.class,()->core.queueControlFeedback(event.getString("id"),"send_custom_model_text"));
    }
    @Test public void transportRotationAndChatRevocationDoNotAuthorizeLateControlFeedback()throws Exception {
        JSONObject a=event("a","member","dm","/task target"),reply=core.queueControlFeedback(a.getString("id"),"owner_task_unconfirmed");
        core.grant(a.getJSONObject("route").getString("spaceId"),new JSONArray());assertThrows(SecurityException.class,()->core.prepareSend(reply.getString("id")));
        core.grant(a.getJSONObject("route").getString("spaceId"),new JSONArray().put("chat"));core.saveProfile(new JSONObject().put("id",profile).put("name","same account new version").put("enabled",true));assertThrows(SecurityException.class,()->core.queueControlFeedback(a.getString("id"),"owner_task_unconfirmed"));assertThrows(SecurityException.class,()->core.prepareSend(reply.getString("id")));
    }
    @Test public void nativeControlQuotaDoesNotChargeDuplicateAndNeverStartsATask()throws Exception {
        core.saveProfile(new JSONObject().put("id",profile).put("name","one native control per hour").put("enabled",true).put("maxRepliesPerHour",1));
        JSONObject a=event("a","member","dm","/task target"),b=event("b","member","dm","/task target");core.queueControlFeedback(a.getString("id"),"owner_task_unconfirmed");assertTrue(core.queueControlFeedback(a.getString("id"),"owner_task_unconfirmed").getBoolean("known"));assertThrows(IllegalStateException.class,()->core.queueControlFeedback(b.getString("id"),"owner_task_unconfirmed"));assertEquals(0,core.snapshot().getJSONArray("taskRequests").length());assertEquals(1,core.snapshot().getJSONArray("outbox").length());
    }
    @Test public void statusUsesFreshExactOwnerCommandAndFrozenMetadataNotPrivateSummaryOrOldReplyContext()throws Exception {
        JSONObject task=task();String request=task.getString("requestId"),id=task.getString("taskId"),run=UUID.randomUUID().toString();core.hostTaskAdmitted(request,new HostTaskPort.TaskState(id,run,1,1,"running","PRIVATE-SECRET-RAW-RESULT"));
        JSONObject command=event("status-command","owner","dm","/task-status   "+request),reply=core.queueHostTaskStatus(command.getString("id"),request,true);
        assertEquals("status-command",core.deliveryEvent(reply).getString("messageId"));assertTrue(reply.getString("text").contains("running"));assertFalse(reply.toString().contains("PRIVATE-SECRET-RAW-RESULT"));assertEquals(request,reply.getString("taskRequestId"));
        core.hostTaskAdmitted(request,new HostTaskPort.TaskState(id,run,1,2,"completed","DIFFERENT PRIVATE RESULT"));JSONObject old=core.queueHostTaskStatus(command.getString("id"),request,true);assertTrue(old.getBoolean("known"));assertEquals(reply.getString("text"),old.getString("text"));assertEquals(1,core.snapshot().getJSONArray("taskRequests").length());assertEquals(1,core.snapshot().getJSONArray("outbox").length());
        JSONObject fresh=event("new-status","owner","dm","/task-status "+request);assertTrue(core.queueHostTaskStatus(fresh.getString("id"),request,false).getString("text").contains("不能证明仍在运行"));
        core.setOwnerTasks(profile,false,true);assertThrows(SecurityException.class,()->core.prepareSend(reply.getString("id")));
    }
    @Test public void claimedOwnerAnotherSenderAndGroupCannotReadPrivateTaskStatus()throws Exception {
        JSONObject task=task();String request=task.getString("requestId");JSONObject stranger=event("stranger","outsider","dm","/task-status "+request),group=event("group","owner","group","/task-status "+request);
        assertThrows(SecurityException.class,()->core.queueHostTaskStatus(stranger.getString("id"),request,true));assertThrows(SecurityException.class,()->core.queueHostTaskStatus(group.getString("id"),request,true));assertThrows(SecurityException.class,()->core.queueControlFeedback(group.getString("id"),"owner_task_unconfirmed"));assertThrows(SecurityException.class,()->core.queueControlFeedback(group.getString("id"),"group_intake_unconfirmed"));assertEquals(0,core.snapshot().getJSONArray("outbox").length());
    }
}
