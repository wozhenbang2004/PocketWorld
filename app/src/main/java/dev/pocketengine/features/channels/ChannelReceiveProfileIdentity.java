package dev.pocketengine.features.channels;

import org.json.JSONObject;

/** Exact reviewed profile metadata for the original foreground receiver lease. */
final class ChannelReceiveProfileIdentity {
    private ChannelReceiveProfileIdentity(){}

    static JSONObject capture(JSONObject profile)throws Exception {
        return new JSONObject().put("id",profile.getString("id"))
                .put("provider",profile.getString("provider"))
                .put("profileVersion",profile.optLong("updatedAt"))
                .put("transportEpoch",epoch(profile));
    }

    static void check(JSONObject selected,JSONObject current)throws Exception {
        if(!current.optBoolean("enabled")||!selected.getString("provider").equals(current.optString("provider"))
                ||selected.getLong("profileVersion")!=current.optLong("updatedAt")
                ||!epoch(selected).equals(epoch(current)))
            throw new SecurityException("已选连接配置变化，请重新确认后台接收");
    }

    private static String epoch(JSONObject profile){
        Object value=profile.opt("transportEpoch");
        if(!(value instanceof String)||((String)value).isEmpty())
            throw new SecurityException("已选连接认证代次不可核对，请重新确认后台接收");
        return (String)value;
    }
}
