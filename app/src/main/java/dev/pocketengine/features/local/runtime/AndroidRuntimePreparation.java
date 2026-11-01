package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android's one glibc/PRoot profile. Sources come exclusively from the release-controlled APK catalog. */
final class AndroidRuntimePreparation {
    interface ConnectionOpener {HttpURLConnection open(URL url) throws Exception;}
    private final ConnectionOpener connections;
    private final AndroidRuntimeCatalog catalog;
    private final RuntimeEnvironment environments;
    private final RuntimeLinkStorage links;
    private final File root,cache,staging,generations,home,tmp,proot,loader,dns;
    private JSONObject active;
    private volatile JSONObject progress=new JSONObject();
    private boolean preparing,ownerStorageMaintenance;
    AndroidRuntimePreparation(File privateDirectory,File nativeLibraryDirectory,AndroidRuntimeCatalog catalog,
            RuntimeEnvironment environments,File home,File tmp) throws Exception {
        this(privateDirectory,nativeLibraryDirectory,catalog,environments,home,tmp,url->(HttpURLConnection)url.openConnection());
    }
    AndroidRuntimePreparation(File privateDirectory,File nativeLibraryDirectory,AndroidRuntimeCatalog catalog,
            RuntimeEnvironment environments,File home,File tmp,ConnectionOpener connections) throws Exception {
        this.connections=java.util.Objects.requireNonNull(connections);
        this.catalog=catalog;this.environments=environments;this.home=home;this.tmp=tmp;
        root=RuntimeFiles.directory(privateDirectory,"android-runtime");cache=RuntimeFiles.directory(root,"downloads");
        links=new RuntimeLinkStorage(root);
        staging=RuntimeFiles.directory(root,"staging");generations=environments.generationStorage();
        File library=nativeLibraryDirectory.getCanonicalFile();proot=new File(library,"libproot.so");loader=new File(library,"libloader.so");
        dns=new File(root,"resolv.conf");StringBuilder nameservers=new StringBuilder();JSONArray servers=catalog.descriptor().getJSONArray("dnsServers");
        for(int i=0;i<servers.length();i++)nameservers.append("nameserver ").append(servers.getString(i)).append('\n');
        writeRegular(dns,nameservers.toString());
        String activeProblem="";File pointer=new File(root,"active.json");if(pointer.isFile()) {try{JSONObject saved=RuntimeFiles.read(pointer);active=loadGeneration(saved.getString("generationIdentity"));}catch(Exception invalid){activeProblem="runtime_active_generation_invalid_repair_required";}}
        File status=new File(root,"preparation.json");if(status.isFile()) {progress=RuntimeFiles.read(status);if(!progress.optString("phase").matches("ready|failed|cancelled"))progress.put("phase","interrupted").put("resumableDownload",true).put("previousProcessObservation","query_original_operation_before_retry");}
        if(!activeProblem.isEmpty())progress.put("phase","repair_required").put("error",activeProblem).put("existingGenerationRetained",true);
    }
    synchronized String identity() {return active==null?catalog.identity:active.optString("generationIdentity");}
    String catalogIdentity() {return catalog.identity;}
    synchronized boolean ready() {return active!=null;}
    synchronized JSONObject describe(boolean supervisorAvailable) throws Exception {
        boolean engine=engineVerified();JSONArray tools=new JSONArray();
        if(active!=null) {
            JSONArray sealed=active.getJSONArray("tools");
            for(int i=0;i<sealed.length();i++) {JSONObject tool=ProjectSources.copy(sealed.getJSONObject(i));boolean unchanged=toolUnchanged(rootfs(active),tool);tool.put("hashVerified",false).put("installationHashVerified",true).put("probeVerified",unchanged).put("installed",unchanged);tools.put(tool);}
        }
        boolean installed=active!=null,canExecute=installed&&catalog.identity.equals(active.optString("catalogIdentity"))&&engine&&supervisorAvailable;
        for(int i=0;i<tools.length();i++)canExecute&=tools.getJSONObject(i).getBoolean("probeVerified");
        return new JSONObject().put("identity",identity()).put("catalogIdentity",catalog.identity).put("installed",installed)
                .put("canExecute",canExecute).put("status",canExecute?"ready":installed?"repair_required":"preparation_required")
                .put("preparationAvailable",engine&&supervisorAvailable).put("automaticDownload",true)
                .put("message",canExecute?"这台手机的开发工具已准备好":engine&&supervisorAvailable?"准备开发工具后，可在同一个项目中安装依赖、运行与构建。":"当前安装包的本机启动工具尚不可用，请使用包含正式运行时的安装包。")
                .put("preparation",ProjectSources.copy(progress)).put("preparationEffects",catalog.preparationEffects())
                .put("tools",tools).put("abi",catalog.abi).put("hostLibc","bionic").put("guestLibc","glibc")
                .put("generationIdentity",active==null?JSONObject.NULL:active.getString("generationIdentity"))
                .put("toolProbeRequired",!canExecute).put("integrityPolicy","archive_sha256_at_download;sealed_tool_hashes_at_install;tool_metadata_before_execution;tool_sha256_on_repair")
                .put("projectFilesRetained",true).put("activeGenerationMutatedByUpgrade",false);
    }
    synchronized AutoCloseable beginPreparation() throws Exception {
        if(preparing||ownerStorageMaintenance)throw new IllegalStateException("runtime_preparation_or_owner_cleanup_already_running");preparing=true;
        return ()->{synchronized(AndroidRuntimePreparation.this){preparing=false;}};
    }
    synchronized AutoCloseable beginOwnerStorageMaintenance()throws Exception {
        if(preparing||ownerStorageMaintenance)throw new IllegalStateException("runtime_preparation_or_owner_cleanup_already_running");AutoCloseable lease=environments.beginOwnerMaintenance();ownerStorageMaintenance=true;return ()->{synchronized(AndroidRuntimePreparation.this){try{lease.close();}finally{ownerStorageMaintenance=false;}}};
    }
    synchronized java.util.List<RuntimeStorage.Target> ownerStorageTargets()throws Exception {
        java.util.List<RuntimeStorage.Target> result=new java.util.ArrayList<>();result.add(new RuntimeStorage.Target("runtime:downloads","运行环境下载缓存","可重新按原固定目录下载；清理不卸载已准备工具",cache,true));result.add(new RuntimeStorage.Target("runtime:temporary","运行临时文件","可能含运行生成的临时数据，先确认不再需要；原项目与回执保留",tmp,true));result.add(new RuntimeStorage.Target("runtime:home","开发环境 HOME","包含用户配置、安装缓存及程序写入的数据；不把整个HOME当缓存删除",home,false));
        result.add(new RuntimeStorage.Target("runtime:npm-cache","HOME 中的 npm 内容缓存","只清理 _cacache，不删除.npmrc或其它HOME配置",new File(home,".npm/_cacache"),true));result.add(new RuntimeStorage.Target("runtime:pip-cache","HOME 中的 pip 下载缓存","不删除已创建的项目venv或HOME其它文件",new File(home,".cache/pip"),true));result.add(new RuntimeStorage.Target("runtime:pnpm-cache","HOME 中的 pnpm 内容缓存","之后依赖安装可能需要按原范围联网重新下载",new File(home,".local/share/pnpm/store"),true));
        // Protected aggregates keep unknown/corrupt generation and backing bytes
        // visible. Their totals overlap the per-version rows; never add them.
        result.add(new RuntimeStorage.Target("runtime:generation-store-all","全部运行环境（含未知版本）","完整目录逻辑占用，包含下方各版及未识别/损坏项；与分项重叠，不可整目录清理",generations,false));
        result.add(new RuntimeStorage.Target("runtime:link-store-all","全部PRoot文件后备（含未知项）","包含已列同版后备与未识别文件；没有准确归属的后备保留，不按名字猜测可删",new File(root,"link-storage"),false));
        File[] all=generations.listFiles();if(all==null)throw new java.io.IOException("runtime_generation_storage_unreadable");java.util.Arrays.sort(all,java.util.Comparator.comparingLong(File::lastModified).reversed());
        boolean unresolved=all.length>256;java.util.Set<String> linksInUse=new java.util.HashSet<>();File[] stages=staging.listFiles();if(stages==null)throw new java.io.IOException("runtime_staging_storage_unreadable");
        for(File stage:stages){File owner=new File(stage,"owner.json");try{if(!owner.isFile()||Files.isSymbolicLink(owner.toPath())){unresolved=true;continue;}JSONObject row=RuntimeFiles.read(owner).optJSONObject("linkStorage");if(row==null||!row.optString("id").matches("[a-f0-9]{32}")||!linksInUse.add(row.getString("id")))unresolved=true;}catch(Exception unknown){unresolved=true;}}
        java.util.List<RuntimeStorage.Target> classified=new java.util.ArrayList<>();
        for(int i=0;i<all.length&&i<256;i++){File entry=all[i];String id=entry.getName();if(!id.matches("[a-f0-9]{64}")){unresolved=true;continue;}
            try{JSONObject generation=loadGeneration(id);boolean isActive=active!=null&&id.equals(active.getString("generationIdentity"));JSONObject binding=generation.getJSONObject("linkStorage");File backing=links.require(binding,generation.getString("catalogIdentity"));if(!linksInUse.add(binding.getString("id")))unresolved=true;
                classified.add(new RuntimeStorage.Target("runtime:generation:"+id,(isActive?"当前运行环境 / rootfs":"已保留的旧运行环境 / rootfs"),isActive?"当前工具环境保留；修复/升级通过原准备流程生成新版本":"只有实际退出且无原未决进程时可清理，之后不能回退到此旧版本",entry,!isActive));classified.add(new RuntimeStorage.Target("runtime:links:"+id,(isActive?"当前运行环境文件后备":"旧运行环境文件后备"),"PRoot链接后备必须与其同版rootfs一起审阅；不会跟随链接删除其他位置",backing,!isActive));
            }catch(Exception unknown){unresolved=true;}}
        // An unclassified record may still refer to an otherwise valid backing.
        // Preserve all version pairs until that original ownership is understood.
        for(RuntimeStorage.Target target:classified)result.add(unresolved?new RuntimeStorage.Target(target.id,target.label,target.note+"；另有未识别的版本/安装归属，本轮只计量保留，不清理旧版本或后备",target.file,false):target);
        result.add(new RuntimeStorage.Target("runtime:installation-staging","运行环境安装暂存","中断安装保留为原恢复依据，不在普通缓存清理中删除",staging,false));return result;
    }
    synchronized File ownerStorageObservationTarget(JSONObject original)throws Exception {
        String id=original.getString("id");if(id.equals("runtime:downloads"))return cache;if(id.equals("runtime:npm-cache"))return new File(home,".npm/_cacache");if(id.equals("runtime:pip-cache"))return new File(home,".cache/pip");if(id.equals("runtime:pnpm-cache"))return new File(home,".local/share/pnpm/store");if(id.startsWith("runtime:generation:")&&id.substring(19).matches("[a-f0-9]{64}"))return new File(generations,id.substring(19));
        if(id.startsWith("runtime:links:")&&id.substring(14).matches("[a-f0-9]{64}")&&original.optString("nativeBackingId").matches("[a-f0-9]{32}"))return new File(new File(root,"link-storage"),original.getString("nativeBackingId"));throw new SecurityException("不是原运行环境声明的只读存储定位");
    }
    private boolean engineVerified() throws Exception {
        JSONObject engine=catalog.descriptor().getJSONObject("engine");
        return proot.isFile()&&proot.canExecute()&&loader.isFile()&&loader.canExecute()
                &&RuntimeFiles.sha256(proot).equals(engine.getString("prootSha256"))&&RuntimeFiles.sha256(loader).equals(engine.getString("loaderSha256"));
    }
    JSONObject prepare(ManagedProcessJobs.PreparationContext context) throws Exception {
        context.check();if(!engineVerified())throw new SecurityException("runtime_apk_engine_missing_or_changed");
        synchronized(this) {if(active!=null&&catalog.identity.equals(active.optString("catalogIdentity"))) {try{verifyTools(active,true);return preparedResult(active);}catch(SecurityException changed){/* Repair builds a fresh generation, never mutates or deletes this one. */}}}
        // A new layout never reuses a pre-L2S partially extracted rootfs. Old staging
        // and generations are retained, not silently deleted or modified by repair.
        File candidate=RuntimeFiles.directory(staging,catalog.identity+"-l2s-v1");File marker=new File(candidate,"owner.json");
        if(marker.isFile()&&!catalog.identity.equals(RuntimeFiles.read(marker).optString("catalogIdentity")))throw new SecurityException("runtime_unknown_staging_not_reused");
        if(!marker.isFile()){String[] existing=candidate.list();if(existing==null||existing.length>0)throw new SecurityException("runtime_unknown_staging_not_reused");}
        JSONObject candidateOwner=marker.isFile()?RuntimeFiles.read(marker):new JSONObject().put("catalogIdentity",catalog.identity).put("linkStorage",links.allocate(catalog.identity));
        links.require(candidateOwner.optJSONObject("linkStorage"),catalog.identity);
        RuntimeFiles.write(marker,candidateOwner);
        JSONObject descriptor=catalog.descriptor();
        long required=Math.addExact(descriptor.getJSONObject("rootfs").getLong("unpackedBytes"),descriptor.getJSONObject("node").getLong("unpackedBytes"))+536870912L;
        if(candidate.getUsableSpace()<required)throw new java.io.IOException("runtime_environment_insufficient_space");
        File rootfs=RuntimeFiles.directory(candidate,"rootfs");
        try {
            prepareArchive("rootfs",descriptor.getJSONObject("rootfs"),rootfs,candidate,context);
            File opt=guestDirectory(rootfs,"opt"),node=guestDirectory(opt,"node");
            prepareArchive("node",descriptor.getJSONObject("node"),node,candidate,context);
            context.check();phase(context,"configuring",0,1);
            writeGuest(rootfs,"etc/apt/pocketworld.sources",descriptor.getString("aptSources"));
            writeGuest(rootfs,"usr/sbin/policy-rc.d","#!/bin/sh\nexit 101\n");new File(rootfs,"usr/sbin/policy-rc.d").setExecutable(true,false);
            // Deliberately signed HTTP Ubuntu bootstrap: the base has no CA bundle yet. Never disable apt signature checks.
            List<String> apt=new ArrayList<>(List.of("/usr/bin/apt-get","-o","Dir::Etc::sourcelist=/etc/apt/pocketworld.sources","-o","Dir::Etc::sourceparts=-","-o","Acquire::AllowInsecureRepositories=false","-o","APT::Get::AllowUnauthenticated=false","-o","Acquire::Retries=0","-o","Acquire::http::Timeout=30","-o","Acquire::https::Timeout=30"));
            File bootstrapMarker=new File(candidate,"bootstrap-started.json");
            if(bootstrapMarker.isFile()){phase(context,"recovering_package_configuration",0,1);run(context,rootfs,null,List.of("/usr/bin/dpkg","--configure","--pending"),900000);}
            RuntimeFiles.write(bootstrapMarker,new JSONObject().put("catalogIdentity",catalog.identity));
            phase(context,"updating_signed_package_indexes",0,1);List<String> update=new ArrayList<>(apt);update.add("update");run(context,rootfs,null,update,900000);
            phase(context,"installing_system_tools",0,descriptor.getJSONArray("systemPackages").length());
            List<String> install=new ArrayList<>(apt);install.addAll(List.of("install","--yes","--no-install-recommends"));JSONArray packages=descriptor.getJSONArray("systemPackages");for(int i=0;i<packages.length();i++)install.add(packages.getString(i));
            run(context,rootfs,null,install,1800000);
            phase(context,"probing_tools",0,descriptor.getJSONArray("tools").length());JSONArray facts=new JSONArray(),tools=descriptor.getJSONArray("tools");
            for(int i=0;i<tools.length();i++) {
                context.check();JSONObject tool=ProjectSources.copy(tools.getJSONObject(i));List<String> command=new ArrayList<>();command.add(tool.getString("path"));JSONArray args=tool.getJSONArray("versionArguments");for(int j=0;j<args.length();j++)command.add(args.getString(j));
                run(context,rootfs,null,command,60000);if(context.outputTruncated())throw new java.io.IOException("runtime_tool_probe_output_truncated");
                String version=context.stdout().trim();if(version.isEmpty()||version.length()>8192)throw new java.io.IOException("runtime_tool_probe_version_missing");
                if(tool.getString("name").equals("node")&&!version.matches("v24\\..*"))throw new java.io.IOException("runtime_node24_probe_failed");
                File binary=resolveGuest(rootfs,tool.getString("path"));tool.put("observedVersion",version).put("sha256",RuntimeFiles.sha256(binary)).put("bytes",binary.length()).put("modifiedAt",binary.lastModified()).put("probeVerified",true);
                facts.put(tool);phase(context,"probing_tools",i+1,tools.length());
            }
            phase(context,"probing_child_process_and_venv",0,1);
            // These are executed feature probes, not echoed success markers. Failures retain the real native exit/log.
            run(context,rootfs,null,List.of("/opt/node/bin/node","-e","const a=require('node:assert/strict'),c=require('node:child_process');a.equal(c.spawnSync('/bin/sh',['-c','exit 7']).status,7);a.equal(c.spawnSync(process.execPath,['-e','process.exit(9)']).status,9);"),60000);
            File probe=RuntimeFiles.directory(tmp,"runtime-probe-"+catalog.identity.substring(0,24));
            writeRegular(new File(probe,"package.json"),"{\"name\":\"pocketworld-runtime-probe\",\"version\":\"1.0.0\",\"private\":true,\"scripts\":{\"probe\":\"node -e \\\"if(require('node:child_process').spawnSync('/bin/sh',['-c','exit 7']).status!==7)process.exit(8)\\\"\"}}\n");
            run(context,rootfs,null,List.of("/opt/node/bin/npm","--prefix","/tmp/"+probe.getName(),"run","probe","--offline"),60000);
            run(context,rootfs,null,List.of("/usr/bin/python3","-c","import os,ssl,subprocess,venv; p='/tmp/"+probe.getName()+"/venv'; venv.EnvBuilder(with_pip=True,clear=True).create(p); subprocess.run([p+'/bin/python','-m','pip','--version'],check=True); assert subprocess.run(['/bin/sh','-c','exit 7']).returncode==7; assert os.path.isfile(ssl.get_default_verify_paths().cafile)"),180000);
            phase(context,"probing_hardlinks_before_activation",0,1);
            run(context,rootfs,null,List.of("/usr/bin/python3","-c","import os; p='/var/tmp/pocketworld-link-probe'; os.makedirs(p,exist_ok=True)\nfor n in ('original','alias'):\n f=p+'/'+n\n if os.path.lexists(f): os.unlink(f)\na=p+'/original'; b=p+'/alias'\nwith open(a,'w') as f: f.write('before')\nos.link(a,b)\nwith open(b,'a') as f: f.write('-activation')\nassert open(a).read()=='before-activation'\nassert os.stat(a).st_ino==os.stat(b).st_ino and os.stat(a).st_nlink==2"),60000);
            run(context,rootfs,null,List.of("/usr/bin/dpkg-query","-W","-f=${Package}\\t${Version}\\n"),60000);
            if(context.outputTruncated())throw new java.io.IOException("runtime_package_manifest_truncated");String packageManifest=context.stdout();
            JSONObject seal=new JSONObject().put("schema","pocketworld.runtime-generation.v1").put("catalogIdentity",catalog.identity)
                    .put("catalog",descriptor).put("linkStorage",candidateOwner.getJSONObject("linkStorage")).put("tools",facts).put("installedPackages",packageManifest)
                    .put("capabilities",new JSONArray().put("node24").put("npm_lifecycle").put("child_process").put("python3").put("venv").put("pip").put("git").put("ca_certificates"));
            String generation=ProjectSources.digest(seal);seal.put("generationIdentity",generation);
            RuntimeFiles.write(new File(candidate,"generation.json"),seal);
            context.check();phase(context,"activating",1,1);
            synchronized(this) {
                environments.requireGenerationIdle(active==null?"":active.getString("generationIdentity"));
                File target=new File(generations,generation);
                if(target.exists()) {JSONObject existing=loadGeneration(generation);if(!ProjectSources.canonical(existing).equals(ProjectSources.canonical(seal)))throw new SecurityException("runtime_generation_collision");}
                else Files.move(candidate.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
                // Probe the moved generation before publishing active.json. The backing
                // directory is not under staging, so both hard-link names must still
                // have the same contents/inode, including after one name is unlinked.
                phase(context,"probing_hardlinks_after_activation",0,1);
                run(context,new File(target,"rootfs"),null,List.of("/usr/bin/python3","-c","import os; p='/var/tmp/pocketworld-link-probe'; a=p+'/original'; b=p+'/alias'; assert open(a).read()=='before-activation'; assert os.stat(a).st_ino==os.stat(b).st_ino and os.stat(b).st_nlink==2; os.unlink(a); assert open(b).read()=='before-activation'; os.unlink(b); os.rmdir(p)"),60000);
                loadGeneration(generation);
                RuntimeFiles.write(new File(root,"active.json"),new JSONObject().put("generationIdentity",generation).put("catalogIdentity",catalog.identity));
                active=loadGeneration(generation);
            }
            // Do not invoke the old identity's capability gate after activating a new identity.
            progress=new JSONObject().put("phase","ready").put("generationIdentity",generation).put("completedBytes",1).put("totalBytes",1);
            RuntimeFiles.write(new File(root,"preparation.json"),progress);return preparedResult(seal);
        } catch(Exception|LinkageError failed) {
            progress=new JSONObject().put("phase",failed instanceof InterruptedException?"cancelled":"failed").put("catalogIdentity",catalog.identity)
                    .put("error",failed.getMessage()==null?"runtime_preparation_failed":failed.getMessage()).put("resumableDownload",true).put("existingGenerationRetained",true);
            RuntimeFiles.write(new File(root,"preparation.json"),progress);throw failed;
        }
    }
    private JSONObject preparedResult(JSONObject receipt) throws Exception {return new JSONObject().put("preparedEnvironmentIdentity",receipt.getString("generationIdentity")).put("catalogIdentity",catalog.identity).put("toolProbeReceipt",ProjectSources.copy(receipt)).put("projectFilesRetained",true).put("businessVerified",false);}
    private void phase(ManagedProcessJobs.PreparationContext context,String name,long completed,long total) throws Exception {
        context.progress(name,completed,total);JSONObject previous=progress;
        progress=new JSONObject().put("phase",name).put("completedBytes",completed).put("totalBytes",total).put("catalogIdentity",catalog.identity);
        if(!name.equals(previous.optString("phase"))||completed==total)RuntimeFiles.write(new File(root,"preparation.json"),progress);
    }
    private void prepareArchive(String name,JSONObject artifact,File output,File candidate,ManagedProcessJobs.PreparationContext context) throws Exception {
        File complete=new File(candidate,name+"-extracted.json");if(complete.isFile()&&artifact.getString("archiveSha256").equals(RuntimeFiles.read(complete).optString("archiveSha256")))return;
        File archive=download(artifact,context);
        // Only this catalog-owned, non-active staging subtree may be discarded after interrupted extraction.
        removeTree(output.toPath());Files.createDirectories(output.toPath());
        phase(context,"extracting_"+name,0,artifact.getLong("unpackedBytes"));
        File rootfs=new File(candidate,"rootfs");
        RuntimeTarExtractor.extract(archive,artifact.getString("format"),output,artifact.getInt("stripComponents"),artifact.getLong("unpackedBytes"),context::check,(stage,done,total)->{context.progress("extracting_"+name,done,total);progress=new JSONObject().put("phase","extracting_"+name).put("completedBytes",done).put("totalBytes",total).put("catalogIdentity",catalog.identity);},
                (path,target)->run(context,rootfs,null,List.of("/usr/bin/ln","--",guestPath(rootfs,target),guestPath(rootfs,path)),60000));
        RuntimeFiles.write(complete,new JSONObject().put("archiveSha256",artifact.getString("archiveSha256")));
    }
    File download(JSONObject artifact,ManagedProcessJobs.PreparationContext context) throws Exception {
        String hash=artifact.getString("archiveSha256");long expected=artifact.getLong("archiveBytes");File complete=new File(cache,hash+".archive"),partial=new File(cache,hash+".part");
        if(Files.isSymbolicLink(complete.toPath()))throw new SecurityException("runtime_download_cache_symlink");
        if(complete.isFile()) {phase(context,"verifying_download",0,expected);if(complete.length()==expected&&RuntimeFiles.sha256(complete).equals(hash))return complete;Files.delete(complete.toPath());}
        if(Files.isSymbolicLink(partial.toPath()))throw new SecurityException("runtime_download_cache_symlink");
        if(partial.length()>expected)Files.delete(partial.toPath());long offset=partial.isFile()?partial.length():0;
        if(offset<expected) {
            context.check();HttpURLConnection connection=connections.open(new URL(artifact.getString("url")));
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(10000);connection.setRequestProperty("Accept-Encoding","identity");
            if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
            try {
                int status=connection.getResponseCode();
                if(status==200)offset=0;
                else if(status!=206||offset==0||!("bytes "+offset+"-"+(expected-1)+"/"+expected).equals(connection.getHeaderField("Content-Range")))throw new java.io.IOException("runtime_download_http_"+status);
                long declared=connection.getContentLengthLong();if(declared>=0&&declared!=expected-offset)throw new SecurityException("runtime_download_content_length_mismatch");
                phase(context,"downloading",offset,expected);long copied=offset;
                try(InputStream input=connection.getInputStream();FileOutputStream out=new FileOutputStream(partial,offset>0)) {
                    byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1) {context.check();if(n==0)continue;copied+=n;if(copied>expected)throw new SecurityException("runtime_download_size_mismatch");out.write(buffer,0,n);context.progress("downloading",copied,expected);progress=new JSONObject().put("phase","downloading").put("completedBytes",copied).put("totalBytes",expected).put("catalogIdentity",catalog.identity);}
                    out.getFD().sync();
                }
            } finally {connection.disconnect();}
        }
        context.check();phase(context,"verifying_download",partial.length(),expected);
        if(partial.length()!=expected||!RuntimeFiles.sha256(partial).equals(hash)) {Files.deleteIfExists(partial.toPath());throw new SecurityException("runtime_package_hash_mismatch");}
        Files.move(partial.toPath(),complete.toPath(),StandardCopyOption.ATOMIC_MOVE);return complete;
    }
    synchronized AutoCloseable acquire() throws Exception {if(active==null)throw new IllegalStateException("runtime_environment_not_prepared");verifyTools(active,false);return environments.acquireGeneration(active.getString("generationIdentity"));}
    synchronized List<String> command(RuntimeWorkspace workspace,String relative,List<String> guestCommand) throws Exception {if(active==null)throw new IllegalStateException("runtime_environment_not_prepared");return command(rootfs(active),workspace,relative,guestCommand);}
    synchronized List<String> command(RuntimeWorkspace workspace,String relative,List<String> guestCommand,Map<String,String> ordinaryEnvironment)throws Exception {if(active==null)throw new IllegalStateException("runtime_environment_not_prepared");return command(rootfs(active),workspace,relative,guestCommand,ordinaryEnvironment);}
    synchronized String guestTool(String name) throws Exception {if(active==null)throw new IllegalStateException("runtime_environment_not_prepared");JSONArray tools=active.getJSONArray("tools");for(int i=0;i<tools.length();i++)if(name.equals(tools.getJSONObject(i).getString("name")))return tools.getJSONObject(i).getString("path");throw new IllegalArgumentException("runtime_tool_not_installed");}
    synchronized List<List<String>> probeCommands() throws Exception {List<List<String>> result=new ArrayList<>();JSONArray tools=active.getJSONArray("tools");for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);List<String> command=new ArrayList<>();command.add(tool.getString("path"));JSONArray args=tool.getJSONArray("versionArguments");for(int j=0;j<args.length();j++)command.add(args.getString(j));result.add(command);}return result;}
    synchronized Map<String,String> hostEnvironment(String environmentIdentity) throws Exception {
        if(active==null||!environmentIdentity.equals(active.getString("generationIdentity")))throw new SecurityException("runtime_reviewed_environment_changed");
        return hostEnvironment(environmentIdentity,links.require(active.optJSONObject("linkStorage"),active.getString("catalogIdentity")));
    }
    private Map<String,String> hostEnvironment(String environmentIdentity,File linkStorage) {Map<String,String> env=new LinkedHashMap<>();env.put("PROOT_LOADER",loader.getPath());env.put("PROOT_TMP_DIR",tmp.getPath());env.put("PROOT_L2S_DIR",linkStorage.getPath());env.put("TMPDIR",tmp.getPath());env.put("PATH","/system/bin");env.put("POCKETWORLD_ENVIRONMENT_IDENTITY",environmentIdentity);return env;}
    private List<String> command(File rootfs,RuntimeWorkspace workspace,String relative,List<String> guest) throws Exception {return command(rootfs,workspace,relative,guest,java.util.Collections.emptyMap());}
    private List<String> command(File rootfs,RuntimeWorkspace workspace,String relative,List<String> guest,Map<String,String> ordinaryEnvironment) throws Exception {
        if(!engineVerified())throw new SecurityException("runtime_apk_engine_missing_or_changed");
        File linkStorage=linkDirectory(rootfs);
        List<String> command=new ArrayList<>(List.of(proot.getPath(),"--kill-on-exit","--link2symlink","-0","-r",rootfs.getCanonicalPath(),"-b","/dev","-b","/proc","-b",linkStorage.getCanonicalPath()+":"+linkStorage.getCanonicalPath(),"-b",home.getCanonicalPath()+":/root","-b",tmp.getCanonicalPath()+":/tmp","-b",dns.getCanonicalPath()+":/etc/resolv.conf"));
        String cwd="/tmp";
        if(workspace!=null) {File directory=workspace.resolve(relative,false);if(!directory.isDirectory())throw new IllegalArgumentException("runtime_working_directory_missing");command.addAll(List.of("-b",workspace.directory().getCanonicalPath()+":/workspace"));String part=workspace.directory().toPath().relativize(directory.toPath()).toString();cwd="/workspace"+(part.isEmpty()?"":"/"+part);}
        command.addAll(List.of("-w",cwd,"/usr/bin/env","-i","PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin","HOME=/root","TMPDIR=/tmp","LANG=C.UTF-8","DEBIAN_FRONTEND=noninteractive","SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt","GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt"));for(Map.Entry<String,String> variable:new java.util.TreeMap<>(ordinaryEnvironment).entrySet()){String key=variable.getKey(),value=variable.getValue();if(!dev.pocketengine.features.local.project.ProjectCommandSpec.allowedEnvironmentKey(key)&&!(key.equals("TERM")&&value.equals("xterm-256color")))throw new SecurityException("runtime_guest_environment_override_rejected");command.add(key+"="+value);}command.addAll(guest);if(command.size()>256)throw new IllegalArgumentException("runtime_tool_arguments_too_many");return command;
    }
    private void run(ManagedProcessJobs.PreparationContext context,File rootfs,RuntimeWorkspace workspace,List<String> guest,long timeout) throws Exception {
        NativeProcessSupervisor.Exit exit=context.execute(command(rootfs,workspace,".",guest),hostEnvironment(catalog.identity,linkDirectory(rootfs)),tmp,timeout);
        if(exit.launchErrno!=0||exit.exitCode!=0||exit.cancelled||exit.timedOut||!exit.groupAbsent||!exit.outputComplete)throw new java.io.IOException("runtime_bootstrap_command_failed_exit_"+exit.exitCode+"_errno_"+exit.launchErrno);
    }
    private JSONObject loadGeneration(String identity) throws Exception {
        if(!identity.matches("[a-f0-9]{64}"))throw new SecurityException("runtime_generation_identity_invalid");File directory=new File(generations,identity);
        if(!directory.isDirectory()||Files.isSymbolicLink(directory.toPath()))throw new SecurityException("runtime_generation_missing");
        File filesystem=new File(directory,"rootfs");if(!directory.getAbsoluteFile().equals(directory.getCanonicalFile())||!filesystem.getAbsoluteFile().equals(filesystem.getCanonicalFile())||!filesystem.isDirectory())throw new SecurityException("runtime_generation_directory_invalid");
        JSONObject receipt=RuntimeFiles.read(new File(directory,"generation.json")),core=ProjectSources.copy(receipt);core.remove("generationIdentity");
        if(!identity.equals(receipt.getString("generationIdentity"))||!identity.equals(ProjectSources.digest(core))||!receipt.getString("catalogIdentity").equals(ProjectSources.digest(receipt.getJSONObject("catalog"))))throw new SecurityException("runtime_generation_receipt_changed");
        links.require(receipt.optJSONObject("linkStorage"),receipt.getString("catalogIdentity"));verifyTools(receipt,false);return receipt;
    }
    private File linkDirectory(File rootfs) throws Exception {
        File parent=rootfs.getCanonicalFile().getParentFile(),container=parent.getParentFile();
        if(!container.equals(staging)&&!container.equals(generations))throw new SecurityException("runtime_link_rootfs_not_owned");
        JSONObject receipt=RuntimeFiles.read(new File(parent,container.equals(staging)?"owner.json":"generation.json"));
        return links.require(receipt.optJSONObject("linkStorage"),receipt.getString("catalogIdentity"));
    }
    private static String guestPath(File rootfs,Path path) throws Exception {
        Path root=rootfs.getCanonicalFile().toPath();
        if(!path.startsWith(root)||path.equals(root))throw new SecurityException("runtime_archive_hardlink_escape");
        return "/"+root.relativize(path);
    }
    private File rootfs(JSONObject receipt) throws Exception {return new File(new File(generations,receipt.getString("generationIdentity")),"rootfs");}
    private boolean toolUnchanged(File rootfs,JSONObject tool) throws Exception {try{File file=resolveGuest(rootfs,tool.getString("path"));return file.length()==tool.getLong("bytes")&&file.lastModified()==tool.getLong("modifiedAt");}catch(SecurityException missing){return false;}}
    private void verifyTools(JSONObject receipt,boolean hashes) throws Exception {JSONArray tools=receipt.getJSONArray("tools");for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);if(!toolUnchanged(rootfs(receipt),tool)||hashes&&!RuntimeFiles.sha256(resolveGuest(rootfs(receipt),tool.getString("path"))).equals(tool.getString("sha256")))throw new SecurityException("runtime_generation_tool_changed_repair_required");}}
    private File resolveGuest(File rootfs,String path) throws Exception {Path root=rootfs.getCanonicalFile().toPath(),backing=linkDirectory(rootfs).toPath();File file=new File(rootfs,path.substring(1)).getCanonicalFile();if((!file.toPath().startsWith(root)&&!file.toPath().startsWith(backing))||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new SecurityException("runtime_guest_tool_missing_or_escaping");return file;}
    private static File guestDirectory(File root,String relative) throws Exception {File file=new File(root,relative);Path base=root.getCanonicalFile().toPath();if(!file.getCanonicalFile().toPath().startsWith(base)||Files.isSymbolicLink(file.toPath()))throw new SecurityException("runtime_guest_directory_escape");Files.createDirectories(file.toPath());return file;}
    private static void writeGuest(File root,String relative,String value) throws Exception {Path base=root.getCanonicalFile().toPath(),path=base.resolve(relative);Path part=base;for(Path name:base.relativize(path)){part=part.resolve(name);if(Files.isSymbolicLink(part))throw new SecurityException("runtime_bootstrap_symlink_write_rejected");}Files.createDirectories(path.getParent());writeRegular(path.toFile(),value);}
    private static void writeRegular(File file,String value) throws Exception {if(Files.isSymbolicLink(file.toPath()))throw new SecurityException("runtime_owned_file_symlink");try(FileOutputStream output=new FileOutputStream(file)){output.write(value.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}}
    private static void removeTree(Path root) throws Exception {if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;if(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))try(java.nio.file.DirectoryStream<Path> children=Files.newDirectoryStream(root)){for(Path child:children)removeTree(child);}Files.delete(root);}
}
