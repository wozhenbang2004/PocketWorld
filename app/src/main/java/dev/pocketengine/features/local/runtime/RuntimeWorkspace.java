package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import org.json.JSONArray;
import org.json.JSONObject;

/** A native host-supplied capability to the one active checkout; never reconstructed from model JSON. */
public final class RuntimeWorkspace {
    private final File root;
    private final String projectId,checkoutId;
    private File nativeGitPool;private JSONObject nativeGitOwner;private boolean creatingGitPool;
    public RuntimeWorkspace(File activeRoot,String projectId,String checkoutId) throws Exception {
        root=java.util.Objects.requireNonNull(activeRoot).getAbsoluteFile();
        if(!root.equals(root.getCanonicalFile()) || !root.isDirectory() || Files.isSymbolicLink(root.toPath()))
            throw new SecurityException("runtime_active_directory_invalid");
        this.projectId=java.util.Objects.requireNonNull(projectId);
        this.checkoutId=java.util.Objects.requireNonNull(checkoutId);
    }
    /** Only a native owner Git factory supplies this extra private mount; no model path/config API. */
    public static RuntimeWorkspace privateGitForHost(File activeRoot,String projectId,String checkoutId,File pool,JSONObject owner,boolean creation)throws Exception {
        RuntimeWorkspace selected=new RuntimeWorkspace(activeRoot,projectId,checkoutId);selected.nativeGitOwner=dev.pocketengine.features.local.project.ProjectGitIsolation.ownership(owner);selected.nativeGitPool=pool.getAbsoluteFile();selected.creatingGitPool=creation;
        selected.verifyPrivateGit();return selected;
    }
    public File privateGitPoolForHost()throws Exception {verifyPrivateGit();return nativeGitPool;}
    private void verifyPrivateGit()throws Exception {if(nativeGitPool==null)return;dev.pocketengine.features.local.project.ProjectGitIsolation.requirePool(nativeGitPool,nativeGitOwner);if(!projectId.equals(nativeGitOwner.getString("projectId"))||!checkoutId.equals(nativeGitOwner.getString(creatingGitPool?"sourceCheckoutId":"targetCheckoutId")))throw new SecurityException("runtime_private_git_owner_mismatch");if(!creatingGitPool){if(!root.equals(new File(nativeGitPool,"files").getCanonicalFile()))throw new SecurityException("runtime_private_git_checkout_changed");dev.pocketengine.features.local.project.ProjectGitIsolation.requireReadyFiles(nativeGitPool,nativeGitOwner);}}
    public File directory() { return root; }
    public String projectId() { return projectId; }
    public String checkoutId() { return checkoutId; }
    public File resolve(String relative,boolean requireFile) throws Exception {
        if(relative==null || relative.indexOf('\0')>=0 || relative.startsWith("/") || relative.contains("\\"))
            throw new SecurityException("runtime_project_path_invalid");
        File current=root;
        if(!relative.isEmpty() && !relative.equals(".")) for(String part:relative.split("/",-1)) {
            if(part.isEmpty() || part.equals(".") || part.equals("..")) throw new SecurityException("runtime_project_path_invalid");
            current=new File(current,part);
            if(Files.isSymbolicLink(current.toPath())) throw new SecurityException("runtime_project_symlink_rejected");
        }
        if(!current.getCanonicalFile().toPath().startsWith(root.toPath())) throw new SecurityException("runtime_project_path_escape");
        if(requireFile && !Files.isRegularFile(current.toPath(),LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("runtime_project_file_missing");
        return current;
    }
    public void verify(RuntimeInvocation invocation) throws Exception {
        verifyPrivateGit();RuntimeExecutionRef ref=invocation.executionRef();
        if(!projectId.equals(ref.projectId()) || !checkoutId.equals(ref.checkoutId()))
            throw new SecurityException("runtime_active_checkout_mismatch");
        JSONObject source=ProjectSources.describe(invocation.sourceSnapshot());
        JSONArray manifest=source.getJSONArray("files");
        for(int i=0;i<manifest.length();i++) {
            invocation.checkCurrent();JSONObject item=manifest.getJSONObject(i);
            File file=resolve(item.getString("path"),true);
            if(file.length()!=item.getLong("bytes") || !RuntimeFiles.sha256(file).equals(item.getString("sha256")))
                throw new SecurityException("runtime_active_source_changed");
        }
        invocation.checkCurrent();
    }
}
