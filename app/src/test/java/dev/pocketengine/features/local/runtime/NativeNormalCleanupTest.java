package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;

/** Actual Linux JNI via a separate, self-owned reaper fixture. No App-wide subreaper,
 * guessed PID signal, task/grant, cached termination proof or Android-device claim. */
public final class NativeNormalCleanupTest {
    @Test public void currentContractNamesTheExactBundledOrdinarySourceWithoutChangingOtherModes()throws Exception {
        Path root=Path.of(Objects.requireNonNull(System.getProperty("pocket.gateway.source"),"original repository source required"));
        String hash=dev.pocketengine.features.local.project.ProjectSources.sha256(Files.readAllBytes(root.resolve("app/src/main/java/dev/pocketengine/features/local/runtime/native/process_supervisor.cpp")));
        assertEquals("pocketworld.posix-exit7-drain-v2:"+hash,NativeProcessSupervisor.EXIT_RESULT_CONTRACT);
        assertEquals("pocketworld.posix-pty-exit8-v1:f9132cee828fcbafd4252e71a139cb7c21839b1336111e1a762fab0afe487524",NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT);
        assertEquals("pocketworld.posix-duplex-exit8-v1:0e9ce13c7e6438484c8f855f763227e0d4c5a0ffa0ae06c1b2490fad68f92423",NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT);
    }
    private static JSONObject fixture(String mode)throws Exception {
        String python=Objects.requireNonNull(System.getProperty("a25.python"),"exact actual host Python required");
        String source=Objects.requireNonNull(System.getProperty("pocket.gateway.source"),"original repository source required");
        Path work=Files.createTempDirectory("native-normal-cleanup-");
        String cp=System.getProperty("pocketworldHostClasspath",System.getProperty("java.class.path"));
        // The existing locked real-JSON test dependency, not Android's stub org.json.
        List<String> entries=new ArrayList<>(Arrays.asList(cp.split(java.util.regex.Pattern.quote(File.pathSeparator))));
        entries.sort(Comparator.comparingInt(path->new File(path).getName().matches("json-[0-9].*\\.jar")?0:1));cp=String.join(File.pathSeparator,entries);
        List<String> command=List.of(python,"-B",new File(source,"app/src/test/java/dev/pocketengine/features/local/runtime/native_group_reaper_fixture.py").getPath(),
                "--mode",mode,"--java",new File(System.getProperty("java.home"),"bin/java").getPath(),"--classpath",cp,
                "--native",System.getProperty("java.library.path"),"--python",python,"--output",work.toString());
        File log=work.resolve("launcher.log").toFile();Process owner=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();
        assertTrue("Only this original test launcher is still settling; evidence retained at "+work,owner.waitFor(60,TimeUnit.SECONDS));
        assertEquals("Original launcher failed; private evidence retained at "+work,0,owner.exitValue());
        JSONObject record=null;for(String line:new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8).split("\\R"))if(line.startsWith("{"))record=new JSONObject(line);
        assertNotNull("No exact native child observation; private evidence retained at "+work,record);assertTrue(record.getBoolean("allCreatedProcessesReaped"));return record;
    }
    @Test public void delayedOriginalChildReapSettlesOnlyAfterActualGroupAbsence()throws Exception {
        JSONObject record=fixture("delayed-reap"),receipt=record.getJSONObject("receipt");
        assertTrue(receipt.getBoolean("cancelled"));assertTrue(receipt.getBoolean("outputComplete"));assertTrue(receipt.getBoolean("groupAbsent"));assertEquals(15,receipt.getInt("signal"));assertEquals(NativeProcessSupervisor.EXIT_RESULT_CONTRACT,receipt.getString("contract"));
        JSONArray observations=record.getJSONArray("observations");assertEquals("own_orphan_zombie_observed",observations.getJSONObject(1).getString("event"));assertEquals("exact_own_child_reaped",observations.getJSONObject(2).getString("event"));assertTrue(observations.getJSONObject(2).getLong("elapsedMillis")-observations.getJSONObject(1).getLong("elapsedMillis")>=400);assertTrue(receipt.getLong("elapsedMillis")<5000);
    }
    @Test public void groupStillPresentAtOriginalDrainDeadlineRemainsUnknownNotReleased()throws Exception {
        JSONObject record=fixture("held-through-receipt"),receipt=record.getJSONObject("receipt");
        assertTrue(receipt.getBoolean("cancelled"));assertTrue(receipt.getBoolean("outputComplete"));assertFalse(receipt.getBoolean("groupAbsent"));assertEquals(15,receipt.getInt("signal"));assertEquals(NativeProcessSupervisor.EXIT_RESULT_CONTRACT,receipt.getString("contract"));assertTrue(receipt.getLong("elapsedMillis")>=2000);assertTrue(receipt.getLong("elapsedMillis")<5000);
    }
    @Test public void exactHistoricalContractsRemainValidWithoutRelabellingThemAsTheNewAlgorithm()throws Exception {
        assertNotEquals(NativeProcessSupervisor.LEGACY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.EXIT_RESULT_CONTRACT);
        for(String contract:List.of(NativeProcessSupervisor.LEGACY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.EXIT_RESULT_CONTRACT)){
            assertTrue(NativeProcessSupervisor.knownOrdinaryExitContract(contract));JSONObject proof=new JSONObject().put("nativeResultContract",contract).put("launchPhase","no_child_created").put("noChildCreated",true).put("processLaunched",false).put("processExited",false).put("exitCode",-1).put("terminationSignal",0).put("launchErrno",4).put("timedOut",false).put("processGroupAbsent",true).put("outputComplete",true);
            assertTrue(NativeProcessSupervisor.validatesNoChildReceipt(proof));assertEquals(contract,proof.getString("nativeResultContract"));assertFalse(NativeProcessSupervisor.validatesNoChildReceipt(new JSONObject(proof.toString()).put("processGroupAbsent",false)));assertFalse(NativeProcessSupervisor.validatesNoChildReceipt(new JSONObject(proof.toString()).put("nativeResultContract",contract+"-unknown")));
        }
        assertFalse(NativeProcessSupervisor.knownOrdinaryExitContract(null));assertFalse(NativeProcessSupervisor.knownOrdinaryExitContract("pocketworld.posix-exit7-drain-v999:"+"a".repeat(64)));
    }
    /** Metadata validators only; these rows cannot create a task, native job or new import. */
    @Test public void originalConsumersKeepHistoricalSettledFactsButCannotPromoteUnknownContracts()throws Exception {
        var cache=dev.pocketengine.store.TaskStore.class.getDeclaredMethod("nativeCacheProcessDrained",JSONObject.class);cache.setAccessible(true);
        var git=dev.pocketengine.features.local.project.ProjectGitImport.class.getDeclaredMethod("drainedSuccess",JSONObject.class);git.setAccessible(true);
        String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        var reference=new dev.pocketengine.features.skills.CompanionSkillBackend.ReceiptRef("owner","private",task,run,1,operation,"a".repeat(64),"b".repeat(64),"original_installation","original_workspace","original_revision",4096,4096);
        for(String contract:List.of(NativeProcessSupervisor.LEGACY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.EXIT_RESULT_CONTRACT)){
            JSONObject row=new JSONObject().put("id",operation).put("status","completed").put("receiptReceived",true).put("processExited",true).put("resourcesReleased",true).put("processGroupAbsent",true).put("outputComplete",true).put("exitCode",0).put("nativeResultContract",contract)
                    .put("projectExecution",new JSONObject().put("taskId",task).put("runId",run).put("coordinatorEpoch",1).put("planDigest",reference.planDigest).put("intent",dev.pocketengine.features.skills.ManagedSkillBackend.INTENT));
            assertEquals(Boolean.TRUE,cache.invoke(null,row));assertEquals(Boolean.TRUE,git.invoke(null,row));assertTrue(RuntimeActivity.describe(operation,row,null,false).getBoolean("processTerminal"));
            assertEquals(dev.pocketengine.features.skills.SkillExecution.Status.COMPLETED,dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,row).status);
            JSONObject unknown=new JSONObject(row.toString()).put("nativeResultContract",contract+"-unknown");assertEquals(Boolean.FALSE,cache.invoke(null,unknown));assertEquals(Boolean.FALSE,git.invoke(null,unknown));assertFalse(RuntimeActivity.describe(operation,unknown,null,false).getBoolean("processTerminal"));assertEquals(dev.pocketengine.features.skills.SkillExecution.Status.UNKNOWN,dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,unknown).status);
            JSONObject held=new JSONObject(row.toString()).put("resourcesReleased",false);assertEquals(Boolean.FALSE,cache.invoke(null,held));assertEquals(Boolean.FALSE,git.invoke(null,held));assertFalse(RuntimeActivity.describe(operation,held,null,false).getBoolean("processTerminal"));assertEquals(dev.pocketengine.features.skills.SkillExecution.Status.UNKNOWN,dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,held).status);assertEquals(contract,row.getString("nativeResultContract"));
        }
    }
}
