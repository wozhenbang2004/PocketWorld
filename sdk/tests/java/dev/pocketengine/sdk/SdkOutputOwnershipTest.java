package dev.pocketengine.sdk;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.sdk.PluginSdk;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class SdkOutputOwnershipTest {
    @Parameterized.Parameters(name = "{0}") public static Object[][] kinds() {
        return new Object[][]{{"declaration", false}, {"executable_js", true}};
    }
    private final boolean js;
    public SdkOutputOwnershipTest(String name, boolean js) { this.js = js; }
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    private interface Packer { void pack(Path target, RunControl control) throws Exception; }
    private record Package(byte[] bytes, Packer packer) { }
    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
    private static Package artifact(boolean js) throws Exception {
        if (js) {
            JsPluginPackage artifact = JsPluginPackage.author(input("// static source"), "test.js", "Test", "1", new RunControl());
            return new Package(artifact.packageBytes(), (target, control) -> JsPluginSdkMain.pack(artifact, target, control));
        }
        PluginSdk sdk = PluginSdk.offline();
        JSONObject manifest = PluginSdk.template("echo", "test.echo", "Test", "1", null);
        PluginSdk.Prepared prepared = sdk.prepare(input(manifest.toString()), new JSONObject().put("label", "Test"), new RunControl());
        return new Package(prepared.bytes(), (target, control) -> PluginSdkMain.pack(sdk, prepared, target, control));
    }
    private static Exception failure(Package artifact, Path target, RunControl control) throws Exception {
        try { artifact.packer.pack(target, control); fail("Pack must fail"); return null; }
        catch (Exception failure) { return failure; }
    }
    private static void retainedFailure(Exception failure, Path target) {
        assertTrue(failure.toString(), failure instanceof IOException);
        assertTrue(failure.getMessage(), failure.getMessage().contains("no output was removed"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("partial or replaced"));
        assertTrue(failure.getMessage(), failure.getMessage().contains(target.toString()));
        assertNotNull(failure.getCause());
    }

    @Test public void replacementFileSurvivesWriteFailure() throws Exception { replacementSurvives(false); }
    @Test public void replacementEmptyDirectorySurvivesWriteFailure() throws Exception { replacementSurvives(true); }
    private void replacementSurvives(boolean replaceWithDirectory) throws Exception {
        Path parent = directory.newFolder().toPath(), target = parent.resolve("package.json"), moved = parent.resolve("original.json");
        RunControl control = new RunControl(); AtomicBoolean replaced = new AtomicBoolean();
        control.setAuthorityCheck(() -> {
            if (replaced.compareAndSet(false, true)) {
                Files.move(target, moved);
                if (replaceWithDirectory) Files.createDirectory(target);
                else Files.writeString(target, "replacement output");
                throw new IOException("injected post-replacement failure");
            }
        });
        Exception failure = failure(artifact(js), target, control);
        assertTrue("Replacement must not be deleted", Files.exists(target));
        if (replaceWithDirectory) assertTrue(Files.isDirectory(target));
        else assertEquals("replacement output", Files.readString(target));
        assertTrue(Files.isRegularFile(moved));
        retainedFailure(failure, target);
        assertTrue(failure.getCause() instanceof InterruptedException);
        assertEquals("injected post-replacement failure", failure.getCause().getCause().getMessage());
    }

    @Test public void unchangedEmptyOutputSurvivesWriteFailure() throws Exception {
        Path target = directory.newFolder().toPath().resolve("package.json");
        RunControl control = new RunControl();
        control.setAuthorityCheck(() -> { throw new IOException("injected before first write"); });
        Exception failure = failure(artifact(js), target, control);
        assertTrue(Files.isRegularFile(target)); assertEquals(0, Files.size(target));
        retainedFailure(failure, target);
    }

    @Test public void unconfirmedWrittenOutputIsRetained() throws Exception {
        Path target = directory.newFolder().toPath().resolve("package.json");
        Package artifact = artifact(js); RunControl control = new RunControl();
        control.setAuthorityCheck(() -> {
            if (Files.size(target) > 0) throw new IOException("injected after write");
        });
        Exception failure = failure(artifact, target, control);
        assertArrayEquals(artifact.bytes, Files.readAllBytes(target));
        retainedFailure(failure, target);
    }

    @Test public void successKeepsExactBytesAndExistingOutputIsNeverOverwritten() throws Exception {
        Path target = directory.newFolder().toPath().resolve("package.json"); Package artifact = artifact(js);
        artifact.packer.pack(target, new RunControl()); assertArrayEquals(artifact.bytes, Files.readAllBytes(target));
        assertTrue(failure(artifact, target, new RunControl()) instanceof FileAlreadyExistsException);
        assertArrayEquals(artifact.bytes, Files.readAllBytes(target));
    }
}
