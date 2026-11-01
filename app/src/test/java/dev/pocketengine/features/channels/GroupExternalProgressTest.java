package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import dev.pocketengine.features.groups.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real registry/proof/outbox/HTTP. Native task/approval truth is an explicit port fixture, not Runner or QQ. */
public final class GroupExternalProgressTest {
    File directory;ChannelCore core;GroupChannelBridge bridge;GroupsModule groups;ChannelServer server;JSONObject profile,group;
    final List<JSONObject> wire=new ArrayList<>();boolean acknowledge=true;final Host host=new Host();
    final String credential="explicit-local-relay-fixture-secret";
    final class Host implements GroupHostPort {
        Request request;int admissions,cancellations;long revision=1;String status="running",approval="none";boolean unresolved;final String run=UUID.randomUUID().toString();
        public TaskReceipt admit(Request value,Authority authority)throws Exception {authority.check();admissions++;request=value;return current();}
        public TaskReceipt inspect(Request value,Authority authority)throws Exception {authority.checkRead();assertEquals(request.taskId,value.taskId);return current();}
        public Progress inspectProgress(Request value,Authority authority)throws Exception {return new Progress(inspect(value,authority),approval,unresolved);}
        public TaskReceipt cancel(Request value,String selected,int epoch,Authority authority)throws Exception {authority.check();assertEquals(run,selected);assertEquals(1,epoch);cancellations++;status="cancelled";revision++;return current();}
        TaskReceipt current(){return new TaskReceipt(request.taskId,run,1,revision,status,"UNTRUSTED_MODEL_SUMMARY_NEVER_IN_STATUS",status.equals("completed")?List.of(new Output(outputId(),request.spaceId,request.audience,"text/plain","","actual fixture result",21)):List.of());}
        String outputId(){return UUID.nameUUIDFromBytes((request.taskId+":fixture-output").getBytes(StandardCharsets.UTF_8)).toString();}
        void state(String value,String approved){status=value;approval=approved;revision++;}
    }
    @Before public void start()throws Exception {
        directory=Files.createTempDirectory("group-external-progress-").toFile();core=new ChannelCore(new File(directory,"channels"));
        server=new ChannelServer((id,headers,body)->{assertEquals("Bearer "+credential,headers.get("authorization"));JSONObject value=new JSONObject(new String(body,StandardCharsets.UTF_8));synchronized(wire){wire.add(value);}return acknowledge?new JSONObject().put("id","relay-receipt-"+wire.size()):new JSONObject().put("ok",true);});server.start(0);
        profile=core.saveProfile(new JSONObject().put("id","relay-fixture").put("provider","webhook").put("name","Explicit local fixture").put("enabled",true).put("replyUrl","http://127.0.0.1:"+server.snapshot().getInt("port")+"/events/replies"));mount();
        group=groups.ownerCommand("group.create",new JSONObject().put("provider","webhook").put("accountId",profile.getString("id")).put("roomId","room-a").put("name","Shared work"));
        group=groups.ownerCommand("member.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("senderId","alice").put("displayName","Alice").put("role","member").put("enabled",true));
        group=groups.ownerCommand("policy.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("enabled",true).put("visibility","members").put("quota",group.getJSONObject("quota")).put("grants",new JSONArray().put(new JSONObject().put("capability","public.answer").put("resource","public").put("expiresAt",System.currentTimeMillis()+3600000).put("allowGroupAudience",true))));
    }
    void mount()throws Exception {bridge=new GroupChannelBridge(core,new ChannelHttp(),id->credential,new File(directory,"channels"));groups=new GroupsModule(new File(directory,"groups"),bridge,host,bridge,()->{});bridge.bind(groups,Runnable::run);}
    void inbound(String id,String text)throws Exception {inbound(id,text,"room-a");}
    void inbound(String id,String text,String room)throws Exception {JSONObject accepted=core.accept(profile.getString("id"),new JSONObject().put("messageId",id).put("senderId","alice").put("roomId",room).put("kind","group").put("text",text).put("sentAt",System.currentTimeMillis()),"trusted_relay").getJSONObject("event");assertTrue(bridge.accepted(profile,accepted.getString("id")));}
    String request(){return host.request.requestId;}
    @After public void close()throws Exception {if(groups!=null)groups.close();if(bridge!=null)bridge.close();if(server!=null)server.close();if(directory!=null)try(var paths=Files.walk(directory.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception ignored){}});}}
    @Test public void sharedTaskCoalescesApprovalAndConfirmationReservingRealResultSlots()throws Exception {
        inbound("source","/task --share --tokens=4000 Work with public facts");assertEquals("members",host.request.visibility);assertEquals("Work with public facts",host.request.goal);assertEquals(1,wire.size());
        host.state("awaiting_user","pending");groups.publishTaskState(request());groups.publishTaskState(request());assertEquals(2,wire.size());assertTrue(wire.get(1).getString("text").contains("等待本机主人审批"));
        host.state("needs_confirmation","approved");groups.publishTaskState(request());assertEquals(3,wire.size());assertTrue(wire.get(2).getString("text").contains("等待本机主人核验"));assertFalse(wire.get(2).getString("text").contains("等待本机主人审批"));
        groups.publishTaskState(request());assertEquals(3,wire.size());host.state("completed","approved");groups.publishTaskState(request());assertEquals(4,wire.size());assertEquals("actual fixture result",wire.get(3).getString("text"));assertEquals(1,host.admissions);
        for(JSONObject body:wire)assertFalse(body.toString().contains("UNTRUSTED_MODEL_SUMMARY"));
    }
    @Test public void privateVisibilityDoesNotPublishStatusToTheRoom()throws Exception {
        inbound("source","/task Private task metadata");assertEquals("requester",host.request.visibility);assertEquals(0,wire.size());host.state("failed","none");groups.publishTaskState(request());inbound("query","/status "+request());assertEquals(0,wire.size());assertEquals(1,host.admissions);
    }
    @Test public void nativeDenialFailureAndCancelAreActualStatesWithNewCommandContext()throws Exception {
        inbound("source","/task --share Public work");host.state("awaiting_user","denied");groups.publishTaskState(request());assertTrue(wire.get(1).getString("text").contains("主人已拒绝"));host.state("failed","denied");inbound("query","/status "+request());assertEquals("query",wire.get(2).getString("replyTo"));assertTrue(wire.get(2).getString("text").contains("原任务执行失败"));assertFalse(wire.get(2).getString("text").contains("等待本机主人审批"));assertEquals(1,host.admissions);
    }
    @Test public void cancelUsesOriginalRunAndReturnsNativeStopStateInSameRoom()throws Exception {
        inbound("source","/task --share Public work");host.approval="pending";inbound("stop","/cancel "+request());assertEquals(1,host.cancellations);assertEquals(1,host.admissions);assertEquals("stop",wire.get(1).getString("replyTo"));assertTrue(wire.get(1).getString("text").contains("已停止原任务后续派发"));
    }
    @Test public void expiredApprovalUsesFreshContextWithARecoveryStepWithoutExecution()throws Exception {
        inbound("source","/task --share Public work");host.state("awaiting_user","expired");inbound("fresh-expired","/status "+request());assertEquals(2,wire.size());assertEquals("fresh-expired",wire.get(1).getString("replyTo"));String text=wire.get(1).getString("text");assertTrue(text.contains("审批已过期或失效"));assertFalse(text.contains("等待本机主人审批"));assertTrue(text.contains("核对原参数与当前授权"));assertEquals(1,host.admissions);assertEquals(0,host.cancellations);
    }
    @Test public void explicitUnknownEffectNeverPublishesAConflictingCompletedOutput()throws Exception {
        inbound("source","/task --share Public work");host.state("completed","approved");host.unresolved=true;JSONObject result=groups.publishTaskState(request());assertEquals(2,wire.size());assertTrue(wire.get(1).getString("text").contains("原操作回执仍未知"));assertFalse(wire.get(1).getString("text").contains("原任务已完成"));assertEquals(0,result.getJSONArray("deliveries").length());assertThrows(IllegalStateException.class,()->groups.sendOutput(request(),host.outputId()));assertEquals(2,wire.size());
    }
    @Test public void lostOutputAckSurvivesRestartAndFreshStatusNeverResendsIt()throws Exception {
        inbound("source","/task --share Public work");host.state("completed","none");acknowledge=false;JSONObject sent=groups.publishTaskState(request()).getJSONArray("deliveries").getJSONObject(0);assertEquals("unknown",sent.getString("state"));assertEquals(2,wire.size());String delivery=sent.getString("deliveryId");
        groups.close();bridge.close();core=new ChannelCore(new File(directory,"channels"));mount();acknowledge=true;inbound("fresh-status","/status "+request());assertEquals(3,wire.size());assertEquals(1,host.admissions);assertEquals("unknown",core.groupOutbox(delivery).getString("status"));assertEquals("source",core.groupOutbox(delivery).getJSONObject("nativeGroupEvent").getString("messageId"));
        int resultMessages=0;for(JSONObject body:wire)if(body.getString("text").equals("actual fixture result"))resultMessages++;assertEquals(1,resultMessages);
    }
    @Test public void anotherRoomCannotUseAValidTaskIdForFeedbackOrResult()throws Exception {
        inbound("source","/task --share Public work");host.state("completed","none");inbound("foreign","/status "+request(),"room-b");assertEquals(1,wire.size());assertEquals(1,host.admissions);
    }
}
