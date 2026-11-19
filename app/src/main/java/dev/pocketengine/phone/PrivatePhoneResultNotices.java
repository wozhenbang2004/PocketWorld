package dev.pocketengine.phone;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Opt-in once, on this device, from an already-live private phone run. No channel creation or permission prompt. */
public final class PrivatePhoneResultNotices {
    private final Context context;private final TaskStore tasks;private final BooleanSupplier sameStore;
    private final ConcurrentHashMap<String,String> watching=new ConcurrentHashMap<>();
    private final Set<String> checking=ConcurrentHashMap.newKeySet(),dirty=ConcurrentHashMap.newKeySet();
    public PrivatePhoneResultNotices(Context context,TaskStore tasks,BooleanSupplier sameStore){this.context=context.getApplicationContext();this.tasks=tasks;this.sameStore=sameStore;}
    private boolean available(){try{
        NotificationManager n=context.getSystemService(NotificationManager.class);
        if(n==null||!n.areNotificationsEnabled()||Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationChannel channel=n.getNotificationChannel(PhoneReadiness.TASK_CHANNEL);if(channel==null||channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;
        if(channel.getGroup()!=null){var group=n.getNotificationChannelGroup(channel.getGroup());if(group==null||group.isBlocked())return false;}return true;
    }catch(RuntimeException unavailable){return false;}}
    public JSONObject choice(String id,String run,int epoch,RunControl original,Boolean wanted,BooleanSupplier window)throws Exception{
        boolean ready=available();if(Boolean.TRUE.equals(wanted)&&!ready)throw new IllegalStateException("现有本机任务通知通道不可用，没有打开或申请权限");
        if(!sameStore.getAsBoolean())throw new IllegalStateException("原任务库已关闭");
        JSONObject state=tasks.privatePhoneWatchChoice(id,run,epoch,original,wanted,()->window.getAsBoolean()&&sameStore.getAsBoolean());
        String mark=run+":"+epoch;
        if(state.getBoolean("watching"))watching.put(id,mark);else if(Boolean.FALSE.equals(wanted))watching.computeIfPresent(id,(key,old)->old.equals(mark)||old.equals("restore")?null:old);
        if(Boolean.TRUE.equals(wanted))afterTask(id); // close the small commit -> in-memory registration race
        return state.put("systemAvailable",ready).put("scope","this_run_this_phone");
    }
    public void restore(){if(!sameStore.getAsBoolean())return;for(String id:tasks.pendingPrivatePhoneWatches()){watching.putIfAbsent(id,"restore");afterTask(id);}}
    public void afterTask(String id){
        if(id==null||!watching.containsKey(id)||!sameStore.getAsBoolean())return;
        dirty.add(id);if(!checking.add(id))return;
        try{do{dirty.remove(id);String mark=watching.get(id);if(mark!=null&&sameStore.getAsBoolean())deliver(id,mark);}while(dirty.remove(id));}
        finally{checking.remove(id);if(!watching.containsKey(id))dirty.remove(id);else if(dirty.contains(id))afterTask(id);}
    }
    private void deliver(String id,String mark){
        JSONObject sealed=null;
        try{
            sealed=tasks.claimPrivatePhoneWatch(id);
            if(sealed==null){watching.remove(id,mark);return;}
            if(sealed.optBoolean("waiting"))return;
            watching.remove(id,mark);
            if(!available()||!sameStore.getAsBoolean())return;
            String run=sealed.getString("runId");int epoch=sealed.getInt("epoch");String status=sealed.getString("status");
            Intent view=PhoneNotificationControls.open(context,EngineApplication.json("id",id,"runId",run,"coordinatorEpoch",epoch));
            PendingIntent open=PendingIntent.getActivity(context,0,view,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            String title=switch(status){case "needs_confirmation","awaiting_user","reconciling","paused"->"这轮手机工作需要你核对";case "completed"->"原手机任务有了结果";default->"这轮手机执行已经停下";};
            Notification note=new Notification.Builder(context,PhoneReadiness.TASK_CHANNEL).setSmallIcon(dev.pocketengine.R.drawable.ic_task_notice)
                    .setContentTitle(title).setContentText("点开查看你选择提醒的那轮原记录。").setCategory(Notification.CATEGORY_STATUS).setLocalOnly(true)
                    .setVisibility(Notification.VISIBILITY_SECRET).setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(open).build();
            // No task goal, package, message, screen or controls on the notification; this is not a receipt of user delivery.
            if(sameStore.getAsBoolean())context.getSystemService(NotificationManager.class).notify("private-phone-result:"+id+":"+run+":"+epoch,2407,note);
        }catch(Exception unavailable){/* Ambiguous OS delivery must never trigger an automatic second post. */}
        finally{try{if(sealed!=null&&sealed.has("runId")&&sameStore.getAsBoolean())tasks.retirePrivatePhoneWatch(id,sealed.getString("runId"),sealed.getInt("epoch"));}catch(Exception ignored){}}
    }
}
