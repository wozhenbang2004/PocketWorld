package dev.pocketengine.features.plugins.catalog;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.runtime.extensions.ExtensionProgram;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Program/Binding/Runtime and catalog/file state; native TaskStore admission is a read-only fixture port. */
public final class ExecutableExtensionSourceTest {
    private static final String TASK = "11111111-1111-1111-1111-111111111111", RUN = "22222222-2222-2222-2222-222222222222", NEXT = "33333333-3333-3333-3333-333333333333";
    private static final class NativeRun implements ExecutableExtensionSource.NativeRunAuthority {
        ExtensionRuntime.Binding binding = new ExtensionRuntime.Binding(TASK, RUN, 1);
        RunControl control = control(binding);
        boolean active = true;
        int checks;
        Action during;
        @Override public void check(ExtensionRuntime.Binding binding, RunControl control) throws Exception {
            checks++;
            if (!active || this.control != control || !this.binding.taskId.equals(binding.taskId) || !this.binding.runId.equals(binding.runId) || this.binding.epoch != binding.epoch)
                throw new SecurityException("fixture authoritative current run/control differs");
            if (during != null) during.run(checks);
        }
        void next() { binding = new ExtensionRuntime.Binding(TASK, NEXT, 2); control = control(binding); }
    }
    private interface Action { void run(int check) throws Exception; }
    private static RunControl control(ExtensionRuntime.Binding binding) { RunControl control = new RunControl(); control.bindHostRun(binding.taskId, binding.runId, binding.epoch); return control; }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void install(ExecutablePluginCatalog catalog, String id, String version, String text) throws Exception {
        JsPluginPackage pkg = JsPluginPackage.author(new ByteArrayInputStream(bytes(text)), id, "实际源码", version, new RunControl());
        ExecutablePluginCatalog.Inspection review = catalog.inspect(pkg, new RunControl());
        assertEquals("installed_unapproved", catalog.installReviewed(review, review.reviewDigest, new RunControl()).getString("status"));
    }
    private static void approveEnable(ExecutablePluginCatalog catalog, String id) throws Exception {
        ExecutablePluginCatalog.InstalledReview review = catalog.reviewInstalled(id, new RunControl()); catalog.approveReviewed(review, review.reviewDigest, true, new RunControl());
        enabled(catalog, id, true);
    }
    private static void enabled(ExecutablePluginCatalog catalog, String id, boolean enabled) throws Exception {
        ExecutablePluginCatalog.InstalledReview review = catalog.reviewInstalled(id, new RunControl()); catalog.setEnabledReviewed(review, review.reviewDigest, enabled, new RunControl());
    }
    private static final class EvaluationBoundary implements ExtensionRuntime.Evaluator {
        int evaluations;
        @Override public String evaluate(byte[] source, JSONObject request, int maxMillis, RunControl control, ExtensionRuntime.CurrentCheck current) {
            evaluations++; throw new IllegalStateException("fixture stops at the real evaluation boundary");
        }
        @Override public void close() { }
    }

