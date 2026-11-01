package dev.pocketengine.features.workspace;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import org.json.JSONObject;

public final class WorkspaceModule implements EngineModule {
    private final VersionedWorkspace workspace;
    private final WorkspaceArtifacts artifacts;
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
    /** Native source observations over the original version store. No new model route or grant. */
    public VersionedWorkspace.FileSnapshot fileSnapshotForHost(String project,String path,int version)throws Exception{return workspace.snapshot(project,path,version);}
    public VersionedWorkspace.HeadObservation refreshFileHeadForHost(String project,String path)throws Exception{return workspace.refreshFileHeadForHost(project,path);}
    /** Body-free no-lock/no-IO currentness; UNKNOWN until explicit native refresh. */
    public VersionedWorkspace.HeadObservation currentFileHeadForHost(String project,String path){return workspace.currentFileHeadForHost(project,path);}
    @Override public void close(){workspace.closeNativeHeadsForHost();}
    @Override public JSONObject snapshot()throws Exception{return new JSONObject().put("projects",workspace.projects()).put("artifacts",artifacts.list(null)).put("runtime","files_only").put("localCompilerAvailable",false).put("commands",new org.json.JSONArray("[\"create\",\"list\",\"read\",\"write\",\"history\",\"diff\",\"removeFile\",\"export\",\"createArtifact\",\"listArtifacts\",\"readArtifact\",\"diffArtifact\",\"prepareExport\"]"));}
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        if(command.equals("create"))return workspace.create(JsonFiles.text(input,"name",80));
        if(command.equals("listArtifacts"))return new JSONObject().put("artifacts",artifacts.list(input.optString("projectId")));
        if(command.equals("readArtifact"))return artifacts.read(input.getString("artifactId"));
        if(command.equals("diffArtifact"))return artifacts.diff(input.getString("artifactId"));
        if(command.equals("prepareExport"))return artifacts.prepareExport(input);
        String project=JsonFiles.text(input,"projectId",80);
        if(command.equals("list"))return new JSONObject().put("files",workspace.list(project));
        if(command.equals("export"))return workspace.export(project);
        String path=JsonFiles.text(input,"path",240);
        switch(command){
            case "createArtifact":return artifacts.create(project,path,input.optInt("version",0),input);
            case "read":return workspace.read(project,path,input.optInt("version",0));
            case "write":if(!(input.opt("content") instanceof String))throw new IllegalArgumentException("content需为文字");return workspace.write(project,path,input.getString("content"),input.optInt("expectedVersion",0),false);
            case "removeFile":return workspace.write(project,path,"",input.getInt("expectedVersion"),true);
            case "history":return new JSONObject().put("history",workspace.history(project,path));
            case "diff":return workspace.diff(project,path,input.getInt("fromVersion"));
            default:throw new IllegalArgumentException("不支持的工作区操作");
        }
    }
}
