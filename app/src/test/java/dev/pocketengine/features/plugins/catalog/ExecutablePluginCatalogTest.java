package dev.pocketengine.features.plugins.catalog;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Future;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ExecutablePluginCatalogTest {
    private JsPluginPackage source(String version, String source) throws Exception {
        return JsPluginPackage.author(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "script", "原生源码审批", version, new RunControl());
    }
    private JSONObject install(ExecutablePluginCatalog catalog, JsPluginPackage source) throws Exception {
        ExecutablePluginCatalog.Inspection review = catalog.inspect(source, new RunControl()); return catalog.installReviewed(review, review.reviewDigest, new RunControl());
    }
    private void approveAndEnable(ExecutablePluginCatalog catalog) throws Exception {
        ExecutablePluginCatalog.InstalledReview approve = catalog.reviewInstalled("script", new RunControl()); catalog.approveReviewed(approve, approve.reviewDigest, true, new RunControl());
        ExecutablePluginCatalog.InstalledReview enable = catalog.reviewInstalled("script", new RunControl()); catalog.setEnabledReviewed(enable, enable.reviewDigest, true, new RunControl());
    }
    @Test(timeout = 5000) public void staticReviewInstallApproveAndEnableNeverExecuteEvenThrowingOrUnboundedSource() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            byte[] exact = "\ufeff// 字符😀\r\n/*\u0000*/\r\nwhile(true){}; throw new Error('must not execute');\n".getBytes(StandardCharsets.UTF_8);
            JsPluginPackage pkg = JsPluginPackage.author(new ByteArrayInputStream(exact), "script", "原始源码", "opaque", new RunControl());
            ExecutablePluginCatalog.Inspection review = f.js.inspect(pkg, new RunControl()); assertEquals(new String(exact, StandardCharsets.UTF_8), review.view().getString("source"));
            JSONObject result = f.js.installReviewed(review, review.reviewDigest, new RunControl());
            assertEquals("installed_unapproved", result.getString("status")); assertFalse(result.getJSONObject("plugin").getBoolean("approved")); assertFalse(result.getJSONObject("plugin").getBoolean("enabled"));
            assertEquals(0, f.js.approvedSources(new RunControl()).size()); approveAndEnable(f.js);
            List<ExecutablePluginCatalog.ApprovedSource> sources = f.js.approvedSources(new RunControl()); assertEquals(1, sources.size()); assertArrayEquals(exact, sources.get(0).exactSourceBytes());
            sources.get(0).exactSourceBytes()[0] = 1; assertArrayEquals(exact, sources.get(0).exactSourceBytes());
            assertThrows(UnsupportedOperationException.class, () -> sources.clear()); f.js.checkCurrent(sources.get(0), new RunControl());
        }
    }
    @Test public void approvalEnableDisableReenableAndRevokeAdvanceRevisionAndOldSourceNeverRevives() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            long installed = install(f.js, source("one", "defineExtension({tools:[]});")).getJSONObject("plugin").getLong("approvalRevision");
            ExecutablePluginCatalog.InstalledReview notApproved = f.js.reviewInstalled("script", new RunControl());
            assertThrows(SecurityException.class, () -> f.js.setEnabledReviewed(notApproved, notApproved.reviewDigest, true, new RunControl()));
            approveAndEnable(f.js); ExecutablePluginCatalog.ApprovedSource original = f.js.approvedSources(new RunControl()).get(0); assertTrue(original.approvalRevision > installed);
            ExecutablePluginCatalog.InstalledReview disable = f.js.reviewInstalled("script", new RunControl()); f.js.setEnabledReviewed(disable, disable.reviewDigest, false, new RunControl());
            assertThrows(SecurityException.class, () -> f.js.checkCurrent(original, new RunControl()));
            ExecutablePluginCatalog.InstalledReview enable = f.js.reviewInstalled("script", new RunControl()); f.js.setEnabledReviewed(enable, enable.reviewDigest, true, new RunControl());
            ExecutablePluginCatalog.ApprovedSource current = f.js.approvedSources(new RunControl()).get(0); assertEquals(original.installationId, current.installationId); assertTrue(current.approvalRevision > original.approvalRevision);
            assertThrows(SecurityException.class, () -> f.js.checkCurrent(original, new RunControl())); f.js.checkCurrent(current, new RunControl());
            ExecutablePluginCatalog.InstalledReview revoke = f.js.reviewInstalled("script", new RunControl()); JSONObject changed = f.js.approveReviewed(revoke, revoke.reviewDigest, false, new RunControl());
            assertFalse(changed.getJSONObject("plugin").getBoolean("enabled")); assertFalse(changed.getJSONObject("plugin").getBoolean("approved"));
            assertThrows(SecurityException.class, () -> f.js.checkCurrent(current, new RunControl())); assertEquals(0, f.js.approvedSources(new RunControl()).size());
        }
    }
    @Test public void sameVersionReplacementGetsNewIdentityAndCannotReuseOldApprovalOrRunSource() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, source("same", "defineExtension({tools:[]});")); approveAndEnable(f.js);
            ExecutablePluginCatalog.ApprovedSource old = f.js.approvedSources(new RunControl()).get(0);
            ExecutablePluginCatalog.InstalledReview oldReview = f.js.reviewInstalled("script", new RunControl());
            JSONObject newer = install(f.js, source("same", "defineExtension({tools:[]});\r\n//changed"));
            assertNotEquals(old.installationId, newer.getJSONObject("plugin").getString("installationId"));
            assertTrue(newer.getJSONObject("plugin").getLong("approvalRevision") > old.approvalRevision);
            assertThrows(IllegalStateException.class, () -> f.js.approveReviewed(oldReview, oldReview.reviewDigest, true, new RunControl()));
            assertThrows(SecurityException.class, () -> f.js.checkCurrent(old, new RunControl())); assertEquals(0, f.js.approvedSources(new RunControl()).size());
        }
    }
    @Test public void absentInstallCasAndApprovalCasAllowOnlyOneConcurrentCommit() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            JsPluginPackage pkg = source("one", "defineExtension({tools:[]});");
            ExecutablePluginCatalog.Inspection a = f.js.inspect(pkg, new RunControl()), b = f.js.inspect(pkg, new RunControl());
            Future<String> first = f.workers.submit(() -> { try { return f.js.installReviewed(a, a.reviewDigest, new RunControl()).getString("status"); } catch (IllegalStateException stale) { return "stale"; } });
            Future<String> second = f.workers.submit(() -> { try { return f.js.installReviewed(b, b.reviewDigest, new RunControl()).getString("status"); } catch (IllegalStateException stale) { return "stale"; } });
            String x = first.get(), y = second.get(); assertTrue(x.equals("installed_unapproved") && y.equals("stale") || y.equals("installed_unapproved") && x.equals("stale"));
            ExecutablePluginCatalog.InstalledReview original = f.js.reviewInstalled("script", new RunControl()), competing = f.js.reviewInstalled("script", new RunControl());
            f.js.approveReviewed(original, original.reviewDigest, true, new RunControl());
            assertThrows(IllegalStateException.class, () -> f.js.approveReviewed(competing, competing.reviewDigest, true, new RunControl()));
            assertThrows(SecurityException.class, () -> f.js.approveReviewed(original, original.reviewDigest, true, new RunControl()));
        }
    }
    @Test public void persistedBytesAndApprovalsReopenWhileForeignSnapshotCannotClaimAuthority() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, source("one", "//exact\r\ndefineExtension({tools:[]});")); approveAndEnable(f.js);
            ExecutablePluginCatalog.ApprovedSource old = f.js.approvedSources(new RunControl()).get(0);
            try (ExecutablePluginCatalog reopened = new ExecutablePluginCatalog(f.catalog, new File(f.root, "catalog/js.json"))) {
                ExecutablePluginCatalog.ApprovedSource current = reopened.approvedSources(new RunControl()).get(0); assertArrayEquals(old.exactSourceBytes(), current.exactSourceBytes());
                assertEquals(old.approvalRevision, current.approvalRevision); assertThrows(SecurityException.class, () -> reopened.checkCurrent(old, new RunControl())); reopened.checkCurrent(current, new RunControl());
            }
            assertFalse(f.js.snapshot().getJSONArray("plugins").getJSONObject(0).has("source"));
        }
    }
    @Test public void realFileTamperingCannotReuseApprovedDigest() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, source("one", "defineExtension({tools:[]});")); approveAndEnable(f.js);
            ExecutablePluginCatalog.ApprovedSource old = f.js.approvedSources(new RunControl()).get(0);
            File file = new File(f.root, "catalog/js.json"); JSONObject state = JsonFiles.read(file); state.getJSONArray("plugins").getJSONObject(0).put("source", "changed source"); JsonFiles.write(file, state);
            assertThrows(SecurityException.class, () -> f.js.checkCurrent(old, new RunControl())); assertThrows(SecurityException.class, () -> f.js.approvedSources(new RunControl()));
        }
    }
    @Test public void realJsCatalogRequiresExactBytesAndIndependentFormatAndSourceConfiguration() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            ExecutablePluginCatalog.Snapshot snapshot = f.js.refresh("scripts", new RunControl()); ExecutablePluginCatalog.Inspection review = f.js.prepare(snapshot, "script", "one", new RunControl());
            JSONObject config = f.catalog.sources(); f.catalog.configure(config.getJSONArray("sources"), config.getString("revision"));
            assertThrows(IllegalStateException.class, () -> f.js.installReviewed(review, review.reviewDigest, new RunControl())); assertEquals(0, f.js.snapshot().getJSONArray("plugins").length());
            assertThrows(IllegalArgumentException.class, () -> f.js.refresh("declarations", new RunControl()));
            snapshot = f.js.refresh("scripts", new RunControl()); f.responses.put("/script.pwjs.json", "tampered".getBytes(StandardCharsets.UTF_8)); final ExecutablePluginCatalog.Snapshot selected = snapshot;
            assertThrows(SecurityException.class, () -> f.js.prepare(selected, "script", "one", new RunControl()));
        }
    }
    @Test public void wrongNativeReviewCancellationAndForeignCatalogDoNotApproveOrInstall() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            ExecutablePluginCatalog.Inspection review = f.js.inspect(source("one", "defineExtension({tools:[]});"), new RunControl());
            assertThrows(SecurityException.class, () -> f.js.installReviewed(review, "f".repeat(64), new RunControl()));
            RunControl stopped = new RunControl(); stopped.cancel(); assertThrows(InterruptedException.class, () -> f.js.installReviewed(review, review.reviewDigest, stopped));
            try (ExecutablePluginCatalog foreign = new ExecutablePluginCatalog(f.catalog, new File(f.root, "foreign.json"))) { assertThrows(SecurityException.class, () -> foreign.installReviewed(review, review.reviewDigest, new RunControl())); }
            assertEquals(0, f.js.snapshot().getJSONArray("plugins").length());
        }
    }
    @Test public void reviewedUninstallPersistsRemovalAndNeverReusesOldRunOrInstallApproval() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            JsPluginPackage pkg = source("same", "defineExtension({tools:[]});");
            install(f.js, pkg); approveAndEnable(f.js);
            ExecutablePluginCatalog.ApprovedSource original = f.js.approvedSources(new RunControl()).get(0);
            ExecutablePluginCatalog.Inspection oldUpdate = f.js.inspect(pkg, new RunControl());
            ExecutablePluginCatalog.InstalledReview remove = f.js.reviewInstalled("script", new RunControl());
            JSONObject receipt = f.js.removeReviewed(remove, remove.reviewDigest, new RunControl());
            assertTrue(receipt.getBoolean("removed")); assertEquals(original.installationId, receipt.getString("installationId"));
            assertEquals(0, f.js.snapshot().getJSONArray("plugins").length());
            assertThrows(SecurityException.class, () -> f.js.checkCurrent(original, new RunControl()));
            assertThrows(SecurityException.class, () -> f.js.removeReviewed(remove, remove.reviewDigest, new RunControl()));
            assertThrows(IllegalStateException.class, () -> f.js.installReviewed(oldUpdate, oldUpdate.reviewDigest, new RunControl()));
            try (ExecutablePluginCatalog reopened = new ExecutablePluginCatalog(f.catalog, new File(f.root, "catalog/js.json"))) {
                assertEquals(0, reopened.approvedSources(new RunControl()).size());
                JSONObject installed = install(reopened, pkg).getJSONObject("plugin");
                assertNotEquals(original.installationId, installed.getString("installationId"));
                assertFalse(installed.getBoolean("approved")); assertFalse(installed.getBoolean("enabled"));
                assertTrue(installed.getLong("approvalRevision") > receipt.getLong("revision"));
            }
        }
    }
    @Test public void cancelledWrongOrStaleUninstallReviewDoesNotRemoveTheCurrentInstallation() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, source("one", "defineExtension({tools:[]});"));
            ExecutablePluginCatalog.InstalledReview remove = f.js.reviewInstalled("script", new RunControl());
            RunControl cancelled = new RunControl(); cancelled.cancel();
            assertThrows(InterruptedException.class, () -> f.js.removeReviewed(remove, remove.reviewDigest, cancelled));
            assertThrows(SecurityException.class, () -> f.js.removeReviewed(remove, "f".repeat(64), new RunControl()));
            install(f.js, source("two", "defineExtension({tools:[]}); //new"));
            assertThrows(IllegalStateException.class, () -> f.js.removeReviewed(remove, remove.reviewDigest, new RunControl()));
            assertEquals("two", f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getString("version"));
        }
    }
    @Test public void uncertainPrivateWriteConsumesReviewAndDoesNotInventAnInstalledReceipt() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            File directory = new File(f.root, "not-a-record-file"); assertTrue(directory.mkdir());
            try (ExecutablePluginCatalog broken = new ExecutablePluginCatalog(f.catalog, directory)) {
                ExecutablePluginCatalog.Inspection review = broken.inspect(source("one", "defineExtension({tools:[]});"), new RunControl());
                JSONObject result = broken.installReviewed(review, review.reviewDigest, new RunControl());
                assertEquals("unknown", result.getString("status")); assertTrue(result.isNull("installed")); assertFalse(result.getBoolean("replayAllowed"));
                assertThrows(SecurityException.class, () -> broken.installReviewed(review, review.reviewDigest, new RunControl()));
            }
        }
    }
}
