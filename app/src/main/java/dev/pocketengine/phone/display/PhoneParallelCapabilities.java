package dev.pocketengine.phone.display;

import org.json.JSONArray;
import org.json.JSONObject;

/** Negotiation for the existing single phone host. Android flags alone cannot certify parallel isolation. */
public final class PhoneParallelCapabilities {
    private PhoneParallelCapabilities() { }
    public static JSONObject current()throws Exception {
        return new JSONObject().put("schemaVersion",1).put("supported",false).put("maximumActivePhoneRuns",1)
                .put("code","parallel_phone_isolation_unverified").put("deviceIsolationVerified",false)
                .put("sameAppUserExclusive",true).put("boundedResourceWait",true).put("deadlockDetection",true)
                .put("originalTaskStoreRequired",true).put("automaticFallback",false)
                .put("unmetPrerequisites",new JSONArray(new String[]{"canonical_per_run_phone_leases","independent_host_session_routing","device_focus_and_ime_isolation","cross_display_input_and_capture_isolation","confirmed_per_run_cleanup"}));
    }
    /** A caller asking for multiple sessions is refused before connection/create, never silently serialized as success. */
    public static void require(JSONObject request)throws Exception {
        if(request==null)return;
        if(request.has("parallel")&&!(request.get("parallel") instanceof Boolean))throw new IllegalArgumentException("parallel_boolean_required");
        if(request.optBoolean("parallel"))throw new UnsupportedOperationException("parallel_phone_isolation_unverified");
        if(request.has("concurrentSessions")){
            Object raw=request.get("concurrentSessions");if(!(raw instanceof Number))throw new IllegalArgumentException("exact_session_count_required");
            int count=new java.math.BigDecimal(raw.toString()).intValueExact();if(count<1||count>4)throw new IllegalArgumentException("session_count_out_of_range");
            if(count!=1)throw new UnsupportedOperationException("parallel_phone_isolation_unverified");
        }
    }
}
