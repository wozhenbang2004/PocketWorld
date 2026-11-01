package dev.pocketengine.features.channels;

import dev.pocketengine.features.groups.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual native proof/outbox/HTTP contracts; fixture host explicitly does not execute AgentRunner. */
public final class GroupChannelBridgeTest {
    private File directory;
    private ChannelCore core;
    private GroupChannelBridge bridge;
    private GroupsModule groups;
    private JSONObject profile,group;
    private final AtomicInteger posts=new AtomicInteger(),admissions=new AtomicInteger();
    private final AtomicBoolean acceptedId=new AtomicBoolean(true);
    private final String secret="0123456789abcdef0123456789abcdef";
    private ChannelServer server;
    private String requestId,outputId;
    @Before public void setup()throws Exception {
        directory=Files.createTempDirectory("native-group-channel-").toFile();core=new ChannelCore(new File(directory,"channels"));
        server=new ChannelServer((id,headers,body)->{posts.incrementAndGet();assertEquals("Bearer "+secret,headers.get("authorization"));JSONObject payload=new JSONObject(new String(body,StandardCharsets.UTF_8));assertEquals("group",payload.getString("audience"));assertEquals("room-a",payload.getString("roomId"));return acceptedId.get()?new JSONObject().put("id","actual-platform-id"):new JSONObject().put("ok",true);});
        server.start(0);String address="http://127.0.0.1:"+server.snapshot().getInt("port")+"/events/replies";
        profile=core.saveProfile(new JSONObject().put("id","native-bot").put("name","Native group relay").put("provider","webhook").put("enabled",true).put("replyUrl",address));
        bridge=new GroupChannelBridge(core,new ChannelHttp(),id->secret,new File(directory,"channels"));
        // This port fixture isolates the real channel contract; the separate SQLite integration uses the production host.
        GroupHostPort fixture=new GroupHostPort(){
            public TaskReceipt admit(Request request,Authority authority)throws Exception{authority.check();admissions.incrementAndGet();requestId=request.requestId;outputId=UUID.nameUUIDFromBytes(request.taskId.getBytes(StandardCharsets.UTF_8)).toString();return state(request);}
            public TaskReceipt inspect(Request request,Authority authority)throws Exception{authority.checkRead();return state(request);}
            public TaskReceipt cancel(Request request,String run,int epoch,Authority authority)throws Exception{authority.check();return state(request);}
            private TaskReceipt state(Request request){return new TaskReceipt(request.taskId,"",0,1,"completed","fixture authoritative answer",List.of(new Output(outputId,request.spaceId,request.audience,"text/plain","","actual fixture output",21)));}
        };
        groups=new GroupsModule(new File(directory,"groups"),bridge,fixture,bridge,()->{});bridge.bind(groups,Runnable::run);
        group=groups.ownerCommand("group.create",new JSONObject().put("name","A").put("provider","webhook").put("accountId",profile.getString("id")).put("roomId","room-a"));
        group=groups.ownerCommand("member.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("senderId","member-a").put("displayName","Member A").put("role","member").put("enabled",true));
        group=groups.ownerCommand("policy.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("enabled",true).put("visibility","requester").put("quota",group.getJSONObject("quota"))
                .put("grants",new JSONArray().put(new JSONObject().put("capability","public.answer").put("resource","public").put("expiresAt",System.currentTimeMillis()+3_600_000).put("allowGroupAudience",true))));
    }
    @After public void cleanup()throws Exception {if(groups!=null)groups.close();if(bridge!=null)bridge.close();if(server!=null)server.close();try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(directory.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    private JSONObject event(String message,String kind)throws Exception {return core.accept(profile.getString("id"),new JSONObject().put("messageId",message).put("senderId","member-a").put("roomId",kind.equals("group")?"room-a":"").put("kind",kind).put("text","/task Answer without private data").put("sentAt",System.currentTimeMillis()),"trusted_relay").getJSONObject("event");}
    private JSONObject admitted()throws Exception {JSONObject event=event("source","group");assertTrue(bridge.accepted(profile,event.getString("id")));assertEquals(1,admissions.get());return event;}
    @Test public void currentNativeGroupEventUsesStableProofNotClaimedOwner()throws Exception {
        JSONObject event=admitted();GroupIngressPort.Event proof=bridge.authenticate(event.getString("id"));assertEquals("member-a",proof.senderId);assertEquals("native-bot",proof.accountId);assertEquals("room-a",proof.roomId);assertEquals("Answer without private data",proof.text);assertFalse(proof.text.contains("/task"));
        JSONObject task=groups.ownerCommand("task.inspect",new JSONObject().put("requestId",requestId));assertEquals("completed",task.getJSONObject("task").getString("status"));
    }
    @Test public void dmAndUnregisteredJsonIdsCannotAcquireNativeGroupProof()throws Exception {JSONObject dm=event("private","dm");ChannelSecurityTest.deny(()->bridge.accepted(profile,dm.getString("id")));ChannelSecurityTest.deny(()->bridge.authenticate(UUID.randomUUID().toString()));assertEquals(0,admissions.get());}
    @Test public void profileEpochChangesRejectDelayedPreviouslyAuthenticatedEvent()throws Exception {JSONObject event=event("before-change","group");profile=core.saveProfile(new JSONObject(profile.toString()).put("enabled",true));ChannelSecurityTest.deny(()->bridge.accepted(profile,event.getString("id")));assertEquals(0,admissions.get());}
    @Test public void credentialRevocationStopsRetainedProof()throws Exception {JSONObject event=admitted();core.prepareCredentialChange(profile.getString("id"));ChannelSecurityTest.deny(()->bridge.authenticate(event.getString("id")));assertEquals(0,posts.get());}
    @Test public void actualHttpReceiptMeansAcceptedAndDuplicateNeverSendsAgain()throws Exception {admitted();JSONObject sent=groups.sendOutput(requestId,outputId);assertEquals("accepted",sent.getString("state"));assertEquals(1,posts.get());assertEquals("accepted",groups.sendOutput(requestId,outputId).getString("state"));assertEquals(1,posts.get());String delivery=sent.getString("deliveryId");assertEquals("sent",core.groupOutbox(delivery).getString("status"));ChannelSecurityTest.deny(()->core.prepareSend(delivery));}
    @Test public void lostPlatformAckRemainsUnknownAcrossQueryAndRestart()throws Exception {admitted();acceptedId.set(false);JSONObject sent=groups.sendOutput(requestId,outputId);assertEquals("unknown",sent.getString("state"));assertEquals(1,posts.get());assertEquals("unknown",groups.sendOutput(requestId,outputId).getString("state"));assertEquals(1,posts.get());core=new ChannelCore(new File(directory,"channels"));assertEquals("unknown",core.groupOutbox(sent.getString("deliveryId")).getString("status"));}
    @Test public void nativeOwnerCanQueryOriginalReceiptAfterGroupPolicyRevoked()throws Exception {admitted();JSONObject sent=groups.sendOutput(requestId,outputId);group=groups.ownerCommand("policy.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("enabled",false).put("visibility","requester").put("quota",group.getJSONObject("quota")).put("grants",group.getJSONArray("grants")));JSONObject inspected=groups.ownerCommand("delivery.inspect",new JSONObject().put("deliveryId",sent.getString("deliveryId")));assertEquals("accepted",inspected.getString("state"));assertEquals(1,posts.get());ChannelSecurityTest.deny(()->groups.sendOutput(requestId,outputId));}
    @Test public void ownerNotDeliveredReconciliationDoesNotAuthorizeGenericOrGroupResend()throws Exception {admitted();acceptedId.set(false);JSONObject sent=groups.sendOutput(requestId,outputId);String id=sent.getString("deliveryId");core.resolveDelivery(id,"not_delivered",true,"checked original platform");ChannelSecurityTest.deny(()->core.prepareSend(id));assertEquals("unknown",groups.sendOutput(requestId,outputId).getString("state"));assertEquals(1,posts.get());}
    @Test public void requesterAudienceAndUnimplementedMediaAreNotAdvertised()throws Exception {assertFalse(bridge.supports("webhook","native-bot","requester","text/plain"));assertFalse(bridge.supports("webhook","native-bot","group","image/png"));assertFalse(bridge.supports("weixin","native-bot","group","text/plain"));}
}
