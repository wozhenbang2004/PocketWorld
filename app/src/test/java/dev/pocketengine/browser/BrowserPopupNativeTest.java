package dev.pocketengine.browser;

import android.content.DialogInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.EngineApplication;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Real Activity callbacks, original TaskStore and RunControl; no physical WebView rendering claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserPopupNativeTest {
    @After public void shutdownOriginalApplication()throws Exception {new BrowserTaskActivityTest().shutdownOriginalApplication();}
    private static WebView web(BrowserActivity activity)throws Exception {Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);return (WebView)field.get(activity);}
    private static WebResourceRequest request(String url,String method,boolean gesture){return new WebResourceRequest(){public Uri getUrl(){return Uri.parse(url);}public boolean isForMainFrame(){return true;}public boolean isRedirect(){return false;}public boolean hasGesture(){return gesture;}public String getMethod(){return method;}public Map<String,String> getRequestHeaders(){return Map.of();}};}
    // AlertController queues the real button listener; drain it before checking its effect.
    private static void choose(int button){android.app.AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());dialog.getButton(button).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static WebView popup(WebView opener)throws Exception {
        AtomicReference<WebView> delivered=new AtomicReference<>();Handler handler=new Handler(Looper.getMainLooper(),message->{delivered.set(((WebView.WebViewTransport)message.obj).getWebView());return true;});Message reply=Message.obtain(handler);reply.obj=opener.new WebViewTransport();
        assertTrue(opener.getWebChromeClient().onCreateWindow(opener,false,true,reply));Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotNull(delivered.get());return delivered.get();
    }
    @Test public void actualPopupKeepsOriginalTaskAndProfileAndReturnsWithoutAutoResume()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            String openerId=h.open("login");WebView opener=web(h.browser);WebView child=popup(opener);assertTrue(h.current.control.isPaused());assertSame(child,web(h.browser));
            JSONObject state=h.browser.tabsForOwner().getJSONObject("taskSpace");JSONArray relations=state.getJSONArray("popupRelations");assertEquals(1,relations.length());assertEquals(openerId,relations.getJSONObject(0).getString("openerTabId"));assertEquals(h.current.binding.taskId,state.getString("taskId"));assertEquals(2,state.getJSONArray("handoffPages").length());
            assertTrue(child.getWebViewClient().shouldOverrideUrlLoading(child,request("https://identity.example/oauth?code=secret","GET",true)));assertNotEquals("https://identity.example/oauth?code=secret",child.getUrl());choose(DialogInterface.BUTTON_POSITIVE);assertEquals("https://identity.example/oauth?code=secret",child.getUrl());
            assertFalse(child.getWebViewClient().shouldOverrideUrlLoading(child,request("https://identity.example/next","GET",false)));
            child.getWebChromeClient().onCloseWindow(child);assertSame(opener,web(h.browser));assertTrue(h.current.control.isPaused());assertEquals(0,h.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("popupRelations").length());assertEquals(1,h.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("handoffPages").length());
        }
    }
    @Test public void interceptedBlockedDestinationCallbacksDoNotExpireItsExactOwnerDecision()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("login");WebView child=popup(web(h.browser));String destination="https://identity.example/auth";
            android.webkit.WebResourceResponse blocked=child.getWebViewClient().shouldInterceptRequest(child,request(destination,"GET",true));assertNotNull(blocked);assertEquals(403,blocked.getStatusCode());Shadows.shadowOf(Looper.getMainLooper()).idle();
            child.getWebViewClient().onPageStarted(child,destination,null);child.getWebViewClient().onPageFinished(child,destination);choose(DialogInterface.BUTTON_POSITIVE);
            assertSame(child,web(h.browser));assertEquals(destination,child.getUrl());assertTrue(h.current.control.isPaused());
        }
    }
    @Test public void crossOriginPopupCannotReuseAnEarlierOriginDecision()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("login");WebView opener=web(h.browser),child=popup(opener);assertTrue(child.getWebViewClient().shouldOverrideUrlLoading(child,request("https://first.example/auth","GET",true)));choose(DialogInterface.BUTTON_POSITIVE);
            assertTrue(child.getWebViewClient().shouldOverrideUrlLoading(child,request("https://second.example/auth","GET",false)));choose(DialogInterface.BUTTON_NEGATIVE);assertSame(opener,web(h.browser));assertTrue(h.current.control.isPaused());
        }
    }
    @Test public void pendingGetCannotBecomeSameUrlPostOrDifferentDestination()throws Exception {
        for(boolean sameUrlPost:new boolean[]{true,false})try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("login");WebView opener=web(h.browser),child=popup(opener);String first="https://identity.example/auth";
            assertTrue(child.getWebViewClient().shouldOverrideUrlLoading(child,request(first,"GET",true)));android.app.AlertDialog decision=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(child.getWebViewClient().shouldInterceptRequest(child,request(sameUrlPost?first:"https://second.example/auth",sameUrlPost?"POST":"GET",false)));Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(decision.isShowing());assertSame(opener,web(h.browser));assertNotEquals(first,child.getUrl());assertTrue(h.current.control.isPaused());
        }
    }
    @Test public void pageStartedBeforeInterceptionDeliveryRetainsOnlyExactBlockedDestination()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("login");WebView child=popup(web(h.browser));String destination="https://identity.example/auth";
            assertNotNull(child.getWebViewClient().shouldInterceptRequest(child,request(destination,"GET",true)));child.getWebViewClient().onPageStarted(child,destination,null);Shadows.shadowOf(Looper.getMainLooper()).idle();child.getWebViewClient().onPageFinished(child,destination);
            choose(DialogInterface.BUTTON_POSITIVE);assertSame(child,web(h.browser));assertEquals(destination,child.getUrl());
        }
    }
    @Test public void pendingExternalDecisionBlocksQueuedReturnAndCannotOutliveOriginalTask()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            String tab=h.open("login");WebView page=web(h.browser);h.browser.takeOverTaskTabForOwner(tab);JSONObject pin=h.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("handoffPages").getJSONObject(0);
            assertTrue(page.getWebViewClient().shouldOverrideUrlLoading(page,request("mailto:recipient@example.com","GET",true)));
            assertThrows(SecurityException.class,()->h.browser.returnTaskSpaceForOwner(pin,h.current.control,()->h.current.control.resumeAtPauseRevision(pin.getLong("pauseRevision"))));
            assertThrows(IllegalStateException.class,()->h.browser.adoptOwnerTabForTask(tab));h.current.control.cancel();choose(DialogInterface.BUTTON_POSITIVE);assertNull(Shadows.shadowOf(h.browser).getNextStartedActivity());
        }
    }
    @Test public void parentNavigationInvalidatesPendingPopupDestinationAndOldTransport()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("login");WebView opener=web(h.browser),child=popup(opener);assertTrue(child.getWebViewClient().shouldOverrideUrlLoading(child,request("https://identity.example/auth","GET",true)));
            android.app.AlertDialog pending=ShadowAlertDialog.getLatestAlertDialog();opener.getWebViewClient().onPageStarted(opener,"https://example.com/changed",null);assertFalse(pending.isShowing());assertSame(opener,web(h.browser));assertEquals(0,h.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("popupRelations").length());
        }
    }
    @Test public void parentNavigationRevokesAlreadyApprovedPopupOrigins()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("login");WebView opener=web(h.browser),child=popup(opener);android.webkit.WebViewClient originalClient=child.getWebViewClient();String destination="https://identity.example/auth";
            assertTrue(originalClient.shouldOverrideUrlLoading(child,request(destination,"GET",true)));choose(DialogInterface.BUTTON_POSITIVE);assertFalse(originalClient.shouldOverrideUrlLoading(child,request(destination,"GET",false)));
            opener.getWebViewClient().onPageStarted(opener,"https://example.com/replaced",null);assertSame(opener,web(h.browser));assertEquals(0,h.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("popupRelations").length());
            assertTrue(originalClient.shouldOverrideUrlLoading(child,request(destination,"GET",false)));assertNotNull(originalClient.shouldInterceptRequest(child,request(destination,"GET",false)));
        }
    }
    @Test public void nonGestureWindowDoesNotConsumeTransportAndPostPopupIsNotReplayedAsGet()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("login");WebView opener=web(h.browser);AtomicInteger replies=new AtomicInteger();Message reply=Message.obtain(new Handler(Looper.getMainLooper(),m->{replies.incrementAndGet();return true;}));reply.obj=opener.new WebViewTransport();assertFalse(opener.getWebChromeClient().onCreateWindow(opener,false,false,reply));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,replies.get());
            WebView child=popup(opener);assertTrue(child.getWebViewClient().shouldOverrideUrlLoading(child,request("https://identity.example/form","POST",true)));assertSame(opener,web(h.browser));assertTrue(h.current.control.isPaused());
        }
    }
    private static final class Permission extends PermissionRequest {
        final String[] resources;int grants,denials;String[] granted;
        Permission(String... resources){this.resources=resources;}
        public Uri getOrigin(){return Uri.parse("https://media.example");}
        public String[] getResources(){return resources.clone();}
        public void grant(String[] resources){grants++;granted=resources.clone();}
        public void deny(){denials++;}
    }
    @Test public void nativeMediaCallbackRequiresOwnerEvenWhenAndroidPermissionAlreadyExists()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("media");WebView page=web(h.browser);Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("android.permission.CAMERA");Permission request=new Permission(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            page.getWebChromeClient().onPermissionRequest(request);assertTrue(h.current.control.isPaused());assertEquals(0,request.grants);choose(DialogInterface.BUTTON_POSITIVE);assertEquals(1,request.grants);assertArrayEquals(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE},request.granted);assertEquals(0,request.denials);assertEquals("site_permission_granted",h.browser.snapshot().getJSONObject("siteNotice").getString("reason"));
            page.getWebChromeClient().onPermissionRequestCanceled(request);assertEquals(1,request.grants);assertEquals(0,request.denials);
        }
    }
    @Test public void navigationAndCancellationRetireTheExactPermissionCallback()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("media");WebView page=web(h.browser);Permission request=new Permission(PermissionRequest.RESOURCE_AUDIO_CAPTURE);page.getWebChromeClient().onPermissionRequest(request);page.getWebViewClient().onPageStarted(page,"https://example.com/next",null);assertEquals(0,request.grants);assertEquals(1,request.denials);page.getWebChromeClient().onPermissionRequestCanceled(request);assertEquals(1,request.denials);
        }
    }
    private static int permissionCode(BrowserActivity activity)throws Exception {Field field=BrowserActivity.class.getDeclaredField("siteRequest");field.setAccessible(true);Object request=field.get(activity);assertNotNull(request);Field code=request.getClass().getDeclaredField("requestCode");code.setAccessible(true);return code.getInt(request);}
    @Test public void platformPermissionResultWhilePausedWaitsForOwnerResumeAndGrantsOnce()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("media");WebView page=web(h.browser);String camera="android.permission.CAMERA";Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(camera);Permission request=new Permission(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            page.getWebChromeClient().onPermissionRequest(request);choose(DialogInterface.BUTTON_POSITIVE);int code=permissionCode(h.browser);h.activity.pause();Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(camera);
            h.browser.onRequestPermissionsResult(code,new String[]{camera},new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});assertEquals(0,request.grants);h.activity.resume();assertEquals(1,request.grants);
            h.browser.onRequestPermissionsResult(code,new String[]{camera},new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});assertEquals(1,request.grants);
        }
    }
    @Test public void navigationDuringPlatformPromptRejectsLateGrant()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("media");WebView page=web(h.browser);String camera="android.permission.CAMERA";Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(camera);Permission request=new Permission(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            page.getWebChromeClient().onPermissionRequest(request);choose(DialogInterface.BUTTON_POSITIVE);int code=permissionCode(h.browser);h.activity.pause();page.getWebViewClient().onPageStarted(page,"https://example.com/replaced",null);Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(camera);
            h.browser.onRequestPermissionsResult(code,new String[]{camera},new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});h.activity.resume();assertEquals(0,request.grants);assertEquals(1,request.denials);
        }
    }
    @Test public void unknownResourceAndSerializedIntentNeverAcquirePermissionOrLaunch()throws Exception {
        try(BrowserTaskActivityTest.Fixture h=new BrowserTaskActivityTest().new Fixture()){
            h.open("media");WebView page=web(h.browser);Permission request=new Permission(PermissionRequest.RESOURCE_VIDEO_CAPTURE,"android.webkit.resource.FUTURE_DEVICE");page.getWebChromeClient().onPermissionRequest(request);assertEquals(1,request.denials);assertEquals(0,request.grants);
            assertTrue(page.getWebViewClient().shouldOverrideUrlLoading(page,request("intent://host/#Intent;component=private.Component;end","GET",true)));assertNull(Shadows.shadowOf(h.browser).getNextStartedActivity());
        }
    }
}
