package dev.pocketengine.features.workspace;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import org.json.JSONObject;

public final class WorkspaceModule implements EngineModule {
    private final VersionedWorkspace workspace;
    private final WorkspaceArtifacts artifacts;
    private volatile boolean nativeClosed;
    public WorkspaceModule(FeatureContext context){workspace=new VersionedWorkspace(new File(context.android.getFilesDir(),"workspace"));artifacts=new WorkspaceArtifacts(new File(context.android.getFilesDir(),"workspace-artifacts"),workspace);}
    @Override public String id(){return "workspace";}
    /** Typed native active-file route; generic feature JSON cannot supply a Frozen object or host path. */
    public JSONObject createProjectPackage(dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen frozen,String ownerActionId)throws Exception{return artifacts.createProjectPackage(frozen,ownerActionId);}
    public dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen projectPackage(String id,String sha)throws Exception{return artifacts.readProjectPackage(id,sha);}
    public JSONObject projectPackageMetadata(String id,String sha)throws Exception{return artifacts.projectPackage(id,sha);}
    public JSONObject findProjectPackage(String action,String project,String checkout,String manifest)throws Exception{return artifacts.findProjectPackage(action,project,checkout,manifest);}
    public JSONObject removeProjectPackage(String id,String sha)throws Exception{return artifacts.removeProjectPackage(id,sha);}
    public long exportProjectPackage(String id,String sha,java.io.OutputStream destination)throws Exception{return artifacts.exportProjectPackage(id,sha,destination);}
    public JSONObject createImportedProject(String name,String client,String packageSha)throws Exception{return workspace.createImported(name,client,packageSha);}
    /** Native callers retain their original TaskStore admission; these observations add no authority. */
    public VersionedWorkspace.FileSnapshot fileSnapshotForHost(String project,String path,int version)throws Exception{return workspace.snapshot(project,path,version);}
    public VersionedWorkspace.ProjectSnapshot projectSnapshotForHost(String project)throws Exception{return workspace.snapshotProject(project);}
    /** Native catalog identity only; no file bodies, source read permission or restored token. */
    public JSONObject projectIdentityForHost(String project)throws Exception{
        if(nativeClosed)throw new IllegalStateException("原工作区已关闭");org.json.JSONArray all=workspace.projects();
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(project.equals(row.optString("id")))return new JSONObject(row.toString());}
        throw new SecurityException("所选原项目已不存在或不可确认");
    }
    public VersionedWorkspace.HeadObservation refreshFileHeadForHost(String project,String path)throws Exception{return workspace.refreshFileHeadForHost(project,path);}
    /** Strict no-lock/no-IO native source currentness projection; not a model command/grant. */
    public VersionedWorkspace.HeadObservation currentFileHeadForHost(String project,String path){return workspace.currentFileHeadForHost(project,path);}
    @Override public void close(){nativeClosed=true;workspace.closeNativeHeadsForHost();}
    public VersionedWorkspace.FileSnapshot writeBytesForHost(VersionedWorkspace.FileSnapshot expected,byte[] actualBytes)throws Exception{return workspace.writeBytes(expected,actualBytes);}
    public VersionedWorkspace.FileSnapshot deleteForHost(VersionedWorkspace.FileSnapshot expected)throws Exception{return workspace.delete(expected);}
    public WorkspaceArtifacts.ArtifactRef freezeFileForHost(VersionedWorkspace.FileSnapshot original,String nativeOperationId,String taskId,String runId)throws Exception{return artifacts.freezeFile(original,nativeOperationId,taskId,runId);}
    public WorkspaceArtifacts.ArtifactRef fileArtifactForHost(String id,String sha)throws Exception{return artifacts.reference(id,sha);}
    /** Exact selected historical bytes + real current target version; no implicit save or grant. */
    public WorkspaceArtifactContinuation continueArtifactForHost(String id,String sha)throws Exception{return new WorkspaceArtifactContinuation(workspace,artifacts,id,sha);}
    public byte[] fileArtifactBytesForHost(WorkspaceArtifacts.ArtifactRef selected)throws Exception{return artifacts.bytes(selected);}
    public long exportFileArtifactForHost(WorkspaceArtifacts.ArtifactRef selected,java.io.OutputStream destination)throws Exception{return artifacts.exportFile(selected,destination);}
    public JSONObject ownerProjectArchiveForHost(String project,dev.pocketengine.core.RunControl control)throws Exception{return workspace.ownerProjectArchive(project,control);}
    public JSONObject restoreOwnerProjectArchiveForHost(JSONObject archive,String nativeImportId,String sectionDigest,dev.pocketengine.core.RunControl control)throws Exception{return workspace.restoreOwnerProjectArchive(archive,nativeImportId,sectionDigest,control);}
    public JSONObject queryOwnerProjectArchiveForHost(JSONObject archive,String nativeImportId,String sectionDigest)throws Exception{return workspace.queryOwnerProjectArchive(archive,nativeImportId,sectionDigest);}
    public JSONObject ownerFileArchiveForHost(String id,String sha)throws Exception{return artifacts.ownerFileArchive(id,sha);}
    public JSONObject restoreOwnerFileArchiveForHost(JSONObject record,byte[] bytes,String newProject,String nativeImportId,String sectionDigest,dev.pocketengine.core.RunControl original)throws Exception{return artifacts.restoreOwnerFileArchive(record,bytes,newProject,nativeImportId,sectionDigest,original);}
    public JSONObject queryOwnerFileArchiveForHost(JSONObject record,String newProject,String nativeImportId,String sectionDigest)throws Exception{return artifacts.queryOwnerFileArchive(record,newProject,nativeImportId,sectionDigest);}
    @Override public JSONObject snapshot()throws Exception{return new JSONObject().put("projects",workspace.projects()).put("artifacts",artifacts.list(null)).put("runtime","files_only").put("localCompilerAvailable",false).put("commands",new org.json.JSONArray("[\"create\",\"list\",\"read\",\"write\",\"history\",\"diff\",\"removeFile\",\"export\",\"createArtifact\",\"listArtifacts\",\"readArtifact\",\"diffArtifact\",\"prepareExport\"]"));}
    private static JSONObject readReceipt(JSONObject nativeResult)throws Exception {
        JSONObject receipt=new JSONObject(nativeResult.toString());
        if(!receipt.has("ok"))receipt.put("ok",true);
        return receipt;
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        if(command.equals("create"))return workspace.create(JsonFiles.text(input,"name",80));
        if(command.equals("listArtifacts"))return new JSONObject().put("ok",true).put("artifacts",artifacts.list(input.optString("projectId")));
        if(command.equals("readArtifact"))return readReceipt(artifacts.read(input.getString("artifactId")));
        if(command.equals("diffArtifact"))return readReceipt(artifacts.diff(input.getString("artifactId")));
        if(command.equals("prepareExport"))return artifacts.prepareExport(input);
        String project=JsonFiles.text(input,"projectId",80);
        if(command.equals("list"))return new JSONObject().put("ok",true).put("files",workspace.list(project));
        if(command.equals("export"))return workspace.export(project);
        String path=JsonFiles.text(input,"path",240);
        switch(command){
            case "createArtifact":return artifacts.create(project,path,input.optInt("version",0),input);
            case "read":return readReceipt(workspace.read(project,path,input.optInt("version",0)));
            case "write":if(!(input.opt("content") instanceof String))throw new IllegalArgumentException("content需为文字");return workspace.write(project,path,input.getString("content"),input.optInt("expectedVersion",0),false);
            case "removeFile":return workspace.write(project,path,"",input.getInt("expectedVersion"),true);
            case "history":return new JSONObject().put("ok",true).put("history",workspace.history(project,path));
            case "diff":return readReceipt(workspace.diff(project,path,input.getInt("fromVersion")));
            default:throw new IllegalArgumentException("不支持的工作区操作");
        }
    }
}
