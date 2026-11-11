package dev.pocketengine.features.local.project;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import dev.pocketengine.ui.web.PocketWebHost;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentOutcomeHost;
import dev.pocketengine.features.attachments.AttachmentViewSnapshots;
import dev.pocketengine.features.attachments.OwnerFilePreviewSource;
import dev.pocketengine.phone.observation.NativeSensitiveStep;
import dev.pocketengine.ui.UiAppearance;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Non-exported owner host for the bundled React screen. No Web/agent tool can manufacture a typed source,
 * click the final system confirmation, or recover a launch from a saved JSON reference. */
public final class ProjectApkInstallActivity extends ComponentActivity {
    private static final int SETTINGS=1949;
    private static final HashMap<String,SourceEntry> SOURCES=new HashMap<>();
    private record SourceEntry(OwnerFilePreviewSource source,long expires){}
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private OwnerFilePreviewSource source;
    private ProjectApkHandoff handoffs;
    private JSONObject review,receipt;
    private String token,awaitingId,message="正在读取原构建成果…";
    private boolean resumed,busy,awaitingSettings;
    private int awaitingCode;
    private long generation;
    private RunControl control;
    private AutoCloseable ownerWindow,systemWindow;
    private PocketWebHost webHost;
    private Runnable surfaceRefresh;

    public static void open(Activity owner,OwnerFilePreviewSource source) throws Exception {
        OwnerProjectApk.requireSource(source);
        String token=UUID.randomUUID().toString();
        synchronized(SOURCES){long now=SystemClock.elapsedRealtime();SOURCES.entrySet().removeIf(entry->entry.getValue().expires<now);
            if(SOURCES.size()>=8)throw new IllegalStateException("请先关闭已有 APK 审阅页面");
            SOURCES.put(token,new SourceEntry(source,now+10*60_000L));}
        try{owner.startActivity(new Intent(owner,ProjectApkInstallActivity.class).putExtra("nativeSource",token));}
        catch(RuntimeException failure){synchronized(SOURCES){SOURCES.remove(token);}throw failure;}
    }

