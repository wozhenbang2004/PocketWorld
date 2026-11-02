package dev.pocketengine.phone;

import dev.pocketengine.phone.diagnostics.PhonePracticeEvidence;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhonePracticeEvidenceTest {
    private JSONObject page(int clicks,int tick,long at,String input,int row)throws Exception {
        return new JSONObject().put("ok",true).put("packageName","dev.pocketengine").put("displayId",7).put("sessionId","PRIVATE_SESSION_783").put("observedAtUptimeMs",at)
                .put("nodes",new JSONArray().put(new JSONObject().put("text","PW_DIAG_TARGET_V1"))
                .put(new JSONObject().put("text","PW_DIAG_CLICK_COUNT:"+clicks)).put(new JSONObject().put("text","PW_DIAG_TICK:"+tick))
                .put(new JSONObject().put("text",input).put("description","PW_DIAG_INPUT"))
                .put(new JSONObject().put("text","PW_DIAG_ROW_"+row).put("visible",true)));
    }
    private String state(PhonePracticeEvidence evidence,String id)throws Exception {JSONArray rows=evidence.result().getJSONArray("checks");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i).getString("state");throw new AssertionError(id);}
    @Test public void acceptedActionAndNativeReadinessNeverPassUnobservedBusinessChecks()throws Exception {PhonePracticeEvidence evidence=new PhonePracticeEvidence("dev.pocketengine",7);evidence.action("phone_click_node",new JSONObject().put("accepted",true));evidence.nativeFacts(new JSONObject().put("helperAuthenticated",true).put("captureAvailable",false));assertEquals("passed",state(evidence,"helper"));for(String id:new String[]{"click","ascii_input","chinese_input","frame","back"})assertEquals("unknown",state(evidence,id));assertEquals("partial",evidence.result().getString("status"));}
    @Test public void wrongPackageOrDisplayCannotProducePracticePasses()throws Exception {PhonePracticeEvidence evidence=new PhonePracticeEvidence("dev.pocketengine",7);JSONObject actual=page(0,0,10,"",1).put("packageName","example.other");evidence.observation(actual);assertEquals("unknown",state(evidence,"tree"));actual.put("packageName","dev.pocketengine").put("displayId",0);evidence.observation(actual);assertEquals("unknown",state(evidence,"tree"));assertFalse(evidence.targetCurrent(actual.put("packageName","example.other")));}
    @Test public void updatesNeedActualDurationAndInputNeedsExactNonPasswordField()throws Exception {PhonePracticeEvidence evidence=new PhonePracticeEvidence("dev.pocketengine",7);evidence.observation(page(0,0,100,"",1));evidence.observation(page(1,4,500,"PocketWorld ASCII 42",2));assertEquals("passed",state(evidence,"click"));assertEquals("passed",state(evidence,"scroll"));assertEquals("passed",state(evidence,"ascii_input"));assertEquals("unknown",state(evidence,"live_updates"));evidence.observation(page(1,10,3100,"口袋世界中文演练42",2));assertEquals("passed",state(evidence,"chinese_input"));assertEquals("passed",state(evidence,"live_updates"));}
    @Test public void backNeedsAcceptedBackAndActualDepartureFromTarget()throws Exception {PhonePracticeEvidence evidence=new PhonePracticeEvidence("dev.pocketengine",7);evidence.observation(page(0,0,0,"",1));evidence.action("phone_back",new JSONObject().put("accepted",true));assertEquals("unknown",state(evidence,"back"));JSONObject next=page(0,0,10,"",1);evidence.observation(next);assertEquals("unknown",state(evidence,"back"));next.put("packageName","example.previous");evidence.observation(next);assertEquals("passed",state(evidence,"back"));}
    @Test public void resultContainsOnlyCheckStatesAndNeverObservedContent()throws Exception {PhonePracticeEvidence evidence=new PhonePracticeEvidence("dev.pocketengine",7);evidence.observation(page(0,0,0,"PRIVATE_OWNER_TEXT_482911",1));evidence.frame(new JSONObject().put("state","passed").put("imageBase64","PRIVATE_PIXELS"));String result=evidence.result().toString();assertFalse(result.contains("PRIVATE_OWNER_TEXT_482911"));assertFalse(result.contains("PRIVATE_PIXELS"));assertFalse(result.contains("PRIVATE_SESSION_783"));assertFalse(evidence.result().getBoolean("deviceVerified"));assertEquals("passed",state(evidence,"frame"));}
}
