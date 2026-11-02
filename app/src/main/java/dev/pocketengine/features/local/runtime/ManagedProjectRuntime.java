package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual local process provider on the host's active checkout; bundled tool assets remain a native build choice. */
public final class ManagedProjectRuntime implements ProjectRuntime,AutoCloseable {
    public static final String RUNTIME_ID="managed_process_v1";
    private final ManagedProcessJobs jobs;
    private final RuntimeEnvironment environments;
    private final RuntimeEnvironment.Package selected;
    private final Map<String,RuntimeTool> tools;
    private final File home,tmp;
    private final String identity;
    private AndroidRuntimePreparation androidRuntime;
    private String configurationProblem="";
    private ManagedProjectRuntime(File privateDirectory,RuntimeEnvironment.Package approvedPackage,Map<String,RuntimeTool> approvedTools) throws Exception {
        File directory=RuntimeFiles.directory(privateDirectory,"managed-process");
        jobs=new ManagedProcessJobs(directory,new NativeProcessSupervisor());
        environments=new RuntimeEnvironment(directory,jobs::requireEnvironmentIdle);
        selected=approvedPackage;tools=Collections.unmodifiableMap(new TreeMap<>(approvedTools));
        home=RuntimeFiles.directory(directory,"home");tmp=RuntimeFiles.directory(directory,"tmp");
        JSONObject toolIds=new JSONObject();for(Map.Entry<String,RuntimeTool> tool:tools.entrySet()) {
            if(!tool.getKey().equals(tool.getValue().name))throw new IllegalArgumentException("runtime_tool_name_mismatch");
            toolIds.put(tool.getKey(),tool.getValue().identity);
        }
        identity=ProjectSources.digest(new JSONObject().put("protocol","pocketworld.process.v1").put("provider",RUNTIME_ID)
                .put("package",selected==null?JSONObject.NULL:selected.identity).put("tools",toolIds));
    }
    public static ManagedProjectRuntime create(File privateRuntimeDirectory,RuntimeEnvironment.Package approvedPackage,
            Map<String,RuntimeTool> approvedTools) throws Exception {
        return new ManagedProjectRuntime(privateRuntimeDirectory,approvedPackage,approvedTools);
    }
    /** Safe default during host wiring: unavailable, never silently redirected to an extra app or cloud executor. */
    public static ManagedProjectRuntime unconfigured(File privateRuntimeDirectory) throws Exception {
        return create(privateRuntimeDirectory,null,Collections.emptyMap());
    }
    /** Android composition only: all paths are resolved from Context, never from model input. */
    public static ManagedProjectRuntime createAndroid(File privateRuntimeDirectory,File nativeLibraryDirectory,AndroidRuntimeCatalog catalog) throws Exception {
        ManagedProjectRuntime runtime=unconfigured(privateRuntimeDirectory);
        runtime.androidRuntime=new AndroidRuntimePreparation(RuntimeFiles.directory(privateRuntimeDirectory,"managed-process"),nativeLibraryDirectory,catalog,runtime.environments,runtime.home,runtime.tmp);
        return runtime;
    }
    public static ManagedProjectRuntime unavailableAndroid(File privateRuntimeDirectory,String reason) throws Exception {
        ManagedProjectRuntime runtime=unconfigured(privateRuntimeDirectory);runtime.configurationProblem=reason;return runtime;
    }
    public RuntimeEnvironment environmentManager() {return environments;}
    /** Body-free owner storage view with the same original package/generation leases. */
    public RuntimeStorage ownerStorageForHost(){return new RuntimeStorage(this,androidRuntime,environments,jobs,home,tmp);}

