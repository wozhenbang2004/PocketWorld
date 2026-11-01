package dev.pocketengine.features.local.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.webkit.*;
import dev.pocketengine.features.local.runtime.ProjectDevSession;
import java.io.ByteArrayInputStream;
import java.util.Map;

/** Real Vite HTTP/WS page in an isolated provider profile. No JavascriptInterface/native bridge. */
public final class ProjectLiveWebView extends WebView {
    public interface Events {void error(String message);void console(String level,String text,String source,int line);}
    private volatile boolean released;
    private ProjectLiveWebView(Context context,ProjectDevSession.Observed observed,Events events)throws Exception {
        super(context);
        try{
            // Must be the first operation on the fresh view, before getSettings/load/JS.
            ProjectLivePreviewProfiles.bindFirst(context,this,observed);
            WebSettings settings=getSettings();settings.setJavaScriptEnabled(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);settings.setDomStorageEnabled(false);settings.setDatabaseEnabled(false);settings.setGeolocationEnabled(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return released||!observed.allows(request.getUrl().toString())||!request.getMethod().equals("GET");}
                @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return released||!observed.allows(url);}
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return !released&&observed.allows(request.getUrl().toString())&&java.util.Set.of("GET","HEAD").contains(request.getMethod())?null:denied();}
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){return !released&&observed.allows(url)?null:denied();}
                @Override public void onPageStarted(WebView view,String url,Bitmap favicon){if(!observed.allows(url)){stopLoading();events.error("活预览离开原服务范围，导航已阻止");}}
                @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();}
                @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(!released)events.console("ERROR","开发页面资源未取得（"+error.getErrorCode()+"）",request.getUrl().toString(),0);}
                @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){if(!released&&response.getStatusCode()>=400)events.console("ERROR","开发页面实际HTTP "+response.getStatusCode(),request.getUrl().toString(),0);}
                @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){release();events.error("活预览渲染进程退出；服务与文件结果须回原任务核对，页面不会自动重放运行");return true;}
            });
            setWebChromeClient(new WebChromeClient(){
                @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
                @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
                @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean gesture,Message result){return false;}
                @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){result.cancel();return true;}
                @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){result.cancel();return true;}
                @Override public boolean onJsPrompt(WebView view,String url,String message,String initial,JsPromptResult result){result.cancel();return true;}
                @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){callback.onReceiveValue(null);return true;}
                @Override public boolean onConsoleMessage(ConsoleMessage message){if(!released)events.console(message.messageLevel().name(),bound(message.message(),1600),bound(message.sourceId(),1000),message.lineNumber());return true;}
            });
            setDownloadListener((url,agent,disposition,mime,length)->events.error("活页面不能直接下载，请返回项目固定成果/导出入口"));loadUrl(observed.entryUrl);
        }catch(Exception|LinkageError failure){released=true;destroy();throw failure;}
    }
    public static ProjectLiveWebView create(Context context,ProjectDevSession.Observed observed,Events events)throws Exception {return new ProjectLiveWebView(context,observed,events);}
    private static WebResourceResponse denied(){return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Map.of("Cache-Control","no-store"),new ByteArrayInputStream(new byte[0]));}
    private static String bound(String value,int max){return value==null?"":value.substring(0,Math.min(value.length(),max));}
    /** Closes this view; does not falsely claim its loaded provider profile has been deleted. */
    public void release(){if(released)return;released=true;stopLoading();onPause();removeAllViews();destroy();}
}
