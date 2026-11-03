package dev.pocketengine.phone.diagnostics;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One body-free allowlist for native phone preview and the original diagnostic export.
 * No screen/task text, endpoint, pairing code, credentials, raw errors or permission-changing commands. */
public final class PhoneDiagnosticReport {
    private PhoneDiagnosticReport() { }
    private static final Set<String> PHASES=Set.of("idle","binding","checking_existing","pairing","paired","connecting","uploading","ready","needs_pairing","service_unavailable","bind_timeout","cancelled","bootstrap_outcome_unknown","pairing_outcome_unknown","stop_unknown","bootstrap_process_exited","identity_forgotten","forget_failed","forget_blocked","forgetting");
    private static final String[] MAIN={"mainObservation","mainScreenshot","mainExecution","taskNotifications","stopControl"};
    private static final String[] BACKGROUND={"backgroundPreparation","backgroundExecution","backgroundInput","backgroundCapture","backgroundIme"};
    /** Not constructible from model/module JSON. All consumers receive the same already projected facts. */
    public static final class Report {
        private final String safeWire;
        private Report(JSONObject projected){safeWire=projected.toString();}
        public JSONObject snapshot()throws Exception{return new JSONObject(safeWire);}
        public JSONObject readiness()throws Exception{return new JSONObject(snapshot().getJSONObject("readiness").toString());}
        public String text()throws Exception{return snapshot().toString(2);}
    }
    public static Report create(JSONObject version,JSONObject platform,JSONObject display,JSONObject readiness)throws Exception {
        version=object(version);platform=object(platform);readiness=object(readiness);boolean sampled=display!=null;display=object(display);
        Object commit=hex(version.optString("commit")),tree=hex(version.optString("tree"));
        JSONObject out=new JSONObject().put("schema","pocketworld.phone.diagnostics.v1").put("generatedAt",System.currentTimeMillis())
                .put("sourceCommit",commit).put("sourceTree",tree).put("sourceIdentityStatus",commit!=JSONObject.NULL&&tree!=JSONObject.NULL?"packaged":"unknown")
                .put("versionCode",number(version.opt("versionCode"),0,Long.MAX_VALUE)).put("sdkInt",number(platform.opt("sdkInt"),1,100));
        String abi=platform.optString("abi");out.put("abi",Set.of("arm64-v8a","armeabi-v7a","x86","x86_64").contains(abi)?abi:JSONObject.NULL);
        JSONObject helper=new JSONObject();for(String key:new String[]{"helperConnected","sessionActive","releasePending","recoveryRequired","taskOwned","supportedAndroid","bootstrapBusy","frameMetadataOnly","inputInjectionAvailable","captureAvailable"})helper.put(key,sampled?bool(display.opt(key)):JSONObject.NULL);
        String backend=display.optString("authorizationBackend");helper.put("authorizationBackend",sampled&&Set.of("wireless_adb","adb","shizuku").contains(backend)?backend:JSONObject.NULL)
                .put("displayId",sampled?number(display.opt("displayId"),1,100000):JSONObject.NULL)
                .put("imeIsolation",sampled?state(display.optString("imeIsolation"),Set.of("available","unavailable","unknown")):"unknown");
        JSONObject adb=display.optJSONObject("adb");helper.put("bootstrapPhase",sampled?state(adb==null?"":adb.optString("phase"),PHASES):"unknown");out.put("helper",helper);
        JSONObject checks=new JSONObject();for(String key:MAIN)checks.put(key,readiness(readiness,key));for(String key:BACKGROUND)checks.put(key,sampled?readiness(readiness,key):"unknown");
        out.put("readiness",checks).put("backgroundSampled",sampled).put("snapshotAtomic",false)
                .put("selfCheck",new JSONArray()).put("selfCheckStatus","not_run_by_diagnostics")
                .put("containsScreenContent",false).put("containsCredentials",false).put("containsTaskText",false)
                .put("requiresOwnerPreview",true).put("deviceVerified",false).put("executionAuthorized",false);
        return new Report(out);
    }
    private static JSONObject object(JSONObject value){return value==null?new JSONObject():value;}
    private static String readiness(JSONObject facts,String key){JSONObject group=facts.optJSONObject(key);return group==null?"unknown":state(group.optString("state"),Set.of("ready","blocked","unknown"));}
    private static Object bool(Object value){return value instanceof Boolean?value:JSONObject.NULL;}
    private static Object number(Object value,long min,long max){if(!(value instanceof Number))return JSONObject.NULL;try{long n=new java.math.BigDecimal(value.toString()).longValueExact();return n>=min&&n<=max?n:JSONObject.NULL;}catch(Exception invalid){return JSONObject.NULL;}}
    private static Object hex(String value){return value.matches("[a-f0-9]{40}")?value:JSONObject.NULL;}
    private static String state(String value,Set<String> accepted){return accepted.contains(value)?value:"unknown";}
}
