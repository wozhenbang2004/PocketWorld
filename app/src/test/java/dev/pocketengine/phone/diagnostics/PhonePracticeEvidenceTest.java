package dev.pocketengine.phone.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Canonical native tree ids and explicit action/readback evidence; this class does not simulate device success. */
public final class PhonePracticeEvidenceTest {
    private static JSONObject j(Object... pairs)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static JSONObject node(String id,String text,String description)throws Exception{return j("id",id,"windowId",9,"text",text,"description",description,"visible",true,"enabled",true,"password",false);}
    private static JSONObject observed(String id,String nonce,int clicks,String text,String row,int tick,long at)throws Exception {
        return j("ok",true,"observationId",id,"packageName","dev.pocketengine","displayId",5,"sessionId","owned-display","observedAtUptimeMs",at,
                "nodes",new JSONArray().put(node("marker",PhonePracticeContract.MARKER+"\nPW_DIAG_SESSION:"+nonce,""))
                        .put(node("button","点击","PW_DIAG_CLICK")).put(node("click-count","PW_DIAG_CLICK_COUNT:"+clicks,"PW_DIAG_CLICK_COUNT"))
                        .put(node("input",text,"PW_DIAG_INPUT")).put(node("list","","PW_DIAG_LIST"))
                        .put(node("row","PW_DIAG_ROW_"+row,"")).put(node("tick","PW_DIAG_TICK:"+tick,"PW_DIAG_TICK")));
    }
    private static PhonePracticeEvidence evidence(){return new PhonePracticeEvidence("dev.pocketengine",5,"original-nonce");}
    private static JSONObject args(String observation,String node)throws Exception{return j("observationId",observation,"nodeId",node);}
    private static String state(PhonePracticeEvidence evidence,String id)throws Exception{JSONArray checks=evidence.result().getJSONArray("checks");for(int i=0;i<checks.length();i++)if(id.equals(checks.getJSONObject(i).getString("id")))return checks.getJSONObject(i).getString("state");throw new AssertionError(id);}
    @Test public void staticPrefilledCountersAndTextAreNotOperationProof()throws Exception {
        PhonePracticeEvidence evidence=evidence();evidence.observation(observed("before","original-nonce",99,PhonePracticeContract.ASCII,"20",40,1000));
        assertEquals("passed",state(evidence,"tree"));for(String id:new String[]{"click","ascii_input","chinese_input","scroll","back","live_updates"})assertEquals(id,"unknown",state(evidence,id));
        JSONObject report=evidence.result();assertEquals(13,report.getInt("applicableChecks"));assertFalse(report.getBoolean("deviceVerified"));assertFalse(report.getBoolean("containsPrivateContent"));assertEquals("partial",report.getString("status"));
    }
    @Test public void originalCanonicalIdsAndFreshChangedReadbackProveTheRequestedActions()throws Exception {
        PhonePracticeEvidence evidence=evidence();evidence.observation(observed("one","original-nonce",0,"","1",0,1000));
        evidence.action("phone_click_node",args("one","button"),j("accepted",true));evidence.observation(observed("two","original-nonce",1,"","1",0,1100));assertEquals("passed",state(evidence,"click"));
        evidence.action("phone_type",args("two","input").put("text",PhonePracticeContract.ASCII),j("accepted",true));
        evidence.observation(observed("two","original-nonce",1,PhonePracticeContract.ASCII,"1",0,1100));assertEquals("unknown",state(evidence,"ascii_input"));
        evidence.observation(observed("three","original-nonce",1,PhonePracticeContract.ASCII,"1",0,1200));assertEquals("passed",state(evidence,"ascii_input"));
        evidence.action("phone_paste_text",args("three","input").put("text",PhonePracticeContract.CHINESE),j("accepted",true));evidence.observation(observed("four","original-nonce",1,PhonePracticeContract.CHINESE,"1",0,1300));assertEquals("passed",state(evidence,"chinese_input"));
        evidence.action("phone_scroll_node",args("four","list"),j("accepted",true));evidence.observation(observed("five","original-nonce",1,PhonePracticeContract.CHINESE,"2",0,1400));assertEquals("passed",state(evidence,"scroll"));
    }
    @Test public void refusedUnknownOrWrongReferenceActionsCannotClaimLaterUserChanges()throws Exception {
        for(Object accepted:new Object[]{false,JSONObject.NULL}){
            PhonePracticeEvidence evidence=evidence();evidence.observation(observed("one","original-nonce",0,"","1",0,1000));
            evidence.action("phone_click_node",args("one","button"),j("accepted",accepted));evidence.action("phone_type",args("one","input").put("text",PhonePracticeContract.ASCII),j("accepted",accepted));
            evidence.action("phone_scroll_node",args("one","list"),j("accepted",accepted));evidence.action("phone_back",j(),j("accepted",accepted));
            evidence.observation(observed("two","original-nonce",1,PhonePracticeContract.ASCII,"2",0,1100));
            for(String id:new String[]{"click","ascii_input","scroll","back"})assertEquals(id,"unknown",state(evidence,id));
        }
        PhonePracticeEvidence stale=evidence();stale.observation(observed("current","original-nonce",0,"","1",0,1000));
        stale.action("phone_type",args("old","input").put("text",PhonePracticeContract.ASCII),j("accepted",true));
        stale.action("phone_click_node",args("current","some-other-button"),j("accepted",true));
        stale.observation(observed("new","original-nonce",1,PhonePracticeContract.ASCII,"1",0,1100));assertEquals("unknown",state(stale,"ascii_input"));assertEquals("unknown",state(stale,"click"));
    }
    @Test public void anotherPracticeNonceBreaksPendingEvidenceEvenWhenTheOriginalPageLaterReturns()throws Exception {
        PhonePracticeEvidence evidence=evidence();JSONObject first=observed("one","original-nonce",0,"","1",0,1000);evidence.observation(first);
        evidence.action("phone_type",args("one","input").put("text",PhonePracticeContract.ASCII),j("accepted",true));
        JSONObject different=observed("other","other-nonce",0,PhonePracticeContract.ASCII,"1",0,1100);assertFalse(evidence.targetCurrent(different));evidence.observation(different);
        evidence.observation(observed("returned","original-nonce",0,PhonePracticeContract.ASCII,"1",0,1200));assertEquals("unknown",state(evidence,"ascii_input"));
        assertFalse(evidence.targetCurrent(new JSONObject(first.toString()).put("displayId",0)));assertFalse(evidence.targetCurrent(new JSONObject(first.toString()).put("packageName","another.app")));
        assertFalse(new PhonePracticeEvidence("dev.pocketengine",5).targetCurrent(first));
    }
    @Test public void laterUnrelatedChangeCannotRepairTheFirstFailedReadback()throws Exception {
        PhonePracticeEvidence evidence=evidence();evidence.observation(observed("one","original-nonce",0,"","1",0,1000));
        evidence.action("phone_type",args("one","input").put("text",PhonePracticeContract.CHINESE),j("accepted",true));
        evidence.observation(observed("two","original-nonce",0,"unchanged","1",0,1100));assertEquals("unknown",state(evidence,"chinese_input"));
        evidence.observation(observed("three","original-nonce",0,PhonePracticeContract.CHINESE,"1",0,1200));assertEquals("unknown",state(evidence,"chinese_input"));
    }
    @Test public void liveUpdatesNeedElapsedNativeTimeAndBackNeedsAnAcceptedOriginalAction()throws Exception {
        PhonePracticeEvidence evidence=evidence();evidence.observation(observed("one","original-nonce",0,"","1",0,1000));
        evidence.observation(observed("two","original-nonce",0,"","1",100,1100));assertEquals("unknown",state(evidence,"live_updates"));
        evidence.observation(observed("three","original-nonce",0,"","1",3,4000));assertEquals("passed",state(evidence,"live_updates"));
        evidence.action("phone_back",j(),j("accepted",true));JSONObject away=j("ok",true,"observationId","away","displayId",5,"packageName","another.app","nodes",new JSONArray());evidence.observation(away);assertEquals("passed",state(evidence,"back"));
        JSONObject report=evidence.result();assertFalse(report.toString().contains("original-nonce"));assertFalse(report.toString().contains("unchanged"));
    }
}
