package dev.pocketengine.phone.display;

import org.json.JSONArray;
import org.json.JSONObject;

/** Software capacity is separate from each device's live display/focus/IME checks. */
public final class PhoneParallelCapabilities {
    public static final int MAXIMUM_ACTIVE_PHONE_RUNS=4;
    private PhoneParallelCapabilities() { }
    public static JSONObject current()throws Exception {
        return new JSONObject().put("schemaVersion",2).put("supported",true).put("maximumActivePhoneRuns",MAXIMUM_ACTIVE_PHONE_RUNS)
                .put("code","per_run_background_sessions").put("sessionRouting",2).put("minimumSdk",34).put("deviceIsolationVerified",false)
                .put("sameAppUserExclusive",true).put("boundedResourceWait",true).put("deadlockDetection",true)
                .put("originalTaskStoreRequired",true).put("automaticFallback",false)
                .put("requiresLiveIsolationChecks",true).put("unmetPrerequisites",new JSONArray())
                .put("unverifiedOnDevice",new JSONArray(new String[]{"simultaneous_focus_and_ime","cross_display_input_and_capture","per_run_physical_cleanup"}));
    }
    /** Request validation does not grant a run or create a display; the native host owns admission. */
    public static void require(JSONObject request)throws Exception {
        if(request==null)return;
        if(request.has("parallel")&&!(request.get("parallel") instanceof Boolean))throw new IllegalArgumentException("parallel_boolean_required");
        if(request.has("concurrentSessions")){
            Object raw=request.get("concurrentSessions");if(!(raw instanceof Number))throw new IllegalArgumentException("exact_session_count_required");
            int count=new java.math.BigDecimal(raw.toString()).intValueExact();if(count<1||count>MAXIMUM_ACTIVE_PHONE_RUNS)throw new IllegalArgumentException("session_count_out_of_range");
        }
    }
}
