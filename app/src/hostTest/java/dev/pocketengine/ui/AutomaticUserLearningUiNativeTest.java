package dev.pocketengine.ui;

import android.app.Application;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real native Controller -> original Application -> persisted policy, without model/startup work.
 * This is controller acceptance, not Compose visual or physical-device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutomaticUserLearningUiNativeTest {
    private static final class Reply implements Function2<JSONObject,String,Unit> {
        final AtomicReference<JSONObject> value=new AtomicReference<>();final AtomicReference<String> error=new AtomicReference<>();int calls;
        public Unit invoke(JSONObject actual,String failure){calls++;value.set(actual);error.set(failure);return Unit.INSTANCE;}
    }
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=new EngineApplication();final PocketWorldController controller;final ExecutorService io;final android.database.sqlite.SQLiteDatabase db;
        final org.robolectric.android.controller.ActivityController<ComposeActivity> activityOwner;
        final MediaController media;
        Fixture()throws Exception {
            Method attach=Application.class.getDeclaredMethod("attach",android.content.Context.class);attach.setAccessible(true);attach.invoke(app,RuntimeEnvironment.getApplication());
            app.deleteDatabase("pocket-world.db");app.settings=new SettingsStore(app);app.tasks=new TaskStore(app);var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);db=(android.database.sqlite.SQLiteDatabase)database.get(app.tasks);app.features=new FeatureHost(event->{},app.tasks,()->false);
            app.features.register(new MemoryModule(new FeatureContext(app,app.settings,new SecretVault(app),event->{})));
            // Honor the original Activity's media-before-controller mount contract without running unrelated startup.
            activityOwner=Robolectric.buildActivity(ComposeActivity.class);
            ComposeActivity activity=activityOwner.get();Shadows.shadowOf(activity).setApplication(app);
            media=new MediaController(activity,app::publish);
            var mediaField=ComposeActivity.class.getDeclaredField("media");mediaField.setAccessible(true);mediaField.set(activity,media);
            controller=new PocketWorldController(activity,app);
            var field=PocketWorldController.class.getDeclaredField("io");field.setAccessible(true);io=(ExecutorService)field.get(controller);
        }
        void call(String prefix,Object...args)throws Exception {Method selected=null;for(Method method:PocketWorldController.class.getDeclaredMethods())if(method.getName().startsWith(prefix)&&method.getParameterCount()==args.length){assertNull("ambiguous original controller entry",selected);selected=method;}assertNotNull(prefix+" was not compiled from current controller",selected);selected.setAccessible(true);selected.invoke(controller,args);}
        void settle()throws Exception {io.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        public void close(){controller.detach();media.close();activityOwner.close();app.onTerminate();db.close();}
    }
    @Test public void ownerReadsDefaultOffAndMustConfirmBeforeEnablingThenCanWithdraw()throws Exception {try(Fixture f=new Fixture()){
        Reply read=new Reply();f.call("readAutomaticUserLearningPolicy",read);f.settle();assertNull(read.error.get());assertFalse(read.value.get().getBoolean("enabled"));
        long revision=read.value.get().getLong("revision");Reply denied=new Reply();f.call("saveAutomaticUserLearningPolicy",revision,true,2,false,denied);f.settle();assertNotNull(denied.error.get());assertFalse(denied.value.get().getBoolean("enabled"));
        Reply enabled=new Reply();f.call("saveAutomaticUserLearningPolicy",revision,true,2,true,enabled);f.settle();assertNull(enabled.error.get());assertTrue(enabled.value.get().getBoolean("enabled"));assertEquals(2,enabled.value.get().getInt("maxUserSourcesPerTask"));
        Reply off=new Reply();f.call("saveAutomaticUserLearningPolicy",enabled.value.get().getLong("revision"),false,2,false,off);f.settle();assertNull(off.error.get());assertFalse(off.value.get().getBoolean("enabled"));assertFalse(f.app.automaticUserLearningPolicy().getBoolean("enabled"));
    }}
    @Test public void staleSaveReportsFailureAndReadsBackActualCommittedSettingWithoutRetry()throws Exception {try(Fixture f=new Fixture()){
        JSONObject before=f.app.automaticUserLearningPolicy(),actual=f.app.setAutomaticUserLearningPolicy(before.getLong("revision"),true,1,true);Reply stale=new Reply();
        f.call("saveAutomaticUserLearningPolicy",before.getLong("revision"),false,1,false,stale);f.settle();assertEquals(1,stale.calls);assertNotNull(stale.error.get());assertTrue(stale.value.get().getBoolean("enabled"));assertEquals(actual.getLong("revision"),stale.value.get().getLong("revision"));assertEquals(actual.getLong("revision"),f.app.automaticUserLearningPolicy().getLong("revision"));
    }}
    @Test public void groupAndTeamViewsCannotReadOrChangePrivateAutomaticPolicy()throws Exception {try(Fixture f=new Fixture()){
        var domain=PocketWorldController.class.getDeclaredField("ownerDomain");domain.setAccessible(true);JSONObject before=f.app.automaticUserLearningPolicy();
        for(String selected:new String[]{"groups","teams"}){domain.set(f.controller,selected);Reply read=new Reply(),save=new Reply();f.call("readAutomaticUserLearningPolicy",read);f.call("saveAutomaticUserLearningPolicy",before.getLong("revision"),true,5,true,save);f.settle();assertEquals(0,read.calls);assertEquals(0,save.calls);assertFalse(f.app.automaticUserLearningPolicy().getBoolean("enabled"));assertEquals(before.getLong("revision"),f.app.automaticUserLearningPolicy().getLong("revision"));}
    }}
}
