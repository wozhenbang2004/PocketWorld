package dev.pocketengine.features.groups;

import static org.junit.Assert.*;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Exercises the actual owner command and durable outbox with the existing native fixture ports. */
public class GroupReviewedDeliveryTest {
    private JSONObject review(GroupsModuleTest.Fixture fixture,String requestId,String outputId)throws Exception {
        JSONObject task=fixture.inspect(requestId).getJSONObject("task");
        return new JSONObject().put("requestId",requestId).put("outputId",outputId).put("runId",task.getString("runId"))
            .put("coordinatorEpoch",task.getInt("coordinatorEpoch")).put("taskRevision",task.getLong("revision"))
            .put("expectedOutput",new JSONObject(task.getJSONArray("outputs").getJSONObject(0).toString()));
    }
    @Test public void changedTaskVersionCannotSendAnEarlierReviewedOutput()throws Exception {try(GroupsModuleTest.Fixture fixture=new GroupsModuleTest.Fixture()) {
        fixture.enable("public.answer");String id=fixture.request("alice");var request=fixture.last();
        var output=fixture.output("answer-a","text/plain","group");fixture.host.finish(request,List.of(output));
        JSONObject reviewed=review(fixture,id,"answer-a");fixture.host.finish(request,List.of(output));
        assertThrows(SecurityException.class,()->fixture.module.ownerCommand("delivery.send",reviewed));
        assertEquals(0,fixture.delivery.sent);assertEquals(0,fixture.module.ownerSnapshot().getJSONArray("deliveries").length());
    }}
    @Test public void changedContentWithTheSameOutputIdentityCannotBeSent()throws Exception {try(GroupsModuleTest.Fixture fixture=new GroupsModuleTest.Fixture()) {
        fixture.enable("public.answer");String id=fixture.request("alice");var request=fixture.last();
        fixture.host.finish(request,List.of(fixture.output("answer-a","text/plain","group")));
        JSONObject reviewed=review(fixture,id,"answer-a");var actual=fixture.host.tasks.get(request.taskId);
        var changed=new GroupHostPort.Output("answer-a",request.spaceId,"group","text/plain","","后来变化的内容",100);
        fixture.host.tasks.put(request.taskId,new GroupHostPort.TaskReceipt(request.taskId,actual.runId,actual.coordinatorEpoch,actual.revision,"completed","",List.of(changed)));
        assertThrows(SecurityException.class,()->fixture.module.ownerCommand("delivery.send",reviewed));
        assertEquals(0,fixture.delivery.sent);
    }}
    @Test public void unchangedReviewedOutputUsesTheExistingOutboxOnlyOnce()throws Exception {try(GroupsModuleTest.Fixture fixture=new GroupsModuleTest.Fixture()) {
        fixture.enable("public.answer");String id=fixture.request("alice");var request=fixture.last();
        fixture.host.finish(request,List.of(fixture.output("answer-a","text/plain","group")));
        JSONObject reviewed=review(fixture,id,"answer-a");
        JSONObject first=fixture.module.ownerCommand("delivery.send",reviewed);
        JSONObject second=fixture.module.ownerCommand("delivery.send",reviewed);
        assertEquals(first.getString("deliveryId"),second.getString("deliveryId"));assertEquals("accepted",first.getString("state"));assertEquals(1,fixture.delivery.sent);
    }}
}
