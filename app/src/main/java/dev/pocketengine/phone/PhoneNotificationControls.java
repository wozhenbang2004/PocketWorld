package dev.pocketengine.phone;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.RunControl;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Native notification routing only. Intent fields identify a run; they never grant permission. */
public final class PhoneNotificationControls {
    public static final String PAUSE="dev.pocketengine.PHONE_OWNER_PAUSE",RESUME="dev.pocketengine.PHONE_OWNER_RESUME";
    public static final String OPEN="dev.pocketengine.PHONE_OWNER_REVIEW";
    private static final String RUN="phoneRunId",EPOCH="phoneEpoch";
    private PhoneNotificationControls() { }
    public static boolean handles(String action){return PhoneRunService.STOP.equals(action)||PAUSE.equals(action)||RESUME.equals(action);}
    public static Intent command(Context context,String action,JSONObject task)throws Exception {
        if(!handles(action))throw new IllegalArgumentException("不支持的手机通知动作");
        Intent identified=identify(new Intent(context,PhoneRunService.class).setAction(action),action,task);
        if(!RESUME.equals(action))return identified;
        // Older callers may retain an unconfirmed request; dispatch will refuse it, never bind it to a later pause.
        try{return reviewedResume(context,task,identified);}catch(Exception unavailable){return identified;}
    }
    public static Intent reviewedResume(Context context,JSONObject task)throws Exception{
        return reviewedResume(context,task,identify(new Intent(context,PhoneRunService.class).setAction(RESUME),RESUME,task));
    }
    private static Intent reviewedResume(Context context,JSONObject task,Intent identified)throws Exception{
        if(!(context.getApplicationContext() instanceof EngineApplication app))throw new SecurityException("原手机宿主不可用");
        String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl original=app.control(id);
        if(original==null||!original.matchesHostRun(id,run,epoch)||!original.isPaused()||original.isCancelled())throw new SecurityException("原暂停已变化");
        long pause=original.pauseRevision();if(!original.ownerPausedAt(pause))throw new SecurityException("请从原任务处理这次等待");
        long scope=app.tasks.notificationResumeScope(id,run,epoch,original,pause);
        if(scope<1||original!=app.control(id)||!original.ownerPausedAt(pause))throw new SecurityException("原暂停或范围已变化");
        String handle=original.notificationPauseHandle(pause);
        return identified.setData(identified.getData().buildUpon().appendPath(Long.toString(pause)).appendPath(Long.toString(scope)).appendPath(handle).build())
                .putExtra("reviewedPhonePause",pause).putExtra("reviewedPhoneScope",scope).putExtra("originalPauseHandle",handle);
    }
    public static final String OWNER_RETURN="dev.pocketengine.PHONE_OWNER_RETURN_STEP";
    /** This single immutable notification belongs to one refusal, pause and retained service hold.
     * It is not a notification-level approve button and cannot create an operation or a run. */
    public static Intent ownerReturn(Context context,JSONObject task,String operation,long pause,long scope,long generation)throws Exception {
        Intent i=identify(new Intent(context,PhoneRunService.class).setAction(OWNER_RETURN),OWNER_RETURN,task);
        return i.setData(i.getData().buildUpon().appendPath(operation).appendPath(Long.toString(pause)).appendPath(Long.toString(scope)).appendPath(Long.toString(generation)).build())
                .putExtra("ownerStep",operation).putExtra("ownerPause",pause).putExtra("ownerScope",scope).putExtra("ownerGeneration",generation);
    }
    private static final String RETURN_NOTICE="dev.pocketengine.phone.OWNER_STEP_NOTICE";
    public static void markOwnerReturn(android.app.Notification.Builder builder,JSONObject task,String operation,long pause,long scope)throws Exception {
        android.os.Bundle extras=new android.os.Bundle();extras.putString(RETURN_NOTICE,task.getString("id"));extras.putString("originalRun",task.getString("runId"));
        extras.putInt("originalEpoch",task.getInt("coordinatorEpoch"));extras.putString("originalStep",operation);extras.putLong("originalPause",pause);extras.putLong("originalScope",scope);builder.addExtras(extras);
    }
    /** Only this app's already-posted original notification. No listener, screen or other app's notifications. */
    public static boolean returnNoticePosted(Context context,String id,String run,int epoch,String operation,long pause,long scope){
        try{
            android.app.NotificationManager notices=context.getSystemService(android.app.NotificationManager.class);
            if(notices==null||!notices.areNotificationsEnabled())return false;
            for(android.service.notification.StatusBarNotification item:notices.getActiveNotifications()){
                if(!context.getPackageName().equals(item.getPackageName()))continue;
                var notification=item.getNotification();if(notification==null||notification.extras==null)continue;var e=notification.extras;
                if(!id.equals(e.getString(RETURN_NOTICE))||!run.equals(e.getString("originalRun"))||epoch!=e.getInt("originalEpoch",-1)||
                        !operation.equals(e.getString("originalStep"))||pause!=e.getLong("originalPause",-1)||scope!=e.getLong("originalScope",-1))continue;
                var channel=notices.getNotificationChannel(notification.getChannelId());
                if(channel==null||channel.getImportance()==android.app.NotificationManager.IMPORTANCE_NONE)continue;
                if(channel.getGroup()!=null){var group=notices.getNotificationChannelGroup(channel.getGroup());if(group==null||group.isBlocked())continue;}
                return true;
            }
        }catch(RuntimeException unavailable){/* Not proof the action is visible. */}return false;
    }
    public static Intent open(Context context,JSONObject task)throws Exception {
        Intent intent=new Intent(context,dev.pocketengine.ui.ComposeActivity.class).setAction(OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return identify(intent,OPEN,task);
    }
    private static Intent identify(Intent intent,String action,JSONObject task)throws Exception {
        String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
        if(id.isEmpty()||id.length()>100||run.isEmpty()||run.length()>100||epoch<1)throw new IllegalArgumentException("缺少原手机执行身份");
        // Extras do not participate in PendingIntent identity; unique data prevents UPDATE_CURRENT retargeting an old run.
        return intent.setData(new Uri.Builder().scheme("pocketengine-phone").authority("owner").appendPath(action).appendPath(id).appendPath(run).appendPath(String.valueOf(epoch)).build())
                .putExtra("taskId",id).putExtra(RUN,run).putExtra(EPOCH,epoch);
    }
    public static boolean dispatch(EngineApplication app,Intent intent,String serviceTask,String serviceRun,int serviceEpoch,RunControl original) {
        boolean nativeOwner=false;
        try {
            String id=intent.getStringExtra("taskId"),run=intent.getStringExtra(RUN);int epoch=intent.getIntExtra(EPOCH,-1);
            synchronized(app){
                if(!handles(intent.getAction())||id==null||run==null||!id.equals(serviceTask)||!run.equals(serviceRun)||epoch!=serviceEpoch
                        ||original==null||original!=app.control(id)||!original.hasHostRun()||!original.matchesHostRun(id,run,epoch))return false;
                nativeOwner=true;
            }
            // Read the original native tuple/state, not audit history or a UI header.
            JSONObject task=app.tasks.foregroundNotificationContextSnapshot(id);
            if(task==null||!"phone".equals(task.optString("kind"))||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))return false;
            boolean active=task.optString("status").matches("preparing|running|pausing|paused|awaiting_user|reconciling");
            if(PhoneRunService.STOP.equals(intent.getAction())){if(active)app.stopOriginalPhoneRun(id,run,epoch);else original.cancel();return true;}
            if(!active||original.isCancelled())return false;
            if(PAUSE.equals(intent.getAction())){if(!original.isPaused())app.pauseOriginalPhoneRun(id,run,epoch);}
            else{
                long pause=intent.getLongExtra("reviewedPhonePause",-1),scope=intent.getLongExtra("reviewedPhoneScope",-1);
                String handle=intent.getStringExtra("originalPauseHandle");
                if(handle==null||handle.length()!=36){app.error("旧通知不含原暂停凭据，未恢复手机操作");return false;}
                Uri expected=new Uri.Builder().scheme("pocketengine-phone").authority("owner").appendPath(RESUME).appendPath(id).appendPath(run)
                        .appendPath(Integer.toString(epoch)).appendPath(Long.toString(pause)).appendPath(Long.toString(scope)).appendPath(handle).build();
                if(pause<1||scope<1||!expected.equals(intent.getData())||!original.matchesNotificationPause(handle,pause)){
                    app.error("这条通知不属于当前暂停，没有继续手机操作；请打开原任务核对");return false;
                }
                app.resumeNotifiedPhoneRun(id,run,epoch,pause,scope,handle);
            }
            return true;
        } catch(Exception unavailable) {
            if(nativeOwner&&PhoneRunService.STOP.equals(intent.getAction()))original.cancel();
            else if(nativeOwner&&PAUSE.equals(intent.getAction()))original.pause();
            app.error("手机通知所属执行暂不可核对，已阻止继续；请打开原任务核对记录");return false;
        }
    }
    /** Read-only navigation. Exported Activity extras cannot resume, approve, stop or create any task. */
    /** Take the immutable navigation tuple only. Does not load a task/approve an effect on the Activity thread. */
    public static boolean takeOriginalOpen(Intent intent,Consumer<JSONObject> lookup,Consumer<String> unavailable){
        if(intent==null||!OPEN.equals(intent.getAction()))return false;
        String task=intent.getStringExtra("taskId"),run=intent.getStringExtra(RUN);int epoch=intent.getIntExtra(EPOCH,-1);
        Uri source=intent.getData();intent.setAction(null);intent.removeExtra("taskId");intent.removeExtra(RUN);intent.removeExtra(EPOCH);
        try{
            if(task==null||run==null||task.isBlank()||run.isBlank()||task.length()>100||run.length()>100||epoch<1)throw new IllegalArgumentException();
            Uri original=new Uri.Builder().scheme("pocketengine-phone").authority("owner").appendPath(OPEN).appendPath(task).appendPath(run).appendPath(Integer.toString(epoch)).build();
            if(!original.equals(source))throw new SecurityException("通知来源已变化");
            lookup.accept(new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch));
        }catch(Exception changed){unavailable.accept("原手机通知身份无法核对，没有打开其他轮次");}
        return true;
    }
    public static boolean openRequestedTask(EngineApplication app,Intent intent,Consumer<String> focus) {
        if(intent==null||!OPEN.equals(intent.getAction()))return false;
        String id=intent.getStringExtra("taskId"),run=intent.getStringExtra(RUN);int epoch=intent.getIntExtra(EPOCH,-1);
        intent.setAction(null);intent.removeExtra("taskId");intent.removeExtra(RUN);intent.removeExtra(EPOCH);
        try {
            if(id==null||id.isEmpty()||id.length()>100||run==null||run.isEmpty()||run.length()>100||epoch<1)throw new IllegalArgumentException();
            JSONObject task=app.tasks.foregroundNotificationContextSnapshot(id);
            if(task==null||!"phone".equals(task.optString("kind"))||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new IllegalArgumentException();
            focus.accept(id);
        } catch(Exception unavailable) {app.error("原通知所属执行已结束或无法核对，请在任务列表查看记录");}
        return true;
    }
}
