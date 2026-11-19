package dev.pocketengine.features.teams;

import dev.pocketengine.features.local.project.ProjectSources;
import org.json.JSONObject;

/** The team's reviewed delivery is a whole fixed source project, not a private user's path subset. */
public final class TeamCompletePackage {
    private TeamCompletePackage(){}
    public static boolean whole(JSONObject plan)throws Exception{
        if(!"freeze_package".equals(plan.optString("intent")))return false;
        JSONObject payload=plan.getJSONArray("steps").getJSONObject(0).getJSONObject("payload");
        return "source_project".equals(payload.optString("mode"))&&!payload.optBoolean("includeGeneratedRoots")&&!payload.has("includePaths");
    }
    /** Both inventories come from the original native checkout/package. Do not include the
     * independently chosen entry path or an artifact ID in the files/directories identity. */
    public static String inventory(JSONObject manifest)throws Exception{
        return ProjectSources.digest(new JSONObject().put("files",manifest.getJSONArray("files")).put("directories",manifest.getJSONArray("directories")));
    }
    public static boolean recordedArtifactMatches(JSONObject delivered,JSONObject evidence)throws Exception{
        JSONObject artifact=evidence.getJSONObject("packageArtifact");
        for(String key:new String[]{"taskId","operationId","planDigest","archiveSha256","archiveBytes","manifestIdentity","sourceDigest","projectId","checkoutId"})
            if(!ProjectSources.canonical(delivered.get(key)).equals(ProjectSources.canonical(evidence.get(key))))return false;
        return artifact.getString("id").equals(delivered.getString("artifactId"))
                &&artifact.getString("versionId").equals(delivered.getString("versionId"))
                &&artifact.getInt("versionSequence")==delivered.getInt("versionSequence");
    }
}