    /** Native Skill description from this same installed generation/tools. No
     * argv/executable/env is accepted from model or downloaded Skill metadata. */
    public synchronized JSONObject skillToolsForHost(String interpreter)throws Exception {
        if(!java.util.Set.of("python3","sh").contains(interpreter))throw new SecurityException("本机Skill只选择已核对的Python或Shell解释器");JSONObject observed=describe();if(!observed.optBoolean("canExecute"))throw new IllegalStateException("本机托管工具尚未实际准备，请从原项目工具准备入口检查和修复");JSONArray actual=observed.getJSONArray("tools");JSONObject chosen=null,python=null;
        for(int i=0;i<actual.length();i++){JSONObject row=actual.getJSONObject(i);if(row.optBoolean("hashVerified")||row.optBoolean("probeVerified")){if(interpreter.equals(row.optString("name")))chosen=row;if("python3".equals(row.optString("name")))python=row;}}
        if(chosen==null||python==null)throw new IllegalStateException("原托管环境缺少已核对的解释器或Python原生派发辅助，未换到电脑或任意shell");JSONArray argv=new JSONArray(),bootstrap=new JSONArray();String path;
        if(androidRuntime!=null){argv.put(androidRuntime.guestTool(interpreter));bootstrap.put(androidRuntime.guestTool("python3"));path="/opt/node/bin:/usr/local/bin:/usr/bin:/bin";}
        else{for(String value:tools.get(interpreter).command(java.util.List.of()))argv.put(value);for(String value:tools.get("python3").command(java.util.List.of()))bootstrap.put(value);StringBuilder prefixes=new StringBuilder();for(RuntimeTool tool:tools.values()){if(prefixes.length()>0)prefixes.append(':');prefixes.append(tool.executableDirectory().getPath());}path=prefixes.toString();}
        String versionProbe=chosen.optString("observedVersion",chosen.optString("version","verified_native_tool"));String firstLine=versionProbe.split("\\R",2)[0];if(firstLine.isEmpty()||firstLine.length()>120||firstLine.chars().anyMatch(Character::isISOControl))throw new SecurityException("原解释器版本首行无法完整展示，请核对原工具探测");
        return new JSONObject().put("runtimeIdentity",identity()).put("interpreter",interpreter).put("interpreterArgv",argv).put("runtimeSha256",chosen.getString("sha256")).put("runtimeVersion",firstLine).put("runtimeVersionScope","first_line_of_native_probe").put("runtimeVersionProbeSha256",ProjectSources.sha256(versionProbe.getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("bootstrapArgv",bootstrap).put("bootstrapSha256",python.getString("sha256")).put("path",path).put("guestProjectRoot",androidRuntime==null?"":"/workspace").put("executionLocation","this_phone").put("osSandbox",false);
    }
    /** Same installed, verified Git/Python generation. This native description
     * does not accept a model executable, shell, URL, environment or credential. */
    public synchronized JSONObject gitImportToolsForHost()throws Exception {
        JSONObject python=skillToolsForHost("python3"),facts=describe(),git=null;JSONArray observed=facts.getJSONArray("tools");for(int i=0;i<observed.length();i++){JSONObject candidate=observed.getJSONObject(i);if("git".equals(candidate.optString("name"))&&(candidate.optBoolean("hashVerified")||candidate.optBoolean("probeVerified")))git=candidate;}
        if(git==null||!git.optString("sha256").matches("[a-f0-9]{64}"))throw new IllegalStateException("原托管环境Git尚未实际准备，请先核对开发工具；未切到电脑或任意shell");JSONArray argv=new JSONArray();if(androidRuntime!=null)argv.put(androidRuntime.guestTool("git"));else for(String value:tools.get("git").command(List.of()))argv.put(value);if(argv.length()!=1)throw new SecurityException("Git导入只接受原单个准确Git二进制");String gitProbe=git.optString("observedVersion",git.optString("version","")),version=gitProbe.split("\\R",2)[0];if(version.isEmpty()||version.length()>120||version.chars().anyMatch(Character::isISOControl))throw new SecurityException("原Git版本探测首行无法准确展示");return new JSONObject().put("runtimeIdentity",identity()).put("gitArgv",argv).put("gitExecutableSha256",git.getString("sha256")).put("gitVersion",version).put("gitVersionProbeSha256",ProjectSources.sha256(gitProbe.getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("bootstrapSha256",python.getString("bootstrapSha256")).put("path",python.getString("path")).put("guestProjectRoot",python.getString("guestProjectRoot"));
    }
    public String identity() {return androidRuntime==null?identity:androidRuntime.identity();}
    /** Native host hook for its original TaskStore receipt row; no new task or execution authority. */
    public void setReceiptSink(java.util.function.Consumer<JSONObject> nativeSink) {jobs.setReceiptSink(nativeSink);}
    public void setProcessInputSink(java.util.function.Consumer<ManagedProcessJobs.ProcessInputReceipt> sink){jobs.setProcessInputSink(sink);}
    public JSONObject processInput(RuntimeExecutionRef reference,dev.pocketengine.core.RunControl original,String operation,JSONObject request,ProjectRuntime.LiveGate gate)throws Exception{requireProvider(reference);reference.checkControl(original);return jobs.processInput(reference,original,operation,request,gate);}
    public AutoCloseable retainOwnedServiceAuthority(RuntimeExecutionRef reference,dev.pocketengine.core.RunControl original)throws Exception {
        requireProvider(reference);reference.checkControl(original);jobs.requireOwnedOrReleased(reference);
        return original.retainAuthorityCheck(()->jobs.requireOwnedOrReleased(reference));
    }
    public ManagedProcessJobs.LiveEditLease beginLiveEdit(RuntimeExecutionRef reference,dev.pocketengine.core.RunControl original)throws Exception {requireProvider(reference);return jobs.beginLiveEdit(reference,original);}
    public ProjectDevSession.Observed probeLivePreview(RuntimeExecutionRef reference,dev.pocketengine.core.RunControl original,dev.pocketengine.core.RunControl observation)throws Exception {requireProvider(reference);return jobs.probeLivePreview(reference,original,observation);}
    public void requireLivePreview(RuntimeExecutionRef reference,dev.pocketengine.core.RunControl original)throws Exception {requireProvider(reference);jobs.requireLivePreview(reference,original);}
    public void requirePtyServiceLive(RuntimeExecutionRef reference,dev.pocketengine.core.RunControl original)throws Exception {requireProvider(reference);jobs.requirePtyServiceLive(reference,original);}
    public void requireCheckoutWritable(String checkout)throws Exception {jobs.requireCheckoutWritable(checkout);}
    /** Body-free original native job order; not a source version or execution grant. */
    public long currentCheckoutSequence(String checkout)throws Exception{return jobs.currentCheckoutSequence(checkout);}
    @Override public JSONObject describe() throws Exception {
        if(androidRuntime!=null)return androidRuntime.describe(jobs.available()).put("runtimeId",RUNTIME_ID).put("provider",RUNTIME_ID)
                .put("ptyAvailable",jobs.ptyAvailable()).put("terminalStreams","pty_merged_stdout_stderr_or_pipe_separate").put("nativeSupervisorAvailable",jobs.available()).put("canonicalProjectOwner","native_active_checkout")
                .put("requiresHostApproval",true).put("createsTasks",false).put("liveStdout",true).put("logDelivery","raw_byte_cursor_pages").put("ownedServiceLifetime","same_original_host_run").put("ownedServiceNativeStartAck",true).put("serviceReadinessVerified",false).put("persistentServices",false)
                .put("scopeEnforced",false).put("securityBoundary","same_uid_process_group_not_a_sandbox")
                .put("cancellationScope","owned_process_group; escaped sessions are outside this boundary")
                .put("managedOutputCollectionAvailable",true).put("phases",new JSONArray().put("PROBE").put("STAGE").put("COMMAND"));
        JSONArray facts=new JSONArray();boolean toolFilesVerified=!tools.isEmpty();
        for(RuntimeTool tool:tools.values()) {JSONObject fact=tool.describe();facts.put(fact);toolFilesVerified&=fact.getBoolean("hashVerified");}
        JSONObject environment=selected==null?new JSONObject().put("installed",false).put("status","not_configured"):environments.describe(selected);
        boolean executable=selected!=null && environment.getBoolean("installed") && toolFilesVerified && jobs.available() && tools.containsKey("sh");
        return new JSONObject().put("runtimeId",RUNTIME_ID).put("provider",RUNTIME_ID).put("identity",identity)
                .put("installed",environment.getBoolean("installed")).put("canExecute",executable).put("configurationProblem",configurationProblem).put("preparationAvailable",false)
                .put("message",configurationProblem.equals("android_runtime_unsupported_abi")?"这台手机的处理器暂不支持本机开发工具。":configurationProblem.isEmpty()?"本机开发工具尚未配置。":"当前安装包未包含完整、有效的本机开发工具配置，尚未下载或执行。")
                .put("status",executable?"installed_unprobed":environment.optString("status").equals("not_configured")?"not_configured":"preparation_required")
                .put("ptyAvailable",jobs.ptyAvailable()).put("terminalStreams","pty_merged_stdout_stderr_or_pipe_separate").put("nativeSupervisorAvailable",jobs.available()).put("environment",environment).put("tools",facts)
                .put("toolProbeRequired",true).put("canonicalProjectOwner","native_active_checkout")
                .put("requiresHostApproval",true).put("createsTasks",false).put("automaticDownload",false)
                .put("liveStdout",true).put("logDelivery","raw_byte_cursor_pages").put("ownedServiceLifetime","same_original_host_run").put("ownedServiceNativeStartAck",true).put("serviceReadinessVerified",false).put("persistentServices",false)
                .put("scopeEnforced",false).put("securityBoundary","same_uid_process_group_not_a_sandbox")
                .put("cancellationScope","owned_process_group; escaped sessions are outside this boundary")
                .put("managedOutputCollectionAvailable",true).put("phases",new JSONArray().put("PROBE").put("STAGE").put("COMMAND"));
    }
    @Override public RuntimeReceipt dispatch(RuntimeInvocation invocation) throws Exception {
        if(dev.pocketengine.features.local.project.ProjectGitImport.exact(invocation.exact()))throw new SecurityException("Git网络导入需要原生主人一次性租约；普通项目命令不能自报该intent");return dispatchOriginal(invocation,null);
    }
    public RuntimeReceipt dispatchGitImport(RuntimeInvocation invocation,dev.pocketengine.features.local.project.ProjectGitImport.ProcessGrant originalGrant)throws Exception {
        java.util.Objects.requireNonNull(originalGrant);boolean entered=false;try{originalGrant.claim(this,invocation);entered=true;return dispatchOriginal(invocation,originalGrant);}finally{if(!entered)originalGrant.close();}
    }
    private RuntimeReceipt dispatchOriginal(RuntimeInvocation invocation,AutoCloseable nativeImportLease) throws Exception {
        boolean passedToJobs=false;try{
        requireProvider(invocation.executionRef());invocation.checkCurrent();
        if(dev.pocketengine.features.skills.ManagedSkillBackend.exact(invocation.exact()))dev.pocketengine.features.skills.ManagedSkillBackend.requireProgram(invocation.exact());
        if(dev.pocketengine.features.local.project.ProjectGitImport.exact(invocation.exact())){if(nativeImportLease==null||invocation.executionRef().phase()!=RuntimeExecutionRef.Phase.COMMAND)throw new SecurityException("缺少原Git导入的固定命令租约");dev.pocketengine.features.local.project.ProjectGitImport.requireProgram(invocation.exact());}
        if(androidRuntime!=null){passedToJobs=true;return dispatchAndroid(invocation,nativeImportLease);}
        if(!identity.equals(invocation.exact().getString("runtimeIdentity")))throw new SecurityException("runtime_reviewed_environment_changed");
        if(!describe().getBoolean("canExecute"))throw new IllegalStateException("runtime_environment_not_prepared");
        RuntimeWorkspace workspace=invocation.workspace();workspace.verify(invocation);
        JSONObject payload=invocation.exact().getJSONObject("payload");boolean generic="command".equals(invocation.exact().optString("intent"));JSONObject commandSpec=generic?dev.pocketengine.features.local.project.ProjectCommandSpec.fromPayload(payload):null;List<String> command;
        if(generic&&"pty".equals(commandSpec.getString("terminalMode"))&&(!jobs.ptyAvailable()||!NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT.equals(payload.optString("terminalNativeContract"))))throw new SecurityException("runtime_reviewed_pty_transport_unavailable_or_changed");
        switch(invocation.executionRef().phase()) {
            case PROBE:
                StringBuilder script=new StringBuilder("set -eu\n");
                for(RuntimeTool tool:tools.values()) {
                    script.append("printf '%s\\n' ").append(ProjectSources.quote(tool.name+"="+tool.identity)).append('\n');
                    for(String argument:tool.probeCommand())script.append(ProjectSources.quote(argument)).append(' ');
                    script.append('\n');
                }
                command=tools.get("sh").command(List.of("-c",script.toString()));break;
            case STAGE:
                // Preparation verifies this active tree; it never stages a second mutable project.
                command=tools.get("sh").command(List.of("-c","pwd -P"));break;
            case COMMAND:
                RuntimeTool tool=tools.get(payload.getString("executable"));
                if(tool==null)throw new IllegalArgumentException("runtime_tool_not_installed");
                JSONArray requested=dev.pocketengine.features.local.project.ProjectGit.exact(invocation.exact())?dev.pocketengine.features.local.project.ProjectGit.arguments(payload):dev.pocketengine.features.local.project.ProjectGitImport.exact(invocation.exact())?dev.pocketengine.features.local.project.ProjectGitImport.arguments(payload):payload.getJSONArray("argv");List<String> argv=new ArrayList<>();
                for(int i=0;i<requested.length();i++)argv.add(requested.getString(i));command=tool.command(argv);break;
            default:throw new IllegalArgumentException("runtime_phase_requires_native_artifact_reader");
        }
        File directory=workspace.resolve(payload.optString("workingDirectory","."),false);
        if(!directory.isDirectory())throw new IllegalArgumentException("runtime_working_directory_missing");
        Map<String,String> environment=new LinkedHashMap<>();StringBuilder path=new StringBuilder();
        for(RuntimeTool tool:tools.values()) {if(path.length()>0)path.append(':');path.append(tool.executableDirectory().getPath());}
        environment.put("PATH",path.toString());environment.put("HOME",home.getPath());environment.put("TMPDIR",tmp.getPath());
        environment.put("LANG","C.UTF-8");environment.put("POCKETWORLD_ENVIRONMENT_IDENTITY",selected.identity);
        if(commandSpec!=null){JSONObject ordinary=commandSpec.getJSONObject("environment");for(java.util.Iterator<String> keys=ordinary.keys();keys.hasNext();){String name=keys.next();environment.put(name,ordinary.getString(name));}if("pty".equals(commandSpec.getString("terminalMode")))environment.put("TERM","xterm-256color");}
        long timeout=payload.optLong("timeoutMillis",300000);
        if(timeout<1 || timeout>86400000)throw new IllegalArgumentException("runtime_timeout_invalid");
        AutoCloseable lease=nativeImportLease==null?environments.acquire(selected):nativeImportLease;
        passedToJobs=true;return jobs.start(invocation,command,environment,directory,timeout,lease);
        }finally{if(nativeImportLease!=null&&!passedToJobs)nativeImportLease.close();}
    }
    private RuntimeReceipt dispatchAndroid(RuntimeInvocation invocation,AutoCloseable nativeImportLease) throws Exception {
        boolean passedToJobs=false;try {
        AndroidRuntimePreparation profile=androidRuntime;
        if(!identity().equals(invocation.exact().getString("runtimeIdentity")))throw new SecurityException("runtime_reviewed_environment_changed");
        RuntimeWorkspace workspace=invocation.workspace();workspace.verify(invocation);
        if(invocation.executionRef().phase()==RuntimeExecutionRef.Phase.STAGE) {
            if(!profile.describe(jobs.available()).getBoolean("preparationAvailable"))throw new IllegalStateException("runtime_apk_engine_or_supervisor_unavailable");
            JSONObject reviewedCatalog=invocation.exact().getJSONObject("payload").optJSONObject("preparationCatalog");
            if(reviewedCatalog==null||!profile.catalogIdentity().equals(reviewedCatalog.optString("catalogIdentity")))throw new SecurityException("runtime_reviewed_download_catalog_required");
            if(!ProjectSources.canonical(reviewedCatalog).equals(ProjectSources.canonical(profile.describe(jobs.available()).getJSONObject("preparationEffects"))))throw new SecurityException("runtime_reviewed_download_catalog_changed");
            RuntimeReceipt previous=jobs.existing(invocation.executionRef());if(previous!=null)return previous;
            jobs.requireEnvironmentIdle(profile.catalogIdentity());AutoCloseable preparation=profile.beginPreparation();
            return jobs.startPreparation(invocation,profile.catalogIdentity(),profile::prepare,preparation);
        }
        List<String> command;Map<String,String> environment;File cwd;long timeout;AutoCloseable lease;
        synchronized(profile) {
            if(!identity().equals(invocation.exact().getString("runtimeIdentity")))throw new SecurityException("runtime_reviewed_environment_changed");
            if(!profile.describe(jobs.available()).getBoolean("canExecute"))throw new IllegalStateException("runtime_environment_not_prepared");
            JSONObject payload=invocation.exact().getJSONObject("payload");boolean generic="command".equals(invocation.exact().optString("intent"));JSONObject commandSpec=generic?dev.pocketengine.features.local.project.ProjectCommandSpec.fromPayload(payload):null;List<String> guest=new ArrayList<>();
            if(generic&&"pty".equals(commandSpec.getString("terminalMode"))&&(!jobs.ptyAvailable()||!NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT.equals(payload.optString("terminalNativeContract"))))throw new SecurityException("runtime_reviewed_pty_transport_unavailable_or_changed");
            switch(invocation.executionRef().phase()) {
                case PROBE:
                    StringBuilder script=new StringBuilder("set -eu\n");
                    for(List<String> probe:profile.probeCommands()){for(String arg:probe)script.append(ProjectSources.quote(arg)).append(' ');script.append('\n');}
                    guest.add(profile.guestTool("sh"));guest.addAll(List.of("-c",script.toString()));break;
                case COMMAND:
                    guest.add(profile.guestTool(payload.getString("executable")));JSONArray args=dev.pocketengine.features.local.project.ProjectGit.exact(invocation.exact())?dev.pocketengine.features.local.project.ProjectGit.arguments(payload):dev.pocketengine.features.local.project.ProjectGitImport.exact(invocation.exact())?dev.pocketengine.features.local.project.ProjectGitImport.arguments(payload):payload.getJSONArray("argv");for(int i=0;i<args.length();i++)guest.add(args.getString(i));break;
                default:throw new IllegalArgumentException("runtime_phase_requires_native_artifact_reader");
            }
            String relative=payload.optString("workingDirectory",".");cwd=workspace.resolve(relative,false);
            timeout=payload.optLong("timeoutMillis",300000);if(timeout<1||timeout>86400000)throw new IllegalArgumentException("runtime_timeout_invalid");
            Map<String,String> ordinary=new LinkedHashMap<>();if(commandSpec!=null){JSONObject requested=commandSpec.getJSONObject("environment");for(java.util.Iterator<String> keys=requested.keys();keys.hasNext();){String name=keys.next();ordinary.put(name,requested.getString(name));}if("pty".equals(commandSpec.getString("terminalMode")))ordinary.put("TERM","xterm-256color");}
            command=profile.command(workspace,relative,guest,ordinary);environment=profile.hostEnvironment(identity());lease=nativeImportLease==null?profile.acquire():nativeImportLease;
        }
        passedToJobs=true;return jobs.start(invocation,command,environment,cwd,timeout,lease);
        }finally{if(nativeImportLease!=null&&!passedToJobs)nativeImportLease.close();}
    }
    private static void requireProvider(RuntimeExecutionRef ref) throws Exception {
        if(!RUNTIME_ID.equals(ref.exact().optString("runtimeId")))throw new SecurityException("runtime_original_provider_required");
    }
    /** Native Git intake observation only. A registered SQL intent can precede
     * the original job receipt; absence is query-only unknown, never permission
     * to start or reconstruct the old operation. */
    public RuntimeReceipt gitImportReceiptForHost(RuntimeExecutionRef ref)throws Exception {
        requireProvider(ref);if(!dev.pocketengine.features.local.project.ProjectGitImport.exact(ref.exact()))throw new SecurityException("runtime_not_original_git_import");return jobs.existing(ref);
    }
    @Override public RuntimeReceipt query(RuntimeExecutionRef ref) throws Exception {requireProvider(ref);return jobs.query(ref);}
    @Override public RuntimeReceipt requestCancel(RuntimeExecutionRef ref) throws Exception {requireProvider(ref);return jobs.requestCancel(ref);}
    @Override public JSONObject readLog(RuntimeExecutionRef ref,long afterSequence,int maxBytes) throws Exception {
        requireProvider(ref);return jobs.readLog(ref,afterSequence,maxBytes);
    }
    @Override public void close() throws Exception {jobs.close();}
}
