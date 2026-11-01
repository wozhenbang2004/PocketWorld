package dev.pocketengine.browser;

import android.app.Application;
import android.net.Uri;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real native WebChromeClient/TaskStore feedback; permission request is an explicit platform-interface fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserSiteRequirementActivityTest {
    static final class Request extends PermissionRequest {int denied,granted;public Uri getOrigin(){return Uri.parse("https://example.com/");}public String[] getResources(){return new String[]{RESOURCE_VIDEO_CAPTURE,RESOURCE_AUDIO_CAPTURE};}public void grant(String[] requested){granted++;throw new AssertionError("No automatic hardware grant");}public void deny(){denied++;}}
    @Test public void denialIsVisibleInSameTaskWithNativeOriginAndNoDeviceGrant()throws Exception {try(BrowserPageDialogActivityTest.F x=new BrowserPageDialogActivityTest.F()){String tab=x.open();WebView page=BrowserPageDialogActivityTest.web(x.browser);Request request=new Request();page.getWebChromeClient().onPermissionRequest(request);assertEquals(1,request.denied);assertEquals(0,request.granted);JSONObject notice=x.browser.snapshot().getJSONObject("siteNotice");assertEquals("https://example.com",notice.getString("origin"));assertEquals(tab,notice.getString("tabId"));assertEquals(x.taskId,notice.getString("taskId"));assertEquals(x.binding.runId,notice.getString("runId"));assertEquals("blocked",notice.getString("status"));assertFalse(notice.getBoolean("hardwareAuthorizationGranted"));assertTrue(x.tasks.get(x.taskId,false).getJSONArray("events").toString().contains("browser_site_requirement"));page.getWebChromeClient().onPermissionRequestCanceled(request);assertEquals("cancelled",x.browser.snapshot().getJSONObject("siteNotice").getString("status"));}}
    @Test public void lateCancelledRequestCannotBecomeFeedbackForANewDocumentOrBackgroundPage()throws Exception {try(BrowserPageDialogActivityTest.F x=new BrowserPageDialogActivityTest.F()){x.open();WebView page=BrowserPageDialogActivityTest.web(x.browser);Request request=new Request();page.getWebChromeClient().onPermissionRequest(request);String oldDoc=x.browser.snapshot().getJSONObject("siteNotice").getString("documentId");page.getWebViewClient().onPageStarted(page,"https://example.com/new-document",null);assertTrue(x.browser.snapshot().isNull("siteNotice"));page.getWebChromeClient().onPermissionRequestCanceled(request);assertTrue(x.browser.snapshot().isNull("siteNotice"));Request late=new Request();page.getWebChromeClient().onPermissionRequest(late);assertEquals(1,late.denied);x.activity.pause();page.getWebChromeClient().onPermissionRequestCanceled(late);assertFalse(x.browser.snapshot().optJSONObject("siteNotice")!=null&&x.browser.snapshot().getJSONObject("siteNotice").getString("status").equals("cancelled"));x.activity.resume();assertNotEquals(oldDoc,x.browser.snapshot().getJSONObject("siteNotice").getString("documentId"));}}
}
