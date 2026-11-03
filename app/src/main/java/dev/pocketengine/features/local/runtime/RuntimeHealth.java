package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Body-free projections of the ORIGINAL catalog/seal/preparation facts. This has
 * no journal, installer, strike counter, scheduler, recovery grant or task state. */
public final class RuntimeHealth {
    public static final String FORMAT="pocketworld.runtime-health.v1";
    private RuntimeHealth(){}

    public static JSONObject failure(Throwable error)throws Exception {
        String code=safeCode(error==null?"":error.getMessage()),category=category(code);
        int depth=0;for(Throwable cause=error;cause!=null&&depth++<8;cause=cause.getCause()){
            if(cause instanceof InterruptedException){category="cancelled";break;}
            if(cause instanceof UnknownHostException||cause instanceof ConnectException||cause instanceof SocketTimeoutException||cause instanceof SocketException){category="network";break;}
            if(cause.getCause()==cause)break;
        }
        if("unknown".equals(category)&&error instanceof SecurityException)category="policy_or_integrity";
        return new JSONObject().put("category",category).put("code",code).put("pluginFaultAttributed",false)
                .put("automaticRestartAllowed",false).put("replayAllowed",false);
    }
    private static String category(String code){
        if(code.equals("runtime_preparation_cancelled"))return "cancelled";
        if(code.startsWith("runtime_download_http_"))return "network";
        if(code.equals("runtime_environment_insufficient_space"))return "storage";
        if(code.matches("runtime_(?:generation_in_use|environment_in_use|environment_has_live_or_unresolved_job|preparation_or_owner_cleanup_already_running|owner_reviewed_storage_cleanup_in_progress)"))return "resource_busy";
        if(code.equals("runtime_preparation_state_unreadable")||code.equals("runtime_execution_observation_failed"))return "observation";
        if(code.startsWith("runtime_bootstrap_command_failed_")||code.startsWith("runtime_tool_probe_")||code.equals("runtime_node24_probe_failed"))return "probe";
        if(code.startsWith("runtime_generation_")||code.startsWith("runtime_package_")||code.startsWith("runtime_archive_")||code.startsWith("runtime_active_generation_")||code.startsWith("runtime_apk_engine_")||code.startsWith("runtime_download_content_length_")||code.startsWith("runtime_download_size_"))return "integrity";
        return "unknown";
    }
    private static String safeCode(String value){return value!=null&&value.matches("runtime_[a-z0-9_]{1,180}")?value:"runtime_preparation_failed";}
    private static String identity(JSONObject value,String key){String id=value==null?"":value.optString(key);return id.matches("[a-f0-9]{64}")?id:"";}
    private static Object optional(String value){return value.isEmpty()?JSONObject.NULL:value;}
    private static String bounded(String value,int maximum){if(value==null)return "";String text=value.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]","");int end=Math.min(maximum,text.length());if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end);}

