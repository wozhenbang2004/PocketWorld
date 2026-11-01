package dev.pocketengine.features.channels;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Durable channel protocol tests; no substitute host scheduler or fabricated device execution. */
public final class ChannelTaskProtocolTest {
    private File directory;private ChannelCore core;private String profile;
    @Before public void setup()throws Exception{directory=Files.createTempDirectory("channel-task-").toFile();core=new ChannelCore(directory);profile=ChannelSecurityTest.addProfile(core,"task-relay","webhook");core.bindOwner(profile,receive("binding","owner","dm","hello").getString("id"),true);}
    @After public void cleanup()throws Exception{try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(directory.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception ignored){}});}}
    private JSONObject receive(String id,String sender,String kind,String text)throws Exception{return core.accept(profile,ChannelSecurityTest.message(id,sender,kind,kind.equals("group")?"room":"").put("text",text),"trusted_relay").getJSONObject("event");}
    private JSONObject task(String id)throws Exception{return receive(id,"owner","dm","/task 检查真实项目");}
    private String enabledRequest()throws Exception{core.setOwnerTasks(profile,true,true);return core.prepareHostTask(task("task").getString("id")).getJSONObject("request").getString("requestId");}
    private HostTaskPort.TaskState receipt(String request,long revision,String status,String text)throws Exception{JSONObject state=core.hostTask(request);String task=state.getString("taskId");return new HostTaskPort.TaskState(task,state.optString("hostRunId",task),state.optInt("hostCoordinatorEpoch",1),revision,status,text);}
    @Test public void bindingDoesNotGrantTasksAndOptInCannotAuthorizeAnEarlierEvent()throws Exception{
        assertFalse(core.snapshot().getJSONArray("bindings").getJSONObject(0).getBoolean("ownerTasksEnabled"));String old=task("before-opt-in").getString("id");ChannelSecurityTest.deny(()->core.prepareHostTask(old));
        ChannelSecurityTest.deny(()->core.setOwnerTasks(profile,true,false));core.setOwnerTasks(profile,true,true);ChannelSecurityTest.deny(()->core.prepareHostTask(old));assertFalse(core.prepareHostTask(task("after-opt-in").getString("id")).getBoolean("known"));
    }
    @Test public void groupsStrangersAndClaimedIdentityNeverReachTaskAuthority()throws Exception{
        core.setOwnerTasks(profile,true,true);String group=receive("group","owner","group","/task 做事").getString("id"),stranger=receive("stranger","someone","dm","/task 做事").getString("id");ChannelSecurityTest.deny(()->core.prepareHostTask(group));ChannelSecurityTest.deny(()->core.prepareHostTask(stranger));
        JSONObject claimed=ChannelSecurityTest.message("claim","someone","dm","").put("text","/task 做事").put("principalId","owner").put("spaceId","private").put("ownerTasksEnabled",true).put("bindingEpoch",core.snapshot().getJSONArray("bindings").getJSONObject(0).getString("bindingEpoch"));
        String event=core.accept(profile,ChannelProtocol.canonicalRelay(claimed),"trusted_relay").getJSONObject("event").getString("id");ChannelSecurityTest.deny(()->core.prepareHostTask(event));ChannelSecurityTest.deny(()->core.grant(core.event(group).getJSONObject("route").getString("spaceId"),new JSONArray().put("owner.tasks")));assertEquals(0,core.snapshot().getJSONArray("taskRequests").length());
    }
    @Test public void pendingAdmissionRestartsUnknownWithTheSameEventTaskAndOrigin()throws Exception{
        String request=enabledRequest();HostTaskPort.Request first=core.hostTaskRequest(request);JSONObject immutable=first.origin();immutable.put("ownerId","injected");assertEquals("owner",first.origin().getString("ownerId"));assertEquals("private",first.origin().getString("spaceId"));assertEquals("trusted_relay",first.origin().getString("authenticatedBy"));assertFalse(first.origin().has("text"));
        String event=core.hostTask(request).getString("eventId");core=new ChannelCore(directory);assertEquals("admission_unknown",core.hostTask(request).getString("admission"));JSONObject duplicate=core.prepareHostTask(event);assertTrue(duplicate.getBoolean("known"));assertEquals(first.taskId,duplicate.getJSONObject("request").getString("taskId"));assertEquals(first.requestId,duplicate.getJSONObject("request").getString("requestId"));assertEquals(1,core.snapshot().getJSONArray("taskRequests").length());
    }
    @Test public void originDedupSurvivesTheBoundedTransportSeenWindow()throws Exception{
        String request=enabledRequest(),taskId=core.hostTask(request).getString("taskId");File file=new File(directory,"channels.json");JSONObject state=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));state.put("seen",new JSONArray()).put("inbox",new JSONArray());Files.write(file.toPath(),state.toString().getBytes(StandardCharsets.UTF_8));core=new ChannelCore(directory);
        JSONObject replay=core.prepareHostTask(task("task").getString("id"));assertTrue(replay.getBoolean("known"));assertEquals(request,replay.getJSONObject("request").getString("requestId"));assertEquals(taskId,replay.getJSONObject("request").getString("taskId"));assertEquals(1,core.snapshot().getJSONArray("taskRequests").length());
    }
    @Test public void grantDisableReenableAndSameOwnerRebindCannotResurrectAnOldTask()throws Exception{
        String request=enabledRequest();core.hostTaskAdmitted(request,receipt(request,1,"queued","已登记"));String outbox=core.queueHostTaskUpdate(request,receipt(request,1,"queued","已登记")).getString("id");core.setOwnerTasks(profile,false,true);ChannelSecurityTest.deny(()->core.checkHostTaskAuthority(request));core.setOwnerTasks(profile,true,true);ChannelSecurityTest.deny(()->core.checkHostTaskAuthority(request));ChannelSecurityTest.deny(()->core.prepareSend(outbox));
        core.bindOwner(profile,receive("new-binding","owner","dm","hello again").getString("id"),true);assertFalse(core.snapshot().getJSONArray("bindings").getJSONObject(0).getBoolean("ownerTasksEnabled"));core.setOwnerTasks(profile,true,true);ChannelSecurityTest.deny(()->core.checkHostTaskAuthority(request));ChannelSecurityTest.deny(()->core.queueHostTaskUpdate(request,receipt(request,2,"completed","迟到的结果")));
    }
    @Test public void credentialRotationRevokesOldTasksAndAllOldPrivateDrafts()throws Exception{
        String request=enabledRequest(),event=task("private-message").getString("id");JSONObject draft=core.queueReplyExpected(event,"更换前私人答复","private");String epoch=core.event(event).getJSONObject("route").getString("bindingEpoch");core.prepareCredentialChange(profile);core.saveProfile(new JSONObject().put("id",profile).put("name","task-relay").put("provider","webhook").put("enabled",true));
        assertFalse(core.snapshot().getJSONArray("bindings").getJSONObject(0).getBoolean("ownerTasksEnabled"));ChannelSecurityTest.deny(()->core.checkHostTaskAuthority(request));ChannelSecurityTest.deny(()->core.prepareSend(draft.getString("id")));ChannelSecurityTest.deny(()->core.queueReplyExpected(event,"旧模型返回","private",epoch));
    }
    @Test public void hostReceiptsRequireExactTaskAndMonotoneVersionsAndUnknownNeverResends()throws Exception{
        String request=enabledRequest();ChannelSecurityTest.deny(()->core.hostTaskAdmitted(request,new HostTaskPort.TaskState(java.util.UUID.randomUUID().toString(),"",0,1,"completed","错误任务")));core.hostTaskAdmitted(request,receipt(request,1,"queued","已登记"));
        JSONObject first=core.queueHostTaskUpdate(request,receipt(request,1,"queued","已登记"));assertEquals(first.getString("id"),core.queueHostTaskUpdate(request,receipt(request,1,"queued","已登记")).getString("id"));assertEquals(1,core.snapshot().getJSONArray("outbox").length());ChannelSecurityTest.deny(()->core.queueHostTaskUpdate(request,receipt(request,1,"completed","同版本伪造变化")));
        JSONObject later=core.queueHostTaskUpdate(request,receipt(request,2,"needs_confirmation","模型返回不表示目标达成"));ChannelSecurityTest.deny(()->core.queueHostTaskUpdate(request,receipt(request,1,"queued","已登记")));core.prepareSend(later.getString("id"));core=new ChannelCore(directory);assertEquals("unknown",core.queueHostTaskUpdate(request,receipt(request,2,"needs_confirmation","模型返回不表示目标达成")).getString("status"));ChannelSecurityTest.deny(()->core.prepareSend(later.getString("id")));assertEquals(2,core.snapshot().getJSONArray("outbox").length());
    }
    @Test public void pinnedTaskOriginStillAllowsAResultAfterInboxTrimming()throws Exception{
        String request=enabledRequest();core.hostTaskAdmitted(request,receipt(request,1,"queued","已登记"));String original=core.hostTask(request).getString("eventId");for(int i=0;i<201;i++)receive("later-"+i,"owner","dm","普通消息");ChannelSecurityTest.deny(()->core.event(original));
        JSONObject row=core.queueHostTaskUpdate(request,receipt(request,2,"completed","主人确认后返回的结果"));assertEquals(original,core.deliveryEvent(row).getString("id"));core.prepareSend(row.getString("id"));core.checkSendAuthority(row.getString("id"));core=new ChannelCore(directory);assertEquals("unknown",core.snapshot().getJSONArray("outbox").getJSONObject(0).getString("status"));
    }
    @Test public void persistenceFailureRollsBackOriginAndOutboxAssociationTogether()throws Exception{
        core.setOwnerTasks(profile,true,true);String event=task("task").getString("id");File blocked=new File(directory,"channels.pending");assertTrue(blocked.mkdir());assertThrows(java.io.IOException.class,()->core.prepareHostTask(event));assertEquals(0,core.snapshot().getJSONArray("taskRequests").length());assertTrue(blocked.delete());
        String request=core.prepareHostTask(event).getJSONObject("request").getString("requestId");core.hostTaskAdmitted(request,receipt(request,1,"queued","已登记"));assertTrue(blocked.mkdir());assertThrows(java.io.IOException.class,()->core.queueHostTaskUpdate(request,receipt(request,1,"queued","已登记")));assertEquals(0,core.snapshot().getJSONArray("outbox").length());assertFalse(core.hostTask(request).has("lastOutboxId"));assertTrue(blocked.delete());assertEquals(1,core.queueHostTaskUpdate(request,receipt(request,1,"queued","已登记")).getInt("sequence"));
    }
    @Test public void cancellationPinsTheOriginalProfileAndRestartsUnknownWithoutAnotherRequest()throws Exception{
        String request=enabledRequest(),group=receive("group-cancel","owner","group","/task-cancel "+request).getString("id");ChannelSecurityTest.deny(()->core.authorizeHostTaskCommand(request,group));
        String other=ChannelSecurityTest.addProfile(core,"other-relay","webhook");JSONObject bind=core.accept(other,ChannelSecurityTest.message("bind","owner","dm",""),"trusted_relay").getJSONObject("event");core.bindOwner(other,bind.getString("id"),true);core.setOwnerTasks(other,true,true);String otherCommand=core.accept(other,ChannelSecurityTest.message("cancel","owner","dm","").put("text","/task-cancel "+request),"trusted_relay").getJSONObject("event").getString("id");ChannelSecurityTest.deny(()->core.authorizeHostTaskCommand(request,otherCommand));
        String own=receive("own-cancel","owner","dm","/task-cancel "+request).getString("id");core.authorizeHostTaskCommand(request,own);assertNotNull(core.prepareHostTaskCancellation(request));core=new ChannelCore(directory);assertEquals("cancellation_unknown",core.hostTask(request).getString("cancellation"));assertNull(core.prepareHostTaskCancellation(request));
    }
    @Test public void rebindingAtTheActualWriteBoundarySendsNoPrivateTaskBytes()throws Exception{
        String request=enabledRequest();core.hostTaskAdmitted(request,receipt(request,1,"queued","已登记"));JSONObject row=core.queueHostTaskUpdate(request,receipt(request,1,"queued","已登记"));core.prepareSend(row.getString("id"));java.util.concurrent.atomic.AtomicInteger received=new java.util.concurrent.atomic.AtomicInteger(),checks=new java.util.concurrent.atomic.AtomicInteger();ChannelServer server=new ChannelServer((p,h,b)->{received.incrementAndGet();return new JSONObject().put("id","must-not-exist");});
        try{int port=server.start(0);assertThrows(SecurityException.class,()->ChannelHttp.post("http://127.0.0.1:"+port+"/events/result","Bearer fixture",new JSONObject().put("text","私人任务结果"),new dev.pocketengine.core.RunControl(),()->{if(checks.incrementAndGet()==3){core.bindOwner(profile,receive("rebind","owner","dm","hello").getString("id"),true);core.setOwnerTasks(profile,true,true);}core.checkSendAuthority(row.getString("id"));}));assertEquals(0,received.get());assertTrue(checks.get()>=3);}finally{server.close();}
    }
    @Test public void cancellationCanTargetANewRunOfTheSameTaskWithoutRevivingAnOldUnknownCancel()throws Exception{
        String request=enabledRequest();HostTaskPort.TaskState first=receipt(request,1,"running","执行一");core.hostTaskAdmitted(request,first);core.queueHostTaskUpdate(request,first);HostTaskPort.Request old=core.prepareHostTaskCancellation(request);assertNotNull(old);core.hostTaskCancellation(request,old.expectedRunId,old.expectedCoordinatorEpoch,false);core=new ChannelCore(directory);assertNull(core.prepareHostTaskCancellation(request));
        String newRun=java.util.UUID.randomUUID().toString();core.queueHostTaskUpdate(request,new HostTaskPort.TaskState(old.taskId,newRun,2,2,"running","主人恢复了同一个任务"));HostTaskPort.Request next=core.prepareHostTaskCancellation(request);assertNotNull(next);assertEquals(newRun,next.expectedRunId);assertEquals(2,next.expectedCoordinatorEpoch);assertEquals(first.runId,old.expectedRunId);assertEquals(1,old.expectedCoordinatorEpoch);
        core.hostTaskCancellation(request,old.expectedRunId,old.expectedCoordinatorEpoch,true);assertEquals("requesting",core.hostTask(request).getString("cancellation"));ChannelSecurityTest.deny(()->core.queueHostTaskUpdate(request,new HostTaskPort.TaskState(old.taskId,old.expectedRunId,1,99,"cancelled","迟到旧run回执")));ChannelSecurityTest.deny(()->core.queueHostTaskUpdate(request,new HostTaskPort.TaskState(old.taskId,java.util.UUID.randomUUID().toString(),2,99,"cancelled","冲突run身份")));core.hostTaskCancellation(request,next.expectedRunId,next.expectedCoordinatorEpoch,true);assertEquals("requested",core.hostTask(request).getString("cancellation"));
    }
}
