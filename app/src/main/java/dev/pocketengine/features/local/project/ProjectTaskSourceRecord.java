package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.UUID;
import org.json.JSONObject;

/** Local import provenance lives beside checkout.json, never in the portable project/ or an agent's working tree. */
public final class ProjectTaskSourceRecord {
    private static final String FORMAT="pocketworld.private-task-project-origin.v1";
    private static final int LIMIT=16384;
    private ProjectTaskSourceRecord(){}
    private static SecurityException changed(){return new SecurityException("新项目和原固定源码的登记不一致，未打开其他工作");}
    private static File record(File directory)throws Exception{
        if(!directory.isDirectory()||!directory.getAbsoluteFile().equals(directory.getCanonicalFile()))throw changed();
        return new File(directory,"private-task-source.json");
    }
    private static String stable(String prefix,String client){return UUID.nameUUIDFromBytes((prefix+client).getBytes(StandardCharsets.UTF_8)).toString();}
    /** Metadata already read with the checkout, not a resolution of the original task or archive. */
    static boolean hasPublishedIdentity(JSONObject checkout){
        JSONObject imported=checkout.optJSONObject("sourceImport");
        if(imported==null||!(imported.opt("clientId") instanceof String client))return false;
        if(!client.matches("[a-f0-9-]{36}")||!"new_project".equals(imported.opt("mode"))||
                !ProjectTaskSourceOrigin.KIND.equals(imported.opt("sourceKind"))||!Boolean.FALSE.equals(imported.opt("executionAuthorityRestored"))||
                !stable("project-package-import:",client).equals(checkout.opt("projectId"))||
                !stable("project-package-checkout:",client).equals(checkout.opt("id")))return false;
        for(String field:new String[]{"packageSha256","manifestIdentity","sourceEvidenceSha256"})
            if(!(imported.opt(field) instanceof String digest)||!digest.matches("[a-f0-9]{64}"))return false;
        return true;
    }
    public static JSONObject verified(JSONObject checkout,JSONObject evidence)throws Exception{
        if(!hasPublishedIdentity(checkout))throw changed();
        JSONObject imported=checkout.getJSONObject("sourceImport");
        if(!ProjectSources.digest(evidence).equals(imported.opt("sourceEvidenceSha256")))throw changed();
        ProjectTaskSourceOrigin.requireStage(evidence,evidence.getJSONObject("original"),imported.getString("packageSha256"),imported.getString("manifestIdentity"));
        return evidence;
    }
    public static void write(File directory,JSONObject checkout,JSONObject evidence)throws Exception{
        verified(checkout,evidence);File f=record(directory);
        JSONObject value=new JSONObject().put("format",FORMAT).put("projectId",checkout.getString("projectId")).put("checkoutId",checkout.getString("id")).put("evidence",evidence);
        if(Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS)||value.toString().getBytes(StandardCharsets.UTF_8).length>LIMIT)throw changed();
        JsonFiles.write(f,value);if(read(directory,checkout)==null)throw changed();
    }
    /** Legacy rows only: a header can select one retained archive for full verification, never a task. */
    public static boolean matchingHeader(File file,JSONObject imported)throws Exception{
        JSONObject header=ProjectPrivateRecord.read(file,32768),e=header.optJSONObject("evidence");
        return ProjectTaskSourceOrigin.is(e)&&"pocketworld.project-source-cache.v1".equals(header.opt("format"))&&
            imported.getString("packageSha256").equals(header.opt("archiveSha256"))&&imported.getString("manifestIdentity").equals(header.opt("manifestIdentity"))&&
            imported.getString("sourceEvidenceSha256").equals(ProjectSources.digest(e));
    }
    public static JSONObject read(File directory,JSONObject checkout)throws Exception{
        File f=record(directory);if(!Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        JSONObject v=ProjectPrivateRecord.read(f,LIMIT);
        if(!FORMAT.equals(v.opt("format"))||!checkout.getString("id").equals(v.opt("checkoutId"))||!checkout.getString("projectId").equals(v.opt("projectId")))throw changed();
        return verified(checkout,v.getJSONObject("evidence"));
    }
}
