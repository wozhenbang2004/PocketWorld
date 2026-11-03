package dev.pocketengine.features.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native snapshot fixtures, not an authority bypass. Stale-review tests retain their captured row. */
public final class PluginOwnerTestReview {
    private PluginOwnerTestReview(){}
    public static JSONObject current(PluginsModule module,String id)throws Exception {
        JSONArray plugins=module.snapshot().getJSONArray("plugins");
        for(int i=0;i<plugins.length();i++)if(id.equals(plugins.getJSONObject(i).getString("id")))return plugins.getJSONObject(i);
        throw new IllegalArgumentException("Missing synthetic plugin");
    }
    public static JSONObject payload(JSONObject displayed,boolean enabled)throws Exception {
        return new JSONObject().put("id",displayed.getString("id")).put("expectedInstallationId",displayed.getString("installationId"))
                .put("expectedStateRevision",displayed.getLong("stateRevision")).put("expectedGrantsDigest",displayed.getString("grantsDigest")).put("enabled",enabled);
    }
    public static JSONObject enableCurrent(PluginsModule module,String id,boolean enabled)throws Exception {return module.handle("enable",payload(current(module,id),enabled));}
    public static JSONObject enableReviewed(PluginsModule module,JSONObject displayed,boolean enabled)throws Exception {
        return module.setEnabledReviewed(displayed.getString("id"),displayed.getString("installationId"),displayed.getLong("stateRevision"),displayed.getString("grantsDigest"),enabled);
    }
}