    @Override public void onCreate(Bundle state){
        setTheme(UiAppearance.theme(this));super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);UiAppearance.window(this,"workspace");
        handoffs=new ProjectApkHandoff(this);token=getIntent().getStringExtra("nativeSource");
        synchronized(SOURCES){SourceEntry entry=SOURCES.get(token);if(entry!=null&&entry.expires>=SystemClock.elapsedRealtime())source=entry.source;}
        awaitingId=state==null?null:state.getString("awaitingId");
        awaitingCode=state==null?0:state.getInt("awaitingCode",0);
        awaitingSettings=state!=null&&state.getBoolean("awaitingSettings",false);
        try{receipt=handoffs.current();if(awaitingId!=null){JSONObject original=handoffs.find(awaitingId);if(original!=null&&"awaiting_system".equals(original.optString("state"))&&original.optInt("requestCode")==awaitingCode)systemWindow=NativeSensitiveStep.openOwnerUpdateWindow();else awaitingId=null;}if(receipt!=null)message="已恢复原交接引用。恢复与核对只读，不会重新打开安装器。";else if(source==null)message="原私人文件引用已失效，请回原项目成果重新打开；没有请求安装。";}
        catch(Exception error){message=safe(error);source=null;}
        if(awaitingSettings&&systemWindow==null)systemWindow=NativeSensitiveStep.openOwnerUpdateWindow();
        webHost=PocketWebHost.projectApk(this);setContentView(webHost.getView());
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){webHost.handleBack(()->{finish();return kotlin.Unit.INSTANCE;});}});
        webHost.start();render();
    }
    @Override public void onStart(){super.onStart();ownerWindow=NativeSensitiveStep.openOwnerUpdateWindow();}
    @Override public void onResume(){super.onResume();resumed=true;UiAppearance.window(this,"workspace");try{refreshReceipt();}catch(Exception error){message=safe(error);source=null;}if(webHost!=null)webHost.resume();render();if(source!=null&&review==null&&!pending()&&!busy&&!awaitingSettings)inspect();}
    @Override public void onPause(){resumed=false;cancelRead();if(webHost!=null)webHost.pause();super.onPause();}
    @Override public void onStop(){closeOwnerWindow();super.onStop();}
    @Override public void onDestroy(){cancelRead();io.shutdownNow();if(webHost!=null)webHost.close();closeOwnerWindow();closeSystemWindow();if(isFinishing()){if(awaitingId!=null)try{JSONObject original=handoffs.find(awaitingId);if(original!=null)revoke(original);}catch(Exception ignored){/* The original provider expiry still applies. */}synchronized(SOURCES){SOURCES.remove(token);}}super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){if(awaitingId!=null){state.putString("awaitingId",awaitingId);state.putInt("awaitingCode",awaitingCode);}state.putBoolean("awaitingSettings",awaitingSettings);super.onSaveInstanceState(state);}

    private boolean pending(){return receipt!=null&&"awaiting_system".equals(receipt.optString("state"));}
    private void refreshReceipt() throws Exception {
        JSONObject previous=receipt;receipt=handoffs.current();
        if(receipt!=null&&(previous==null||!receipt.optString("requestId").equals(previous.optString("requestId"))||!receipt.optString("state").equals(previous.optString("state"))))message="已读取当前原交接引用。这里只核对状态，没有重新打开安装器。";
        if(awaitingId!=null){JSONObject original=handoffs.find(awaitingId);if(original==null||!"awaiting_system".equals(original.optString("state"))||original.optInt("requestCode")!=awaitingCode){awaitingId=null;if(!awaitingSettings)closeSystemWindow();}}
    }
    public void readApkState() throws Exception {requireSurface();refreshReceipt();render();}
    private void inspect(){
        if(source==null||pending())return;
        review=null;
        runRead(c->OwnerProjectApk.inspect(this,source,c),result->{review=result;message="已读取固定 APK 的包信息。证书指纹是解析结果，不等于已独立验证签名；最终由系统安装器校验。";});
    }
    private interface Read {JSONObject get(RunControl control)throws Exception;}
    private interface Loaded {void accept(JSONObject value)throws Exception;}
    private void runRead(Read call,Loaded loaded){
        if(!resumed||busy)return;busy=true;long gen=++generation;RunControl c=new RunControl();control=c;render();
        io.execute(()->{try{JSONObject result=call.get(c);c.check();runOnUiThread(()->{if(!live(gen,c))return;busy=false;control=null;try{loaded.accept(result);}catch(Exception error){message=safe(error);}render();});}
            catch(Exception error){runOnUiThread(()->{if(!live(gen,c))return;busy=false;control=null;message=safe(error);render();});}});
    }
    private boolean live(long gen,RunControl c){return resumed&&!isFinishing()&&!isDestroyed()&&generation==gen&&control==c&&!c.isCancelled();}
    private void cancelRead(){generation++;if(control!=null)control.cancel();control=null;busy=false;}

    private void prepareInstall(){
        if(!resumed||busy||source==null||review==null||pending())return;
        if(!getPackageManager().canRequestPackageInstalls()){message="尚未允许 PocketWorld 请求安装。可由你打开系统设置决定；返回后不会自动安装。";render();return;}
        JSONObject reviewed=review;busy=true;long gen=++generation;RunControl c=new RunControl();control=c;render();
        io.execute(()->{Intent prepared=null;
            try{prepared=OwnerProjectApk.prepare(this,source,reviewed,c);c.check();Intent exact=prepared;
                runOnUiThread(()->{if(!live(gen,c)){discard(exact);return;}busy=false;control=null;
                    try{
                        // Commit the original reference BEFORE any external handoff. It is never launch authority.
                        receipt=handoffs.begin(reviewed,exact.getData().toString());awaitingId=receipt.getString("requestId");awaitingCode=receipt.getInt("requestCode");
                        systemWindow=NativeSensitiveStep.openOwnerUpdateWindow();
                        exact.putExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,true);
                        try{startActivityForResult(exact,awaitingCode);message="已请求系统审阅，等待原结果。没有把打开安装器记作安装成功。";}
                        catch(ActivityNotFoundException absent){exact.removeExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED);discard(exact);receipt=handoffs.update(awaitingId,"not_started",null,null);awaitingId=null;closeSystemWindow();message="系统安装器没有启动；原文件保留。";}
                        catch(RuntimeException uncertain){message="系统安装器是否接手尚未确认。保留原交接引用，只能先核对，不会重投。";}
                    }catch(Exception error){if(!exact.getBooleanExtra(AttachmentOutcomeHost.NATIVE_VIEW_ISSUED,false))discard(exact);review=null;try{refreshReceipt();}catch(Exception refresh){error.addSuppressed(refresh);source=null;}message=safe(error);}
                    render();});
            }catch(Exception error){if(prepared!=null)discard(prepared);runOnUiThread(()->{if(!live(gen,c))return;busy=false;control=null;review=null;message=safe(error);render();});}
        });
    }
    public void openApkSettings() throws Exception {
        requireSurface();refreshReceipt();if(busy||pending())throw new IllegalStateException("请先核对原交接或等待读取完成");
        Intent settings=OwnerProjectApk.systemIntent(this,new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));
        awaitingSettings=true;systemWindow=NativeSensitiveStep.openOwnerUpdateWindow();
        try{startActivityForResult(settings,SETTINGS);}
        catch(RuntimeException error){awaitingSettings=false;closeSystemWindow();throw error;}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SETTINGS){awaitingSettings=false;closeSystemWindow();message="已返回设置。没有自动提交安装，请重新核对并由你发起。";render();return;}
        if(request!=awaitingCode||awaitingId==null)return;
        String original=awaitingId;awaitingId=null;closeSystemWindow();
        try{
            JSONObject reference=handoffs.find(original);
            if(reference==null){message="系统返回了结果，但原交接引用已不可核对；未更新其他安装记录。";return;}
            revoke(reference);
            handoffs.update(original,result==RESULT_OK?"system_reported_success":"system_returned_without_success",null,result);refreshReceipt();
            message=result==RESULT_OK?"系统报告成功。还需核对当前包身份；同版本原已存在时，不会宣称本次新安装已证实。":"系统未返回成功，可能取消或失败；请核对当前状态，没有重投。";
        }catch(Exception error){message=safe(error);}finally{render();}
    }
    private void reconcile(){
        JSONObject selected=receipt;if(selected==null)return;
        runRead(c->{c.check();JSONObject original=handoffs.find(selected.getString("requestId"));if(original==null)throw new IllegalStateException("原交接引用不可核对");
            JSONObject expected=original.getJSONObject("review"),installed=OwnerProjectApk.installed(this,expected.getString("packageName"));c.check();
            JSONObject observation=new JSONObject().put("installed",installed).put("installedIdentityMatches",OwnerProjectApk.matches(expected,installed))
                    .put("matchedBeforeHandoff",OwnerProjectApk.matches(expected,expected.getJSONObject("beforeInstalled")))
                    .put("installedApkBytesVerified",false).put("replacementProved",false);
            return handoffs.observe(original,observation);
        },result->{refreshReceipt();message=receipt!=null&&receipt.optString("requestId").equals(result.optString("requestId"))?"仅查询原包名、版本与证书。当前匹配不证明由这次交接安装，更不等于已运行或测试。":"核对期间当前交接已变化，已读取当前原引用；没有将旧回执替代当前交接。";});
    }
    public void closeUnknownApkWait(String requestId) throws Exception {
        requireSurface();refreshReceipt();if(busy||!pending()||!receipt.getString("requestId").equals(requestId))throw new IllegalStateException("原等待已变化，请重新读取");
        receipt=handoffs.update(requestId,"owner_closed_unknown",null,null);revoke(receipt);awaitingId=null;closeSystemWindow();review=null;
        message="owner_closed_unknown".equals(receipt.optString("state"))?"已结束等待，结果仍未知。另一次交接必须重新读取、审阅并由你确认。":"原交接已有新结果，请重新核对；没有把已知回执改成未知。";render();
    }
    public void requestApkInstall(String reviewId) throws Exception {
        requireSurface();refreshReceipt();if(busy||source==null||review==null||pending()||!ProjectSources.digest(review).equals(reviewId))throw new IllegalStateException("原 APK 审阅已变化，请重新读取后明确确认");
        prepareInstall();
    }
    public void inspectApk() throws Exception {requireSurface();refreshReceipt();if(busy||source==null||pending())throw new IllegalStateException("原文件不可读或原交接仍未决");inspect();}
    public void reconcileApk(String requestId) throws Exception {requireSurface();refreshReceipt();if(busy||receipt==null||!receipt.getString("requestId").equals(requestId))throw new IllegalStateException("原交接引用已变化");reconcile();}
    private void requireSurface(){if(!apkSurfaceAvailable())throw new IllegalStateException("请回原主人 APK 页面重新读取，离开页面不会继续提交");}
    public boolean apkSurfaceAvailable(){return resumed&&ownerWindow!=null&&!awaitingSettings&&!isFinishing()&&!isDestroyed();}
    private static JSONObject presentedReview(JSONObject original) throws Exception {
        // Only the presentation uses decimal text. Native comparisons and durable identity retain the exact long.
        return ProjectSources.copy(original).put("versionCode",Long.toString(original.getLong("versionCode")));
    }
    public JSONObject apkSurfaceSnapshot() throws Exception {
        JSONObject publicReceipt=receipt==null?null:ProjectSources.copy(receipt);if(publicReceipt!=null){publicReceipt.remove("viewUri");publicReceipt.put("review",presentedReview(receipt.getJSONObject("review")));}
        return new JSONObject().put("ready",apkSurfaceAvailable()).put("busy",busy).put("message",message)
                .put("sourcePresent",source!=null).put("pendingHandoff",pending()).put("canRequestInstalls",getPackageManager().canRequestPackageInstalls())
                .put("review",review==null?JSONObject.NULL:presentedReview(review)).put("reviewId",review==null?"":ProjectSources.digest(review))
                .put("receipt",publicReceipt==null?JSONObject.NULL:publicReceipt);
    }
    public void bindApkSurface(Runnable listener){surfaceRefresh=listener;}
    public void unbindApkSurface(Runnable listener){if(surfaceRefresh==listener)surfaceRefresh=null;}
    public void retireApkPresentation(){cancelRead();}
    private void render(){if(!isFinishing()&&!isDestroyed()&&surfaceRefresh!=null)surfaceRefresh.run();}
    private void discard(Intent intent){try{new AttachmentViewSnapshots(this).discardUnissuedView(intent);}catch(Exception ignored){/* Bounded immutable copy expires normally. */}}
    private void revoke(JSONObject reference){try{Uri uri=Uri.parse(reference.getString("viewUri"));if("content".equals(uri.getScheme())&&(getPackageName()+".attachments").equals(uri.getAuthority())&&uri.getPath()!=null&&uri.getPath().matches("/view/[a-f0-9-]{36}"))revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){/* Provider still enforces its original bounded expiry. */}}
    private static String safe(Exception error){String text=error.getMessage();return text==null||text.contains("content://")||text.contains("http")?"原 APK 或交接引用暂不可核对；未清空、未重新提交，请回原成果核对。":text.substring(0,Math.min(300,text.length()));}
    private void closeOwnerWindow(){if(ownerWindow!=null)try{ownerWindow.close();}catch(Exception ignored){}finally{ownerWindow=null;}}
    private void closeSystemWindow(){if(systemWindow!=null)try{systemWindow.close();}catch(Exception ignored){}finally{systemWindow=null;}}
}
