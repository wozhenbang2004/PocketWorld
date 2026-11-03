package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product-managed, versioned environment data. Project directories and homes are never installation targets. */
public final class RuntimeEnvironment {
    public interface Progress {void changed(String phase,long completed,long total) throws Exception;}
    public interface Usage {void requireIdle(String environmentIdentity) throws Exception;}
    /** Native reviewed package identity; constructing this does not grant model/Task approval. */
    public static final class Package {
        private final JSONObject descriptor;
        public final String identity,id;
        public Package(JSONObject approvedDescriptor) throws Exception {
            descriptor=ProjectSources.copy(approvedDescriptor);id=descriptor.getString("id");
            if(!id.matches("[A-Za-z0-9_-]{1,80}") || !descriptor.getString("archiveSha256").matches("[a-f0-9]{64}")
                    || descriptor.getString("revision").isEmpty() || descriptor.getString("license").isEmpty()
                    || descriptor.getString("abi").isEmpty() || descriptor.getLong("archiveBytes")<1
                    || descriptor.getLong("archiveBytes")>2147483648L)
                throw new IllegalArgumentException("runtime_package_identity_invalid");
            Set<String> seen=new HashSet<>();long total=0;JSONArray files=descriptor.getJSONArray("files");
            for(int i=0;i<files.length();i++) {
                JSONObject file=files.getJSONObject(i);String path=path(file.getString("path"));
                long size=file.getLong("bytes");total=Math.addExact(total,size);
                if(size<0 || !file.getString("sha256").matches("[a-f0-9]{64}") || !seen.add(path))
                    throw new IllegalArgumentException("runtime_package_manifest_invalid");
            }
            if(total>4294967296L || files.length()>100000)throw new IllegalArgumentException("runtime_package_manifest_too_large");
            if(descriptor.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>900000)
                throw new IllegalArgumentException("runtime_package_descriptor_too_large");
            identity=ProjectSources.digest(descriptor); // includes revision, ABI, license, archive AND file identities.
        }
        public JSONObject descriptor() throws Exception {return ProjectSources.copy(descriptor);}
    }
    private final File root;
    private final Usage usage;
    private int leases;
    private boolean ownerMaintenance;
    public RuntimeEnvironment(File privateRuntimeDirectory,Usage usage) throws Exception {
        root=RuntimeFiles.directory(privateRuntimeDirectory,"environments");this.usage=java.util.Objects.requireNonNull(usage);
    }
    public synchronized JSONObject describe(Package spec) throws Exception {
        File location=location(spec);boolean installed=verifiedReceipt(spec,location);
        return new JSONObject().put("packageId",spec.id).put("identity",spec.identity).put("installed",installed)
                .put("status",installed?"installed_unprobed":"not_installed").put("descriptor",spec.descriptor())
                .put("activeLeases",leases).put("automaticDownload",false).put("projectFilesRetained",true);
    }
    /** Source is an explicitly admitted APK asset/SAF/download stream; this method never opens a network URL. */
    public synchronized JSONObject install(Package spec,InputStream admittedSource,ProjectRuntime.LiveGate originalGate,Progress progress) throws Exception {
        try(InputStream owned=java.util.Objects.requireNonNull(admittedSource)) {return installAdmitted(spec,owned,originalGate,progress);}
    }
    private JSONObject installAdmitted(Package spec,InputStream admittedSource,ProjectRuntime.LiveGate originalGate,Progress progress) throws Exception {
        requireNotMaintaining();
        java.util.Objects.requireNonNull(admittedSource);java.util.Objects.requireNonNull(originalGate);java.util.Objects.requireNonNull(progress);
        originalGate.check();File target=location(spec);
        if(verifiedReceipt(spec,target))return describe(spec);
        usage.requireIdle(spec.identity);
        File parent=RuntimeFiles.directory(root,spec.id),staging=RuntimeFiles.directory(parent,"pending-"+UUID.randomUUID());
        File archive=new File(staging,"package.zip"),content=RuntimeFiles.directory(staging,"content");
        try {
            JSONObject descriptor=spec.descriptor();long expected=descriptor.getLong("archiveBytes"),copied=0;
            if(parent.getUsableSpace()<expected+expandedBytes(descriptor)+16*1024*1024)
                throw new java.io.IOException("runtime_environment_insufficient_space");
            progress.changed("receiving",0,expected);
            try(FileOutputStream output=new FileOutputStream(archive)) {
                byte[] bytes=new byte[65536];int count;
                while((count=admittedSource.read(bytes))>=0) {
                    originalGate.check();if(count==0)continue;copied+=count;
                    if(copied>expected)throw new SecurityException("runtime_package_size_mismatch");
                    output.write(bytes,0,count);progress.changed("receiving",copied,expected);
                }
                output.getFD().sync();
            }
            originalGate.check();progress.changed("verifying",copied,expected);
            if(copied!=expected || !RuntimeFiles.sha256(archive).equals(descriptor.getString("archiveSha256")))
                throw new SecurityException("runtime_package_hash_mismatch");
            Map<String,JSONObject> manifest=new HashMap<>();JSONArray files=descriptor.getJSONArray("files");
            for(int i=0;i<files.length();i++)manifest.put(files.getJSONObject(i).getString("path"),files.getJSONObject(i));
            Set<String> extracted=new HashSet<>();long expanded=0,total=expandedBytes(descriptor);
            try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))) {
                ZipEntry entry;byte[] bytes=new byte[65536];
                while((entry=zip.getNextEntry())!=null) {
                    originalGate.check();String relative=entry.getName();
                    if(entry.isDirectory()) {
                        path(relative.substring(0,relative.length()-1));zip.closeEntry();continue;
                    }
                    relative=path(relative);JSONObject expectedFile=manifest.get(relative);
                    if(expectedFile==null || !extracted.add(relative))throw new SecurityException("runtime_package_unreviewed_entry");
                    File file=new File(content,relative);File directory=file.getParentFile();
                    if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("runtime_extract_directory_failed");
                    long size=0;
                    try(FileOutputStream output=new FileOutputStream(file)) {
                        int count;while((count=zip.read(bytes))>=0) {
                            originalGate.check();if(count==0)continue;size+=count;expanded+=count;
                            if(size>expectedFile.getLong("bytes")||expanded>total)throw new SecurityException("runtime_package_expansion_mismatch");
                            output.write(bytes,0,count);progress.changed("extracting",expanded,total);
                        }
                        output.getFD().sync();
                    }
                    if(size!=expectedFile.getLong("bytes")||!RuntimeFiles.sha256(file).equals(expectedFile.getString("sha256")))
                        throw new SecurityException("runtime_package_file_hash_mismatch");
                    zip.closeEntry();
                }
            }
            if(extracted.size()!=manifest.size())throw new SecurityException("runtime_package_files_missing");
            RuntimeFiles.write(new File(content,".environment-receipt.json"),new JSONObject().put("identity",spec.identity).put("descriptor",descriptor));
            originalGate.check();usage.requireIdle(spec.identity);progress.changed("committing",total,total);
            // Never overwrite another installation, user home, project or unknown directory.
            if(target.exists())throw new SecurityException("runtime_existing_installation_requires_repair");
            Files.move(content.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
            if(!verifiedReceipt(spec,target))throw new java.io.IOException("runtime_install_commit_not_observed");
            progress.changed("installed",total,total);return describe(spec);
        } finally {removeOwnedTree(staging);}
    }
    public synchronized AutoCloseable acquire(Package spec) throws Exception {
        requireNotMaintaining();
        if(!verifiedReceipt(spec,location(spec)))throw new IllegalStateException("runtime_environment_not_installed");
        verifyContent(spec,location(spec));
        leases++;return new AutoCloseable(){private boolean released;@Override public void close(){synchronized(RuntimeEnvironment.this){if(!released){released=true;leases--;}}}};
    }
    public synchronized File installedDirectory(Package spec) throws Exception {
        if(!verifiedReceipt(spec,location(spec)))throw new IllegalStateException("runtime_environment_not_installed");
        verifyContent(spec,location(spec));return location(spec);
    }
    public synchronized JSONObject uninstall(Package spec,ProjectRuntime.LiveGate originalGate) throws Exception {
        requireNotMaintaining();
        originalGate.check();usage.requireIdle(spec.identity);
        if(leases!=0)throw new IllegalStateException("runtime_environment_in_use");
        File directory=location(spec);
        if(!directory.exists())return new JSONObject().put("status","absent").put("identity",spec.identity).put("projectFilesRetained",true);
        if(!verifiedReceipt(spec,directory))throw new SecurityException("runtime_unknown_installation_not_deleted");
        originalGate.check();removeOwnedTree(directory);
        if(directory.exists())throw new java.io.IOException("runtime_environment_delete_not_observed");
        return new JSONObject().put("status","uninstalled").put("identity",spec.identity).put("projectFilesRetained",true);
    }
    /** Same original lease owner, not an independent scheduler. No new execution
     * may acquire a generation/package while a reviewed owner cleanup holds it. */
    synchronized AutoCloseable beginOwnerMaintenance()throws Exception {
        requireNotMaintaining();if(leases!=0||!generationLeases.isEmpty())throw new IllegalStateException("runtime_environment_in_use");usage.requireIdle(null);ownerMaintenance=true;
        return new AutoCloseable(){private boolean closed;@Override public void close(){synchronized(RuntimeEnvironment.this){if(!closed){closed=true;ownerMaintenance=false;}}}};
    }
    private void requireNotMaintaining(){if(ownerMaintenance)throw new IllegalStateException("runtime_owner_reviewed_storage_cleanup_in_progress");}
    private final Map<String,Integer> generationLeases=new HashMap<>();
    /** Generation storage is separate from the legacy immutable ZIP package content. */
    File generationStorage() throws Exception {return RuntimeFiles.directory(root,"generations");}
    synchronized AutoCloseable acquireGeneration(String identity) {
        requireNotMaintaining();
        if(!identity.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("runtime_generation_identity_invalid");
        generationLeases.put(identity,generationLeases.getOrDefault(identity,0)+1);
        return new AutoCloseable(){private boolean released;@Override public void close(){synchronized(RuntimeEnvironment.this){if(!released){released=true;int count=generationLeases.get(identity)-1;if(count==0)generationLeases.remove(identity);else generationLeases.put(identity,count);}}}};
    }
    synchronized void requireGenerationIdle(String identity) throws Exception {
        if(identity==null||identity.isEmpty())return;
        requireGenerationUnleased(identity);
        usage.requireIdle(identity);
    }
    /** Local publication fence only; the original persisted-job usage check runs outside the profile monitor. */
    synchronized void requireGenerationUnleased(String identity) {
        requireNotMaintaining();
        if(generationLeases.getOrDefault(identity,0)>0)throw new IllegalStateException("runtime_generation_in_use");
    }
    private File location(Package spec) throws Exception {return new File(RuntimeFiles.directory(root,spec.id),spec.identity);}
    private static long expandedBytes(JSONObject descriptor) throws Exception {
        long total=0;JSONArray files=descriptor.getJSONArray("files");
        for(int i=0;i<files.length();i++)total=Math.addExact(total,files.getJSONObject(i).getLong("bytes"));return total;
    }
    private static boolean verifiedReceipt(Package spec,File directory) throws Exception {
        if(!directory.exists())return false;
        if(Files.isSymbolicLink(directory.toPath())||!directory.isDirectory())throw new SecurityException("runtime_installation_directory_invalid");
        File receipt=new File(directory,".environment-receipt.json");if(!receipt.isFile())return false;
        JSONObject actual=RuntimeFiles.read(receipt);
        if(!spec.identity.equals(actual.optString("identity"))||!spec.identity.equals(ProjectSources.digest(actual.getJSONObject("descriptor")) ))
            throw new SecurityException("runtime_installation_identity_mismatch");
        return true;
    }
    private static void verifyContent(Package spec,File directory) throws Exception {
        JSONArray files=spec.descriptor().getJSONArray("files");
        for(int i=0;i<files.length();i++) {
            JSONObject expected=files.getJSONObject(i);File file=directory;
            for(String component:expected.getString("path").split("/")) {
                file=new File(file,component);if(Files.isSymbolicLink(file.toPath()))throw new SecurityException("runtime_installed_file_symlink");
            }
            if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||file.length()!=expected.getLong("bytes")
                    ||!RuntimeFiles.sha256(file).equals(expected.getString("sha256")))throw new SecurityException("runtime_installed_file_changed");
        }
    }
    private static String path(String value) {
        if(value.isEmpty()||value.startsWith("/")||value.contains("\\")||value.indexOf('\0')>=0||value.equals(".environment-receipt.json"))
            throw new IllegalArgumentException("runtime_package_path_invalid");
        for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("runtime_package_path_invalid");
        return value;
    }
    private static void removeOwnedTree(File root) throws Exception {
        if(!Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(root.toPath())) {Files.delete(root.toPath());return;}
        File[] children=root.listFiles();if(children!=null)for(File child:children)removeOwnedTree(child);
        Files.delete(root.toPath());
    }
}
