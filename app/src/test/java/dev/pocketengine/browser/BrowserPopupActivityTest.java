package dev.pocketengine.browser;

import android.app.AlertDialog;
import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import dev.pocketengine.BrowserActivity;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real Activity/native transport/dialog ownership. No renderer, OAuth service or device permission is claimed. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserPopupActivityTest {
    static Object field(BrowserActivity activity,String name)throws Exception {Field field=BrowserActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
    static WebView web(BrowserActivity activity)throws Exception{return (WebView)field(activity,"web");}
    static AlertDialog dialog(BrowserActivity activity)throws Exception {return (AlertDialog)field(activity,"popupConsent");}
    static WebResourceRequest request(String url){return new WebResourceRequest(){public Uri getUrl(){return Uri.parse(url);}public boolean isForMainFrame(){return true;}public boolean isRedirect(){return false;}public boolean hasGesture(){return true;}public String getMethod(){return "GET";}public Map<String,String> getRequestHeaders(){return Map.of();}};}
    static Message nativeReply(WebView source,WebView.WebViewTransport transport,AtomicInteger count){Message reply=Message.obtain(new Handler(Looper.getMainLooper(),message->{assertSame(transport,message.obj);count.incrementAndGet();return true;}));reply.obj=transport;return reply;}
    @Test public void exactNativePopupUsesOneTransportAndRejectableDestinationReturnsToOriginalTaskPage()throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            String parent=h.open("popup-login"),session=h.browser.snapshot().getString("browserSessionId"),run=h.current.binding.runId;WebView source=web(h.browser);AtomicInteger replies=new AtomicInteger();WebView.WebViewTransport transport=source.new WebViewTransport();
            assertTrue(source.getWebChromeClient().onCreateWindow(source,false,true,nativeReply(source,transport,replies)));assertTrue(h.current.control.isPaused());assertNotNull(dialog(h.browser));dialog(h.browser).getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            WebView child=transport.getWebView();assertNotNull(child);assertSame(child,web(h.browser));assertEquals(1,replies.get());assertEquals(session,h.browser.snapshot().getString("browserSessionId"));assertEquals(3,h.browser.tabsForOwner().getJSONArray("tabs").length());
            assertTrue(child.getWebViewClient().shouldOverrideUrlLoading(child,request("https://login.other.example/authorize?private-code=secret")));AlertDialog destination=dialog(h.browser);assertNotNull(destination);String preview=((android.widget.TextView)destination.findViewById(android.R.id.message)).getText().toString();assertTrue(preview.contains("https://login.other.example"));assertFalse(preview.contains("private-code"));destination.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertSame(source,web(h.browser));assertEquals(parent,h.browser.tabsForOwner().getJSONArray("tabs").getJSONObject(1).getString("tabId"));assertEquals(2,h.browser.tabsForOwner().getJSONArray("tabs").length());assertEquals(1,replies.get());assertTrue(h.current.control.isPaused());assertEquals(run,h.current.binding.runId);assertEquals(1,h.tasks.list().length());
        }
    }
    @Test public void parentNavigationNullCompletesThePendingTransportAndOldAllowCannotResurrectIt()throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            h.open("popup-stale-parent");WebView source=web(h.browser);AtomicInteger replies=new AtomicInteger();WebView.WebViewTransport transport=source.new WebViewTransport();assertTrue(source.getWebChromeClient().onCreateWindow(source,false,true,nativeReply(source,transport,replies)));android.widget.Button oldAllow=dialog(h.browser).getButton(AlertDialog.BUTTON_POSITIVE);
            source.getWebViewClient().onPageStarted(source,"https://example.com/new-document",null);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,replies.get());assertNull(transport.getWebView());oldAllow.performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,replies.get());assertNull(transport.getWebView());assertSame(source,web(h.browser));assertEquals(2,h.browser.tabsForOwner().getJSONArray("tabs").length());assertTrue(h.current.control.isPaused());
        }
    }
    @Test public void pageWithoutNativeGestureCannotOpenAWindowOrAcquireAReply()throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            h.open("popup-without-gesture");WebView source=web(h.browser);AtomicInteger replies=new AtomicInteger();WebView.WebViewTransport transport=source.new WebViewTransport();assertFalse(source.getWebChromeClient().onCreateWindow(source,false,false,nativeReply(source,transport,replies)));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,replies.get());assertNull(transport.getWebView());assertNull(dialog(h.browser));assertEquals(2,h.browser.tabsForOwner().getJSONArray("tabs").length());
        }
    }
}
