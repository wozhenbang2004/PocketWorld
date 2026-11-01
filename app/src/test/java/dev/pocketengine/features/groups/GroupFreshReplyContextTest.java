package dev.pocketengine.features.groups;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/** Actual registry/module; deterministic clock, authenticated ingress/host/delivery ports are fixtures. */
public final class GroupFreshReplyContextTest {
    static final class Transport implements GroupDeliveryPort {
        final List<Delivery> sent=new ArrayList<>();final Map<String,Receipt> receipts=new HashMap<>();boolean unknown;
        public boolean supports(String provider,String account,String audience,String mime){return true;}
        public Receipt send(Delivery value,GroupHostPort.Authority authority)throws Exception {authority.check();sent.add(value);Receipt actual=new Receipt(value.deliveryId,unknown?"unknown":"accepted",unknown?"":"fixture-platform-"+sent.size());receipts.put(value.deliveryId,actual);return actual;}
        public Receipt inspect(Delivery value,GroupHostPort.Authority authority)throws Exception {authority.checkRead();return receipts.get(value.deliveryId);}
        public int remainingReplies(GroupIngressPort.Event event,GroupHostPort.Authority authority)throws Exception {authority.checkRead();int count=0;for(Delivery d:sent)if(d.event.eventId.equals(event.eventId))count++;return Math.max(0,5-count);}
    }
    void mount(GroupsModuleTest.Fixture f,Transport transport)throws Exception {f.module.close();f.module=new GroupsModule(f.directory,f.ingress,f.host,transport,()->{if(!f.ownerAllowed)throw new SecurityException("fixture owner denied");},f.now::get);}
    @Test public void completedOutputWaitsForFreshWindowWithoutChangingOriginOrReexecuting()throws Exception {try(var f=new GroupsModuleTest.Fixture()){
        Transport transport=new Transport();mount(f,transport);f.enable("public.answer");String id=f.request("alice");GroupHostPort.Request original=f.last();f.host.finish(original,List.of(f.output("real-output","text/plain","group")));
        f.now.addAndGet(300001);JSONObject deferred=f.module.publishTaskState(id);assertTrue(deferred.getBoolean("waitingNewReplyContext"));assertEquals(0,transport.sent.size());
        f.event("new-status","bob");JSONObject queried=f.module.replyTaskStatus("new-status",id);assertTrue(queried.getBoolean("taskReadOnly"));assertFalse(queried.getBoolean("resubmitsTask"));assertFalse(queried.has("queryOnly"));assertEquals(2,transport.sent.size());GroupDeliveryPort.Delivery actual=transport.sent.get(1);assertEquals("new-status",actual.event.eventId);assertEquals("bob",actual.senderId);assertEquals(original.taskId,actual.taskId);assertEquals("real-output",actual.output.outputId);assertEquals(1,f.host.admitted);
        AtomicReference<GroupHostPort.Authority> escaped=new AtomicReference<>();JSONObject evidence=f.module.withOwnerDeliveryRead(actual.deliveryId,(request,delivery,read)->{read.checkRead();assertThrows(SecurityException.class,read::check);escaped.set(read);return new JSONObject().put("origin",request.event.eventId).put("reply",delivery.event.eventId);});
        assertEquals(original.event.eventId,evidence.getString("origin"));assertEquals("new-status",evidence.getString("reply"));assertThrows(SecurityException.class,escaped.get()::checkRead);
        f.event("next-status","alice");f.module.replyTaskStatus("next-status",id);assertEquals(3,transport.sent.size());assertEquals(1,f.host.admitted);assertEquals("accepted",queried.getJSONArray("deliveries").getJSONObject(0).getString("state"));
    }}
    @Test public void unknownOutputNeverMovesToAnotherReplyContextOrSendsAgainAfterRestart()throws Exception {try(var f=new GroupsModuleTest.Fixture()){
        Transport transport=new Transport();mount(f,transport);f.enable("public.answer");String id=f.request("alice");f.host.finish(f.last(),List.of(f.output("real-output","text/plain","group")));transport.unknown=true;JSONObject first=f.module.sendOutput(id,"real-output");String deliveryId=first.getString("deliveryId");assertEquals(1,transport.sent.size());
        f.module.close();f.module=new GroupsModule(f.directory,f.ingress,f.host,transport,()->{},f.now::get);f.now.addAndGet(300001);f.event("fresh","alice");transport.unknown=false;JSONObject queried=f.module.replyTaskStatus("fresh",id);assertEquals(2,transport.sent.size());assertEquals("unknown",queried.getJSONArray("deliveries").getJSONObject(0).getString("state"));assertEquals(deliveryId,queried.getJSONArray("deliveries").getJSONObject(0).getString("deliveryId"));assertEquals(1,f.host.admitted);
        assertEquals("real-output",transport.sent.get(0).output.outputId);assertNotEquals("fresh",transport.sent.get(0).event.eventId);
    }}
    @Test public void currentReaderRevocationInsideHostReadBlocksAnyFreshReply()throws Exception {try(var f=new GroupsModuleTest.Fixture()){
        f.enable("public.answer");String id=f.request("alice");Transport transport=new Transport();f.module.close();
        GroupHostPort host=new GroupHostPort(){
            public TaskReceipt admit(Request request,Authority authority)throws Exception{return f.host.admit(request,authority);}
            public TaskReceipt inspect(Request request,Authority authority)throws Exception {TaskReceipt result=f.host.inspect(request,authority);f.member("bob","member",false);return result;}
            public TaskReceipt cancel(Request request,String run,int epoch,Authority authority)throws Exception{return f.host.cancel(request,run,epoch,authority);}
        };
        f.module=new GroupsModule(f.directory,f.ingress,host,transport,()->{},f.now::get);f.event("read","bob");assertThrows(SecurityException.class,()->f.module.replyTaskStatus("read",id));assertEquals(0,transport.sent.size());
    }}
    @Test public void persistedPreparedIntentIsOnlyQueriedAfterCrashBeforeFirstDispatch()throws Exception {try(var f=new GroupsModuleTest.Fixture()){
        Transport transport=new Transport();mount(f,transport);f.enable("public.answer");String id=f.request("alice");GroupHostPort.Output output=f.output("real-output","text/plain","group");f.host.finish(f.last(),List.of(output));
        f.module.close();try(var registry=new GroupRegistry(f.directory)){assertTrue(registry.prepareDelivery(id,output,f.now.get()).newlyPrepared);}
        f.module=new GroupsModule(f.directory,f.ingress,f.host,transport,()->{},f.now::get);assertEquals("prepared",f.module.sendOutput(id,"real-output").getString("state"));assertEquals(0,transport.sent.size());
        f.event("fresh","alice");f.module.replyTaskStatus("fresh",id);assertEquals(1,transport.sent.size());assertEquals("feedback",transport.sent.get(0).kind);assertEquals(1,f.host.admitted);
    }}
}
