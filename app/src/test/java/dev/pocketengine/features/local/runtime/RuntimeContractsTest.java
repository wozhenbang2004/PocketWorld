package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class RuntimeContractsTest {
    static final String CHECKOUT="12345678-1234-1234-1234-123456789abc";
    static JSONObject source() throws Exception {
        return new JSONObject().put("checkout",new JSONObject().put("id",CHECKOUT).put("projectId","project"))
                .put("files",new JSONArray().put(new JSONObject().put("path","Main.java").put("content","class Main {}\n")));
    }
    static JSONObject exact(JSONObject source,String command,JSONArray argv) throws Exception {
        String digest=ProjectSources.describe(source).getString("sourceDigest");
        JSONObject payload=new JSONObject().put("checkoutId",CHECKOUT);
        if(!command.equals("termux.probe"))payload.put("expectedSourceDigest",digest);
        if(command.equals("termux.run"))payload.put("executable","javac").put("argv",argv);
        return new JSONObject().put("feature","local").put("command",command).put("projectId","project").put("checkoutId",CHECKOUT)
                .put("sourceDigest",digest).put("planDigest","a".repeat(64)).put("stepId","step").put("intent","build")
                .put("payload",payload).put("requestDigest",ProjectSources.digest(payload));
    }
    static JSONObject binding(String operation) throws Exception {
        return new JSONObject().put("taskId","task").put("runId","run").put("coordinatorEpoch",1).put("operationId",operation);
    }
    static RunControl original() { RunControl control=new RunControl();control.bindHostRun("task","run",1);return control; }

    @Test public void sourceAndRequestAreFrozenCopies() throws Exception {
        JSONObject source=source(),exact=exact(source,"termux.prepare",null),binding=binding("stage");
        RuntimeInvocation invocation=new RuntimeInvocation(exact,binding,source,original(),()->{});
        source.getJSONArray("files").getJSONObject(0).put("content","changed");exact.getJSONObject("payload").put("checkoutId","changed");binding.put("runId","later");
        assertEquals("class Main {}\n",invocation.sourceSnapshot().getJSONArray("files").getJSONObject(0).getString("content"));
        assertEquals(CHECKOUT,invocation.exact().getJSONObject("payload").getString("checkoutId"));
        assertEquals("run",invocation.binding().getString("runId"));
        invocation.sourceSnapshot().getJSONArray("files").getJSONObject(0).put("content","another");
        assertEquals("class Main {}\n",invocation.sourceSnapshot().getJSONArray("files").getJSONObject(0).getString("content"));
    }
    @Test public void sourceOrRequestMismatchCannotReachRuntime() throws Exception {
        JSONObject source=source(),exact=exact(source,"termux.prepare",null);
        JSONObject changed=ProjectSources.copy(source);changed.getJSONArray("files").getJSONObject(0).put("content","other");
        assertThrows(SecurityException.class,()->new RuntimeInvocation(exact,binding("stage"),changed,original(),()->{}));
        exact.getJSONObject("payload").put("expectedSourceDigest","b".repeat(64));
        assertThrows(SecurityException.class,()->RuntimeExecutionRef.fromHost(exact,binding("stage")));
    }
    @Test public void originalControlAndLiveGateAreRequiredAfterConstruction() throws Exception {
        JSONObject source=source(),exact=exact(source,"termux.prepare",null);RunControl original=original();AtomicBoolean live=new AtomicBoolean(true);
        RuntimeInvocation invocation=new RuntimeInvocation(exact,binding("stage"),source,original,()->{if(!live.get())throw new SecurityException("revoked");});
        live.set(false);assertThrows(SecurityException.class,invocation::checkCurrent);live.set(true);
        original.pause();original.resume();assertThrows(SecurityException.class,invocation::checkCurrent);
        RunControl wrong=new RunControl();wrong.bindHostRun("task","later",2);
        assertThrows(SecurityException.class,()->new RuntimeInvocation(exact,binding("stage"),source,wrong,()->{}));
    }
    @Test public void terminalReceiptMustMatchOriginalRunSourceAndProject() throws Exception {
        RuntimeExecutionRef ref=RuntimeExecutionRef.fromHost(exact(source(),"termux.prepare",null),binding("stage"));
        JSONObject backend=new JSONObject().put("id","stage").put("operationId","stage").put("checkoutId",CHECKOUT)
                .put("sourceDigest",ref.sourceDigest()).put("projectExecution",ref.executionLink()).put("status","completed").put("exitCode",0).put("receiptReceived",true);
        RuntimeReceipt receipt=RuntimeReceipt.fromBackend(ref,backend);assertTrue(receipt.terminal());
        backend.put("stdout","mutated");assertFalse(receipt.json().has("stdout"));
        backend.getJSONObject("projectExecution").put("runId","later");assertThrows(SecurityException.class,()->RuntimeReceipt.fromBackend(ref,backend));
    }
    @Test public void acceptedOrCancelledWithoutExitIsNeverTerminal() throws Exception {
        RuntimeExecutionRef ref=RuntimeExecutionRef.fromHost(exact(source(),"termux.prepare",null),binding("stage"));
        JSONObject receipt=new JSONObject().put("id","stage").put("operationId","stage").put("checkoutId",CHECKOUT)
                .put("sourceDigest",ref.sourceDigest()).put("projectExecution",ref.executionLink()).put("status","awaiting_result");
        assertFalse(RuntimeReceipt.fromBackend(ref,receipt).terminal());
        receipt.put("status","unknown").put("cancelRequested",true);assertFalse(RuntimeReceipt.fromBackend(ref,receipt).terminal());
        receipt.put("status","completed").put("receiptReceived",true).put("exitCode","0");assertFalse(RuntimeReceipt.fromBackend(ref,receipt).terminal());
    }
}
