package dev.pocketengine.features.channels;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import dev.pocketengine.EngineApplication;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit, visible foreground lifetime for the app's existing QQ/iLink receivers only. */
public class ChannelReceiveService extends Service {
    public static final String START="dev.pocketengine.channels.RECEIVE_START",STOP="dev.pocketengine.channels.RECEIVE_STOP";
    public static final String NOTIFICATION_CHANNEL="channel_receiving";
    private static final int NOTIFICATION_ID=7824;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"pocket-channel-lifecycle");t.setDaemon(true);return t;});
    private ChannelsModule channels;
    private String session;
    private int lastStartId;
    private boolean foreground,admissionComplete,stopping,destroyed,setupUnavailable;
    private PowerManager.WakeLock wakeLock;
    private long renewAt;

    /** Call only from the native visible Activity's explicit confirmation, never a feature command. */
    public static JSONObject startFromVisibleActivity(Activity activity,ChannelsModule channels,JSONArray profileIds,boolean confirmed)throws Exception {
        if(!confirmed||!visibleOwner(activity))throw new SecurityException("请返回可见应用界面，明确确认后台接收");
        JSONObject platform=platformState(activity);if(!platform.getBoolean("serviceDeclared"))throw new IllegalStateException("后台接收服务尚未安装到此版本，未启动连接");
        if(!platform.getBoolean("notificationsVisible"))throw new SecurityException("请先在系统设置允许本应用及后台接收通知，再开启接收");
        JSONObject ticket=channels.prepareBackgroundReceiving(profileIds,true);String session=ticket.getString("sessionId");
        try{activity.startForegroundService(new Intent(activity,ChannelReceiveService.class).setAction(START).putExtra("sessionId",session));}
        catch(RuntimeException rejected){channels.finishBackgroundReceiving(session,"blocked","系统未允许启动前台接收服务，请在可见界面重试");throw new IllegalStateException("系统未允许启动前台接收服务，没有启动新连接",rejected);}
        return ticket.put("foregroundEstablished",false).put("message","已提交可见前台服务请求，尚未确认平台在线");
    }
    private static boolean visibleOwner(Activity activity){
        if(activity==null||activity.isFinishing()||activity.isDestroyed()||!activity.getWindow().getDecorView().isShown())return false;
        // An Activity-owned confirmation Dialog temporarily takes window focus without pausing
        // the visible Activity. Use its real lifecycle, not a model/caller-provided foreground bit.
        return activity instanceof LifecycleOwner?((LifecycleOwner)activity).getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED):activity.hasWindowFocus();
    }
    /** UI/notification stop carries the exact prior session; an old action cannot stop a newer one. */
    public static JSONObject stopFromOwnerUi(Context context,ChannelsModule channels,String expectedSession)throws Exception {
        JSONObject before=channels.backgroundState();if(!expectedSession.equals(before.optString("sessionId")))throw new SecurityException("后台接收会话已变化，请刷新后再停止");
        JSONObject result=channels.stopBackgroundReceiving(expectedSession);
        if(before.optBoolean("foregroundServicePresent"))try{context.startService(stopIntent(context,expectedSession));}catch(RuntimeException blocked){return result.put("serviceStopSignalAccepted",false).put("message","已取消接收权限；服务停止信号未获系统确认，通知清理状态仍需核对");}
        return result.put("serviceStopSignalAccepted",true);
    }
    public static JSONObject platformState(Context context)throws Exception {
        boolean declared=false;try{ServiceInfo info=context.getPackageManager().getServiceInfo(new ComponentName(context,ChannelReceiveService.class),0);declared=!info.exported&&(Build.VERSION.SDK_INT<34||(info.getForegroundServiceType()&ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)!=0);}catch(PackageManager.NameNotFoundException missing){}
        NotificationManager notifications=context.getSystemService(NotificationManager.class);boolean visible=notifications!=null&&notifications.areNotificationsEnabled();
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)visible=false;
        NotificationChannel channel=notifications==null?null:notifications.getNotificationChannel(NOTIFICATION_CHANNEL);if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE)visible=false;
        PowerManager power=context.getSystemService(PowerManager.class);boolean exempt=power!=null&&power.isIgnoringBatteryOptimizations(context.getPackageName()),idle=power!=null&&power.isDeviceIdleMode();
        return new JSONObject().put("serviceDeclared",declared).put("serviceType","specialUse").put("notificationsVisible",visible).put("batteryOptimizationsIgnored",exempt).put("deviceIdle",idle)
                .put("dozeMayDelay",!exempt).put("startRequiresVisibleOwnerUi",true).put("forceStopOrRebootSurvival",false).put("silentRestart",false);
    }
    protected ChannelsModule resolveChannels(){
        if(!(getApplication() instanceof EngineApplication))throw new IllegalStateException("正式渠道宿主不可用");
        Object module=((EngineApplication)getApplication()).features.module("channels");if(!(module instanceof ChannelsModule))throw new IllegalStateException("正式渠道模块不可用");return (ChannelsModule)module;
    }
    @Override public void onCreate(){super.onCreate();try{channels=resolveChannels();}catch(RuntimeException unavailable){stopSelf();return;}try{NotificationManager manager=getSystemService(NotificationManager.class);if(manager!=null)manager.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL,"QQ / 微信后台接收",NotificationManager.IMPORTANCE_LOW));}catch(RuntimeException unavailable){setupUnavailable=true;}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        lastStartId=startId;
        if(channels==null){stopSelfResult(startId);return START_NOT_STICKY;}
        if(intent==null){if(session==null)stopSelfResult(startId);return START_NOT_STICKY;}
        String requested=intent.getStringExtra("sessionId");
        if(STOP.equals(intent.getAction())){if(requested!=null&&requested.equals(session))requestStop(requested,"stopped","机主已停止后台接收；已有任务请在原任务中停止");else if(session==null)stopSelfResult(startId);return START_NOT_STICKY;}
        if(!START.equals(intent.getAction())||requested==null||!requested.matches("[a-f0-9-]{36}")||!channels.canClaimBackground(requested)){if(session==null)stopSelfResult(startId);return START_NOT_STICKY;}
        if(session!=null){if(!session.equals(requested))try{channels.finishBackgroundReceiving(requested,"blocked","原接收服务仍在清理，请稍后重新确认");}catch(Exception ignored){}return START_NOT_STICKY;}
        session=requested;admissionComplete=false;stopping=false;
        try{
            if(setupUnavailable)throw new IllegalStateException("后台接收通知通道无法建立");
            if(!platformState(this).getBoolean("notificationsVisible"))throw new SecurityException("后台接收通知未获允许");
            Notification notification=notification("正在建立前台接收，平台认证状态单独核对");
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION_ID,notification);foreground=true;
            channels.foregroundReceivingEstablished(requested);renewWakeLock();
            worker.execute(()->{try{channels.startBackgroundReceivers(requested);main.post(()->{if(requested.equals(session)&&!stopping){admissionComplete=true;main.post(poll);}});}catch(Exception failed){main.post(()->requestStop(requested,"blocked","后台连接未能启动，请核对配置和授权后重新开启"));}});
        }catch(Exception rejected){requestStop(requested,"blocked","前台接收服务或唤醒资源未能建立，没有继续接收");}
        return START_NOT_STICKY;
    }
    private final Runnable poll=new Runnable(){public void run(){
        String expected=session;if(expected==null||destroyed||stopping)return;
        try{
            JSONObject state=channels.backgroundState();if(!expected.equals(state.optString("sessionId"))||!state.optBoolean("foregroundActive")){requestStop(expected,"interrupted","本次后台接收授权已结束，连接已停止");return;}
            if(!platformState(ChannelReceiveService.this).getBoolean("notificationsVisible")){requestStop(expected,"blocked","后台接收通知已被关闭，已停止连接");return;}
            if(admissionComplete&&state.getInt("runningReceivers")==0){requestStop(expected,"stopped","所有接收连接已结束，请处理平台授权后由本机重新开启");return;}
            if(SystemClock.elapsedRealtime()>=renewAt)renewWakeLock();
            boolean idle=state.getJSONObject("platform").optBoolean("deviceIdle")&&!state.getJSONObject("platform").optBoolean("batteryOptimizationsIgnored");
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(idle?"系统省电可能延迟接收；可在应用内核对":"已认证 "+state.getInt("authenticatedReceivers")+" · 运行连接 "+state.getInt("runningReceivers")+"，可随时停止"));
            main.postDelayed(this,2000);
        }catch(Exception failed){requestStop(expected,"blocked","接收状态或资源无法核对，已请求停止");}
    }};
    private void requestStop(String expected,String status,String reason){
        if(expected==null||!expected.equals(session)||stopping)return;stopping=true;main.removeCallbacks(poll);boolean cleanupError=false;
        try{channels.finishBackgroundReceiving(expected,status,reason);}catch(Exception failed){cleanupError=true;}
        final boolean stateError=cleanupError;
        try{worker.execute(()->{
            boolean error=stateError;try{long end=SystemClock.elapsedRealtime()+2000;while(channels.backgroundState().optInt("runningReceivers")>0&&SystemClock.elapsedRealtime()<end)Thread.sleep(25);if(channels.backgroundState().optInt("runningReceivers")>0)error=true;}catch(Exception failed){error=true;}
            final boolean failed=error;main.post(()->completeStop(expected,failed));
        });}catch(RuntimeException unavailable){completeStop(expected,true);}
    }
    private void completeStop(String expected,boolean cleanupError){
        if(!expected.equals(session))return;main.removeCallbacks(poll);
        try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(RuntimeException failed){cleanupError=true;}wakeLock=null;
        try{if(foreground)stopForeground(STOP_FOREGROUND_REMOVE);}catch(RuntimeException failed){cleanupError=true;}foreground=false;
        if(cleanupError)channels.backgroundCleanupFailed(expected);channels.foregroundReceivingReleased(expected);session=null;stopSelfResult(lastStartId);
    }
    private void renewWakeLock(){
        if(wakeLock==null){PowerManager power=getSystemService(PowerManager.class);if(power==null)throw new IllegalStateException("设备电源管理不可用");wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketWorld:ChannelReceive");wakeLock.setReferenceCounted(false);}
        wakeLock.acquire(10*60_000L);renewAt=SystemClock.elapsedRealtime()+5*60_000L;
    }
    private Notification notification(String text){
        Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());if(launch==null)launch=new Intent().setClassName(getPackageName(),"dev.pocketengine.ui.ComposeActivity");
        PendingIntent open=PendingIntent.getActivity(this,7824,launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,0,stopIntent(this,session),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,NOTIFICATION_CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("QQ / 微信后台接收").setContentText(text).setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null,"停止接收",stop).build()).build();
    }
    private static Intent stopIntent(Context context,String session){return new Intent(context,ChannelReceiveService.class).setAction(STOP).setData(Uri.parse("pocketengine://channel-receive/stop/"+session)).putExtra("sessionId",session);}
    @Override public void onTimeout(int startId,int type){if(session!=null)requestStop(session,"interrupted","系统要求结束接收服务，未自动重启");else stopSelfResult(startId);}
    @Override public void onDestroy(){
        destroyed=true;main.removeCallbacksAndMessages(null);String expected=session;
        if(expected!=null){try{channels.finishBackgroundReceiving(expected,"interrupted","前台接收服务已结束，请在本机重新开启");}catch(Exception failed){channels.backgroundCleanupFailed(expected);}completeStop(expected,false);}
        worker.shutdownNow();super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
