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
    @Test public void currentContractsNameTheExactBundledSourcesWithoutRelabellingHistoricalModes()throws Exception {
        Path root=Path.of(Objects.requireNonNull(System.getProperty("pocket.gateway.source"),"original repository source required"));
        String hash=dev.pocketengine.features.local.project.ProjectSources.sha256(Files.readAllBytes(root.resolve("app/src/main/java/dev/pocketengine/features/local/runtime/native/process_supervisor.cpp")));
        assertEquals("pocketworld.posix-exit7-owner-v3:"+hash,NativeProcessSupervisor.EXIT_RESULT_CONTRACT);
        String oldHash=dev.pocketengine.features.local.project.ProjectSources.sha256(Files.readAllBytes(root.resolve("app/src/test/resources/runtime/ordinary-process-protocol2.cpp")));
        assertEquals("pocketworld.posix-exit7-drain-v2:"+oldHash,NativeProcessSupervisor.DRAIN_V2_EXIT_RESULT_CONTRACT);
        assertEquals("pocketworld.posix-pty-exit8-v1:f9132cee828fcbafd4252e71a139cb7c21839b1336111e1a762fab0afe487524",NativeProcessSupervisor.LEGACY_PTY_EXIT_RESULT_CONTRACT);
        assertEquals("pocketworld.posix-duplex-exit8-v1:0e9ce13c7e6438484c8f855f763227e0d4c5a0ffa0ae06c1b2490fad68f92423",NativeProcessSupervisor.LEGACY_PIPE_EXIT_RESULT_CONTRACT);
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
    @Test public void ordinaryUnknownRetainsItsActualLeaseThroughColdReadCancelAndClose()throws Exception {
        assertManagedLeaseHeld(fixture("managed-held-through-receipt"));
    }
    @Test public void preparationUnknownRetainsItsActualLeaseWithoutReplayingItsSubprocess()throws Exception {
        assertManagedLeaseHeld(fixture("preparation-held-through-receipt"));
    }
    private static JSONObject lostWait(String mode,int after,boolean old)throws Exception {
        String cp=System.getProperty("pocketworldHostClasspath",System.getProperty("java.class.path"));List<String> entries=new ArrayList<>(Arrays.asList(cp.split(java.util.regex.Pattern.quote(File.pathSeparator))));entries.sort(Comparator.comparingInt(path->new File(path).getName().matches("json-[0-9].*\\.jar")?0:1));
        Path work=Files.createTempDirectory("native-lost-wait-");String library=Objects.requireNonNull(System.getProperty(old?"pocketworldHostOldLostWaitDir":"pocketworldHostLostWaitDir"),"isolated real wait/reap JNI fixture required");
        Process process=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),"-Djava.library.path="+library,"-cp",String.join(File.pathSeparator,entries),LostWaitOwnershipProbe.class.getName(),mode,Objects.requireNonNull(System.getProperty("a25.python")),work.toString(),Integer.toString(after)).redirectErrorStream(true).redirectOutput(work.resolve("probe.log").toFile()).start();
        boolean finished;try{finished=process.waitFor(15,TimeUnit.SECONDS);}finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}}
        assertTrue("Own probe timed out; exact child cleanup unconfirmed; evidence="+work,finished);String output=new String(Files.readAllBytes(work.resolve("probe.log")),StandardCharsets.UTF_8);JSONObject result=null;for(String line:output.split("\\R"))if(line.startsWith("{"))result=new JSONObject(line);
        assertNotNull("Missing native facts: "+output,result);assertEquals("Original probe failed: "+output,old?124:0,process.exitValue());JSONObject facts=result.getJSONObject("facts");assertEquals(1,facts.getInt("actualReapCount"));assertTrue(facts.getLong("exactReapedPid")>0);assertEquals(7<<8,facts.getInt("realWaitStatus"));assertTrue(facts.getInt("realEchildCount")>0);
        if(old){assertTrue(result.getBoolean("watchdogAfterActualReap"));assertTrue(facts.getInt("signalsAfterReap")>0);}else{assertEquals(0,facts.getInt("signalsAfterReap"));assertTrue(result.getLong("elapsedMillis")<5000);}return result;
    }
    @Test public void originalProtocolTwoActuallyFailsToConvergeAfterAnotherThreadReapsItsExactLeader()throws Exception {lostWait("direct",1,true);}
    @Test public void lostOriginalWaitOwnershipReturnsUnknownWithoutSignallingAReapedNumber()throws Exception {assertLostExit(lostWait("direct",1,false));}
    @Test public void alreadyObservedZombieStillRequiresCurrentWaitOwnershipBeforeCleanup()throws Exception {assertLostExit(lostWait("direct",2,false));}
    private static void assertLostExit(JSONObject result)throws Exception {JSONObject exit=result.getJSONObject("receipt");assertEquals(-1,exit.getInt("exitCode"));assertEquals(0,exit.getInt("signal"));assertEquals(0,exit.getInt("launchErrno"));assertFalse(exit.getBoolean("groupAbsent"));assertFalse(exit.getBoolean("outputComplete"));assertFalse(exit.getBoolean("noChildCreated"));assertEquals(NativeProcessSupervisor.EXIT_RESULT_CONTRACT,exit.getString("contract"));}
    @Test public void lostWaitThroughOriginalManagedCommandRetainsTheActualLeaseAndColdIdentity()throws Exception {assertLostManaged(lostWait("command",1,false));}
    @Test public void lostWaitThroughOriginalPreparationRetainsTheActualLeaseWithoutReplay()throws Exception {assertLostManaged(lostWait("preparation",1,false));}
    private static void assertLostManaged(JSONObject result)throws Exception {
        JSONObject receipt=result.getJSONObject("receipt"),actual=receipt.getJSONObject("actual"),cold=receipt.getJSONObject("cold"),cancel=receipt.getJSONObject("coldCancel");
        assertEquals(0,receipt.getInt("leaseCloseCount"));assertTrue(receipt.getBoolean("closeRejected"));assertTrue(receipt.getBoolean("maintenanceRejected"));
        assertEquals("unknown",actual.getString("status"));assertFalse(actual.getBoolean("resourcesReleased"));assertFalse(actual.getBoolean("processGroupAbsent"));assertFalse(actual.getBoolean("ownedWorkerLive"));assertFalse(actual.optBoolean("noChildCreated"));
        assertEquals("unknown",cold.getString("status"));assertFalse(cold.getBoolean("resourcesReleased"));assertEquals("no_owned_live_process",cancel.getString("cancelDelivery"));assertEquals(actual.getJSONObject("projectExecution").toString(),cold.getJSONObject("projectExecution").toString());assertEquals(actual.getString("operationId"),cancel.getString("operationId"));assertEquals(1,result.getJSONObject("facts").getInt("actualReapCount"));
    }
    private static void assertManagedLeaseHeld(JSONObject record)throws Exception {
        JSONObject result=record.getJSONObject("receipt"),actual=result.getJSONObject("actual"),cold=result.getJSONObject("cold"),cancel=result.getJSONObject("coldCancel");
        assertEquals(0,result.getInt("leaseCloseCount"));assertTrue(result.getBoolean("closeRejected"));assertTrue(result.getBoolean("maintenanceRejected"));
        assertEquals("unknown",actual.getString("status"));assertFalse(actual.getBoolean("processGroupAbsent"));assertFalse(actual.getBoolean("resourcesReleased"));assertFalse(actual.getBoolean("ownedWorkerLive"));
        assertEquals("unknown",cold.getString("status"));assertFalse(cold.getBoolean("resourcesReleased"));assertEquals("no_owned_live_process",cancel.getString("cancelDelivery"));assertFalse(cancel.getBoolean("resourcesReleased"));
        assertEquals(actual.getJSONObject("projectExecution").toString(),cold.getJSONObject("projectExecution").toString());
        assertEquals(actual.getString("operationId"),cancel.getString("operationId"));assertTrue(result.getLong("elapsedMillis")<8000);
        assertEquals("exact_own_child_reaped",record.getJSONArray("observations").getJSONObject(2).getString("event"));
    }
    @Test public void exactHistoricalContractsRemainValidWithoutRelabellingThemAsTheNewAlgorithm()throws Exception {
        assertNotEquals(NativeProcessSupervisor.LEGACY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.EXIT_RESULT_CONTRACT);
        for(String contract:List.of(NativeProcessSupervisor.LEGACY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.DRAIN_V2_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.PRE_FORMAT_OWNER_V3_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.EXIT_RESULT_CONTRACT)){
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
        for(String contract:List.of(NativeProcessSupervisor.LEGACY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.DRAIN_V2_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.PRE_FORMAT_OWNER_V3_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.EXIT_RESULT_CONTRACT)){
            JSONObject row=new JSONObject().put("id",operation).put("status","completed").put("receiptReceived",true).put("processExited",true).put("resourcesReleased",true).put("processGroupAbsent",true).put("outputComplete",true).put("exitCode",0).put("nativeResultContract",contract)
                    .put("projectExecution",new JSONObject().put("taskId",task).put("runId",run).put("coordinatorEpoch",1).put("planDigest",reference.planDigest).put("intent",dev.pocketengine.features.skills.ManagedSkillBackend.INTENT));
            assertEquals(Boolean.TRUE,cache.invoke(null,row));assertEquals(Boolean.TRUE,git.invoke(null,row));assertTrue(RuntimeActivity.describe(operation,row,null,false).getBoolean("processTerminal"));
            assertEquals(dev.pocketengine.features.skills.SkillExecution.Status.COMPLETED,dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,row).status);
            JSONObject unknown=new JSONObject(row.toString()).put("nativeResultContract",contract+"-unknown");assertEquals(Boolean.FALSE,cache.invoke(null,unknown));assertEquals(Boolean.FALSE,git.invoke(null,unknown));assertFalse(RuntimeActivity.describe(operation,unknown,null,false).getBoolean("processTerminal"));assertEquals(dev.pocketengine.features.skills.SkillExecution.Status.UNKNOWN,dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,unknown).status);
            JSONObject held=new JSONObject(row.toString()).put("resourcesReleased",false);assertEquals(Boolean.FALSE,cache.invoke(null,held));assertEquals(Boolean.FALSE,git.invoke(null,held));assertFalse(RuntimeActivity.describe(operation,held,null,false).getBoolean("processTerminal"));assertEquals(dev.pocketengine.features.skills.SkillExecution.Status.UNKNOWN,dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,held).status);assertEquals(contract,row.getString("nativeResultContract"));
            // These are original unreleased facts, not a forged released receipt. The Skill adapter
            // deliberately relies on upstream resourcesReleased; it is not a second cleanup verifier.
            for(String missing:new String[]{"processGroupAbsent","outputComplete"}){
                JSONObject incomplete=new JSONObject(row.toString()).put(missing,false).put("resourcesReleased",false);
                assertEquals(dev.pocketengine.features.skills.SkillExecution.Status.UNKNOWN,dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,incomplete).status);
            }
            JSONObject lostWait=new JSONObject(row.toString()).put("processExited",false).put("processGroupAbsent",false).put("outputComplete",false).put("resourcesReleased",false).put("exitCode",-1);
            assertEquals(Boolean.FALSE,cache.invoke(null,lostWait));assertEquals(Boolean.FALSE,git.invoke(null,lostWait));assertEquals(dev.pocketengine.features.skills.SkillExecution.Status.UNKNOWN,dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,lostWait).status);
            JSONObject wrongIdentity=new JSONObject(row.toString()).put("id",UUID.randomUUID().toString());
            assertThrows(SecurityException.class,()->dev.pocketengine.features.skills.ManagedSkillBackend.receipt(reference,wrongIdentity));

        }
    }
}