    @Test public void actualSevenArgumentProgramKeepsExactSourceApprovalIdentityAndNativeBudgetWithoutEvaluation() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            String exact = "\ufeff//原始😀\r\nthrow new Error('do not run during admission');\n";
            install(f.js, "script", "opaque-version", exact); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun(); EvaluationBoundary evaluator = new EvaluationBoundary();
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 150)) {
                List<ExtensionProgram> programs = source.approved(nativeRun.binding, nativeRun.control); assertEquals(1, programs.size()); ExtensionProgram program = programs.get(0);
                ExecutablePluginCatalog.ApprovedSource approved = f.js.approvedSources(new RunControl()).get(0);
                assertEquals(approved.installationId, program.installationId); assertEquals(approved.approvalRevision, program.approvalRevision);
                assertTrue(program.approvalRevision > 1); assertEquals(approved.sourceSha256, program.sourceSha256); assertEquals(150, program.maxMillis); assertArrayEquals(bytes(exact), program.sourceBytes());
                assertEquals(0, evaluator.evaluations); assertTrue(nativeRun.checks >= 2); assertThrows(UnsupportedOperationException.class, programs::clear);
                program.sourceBytes()[0] = 0; source.checkCurrent(program, new ExtensionRuntime.Binding(TASK, RUN, 1), nativeRun.control); assertArrayEquals(bytes(exact), program.sourceBytes());
                assertSame(program, source.approved(nativeRun.binding, nativeRun.control).get(0));
                try (ExtensionRuntime runtime = new ExtensionRuntime(evaluator, nativeRun.binding, source::checkCurrent)) {
                    assertThrows(IllegalStateException.class, () -> runtime.mount(program, nativeRun.control));
                    assertEquals(1, evaluator.evaluations); // Only the explicit real runtime.mount crosses this boundary.
                }
            }
        }
    }
    @Test public void unapprovedDisabledSourcesAndLaterAdditionsDoNotJoinTheFixedOldRunSet() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, "script", "one", "defineExtension({tools:[]});"); NativeRun nativeRun = new NativeRun();
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 50)) {
                assertTrue(source.approved(nativeRun.binding, nativeRun.control).isEmpty());
                approveEnable(f.js, "script"); assertTrue(source.approved(nativeRun.binding, nativeRun.control).isEmpty());
            }
            nativeRun.next();
            try (ExecutableExtensionSource next = new ExecutableExtensionSource(f.js, nativeRun, 2000)) { assertEquals(1, next.approved(nativeRun.binding, nativeRun.control).size()); }
        }
    }
    @Test public void disableReenableAbaCannotRefreshOriginalProgramOrRepeatApprovedIntoANewPermission() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, "script", "one", "defineExtension({tools:[]});"); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun();
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) {
                ExtensionProgram original = source.approved(nativeRun.binding, nativeRun.control).get(0); enabled(f.js, "script", false); enabled(f.js, "script", true);
                assertThrows(SecurityException.class, () -> source.checkCurrent(original, nativeRun.binding, nativeRun.control));
                assertThrows(SecurityException.class, () -> source.approved(nativeRun.binding, nativeRun.control));
                nativeRun.next();
                try (ExecutableExtensionSource next = new ExecutableExtensionSource(f.js, nativeRun, 200)) {
                    ExtensionProgram current = next.approved(nativeRun.binding, nativeRun.control).get(0); assertEquals(original.installationId, current.installationId); assertTrue(current.approvalRevision > original.approvalRevision);
                }
            }
        }
    }
    @Test public void currentRunCallbackAndExactLiveControlRejectOldRunEpochAndForeignProgramObjects() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, "script", "one", "defineExtension({tools:[]});"); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun();
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) {
                ExtensionProgram program = source.approved(nativeRun.binding, nativeRun.control).get(0);
                ExtensionProgram copied = new ExtensionProgram(program.id, program.version, program.installationId, program.approvalRevision, program.sourceBytes(), program.sourceSha256, program.maxMillis);
                assertThrows(SecurityException.class, () -> source.checkCurrent(copied, nativeRun.binding, nativeRun.control));
                assertThrows(SecurityException.class, () -> source.checkCurrent(program, nativeRun.binding, control(nativeRun.binding)));
                ExtensionRuntime.Binding oldBinding = nativeRun.binding; RunControl oldControl = nativeRun.control; nativeRun.next();
                assertThrows(SecurityException.class, () -> source.checkCurrent(program, oldBinding, oldControl));
                assertThrows(SecurityException.class, () -> source.checkCurrent(program, nativeRun.binding, nativeRun.control));
                assertThrows(SecurityException.class, () -> source.approved(nativeRun.binding, nativeRun.control));
            }
        }
    }
    @Test public void cancellationAndPauseAreFencedPerApiCallWithoutPermanentlyRevokingNormalResume() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, "script", "one", "defineExtension({tools:[]});"); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun();
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) {
                assertThrows(SecurityException.class, () -> source.approved(nativeRun.binding, new RunControl()));
                ExtensionProgram program = source.approved(nativeRun.binding, nativeRun.control).get(0);
                nativeRun.control.pause(); assertThrows(SecurityException.class, () -> source.checkCurrent(program, nativeRun.binding, nativeRun.control));
                nativeRun.control.resume(); source.checkCurrent(program, nativeRun.binding, nativeRun.control);
                nativeRun.during = check -> { nativeRun.control.pause(); nativeRun.control.resume(); };
                assertThrows(SecurityException.class, () -> source.checkCurrent(program, nativeRun.binding, nativeRun.control));
                nativeRun.during = null; source.checkCurrent(program, nativeRun.binding, nativeRun.control);
                nativeRun.control.cancel(); assertThrows(InterruptedException.class, () -> source.checkCurrent(program, nativeRun.binding, nativeRun.control));
            }
        }
    }
    @Test public void nativeRunAndCatalogAreBothRecheckedAfterReadingAndImmediatelyBeforeReturningAuthority() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, "script", "one", "defineExtension({tools:[]});"); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun();
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) {
                // The callback is a read-side authority; this fixture simulates revocation racing its return.
                nativeRun.during = check -> { if (check == 2) { nativeRun.active = false; throw new SecurityException("fixture run retired during read"); } };
                assertThrows(SecurityException.class, () -> source.approved(nativeRun.binding, nativeRun.control));
                nativeRun.active = true; nativeRun.during = null;
                ExtensionProgram program = source.approved(nativeRun.binding, nativeRun.control).get(0); nativeRun.checks = 0;
                nativeRun.during = check -> { if (check == 2) enabled(f.js, "script", false); };
                assertThrows(SecurityException.class, () -> source.checkCurrent(program, nativeRun.binding, nativeRun.control));
            }
        }
    }
    @Test public void persistentReloadAndSameVersionReplacementStillInvalidateTheOriginalSourceSnapshot() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, "script", "same", "//original\r\ndefineExtension({tools:[]});"); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun();
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200);
                    ExecutablePluginCatalog reopened = new ExecutablePluginCatalog(f.catalog, new File(f.root, "catalog/js.json"))) {
                ExtensionProgram original = source.approved(nativeRun.binding, nativeRun.control).get(0);
                NativeRun second = new NativeRun();
                try (ExecutableExtensionSource read = new ExecutableExtensionSource(reopened, second, 200)) {
                    ExtensionProgram loaded = read.approved(second.binding, second.control).get(0); assertArrayEquals(original.sourceBytes(), loaded.sourceBytes()); assertEquals(original.approvalRevision, loaded.approvalRevision);
                }
                install(f.js, "script", "same", "//changed\r\ndefineExtension({tools:[]});"); approveEnable(f.js, "script");
                assertThrows(SecurityException.class, () -> source.checkCurrent(original, nativeRun.binding, nativeRun.control));
                assertThrows(SecurityException.class, () -> source.approved(nativeRun.binding, nativeRun.control));
            }
        }
    }
    @Test public void realProgramRejectsInertNulAndCorruptVersionOrEmptySourceWithoutRepairOrExecution() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            String nul = "//inert\u0000\ndefineExtension({tools:[]});"; install(f.js, "script", "one", nul); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun();
            String hash = f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getString("sourceSha256");
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) { assertThrows(IllegalArgumentException.class, () -> source.approved(nativeRun.binding, nativeRun.control)); }
            assertEquals(hash, f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getString("sourceSha256"));
            install(f.js, "script", "one", "defineExtension({tools:[]});"); approveEnable(f.js, "script");
            File file = new File(f.root, "catalog/js.json"); JSONObject state = JsonFiles.read(file); state.getJSONArray("plugins").getJSONObject(0).put("version", "bad\u0007version"); JsonFiles.write(file, state);
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) { assertThrows(IllegalArgumentException.class, () -> source.approved(nativeRun.binding, nativeRun.control)); }
            state.getJSONArray("plugins").getJSONObject(0).put("version", "one").put("source", "").put("sourceSha256", ExtensionProgram.sha256(new byte[0])); JsonFiles.write(file, state);
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) { assertThrows(SecurityException.class, () -> source.approved(nativeRun.binding, nativeRun.control)); }
        }
    }
    @Test public void ownerBudgetAndRuntimeCapacityAreExplicitAndNeverSilentlyTruncated() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            NativeRun nativeRun = new NativeRun(); assertThrows(IllegalArgumentException.class, () -> new ExecutableExtensionSource(f.js, nativeRun, 49)); assertThrows(IllegalArgumentException.class, () -> new ExecutableExtensionSource(f.js, nativeRun, 2001));
            for (int i = 0; i <= ExtensionRuntime.MAX_EXTENSIONS; i++) { String id = "script" + i; install(f.js, id, "one", "defineExtension({tools:[]});"); approveEnable(f.js, id); }
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) { assertThrows(IllegalStateException.class, () -> source.approved(nativeRun.binding, nativeRun.control)); }
        }
    }
    @Test public void actualRuntimeDispatchChecksSourceBeforeDescribeAndProviderItselfNeverEvaluates() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, "script", "one", "throw new Error('evaluation belongs to runtime');"); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun(); EvaluationBoundary evaluator = new EvaluationBoundary();
            try (ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200)) {
                ExtensionProgram program = source.approved(nativeRun.binding, nativeRun.control).get(0); assertEquals(0, evaluator.evaluations);
                enabled(f.js, "script", false);
                try (ExtensionRuntime runtime = new ExtensionRuntime(evaluator, nativeRun.binding, source::checkCurrent)) {
                    assertThrows(SecurityException.class, () -> runtime.mount(program, nativeRun.control)); assertEquals(0, evaluator.evaluations);
                }
            }
        }
    }
    @Test public void closedSourceInvalidatesAlreadyIssuedObjects() throws Exception {
        try (PluginCatalogSessionTest.Fixture f = new PluginCatalogSessionTest.Fixture()) {
            install(f.js, "script", "one", "defineExtension({tools:[]});"); approveEnable(f.js, "script"); NativeRun nativeRun = new NativeRun(); ExecutableExtensionSource source = new ExecutableExtensionSource(f.js, nativeRun, 200);
            ExtensionProgram program = source.approved(nativeRun.binding, nativeRun.control).get(0); source.close();
            assertThrows(SecurityException.class, () -> source.checkCurrent(program, nativeRun.binding, nativeRun.control)); assertThrows(SecurityException.class, () -> source.approved(nativeRun.binding, nativeRun.control));
        }
    }
}
