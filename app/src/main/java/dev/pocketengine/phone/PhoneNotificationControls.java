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
        return identify(new Intent(context,PhoneRunService.class).setAction(action),action,task);
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
            else if(original.isPaused())app.resumeOriginalPhoneRun(id,run,epoch);
            return true;
        } catch(Exception unavailable) {
            if(nativeOwner&&PhoneRunService.STOP.equals(intent.getAction()))original.cancel();
            else if(nativeOwner&&PAUSE.equals(intent.getAction()))original.pause();
            app.error("手机通知所属执行暂不可核对，已阻止继续；请打开原任务核对记录");return false;
        }
    }
    /** Read-only navigation. Exported Activity extras cannot resume, approve, stop or create any task. */
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
