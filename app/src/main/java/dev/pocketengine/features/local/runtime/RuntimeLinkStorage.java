package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.UUID;
import org.json.JSONObject;

/** Stable, generation-owned PRoot backing storage. Activation must never rename these paths. */
final class RuntimeLinkStorage {
    static final String POLICY="proot-link2symlink-stable-v1";
    private final File root;
    RuntimeLinkStorage(File runtimeRoot) throws Exception {root=RuntimeFiles.directory(runtimeRoot,"link-storage");}

    JSONObject allocate(String catalogIdentity) throws Exception {
        if(!catalogIdentity.matches("[a-f0-9]{64}"))throw new SecurityException("runtime_link_catalog_invalid");
        String id=UUID.randomUUID().toString().replace("-","");
        File directory=new File(root,id);Files.createDirectory(directory.toPath());
        JSONObject owner=owner(id,catalogIdentity);
        RuntimeFiles.write(new File(directory,"owner.json"),owner);
        return new JSONObject(owner.toString()).put("hostPath",directory.getCanonicalPath());
    }

    File require(JSONObject binding,String catalogIdentity) throws Exception {
        if(binding==null||!POLICY.equals(binding.optString("policy"))
                ||!catalogIdentity.matches("[a-f0-9]{64}")||!catalogIdentity.equals(binding.optString("catalogIdentity"))
                ||!binding.optString("id").matches("[a-f0-9]{32}"))
            throw new SecurityException("runtime_link_policy_repair_required");
        File directory=new File(root,binding.getString("id"));
        if(!directory.getAbsoluteFile().equals(directory.getCanonicalFile())||!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS)
                ||!directory.getCanonicalPath().equals(binding.optString("hostPath")))
            throw new SecurityException("runtime_link_storage_missing_or_changed");
        File marker=new File(directory,"owner.json");
        if(!Files.isRegularFile(marker.toPath(),LinkOption.NOFOLLOW_LINKS)
                ||!ProjectSources.canonical(owner(binding.getString("id"),catalogIdentity)).equals(ProjectSources.canonical(RuntimeFiles.read(marker))))
            throw new SecurityException("runtime_link_storage_owner_changed");
        return directory;
    }

    private static JSONObject owner(String id,String catalog) throws Exception {
        return new JSONObject().put("policy",POLICY).put("id",id).put("catalogIdentity",catalog);
    }
}
