package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.plugins.mcp.McpJson;
import dev.pocketengine.features.plugins.mcp.McpTaskCoordinator;
import dev.pocketengine.features.plugins.mcp.McpTaskCoordinator.Access;
import dev.pocketengine.features.plugins.mcp.McpTaskCoordinator.Binding;
import java.time.Instant;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** The existing operations row is the only remote-task journal. This class creates no task,
 * grant, budget, table, runner or transport. TaskStore supplies admission; PluginsModule keeps
 * the live installation/credential checks. Real late receipts may settle their original row. */
public final class TaskStoreMcpTasks {
    public static final String KEY = "remoteMcp";
    public static final String FORMAT = "pocketworld.remote-mcp-task.v1";
    private static final int MAX_RECEIPT_BYTES = 3_145_728;
    private static final String UNRESOLVED = "'prepared','unknown','project_running','project_stopping',"
            + "'remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending',"
            + "'remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown'";

    /** Runs under the original TaskStore lock. Recovery must require an explicit native owner
     * context and the exact historical row; it never borrows a newer run's execution authority. */
    public interface Authority {
        void check(Binding binding, Access access, boolean recovery) throws Exception;
    }

    private final Object lock;
    private final SQLiteDatabase db;
    private final Authority authority;

    public TaskStoreMcpTasks(Object lock, SQLiteDatabase db, Authority authority) {
        this.lock = Objects.requireNonNull(lock);
        this.db = Objects.requireNonNull(db);
        this.authority = Objects.requireNonNull(authority);
    }

