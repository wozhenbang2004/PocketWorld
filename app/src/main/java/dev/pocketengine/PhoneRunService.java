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
    private static final String CHANNEL=PhoneReadiness.TASK_CHANNEL;
    private static final int NOTIFICATION=100;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
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
    private PowerManager.WakeLock wakeLock;
    private final EngineApplication.Observer observer=event->{
        if(runningId==null||!"task".equals(event.optString("type")))return;
        JSONObject task=event.optJSONObject("task");if(task==null||!runningId.equals(task.optString("id")))return;
        if(!task.optString("runId").equals(runningRunId)||task.optInt("coordinatorEpoch")!=runningEpoch)return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(task));
        if(waitingForUser&&"stopping".equals(task.optString("status"))){
            waitingForUser=false;ownerWaitGeneration++;String id=runningId,run=runningRunId;RunControl owner=ownerControl;int start=lastStartId;
            worker.execute(()->{closeHeld();finishAttempt(id,run,owner,new RunOutcome("cancelled","已停止并释放接管会话",new JSONObject()),start);});
        }
    };
    @Override public void onCreate(){super.onCreate();app=(EngineApplication)getApplication();NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel(CHANNEL,"手机任务",NotificationManager.IMPORTANCE_LOW));app.addObserver(observer);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf(startId);return START_NOT_STICKY;}
        String id=intent.getStringExtra("taskId");
        if(PhoneNotificationControls.handles(intent.getAction())){
            PhoneNotificationControls.dispatch(app,intent,runningId,runningRunId,runningEpoch,ownerControl);
            if(runningId==null)stopSelf(startId);return START_NOT_STICKY;
        }
        RunControl control=app.control(id);ModelConfig config=app.config(id);
        if(CONTINUE.equals(intent.getAction())){
            if(id!=null&&id.equals(runningId)&&waitingForUser&&control==ownerControl&&!control.isCancelled()&&!control.isPaused()){
                waitingForUser=false;ownerWaitGeneration++;lastStartId=startId;
                worker.execute(()->run(id,runningRunId,runningEpoch,runningGoal,runningConfig,control,startId,false));
            }
            return START_NOT_STICKY;
        }
        if(runningId!=null)return START_NOT_STICKY;
        if(control==null||config==null){stopSelfResult(startId);return START_NOT_STICKY;}
        runningId=id;
        try{
            JSONObject task=app.tasks.get(id,false);String runId=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");runningRunId=runId;runningEpoch=epoch;runningGoal=task.optString("goal");runningConfig=config;ownerControl=control;lastStartId=startId;startForeground(NOTIFICATION,notification(task));
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
    private void run(String id,String runId,int epoch,String goal,ModelConfig config,RunControl control,int startId,boolean manualOnly){
        AtomicBoolean deadline=new AtomicBoolean();Runnable timeout=()->{deadline.set(true);control.cancel();};main.postDelayed(timeout,10*60*1000L);
        BackgroundPhonePort backgroundPort=null;
        try{
            PowerManager power=getSystemService(PowerManager.class);wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketEngine:PhoneRun");wakeLock.acquire(11*60*1000L);
            PhonePort port=heldPort;heldPort=null;
            if(port instanceof BackgroundPhonePort)backgroundPort=(BackgroundPhonePort)port;
            if(port==null&&"background".equals(app.mode(id))){
                app.setStatus(id,"preparing","正在检查独立后台屏，不会改为主屏操作",control);
                if(!(app.features.module("display") instanceof DisplayModule))throw new IllegalStateException("后台助手不可用");
                DisplayModule display=(DisplayModule)app.features.module("display");display.prepare(control);backgroundPort=new BackgroundPhonePort(display);port=backgroundPort;
            }else if(port==null)port=new AndroidPhonePort(this);
            if(!app.attachPort(id,port,control)){if(backgroundPort!=null)backgroundPort.closeSession();throw new InterruptedException("Task stopped before phone admission");}
            if(manualOnly){
                JSONObject frame=port.observe(true,control);holdForOwner(id,port,control,new RunOutcome("paused","独立屏已由主人接管，可操作后交回原任务",frame));backgroundPort=null;return;
            }
            app.setStatus(id,"running","background".equals(app.mode(id))?"正在观察独立后台屏":"正在观察手机主屏",control);
            String sessionId;int displayId;
            if(backgroundPort!=null){
                if(!backgroundPort.isConnected()||!(app.features.module("display") instanceof DisplayModule))throw new IllegalStateException("原后台屏会话已变化，请由主人重新连接核对");
                JSONObject identity=((DisplayModule)app.features.module("display")).session();sessionId=identity.getString("sessionId");displayId=identity.getInt("displayId");
            }else{
                PhoneAccessibilityService connected=PhoneAccessibilityService.get();if(connected==null)throw new IllegalStateException("主屏读取服务已断开，请重新连接");sessionId=connected.observationSessionId();displayId=android.view.Display.DEFAULT_DISPLAY;
            }
            // Only the runner uses the adapter. app.activePort/heldPort and all owner/cleanup paths keep the physical port.
            PhonePort agentPort=new AdaptivePhonePort(port,new AdaptivePhonePort.Scope(id,runId,epoch,sessionId,displayId),config.sendScreenshots,config.usesAccount()?dev.pocketengine.runtime.ModelGateway.accountSupportsImageInput(config.accountModel):config.supportsImageInput,android.os.SystemClock::uptimeMillis,app.localOcr);
            RunOutcome result=new AgentRunner(agentPort,app.capabilitiesForTask(id,control,agentPort),dev.pocketengine.runtime.context.ContextBudgetPolicy.currentHostSource(),
                    app.phoneOwnerProjection(id,control),app.phoneOwnerInputs(id,runId,epoch,control)).run(id,goal,config,control,event->app.record(id,event,control),
                    app.journalForTask(id,runId,epoch,control,config));
            if(deadline.get())result=new RunOutcome("cancelled","已达到本次任务的10分钟时限，停止后续操作；请检查已有结果",result.evidence);
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
        finally{if(backgroundPort!=null)backgroundPort.closeSession();main.removeCallbacks(timeout);releaseWakeLock();app.features.sendState();}
    }
    private void holdForOwner(String id,PhonePort port,RunControl control,RunOutcome outcome)throws Exception{
        control.pause();port.clearReferences();heldPort=port;waitingForUser=true;
        boolean unresolved=app.tasks.hasUnresolved(id);String status=unresolved?"reconciling":outcome.status;
        app.tasks.finish(id,status,outcome.summary,outcome.evidence);app.pushTask(id);
        app.publish(EngineApplication.json("type","owner_takeover","taskId",id,"manualAllowed",!unresolved,"status",status));
        long waitGeneration=++ownerWaitGeneration;
        main.postDelayed(()->{if(waitingForUser&&waitGeneration==ownerWaitGeneration&&id.equals(runningId))app.stop(id);},5*60_000L);
    }
    private void closeHeld(){PhonePort port=heldPort;heldPort=null;if(port instanceof BackgroundPhonePort)((BackgroundPhonePort)port).closeSession();}
    private void closeHeldAsync(){PhonePort port=heldPort;heldPort=null;if(port instanceof BackgroundPhonePort){BackgroundPhonePort background=(BackgroundPhonePort)port;background.noteReleasePending();CLEANUP.execute(background::closeSession);}}
    private void finish(String id,RunOutcome outcome,int startId){
        finishAttempt(id,runningRunId,app.control(id),outcome,startId);
    }
    private void finishAttempt(String id,String runId,RunControl owner,RunOutcome outcome,int startId){
        try{if(runId!=null&&!runId.isEmpty()){if(outcome.status.equals("paused")&&owner!=null&&owner.conditionWait()!=null&&!owner.isCancelled())app.tasks.endAutomationConditionWait(id,runId,owner,outcome.summary,outcome.evidence);else app.tasks.endAttempt(id,runId,outcome.status,outcome.summary,outcome.evidence);}else if(owner==app.control(id))app.tasks.finish(id,outcome.status,outcome.summary,outcome.evidence);}catch(Exception ex){app.setStatus(id,"failed","无法保存结果，请检查可用空间",owner);}
        // Admission and service ownership change together on main, before another start can run.
        main.post(()->{
            if(owner==null||owner!=app.control(id))return;
            if(id.equals(runningId)){runningId=null;runningRunId=null;}
            app.released(id,owner);app.pushTask(id);
            String active=app.activeId();
            if(!destroyed&&(active==null||id.equals(active))){PhoneAccessibilityService phone=PhoneAccessibilityService.get();if(phone!=null)phone.hideControls();stopForeground(STOP_FOREGROUND_REMOVE);}
            if(!destroyed)stopSelfResult(startId);
        });
    }
    private Notification notification(JSONObject t){
        String id=t==null?"":t.optString("id");
        Intent openIntent=new Intent(this,dev.pocketengine.ui.ComposeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        boolean identified=t!=null&&!id.isEmpty()&&!t.optString("runId").isEmpty()&&t.optInt("coordinatorEpoch")>0;
        try{if(identified)openIntent=PhoneNotificationControls.open(this,t);}catch(Exception invalid){identified=false;}
        PendingIntent open=PendingIntent.getActivity(this,0,openIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text=t==null?"正在准备":t.optString("summary");if(text.isEmpty())text="运行中 · 可随时停止";
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle(t==null?"手机任务":t.optString("goal","手机任务")).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
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
        return builder.build();
    }
    private static String safeMessage(Exception e){String m=e.getMessage();return m==null?"任务执行异常，请查看步骤后重试":m.length()>250?m.substring(0,250):m;}
    private synchronized void releaseWakeLock(){if(wakeLock!=null){if(wakeLock.isHeld())wakeLock.release();wakeLock=null;}}
    @Override public void onDestroy(){destroyed=true;waitingForUser=false;ownerWaitGeneration++;main.removeCallbacksAndMessages(null);closeHeldAsync();if(runningId!=null){String id=runningId;RunControl owner=app.control(id);app.stop(id);try{if(runningRunId!=null&&!runningRunId.isEmpty())app.tasks.endAttempt(id,runningRunId,"cancelled","执行服务已结束，目标与上下文已保留，可核对后继续原任务",new JSONObject());else app.setStatus(id,"interrupted","执行服务已结束，请检查已有结果",owner);}catch(Exception failure){app.error("任务中断状态保存失败，请核对已有结果");}app.released(id,owner);}app.removeObserver(observer);worker.shutdownNow();releaseWakeLock();PhoneAccessibilityService phone=PhoneAccessibilityService.get();if(phone!=null)phone.hideControls();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