    static JSONObject android(AndroidRuntimeCatalog catalog,JSONObject active,JSONArray observedTools,JSONObject preparation,
                              boolean engineVerified,boolean supervisorAvailable,boolean canExecute)throws Exception {
        JSONObject release=catalog.descriptor(),source=active==null?release:active.getJSONObject("catalog");
        String generation=identity(active,"generationIdentity"),activeCatalog=identity(active,"catalogIdentity");
        JSONArray components=new JSONArray();JSONObject engine=release.getJSONObject("engine");
        components.put(new JSONObject().put("id","apk_proot").put("version",bounded(engine.optString("version"),120))
                .put("sha256",engine.getString("prootSha256")).put("loaderSha256",engine.getString("loaderSha256"))
                .put("sourceLockSha256",optional(identity(engine,"sourceLockSha256"))).put("hashVerifiedNow",engineVerified)
                .put("source","release_apk_catalog"));
        for(String name:new String[]{"rootfs","node"}){
            JSONObject archive=source.getJSONObject(name);components.put(new JSONObject().put("id",name)
                    .put("archiveSha256",archive.getString("archiveSha256")).put("archiveBytes",archive.getLong("archiveBytes"))
                    .put("source",active==null?"release_apk_catalog":"original_active_generation_seal")
                    .put("installedGeneration",optional(generation)).put("hashValidationScope",active==null?"not_installed":"archive_verified_at_install"));
        }
        JSONArray tools=new JSONArray();
        for(int i=0;i<observedTools.length()&&i<32;i++){
            JSONObject actual=observedTools.getJSONObject(i);String probe=actual.optString("observedVersion",actual.optString("version")),line=probe.split("\\R",2)[0];
            tools.put(new JSONObject().put("name",bounded(actual.optString("name"),80)).put("sealedSha256",optional(identity(actual,"sha256")))
                    .put("version",bounded(line,120)).put("versionDisplayTruncated",line.length()>120)
                    .put("versionProbeSha256",ProjectSources.sha256(probe.getBytes(StandardCharsets.UTF_8)))
                    .put("installed",actual.optBoolean("installed")).put("metadataUnchanged",actual.optBoolean("probeVerified"))
                    .put("hashValidationScope","sealed_at_install_metadata_checked_now_not_current_full_hash"));
        }
        JSONObject progress=preparation(catalog.identity,generation,preparation);
        String packages=active==null?"":active.optString("installedPackages");int count=0;for(String line:packages.split("\\R"))if(!line.isEmpty())count++;
        return new JSONObject().put("format",FORMAT).put("runtimeIdentity",generation.isEmpty()?catalog.identity:generation)
                .put("releaseCatalogIdentity",catalog.identity).put("activeCatalogIdentity",optional(activeCatalog))
                .put("activeGenerationIdentity",optional(generation)).put("catalogMatchesActive",!activeCatalog.isEmpty()&&catalog.identity.equals(activeCatalog))
                .put("abi",catalog.abi).put("profileId",bounded(release.optString("profileId"),80)).put("revision",bounded(release.optString("revision"),160))
                .put("executionReady",canExecute).put("nativeSupervisorAvailable",supervisorAvailable).put("components",components).put("tools",tools)
                .put("installedPackageManifest",new JSONObject().put("present",!packages.isEmpty()).put("packageCount",count)
                        .put("sha256",packages.isEmpty()?JSONObject.NULL:ProjectSources.sha256(packages.getBytes(StandardCharsets.UTF_8)))
                        .put("source","original_active_generation_dpkg_query").put("buildTimePackagePinsClaimed",false))
                .put("preparation",progress).put("recoveryPreview",recovery(catalog.identity,generation,canExecute,progress))
                .put("taskAuthority","original_taskstore").put("businessVerified",false).put("logsIncluded",false).put("pluginStrikeCountChanged",false);
    }
    private static JSONObject preparation(String catalog,String generation,JSONObject original)throws Exception {
        String sourceCatalog=identity(original,"catalogIdentity"),sourceGeneration=identity(original,"generationIdentity");
        boolean current=(sourceCatalog.isEmpty()||catalog.equals(sourceCatalog))&&(sourceGeneration.isEmpty()||generation.equals(sourceGeneration));
        String phase=original.optString("phase","not_started");if(!phase.matches("[a-z_]{1,80}"))phase="observation_unavailable";
        JSONObject fault=original.optJSONObject("failure");
        String category=fault==null?category(safeCode(original.optString("error"))):fault.optString("category","unknown");
        if(!Set.of("cancelled","network","storage","resource_busy","observation","probe","integrity","policy_or_integrity","unknown").contains(category))category="unknown";
        if(!original.has("error")&&fault==null)category="none";
        return new JSONObject().put("phase",current?phase:"stale_observation").put("sourcePhase",phase).put("current",current)
                .put("catalogIdentity",optional(sourceCatalog)).put("generationIdentity",optional(sourceGeneration))
                .put("failureCategory",current?category:"none").put("errorCode",current&&original.has("error")?safeCode(original.optString("error")):JSONObject.NULL)
                .put("pluginFaultAttributed",false).put("automaticRestartAllowed",false).put("replayAllowed",false);
    }
    private static JSONObject recovery(String catalog,String generation,boolean ready,JSONObject progress)throws Exception {
        boolean review=!ready||progress.getBoolean("current")&&progress.getString("phase").matches("failed|cancelled|interrupted|repair_required|observation_unavailable");
        return new JSONObject().put("previewOnly",true).put("reviewUseful",review).put("candidateCatalogIdentity",catalog)
                .put("retainedActiveGenerationIdentity",optional(generation)).put("activationRequiresActualProbe",true)
                .put("activationRequiresOriginalJobsIdle",true).put("queryOriginalOperationBeforeRetry",true)
                .put("homeRetained",true).put("projectFilesRetained",true).put("originalReceiptsRetained",true)
                .put("unknownGenerationsDeleted",false).put("replayPriorOperations",false).put("restoresApprovals",false)
                .put("automaticRestart",false).put("configurationSource","release_apk_catalog_and_original_generation_seal");
    }
}
