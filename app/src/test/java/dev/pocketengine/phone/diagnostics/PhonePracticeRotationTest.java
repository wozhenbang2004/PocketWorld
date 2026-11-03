package dev.pocketengine.phone.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Evidence reducer fixtures do not simulate an Android rotation or assert device compatibility. */
public final class PhonePracticeRotationTest {
    private static JSONObject j(Object... pairs)throws Exception { JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out; }
    private static JSONObject node(String id,String text,String description)throws Exception { return j("id",id,"text",text,"description",description,"visible",true,"enabled",true,"editable",false); }
    private static JSONObject observation(String id,int clicks,boolean rotated)throws Exception {
        int width=rotated?1280:720,height=rotated?720:1280;
        return j("ok",true,"observationId",id,"displayId",8,"sessionId","private-display-id","packageName","dev.pocketengine",
                "observedAtUptimeMs",1000L,"screenWidth",width,"screenHeight",height,
                "nodes",new JSONArray().put(node("marker",PhonePracticeContract.MARKER+"\nPW_DIAG_SESSION:private-nonce",""))
                        .put(node("counter","PW_DIAG_CLICK_COUNT:"+clicks,"PW_DIAG_CLICK_COUNT"))
                        .put(node("button","点击","PW_DIAG_CLICK"))
                        .put(node("layout",PhonePracticeContract.layoutText(width,height,rotated?2:1,rotated?1:0,rotated?1:0,rotated?2:0),PhonePracticeContract.LAYOUT))
                        .put(node("rotate","旋转",PhonePracticeContract.ROTATE)));
    }
    private static PhonePracticeEvidence evidence() { return new PhonePracticeEvidence("dev.pocketengine",8,"private-nonce"); }
    private static JSONObject row(PhonePracticeEvidence evidence,String id)throws Exception { JSONArray rows=evidence.result().getJSONArray("checks");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(id))return rows.getJSONObject(i);throw new AssertionError(id); }
    private static JSONObject frame(int width,int height)throws Exception { return j("state","passed","reason","actual_nonuniform_frame","width",width,"height",height); }
    private static void rotate(PhonePracticeEvidence evidence)throws Exception {
        evidence.observation(observation("before",0,false));
        evidence.action("phone_click_node",j("observationId","before","nodeId","rotate"),j("accepted",true));
    }
    private static void clickAfter(PhonePracticeEvidence evidence)throws Exception {
        evidence.action("phone_click_node",j("observationId","rotated","nodeId","button"),j("accepted",true));
        evidence.observation(observation("clicked",1,true));
    }
    @Test public void acceptedRequestAndChangedLayoutNeedBothAnOriginalFrameAndPostRotationInput()throws Exception {
        PhonePracticeEvidence evidence=evidence();rotate(evidence);
        assertEquals("unknown",row(evidence,"rotation").getString("state"));
        evidence.observation(observation("ignored",0,false));
        assertEquals("rotation_requested_not_observed",row(evidence,"rotation").getString("reasonCode"));
        evidence.observation(observation("rotated",0,true));
        evidence.frame(frame(1280,720)); // A legacy bare result has no original tree/frame correlation.
        assertEquals("unknown",row(evidence,"rotation").getString("state"));
        evidence.frame(frame(1280,720),observation("pixels",0,true));
        assertEquals("unknown",row(evidence,"rotation").getString("state"));
        clickAfter(evidence);
        JSONObject result=row(evidence,"rotation");assertEquals("passed",result.getString("state"));
        assertTrue(result.getJSONObject("observed").getBoolean("frameGeometryMatched"));
        assertTrue(result.getJSONObject("observed").getBoolean("postRotationClickReadback"));
        assertEquals(1280,result.getJSONObject("observed").getJSONObject("after").getInt("windowWidth"));
        assertFalse(evidence.result().getBoolean("deviceVerified"));
    }
    @Test public void unchangedPortraitReaderCannotClaimSuccessfulLandscapeRotation()throws Exception {
        PhonePracticeEvidence evidence=evidence();rotate(evidence);evidence.observation(observation("rotated",0,true));clickAfter(evidence);
        evidence.frame(frame(720,1280),observation("pixels",1,true));
        JSONObject result=row(evidence,"rotation");assertEquals("failed",result.getString("state"));
        assertEquals("rotation_frame_geometry_mismatch",result.getString("reasonCode"));
        assertFalse(result.getJSONObject("observed").getBoolean("frameGeometryMatched"));
    }
    @Test public void lostOriginalTargetCannotBeRepairedByLaterUnrelatedReadback()throws Exception {
        PhonePracticeEvidence evidence=evidence();rotate(evidence);evidence.observation(observation("rotated",0,true));
        evidence.observation(observation("other",0,true).put("displayId",9));
        evidence.observation(observation("rotated",0,true));clickAfter(evidence);evidence.frame(frame(1280,720),observation("pixels",1,true));
        assertEquals("unknown",row(evidence,"rotation").getString("state"));
        assertEquals("rotation_target_lost",row(evidence,"rotation").getString("reasonCode"));
        PhonePracticeEvidence nonce=evidence();rotate(nonce);nonce.observation(observation("rotated",0,true));
        JSONObject changed=observation("pixels",0,true);changed.getJSONArray("nodes").getJSONObject(0).put("text",PhonePracticeContract.MARKER+"\nPW_DIAG_SESSION:another-nonce");
        nonce.frame(frame(1280,720),changed);nonce.observation(observation("rotated",0,true));clickAfter(nonce);nonce.frame(frame(1280,720),observation("later",1,true));
        assertEquals("unknown",row(nonce,"rotation").getString("state"));
    }
    @Test public void wrongReferencesUnknownEffectsAndWrongFrameIdsNeverBecomeProof()throws Exception {
        for(Object accepted:new Object[]{false,JSONObject.NULL}) {
            PhonePracticeEvidence evidence=evidence();evidence.observation(observation("before",0,false));
            evidence.action("phone_click_node",j("observationId","before","nodeId","rotate"),j("accepted",accepted));
            evidence.observation(observation("rotated",0,true));clickAfter(evidence);evidence.frame(frame(1280,720),observation("pixels",1,true));
            assertEquals("unknown",row(evidence,"rotation").getString("state"));
        }
        PhonePracticeEvidence stale=evidence();stale.observation(observation("before",0,false));
        stale.action("phone_click_node",j("observationId","older","nodeId","rotate"),j("accepted",true));
        stale.observation(observation("rotated",0,true));clickAfter(stale);stale.frame(frame(1280,720),observation("pixels",1,true));
        assertEquals("unknown",row(stale,"rotation").getString("state"));
        PhonePracticeEvidence repeated=evidence();rotate(repeated);repeated.observation(observation("rotated",0,true));clickAfter(repeated);
        repeated.frame(frame(1280,720),observation("before",1,true));assertEquals("unknown",row(repeated,"rotation").getString("state"));
    }
    @Test public void layoutFactsMustComeFromOneNonEditableMarkedNativeNode()throws Exception {
        JSONObject source=observation("before",0,false);assertNotNull(PhonePracticeContract.layout(source));
        source.getJSONArray("nodes").getJSONObject(3).put("editable",true);assertNull(PhonePracticeContract.layout(source));
        source=observation("before",0,false);source.getJSONArray("nodes").put(new JSONObject(source.getJSONArray("nodes").getJSONObject(3).toString()));assertNull(PhonePracticeContract.layout(source));
        source=observation("before",0,false);source.getJSONArray("nodes").getJSONObject(3).put("text","PW_DIAG_LAYOUT:720,1280,1,0,1.0,2");assertNull(PhonePracticeContract.layout(source));
    }
    @Test public void everyCheckHasVersionedExpectedObservedAndReasonWithoutPrivateContent()throws Exception {
        PhonePracticeEvidence evidence=evidence();rotate(evidence);JSONObject result=evidence.result();JSONArray checks=result.getJSONArray("checks");
        assertEquals(PhonePracticeEvidence.CHECK_IDS.size(),checks.length());
        for(int i=0;i<checks.length();i++) {
            JSONObject check=checks.getJSONObject(i);assertEquals(2,check.getInt("contractVersion"));
            assertFalse(check.getJSONObject("expected").getBoolean("requestAcceptedIsSuccess"));assertFalse(check.getJSONObject("expected").getString("description").isBlank());
            assertNotNull(check.getJSONObject("observed"));assertFalse(check.getString("reasonCode").isBlank());
        }
        assertFalse(result.toString().contains("private-nonce"));assertFalse(result.toString().contains("private-display-id"));
        JSONObject main=row(new PhonePracticeEvidence("dev.pocketengine",0,"nonce"),"rotation");assertFalse(main.getBoolean("applicable"));assertEquals("unknown",main.getString("state"));
    }
}
