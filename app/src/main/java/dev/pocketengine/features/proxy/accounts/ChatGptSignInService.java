package dev.pocketengine.features.proxy.accounts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.ui.ComposeActivity;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Keeps the application-owned PKCE attempt reachable during the user's system-browser handoff. */
public final class ChatGptSignInService extends Service {
    private static final String CHANNEL="chatgpt-sign-in",CANCEL="dev.pocketengine.CANCEL_CHATGPT_SIGN_IN",SESSION="sessionId";
    private static final int NOTIFICATION=7403;
    private ScheduledExecutorService watcher;
    private volatile String session="";
    private volatile int latestStartId;
    public static void start(Context context,String sessionId){
        if(sessionId==null||!sessionId.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("INVALID_LOGIN_SESSION");
        Intent intent=new Intent(context,ChatGptSignInService.class).putExtra(SESSION,sessionId);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);
    }
    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"ChatGPT 登录",NotificationManager.IMPORTANCE_LOW));
        watcher=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"pocket-chatgpt-sign-in");thread.setDaemon(true);return thread;});
    }
    @Override public synchronized int onStartCommand(Intent intent,int flags,int startId){
        String requested=intent==null?"":intent.getStringExtra(SESSION);
        if(requested==null||!requested.matches("[0-9a-f-]{36}")){stopSelf(startId);return START_NOT_STICKY;}
        latestStartId=startId;boolean cancelling=CANCEL.equals(intent.getAction());
        if(!cancelling||session.isEmpty())session=requested;
        Notification notification=notification(session);
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION,notification);
        if(cancelling)watcher.execute(()->cancelExact(requested));
        // One observer per service instance, even when the Activity or notification starts it again.
        if(!observing){observing=true;watcher.scheduleWithFixedDelay(this::observe,0,400,TimeUnit.MILLISECONDS);}
        return START_NOT_STICKY;
    }
    private boolean observing;
    private NativeOwnerWorkflows workflows(){return ((EngineApplication)getApplication()).nativeOwnerWorkflows;}
    private void cancelExact(String requested){
        try{NativeOwnerWorkflows workflows=workflows();JSONObject login=workflows.accountSnapshot().optJSONObject("login");
            if(active(login,requested))try(GatewayCall call=new GatewayCall(5000)){workflows.account("cancelLogin",new JSONObject().put("sessionId",requested),call);}
        }catch(Exception ignored){}finally{observe();}
    }
    private void observe(){
        String expected=session;int startId=latestStartId;
        try{NativeOwnerWorkflows workflows=workflows();if(workflows!=null&&active(workflows.accountSnapshot().optJSONObject("login"),expected))return;}
        catch(Exception ignored){}finish(expected,startId);
    }
    private static boolean active(JSONObject login,String id){return login!=null&&id.equals(login.optString("sessionId"))&&
        ("pending".equals(login.optString("status"))||"exchanging".equals(login.optString("status")));}
    private Notification notification(String id){
        Intent returnToApp=new Intent(this,ComposeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openChatGptAccounts",true);
        PendingIntent content=PendingIntent.getActivity(this,NOTIFICATION,returnToApp,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent cancel=new Intent(this,ChatGptSignInService.class).setAction(CANCEL).putExtra(SESSION,id);
        // A per-attempt data identity prevents an older notification action from cancelling a newer attempt.
        cancel.setData(android.net.Uri.parse("pocketworld-login:"+id));
        PendingIntent stop=PendingIntent.getService(this,id.hashCode(),cancel,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("正在用 ChatGPT 登录")
            .setContentText("请在系统浏览器完成登录，或取消。到期后会自动结束。")
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null,"取消登录",stop).build()).build();
    }
    private synchronized void finish(String expected,int startId){
        if(!expected.equals(session)||startId!=latestStartId)return;
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);
    }
    @Override public void onDestroy(){if(watcher!=null)watcher.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
