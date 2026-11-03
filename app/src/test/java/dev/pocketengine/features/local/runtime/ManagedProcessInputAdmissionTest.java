package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectCommandSpec;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Original provider admission only. No native library, process or device is needed. */
public final class ManagedProcessInputAdmissionTest {
    private Path root;
    private ManagedProjectRuntime runtime;
    private RunControl original;
    private RuntimeExecutionRef reference;
    private final List<ManagedProcessJobs.ProcessInputReceipt> receipts=new ArrayList<>();
    private JSONObject request;
    @Before public void create()throws Exception {
        request=new JSONObject().put("action","text").put("text","never queued\n");
        root=Files.createTempDirectory("pty-input-admission-");
        runtime=ManagedProjectRuntime.unconfigured(root.toFile());runtime.setProcessInputSink(receipts::add);
        original=RuntimeContractsTest.original();JSONObject source=RuntimeContractsTest.source();
        JSONObject payload=ProjectCommandSpec.normalize(new JSONObject().put("executable","python3").put("argv",new JSONArray().put("-V")).put("runMode","owned_service").put("terminal","pty").put("inputByteLimit",100))
                .put("checkoutId",RuntimeContractsTest.CHECKOUT).put("expectedSourceDigest",ProjectSources.describe(source).getString("sourceDigest")).put("genericCommandVersion",1).put("terminalNativeContract",NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT);
        JSONObject exact=RuntimeContractsTest.exact(source,"termux.run",new JSONArray()).put("command","project.runtime.run").put("intent","command").put("runtimeId",ManagedProjectRuntime.RUNTIME_ID).put("runtimeIdentity",runtime.identity()).put("payload",payload).put("requestDigest",ProjectSources.digest(payload));
        reference=RuntimeExecutionRef.fromHost(exact,RuntimeContractsTest.binding("original-service"));
    }
    @After public void close()throws Exception {
        try{if(runtime!=null)runtime.close();}
        finally{if(root!=null)try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private void assertOriginalNeverQueued(JSONObject result,String operation)throws Exception {
        assertEquals("not_dispatched",result.getString("status"));assertTrue(result.getBoolean("nativeQueueNeverEntered"));assertFalse(result.getBoolean("nativeAcknowledged"));assertEquals(0,result.getInt("inputSequence"));assertEquals(0,result.getInt("writtenBytes"));assertFalse(result.getBoolean("replayAllowed"));
        assertEquals(1,receipts.size());ManagedProcessJobs.ProcessInputReceipt proof=receipts.get(0);assertSame(original,proof.originalControl());assertSame(reference,proof.execution());JSONObject actual=proof.json();assertEquals(operation,actual.getString("inputOperationId"));assertEquals(ProjectSources.digest(request),actual.getString("inputDigest"));assertEquals("original-service",actual.getJSONObject("projectExecution").getString("operationId"));assertFalse(actual.toString().contains("never queued"));
    }
    @Test public void providerCancellationBeforeQueueStillPublishesOriginalTypedEvidence()throws Exception {
        original.cancel();
        assertOriginalNeverQueued(runtime.processInput(reference,original,"cancelled-input",request,()->{fail("cancelled input must never reach its live input gate");}),"cancelled-input");
    }
    @Test public void threadInterruptionBeforeQueueKeepsInterruptAndPublishesTypedEvidence()throws Exception {
        Thread.currentThread().interrupt();
        try{
            assertOriginalNeverQueued(runtime.processInput(reference,original,"interrupted-input",request,()->{fail("interrupted input must never reach its live input gate");}),"interrupted-input");
            assertTrue("receipt publication must not clear the caller's interruption",Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
    @Test public void foreignProviderOrRunCannotMintOriginalInputEvidence()throws Exception {
        RunControl foreign=new RunControl();foreign.bindHostRun("task","later-run",2);
        assertThrows(SecurityException.class,()->runtime.processInput(reference,foreign,"foreign-run",request,()->{}));
        RuntimeExecutionRef other=RuntimeExecutionRef.fromHost(reference.exact().put("runtimeId","termux"),reference.binding());
        assertThrows(SecurityException.class,()->runtime.processInput(other,original,"foreign-provider",request,()->{}));
        assertTrue(receipts.isEmpty());
    }
}
