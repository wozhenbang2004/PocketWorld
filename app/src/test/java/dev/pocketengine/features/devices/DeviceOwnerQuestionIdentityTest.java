package dev.pocketengine.features.devices;

import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DeviceOwnerQuestionIdentityTest {
    private static JSONObject evidence(String task,String run)throws Exception{return new JSONObject().put("ownerQuestion",new JSONObject().put("format","pocketworld.owner-question.v1").put("taskId",task).put("runId",run).put("coordinatorEpoch",4)
        .put("requestId",UUID.randomUUID().toString()).put("waitRevision",7).put("question","Which original input?").put("replyKind","answer").put("approval",false).put("takeover",false));}
    @Test public void nativeDeviceTaskIdsAreAcceptedWithoutPretendingTheyArePrivateConversationUuids()throws Exception{
        String task="dt1_"+"a".repeat(64),run=UUID.randomUUID().toString();JSONObject nativeEvidence=evidence(task,run),identity=DeviceOwnerInputs.question(nativeEvidence,task,run,4);
        assertNotNull(identity);assertEquals(task,identity.getString("taskId"));assertEquals(4,identity.getInt("executorEpoch"));assertFalse(identity.getBoolean("approval"));assertFalse(identity.getBoolean("takeover"));assertFalse(identity.has("grant"));assertFalse(identity.has("controlLeaseId"));
        JSONObject changed=new JSONObject(nativeEvidence.toString());changed.getJSONObject("ownerQuestion").put("approval",true);assertNull(DeviceOwnerInputs.question(changed,task,run,4));
        changed=new JSONObject(nativeEvidence.toString());changed.getJSONObject("ownerQuestion").put("takeover",true);assertNull(DeviceOwnerInputs.question(changed,task,run,4));
        changed=new JSONObject(nativeEvidence.toString());changed.getJSONObject("ownerQuestion").put("replyConsumed",true);assertNull(DeviceOwnerInputs.question(changed,task,run,4));
        changed=new JSONObject(nativeEvidence.toString());changed.getJSONObject("ownerQuestion").put("requestId","model-made-request");assertNull(DeviceOwnerInputs.question(changed,task,run,4));
        changed=new JSONObject(nativeEvidence.toString());changed.getJSONObject("ownerQuestion").put("waitRevision",7.5);assertNull(DeviceOwnerInputs.question(changed,task,run,4));
        assertNull(DeviceOwnerInputs.question(nativeEvidence,task,run,5));assertNull(DeviceOwnerInputs.question(nativeEvidence,task,UUID.randomUUID().toString(),4));
    }
}
