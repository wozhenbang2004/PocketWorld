package dev.pocketengine.media.jobs;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.media.MediaModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual native module + loopback WebSocket; no real account, device or capture is involved. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class MediaProgressModuleTest {
    @Test public void nativeObserverFreezesConfigurationAndModuleCloseStopsOnlyObservation() throws Exception {
        try (ComfyUiProgressObserverTest.Fixture fixture = new ComfyUiProgressObserverTest.Fixture()) {
            File files = Files.createTempDirectory("native-progress-").toFile();
            Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return files; }
            };
            MediaModule module = new MediaModule(new FeatureContext(context, new SettingsStore(context), new SecretVault(context), event -> {}));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                JSONObject configure = new JSONObject().put("provider", "comfyui").put("baseUrl", fixture.base());
                module.handle("configure", configure); module.handle("submitWorkflow", fixture.request());
                String revision = module.snapshot().getString("configurationRevision");
                RunControl control = ComfyUiProgressObserverTest.control(); AtomicInteger observed = new AtomicInteger();
                Future<JSONObject> result = worker.submit(() -> module.observeProgress(fixture.binding(revision), control, event -> observed.incrementAndGet()));
                assertTrue(fixture.connected.await(2, TimeUnit.SECONDS));
                assertEquals(1, module.snapshot().getInt("activeProgressObservers"));
                assertThrows(IllegalStateException.class, () -> module.handle("configure", configure));
                module.close();
                assertEquals("observation_cancelled", result.get(2, TimeUnit.SECONDS).getString("endReason"));
                assertFalse(control.isCancelled()); // Module closes its child observation, not the host execution control.
                assertEquals(0, observed.get()); assertEquals(0, fixture.cancels.get()); assertEquals(1, fixture.submissions.get());
            } finally { module.close(); worker.shutdownNow(); }
        }
    }
    @Test public void unboundOrChangedNativeControlsCannotStartObservation() throws Exception {
        File files = Files.createTempDirectory("native-progress-guard-").toFile();
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
        };
        MediaModule module = new MediaModule(new FeatureContext(context, new SettingsStore(context), new SecretVault(context), event -> {}));
        try {
            JSONObject input = new JSONObject().put("_taskId", "task-1").put("_runId", "run-1").put("_epoch", 1);
            assertThrows(SecurityException.class, () -> module.observeProgress(input, new RunControl(), event -> {}));
            RunControl wrong = new RunControl(); wrong.bindHostRun("task-1", "run-1", 2);
            assertThrows(SecurityException.class, () -> module.observeProgress(input, wrong, event -> {}));
        } finally { module.close(); }
    }
}
