package dev.pocketengine;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.AndroidPhonePort;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private WebView web;
    private EngineApplication app;
    private MediaController media;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private boolean ready;
    private final String compatibilityPageId=java.util.UUID.randomUUID().toString();
    private final EngineApplication.Observer observer=this::send;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);app=(EngineApplication)getApplication();media=new MediaController(this,app::publish);
        web=new WebView(this);web.setBackgroundColor(0xffffffff);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}});
        web.addJavascriptInterface(new Bridge(),"Native");setContentView(web);app.addObserver(observer);
        try(InputStream stream=getAssets().open("index.html")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);String html=new String(bytes.toByteArray(),StandardCharsets.UTF_8);web.loadDataWithBaseURL("https://pocketengine.local/",html,"text/html","UTF-8",null);}catch(Exception ex){web.loadData("<html><body><p>界面资源未加载，请重新安装完整测试包。</p></body></html>","text/html","UTF-8");}
    }
    @Override protected void onResume(){super.onResume();if(ready)app.sendState();}
    @Override protected void onPause(){media.stopListening();super.onPause();}
    @Override public void onBackPressed(){if(web!=null&&ready)web.evaluateJavascript("Boolean(window.Pocket&&window.Pocket.handleBack())",handled->{if(!"true".equals(handled))moveTaskToBack(true);});else moveTaskToBack(true);}
    @Override protected void onDestroy(){app.removeObserver(observer);media.close();io.shutdownNow();web.removeJavascriptInterface("Native");web.destroy();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);media.onRequestPermissionsResult(requestCode,permissions,results);}
    private void send(JSONObject event){if(!ready||isDestroyed())return;runOnUiThread(()->{if(!isDestroyed())web.evaluateJavascript("window.Pocket&&window.Pocket.onNativeEvent("+JSONObject.quote(event.toString())+")",null);});}
    private final class Bridge {
        @JavascriptInterface public void postMessage(String data){if(data==null||data.length()>500_000)return;try{JSONObject message=new JSONObject(data);runOnUiThread(()->handle(message));}catch(Exception ignored){app.error("无法读取界面请求");}}
    }
    private void handle(JSONObject m){
        String action=m.optString("action");
        try{
            switch(action){
                case "ready":ready=true;app.sendState();app.features.sendState();send(media.state().put("type","media_state"));deliverAutomationGoal();deliverAdvancedPanel();break;
                case "getState":app.sendState();break;
                case "getFeatures":app.features.sendState();break;
                case "featureRequest":{
                    app.dispatchOwnerAction(action,m);break;
                }
                case "openBrowser":startActivity(new Intent(this,BrowserActivity.class).putExtra("url",m.optString("url")));break;
                case "startListening":media.startListening();break;
                case "stopListening":media.stopListening();break;
                case "speakText":media.speak(m.optString("text"));break;
                case "stopSpeaking":media.stopSpeaking();break;
                case "getMediaState":send(media.state().put("type","media_state"));break;
                case "continueNativeChat":startActivity(dev.pocketengine.features.assistant.CompatibilityChatHandoff.open(app,compatibilityPageId,m));break;
                case "openSettings":send(EngineApplication.json("type","open_settings"));break;
                case "openAccessibility":startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));break;
                case "requestNotifications":if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},41);else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));break;
                case "saveConfig":{
                    JSONObject c=m.getJSONObject("config");io.execute(()->{try{app.settings.save(c);app.sendState();send(EngineApplication.json("type","config_saved","ok",true));}catch(Exception ex){app.error(safeMessage(ex));}});break;
                }
                case "testConnection":{
                    JSONObject c=m.optJSONObject("config");io.execute(()->{try{ModelConfig config=c==null?app.settings.get():app.settings.merged(c);SettingsStore.validate(config);JSONObject result=app.testModelConnection(config);result.put("type","connection_test");send(result);}catch(Exception ex){send(EngineApplication.json("type","connection_test","ok",false,"message",safeMessage(ex)));}});break;
                }
                case "startRun":{
                    String mode=m.optString("executionMode","background");String id=app.start(m.optString("goal"),m.optBoolean("screenConsent",false),mode);
                    if("main_screen".equals(mode))web.postDelayed(()->{if(id.equals(app.activeId())&&!isFinishing())moveTaskToBack(true);},350);break;
                }
                case "pauseRun":app.pause();break;
                case "resumeRun":app.resume();break;
                case "stopRun":app.stop(null);break;
                case "getTask":{JSONObject task=app.tasks.get(m.getString("taskId"),true);if(task==null)throw new IllegalArgumentException("任务不存在");send(EngineApplication.json("type","task","task",task));break;}
                case "confirmResult":case "updateTask":case "resumeTask":case "resolveOperation":app.dispatchOwnerAction(action,m);break;
                case "inspectScreen":{
                    if(app.activeId()!=null)throw new IllegalStateException("任务正在使用手机，请先停止任务再单独观察");io.execute(()->{try{JSONObject o=new AndroidPhonePort(this).observe(true,new RunControl());String image=o.optString("imageBase64");if(!image.isEmpty())o.put("imageDataUrl","data:"+o.optString("imageMimeType","image/jpeg")+";base64,"+image);o.remove("imageBase64");send(EngineApplication.json("type","inspection","observation",o));}catch(Exception ex){app.error(safeMessage(ex));}});break;
                }
                default:app.error("当前版本不支持该操作");
            }
        }catch(Exception ex){app.error(safeMessage(ex));}
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(ready){deliverAutomationGoal();deliverAdvancedPanel();}}
    /** Navigation only: exact local routes never grant capabilities or carry settings secrets. */
    public static Intent advancedPanelIntent(android.content.Context context,String panel){
        requireAdvancedPanel(panel);return new Intent(context,MainActivity.class).putExtra("openPanel",panel);
    }
    private static void requireAdvancedPanel(String panel){
        if(panel==null||!java.util.Set.of("settings","channels","plugins","proxy").contains(panel))throw new IllegalArgumentException("不支持的设置页面");
    }
    private void deliverAdvancedPanel(){
        if(!getIntent().hasExtra("openPanel"))return;
        String panel=getIntent().getStringExtra("openPanel");getIntent().removeExtra("openPanel");
        try{requireAdvancedPanel(panel);send(panel.equals("settings")?EngineApplication.json("type","open_settings"):EngineApplication.json("type","open_features","feature",panel));}
        catch(Exception invalid){send(EngineApplication.json("type","error","message","不支持的设置页面"));}
    }
    private void deliverAutomationGoal(){if(getIntent().hasExtra("automationGoal")){getIntent().removeExtra("automationGoal");send(EngineApplication.json("type","open_features","feature","automation"));}}
    private static String safeMessage(Exception e){String m=e.getMessage();return m==null?"操作失败，请重试":m.length()>250?m.substring(0,250):m;}
}
