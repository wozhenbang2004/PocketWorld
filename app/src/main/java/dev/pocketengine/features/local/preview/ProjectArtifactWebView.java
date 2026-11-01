package dev.pocketengine.features.local.preview;

import android.content.Context;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import java.io.ByteArrayInputStream;
import java.util.Collections;

/** New WebView per verified artifact. No Native interface, external navigation or resource resolver. */
public final class ProjectArtifactWebView extends WebView {
    public interface Events {void error(String message);}
    private boolean released;
    public ProjectArtifactWebView(Context context,ProjectPreviewArtifact.Verified artifact,Events events) {
        super(context);if(!artifact.html)throw new IllegalArgumentException("HTML artifact required");
        String origin=ProjectPreviewPolicy.origin(artifact);WebSettings settings=getSettings();
        settings.setJavaScriptEnabled(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);settings.setBlockNetworkImage(true);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDomStorageEnabled(false);settings.setGeolocationEnabled(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,false);
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !ProjectPreviewPolicy.sameDocumentFragment(request.getUrl().toString(),origin);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !ProjectPreviewPolicy.sameDocumentFragment(url,origin);}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return blocked();}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){return blocked();}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){release();events.error("预览进程已退出。可重新核对产物后打开，不影响原构建回执。");return true;}
        });
        setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean gesture,Message result){return false;}
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){result.cancel();return true;}
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){result.cancel();return true;}
            @Override public boolean onJsPrompt(WebView view,String url,String message,String initial,JsPromptResult result){result.cancel();return true;}
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){callback.onReceiveValue(null);return true;}
            @Override public boolean onConsoleMessage(ConsoleMessage message){return true;}
        });
        setDownloadListener((url,agent,disposition,mime,length)->events.error("离线产物预览不下载或打开外部链接。"));
        loadDataWithBaseURL(origin,ProjectPreviewPolicy.document(artifact),"text/html","UTF-8",null);
    }
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    public void release(){if(released)return;released=true;stopLoading();onPause();removeAllViews();destroy();}
}
