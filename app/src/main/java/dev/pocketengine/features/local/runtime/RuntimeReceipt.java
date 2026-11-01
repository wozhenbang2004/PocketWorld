package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import org.json.JSONObject;

/** Observed backend receipt, compatible with the existing project journal; no task state authority. */
public final class RuntimeReceipt {
    private final RuntimeExecutionRef reference;
    private final JSONObject receipt;
    private RuntimeReceipt(RuntimeExecutionRef reference,JSONObject actual) throws Exception {
        this.reference=java.util.Objects.requireNonNull(reference);receipt=ProjectSources.copy(actual);reference.requireReceipt(receipt);
    }
    public static RuntimeReceipt fromBackend(RuntimeExecutionRef reference,JSONObject actual) throws Exception {
        return new RuntimeReceipt(reference,actual);
    }
    public RuntimeExecutionRef reference() { return reference; }
    public JSONObject json() throws Exception { return ProjectSources.copy(receipt); }
    public String status() { return receipt.optString("status","unknown"); }
    public boolean terminal() {
        Object exit=receipt.opt("exitCode");
        return receipt.optBoolean("receiptReceived")&&(exit instanceof Integer||exit instanceof Long)
                &&(status().equals("completed")||status().equals("failed"));
    }
}
