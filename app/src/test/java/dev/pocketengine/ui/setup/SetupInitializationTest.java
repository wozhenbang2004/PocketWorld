package dev.pocketengine.ui.setup;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Uses Android storage paths in an isolated host Application, not the real owner's database. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public final class SetupInitializationTest {
    private Context context;

    @Before public void resetOnlyFixture() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(SetupPreferences.PREFERENCES_NAME, 0).edit().clear().commit();
        context.getSharedPreferences("private_settings", 0).edit().clear().commit();
        context.deleteDatabase("pocket-world.db"); context.deleteDatabase("sdk-cache.db");
    }

    private void existingFile(File file) throws Exception {
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), "isolated prior-use fixture".getBytes(StandardCharsets.UTF_8));
    }

    private String status() throws Exception {
        return new SetupPreferences(context).read().getJSONObject("preferences").getString("status");
    }

    @Test public void sdkCacheDoesNotHideFirstUseOnCleanInstall() throws Exception {
        existingFile(context.getDatabasePath("sdk-cache.db"));
        SetupPreferences.initialize(context);
        assertEquals("new", status());
    }

    @Test public void businessDatabaseCreatedAfterEarlyInitializationDoesNotChangeNewUserDecision() throws Exception {
        SetupPreferences.initialize(context);
        existingFile(context.getDatabasePath("pocket-world.db"));
        context.getSharedPreferences("private_settings", 0).edit().putString("model", "later-choice").commit();
        SetupPreferences.initialize(context);
        assertEquals("new", status());
    }

    @Test public void existingBusinessDatabaseSkipsAutomaticEntryWithoutModifyingOriginalBytes() throws Exception {
        File original = context.getDatabasePath("pocket-world.db"); existingFile(original);
        byte[] before = Files.readAllBytes(original.toPath());
        SetupPreferences.initialize(context);
        assertEquals("skipped", status()); assertArrayEquals(before, Files.readAllBytes(original.toPath()));
    }

    @Test public void priorSettingsRemainExistingUserEvidenceWithoutAReadableDatabase() throws Exception {
        context.getSharedPreferences("private_settings", 0).edit().putString("model", "owner-choice").commit();
        SetupPreferences.initialize(context); assertEquals("skipped", status());
    }

    @Test public void savedChoicesResumeAfterReopeningNativeStore() throws Exception {
        SetupPreferences.initialize(context);
        new SetupPreferences(context).save(new JSONObject("{\"expectedRevision\":1,\"step\":\"capabilities\",\"capabilities\":[\"tools\"],\"status\":\"in_progress\"}"));
        SetupPreferences.initialize(context);
        JSONObject actual = new SetupPreferences(context).read();
        assertEquals(2, actual.getLong("revision"));
        assertEquals("capabilities", actual.getJSONObject("preferences").getString("step"));
        assertEquals("in_progress", actual.getJSONObject("preferences").getString("status"));
    }
}
