package dev.pocketengine.features.devices;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import org.json.JSONObject;

/** Local owner's exact-run notification route. Intent strings identify, but cannot grant authority. */
public final class DeviceNotificationControls {
    public static final String STOP="dev.pocketengine.DEVICE_OWNER_STOP", PAUSE="dev.pocketengine.DEVICE_OWNER_PAUSE", RESUME="dev.pocketengine.DEVICE_OWNER_RESUME";
    private DeviceNotificationControls() { }
    public static boolean handles(String action) { return STOP.equals(action)||PAUSE.equals(action)||RESUME.equals(action); }
    public static Intent command(Context context,String action,JSONObject task)throws Exception {
        if(!handles(action)||!TaskStoreDeviceTasks.deviceTask(task))throw new SecurityException("不是原设备任务通知");
        String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
        if(id.isEmpty()||run.isEmpty()||epoch<1)throw new SecurityException("原设备run未就绪");
        return new Intent(context,PhoneRunService.class).setAction(action).setData(new Uri.Builder().scheme("pocketengine-device").authority("owner").appendPath(action).appendPath(id).appendPath(run).appendPath(Integer.toString(epoch)).build())
                .putExtra("taskId",id).putExtra("deviceRunId",run).putExtra("deviceEpoch",epoch);
    }
    public static boolean dispatch(EngineApplication app,Intent intent,String serviceTask,String serviceRun,int serviceEpoch,RunControl original) {
        synchronized(app) {
            boolean owner=false;
            try {
                String id=intent.getStringExtra("taskId"),run=intent.getStringExtra("deviceRunId");int epoch=intent.getIntExtra("deviceEpoch",-1);
                if(!handles(intent.getAction())||id==null||run==null||!id.equals(serviceTask)||!run.equals(serviceRun)||epoch!=serviceEpoch||original==null||original!=app.control(id)||!id.equals(app.activeId())||!original.matchesHostRun(id,run,epoch))return false;
                JSONObject task=app.tasks.get(id,false);if(!TaskStoreDeviceTasks.deviceTask(task)||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))return false;
                owner=true;
                if(STOP.equals(intent.getAction())){app.stop(id);return true;}
                if(original.isCancelled())return false;
                if(PAUSE.equals(intent.getAction()))app.pause();else app.resumeDeviceExecution(id);
                return true;
            } catch(Exception unavailable) {
                if(owner&&STOP.equals(intent.getAction()))original.cancel();else if(owner&&PAUSE.equals(intent.getAction()))original.pause();
                app.error("原设备执行不可核对；没有恢复或新建授权，请在原任务检查");return false;
            }
        }
    }
}
