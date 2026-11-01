package dev.pocketengine.features.local.preview;

import android.content.Context;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.webkit.*;
import java.io.ByteArrayInputStream;
import java.util.Map;

/** Same-origin real HTML/CSS/JS/image bytes, scoped to one immutable native preview. */
public final class ProjectBundleWebView extends WebView {
    public interface Events {void error(String message);void console(String level,String text,String source,int line);}
    private volatile boolean released;
    private final java.util.Set<String> resourceErrors=new java.util.HashSet<>();
    private static final String CSP="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; media-src 'none'; object-src 'none'; worker-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'self'";
    public ProjectBundleWebView(Context context,ProjectPreviewBundle.Verified bundle,Events events){
        super(context);WebSettings settings=getSettings();settings.setJavaScriptEnabled(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);settings.setBlockNetworkLoads(true);settings.setBlockNetworkImage(false);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setDomStorageEnabled(false);settings.setGeolocationEnabled(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);CookieManager.getInstance().setAcceptThirdPartyCookies(this,false);
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !bundle.document(request.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !bundle.document(url);}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return intercept(bundle,events,request.getUrl().toString(),request.getMethod());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){return intercept(bundle,events,url,"GET");}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){release();events.error("预览进程退出，可重新打开原资源；构建回执保留");return true;}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())events.error("当前预览入口未能显示，请核对原项目文件");else resourceError(bundle,events,request.getUrl().toString(),"加载失败（"+error.getErrorCode()+"）");}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse error){if(error.getStatusCode()>=400)resourceError(bundle,events,request.getUrl().toString(),"返回错误（HTTP "+error.getStatusCode()+"）");}
        });
        setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean gesture,Message result){return false;}
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){result.cancel();return true;}
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){result.cancel();return true;}
            @Override public boolean onJsPrompt(WebView view,String url,String message,String initial,JsPromptResult result){result.cancel();return true;}
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){callback.onReceiveValue(null);return true;}
            @Override public boolean onConsoleMessage(ConsoleMessage message){events.console(message.messageLevel().name(),bound(message.message(),1600),bound(message.sourceId(),240),message.lineNumber());return true;}
        });
        setDownloadListener((url,agent,disposition,mime,length)->events.error("预览中的下载需返回原项目另行处理"));loadUrl(bundle.entryUrl);
    }
    private WebResourceResponse intercept(ProjectPreviewBundle.Verified bundle,Events events,String url,String method){
        WebResourceResponse actual=response(bundle,url,method);if(actual.getStatusCode()>=400)resourceError(bundle,events,url,"未能读取（HTTP "+actual.getStatusCode()+"）");return actual;
    }
    /** Interception is off the UI thread. Only bounded local paths enter the existing task callback. */
    private void resourceError(ProjectPreviewBundle.Verified bundle,Events events,String url,String reason){
        String path=bundle.resourcePath(url);if(path==null)return;
        synchronized(resourceErrors){if(released||resourceErrors.size()>=24||!resourceErrors.add(path))return;}
        post(()->{if(!released)events.console("ERROR","预览资源"+reason+"："+path,bundle.origin+path,0);});
    }
    /** Public SDK response boundary is also exercised with actual binary fixture bytes. */
    public static WebResourceResponse response(ProjectPreviewBundle.Verified bundle,String url,String method){
        ProjectPreviewBundle.Resource resource=method.equals("GET")||method.equals("HEAD")?bundle.resource(url):null;
        if(resource==null)return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Map.of("Cache-Control","no-store","Content-Security-Policy",CSP),new ByteArrayInputStream(new byte[0]));
        return new WebResourceResponse(resource.mimeType,resource.mimeType.startsWith("text/")||resource.mimeType.endsWith("json")?"UTF-8":null,200,"OK",Map.of("Cache-Control","no-store","Content-Security-Policy",CSP,"X-Content-Type-Options","nosniff"),new ByteArrayInputStream(method.equals("HEAD")?new byte[0]:resource.bytes()));
    }
    private static String bound(String value,int max){return value==null?"":value.substring(0,Math.min(value.length(),max));}
    public void release(){if(released)return;released=true;stopLoading();onPause();removeAllViews();destroy();}
}
