package dev.pocketengine.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.platform.ComposeView;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original Activity/Controller/App, exact preview object, native clipboard and system chooser intents.
 * No external recipient transmission, screenshots, device or visual acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,qualifiers="w400dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneDiagnosticsOwnerNativeTest {
    private static final class Rig implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final ActivityController<ComposeActivity> owner;
        final ComposeActivity activity;
        final PocketWorldController controller;
        final ClipboardManager clipboard;
        Rig()throws Exception {
            org.robolectric.shadows.ShadowChoreographer.setPaused(true);org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
            owner=Robolectric.buildActivity(ComposeActivity.class).setup();activity=owner.get();
            Field field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);controller=(PocketWorldController)field.get(activity);
            // Mount the existing changed setup component only, not unrelated cached Kotlin module ABIs.
            ComposeView setup=new ComposeView(activity);
            setup.setContent(new Function2<Composer,Integer,Unit>(){public Unit invoke(Composer composer,Integer flags){if(controller.getState().getBackgroundSetupOpen())BackgroundPhoneSetupKt.BackgroundPhoneSetup(controller.getState(),controller,composer,0);return Unit.INSTANCE;}});
            activity.setContentView(setup);
            clipboard=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("owner marker","unchanged owner marker"));
        }
        Object invoke(String prefix,Object... args)throws Exception {for(Method m:PocketWorldController.class.getDeclaredMethods())if((m.getName().equals(prefix)||m.getName().startsWith(prefix+"$"))&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(controller,args);}throw new AssertionError("No actual native method "+prefix);}
        void drain()throws Exception {Field f=PocketWorldController.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(controller)).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));}
        ExecutorService worker()throws Exception {Field f=PocketWorldController.class.getDeclaredField("io");f.setAccessible(true);return (ExecutorService)f.get(controller);}
        PhoneDiagnosticReport.Report preview()throws Exception {
            AtomicReference<PhoneDiagnosticReport.Report> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();invoke("previewPhoneDiagnostics",(Function2<PhoneDiagnosticReport.Report,String,Unit>)(report,failure)->{result.set(report);error.set(failure);return Unit.INSTANCE;});
            for(int i=0;i<60&&result.get()==null&&error.get()==null;i++){drain();Thread.sleep(5);}assertNull(error.get());assertNotNull("Original native preview completed",result.get());return result.get();
        }
        String clip(){return clipboard.getPrimaryClip().getItemAt(0).getText().toString();}
        private android.view.View compose(android.view.View view){if(view.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView"))return view;if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){var found=compose(group.getChildAt(i));if(found!=null)return found;}return null;}
        private void collect(androidx.compose.ui.semantics.SemanticsNode node,java.util.List<androidx.compose.ui.semantics.SemanticsNode> result){result.add(node);for(var child:node.getChildren())collect(child,result);}
        java.util.List<androidx.compose.ui.semantics.SemanticsNode> nodes()throws Exception {var result=new java.util.ArrayList<androidx.compose.ui.semantics.SemanticsNode>();for(var root:android.view.inspector.WindowInspector.getGlobalWindowViews()){if(!root.isAttachedToWindow())continue;var v=compose(root);if(v!=null)collect(((androidx.compose.ui.semantics.SemanticsOwner)v.getClass().getMethod("getSemanticsOwner").invoke(v)).getRootSemanticsNode(),result);}return result;}
        String text(androidx.compose.ui.semantics.SemanticsNode node){var key=androidx.compose.ui.semantics.SemanticsProperties.INSTANCE.getText();if(!node.getConfig().contains(key))return "";StringBuilder result=new StringBuilder();for(var word:node.getConfig().get(key))result.append(word.getText());return result.toString();}
        void await(String value)throws Exception {for(int i=0;i<40;i++){if(nodes().stream().anyMatch(n->text(n).contains(value)))return;drain();}throw new AssertionError("No existing setup semantics "+value);}
        void click(String label)throws Exception {await(label);for(var node:nodes()){var click=androidx.compose.ui.semantics.SemanticsActions.INSTANCE.getOnClick();if(!text(node).equals(label)||!node.getConfig().contains(click))continue;assertFalse(node.getConfig().contains(androidx.compose.ui.semantics.SemanticsProperties.INSTANCE.getDisabled()));assertEquals(Boolean.TRUE,((kotlin.jvm.functions.Function0<?>)node.getConfig().get(click).getAction()).invoke());drain();return;}throw new AssertionError("No native action "+label);}

        public void close(){owner.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();app.onTerminate();app.features.close();}
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
            try{r.invoke("previewPhoneDiagnostics",(Function2<PhoneDiagnosticReport.Report,String,Unit>)(report,error)->{callbacks.incrementAndGet();return Unit.INSTANCE;});r.invoke("closeBackgroundSetup");}
            finally{release.countDown();}r.drain();assertEquals(0,callbacks.get());assertNotNull(r.invoke("copyPhoneDiagnostics",first));assertEquals("unchanged owner marker",r.clip());
            r.invoke("openBackgroundSetup");PhoneDiagnosticReport.Report second=r.preview();assertNotSame(first,second);assertNotNull(r.invoke("copyPhoneDiagnostics",first));assertNull(r.invoke("copyPhoneDiagnostics",second));assertEquals(second.text(),r.clip());
        }
    }
    @Test public void groupAndTeamViewsCannotStartOrPublishAPrivatePhonePreview()throws Exception {
        try(Rig r=new Rig()){
            r.invoke("openBackgroundSetup");PhoneDiagnosticReport.Report report=r.preview();Field domain=PocketWorldController.class.getDeclaredField("ownerDomain");domain.setAccessible(true);
            for(String name:new String[]{"groups","teams"}){domain.set(r.controller,name);AtomicInteger callbacks=new AtomicInteger();r.invoke("previewPhoneDiagnostics",(Function2<PhoneDiagnosticReport.Report,String,Unit>)(value,error)->{callbacks.incrementAndGet();return Unit.INSTANCE;});r.drain();assertEquals(0,callbacks.get());assertNotNull(r.invoke("copyPhoneDiagnostics",report));assertNotNull(r.invoke("sharePhoneDiagnostics",report));assertEquals("unchanged owner marker",r.clip());}
            domain.set(r.controller,null);r.invoke("closeBackgroundSetup");assertNotNull(r.invoke("sharePhoneDiagnostics",report));
        }
    }
    @Test public void existingSetupButtonsPreviewAndCopyTheActualNativeReportWithoutAutomaticPublication()throws Exception {
        try(Rig r=new Rig()){
            r.invoke("openBackgroundSetup");r.click("预览脱敏诊断报告");r.await("复制这份诊断");assertEquals("unchanged owner marker",r.clip());
            Field field=PocketWorldController.class.getDeclaredField("phoneDiagnosticReport");field.setAccessible(true);PhoneDiagnosticReport.Report actual=(PhoneDiagnosticReport.Report)field.get(r.controller);assertNotNull(actual);assertTrue(r.nodes().stream().anyMatch(node->r.text(node).contains("pocketworld.phone.diagnostics.v1")));
            r.click("复制这份诊断");assertEquals(actual.text(),r.clip());assertEquals(0,r.app.tasks.list().length());assertFalse(actual.snapshot().getBoolean("executionAuthorized"));
        }
    }

}
