package dev.pocketengine.store;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConversationPhoneHandoffTest {
    private static final String TASK="10000000-0000-0000-0000-000000000001",CONVERSATION="20000000-0000-0000-0000-000000000002";
    private static final String RUN="30000000-0000-0000-0000-000000000003",NEXT="40000000-0000-0000-0000-000000000004";
    private static JSONObject source()throws Exception{return new JSONObject().put("format","pocketworld.phone-handoff-source.v1")
            .put("generation","50000000-0000-0000-0000-000000000005").put("conversationId",CONVERSATION)
            .put("sourceMessageId","60000000-0000-0000-0000-000000000006").put("sourceMessageRevision",2L)
            .put("bodyFingerprint","sha256:"+"a".repeat(64)).put("sourceSequence",8L).put("conversationTitle","原对话");}
    private static JSONObject facts()throws Exception{return new JSONObject().put("modelName","fixture").put("provider","openai")
            .put("toolLimit",4).put("modelCallLimit",12).put("scopeMinutes",30).put("modelSelectionRevision",2L);}
    private static JSONObject review()throws Exception{return ConversationPhoneHandoff.review(TASK,source(),"查看今日天气","background",facts());}
    private static JSONObject task(JSONObject review)throws Exception{return new JSONObject().put("id",TASK).put("goal",review.getString("goal"))
            .put("kind","phone").put("executionMode","background").put("actorId","owner").put("spaceId","private")
            .put("createdAt",71L).put("status","preparing").put("coordinatorEpoch",0).put("summary","")
            .put("conversationPhoneHandoff",ConversationPhoneHandoff.link(review,71));}
    private static JSONObject ended()throws Exception{return task(review()).put("runId",RUN).put("coordinatorEpoch",1)
            .put("status","needs_confirmation").put("summary","实际已看到天气，待主人核对").put("resultRevision",5L)
            .put("evidence",new JSONObject().put("screenText","实际屏幕文字").put("imageBase64","not-copied"));}

    @Test public void reviewPinsOnlyPublicFactsAndCopiesOriginalValues()throws Exception {
        JSONObject source=source(),facts=facts(),review=ConversationPhoneHandoff.review(TASK,source,"  查看天气  ","background",facts);
        source.put("sourceMessageRevision",99);facts.put("modelName","changed");
        assertEquals(TASK,review.getString("requestId"));assertEquals(TASK,review.getString("taskId"));assertEquals("查看天气",review.getString("goal"));
        assertEquals(2L,review.getJSONObject("source").getLong("sourceMessageRevision"));assertEquals("fixture",review.getJSONObject("reviewFacts").getString("modelName"));
        assertFalse(review.has("deadlineAt"));assertFalse(review.has("grant"));assertFalse(review.has("origin"));assertFalse(review.has("parentTaskId"));
    }
    @Test public void reviewRejectsCredentialFieldsAndCoercedBudgets()throws Exception {
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.review(TASK,source(),"goal","background",facts().put("apiKey","secret")));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.review(TASK,source().put("parentTaskId",RUN),"goal","background",facts()));
        for(Object bad:new Object[]{"4",4.0,true,0,401})assertThrows(SecurityException.class,()->ConversationPhoneHandoff.review(TASK,source(),"goal","background",facts().put("toolLimit",bad)));
        assertThrows(IllegalArgumentException.class,()->ConversationPhoneHandoff.review(TASK,source(),"goal","background",facts().put("scopeMinutes",10)));
    }
    @Test public void creationAckSeparatesStoredTaskFromAdmittedRunAndKeepsIdentity()throws Exception {
        JSONObject task=task(review()),created=ConversationPhoneHandoff.creation(task,false);
        assertTrue(created.getBoolean("accepted"));assertTrue(created.getBoolean("created"));assertFalse(created.getBoolean("admitted"));assertFalse(created.getBoolean("duplicate"));
        task.put("runId",RUN).put("coordinatorEpoch",1).put("status","running");
        JSONObject queried=ConversationPhoneHandoff.creation(task,true);assertTrue(queried.getBoolean("admitted"));assertTrue(queried.getBoolean("duplicate"));
        assertFalse(queried.getBoolean("created"));
        assertEquals(created.getString("requestId"),queried.getString("requestId"));assertEquals(created.getLong("createdAt"),queried.getLong("createdAt"));
    }
    @Test public void preservedNativeSourceIdsNeedNotBeUuids()throws Exception {
        JSONObject source=source().put("conversationId","original-conversation").put("sourceMessageId","message-first-0");
        JSONObject review=ConversationPhoneHandoff.review(TASK,source,"goal","background",facts());
        assertEquals("original-conversation",review.getString("conversationId"));assertEquals("message-first-0",review.getJSONObject("source").getString("sourceMessageId"));
    }
    @Test public void repeatedReviewAllowsStatusChangesButCannotChangeCreationOrAuthority()throws Exception {
        JSONObject review=review(),task=task(review);task.put("status","failed").put("runId",RUN).put("coordinatorEpoch",1);
        ConversationPhoneHandoff.requireSameReview(task,review);
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.requireSameReview(task,new JSONObject(review.toString()).put("reviewDigest","0".repeat(64))));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.association(new JSONObject(task.toString()).put("goal","different")));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.association(new JSONObject(task.toString()).put("origin",new JSONObject().put("kind","channel"))));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.association(new JSONObject(task.toString()).put("parentTaskId",RUN)));
    }
    @Test public void eachRunFreezesItsResultAndOnlyAnEvidenceReference()throws Exception {
        JSONObject task=ended(),first=ConversationPhoneHandoff.result(task,RUN,1,"needs_confirmation",81);
        String original=first.toString();task.put("runId",NEXT).put("coordinatorEpoch",2).put("status","failed").put("summary","继续后失败");
        task.getJSONObject("evidence").put("screenText","新现场");JSONObject second=ConversationPhoneHandoff.result(task,NEXT,2,"failed",91);
        assertEquals(original,first.toString());assertNotEquals(first.getString("deliveryId"),second.getString("deliveryId"));
        assertEquals("实际已看到天气，待主人核对",first.getString("content"));assertEquals("继续后失败",second.getString("content"));
        assertFalse(first.getJSONObject("evidence").has("screenText"));assertFalse(first.getJSONObject("evidence").has("imageBase64"));
        assertNotEquals(first.getJSONObject("evidence").getString("evidenceDigest"),second.getJSONObject("evidence").getString("evidenceDigest"));
        assertFalse(first.getBoolean("resultVerified"));assertFalse(first.getBoolean("executionAuthorized"));
    }
    @Test public void originalReviewSurvivesAnExplicitPhoneModeContinuation()throws Exception {
        JSONObject review=review(),task=ended().put("executionMode","main_screen").put("runId",NEXT).put("coordinatorEpoch",2);
        ConversationPhoneHandoff.requireSameReview(task,review);
        JSONObject result=ConversationPhoneHandoff.result(task,NEXT,2,"needs_confirmation",91);
        assertEquals(CONVERSATION,result.getString("conversationId"));assertEquals(NEXT,result.getString("runId"));
        assertEquals("background",ConversationPhoneHandoff.association(task).getString("executionMode"));
        JSONObject changed=new JSONObject(task.toString());changed.getJSONObject("conversationPhoneHandoff").put("executionMode","main_screen");
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.association(changed));
    }
    @Test public void summaryLinkSurvivesLongChineseGoalWithoutCopyingTheReviewOrGrantingARun()throws Exception {
        String goal="查看".repeat(2000);JSONObject task=task(ConversationPhoneHandoff.review(TASK,source(),goal,"background",facts())).put("status","awaiting_user");
        assertTrue(task.getJSONObject("conversationPhoneHandoff").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192);
        String original=task.toString();JSONObject header=TaskAuditStore.header(task),link=header.getJSONObject("conversationPhoneLink");
        assertEquals(2,link.length());assertEquals(TASK,link.getString("taskId"));assertEquals(CONVERSATION,link.getString("conversationId"));
        assertFalse(header.has("conversationPhoneHandoff"));assertFalse(header.has("conversationId"));assertFalse(header.has("runId"));
        assertEquals(0,header.getInt("coordinatorEpoch"));assertEquals("awaiting_user",header.getString("status"));
        assertTrue(header.getString("goal").length()<goal.length());assertEquals(original,task.toString());
    }
    @Test public void summaryLinkUsesOriginalConversationAndPreservesTheCurrentTaskState()throws Exception {
        JSONObject task=ended().put("status","failed").put("executionMode","main_screen")
                .put("conversationPhoneLink",new JSONObject().put("taskId",NEXT).put("conversationId","other-conversation"));
        JSONObject header=TaskAuditStore.header(task);
        assertEquals(TASK,header.getJSONObject("conversationPhoneLink").getString("taskId"));
        assertEquals(CONVERSATION,header.getJSONObject("conversationPhoneLink").getString("conversationId"));
        assertEquals("failed",header.getString("status"));assertEquals("main_screen",header.getString("executionMode"));assertEquals(RUN,header.getString("runId"));
    }
    @Test public void invalidAssociationOmitsOnlyTheSummaryLink()throws Exception {
        JSONObject[] invalid={task(review()).put("goal","changed"),task(review()).put("actorId","member"),
                task(review()).put("spaceId","shared"),task(review()).put("origin",new JSONObject()),task(review()).put("parentTaskId",NEXT),task(review())};
        invalid[invalid.length-1].getJSONObject("conversationPhoneHandoff").put("conversationId","other-conversation");
        for(JSONObject task:invalid){String original=task.toString();JSONObject header=TaskAuditStore.header(task);
            assertFalse(header.has("conversationPhoneLink"));assertEquals(TASK,header.getString("id"));assertEquals(task.getString("goal"),header.getString("goal"));
            assertEquals("preparing",header.getString("status"));assertEquals(original,task.toString());
        }
    }
    @Test public void missingOrSwappedIdentityCannotBeRecoveredFromAnUnverifiedSummaryHint()throws Exception {
        for(String missing:new String[]{"requestId","taskId","conversationId","reviewDigest"}){
            JSONObject task=task(review());task.getJSONObject("conversationPhoneHandoff").remove(missing);
            task.put("conversationPhoneLink",new JSONObject().put("taskId",TASK).put("conversationId",CONVERSATION));
            assertFalse(TaskAuditStore.header(task).has("conversationPhoneLink"));
        }
        JSONObject task=task(review());task.getJSONObject("conversationPhoneHandoff").put("taskId",NEXT);assertFalse(TaskAuditStore.header(task).has("conversationPhoneLink"));
        task=task(review());task.remove("actorId");assertFalse(TaskAuditStore.header(task).has("conversationPhoneLink"));
        task=task(review());task.remove("id");assertFalse(TaskAuditStore.header(task).has("conversationPhoneLink"));
        task=task(review());task.remove("conversationPhoneHandoff");task.put("conversationPhoneLink",new JSONObject().put("taskId",TASK).put("conversationId",CONVERSATION));
        assertFalse(TaskAuditStore.header(task).has("conversationPhoneLink"));
    }
    @Test public void summaryLinkRequiresBoundedConversationIdentityMatchingItsOriginalSource()throws Exception {
        for(String conversation:new String[]{"other-conversation","", "会".repeat(241)}){
            JSONObject review=review();review.put("conversationId",conversation);review.remove("reviewDigest");review.put("reviewDigest",ConversationPhoneHandoff.digest(review));
            assertFalse(TaskAuditStore.header(task(review)).has("conversationPhoneLink"));
        }
    }
    @Test public void activeRunCannotBecomeACompletedDelivery()throws Exception {
        for(String state:new String[]{"running","pausing","preparing","queued","stopping"}){
            assertFalse(ConversationPhoneHandoff.ended(state));assertThrows(SecurityException.class,()->ConversationPhoneHandoff.result(ended(),RUN,1,state,81));
        }
        for(String state:new String[]{"completed","needs_confirmation","awaiting_user","failed","cancelled","interrupted","reconciling","paused"})assertTrue(ConversationPhoneHandoff.ended(state));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.result(ended(),NEXT,1,"failed",81));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.result(ended(),RUN,2,"failed",81));
    }
    @Test public void emptyAndLongSummariesRemainTruthfulBoundedDeliveries()throws Exception {
        JSONObject task=ended().put("summary","");JSONObject empty=ConversationPhoneHandoff.result(task,RUN,1,"failed",81);
        assertTrue(empty.getString("content").contains("未提供文字总结"));assertEquals("failed",empty.getString("status"));assertFalse(empty.getBoolean("resultVerified"));
        assertEquals(0,empty.getInt("summaryChars"));ConversationPhoneHandoff.requireOriginalSummary(empty,"");
        String longSummary="🙂".repeat(13000);task.put("summary",longSummary);JSONObject longResult=ConversationPhoneHandoff.result(task,RUN,1,"needs_confirmation",81);
        String content=longResult.getString("content");assertTrue(content.length()<=24000);assertTrue(java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(content));
        assertTrue(content.contains("完整结果请查看原手机任务"));assertTrue(longResult.getBoolean("summaryTruncated"));assertEquals(longSummary.length(),longResult.getInt("summaryChars"));
        ConversationPhoneHandoff.requireOriginalSummary(longResult,longSummary);assertThrows(SecurityException.class,()->ConversationPhoneHandoff.requireOriginalSummary(longResult,longSummary+"changed"));
    }
    @Test public void fixedResultDigestRejectsRewrittenBodyStatusOrEvidence()throws Exception {
        JSONObject result=ConversationPhoneHandoff.result(ended(),RUN,1,"needs_confirmation",81);
        ConversationPhoneHandoff.requireResult(result,TASK,RUN,1);
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.requireResult(new JSONObject(result.toString()).put("content","invented success"),TASK,RUN,1));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.requireResult(new JSONObject(result.toString()).put("status","completed"),TASK,RUN,1));
        JSONObject changed=new JSONObject(result.toString());changed.getJSONObject("evidence").put("evidenceDigest","0".repeat(64));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.requireResult(changed,TASK,RUN,1));
        assertThrows(SecurityException.class,()->ConversationPhoneHandoff.requireResult(result,TASK,NEXT,1));
    }
    @Test public void canonicalFingerprintAndPendingCursorStayStable()throws Exception {
        assertEquals(ConversationPhoneHandoff.digest(new JSONObject().put("a",1).put("b",2)),ConversationPhoneHandoff.digest(new JSONObject().put("b",2).put("a",1)));
        JSONObject result=ConversationPhoneHandoff.result(ended(),RUN,1,"interrupted",81);String key=ConversationPhoneHandoff.pendingKey(result);
        ConversationPhoneHandoff.cursor(key);assertTrue(key.contains("00000000000000000081"));
        assertEquals("phone_handoff:"+TASK+":"+RUN+":1",result.getString("deliveryId"));
        assertThrows(IllegalArgumentException.class,()->ConversationPhoneHandoff.cursor(key+"\n"));
    }
    @Test public void sameMillisecondResultsKeepTheOriginalTaskEpochOrder()throws Exception {
        JSONObject first=ConversationPhoneHandoff.result(ended().put("runId",NEXT),NEXT,1,"failed",81);
        JSONObject next=ConversationPhoneHandoff.result(ended().put("coordinatorEpoch",2),RUN,2,"failed",81);
        String earlier=ConversationPhoneHandoff.pendingKey(first),later=ConversationPhoneHandoff.pendingKey(next);
        ConversationPhoneHandoff.cursor(earlier);ConversationPhoneHandoff.cursor(later);assertTrue(earlier.compareTo(later)<0);
    }
}
