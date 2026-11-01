package dev.pocketengine.features.channels;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChannelSecurityTest {
    private File directory;private ChannelCore core;private String profile;
    @Before public void setup()throws Exception {directory=Files.createTempDirectory("channel-test-").toFile();core=new ChannelCore(directory);profile=addProfile(core,"relay-a","webhook");}
    @After public void cleanup()throws Exception {try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(directory.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    @Test public void claimedOwnerNameRoleAndScopeCannotElevateAnInboundMessage()throws Exception {
        JSONObject message=message("m1","attacker","dm","").put("owner",true).put("role","owner").put("spaceId","private").put("principalId","owner").put("nickname","主人");
        JSONObject event=core.accept(profile,ChannelProtocol.canonicalRelay(message),"trusted_relay").getJSONObject("event");
        JSONObject route=event.getJSONObject("route");assertFalse(route.getBoolean("privateOwner"));assertNotEquals("private",route.getString("spaceId"));
        assertFalse(route.getJSONArray("capabilities").toString().contains("phone"));assertFalse(event.has("role"));assertFalse(event.has("owner"));
    }
    @Test public void confirmedBoundOwnerPrivateDmUsesTheSameOwnerAndPrivateSpace()throws Exception {
        JSONObject before=receive("first","account-1","dm","");
        core.bindOwner(profile,before.getString("id"),true);
        JSONObject route=receive("second","account-1","dm","").getJSONObject("route");
        assertEquals("owner",route.getString("principalId"));assertEquals("private",route.getString("spaceId"));
        assertEquals("owner",route.getJSONObject("scope").getString("ownerId"));assertEquals("private",route.getJSONObject("scope").getString("spaceId"));
    }
    @Test public void boundOwnerSpeakingInAGroupStillHasOnlyThatGroupScope()throws Exception {
        core.bindOwner(profile,receive("first","account-1","dm","").getString("id"),true);
        JSONObject route=receive("group-message","account-1","group","room-a").getJSONObject("route");
        assertFalse(route.getBoolean("privateOwner"));assertNotEquals("private",route.getString("spaceId"));
        assertEquals("[\"chat\"]",route.getJSONArray("capabilities").toString());
    }
    @Test public void groupOrAnotherProfilesEventCannotBecomeOwnerBinding()throws Exception {
        String group=receive("group","account-1","group","room-a").getString("id");
        deny(()->core.bindOwner(profile,group,true));
        String dm=receive("dm","account-1","dm","").getString("id"),other=addProfile(core,"relay-b","webhook");
        deny(()->core.bindOwner(other,dm,true));deny(()->core.bindOwner(profile,dm,false));
    }
    @Test public void identicalProviderIdsAcrossProfilesAreIndependent()throws Exception {
        core.bindOwner(profile,receive("dm","same-user","dm","").getString("id"),true);
        String other=addProfile(core,"relay-b","webhook");JSONObject routed=core.accept(other,message("dm","same-user","dm",""),"trusted_relay").getJSONObject("route");
        assertFalse(routed.getBoolean("privateOwner"));
    }
    @Test public void sharedGrantCannotEscalateToPhoneOrPrivateMemoryAndCanDisableChat()throws Exception {
        JSONObject event=receive("g","member","group","room-a");String space=event.getJSONObject("route").getString("spaceId");
        deny(()->core.grant(space,new JSONArray().put("phone.request")));
        deny(()->core.grant(space,new JSONArray().put("memory.private")));
        core.grant(space,new JSONArray());deny(()->core.beginAnswer(event.getString("id")));
    }
    @Test public void authenticatedMessageDedupSurvivesRestartAndIsProfileScoped()throws Exception {
        JSONObject first=receive("same-message","user","dm","");core=new ChannelCore(directory);
        JSONObject replay=core.accept(profile,message("same-message","user","dm",""),"trusted_relay");
        assertTrue(replay.getBoolean("duplicate"));assertEquals(first.getString("id"),replay.getString("eventId"));
        String other=addProfile(core,"relay-b","webhook");assertFalse(core.accept(other,message("same-message","user","dm",""),"trusted_relay").getBoolean("duplicate"));
    }
    @Test public void interruptedSendBecomesUnknownAndCannotBeBlindlyRetried()throws Exception {
        JSONObject event=receive("m","user","dm","");String reply=core.queueReply(event.getString("id"),"答案").getString("id");
        core.prepareSend(reply);core=new ChannelCore(directory);
        assertEquals("unknown",core.snapshot().getJSONArray("outbox").getJSONObject(0).getString("status"));
        deny(()->core.prepareSend(reply));
    }
    @Test public void uncertainReplyRequiresLocalReconciliationBeforeAnExplicitRetry()throws Exception {
        JSONObject event=receive("m","user","dm","");String reply=core.queueReply(event.getString("id"),"答案").getString("id");
        core.prepareSend(reply);core=new ChannelCore(directory);
        deny(()->core.resolveDelivery(reply,"not_delivered",false,"已查看原平台"));
        deny(()->core.resolveDelivery(reply,"not_delivered",true,""));
        JSONObject result=core.resolveDelivery(reply,"not_delivered",true,"已查看原会话，没有出现此消息");
        assertEquals("queued",result.getString("status"));assertEquals("local_owner",result.getJSONObject("reconciliation").getString("source"));
        assertEquals("queued",new ChannelCore(directory).snapshot().getJSONArray("outbox").getJSONObject(0).getString("status"));
        core.prepareSend(reply);core.delivery(reply,"sent","平台回执","platform-id");
        deny(()->core.resolveDelivery(reply,"not_delivered",true,"不能覆盖已知结果"));deny(()->core.prepareSend(reply));
    }
    @Test public void ownerConfirmedDeliveryIsFinalAndQueuedDraftCanBeCancelled()throws Exception {
        JSONObject event=receive("m","user","dm","");String first=core.queueReply(event.getString("id"),"已发送答案").getString("id");
        core.prepareSend(first);core.delivery(first,"unknown","网络结果未知","");
        assertEquals("sent",core.resolveDelivery(first,"delivered",true,"原会话已经显示这条答案").getString("status"));deny(()->core.prepareSend(first));
        String second=core.queueReply(event.getString("id"),"尚未发送答案").getString("id");assertEquals("cancelled",core.cancelDraft(second).getString("status"));
        deny(()->core.prepareSend(second));deny(()->core.delivery(second,"sent","迟来的旧回执","old-id"));
    }
    @Test public void revocationPreventsPrivateAnswerQueueAndPrivateDraftDelivery()throws Exception {
        core.bindOwner(profile,receive("bind","user","dm","").getString("id"),true);
        JSONObject event=receive("private-question","user","dm","");String id=event.getString("id");
        JSONObject draft=core.queueReplyExpected(id,"私人答案","private");core.unbindOwner(profile);
        deny(()->core.queueReplyExpected(id,"迟到的私人答案","private"));deny(()->core.prepareSend(draft.getString("id")));
    }
    @Test public void groupHistoryCannotReadPrivateOrAnotherGroupAndOldDmDoesNotPromote()throws Exception {
        receive("old-private","user","dm","");
        JSONObject bind=receive("bind","user","dm","");core.bindOwner(profile,bind.getString("id"),true);
        JSONObject owner=receive("new-private","user","dm","");assertEquals(0,core.history(owner,8).length());
        receive("ga","member","group","room-a");JSONObject groupB=receive("gb","member","group","room-b");
        assertEquals(0,core.history(groupB,8).length());JSONObject groupA=receive("ga2","other-member","group","room-a");
        assertEquals(1,core.history(groupA,8).length());assertFalse(core.history(groupA,8).toString().contains("new-private"));
    }
    @Test public void channelFollowupIncludesDeliveredAnswerButNoDraftUnknownOrFutureMessages()throws Exception {
        JSONObject question=receive("first","user","dm","");String eventId=question.getString("id");
        String delivered=core.queueReply(eventId,"已收到的回答").getString("id");core.prepareSend(delivered);core.delivery(delivered,"sent","平台回执","receipt-1");
        core.queueReply(eventId,"尚未发出的草稿");String uncertain=core.queueReply(eventId,"发送结果未知的内容").getString("id");core.prepareSend(uncertain);core.delivery(uncertain,"unknown","网络断开","");
        JSONObject followup=receive("followup","user","dm","");receive("future","user","dm","");
        JSONArray history=core.history(followup,8);assertEquals(2,history.length());assertEquals("assistant",history.getJSONObject(1).getString("role"));
        assertEquals("已收到的回答",history.getJSONObject(1).getString("content"));assertFalse(history.toString().contains("草稿"));assertFalse(history.toString().contains("未知的内容"));assertFalse(history.toString().contains("future"));
    }
    @Test public void qqRequiresItsSignedTransportAndSceneSpecificUserIdentity()throws Exception {
        String qq=addProfile(core,"qq-a","qq");deny(()->core.accept(qq,message("m","u","dm",""),"trusted_relay"));
        JSONObject envelope=new JSONObject().put("op",0).put("t","C2C_MESSAGE_CREATE").put("d",new JSONObject().put("id","m")
                .put("author",new JSONObject().put("id","user").put("username","owner").put("member_role","owner"))
                .put("content","hello").put("timestamp","2026-10-30T12:00:00Z"));
        deny(()->ChannelProtocol.canonicalQq(envelope));
        envelope.getJSONObject("d").getJSONObject("author").put("user_openid","verified-user");
        assertEquals("verified-user",ChannelProtocol.canonicalQq(envelope).getString("senderId"));
    }
    @Test public void qqExactRawByteReferenceAndTamperReplayAppIdRejection()throws Exception {
        String secret="naOC0ocQE3shWLAfffVLB1rhYPG7",timestamp="1725442341";
        byte[] body="{ \"op\": 0,\"d\": {}, \"t\": \"GATEWAY_EVENT_NAME\"}".getBytes(StandardCharsets.UTF_8);
        Map<String,String> headers=new HashMap<>();headers.put("x-bot-appid","123456");headers.put("x-signature-timestamp",timestamp);
        // Fixed signature generated independently with Python cryptography for these exact bytes.
        // Its public key matches QQ's published seed vector; the separate challenge fixture below
        // also matches QQ's published signature. Do not normalize JSON whitespace before signing.
        headers.put("x-signature-ed25519","2eb9983ebb8bb209e78fd095942f58e442656656e7975d01e64f9023a84b7c964290fdd40e5500c33867ccfe9563b7e0b6bac0e1d42c13e787b304fd51f71102");
        long now=Long.parseLong(timestamp)*1000;
        ChannelProtocol.authenticateQq("123456",secret,headers,body,now);
        deny(()->ChannelProtocol.authenticateQq("123456",secret,headers,"{}".getBytes(StandardCharsets.UTF_8),now));
        deny(()->ChannelProtocol.authenticateQq("other-app",secret,headers,body,now));
        deny(()->ChannelProtocol.authenticateQq("123456",secret,headers,body,now+301000));
    }
    @Test public void qqOfficialChallengeSignatureFixtureMatchesStandardProvider()throws Exception {
        String signed=ChannelProtocol.hex(ChannelProtocol.sign("DG5g3B4j9X2KOErG","1725442341Arq0D5A61EgUu4OxUvOp".getBytes(StandardCharsets.UTF_8)));
        assertEquals("87befc99c42c651b3aac0278e71ada338433ae26fcb24307bdc5ad38c1adc2d01bcfcadc0842edac85e85205028a1132afe09280305f13aa6909ffc2d652c706",signed);
    }
    @Test public void replyUrlProtectsCredentialTransport()throws Exception {
        ChannelHttp.validateReplyUrl("https://reply.example/events");ChannelHttp.validateReplyUrl("http://127.0.0.1:8080/reply");
        deny(()->ChannelHttp.validateReplyUrl("http://external.example/reply"));deny(()->ChannelHttp.validateReplyUrl("https://user:pass@reply.example/reply"));
        deny(()->ChannelHttp.validateReplyUrl("https://reply.example/reply?token=secret"));
    }
    @Test public void credentialOriginIncludesSchemeHostAndEffectivePort() {
        assertTrue(ChannelHttp.sameOrigin("https://relay.example/old","https://RELAY.example:443/new"));
        assertFalse(ChannelHttp.sameOrigin("https://old.example/","https://new.example/"));
        assertFalse(ChannelHttp.sameOrigin("https://relay.example/","http://relay.example/"));
        assertFalse(ChannelHttp.sameOrigin("http://127.0.0.1:8080/","http://127.0.0.1:8081/"));
        assertFalse(ChannelHttp.sameOrigin("","https://relay.example/"));
    }
    @Test public void credentialUpdateIsDisabledUntilMetadataAndVaultTransitionCanFinish()throws Exception {
        core.prepareCredentialChange(profile);
        JSONObject interrupted=new ChannelCore(directory).profile(profile);
        assertFalse(interrupted.getBoolean("enabled"));assertTrue(interrupted.getBoolean("configurationPending"));
        deny(()->core.accept(profile,message("blocked","u","dm",""),"trusted_relay"));
        core.saveProfile(new JSONObject().put("id",profile).put("name","relay-a").put("provider","webhook").put("enabled",true));
        assertTrue(core.profile(profile).getBoolean("enabled"));assertFalse(core.profile(profile).has("configurationPending"));
    }
    @Test public void failedPersistenceDoesNotLeaveAnUnsavedPermissionChangeInMemory()throws Exception {
        File blocked=new File(directory,"channels.pending");assertTrue(blocked.mkdir());
        try{core.prepareCredentialChange(profile);fail("expected write failure");}catch(java.io.IOException expected){}
        assertTrue(core.profile(profile).getBoolean("enabled"));assertTrue(new ChannelCore(directory).profile(profile).getBoolean("enabled"));
    }
    private JSONObject receive(String message,String sender,String kind,String room)throws Exception{return core.accept(profile,message(message,sender,kind,room),"trusted_relay").getJSONObject("event");}
    static JSONObject message(String id,String sender,String kind,String room)throws Exception{return new JSONObject().put("messageId",id).put("senderId",sender).put("kind",kind).put("roomId",room).put("text","内容:"+id).put("sentAt",System.currentTimeMillis());}
    static String addProfile(ChannelCore core,String name,String provider)throws Exception{return core.saveProfile(new JSONObject().put("name",name).put("provider",provider).put("appId",provider.equals("qq")?"123456":"").put("enabled",true)).getString("id");}
    interface Checked {void run()throws Exception;}
    static void deny(Checked call)throws Exception {try{call.run();fail("expected rejection");}catch(SecurityException|IllegalArgumentException|IllegalStateException expected){}}
}
