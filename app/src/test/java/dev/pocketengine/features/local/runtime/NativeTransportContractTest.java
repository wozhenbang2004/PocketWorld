package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.json.JSONObject;
import org.junit.Test;

public final class NativeTransportContractTest {
    @Test public void currentTransportContractsNameTheExactBundledOwnershipAlgorithms()throws Exception {
        Path root=Path.of(Objects.requireNonNull(System.getProperty("pocket.gateway.source"),"exact repository source required"))
                .resolve("app/src/main/java/dev/pocketengine/features/local/runtime/native");
        String pty=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("pty_supervisor.cpp"))));
        String pipes=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("pipe_supervisor.cpp"))));
        assertEquals("pocketworld.posix-pty-exit8-owner-v2:"+pty,NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT);
        assertEquals("pocketworld.posix-duplex-exit8-owner-v2:"+pipes,NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT);
    }
    @Test public void historicalSessionReceiptsKeepTheirExactFactsWithoutBecomingNewBindings()throws Exception {
        assertEquals("pocketworld.posix-pty-exit8-v1:f9132cee828fcbafd4252e71a139cb7c21839b1336111e1a762fab0afe487524",NativeProcessSupervisor.LEGACY_PTY_EXIT_RESULT_CONTRACT);
        assertEquals("pocketworld.posix-duplex-exit8-v1:0e9ce13c7e6438484c8f855f763227e0d4c5a0ffa0ae06c1b2490fad68f92423",NativeProcessSupervisor.LEGACY_PIPE_EXIT_RESULT_CONTRACT);
        assertNotEquals(NativeProcessSupervisor.LEGACY_PTY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT);
        assertNotEquals(NativeProcessSupervisor.LEGACY_PIPE_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT);
        for(String contract:List.of(NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT,
                NativeProcessSupervisor.LEGACY_PTY_EXIT_RESULT_CONTRACT,NativeProcessSupervisor.LEGACY_PIPE_EXIT_RESULT_CONTRACT)) {
            JSONObject receipt=new JSONObject().put("nativeResultContract",contract).put("status","completed").put("receiptReceived",true)
                    .put("resourcesReleased",true).put("processExited",true).put("processGroupAbsent",true).put("processSessionAbsent",true);
            assertTrue(RuntimeActivity.describe("original",receipt,null,false).getBoolean("processTerminal"));
            assertEquals(contract,receipt.getString("nativeResultContract"));
            assertFalse(RuntimeActivity.describe("original",new JSONObject(receipt.toString()).put("processSessionAbsent",false),null,false).getBoolean("processTerminal"));
            assertFalse(RuntimeActivity.describe("original",new JSONObject(receipt.toString()).put("nativeResultContract",contract+"-unknown"),null,false).getBoolean("processTerminal"));
            JSONObject absent=new JSONObject().put("nativeResultContract",contract).put("launchPhase","no_child_created").put("noChildCreated",true)
                    .put("processLaunched",false).put("processExited",false).put("exitCode",-1).put("terminationSignal",0).put("launchErrno",4)
                    .put("timedOut",false).put("processGroupAbsent",true).put("processSessionAbsent",true).put("outputComplete",true);
            assertTrue(NativeProcessSupervisor.validatesNoChildReceipt(absent));assertEquals(contract,absent.getString("nativeResultContract"));
            assertFalse(NativeProcessSupervisor.validatesNoChildReceipt(new JSONObject(absent.toString()).put("processSessionAbsent",false)));
        }
        assertFalse(NativeProcessSupervisor.knownSessionExitContract(null));
        assertFalse(NativeProcessSupervisor.knownSessionExitContract("pocketworld.posix-pty-exit8-owner-v999:"+"a".repeat(64)));
    }
}
