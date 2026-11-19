package dev.pocketengine.features.devices;

import org.json.JSONArray;
import org.json.JSONObject;

/** Only a directed original Android owner task may choose this instead of an
 * application/screen route. Nothing here issues a grant or contacts a device. */
public final class AndroidTextHandoff {
    private AndroidTextHandoff() {}

    public static boolean plain(JSONObject resource) {
        return resource!=null && resource.length()==1 && "capabilities".equals(resource.opt("screenMode"));
    }
    public static boolean available(JSONObject profile, JSONObject grant, JSONObject resource) throws Exception {
        if (!plain(resource) || !"agent.goal".equals(profile.opt("id")) || !DeviceProtocol.VERSION.equals(profile.opt("version")) ||
                !Boolean.TRUE.equals(profile.opt("installed")) || !Boolean.TRUE.equals(profile.opt("implemented")) || profile.optBoolean("busy") || profile.optBoolean("blocked") ||
                !DeviceProtocol.ANDROID_TEXT_RETURN.equals(profile.opt("androidOriginalTextContract")) ||
                !DeviceExecutionAdmission.ZERO_TOOL_PHONE_CONTRACT.equals(profile.opt("zeroToolPhoneContract")) ||
                grant.optBoolean("allowDelegation") || grant.optJSONObject("forwardingScope")!=null) return false;
        JSONArray methods=grant.getJSONArray("capabilities");
        for(String method:new String[]{"task.submitGoal","task.get","operation.get"})
            if(!PairingCoordinator.contains(methods,method))return false;
        JSONArray tools=grant.getJSONArray("toolNames");
        for(int i=0;i<tools.length();i++)if(!java.util.Set.of("phone_finish","phone_request_user").contains(tools.getString(i)))return false;
        return true;
    }
    public static boolean selected(JSONObject route) {
        return route!=null && DeviceProtocol.ANDROID_TEXT_RETURN.equals(route.opt("textReturnContract"));
    }
    public static boolean bounded(JSONObject route) {
        if(!selected(route)||!"goal".equals(route.opt("kind"))||!plain(route.optJSONObject("resourceBinding"))
                ||!"goal_only".equals(route.opt("contextMode"))||route.optInt("toolBudget",-1)!=0||route.optInt("localToolShare",-1)!=0
                ||route.optInt("modelBudget")<1||route.optInt("modelBudget")>3||route.optInt("localModelShare")!=route.optInt("modelBudget")
                ||route.optJSONObject("toolDeclarations")==null||route.optJSONObject("toolDeclarations").length()!=0)return false;
        for(String name:new String[]{"files","projects","downstreamShares"})if(route.optJSONArray(name)==null||route.optJSONArray(name).length()!=0)return false;
        return true;
    }
}
