package dev.pocketengine.browser;

import android.app.Application;
import android.graphics.Bitmap;
import android.os.Looper;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserSiteModeTest {
    private static JSONObject obj(Object... pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static final class HistoryItem extends WebHistoryItem {
        final String url;HistoryItem(String url){this.url=url;}public int getId(){return 1;}@Override public String getUrl(){return url;}@Override public String getOriginalUrl(){return url;}@Override public String getTitle(){return "fixture";}@Override public Bitmap getFavicon(){return null;}@Override protected WebHistoryItem clone(){return new HistoryItem(url);}
    }
    static final class History extends WebBackForwardList {
        final int index;final String[] urls;History(int index,String... urls){this.index=index;this.urls=urls;}@Override public int getSize(){return urls.length;}@Override public int getCurrentIndex(){return index;}@Override public WebHistoryItem getCurrentItem(){return getItemAtIndex(index);}@Override public WebHistoryItem getItemAtIndex(int at){return at<0||at>=urls.length?null:new HistoryItem(urls[at]);}@Override protected WebBackForwardList clone(){return new History(index,urls.clone());}
    }
    static final class View extends WebView {
        History history=new History(0,"https://example.test/one");int back,forward,reloads;View(){super(RuntimeEnvironment.getApplication());}@Override public WebBackForwardList copyBackForwardList(){return history;}@Override public boolean canGoBack(){return history.index>0;}@Override public boolean canGoForward(){return history.index+1<history.urls.length;}@Override public void goBack(){back++;}@Override public void goForward(){forward++;}@Override public void reload(){reloads++;}
    }
    @Test public void desktopChangesActualSettingsAndMobileRestoresActualDefaultWithoutExtraReload()throws Exception {
        View view=new View();String actual=WebSettings.getDefaultUserAgent(view.getContext());AtomicInteger entered=new AtomicInteger();JSONObject desktop=BrowserSiteMode.apply(view,"desktop",()->{},entered::incrementAndGet);assertEquals(BrowserSiteMode.desktopUserAgent(actual),view.getSettings().getUserAgentString());assertTrue(view.getSettings().getUseWideViewPort());assertFalse(desktop.getBoolean("desktopOsEmulated"));assertFalse(desktop.getBoolean("uaClientHintsEmulated"));assertEquals(0,view.reloads);assertEquals("no_change",BrowserSiteMode.apply(view,"desktop",()->{},entered::incrementAndGet).getString("status"));assertEquals(1,entered.get());BrowserSiteMode.apply(view,"mobile",()->{},entered::incrementAndGet);assertEquals(actual,view.getSettings().getUserAgentString());assertFalse(view.getSettings().getUseWideViewPort());assertEquals(0,view.reloads);view.destroy();
    }
    @Test public void unavailableHistoryDoesNotClaimNavigationAndChangedHistoryCannotDispatch()throws Exception {
        View view=new View();AtomicInteger entered=new AtomicInteger();JSONObject original=BrowserSiteMode.historyPin(view),noHistory=BrowserSiteMode.navigateHistory(view,"back",original,()->{},entered::incrementAndGet);assertEquals("no_change",noHistory.getString("status"));assertTrue(noHistory.getBoolean("noOp"));assertEquals(0,entered.get());assertEquals(0,view.back);
        view.history=new History(1,"https://example.test/one","https://example.test/two");assertThrows(BrowserTaskSpace.StalePage.class,()->BrowserSiteMode.navigateHistory(view,"back",original,()->{},entered::incrementAndGet));assertEquals(0,view.back);JSONObject current=BrowserSiteMode.historyPin(view),back=BrowserSiteMode.navigateHistory(view,"back",current,()->{},entered::incrementAndGet);assertEquals("https://example.test/one",back.getString("targetUrl"));assertEquals(1,view.back);assertEquals(1,entered.get());view.destroy();
    }
    @Test public void unsupportedHistorySchemeIsRejectedBeforeNativeEntry()throws Exception {
        View view=new View();view.history=new History(1,"intent://external","https://example.test/two");AtomicInteger entered=new AtomicInteger();assertThrows(SecurityException.class,()->BrowserSiteMode.navigateHistory(view,"back",BrowserSiteMode.historyPin(view),()->{},entered::incrementAndGet));assertEquals(0,view.back);assertEquals(0,entered.get());view.destroy();
    }
    @Test public void resizeChangesActualLayoutAndReturnsActualCssViewportWithNoDprEmulation()throws Exception {
        View view=new View();FrameLayout parent=new FrameLayout(RuntimeEnvironment.getApplication());parent.addView(view,new FrameLayout.LayoutParams(400,600));parent.layout(0,0,400,600);view.layout(0,0,400,600);CompletableFuture<JSONObject> result=new CompletableFuture<>();AtomicInteger entered=new AtomicInteger();BrowserSiteMode.resize(view,obj("width",300,"height",500),()->{},entered::incrementAndGet,result);assertFalse(result.isDone());assertEquals(300,view.getLayoutParams().width);assertEquals(500,view.getLayoutParams().height);view.layout(0,0,300,500);var callback=Shadows.shadowOf(view).getLastEvaluatedJavascriptCallback();assertNotNull(callback);callback.onReceiveValue(JSONObject.quote(obj("width",150,"height",250,"dpr",2,"visualWidth",150,"visualHeight",250,"scale",1).toString()));assertEquals("viewport_resized",result.get().getString("status"));assertEquals(300,result.get().getInt("actualWidth"));assertEquals(2,result.get().getJSONObject("cssViewport").getInt("dpr"));assertFalse(result.get().getBoolean("devicePixelRatioEmulated"));assertEquals(1,entered.get());view.destroy();
    }
    @Test public void oversizedResizeAndCancelledReadbackNeverInventTheRequestedViewport()throws Exception {
        View view=new View();FrameLayout parent=new FrameLayout(RuntimeEnvironment.getApplication());parent.addView(view,new FrameLayout.LayoutParams(400,600));parent.layout(0,0,400,600);view.layout(0,0,400,600);assertThrows(IllegalArgumentException.class,()->BrowserSiteMode.resizePin(view,obj("width",1200,"height",800)));assertEquals(400,view.getWidth());CompletableFuture<JSONObject> result=new CompletableFuture<>();BrowserSiteMode.resize(view,obj("width",300,"height",500),()->{},()->{},result);result.cancel(false);view.layout(0,0,300,500);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertTrue(result.isCancelled());assertNull(Shadows.shadowOf(view).getLastEvaluatedJavascriptCallback());view.destroy();
    }
    @Test public void ownerReviewNamesExactHistoryDestinationAndRealRequestedDimensions()throws Exception {
        JSONObject pin=obj("pageUrl","https://example.test/current","navigationHistory",obj("back",obj("url","https://example.test/previous?record=one")),"siteMode",obj("width",400,"height",600),"resize",obj("layout",obj("width",400,"height",600,"containerWidth",400,"containerHeight",600)));
        String back=BrowserSiteMode.approvalSummary("back",new JSONObject(),pin);assertTrue(back.contains("精确目的地：https://example.test/previous?record=one"));assertTrue(BrowserSiteMode.approvalSummary("forward",new JSONObject(),pin).contains("没有对应历史页"));String resize=BrowserSiteMode.approvalSummary("resize",obj("width",300,"height",500),pin);assertTrue(resize.contains("400 × 600 → 300 × 500 物理像素"));assertTrue(BrowserSiteMode.approvalSummary("site_mode",obj("mode","desktop"),pin).contains("桌面站点请求模式"));
    }
}
