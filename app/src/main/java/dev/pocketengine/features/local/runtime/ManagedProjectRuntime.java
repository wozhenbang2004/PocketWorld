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
    @Override public JSONObject describe() throws Exception {
        if(androidRuntime!=null)return androidRuntime.describe(jobs.available()).put("runtimeId",RUNTIME_ID).put("provider",RUNTIME_ID)
                .put("ptyAvailable",jobs.ptyAvailable()).put("terminalStreams","pty_merged_stdout_stderr_or_pipe_separate").put("nativeSupervisorAvailable",jobs.available()).put("canonicalProjectOwner","native_active_checkout")
                .put("requiresHostApproval",true).put("createsTasks",false).put("liveStdout",true).put("logDelivery","raw_byte_cursor_pages").put("ownedServiceLifetime","same_original_host_run").put("ownedServiceNativeStartAck",true).put("serviceReadinessVerified",false).put("persistentServices",false)
                .put("scopeEnforced",false).put("securityBoundary","same_uid_process_group_not_a_sandbox")
                .put("cancellationScope","owned_process_group; escaped sessions are outside this boundary")
                .put("phases",new JSONArray().put("PROBE").put("STAGE").put("COMMAND"));
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
                .put("phases",new JSONArray().put("PROBE").put("STAGE").put("COMMAND"));
    }
    @Override public RuntimeReceipt dispatch(RuntimeInvocation invocation) throws Exception {
        requireProvider(invocation.executionRef());invocation.checkCurrent();
        if(androidRuntime!=null)return dispatchAndroid(invocation);
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
                JSONArray requested=payload.getJSONArray("argv");List<String> argv=new ArrayList<>();
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
        AutoCloseable lease=environments.acquire(selected);
        return jobs.start(invocation,command,environment,directory,timeout,lease);
    }
    private RuntimeReceipt dispatchAndroid(RuntimeInvocation invocation) throws Exception {
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
                    guest.add(profile.guestTool(payload.getString("executable")));JSONArray args=payload.getJSONArray("argv");for(int i=0;i<args.length();i++)guest.add(args.getString(i));break;
                default:throw new IllegalArgumentException("runtime_phase_requires_native_artifact_reader");
            }
            String relative=payload.optString("workingDirectory",".");cwd=workspace.resolve(relative,false);
            timeout=payload.optLong("timeoutMillis",300000);if(timeout<1||timeout>86400000)throw new IllegalArgumentException("runtime_timeout_invalid");
            Map<String,String> ordinary=new LinkedHashMap<>();if(commandSpec!=null){JSONObject requested=commandSpec.getJSONObject("environment");for(java.util.Iterator<String> keys=requested.keys();keys.hasNext();){String name=keys.next();ordinary.put(name,requested.getString(name));}if("pty".equals(commandSpec.getString("terminalMode")))ordinary.put("TERM","xterm-256color");}
            command=profile.command(workspace,relative,guest,ordinary);environment=profile.hostEnvironment(identity());lease=profile.acquire();
        }
        return jobs.start(invocation,command,environment,cwd,timeout,lease);
    }
    private static void requireProvider(RuntimeExecutionRef ref) throws Exception {
        if(!RUNTIME_ID.equals(ref.exact().optString("runtimeId")))throw new SecurityException("runtime_original_provider_required");
    }
    @Override public RuntimeReceipt query(RuntimeExecutionRef ref) throws Exception {requireProvider(ref);return jobs.query(ref);}
    @Override public RuntimeReceipt requestCancel(RuntimeExecutionRef ref) throws Exception {requireProvider(ref);return jobs.requestCancel(ref);}
    @Override public JSONObject readLog(RuntimeExecutionRef ref,long afterSequence,int maxBytes) throws Exception {
        requireProvider(ref);return jobs.readLog(ref,afterSequence,maxBytes);
    }
    @Override public void close() throws Exception {jobs.close();}
}
