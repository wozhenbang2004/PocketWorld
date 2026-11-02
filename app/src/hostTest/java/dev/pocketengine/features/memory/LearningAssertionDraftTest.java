package dev.pocketengine.features.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure contract over real model-draft parsing and the original Book schema. */
public final class LearningAssertionDraftTest {
    private static final String TEXT="开头🙂我现在住B城。我的同事小王喜欢咖啡。";
    private static SourceRef source(String kind){return new SourceRef(kind,"original-message",7,"owner","private");}
    private static JSONObject draft(String kind,String quote)throws Exception {
        return new JSONObject().put("layer","fact").put("text",quote).put("reason","从本次原话提出，尚未由主人审阅").put("applicability","").put("confidence",0.5)
                .put("assertion",new JSONObject().put("kind",kind).put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人").put("predicate","居住城市").put("object",quote).put("quote",quote).put("offset",TEXT.indexOf(quote)).put("eventTime",JSONObject.NULL));
    }
    @Test public void hostBindsExactReferenceSpanAndHashWithoutInventingEventOrAssertionTime()throws Exception {
        JSONObject response=draft("user_said","我现在住B城。"),result=LearningAssertionDraft.checked(response,source("message"),TEXT),assertion=result.getJSONObject("assertion");
        JSONObject span=assertion.getJSONArray("evidenceSpans").getJSONObject(0);
        assertEquals(source("message"),SourceRef.fromJson(span.getJSONObject("sourceRef")));assertEquals(TEXT.indexOf("我现在住B城。"),span.getInt("offset"));assertEquals("我现在住B城。".length(),span.getInt("chars"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("我现在住B城。".getBytes(StandardCharsets.UTF_8))),span.getString("sha256"));
        assertTrue(assertion.isNull("eventTime"));assertTrue(assertion.isNull("assertedAt"));assertTrue(response.getJSONObject("assertion").has("quote"));
    }
    @Test public void inferenceAndImaginationStayTypedRatherThanBecomingLiteralUserFacts()throws Exception {
        for(String kind:new String[]{"inferred","told","imagined"}){JSONObject response=draft(kind,"我现在住B城。");response.put("text","可能住在B城");response.getJSONObject("assertion").put("object","B城");assertEquals(kind,LearningAssertionDraft.checked(response,source("message"),TEXT).getJSONObject("assertion").getString("kind"));}
    }
    @Test public void thirdPersonAndUnknownStableIdsStayOrdinaryCandidatesNotOwnerProfiles()throws Exception {
        for(String subject:new String[]{"person:guessed-xiaowang","unknown"}){JSONObject response=draft("told","我的同事小王喜欢咖啡。");response.getJSONObject("assertion").put("subjectId",subject).put("subjectType","person").put("subjectLabel","小王");JSONObject result=LearningAssertionDraft.checked(response,source("message"),TEXT);assertFalse(result.has("assertion"));assertEquals("我的同事小王喜欢咖啡。",result.getString("text"));}
    }
    @Test public void modelCannotSmuggleNativeReferenceProofOrCurrentWriteTime()throws Exception {
        for(String field:new String[]{"evidenceSpans","sourceRef","assertedAt","version","executionAuthorized","objectId"}){JSONObject response=draft("inferred","我现在住B城。");response.getJSONObject("assertion").put(field,1);assertThrows(SecurityException.class,()->LearningAssertionDraft.checked(response,source("message"),TEXT));}
    }
    @Test public void quoteMustMatchThisSourceAtExactUtf16Offset()throws Exception {
        JSONObject changed=draft("inferred","我现在住B城。");changed.getJSONObject("assertion").put("offset",0);assertThrows(SecurityException.class,()->LearningAssertionDraft.checked(changed,source("message"),TEXT));
        JSONObject absent=draft("inferred","我现在住B城。");absent.getJSONObject("assertion").put("quote","不是原句");assertThrows(SecurityException.class,()->LearningAssertionDraft.checked(absent,source("message"),TEXT));
        JSONObject fractional=draft("inferred","我现在住B城。");fractional.getJSONObject("assertion").put("offset",1.5);assertThrows(SecurityException.class,()->LearningAssertionDraft.checked(fractional,source("message"),TEXT));
    }
    @Test public void utf16RangeCannotCutOneHalfOfAnEmoji()throws Exception {
        JSONObject response=draft("inferred","我现在住B城。");response.getJSONObject("assertion").put("quote",TEXT.substring(3,4)).put("offset",3);assertThrows(SecurityException.class,()->LearningAssertionDraft.checked(response,source("message"),TEXT));
    }
    @Test public void literalUserClaimCannotBeParaphrasedOrAttributedToAFile()throws Exception {
        JSONObject response=draft("user_said","我现在住B城。");response.put("text","用户住在B城");assertThrows(SecurityException.class,()->LearningAssertionDraft.checked(response,source("message"),TEXT));
        assertThrows(SecurityException.class,()->LearningAssertionDraft.checked(draft("user_said","我现在住B城。"),source("task_evidence"),TEXT));
    }
    @Test public void oldTextOnlyAndNoCandidateResponsesRemainCompatible()throws Exception {
        JSONObject plain=draft("inferred","我现在住B城。");plain.remove("assertion");assertFalse(LearningAssertionDraft.checked(plain,source("message"),TEXT).has("assertion"));assertNull(LearningAssertionDraft.checked(null,source("message"),TEXT));
        JSONObject malicious=new JSONObject(plain.toString()).put("approved",true);assertThrows(IllegalArgumentException.class,()->LearningAssertionDraft.checked(malicious,source("message"),TEXT));
    }
}
