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
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.AndroidPhonePort;
import dev.pocketengine.runtime.models.ModelSelectionCoordinator;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private WebView web;
    private EngineApplication app;
    private MediaController media;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService controls=Executors.newSingleThreadExecutor();
    private final java.util.Set<ModelSelectionCoordinator.Mutation> modelMutations=ConcurrentHashMap.newKeySet();
    private AutoCloseable modelSelectionObserver;
    private volatile boolean ready,closed;
    private final String compatibilityPageId=java.util.UUID.randomUUID().toString();
    private final EngineApplication.Observer observer=this::send;
    @Override public void onCreate(Bundle state){
        setTheme(dev.pocketengine.ui.UiAppearance.theme(this));
        super.onCreate(state);dev.pocketengine.ui.UiAppearance.window(this);app=(EngineApplication)getApplication();media=new MediaController(this,app::publish);
        web=new WebView(this);web.setBackgroundColor(0xffffffff);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}});
        web.addJavascriptInterface(new Bridge(),"Native");setContentView(web);app.addObserver(observer);
        modelSelectionObserver=app.settings.modelSelection().observe(this::modelSelectionChanged);
        try(InputStream stream=getAssets().open("index.html")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);String html=new String(bytes.toByteArray(),StandardCharsets.UTF_8);web.loadDataWithBaseURL("https://pocketengine.local/",html,"text/html","UTF-8",null);}catch(Exception ex){web.loadData("<html><body><p>界面资源未加载，请重新安装完整测试包。</p></body></html>","text/html","UTF-8");}
    }
    @Override protected void onResume(){super.onResume();dev.pocketengine.ui.UiAppearance.window(this);if(ready)sendCurrentState();}
    @Override protected void onPause(){media.stopListening();super.onPause();}
    @Override public void onBackPressed(){if(web!=null&&ready)web.evaluateJavascript("Boolean(window.Pocket&&window.Pocket.handleBack())",handled->{if(!"true".equals(handled))moveTaskToBack(true);});else moveTaskToBack(true);}
    @Override protected void onDestroy(){closed=true;app.removeObserver(observer);if(modelSelectionObserver!=null)try{modelSelectionObserver.close();}catch(Exception ignored){}for(var mutation:modelMutations)mutation.close();modelMutations.clear();media.close();controls.shutdownNow();io.shutdownNow();web.removeJavascriptInterface("Native");web.destroy();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);media.onRequestPermissionsResult(requestCode,permissions,results);}
    private void send(JSONObject event){if("state".equals(event.optString("type")))sendCurrentState();else deliver(event);}
    private void deliver(JSONObject event){if(!ready||closed||isDestroyed())return;String encoded=JSONObject.quote(event.toString());runOnUiThread(()->{if(!closed&&!isDestroyed())web.evaluateJavascript("window.Pocket&&window.Pocket.onNativeEvent("+encoded+")",null);});}
    private void modelSelectionChanged(){
        var status=app.settings.modelSelection().status();
        deliver(EngineApplication.json("type","model_selection","revision",status.revision(),"pending",status.pending(),"available",false));
        if(!status.pending())sendCurrentState();
    }
    /** Configuration and revision must come from the same stable read, never from an older app event. */
    private void sendCurrentState(){
        if(!ready||closed)return;
        try{io.execute(()->{if(closed)return;try{
            var projection=app.settings.modelSelection().readStable(app::state);JSONObject event=projection.value();
            event.getJSONObject("payload").put("modelSelectionRevision",projection.revision()).put("modelSelectionPending",false).put("modelSelectionAvailable",true);deliver(event);
        }catch(Exception unavailable){var status=app.settings.modelSelection().status();deliver(EngineApplication.json("type","model_selection","revision",status.revision(),"pending",status.pending(),"available",false,"message",safeMessage(unavailable)));}});}
        catch(RejectedExecutionException ignored){/* A closed Activity cannot receive a new projection. */}
    }
    private static long exactNonnegativeLong(JSONObject request,String key)throws Exception {
        Object value=request.get(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0)throw new SecurityException("原操作身份不完整，请重新读取当前状态");return ((Number)value).longValue();
    }
    private void saveConfiguration(JSONObject request){
        try{
            long expected=exactNonnegativeLong(request,"modelSelectionRevision");JSONObject config=new JSONObject(request.getJSONObject("config").toString());
            var selection=app.settings.modelSelection();var mutation=selection.beginMutation(expected);modelMutations.add(mutation);
            try{io.execute(()->{try{
                mutation.run(()->{synchronized(app.settings){
                    if(closed)throw new IllegalStateException("原设置窗口已关闭，未执行排队中的保存");
                    var current=selection.status();if(!current.pending()||current.revision()!=Math.addExact(expected,1))throw new SecurityException("模型设置已变化，请读取最新选择后再保存；草稿已保留");
                    app.settings.save(config);
                }return null;});
                app.sendState();deliver(EngineApplication.json("type","config_saved","ok",true));
            }catch(Exception failure){deliver(EngineApplication.json("type","config_saved","ok",false,"message",safeMessage(failure)));}
            finally{modelMutations.remove(mutation);}});}
            catch(Exception failure){mutation.close();modelMutations.remove(mutation);throw failure;}
        }catch(Exception failure){deliver(EngineApplication.json("type","config_saved","ok",false,"message",safeMessage(failure)));}
    }
    private void testConfiguration(JSONObject request){
        try{
            long expected=exactNonnegativeLong(request,"modelSelectionRevision");JSONObject draft=request.optJSONObject("config");JSONObject config=draft==null?null:new JSONObject(draft.toString());
            io.execute(()->{try{
                if(closed)return;var projection=app.settings.modelSelection().readStable(()->config==null?app.settings.get():app.settings.merged(config));
                if(projection.revision()!=expected)throw new SecurityException("模型设置已变化，请读取最新选择后再测试；草稿已保留");
                SettingsStore.validate(projection.value());JSONObject result=app.testModelConnection(projection.value());result.put("type","connection_test").put("modelSelectionRevision",expected);deliver(result);
            }catch(Exception failure){deliver(EngineApplication.json("type","connection_test","ok",false,"modelSelectionRevision",expected,"message",safeMessage(failure)));}});
        }catch(Exception failure){deliver(EngineApplication.json("type","connection_test","ok",false,"message",safeMessage(failure)));}
    }
    private RunControl requireOriginalPhone(String id,String run,int epoch,RunControl captured){
        synchronized(app){RunControl current=app.control(id);if(id.isBlank()||run.isBlank()||epoch<1||current==null||captured!=null&&current!=captured||!id.equals(app.activeId())||!current.matchesHostRun(id,run,epoch))throw new SecurityException("原手机执行已变化，请刷新核对");return current;}
    }
    private void controlOriginalPhone(JSONObject request,String action){
        try{
            String id=request.getString("taskId"),run=request.getString("runId");long rawEpoch=exactNonnegativeLong(request,"epoch");if(rawEpoch<1||rawEpoch>Integer.MAX_VALUE)throw new SecurityException("原手机执行身份不完整");int epoch=(int)rawEpoch;
            RunControl original=requireOriginalPhone(id,run,epoch,null);
            controls.execute(()->{try{
                if(closed)return;requireOriginalPhone(id,run,epoch,original);
                JSONObject receipt=switch(action){case "pauseRun"->app.pauseOriginalPhoneRun(id,run,epoch);case "resumeRun"->app.resumeOriginalPhoneRun(id,run,epoch);case "stopRun"->app.stopOriginalPhoneRun(id,run,epoch);default->throw new IllegalArgumentException("不支持的原任务操作");};
                deliver(receipt.put("type","phone_control_result").put("ok",true));
            }catch(Exception failure){deliver(EngineApplication.json("type","phone_control_result","ok",false,"message",safeMessage(failure)));}});
        }catch(Exception failure){deliver(EngineApplication.json("type","phone_control_result","ok",false,"message",safeMessage(failure)));}
    }
    private final class Bridge {
        @JavascriptInterface public void postMessage(String data){if(data==null||data.length()>500_000)return;try{JSONObject message=new JSONObject(data);runOnUiThread(()->handle(message));}catch(Exception ignored){app.error("无法读取界面请求");}}
    }
    private void handle(JSONObject m){
        if(closed)return;
        String action=m.optString("action");
        try{
            switch(action){
                case "ready":ready=true;sendCurrentState();app.features.sendState();send(media.state().put("type","media_state"));deliverAutomationGoal();deliverAdvancedPanel();break;
                case "getState":sendCurrentState();break;
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
                case "saveConfig":saveConfiguration(m);break;
                case "testConnection":testConfiguration(m);break;
                case "startRun":{
                    String mode=m.optString("executionMode","background");String id=app.start(m.optString("goal"),m.optBoolean("screenConsent",false),mode);
                    RunControl started=app.control(id);if("main_screen".equals(mode))web.postDelayed(()->{if(!closed&&started!=null&&app.control(id)==started&&!isFinishing())moveTaskToBack(true);},350);break;
                }
                case "pauseRun":case "resumeRun":case "stopRun":controlOriginalPhone(m,action);break;
                case "getTask":{JSONObject task=app.tasks.get(m.getString("taskId"),true);if(task==null)throw new IllegalArgumentException("任务不存在");send(EngineApplication.json("type","task","task",task));break;}
                case "confirmResult":case "updateTask":case "resumeTask":case "resolveOperation":app.dispatchOwnerAction(action,m);break;
                case "inspectScreen":{
                    if(app.activeId()!=null)throw new IllegalStateException("任务正在使用手机，请先停止任务再单独观察");io.execute(()->{try{
                        RunControl original=new RunControl();var policy=new dev.pocketengine.phone.access.AndroidPhoneAccessStore(this);long revision=policy.current().revision();
                        var display=app.features.module("display") instanceof dev.pocketengine.phone.display.DisplayModule module?module:null;
                        dev.pocketengine.phone.access.PhoneAccessBindings.requireOwnerShare(this,display,original);
                        JSONObject o=new AndroidPhonePort(this).observe(true,original);
                        if(policy.current().revision()!=revision)throw new SecurityException("画面分享策略已改变，请重新捕获");
                        dev.pocketengine.phone.access.PhoneAccessBindings.requireOwnerShare(this,display,original);
                        String image=o.optString("imageBase64");if(!image.isEmpty())o.put("imageDataUrl","data:"+o.optString("imageMimeType","image/jpeg")+";base64,"+image);o.remove("imageBase64");send(EngineApplication.json("type","inspection","observation",o));
                    }catch(Exception ex){app.error(safeMessage(ex));}});break;
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
