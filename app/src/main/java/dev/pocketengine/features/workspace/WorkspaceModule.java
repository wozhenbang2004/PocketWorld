package dev.pocketengine.features.workspace;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import org.json.JSONObject;

public final class WorkspaceModule implements EngineModule {
    private final VersionedWorkspace workspace;
    private final WorkspaceArtifacts artifacts;
    private final File filesRoot;
    private File developmentRoot()throws Exception{return new File(filesRoot.getCanonicalFile(),"local-development");}
    private volatile boolean nativeClosed;
    public WorkspaceModule(FeatureContext context){this(context.android.getFilesDir(),new android.os.Handler(android.os.Looper.getMainLooper())::post,context::emit);}
    /** Same real stores; package-local tests control delivery without replacing file IO. */
    WorkspaceModule(File files,java.util.function.Consumer<Runnable> post,java.util.function.BiConsumer<String,JSONObject> events){
        filesRoot=files;java.util.Objects.requireNonNull(post);java.util.Objects.requireNonNull(events);
        workspace=new VersionedWorkspace(new File(files,"workspace"),JsonFiles::write,metadata->{
            String frozen=metadata.toString();
            // EngineApplication.publish enters TaskStore before notifying UI.
            // Post first, so its event sink never runs under the workspace monitor.
            post.accept(()->{try{events.accept("workspace",new JSONObject(frozen).put("event","workspace_changed"));}catch(Exception presentationUnavailable){/* Committed source is still authoritative. */}});
        });
        artifacts=new WorkspaceArtifacts(new File(files,"workspace-artifacts"),workspace);
    }
    @Override public String id(){return "workspace";}
    /** Typed native active-file route; generic feature JSON cannot supply a Frozen object or host path. */
    public JSONObject createProjectPackage(dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen frozen,String ownerActionId)throws Exception{return artifacts.createProjectPackage(frozen,ownerActionId);}
    public JSONObject createFirstNodeWebPackage(dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen frozen,String ownerActionId)throws Exception{
        if(nativeClosed)throw new IllegalStateException("原工作区已关闭");
        return artifacts.createFirstNodeWebPackage(frozen,ownerActionId);
    }
    public dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen projectPackage(String id,String sha)throws Exception{return artifacts.readProjectPackage(id,sha);}
    public JSONObject projectPackageMetadata(String id,String sha)throws Exception{return artifacts.projectPackage(id,sha);}
    public dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen originalTaskPackage(JSONObject source)throws Exception{return artifacts.originalTaskPackage(source);}
    public JSONObject findProjectPackage(String action,String project,String checkout,String manifest)throws Exception{return artifacts.findProjectPackage(action,project,checkout,manifest);}
    public JSONObject removeProjectPackage(String id,String sha)throws Exception{return artifacts.removeProjectPackage(id,sha);}
    public long exportProjectPackage(String id,String sha,java.io.OutputStream destination)throws Exception{return artifacts.exportProjectPackage(id,sha,destination);}
    public JSONObject createImportedProject(String name,String client,String packageSha)throws Exception{return workspace.createImported(name,client,packageSha);}
    /** Read one original source-import registration. Absence is not evidence that a checkout was ever published. */
    public JSONObject sourceProjectRegistrationForOwner(String client,String sha,String reviewedName)throws Exception {
        return sourceProjectRegistrationForOwner(client,sha,reviewedName,false);
    }
    public JSONObject sourceProjectRegistrationForOwner(String client,String sha,String reviewedName,boolean requireNoAuthority)throws Exception {
        if(nativeClosed||client==null||!client.matches("[a-f0-9-]{36}")||sha==null||!sha.matches("[a-f0-9]{64}"))throw new SecurityException("原项目登记身份不可核对");
        String expected=java.util.UUID.nameUUIDFromBytes(("project-package-import:"+client).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        org.json.JSONArray all=workspace.projects();JSONObject match=null;
        for(int i=0;i<all.length();i++){
            JSONObject row=all.getJSONObject(i),src=row.optJSONObject("sourceImport");boolean id=expected.equals(row.optString("id")),op=src!=null&&client.equals(src.optString("clientId"));
            if(!id&&!op)continue;
            if(!id||!op||match!=null||!sha.equals(src.optString("packageSha256"))||
               requireNoAuthority&&!Boolean.FALSE.equals(src.opt("executionAuthorityRestored")))throw new SecurityException("项目目录有冲突的原导入登记，未选中其他工程");
            match=row;
        }
        return new JSONObject().put("registered",match!=null).put("expectedProjectId",expected)
            .put("reviewedNameCurrent",match!=null&&reviewedName.equals(match.optString("name")));
    }
    /** One original draft's published project references only, never file contents or a tool. */
    public org.json.JSONArray retainedDraftCopyReferencesForHost(String task,String draft)throws Exception{
        return retainedDraftCopyReferencesForHost(task,draft,false);
    }
    public org.json.JSONArray retainedDraftCopyReferencesForHost(String task,String draft,boolean chatMetadataOnly)throws Exception{
        if(nativeClosed)throw new IllegalStateException("原工作区已关闭");
        return dev.pocketengine.features.local.project.OwnerDraftCopyPublication.retainedReferences(developmentRoot(),workspace.projects(),task,draft,chatMetadataOnly);
    }
    /** Native callers retain their original TaskStore admission; these observations add no authority. */
    public VersionedWorkspace.FileSnapshot fileSnapshotForHost(String project,String path,int version)throws Exception{return workspace.snapshot(project,path,version);}
    public RemovedProjectFiles.Shelf removedFilesForOwner(String project,java.util.function.BooleanSupplier live)throws Exception{return workspace.removedFilesForOwner(project,live);}
    public JSONObject removedFileHistoryForOwner(RemovedProjectFiles.Shelf shelf,int index,java.util.function.BooleanSupplier live)throws Exception{return workspace.removedFileHistoryForOwner(shelf,index,live);}
    public JSONObject removedFileTextForOwner(RemovedProjectFiles.Shelf shelf,int index,JSONObject selected,java.util.function.BooleanSupplier live)throws Exception{return workspace.removedFileTextForOwner(shelf,index,selected,live);}
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
    public VersionedWorkspace.FileSnapshot commitTeamFileForHost(VersionedWorkspace.FileSnapshot original,byte[] bytes,java.util.UUID revision)throws Exception{return workspace.commitTeamFile(original,bytes,revision);}
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
    @Override public JSONObject snapshot()throws Exception{return new JSONObject().put("projects",dev.pocketengine.features.local.project.OwnerDraftCopyPublication.visibleProjects(developmentRoot(),workspace.projects())).put("artifacts",artifacts.list(null)).put("runtime","files_only").put("localCompilerAvailable",false).put("commands",new org.json.JSONArray("[\"create\",\"list\",\"read\",\"write\",\"history\",\"diff\",\"removeFile\",\"export\",\"createArtifact\",\"listArtifacts\",\"readArtifact\",\"diffArtifact\",\"prepareExport\"]"));}
    private static JSONObject completedReceipt(JSONObject nativeResult)throws Exception {
        JSONObject receipt=new JSONObject(nativeResult.toString());
        if(!receipt.has("ok"))receipt.put("ok",true);
        return receipt;
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        if(command.equals("create"))return completedReceipt(workspace.create(JsonFiles.text(input,"name",80)));
        if(command.equals("listArtifacts"))return new JSONObject().put("ok",true).put("artifacts",artifacts.list(input.optString("projectId")));
        if(command.equals("readArtifact"))return completedReceipt(artifacts.read(input.getString("artifactId")));
        if(command.equals("diffArtifact"))return completedReceipt(artifacts.diff(input.getString("artifactId")));
        if(command.equals("prepareExport"))return artifacts.prepareExport(input);
        String project=JsonFiles.text(input,"projectId",80);
        File local=developmentRoot();dev.pocketengine.features.local.project.OwnerDraftCopyPublication.requireRegisteredCopyPresent(local,project);
        if(command.equals("write")||command.equals("removeFile")||command.equals("createArtifact"))dev.pocketengine.features.local.project.OwnerDraftCopyPublication.requireOpenedForFormalChange(local,project);
        if(command.equals("list"))return new JSONObject().put("ok",true).put("files",workspace.list(project));
        if(command.equals("export"))return workspace.export(project);
        String path=JsonFiles.text(input,"path",240);
        switch(command){
            case "createArtifact":return completedReceipt(artifacts.create(project,path,input.optInt("version",0),input));
            case "read":
                try{return completedReceipt(workspace.read(project,path,input.optInt("version",0)));}
                catch(VersionedWorkspace.FileUnavailable unavailable){return new JSONObject().put("ok",false).put("accepted",false).put("status","failed").put("code",unavailable.code).put("message",unavailable.getMessage()).put("projectId",project).put("path",path).put("contentAvailable",false).put("authorityAdded",false);}
            case "write":if(!(input.opt("content") instanceof String))throw new IllegalArgumentException("content需为文字");return completedReceipt(workspace.write(project,path,input.getString("content"),input.optInt("expectedVersion",0),false));
            case "removeFile":return completedReceipt(workspace.write(project,path,"",input.getInt("expectedVersion"),true));
            case "history":return new JSONObject().put("ok",true).put("history",workspace.history(project,path));
            case "diff":return completedReceipt(workspace.diff(project,path,input.getInt("fromVersion")));
            default:throw new IllegalArgumentException("不支持的工作区操作");
        }
    }
}
