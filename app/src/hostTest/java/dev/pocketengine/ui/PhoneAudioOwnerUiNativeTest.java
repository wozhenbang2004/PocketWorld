package dev.pocketengine.ui;

import android.app.Application;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
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

/** Current real Controller/Module/private preferences and durable ledger, not Compose rendering
 * or an assertion that system authorization, a helper or physical appops are available. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneAudioOwnerUiNativeTest {
    private static final class Reply implements Function2<JSONObject,String,Unit> {
        final AtomicReference<JSONObject> value=new AtomicReference<>();final AtomicReference<String> error=new AtomicReference<>();int calls;
        public Unit invoke(JSONObject actual,String failure){calls++;value.set(actual);error.set(failure);return Unit.INSTANCE;}
    }
    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=new EngineApplication();final DisplayModule display;final PocketWorldController controller;final ExecutorService io;final android.database.sqlite.SQLiteDatabase db;
        Fixture()throws Exception {
            Method attach=Application.class.getDeclaredMethod("attach",android.content.Context.class);attach.setAccessible(true);attach.invoke(app,RuntimeEnvironment.getApplication());
            app.getSharedPreferences("phone_audio_policy",0).edit().clear().commit();app.getSharedPreferences("phone_audio_recovery",0).edit().clear().commit();
            app.deleteDatabase("pocket-world.db");app.settings=new SettingsStore(app);app.tasks=new TaskStore(app);var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);db=(android.database.sqlite.SQLiteDatabase)database.get(app.tasks);app.features=new FeatureHost(event->{},app.tasks,()->false);
            display=new DisplayModule(new FeatureContext(app,app.settings,new SecretVault(app),event->{}));app.features.register(display);controller=new PocketWorldController(new ComposeActivity(),app);
            var field=PocketWorldController.class.getDeclaredField("io");field.setAccessible(true);io=(ExecutorService)field.get(controller);
        }
        void call(String prefix,Object...args)throws Exception {Method selected=null;for(Method m:PocketWorldController.class.getDeclaredMethods())if(m.getName().startsWith(prefix)&&!java.lang.reflect.Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==args.length){assertNull(selected);selected=m;}assertNotNull("entry must be compiled from the current Controller",selected);selected.setAccessible(true);selected.invoke(controller,args);}
        void domain(String selected)throws Exception{var field=PocketWorldController.class.getDeclaredField("ownerDomain");field.setAccessible(true);field.set(controller,selected);}
        void settle()throws Exception{io.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        public void close(){controller.detach();app.onTerminate();db.close();}
    }
    @Test public void explicitPrivateOwnerCanEnableOrDisableWithoutGrantingOrMutatingSystemAudio()throws Exception{try(Fixture f=new Fixture()){
        assertFalse(f.display.snapshot().getBoolean("audioSilenceEnabled"));Reply on=new Reply();f.call("setBackgroundAudioSilence",true,on);f.settle();assertNull(on.error.get());assertTrue(on.value.get().getBoolean("enabled"));assertFalse(on.value.get().getBoolean("systemPermissionGranted"));assertFalse(on.value.get().getBoolean("audioMutation"));assertFalse(f.display.snapshot().getBoolean("helperConnected"));
        Reply off=new Reply();f.call("setBackgroundAudioSilence",false,off);f.settle();assertNull(off.error.get());assertFalse(off.value.get().getBoolean("enabled"));assertFalse(AndroidPhoneAudioRecovery.silenceEnabled(f.app));
    }}
    @Test public void groupsTeamsAndGroupTaskPagesCannotReadOrChangePrivateAudioPolicyOrLedger()throws Exception{try(Fixture f=new Fixture()){
        for(String selected:new String[]{"groups","teams"}){f.domain(selected);Reply save=new Reply(),restore=new Reply();f.call("setBackgroundAudioSilence",true,save);f.call("restoreBackgroundAudio",restore);f.settle();assertEquals(0,save.calls);assertEquals(0,restore.calls);assertFalse(AndroidPhoneAudioRecovery.silenceEnabled(f.app));}
        f.domain(null);var page=PocketWorldController.class.getDeclaredField("groupTaskPage");page.setAccessible(true);page.set(f.controller,"original-group-page");Reply save=new Reply();f.call("setBackgroundAudioSilence",true,save);f.settle();assertEquals(0,save.calls);assertFalse(AndroidPhoneAudioRecovery.silenceEnabled(f.app));
    }}
    @Test public void viewChangedBeforeQueuedWorkerCannotApplyTheOldPrivateOptIn()throws Exception{try(Fixture f=new Fixture()){
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.io.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
        Reply save=new Reply();try{f.call("setBackgroundAudioSilence",true,save);f.domain("groups");}finally{release.countDown();}f.settle();assertEquals(0,save.calls);assertFalse(AndroidPhoneAudioRecovery.silenceEnabled(f.app));
    }}
    @Test public void enablingOverALiveNativeTaskIsDeniedButRevocationRemainsAvailable()throws Exception{try(Fixture f=new Fixture()){
        f.display.pinOwner("native-task");Reply denied=new Reply();f.call("setBackgroundAudioSilence",true,denied);f.settle();assertEquals(1,denied.calls);assertNotNull(denied.error.get());assertFalse(denied.value.get().getBoolean("enabled"));
        AndroidPhoneAudioRecovery.setOwnerSilenceEnabled(f.app,true);Reply off=new Reply();f.call("setBackgroundAudioSilence",false,off);f.settle();assertNull(off.error.get());assertFalse(off.value.get().getBoolean("enabled"));
    }}
    @Test public void absentHelperCannotTurnAPriorDurableUndoIntoConfirmedSuccess()throws Exception{try(Fixture f=new Fixture()){
        AtomicReference<String> priorMode=new AtomicReference<>("allow");AndroidPhoneAudioRecovery.withCommands((command,args)->{if(command.equals("audio.set"))priorMode.set(args.getString("mode"));return new JSONObject().put("ok",true).put("mode",priorMode.get());},()->AndroidPhoneAudioRecovery.get(f.app).silence("retained-original/run/1",new AppCopyKey("example.original",0),true));
        assertEquals("ignore",priorMode.get());Reply restore=new Reply();f.call("restoreBackgroundAudio",restore);f.settle();assertNull(restore.error.get());assertEquals(0,restore.value.get().getInt("restored"));assertEquals(1,restore.value.get().getInt("pending"));assertEquals(1,AndroidPhoneAudioRecovery.get(f.app).snapshot().getInt("pendingCount"));assertFalse(f.display.snapshot().getBoolean("helperConnected"));
    }}
}
