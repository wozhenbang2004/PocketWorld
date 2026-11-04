package dev.pocketengine.features.groups;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Real registry/module receipts; explicit fixture ports do not send to a platform. */
public class GroupDeliveryReceiptIdentityTest {
    private static void assertOriginal(JSONObject receipt, String requestId, String taskId, String outputId) throws Exception {
        assertEquals(requestId, receipt.getString("requestId"));
        assertEquals(taskId, receipt.getString("taskId"));
        assertEquals(outputId, receipt.getString("outputId"));
        assertEquals("group", receipt.getString("audience"));
        assertFalse(receipt.has("event"));
        assertFalse(receipt.has("output"));
        assertFalse(receipt.has("text"));
        assertFalse(receipt.has("artifactId"));
    }

    @Test public void sendAndReadKeepOnlyTheOriginalOutputIdentityWithoutAnotherSend() throws Exception {
        try (GroupsModuleTest.Fixture f = new GroupsModuleTest.Fixture()) {
            f.enable("public.answer");
            String requestId = f.request("alice"), taskId = f.last().taskId;
            f.host.finish(f.last(), List.of(f.output("output-original", "text/plain", "group")));
            JSONObject task = f.inspect(requestId).getJSONObject("task");
            JSONObject reviewed = new JSONObject().put("requestId", requestId).put("outputId", "output-original")
                    .put("runId", task.getString("runId")).put("coordinatorEpoch", task.getInt("coordinatorEpoch"))
                    .put("taskRevision", task.getLong("revision"))
                    .put("expectedOutput", new JSONObject(task.getJSONArray("outputs").getJSONObject(0).toString()));
            JSONObject sent = f.module.ownerCommand("delivery.send", reviewed);
            assertOriginal(sent, requestId, taskId, "output-original");
            assertEquals("accepted", sent.getString("state"));
            String deliveryId = sent.getString("deliveryId");
            f.delivery.receipts.put(deliveryId, new GroupDeliveryPort.Receipt(deliveryId, "delivered", "platform-1"));
            JSONObject read = f.module.ownerCommand("delivery.inspect", new JSONObject().put("deliveryId", deliveryId));
            assertOriginal(read, requestId, taskId, "output-original");
            assertEquals("delivered", read.getString("state"));
            assertEquals("platform-1", read.getString("platformMessageId"));
            assertEquals(1, f.delivery.sent);
        }
    }

    @Test public void missingAcknowledgementAfterRestartRetainsUnknownAndTheStoredOutput() throws Exception {
        try (GroupsModuleTest.Fixture f = new GroupsModuleTest.Fixture()) {
            f.enable("public.answer");
            String requestId = f.request("alice"), taskId = f.last().taskId;
            f.host.finish(f.last(), List.of(f.output("output-unknown", "text/plain", "group")));
            f.delivery.unknown = true;
            assertThrows(IOException.class, () -> f.module.sendOutput(requestId, "output-unknown"));
            JSONObject stored = f.module.ownerSnapshot().getJSONArray("deliveries").getJSONObject(0);
            String deliveryId = stored.getString("deliveryId");
            assertEquals("output-unknown", stored.getJSONObject("output").getString("outputId"));
            f.delivery.receipts.clear();
            f.closeAndReopen();
            JSONObject read = f.module.ownerCommand("delivery.inspect", new JSONObject().put("deliveryId", deliveryId));
            assertOriginal(read, requestId, taskId, "output-unknown");
            assertEquals("unknown", read.getString("state"));
            assertEquals("", read.getString("platformMessageId"));
            JSONObject same = f.module.sendOutput(requestId, "output-unknown");
            assertOriginal(same, requestId, taskId, "output-unknown");
            assertEquals(deliveryId, same.getString("deliveryId"));
            assertEquals("unknown", same.getString("state"));
            assertEquals(1, f.delivery.sent);
        }
    }

    @Test public void aForeignReceiptAndAnotherAudienceCannotReplaceTheOriginal() throws Exception {
        try (GroupsModuleTest.Fixture f = new GroupsModuleTest.Fixture()) {
            f.enable("public.answer");
            String requestId = f.request("alice"), taskId = f.last().taskId;
            f.host.finish(f.last(), List.of(f.output("output-original", "text/plain", "group")));
            JSONObject sent = f.module.sendOutput(requestId, "output-original");
            String deliveryId = sent.getString("deliveryId");
            f.delivery.receipts.put(deliveryId, new GroupDeliveryPort.Receipt("another-delivery", "accepted", "platform-other"));
            assertThrows(SecurityException.class, () -> f.module.ownerCommand("delivery.inspect", new JSONObject().put("deliveryId", deliveryId)));
            f.host.finish(f.last(), List.of(f.output("output-original", "text/plain", "requester")));
            assertThrows(SecurityException.class, () -> f.module.sendOutput(requestId, "output-original"));
            f.delivery.receipts.clear();
            JSONObject read = f.module.ownerCommand("delivery.inspect", new JSONObject().put("deliveryId", deliveryId));
            assertOriginal(read, requestId, taskId, "output-original");
            assertEquals("accepted", read.getString("state"));
            assertEquals(1, f.delivery.sent);
        }
    }
}
