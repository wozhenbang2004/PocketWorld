package dev.pocketengine.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import dev.pocketengine.ui.web.PocketWebBridge;
import dev.pocketengine.ui.web.PocketWebWindowTicket;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Provider;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original Activity/Controller/App, exact preview object, native clipboard and system chooser intents.
 * No external recipient transmission, screenshots, device or visual acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,
        shadows={ReadyApplicationFixture.NoUnrelatedStartup.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneDiagnosticsOwnerNativeTest {
    private final Provider keys=new Provider("PhoneDiagnosticsOwner-"+UUID.randomUUID(),1.0,"Explicit test-only AndroidKeyStore") {
        { put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName()); }
    };
    @Before public void installKeys(){assertTrue(Security.insertProviderAt(keys,1)>0);}
    @After public void removeKeys(){Security.removeProvider(keys.getName());}
    private static final class Rig implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final ActivityController<ComposeActivity> owner;
        final ComposeActivity activity;
        final PocketWorldController controller;
        final MediaController media;
        final ClipboardManager clipboard;
        final List<JSONObject> events=new ArrayList<>();
        PocketWebBridge bridge;
        int commandSequence;
        Rig()throws Exception {
            ReadyApplicationFixture.initializeHost(app);
            owner=Robolectric.buildActivity(ComposeActivity.class);activity=owner.get();
            media=new MediaController(activity,app::publish);
            Field field=ComposeActivity.class.getDeclaredField("media");field.setAccessible(true);field.set(activity,media);
            controller=new PocketWorldController(activity,app);
            Field foreground=PocketWorldController.class.getDeclaredField("voiceForeground");foreground.setAccessible(true);foreground.setBoolean(controller,true);
            clipboard=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("owner marker","unchanged owner marker"));
        }
        Object invoke(String prefix,Object... args)throws Exception{return invokeOn(controller,prefix,args);}
        Object invokeOn(Object target,String prefix,Object... args)throws Exception {for(Method m:target.getClass().getDeclaredMethods())if((m.getName().equals(prefix)||m.getName().startsWith(prefix+"$"))&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(target,args);}throw new AssertionError("No actual native method "+prefix);}
        void drain()throws Exception {Field f=PocketWorldController.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(controller)).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));}
        ExecutorService worker()throws Exception {Field f=PocketWorldController.class.getDeclaredField("io");f.setAccessible(true);return (ExecutorService)f.get(controller);}
        PhoneDiagnosticReport.Report preview()throws Exception {
            AtomicReference<PhoneDiagnosticReport.Report> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();invoke("previewPhoneDiagnostics",(Function2<PhoneDiagnosticReport.Report,String,Unit>)(report,failure)->{result.set(report);error.set(failure);return Unit.INSTANCE;});
            for(int i=0;i<60&&result.get()==null&&error.get()==null;i++){drain();Thread.sleep(5);}assertNull(error.get());assertNotNull("Original native preview completed",result.get());return result.get();
        }
        String clip(){return clipboard.getPrimaryClip().getItemAt(0).getText().toString();}
        void startBridge()throws Exception{
            bridge=new PocketWebBridge(controller,event->{events.add(event);return Unit.INSTANCE;});bridge.start();
            assertTrue(command("app.ready",new JSONObject().put("documentId","diagnostics-owner-document")).getBoolean("ok"));
        }
        JSONObject command(String action,JSONObject args)throws Exception{
            String id="diagnostics-"+(++commandSequence);
            JSONObject request=new JSONObject().put("v",1).put("id",id).put("action",action).put("args",args)
                    .put("documentId","diagnostics-owner-document");
            PocketWebWindowTicket window=(PocketWebWindowTicket)invokeOn(bridge,"captureWindow");
            bridge.postMessage(request.toString(),window,()->{throw new AssertionError("Original bridge window unexpectedly changed");});
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            for(int i=0;i<60;i++){
                for(JSONObject event:events)if("result".equals(event.optString("kind"))&&id.equals(event.optString("id")))return event;
                drain();
            }
            throw new AssertionError("No original bridge completion for "+action);
        }
        public void close()throws Exception{
            try{if(bridge!=null)bridge.close();controller.detach();}
            finally{try{media.close();owner.close();}finally{ReadyApplicationFixture.closeHost(app);}}
        }
    }
    @Test public void ownerPreviewDoesNotCopyUntilClickAndChooserUsesExactlyThePreviewedText()throws Exception {
        try(Rig r=new Rig()){
            r.invoke("openBackgroundSetup");PhoneDiagnosticReport.Report report=r.preview();assertEquals("unchanged owner marker",r.clip());
            assertNull(r.invoke("copyPhoneDiagnostics",report));assertEquals(report.text(),r.clip());
            while(Shadows.shadowOf(r.activity).getNextStartedActivity()!=null){/* Drain any unrelated existing startup navigation before explicit share. */}
            assertNull(r.invoke("sharePhoneDiagnostics",report));Intent chooser=Shadows.shadowOf(r.activity).getNextStartedActivity();assertNotNull(chooser);assertEquals(Intent.ACTION_CHOOSER,chooser.getAction());Intent share=chooser.getParcelableExtra(Intent.EXTRA_INTENT);assertNotNull(share);assertEquals(Intent.ACTION_SEND,share.getAction());assertEquals("text/plain",share.getType());assertEquals(report.text(),share.getStringExtra(Intent.EXTRA_TEXT));assertFalse(share.hasExtra(Intent.EXTRA_STREAM));assertEquals(0,r.app.tasks.list().length());assertFalse(report.snapshot().getBoolean("deviceVerified"));
        }
    }
    @Test public void closeDiscardsPendingPreviewAndOldObjectCannotCopyAfterReopen()throws Exception {
        try(Rig r=new Rig()){
            r.invoke("openBackgroundSetup");PhoneDiagnosticReport.Report first=r.preview();CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger callbacks=new AtomicInteger();r.worker().execute(()->{held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});assertTrue(held.await(3,TimeUnit.SECONDS));
            AtomicReference<PhoneDiagnosticReport.Report> late=new AtomicReference<>();AtomicReference<String> failure=new AtomicReference<>();
            try{r.invoke("previewPhoneDiagnostics",(Function2<PhoneDiagnosticReport.Report,String,Unit>)(report,error)->{callbacks.incrementAndGet();late.set(report);failure.set(error);return Unit.INSTANCE;});r.invoke("closeBackgroundSetup");}
            finally{release.countDown();}r.drain();assertEquals(1,callbacks.get());assertNull(late.get());assertNotNull(failure.get());assertNotNull(r.invoke("copyPhoneDiagnostics",first));assertEquals("unchanged owner marker",r.clip());
            r.invoke("openBackgroundSetup");PhoneDiagnosticReport.Report second=r.preview();assertNotSame(first,second);assertNotNull(r.invoke("copyPhoneDiagnostics",first));assertNull(r.invoke("copyPhoneDiagnostics",second));assertEquals(second.text(),r.clip());
        }
    }
    @Test public void groupAndTeamViewsCannotStartOrPublishAPrivatePhonePreview()throws Exception {
        try(Rig r=new Rig()){
            r.invoke("openBackgroundSetup");PhoneDiagnosticReport.Report report=r.preview();Field domain=PocketWorldController.class.getDeclaredField("ownerDomain");domain.setAccessible(true);
            for(String name:new String[]{"groups","teams"}){domain.set(r.controller,name);AtomicInteger callbacks=new AtomicInteger();AtomicReference<PhoneDiagnosticReport.Report> leaked=new AtomicReference<>();AtomicReference<String> failure=new AtomicReference<>();r.invoke("previewPhoneDiagnostics",(Function2<PhoneDiagnosticReport.Report,String,Unit>)(value,error)->{callbacks.incrementAndGet();leaked.set(value);failure.set(error);return Unit.INSTANCE;});r.drain();assertEquals(1,callbacks.get());assertNull(leaked.get());assertNotNull(failure.get());assertNotNull(r.invoke("copyPhoneDiagnostics",report));assertNotNull(r.invoke("sharePhoneDiagnostics",report));assertEquals("unchanged owner marker",r.clip());}
            domain.set(r.controller,null);r.invoke("closeBackgroundSetup");assertNotNull(r.invoke("sharePhoneDiagnostics",report));
        }
    }
    @Test public void unifiedWorkspaceCommandsPreviewAndCopyTheActualNativeReportWithoutAutomaticPublication()throws Exception {
        try(Rig r=new Rig()){
            r.invoke("openBackgroundSetup");r.startBridge();
            JSONObject preview=r.command("background.diagnostics.preview",new JSONObject());assertTrue(preview.getBoolean("ok"));assertEquals("unchanged owner marker",r.clip());
            Field field=PocketWorldController.class.getDeclaredField("phoneDiagnosticReport");field.setAccessible(true);PhoneDiagnosticReport.Report actual=(PhoneDiagnosticReport.Report)field.get(r.controller);assertNotNull(actual);
            JSONObject value=preview.getJSONObject("value");assertEquals(actual.text(),value.getString("text"));assertTrue(value.getString("text").contains("pocketworld.phone.diagnostics.v1"));
            JSONObject forged=r.command("background.diagnostics.copy",new JSONObject().put("reportHandle","forged"));assertFalse(forged.getBoolean("ok"));assertEquals("unchanged owner marker",r.clip());
            JSONObject copied=r.command("background.diagnostics.copy",new JSONObject().put("reportHandle",value.getString("reportHandle")));assertTrue(copied.getBoolean("ok"));assertTrue(copied.getJSONObject("value").getBoolean("copied"));
            assertEquals(actual.text(),r.clip());assertEquals(0,r.app.tasks.list().length());assertFalse(actual.snapshot().getBoolean("executionAuthorized"));
        }
    }

}
