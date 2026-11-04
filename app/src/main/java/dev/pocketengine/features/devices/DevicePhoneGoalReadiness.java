package dev.pocketengine.features.devices;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only candidate facts. Creation readiness is not an already-created display or a grant. */
public final class DevicePhoneGoalReadiness {
    private DevicePhoneGoalReadiness(){}
    public static boolean canPrepareBackground(JSONObject readiness,JSONObject display) {
        JSONObject preparation=readiness==null?null:readiness.optJSONObject("backgroundPreparation"),parallel=display==null?null:display.optJSONObject("parallelPhone");
        return preparation!=null&&preparation.optBoolean("ready")&&display!=null&&display.optBoolean("helperConnected")
                &&parallel!=null&&parallel.optBoolean("helperSupportsParallel")&&!display.optBoolean("recoveryRequired");
    }
    /** An unrelated run's creation/release must not revoke this admitted run. Its native physical
     * port and TaskStore still check this run's exact lease, session, user and display. */
    public static boolean admittedBackgroundPrerequisites(JSONObject readiness,JSONObject display) {
        if(display==null||!display.optBoolean("helperConnected"))return false;
        JSONObject preparation=readiness==null?null:readiness.optJSONObject("backgroundPreparation");JSONArray checks=preparation==null?null:preparation.optJSONArray("checks");
        if(checks==null||checks.length()==0)return false;
        for(int i=0;i<checks.length();i++){JSONObject check=checks.optJSONObject(i);if(check==null)return false;if(!"backgroundClean".equals(check.optString("id"))&&!"ready".equals(check.optString("state")))return false;}
        return true;
    }
}
