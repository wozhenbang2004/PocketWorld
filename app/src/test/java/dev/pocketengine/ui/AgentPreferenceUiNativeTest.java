package dev.pocketengine.ui;

import android.app.Application;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual application state and owner controller; no app startup jobs, model or device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AgentPreferenceUiNativeTest {
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app;
        final PocketWorldController controller;
        final Method receive;
        final ExecutorService io;
        Fixture()throws Exception {
            // Context-attached original application without starting unrelated recovery/workers.
            app=new EngineApplication();
            Method attach=Application.class.getDeclaredMethod("attach",android.content.Context.class);attach.setAccessible(true);
            attach.invoke(app,RuntimeEnvironment.getApplication());
            app.getSharedPreferences("private_settings",0).edit().clear().commit();
            app.deleteDatabase("pocket-world.db");
            app.settings=new SettingsStore(app);app.tasks=new TaskStore(app);
            controller=new PocketWorldController(new ComposeActivity(),app);
            receive=PocketWorldController.class.getDeclaredMethod("receive",JSONObject.class);receive.setAccessible(true);
            var field=PocketWorldController.class.getDeclaredField("io");field.setAccessible(true);io=(ExecutorService)field.get(controller);
            receive.invoke(controller,app.state());
        }
        void completedSave()throws Exception {io.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        public void close(){controller.detach();app.onTerminate();}
    }
    @Test public void originalOwnerControllerSavesConfirmedPreferencesAndApplicationStateReturnsThem()throws Exception {
        try(var f=new Fixture()){
            assertFalse(f.controller.getState().getAgentPreferences().getBoolean("enabled"));
            assertEquals(1,f.controller.getState().getAgentPreferences().getLong("revision"));
            f.controller.saveAgentPreferences(true,"detailed","careful",1);assertTrue(f.controller.getState().getAgentPreferencesSaving());f.completedSave();
            assertNull(f.controller.getState().getAgentPreferencesError());assertFalse(f.controller.getState().getAgentPreferencesSaving());
            assertEquals("detailed",f.app.settings.agentPreferences().getString("response_detail"));
            assertEquals("careful",f.controller.getState().getAgentPreferences().getString("operation_pace"));
            assertEquals(2,f.app.state().getJSONObject("payload").getJSONObject("agentPreferences").getLong("revision"));
            f.controller.saveAgentPreferences(false,"standard","standard",1);f.completedSave();
            assertNotNull("Stale owner action must surface the CAS failure",f.controller.getState().getAgentPreferencesError());
            assertTrue(f.app.settings.agentPreferences().getBoolean("enabled"));assertEquals(2,f.app.settings.agentPreferences().getLong("revision"));
        }
    }
    @Test public void olderReceiptAndStateCannotReplaceNewerConfirmedOwnerRevision()throws Exception {
        try(var f=new Fixture()){
            f.controller.saveAgentPreferences(true,"concise","standard",1);
            f.io.submit(()->{}).get(5,TimeUnit.SECONDS); // Receipt for revision 2 is queued, not yet delivered.
            f.app.settings.saveAgentPreferences(false,"detailed","careful",2);
            receiveCurrent(f); // Newer original state reaches the owner UI first.
            assertEquals(3,f.controller.getState().getAgentPreferences().getLong("revision"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Delayed save receipt must not overwrite newer confirmed owner state",3,f.controller.getState().getAgentPreferences().getLong("revision"));
            assertFalse(f.controller.getState().getAgentPreferencesSaving());
            JSONObject stale=f.app.state();stale.getJSONObject("payload").put("agentPreferences",new JSONObject().put("enabled",true).put("revision",2).put("response_detail","concise").put("operation_pace","standard"));
            f.receive.invoke(f.controller,stale);
            assertEquals(3,f.controller.getState().getAgentPreferences().getLong("revision"));
            assertFalse(f.controller.getState().getAgentPreferences().getBoolean("enabled"));
        }
    }
    private static void receiveCurrent(Fixture f)throws Exception {f.receive.invoke(f.controller,f.app.state());}
}
