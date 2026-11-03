package dev.pocketengine.phone;

import dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneDiagnosticReportTest {
    private static final String SECRET="private-screen-task https://private.invalid/?token=secret COOKIE Bearer key 123456";
    private JSONObject group(String state)throws Exception{return new JSONObject().put("state",state).put("message",SECRET).put("checks",new JSONArray().put(new JSONObject().put("message",SECRET)));}
    @Test public void oneAllowlistDropsRawSecretsAndCopiesOnlyKnownNativeFacts()throws Exception {
        JSONObject version=new JSONObject().put("commit","a".repeat(40)).put("tree","b".repeat(40)).put("versionCode",3).put("raw",SECRET);
        JSONObject display=new JSONObject().put("helperConnected",true).put("taskOwned",true).put("displayId",2).put("imeIsolation","unknown").put("authorizationBackend","wireless_adb").put("rawError",SECRET).put("adb",new JSONObject().put("phase","pairing_outcome_unknown").put("pairingCode","123456").put("address",SECRET));
        JSONObject readiness=new JSONObject().put("mainObservation",group("ready")).put("backgroundCapture",group("unknown")).put("backgroundSessionId",SECRET);
        PhoneDiagnosticReport.Report report=PhoneDiagnosticReport.create(version,new JSONObject().put("sdkInt",35).put("abi","arm64-v8a").put("model",SECRET),display,readiness);
        JSONObject out=report.snapshot();assertEquals("a".repeat(40),out.getString("sourceCommit"));assertEquals("packaged",out.getString("sourceIdentityStatus"));assertTrue(out.getJSONObject("helper").getBoolean("taskOwned"));assertEquals("pairing_outcome_unknown",out.getJSONObject("helper").getString("bootstrapPhase"));assertEquals("ready",report.readiness().getString("mainObservation"));
        String text=report.text();for(String secret:new String[]{SECRET,"private.invalid","123456","pairingCode","rawError","backgroundSessionId","COOKIE","Bearer"})assertFalse(secret,text.contains(secret));assertFalse(out.getBoolean("deviceVerified"));assertFalse(out.getBoolean("executionAuthorized"));assertFalse(out.getBoolean("snapshotAtomic"));assertTrue(out.getBoolean("requiresOwnerPreview"));assertEquals(0,out.getJSONArray("selfCheck").length());
    }
    @Test public void nullBackgroundNeverInheritsAnUnsampledPositiveCapability()throws Exception {
        JSONObject ready=new JSONObject();for(String key:new String[]{"mainObservation","mainScreenshot","mainExecution","taskNotifications","stopControl","backgroundPreparation","backgroundExecution","backgroundInput","backgroundCapture","backgroundIme"})ready.put(key,group("ready"));
        JSONObject out=PhoneDiagnosticReport.create(null,null,null,ready).snapshot();assertFalse(out.getBoolean("backgroundSampled"));assertEquals("ready",out.getJSONObject("readiness").getString("stopControl"));assertEquals("unknown",out.getJSONObject("readiness").getString("backgroundExecution"));assertTrue(out.getJSONObject("helper").isNull("helperConnected"));assertTrue(out.isNull("sourceCommit"));assertEquals("unknown",out.getString("sourceIdentityStatus"));
    }
    @Test public void malformedTypesEnumsEpochsAndIdentifiersRemainUnknownNotRawInput()throws Exception {
        JSONObject version=new JSONObject().put("commit",SECRET).put("tree","A".repeat(40)).put("versionCode",1.5);
        JSONObject platform=new JSONObject().put("sdkInt",-2).put("abi",SECRET);JSONObject display=new JSONObject().put("helperConnected","true").put("displayId",1.1).put("imeIsolation",SECRET).put("authorizationBackend",SECRET).put("adb",new JSONObject().put("phase",SECRET));
        JSONObject out=PhoneDiagnosticReport.create(version,platform,display,new JSONObject().put("mainObservation",group(SECRET))).snapshot();assertTrue(out.isNull("sourceCommit"));assertTrue(out.isNull("sourceTree"));assertTrue(out.isNull("versionCode"));assertTrue(out.isNull("sdkInt"));assertTrue(out.isNull("abi"));assertTrue(out.getJSONObject("helper").isNull("helperConnected"));assertTrue(out.getJSONObject("helper").isNull("displayId"));assertEquals("unknown",out.getJSONObject("readiness").getString("mainObservation"));assertFalse(out.toString().contains(SECRET));
    }
    @Test public void reportCannotBeMutatedByLaterSourceOrConsumerChanges()throws Exception {
        JSONObject facts=new JSONObject().put("mainObservation",group("ready"));var report=PhoneDiagnosticReport.create(null,null,null,facts);facts.put("mainObservation",group("blocked"));report.snapshot().put("sourceCommit",SECRET);report.readiness().put("mainObservation",SECRET);assertEquals("ready",report.readiness().getString("mainObservation"));assertTrue(report.snapshot().isNull("sourceCommit"));
    }
}
