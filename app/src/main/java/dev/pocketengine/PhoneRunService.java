package dev.pocketengine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.phone.AndroidPhonePort;
import dev.pocketengine.phone.AndroidPhoneReadiness;
import dev.pocketengine.phone.PhoneReadiness;
import dev.pocketengine.phone.display.BackgroundPhonePort;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.PhoneNotificationControls;
import dev.pocketengine.phone.observation.AdaptivePhonePort;
import dev.pocketengine.runtime.AgentRunner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class PhoneRunService extends Service {
    public static final String START="dev.pocketengine.START",STOP="dev.pocketengine.STOP",CONTINUE="dev.pocketengine.CONTINUE";
    public static final String OWNER_DISABLE_ALL="dev.pocketengine.OWNER_DISABLE_ALL";
    /** Holds visibility for an already admitted capability run; never starts a runner. */
    public static final String OWNER_WORK_LIFETIME="dev.pocketengine.OWNER_WORK_LIFETIME";
    public static final String DEVICE_TOOLS="dev.pocketengine.DEVICE_TOOLS";
    public static final String DEVICE_GOAL="dev.pocketengine.DEVICE_GOAL",DEVICE_GOAL_CONTINUE="dev.pocketengine.DEVICE_GOAL_CONTINUE",DEVICE_GOAL_CLOSE_RESULT="dev.pocketengine.DEVICE_GOAL_CLOSE_RESULT";
    private volatile dev.pocketengine.features.devices.DeviceGoalSession deviceGoal;
    private volatile String pendingDeviceApproval;
    public static final String START_DEVICE_RECEIVER="dev.pocketengine.START_DEVICE_RECEIVER",STOP_DEVICE_RECEIVER="dev.pocketengine.STOP_DEVICE_RECEIVER";
    private static final String RECEIVER_CHANNEL="device_receiver";
    private static final int RECEIVER_NOTIFICATION=101;
    private volatile dev.pocketengine.features.devices.DeviceReceiverServiceResource receiverOnly;
    private final ExecutorService receiverIo=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"pocket-device-receiver-io");t.setDaemon(true);return t;});
    private int latestServiceStartId;

    private volatile dev.pocketengine.features.devices.DeviceToolsRuntime deviceTools;
    public static final String START_GATEWAY="dev.pocketengine.START_GATEWAY",STOP_GATEWAY="dev.pocketengine.STOP_GATEWAY";
    public static final String START_GATEWAY_PARENT="dev.pocketengine.START_GATEWAY_PARENT",STOP_GATEWAY_PARENT="dev.pocketengine.STOP_GATEWAY_PARENT";
    private volatile EngineApplication.GatewayParentRun gatewayParent;
    private volatile String lastGatewayProjection;
    private volatile dev.pocketengine.features.proxy.NativeHarnessGatewayBridge gatewayOnly;
    private static final String CHANNEL=PhoneReadiness.TASK_CHANNEL;
    private static final int NOTIFICATION=100;
    private static final int OWNER_WORK_NOTIFICATION=102;
    /** Main-thread-only exact native run identities. Task events cannot replace these tuples. */
    private final java.util.LinkedHashMap<String,OwnerWorkLifetime> ownerWorkLifetimes=new java.util.LinkedHashMap<>();
    private static final class OwnerWorkLifetime {
        final String task,run;final int epoch;JSONObject view;
        OwnerWorkLifetime(String task,String run,int epoch,JSONObject view){this.task=task;this.run=run;this.epoch=epoch;this.view=view;}
    }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService leaseSupervisor=Executors.newScheduledThreadPool(dev.pocketengine.core.ExecutionResources.MAX_BACKGROUND_PHONE_RUNS,r->{Thread t=new Thread(r,"pocket-task-lease");t.setDaemon(true);return t;});
    private volatile java.util.concurrent.ScheduledFuture<?> leaseWatch;
    private volatile BackgroundPhonePort taskBackgroundPort;
    private volatile boolean deadlineReached;
    private final Handler main=new Handler(Looper.getMainLooper());
    private EngineApplication app;
    private volatile String runningId;
    private volatile String runningRunId;
    private volatile boolean destroyed;
    private volatile boolean waitingForUser;
    private volatile PhonePort heldPort;
    private volatile long ownerWaitGeneration;
    private static final ExecutorService CLEANUP=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"pocket-display-cleanup");thread.setDaemon(true);return thread;});
    private RunControl ownerControl;
    private int runningEpoch,lastStartId;
    private String runningGoal;
    private ModelConfig runningConfig;
    private boolean deviceGoalPhysicalCreated;
    private String deviceGoalPhysicalSessionId;
    private PowerManager.WakeLock wakeLock;
    private final java.util.concurrent.ConcurrentHashMap<String,BackgroundPhoneRun> backgroundRuns=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger backgroundNotificationIds=new java.util.concurrent.atomic.AtomicInteger(1000);
    private final EngineApplication.Observer observer=event->{
        if(destroyed)return;
        if("task".equals(event.optString("type"))){JSONObject changed=event.optJSONObject("task");if(changed!=null&&ownerWorkLifetimes.containsKey(changed.optString("id")))settleForegroundOwnership(latestServiceStartId);}
        if("task".equals(event.optString("type"))){JSONObject changed=event.optJSONObject("task");BackgroundPhoneRun session=changed==null?null:backgroundRuns.get(changed.optString("id"));if(session!=null){session.onTaskEvent(changed);refreshBackgroundNotifications();}}
        if(runningId==null||!"task".equals(event.optString("type")))return;
        JSONObject task=event.optJSONObject("task");if(task==null||!runningId.equals(task.optString("id")))return;
        if(!task.optString("runId").equals(runningRunId)||task.optInt("coordinatorEpoch")!=runningEpoch)return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(task));
        if(waitingForUser&&"stopping".equals(task.optString("status"))){
            waitingForUser=false;ownerWaitGeneration++;String id=runningId,run=runningRunId;RunControl owner=ownerControl;int start=lastStartId,epoch=runningEpoch;
            worker.execute(()->{
                JSONObject cleanup=closeHeld(id,run,epoch),release=cleanup.optJSONObject("displayRelease");
                String summary=release==null?"已停止后续操作，原任务记录与已有结果保留":Boolean.TRUE.equals(release.opt("releaseConfirmed"))?"已停止后续操作；原独立屏已确认释放，声音复原以原回执为准":"已停止后续操作；原独立屏释放尚未确认，请核对原连接";
                finishAttempt(id,run,owner,new RunOutcome("cancelled",summary,withOriginalEvidence(id,run,epoch,cleanup)),start);
            });
        }
    };
    @Override public void onCreate(){super.onCreate();app=(EngineApplication)getApplication();NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel(CHANNEL,"手机任务",NotificationManager.IMPORTANCE_LOW));nm.createNotificationChannel(new NotificationChannel(RECEIVER_CHANNEL,"设备接收",NotificationManager.IMPORTANCE_LOW));app.addObserver(observer);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestServiceStartId=Math.max(latestServiceStartId,startId);
        if(intent==null){settleForegroundOwnership(startId);return START_NOT_STICKY;}
        if(OWNER_WORK_LIFETIME.equals(intent.getAction())){retainOwnerWorkLifetime(intent,startId);return START_NOT_STICKY;}
        if(OWNER_DISABLE_ALL.equals(intent.getAction())){
            try{if(app.executionAllowed()||intent.getLongExtra("ownerExecutionRevision",-1)!=app.tasks.ownerExecutionState().getLong("revision")){settleForegroundOwnership(startId);return START_NOT_STICKY;}}
            catch(Exception unknown){app.error("原总停用广播身份尚未确认；未选择其他任务");settleForegroundOwnership(startId);return START_NOT_STICKY;}
            var receiver=receiverOnly;if(receiver!=null)stopDeviceReceiver(receiver,startId);
            var parent=gatewayParent;if(parent!=null)stopGatewayParent(parent,startId);
            else{var gateway=gatewayOnly;if(gateway!=null)stopGatewayOnly(gateway,startId);}
            for(BackgroundPhoneRun session:new java.util.ArrayList<>(backgroundRuns.values()))session.ownerControl.cancel();
            RunControl original=ownerControl;String originalId=runningId,originalRun=runningRunId;int originalEpoch=runningEpoch;
            if(original!=null)original.cancel();
            if(waitingForUser&&original!=null&&original.matchesHostRun(originalId,originalRun,originalEpoch)){
                waitingForUser=false;ownerWaitGeneration++;
                worker.execute(()->{JSONObject cleanup=closeHeld(originalId,originalRun,originalEpoch);finishAttempt(originalId,originalRun,original,new RunOutcome("cancelled","总停用已停止原手机执行；资源释放以原回执为准",withOriginalEvidence(originalId,originalRun,originalEpoch,cleanup)),startId);});
            }
            settleForegroundOwnership(startId);return START_NOT_STICKY;
        }
        if(!app.executionAllowed()&&intent.getAction()!=null&&java.util.Set.of(START,CONTINUE,DEVICE_TOOLS,DEVICE_GOAL,DEVICE_GOAL_CONTINUE,START_DEVICE_RECEIVER,START_GATEWAY,START_GATEWAY_PARENT).contains(intent.getAction())){
            app.error("PocketWorld 已停用；旧通知或广播没有开始或继续任务");settleForegroundOwnership(startId);return START_NOT_STICKY;
        }
        if(START_DEVICE_RECEIVER.equals(intent.getAction())){startDeviceReceiver(intent,startId);return START_NOT_STICKY;}
        if(STOP_DEVICE_RECEIVER.equals(intent.getAction())){var resource=receiverOnly;if(resource!=null&&resource.resourceId().equals(intent.getStringExtra("deviceReceiverResourceId"))){try{resource.disableByOwner();}catch(Exception notPersisted){app.error("停止接收开关保存尚未确认；已禁止本次新请求并继续关闭原监听");}stopDeviceReceiver(resource,startId);}else settleForegroundOwnership(startId);return START_NOT_STICKY;}
        String id=intent.getStringExtra("taskId");
        BackgroundPhoneRun backgroundRun=id==null?null:backgroundRuns.get(id);
        if(backgroundRun!=null){
            if(PhoneNotificationControls.handles(intent.getAction())){PhoneNotificationControls.dispatch(app,intent,backgroundRun.runningId,backgroundRun.runningRunId,backgroundRun.runningEpoch,backgroundRun.ownerControl);return START_NOT_STICKY;}
            if(dev.pocketengine.features.devices.DeviceNotificationControls.handles(intent.getAction())){dev.pocketengine.features.devices.DeviceNotificationControls.dispatch(app,intent,backgroundRun.runningId,backgroundRun.runningRunId,backgroundRun.runningEpoch,backgroundRun.ownerControl);return START_NOT_STICKY;}
            if(CONTINUE.equals(intent.getAction())||DEVICE_GOAL_CONTINUE.equals(intent.getAction())){backgroundRun.continueOriginal(intent,startId);return START_NOT_STICKY;}
            if(DEVICE_GOAL_CLOSE_RESULT.equals(intent.getAction())){backgroundRun.closeReviewedDeviceResult(intent,startId);return START_NOT_STICKY;}
            if(START.equals(intent.getAction())||DEVICE_GOAL.equals(intent.getAction()))return START_NOT_STICKY;
        }
        if((START.equals(intent.getAction())||DEVICE_GOAL.equals(intent.getAction()))&&app.isBackgroundPhoneRun(id,app.control(id))){startBackgroundRun(intent,startId);return START_NOT_STICKY;}
        if(STOP_GATEWAY_PARENT.equals(intent.getAction())){var owner=gatewayParent;if(owner!=null&&owner.id().equals(intent.getStringExtra("gatewayParentResourceId")))stopGatewayParent(owner,startId);else if(runningId==null)settleForegroundOwnership(startId);return START_NOT_STICKY;}
        if(START_GATEWAY_PARENT.equals(intent.getAction())){startGatewayParent(intent,startId);return START_NOT_STICKY;}
        if(STOP_GATEWAY.equals(intent.getAction())){var bridge=gatewayOnly;if(bridge!=null&&bridge.leaseId().equals(intent.getStringExtra("gatewayLeaseId")))stopGatewayOnly(bridge,startId);else if(runningId==null)settleForegroundOwnership(startId);return START_NOT_STICKY;}
        if(dev.pocketengine.features.devices.DeviceNotificationControls.handles(intent.getAction())){dev.pocketengine.features.devices.DeviceNotificationControls.dispatch(app,intent,runningId,runningRunId,runningEpoch,ownerControl);if(runningId==null)settleForegroundOwnership(startId);return START_NOT_STICKY;}
        if(PhoneNotificationControls.handles(intent.getAction())){
            if(gatewayParent!=null||gatewayOnly!=null)return START_NOT_STICKY;
            PhoneNotificationControls.dispatch(app,intent,runningId,runningRunId,runningEpoch,ownerControl);
            if(runningId==null)settleForegroundOwnership(startId);return START_NOT_STICKY;
        }
        if(START_GATEWAY.equals(intent.getAction())){startGatewayOnly(intent,startId);return START_NOT_STICKY;}
        if(DEVICE_TOOLS.equals(intent.getAction())){startDeviceTools(id,startId);return START_NOT_STICKY;}
        if(DEVICE_GOAL.equals(intent.getAction())){startDeviceGoal(id,startId);return START_NOT_STICKY;}
        if(DEVICE_GOAL_CLOSE_RESULT.equals(intent.getAction())){
            var session=deviceGoal;if(session!=null&&id!=null&&id.equals(runningId)&&waitingForUser&&runningRunId.equals(intent.getStringExtra("deviceRunId"))&&runningEpoch==intent.getIntExtra("deviceEpoch",-1)&&session.originalControl()==ownerControl){
                try{JSONObject task=app.tasks.get(id,false),result=task.optJSONObject("deviceGoalResult");if(result==null||!result.optBoolean("closed")||!runningRunId.equals(result.optString("runId"))||runningEpoch!=result.optInt("executorEpoch"))throw new SecurityException("没有本原run已保存的结果关闭");RunControl finished=ownerControl;waitingForUser=false;ownerWaitGeneration++;pendingDeviceApproval=null;runningId=null;runningRunId=null;deviceGoal=null;if(leaseWatch!=null){leaseWatch.cancel(false);leaseWatch=null;}app.released(id,finished);ownerControl=null;app.pushTask(id);settleForegroundOwnership(startId);}catch(Exception changed){app.error("原结果关闭尚未完成，请核对原任务，不重启执行");}
            }else settleForegroundOwnership(startId);return START_NOT_STICKY;
        }
        if(DEVICE_GOAL_CONTINUE.equals(intent.getAction())){
            var session=deviceGoal;if(session!=null&&id!=null&&id.equals(runningId)&&runningRunId.equals(intent.getStringExtra("deviceRunId"))&&runningEpoch==intent.getIntExtra("deviceEpoch",-1)&&session.originalControl()==ownerControl&&!ownerControl.isCancelled()){
                String approval=intent.getStringExtra("deviceApprovalId");if(approval!=null&&!approval.isEmpty())pendingDeviceApproval=approval;
                if(waitingForUser&&!ownerControl.isPaused()){
                    if("main_screen".equals(session.admission().executionMode())&&heldPort==null){ownerControl.pause();app.setStatus(id,"awaiting_user","原设备主屏物理口已释放；请核对原任务，未创建替代执行",ownerControl);return START_NOT_STICKY;}
                    pendingDeviceApproval=null;waitingForUser=false;ownerWaitGeneration++;lastStartId=startId;worker.execute(()->runDeviceGoal(session,startId));
                }
            }return START_NOT_STICKY;
        }
        RunControl control=app.control(id);ModelConfig config=app.config(id);
        if(CONTINUE.equals(intent.getAction())){
            String expectedRun=intent.getStringExtra("phoneRunId");int expectedEpoch=intent.getIntExtra("phoneEpoch",-1);
            if(id!=null&&id.equals(runningId)&&expectedRun!=null&&expectedRun.equals(runningRunId)&&expectedEpoch==runningEpoch&&waitingForUser&&control!=null&&control==ownerControl&&control.matchesHostRun(id,expectedRun,expectedEpoch)&&!control.isCancelled()&&!control.isPaused()){
                if(heldPort==null){control.pause();app.setStatus(id,"awaiting_user","原手机物理会话已不可核对，请停止并检查原任务；未创建替代执行",control);return START_NOT_STICKY;}
                waitingForUser=false;ownerWaitGeneration++;lastStartId=startId;
                String originalGoal=runningGoal;ModelConfig originalConfig=runningConfig;
                worker.execute(()->run(id,expectedRun,expectedEpoch,originalGoal,originalConfig,control,startId,false));
            }
            return START_NOT_STICKY;
        }
        if(runningId!=null)return START_NOT_STICKY;
        if(control==null||config==null){settleForegroundOwnership(startId);return START_NOT_STICKY;}
        runningId=id;
        try{
            JSONObject task=app.tasks.get(id,false);String runId=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");runningRunId=runId;runningEpoch=epoch;runningGoal=task.optString("goal");runningConfig=config;ownerControl=control;lastStartId=startId;startLeaseWatch(id,runId,epoch,control);startTaskForeground(task);
            PhoneAccessibilityService phone=PhoneAccessibilityService.get();
            boolean background="background".equals(app.mode(id));
            if(!background){if(phone==null)throw new IllegalStateException("手机操作服务已断开，请重新授权");phone.showControls(id,task.optString("goal"),control);}
            main.postDelayed(()->{
                if(destroyed)return;
                if(control.isCancelled()){finishAttempt(id,runId,control,new RunOutcome("cancelled","已停止后续操作",new JSONObject()),startId);return;}
                boolean stopReady=false;
                try{JSONObject readiness=AndroidPhoneReadiness.snapshot(this,null);stopReady=readiness.getJSONObject(background?"taskNotifications":"stopControl").getBoolean("ready");}
                catch(Exception unknown){/* An unavailable native check is never treated as a visible stop route. */}
                if(!stopReady){finishAttempt(id,runId,control,new RunOutcome("awaiting_user","停止入口不可用，请开启任务通知或重新启用手机操作服务",new JSONObject()),startId);return;}
                worker.execute(()->run(id,runId,epoch,task.optString("goal"),config,control,startId,intent.getBooleanExtra("manualOnly",false)));
            },750);
        }catch(Exception ex){finish(id,new RunOutcome("failed",ex.getMessage()==null?"无法启动手机任务":ex.getMessage(),new JSONObject()),startId);}
        return START_NOT_STICKY;
    }
    private void startBackgroundRun(Intent intent,int startId){
        String id=intent.getStringExtra("taskId");RunControl original=app.control(id);
        String run=intent.getStringExtra("phoneRunId");int epoch=intent.getIntExtra("phoneEpoch",-1);
        if(original==null||run==null||!original.matchesHostRun(id,run,epoch)||!app.isBackgroundPhoneRun(id,original)){settleForegroundOwnership(startId);return;}
        BackgroundPhoneRun session=null;
        try{
            session=new BackgroundPhoneRun(this,app,id,original,backgroundNotificationIds.getAndIncrement(),startId,leaseSupervisor,CLEANUP);
            BackgroundPhoneRun previous=backgroundRuns.putIfAbsent(id,session);
            if(previous!=null){session.discardUnstarted();return;}
            refreshBackgroundNotifications();session.start(intent.getBooleanExtra("manualOnly",false));
        }catch(Exception unavailable){
            if(session!=null){backgroundRuns.remove(id,session);session.destroy();}
            else{original.cancel();try{app.tasks.endAttempt(id,run,"awaiting_user",safeMessage(unavailable),new JSONObject());}catch(Exception ignored){app.error("原后台启动结果保存尚未确认");}app.released(id,original);}
            app.pushTask(id);settleForegroundOwnership(startId);
        }
    }
    /** Exact owner callback; no completion may remove another run of the same task. */
    void backgroundFinished(BackgroundPhoneRun session,int startId){
        if(!backgroundRuns.remove(session.runningId,session))return;
        getSystemService(NotificationManager.class).cancel(session.notificationId);
        if(!destroyed)settleForegroundOwnership(startId);
    }
    private void refreshBackgroundNotifications(){
        if(destroyed||backgroundRuns.isEmpty())return;
        NotificationManager notifications=getSystemService(NotificationManager.class);boolean primary=runningId==null;
        for(BackgroundPhoneRun session:backgroundRuns.values())try{
            JSONObject task=app.tasks.get(session.runningId,false);
            if(task==null||!session.matches(task.optString("id"),task.optString("runId"),task.optInt("coordinatorEpoch"),app.control(session.runningId)))continue;
            Notification note=notification(task,session.ownerControl,false);
            if(primary){startForeground(NOTIFICATION,note);notifications.cancel(session.notificationId);primary=false;}
            else notifications.notify(session.notificationId,note);
        }catch(Exception unavailable){app.error("原后台任务通知暂不可用，请在任务页核对原执行");}
        var receiver=receiverOnly;if(receiver!=null)notifications.notify(RECEIVER_NOTIFICATION,receiverNotification(receiver));
        showOwnerWorkNotification(false);
    }
    /** Native original-task recovery only; no AgentRunner, PhonePort or phone.local grant. */
    private void startGatewayParent(Intent intent,int startId){
        if(runningId!=null||gatewayParent!=null||gatewayOnly!=null)return;EngineApplication.GatewayParentRun owner=app.originalGatewayParentRun(intent.getStringExtra("gatewayParentResourceId"));if(owner==null){settleForegroundOwnership(startId);return;}
        try{gatewayParent=owner;runningId=owner.task();runningRunId=owner.run();runningEpoch=owner.epoch();ownerControl=owner.control();lastStartId=startId;startTaskForeground(app.tasks.get(runningId,false));owner.serviceRegistered();watchGatewayParent(owner,startId);}catch(Exception unavailable){gatewayParent=null;runningId=null;runningRunId=null;ownerControl=null;CLEANUP.execute(()->{try{owner.close();}finally{main.post(()->settleForegroundOwnership(startId));}});settleForegroundOwnership(startId);}
    }
    private void watchGatewayParent(EngineApplication.GatewayParentRun owner,int startId){if(destroyed||gatewayParent!=owner)return;try{owner.checkCurrent();JSONObject projection=owner.snapshot();String state=projection.optString("state")+":"+projection.optInt("nativeCallsInProgress")+":"+projection.optInt("remainingModels");if(!state.equals(lastGatewayProjection)){lastGatewayProjection=state;app.refreshGatewayParentProjection(owner.task());getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(app.tasks.get(owner.task(),false)));}}catch(Exception stopped){stopGatewayParent(owner,startId);return;}main.postDelayed(()->watchGatewayParent(owner,startId),500);}
    private void stopGatewayParent(EngineApplication.GatewayParentRun owner,int startId){if(gatewayParent!=owner)return;gatewayParent=null;gatewayOnly=null;runningId=null;runningRunId=null;ownerControl=null;lastGatewayProjection=null;CLEANUP.execute(()->{try{owner.close();}finally{main.post(()->settleForegroundOwnership(startId));}});settleForegroundOwnership(startId);}
    private void startTaskForeground(JSONObject task){startForeground(NOTIFICATION,notification(task));var resource=receiverOnly;if(resource!=null)getSystemService(NotificationManager.class).notify(RECEIVER_NOTIFICATION,receiverNotification(resource));showOwnerWorkNotification(false);}
    private void retainOwnerWorkLifetime(Intent intent,int startId){
        String id=intent.getStringExtra("taskId"),run=intent.getStringExtra("runId");int epoch=intent.getIntExtra("epoch",-1);
        if(destroyed)return;
        try{
            if(id!=null&&!id.isEmpty()&&run!=null&&!run.isEmpty()&&epoch>0){
                JSONObject current=app.ownerWorkServiceTask(id,run,epoch);
                if(current!=null)ownerWorkLifetimes.put(id,new OwnerWorkLifetime(id,run,epoch,current));
            }
        }catch(Exception unavailable){app.error("原后台工作通知暂不可核对，请在应用中查看原工作");}
        settleForegroundOwnership(startId);
    }
    private void refreshOwnerWorkLifetimes(){
        for(var entries=ownerWorkLifetimes.entrySet().iterator();entries.hasNext();){
            OwnerWorkLifetime original=entries.next().getValue();JSONObject current=null;
            try{current=app.ownerWorkServiceTask(original.task,original.run,original.epoch);}
            catch(Exception unavailable){app.error("原后台工作通知暂不可核对，请在应用中查看原工作");}
            if(current==null)entries.remove();else original.view=current;
        }
    }
    private void showOwnerWorkNotification(boolean foreground){
        NotificationManager notifications=getSystemService(NotificationManager.class);
        if(ownerWorkLifetimes.isEmpty()){notifications.cancel(OWNER_WORK_NOTIFICATION);return;}
        Intent open=new Intent(this,dev.pocketengine.ui.ComposeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent view=PendingIntent.getActivity(this,OWNER_WORK_NOTIFICATION,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text=ownerWorkLifetimes.size()+" 项后台工作 · 打开应用查看进度";
        if(ownerWorkLifetimes.size()==1){JSONObject task=ownerWorkLifetimes.values().iterator().next().view;String status=switch(task.optString("status")){case "preparing","queued"->"准备中";case "paused"->"已暂停";case "pausing"->"正在暂停";case "stopping"->"正在停止";default->"进行中";};String goal=task.optString("goal");text=status+" · "+(goal.isEmpty()?"打开应用查看原工作":goal.substring(0,Math.min(120,goal.length())));}
        Notification notice=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("管家后台工作").setContentText(text).setContentIntent(view).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        if(foreground)startForeground(OWNER_WORK_NOTIFICATION,notice);else notifications.notify(OWNER_WORK_NOTIFICATION,notice);
    }
    /** Composable resource foreground ownership, never a task admission or release. Main thread only. */
    private void settleForegroundOwnership(int completedStartId){
        if(destroyed)return;
        refreshOwnerWorkLifetimes();
        if(!backgroundRuns.isEmpty()){refreshBackgroundNotifications();return;}
        if(runningId!=null||app.activeId()!=null){showOwnerWorkNotification(false);return;}
        if(!ownerWorkLifetimes.isEmpty()){showOwnerWorkNotification(true);NotificationManager notifications=getSystemService(NotificationManager.class);notifications.cancel(NOTIFICATION);var receiver=receiverOnly;if(receiver!=null)notifications.notify(RECEIVER_NOTIFICATION,receiverNotification(receiver));return;}
        getSystemService(NotificationManager.class).cancel(OWNER_WORK_NOTIFICATION);
        var resource=receiverOnly;if(resource!=null){startForeground(RECEIVER_NOTIFICATION,receiverNotification(resource));getSystemService(NotificationManager.class).cancel(NOTIFICATION);return;}
        stopForeground(STOP_FOREGROUND_REMOVE);getSystemService(NotificationManager.class).cancel(RECEIVER_NOTIFICATION);stopSelfResult(Math.max(latestServiceStartId,completedStartId));
    }
    private void startDeviceReceiver(Intent intent,int startId){
        var resource=app.originalDeviceReceiverResource(intent.getStringExtra("deviceReceiverResourceId"));
        if(resource==null){settleForegroundOwnership(startId);return;}
        if(receiverOnly!=null&&receiverOnly!=resource){app.error("原设备监听尚未释放，不叠新监听");return;}
        receiverOnly=resource;
        if(runningId==null&&backgroundRuns.isEmpty()&&ownerWorkLifetimes.isEmpty())startForeground(RECEIVER_NOTIFICATION,receiverNotification(resource));else getSystemService(NotificationManager.class).notify(RECEIVER_NOTIFICATION,receiverNotification(resource));
        NotificationManager notifications=getSystemService(NotificationManager.class);
        boolean visible=notifications.areNotificationsEnabled()&&(android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)&&notifications.getNotificationChannel(RECEIVER_CHANNEL).getImportance()!=NotificationManager.IMPORTANCE_NONE;
        if(!visible){resource.requestStop();stopDeviceReceiver(resource,startId);app.error("设备接收需要本机可见通知；未开启mTLS监听");return;}
        receiverIo.execute(()->{try{resource.start();main.post(()->watchDeviceReceiver(resource,startId));}catch(Exception unavailable){try{resource.close();}catch(Exception closeUnknown){}main.post(()->receiverStoppedOrUnknown(resource,startId));}});
    }
    private void watchDeviceReceiver(dev.pocketengine.features.devices.DeviceReceiverServiceResource resource,int startId){
        if(destroyed||receiverOnly!=resource)return;
        try{resource.requireOwnerEnabled();JSONObject actual=resource.snapshot();if(!Boolean.TRUE.equals(actual.opt("listening")))throw new SecurityException("原实际监听已不可用");getSystemService(NotificationManager.class).notify(RECEIVER_NOTIFICATION,receiverNotification(resource));}
        catch(Exception unavailable){resource.requestStop();stopDeviceReceiver(resource,startId);return;}
        main.postDelayed(()->watchDeviceReceiver(resource,startId),1000);
    }
    private void stopDeviceReceiver(dev.pocketengine.features.devices.DeviceReceiverServiceResource resource,int startId){
        if(receiverOnly!=resource)return;resource.requestStop();getSystemService(NotificationManager.class).notify(RECEIVER_NOTIFICATION,receiverNotification(resource));
        receiverIo.execute(()->{try{resource.close();}catch(Exception closeUnknown){/* Keep visible stop-unconfirmed state; no task state is changed. */}main.post(()->receiverStoppedOrUnknown(resource,startId));});
    }
    private void receiverStoppedOrUnknown(dev.pocketengine.features.devices.DeviceReceiverServiceResource resource,int startId){
        if(receiverOnly!=resource)return;
        if(resource.stopped()){receiverOnly=null;app.deviceReceiverReleased(resource);getSystemService(NotificationManager.class).cancel(RECEIVER_NOTIFICATION);settleForegroundOwnership(startId);}
        else{getSystemService(NotificationManager.class).notify(RECEIVER_NOTIFICATION,receiverNotification(resource));app.error("设备监听关闭尚未确认；新请求已禁止，原任务和回执不被冒作已停止");}
        app.features.sendState();
    }
    private Notification receiverNotification(dev.pocketengine.features.devices.DeviceReceiverServiceResource resource){
        Intent open=new Intent(this,dev.pocketengine.ui.ComposeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent view=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,PhoneRunService.class).setAction(STOP_DEVICE_RECEIVER).setData(android.net.Uri.parse("pocketengine-device-receiver://stop/"+resource.resourceId())).putExtra("deviceReceiverResourceId",resource.resourceId());PendingIntent disable=PendingIntent.getService(this,0,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text="新请求仅按本机授权接收 · 原任务可单独停止";try{JSONObject state=resource.snapshot();if(!state.optBoolean("acceptingNewRequests"))text=state.optString("state").equals("starting")?"正在核对本机配置并开启实际mTLS监听":"已禁止新请求 · 连接关闭状态请在应用核对";}catch(Exception unavailable){text="监听状态待核对 · 原任务状态独立保留";}
        return new Notification.Builder(this,RECEIVER_CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("PocketWorld设备接收").setContentText(text).setContentIntent(view).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null,"停止接收（原任务继续）",disable).build()).build();
    }
    /** The authenticated device goal uses only its frozen capability or exact GUI admission. */
    private void startDeviceGoal(String id,int startId){
        if(runningId!=null)return;var session=app.originalDeviceGoalSession(id);
        if(session==null||session.originalControl()!=app.control(id)){settleForegroundOwnership(startId);return;}
        RunControl original=session.originalControl();
        try{
            session.checkCurrent();JSONObject task=app.tasks.get(id,false);
            if(!original.matchesHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch")))throw new SecurityException("设备目标没有原run");
            runningId=id;runningRunId=task.getString("runId");runningEpoch=task.getInt("coordinatorEpoch");ownerControl=original;runningGoal=task.getString("goal");runningConfig=session.capturedModel();lastStartId=startId;deviceGoal=session;deviceGoalPhysicalCreated=false;deviceGoalPhysicalSessionId=null;
            startTaskForeground(task);
            if(!AndroidPhoneReadiness.snapshot(this,null).getJSONObject("taskNotifications").optBoolean("ready"))throw new SecurityException("设备目标需要本机可见任务通知和停止入口");
            if("main_screen".equals(session.admission().executionMode())){PhoneAccessibilityService phone=PhoneAccessibilityService.get();if(phone==null)throw new SecurityException("原主屏无障碍未连接");phone.showControls(id,runningGoal,original);}
            startLeaseWatch(id,runningRunId,runningEpoch,original);worker.execute(()->runDeviceGoal(session,startId));
        }catch(Exception unavailable){finishAttempt(id,runningRunId,original,new RunOutcome("awaiting_user","原设备目标本机模型或可见停止条件不可用，未借主屏START",new JSONObject()),startId);}
    }
    private void runDeviceGoal(dev.pocketengine.features.devices.DeviceGoalSession session,int startId){
        String id=session.taskId(),run=runningRunId;RunControl original=session.originalControl();
        PhonePort physical=heldPort;heldPort=null;
        try{
            session.checkCurrent();if(destroyed||session!=deviceGoal||original!=ownerControl||original!=app.control(id))throw new SecurityException("原设备目标宿主已变化");
            if(!AndroidPhoneReadiness.snapshot(this,null).getJSONObject("taskNotifications").optBoolean("ready"))throw new SecurityException("本机可见停止入口不可用");
            PhonePort scopedPhone=null;String mode=session.admission().executionMode();
            if("main_screen".equals(mode)){
                if(!(app.features.module("display") instanceof DisplayModule display)||!display.isConnected())throw new SecurityException("主屏应用用户身份核验不可用，未借其他显示");
                PhoneAccessibilityService connected=PhoneAccessibilityService.get();if(connected==null)throw new SecurityException("原主屏读取服务已断开");
                if(physical==null){
                    if(deviceGoalPhysicalCreated)throw new SecurityException("原主屏物理口已释放，不能创建替代执行");
                    physical=mainPhonePort();deviceGoalPhysicalCreated=true;deviceGoalPhysicalSessionId=connected.observationSessionId();
                }
                if(!(physical instanceof AndroidPhonePort)||!deviceGoalPhysicalSessionId.equals(connected.observationSessionId()))throw new SecurityException("原主屏连接已变化");
                if(!app.attachPort(id,physical,original))throw new SecurityException("原设备主屏未获准入");
                PhonePort scoped=new dev.pocketengine.features.devices.DeviceGoalPhonePort(app.phoneAgentSource(id,original,physical),session.admission(),original,
                        live->dev.pocketengine.phone.access.PhoneAccessBindings.primaryOwners(display,live));
                scopedPhone=new AdaptivePhonePort(scoped,new AdaptivePhonePort.Scope(id,run,runningEpoch,deviceGoalPhysicalSessionId,android.view.Display.DEFAULT_DISPLAY),
                        runningConfig.localCaptureAllowed,runningConfig.localOcrAllowed,runningConfig.sendScreenshots,runningConfig.supportsImageInput,android.os.SystemClock::uptimeMillis,app.localOcr);
            }else if(!"capabilities".equals(mode))throw new SecurityException("后台设备目标必须保留自己的原后台运行，不能借主屏宿主");
            PowerManager power=getSystemService(PowerManager.class);wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketEngine:DeviceGoal");wakeLock.setReferenceCounted(false);wakeLock.acquire(30_000L);
            app.setStatus(id,"running","原设备目标在本机已授权模型与准确能力范围执行",original);
            var port=app.deviceGoalCapabilities(session);var journal=app.tasks.journal(id,run,runningEpoch,original);
            session.runnerStarted();RunOutcome result=new AgentRunner(scopedPhone,port,dev.pocketengine.runtime.context.ContextBudgetPolicy.currentHostSource(),null,app.deviceOwnerInputs().runnerInputs(session)).run(id,runningGoal,runningConfig,original,event->app.record(id,event,original),journal);
            var finished=session.runnerFinished(result);if(deadlineReached||original.isCancelled()){finishAttempt(id,run,original,new RunOutcome("cancelled","原设备目标已停止后续派发；已有动作与下游未知效果不冒作已撤销",result.evidence),startId);return;}
            if(result.status.equals("needs_confirmation")){
                synchronized(this){if(destroyed||original.isCancelled()||app.control(id)!=original)throw new InterruptedException("原设备目标已停止");app.tasks.deviceTasks().recordGoalIdle(finished);original.pause();waitingForUser=true;ownerWaitGeneration++;pendingDeviceApproval=null;}
                app.pushTask(id);main.post(()->{if(waitingForUser&&original.isCancelled())app.stop(id,original);});return;
            }
            if(result.status.matches("awaiting_user|paused")&&!app.tasks.hasUnresolved(id)){
                // The AgentRunner has exited/closed its scoped surface, but its original SQL run,
                // control, captured model and allocation stay alive. Exact approval does not mint a run.
                synchronized(this){if(destroyed||original.isCancelled()||app.control(id)!=original)throw new InterruptedException("原设备目标已停止");original.pause();if(physical!=null)physical.clearReferences();heldPort=physical;waitingForUser=true;ownerWaitGeneration++;app.tasks.status(id,"awaiting_user",result.summary);app.recordDeviceOrdinaryQuestionWait(finished);}
                app.pushTask(id);main.post(()->{if(waitingForUser&&original.isCancelled())app.stop(id,original);});
                String decided=pendingDeviceApproval;if(decided!=null)main.post(()->{try{if(waitingForUser&&session==deviceGoal)app.resumeDeviceExecution(id,decided);}catch(Exception stale){pendingDeviceApproval=null;app.error("原审批后续接条件已变化，请核对原任务；未新建run");}});return;
            }
            finishAttempt(id,run,original,result,startId);
        }catch(Exception unavailable){session.runnerFailed();finishAttempt(id,run,original,new RunOutcome(original.isCancelled()?"cancelled":"awaiting_user","原设备目标已停止派发，请核对原模型、授权与操作记录；未新建执行",new JSONObject()),startId);}
        finally{if(physical instanceof AndroidPhonePort mainPort&&physical!=heldPort)mainPort.closeExtensions();releaseWakeLock();app.features.sendState();}
    }
    /** Explicit no-model phone-tools host. No START/GATEWAY_ONLY, ModelConfig or AgentRunner is used. */
    private void startDeviceTools(String id,int startId){
        if(runningId!=null)return;
        var session=app.originalDeviceToolsSession(id);
        if(session==null||session.originalControl()!=app.control(id)){settleForegroundOwnership(startId);return;}
        RunControl original=session.originalControl();
        try{
            var admission=session.firstAdmission();admission.checkCurrent();JSONObject task=app.tasks.get(id,false);
            if(task==null||!original.matchesHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch")))throw new SecurityException("设备工具服务没有原运行身份");
            runningId=id;runningRunId=task.getString("runId");runningEpoch=task.getInt("coordinatorEpoch");ownerControl=original;lastStartId=startId;
            runningGoal=task.getString("goal");runningConfig=null;startTaskForeground(task);startLeaseWatch(id,runningRunId,runningEpoch,original);
            if(admission.executionMode().equals("main_screen")){PhoneAccessibilityService phone=PhoneAccessibilityService.get();if(phone==null)throw new SecurityException("本机无障碍未连接");phone.showControls(id,runningGoal,original);}
            worker.execute(()->runDeviceTools(session,startId));
        }catch(Exception unavailable){finishAttempt(id,runningRunId,original,new RunOutcome("awaiting_user","原设备工具本机条件不可用，未运行模型或切换显示",new JSONObject()),startId);}
    }
    private void requireDeviceToolsReady(String mode,RunControl original)throws Exception{
        if(original!=ownerControl||original!=app.control(runningId)||original.isCancelled())throw new SecurityException("设备工具原控制已变化");
        var display=app.features.module("display") instanceof DisplayModule?(DisplayModule)app.features.module("display"):null;
        JSONObject health=AndroidPhoneReadiness.snapshot(this,display);
        if(!health.getJSONObject(mode.equals("background")?"backgroundExecution":"mainExecution").optBoolean("ready"))throw new SecurityException("本机屏幕、无障碍、会话或可见停止入口不可用；不会回落其他显示");
    }
    private AndroidPhonePort mainPhonePort(){
        return new AndroidPhonePort(this,live->dev.pocketengine.phone.access.PhoneAccessBindings.primaryOwners(
                app.features.module("display") instanceof DisplayModule display?display:null,live));
    }
    private void runDeviceTools(dev.pocketengine.features.devices.DeviceToolsSession session,int startId){
        String id=session.taskId(),run=runningRunId;RunControl original=session.originalControl();BackgroundPhonePort background=null;
        PhonePort port=null;
        JSONObject evidence=new JSONObject();
        try{
            PowerManager power=getSystemService(PowerManager.class);wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketEngine:DeviceTools");wakeLock.setReferenceCounted(false);wakeLock.acquire(30_000L);
            var admission=session.firstAdmission();
            if(admission.executionMode().equals("background")){
                if(!(app.features.module("display") instanceof DisplayModule))throw new SecurityException("本机后台屏helper未安装");
                DisplayModule display=(DisplayModule)app.features.module("display");display.prepare(original);background=new BackgroundPhonePort(display,original);port=background;
            }else port=mainPhonePort();
            if(background!=null)background.bindLifecycle(this,id,run,runningEpoch,original);
            taskBackgroundPort=background;if(!app.attachPort(id,port,original))throw new SecurityException("原PhonePort未获本机准入");
            requireDeviceToolsReady(admission.executionMode(),original);
            deviceTools=new dev.pocketengine.features.devices.DeviceToolsRuntime(admission.request().targetDeviceId(),admission,original,app.phoneAgentSource(id,original,port),app.tasks.deviceTasks(),this::requireDeviceToolsReady,app.deviceObservedImageSink());
            app.setStatus(id,"running","已连接本机手机工具 · 不调用模型 · 可随时停止",original);
            while(!destroyed&&!original.isCancelled()&&System.currentTimeMillis()<session.deadline()){
                if(original.isPaused()){deviceTools.invalidate();port.clearReferences();JSONObject paused=app.tasks.get(id,false);if("pausing".equals(paused.optString("status"))){app.tasks.status(id,"paused","原设备工具已暂停新派发；已有真实回执保留");app.pushTask(id);}Thread.sleep(100);continue;}
                original.check();var request=session.poll();if(request==null)continue;
                try{deviceTools.invoke(request);app.pushTask(id);if(app.tasks.hasUnresolved(id)){original.pause();deviceTools.invalidate();}}
                catch(Exception refused){app.tasks.deviceTasks().rejectPending(request,"native_device_pre_effect_refusal");app.pushTask(id);}
            }
            evidence.put("modelRequests",0).put("executionKind","DEVICE_TOOLS");
            if(deviceTools!=null){deviceTools.close();deviceTools=null;}
            if(background!=null){JSONObject released=background.closeSession();evidence.put("displayRelease",released);background=null;if(!released.optBoolean("releaseConfirmed"))throw new SecurityException("原后台屏释放尚未核实");}
            finishAttempt(id,run,original,new RunOutcome(original.isCancelled()?"cancelled":"paused",original.isCancelled()?"原设备工具已停止后续派发，已有结果保留":"原设备工具期限已到，原身份和实际回执保留",evidence),startId);
        }catch(Exception stopped){
            if(deviceTools!=null){deviceTools.close();deviceTools=null;}
            if(background!=null){try{evidence.put("displayRelease",background.closeSession());}catch(Exception ignored){}background=null;}
            finishAttempt(id,run,original,new RunOutcome(original.isCancelled()?"cancelled":"awaiting_user","原设备工具已停止派发；请核对实际操作与本机权限，未运行模型或回落显示",evidence),startId);
        }finally{if(port instanceof AndroidPhonePort mainPort)mainPort.closeExtensions();if(background!=null)background.closeSession();releaseWakeLock();app.features.sendState();}
    }
    /** Service resource ownership is separate from task execution. This branch never creates a
     * PhonePort, AgentRunner, phone.local lease, or a replacement RunControl/grant. */
    private void startGatewayOnly(Intent intent,int startId){
        if(gatewayOnly!=null||runningId!=null&&gatewayParent==null)return;dev.pocketengine.features.proxy.NativeHarnessGatewayBridge candidate=null;
        try{
            Object module=app.features.module("proxy");if(!(module instanceof dev.pocketengine.features.proxy.ProxyModule))throw new SecurityException("原网关宿主不可用");
            var bridge=((dev.pocketengine.features.proxy.ProxyModule)module).originalHarnessGateway(intent.getStringExtra("gatewayLeaseId"));candidate=bridge;
            if(bridge==null)throw new SecurityException("没有原生签发的当前任务网关租约");
            JSONObject state=bridge.snapshot();RunControl control=bridge.originalControl();String id=state.getString("taskId");
            if(gatewayParent!=null&&(!gatewayParent.active()||!id.equals(gatewayParent.task())||!state.getString("runId").equals(gatewayParent.run())||control!=gatewayParent.control()))throw new SecurityException("不能把别的scope接到原网关服务");
            if(!app.ownsNativeTaskControl(id,control)||control.isCancelled()||control.isPaused())throw new SecurityException("原任务控制器已变化");
            gatewayOnly=bridge;runningId=id;runningRunId=state.getString("runId");runningEpoch=state.getInt("epoch");ownerControl=control;lastStartId=startId;
            startTaskForeground(app.tasks.get(id,false));
            worker.execute(()->{try{bridge.start();main.post(()->watchGatewayOnly(bridge,startId));}catch(Exception stopped){main.post(()->stopGatewayOnly(bridge,startId));}});
        }catch(Exception unavailable){var bridge=candidate;if(bridge!=null)CLEANUP.execute(bridge::close);gatewayOnly=null;if(gatewayParent==null){runningId=null;runningRunId=null;settleForegroundOwnership(startId);}}
    }
    private void watchGatewayOnly(dev.pocketengine.features.proxy.NativeHarnessGatewayBridge bridge,int startId){
        if(destroyed||gatewayOnly!=bridge)return;
        if(!bridge.running()||bridge.originalControl().isCancelled()||!app.ownsNativeTaskControl(runningId,bridge.originalControl())){stopGatewayOnly(bridge,startId);return;}
        main.postDelayed(()->watchGatewayOnly(bridge,startId),500);
    }
    private void stopGatewayOnly(dev.pocketengine.features.proxy.NativeHarnessGatewayBridge bridge,int startId){
        if(gatewayOnly!=bridge)return;if(gatewayParent!=null){stopGatewayParent(gatewayParent,startId);return;}gatewayOnly=null;CLEANUP.execute(bridge::close);runningId=null;runningRunId=null;ownerControl=null;settleForegroundOwnership(startId);
    }
    private void run(String id,String runId,int epoch,String goal,ModelConfig config,RunControl control,int startId,boolean manualOnly){
        run(id,runId,epoch,goal,config,control,startId,manualOnly,null);
    }
    private void run(String id,String runId,int epoch,String goal,ModelConfig config,RunControl control,int startId,boolean manualOnly,dev.pocketengine.features.assistant.OwnerPhoneQuestions.Held requiredHold){
        final PhonePort[] continuationPort=new PhonePort[1];
        if(requiredHold!=null)try{
            control.dispatchAtPauseRevision(requiredHold.pauseRevision(),()->{
                if(destroyed||waitingForUser||heldPort!=requiredHold.physicalPort()||ownerControl!=control||!id.equals(runningId)||!runId.equals(runningRunId)||epoch!=runningEpoch
                        ||requiredHold.capturedModel()!=runningConfig||ownerWaitGeneration!=requiredHold.waitGeneration()+1)
                    throw new SecurityException("原问题续接的物理口或暂停状态已变化，未创建替代执行");
                continuationPort[0]=heldPort;heldPort=null;
            });
        }catch(Exception unavailable){ownerPhoneQuestionFailed(requiredHold,true);return;}
        BackgroundPhonePort backgroundPort=continuationPort[0] instanceof BackgroundPhonePort?(BackgroundPhonePort)continuationPort[0]:null;PhonePort port=continuationPort[0];
        try{
            PowerManager power=getSystemService(PowerManager.class);wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketEngine:PhoneRun");wakeLock.setReferenceCounted(false);wakeLock.acquire(30_000L);
            port=requiredHold==null?heldPort:continuationPort[0];
            if(requiredHold!=null&&port!=requiredHold.physicalPort())throw new SecurityException("原问题物理口已释放，不能回落新建");
            if(requiredHold==null)heldPort=null;
            if(port instanceof BackgroundPhonePort)backgroundPort=(BackgroundPhonePort)port;
            if(port==null&&"background".equals(app.mode(id))){
                app.setStatus(id,"preparing","正在检查独立后台屏，不会改为主屏操作",control);
                if(!(app.features.module("display") instanceof DisplayModule))throw new IllegalStateException("后台助手不可用");
                DisplayModule display=(DisplayModule)app.features.module("display");display.prepare(control);backgroundPort=new BackgroundPhonePort(display,control);port=backgroundPort;
            }else if(port==null)port=mainPhonePort();
            if(backgroundPort!=null)backgroundPort.bindLifecycle(this,id,runId,epoch,control);
            taskBackgroundPort=backgroundPort;
            if(!app.attachPort(id,port,control)){if(backgroundPort!=null)backgroundPort.closeSession();throw new InterruptedException("Task stopped before phone admission");}
            if(manualOnly){
                JSONObject frame=port.observe(true,control);holdForOwner(id,port,control,new RunOutcome("paused","独立屏已由主人接管，可操作后交回原任务",frame));backgroundPort=null;return;
            }
            String sessionId;int displayId;
            if(backgroundPort!=null){
                if(!backgroundPort.isConnected()||!(app.features.module("display") instanceof DisplayModule))throw new IllegalStateException("原后台屏会话已变化，请由主人重新连接核对");
                JSONObject identity=((DisplayModule)app.features.module("display")).session(control);sessionId=identity.getString("sessionId");displayId=identity.getInt("displayId");
            }else{
                PhoneAccessibilityService connected=PhoneAccessibilityService.get();if(connected==null)throw new IllegalStateException("主屏读取服务已断开，请重新连接");sessionId=connected.observationSessionId();displayId=android.view.Display.DEFAULT_DISPLAY;
            }
            // Only the runner uses the adapter. app.activePort/heldPort and all owner/cleanup paths keep the physical port.
            AdaptivePhonePort agentPort=new AdaptivePhonePort(app.phoneAgentSource(id,control,port),new AdaptivePhonePort.Scope(id,runId,epoch,sessionId,displayId),config.localCaptureAllowed,config.localOcrAllowed,config.sendScreenshots,config.usesAccount()?dev.pocketengine.runtime.ModelGateway.accountSupportsImageInput(config.accountModel):config.supportsImageInput,android.os.SystemClock::uptimeMillis,app.localOcr);
            app.retainPhonePractice(id,control,agentPort);
            // Declare this original port while a new task is still preparing. The
            // existing scope pins its tool set on dispatch; retained continuations
            // keep their original no-expansion fence.
            dev.pocketengine.core.CapabilityPort capabilities=app.capabilitiesForTask(id,control,agentPort);
            app.setStatus(id,"running","background".equals(app.mode(id))?"正在观察独立后台屏":"正在观察手机主屏",control);
            RunOutcome result=new AgentRunner(agentPort,capabilities,dev.pocketengine.runtime.context.ContextBudgetPolicy.currentHostSource(),
                    app.phoneOwnerProjection(id,control),app.phoneOwnerInputs(id,runId,epoch,control)).run(id,goal,config,control,event->app.record(id,event,control),
                    app.journalForTask(id,runId,epoch,control,config));
            app.snapshotPhonePractice(id,control,result.evidence);
            if(result.evidence.has("phonePractice"))result.evidence.put("practiceCleanup",dev.pocketengine.phone.diagnostics.PhonePracticeActivity.closeOriginal(control));
            if(deadlineReached)result=new RunOutcome("cancelled","已达到主人设定的原任务期限，停止后续派发；请检查已发生的动作与结果",result.evidence);
            else if(control.isCancelled()&&!"cancelled".equals(result.status))result=new RunOutcome("cancelled","已停止后续操作，请检查已发生的动作与结果",result.evidence);
            if("awaiting_user".equals(result.status)&&!control.isCancelled()){
                holdForOwner(id,port,control,result);backgroundPort=null;return;
            }
            if(backgroundPort!=null){JSONObject release=backgroundPort.closeSession();result.evidence.put("displayRelease",release);backgroundPort=null;
                if(result.status.equals("paused")&&control.conditionWait()!=null&&!release.optBoolean("releaseConfirmed"))result=new RunOutcome("awaiting_user","原任务等待运行条件，但后台屏释放尚未核实；请先核对原会话",result.evidence);
            }
            finishAttempt(id,runId,control,result,startId);
        }catch(RunControl.ConditionWaitException waiting){
            JSONObject evidence=new JSONObject();boolean cleanupConfirmed=true;if(backgroundPort!=null){try{JSONObject release=backgroundPort.closeSession();evidence.put("displayRelease",release);cleanupConfirmed=release.optBoolean("releaseConfirmed");}catch(Exception unknown){cleanupConfirmed=false;}backgroundPort=null;}
            finishAttempt(id,runId,control,new RunOutcome(control.isCancelled()?"cancelled":cleanupConfirmed?"paused":"awaiting_user",control.isCancelled()?"已停止后续操作":cleanupConfirmed?"运行条件暂不满足，已保留原任务与预算":"后台屏释放尚未核实，请先核对原会话",evidence),startId);
        }catch(Exception ex){JSONObject evidence=new JSONObject();if(backgroundPort!=null){try{evidence.put("displayRelease",backgroundPort.closeSession());}catch(Exception ignored){}backgroundPort=null;}finishAttempt(id,runId,control,new RunOutcome(control.isCancelled()?"cancelled":"failed",control.isCancelled()?"已停止后续操作":safeMessage(ex),evidence),startId);}
        finally{dev.pocketengine.phone.diagnostics.PhonePracticeActivity.requestCloseOriginal(control);app.releasePhonePractice(control);if(port instanceof AndroidPhonePort mainPort&&port!=heldPort)mainPort.closeExtensions();if(backgroundPort!=null)backgroundPort.closeSession();releaseWakeLock();app.features.sendState();}
    }
    private void holdForOwner(String id,PhonePort port,RunControl control,RunOutcome outcome)throws Exception{
        control.pause();port.clearReferences();
        if(port instanceof BackgroundPhonePort)try{outcome.evidence.put("audioRestore",((BackgroundPhonePort)port).restoreTaskAudio());}catch(Exception pending){outcome.evidence.put("audioRestore",new JSONObject().put("status","audio_recovery_pending").put("pending",true));}
        heldPort=port;waitingForUser=true;
        boolean unresolved=app.tasks.hasUnresolved(id);String status=unresolved?"reconciling":outcome.status;
        if(port instanceof BackgroundPhonePort){
            var original=control.hostRunIdentity();if(original==null||!original.taskId().equals(id))throw new SecurityException("original_phone_hold_required");
            Long deadline=null;try{deadline=app.tasks.taskExecutionDeadline(id,original.runId(),original.epoch());}catch(Exception unverified){/* Unknown is not an unlimited lease or successful cleanup. */}
            outcome.evidence.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.held(id,original.runId(),original.epoch(),unresolved?"reconciliation":outcome.status.equals("paused")?"manual_takeover":"awaiting_owner",deadline,outcome.evidence.optJSONObject("audioRestore")));
        }
        app.tasks.finish(id,status,outcome.summary,outcome.evidence);
        ++ownerWaitGeneration; // The same canonical task deadline remains supervised during owner handoff.
        try{app.retainOwnerPhoneQuestion(id,control,port,runningConfig,ownerWaitGeneration,ownerPhoneQuestionHost());}catch(Exception unavailable){/* Manual/approval/changed holds have no ordinary-answer authority. */}
        app.pushTask(id);
        app.publish(EngineApplication.json("type","owner_takeover","taskId",id,"manualAllowed",!unresolved,"status",status));
    }
    private dev.pocketengine.features.assistant.OwnerPhoneQuestions.PhysicalOwner ownerPhoneQuestionHost(){
        return new dev.pocketengine.features.assistant.OwnerPhoneQuestions.PhysicalOwner(){
            @Override public void requireHeld(dev.pocketengine.features.assistant.OwnerPhoneQuestions.Held expected)throws Exception{
                if(destroyed||deviceGoal!=null||deviceTools!=null||gatewayParent!=null||gatewayOnly!=null||!waitingForUser||heldPort!=expected.physicalPort()
                        ||ownerControl!=expected.originalControl()||!expected.taskId().equals(runningId)||!expected.runId().equals(runningRunId)||expected.epoch()!=runningEpoch
                        ||expected.waitGeneration()!=ownerWaitGeneration||expected.capturedModel()!=runningConfig)throw new SecurityException("原手机 service 持有会话已变化");
            }
            @Override public void requestContinuation(dev.pocketengine.features.assistant.OwnerPhoneQuestions.Held expected)throws Exception{
                requireHeld(expected);
                // Remain paused while queued. The original single worker rechecks the live
                // physical owner, lease, sources and scope before it consumes the answer.
                worker.execute(()->{
                    final boolean[] claimed={false};
                    try{
                        requireHeld(expected);String goal=runningGoal;int start=lastStartId;
                        app.continueOwnerPhoneQuestion(expected,()->{
                            requireHeld(expected);
                            expected.originalControl().dispatchAtPauseRevision(expected.pauseRevision(),()->{waitingForUser=false;++ownerWaitGeneration;claimed[0]=true;});
                        });
                        run(expected.taskId(),expected.runId(),expected.epoch(),goal,expected.capturedModel(),expected.originalControl(),start,false,expected);
                    }catch(Exception unavailable){ownerPhoneQuestionFailed(expected,claimed[0]);}
                });
            }
        };
    }
    private void ownerPhoneQuestionFailed(dev.pocketengine.features.assistant.OwnerPhoneQuestions.Held expected,boolean claimed){
        if(claimed&&!waitingForUser&&ownerWaitGeneration==expected.waitGeneration()+1&&(heldPort==null||heldPort==expected.physicalPort())&&ownerControl==expected.originalControl()
                &&expected.taskId().equals(runningId)&&expected.runId().equals(runningRunId)&&expected.epoch()==runningEpoch){
            // A later owner pause wins over this queued continuation. Keep that pause
            // and return the original physical hold to the ordinary Continue/Stop path.
            if(!ownerControl.isCancelled()&&!ownerControl.isPaused())ownerControl.pause();waitingForUser=true;++ownerWaitGeneration;
        }
        app.ownerPhoneQuestionContinuationFailed(expected);
    }
    private void startLeaseWatch(String id,String run,int epoch,RunControl original){
        if(leaseWatch!=null)leaseWatch.cancel(false);deadlineReached=false;
        leaseWatch=leaseSupervisor.scheduleWithFixedDelay(()->{
            if(destroyed||!id.equals(runningId)||!run.equals(runningRunId)||original!=ownerControl)return;
            try{if(original.isCancelled()||app.control(id)!=original)throw new SecurityException("原任务控制已变化");
                var goal=deviceGoal;long end;
                if(goal!=null&&goal.originalControl()==original&&id.equals(goal.taskId())){
                    // A real exited Runner with its original closed dispatch and
                    // paused control is waiting for owner result review, not execution.
                    if(original.isPaused())try{app.tasks.deviceTasks().reviewGoalResult(id,run,epoch);return;}catch(SecurityException notClosedYet){/* Ordinary question/pause still retains the original deadline. */}
                    end=app.tasks.deviceTasks().goalSupervisionDeadline(goal);
                }else end=app.tasks.taskExecutionDeadline(id,run,epoch);
                if(end!=Long.MAX_VALUE&&System.currentTimeMillis()>=end){deadlineReached=true;throw new SecurityException("任务期限已到");}
                BackgroundPhonePort background=taskBackgroundPort;if(background!=null&&background.isConnected())background.renewTaskLease(id,run,epoch,original,end);
                synchronized(this){if(wakeLock!=null&&!waitingForUser)wakeLock.acquire(30_000L);}
            }catch(Exception stopped){var completed=deviceGoal;if(completed!=null&&completed.originalControl()==original&&completed.ownerResultClosed())return;original.cancel();main.post(()->{var current=deviceGoal;if(id.equals(runningId)&&original==ownerControl&&waitingForUser&&(current==null||current.originalControl()!=original||!current.ownerResultClosed()))app.stop(id,original);});}
        },0,1,java.util.concurrent.TimeUnit.SECONDS);
    }
    private JSONObject closeHeld(String id,String run,int epoch){PhonePort port=heldPort;heldPort=null;return releaseOriginalPort(id,run,epoch,port);}
    private JSONObject releaseOriginalPort(String id,String run,int epoch,PhonePort port){
        JSONObject evidence=new JSONObject();
        try{
            if(port instanceof AndroidPhonePort mainPort){mainPort.closeExtensions();evidence.put("mainScreenExtensionsCleanupRequested",true);}
            if(port instanceof BackgroundPhonePort background){
                JSONObject release=background.closeSession();evidence.put("displayRelease",release);
                evidence.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.release(id,run,epoch,release));
            }
        }catch(Exception unknown){
            if(port instanceof BackgroundPhonePort)try{JSONObject release=EngineApplication.json("releaseConfirmed",false,"error","cleanup_unknown");evidence.put("displayRelease",release).put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.release(id,run,epoch,release));}catch(Exception invalidIdentity){/* No replacement run is assigned to an unidentifiable old resource. */}
        }
        return evidence;
    }
    private JSONObject closeHeldAsync(){
        PhonePort port=heldPort;heldPort=null;String id=runningId,run=runningRunId;int epoch=runningEpoch;JSONObject pending=new JSONObject();
        if(port==null){
            // An executing worker still owns this port and performs its existing finally/catch cleanup.
            // Record pending without starting a second release operation.
            if(taskBackgroundPort!=null)try{pending.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.pending(id,run,epoch));}catch(Exception unavailable){/* Unknown remains unconfirmed. */}
            return pending;
        }
        if(port instanceof BackgroundPhonePort background){background.noteReleasePending();try{pending.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.pending(id,run,epoch));}catch(Exception unavailable){/* The original resource stays behind its existing release barrier. */}}
        CLEANUP.execute(()->{
            JSONObject evidence=releaseOriginalPort(id,run,epoch,port);
            if(evidence.has("phoneResources"))try{
                // Append only the captured run's receipt. A later run is never finished, released or relabelled here.
                app.tasks.event(id,EngineApplication.json("type","phone_resource_release","runId",run,"coordinatorEpoch",epoch,"phoneResources",evidence.getJSONObject("phoneResources"),"displayRelease",evidence.optJSONObject("displayRelease")));
                app.pushTask(id);
            }catch(Exception unavailable){app.error("原后台屏清理回执保存尚未确认，请到连接中心核对；没有重发清理");}
        });
        return pending;
    }
    private JSONObject withOriginalEvidence(String id,String run,int epoch,JSONObject addition){
        try{JSONObject task=app.tasks.get(id,false),old=task==null?null:task.optJSONObject("evidence");
            if(task==null||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))return addition;
            JSONObject merged=old==null?new JSONObject():new JSONObject(old.toString());for(java.util.Iterator<String> keys=addition.keys();keys.hasNext();){String key=keys.next();merged.put(key,addition.get(key));}return merged;
        }catch(Exception unavailable){return addition;}
    }
    private void finish(String id,RunOutcome outcome,int startId){
        finishAttempt(id,runningRunId,ownerControl,outcome,startId);
    }
    private void finishAttempt(String id,String runId,RunControl owner,RunOutcome outcome,int startId){
        if(owner!=null)try{app.snapshotPhonePractice(id,owner,outcome.evidence);}catch(Exception unavailable){/* Original task result is retained even if diagnostic evidence is unavailable. */}
        if(owner!=null&&outcome.evidence.optJSONObject("displayRelease")!=null)try{
            var original=owner.hostRunIdentity();if(original!=null&&original.taskId().equals(id)&&original.runId().equals(runId))outcome.evidence.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.release(id,runId,original.epoch(),outcome.evidence.getJSONObject("displayRelease")));
        }catch(Exception unavailable){/* Preserve the actual displayRelease even when the presentation identity is unavailable. */}
        try{if(runId!=null&&!runId.isEmpty()){if(outcome.status.equals("paused")&&owner!=null&&owner.conditionWait()!=null&&!owner.isCancelled())app.tasks.endAutomationConditionWait(id,runId,owner,outcome.summary,outcome.evidence);else app.tasks.endAttempt(id,runId,outcome.status,outcome.summary,outcome.evidence);}else if(owner==app.control(id))app.tasks.finish(id,outcome.status,outcome.summary,outcome.evidence);}catch(Exception ex){app.setStatus(id,"failed","无法保存结果，请检查可用空间",owner);}
        // Admission and service ownership change together on main, before another start can run.
        main.post(()->{
            if(owner==null||owner!=app.control(id))return;
            if(id.equals(runningId)){runningId=null;runningRunId=null;taskBackgroundPort=null;deviceGoal=null;pendingDeviceApproval=null;if(leaseWatch!=null){leaseWatch.cancel(false);leaseWatch=null;}}
            app.released(id,owner);app.pushTask(id);
            String active=app.activeId();
            if(!destroyed&&(active==null||id.equals(active))){PhoneAccessibilityService phone=PhoneAccessibilityService.get();if(phone!=null)phone.hideControls(owner);}
            if(!destroyed)settleForegroundOwnership(startId);
        });
    }
    private Notification notification(JSONObject t){return notification(t,ownerControl,true);}
    private Notification notification(JSONObject t,RunControl noticeControl,boolean gatewayAware){
        EngineApplication.GatewayParentRun gatewayParent=gatewayAware?this.gatewayParent:null;
        dev.pocketengine.features.proxy.NativeHarnessGatewayBridge gatewayOnly=gatewayAware?this.gatewayOnly:null;
        RunControl ownerControl=noticeControl;
        String id=t==null?"":t.optString("id");
        if(t!=null&&"phone".equals(t.optString("kind"))&&"awaiting_user".equals(t.optString("status"))){
            JSONObject fallback=EngineApplication.json("id",id,"kind","phone","executionMode",t.optString("executionMode"),"status",t.optString("status"),"runId",t.optString("runId"),"coordinatorEpoch",t.optInt("coordinatorEpoch"),"goal","原手机任务","summary","原问题暂不可核对，请打开原任务；你仍可停止原任务。");
            try{JSONObject current=app.tasks.getForUi(id,false);t=current==null?fallback:current;}catch(Exception unavailable){t=fallback;}
        }
        Intent openIntent=new Intent(this,dev.pocketengine.ui.ComposeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        boolean identified=t!=null&&!id.isEmpty()&&!t.optString("runId").isEmpty()&&t.optInt("coordinatorEpoch")>0;
        try{if(identified&&!dev.pocketengine.store.TaskStoreDeviceTasks.deviceTask(t))openIntent=PhoneNotificationControls.open(this,t);}catch(Exception invalid){identified=false;}
        PendingIntent open=PendingIntent.getActivity(this,0,openIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean gatewayMode=gatewayParent!=null||gatewayOnly!=null;String text=gatewayParent!=null&&gatewayOnly==null?"原网关会话已登记，等待本轮配置与确认 · 不执行Agent或工具":gatewayOnly!=null?"仅模型推理 · 不执行手机或工具 · 可随时停止":t==null?"正在准备":t.optString("summary");if(gatewayParent!=null)try{String state=app.tasks.gatewayParentSessionForUi(gatewayParent.task()).optString("state");text=switch(state){case "REQUEST_IN_PROGRESS"->"正在处理原模型请求 · 不执行Agent或工具";case "REQUEST_CLAIMED"->"原模型请求已登记，等待确定回执";case "READY_WAITING","REQUEST_RECEIPT_RECORDED"->"网关会话已就绪，等待远端模型请求";case "PREPARING_SERVICE"->"正在登记前台服务，尚不可派发";case "WAITING_CONFIGURATION"->"等待本轮准确配置与主人确认";default->"本轮网关停止、到期或结果待核对";};}catch(Exception unknown){text="网关状态待核对，不推定正在执行";}if(text.isEmpty())text="运行中 · 可随时停止";
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle(gatewayMode?"任务模型网关":t==null?"手机任务":t.optString("goal","手机任务")).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
        if(gatewayParent!=null){var owner=gatewayParent;Intent stopIntent=new Intent(this,PhoneRunService.class).setAction(STOP_GATEWAY_PARENT).setData(android.net.Uri.parse("pocketengine-gateway-parent://stop/"+owner.id())).putExtra("gatewayParentResourceId",owner.id());builder.addAction(new Notification.Action.Builder(null,"停止本轮网关",PendingIntent.getService(this,owner.id().hashCode(),stopIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());return builder.build();}
        if(gatewayOnly!=null){
            var bridge=gatewayOnly;Intent stopIntent=new Intent(this,PhoneRunService.class).setAction(STOP_GATEWAY).setData(android.net.Uri.parse("pocketengine-gateway://stop/"+bridge.leaseId())).putExtra("gatewayLeaseId",bridge.leaseId());
            PendingIntent stop=PendingIntent.getService(this,0,stopIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);builder.addAction(new Notification.Action.Builder(null,"停止模型网关",stop).build());return builder.build();
        }
        if(identified&&dev.pocketengine.store.TaskStoreDeviceTasks.deviceTask(t))try{
            PendingIntent stop=PendingIntent.getService(this,0,dev.pocketengine.features.devices.DeviceNotificationControls.command(this,dev.pocketengine.features.devices.DeviceNotificationControls.STOP,t),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);builder.addAction(new Notification.Action.Builder(null,"停止设备执行",stop).build());
            RunControl owner=ownerControl;if(owner!=null&&owner==app.control(id)&&!owner.isCancelled()&&owner.matchesHostRun(id,t.getString("runId"),t.getInt("coordinatorEpoch"))){String action=owner.isPaused()?dev.pocketengine.features.devices.DeviceNotificationControls.RESUME:dev.pocketengine.features.devices.DeviceNotificationControls.PAUSE;PendingIntent control=PendingIntent.getService(this,0,dev.pocketengine.features.devices.DeviceNotificationControls.command(this,action,t),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);builder.addAction(new Notification.Action.Builder(null,owner.isPaused()?"继续原设备任务":"暂停",control).build());}
            return builder.build();
        }catch(Exception unavailable){app.error("原设备通知控制暂不可用，请打开原任务核对");}
        if(identified)try{
            PendingIntent stop=PendingIntent.getService(this,0,PhoneNotificationControls.command(this,STOP,t),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,"停止",stop).build());
            RunControl owner=ownerControl;
            if(owner!=null&&owner==app.control(id)&&!owner.isCancelled()&&owner.matchesHostRun(id,t.getString("runId"),t.getInt("coordinatorEpoch"))
                    &&t.optString("status").matches("preparing|running|pausing|paused|awaiting_user|reconciling")){
                if(owner.isPaused()&&app.tasks.hasUnresolved(id))builder.addAction(new Notification.Action.Builder(null,"核对原动作",open).build());
                else{String action=owner.isPaused()?PhoneNotificationControls.RESUME:PhoneNotificationControls.PAUSE;
                    PendingIntent control=PendingIntent.getService(this,0,PhoneNotificationControls.command(this,action,t),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    builder.addAction(new Notification.Action.Builder(null,owner.isPaused()?"继续原任务":"暂停",control).build());}
            }
        }catch(Exception invalid){app.error("原任务通知控制暂不可用，请打开应用核对");}
        try{dev.pocketengine.features.assistant.notifications.OwnerPhoneQuestionNotification.add(builder,this,t);}catch(Exception unavailable){/* Original open/stop/pause routes remain usable. */}
        return builder.build();
    }
    private static String safeMessage(Exception e){String m=e.getMessage();return m==null?"任务执行异常，请查看步骤后重试":m.length()>250?m.substring(0,250):m;}
    private synchronized void releaseWakeLock(){if(wakeLock!=null){if(wakeLock.isHeld())wakeLock.release();wakeLock=null;}}
    @Override public void onDestroy(){synchronized(this){destroyed=true;}ownerWorkLifetimes.clear();getSystemService(NotificationManager.class).cancel(OWNER_WORK_NOTIFICATION);for(BackgroundPhoneRun session:new java.util.ArrayList<>(backgroundRuns.values())){session.destroy();getSystemService(NotificationManager.class).cancel(session.notificationId);}backgroundRuns.clear();var receiver=receiverOnly;receiverOnly=null;if(receiver!=null){receiver.requestStop();CLEANUP.execute(()->{try{receiver.close();}catch(Exception ignored){}app.deviceReceiverReleased(receiver);});}receiverIo.shutdown();getSystemService(NotificationManager.class).cancel(RECEIVER_NOTIFICATION);if(gatewayParent!=null){destroyed=true;var owner=gatewayParent;gatewayParent=null;gatewayOnly=null;main.removeCallbacksAndMessages(null);CLEANUP.execute(owner::close);runningId=null;runningRunId=null;app.removeObserver(observer);worker.shutdownNow();leaseSupervisor.shutdownNow();super.onDestroy();return;}if(gatewayOnly!=null){destroyed=true;var bridge=gatewayOnly;gatewayOnly=null;main.removeCallbacksAndMessages(null);CLEANUP.execute(bridge::close);runningId=null;runningRunId=null;app.removeObserver(observer);worker.shutdownNow();leaseSupervisor.shutdownNow();super.onDestroy();return;}destroyed=true;waitingForUser=false;ownerWaitGeneration++;main.removeCallbacksAndMessages(null);JSONObject cleanup=closeHeldAsync();if(runningId!=null){String id=runningId;RunControl owner=ownerControl;if(owner!=null){try{app.stopOriginalPhoneRun(id,runningRunId,runningEpoch);}catch(Exception stale){owner.cancel();}}try{if(runningRunId!=null&&!runningRunId.isEmpty())app.tasks.endAttempt(id,runningRunId,"cancelled","执行服务已结束；原资源清理以回执为准，目标与上下文保留",withOriginalEvidence(id,runningRunId,runningEpoch,cleanup));else app.setStatus(id,"interrupted","执行服务已结束，请检查已有结果",owner);}catch(Exception failure){app.error("任务中断状态保存失败，请核对已有结果");}app.released(id,owner);}app.removeObserver(observer);worker.shutdownNow();leaseSupervisor.shutdownNow();releaseWakeLock();PhoneAccessibilityService phone=PhoneAccessibilityService.get();if(phone!=null)phone.hideControls(ownerControl);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
