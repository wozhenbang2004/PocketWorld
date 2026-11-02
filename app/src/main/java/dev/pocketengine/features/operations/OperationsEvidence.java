package dev.pocketengine.features.operations;

import java.io.InputStream;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded native facts for the existing owner maintenance page, without task bodies. */
public final class OperationsEvidence {
    private static final List<String> PRACTICE=List.of("helper","display","tree","frame","click","ascii_input","chinese_input","scroll","back","live_updates","main_screen_isolation","ime_isolation");
    private OperationsEvidence(){}
    public static JSONObject buildIdentity(InputStream nativeAsset)throws Exception {
        JSONObject result=new JSONObject().put("sourceCommit","unavailable").put("sourceTree","unavailable").put("sourceDirty",true);
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[1024];int read;while((read=nativeAsset.read(chunk))!=-1){if(bytes.size()+read>4096)throw new SecurityException("build_identity_too_large");bytes.write(chunk,0,read);}
        JSONObject exact=new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));if(!Boolean.FALSE.equals(exact.opt("dirty")))return result;
        String commit=exact.optString("commit"),tree=exact.optString("tree");if(commit.matches("[0-9a-f]{40}")&&tree.matches("[0-9a-f]{40}"))result.put("sourceCommit",commit).put("sourceTree",tree).put("sourceDirty",false);
        return result;
    }
    /** Read-only result from the original phone run. Historical checks never prove
     * a current display/permission, and an absent observation is always unknown. */
    public static JSONObject phonePractice(JSONObject readiness,JSONObject nativeResult)throws Exception {
        JSONObject result=new JSONObject().put("status","partial").put("connected",readiness!=null&&readiness.optBoolean("backgroundConnected"))
            .put("phoneActionsVerified",false).put("reason","run_original_phone_practice_task_for_action_evidence").put("deviceVerified",false).put("containsPrivateContent",false);
        String scope=nativeResult==null?"":nativeResult.optString("resultScope");boolean trusted=nativeResult!=null&&nativeResult.optBoolean("ok")&&"original_run_native_observations".equals(nativeResult.optString("evidenceOrigin"))
            &&List.of("current_original_phone_run","last_completed_original_phone_run").contains(scope);
        JSONArray rows=nativeResult==null?null:nativeResult.optJSONArray("checks"),safe=new JSONArray();
        for(String id:PRACTICE){String state="unknown";if(trusted&&rows!=null&&rows.length()<=12){int matches=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&id.equals(row.optString("id"))){matches++;String actual=row.optString("state");state=List.of("passed","failed","unknown").contains(actual)?actual:"unknown";}}if(matches!=1)state="unknown";}safe.put(new JSONObject().put("id",id).put("state",state));}
        result.put("checks",safe).put("resultScope",trusted?scope:"unavailable").put("currentPracticeVerified",false);
        if(trusted)result.put("reason",scope.equals("last_completed_original_phone_run")?"historical_original_practice_read_only":"current_original_practice_read_only").put("evidenceOrigin","original_run_native_observations");return result;
    }
}
