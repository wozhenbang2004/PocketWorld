package dev.pocketengine.store;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.nio.file.Files;
import java.time.Clock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ModelPreferencesNativeJourneyTest {
    @Test public void onlyCommittedSuccessfulNativeInferenceRecordsRecentUse()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.getSharedPreferences("private_settings",0).edit().clear().commit();SettingsStore settings=new SettingsStore(app);
        settings.save(new JSONObject().put("provider","openai_chat").put("baseUrl","https://fixture.invalid/v1").put("model","fixture-model").put("sendScreenshots",false));
        TaskStore tasks=new TaskStore(app);
        try{
            ModelConnectionService service=new ModelConnectionService(settings,Files.createTempDirectory("native-preference-test").resolve("catalog.json"),Clock.systemUTC());service.connections();service.install();var captured=settings.get();
            JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("preference evidence","conversation").getString("id"),"capabilities",12);RunControl original=new RunControl();original.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));var journal=tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),original);
            assertEquals(0,first(service).getLong("lastUsedAt"));
            InferenceCapture failed=new InferenceCapture(captured,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));failed.dispatch();failed.finish(InferenceReceipt.Outcome.FAILED);assertEquals(0,first(service).getLong("lastUsedAt"));
            InferenceCapture pending=new InferenceCapture(captured,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));pending.dispatch();assertEquals(0,first(service).getLong("lastUsedAt"));pending.finish(InferenceReceipt.Outcome.CANCELLED);assertEquals(0,first(service).getLong("lastUsedAt"));
            InferenceCapture success=new InferenceCapture(captured,original,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));success.dispatch();success.finish(InferenceReceipt.Outcome.COMPLETED);assertTrue(success.terminalPersisted());assertTrue(first(service).getLong("lastUsedAt")>0);assertEquals(1,tasks.inferenceUsage(task.getString("id")).getInt("completed"));
        }finally{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    @Test public void originalPersistenceFailureCannotPublishSuccessfulRecentUse()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences("private_settings",0).edit().clear().commit();SettingsStore settings=new SettingsStore(app);settings.save(new JSONObject().put("provider","openai_chat").put("baseUrl","https://fixture.invalid/v1").put("model","fixture-model").put("sendScreenshots",false));
        ModelConnectionService service=new ModelConnectionService(settings,Files.createTempDirectory("native-preference-failure").resolve("catalog.json"),Clock.systemUTC());service.connections();service.install();RunControl original=new RunControl();original.bindHostRun("fixture-task","fixture-run",1);
        var target=new InferenceCapture.Target(new InferenceReceipt.Context("fixture-task","fixture-run",1,InferenceReceipt.Purpose.MAIN,""),new InferenceCapture.Sink(){public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt)throws Exception{throw new java.io.IOException("fixture persistence unavailable");}});
        InferenceCapture capture=new InferenceCapture(settings.get(),original,target);capture.dispatch();assertThrows(java.io.IOException.class,()->capture.finish(InferenceReceipt.Outcome.COMPLETED));assertFalse(capture.terminalPersisted());assertEquals(0,first(service).getLong("lastUsedAt"));
    }
    private static JSONObject first(ModelConnectionService service)throws Exception{JSONArray models=service.modelPickerSnapshot().getJSONArray("models");assertEquals(1,models.length());return models.getJSONObject(0);}
}
