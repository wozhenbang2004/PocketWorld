package dev.pocketengine.features.channels;

import static org.junit.Assert.*;

import dev.pocketengine.features.groups.GroupDeliveryPort;
import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.features.groups.GroupsModule;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Real group registry/proof/Outbox. The task and upload/message transitions are explicit fixtures, not QQ HTTP. */
public class GroupDeliveryEvidenceTest {
    static final class Fixture implements AutoCloseable {
        final File directory=Files.createTempDirectory("group-evidence-").toFile();
        final ChannelCore core=new ChannelCore(new File(directory,"channel"));
        final GroupChannelBridge bridge=new GroupChannelBridge(core,new ChannelHttp(),id->"fixture-secret",new File(directory,"channel"));
        final GroupsModule groups;final boolean acknowledge;JSONObject event,group;GroupHostPort.Request request;GroupHostPort.Output output;int sends;
        Fixture(boolean acknowledge)throws Exception {
            this.acknowledge=acknowledge;
            JSONObject profile=core.saveProfile(new JSONObject().put("id","fixture-qq").put("name","Fixture QQ metadata").put("provider","qq").put("enabled",true).put("appId","fixture-app"));
            GroupHostPort taskPort=new GroupHostPort() {
                public TaskReceipt admit(Request value,Authority authority)throws Exception{authority.check();request=value;output=new Output(UUID.randomUUID().toString(),value.spaceId,"group","image/png",UUID.randomUUID().toString(),"",12);return receipt(value);}
                public TaskReceipt inspect(Request value,Authority authority)throws Exception{authority.checkRead();return receipt(value);}
                public TaskReceipt cancel(Request value,String run,int epoch,Authority authority)throws Exception{authority.check();return receipt(value);}
                private TaskReceipt receipt(Request value){return new TaskReceipt(value.taskId,"",0,1,"completed","fixture media metadata",List.of(output));}
            };
            GroupDeliveryPort platformFixture=new GroupDeliveryPort() {
                public boolean supports(String provider,String account,String audience,String mime){return true;}
                public Receipt send(Delivery delivery,GroupHostPort.Authority authority)throws Exception {
                    sends++;core.queueGroupMedia(delivery.deliveryId,event,delivery.output,authority);core.prepareGroupSend(delivery.deliveryId,authority);
                    core.groupMediaPhase(delivery.deliveryId,"uploading","",authority);core.groupMediaPhase(delivery.deliveryId,"uploaded","PRIVATE-UPLOAD-TOKEN",authority);
                    if(acknowledge){core.groupMediaPhase(delivery.deliveryId,"message_sending","",authority);core.delivery(delivery.deliveryId,"sent","fixture accepted","fixture-platform-message");}
                    else core.delivery(delivery.deliveryId,"unknown","fixture message receipt unavailable","");
                    return new Receipt(delivery.deliveryId,acknowledge?"accepted":"unknown",acknowledge?"fixture-platform-message":"");
                }
                public Receipt inspect(Delivery delivery,GroupHostPort.Authority authority)throws Exception{authority.check();return new Receipt(delivery.deliveryId,acknowledge?"accepted":"unknown",acknowledge?"fixture-platform-message":"");}
            };
            groups=new GroupsModule(new File(directory,"groups"),bridge,taskPort,platformFixture,()->{});bridge.bind(groups,Runnable::run);
            group=groups.ownerCommand("group.create",new JSONObject().put("name","Fixture group").put("provider","qq").put("accountId",profile.getString("id")).put("roomId","fixture-room"));
            group=groups.ownerCommand("member.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("senderId","fixture-member").put("displayName","Fixture member").put("role","member").put("enabled",true));
            JSONObject quota=group.getJSONObject("quota").put("dailyTokens",64000).put("maxTokensPerTask",32000);
            group=groups.ownerCommand("policy.set",new JSONObject().put("id",group.getString("id")).put("revision",group.getLong("revision")).put("enabled",true).put("visibility","members").put("quota",quota).put("grants",new JSONArray().put(new JSONObject().put("capability","media.image").put("resource","media-fixture").put("expiresAt",System.currentTimeMillis()+3600000).put("allowGroupAudience",true))));
            event=core.accept(profile.getString("id"),new JSONObject().put("messageId","fixture-message").put("senderId","fixture-member").put("roomId","fixture-room").put("kind","group").put("text","/image --tokens=32000 fixture image").put("sentAt",System.currentTimeMillis()),"qq_gateway").getJSONObject("event");assertTrue(bridge.accepted(profile,event.getString("id")));
        }
        String send()throws Exception{return groups.sendOutput(request.requestId,output.outputId).getString("deliveryId");}
        JSONObject read(String delivery)throws Exception{return groups.withOwnerTaskRead(request.requestId,(original,read)->bridge.inspectDeliveryEvidence(original,delivery,output,read));}
        @Override public void close()throws Exception{groups.close();bridge.close();try(var paths=Files.walk(directory.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception ignored){}});}}
    }
    @Test public void uploadWithoutMessageAcknowledgementIsQueryableAndNeverDelivered()throws Exception {
        try(Fixture f=new Fixture(false)) {
            String delivery=f.send();JSONObject value=f.read(delivery);assertEquals("uploaded",value.getString("uploadState"));assertEquals("unknown",value.getString("state"));
            assertFalse(value.getBoolean("messageAcknowledged"));assertFalse(value.getBoolean("readConfirmed"));assertTrue(value.getBoolean("queryOnly"));
            assertEquals("delivery_unknown",value.getJSONObject("error").getString("code"));assertFalse(value.toString().contains("PRIVATE-UPLOAD-TOKEN"));
            f.core.resolveDelivery(delivery,"delivered",true,"fixture owner checked, no platform id");assertEquals("unknown",f.read(delivery).getString("state"));assertEquals(1,f.sends);
        }
    }
    @Test public void acceptedMessageSurvivesGroupRevocationAsNativeReadWithoutSendAuthority()throws Exception {
        try(Fixture f=new Fixture(true)) {
            String delivery=f.send();f.groups.ownerCommand("policy.set",new JSONObject().put("id",f.group.getString("id")).put("revision",f.group.getLong("revision")).put("enabled",false).put("visibility","members").put("quota",f.group.getJSONObject("quota")).put("grants",f.group.getJSONArray("grants")));
            JSONObject value=f.read(delivery);assertEquals("accepted",value.getString("state"));assertEquals("uploaded",value.getString("uploadState"));assertTrue(value.getBoolean("messageAcknowledged"));assertEquals("fixture-platform-message",value.getString("platformMessageId"));assertFalse(value.getBoolean("readConfirmed"));
            assertThrows(SecurityException.class,()->f.groups.sendOutput(f.request.requestId,f.output.outputId));assertEquals(1,f.sends);
        }
    }
    @Test public void exactDeliveryAndMediaIdentityRejectCrossOutputAndChangedArtifact()throws Exception {
        try(Fixture f=new Fixture(true)) {
            String delivery=f.send();assertThrows(SecurityException.class,()->f.read(UUID.randomUUID().toString()));
            GroupHostPort.Output changed=new GroupHostPort.Output(f.output.outputId,f.output.spaceId,f.output.audience,f.output.mimeType,UUID.randomUUID().toString(),"",f.output.bytes);
            assertThrows(SecurityException.class,()->f.groups.withOwnerTaskRead(f.request.requestId,(request,read)->f.bridge.inspectDeliveryEvidence(request,delivery,changed,read)));
            assertEquals(1,f.sends);
        }
    }
    @Test public void resultReadChecksAuthorityAgainAfterOriginalOutboxSnapshot()throws Exception {
        try(Fixture f=new Fixture(true)) {
            String delivery=f.send();AtomicInteger checks=new AtomicInteger();
            GroupHostPort.Authority read=new GroupHostPort.Authority(){public void check(){throw new AssertionError("read must never ask execution authority");}public void checkRead(){if(checks.incrementAndGet()==2)throw new SecurityException("fixture owner revoked after snapshot");}};
            assertThrows(SecurityException.class,()->f.bridge.inspectDeliveryEvidence(f.request,delivery,f.output,read));assertEquals(2,checks.get());assertEquals(1,f.sends);
        }
    }
}
