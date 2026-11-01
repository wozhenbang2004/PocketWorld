package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import dev.pocketengine.features.workspace.WorkspaceModule;
import org.json.JSONObject;

/** Exact original workspace byte/CAS/catalog adapter; not an authority or a second file store. */
public final class TeamWorkspaceAccess implements TeamWorkFiles.Workspace {
    private final WorkspaceModule original;
    public TeamWorkspaceAccess(WorkspaceModule original){this.original=java.util.Objects.requireNonNull(original);}
    @Override public JSONObject call(String command,JSONObject input)throws Exception{return original.handle(command,input);}
    @Override public boolean byteFilesSupported(){return true;}
    @Override public VersionedWorkspace.FileSnapshot nativeFile(String project,String path,int version)throws Exception{return original.fileSnapshotForHost(project,path,version);}
    @Override public VersionedWorkspace.FileSnapshot writeBytes(VersionedWorkspace.FileSnapshot expected,byte[] bytes)throws Exception{return original.writeBytesForHost(expected,bytes);}
    @Override public VersionedWorkspace.FileSnapshot delete(VersionedWorkspace.FileSnapshot expected)throws Exception{return original.deleteForHost(expected);}
    @Override public WorkspaceArtifacts.ArtifactRef freeze(VersionedWorkspace.FileSnapshot selected,String operation,String task,String run)throws Exception{return original.freezeFileForHost(selected,operation,task,run);}
    @Override public WorkspaceArtifacts.ArtifactRef nativeArtifact(String id,String sha)throws Exception{return original.fileArtifactForHost(id,sha);}
    @Override public byte[] artifactBytes(WorkspaceArtifacts.ArtifactRef selected)throws Exception{return original.fileArtifactBytesForHost(selected);}
    @Override public dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen projectPackage(String id,String sha)throws Exception{return original.projectPackage(id,sha);}
}
