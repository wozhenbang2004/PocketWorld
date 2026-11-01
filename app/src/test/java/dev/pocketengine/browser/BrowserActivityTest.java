package dev.pocketengine.browser;

import android.app.Application;
import android.net.Uri;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import dev.pocketengine.BrowserActivity;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Verifies native browser boundaries; Robolectric does not render the remote page or execute its JS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserActivityTest {
    private ActivityController<BrowserActivity> controller;
    private BrowserActivity open(){controller=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();return controller.get();}
    private WebView web(BrowserActivity activity)throws Exception{Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);return (WebView)field.get(activity);}
    @After public void close(){if(controller!=null)controller.pause().stop().destroy();BrowserActivity.setEvents(null);}
    @Test public void browsingSurfaceRestrictsLocalFilesAndMixedContent()throws Exception {
        BrowserActivity browser=open();WebView web=web(browser);WebSettings settings=web.getSettings();
        assertTrue(settings.getJavaScriptEnabled());assertFalse(settings.getAllowFileAccess());assertFalse(settings.getAllowContentAccess());
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW,settings.getMixedContentMode());assertFalse(settings.getJavaScriptCanOpenWindowsAutomatically());
        assertTrue(web.getWebViewClient().shouldOverrideUrlLoading(web,new Request("file:///data/data/dev.pocketengine/files/tasks.json")));
        assertTrue(web.getWebViewClient().shouldOverrideUrlLoading(web,new Request("intent://other-app")));
        assertFalse(web.getWebViewClient().shouldOverrideUrlLoading(web,new Request("https://example.com/path")));
        assertSame(browser,BrowserActivity.current());controller.pause().stop().destroy();controller=null;assertNull(BrowserActivity.current());
    }
    @Test public void staleObservationCannotClickAndNavigationCannotExecuteJavaScript()throws Exception {
        BrowserActivity browser=open();CompletableFuture<JSONObject> stale=new CompletableFuture<>();
        browser.execute("click",new JSONObject().put("observationId","not-current").put("selector","button"),stale);
        assertTrue(stale.isCompletedExceptionally());
        CompletableFuture<JSONObject> injected=new CompletableFuture<>();browser.execute("navigate",new JSONObject().put("url","javascript:alert(1)"),injected);
        assertTrue(injected.isCompletedExceptionally());
    }
    @Test public void webPagesCannotObtainMicrophoneOrCameraThroughTheBrowser()throws Exception {
        BrowserActivity browser=open();final boolean[] denied={false};
        web(browser).getWebChromeClient().onPermissionRequest(new PermissionRequest(){
            @Override public Uri getOrigin(){return Uri.parse("https://example.com");}
            @Override public String[] getResources(){return new String[]{RESOURCE_AUDIO_CAPTURE,RESOURCE_VIDEO_CAPTURE};}
            @Override public void grant(String[] resources){fail("Page permissions must not be automatically granted");}
            @Override public void deny(){denied[0]=true;}
        });assertTrue(denied[0]);
    }
    @Test public void manualTakeoverFencesAutomaticActionsFromTheOldRun()throws Exception {
        BrowserActivity browser=open();JSONObject lease=acquire(browser);
        assertFalse(browser.snapshot().getBoolean("sharedWithSystemChrome"));
        assertEquals("default-webview-only",browser.snapshot().getString("profileIsolation"));
        browser.takeover("主人接管");
        CompletableFuture<JSONObject> stale=new CompletableFuture<>();browser.execute("observe",lease,stale);
        assertTrue(stale.isCompletedExceptionally());assertFalse(browser.snapshot().getJSONObject("lease").getBoolean("active"));
    }
    @Test public void cancellationPreservesUnknownDispatchedSideEffect()throws Exception {
        BrowserActivity browser=open();JSONObject lease=acquire(browser);CompletableFuture<JSONObject> action=new CompletableFuture<>();
        set(browser,"pending",action);set(browser,"pendingMutation",true);
        CompletableFuture<JSONObject> cancel=new CompletableFuture<>();browser.execute("cancel",lease,cancel);
        assertEquals("cancel",cancel.get().getString("status"));assertEquals("unknown",action.get().getString("status"));
        assertTrue(action.get().isNull("accepted"));assertFalse(action.get().getBoolean("verified"));
        CompletableFuture<JSONObject> stale=new CompletableFuture<>();browser.execute("reload",lease,stale);assertTrue(stale.isCompletedExceptionally());
    }
    @Test public void expiredObservationIsRejectedBeforeInjectingJavaScript()throws Exception {
        BrowserActivity browser=open();JSONObject lease=acquire(browser);set(browser,"loading",false);set(browser,"observationId","old");set(browser,"observationAt",android.os.SystemClock.elapsedRealtime()-21000L);
        CompletableFuture<JSONObject> action=new CompletableFuture<>();browser.execute("click",lease.put("observationId","old").put("ref","n0"),action);assertTrue(action.isCompletedExceptionally());
    }
    @Test public void reopeningWithoutUrlPreservesThePageAndOnlyTakesOver()throws Exception {
        BrowserActivity browser=open();WebView page=web(browser);page.loadUrl("https://example.com/login/step2");acquire(browser);
        browser.openForOwner("");assertSame(page,web(browser));assertFalse(browser.isFinishing());assertEquals("https://example.com/login/step2",org.robolectric.Shadows.shadowOf(page).getLastLoadedUrl());assertFalse(browser.snapshot().getJSONObject("lease").getBoolean("active"));
        browser.openForOwner("https://example.com/explicit");assertSame(page,web(browser));assertEquals("https://example.com/explicit",org.robolectric.Shadows.shadowOf(page).getLastLoadedUrl());
    }
    @Test public void snapshotEmitsExpiredLeaseWithItsOriginalTaskIdentity()throws Exception {
        BrowserActivity browser=open();JSONObject acquired=acquire(browser);java.util.List<JSONObject> events=new java.util.ArrayList<>();BrowserActivity.setEvents(events::add);
        Field leaseField=BrowserActivity.class.getDeclaredField("lease");leaseField.setAccessible(true);Object resource=leaseField.get(browser);Field expiry=resource.getClass().getDeclaredField("expiresAt");expiry.setAccessible(true);expiry.set(resource,System.currentTimeMillis()-1);
        assertFalse(browser.snapshot().getJSONObject("lease").getBoolean("active"));browser.snapshot();assertEquals(1,events.size());assertEquals("expired",events.get(0).getString("status"));assertEquals(acquired.getString("taskId"),events.get(0).getString("taskId"));assertEquals(acquired.getString("leaseId"),events.get(0).getString("leaseId"));
    }
    @Test public void rendererLossFencesOldRunAndRequiresExplicitOwnerRecovery()throws Exception {
        BrowserActivity browser=open();JSONObject acquired=acquire(browser);WebView before=web(browser);assertTrue(before.getWebViewClient().onRenderProcessGone(before,new android.webkit.RenderProcessGoneDetail(){@Override public boolean didCrash(){return true;}@Override public int rendererPriorityAtExit(){return WebView.RENDERER_PRIORITY_BOUND;}}));
        assertNull(web(browser));assertTrue(browser.snapshot().getBoolean("rendererRecoveryNeeded"));CompletableFuture<JSONObject> stale=new CompletableFuture<>();browser.execute("observe",acquired,stale);assertTrue(stale.isCompletedExceptionally());
        browser.openForOwner("");assertNotNull(web(browser));assertNotSame(before,web(browser));assertFalse(browser.snapshot().getJSONObject("lease").getBoolean("active"));assertFalse(web(browser).getSettings().getAllowFileAccess());assertFalse(web(browser).getSettings().getAllowContentAccess());
    }
    @Test public void uploadPickerIsNativeAndNavigationCancelsItsCallback()throws Exception {
        BrowserActivity browser=open();acquire(browser);java.util.List<Uri[]> delivered=new java.util.ArrayList<>();WebView page=web(browser);
        assertTrue(page.getWebChromeClient().onShowFileChooser(page,delivered::add,chooser(android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN)));
        assertFalse(browser.snapshot().getJSONObject("lease").getBoolean("active"));assertTrue(browser.snapshot().getBoolean("fileChooserPending"));android.content.Intent launched=org.robolectric.Shadows.shadowOf(browser).getNextStartedActivityForResult().intent;assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT,launched.getAction());assertEquals("image/png",launched.getType());assertEquals(0,launched.getFlags()&android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        page.getWebViewClient().onPageStarted(page,"https://example.com/next",null);assertEquals(1,delivered.size());assertNull(delivered.get(0));assertFalse(browser.snapshot().getBoolean("fileChooserPending"));
    }
    @Test public void unsupportedWebSaveRequestDoesNotReceiveReadOnlyUploadUri()throws Exception {
        BrowserActivity browser=open();java.util.List<Uri[]> delivered=new java.util.ArrayList<>();WebView page=web(browser);assertTrue(page.getWebChromeClient().onShowFileChooser(page,delivered::add,chooser(3)));assertEquals(1,delivered.size());assertNull(delivered.get(0));assertFalse(browser.snapshot().getBoolean("fileChooserPending"));
    }
    @Test public void nativeSelectionRebuildsTheViewAndFencesOriginalRunAndPendingMutation()throws Exception {
        BrowserActivity browser=open();JSONObject acquired=acquire(browser);WebView first=web(browser);CompletableFuture<JSONObject> dispatched=new CompletableFuture<>();set(browser,"pending",dispatched);set(browser,"pendingMutation",true);
        BrowserProfiles profiles=BrowserProfiles.get(browser);JSONObject catalog=profiles.snapshot();profiles.selectForOwner(catalog.getString("selectedProfileId"),catalog.getLong("revision"));browser.profileChangedForOwner();
        assertNotSame(first,web(browser));assertEquals("unknown",dispatched.get().getString("status"));assertFalse(browser.snapshot().getJSONObject("lease").getBoolean("active"));assertNotEquals(acquired.getString("browserSessionId"),browser.snapshot().getString("browserSessionId"));
        CompletableFuture<JSONObject> stale=new CompletableFuture<>();browser.execute("reload",acquired,stale);assertTrue(stale.isCompletedExceptionally());
    }
    @Test public void oldPickerResultCannotConsumeTheNextProfilesPicker()throws Exception {
        BrowserActivity browser=open();java.util.List<Uri[]> old=new java.util.ArrayList<>(),next=new java.util.ArrayList<>();WebView first=web(browser);first.getWebChromeClient().onShowFileChooser(first,old::add,chooser(android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN));int oldCode=org.robolectric.Shadows.shadowOf(browser).getNextStartedActivityForResult().requestCode;
        BrowserProfiles profiles=BrowserProfiles.get(browser);JSONObject catalog=profiles.snapshot();profiles.selectForOwner(catalog.getString("selectedProfileId"),catalog.getLong("revision"));browser.profileChangedForOwner();assertEquals(1,old.size());assertNull(old.get(0));
        WebView second=web(browser);second.getWebChromeClient().onShowFileChooser(second,next::add,chooser(android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN));int nextCode=org.robolectric.Shadows.shadowOf(browser).getNextStartedActivityForResult().requestCode;assertNotEquals(oldCode,nextCode);
        java.lang.reflect.Method result=BrowserActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,android.content.Intent.class);result.setAccessible(true);result.invoke(browser,oldCode,android.app.Activity.RESULT_CANCELED,null);assertEquals(0,next.size());assertTrue(browser.snapshot().getBoolean("fileChooserPending"));result.invoke(browser,nextCode,android.app.Activity.RESULT_CANCELED,null);assertEquals(1,next.size());assertFalse(browser.snapshot().getBoolean("fileChooserPending"));
    }
    @Test public void missingMultiProfileSupportIsExplicitAndFeatureCommandsCannotManageProfiles()throws Exception {
        BrowserActivity browser=open();JSONObject catalog=BrowserProfiles.get(browser).snapshot();assertFalse(catalog.getBoolean("multiProfileSupported"));assertEquals("default-webview-only",browser.snapshot().getString("profileIsolation"));assertThrows(UnsupportedOperationException.class,()->BrowserProfiles.get(browser).createForOwner("work",catalog.getLong("revision")));
        BrowserModule module=new BrowserModule(new dev.pocketengine.features.FeatureContext(browser,null,null,event->{}));try{assertThrows(SecurityException.class,()->module.handle("profile.create",new JSONObject().put("label","forged")));assertEquals(catalog.getLong("revision"),module.nativeProfiles().getLong("revision"));}finally{module.close();}
    }
    private android.webkit.WebChromeClient.FileChooserParams chooser(int mode){return new android.webkit.WebChromeClient.FileChooserParams(){@Override public int getMode(){return mode;}@Override public String[] getAcceptTypes(){return new String[]{"image/png"};}@Override public boolean isCaptureEnabled(){return false;}@Override public CharSequence getTitle(){return null;}@Override public String getFilenameHint(){return null;}@Override public android.content.Intent createIntent(){return new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);}};}
    private JSONObject acquire(BrowserActivity browser)throws Exception {
        CompletableFuture<JSONObject> acquired=new CompletableFuture<>();browser.execute("acquire",new JSONObject().put("taskId","task-1").put("runId","run-1").put("principalId","owner").put("spaceId","private"),acquired);return acquired.get();
    }
    private void set(BrowserActivity browser,String name,Object value)throws Exception{Field field=BrowserActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(browser,value);}
    private static final class Request implements WebResourceRequest {
        private final Uri uri;Request(String url){uri=Uri.parse(url);}
        @Override public Uri getUrl(){return uri;}
        @Override public boolean isForMainFrame(){return true;}
        @Override public boolean isRedirect(){return false;}
        @Override public boolean hasGesture(){return true;}
        @Override public String getMethod(){return "GET";}
        @Override public Map<String,String> getRequestHeaders(){return Collections.emptyMap();}
    }
}
