package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only the same local V1 files, in the same native destination. No Web-selected paths or model. */
public final class TeamFileRemainder {
    private TeamFileRemainder() {}
    private static JSONObject model(ModelConfig c)throws Exception{
        JSONObject m=new JSONObject().put("configuration",ModelRoutePlan.configurationDigest(c)).put("credentials",c.credentialIdentityDigest())
                .put("cleartext",c.cleartextConsent).put("maxSteps",c.maxSteps);
        if(c.capabilityBinding!=null)m.put("binding",c.capabilityBinding.toJson());
        if(c.usesAccount())m.put("account",c.accountModel.toJson());
        if(c.usesBedrock())m.put("region",c.bedrockRegion);
        return m;
    }
    private static JSONObject plan(ModelRoutePlan plan)throws Exception{
        JSONObject p=plan.toJson();p.remove("snapshotId"); // Ephemeral for each settings read, not an authority.
        JSONArray identities=new JSONArray();for(var candidate:plan.candidates)identities.put(candidate.config()==null?JSONObject.NULL:model(candidate.config()));
        return p.put("identities",identities);
    }
    public static String route(ModelConfig c)throws Exception{
        JSONObject route=model(c),purposes=new JSONObject();if(c.routePlan!=null)route.put("primaryPlan",plan(c.routePlan));
        for(var entry:c.purposePlans.entrySet())purposes.put(entry.getKey(),plan(entry.getValue()));
        return TeamWorkFiles.hash(TeamWorkFiles.canonical(route.put("purposes",purposes)));
    }
    static JSONObject files(TeamWorkFiles.Workspace workspace,JSONObject context)throws Exception{
        if(!workspace.byteFilesSupported())throw new IllegalStateException("原工作区没有可核对的原生文件版本。");
        JSONObject binding=context.getJSONObject("binding"),plan=binding.getJSONObject("plan");
        JSONObject progress=TeamIntegrationFiles.progress(workspace,binding,context.getJSONArray("receipts"));
        if(plan.has("recovery")||plan.has("retained"))throw new SecurityException("只处理没有修订的原文件。");
        JSONArray refs=plan.getJSONArray("files");for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);
            if(!"text_file".equals(workspace.nativeArtifact(ref.getString("artifactId"),ref.getString("sha256")).kind))throw new SecurityException("原成果不是本机文本，不从这里处理。");}
        return TeamRemainingSelection.split(plan,progress);
    }
}
