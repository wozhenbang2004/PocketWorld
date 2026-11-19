package dev.pocketengine.features.local.project;

import dev.pocketengine.features.remote.RemoteSavedOriginal;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashSet;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local historical custody only. The receipt is outside the editable/exported files tree,
 * never grants access to a server, and is not a claim about today's phone file bytes. */
public final class ProjectHostOrigin {
    private static final String FORMAT="pocketworld.host-source-origin.v1",FILE="owner-host-source-origin-v1.json";
    private static final int LIMIT=32768;
    private final String project,checkout,digest;
    private final JSONObject original;
    private static SecurityException changed(){return new SecurityException("这份本机副本的原主机来源不一致，未打开其他委托。");}
    private static boolean same(JSONObject a,String key,String value){return value!=null&&value.equals(a.opt(key));}
    private static String stable(String prefix,String client){return UUID.nameUUIDFromBytes((prefix+client).getBytes(StandardCharsets.UTF_8)).toString();}
    private ProjectHostOrigin(JSONObject row,JSONObject evidence)throws Exception {
        JSONObject imported=row.getJSONObject("sourceImport");String client=imported.getString("clientId");
        project=row.getString("projectId");checkout=row.getString("id");digest=ProjectSources.digest(evidence);
        if(!stable("project-package-import:",client).equals(project)||!stable("project-package-checkout:",client).equals(checkout)||
                !same(imported,"sourceKind","host_saved_snapshot")||!same(imported,"mode","new_project")||!same(imported,"sourceEvidenceSha256",digest)||
                !Boolean.FALSE.equals(imported.opt("executionAuthorityRestored"))||!same(evidence,"format","pocketworld.project-source-selection.v1")||
                !same(evidence,"sourceKind","host_saved_snapshot")||!same(evidence,"manifestIdentity",imported.getString("manifestIdentity"))||
                !Boolean.TRUE.equals(evidence.opt("completeSelection"))||!Boolean.FALSE.equals(evidence.opt("sourcePermissionPersisted"))||
                !Boolean.FALSE.equals(evidence.opt("executionAuthorityRestored")))throw changed();
        original=ProjectSources.copy(evidence.getJSONObject("original"));String selected=original.getString("selectionDigest");original.remove("selectionDigest");
        JSONArray claims=original.getJSONArray("files");long size=original.getLong("bytes");
        if(!selected.equals(ProjectSources.digest(original))||!same(evidence,"selectionId",stable("owner-host-source-v1:",selected))||
                !original.getString("originalBindingDigest").matches("[0-9a-f]{64}")||!Boolean.FALSE.equals(original.opt("entireRepository"))||
                !Boolean.FALSE.equals(original.opt("networkUsed"))||claims.length()<1||claims.length()>ProjectHostSource.MAX_COUNT||
                size<0||size>ProjectHostSource.MAX_BYTES||size!=evidence.getLong("expandedBytes")||claims.length()!=evidence.getInt("files"))throw changed();
        ProjectBuildPlan.identity(task());
    }
    public String task()throws Exception{return original.getString("taskId");}
    public String digest(){return digest;}
    private static File record(File directory)throws Exception{
        if(!directory.getAbsoluteFile().equals(directory.getCanonicalFile())||!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS))throw changed();
        return new File(directory,FILE);
    }
    public static void write(File prepared,JSONObject row,JSONObject evidence)throws Exception {
        ProjectHostOrigin origin=new ProjectHostOrigin(row,evidence);
        JSONObject value=new JSONObject().put("format",FORMAT).put("projectId",origin.project).put("checkoutId",origin.checkout)
            .put("evidenceDigest",origin.digest).put("evidence",evidence);
        File file=record(prepared);
        if(value.toString().getBytes(StandardCharsets.UTF_8).length>LIMIT||Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw changed();
        JsonFiles.write(file,value);ProjectHostOrigin saved=read(prepared,row);
        if(saved==null||!origin.digest.equals(saved.digest))throw changed();
    }
    public static ProjectHostOrigin read(File directory,JSONObject row)throws Exception{
        File file=record(directory);if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        JSONObject value=ProjectPrivateRecord.read(file,LIMIT);
        ProjectHostOrigin origin=new ProjectHostOrigin(row,value.getJSONObject("evidence"));
        if(!same(value,"format",FORMAT)||!same(value,"projectId",origin.project)||!same(value,"checkoutId",origin.checkout)||
            !same(value,"evidenceDigest",origin.digest))throw changed();
        return origin;
    }
    /** Bounded metadata-only filter for pre-sidecar checkouts; never read unrelated source archives. */
    public static boolean matchesLegacyHeader(File file,JSONObject source)throws Exception{
        JSONObject header=ProjectPrivateRecord.read(file,LIMIT),e=header.optJSONObject("evidence");
        return e!=null&&same(header,"format","pocketworld.project-source-cache.v1")&&same(header,"archiveSha256",source.getString("packageSha256"))&&
            same(header,"manifestIdentity",source.getString("manifestIdentity"))&&same(source,"sourceEvidenceSha256",ProjectSources.digest(e));
    }
    /** Legacy checkout: no names, paths or timestamp matching. The retained original package must still validate. */
    public static ProjectHostOrigin fromOriginalStage(JSONObject row,JSONObject staged)throws Exception{
        JSONObject imported=row.getJSONObject("sourceImport"),e=staged.optJSONObject("sourceEvidence");
        if(e==null||!Boolean.TRUE.equals(staged.opt("complete"))||!same(staged,"sha256",imported.getString("packageSha256"))||
                !same(staged,"manifestIdentity",imported.getString("manifestIdentity"))||!same(imported,"sourceEvidenceSha256",ProjectSources.digest(e)))return null;
        return new ProjectHostOrigin(row,e);
    }
    public JSONObject compareSavedFiles(File files,java.util.function.BooleanSupplier check)throws Exception{
        JSONArray observed=ProjectHostFileComparison.read(files,original.getJSONArray("files"),ProjectHostSource.MAX_COUNT,ProjectHostSource.MAX_FILE,ProjectHostSource.MAX_BYTES,check);
        return new JSONObject().put("projectId",project).put("checkoutId",checkout).put("evidenceDigest",digest)
            .put("snapshotVersion",original.getString("snapshotVersion")).put("observedAt",System.currentTimeMillis()).put("files",observed);
    }
    public JSONObject summary(JSONObject task)throws Exception{
        JSONArray files=original.getJSONArray("files");
        JSONObject out=new JSONObject().put("projectId",project).put("checkoutId",checkout).put("evidenceDigest",digest).put("localSourceRecorded",true)
            .put("snapshotVersion",original.getString("snapshotVersion")).put("files",files.length()).put("bytes",original.getLong("bytes")).put("taskVerified",false);
        if(task==null||!same(task,"id",task()))return out;
        HashSet<String> ids=new HashSet<>(),paths=new HashSet<>();long sum=0;
        try{for(int i=0;i<files.length();i++){
            JSONObject claim=files.getJSONObject(i),archived=RemoteSavedOriginal.resolve(task,claim);long size=claim.getLong("bytes");
            if(!same(claim,"snapshotId",original.getString("snapshotId"))||!same(claim,"snapshotVersion",original.getString("snapshotVersion"))||
                    !ids.add(claim.getString("attachmentId"))||!paths.add(claim.getString("path"))||size<0||size>ProjectHostSource.MAX_FILE||
                    size!=archived.getLong("size")||!same(original,"originalBindingDigest",ProjectHostSource.bindingDigest(archived)))return out;
            sum+=size;
        }}catch(Exception missing){return out;}
        return sum==original.getLong("bytes")?out.put("taskVerified",true).put("taskId",task()):out;
    }
}
