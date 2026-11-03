package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Projection fixtures only. No Android runtime, tool probe or plugin was executed. */
public final class RuntimeHealthTest {
    private static AndroidRuntimeCatalog catalog()throws Exception {
        JSONObject archive=new JSONObject().put("url","https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/fixture.tar.gz").put("archiveBytes",1).put("archiveSha256","a".repeat(64)).put("unpackedBytes",1000).put("stripComponents",0).put("format","tar.gz");
        JSONObject node=new JSONObject(archive.toString()).put("url","https://nodejs.org/dist/v24.22.2/fixture.tar.xz").put("stripComponents",1).put("format","tar.xz");
        JSONArray tools=new JSONArray();for(String name:new String[]{"sh","node","npm","python3","git"})tools.put(new JSONObject().put("name",name).put("path","/usr/bin/"+name).put("license","fixture-only").put("versionArguments",new JSONArray().put("--version")));
        return new AndroidRuntimeCatalog(new JSONObject().put("schema","pocketworld.android-runtime.v1").put("profileId","fixture").put("revision","fixture-v1").put("abi","arm64-v8a").put("libc","glibc").put("distribution","fixture")
                .put("engine",new JSONObject().put("prootLibrary","libproot.so").put("loaderLibrary","libloader.so").put("prootSha256","b".repeat(64)).put("loaderSha256","c".repeat(64)).put("sourceLockSha256","e".repeat(64)))
                .put("rootfs",archive).put("node",node).put("systemPackages",new JSONArray().put("ca-certificates").put("git").put("python3").put("python3-venv").put("python3-pip"))
                .put("aptSources","Types: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports/\nSuites: noble noble-updates noble-security\nComponents: main universe\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n")
                .put("dnsServers",new JSONArray().put("1.1.1.1")).put("tools",tools));
    }
    private static JSONObject active(AndroidRuntimeCatalog catalog)throws Exception {return new JSONObject().put("generationIdentity","d".repeat(64)).put("catalogIdentity",catalog.identity).put("catalog",catalog.descriptor()).put("installedPackages","python3\t3.12.3\ngit\t2.43.0\n");}
    @Test public void networkFailureIsNotAPluginStrikeOrAutomaticRestartPermission()throws Exception {
        JSONObject fault=RuntimeHealth.failure(new SocketTimeoutException("credential fixture-secret should not leave failure"));
        assertEquals("network",fault.getString("category"));assertFalse(fault.getBoolean("pluginFaultAttributed"));assertFalse(fault.getBoolean("automaticRestartAllowed"));assertFalse(fault.toString().contains("fixture-secret"));
    }
    @Test public void explicitNativeCancellationAndItsCauseStaySeparateFromNetworkFaults()throws Exception {
        assertEquals("cancelled",RuntimeHealth.failure(new IOException("outer",new InterruptedException("stop"))).getString("category"));
        assertFalse(RuntimeHealth.failure(new InterruptedException("stop")).getBoolean("replayAllowed"));
    }
    @Test public void concreteIntegrityStorageBusyAndProbeFactsAreClassifiedWithoutRawLogs()throws Exception {
        String[][] facts={{"runtime_package_hash_mismatch","integrity"},{"runtime_environment_insufficient_space","storage"},{"runtime_environment_has_live_or_unresolved_job","resource_busy"},{"runtime_bootstrap_command_failed_exit_7_errno_0","probe"},{"arbitrary text accusing a plugin","unknown"}};
        for(String[] fact:facts)assertEquals(fact[0],fact[1],RuntimeHealth.failure(new IOException(fact[0])).getString("category"));
    }
    @Test public void staleGenerationAndCatalogFailureCannotReplaceCurrentHealth()throws Exception {
        AndroidRuntimeCatalog catalog=catalog();JSONObject active=active(catalog);
        for(JSONObject old:new JSONObject[]{new JSONObject().put("catalogIdentity","f".repeat(64)),new JSONObject().put("generationIdentity","f".repeat(64))}){
            old.put("phase","failed").put("error","runtime_package_hash_mismatch");JSONObject health=RuntimeHealth.android(catalog,active,new JSONArray(),old,true,true,true);
            assertTrue(health.getBoolean("executionReady"));assertEquals("stale_observation",health.getJSONObject("preparation").getString("phase"));assertEquals("none",health.getJSONObject("preparation").getString("failureCategory"));assertFalse(health.getJSONObject("recoveryPreview").getBoolean("reviewUseful"));
        }
    }
    @Test public void metadataReadabilityIsNotAnExecutionOrTaskCompletionFact()throws Exception {
        AndroidRuntimeCatalog catalog=catalog();JSONObject progress=new JSONObject().put("phase","observation_unavailable").put("error","runtime_preparation_state_unreadable").put("privateLog","fixture-secret-body");
        JSONObject health=RuntimeHealth.android(catalog,active(catalog),new JSONArray(),progress,true,true,true);
        assertTrue(health.getBoolean("executionReady"));assertEquals("observation",health.getJSONObject("preparation").getString("failureCategory"));assertFalse(health.getBoolean("businessVerified"));assertFalse(health.getBoolean("logsIncluded"));assertFalse(health.toString().contains("fixture-secret-body"));
    }
    @Test public void componentAndPackageIdentitiesComeFromTheOriginalSealNotInventedBuildPins()throws Exception {
        AndroidRuntimeCatalog catalog=catalog();JSONObject health=RuntimeHealth.android(catalog,active(catalog),new JSONArray(),new JSONObject(),true,true,true);
        assertEquals(catalog.identity,health.getString("releaseCatalogIdentity"));assertEquals("d".repeat(64),health.getString("activeGenerationIdentity"));assertEquals("e".repeat(64),health.getJSONArray("components").getJSONObject(0).getString("sourceLockSha256"));
        assertEquals("a".repeat(64),health.getJSONArray("components").getJSONObject(1).getString("archiveSha256"));assertEquals(2,health.getJSONObject("installedPackageManifest").getInt("packageCount"));assertFalse(health.getJSONObject("installedPackageManifest").getBoolean("buildTimePackagePinsClaimed"));
    }
    @Test public void currentToolMetadataDoesNotClaimANewFullHashOrFreshProbe()throws Exception {
        AndroidRuntimeCatalog catalog=catalog();JSONArray tools=new JSONArray().put(new JSONObject().put("name","python3").put("sha256","a".repeat(64)).put("observedVersion","Python 3.12.3").put("installed",true).put("probeVerified",true));
        JSONObject observed=RuntimeHealth.android(catalog,active(catalog),tools,new JSONObject(),true,true,true).getJSONArray("tools").getJSONObject(0);
        assertEquals("a".repeat(64),observed.getString("sealedSha256"));assertTrue(observed.getBoolean("metadataUnchanged"));assertEquals("sealed_at_install_metadata_checked_now_not_current_full_hash",observed.getString("hashValidationScope"));assertFalse(observed.has("hashVerifiedNow"));
    }
    @Test public void recoveryIsAReadOnlyPreviewAndCannotReplayClearHomeOrRestoreApproval()throws Exception {
        AndroidRuntimeCatalog catalog=catalog();JSONObject preview=RuntimeHealth.android(catalog,active(catalog),new JSONArray(),new JSONObject(),true,false,false).getJSONObject("recoveryPreview");
        assertTrue(preview.getBoolean("previewOnly"));assertTrue(preview.getBoolean("reviewUseful"));assertTrue(preview.getBoolean("activationRequiresOriginalJobsIdle"));assertTrue(preview.getBoolean("homeRetained"));assertTrue(preview.getBoolean("projectFilesRetained"));assertFalse(preview.getBoolean("replayPriorOperations"));assertFalse(preview.getBoolean("restoresApprovals"));assertFalse(preview.getBoolean("automaticRestart"));
    }
}
