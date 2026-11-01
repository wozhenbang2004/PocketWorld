package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class ProjectLiveInputTest {
    private static String id(int digit){return String.valueOf(digit).repeat(8)+"-"+String.valueOf(digit).repeat(4)+"-4"+String.valueOf(digit).repeat(3)+"-8"+String.valueOf(digit).repeat(3)+"-"+String.valueOf(digit).repeat(12);}
    private static JSONObject task()throws Exception{return new JSONObject().put("id",id(1)).put("runId",id(2)).put("conversationId",id(3)).put("coordinatorEpoch",4).put("kind","assistant").put("actorId","owner").put("spaceId","private").put("status","running").put("developmentContext",new JSONObject().put("checkoutId",id(5)));}
    private static JSONObject ack(JSONObject record)throws Exception{return ProjectSources.copy(record.getJSONObject("target")).put("clientMessageId",record.getString("clientMessageId")).put("status","queued");}
    @Test public void sameRunProjectInputDoesNotCarryNewGrantOrStartAnotherTask()throws Exception {
        JSONObject record=ProjectLiveInput.prepare(task(),id(5),"保留正在运行的服务，增加排序"),payload=ProjectLiveInput.payload(record);assertEquals("steer",payload.getString("mode"));assertEquals(id(1),payload.getString("taskId"));assertEquals(id(2),payload.getString("runId"));assertEquals(id(3),payload.getString("conversationId"));assertEquals(4,payload.getInt("epoch"));assertEquals(0,payload.getJSONArray("attachments").length());for(String key:new String[]{"grant","approval","operationId","runtimeId","plan"})assertFalse(payload.has(key));assertTrue(ProjectLiveInput.acknowledged(record,ack(record)));
    }
    @Test public void uncertainPartialAndOtherTargetAcksNeverClearTheOriginalInput()throws Exception {
        JSONObject record=ProjectLiveInput.prepare(task(),id(5),"明确的原项目补充");for(String key:new String[]{"clientMessageId","taskId","runId","conversationId","epoch"}){JSONObject wrong=ack(record);wrong.remove(key);assertFalse(key,ProjectLiveInput.acknowledged(record,wrong));wrong=ack(record).put(key,key.equals("epoch")?5:id(6));assertFalse(key,ProjectLiveInput.acknowledged(record,wrong));}assertFalse(ProjectLiveInput.acknowledged(record,ack(record).put("status","unconfirmed")));assertFalse(ProjectLiveInput.acknowledged(record,null));
    }
    @Test public void persistedOriginalPayloadHasStableClientAndRejectsChangedTextOrTarget()throws Exception {
        JSONObject record=ProjectLiveInput.prepare(task(),id(5),"原稿");JSONObject reopened=ProjectLiveInput.verified(new JSONObject(record.toString()));assertEquals(ProjectSources.canonical(ProjectLiveInput.payload(record)),ProjectSources.canonical(ProjectLiveInput.payload(reopened)));assertThrows(SecurityException.class,()->ProjectLiveInput.verified(ProjectSources.copy(record).put("message","另一个稿")));JSONObject changed=ProjectSources.copy(record);changed.getJSONObject("target").put("runId",id(6));assertThrows(SecurityException.class,()->ProjectLiveInput.verified(changed));
    }
    @Test public void endedForeignGroupAndOtherCheckoutDoNotBecomeLiveInputTargets()throws Exception {
        assertThrows(SecurityException.class,()->ProjectLiveInput.prepare(task().put("status","completed"),id(5),"x"));assertThrows(SecurityException.class,()->ProjectLiveInput.prepare(task().put("actorId","someone_else"),id(5),"x"));assertThrows(SecurityException.class,()->ProjectLiveInput.prepare(task().put("origin",new JSONObject().put("kind","group")),id(5),"x"));assertThrows(SecurityException.class,()->ProjectLiveInput.prepare(task(),id(6),"x"));assertThrows(IllegalArgumentException.class,()->ProjectLiveInput.prepare(task(),id(5)," "));assertThrows(IllegalArgumentException.class,()->ProjectLiveInput.prepare(task(),id(5),"x".repeat(8001)));
    }
}