    public McpTaskCoordinator.Host host(String nativeTool, boolean recovery) {
        if (nativeTool == null || !nativeTool.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Original native tool required");
        return new McpTaskCoordinator.Host() {
            @Override public void check(Binding binding, Access access) throws Exception {
                synchronized (lock) {
                    if (recovery && access == Access.DISPATCH)
                        throw new SecurityException("Recovered MCP tasks may only be queried or cancelled");
                    authority.check(binding, access, recovery);
                    row(binding, nativeTool);
                }
            }

            @Override public JSONObject load(Binding binding) throws Exception {
                synchronized (lock) {
                    Row row = row(binding, nativeTool);
                    if (row.receipt == null) return null;
                    return copy(envelope(row, binding, nativeTool).getJSONObject("receipt"));
                }
            }

            @Override public void reserve(Binding binding, String argumentsDigest, JSONObject reservation) throws Exception {
                synchronized (lock) {
                    if (recovery) throw new SecurityException("Recovery cannot dispatch tools/call");
                    db.beginTransaction();
                    try {
                        authority.check(binding, Access.DISPATCH, false);
                        Row row = row(binding, nativeTool);
                        if (!row.state.equals("prepared") || row.receipt != null)
                            throw new SecurityException("MCP operation already reserved; query its original receipt");
                        if (!McpJson.digest(new JSONObject(row.arguments)).equals(argumentsDigest))
                            throw new SecurityException("Original prepared arguments changed");
                        try (Cursor pending = db.rawQuery("SELECT 1 FROM operations WHERE task_id=? AND operation_id!=? AND state IN ("
                                + UNRESOLVED + ") LIMIT 1", new String[]{binding.taskId, binding.operationId})) {
                            if (pending.moveToFirst()) throw new SecurityException("Another original effect is unresolved; no replay");
                        }
                        JSONObject receipt = checkedReceipt(binding, reservation, argumentsDigest);
                        if (!receipt.optString("status").equals("unknown") || receipt.optBoolean("resolved")
                                || receipt.has("remoteTaskId"))
                            throw new SecurityException("Dispatch must first reserve an uncertain original operation");
                        JSONObject stored = new JSONObject().put("format", FORMAT).put("nativeTool", nativeTool)
                                .put("binding", encodeBinding(binding)).put("receipt", receipt).put("cancelReserved", false);
                        write(row, binding, wrap(stored));
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                }
            }

            @Override public void save(Binding binding, JSONObject candidate) throws Exception {
                synchronized (lock) {
                    db.beginTransaction();
                    try {
                        // No cancelled RunControl check here: this row may receive a real result
                        // after its originating run stopped. Its exact SQL identity still wins.
                        Row row = row(binding, nativeTool);
                        JSONObject stored = envelope(row, binding, nativeTool);
                        JSONObject old = stored.getJSONObject("receipt");
                        JSONObject next = checkedReceipt(binding, candidate, old.getString("argumentsDigest"));
                        stored.put("receipt", merge(old, next));
                        JSONObject result = wrap(stored);
                        if (row.receipt.has("outputCapture")) result.put("outputCapture", row.receipt.getJSONObject("outputCapture"));
                        write(row, binding, result);
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                }
            }

            @Override public boolean reserveCancellation(Binding binding) throws Exception {
                synchronized (lock) {
                    db.beginTransaction();
                    try {
                        authority.check(binding, Access.CANCEL, recovery);
                        Row row = row(binding, nativeTool);
                        JSONObject stored = envelope(row, binding, nativeTool);
                        JSONObject receipt = stored.getJSONObject("receipt");
                        if (stored.getBoolean("cancelReserved") || receipt.optBoolean("terminal")) {
                            db.setTransactionSuccessful();
                            return false;
                        }
                        stored.put("cancelReserved", true);
                        receipt.put("cancelRequested", true).put("cancelAcknowledged", false);
                        JSONObject result = wrap(stored);
                        if (row.receipt.has("outputCapture")) result.put("outputCapture", row.receipt.getJSONObject("outputCapture"));
                        write(row, binding, result);
                        db.setTransactionSuccessful();
                        return true;
                    } finally { db.endTransaction(); }
                }
            }
        };
    }

    /** Native owner lookup only. The caller must check owner access before exposing any result. */
    public Binding recover(String taskId, String operationId) throws Exception {
        synchronized (lock) {
            Row row = row(taskId, operationId);
            JSONObject stored = requireEnvelope(row);
            Binding binding = decodeBinding(stored.getJSONObject("binding"));
            if (!taskId.equals(binding.taskId) || !operationId.equals(binding.operationId))
                throw new SecurityException("Stored MCP binding does not belong to this exact operation");
            envelope(row(binding, stored.getString("nativeTool")), binding, stored.getString("nativeTool"));
            return binding;
        }
    }

    /** No network and no admission. Exact native receipt for owner recovery, not a model-supplied binding. */
    public JSONObject record(String taskId, String operationId) throws Exception {
        synchronized (lock) {
            Binding binding = recover(taskId, operationId);
            return copy(row(taskId, operationId).receipt).put("taskId", binding.taskId).put("runId", binding.runId).put("epoch", binding.epoch);
        }
    }

    public JSONObject details(String taskId, String operationId) throws Exception { return record(taskId, operationId); }

    public JSONArray list(String taskId) throws Exception {
        if (taskId == null || taskId.isEmpty()) throw new IllegalArgumentException("Exact task required");
        synchronized (lock) {
            JSONArray result = new JSONArray();
            try (Cursor rows = db.rawQuery("SELECT operation_id,receipt FROM operations WHERE task_id=? AND receipt IS NOT NULL ORDER BY created_at,operation_id", new String[]{taskId})) {
                while (rows.moveToNext()) {
                    JSONObject receipt = new JSONObject(rows.getString(1));
                    if (receipt.has(KEY)) result.put(metadata(record(taskId, rows.getString(0))));
                }
            }
            return result;
        }
    }

    /** Latest owner/private operations, unresolved first. A full 64-row result is a bounded
     * window, not a claim that no older task exists; exact task lookup always remains available. */
    public JSONArray listForOwner() throws Exception {
        synchronized (lock) {
            JSONArray result = new JSONArray();
            try (Cursor rows = db.rawQuery("SELECT o.task_id,o.operation_id,o.receipt,t.document FROM operations o JOIN tasks t ON t.task_id=o.task_id "
                    + "WHERE o.receipt IS NOT NULL AND instr(o.receipt,'\"remoteMcp\"')>0 ORDER BY CASE WHEN o.state IN ("
                    + UNRESOLVED + ") THEN 0 ELSE 1 END,o.created_at DESC,o.operation_id", null)) {
                while (result.length() < 64 && rows.moveToNext()) {
                    JSONObject task = new JSONObject(rows.getString(3));
                    if (!"owner".equals(task.optString("actorId")) || !"private".equals(task.optString("spaceId"))) continue;
                    if (new JSONObject(rows.getString(2)).has(KEY)) result.put(metadata(record(rows.getString(0), rows.getString(1))));
                }
            }
            return result;
        }
    }

    private static JSONObject metadata(JSONObject full) throws Exception {
        JSONObject stored = full.getJSONObject(KEY), binding = stored.getJSONObject("binding"), receipt = stored.getJSONObject("receipt");
        JSONObject result = new JSONObject().put("taskId", binding.getString("taskId")).put("runId", binding.getString("runId"))
                .put("epoch", binding.getInt("epoch")).put("operationId", binding.getString("operationId")).put("nativeTool", stored.getString("nativeTool"))
                .put("installationId", binding.getString("installationId")).put("status", full.getString("status"))
                .put("remoteStatus", receipt.getString("status")).put("resolved", receipt.optBoolean("resolved"))
                .put("terminal", receipt.optBoolean("terminal")).put("cancelRequested", receipt.optBoolean("cancelRequested"))
                .put("cancelAcknowledged", receipt.optBoolean("cancelAcknowledged")).put("automaticReplay", false).put("metadataOnly", true);
        for (String key : new String[]{"nextPollAt", "receiptExpired", "unsupportedInputRequired", "createdAt", "lastUpdatedAt"})
            if (receipt.has(key)) result.put(key, receipt.get(key));
        return result;
    }

    /** Call AFTER generic receipt sanitization. Only the existing SQL envelope can supply native
     * identity. A generic delivered receipt cannot create or replace it, truncate its schema, or
     * turn a running/unknown remote task into success. Does not modify another run or any task. */
    public JSONObject preserve(String taskId, String runId, String operationId, JSONObject delivered) throws Exception {
        synchronized (lock) {
            Row row = row(taskId, operationId);
            if (!row.runId.equals(runId)) throw new SecurityException("Receipt belongs to a different original run");
            if (row.receipt == null || !row.receipt.has(KEY)) {
                JSONObject clean = copy(delivered);
                clean.remove(KEY);
                return clean;
            }
            Binding binding = recover(taskId, operationId);
            JSONObject stored = envelope(row, binding, row.receipt.getJSONObject(KEY).getString("nativeTool"));
            JSONObject result = wrap(stored);
            if (row.receipt.has("outputCapture")) result.put("outputCapture", row.receipt.getJSONObject("outputCapture"));
            if (delivered.optJSONObject("outputCapture") != null)
                result.put("outputCapture", dev.pocketengine.features.memory.ToolOutputCapture.checkedMetadata(delivered.getJSONObject("outputCapture")));
            return result;
        }
    }

    private Row row(Binding binding, String nativeTool) throws Exception {
        Row row = row(binding.taskId, binding.operationId);
        if (!row.runId.equals(binding.runId) || !row.tool.equals(nativeTool))
            throw new SecurityException("Original MCP operation/run/tool changed");
        try (Cursor run = db.rawQuery("SELECT epoch FROM runs WHERE task_id=? AND run_id=?", new String[]{binding.taskId, binding.runId})) {
            if (!run.moveToFirst() || run.getInt(0) != binding.epoch)
                throw new SecurityException("Original MCP run epoch no longer exists");
        }
        return row;
    }

    private Row row(String task, String operation) throws Exception {
        try (Cursor row = db.rawQuery("SELECT run_id,tool,arguments,state,receipt FROM operations WHERE task_id=? AND operation_id=?", new String[]{task, operation})) {
            if (!row.moveToFirst()) throw new SecurityException("Original MCP operation does not exist");
            return new Row(row.getString(0), row.getString(1), row.getString(2), row.getString(3), row.isNull(4) ? null : row.getString(4));
        }
    }

    private static JSONObject requireEnvelope(Row row) throws Exception {
        JSONObject stored = row.receipt == null ? null : row.receipt.optJSONObject(KEY);
        if (stored == null || !FORMAT.equals(stored.optString("format")))
            throw new SecurityException("No durable original MCP task receipt");
        return copy(stored);
    }

    private static JSONObject envelope(Row row, Binding expected, String nativeTool) throws Exception {
        JSONObject stored = requireEnvelope(row);
        Binding persisted = decodeBinding(stored.getJSONObject("binding"));
        if (!nativeTool.equals(stored.getString("nativeTool")) || !expected.fingerprint().equals(persisted.fingerprint()))
            throw new SecurityException("Stored MCP task binding changed");
        JSONObject receipt = stored.getJSONObject("receipt");
        checkedReceipt(expected, receipt, McpJson.digest(new JSONObject(row.arguments)));
        if (!(stored.opt("cancelReserved") instanceof Boolean)) throw new SecurityException("Invalid cancellation reservation");
        return stored;
    }

    private static JSONObject encodeBinding(Binding binding) throws Exception {
        JSONObject declaration = binding.declaration();
        for (String key : new String[]{"token", "authorization", "headers", "env", "password", "secret", "clientSecret", "accessToken", "refreshToken"}) {
            for (java.util.Iterator<String> keys = declaration.keys(); keys.hasNext(); )
                if (key.equalsIgnoreCase(keys.next())) throw new SecurityException("Credentials cannot be stored in a task declaration");
        }
        return new JSONObject().put("taskId", binding.taskId).put("runId", binding.runId).put("epoch", binding.epoch)
                .put("operationId", binding.operationId).put("installationId", binding.installationId).put("credentialId", binding.credentialId)
                .put("declaration", declaration);
    }

    private static Binding decodeBinding(JSONObject value) throws Exception {
        return new Binding(value.getString("taskId"), value.getString("runId"), value.getInt("epoch"), value.getString("operationId"),
                value.getString("installationId"), value.getString("credentialId"), value.getJSONObject("declaration"));
    }

    private static JSONObject checkedReceipt(Binding binding, JSONObject supplied, String argumentsDigest) throws Exception {
        JSONObject receipt = copy(supplied);
        if (!binding.fingerprint().equals(receipt.optString("binding")) || !binding.operationId.equals(receipt.optString("operationId"))
                || !argumentsDigest.equals(receipt.optString("argumentsDigest")))
            throw new SecurityException("MCP receipt identity/arguments changed");
        String status = receipt.getString("status");
        if (!status.matches("unknown|working|input_required|completed|failed|cancelled|response_received"))
            throw new SecurityException("Unsupported task receipt state");
        for (String key : new String[]{"terminal", "resolved", "cancelRequested", "cancelAcknowledged"})
            if (receipt.has(key) && !(receipt.get(key) instanceof Boolean)) throw new SecurityException("Invalid receipt flags");
        if (receipt.optBoolean("resolved") && !receipt.optBoolean("terminal")) throw new SecurityException("Unfinished result cannot be resolved");
        if (receipt.optBoolean("resolved") && status.matches("unknown|working|input_required"))
            throw new SecurityException("Uncertain task cannot be resolved");
        if (receipt.optBoolean("resolved") && status.matches("completed|response_received") && receipt.optJSONObject("toolResult") == null)
            throw new SecurityException("Completed task needs its actual result");
        if (receipt.optBoolean("resolved") && status.equals("failed") && receipt.optJSONObject("protocolError") == null
                && !"not_dispatched".equals(receipt.optString("dispatchStatus")))
            throw new SecurityException("Failed task needs actual failure evidence");
        if (receipt.has("remoteTaskId")) {
            String id = receipt.getString("remoteTaskId");
            if (id.isEmpty() || id.length() > 4096 || id.chars().anyMatch(c -> c < 32 || c == 127)) throw new SecurityException("Invalid remote task ID");
            if (Instant.parse(receipt.getString("lastUpdatedAt")).isBefore(Instant.parse(receipt.getString("createdAt"))))
                throw new SecurityException("Invalid remote task chronology");
        }
        return receipt;
    }

    private static JSONObject merge(JSONObject old, JSONObject next) throws Exception {
        if (old.has("remoteTaskId") && (!old.getString("remoteTaskId").equals(next.optString("remoteTaskId"))
                || !old.getString("createdAt").equals(next.optString("createdAt"))))
            throw new SecurityException("Remote task identity cannot be replaced");
        JSONObject result;
        if (old.optBoolean("resolved")) {
            if (next.optBoolean("resolved") && !McpJson.digest(outcome(old)).equals(McpJson.digest(outcome(next))))
                throw new SecurityException("Resolved remote task result cannot change");
            // A late cancel ACK may still carry its earlier working snapshot. Merge only flags.
            result = copy(old);
        } else {
            if (old.optBoolean("terminal") && !old.getString("status").equals(next.getString("status")))
                throw new SecurityException("Terminal remote task state cannot regress");
            if (old.has("lastUpdatedAt") && Instant.parse(next.getString("lastUpdatedAt")).isBefore(Instant.parse(old.getString("lastUpdatedAt"))))
                throw new SecurityException("Stale remote task callback");
            result = copy(next);
        }
        if (old.optBoolean("cancelRequested") || next.optBoolean("cancelRequested")) result.put("cancelRequested", true);
        if (old.optBoolean("cancelAcknowledged") || next.optBoolean("cancelAcknowledged")) result.put("cancelRequested", true).put("cancelAcknowledged", true);
        return result;
    }

    private static JSONObject outcome(JSONObject receipt) throws Exception {
        JSONObject value = new JSONObject();
        for (String key : new String[]{"status", "remoteTaskId", "createdAt", "lastUpdatedAt", "toolResult", "protocolError", "dispatchStatus"})
            if (receipt.has(key)) value.put(key, receipt.get(key));
        return value;
    }

    private static JSONObject wrap(JSONObject stored) throws Exception {
        JSONObject receipt = stored.getJSONObject("receipt");
        boolean resolved = receipt.optBoolean("resolved");
        String status = resolved ? ("failed".equals(receipt.getString("status")) ? "failed" : "response_received") : "unknown";
        JSONObject result = new JSONObject().put("operationId", stored.getJSONObject("binding").getString("operationId"))
                .put("status", status).put("verified", false).put("automaticReplay", false).put("requiresReconciliation", !resolved)
                .put("cancelRequested", receipt.optBoolean("cancelRequested")).put("cancelAcknowledged", receipt.optBoolean("cancelAcknowledged"))
                .put(KEY, copy(stored));
        if (receipt.has("dispatchStatus")) result.put("dispatchStatus", receipt.get("dispatchStatus"));
        // Complete provider data lives once in the native envelope. Generic journal/owner
        // projections expose only a small summary; capture consumers read the exact inner result.
        if (receipt.has("toolResult")) {
            JSONObject actual = receipt.getJSONObject("toolResult");
            JSONObject summary = new JSONObject().put("bodyIncluded", false).put("resultAvailable", true);
            if (actual.opt("isError") instanceof Boolean) summary.put("isError", actual.getBoolean("isError"));
            if (actual.optJSONArray("content") != null) summary.put("contentBlocks", actual.getJSONArray("content").length());
            result.put("resultSummary", summary);
        }
        if (receipt.has("protocolError")) result.put("protocolErrorAvailable", true);
        if (receipt.has("message")) {
            String message = receipt.getString("message");
            result.put("message", message.substring(0, Math.min(1000, message.length())));
        }
        return result;
    }

    private void write(Row previous, Binding binding, JSONObject receipt) throws Exception {
        if (!previous.state.matches("prepared|unknown|receipt"))
            throw new SecurityException("Original MCP row was independently reconciled or retired");
        String encoded = receipt.toString();
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RECEIPT_BYTES)
            throw new IllegalStateException("MCP receipt exceeds durable limit; retain original unknown operation");
        ContentValues update = new ContentValues();
        update.put("state", receipt.getJSONObject(KEY).getJSONObject("receipt").optBoolean("resolved") ? "receipt" : "unknown");
        update.put("receipt", encoded);
        String clause = "task_id=? AND run_id=? AND operation_id=? AND tool=? AND state=? AND " + (previous.rawReceipt == null ? "receipt IS NULL" : "receipt=?");
        String[] args = previous.rawReceipt == null
                ? new String[]{binding.taskId, binding.runId, binding.operationId, previous.tool, previous.state}
                : new String[]{binding.taskId, binding.runId, binding.operationId, previous.tool, previous.state, previous.rawReceipt};
        if (db.update("operations", update, clause, args) != 1) throw new SecurityException("Original MCP receipt CAS changed");
    }

    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static final class Row {
        final String runId, tool, arguments, state, rawReceipt;
        final JSONObject receipt;
        Row(String runId, String tool, String arguments, String state, String rawReceipt) throws Exception {
            this.runId = runId; this.tool = tool; this.arguments = arguments; this.state = state; this.rawReceipt = rawReceipt;
            receipt = rawReceipt == null ? null : new JSONObject(rawReceipt);
        }
    }
}
