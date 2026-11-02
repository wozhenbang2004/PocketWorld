package dev.pocketengine.features.local;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.local.project.ProjectDevelopment;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.ProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.runtime.TermuxProjectRuntime;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import dev.pocketengine.core.RunControl;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local checkout files and executor receipts; the existing native supervisor remains task authority. */
public final class LocalModule implements EngineModule,dev.pocketengine.features.operations.OperationsRuntime {
    private final FeatureContext context;
    private final EngineModule projects;
    private final File root;
    private final NativeCommandExecutor commands;
    private final TermuxBridge termux;
    private final ProjectRuntime projectRuntime;
    private final Map<String,ProjectRuntime> runtimes=new LinkedHashMap<>();
    private final Map<String,String> runtimeGenerations=new LinkedHashMap<>();
    private final ProjectDevelopment development;
    private final Map<String,dev.pocketengine.features.local.preview.ProjectPreviewBundle.Verified> previews=new LinkedHashMap<>();
    private volatile boolean closed;
    private final Object projectPackageStaging=new Object();
    private dev.pocketengine.features.local.project.ProjectGitImport nativeGitImport;
    private dev.pocketengine.features.local.project.NativeExecutionCaches nativeExecutionCaches;
    private dev.pocketengine.store.TaskStore nativeCacheTasks;
    public LocalModule(FeatureContext context,EngineModule projects)throws Exception {
        this(context,projects,dev.pocketengine.features.local.runtime.android.AndroidManagedRuntimeFactory.create(context.android));
    }
    /** Native composition injects an approved managed environment; raw feature JSON cannot install one. */
    public LocalModule(FeatureContext context,EngineModule projects,ProjectRuntime nativeRuntime)throws Exception {
        this.context=context;this.projects=projects;root=new File(context.android.getFilesDir().getCanonicalFile(),"local-development");
        commands=new NativeCommandExecutor(new File(root,"command-receipts"),Arrays.asList(new File("/system/bin"),new File("/system/xbin")),job->context.emit(id(),json("kind","job","job",job)));
        termux=new TermuxBridge(context);
        projectRuntime=java.util.Objects.requireNonNull(nativeRuntime);String selected=projectRuntime.describe().getString("runtimeId");runtimes.put(selected,projectRuntime);runtimes.put("termux",new TermuxProjectRuntime(termux));for(String key:runtimes.keySet())runtimeGenerations.put(key,UUID.randomUUID().toString());
        development=new ProjectDevelopment(this);
    }
    @Override public String id(){return "local";}
    @Override public JSONObject runOwnerCompatibilityProbe(RunControl original)throws Exception{if(!(projectRuntime instanceof ManagedProjectRuntime))return new JSONObject().put("status","partial").put("reason","managed_runtime_unavailable").put("resourcesReleased",true);return ((ManagedProjectRuntime)projectRuntime).runOwnerCompatibilityProbe(original);}
    @Override public synchronized JSONObject snapshot()throws Exception {
        JSONArray checkouts=new JSONArray();File[] entries=new File(root,"checkouts").listFiles();
        if(entries!=null)for(File entry:entries)if(entry.isDirectory()&&new File(entry,"checkout.json").isFile())checkouts.put(JsonFiles.read(new File(entry,"checkout.json")));
        return new JSONObject().put("tools",commands.tools()).put("jobs",commands.list()).put("termuxJobs",termux.list()).put("checkouts",checkouts).put("termux",termux.status()).put("projectRuntime",projectRuntime.describe()).put("projectRuntimes",projectRuntimesForOwner()).put("arbitraryAppUidShell",false).put("reviewedImportRequired",true).put("termuxReturnMaxBytes",TermuxWorkspaceTransfer.MAX_BYTES).put("projectDevelopment",development.snapshot());
    }
    /** Native-only wiring; no JSON operation can install an authorization provider. */
    public void setProjectHost(ProjectDevelopment.HostPort host){development.setHost(host);for(ProjectRuntime runtime:runtimes.values())if(runtime instanceof ManagedProjectRuntime)((ManagedProjectRuntime)runtime).setProcessInputSink(receipt->{try{host.recordProcessInputReceipt(receipt);}catch(Exception failed){throw new IllegalStateException("runtime_original_pty_input_receipt_unconfirmed",failed);}});}
    /** Native original FeatureHost binding only; not exposed through handle(). */
    public synchronized void bindNativeExecutionCachesForHost(dev.pocketengine.store.TaskStore originalTasks){if(nativeCacheTasks!=null&&nativeCacheTasks!=originalTasks)throw new SecurityException("原执行缓存不能更换任务账本");nativeCacheTasks=java.util.Objects.requireNonNull(originalTasks);if(nativeExecutionCaches==null)nativeExecutionCaches=new dev.pocketengine.features.local.project.NativeExecutionCaches(root,originalTasks);}
    /** No JSON URL is accepted: the host returns this one original job's observed native page. */
    public dev.pocketengine.features.local.runtime.ProjectDevSession.Observed projectLivePreviewForHost(JSONObject binding,String executionId,RunControl observation)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");return development.livePreview(binding,executionId,observation);}
    public void requireProjectLivePreviewForOwner(dev.pocketengine.features.local.runtime.ProjectDevSession.Observed observed)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");development.checkLivePreview(observed);}
    public void requireProjectInputServiceForHost(JSONObject binding,String executionId)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");development.checkInputService(binding,executionId);}
    /** Waits for actual native IO without holding the file-module monitor needed by its live gate. */
    public JSONObject projectProcessInputForHost(JSONObject binding,String executionId)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");return development.processInput(binding,executionId);}
    public void requireProjectDevServiceForHost(JSONObject binding,String executionId)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");development.checkDevService(binding,executionId);}
    public void setProjectReceiptSink(java.util.function.Consumer<JSONObject> sink){for(ProjectRuntime runtime:runtimes.values())if(runtime instanceof ManagedProjectRuntime)((ManagedProjectRuntime)runtime).setReceiptSink(sink);}
    public synchronized JSONObject projectRuntimeForHost(String id)throws Exception {ProjectRuntime runtime=runtime(id);JSONObject facts=runtime.describe();return facts.put("runtimeId",id).put("name",id.equals("termux")?"Termux（可选外部环境）":"这台手机");}
    public synchronized JSONArray projectRuntimesForOwner()throws Exception {JSONArray facts=new JSONArray();for(String id:runtimes.keySet())facts.put(projectRuntimeForHost(id));return facts;}
    private ProjectRuntime runtime(String id){ProjectRuntime runtime=runtimes.get(id);if(runtime==null)throw new SecurityException("所选真实运行位置尚未配置");return runtime;}
    private static String runtimeId(JSONObject exact){return exact.optString("runtimeId","termux");}
    public synchronized boolean projectCapabilityStillCurrent(JSONObject exact,JSONObject captured)throws Exception {
        if(closed)return false;if(java.util.Set.of("writeFile","patchFiles","freezeProjectPackage","collectProjectOutput").contains(exact.optString("command")))return exact.getString("checkoutId").equals(captured.getString("checkoutId"));String id=runtimeId(exact);ProjectRuntime runtime=runtime(id);
        if(!runtimeGenerations.get(id).equals(captured.optString("nativeRuntimeGeneration")))return false;
        if(runtime instanceof ManagedProjectRuntime)return ((ManagedProjectRuntime)runtime).identity().equals(exact.getString("runtimeIdentity"));
        return dev.pocketengine.store.TaskStore.capabilityFingerprint(captured.getJSONObject("target")).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(projectRuntimeForHost(id)));
    }
    public synchronized JSONObject projectCheckoutForHost(String checkoutId)throws Exception {if(closed)throw new IllegalStateException("项目执行器已关闭");return new JSONObject(checkout(checkoutId).toString());}
    /** Native complete-copy values. A checkout is version isolation, never an OS command sandbox. */
    public static final class TeamProjectSource {
        private final LocalModule issuer;private final dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen frozen;private final String value,checkout;
        private TeamProjectSource(LocalModule issuer,String checkout,dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen frozen,JSONObject value){this.issuer=issuer;this.checkout=checkout;this.frozen=frozen;this.value=value.toString();}
        public JSONObject view()throws Exception{return new JSONObject(value);}
    }
    public static final class TeamProjectCheckout {
        private final LocalModule issuer;private final String value;
        private TeamProjectCheckout(LocalModule issuer,JSONObject value){this.issuer=issuer;this.value=value.toString();}
        public JSONObject view()throws Exception{return new JSONObject(value);}
    }
    /** Captures real bytes through the existing package codec/policy; no URI, grant or raw host path is accepted. */
    public synchronized TeamProjectSource captureTeamProjectSourceForHost(String checkoutId,String expectedSourceDigest)throws Exception {
        JSONObject selected=checkout(checkoutId),originalSource=ProjectSources.describe(export(checkoutId));if(!originalSource.getString("sourceDigest").equals(expectedSourceDigest))throw new SecurityException("原父项目源码已变化，请重新审阅完整基线");requireCheckoutWritable(checkoutId);requireIdle(checkoutId);
        var frozen=capturePackage(new JSONObject().put("checkoutId",checkoutId).put("expectedSourceDigest",expectedSourceDigest).put("mode","source_project").put("includeGeneratedRoots",false));JSONObject manifest=frozen.manifest();JSONArray sourceManifest=new JSONArray();for(var file:frozen.files().entrySet())sourceManifest.put(new JSONObject().put("path",file.getKey()).put("sha256",ProjectSources.sha256(file.getValue())).put("bytes",file.getValue().length));
        JSONObject value=new JSONObject().put("parentCheckoutId",checkoutId).put("parentProjectId",selected.getString("projectId")).put("parentSourceDigest",expectedSourceDigest).put("snapshotIdentity",frozen.identity).put("manifest",manifest).put("sourceManifest",sourceManifest).put("sourceDigest",ProjectSources.manifestDigest(sourceManifest)).put("sourceSecretsAbsenceVerified",false).put("osSandboxVerified",false);
        return new TeamProjectSource(this,checkoutId,frozen,value);
    }
    /** Native fixed-result reopening. Reading this original catalog does not authorize a copy or code execution. */
    public synchronized TeamProjectSource captureTeamProjectPackageForHost(String artifactId,String archiveSha256)throws Exception {
        if(closed)throw new IllegalStateException("原Local已关闭");var frozen=packageWorkspace().projectPackage(artifactId,archiveSha256);JSONObject manifest=frozen.manifest(),original=manifest.getJSONObject("source");JSONArray files=new JSONArray();for(var file:frozen.files().entrySet())files.put(new JSONObject().put("path",file.getKey()).put("sha256",ProjectSources.sha256(file.getValue())).put("bytes",file.getValue().length));
        JSONObject value=new JSONObject().put("parentCheckoutId",original.getString("checkoutId")).put("parentProjectId",original.getString("projectId")).put("parentSourceDigest",original.getString("sourceDigest")).put("snapshotIdentity",frozen.identity).put("manifest",manifest).put("sourceManifest",files).put("sourceDigest",ProjectSources.manifestDigest(files)).put("originalPackageArtifactId",artifactId).put("originalPackageArchiveSha256",archiveSha256).put("sourceSecretsAbsenceVerified",false).put("osSandboxVerified",false);
        return new TeamProjectSource(this,"",frozen,value);
    }
    /** Exact native catalog bytes/tombstone only; JSON metadata cannot construct this overlay. */
    public static final class TeamProjectOverlay {
        private final LocalModule issuer;private final byte[] bytes;public final String path,sha256,artifactId;public final int version;public final boolean tombstone;
        private TeamProjectOverlay(LocalModule issuer,String path,String sha,String artifact,int version,boolean tombstone,byte[] bytes){this.issuer=issuer;this.path=path;sha256=sha;artifactId=artifact;this.version=version;this.tombstone=tombstone;this.bytes=bytes==null?null:bytes.clone();}
        public JSONObject view()throws Exception{return new JSONObject().put("path",path).put("sha256",sha256).put("artifactId",artifactId).put("version",version).put("tombstone",tombstone).put("bytes",bytes==null?0:bytes.length);}
    }
    public synchronized TeamProjectOverlay teamProjectOverlayForHost(dev.pocketengine.features.workspace.WorkspaceArtifacts.ArtifactRef original,int appliedVersion)throws Exception {
        if(closed||original==null||appliedVersion<0)throw new SecurityException("缺少原不可变文件成果");var actual=packageWorkspace().fileArtifactForHost(original.artifactId,original.sha256);if(!ProjectSources.canonical(actual.json()).equals(ProjectSources.canonical(original.json())))throw new SecurityException("原成果目录版本改变");byte[] bytes=actual.tombstone?null:packageWorkspace().fileArtifactBytesForHost(original);if(bytes!=null&&!ProjectSources.sha256(bytes).equals(actual.sha256))throw new SecurityException("原成果实际字节不符");return new TeamProjectOverlay(this,actual.path,actual.sha256,actual.artifactId,appliedVersion,actual.tombstone,bytes);
    }
    /** Only an original Team host with a live native admission can create this full physical copy. */
    public synchronized TeamProjectCheckout cloneTeamProjectForHost(TeamProjectSource source,String logicalProjectId,JSONObject originalBinding,JSONArray nativeOverlays,ProjectAdmission admission)throws Exception {
        java.util.List<TeamProjectOverlay> overlays=new java.util.ArrayList<>();for(int i=0;i<nativeOverlays.length();i++){JSONObject value=nativeOverlays.getJSONObject(i);String path=VersionedWorkspace.path(value.getString("path"));byte[] bytes=value.getString("content").getBytes(StandardCharsets.UTF_8);if(!ProjectSources.sha256(bytes).equals(value.getString("sha256")))throw new SecurityException("原文字overlay字节不符");overlays.add(new TeamProjectOverlay(this,path,value.getString("sha256"),"legacy_native_text",value.optInt("version"),false,bytes));}return cloneTeamProjectForHost(source,logicalProjectId,originalBinding,overlays,admission);
    }
    public synchronized TeamProjectCheckout cloneTeamProjectForHost(TeamProjectSource source,String logicalProjectId,JSONObject originalBinding,java.util.List<TeamProjectOverlay> nativeOverlays,ProjectAdmission admission)throws Exception {
        if(closed||source==null||source.issuer!=this||admission==null)throw new SecurityException("没有原Local完整基线与native准入");dev.pocketengine.features.local.project.ProjectBuildPlan.identity(logicalProjectId);for(String key:new String[]{"taskId","runId","operationId"})dev.pocketengine.features.local.project.ProjectBuildPlan.identity(originalBinding.getString(key));admission.check();
        String id=UUID.nameUUIDFromBytes(("team-project-copy-v1:"+source.frozen.identity+":"+originalBinding.getString("taskId")+":"+originalBinding.getString("operationId")).getBytes(StandardCharsets.UTF_8)).toString();File checkouts=new File(root,"checkouts"),target=new File(checkouts,id),stage=new File(checkouts,id+".staging");File[] existing=checkouts.listFiles();if(existing!=null&&existing.length>=40)throw new IllegalStateException("本机工作副本已满，未删除未知资源");if(target.exists()||stage.exists())throw new SecurityException("原物理复制已有记录或部分结果，只核对原操作，不覆盖/重放");
        Map<String,byte[]> bytes=new java.util.TreeMap<>(source.frozen.files());java.util.Set<String> overlaid=new java.util.HashSet<>();for(TeamProjectOverlay overlay:nativeOverlays){if(overlay==null||overlay.issuer!=this)throw new SecurityException("overlay并非原Local签发的真实成果");String path=VersionedWorkspace.path(overlay.path);if(!overlaid.add(path))throw new SecurityException("原已审overlay路径重复");if(overlay.tombstone){if(!overlay.sha256.equals("absent")||overlay.bytes!=null)throw new SecurityException("真实删除不属于原完整基线");bytes.remove(path);}else{byte[] content=overlay.bytes.clone();if(!ProjectSources.sha256(content).equals(overlay.sha256)||content.length>dev.pocketengine.features.local.project.ProjectPackageArchive.MAX_FILE_BYTES)throw new SecurityException("原冻结前置/整合字节无效");bytes.put(path,content);}}if(nativeOverlays.size()>32||bytes.size()>ProjectSources.MAX_FILES)throw new SecurityException("完整副本超出已批准范围");long total=0;JSONArray sourceManifest=new JSONArray(),entries=new JSONArray(),originalFiles=source.checkout.isEmpty()?new JSONArray():checkout(source.checkout).getJSONArray("files");
        for(var file:bytes.entrySet()){total+=file.getValue().length;if(total>ProjectSources.MAX_TOTAL_BYTES)throw new SecurityException("完整副本展开超过16MiB");String sha=ProjectSources.sha256(file.getValue());sourceManifest.put(new JSONObject().put("path",file.getKey()).put("sha256",sha).put("bytes",file.getValue().length));int version=0;for(int i=0;i<originalFiles.length();i++)if(file.getKey().equals(originalFiles.getJSONObject(i).getString("path")))version=originalFiles.getJSONObject(i).optInt("sourceVersion",0);for(TeamProjectOverlay overlay:nativeOverlays)if(file.getKey().equals(overlay.path))version=overlay.version;entries.put(new JSONObject().put("path",file.getKey()).put("sha256",sha).put("sourceVersion",version));}
        String digest=ProjectSources.manifestDigest(sourceManifest);if(!stage.mkdirs())throw new IllegalStateException("无法创建本次独立副本");File directory=new File(stage,"files");if(!directory.mkdir())throw new IllegalStateException("无法创建副本内容目录");JSONObject manifest=source.frozen.manifest();JSONArray directories=manifest.optJSONArray("directories");for(int i=0;directories!=null&&i<directories.length();i++){File child=NativeCommandExecutor.within(directory,VersionedWorkspace.path(directories.getString(i)));if(!child.isDirectory()&&!child.mkdirs())throw new IllegalStateException("无法保留原空目录");}
        JSONArray frozenFiles=manifest.getJSONArray("files");for(var file:bytes.entrySet()){admission.check();File child=NativeCommandExecutor.within(directory,file.getKey());if(!child.getParentFile().isDirectory()&&!child.getParentFile().mkdirs())throw new IllegalStateException("无法创建原目录");atomic(child,file.getValue());for(int i=0;i<frozenFiles.length();i++)if(file.getKey().equals(frozenFiles.getJSONObject(i).getString("path"))&&frozenFiles.getJSONObject(i).optBoolean("executable")&&!child.setExecutable(true,true))throw new IllegalStateException("无法保留原可执行位");}
        JSONObject structuralManifest=dev.pocketengine.features.local.project.ProjectPackageArchive.capture(directory,new JSONObject().put("projectId",logicalProjectId).put("checkoutId",id).put("sourceDigest",digest),"source_project","",false).manifest();JSONObject structure=teamProjectStructure(structuralManifest);
        JSONObject metadata=new JSONObject().put("id",id).put("projectId",logicalProjectId).put("name","团队完整项目副本").put("createdAt",System.currentTimeMillis()).put("files",entries).put("teamProjectCopy",new JSONObject().put("nativeBinding",new JSONObject(originalBinding.toString())).put("snapshotIdentity",source.frozen.identity).put("sourceDigest",digest).put("sourceManifest",sourceManifest).put("structure",structure).put("fullSourceCopy",true).put("osSandboxVerified",false));JsonFiles.write(new File(stage,"checkout.json"),metadata);admission.check();Files.move(stage.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        JSONObject actual=ProjectSources.describe(export(id));if(!digest.equals(actual.getString("sourceDigest")))throw new SecurityException("完整复制后的实际源码与冻结基线不同");admission.check();return new TeamProjectCheckout(this,new JSONObject(metadata.toString()).put("sourceDigest",digest).put("sourceManifest",sourceManifest));
    }
    /** Private native lookup of an existing copy, not a new task or execution right. */
    /** Content-independent executable/directory facts, for native full-copy version checks only. */
    public static JSONObject teamProjectStructure(JSONObject packageManifest)throws Exception {java.util.TreeSet<String> executable=new java.util.TreeSet<>(),directories=new java.util.TreeSet<>();JSONArray files=packageManifest.getJSONArray("files"),dirs=packageManifest.getJSONArray("directories");for(int i=0;i<files.length();i++)if(files.getJSONObject(i).getBoolean("executable"))executable.add(files.getJSONObject(i).getString("path"));for(int i=0;i<dirs.length();i++)directories.add(dirs.getString(i));return new JSONObject().put("executableFiles",new JSONArray(executable)).put("directories",new JSONArray(directories));}
    public synchronized TeamProjectCheckout teamProjectCheckoutForHost(String checkoutId)throws Exception {
        JSONObject actual=checkout(checkoutId);if(actual.optJSONObject("teamProjectCopy")==null)throw new SecurityException("不是原native创建的团队副本");return new TeamProjectCheckout(this,actual);
    }
    public synchronized JSONObject captureTeamProjectCheckoutForHost(TeamProjectCheckout selected)throws Exception {
        if(selected==null||selected.issuer!=this||closed)throw new SecurityException("不是原Local团队副本");JSONObject metadata=selected.view(),actual=checkout(metadata.getString("id"));JSONObject expected=new JSONObject(metadata.toString());expected.remove("sourceDigest");expected.remove("sourceManifest");if(!ProjectSources.canonical(actual).equals(ProjectSources.canonical(expected)))throw new SecurityException("原团队副本元数据改变");
        String checkout=metadata.getString("id");requireCheckoutWritable(checkout);requireIdle(checkout);JSONObject source=ProjectSources.describe(export(checkout));var frozen=capturePackage(new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",source.getString("sourceDigest")).put("mode","source_project").put("includeGeneratedRoots",false));return new JSONObject().put("checkout",actual).put("source",source).put("snapshotIdentity",frozen.identity).put("manifest",frozen.manifest());
    }
    public synchronized JSONObject createProjectForOwner(String name,String template)throws Exception {
        if(closed||name==null||name.trim().isEmpty()||name.length()>80)throw new IllegalArgumentException("请输入1至80字项目名");JSONArray files=dev.pocketengine.features.local.project.ProjectTemplates.files(template);JSONObject project=projects.handle("create",new JSONObject().put("name",name.trim()));String id=project.getString("id");
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);projects.handle("write",new JSONObject().put("projectId",id).put("path",file.getString("path")).put("content",file.getString("content")).put("expectedVersion",0));}
        return prepare(id).put("template",template).put("templateFilesWritten",files.length());
    }
    public synchronized JSONObject openProjectForOwner(String projectId)throws Exception {
        projects.handle("export",new JSONObject().put("projectId",projectId));JSONObject latest=null;File[] entries=new File(root,"checkouts").listFiles();
        if(entries!=null)for(File entry:entries){File metadata=new File(entry,"checkout.json");if(!metadata.isFile())continue;JSONObject value=JsonFiles.read(metadata);if(projectId.equals(value.optString("projectId"))&&(latest==null||value.optLong("createdAt")>latest.optLong("createdAt")))latest=value;}
        return latest==null?prepare(projectId):new JSONObject(latest.toString());
    }
    public ManagedProjectRuntime managedRuntimeForOwner(){return projectRuntime instanceof ManagedProjectRuntime?(ManagedProjectRuntime)projectRuntime:null;}
    public synchronized ManagedProjectRuntime ownedServiceRuntimeForHost(String id)throws Exception {
        ProjectRuntime selected=runtime(id);if(!(selected instanceof ManagedProjectRuntime))throw new SecurityException("受控服务需要原本机managed运行时");return (ManagedProjectRuntime)selected;
    }
    private void requireCheckoutWritable(String checkout)throws Exception {
        for(ProjectRuntime selected:runtimes.values())if(selected instanceof ManagedProjectRuntime)((ManagedProjectRuntime)selected).requireCheckoutWritable(checkout);
    }
    public synchronized dev.pocketengine.features.local.preview.ProjectPreviewBundle.Verified projectPreviewForOwner(String previewId,String checkoutId){var preview=previews.get(previewId);if(closed||preview==null||!checkoutId.equals(preview.checkoutId))throw new IllegalStateException("此预览资源已释放，请重新核对当前项目");return preview;}
    private JSONObject preview(JSONObject input)throws Exception {
        String checkoutId=JsonFiles.text(input,"checkoutId",80);JSONObject source=ProjectSources.describe(export(checkoutId));if(!source.getString("sourceDigest").equals(input.getString("expectedSourceDigest")))throw new SecurityException("预览所选源码已变化，请重新核对");
        var bundle=dev.pocketengine.features.local.preview.ProjectPreviewBundle.capture(content(checkoutId),checkoutId,source.getString("projectId"),source.getString("sourceDigest"),input.getString("entryPath"));
        if(!source.getString("sourceDigest").equals(ProjectSources.describe(export(checkoutId)).getString("sourceDigest")))throw new SecurityException("预览采集期间源码变化，请重新核对");
        previews.put(bundle.identity,bundle);int bytes=0;for(var value:previews.values())bytes+=value.totalBytes;while(previews.size()>3||bytes>48*1024*1024){var key=previews.keySet().iterator().next();bytes-=previews.remove(key).totalBytes;}
        JSONObject metadata=bundle.metadata();context.emit(id(),new JSONObject().put("kind","project_preview_ready").put("preview",metadata));return metadata;
    }
    private dev.pocketengine.features.workspace.WorkspaceModule packageWorkspace(){if(!(projects instanceof dev.pocketengine.features.workspace.WorkspaceModule))throw new IllegalStateException("原工作区文件成果宿主不可用");return (dev.pocketengine.features.workspace.WorkspaceModule)projects;}
    private dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen capturePackage(JSONObject input)throws Exception {
        String checkoutId=JsonFiles.text(input,"checkoutId",80);requireCheckoutWritable(checkoutId);requireIdle(checkoutId);JSONObject checkout=checkout(checkoutId),source=ProjectSources.describe(export(checkoutId));
        if(input.has("expectedSourceDigest")&&!source.getString("sourceDigest").equals(input.getString("expectedSourceDigest")))throw new SecurityException("原项目源码已变化，请重新核对项目包清单");
        if(input.has("includeGeneratedRoots")&&!(input.opt("includeGeneratedRoots") instanceof Boolean))throw new IllegalArgumentException("输出根目录选择需为布尔值");
        JSONObject original=new JSONObject().put("projectId",source.getString("projectId")).put("checkoutId",checkoutId).put("sourceDigest",source.getString("sourceDigest")).put("name",checkout.getString("name"));
        var frozen=dev.pocketengine.features.local.project.ProjectPackageArchive.capture(content(checkoutId),original,input.optString("mode","source_project"),input.optString("entryPath"),input.optBoolean("includeGeneratedRoots"));
        if(!source.getString("sourceDigest").equals(ProjectSources.describe(export(checkoutId)).getString("sourceDigest")))throw new SecurityException("采集期间源码变化，未保存不完整项目包");
        // Verify the complete selected listing again, including generated resources outside the source fence.
        var checked=dev.pocketengine.features.local.project.ProjectPackageArchive.capture(content(checkoutId),original,input.optString("mode","source_project"),input.optString("entryPath"),input.optBoolean("includeGeneratedRoots"));
        if(!frozen.identity.equals(checked.identity))throw new SecurityException("采集期间所选文件/资源清单变化，未保存项目包");return frozen;
    }
    private JSONObject projectPackagePlan(JSONObject input)throws Exception {
        var frozen=capturePackage(input);JSONObject manifest=frozen.manifest(),archive=dev.pocketengine.features.local.project.ProjectPackageDelivery.archiveIdentity(frozen);
        return new JSONObject().put("format","pocketworld.project-package-selection.v1").put("projectId",manifest.getJSONObject("source").getString("projectId")).put("checkoutId",manifest.getJSONObject("source").getString("checkoutId"))
            .put("sourceDigest",manifest.getJSONObject("source").getString("sourceDigest")).put("manifestIdentity",frozen.identity).put("manifest",manifest).put("archiveSha256",archive.getString("archiveSha256")).put("archiveBytes",archive.getLong("archiveBytes")).put("businessVerified",false).put("authorityAdded",false)
            .put("message","这是当前实际文件的固定快照清单，不代表构建成功或输出一定来自最后一次构建。请审阅包含/排除项，再明确保存；不会自动执行或导回版本库。");
    }
    /** Same original codec, native selected checkout and stopped/drained resource boundary. */
    public synchronized JSONObject projectPackageSelectionForHost(JSONObject input)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");return projectPackagePlan(new JSONObject(input.toString()));}
    private JSONObject freezeProjectPackage(JSONObject input)throws Exception {
        if(!input.optString("expectedManifestIdentity").matches("[a-f0-9]{64}")||!input.optString("expectedSourceDigest").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("请先核对原项目包清单与实际源码");
        var frozen=capturePackage(input);if(!frozen.identity.equals(input.getString("expectedManifestIdentity")))throw new SecurityException("项目包所选字节或范围已变化，请重新读取清单后决定");
        return packageWorkspace().createProjectPackage(frozen,input.getString("clientSnapshotId"));
    }
    private JSONObject previewProjectPackage(JSONObject input)throws Exception {
        String id=input.getString("artifactId"),sha=input.getString("sha256");var frozen=packageWorkspace().projectPackage(id,sha);
        var bundle=dev.pocketengine.features.local.preview.ProjectPreviewBundle.fromProjectPackage(frozen,id,sha,input.getString("entryPath"));
        previews.put(bundle.identity,bundle);int bytes=0;for(var value:previews.values())bytes+=value.totalBytes;while(previews.size()>3||bytes>48*1024*1024){var key=previews.keySet().iterator().next();bytes-=previews.remove(key).totalBytes;}
        return bundle.metadata();
    }
    /** Native-only stream export; generic feature JSON never receives an app-private payload path. */
    public long exportProjectPackageForOwner(String id,String sha,java.io.OutputStream destination)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");return packageWorkspace().exportProjectPackage(id,sha,destination);}
    public JSONObject removeProjectPackageForOwner(String id,String sha)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");return packageWorkspace().removeProjectPackage(id,sha);}
    private File packageImportRoot()throws Exception {File folder=new File(context.android.getCacheDir(),"project-package-imports-v1");if(Files.isSymbolicLink(folder.toPath())||!folder.isDirectory()&&!folder.mkdirs()||!folder.getAbsoluteFile().equals(folder.getCanonicalFile()))throw new IllegalStateException("项目包临时目录不可用");return folder;}
    private static String packageIdentity(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原项目包动作身份无效");return value;}
    private static String packageHash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原项目包摘要无效");return value;}
    /** Only the native document picker passes its already granted stream; no URI/grant is serialized. */
    public JSONObject stageProjectPackageForOwner(java.io.InputStream source,String ownerSelectionId)throws Exception {
        packageIdentity(ownerSelectionId);if(source==null||closed)throw new IllegalArgumentException("没有可读取的原项目包");
        // Provider IO never holds LocalModule's runtime/stop monitor.
        synchronized(projectPackageStaging){
            File directory=packageImportRoot(),target=new File(directory,ownerSelectionId+".zip");
            if(target.isFile())return stagedProjectPackage(ownerSelectionId,null);
            File[] staged=directory.listFiles(file->file.getName().matches("[a-f0-9-]{36}\\.zip"));if(staged==null||staged.length>=4)throw new IllegalStateException("已保留4份临时项目包，请明确导入或移除一份后再选择");
            File pending=File.createTempFile("project-import-",".pending",directory);
            try{
                long count=0;byte[] buffer=new byte[8192];try(FileOutputStream output=new FileOutputStream(pending)){
                    for(int n;(n=source.read(buffer))>=0;){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("项目包读取已取消");if(n==0)continue;count+=n;if(count>dev.pocketengine.features.local.project.ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("项目包超过20MiB，未导入部分文件");output.write(buffer,0,n);}output.getFD().sync();
                }
                var decoded=dev.pocketengine.features.local.project.ProjectPackageArchive.read(pending);
                if(!decoded.manifest().getString("mode").equals("source_project"))throw new IllegalArgumentException("这是固定网页输出成果，不是可继续源码项目包；可打开或导出，不导成源码项目");
                Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
                return stagedProjectPackage(ownerSelectionId,null);
            }finally{Files.deleteIfExists(pending.toPath());}
        }
    }
    /** Native owner-selected stream/tree only; complete selections feed the existing package importer. */
    private dev.pocketengine.features.local.project.ProjectSourceImport.Current sourceCurrent(RunControl original){
        java.util.Objects.requireNonNull(original);return ()->{original.check();if(closed||original.isPaused()||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("来源读取已取消或原入口失效，未发布部分工程");};
    }
    private void sourceStageCapacity(File directory)throws Exception {File[] saved=directory.listFiles(f->f.getName().matches("[a-f0-9-]{36}\\.zip"));if(saved==null||saved.length>=4)throw new IllegalStateException("已保留4份完整临时选择，请先明确导入或移除一份");}
    public JSONObject stageSourceTreeForOwner(dev.pocketengine.features.local.project.ProjectSourceImport.Tree source,String label,String selection,boolean generated,RunControl original)throws Exception {
        packageIdentity(selection);var current=sourceCurrent(original);current.check();synchronized(projectPackageStaging){File directory=packageImportRoot(),target=new File(directory,selection+".zip");if(target.exists()){JSONObject saved=stagedProjectPackage(selection,null);JSONObject evidence=saved.optJSONObject("sourceEvidence");if(evidence==null||!"saf_tree".equals(evidence.optString("sourceKind"))||generated!=evidence.optBoolean("includeGeneratedRoots"))throw new SecurityException("已有来源选择与本次类型或范围不同，未替换原选择");return saved;}sourceStageCapacity(directory);File copied=Files.createTempDirectory(directory.toPath(),"source-read-").toFile();
            try{var selected=dev.pocketengine.features.local.project.ProjectSourceImport.tree(source,copied,selection,label,generated,current);return publishSourceSelection(selected,selection,current);}finally{deleteOwnPackageStaging(copied);}}
    }
    public JSONObject stageSourceZipForOwner(java.io.InputStream stream,String label,String selection,boolean stripRoot,boolean generated,RunControl original)throws Exception {
        packageIdentity(selection);if(stream==null)throw new IllegalArgumentException("没有主人所选ZIP字节");var current=sourceCurrent(original);current.check();synchronized(projectPackageStaging){File directory=packageImportRoot(),target=new File(directory,selection+".zip");if(target.exists()){JSONObject saved=stagedProjectPackage(selection,null);JSONObject evidence=saved.optJSONObject("sourceEvidence");if(evidence==null||!"external_zip".equals(evidence.optString("sourceKind"))||generated!=evidence.optBoolean("includeGeneratedRoots"))throw new SecurityException("已有来源选择与本次类型或范围不同，未替换原选择");return saved;}sourceStageCapacity(directory);File copied=Files.createTempDirectory(directory.toPath(),"source-read-").toFile(),archive=new File(directory,"source-archive-"+UUID.randomUUID()+".pending");
            try{dev.pocketengine.features.local.project.ProjectSourceImport.copyArchive(stream,archive,current);var selected=dev.pocketengine.features.local.project.ProjectSourceImport.zip(archive,copied,selection,label,stripRoot,generated,current);return publishSourceSelection(selected,selection,current);}finally{deleteOwnPackageStaging(copied);Files.deleteIfExists(archive.toPath());}}
    }
    private JSONObject publishSourceSelection(dev.pocketengine.features.local.project.ProjectSourceImport.Selection selected,String selection,dev.pocketengine.features.local.project.ProjectSourceImport.Current current)throws Exception {
        File directory=packageImportRoot(),target=new File(directory,selection+".zip"),evidenceFile=new File(directory,selection+".source.json"),pending=File.createTempFile("source-package-",".pending",directory);boolean published=false;
        try{current.check();if(Files.exists(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.exists(evidenceFile.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new SecurityException("来源选择已有未确认文件，未覆盖");
            try(FileOutputStream output=new FileOutputStream(pending)){dev.pocketengine.features.local.project.ProjectPackageArchive.write(selected.frozen,output);output.getFD().sync();}current.check();var actual=dev.pocketengine.features.local.project.ProjectPackageArchive.read(pending);if(!selected.frozen.identity.equals(actual.identity))throw new SecurityException("来源复制后的原项目包清单不同");
            String hash=packageFileHash(pending);JSONObject evidence=selected.evidence();if(!selection.equals(evidence.getString("selectionId"))||!actual.identity.equals(evidence.getString("manifestIdentity")))throw new SecurityException("来源选择身份不同");
            // Sidecar is descriptive evidence, never URI/grant. Publish it first: a crash cannot
            // expose a source ZIP lacking its scope evidence as an ordinary portable package.
            JsonFiles.write(evidenceFile,new JSONObject().put("format","pocketworld.project-source-cache.v1").put("archiveSha256",hash).put("manifestIdentity",actual.identity).put("evidence",evidence));current.check();Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);published=true;return stagedProjectPackage(selection,hash);
        }finally{Files.deleteIfExists(pending.toPath());if(!published&&!target.exists())Files.deleteIfExists(evidenceFile.toPath());}
    }
    /** Descriptive provenance is accepted only with the retained complete ZIP's actual identity. */
    private JSONObject sourceSelectionEvidence(String selection,String sha,String manifest)throws Exception {
        File file=new File(packageImportRoot(),selection+".source.json");if(!Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>32768)throw new SecurityException("原来源选择说明无法核对");JSONObject retained=JsonFiles.read(file),evidence=retained.getJSONObject("evidence");
        if(!"pocketworld.project-source-cache.v1".equals(retained.optString("format"))||!sha.equals(retained.optString("archiveSha256"))||!manifest.equals(retained.optString("manifestIdentity"))||!"pocketworld.project-source-selection.v1".equals(evidence.optString("format"))||!selection.equals(evidence.optString("selectionId"))||!manifest.equals(evidence.optString("manifestIdentity"))||!java.util.Set.of("saf_tree","external_zip","git_https").contains(evidence.optString("sourceKind"))||!Boolean.TRUE.equals(evidence.opt("completeSelection"))||!Boolean.FALSE.equals(evidence.opt("executionAuthorityRestored"))||!Boolean.FALSE.equals(evidence.opt("sourcePermissionPersisted")))throw new SecurityException("原来源选择与实际包字节不同，未导入");return new JSONObject(evidence.toString());
    }
    public JSONObject clearInterruptedSourceImportsForOwner()throws Exception {
        if(closed)throw new IllegalStateException("原项目模块已关闭");synchronized(projectPackageStaging){File directory=packageImportRoot();File[] files=directory.listFiles();if(files==null)throw new IllegalStateException("无法列出来源读取缓存");int removed=0;for(File file:files){String name=file.getName();boolean ownIncomplete=name.matches("source-read-[A-Za-z0-9_-]+")||name.matches("source-(?:archive|package)-[A-Za-z0-9_-]+\\.pending")||name.matches("[a-f0-9-]{36}\\.source\\.json")&&!new File(directory,name.substring(0,36)+".zip").exists();if(!ownIncomplete)continue;if(Files.isSymbolicLink(file.toPath()))Files.delete(file.toPath());else if(file.isDirectory())deleteOwnPackageStaging(file);else Files.delete(file.toPath());removed++;}return new JSONObject().put("removedIncompleteCaches",removed).put("completeSelectionsRetained",true).put("projectsOrToolsDeleted",false);}
    }

    private JSONObject stagedProjectPackage(String selection,String expectedSha)throws Exception {
        File target=new File(packageImportRoot(),packageIdentity(selection)+".zip");
        if(Files.isSymbolicLink(target.toPath())||!target.isFile()||target.length()>dev.pocketengine.features.local.project.ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new IllegalStateException("原临时项目包已失效，请重新选择；未使用其他文件");
        String sha=packageFileHash(target);if(expectedSha!=null&&!packageHash(expectedSha).equals(sha))throw new SecurityException("所选项目包字节已变化，未导入");
        var frozen=dev.pocketengine.features.local.project.ProjectPackageArchive.read(target);if(!sha.equals(packageFileHash(target)))throw new SecurityException("读取期间原导入临时包变化，请重新核对");
        JSONObject result=new JSONObject().put("format","pocketworld.project-package-import-source.v1").put("selectionId",selection).put("sha256",sha).put("sizeBytes",target.length()).put("manifestIdentity",frozen.identity).put("manifest",frozen.manifest()).put("complete",true).put("authorityRestored",false);JSONObject evidence=sourceSelectionEvidence(selection,sha,frozen.identity);if(evidence!=null)result.put("sourceEvidence",evidence);return result;
    }
    public JSONObject inspectStagedProjectPackageForOwner(String selection,String sha)throws Exception {if(closed)throw new IllegalStateException("原项目模块已关闭");synchronized(projectPackageStaging){return stagedProjectPackage(selection,sha);}}
    public JSONArray listStagedProjectPackagesForOwner()throws Exception {
        if(closed)throw new IllegalStateException("原项目模块已关闭");synchronized(projectPackageStaging){JSONArray result=new JSONArray();File[] files=packageImportRoot().listFiles(file->file.getName().matches("[a-f0-9-]{36}\\.zip"));if(files==null||files.length>4)throw new IllegalStateException("项目包临时目录超出范围，请核对原目录");java.util.Arrays.sort(files,java.util.Comparator.comparing(File::getName));for(File file:files){String selection=file.getName().substring(0,36);if(Files.isSymbolicLink(file.toPath()))throw new SecurityException("项目包临时目录含链接，未读取目标");String sha=packageFileHash(file);try{result.put(stagedProjectPackage(selection,sha).put("available",true));}catch(Exception invalid){result.put(new JSONObject().put("selectionId",selection).put("sha256",sha).put("sizeBytes",file.length()).put("available",false).put("message","原临时包无法完整校验；可明确移除此缓存，不影响原项目"));}}return result;}
    }
    public void discardStagedProjectPackageForOwner(String selection,String sha)throws Exception {synchronized(projectPackageStaging){File file=new File(packageImportRoot(),packageIdentity(selection)+".zip");if(Files.isSymbolicLink(file.toPath())||!file.isFile()||!packageHash(sha).equals(packageFileHash(file)))throw new SecurityException("所选临时包已变化，未移除其他内容");Files.delete(file.toPath());Files.deleteIfExists(new File(packageImportRoot(),selection+".source.json").toPath());}}
    public JSONObject planProjectPackageImportForOwner(String selection,String sha,String projectName,String targetCheckout)throws Exception {return planProjectPackageImport(selection,sha,projectName,targetCheckout,null);}
    private JSONObject planProjectPackageImport(String selection,String sha,String projectName,String targetCheckout,dev.pocketengine.features.local.project.ProjectGitImport.Result nativeGit)throws Exception {
        JSONObject staged=inspectStagedProjectPackageForOwner(selection,sha);if(projectName==null||projectName.trim().isEmpty()||projectName.length()>80)throw new IllegalArgumentException("请输入1至80字项目名");
        if(!staged.getJSONObject("manifest").getString("mode").equals("source_project"))throw new IllegalArgumentException("请选择源码项目包");
        JSONObject plan=new JSONObject().put("format","pocketworld.project-package-import-plan.v1").put("selectionId",selection).put("packageSha256",sha).put("manifestIdentity",staged.getString("manifestIdentity")).put("projectName",projectName.trim()).put("clientImportId",UUID.randomUUID().toString());
        JSONObject sourceEvidence=staged.optJSONObject("sourceEvidence");if(sourceEvidence!=null&&"git_https".equals(sourceEvidence.optString("sourceKind"))&&nativeGit==null)throw new SecurityException("Git原读取需要在原Git入口审阅其固定历史与源码，不能作为普通ZIP绕过发布");if(sourceEvidence!=null){if(targetCheckout!=null&&!targetCheckout.isEmpty())throw new SecurityException("现有源码只能明确创建新项目，不覆盖或借用原项目权限");plan.put("sourceEvidenceSha256",ProjectSources.digest(sourceEvidence));}
        if(targetCheckout==null||targetCheckout.isEmpty())plan.put("mode","new_project");
        else synchronized(this){JSONObject actual=ProjectSources.describe(export(targetCheckout));plan.put("mode","same_project_new_checkout").put("targetCheckoutId",targetCheckout).put("projectId",actual.getString("projectId")).put("expectedTargetSourceDigest",actual.getString("sourceDigest"));}
        plan.put("planDigest",ProjectSources.digest(plan));return new JSONObject().put("plan",plan).put("manifest",staged.getJSONObject("manifest")).put("message","导入会创建新的工作副本，不覆盖原目录、不恢复任务或运行授权。原项目选项也保留旧任务和旧副本；在新副本明确开始新的项目任务。");
    }
    private static JSONObject verifiedPackageImportPlan(JSONObject input)throws Exception {
        JSONObject plan=ProjectSources.copy(input);String digest=plan.getString("planDigest");plan.remove("planDigest");if(!digest.equals(ProjectSources.digest(plan))||!"pocketworld.project-package-import-plan.v1".equals(plan.optString("format")))throw new SecurityException("请重新核对原项目包导入方案");
        packageIdentity(plan.getString("selectionId"));packageIdentity(plan.getString("clientImportId"));packageHash(plan.getString("packageSha256"));packageHash(plan.getString("manifestIdentity"));
        if(!java.util.Set.of("new_project","same_project_new_checkout").contains(plan.getString("mode"))||plan.getString("projectName").codePoints().allMatch(Character::isWhitespace)||plan.getString("projectName").length()>80)throw new IllegalArgumentException("导入范围或项目名无效");
        var allowed=new java.util.HashSet<>(java.util.Set.of("format","selectionId","packageSha256","manifestIdentity","projectName","clientImportId","mode","sourceEvidenceSha256"));
        if(plan.has("sourceEvidenceSha256")){packageHash(plan.getString("sourceEvidenceSha256"));if(!"new_project".equals(plan.getString("mode")))throw new SecurityException("来源源码不采用原项目权限");}
        if(plan.getString("mode").equals("same_project_new_checkout")){allowed.addAll(java.util.Set.of("targetCheckoutId","projectId","expectedTargetSourceDigest"));packageIdentity(plan.getString("targetCheckoutId"));packageIdentity(plan.getString("projectId"));packageHash(plan.getString("expectedTargetSourceDigest"));}
        var keys=plan.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new SecurityException("项目包方案含未支持字段，未恢复授权");return plan;
    }
    /** Copy a new, unpublished checkout outside the runtime monitor; the final native publication is short. */
    public JSONObject importProjectPackageForOwner(JSONObject originalPlan)throws Exception {return importProjectPackage(originalPlan,null,null);}
    private JSONObject importProjectPackage(JSONObject originalPlan,dev.pocketengine.features.local.project.ProjectGitImport.Result nativeGit,RunControl gitControl)throws Exception {
        JSONObject plan=verifiedPackageImportPlan(originalPlan);String client=plan.getString("clientImportId"),sha=plan.getString("packageSha256"),id=UUID.nameUUIDFromBytes(("project-package-checkout:"+client).getBytes(StandardCharsets.UTF_8)).toString();
        synchronized(this){JSONObject prior=existingPackageImport(id,client,sha,plan);if(prior!=null)return prior;}
        JSONObject staged=inspectStagedProjectPackageForOwner(plan.getString("selectionId"),sha);if(!plan.getString("manifestIdentity").equals(staged.getString("manifestIdentity")))throw new SecurityException("原项目包清单已变化");JSONObject sourceEvidence=staged.optJSONObject("sourceEvidence");if(sourceEvidence!=null&&"git_https".equals(sourceEvidence.optString("sourceKind"))){if(nativeGit==null||gitControl==null)throw new SecurityException("Git导入不能借普通项目包恢复其未审历史");nativeGit.requireCurrent(gitControl);JSONObject pins=nativeGit.metadata();pins.remove("files");if(!ProjectSources.digest(pins).equals(ProjectSources.digest(sourceEvidence.getJSONObject("original"))))throw new SecurityException("待发布Git版本与原来源选择不同");}else if(nativeGit!=null)throw new SecurityException("本次原来源不是Git读取");if(plan.has("sourceEvidenceSha256")!=(sourceEvidence!=null)||sourceEvidence!=null&&!plan.getString("sourceEvidenceSha256").equals(ProjectSources.digest(sourceEvidence)))throw new SecurityException("原来源选择说明在审阅后变化，未导入");
        dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen frozen; synchronized(projectPackageStaging){File archive=new File(packageImportRoot(),plan.getString("selectionId")+".zip");frozen=dev.pocketengine.features.local.project.ProjectPackageArchive.read(archive);if(!sha.equals(packageFileHash(archive))||!frozen.identity.equals(plan.getString("manifestIdentity")))throw new SecurityException("原项目包字节或清单不一致");}
        String projectId=plan.getString("mode").equals("new_project")?UUID.nameUUIDFromBytes(("project-package-import:"+client).getBytes(StandardCharsets.UTF_8)).toString():plan.getString("projectId");
        File staging=new File(root,"package-checkout-staging");if(Files.isSymbolicLink(staging.toPath())||!staging.isDirectory()&&!staging.mkdirs()||!staging.getAbsoluteFile().equals(staging.getCanonicalFile()))throw new IllegalStateException("无法建立原项目导入暂存目录");File prepared=Files.createTempDirectory(staging.toPath(),"checkout-").toFile();
        try{
            File files=new File(prepared,"files");if(!files.mkdir())throw new IllegalStateException("无法建立项目文件目录");JSONArray directories=frozen.manifest().getJSONArray("directories");for(int i=0;i<directories.length();i++){File folder=NativeCommandExecutor.within(files,directories.getString(i));if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("无法保留项目空目录");}JSONArray originalFiles=frozen.manifest().getJSONArray("files"),baseline=new JSONArray();
            for(int i=0;i<originalFiles.length();i++){
                if(closed||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("项目包导入已中止，原项目未覆盖");JSONObject file=originalFiles.getJSONObject(i);String path=file.getString("path");File target=NativeCommandExecutor.within(files,path);if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IllegalStateException("无法建立项目文件子目录");byte[] bytes=frozen.file(path);
                try(FileOutputStream output=new FileOutputStream(target)){output.write(bytes);output.getFD().sync();}if(file.getBoolean("executable")&&!target.setExecutable(true,true))throw new IllegalStateException("无法保留所选项目脚本的主人执行位");
                if(ProjectSources.sourcePath(path))baseline.put(new JSONObject().put("path",path).put("sha256",file.getString("sha256")).put("bytes",bytes.length).put("sourceVersion",0));
            }
            if(nativeGit!=null)nativeGit.copyGitMetadata(files,gitControl);
            JSONObject checkout=new JSONObject().put("id",id).put("projectId",projectId).put("name",plan.getString("projectName")).put("createdAt",System.currentTimeMillis()).put("files",baseline)
                .put("sourceImport",new JSONObject().put("clientId",client).put("packageSha256",sha).put("manifestIdentity",frozen.identity).put("mode",plan.getString("mode")).put("executionAuthorityRestored",false));
            if(sourceEvidence!=null)checkout.getJSONObject("sourceImport").put("sourceKind",sourceEvidence.getString("sourceKind")).put("sourceEvidenceSha256",plan.getString("sourceEvidenceSha256"));
            if(nativeGit!=null){JSONObject pins=nativeGit.metadata();pins.remove("files");checkout.getJSONObject("sourceImport").put("gitOriginal",pins).put("gitHistoryCopied",true).put("credentialsRestored",false);}

            JsonFiles.write(new File(prepared,"checkout.json"),checkout);
            synchronized(this){
                if(closed||Thread.currentThread().isInterrupted())throw new IllegalStateException("原项目模块已关闭或导入等待已取消，未发布新工作副本");JSONObject prior=existingPackageImport(id,client,sha,plan);if(prior!=null)return prior;
                File checkouts=new File(root,"checkouts");File[] saved=checkouts.listFiles();if(saved!=null&&saved.length>=40)throw new IllegalStateException("最多保留40个工作副本，原项目包仍保留");if(!checkouts.isDirectory()&&!checkouts.mkdirs())throw new IllegalStateException("无法建立工作副本目录");
                if(plan.getString("mode").equals("new_project")){JSONObject project=packageWorkspace().createImportedProject(plan.getString("projectName"),client,sha);if(!projectId.equals(project.getString("id")))throw new SecurityException("原项目导入身份不一致");}
                else {JSONObject actual=ProjectSources.describe(export(plan.getString("targetCheckoutId")));if(!projectId.equals(actual.getString("projectId"))||!packageHash(plan.getString("expectedTargetSourceDigest")).equals(actual.getString("sourceDigest")))throw new SecurityException("原目标副本已变化，请重新读取并核对；没有覆盖原目录");}
                File destination=new File(checkouts,id);if(Files.exists(destination.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new SecurityException("目标副本目录已有未确认来源，未覆盖");Files.move(prepared.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);
                return new JSONObject(checkout.toString()).put("imported",true).put("continuedExisting",false).put("originalCheckoutUnchanged",true).put("taskOrGrantRestored",false);
            }
        }finally{if(prepared.exists())deleteOwnPackageStaging(prepared);}
    }
    public synchronized JSONObject findProjectPackageImportForOwner(JSONObject originalPlan)throws Exception {JSONObject plan=verifiedPackageImportPlan(originalPlan);String client=plan.getString("clientImportId"),id=UUID.nameUUIDFromBytes(("project-package-checkout:"+client).getBytes(StandardCharsets.UTF_8)).toString();JSONObject result=existingPackageImport(id,client,plan.getString("packageSha256"),plan);return result==null?new JSONObject().put("found",false).put("clientImportId",client):result.put("found",true);}
    private JSONObject existingPackageImport(String id,String client,String sha,JSONObject plan)throws Exception {
        File directory=new File(new File(root,"checkouts"),id),record=new File(directory,"checkout.json");if(!Files.exists(directory.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))return null;if(Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(record.toPath())||!record.isFile())throw new SecurityException("原导入副本登记不是正常文件，未覆盖或重建");JSONObject value=JsonFiles.read(record),source=value.optJSONObject("sourceImport");
        if(!id.equals(value.optString("id"))||source==null||!client.equals(source.optString("clientId"))||!sha.equals(source.optString("packageSha256"))||!plan.getString("manifestIdentity").equals(source.optString("manifestIdentity"))||!plan.getString("projectName").equals(value.optString("name"))||!plan.getString("mode").equals(source.optString("mode"))||plan.getString("mode").equals("same_project_new_checkout")&&!plan.getString("projectId").equals(value.optString("projectId")))throw new SecurityException("原导入动作与已有副本不同，未重新导入或覆盖");
        if(plan.has("sourceEvidenceSha256")!=source.has("sourceEvidenceSha256")||plan.has("sourceEvidenceSha256")&&!plan.getString("sourceEvidenceSha256").equals(source.getString("sourceEvidenceSha256")))throw new SecurityException("原导入来源说明与已有副本不同，未用改变的方案重放或查询");
        if(Files.isSymbolicLink(new File(directory,"files").toPath())||!new File(directory,"files").isDirectory()||!directory.getAbsoluteFile().equals(directory.getCanonicalFile()))throw new IllegalStateException("原导入副本文件目录已失效，未自动重建");return new JSONObject(value.toString()).put("imported",true).put("continuedExisting",true).put("originalCheckoutUnchanged",true).put("taskOrGrantRestored",false);
    }
    private static String packageFileHash(File file)throws Exception {java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long count=0;try(java.io.InputStream input=Files.newInputStream(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){byte[] bytes=new byte[8192];for(int n;(n=input.read(bytes))>=0;){if(n==0)continue;count+=n;if(count>dev.pocketengine.features.local.project.ProjectPackageArchive.MAX_ARCHIVE_BYTES)throw new IllegalArgumentException("项目包超过20MiB");digest.update(bytes,0,n);}}StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void deleteOwnPackageStaging(File directory)throws Exception {try(var paths=Files.walk(directory.toPath())){for(var path:java.util.Collections.unmodifiableList(paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())))Files.deleteIfExists(path);}}
    private dev.pocketengine.features.local.project.ProjectStorage storageForOwner()throws Exception {
        if(closed||!(projectRuntime instanceof ManagedProjectRuntime))throw new IllegalStateException("原managed存储宿主不可用");return new dev.pocketengine.features.local.project.ProjectStorage(this,((ManagedProjectRuntime)projectRuntime).ownerStorageForHost(),new dev.pocketengine.features.local.project.ProjectStorage.Checkouts(){public JSONArray list()throws Exception{return ownerArchiveCheckoutsForHost();}public File root(String id)throws Exception{JSONObject actual=checkout(id);if(actual.has("teamProjectCopy"))throw new SecurityException("团队活动副本不是私人缓存清理范围");return content(id);}public void requireIdle(String id)throws Exception{requireCheckoutWritable(id);LocalModule.this.requireIdle(id);}},context.android.getFilesDir(),context.android.getCacheDir(),nativeExecutionCaches);
    }
    public synchronized JSONObject ownerStorageInventoryForHost(RunControl original)throws Exception{return storageForOwner().inventory(original).put("retentionPolicy",ownerStoragePolicyForHost());}
    public synchronized dev.pocketengine.features.local.project.ProjectStorage.Review reviewOwnerStorageForHost(java.util.Set<String> ids,RunControl original)throws Exception{return storageForOwner().review(ids,original);}
    public synchronized JSONObject executeOwnerStorageForHost(dev.pocketengine.features.local.project.ProjectStorage.Confirmed confirmed,dev.pocketengine.features.local.project.ProjectStorage.Current nativeCurrent)throws Exception {if(closed)throw new SecurityException("原项目宿主已关闭");return dev.pocketengine.features.local.project.ProjectStorage.executeOriginal(confirmed,this,nativeCurrent);}
    public synchronized JSONObject queryOwnerStorageForHost(JSONObject originalScope,RunControl observation)throws Exception{return storageForOwner().query(originalScope,observation);}
    /** Policy only suggests reviewed targets. It grants no delete permission and
     * creates no timer, startup sweep, Runner or background maintenance job. */
    public synchronized JSONObject ownerStoragePolicyForHost()throws Exception {
        File file=new File(root,"owner-storage-policy.json");if(Files.isSymbolicLink(root.toPath())||Files.isSymbolicLink(file.toPath()))throw new SecurityException("原存储偏好目录不可用");JSONObject policy=JsonFiles.read(file);if(policy.length()==0)policy=new JSONObject().put("keepOldRuntimeGenerations",2).put("keepRecentBackupCopies",2).put("suggestGeneratedCleanup",false);if(policy.getInt("keepOldRuntimeGenerations")<0||policy.getInt("keepOldRuntimeGenerations")>5||policy.getInt("keepRecentBackupCopies")<0||policy.getInt("keepRecentBackupCopies")>5||!(policy.opt("suggestGeneratedCleanup") instanceof Boolean))throw new SecurityException("原存储保留规则不可用，未自动清理");return new JSONObject(policy.toString()).put("revision",ProjectSources.digest(policy)).put("automaticCleanup",false);
    }
    public synchronized JSONObject saveOwnerStoragePolicyForHost(int keepGenerations,int keepBackups,boolean generated,String expectedRevision)throws Exception {
        JSONObject old=ownerStoragePolicyForHost();if(!old.getString("revision").equals(expectedRevision)||keepGenerations<0||keepGenerations>5||keepBackups<0||keepBackups>5)throw new SecurityException("原保留规则已变化，请重新读取");JSONObject next=new JSONObject().put("keepOldRuntimeGenerations",keepGenerations).put("keepRecentBackupCopies",keepBackups).put("suggestGeneratedCleanup",generated);JsonFiles.write(new File(root,"owner-storage-policy.json"),next);return ownerStoragePolicyForHost();
    }
    public static final class GitPublicationReview implements AutoCloseable {
        private final LocalModule issuer;private final dev.pocketengine.features.local.project.ProjectGitImport.Result source;private final JSONObject plan;private final RunControl original;private final long expires=System.currentTimeMillis()+120000;private boolean used,closed;
        private GitPublicationReview(LocalModule issuer,dev.pocketengine.features.local.project.ProjectGitImport.Result source,JSONObject plan,RunControl original){this.issuer=issuer;this.source=source;this.plan=plan;this.original=original;}
        private void current()throws Exception{original.checkpoint();if(closed||issuer.closed||System.currentTimeMillis()>=expires)throw new SecurityException("原Git新项目审阅已失效；未重新确认或执行");}
        public JSONObject metadata()throws Exception{current();return new JSONObject().put("actionId",plan.getString("clientImportId")).put("sourceActionId",source.actionId()).put("plan",new JSONObject(plan.toString())).put("source",source.metadata()).put("expiresAt",expires).put("overwritesExistingProject",false).put("taskOrGrantRestored",false);}
        public synchronized GitPublicationConfirmed confirm()throws Exception{current();if(used)throw new SecurityException("原Git发布审阅已确认，只核对原导入编号");used=true;return new GitPublicationConfirmed(this);}
        @Override public synchronized void close(){closed=true;}
    }
    public static final class GitPublicationConfirmed {
        private final GitPublicationReview review;private boolean entered;private GitPublicationConfirmed(GitPublicationReview review){this.review=review;}
        public String actionId()throws Exception{return review.plan.getString("clientImportId");}
        public RunControl original(){return review.original;}
        public JSONObject metadata()throws Exception{JSONObject value=review.metadata(),source=value.getJSONObject("source");source.put("fileCount",source.getJSONArray("files").length());source.remove("files");return value;}
        private synchronized void enter(LocalModule actual)throws Exception{review.current();if(entered||review.issuer!=actual)throw new SecurityException("Git发布没有同一原生审阅或已进入，只查询原导入动作");entered=true;}
    }
    /** Original package import remains the single new-project publisher. Git
     * metadata never comes from a model ZIP, JSON host path or raw clone result. */
    public GitPublicationReview reviewGitPublicationForOwner(dev.pocketengine.features.local.project.ProjectGitImport.Result source,RunControl original)throws Exception {
        if(original==null||original.hasHostRun()||closed)throw new SecurityException("Git新项目从主人原生页面明确审阅");source.requireCurrent(original);JSONObject pins=source.metadata();String selection=source.actionId();JSONObject staged;
        synchronized(projectPackageStaging){File target=new File(packageImportRoot(),selection+".zip");if(Files.exists(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){staged=stagedProjectPackage(selection,null);if(!"git_https".equals(staged.getJSONObject("sourceEvidence").optString("sourceKind"))||!pins.getString("manifestSha256").equals(staged.getJSONObject("sourceEvidence").getJSONObject("original").optString("manifestSha256"))||!pins.getString("gitMetadataDigest").equals(staged.getJSONObject("sourceEvidence").getJSONObject("original").optString("gitMetadataDigest")))throw new SecurityException("原Git临时来源与当前已读版本不同，未替换原选择");}
            else{sourceStageCapacity(packageImportRoot());staged=publishSourceSelection(dev.pocketengine.features.local.project.ProjectSourceImport.nativeGit(source),selection,sourceCurrent(original));}}
        JSONObject proposal=planProjectPackageImport(selection,staged.getString("sha256"),pins.getString("projectName"),null,source).getJSONObject("plan");return new GitPublicationReview(this,source,proposal,original);
    }
    public GitPublicationResult executeGitPublicationForOwner(GitPublicationConfirmed selected)throws Exception {java.util.Objects.requireNonNull(selected).enter(this);selected.review.source.requireCurrent(selected.review.original);return new GitPublicationResult(selected.review.plan,importProjectPackage(selected.review.plan,selected.review.source,selected.review.original).put("found",true));}
    /** Exact native import/query fact, not a model-supplied success JSON. */
    public static final class GitPublicationResult {private final String value,plan;private GitPublicationResult(JSONObject plan,JSONObject value){this.plan=plan.toString();this.value=value.toString();}public JSONObject metadata()throws Exception{return new JSONObject(value);}public JSONObject originalPlan()throws Exception{return new JSONObject(plan);}}
    public GitPublicationResult queryGitPublicationForOwner(JSONObject originalNativePlan)throws Exception{return new GitPublicationResult(originalNativePlan,findProjectPackageImportForOwner(originalNativePlan));}

    /** Native owner Git intake only, not a feature/model command or second runtime. */
    public synchronized dev.pocketengine.features.local.project.ProjectGitImport gitImportsForOwner()throws Exception {
        if(closed||!(projectRuntime instanceof ManagedProjectRuntime))throw new SecurityException("原本机Git读取环境不可用");if(nativeGitImport==null)nativeGitImport=new dev.pocketengine.features.local.project.ProjectGitImport(new File(root,"git-source-intakes"),(ManagedProjectRuntime)projectRuntime);return nativeGitImport;
    }
    /** Native Skill host only: retain the original runtime, never create a second
     * runner or turn a model-supplied native=true marker into an executor. */
    public synchronized ManagedProjectRuntime managedSkillRuntimeForHost()throws Exception {
        if(closed||!(projectRuntime instanceof ManagedProjectRuntime))throw new SecurityException("本机托管Skill运行环境不可用");return (ManagedProjectRuntime)projectRuntime;
    }
    public synchronized JSONArray managedSkillCheckoutsForHost()throws Exception {
        if(closed)throw new SecurityException("原项目宿主已关闭");JSONArray result=new JSONArray();File[] rows=new File(root,"checkouts").listFiles();if(rows==null)return result;
        java.util.Arrays.sort(rows,java.util.Comparator.comparing(File::getName));
        for(File entry:rows){if(result.length()>=40)throw new SecurityException("原项目副本清单超过当前容量，未截断当成完整列表");if(Files.isSymbolicLink(entry.toPath())||!entry.isDirectory()||!entry.getName().matches("[a-f0-9-]{36}"))continue;JSONObject row=checkout(entry.getName());if(row.has("teamProjectCopy")||row.has("nativeGitImportPending")||row.has("skillExecutionCopy"))continue;result.put(new JSONObject().put("checkoutId",row.getString("id")).put("projectId",row.getString("projectId")).put("name",row.getString("name")));}return result;
    }
    /** Exact ordinary private checkout source observation. Execution uses a
     * declared isolated copy; this method grants no write-back or task authority. */
    public synchronized JSONObject managedSkillSourceForHost(String checkoutId,RunControl observation)throws Exception {
        observation.check();if(observation.isPaused())throw new SecurityException("原Skill输入观察已暂停");if(closed)throw new SecurityException("原项目宿主已关闭");JSONObject nativeCheckout=checkout(checkoutId);if(nativeCheckout.has("teamProjectCopy")||nativeCheckout.has("nativeGitImportPending")||nativeCheckout.has("skillExecutionCopy"))throw new SecurityException("本机私人Skill入口不借用团队或未发布副本权限");requireIdle(checkoutId);requireCheckoutWritable(checkoutId);JSONObject source=export(checkoutId);observation.check();if(observation.isPaused())throw new SecurityException("原Skill输入观察已暂停");return source;
    }
    /** Native owner backup only. Team copies stay under their original typed
     * team/result source; never copy them by treating a JSON checkout as private. */
    public synchronized JSONArray ownerArchiveCheckoutsForHost()throws Exception {
        JSONArray all=snapshot().getJSONArray("checkouts"),selected=new JSONArray();for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(row.has("teamProjectCopy"))continue;selected.put(new JSONObject().put("checkoutId",row.getString("id")).put("projectId",row.getString("projectId")).put("name",row.getString("name")));}return selected;
    }
    public synchronized dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen ownerCheckoutArchiveForHost(String id,RunControl original)throws Exception {
        original.checkpoint();JSONObject selected=checkout(id);if(selected.has("teamProjectCopy"))throw new SecurityException("团队副本必须通过原完成成果备份，不能借私人归档绕过团队来源");requireCheckoutWritable(id);requireIdle(id);JSONObject source=ProjectSources.describe(export(id));var result=capturePackage(new JSONObject().put("checkoutId",id).put("expectedSourceDigest",source.getString("sourceDigest")).put("mode","source_project").put("includeGeneratedRoots",true));original.checkpoint();return result;
    }
    /** New private checkout from a previously verified portable package. No
     * runtime/home/lease/task/grant or old checkout identity is restored. */
    public synchronized JSONObject restoreOwnerCheckoutArchiveForHost(dev.pocketengine.features.local.project.ProjectPackageArchive.Frozen frozen,String newProject,String nativeImportId,String originalCheckout,String sectionDigest,RunControl original)throws Exception {
        packageIdentity(nativeImportId);packageIdentity(originalCheckout);packageHash(sectionDigest);packageWorkspace().projectSnapshotForHost(newProject);JSONObject manifest=frozen.manifest(),source=manifest.getJSONObject("source");if(!"source_project".equals(manifest.getString("mode"))||!originalCheckout.equals(source.getString("checkoutId")))throw new SecurityException("工作副本档案不是原来源完整项目包");
        JSONObject prior=queryOwnerCheckoutArchiveForHost(newProject,nativeImportId,originalCheckout,sectionDigest,frozen.identity);if(prior.getBoolean("found"))return prior;File checkouts=new File(root,"checkouts");if(Files.isSymbolicLink(checkouts.toPath())||!checkouts.isDirectory()&&!checkouts.mkdirs()||!checkouts.getCanonicalFile().equals(checkouts.getAbsoluteFile()))throw new SecurityException("原工作副本目录不可用");File[] existing=checkouts.listFiles();if(existing!=null&&existing.length>=40)throw new IllegalStateException("原工作副本已满40项，未清除旧副本");
        String id=ownerArchiveCheckoutId(nativeImportId,originalCheckout);File pending=new File(checkouts,id+".backup-pending"),target=new File(checkouts,id);if(Files.exists(pending.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.exists(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new SecurityException("原副本恢复已有未确认数据，只查询原动作");if(!pending.mkdir())throw new java.io.IOException("无法建立原副本恢复暂存");boolean moved=false;
        try {File files=new File(pending,"files");if(!files.mkdir())throw new java.io.IOException("无法创建恢复副本文件目录");JSONArray dirs=manifest.getJSONArray("directories");for(int i=0;i<dirs.length();i++){original.checkpoint();File directory=NativeCommandExecutor.within(files,dirs.getString(i));if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("无法恢复原空目录");}JSONArray entries=new JSONArray(),items=manifest.getJSONArray("files");for(int i=0;i<items.length();i++){original.checkpoint();JSONObject row=items.getJSONObject(i);String path=row.getString("path");File targetFile=NativeCommandExecutor.within(files,path);if(!targetFile.getParentFile().isDirectory()&&!targetFile.getParentFile().mkdirs())throw new java.io.IOException("无法创建恢复文件子目录");byte[] bytes=frozen.file(path);try(FileOutputStream output=new FileOutputStream(targetFile)){output.write(bytes);output.getFD().sync();}if(row.getBoolean("executable")&&!targetFile.setExecutable(true,true))throw new java.io.IOException("无法恢复原文件执行位");if(ProjectSources.sourcePath(path))entries.put(new JSONObject().put("path",path).put("sha256",row.getString("sha256")).put("bytes",bytes.length).put("sourceVersion",0));}
            JSONObject value=new JSONObject().put("id",id).put("projectId",newProject).put("name",manifest.getJSONObject("project").optString("name","恢复的项目副本")).put("createdAt",System.currentTimeMillis()).put("files",entries).put("ownerArchiveImport",new JSONObject().put("nativeImportId",nativeImportId).put("sectionDigest",sectionDigest).put("originalCheckoutId",originalCheckout).put("manifestIdentity",frozen.identity).put("originalProjectId",source.getString("projectId")).put("executionAuthorityRestored",false));JsonFiles.write(new File(pending,"checkout.json"),value);original.checkpoint();if(closed)throw new SecurityException("原项目宿主已关闭，未发布恢复副本");Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);moved=true;return queryOwnerCheckoutArchiveForHost(newProject,nativeImportId,originalCheckout,sectionDigest,frozen.identity);
        }finally{if(!moved&&pending.exists())deleteOwnPackageStaging(pending);}
    }
    public synchronized JSONObject queryOwnerCheckoutArchiveForHost(String newProject,String nativeImportId,String originalCheckout,String sectionDigest,String manifest)throws Exception {
        packageIdentity(newProject);packageIdentity(nativeImportId);packageIdentity(originalCheckout);packageHash(sectionDigest);packageHash(manifest);String id=ownerArchiveCheckoutId(nativeImportId,originalCheckout);File directory=new File(new File(root,"checkouts"),id),record=new File(directory,"checkout.json");if(!Files.exists(directory.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))return new JSONObject().put("found",false).put("status","unknown").put("checkoutId",id).put("replayAllowed",false);if(Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(record.toPath())||!record.isFile())throw new SecurityException("原恢复副本记录不可用，未重新创建");JSONObject value=JsonFiles.read(record),pin=value.getJSONObject("ownerArchiveImport");if(!newProject.equals(value.getString("projectId"))||!nativeImportId.equals(pin.getString("nativeImportId"))||!sectionDigest.equals(pin.getString("sectionDigest"))||!originalCheckout.equals(pin.getString("originalCheckoutId"))||!manifest.equals(pin.getString("manifestIdentity")))throw new SecurityException("恢复副本不是此原归档动作");return new JSONObject().put("found",true).put("status","found").put("id",id).put("checkoutId",id).put("projectId",newProject).put("name",value.getString("name")).put("restoredTaskOrGrant",false).put("currentBytesRevalidated",false).put("replayAllowed",false);
    }
    private static String ownerArchiveCheckoutId(String action,String old){return UUID.nameUUIDFromBytes(("owner-checkout-restore-v1:"+action+":"+old).getBytes(StandardCharsets.UTF_8)).toString();}
    /** Native project page only. The returned Java value is never accepted by generic feature/model JSON. */
    public synchronized dev.pocketengine.features.local.project.ProjectGit.OwnerRequest reviewGitForOwner(String checkoutId,JSONObject selected)throws Exception {
        if(closed)throw new IllegalStateException("原项目模块已关闭");requireIdle(checkoutId);if(dev.pocketengine.features.local.project.ProjectGit.writes(selected.optString("gitAction")))requireCheckoutWritable(checkoutId);
        JSONObject source=ProjectSources.describe(export(checkoutId)),input=new JSONObject(selected.toString()).put("checkoutId",checkoutId).put("expectedSourceDigest",source.getString("sourceDigest")).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("intent","git");
        JSONObject plan=dev.pocketengine.features.local.project.ProjectGit.create(this,input),step=dev.pocketengine.features.local.project.ProjectGit.step(plan),exact=ProjectDevelopment.exact(plan,step);
        return dev.pocketengine.features.local.project.ProjectGit.ownerRequest(plan,projectCapabilityForHost(exact));
    }
    /** Original owner external task already owns the one approval/budget/journal row. No task or run is created here. */
    public synchronized JSONObject executeGitForOwner(dev.pocketengine.features.local.project.ProjectGit.Confirmed selected,JSONObject nativeBinding,RunControl original,ProjectAdmission admission)throws Exception {
        final RuntimeInvocation invocation;final ProjectRuntime target;
        try {
            selected.requireUnexpired();JSONObject plan=selected.plan(),exact=ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectGit.step(plan));
            if(!projectCapabilityStillCurrent(exact,selected.capability()))throw new SecurityException("Git工具或执行位置已变化，请重新审阅；未换宿主");
            String checkout=exact.getString("checkoutId");requireIdle(checkout);if(dev.pocketengine.features.local.project.ProjectGit.writes(dev.pocketengine.features.local.project.ProjectGit.action(plan)))requireCheckoutWritable(checkout);JSONObject snapshot=export(checkout);
            if(!exact.getString("sourceDigest").equals(ProjectSources.describe(snapshot).getString("sourceDigest")))throw new SecurityException("原Git审阅之后源码已变化；没有派发");
            original.check();admission.check();target=runtime(ManagedProjectRuntime.RUNTIME_ID);
            invocation=new RuntimeInvocation(exact,nativeBinding,snapshot,original,admission,new RuntimeWorkspace(content(checkout).getCanonicalFile(),exact.getString("projectId"),checkout));
        } catch(Exception rejected) {throw dev.pocketengine.features.local.project.ProjectGit.rejectedBeforeRuntime(selected,rejected);}
        // Beyond this one boundary, an exception is uncertain; never label it not-dispatched.
        return target.dispatch(invocation).json();
    }
    /** Rebuild only from the original SQL plan/ref, including after UI navigation; never dispatches. */
    public synchronized JSONObject observeGitForOwner(JSONObject fullPlan,JSONObject originalBinding,boolean cancel)throws Exception {
        JSONObject plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(fullPlan);if(!dev.pocketengine.features.local.project.ProjectGit.plan(plan))throw new SecurityException("不是原本机Git操作");
        JSONObject exact=ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectGit.step(plan)),backend=projectReceiptForHost(exact,originalBinding,cancel);
        return new JSONObject().put("backend",backend).put("git",dev.pocketengine.features.local.project.ProjectGitResult.observe(exact,backend));
    }
    public interface ProjectAdmission extends ProjectRuntime.LiveGate { }
    public synchronized JSONObject projectCapabilityForHost(JSONObject exact)throws Exception {
        if(closed)throw new IllegalStateException("项目执行器已关闭");
        boolean edit=java.util.Set.of("writeFile","patchFiles","freezeProjectPackage","collectProjectOutput").contains(exact.getString("command"));
        String selected=edit?"app_private_checkout":runtimeId(exact);
        JSONObject capability=new JSONObject().put("feature","local").put("runtime",selected).put("command",exact.getString("command")).put("checkoutId",exact.getString("checkoutId")).put("sourceDigest",exact.getString("sourceDigest"))
                .put("requestDigest",exact.getString("requestDigest")).put("nativeRuntimeGeneration",edit?"private_checkout":runtimeGenerations.get(selected)).put("target",edit?new JSONObject().put("projectId",checkout(exact.getString("checkoutId")).getString("projectId")):projectRuntimeForHost(selected)).put("scopeEnforced",false).put("reliableCancellation",selected.equals(ManagedProjectRuntime.RUNTIME_ID));
        if(dev.pocketengine.features.local.project.ProjectGit.exact(exact)){dev.pocketengine.features.local.project.ProjectGit.requireCurrentTools(exact,capability.getJSONObject("target"));capability.put("fixedGit",dev.pocketengine.features.local.project.ProjectGit.semantic(exact.getJSONObject("payload")));}
        if("command".equals(exact.optString("intent"))){
            JSONObject commandSpec=dev.pocketengine.features.local.project.ProjectCommandSpec.fromPayload(exact.getJSONObject("payload"));
            JSONObject target=capability.getJSONObject("target");JSONArray actualTools=target.optJSONArray("tools");boolean registered=false;
            for(int i=0;actualTools!=null&&i<actualTools.length();i++){JSONObject tool=actualTools.getJSONObject(i);if(commandSpec.getString("executable").equals(tool.optString("name"))&&(tool.optBoolean("hashVerified")||tool.optBoolean("probeVerified")))registered=true;}
            if(!registered||"pty".equals(commandSpec.getString("terminalMode"))&&!target.optBoolean("ptyAvailable"))throw new ProjectDevelopment.NotDispatched(new JSONObject().put("status","not_dispatched").put("accepted",false).put("code","project_command_tool_unavailable").put("message","所选工具或交互终端接口未实际准备，请核对当前工具清单；本步骤未派发，不切换到其他宿主"));
            try{File directory=new RuntimeWorkspace(content(exact.getString("checkoutId")).getCanonicalFile(),exact.getString("projectId"),exact.getString("checkoutId")).resolve(commandSpec.getString("workingDirectory"),false);if(!directory.isDirectory())throw new IllegalArgumentException("cwd_missing");}
            catch(IllegalArgumentException|SecurityException invalid){throw new ProjectDevelopment.NotDispatched(new JSONObject().put("status","not_dispatched").put("accepted",false).put("code","project_command_cwd_unavailable").put("message","工作目录不存在或不可安全使用，请核对项目相对cwd；本步骤未派发"));}
        }
        if(selected.equals(ManagedProjectRuntime.RUNTIME_ID)&&"owned_service".equals(exact.getJSONObject("payload").optString("runMode"))){JSONObject owned=new JSONObject().put("lifetime","same_original_host_run").put("stopSameJob",true).put("stopAndDrainOnRunEnd",true).put("blocksCheckoutWrites",true).put("nativeStartIsBusinessCompletion",false).put("serviceReadyVerified",false).put("timeoutMillis",exact.getJSONObject("payload").optLong("timeoutMillis",300000));
            JSONObject commandPayload=exact.getJSONObject("payload");if("pty".equals(commandPayload.optString("terminalMode"))){dev.pocketengine.features.local.project.ProjectCommandSpec.fromPayload(commandPayload);owned.put("interactiveInputExplicitlyIncluded",true).put("interactiveInputScopeDigest",dev.pocketengine.features.local.project.ProjectCommandSpec.inputScopeDigest(commandPayload)).put("inputByteLimit",commandPayload.getLong("inputByteLimit")).put("inputChunkByteLimit",dev.pocketengine.features.local.project.ProjectCommandSpec.MAX_INPUT_CHUNK).put("inputRequestLimit",128).put("terminalOutputMerged",true).put("eofSemantics","canonical_VEOF_not_pipe_half_close").put("cleanupScope","owned_session_and_observed_descendants").put("allDescendantsSandboxed",false).put("secretInputSupported",false);}
            JSONObject policy=dev.pocketengine.features.local.project.ProjectLiveEditPolicy.fromExact(exact);if(policy!=null){owned.put("liveEditPolicyDigest",policy.getString("policyDigest")).put("liveEditPolicy",policy).put("editablePaths",dev.pocketengine.features.local.project.ProjectLiveEditPolicy.selectedPaths(policy)).put("subsequentSourceExecution",true).put("configurationChangesAllowed",false).put("blocksCheckoutWrites",true).put("permitsOnlyApprovedSourceCas",true).put("blocksUnapprovedCheckoutWrites",true).put("createsFiles",false).put("notice","批准启动也允许本run按原SHA修改列明的既有源码，Vite会随即执行这些源码；配置、锁、脚本与环境不在此批准内。结束原任务必须停止并排空原服务。");}capability.put("ownedService",owned);}
        if(exact.getString("command").equals("freezeProjectPackage"))capability.put("packageSnapshot",new JSONObject(exact.getJSONObject("payload").toString())).put("nativeFileOperation",true).put("publishesImmutableArtifact",true).put("writesCheckout",false).put("automaticallyExported",false);
        if(exact.getString("command").equals("collectProjectOutput")){JSONObject generated=dev.pocketengine.features.local.project.ProjectManagedOutput.verified(exact.getJSONObject("payload").getJSONObject("artifactSelection"));capability.put("generatedFile",dev.pocketengine.features.local.project.ProjectManagedOutput.summary(generated)).put("nativeFileOperation",true).put("publishesImmutableArtifact",true).put("writesCheckout",false).put("automaticallyExported",false);}
        if(edit&&exact.getJSONObject("payload").has("liveEditServiceExecutionId"))capability.put("liveEditServiceExecutionId",exact.getJSONObject("payload").getString("liveEditServiceExecutionId"));
        return capability;
    }
    /** Native original-operation port. Raw feature requests cannot supply its retained authority. */
    public synchronized JSONObject executeProjectForHost(JSONObject exact,JSONObject binding,RunControl original,ProjectAdmission admission)throws Exception {
        return executeProjectForHost(exact,binding,original,admission,null);
    }
    public synchronized JSONObject executeProjectForHost(JSONObject exact,JSONObject binding,RunControl original,ProjectAdmission admission,RuntimeExecutionRef originalService)throws Exception {
        if(closed||original==null||admission==null)throw new SecurityException("缺少项目原执行控制");
        JSONObject request=new JSONObject(exact.toString()),nativeBinding=new JSONObject(binding.toString()),payload=new JSONObject(request.getJSONObject("payload").toString());
        String command=request.getString("command"),checkout=JsonFiles.text(payload,"checkoutId",80);
        if(!"local".equals(request.optString("feature"))||!command.matches("writeFile|patchFiles|freezeProjectPackage|collectProjectOutput|(?:termux|project\\.runtime)\\.(probe|prepare|run|collect)")||!checkout.equals(request.getString("checkoutId"))||!ProjectSources.digest(payload).equals(request.getString("requestDigest")))throw new SecurityException("不是已核对的项目命令");
        JSONObject link=new JSONObject(nativeBinding.toString()).put("authority","native_host_operation").put("command",command);
        for(String key:new String[]{"planDigest","stepId","intent","sourceDigest","requestDigest"})link.put(key,request.get(key));
        long pauseRevision=original.pauseRevision();if(command.equals("collectProjectOutput")){link.put("projectId",request.getString("projectId")).put("checkoutId",checkout);return executeManagedOutput(request,link,payload,original,admission,nativeBinding,pauseRevision);}if(command.equals("freezeProjectPackage"))return executeProjectPackage(request,link,payload,original,admission,nativeBinding,pauseRevision);checkProjectControl(original,admission,nativeBinding,pauseRevision);
        JSONObject snapshot=export(checkout);if(!request.getString("projectId").equals(snapshot.getJSONObject("checkout").getString("projectId"))||!request.getString("sourceDigest").equals(ProjectSources.describe(snapshot).getString("sourceDigest")))throw new SecurityException("项目归属或源码已变化，未派发旧方案");
        if(command.equals("patchFiles"))return applyProjectPatch(request,link,payload,snapshot,original,admission,nativeBinding,pauseRevision);
        if(command.equals("writeFile")){
            if(payload.has("liveEditServiceExecutionId"))return writeLiveProjectFile(request,link,payload,snapshot,original,admission,nativeBinding,pauseRevision,originalService);
            checkProjectControl(original,admission,nativeBinding,pauseRevision);JSONObject actual=write(payload);actual.remove("content");String next=ProjectSources.describe(export(checkout)).getString("sourceDigest");
            return actual.put("id",nativeBinding.getString("operationId")).put("operationId",nativeBinding.getString("operationId")).put("kind","local_edit").put("projectExecution",link)
                    .put("status","completed").put("receiptReceived",true).put("exitCode",0).put("sourceDigest",request.getString("sourceDigest")).put("updatedSourceDigest",next).put("sourceCheckStatus","native_checkout_verified").put("businessVerified",false);
        }
        if(command.startsWith("project.runtime."))requireCheckoutWritable(checkout);
        if(command.equals("termux.run")&&commands.busy(checkout))throw new IllegalStateException("本机命令尚未结束");
        checkProjectControl(original,admission,nativeBinding,pauseRevision);
        ProjectRuntime selected=runtime(runtimeId(request));return selected.dispatch(new RuntimeInvocation(request,nativeBinding,snapshot,original,admission,new RuntimeWorkspace(content(checkout).getCanonicalFile(),request.getString("projectId"),checkout))).json();
    }
    /** Native producer for one exact managed build result, not a generic raw-file model tool. */
    public synchronized JSONObject managedOutputSelectionForHost(JSONObject originalBuild,String path,RunControl original,dev.pocketengine.features.capabilities.ModelCapabilityAdapters.Binding currentAdmission)throws Exception {
        if(closed)throw new IllegalStateException("原项目模块已关闭");JSONObject plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(originalBuild.getJSONObject("plan")),binding=originalBuild.getJSONObject("binding");
        if(!original.matchesHostRun(currentAdmission.taskId,currentAdmission.runId,currentAdmission.epoch)||!currentAdmission.taskId.equals(binding.getString("taskId"))||!currentAdmission.projectId.equals(plan.getString("projectId"))||!"build".equals(originalBuild.getString("stepId")))throw new SecurityException("请选择本任务原build执行；当前read scope不能替换");
        original.check();development.handle("project.poll",ProjectSources.copy(originalBuild));String checkout=plan.getString("checkoutId");requireIdle(checkout);requireCheckoutWritable(checkout);ManagedProjectRuntime runtime=ownedServiceRuntimeForHost(ManagedProjectRuntime.RUNTIME_ID);if(!plan.getString("runtimeIdentity").equals(runtime.identity()))throw new SecurityException("原构建工具环境已变化");
        JSONObject exact=ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,"build")),actual=runtime.query(RuntimeExecutionRef.fromHost(exact,binding)).json();
        JSONObject selected=dev.pocketengine.features.local.project.ProjectManagedOutput.prepare(new RuntimeWorkspace(content(checkout).getCanonicalFile(),plan.getString("projectId"),checkout),export(checkout),plan,binding,actual,runtime.currentCheckoutSequence(checkout),path,original);requireManagedOutputCurrent(selected,original);return selected;
    }
    private JSONObject requireManagedOutputCurrent(JSONObject supplied,RunControl original)throws Exception {
        JSONObject selected=dev.pocketengine.features.local.project.ProjectManagedOutput.verified(supplied),plan=selected.getJSONObject("buildPlan");String checkout=selected.getString("checkoutId");requireIdle(checkout);requireCheckoutWritable(checkout);original.check();
        JSONObject current=ProjectSources.describe(export(checkout));if(!selected.getString("projectId").equals(current.getString("projectId"))||!selected.getString("sourceDigest").equals(current.getString("sourceDigest")))throw new SecurityException("原build源码已变化");ManagedProjectRuntime runtime=ownedServiceRuntimeForHost(ManagedProjectRuntime.RUNTIME_ID);if(!selected.getString("runtimeIdentity").equals(runtime.identity()))throw new SecurityException("原build工具环境已变化");
        JSONObject exact=ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,"build")),actual=runtime.query(RuntimeExecutionRef.fromHost(exact,selected.getJSONObject("buildBinding"))).json();dev.pocketengine.features.local.project.ProjectManagedOutput.requireActual(selected,actual,runtime.currentCheckoutSequence(checkout));return selected;
    }
    private JSONObject executeManagedOutput(JSONObject request,JSONObject link,JSONObject payload,RunControl original,ProjectAdmission admission,JSONObject binding,long pauseRevision)throws Exception {
        JSONObject selection=dev.pocketengine.features.local.project.ProjectManagedOutput.verified(payload.getJSONObject("artifactSelection"));dev.pocketengine.features.local.project.ProjectManagedOutput.requireCollection(selection,link);
        var book=dev.pocketengine.features.attachments.AttachmentRepository.get(context.android).book;JSONObject prior=dev.pocketengine.features.local.project.ProjectManagedOutput.observe(book,selection,link,original);if("completed".equals(prior.optString("status")))return prior;
        java.util.concurrent.atomic.AtomicBoolean entered=new java.util.concurrent.atomic.AtomicBoolean();try{
            checkProjectControl(original,admission,binding,pauseRevision);requireManagedOutputCurrent(selection,original);RuntimeWorkspace workspace=new RuntimeWorkspace(content(selection.getString("checkoutId")).getCanonicalFile(),selection.getString("projectId"),selection.getString("checkoutId"));
            return dev.pocketengine.features.local.project.ProjectManagedOutput.publish(book,workspace,selection,link,original,()->{try{checkProjectControl(original,admission,binding,pauseRevision);requireManagedOutputCurrent(selection,original);entered.set(true);}catch(Exception rejected){throw new IllegalStateException("生成文件发布前原批准或构建版本已变化",rejected);}});
        }catch(Exception failure){if(!entered.get())return dev.pocketengine.features.local.project.ProjectManagedOutput.failedBeforePublication(selection,link,failure);throw failure;}
    }
    private JSONObject queryManagedOutputReceipt(JSONObject exact,JSONObject binding)throws Exception {
        JSONObject link=new JSONObject(binding.toString()).put("authority","native_host_operation").put("command",exact.getString("command"));for(String key:new String[]{"planDigest","stepId","intent","sourceDigest","requestDigest","projectId","checkoutId"})link.put(key,exact.get(key));
        return dev.pocketengine.features.local.project.ProjectManagedOutput.observe(dev.pocketengine.features.attachments.AttachmentRepository.get(context.android).book,exact.getJSONObject("payload").getJSONObject("artifactSelection"),link,new RunControl());
    }
    /** Original SQL request only; no arbitrary attachment ID or latest-file fallback. */
    public synchronized JSONObject managedOutputForHost(JSONObject originalCollection,RunControl original,dev.pocketengine.features.capabilities.ModelCapabilityAdapters.Binding currentAdmission)throws Exception {
        original.check();if(!original.matchesHostRun(currentAdmission.taskId,currentAdmission.runId,currentAdmission.epoch)||!currentAdmission.taskId.equals(originalCollection.getJSONObject("binding").getString("taskId")))throw new SecurityException("生成文件读取不是原任务当前scope");JSONObject plan=dev.pocketengine.features.local.project.ProjectBuildPlan.verified(originalCollection.getJSONObject("plan")),step=dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,originalCollection.getString("stepId"));if(!"collectProjectOutput".equals(step.getString("localCommand")))throw new SecurityException("请选择原managed生成文件保存动作");
        development.handle("project.poll",ProjectSources.copy(originalCollection));JSONObject actual=queryManagedOutputReceipt(ProjectDevelopment.exact(plan,step),originalCollection.getJSONObject("binding"));if(!"completed".equals(actual.optString("status")))throw new IllegalStateException("原生成文件保存尚未确认，只核对原动作");original.check();return new JSONObject(actual.getJSONObject("attachment").toString()).put("ok",true).put("accepted",true).put("status","observed").put("code","project_generated_output_observed").put("generatedArtifact",true).put("buildReceiptIdentity",actual.getString("buildReceiptIdentity")).put("buildJobId",actual.getString("buildJobId")).put("collectionJobId",actual.getString("operationId")).put("originalPath",actual.getString("path")).put("buildOutputAttribution","selected_output_observed_after_build");
    }
    /** One original journal operation; preflight all files, then report each real per-file effect. */
    private JSONObject applyProjectPatch(JSONObject request,JSONObject link,JSONObject payload,JSONObject snapshot,RunControl original,ProjectAdmission admission,JSONObject binding,long pauseRevision)throws Exception {
        String checkout=request.getString("checkoutId");JSONObject result=new JSONObject().put("id",binding.getString("operationId")).put("operationId",binding.getString("operationId")).put("checkoutId",checkout).put("kind","local_patch").put("provider","app_private_checkout").put("projectExecution",link).put("sourceDigest",request.getString("sourceDigest")).put("sourceCheckStatus","native_checkout_verified").put("crossFileAtomic",false).put("businessVerified",false).put("replayAllowed",false);
        JSONArray files=payload.getJSONArray("files"),receipts=new JSONArray();java.util.Map<String,byte[]> before=ProjectSources.byteFiles(snapshot);java.util.Set<String> unique=new java.util.HashSet<>();
        if(files.length()<1||files.length()>dev.pocketengine.features.local.project.ProjectExactPatch.MAX_FILES)throw new SecurityException("原补丁文件数无效");
        boolean uncertain=false;int applied=0;String failure="";
        try {
            requireIdle(checkout);requireCheckoutWritable(checkout);
            // No file/parent mutation before every original byte/hash/target is checked.
            long total=0;for(byte[] value:before.values())total+=value.length;int count=before.size();
            for(int i=0;i<files.length();i++){
                checkProjectControl(original,admission,binding,pauseRevision);JSONObject file=files.getJSONObject(i);String path=VersionedWorkspace.path(file.getString("path")),action=file.getString("action");if(!unique.add(path)||!ProjectSources.sourcePath(path)||!java.util.Set.of("replace","create","delete").contains(action))throw new SecurityException("原补丁路径或动作不合法");
                File target=NativeCommandExecutor.within(content(checkout),path);boolean exists=before.containsKey(path);String expected=exists?hash(before.get(path)):"absent";byte[] old=dev.pocketengine.features.local.project.ProjectExactPatch.encode(file.getString("beforeContent")),bytes=dev.pocketengine.features.local.project.ProjectExactPatch.encode(file.getString("content"));
                if(!expected.equals(file.getString("expectedHash"))||exists&&!java.util.Arrays.equals(old,before.get(path))||!exists&&old.length!=0||action.equals("create")==exists||bytes.length>262144||old.length>262144||!(action.equals("delete")?"absent":hash(bytes)).equals(file.getString("afterHash"))||action.equals("delete")&&bytes.length!=0)throw new SecurityException("原补丁完整正文/前后SHA与真实文件不同："+path);
                if(target.exists()&&!target.isFile())throw new SecurityException("补丁目标不是普通文件："+path);String disk=target.exists()?hash(dev.pocketengine.features.local.runtime.WorkspaceFileAccess.read(content(checkout),path,ProjectSources.MAX_FILE_BYTES)):"absent";if(!expected.equals(disk))throw new SecurityException("补丁预检时文件SHA已变化："+path);
                total-=exists?before.get(path).length:0;total+=action.equals("delete")?0:bytes.length;if(action.equals("delete"))count--;else if(!exists)count++;
                receipts.put(new JSONObject().put("path",path).put("action",action).put("beforeHash",expected).put("afterHash",file.getString("afterHash")).put("state","skipped").put("commitAttempted",false));
            }
            if(total>ProjectSources.MAX_TOTAL_BYTES||count>ProjectSources.MAX_FILES)throw new IllegalArgumentException("原补丁结果超过源码范围");
            for(int i=0;i<files.length();i++){
                JSONObject file=files.getJSONObject(i),receipt=receipts.getJSONObject(i);String path=file.getString("path"),action=file.getString("action");File target=NativeCommandExecutor.within(content(checkout),path);
                try {
                    checkProjectControl(original,admission,binding,pauseRevision);
                    receipt.put("commitAttempted",true).put("state","unknown");
                    dev.pocketengine.features.local.runtime.WorkspaceFileAccess.commit(content(checkout),path,
                            action.equals("create")?null:dev.pocketengine.features.local.project.ProjectExactPatch.encode(file.getString("beforeContent")),
                            action.equals("delete")?null:dev.pocketengine.features.local.project.ProjectExactPatch.encode(file.getString("content")));
                    String after=Files.exists(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)?hash(dev.pocketengine.features.local.runtime.WorkspaceFileAccess.read(content(checkout),path,ProjectSources.MAX_FILE_BYTES)):"absent";if(!file.getString("afterHash").equals(after))throw new SecurityException("提交后未取得原补丁afterSHA："+path);
                    receipt.put("state","applied").put("actualAfterHash",after);applied++;
                }catch(Exception failed){uncertain=receipt.optBoolean("commitAttempted")&&!receipt.optString("state").equals("applied");receipt.put("state",uncertain?"unknown":"failed").put("message",String.valueOf(failed.getMessage()));failure=String.valueOf(failed.getMessage());break;}
            }
        }catch(Exception failed){failure=String.valueOf(failed.getMessage());}
        for(int i=receipts.length();i<files.length();i++)receipts.put(new JSONObject().put("path",files.getJSONObject(i).getString("path")).put("state","skipped").put("commitAttempted",false));
        boolean complete=applied==files.length();result.put("files",receipts).put("appliedFiles",applied).put("allFilesApplied",complete).put("status",uncertain?"unknown":complete?"completed":"failed").put("receiptReceived",!uncertain).put("exitCode",uncertain?JSONObject.NULL:complete?0:1).put("stderr",failure).put("partialEffects",applied>0&&!complete).put("message",uncertain?"原文件提交效果待核；只读原路径/原操作，不自动重放补丁":complete?"逐文件原子保存并核对afterSHA完成；不是跨文件事务，也不是测试/业务成功":"原补丁未全部应用，按逐文件回执核对，未覆盖后续新SHA");
        try{result.put("updatedSourceDigest",ProjectSources.describe(export(checkout)).getString("sourceDigest"));}catch(Exception unavailable){result.put("updatedSourceDigestUnavailable",true);if(applied>0)result.put("status","unknown").put("receiptReceived",false).put("exitCode",JSONObject.NULL);}
        return result;
    }
    public synchronized JSONObject projectFileQueryForHost(JSONObject input,RunControl original,boolean search)throws Exception {
        if(closed||original==null)throw new SecurityException("缺少原项目只读控制");String id=JsonFiles.text(input,"checkoutId",80);dev.pocketengine.features.local.project.ProjectFileQueries.Current current=()->{original.check();if(original.isPaused())throw new SecurityException("原项目读取已暂停");};
        return search?dev.pocketengine.features.local.project.ProjectFileQueries.search(content(id),id,input,current):dev.pocketengine.features.local.project.ProjectFileQueries.read(content(id),id,input,current);
    }
    private static JSONObject packageReceipt(JSONObject exact,JSONObject link)throws Exception {
        return new JSONObject().put("id",link.getString("operationId")).put("operationId",link.getString("operationId")).put("checkoutId",exact.getString("checkoutId")).put("projectExecution",new JSONObject(link.toString())).put("kind","project_package_freeze").put("provider","app_private_checkout").put("nativeFileOperation",true).put("processExitCode",JSONObject.NULL).put("sourceDigest",exact.getString("sourceDigest")).put("businessVerified",false).put("automaticallyExported",false).put("replayAllowed",false).put("cancelRequested",link.optBoolean("cancelRequested"));
    }
    private JSONObject completedPackageReceipt(JSONObject exact,JSONObject link,JSONObject artifact)throws Exception {
        JSONObject result=packageReceipt(exact,link).put("nativePublicationEntered",true).put("nativeOperationCode",0).put("exitCode",0).put("status","completed").put("receiptReceived",true).put("sourceCheckStatus","native_checkout_verified").put("artifactReady",true).put("packageArtifact",dev.pocketengine.features.local.project.ProjectPackageDelivery.metadata(artifact)).put("message","已取得本次原操作的固定文件成果；没有导出、分享、运行或覆盖原项目。");
        if(!dev.pocketengine.features.local.project.ProjectPackageDelivery.terminalReceipt(exact,result))throw new SecurityException("原固定成果与批准的清单/字节不同");return result;
    }
    private JSONObject executeProjectPackage(JSONObject exact,JSONObject link,JSONObject payload,RunControl original,ProjectAdmission admission,JSONObject binding,long pauseRevision)throws Exception {
        JSONObject result=packageReceipt(exact,link);boolean publicationEntered=false;
        try {
            checkProjectControl(original,admission,binding,pauseRevision);requireCheckoutWritable(exact.getString("checkoutId"));requireIdle(exact.getString("checkoutId"));
            var frozen=capturePackage(payload);JSONObject source=frozen.manifest().getJSONObject("source"),archive=dev.pocketengine.features.local.project.ProjectPackageDelivery.archiveIdentity(frozen);
            if(!exact.getString("projectId").equals(source.getString("projectId"))||!exact.getString("sourceDigest").equals(source.getString("sourceDigest"))||!payload.getString("expectedManifestIdentity").equals(frozen.identity)||!payload.getString("expectedArchiveSha256").equals(archive.getString("archiveSha256"))||payload.getLong("expectedArchiveBytes")!=archive.getLong("archiveBytes"))throw new SecurityException("所选实际文件、包清单或压缩字节已变化，请重新审阅；没有保存新成果");
            checkProjectControl(original,admission,binding,pauseRevision);requireCheckoutWritable(exact.getString("checkoutId"));
            publicationEntered=true;JSONObject artifact=packageWorkspace().createProjectPackage(frozen,dev.pocketengine.features.local.project.ProjectPackageDelivery.actionId(binding.getString("operationId")));
            return completedPackageReceipt(exact,link,artifact);
        }catch(Exception failed){String message=failed.getMessage()==null?"原项目包没有确定保存回执":failed.getMessage();return result.put("nativePublicationEntered",publicationEntered).put("nativeOperationCode",publicationEntered?JSONObject.NULL:1).put("exitCode",publicationEntered?JSONObject.NULL:1).put("status",publicationEntered?"unknown":"failed").put("receiptReceived",!publicationEntered).put("effectStatus",publicationEntered?"unknown":"not_dispatched").put("artifactReady",false).put("errorType",failed.getClass().getName()).put("stderr",message.substring(0,Math.min(1600,message.length()))).put("message",publicationEntered?"已进入原成果发布边界，结果待核。只查询原操作，不再次保存。":"本次保存尚未进入成果发布；可以读取最新文件后明确重新审阅，不会自动重试。");}
    }
    /** Original catalog lookup only: never calls create again after an uncertain publication. */
    private JSONObject queryProjectPackageReceipt(JSONObject exact,JSONObject binding)throws Exception {
        JSONObject payload=exact.getJSONObject("payload"),link=new JSONObject(binding.toString()),result=packageReceipt(exact,link);JSONObject artifact=packageWorkspace().findProjectPackage(dev.pocketengine.features.local.project.ProjectPackageDelivery.actionId(binding.getString("operationId")),exact.getString("projectId"),exact.getString("checkoutId"),payload.getString("expectedManifestIdentity"));
        if(artifact.optBoolean("found"))return completedPackageReceipt(exact,link,artifact).put("reconciledFromOriginalCatalog",true);
        return result.put("status","unknown").put("receiptReceived",false).put("exitCode",JSONObject.NULL).put("nativeOperationCode",JSONObject.NULL).put("nativePublicationEntered",true).put("artifactReady",false).put("message","原成果目录尚无本次操作的可核对固定包；没有重新保存，也不把未找到视为未派发。");
    }
    /** Original consumed service approval + real retained job lease + actual file SHA.
     * Raw writeFile and generic executeProjectForHost never receive this typed lease. */
    private JSONObject writeLiveProjectFile(JSONObject request,JSONObject link,JSONObject payload,JSONObject snapshot,RunControl original,ProjectAdmission admission,JSONObject binding,long pauseRevision,RuntimeExecutionRef service)throws Exception {
        if(service==null||!service.operationId().equals(payload.getString("liveEditServiceExecutionId"))||!service.operationId().equals(binding.optString("liveEditServiceExecutionId"))||!service.projectId().equals(request.getString("projectId"))||!service.checkoutId().equals(request.getString("checkoutId")))throw new SecurityException("热改没有原本机服务引用，普通文件API不能借用运行租约");
        for(String key:new String[]{"liveEditServiceExecutionId","liveEditPolicyDigest"})link.put(key,binding.get(key));
        JSONObject result=new JSONObject().put("id",binding.getString("operationId")).put("operationId",binding.getString("operationId")).put("checkoutId",request.getString("checkoutId")).put("kind","local_edit").put("provider","app_private_checkout").put("projectExecution",link).put("sourceDigest",request.getString("sourceDigest")).put("sourceCheckStatus","native_checkout_verified").put("businessVerified",false).put("replayAllowed",false);
        boolean commitAttempted=false;File pending=null;
        try(var lease=ownedServiceRuntimeForHost(ManagedProjectRuntime.RUNTIME_ID).beginLiveEdit(service,original)){
            JSONObject policy=lease.policy();if(!policy.getString("policyDigest").equals(binding.getString("liveEditPolicyDigest")))throw new SecurityException("热改原启动批准已变化");String path=payload.getString("path"),text=payload.getString("content");
            dev.pocketengine.features.local.project.ProjectLiveEditPolicy.requireEdit(policy,snapshot,path,payload.getString("expectedHash"),text);checkProjectControl(original,admission,binding,pauseRevision);lease.check();
            File target=NativeCommandExecutor.within(content(request.getString("checkoutId")),path);if(!target.isFile()||Files.isSymbolicLink(target.toPath()))throw new SecurityException("原热改文件已失效，未创建新文件");
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);pending=File.createTempFile("pending-",".tmp",target.getParentFile());try(FileOutputStream output=new FileOutputStream(pending)){output.write(bytes);output.getFD().sync();}
            // Readers see a whole source file, never an editor's truncate/write interval.
            checkProjectControl(original,admission,binding,pauseRevision);lease.check();if(!payload.getString("expectedHash").equals(hash(dev.pocketengine.features.local.runtime.WorkspaceFileAccess.read(content(checkout),path,ProjectSources.MAX_FILE_BYTES))))throw new SecurityException("提交前文件SHA已变化，请重新读取；草稿未覆盖");
            commitAttempted=true;Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);pending=null;
            JSONObject after=export(request.getString("checkoutId")),edit=lease.record(after,path,hash(bytes));
            return result.put("path",path).put("sha256",hash(bytes)).put("bytes",bytes.length).put("updatedSourceDigest",ProjectSources.describe(after).getString("sourceDigest")).put("liveEdit",edit).put("status","completed").put("receiptReceived",true).put("exitCode",0).put("message","原文件原子保存已回读；开发服务器将自行处理热更新，保存回执不等于浏览器已应用或业务成功");
        }catch(Exception failure){return result.put("status",commitAttempted?"unknown":"failed").put("receiptReceived",!commitAttempted).put("exitCode",commitAttempted?JSONObject.NULL:1).put("fileCommitAttempted",commitAttempted).put("errorType",failure.getClass().getName()).put("stderr",failure.getMessage()==null?"原热改未取得确定回执":failure.getMessage()).put("message",commitAttempted?"原文件提交结果待核，只读原文件，不自动重放；原服务必须核对排空":"本次热改尚未提交，原文件未覆盖；读取当前SHA并明确修正后可重新生成方案");}
        finally{if(pending!=null)Files.deleteIfExists(pending.toPath());}
    }
    private static void checkProjectControl(RunControl original,ProjectAdmission admission,JSONObject binding,long pauseRevision)throws Exception {
        original.check();if(!original.hasHostRun()||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"))||original.isPaused()||original.pauseRevision()!=pauseRevision)throw new SecurityException("原项目执行控制已停止或暂停");admission.check();original.check();
    }
    /** Native receipt lookup uses the same runtime and its original immutable reference. */
    /** Native editor read: immutable current files plus this private unsaved buffer; never executes user code. */
    public synchronized dev.pocketengine.features.local.language.ProjectLanguageSnapshot languageSnapshotForOwner(String checkoutId,String selectedPath,String originalEditorText,long draftRevision)throws Exception {
        if(closed)throw new IllegalStateException("原项目已关闭");JSONObject before=export(checkoutId);var snapshot=dev.pocketengine.features.local.language.ProjectLanguageSnapshot.fromNativeExport(before,selectedPath,originalEditorText,draftRevision);String after=ProjectSources.describe(export(checkoutId)).getString("sourceDigest");if(!snapshot.sourceDigest.equals(after))throw new IllegalStateException("读取语言快照期间源文件已变化，请重新分析；没有修改文件");return snapshot;
    }
    public synchronized void requireLanguageSnapshotForOwner(dev.pocketengine.features.local.language.ProjectLanguageSnapshot original)throws Exception {
        if(closed||original==null)throw new IllegalStateException("原项目语言快照已关闭");JSONObject actual=export(original.checkoutId);if(!original.projectId.equals(actual.getJSONObject("checkout").getString("projectId"))||!original.sourceDigest.equals(ProjectSources.describe(actual).getString("sourceDigest")))throw new SecurityException("语言预览后实际源文件已变化，请重新分析；草稿未覆盖");
    }
    public synchronized JSONObject projectReceiptForHost(JSONObject exact,JSONObject binding,boolean cancel)throws Exception {
        if("freezeProjectPackage".equals(exact.optString("command")))return queryProjectPackageReceipt(exact,binding);
        if("collectProjectOutput".equals(exact.optString("command")))return queryManagedOutputReceipt(exact,binding);
        RuntimeExecutionRef reference=RuntimeExecutionRef.fromHost(exact,binding);ProjectRuntime selected=runtime(runtimeId(exact));return (cancel?selected.requestCancel(reference):selected.query(reference)).json();
    }
    public synchronized JSONObject projectLogForHost(JSONObject exact,JSONObject binding,long cursor,int maxBytes)throws Exception {
        RuntimeExecutionRef reference=RuntimeExecutionRef.fromHost(exact,binding);return runtime(runtimeId(exact)).readLog(reference,cursor,maxBytes);
    }
    @Override public synchronized JSONObject handle(String command,JSONObject input)throws Exception {
        if(closed)throw new IllegalStateException("本地开发模块已关闭");if(input==null)input=new JSONObject();
        if(command.equals("project.preview"))return preview(input);
        if(command.equals("project.package.plan"))return projectPackagePlan(input);
        if(command.equals("project.package.freeze"))return freezeProjectPackage(input);
        if(command.equals("project.package.get"))return packageWorkspace().projectPackageMetadata(input.getString("artifactId"),input.getString("sha256"));
        if(command.equals("project.package.preview"))return previewProjectPackage(input);
        if(command.equals("project.package.find")){String checkout=JsonFiles.text(input,"checkoutId",80);return packageWorkspace().findProjectPackage(input.getString("clientSnapshotId"),checkout(checkout).getString("projectId"),checkout,input.getString("expectedManifestIdentity"));}
        if(command.equals("project.runtime.describe"))return projectRuntimeForHost(input.getString("runtimeId"));
        if(command.startsWith("project."))return development.handle(command,input);
        switch(command){
            case "probe":return new JSONObject().put("tools",commands.tools()).put("termux",termux.status());
            case "prepare":return prepare(JsonFiles.text(input,"projectId",80));
            case "listJobs":return new JSONObject().put("jobs",commands.list()).put("termuxJobs",termux.list());
            case "getJob":return input.optBoolean("termux")?termux.get(JsonFiles.text(input,"jobId",80)):commands.get(JsonFiles.text(input,"jobId",80));
            case "cancelJob":return input.optBoolean("termux")?termux.cancel(JsonFiles.text(input,"jobId",80)):commands.cancel(JsonFiles.text(input,"jobId",80));
            case "run":{
                String id=JsonFiles.text(input,"checkoutId",80);requireIdle(id);
                return commands.submit(id,content(id),input.getJSONArray("argv"),input.optString("operationId",UUID.randomUUID().toString()),input.optInt("timeoutSeconds",30));
            }
            case "termux.setEnabled":return termux.setEnabled(input.getBoolean("enabled"));
            case "termux.probe":if(input.has("checkoutId"))content(JsonFiles.text(input,"checkoutId",80));return termux.probe(input);
            case "termux.prepare":return termux.stage(export(JsonFiles.text(input,"checkoutId",80)),input);
            case "termux.run":{String id=JsonFiles.text(input,"checkoutId",80);content(id);if(commands.busy(id))throw new IllegalStateException("本机命令尚未结束");if(input.has("expectedSourceDigest")&&!input.getString("expectedSourceDigest").equals(ProjectSources.describe(export(id)).getString("sourceDigest")))throw new SecurityException("计划审阅后源码已变化，未派发构建");return termux.run(input);}
            case "termux.collect":{String id=JsonFiles.text(input,"checkoutId",80);requireIdle(id);if(input.has("artifactSelection")&&!input.optString("expectedSourceDigest").equals(ProjectSources.describe(export(id)).getString("sourceDigest")))throw new SecurityException("源码已变化，未收集旧build产物");return termux.collect(checkout(id),input);}
            case "termux.materialize":return materialize(input);
            case "termux.get":return termux.get(JsonFiles.text(input,"jobId",80));
            case "termux.cancel":return termux.cancel(JsonFiles.text(input,"jobId",80));
            case "readFile":return read(JsonFiles.text(input,"checkoutId",80),JsonFiles.text(input,"path",240));
            case "writeFile":return write(input);
            case "deleteFile":return delete(input);
            case "changes":return changes(JsonFiles.text(input,"checkoutId",80));
            case "reviewFile":return review(JsonFiles.text(input,"checkoutId",80),JsonFiles.text(input,"path",240));
            case "importToProject":return importToProject(input);
            case "export":return export(JsonFiles.text(input,"checkoutId",80));
            default:throw new IllegalArgumentException("未知本地开发操作");
        }
    }
    private JSONObject prepare(String projectId)throws Exception {
        var snapshot=packageWorkspace().projectSnapshotForHost(projectId);JSONArray entries=new JSONArray();java.util.Map<String,byte[]> files=new java.util.LinkedHashMap<>();
        for(var file:snapshot.files()){entries.put(new JSONObject().put("path",file.path).put("sourceVersion",file.version).put("sha256",file.sha256));files.put(file.path,file.bytes());}
        JSONObject checkout=new JSONObject().put("id",UUID.randomUUID().toString()).put("projectId",projectId).put("name",snapshot.definition().optString("name")).put("files",entries).put("createdAt",System.currentTimeMillis());
        createCheckoutBytes(checkout,files);return checkout;
    }
    private void createCheckoutBytes(JSONObject checkout,java.util.Map<String,byte[]> files)throws Exception {
        File checkouts=new File(root,"checkouts");File[] saved=checkouts.listFiles();if(saved!=null&&saved.length>=40)throw new IllegalStateException("最多保留40个本地工作副本，请先导出整理");
        File directory=new File(new File(checkouts,checkout.getString("id")),"files");if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("无法创建本地项目工作副本");
        for(var file:files.entrySet()){File target=NativeCommandExecutor.within(directory,VersionedWorkspace.path(file.getKey()));if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IllegalStateException("无法创建项目文件目录");atomic(target,file.getValue());}
        JsonFiles.write(new File(directory.getParentFile(),"checkout.json"),checkout);
    }
    private void createCheckout(JSONObject checkout,JSONArray files)throws Exception {
        File checkouts=new File(root,"checkouts");File[] saved=checkouts.listFiles();if(saved!=null&&saved.length>=40)throw new IllegalStateException("最多保留40个本地工作副本，请先导出整理");
        File directory=new File(new File(checkouts,checkout.getString("id")),"files");if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("无法创建本地项目工作副本");
        for(int i=0;i<files.length();i++){
            JSONObject file=files.getJSONObject(i);File target=NativeCommandExecutor.within(directory,VersionedWorkspace.path(file.getString("path")));
            if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IllegalStateException("无法创建项目文件目录");atomic(target,file.getString("content").getBytes(StandardCharsets.UTF_8));
        }
        // Metadata is committed last: partially copied files are never offered as a ready checkout.
        JsonFiles.write(new File(directory.getParentFile(),"checkout.json"),checkout);
    }
    private File content(String id)throws Exception {
        if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("本地工作副本编号无效");
        File checkout=new File(new File(root,"checkouts"),id);if(!new File(checkout,"checkout.json").isFile())throw new IllegalArgumentException("本地工作副本不存在");return new File(checkout,"files");
    }
    private JSONObject checkout(String id)throws Exception{return JsonFiles.read(new File(content(id).getParentFile(),"checkout.json"));}
    private void requireIdle(String id)throws Exception {
        content(id);if(commands.busy(id)||termux.busy(id))throw new IllegalStateException("工作副本正在执行或Termux结果不明，请先停止并核对原任务");
    }
    private JSONObject read(String id,String path)throws Exception {
        File target=NativeCommandExecutor.within(content(id),VersionedWorkspace.path(path));if(!target.isFile()||target.length()>ProjectSources.MAX_FILE_BYTES)throw new IllegalArgumentException("本地文件不存在或超过2MiB");
        byte[] bytes=Files.readAllBytes(target.toPath());if(bytes.length>ProjectSources.MAX_FILE_BYTES)throw new IllegalArgumentException("本地文件超过2MiB");
        String text;try{text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(text.indexOf('\0')>=0)throw new java.nio.charset.CharacterCodingException();}catch(java.nio.charset.CharacterCodingException binary){throw new IllegalArgumentException("此文件不是可编辑的UTF-8文字；二进制原字节仍保留，可用固定成果预览或项目包导出",binary);}return new JSONObject().put("checkoutId",id).put("path",path).put("content",text).put("sha256",hash(bytes)).put("bytes",bytes.length);
    }
    private JSONObject localFile(String id,String path)throws Exception {
        File target=NativeCommandExecutor.within(content(id),VersionedWorkspace.path(path));
        if(target.exists())return read(id,path).put("deleted",false);
        return new JSONObject().put("checkoutId",id).put("path",path).put("content","").put("sha256","absent").put("bytes",0).put("deleted",true);
    }
    private JSONObject write(JSONObject input)throws Exception {
        String id=JsonFiles.text(input,"checkoutId",80),path=JsonFiles.text(input,"path",240);requireIdle(id);requireCheckoutWritable(id);
        if(!(input.opt("content") instanceof String))throw new IllegalArgumentException("内容需为UTF-8文字");byte[] value=input.getString("content").getBytes(StandardCharsets.UTF_8);if(value.length>262144)throw new IllegalArgumentException("单文件上限256KiB");
        JSONObject current=localFile(id,path);requireHash(input,current.getString("sha256"));File target=NativeCommandExecutor.within(content(id),path);
        if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IllegalStateException("无法建立文件目录");atomic(target,value);return read(id,path);
    }
    private JSONObject delete(JSONObject input)throws Exception {
        String id=JsonFiles.text(input,"checkoutId",80),path=JsonFiles.text(input,"path",240);requireIdle(id);requireCheckoutWritable(id);JSONObject file=read(id,path);requireHash(input,file.getString("sha256"));
        Files.delete(NativeCommandExecutor.within(content(id),path).toPath());return localFile(id,path);
    }
    private JSONObject changes(String id)throws Exception {
        JSONObject exported=export(id),checkout=exported.getJSONObject("checkout");Map<String,JSONObject> original=byPath(checkout.getJSONArray("files")),current=byPath(exported.getJSONArray("files"));
        TreeSet<String> paths=new TreeSet<>(original.keySet());paths.addAll(current.keySet());JSONArray entries=new JSONArray();int changed=0;
        for(String path:paths){JSONObject before=original.get(path),now=current.get(path);String hash=now==null?"absent":now.getString("sha256"),old=before==null?"absent":before.optString("sha256");
            String change=hash.equals(old)?"unchanged":now==null?"deleted":old.equals("absent")?"added":"modified";if(!change.equals("unchanged"))changed++;
            if(now==null&&change.equals("unchanged"))continue;
            entries.put(new JSONObject().put("path",path).put("change",change).put("sha256",hash).put("bytes",now==null?0:now.getLong("bytes")).put("sourceVersion",before==null?0:before.optInt("sourceVersion")).put("binary",now!=null&&now.optBoolean("binary")));
        }
        JSONArray baseline=new JSONArray();for(String path:new TreeSet<>(original.keySet()))baseline.put(original.get(path));
        return new JSONObject().put("checkoutId",id).put("projectId",checkout.getString("projectId")).put("files",entries).put("changedCount",changed).put("fileCount",current.size())
                .put("sourceDigest",ProjectSources.manifestDigest(exported.getJSONArray("files")))
                .put("baselineIdentity",ProjectSources.digest(new JSONObject().put("checkoutId",id).put("projectId",checkout.getString("projectId")).put("files",baseline)));
    }
    private JSONObject target(String project,String path)throws Exception {
        JSONArray history=projects.handle("history",new JSONObject().put("projectId",project).put("path",path)).getJSONArray("history");
        if(history.length()==0)return new JSONObject().put("version",0).put("content","").put("deleted",true);
        JSONObject latest=history.getJSONObject(history.length()-1);if(latest.optBoolean("deleted"))return new JSONObject().put("version",latest.getInt("version")).put("content","").put("deleted",true);
        return projects.handle("read",new JSONObject().put("projectId",project).put("path",path).put("version",latest.getInt("version")));
    }
    private JSONObject review(String id,String path)throws Exception {
        requireIdle(id);JSONObject checkout=checkout(id),file=localFile(id,path),target=target(checkout.getString("projectId"),path);
        if(file.optBoolean("deleted")&&!byPath(checkout.getJSONArray("files")).containsKey(path))throw new IllegalArgumentException("工作副本没有这个文件");
        boolean same=file.optBoolean("deleted")==target.optBoolean("deleted")&&file.getString("content").equals(target.getString("content"));
        JSONObject result=VersionedWorkspace.compare(path,target.getString("content"),file.getString("content"),"项目v"+target.getInt("version"),"工作副本");
        return result.put("checkoutId",id).put("projectId",checkout.getString("projectId")).put("content",file.getString("content")).put("expectedHash",file.getString("sha256")).put("expectedVersion",target.getInt("version")).put("deleted",file.optBoolean("deleted")).put("targetDeleted",target.optBoolean("deleted")).put("same",same).put("importable",!same&&!result.optBoolean("truncated")&&file.getString("content").length()<=65536).put("message",same?"项目与副本内容一致":file.getString("content").length()>65536?"正式项目单文件最多65536字符，请缩小内容":"确认此差异后，按看到的本地摘要与项目版本导回；任一变化都会拒绝");
    }
    private JSONObject importToProject(JSONObject input)throws Exception {
        String id=JsonFiles.text(input,"checkoutId",80),path=JsonFiles.text(input,"path",240);requireIdle(id);requireCheckoutWritable(id);
        Object rawVersion=input.opt("expectedVersion");if(!(rawVersion instanceof Integer||rawVersion instanceof Long)||((Number)rawVersion).longValue()<0||((Number)rawVersion).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("请先审阅差异，并提交看到的整数项目版本");
        JSONObject file=localFile(id,path),checkout=checkout(id);requireHash(input,file.getString("sha256"));
        JSONArray files=checkout.getJSONArray("files");if(file.optBoolean("deleted")&&!byPath(files).containsKey(path))throw new IllegalArgumentException("工作副本没有这个文件");
        JSONObject result=projects.handle(file.optBoolean("deleted")?"removeFile":"write",new JSONObject().put("projectId",checkout.getString("projectId")).put("path",path).put("content",file.getString("content")).put("expectedVersion",((Number)rawVersion).intValue()));
        boolean found=false;for(int i=0;i<files.length();i++)if(path.equals(files.getJSONObject(i).getString("path"))){files.getJSONObject(i).put("sourceVersion",result.getInt("version")).put("sha256",file.getString("sha256"));found=true;}
        if(!found)files.put(new JSONObject().put("path",path).put("sourceVersion",result.getInt("version")).put("sha256",file.getString("sha256")));
        JsonFiles.write(new File(content(id).getParentFile(),"checkout.json"),checkout);
        return result.put("projectId",checkout.getString("projectId")).put("checkoutId",id).put("importedHash",file.getString("sha256"));
    }
    private JSONObject materialize(JSONObject input)throws Exception {
        String source=JsonFiles.text(input,"checkoutId",80),operation=JsonFiles.text(input,"jobId",80);JSONObject receipt=termux.collection(operation);
        if(!source.equals(receipt.optString("checkoutId")))throw new SecurityException("文件回收回执不属于此工作副本");
        JSONArray files=TermuxWorkspaceTransfer.decode(receipt);String id=UUID.nameUUIDFromBytes(("termux-collection:"+operation).getBytes(StandardCharsets.UTF_8)).toString();
        File saved=new File(new File(new File(root,"checkouts"),id),"checkout.json");
        if(saved.isFile()){JSONObject existing=JsonFiles.read(saved);if(!operation.equals(existing.optString("collectionOperationId")))throw new SecurityException("文件回收身份冲突");return existing;}
        JSONObject checkout=JsonFiles.copy(receipt.getJSONObject("sourceCheckout"));checkout.put("id",id).put("collectedFromCheckoutId",source).put("collectionOperationId",operation).put("createdAt",System.currentTimeMillis());
        createCheckout(checkout,files);termux.materialized(operation,id);return checkout;
    }
    private JSONObject export(String id)throws Exception {
        requireIdle(id);File directory=content(id);JSONArray files=new JSONArray();long[] total={0};
        Files.walkFileTree(directory.toPath(),new java.nio.file.SimpleFileVisitor<java.nio.file.Path>(){
            @Override public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path path,java.nio.file.attribute.BasicFileAttributes attrs){return ProjectSources.sourcePath(directory.toPath().relativize(path).toString())?java.nio.file.FileVisitResult.CONTINUE:java.nio.file.FileVisitResult.SKIP_SUBTREE;}
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path path,java.nio.file.attribute.BasicFileAttributes attrs)throws java.io.IOException {
                String relative=directory.toPath().relativize(path).toString();if(!ProjectSources.sourcePath(relative))return java.nio.file.FileVisitResult.CONTINUE;
                try{if(attrs.isSymbolicLink()||!attrs.isRegularFile())throw new SecurityException("源码清单不跟随符号链接或特殊文件");if(files.length()>=ProjectSources.MAX_FILES||attrs.size()>ProjectSources.MAX_FILE_BYTES)throw new IllegalStateException("源码清单超过2000文件/单文件2MiB范围");
                    byte[] bytes=Files.readAllBytes(path);total[0]+=bytes.length;if(bytes.length>ProjectSources.MAX_FILE_BYTES||total[0]>ProjectSources.MAX_TOTAL_BYTES)throw new IllegalStateException("源码清单超过16MiB；依赖缓存和构建输出不纳入版本");
                    JSONObject file=new JSONObject().put("checkoutId",id).put("path",relative).put("sha256",hash(bytes)).put("bytes",bytes.length);
                    try{String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(text.indexOf('\0')>=0)throw new java.nio.charset.CharacterCodingException();file.put("content",text);}catch(java.nio.charset.CharacterCodingException binary){file.put("binary",true).put("contentBase64",java.util.Base64.getEncoder().encodeToString(bytes));}files.put(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }catch(java.io.IOException error){throw error;}catch(Exception invalid){throw new java.io.IOException(invalid.getMessage(),invalid);}
            }
        });
        return new JSONObject().put("format","pocketworld.localworkspace.v1").put("checkout",checkout(id)).put("files",files).put("bytes",total[0]);
    }
    private static Map<String,JSONObject> byPath(JSONArray array)throws Exception {Map<String,JSONObject> result=new LinkedHashMap<>();for(int i=0;i<array.length();i++){JSONObject file=array.getJSONObject(i);result.put(file.getString("path"),file);}return result;}
    private static void requireHash(JSONObject input,String actual)throws Exception {if(!actual.equals(JsonFiles.text(input,"expectedHash",80)))throw new IllegalStateException("本地文件已变化，请重新读取并审阅后再保存或导回");}
    private static String hash(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static void atomic(File target,byte[] value)throws Exception {File pending=File.createTempFile("pending-",".tmp",target.getParentFile());try{try(FileOutputStream output=new FileOutputStream(pending)){output.write(value);output.getFD().sync();}Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(pending.toPath());}}
    private static JSONObject json(Object... pairs){JSONObject out=new JSONObject();try{for(int i=0;i+1<pairs.length;i+=2)out.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(Exception ignored){}return out;}
    @Override public void close(){closed=true;commands.close();termux.close();for(ProjectRuntime runtime:runtimes.values())if(runtime instanceof ManagedProjectRuntime)try{((ManagedProjectRuntime)runtime).close();}catch(Exception unconfirmed){context.emit(id(),json("kind","runtime_cleanup_unknown","message","本机进程清理尚未确认，请核对原任务"));}synchronized(this){previews.clear();}}
}
