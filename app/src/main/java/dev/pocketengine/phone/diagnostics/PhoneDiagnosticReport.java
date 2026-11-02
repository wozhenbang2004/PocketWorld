package dev.pocketengine.phone.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;

/** Construct safe diagnostics from an allowlist. Raw errors, addresses and task/screen content are never copied. */
public final class PhoneDiagnosticReport {
    private PhoneDiagnosticReport() { }
    public static JSONObject create(JSONObject version,JSONObject platform,JSONObject display,JSONObject readiness,JSONObject selfCheck)throws Exception {
        JSONObject out=new JSONObject().put("schema","pocketworld.phone.diagnostics.v1").put("generatedAt",System.currentTimeMillis())
                .put("sourceCommit",hex(version.optString("commit"),40)).put("sourceTree",hex(version.optString("tree"),40))
                .put("versionCode",number(version.opt("versionCode"),0,Integer.MAX_VALUE)).put("sdkInt",number(platform.opt("sdkInt"),1,100));
        String abi=platform.optString("abi");out.put("abi",abi.matches("arm64-v8a|armeabi-v7a|x86|x86_64")?abi:JSONObject.NULL);
        JSONObject helper=new JSONObject();for(String key:new String[]{"helperConnected","sessionActive","releasePending","recoveryRequired","taskOwned","supportedAndroid","bootstrapBusy","frameMetadataOnly","inputInjectionAvailable","captureAvailable"})helper.put(key,bool(display.opt(key)));
        String backend=display.optString("authorizationBackend");helper.put("authorizationBackend",backend.matches("wireless_adb|adb|shizuku")?backend:JSONObject.NULL);
        helper.put("displayId",number(display.opt("displayId"),1,100000));helper.put("imeIsolation",state(display.optString("imeIsolation"),"available|unavailable|unknown"));
        JSONObject adb=display.optJSONObject("adb");String phase=adb==null?"":adb.optString("phase");
        helper.put("bootstrapPhase",state(phase,"idle|binding|checking_existing|pairing|paired|connecting|uploading|ready|needs_pairing|service_unavailable|bind_timeout|cancelled|bootstrap_outcome_unknown|pairing_outcome_unknown|stop_unknown|bootstrap_process_exited|identity_forgotten|forget_failed|forget_blocked|forgetting"));out.put("helper",helper);
        JSONObject checks=new JSONObject();for(String key:new String[]{"mainObservation","mainScreenshot","mainExecution","taskNotifications","backgroundPreparation","backgroundExecution","backgroundInput","backgroundCapture","backgroundIme"}){
            JSONObject item=readiness.optJSONObject(key);checks.put(key,item==null?"unknown":state(item.optString("state"),"ready|blocked|unknown"));}out.put("readiness",checks);
        JSONArray results=new JSONArray();JSONArray source=selfCheck==null?null:selfCheck.optJSONArray("checks");if(source!=null)for(int i=0;i<Math.min(source.length(),20);i++){
            JSONObject row=source.optJSONObject(i);if(row==null)continue;String id=row.optString("id");if(!id.matches("[a-z_]{1,40}"))continue;
            results.put(new JSONObject().put("id",id).put("state",state(row.optString("state"),"passed|failed|unknown|blocked|skipped")));}out.put("selfCheck",results);
        return out.put("containsScreenContent",false).put("containsCredentials",false).put("containsTaskText",false).put("requiresOwnerPreview",true).put("deviceVerified",false);
    }
    public static String text(JSONObject report)throws Exception{return report.toString(2);}
    private static Object bool(Object value){return value instanceof Boolean?value:JSONObject.NULL;}
    private static Object number(Object value,long min,long max){if(!(value instanceof Number))return JSONObject.NULL;try{long n=new java.math.BigDecimal(value.toString()).longValueExact();return n>=min&&n<=max?n:JSONObject.NULL;}catch(Exception invalid){return JSONObject.NULL;}}
    private static Object hex(String value,int count){return value.matches("[a-f0-9]{"+count+"}")?value:JSONObject.NULL;}
    private static Object state(String value,String accepted){return value.matches(accepted)?value:JSONObject.NULL;}
}
