package dev.pocketengine.features.ssh;

import android.content.Context;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.webkit.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import org.json.JSONObject;

/** A separate, offline, display-only scripting context. All PTY input remains native. */
public final class SshTerminalWebView extends WebView {
    static final String ORIGIN="https://pocketworld-terminal.invalid/";
    private boolean ready,released;private String pending;
    public interface Events {void failed(String message);}
    public SshTerminalWebView(Context context,Events events){
        super(context);WebSettings settings=getSettings();settings.setJavaScriptEnabled(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);settings.setBlockNetworkImage(true);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setDomStorageEnabled(false);settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,false);
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return resource(context,request.getUrl().toString());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){return resource(context,url);}
            @Override public void onPageFinished(WebView view,String url){if(!released&&url.equals(ORIGIN+"index.html")){ready=true;render();}}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){release();events.failed("终端显示进程结束；原SSH任务未因此重建或重放。请收起后重新打开原终端。");return true;}
        });
        setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean gesture,Message result){return false;}
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){result.cancel();return true;}
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){result.cancel();return true;}
            @Override public boolean onJsPrompt(WebView view,String url,String message,String initial,JsPromptResult result){result.cancel();return true;}
            @Override public boolean onShowFileChooser(WebView view,android.webkit.ValueCallback<Uri[]> callback,FileChooserParams params){callback.onReceiveValue(null);return true;}
            @Override public boolean onConsoleMessage(ConsoleMessage message){return true;}
        });
        setDownloadListener((url,agent,disposition,mime,length)->events.failed("终端输出不能启动下载或打开外部链接。"));
        loadUrl(ORIGIN+"index.html");
    }
    public void display(JSONObject state){if(released)return;pending=state.toString();render();}
    private void render(){if(ready&&!released&&pending!=null)evaluateJavascript("window.PocketTerminal&&window.PocketTerminal.display("+pending+")",null);}
    static boolean allowed(String url){return url.equals(ORIGIN+"index.html")||url.equals(ORIGIN+"xterm.js")||url.equals(ORIGIN+"xterm.css")||url.equals(ORIGIN+"terminal-policy.js")||url.equals(ORIGIN+"terminal.js")||url.equals(ORIGIN+"terminal.css");}
    private static WebResourceResponse resource(Context context,String url){
        if(!allowed(url))return blocked();String name=url.substring(ORIGIN.length());try{
            String mime=name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":"text/html";InputStream bytes=context.getAssets().open("ssh-terminal/"+name);
            return new WebResourceResponse(mime,"UTF-8",200,"OK",Collections.singletonMap("Cache-Control","no-store"),bytes);
        }catch(Exception absent){return blocked();}
    }
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    public void release(){if(released)return;released=true;ready=false;pending=null;stopLoading();onPause();removeAllViews();destroy();}
}
