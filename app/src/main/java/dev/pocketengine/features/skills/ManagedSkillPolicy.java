package dev.pocketengine.features.skills;

import java.util.*;
import org.json.*;

/** Native configuration metadata, NOT permission to execute. Only the original
 * foreground OwnerWindow may install it; every concrete Skill still needs its
 * exact per-operation plan approval in the original TaskStore. */
public final class ManagedSkillPolicy {
    public static final String FORMAT="pocketworld.skill-phone-configuration.v1",CONTRACT="managed_same_uid_owner_reviewed_v1";
    private ManagedSkillPolicy(){}
    /** Identity-only carrier is never decoded from skills_execute/model args. */
    static final class Authority {
        final String fingerprint;final boolean prepareDependencies,immediatePrivateArtifacts;
        private Authority(String fingerprint,boolean prepareDependencies,boolean immediatePrivateArtifacts){this.fingerprint=fingerprint;this.prepareDependencies=prepareDependencies;this.immediatePrivateArtifacts=immediatePrivateArtifacts;}
        JSONObject risks()throws Exception{return new JSONObject().put("contract",CONTRACT).put("executionLocation","this_phone").put("osSandbox",false).put("network","possible_same_uid").put("fileScopeOsEnforced",false).put("packageReadOnlyOsEnforced",false).put("environment","native_controlled_minimal").put("stdin","closed").put("cancellationScope","original_owned_process_group_and_observed_descendants").put("allDescendantsTerminationGuaranteed",false).put("dependencyPreparationAllowed",prepareDependencies).put("immediatePrivateArtifacts",immediatePrivateArtifacts).put("privateArtifactPublicationScope","approved_output_paths_only;original_private_task;no_external_transmission").put("configurationFingerprint",fingerprint).put("maxPackageBytes",131072).put("maxInputFiles",200).put("maxInputBytes",2*1024*1024).put("perFileRuntimeSizeLimit",64L*1024*1024).put("observedTemporaryStorageLimit",256L*1024*1024).put("observedTemporaryEntriesLimit",20000).put("temporaryStorageHardQuota",false).put("memoryIsolationGuaranteed",false).put("unknownCopiesAutomaticallyRemoved",false);}
    }
    static Authority authority(JSONObject ownerConfiguration)throws Exception {JSONObject checked=checked(ownerConfiguration);return new Authority(SkillExecution.digestJson(checked),checked.getBoolean("preparePinnedDependencies"),checked.optBoolean("immediatePrivateArtifacts"));}
    static JSONObject checked(JSONObject source)throws Exception {
        JSONObject copy=new JSONObject(source.toString());Set<String> actual=new HashSet<>();copy.keys().forEachRemaining(actual::add);Set<String> allowed=new HashSet<>(Set.of("format","runtimeId","checkoutId","workingDirectory","readPaths","writePaths","outputPaths","timeoutSeconds","maxOutputBytes","maxResultChars","preparePinnedDependencies"));if(copy.has("immediatePrivateArtifacts")){allowed.add("immediatePrivateArtifacts");if(!(copy.opt("immediatePrivateArtifacts") instanceof Boolean))throw new IllegalArgumentException("即刻私有成果须由主人明确选择");}if(!actual.equals(allowed)||!FORMAT.equals(copy.optString("format"))||!Set.of("python3","sh").contains(copy.optString("runtimeId"))||!(copy.opt("preparePinnedDependencies") instanceof Boolean))throw new IllegalArgumentException("本机Skill仅支持明确的Python/Shell及主人原生准备字段，不接受网络隔离自报或任意命令");String checkout=copy.getString("checkoutId");if(!checkout.isEmpty()&&!checkout.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("请选择原本机项目副本或明确的空临时工作区");
        // Reuse the canonical request's relative path/argv/budget validation.
        new SkillExecution.Request("scripts/check.py",copy.getString("runtimeId"),checkout.isEmpty()?"native_task_copy":checkout,copy.getString("workingDirectory"),List.of(),strings(copy.getJSONArray("readPaths")),strings(copy.getJSONArray("writePaths")),strings(copy.getJSONArray("outputPaths")),copy.getInt("timeoutSeconds"),copy.getInt("maxOutputBytes"),copy.getInt("maxResultChars"));
        if(checkout.isEmpty()&&copy.getJSONArray("readPaths").length()!=0)throw new IllegalArgumentException("空临时工作区没有可读取的原项目文件，请先选择原项目副本");return copy;
    }
    static List<String> strings(JSONArray rows)throws Exception{List<String> values=new ArrayList<>();for(int i=0;i<rows.length();i++)values.add(rows.getString(i));return values;}
    static SkillExecution.Request request(JSONObject configuration,Authority nativeAuthority,String script,List<String> arguments,int seconds,int output)throws Exception {
        JSONObject q=checked(configuration);if(!nativeAuthority.fingerprint.equals(SkillExecution.digestJson(q)))throw new SecurityException("本机Skill准备元数据已变化");String checkout=q.getString("checkoutId");return SkillExecution.Request.managed(nativeAuthority,script,q.getString("runtimeId"),checkout.isEmpty()?"native_task_copy":checkout,q.getString("workingDirectory"),arguments,strings(q.getJSONArray("readPaths")),strings(q.getJSONArray("writePaths")),strings(q.getJSONArray("outputPaths")),Math.min(seconds,q.getInt("timeoutSeconds")),Math.min(output,q.getInt("maxOutputBytes")),q.getInt("maxResultChars"));
    }
}
