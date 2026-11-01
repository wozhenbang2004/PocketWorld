package dev.pocketengine.features.local.preview;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.webkit.*;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import dev.pocketengine.features.local.project.ProjectOwnerTerminal;
import java.io.*;
import java.util.*;
import org.json.*;

/** Pinned local VT renderer, isolated from project previews/account browsers. The
 * tiny main-frame message protocol can only propose bounded ordinary input to
 * the original native PTY Session; it has no URL, filesystem or feature bridge. */
public final class ProjectTerminalWebView extends WebView {
    private static final String ORIGIN="https://pw-terminal.invalid",ENTRY=ORIGIN+"/terminal/index.html";
    private static final Set<String> FILES=Set.of("index.html","terminal.js","xterm-5.5.0/xterm.js","xterm-5.5.0/xterm.css");
    public interface Events {void ready();void input(String text);void written(long cursor);void error(String message);}
    private final Events events;private final String token=UUID.randomUUID().toString().replace("-",""),execution;private final int columns,rows;
    private boolean released,ready,writing;private long awaiting;
    private ProjectTerminalWebView(Context context,JSONObject metadata,Events events)throws Exception{
        super(context);this.events=Objects.requireNonNull(events);execution=metadata.getString("executionId");columns=metadata.getInt("columns");rows=metadata.getInt("rows");
    }
    private void initialize(Context context)throws Exception{
        // Binding must precede settings, navigation, scripting or any other WebView use.
        ProjectLivePreviewProfiles.bindFirst(context,this,execution.replace("-",""));
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))throw new IllegalStateException("当前WebView没有受限主frame消息接口，未开放其他Native桥");
        var settings=getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setBlockNetworkLoads(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);setSaveEnabled(false);
        WebViewCompat.addWebMessageListener(this,"NativeTerminal",Set.of(ORIGIN),(view,message,source,mainFrame,reply)->{
            if(released||view!=this||!mainFrame||!"https".equals(source.getScheme())||!"pw-terminal.invalid".equals(source.getHost())||source.getPort()!=-1)return;
            try{String data=message.getData();if(data==null||data.length()>9000)return;JSONObject value=new JSONObject(data);if(!token.equals(value.optString("token")))return;String type=value.optString("type");
                if(type.equals("ready")){ready=true;events.ready();}
                else if(type.equals("input")&&ready){String text=value.getString("text");if(dev.pocketengine.features.local.project.ProjectCommandSpec.encode(text).length>4000)throw new SecurityException("终端输入超过单次范围");events.input(text);}
                else if(type.equals("written")&&writing&&value.opt("id") instanceof Number&&value.getLong("id")==awaiting){writing=false;events.written(awaiting);}
                else if(type.equals("blocked"))events.error("终端没有发送超大粘贴或二进制鼠标输入；普通文字、方向键与EOF仍按原范围操作");
            }catch(Exception invalid){events.error("终端消息未通过原范围校验，未发送");}
        });
        setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return resource(request);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public void onPageFinished(WebView view,String url){if(!released&&ENTRY.equals(url))evaluateJavascript("C2Terminal.boot("+JSONObject.quote(token)+","+columns+","+rows+")",null);}
            @Override public void onReceivedSslError(WebView view,android.webkit.SslErrorHandler handler,android.net.http.SslError error){handler.cancel();}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())events.error("本机终端页面未完整打开；原PTY仍由原任务监督");}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){events.error("终端显示进程已结束；未重启或重复输入原PTY");release();return true;}
        });
        setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){result.cancel();return true;}
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){result.cancel();return true;}
            @Override public boolean onJsPrompt(WebView view,String url,String message,String initial,JsPromptResult result){result.cancel();return true;}
            @Override public boolean onShowFileChooser(WebView view,android.webkit.ValueCallback<Uri[]> result,FileChooserParams params){result.onReceiveValue(null);return true;}
        });
        setDownloadListener((url,userAgent,disposition,mime,size)->events.error("终端页面不下载或打开链接"));
        loadUrl(ENTRY);
    }
    public static ProjectTerminalWebView create(Context context,JSONObject metadata,Events events)throws Exception{
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("终端必须在原生主线程打开");ProjectTerminalWebView view=new ProjectTerminalWebView(context,metadata,events);try{view.initialize(context);return view;}catch(Exception failed){try{view.release();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
    }
    private WebResourceResponse resource(WebResourceRequest request){
        Uri uri=request.getUrl();String prefix="/terminal/",path=uri.getPath();boolean allowed=!released&&"GET".equals(request.getMethod())&&"https".equals(uri.getScheme())&&"pw-terminal.invalid".equals(uri.getHost())&&uri.getPort()==-1&&uri.getQuery()==null&&path!=null&&path.startsWith(prefix)&&FILES.contains(path.substring(prefix.length()));
        try{if(allowed){String name=path.substring(prefix.length()),mime=name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":"text/html";InputStream input=getContext().getAssets().open("terminal/"+name);return new WebResourceResponse(mime,"UTF-8",200,"OK",Map.of("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'","Cache-Control","no-store","X-Content-Type-Options","nosniff","Referrer-Policy","no-referrer"),input);}}catch(IOException failed){/* Exact local asset absent: never fall through to network. */}
        return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Map.of("Cache-Control","no-store"),new ByteArrayInputStream(new byte[0]));
    }
    public boolean display(JSONObject nativePage)throws Exception{
        if(released||!ready||writing)return false;if(!execution.equals(nativePage.getString("operationId")))throw new SecurityException("输出不是这个原PTY");ByteArrayOutputStream bytes=new ByteArrayOutputStream();JSONArray chunks=nativePage.getJSONArray("chunks");long previous=-1;
        for(int i=0;i<chunks.length();i++){JSONObject chunk=chunks.getJSONObject(i);long sequence=chunk.getLong("sequence");byte[] raw=Base64.getDecoder().decode(chunk.getString("dataBase64"));if(sequence<=previous||raw.length!=chunk.getInt("bytes")||raw.length<1||raw.length>8192||bytes.size()+raw.length>32768)throw new SecurityException("原终端日志分页无效");previous=sequence;bytes.write(raw);}
        if(bytes.size()==0)return true;awaiting=nativePage.getLong("nextCursor");writing=true;String encoded=Base64.getEncoder().encodeToString(bytes.toByteArray());evaluateJavascript("C2Terminal.bytes("+JSONObject.quote(encoded)+","+awaiting+")",null);return true;
    }
    public void inputEnabled(boolean enabled){if(!released&&ready)evaluateJavascript("C2Terminal.inputEnabled("+enabled+")",null);}
    public void resized(int columns,int rows){if(columns<4||columns>400||rows<2||rows>200)throw new IllegalArgumentException("终端尺寸超限");if(!released&&ready)evaluateJavascript("C2Terminal.resize("+columns+","+rows+")",null);}
    public boolean waitingForParser(){return writing;}
    public void release(){if(released)return;released=true;ready=false;writing=false;try{WebViewCompat.removeWebMessageListener(this,"NativeTerminal");stopLoading();loadUrl("about:blank");}finally{destroy();}}
}
