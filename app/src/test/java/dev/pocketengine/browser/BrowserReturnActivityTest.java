package dev.pocketengine.browser;

import android.app.Application;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.widget.Button;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.ui.web.BrowserChromeAction;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Actual BrowserActivity lifecycle/native Views/TaskStore/approval/source/journal. Web DOM callbacks and OS launcher resolution are explicit fixtures, not device or Compose rendering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class BrowserReturnActivityTest {
    static JSONObject j(Object...p)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<p.length;i+=2)out.put((String)p[i],p[i+1]);return out;}
    static WebView web(BrowserActivity a)throws Exception {Field f=BrowserActivity.class.getDeclaredField("web");f.setAccessible(true);return(WebView)f.get(a);}
    static void launcher(BrowserActivity a){android.content.pm.ResolveInfo info=new android.content.pm.ResolveInfo();info.activityInfo=new android.content.pm.ActivityInfo();info.activityInfo.packageName=a.getPackageName();info.activityInfo.name="dev.pocketengine.ui.ComposeActivity";Shadows.shadowOf(a.getPackageManager()).addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(a.getPackageName()),info);}
    // The address/menus now live in a fixed-origin WebView. These native lifecycle tests
    // invoke its exact owner adapter/confirmation target, not a synthetic DOM or a hidden old Button.
    // Web command tokens and menu entry wiring have separate contract checks; real UI remains pending.
    static void chromeReturn(BrowserActivity a)throws Exception {var binding=BrowserActivity.class.getDeclaredMethod("chromeBinding");binding.setAccessible(true);var perform=BrowserActivity.class.getDeclaredMethod("performChromeAction",Object.class,BrowserChromeAction.class,String.class);perform.setAccessible(true);assertEquals(true,perform.invoke(a,binding.invoke(a),BrowserChromeAction.RETURN_OWNER,null));}
    static void closeDialog(BrowserActivity a,boolean close)throws Exception {var confirm=BrowserActivity.class.getDeclaredMethod("confirmCloseForOwner");confirm.setAccessible(true);confirm.invoke(a);Field f=BrowserActivity.class.getDeclaredField("closeConfirmation");f.setAccessible(true);AlertDialog dialog=(AlertDialog)f.get(a);assertNotNull(dialog);assertTrue(dialog.isShowing());assertTrue(dialog.getButton(close?AlertDialog.BUTTON_POSITIVE:AlertDialog.BUTTON_NEGATIVE).performClick());Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static final class F implements AutoCloseable {
        final BrowserTaskActivityTest.Fixture h;
        F()throws Exception {BrowserActivity.setOwnerPanelInstaller(null);h=new BrowserTaskActivityTest().new Fixture();h.activity.windowFocusChanged(true);launcher(h.browser);}
        public void close()throws Exception {if(!h.browser.isDestroyed()){h.close();return;}h.worker.shutdownNow();h.worker.awaitTermination(2,TimeUnit.SECONDS);BrowserDownloads.closeFor(h.browser);BrowserActivity.setEvents(null);BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskPersistenceTest.closeDatabase(h.tasks);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    static void durableSources(F f)throws Exception {
        var h=f.h;var a=h.current;h.browser.endTaskSpaceForOwner("fixture_install_original_durable_source");assertTrue(h.resources.acquireBrowser(a.token,System.currentTimeMillis()+600000));
        TaskStoreCapabilityAuthority authority=new TaskStoreCapabilityAuthority(h.tasks,a.binding,event->{});ModelCapabilityAdapters.ResourceCheck resource=()->{if(!h.resources.ownsBrowser(a.token))throw new SecurityException("original browser resource not owned");};BrowserTaskSourcePort source=new BrowserTaskSourcePort(h.tasks,a.binding,a.control,authority,resource,event->{});
        BrowserTaskCapabilityPort port=h.browser.attachTaskSpaceForOwner(a.binding,a.control,authority,resource,(b,c,op,name,args,pin)->{assertSame(a.control,c);authority.check(name,c);authority.requirePrepared(op,name,args);JSONObject approval=h.tasks.requireCapabilityApproval(b.taskId,b.runId,name,args,pin);if(!approval.optBoolean("approved")){h.tasks.decideCapabilityApproval(b.taskId,approval.getString("approvalId"),true);approval=h.tasks.requireCapabilityApproval(b.taskId,b.runId,name,args,pin);}return approval;},source,new BrowserTaskSpace.NativeEvents(){public void takenOver(ModelCapabilityAdapters.Binding b,RunControl c,String space,String tab){h.resources.releaseBrowser(a.token);c.pause();}public void ended(ModelCapabilityAdapters.Binding b,RunControl c,String space,JSONObject receipt){h.resources.releaseBrowser(a.token);}},null,h.browser.snapshot().getString("browserSessionId"));
        h.current=h.new Attempt(a.task,a.binding,a.control,a.journal,port,a.token);
    }
    @Test public void returnPauseResumeAndNewIntentKeepSameWebViewProfilePageAndOriginalRunWithoutRestart()throws Exception {
        try(F f=new F()){var h=f.h;String tab=h.open("kept?version=1#anchor");WebView before=web(h.browser);JSONObject identity=h.browser.snapshot();int tasks=h.tasks.list().length(),ops=h.tasks.get(h.taskId,false).getJSONArray("operations").length();h.browser.showResearchPanelForOwner(h.taskId);chromeReturn(h.browser);Intent returned=Shadows.shadowOf(h.browser).getNextStartedActivity();assertEquals(h.taskId,returned.getStringExtra("quickPanelTaskId"));assertNotEquals(0,returned.getFlags()&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);assertNotEquals(0,returned.getFlags()&Intent.FLAG_ACTIVITY_SINGLE_TOP);assertFalse(h.browser.isFinishing());assertFalse(h.current.control.isPaused());assertTrue(h.resources.ownsBrowser(h.current.token));
            h.activity.pause().stop();h.activity.restart().start().resume();h.browser.onNewIntent(new Intent().putExtra("showResearch",true).putExtra("browserResearchTaskId",h.taskId).putExtra("url","https://must-not-replace.example/"));assertSame(before,web(h.browser));assertEquals("https://example.com/kept?version=1#anchor",web(h.browser).getUrl());assertEquals(identity.getString("profileId"),h.browser.snapshot().getString("profileId"));assertEquals(identity.getString("browserSessionId"),h.browser.snapshot().getString("browserSessionId"));assertEquals(tab,h.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("tabs").getJSONObject(0).getString("tabId"));assertEquals(h.taskId,h.browser.researchTaskContextForOwner());assertEquals(tasks,h.tasks.list().length());assertEquals(ops,h.tasks.get(h.taskId,false).getJSONArray("operations").length());assertFalse(h.current.control.isCancelled());
        }
    }
    @Test public void noHistoryBackUsesBoundOriginalTaskButHistoryBackKeepsExistingTakeoverAndNeverResumesAi()throws Exception {
        try(F f=new F()){var h=f.h;h.open("original-back");assertTrue("System Back is registered with the actual AndroidX dispatcher",h.browser.getOnBackPressedDispatcher().hasEnabledCallbacks());WebView before=web(h.browser);Shadows.shadowOf(before).setCanGoBack(false);h.browser.getOnBackPressedDispatcher().onBackPressed();Intent returned=Shadows.shadowOf(h.browser).getNextStartedActivity();assertEquals(h.taskId,returned.getStringExtra("quickPanelTaskId"));assertSame(before,web(h.browser));assertFalse(h.browser.isFinishing());assertFalse(h.current.control.isPaused());Shadows.shadowOf(before).setCanGoBack(true);int back=Shadows.shadowOf(before).getGoBackInvocations();h.browser.getOnBackPressedDispatcher().onBackPressed();assertEquals(back+1,Shadows.shadowOf(before).getGoBackInvocations());assertNull(Shadows.shadowOf(h.browser).getNextStartedActivity());assertTrue(h.current.control.isPaused());assertFalse(h.current.control.isCancelled());assertFalse(h.browser.isFinishing());}
    }
    @Test public void actualInsetsRemainUiOnlyWhileAppDispatcherHandlesBackAfterPlatformImePriority()throws Exception {
        try(F f=new F()){var h=f.h;h.open("ime-and-workspace");View root=h.browser.findViewById(android.R.id.content).findViewWithTag("browser_root");final boolean[] sheet={true};final int[] calls={0};Field panel=BrowserActivity.class.getDeclaredField("ownerPanel");panel.setAccessible(true);panel.set(h.browser,new BrowserActivity.OwnerPanel(){public void show(){}public boolean backToPage(){calls[0]++;if(!sheet[0])return false;sheet[0]=false;return true;}public void close(){}});
            root.dispatchApplyWindowInsets(new WindowInsets.Builder().setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,240)).setVisible(WindowInsets.Type.ime(),true).build());assertEquals(240,root.getPaddingBottom());assertEquals(0,calls[0]);assertTrue(sheet[0]);assertFalse(h.current.control.isPaused());
            // Explicit app dispatch means the platform already offered Back to the IME; a stale hide-animation inset must not eat this app event.
            h.browser.getOnBackPressedDispatcher().onBackPressed();assertEquals(1,calls[0]);assertFalse(sheet[0]);assertNull(Shadows.shadowOf(h.browser).getNextStartedActivity());assertFalse(h.current.control.isPaused());
            root.dispatchApplyWindowInsets(new WindowInsets.Builder().setInsets(WindowInsets.Type.ime(),Insets.NONE).setVisible(WindowInsets.Type.ime(),false).build());Shadows.shadowOf(web(h.browser)).setCanGoBack(false);h.browser.getOnBackPressedDispatcher().onBackPressed();assertEquals(h.taskId,Shadows.shadowOf(h.browser).getNextStartedActivity().getStringExtra("quickPanelTaskId"));assertFalse(h.browser.isFinishing());assertFalse(h.current.control.isPaused());}
    }
    @Test public void explicitCloseCancelPreservesRunAndConfirmedDestroyReleasesPagesButKeepsActualDurableSources()throws Exception {
        try(F f=new F()){durableSources(f);var h=f.h;String tab=h.open("retained-source");JSONObject observed=h.observe(tab);h.tool("retain_source",j("tabId",tab,"observationId",observed.getString("observationId"),"excerpt","research evidence"));JSONArray sources=BrowserTaskSourcePort.sourcesForOwner(h.tasks,h.taskId);assertEquals(1,sources.length());String source=sources.getJSONObject(0).getString("sourceId"),fingerprint=sources.getJSONObject(0).getString("sourceFingerprint");WebView before=web(h.browser);closeDialog(h.browser,false);assertFalse(h.browser.isFinishing());assertSame(before,web(h.browser));assertTrue(h.resources.ownsBrowser(h.current.token));assertFalse(h.current.control.isPaused());closeDialog(h.browser,true);assertTrue(h.browser.isFinishing());assertFalse(h.current.control.isCancelled());h.activity.pause().stop().destroy();assertFalse(h.resources.ownsBrowser(h.current.token));assertEquals(0,h.browser.tabsForOwner().getJSONArray("tabs").length());assertNull(BrowserActivity.current());JSONArray kept=BrowserTaskSourcePort.sourcesForOwner(h.tasks,h.taskId);assertEquals(1,kept.length());assertEquals(source,kept.getJSONObject(0).getString("sourceId"));assertEquals(fingerprint,kept.getJSONObject(0).getString("sourceFingerprint"));assertNotEquals("cancelled",h.tasks.get(h.taskId,false).optString("status"));}
    }
    @Test public void closeAfterRealNativeDispatchKeepsOriginalPreparedOperationUnknownAndNeverReplaysInput()throws Exception {
        try(F f=new F()){var h=f.h;String tab=h.open("close-pending");JSONObject observed=h.observe(tab);WebView page=web(h.browser);Object previous=Shadows.shadowOf(page).getLastEvaluatedJavascriptCallback();JSONObject args=j("tabId",tab,"observationId",observed.getString("observationId"),"ref","n0");String op=h.current.journal.beginOperation("browser_task_click",args);Future<JSONObject> pending=h.worker.submit(()->h.current.port.execute("browser_task_click",new JSONObject(args.toString()).put("_hostOperationId",op),h.current.control));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(Shadows.shadowOf(page).getLastEvaluatedJavascriptCallback()==previous&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(3);}Object dispatched=Shadows.shadowOf(page).getLastEvaluatedJavascriptCallback();assertNotSame("actual BrowserActivity issued one native JS operation",previous,dispatched);closeDialog(h.browser,true);JSONObject receipt=pending.get(2,TimeUnit.SECONDS);assertEquals("unknown",receipt.getString("status"));assertSame(JSONObject.NULL,receipt.get("accepted"));h.current.journal.finishOperation(op,receipt);h.activity.pause().stop().destroy();assertFalse(h.resources.ownsBrowser(h.current.token));assertTrue(h.tasks.hasUnresolved(h.taskId));int matching=0;JSONArray operations=h.tasks.get(h.taskId,false).getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);if(op.equals(row.getString("operationId"))){matching++;assertEquals("unknown",row.getString("state"));}}assertEquals(1,matching);assertSame(dispatched,Shadows.shadowOf(page).getLastEvaluatedJavascriptCallback());assertNotEquals("cancelled",h.tasks.get(h.taskId,false).optString("status"));}
    }
    @Test public void systemDestroyStillReleasesRealOwnedBrowserResourceWithoutClaimingTaskCancellation()throws Exception {
        try(F f=new F()){var h=f.h;h.open("system-destroy");assertTrue(h.resources.ownsBrowser(h.current.token));h.activity.pause().stop().destroy();assertFalse(h.resources.ownsBrowser(h.current.token));assertEquals(0,h.browser.tabsForOwner().getJSONArray("tabs").length());assertFalse(h.current.control.isCancelled());assertNotEquals("cancelled",h.tasks.get(h.taskId,false).optString("status"));}
    }
    @org.junit.Ignore("Robolectric 4.16.1 uses DeepProxy WebViewProvider: setFrame returns false, so actual WebView frame/pixels are unsupported. Keep the real chrome/page geometry gate blocked; retained input target, text clipping and keyboard acceptance require an authorized device renderer.")
    @Test @Config(qualifiers="w320dp-h480dp-mdpi") public void nativeSmallScreenLargeTextAndKeyboardInsetsKeepAddressAndNavigationReachable()throws Exception {
        float before=RuntimeEnvironment.getFontScale();RuntimeEnvironment.setFontScale(2f);
        try(F f=new F()){var h=f.h;h.open("small-screen");View root=h.browser.findViewById(android.R.id.content).findViewWithTag("browser_root");root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,320,480);View chrome=root.findViewWithTag("browser_chrome");assertNotNull("Requires an actual trusted Web toolbar",chrome);String dims="root="+root.getWidth()+"x"+root.getHeight()+",chrome="+chrome.getWidth()+"x"+chrome.getHeight()+",web="+web(h.browser).getWidth()+"x"+web(h.browser).getHeight();System.out.println("ACTUAL_NATIVE_LAYOUT "+dims);assertTrue("Chrome width: "+dims,chrome.getWidth()>=300);assertTrue("Chrome within viewport: "+dims,chrome.getBottom()<=root.getHeight()-root.getPaddingBottom());assertTrue("Actual WebView height: "+dims,web(h.browser).getHeight()>0);int fullHeight=chrome.getHeight();assertEquals(2f,chrome.getResources().getConfiguration().fontScale,.001f);
            root.dispatchApplyWindowInsets(new WindowInsets.Builder().setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,180)).setVisible(WindowInsets.Type.ime(),true).build());root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,320,480);assertTrue("IME removes secondary chrome row while retaining input and status",chrome.getHeight()<fullHeight);assertTrue("Typing leaves real WebView space instead of the secondary nav row",web(h.browser).getHeight()>0);assertTrue("Input and status retain usable height",chrome.getHeight()>=68);assertFalse(h.current.control.isPaused());
            Bitmap image=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));File out=new File(System.getProperty("pocketworld.browser.return.renders"),"browser-320-font2-ime.png");out.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(out)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}image.recycle();assertTrue(out.length()>1000);
        }finally{RuntimeEnvironment.setFontScale(before);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
}
