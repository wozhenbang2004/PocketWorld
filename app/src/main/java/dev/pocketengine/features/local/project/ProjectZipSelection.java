package dev.pocketengine.features.local.project;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import org.json.JSONObject;

/** Same new SAF-selected bytes and effective copied tree, never an assertion of the same provider, URI or authority. */
public final class ProjectZipSelection {
    private ProjectZipSelection(){}
    private static boolean compatible(JSONObject saved,JSONObject wanted)throws Exception{
        if(saved==null||!"external_zip".equals(saved.opt("sourceKind"))||!"external_zip".equals(wanted.opt("sourceKind"))||
                !wanted.getString("sourceLabel").equals(saved.opt("sourceLabel"))||!Boolean.TRUE.equals(saved.opt("completeSelection"))||
                !Boolean.FALSE.equals(saved.opt("sourcePermissionPersisted"))||!Boolean.FALSE.equals(saved.opt("executionAuthorityRestored"))||
                !wanted.get("includeGeneratedRoots").equals(saved.opt("includeGeneratedRoots")))return false;
        JSONObject a=saved.getJSONObject("original"),b=wanted.getJSONObject("original");
        if(!b.getString("originalArchiveSha256").equals(a.opt("originalArchiveSha256"))||b.getLong("originalArchiveBytes")!=a.optLong("originalArchiveBytes",-1)||
                !b.getString("singleRootStripped").equals(a.opt("singleRootStripped"))||
                a.has("stripSingleRootRequested")&&!b.get("stripSingleRootRequested").equals(a.opt("stripSingleRootRequested")))return false;
        JSONObject left=new JSONObject(saved.toString()),right=new JSONObject(wanted.toString());
        for(JSONObject e:new JSONObject[]{left,right}){
            e.remove("selectionId");e.remove("manifestIdentity");e.getJSONObject("original").remove("stripSingleRootRequested");
        }
        return ProjectSources.canonical(left).equals(ProjectSources.canonical(right));
    }
    /** Prefilter at most four tiny app-private headers. Never open a host, device or Git archive to look for a match. */
    public static String candidate(File header,String id,JSONObject wanted)throws Exception{
        if(!Files.exists(header.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        JSONObject h=ProjectPrivateRecord.read(header,32768),e=h.optJSONObject("evidence");
        if(!"pocketworld.project-source-cache.v1".equals(h.opt("format"))||!compatible(e,wanted)||
                !id.equals(e.opt("selectionId"))||!h.getString("manifestIdentity").equals(e.opt("manifestIdentity")))return null;
        String sha=h.getString("archiveSha256");return sha.matches("[a-f0-9]{64}")?sha:null;
    }
    private static String layout(JSONObject manifest,String id)throws Exception{
        JSONObject m=new JSONObject(manifest.toString()),p=m.getJSONObject("project"),s=m.getJSONObject("source");
        if(!"source_project".equals(m.opt("mode"))||!id.equals(p.opt("id"))||!id.equals(s.opt("projectId"))||!id.equals(s.opt("checkoutId")))
            throw new SecurityException("选择身份不是本机导入临时包");
        // Only the per-pick staging IDs differ. Retain every effective path, digest, directory, bit and exclusion.
        p.remove("id");s.remove("projectId");s.remove("checkoutId");return ProjectSources.digest(m);
    }
    public static String selectedLayout(ProjectSourceImport.Selection fresh)throws Exception {
        return layout(fresh.frozen.manifest(),fresh.evidence().getString("selectionId"));
    }
    public static boolean same(JSONObject staged,JSONObject wanted,String wantedLayout)throws Exception{
        JSONObject e=staged.optJSONObject("sourceEvidence");
        return staged.optBoolean("complete")&&compatible(e,wanted)&&staged.getString("selectionId").equals(e.opt("selectionId"))&&
            staged.getString("manifestIdentity").equals(e.opt("manifestIdentity"))&&wantedLayout.equals(layout(staged.getJSONObject("manifest"),staged.getString("selectionId")));
    }
}
