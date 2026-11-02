package dev.pocketengine.features.assistant.notifications;

import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OwnerQuestionReferenceTest {
    private static JSONObject task()throws Exception{
        String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),conversation=UUID.randomUUID().toString();
        return new JSONObject().put("id",task).put("runId",run).put("coordinatorEpoch",3).put("status","awaiting_user")
            .put("kind","assistant").put("executionMode","capabilities").put("actorId","owner").put("spaceId","private").put("conversationId",conversation)
            .put("ownerInputMailbox",new JSONObject().put("runId",run).put("epoch",3).put("conversationId",conversation).put("open",false))
            .put("evidence",new JSONObject().put("ownerQuestion",new JSONObject().put("format","pocketworld.owner-question.v1")
                .put("taskId",task).put("runId",run).put("coordinatorEpoch",3).put("requestId",UUID.randomUUID().toString())
                .put("waitRevision",9).put("question","Which original option?").put("replyKind","answer").put("approval",false).put("takeover",false)));
    }
    @Test public void onlyExactOrdinaryWaitingQuestionCanHaveAnInlineReply()throws Exception{
        JSONObject original=task(),identity=OwnerQuestionReply.reference(original);assertNotNull(identity);
        assertEquals(original.getString("runId"),identity.getString("runId"));assertFalse(identity.has("question"));
        JSONObject changed=new JSONObject(original.toString());changed.getJSONObject("evidence").getJSONObject("ownerQuestion").put("approval",true);assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.getJSONObject("evidence").getJSONObject("ownerQuestion").put("takeover",true);assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.put("runId",UUID.randomUUID().toString());assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.put("coordinatorEpoch",4);assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.put("status","completed");assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.getJSONObject("evidence").getJSONObject("ownerQuestion").put("waitRevision",0);assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.getJSONObject("evidence").getJSONObject("ownerQuestion").put("replyConsumed",true);assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.put("kind","phone").put("executionMode","background");assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.getJSONObject("ownerInputMailbox").put("open",true);assertNull(OwnerQuestionReply.reference(changed));
        changed=new JSONObject(original.toString());changed.getJSONObject("ownerInputMailbox").put("conversationId",UUID.randomUUID().toString());assertNull(OwnerQuestionReply.reference(changed));
    }
}
