package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.ToolOutcome;
import dev.pocketengine.features.plugins.mcp.McpJson;
import dev.pocketengine.features.plugins.mcp.McpTaskCoordinator;
import dev.pocketengine.features.plugins.mcp.McpTaskCoordinator.Access;
import dev.pocketengine.features.plugins.mcp.McpTaskCoordinator.Binding;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual TaskStore schema, debited prepared operations and native SQLite across reopen.
 * Protocol samples below test persistence contracts, not an external provider or phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskStoreMcpTasksTest {
    private static final String TOOL = "plugin_0123456789abcdef01234567";
    private static final String CREATED = "2026-11-01T00:00:00Z";
    private static final String UPDATED = "2026-11-01T00:00:01Z";

    private static final class Fixture implements AutoCloseable {
        final Application app = RuntimeEnvironment.getApplication();
        TaskStore tasks;
        SQLiteDatabase db;
        TaskStoreMcpTasks store;
        final String task, run, operation;
        final int epoch;
        final JSONObject arguments = new JSONObject().put("text", "original reviewed arguments");
        final RunControl original = new RunControl();
        final Binding binding;
        boolean sourceCurrent = true;

        Fixture() throws Exception {
            app.deleteDatabase("pocket-world.db");
            tasks = new TaskStore(app);
            task = tasks.createCapabilityTask("Remote MCP task persistence", "").getString("id");
            tasks.initializeExecutionScope(task, 8);
            JSONObject attempt = tasks.startAttempt(task, "capabilities", 8);
            run = attempt.getString("runId"); epoch = attempt.getInt("coordinatorEpoch");
            original.bindHostRun(task, run, epoch);
            tasks.authorizeTools(task, run, new JSONArray().put(TOOL));
            RunJournal journal = tasks.journal(task, run, epoch, original);
            operation = journal.beginOperation(TOOL, arguments);
            binding = new Binding(task, run, epoch, operation, "installation-1", "credential-revision-1", declaration());
            connect();
        }

        void connect() throws Exception {
            java.lang.reflect.Field field = TaskStore.class.getDeclaredField("db");
            field.setAccessible(true); db = (SQLiteDatabase) field.get(tasks);
            store = new TaskStoreMcpTasks(tasks, db, (b, access, recovery) -> {
                if (!sourceCurrent) throw new SecurityException("Synthetic credential revision withdrawn");
                if (recovery) {
                    JSONObject owner = tasks.get(b.taskId, false);
                    if (!"owner".equals(owner.optString("actorId")) || !"private".equals(owner.optString("spaceId")))
                        throw new SecurityException("Not the original private owner task");
                } else tasks.checkCapabilityCurrent(b.taskId, b.runId, b.epoch, TOOL);
            });
        }

        void reopen() throws Exception { db.close(); tasks = new TaskStore(app); connect(); }
        McpTaskCoordinator.Host host() { return store.host(TOOL, false); }
        void reserve() throws Exception { host().reserve(binding, McpJson.digest(arguments), reservation(binding, arguments)); }
        JSONObject saved() throws Exception { return host().load(binding); }
        String state() { try (Cursor row = db.rawQuery("SELECT state FROM operations WHERE operation_id=?", new String[]{operation})) { assertTrue(row.moveToFirst()); return row.getString(0); } }
        String inserted(String state) throws Exception {
            String id = UUID.randomUUID().toString(); ContentValues row = new ContentValues();
            row.put("operation_id", id); row.put("task_id", task); row.put("run_id", run); row.put("tool", TOOL);
            row.put("arguments", arguments.toString()); row.put("state", state); row.put("created_at", System.currentTimeMillis());
            db.insertOrThrow("operations", null, row); return id;
        }
        @Override public void close() { original.cancel(); db.close(); }
    }

    private static JSONObject declaration() throws Exception {
        return new JSONObject().put("name", "remote_write").put("protocolVersion", "2026-07-28")
                .put("endpoint", "https://fixture.example/mcp").put("serverDigest", "a".repeat(64)).put("descriptorDigest", "b".repeat(64))
                .put("schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("token", new JSONObject().put("type", "string").put("description", "A schema property, not a credential"))));
    }

    private static JSONObject reservation(Binding binding, JSONObject arguments) throws Exception {
        return new JSONObject().put("binding", binding.fingerprint()).put("operationId", binding.operationId)
                .put("argumentsDigest", McpJson.digest(arguments)).put("status", "unknown").put("dispatchStatus", "may_have_dispatched")
                .put("verified", false);
    }

    private static JSONObject working(Fixture f) throws Exception {
        return reservation(f.binding, f.arguments).put("status", "working").put("dispatchStatus", "response_received")
                .put("remoteTaskId", "provider-task-1").put("createdAt", CREATED).put("lastUpdatedAt", CREATED)
                .put("ttlMs", JSONObject.NULL).put("nextPollAt", 0L).put("terminal", false).put("resolved", false);
    }

    private static JSONObject completed(Fixture f) throws Exception {
        return working(f).put("status", "completed").put("lastUpdatedAt", UPDATED).put("terminal", true).put("resolved", true)
                .put("toolResult", new JSONObject().put("isError", true).put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "Provider returned a tool error; no business success inferred"))));
    }

    @Test public void existingPreparedOperationIsReservedOnceAndSurvivesDatabaseReopen() throws Exception {
        try (Fixture f = new Fixture()) {
            f.reserve(); assertEquals("unknown", f.state()); assertTrue(f.tasks.hasUnresolved(f.task));
            f.host().save(f.binding, working(f));
            assertThrows(SecurityException.class, f::reserve);
            f.reopen(); Binding recovered = f.store.recover(f.task, f.operation);
            assertEquals(f.binding.fingerprint(), recovered.fingerprint());
            assertEquals("provider-task-1", f.store.host(TOOL, true).load(recovered).getString("remoteTaskId"));
            assertThrows(SecurityException.class, () -> f.store.host(TOOL, true).check(recovered, Access.DISPATCH));
            assertEquals(1, f.tasks.get(f.task, false).getJSONArray("operations").length());
            assertEquals(1, f.tasks.get(f.task, false).getJSONObject("executionScope").getInt("toolsUsed"));
            assertEquals(1, f.tasks.list().length());
        }
    }

    @Test public void originalArgumentsRunToolAndOtherUnknownEffectsFenceReservation() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject changed = new JSONObject().put("text", "changed");
            assertThrows(SecurityException.class, () -> f.host().reserve(f.binding, McpJson.digest(changed), reservation(f.binding, changed)));
            Binding wrongEpoch = new Binding(f.task, f.run, f.epoch + 1, f.operation, "installation-1", "credential-revision-1", declaration());
            assertThrows(SecurityException.class, () -> f.host().load(wrongEpoch));
            assertThrows(SecurityException.class, () -> f.store.host("different_tool", false).load(f.binding));
            String other = f.inserted("unknown");
            assertThrows(SecurityException.class, f::reserve); assertNull(f.host().load(f.binding));
            f.db.delete("operations", "operation_id=?", new String[]{other});
            f.reserve();
            Binding otherOperation = new Binding(f.task, f.run, f.epoch, f.inserted("prepared"), "installation-1", "credential-revision-1", declaration());
            assertThrows(SecurityException.class, () -> f.host().reserve(otherOperation, McpJson.digest(f.arguments), reservation(otherOperation, f.arguments)));
        }
    }

    @Test public void cancellationReservationPersistsAndLateAckCannotReplaceCompletedResult() throws Exception {
        try (Fixture f = new Fixture()) {
            f.reserve(); f.host().save(f.binding, working(f));
            assertTrue(f.host().reserveCancellation(f.binding));
            f.reopen(); assertFalse(f.store.host(TOOL, true).reserveCancellation(f.binding));
            JSONObject staleCancellation = f.saved();
            f.host().save(f.binding, completed(f));
            f.host().save(f.binding, staleCancellation.put("cancelAcknowledged", true));
            JSONObject result = f.saved();
            assertEquals("completed", result.getString("status")); assertTrue(result.getBoolean("resolved"));
            assertTrue(result.getBoolean("cancelRequested")); assertTrue(result.getBoolean("cancelAcknowledged"));
            assertTrue(result.getJSONObject("toolResult").getBoolean("isError"));
            assertEquals("receipt", f.state()); assertFalse(f.tasks.hasUnresolved(f.task));
            JSONObject contradictory = completed(f); contradictory.getJSONObject("toolResult").put("isError", false);
            assertThrows(SecurityException.class, () -> f.host().save(f.binding, contradictory));
            assertTrue(f.saved().getJSONObject("toolResult").getBoolean("isError"));
        }
    }

    @Test public void changedRemoteHandleChronologyAndInstallationCannotOverwriteReceipt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.reserve(); f.host().save(f.binding, working(f));
            assertThrows(SecurityException.class, () -> f.host().save(f.binding, working(f).put("remoteTaskId", "another-job")));
            assertThrows(SecurityException.class, () -> f.host().save(f.binding, working(f).put("createdAt", "2026-10-31T00:00:00Z")));
            f.host().save(f.binding, working(f).put("lastUpdatedAt", UPDATED));
            assertThrows(SecurityException.class, () -> f.host().save(f.binding, working(f)));
            Binding replacement = new Binding(f.task, f.run, f.epoch, f.operation, "reinstalled", "credential-revision-2", declaration());
            assertThrows(SecurityException.class, () -> f.host().load(replacement));
            assertEquals("provider-task-1", f.saved().getString("remoteTaskId")); assertEquals(UPDATED, f.saved().getString("lastUpdatedAt"));
            String other = f.inserted("receipt"); ContentValues transplanted = new ContentValues();
            transplanted.put("receipt", f.store.record(f.task, f.operation).toString());
            f.db.update("operations", transplanted, "operation_id=?", new String[]{other});
            assertThrows(SecurityException.class, () -> f.store.recover(f.task, other));
        }
    }

    @Test public void withdrawalStopsFurtherNetworkAdmissionButRetainsRealLateHistoricalReceipt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.reserve(); f.host().save(f.binding, working(f)); f.sourceCurrent = false; f.original.cancel();
            assertThrows(SecurityException.class, () -> f.host().check(f.binding, Access.QUERY));
            assertThrows(SecurityException.class, () -> f.host().reserveCancellation(f.binding));
            f.host().save(f.binding, completed(f));
            assertEquals("completed", f.saved().getString("status"));
            assertEquals(f.run, f.tasks.get(f.task, false).getString("runId"));
            assertNotEquals("completed", f.tasks.get(f.task, false).getString("status"));
            assertFalse(f.store.record(f.task, f.operation).getBoolean("verified"));
        }
    }

    @Test public void nativeEnvelopeSurvivesGenericProjectionAndOwnerListOmitsContentAndCredentialIdentity() throws Exception {
        try (Fixture f = new Fixture()) {
            f.reserve(); f.host().save(f.binding, working(f));
            JSONObject delivered = new JSONObject().put("status", "completed").put("verified", true)
                    .put(TaskStoreMcpTasks.KEY, new JSONObject().put("binding", "forged"));
            JSONObject kept = f.store.preserve(f.task, f.run, f.operation, delivered);
            assertEquals("unknown", kept.getString("status")); assertFalse(kept.getBoolean("verified"));
            assertTrue(kept.getJSONObject(TaskStoreMcpTasks.KEY).getJSONObject("binding").getJSONObject("declaration")
                    .getJSONObject("schema").getJSONObject("properties").has("token"));
            assertThrows(SecurityException.class, () -> f.store.preserve(f.task, "another-run", f.operation, delivered));
            JSONArray listed = f.store.listForOwner(); assertEquals(1, listed.length());
            String text = listed.toString(); assertFalse(text.contains("credential-revision-1")); assertFalse(text.contains("declaration"));
            assertFalse(text.contains("toolResult")); assertFalse(text.contains("original reviewed arguments"));
            assertEquals(f.operation, listed.getJSONObject(0).getString("operationId"));
            assertThrows(IllegalArgumentException.class, () -> f.store.list(""));
            JSONObject complete = completed(f);
            String longResult = "r".repeat(16000);
            complete.getJSONObject("toolResult").getJSONArray("content").getJSONObject(0).put("text", longResult);
            f.host().save(f.binding, complete);
            JSONObject full = f.store.record(f.task, f.operation);
            assertFalse(full.has("toolResult")); assertTrue(full.getJSONObject("resultSummary").getBoolean("isError"));
            assertEquals(longResult, full.getJSONObject(TaskStoreMcpTasks.KEY).getJSONObject("receipt")
                    .getJSONObject("toolResult").getJSONArray("content").getJSONObject(0).getString("text"));
        }
    }

    @Test public void genericJournalReturnsTheCanonicalCompletedMcpResultWithoutReopeningItsUnknownBarrier() throws Exception {
        for(boolean toolError:new boolean[]{false,true})try(Fixture f=new Fixture()){
            f.reserve();assertEquals("unknown",f.state());
            JSONObject actual=completed(f);actual.getJSONObject("toolResult").put("isError",toolError);
            f.host().save(f.binding,actual);assertEquals("receipt",f.state());
            String nativeDigest=McpJson.digest(f.store.record(f.task,f.operation).getJSONObject(TaskStoreMcpTasks.KEY));
            // An ordinary delivery cannot replace the already recorded native result.
            JSONObject delivered=new JSONObject().put("ok",true).put("status","completed").put("verified",true)
                    .put(TaskStoreMcpTasks.KEY,new JSONObject().put("binding","forged"));
            JSONObject canonical=f.tasks.journal(f.task,f.run,f.epoch,f.original).finishOperationReceipt(f.operation,delivered);
            JSONObject row=f.tasks.get(f.task,false).getJSONArray("operations").getJSONObject(0);
            assertEquals("receipt",f.state());assertFalse(f.tasks.hasUnresolved(f.task));
            assertEquals(toolError?ToolOutcome.Kind.CORRECTABLE_FAILURE:ToolOutcome.Kind.SUCCESS,ToolOutcome.classify(canonical).kind());
            assertEquals(nativeDigest,McpJson.digest(canonical.getJSONObject(TaskStoreMcpTasks.KEY)));
            assertEquals(McpJson.digest(row.getJSONObject("receipt")),McpJson.digest(canonical));
            assertEquals(toolError,canonical.getJSONObject(TaskStoreMcpTasks.KEY).getJSONObject("receipt").getJSONObject("toolResult").getBoolean("isError"));
            assertFalse(canonical.getBoolean("verified"));assertEquals(f.operation,row.getString("operationId"));
            assertEquals(1,f.tasks.get(f.task,false).getJSONArray("operations").length());
            assertEquals(1,f.tasks.get(f.task,false).getJSONObject("executionScope").getInt("toolsUsed"));
        }
    }

    @Test public void legacyCompletedMcpReceiptAboveOneMiBStaysKnownAcrossColdRecovery() throws Exception {
        try(Fixture f=new Fixture()){
            f.reserve();JSONObject actual=completed(f);actual.getJSONObject("toolResult").put("isError",false);
            String body="r".repeat(1_100_000);
            actual.getJSONObject("toolResult").getJSONArray("content").getJSONObject(0).put("text",body);
            f.host().save(f.binding,actual);
            JSONObject original=f.store.record(f.task,f.operation);
            int bytes=original.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertTrue(bytes>1024*1024);assertTrue(bytes<2*1024*1024);
            String fingerprint=McpJson.digest(original);assertEquals("receipt",f.state());
            f.tasks.endAttempt(f.task,f.run,"completed","The original provider result was recorded",new JSONObject());
            assertEquals("completed",f.tasks.get(f.task,false).getString("status"));
            f.db.delete("meta","key=?",new String[]{"tool_outcome_uncertainty_v1"});
            for(int reopen=0;reopen<2;reopen++){
                f.reopen();assertEquals("receipt",f.state());assertFalse(f.tasks.hasUnresolved(f.task));
                assertEquals("completed",f.tasks.get(f.task,false).getString("status"));
                assertEquals(fingerprint,McpJson.digest(f.store.record(f.task,f.operation)));
                assertTrue("The full original provider result must survive migration",body.equals(f.saved().getJSONObject("toolResult").getJSONArray("content").getJSONObject(0).getString("text")));
                assertEquals(1,f.tasks.get(f.task,false).getJSONArray("operations").length());
            }
        }
    }

    @Test public void lostCreationAckHasNoHandleAndCannotProduceANewDispatchAfterRestart() throws Exception {
        try (Fixture f = new Fixture()) {
            f.reserve(); f.reopen();
            McpTaskCoordinator.Transport noDispatch = new McpTaskCoordinator.Transport() {
                public JSONObject start(JSONObject d, JSONObject a, RunControl c) { throw new AssertionError("must not replay"); }
                public JSONObject query(JSONObject d, String id, RunControl c) { throw new AssertionError("missing handle cannot be guessed"); }
                public void cancel(JSONObject d, String id, RunControl c) { throw new AssertionError("missing handle cannot be cancelled remotely"); }
            };
            RunControl recovery = new RunControl(); recovery.bindHostRun(f.task, f.run, f.epoch);
            McpTaskCoordinator coordinator = new McpTaskCoordinator(f.store.host(TOOL, true), noDispatch);
            assertEquals("unknown", coordinator.query(f.store.recover(f.task, f.operation), recovery).getString("status"));
            assertFalse(coordinator.cancel(f.binding, recovery).getBoolean("cancelAcknowledged"));
            assertThrows(SecurityException.class, () -> coordinator.start(f.binding, f.arguments, recovery));
            assertTrue(f.saved().getBoolean("cancelRequested"));
            assertEquals(1, f.tasks.get(f.task, false).getJSONArray("operations").length());
        }
    }
}
