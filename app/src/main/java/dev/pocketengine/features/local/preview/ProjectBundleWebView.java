package dev.pocketengine.features.local.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.webkit.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Same-origin real HTML/CSS/JS/image bytes, scoped to one immutable native preview. */
public final class ProjectBundleWebView extends WebView {
    public interface Events {void error(String message);void console(String level,String text,String source,int line);}
    public interface SelectionEvents {void selected(ProjectPreviewDocument.Selection selection);default void changed(){}}
    private volatile boolean released;
    private final ProjectPreviewDocument document;
    private final Events events;
    private final SelectionEvents selectionEvents;
    private final String selectionScript;
    private final int touchSlop;
    private boolean selecting,suppressedGesture,gestureMoved,pageGesture,loadFailed;
    private float gestureX,gestureY;
    private int gesturePointer;
    private long selectionTicket,gestureGeneration,gestureTicket;
    private String gestureUrl;
    private final java.util.Set<String> resourceErrors=new java.util.HashSet<>();
    private static final String CSP="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; media-src 'none'; object-src 'none'; worker-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'self'";
    public ProjectBundleWebView(Context context,ProjectPreviewBundle.Verified bundle,Events events){
        this(context,new ProjectPreviewDocument(bundle),events,selection->{});
    }
    public ProjectBundleWebView(Context context,ProjectPreviewDocument document,Events events,SelectionEvents selectionEvents){
        super(context);this.document=java.util.Objects.requireNonNull(document);this.events=java.util.Objects.requireNonNull(events);this.selectionEvents=java.util.Objects.requireNonNull(selectionEvents);
        ProjectPreviewBundle.Verified bundle=document.bundle();touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();selectionScript=readSelectionScript(context);
        WebSettings settings=getSettings();settings.setJavaScriptEnabled(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);settings.setBlockNetworkLoads(true);settings.setBlockNetworkImage(false);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setDomStorageEnabled(false);settings.setGeolocationEnabled(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);CookieManager.getInstance().setAcceptThirdPartyCookies(this,false);
        setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){if(released)return;loadFailed=false;loading();}
            @Override public void onPageFinished(WebView view,String url){if(released||loadFailed||url==null||!url.equals(view.getUrl()))return;try{document.loaded(url);changed();}catch(SecurityException invalid){events.error("原预览入口已改变，请重新打开");changed();}}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !bundle.document(request.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !bundle.document(url);}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return intercept(bundle,events,request.getUrl().toString(),request.getMethod());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){return intercept(bundle,events,url,"GET");}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();loadFailed=true;loading();}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){release();events.error("预览进程退出，可重新打开原资源；构建回执保留");return true;}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()){loadFailed=true;loading();events.error("当前预览入口未能显示，请核对原项目文件");}else resourceError(bundle,events,request.getUrl().toString(),"加载失败（"+error.getErrorCode()+"）");}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse error){if(error.getStatusCode()>=400){if(request.isForMainFrame()){loadFailed=true;loading();}resourceError(bundle,events,request.getUrl().toString(),"返回错误（HTTP "+error.getStatusCode()+"）");}}
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
        setDownloadListener((url,agent,disposition,mime,length)->events.error("预览中的下载需返回原项目另行处理"));document.loading();loadUrl(bundle.entryUrl);
    }
    public void setSelectionEnabled(boolean enabled){
        if(enabled){if(released||!document.ready()||selectionScript==null||pageGesture||!isShown()||!hasWindowFocus())throw new SecurityException("请在当前已显示的原预览中启用元素选择");selectionTicket=document.beginSelection();selecting=true;}
        else {selecting=false;document.endSelection();}
        changed();
    }
    public boolean isSelectionEnabled(){return selecting&&!released&&document.ready();}
    private void changed(){try{selectionEvents.changed();}catch(RuntimeException ignored){}}
    private void loading(){selecting=false;document.loading();changed();}
    @Override public void reload(){if(released)return;loadFailed=false;loading();super.reload();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused&&document!=null&&selecting)setSelectionEnabled(false);}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&document!=null&&selecting)setSelectionEnabled(false);}
    @Override protected void onVisibilityChanged(android.view.View changedView,int visibility){super.onVisibilityChanged(changedView,visibility);if(visibility!=VISIBLE&&document!=null&&selecting)setSelectionEnabled(false);}
    @Override protected void onDetachedFromWindow(){if(document!=null&&selecting)setSelectionEnabled(false);super.onDetachedFromWindow();}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(!selecting&&event.getActionMasked()==MotionEvent.ACTION_DOWN)suppressedGesture=false;
        if(released||selecting||suppressedGesture)return consumeSelection(event);
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)pageGesture=true;
        try{return super.dispatchTouchEvent(event);}finally{if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)pageGesture=false;}
    }
    @Override public boolean onTouchEvent(MotionEvent event){return released||selecting||suppressedGesture?consumeSelection(event):super.onTouchEvent(event);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){return released||selecting||suppressedGesture||super.dispatchGenericMotionEvent(event);}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(released||selecting||suppressedGesture)return event.getKeyCode()!=KeyEvent.KEYCODE_BACK;return super.dispatchKeyEvent(event);}
    @Override public boolean performClick(){return released||selecting||suppressedGesture||super.performClick();}
    @Override public boolean performAccessibilityAction(int action,android.os.Bundle arguments){if(released||selecting||suppressedGesture)return false;return super.performAccessibilityAction(action,arguments);}
    private boolean consumeSelection(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){suppressedGesture=true;gestureMoved=false;gesturePointer=event.getPointerId(0);gestureX=event.getX();gestureY=event.getY();gestureGeneration=document.generation();gestureTicket=selectionTicket;gestureUrl=getUrl();}
        if(event.getPointerCount()!=1||action==MotionEvent.ACTION_POINTER_DOWN||Math.abs(event.getX()-gestureX)>touchSlop||Math.abs(event.getY()-gestureY)>touchSlop)gestureMoved=true;
        if(action==MotionEvent.ACTION_UP){boolean capture=suppressedGesture&&!gestureMoved&&selecting&&!released&&event.getPointerId(0)==gesturePointer;suppressedGesture=false;if(capture)capture(event.getX(),event.getY(),gestureGeneration,gestureTicket,gestureUrl);}
        else if(action==MotionEvent.ACTION_CANCEL)suppressedGesture=false;
        return true;
    }
    private void capture(float x,float y,long generation,long ticket,String nativeUrl){
        final int width=getWidth(),height=getHeight();
        if(!Float.isFinite(x)||!Float.isFinite(y)||width<=0||height<=0||x<0||y<0||x>=width||y>=height||!java.util.Objects.equals(nativeUrl,getUrl()))return;
        String call=selectionScript+"("+Float.toString(x)+","+Float.toString(y)+","+width+","+height+")";
        evaluateJavascript(call,raw->{
            if(released||!selecting||!isShown()||!hasWindowFocus()||width!=getWidth()||height!=getHeight()||!java.util.Objects.equals(nativeUrl,getUrl()))return;
            try{JSONTokener parser=new JSONTokener(raw==null?"null":raw);Object value=parser.nextValue();if(parser.nextClean()!=0||!(value instanceof JSONObject))throw new SecurityException();
                ProjectPreviewDocument.Selection selection=document.select(generation,ticket,nativeUrl,(JSONObject)value);selectionEvents.selected(selection);
            }catch(Exception unavailable){events.error("原元素引用未能加入草稿，请重新核对预览和输入位置");}
        });
    }
    private static String readSelectionScript(Context context){
        try(var input=context.getAssets().open("project-preview/element-read.js");var output=new java.io.ByteArrayOutputStream()){
            byte[] chunk=new byte[2048];int count;
            while((count=input.read(chunk))!=-1){if(output.size()+count>32768)return null;output.write(chunk,0,count);}
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }catch(Exception unavailable){return null;}
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
    public void release(){if(released)return;released=true;selecting=false;document.release();changed();stopLoading();onPause();removeAllViews();destroy();}
}
