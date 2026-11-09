package dev.pocketengine.ui.setup;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Preference transitions and commit-failure behavior; this does not claim Android disk/permission QA. */
public final class SetupPreferencesTest {
    private static final class Storage {
        final Map<String,Object> values = new HashMap<>();
        boolean failCommit;
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "first-use-test-preferences";
                        case "edit": return editor();
                        case "getAll": return new HashMap<>(values);
                        case "contains": return values.containsKey(args[0]);
                        case "getString": case "getBoolean": case "getLong": case "getInt": case "getFloat": case "getStringSet":
                            return values.getOrDefault(args[0], args[1]);
                        case "registerOnSharedPreferenceChangeListener": case "unregisterOnSharedPreferenceChangeListener": return null;
                        default: throw new AssertionError(method.getName());
                    }
                });
        SharedPreferences.Editor editor() {
            Map<String,Object> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("put")) { pending.put((String)args[0], args[1]); return proxy; }
                        if (name.equals("remove")) { pending.put((String)args[0], null); return proxy; }
                        if (name.equals("clear")) { pending.put("__clear", true); return proxy; }
                        if (name.equals("commit") || name.equals("apply")) {
                            if (pending.containsKey("__clear")) values.clear();
                            for (var entry : pending.entrySet()) if (!entry.getKey().equals("__clear")) {
                                if (entry.getValue() == null) values.remove(entry.getKey()); else values.put(entry.getKey(), entry.getValue());
                            }
                            // Android may already expose the new memory map when disk commit fails.
                            return name.equals("commit") ? !failCommit : null;
                        }
                        throw new AssertionError(name);
                    });
        }
    }

    private static JSONObject save(long revision, String step, String status, String... capabilities) throws Exception {
        JSONArray selected = new JSONArray(); for (String capability : capabilities) selected.put(capability);
        return new JSONObject().put("expectedRevision", revision).put("step", step).put("status", status).put("capabilities", selected);
    }

    private static JSONObject reference() throws Exception {
        return new JSONObject().put("purpose", "CONNECTION_TEST").put("accountingOnly", true).put("executionAuthorized", false)
                .put("taskId", "8bd70e87-af78-4fc5-9d5a-34a083288179").put("runId", "c643f007-28f5-49a1-97e1-ac8cb8490bfd").put("epoch", 1);
    }

    @Test public void cleanInstallStartsEmptyAndExistingInstallDoesNotInterruptTheOwner() throws Exception {
        Storage fresh = new Storage(), existing = new Storage();
        SetupPreferences.initialize(fresh.prefs, false); SetupPreferences.initialize(existing.prefs, true);
        JSONObject clean = new SetupPreferences(fresh.prefs).read();
        assertEquals(1, clean.getLong("revision"));
        assertEquals("new", clean.getJSONObject("preferences").getString("status"));
        assertEquals("welcome", clean.getJSONObject("preferences").getString("step"));
        assertEquals(0, clean.getJSONObject("preferences").getJSONArray("capabilities").length());
        SetupPreferences upgrade = new SetupPreferences(existing.prefs);
        assertEquals("skipped", upgrade.read().getJSONObject("preferences").getString("status"));
        upgrade.save(save(1, "model", "in_progress")); // Explicit re-entry is allowed without resetting any product data.
        SetupPreferences.initialize(existing.prefs, false);
        assertEquals("model", new SetupPreferences(existing.prefs).read().getJSONObject("preferences").getString("step"));
    }

    @Test public void returningOwnerResumesSavedStepAndChoicesWithoutGrantingAnything() throws Exception {
        Storage disk = new Storage(); SetupPreferences.initialize(disk.prefs, false);
        SetupPreferences first = new SetupPreferences(disk.prefs);
        first.save(save(1, "capabilities", "in_progress", "phone", "voice"));
        JSONObject detached = first.read(); detached.getJSONObject("preferences").put("status", "completed");
        JSONObject restored = new SetupPreferences(disk.prefs).read();
        assertEquals(2, restored.getLong("revision"));
        assertEquals("in_progress", restored.getJSONObject("preferences").getString("status"));
        assertEquals("[\"phone\",\"voice\"]", restored.getJSONObject("preferences").getJSONArray("capabilities").toString());
        assertFalse(first.vendorBackgroundVisited());
        assertFalse(first.permissionRequested("microphone"));
        assertFalse(restored.has("permissions"));
    }

    @Test public void staleWindowAndMalformedChoicesCannotReplaceNewerProgress() throws Exception {
        Storage disk = new Storage(); SetupPreferences.initialize(disk.prefs, false);
        SetupPreferences first = new SetupPreferences(disk.prefs), second = new SetupPreferences(disk.prefs);
        first.save(save(1, "model", "in_progress"));
        String current = second.read().toString();
        assertThrows(IllegalStateException.class, () -> second.save(save(1, "finish", "skipped")));
        assertThrows(IllegalArgumentException.class, () -> second.save(save(2, "model", "completed")));
        assertThrows(IllegalArgumentException.class, () -> second.save(save(2, "model", "new")));
        assertThrows(IllegalArgumentException.class, () -> second.save(save(2, "capabilities", "in_progress", "phone", "phone")));
        assertThrows(IllegalArgumentException.class, () -> second.save(save(2, "capabilities", "in_progress", "admin")));
        assertThrows(IllegalArgumentException.class, () -> second.save(save(2, "model", "in_progress").put("expectedRevision", "2")));
        assertThrows(IllegalArgumentException.class, () -> second.save(save(2, "model", "in_progress").put("expectedRevision", 2.5)));
        assertEquals(current, second.read().toString());
    }

    @Test public void completedAndSkippedChoicesAreDurableAndCanBeExplicitlyReopened() throws Exception {
        Storage disk = new Storage(); SetupPreferences.initialize(disk.prefs, false);
        SetupPreferences prefs = new SetupPreferences(disk.prefs);
        prefs.save(save(1, "finish", "completed", "tools"));
        assertEquals("completed", new SetupPreferences(disk.prefs).read().getJSONObject("preferences").getString("status"));
        prefs.save(save(2, "welcome", "in_progress", "tools"));
        prefs.save(save(3, "welcome", "skipped", "tools"));
        SetupPreferences.initialize(disk.prefs, false);
        assertEquals("skipped", prefs.read().getJSONObject("preferences").getString("status"));
    }

    @Test public void unknownPersistenceCannotMasqueradeAsSavedProgress() throws Exception {
        Storage disk = new Storage(); SetupPreferences.initialize(disk.prefs, false);
        SetupPreferences prefs = new SetupPreferences(disk.prefs); disk.failCommit = true;
        assertThrows(IllegalStateException.class, () -> prefs.save(save(1, "finish", "completed")));
        assertTrue(disk.values.get("state").toString().contains("completed")); // Simulated in-memory Android write really happened.
        assertThrows(IllegalStateException.class, prefs::read);
        assertThrows(IllegalStateException.class, () -> new SetupPreferences(disk.prefs).read());
        assertThrows(IllegalStateException.class, () -> SetupPreferences.initialize(disk.prefs, false));
    }

    @Test public void corruptOrFutureRecordsArePreservedInsteadOfResetToFresh() throws Exception {
        Storage disk = new Storage(); disk.values.put("state", "{broken");
        assertThrows(Exception.class, () -> SetupPreferences.initialize(disk.prefs, false));
        assertEquals("{broken", disk.values.get("state"));
        Storage future = new Storage(); SetupPreferences.initialize(future.prefs, false);
        String value = new JSONObject(future.values.get("state").toString()).put("version", 2).toString(); future.values.put("state", value);
        assertThrows(IllegalStateException.class, () -> SetupPreferences.initialize(future.prefs, true));
        assertEquals(value, future.values.get("state"));
    }

    @Test public void testsNeedOriginalReceiptsAndOnlyMatchTheirExactModelRevision() throws Exception {
        Storage disk = new Storage(); SetupPreferences.initialize(disk.prefs, false);
        SetupPreferences prefs = new SetupPreferences(disk.prefs);
        String first = prefs.fingerprint("model-revision-4:credential-fixture");
        assertEquals(first, new SetupPreferences(disk.prefs).fingerprint("model-revision-4:credential-fixture"));
        String changed = prefs.fingerprint("model-revision-5:credential-fixture"); assertNotEquals(first, changed);
        assertThrows(IllegalArgumentException.class, () -> prefs.saveModelTest(first, "passed", "OK", null));
        assertThrows(IllegalArgumentException.class, () -> prefs.saveModelTest(first, "passed", "OK", reference().put("executionAuthorized", true)));
        prefs.saveModelTest(first, "passed", "文字连接已实测；未验证工具。", reference());
        assertTrue(prefs.modelEvidence(first, false).getBoolean("tested"));
        assertFalse(prefs.modelEvidence(changed, false).getBoolean("tested"));
        assertFalse(prefs.read().toString().contains(first));
        assertFalse(prefs.read().toString().contains("credential-fixture"));
        prefs.saveModelTest(first, "unknown", "原结果尚不能确认。", null);
        assertFalse(prefs.modelEvidence(first, false).getBoolean("tested"));
    }

    @Test public void interruptedTestIsUnknownAndVendorEntryNeverBecomesAGrant() throws Exception {
        Storage disk = new Storage(); SetupPreferences.initialize(disk.prefs, false);
        SetupPreferences prefs = new SetupPreferences(disk.prefs);
        prefs.saveModelTest("same-model", "testing", "正在测试", null);
        assertTrue(prefs.modelEvidence("same-model", true).getBoolean("testing"));
        JSONObject reopened = new SetupPreferences(disk.prefs).modelEvidence("same-model", false);
        assertFalse(reopened.getBoolean("testing")); assertFalse(reopened.getBoolean("tested"));
        assertTrue(reopened.getString("message").contains("未知"));
        prefs.markVendorBackgroundVisited(); prefs.markVendorBackgroundVisited();
        assertTrue(prefs.vendorBackgroundVisited()); assertEquals(2, prefs.read().getLong("revision"));
        assertFalse(prefs.read().toString().contains("granted"));
        prefs.markPermissionRequested("microphone"); assertTrue(prefs.permissionRequested("microphone"));
        assertFalse(prefs.permissionRequested("notifications"));
    }
}
