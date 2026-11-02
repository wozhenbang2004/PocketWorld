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
    public static final String START_GATEWAY="dev.pocketengine.START_GATEWAY",STOP_GATEWAY="dev.pocketengine.STOP_GATEWAY";
    public static final String START_GATEWAY_PARENT="dev.pocketengine.START_GATEWAY_PARENT",STOP_GATEWAY_PARENT="dev.pocketengine.STOP_GATEWAY_PARENT";
    private volatile EngineApplication.GatewayParentRun gatewayParent;
    private volatile String lastGatewayProjection;
    private volatile dev.pocketengine.features.proxy.NativeHarnessGatewayBridge gatewayOnly;
    private static final String CHANNEL=PhoneReadiness.TASK_CHANNEL;
    private static final int NOTIFICATION=100;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService leaseSupervisor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"pocket-task-lease");t.setDaemon(true);return t;});
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
        if(STOP_GATEWAY_PARENT.equals(intent.getAction())){var owner=gatewayParent;if(owner!=null&&owner.id().equals(intent.getStringExtra("gatewayParentResourceId")))stopGatewayParent(owner,startId);else if(runningId==null)stopSelfResult(startId);return START_NOT_STICKY;}
        if(START_GATEWAY_PARENT.equals(intent.getAction())){startGatewayParent(intent,startId);return START_NOT_STICKY;}
        if(STOP_GATEWAY.equals(intent.getAction())){var bridge=gatewayOnly;if(bridge!=null&&bridge.leaseId().equals(intent.getStringExtra("gatewayLeaseId")))stopGatewayOnly(bridge,startId);else if(runningId==null)stopSelfResult(startId);return START_NOT_STICKY;}
        if(PhoneNotificationControls.handles(intent.getAction())){
            if(gatewayParent!=null||gatewayOnly!=null)return START_NOT_STICKY;
            PhoneNotificationControls.dispatch(app,intent,runningId,runningRunId,runningEpoch,ownerControl);
            if(runningId==null)stopSelf(startId);return START_NOT_STICKY;
        }
        if(START_GATEWAY.equals(intent.getAction())){startGatewayOnly(intent,startId);return START_NOT_STICKY;}
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
            JSONObject task=app.tasks.get(id,false);String runId=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");runningRunId=runId;runningEpoch=epoch;runningGoal=task.optString("goal");runningConfig=config;ownerControl=control;lastStartId=startId;startLeaseWatch(id,runId,epoch,control);startForeground(NOTIFICATION,notification(task));
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
    /** Native original-task recovery only; no AgentRunner, PhonePort or phone.local grant. */
    private void startGatewayParent(Intent intent,int startId){
        if(runningId!=null||gatewayParent!=null||gatewayOnly!=null)return;EngineApplication.GatewayParentRun owner=app.originalGatewayParentRun(intent.getStringExtra("gatewayParentResourceId"));if(owner==null){stopSelfResult(startId);return;}
        try{gatewayParent=owner;runningId=owner.task();runningRunId=owner.run();runningEpoch=owner.epoch();ownerControl=owner.control();lastStartId=startId;startForeground(NOTIFICATION,notification(app.tasks.get(runningId,false)));owner.serviceRegistered();watchGatewayParent(owner,startId);}catch(Exception unavailable){gatewayParent=null;runningId=null;runningRunId=null;ownerControl=null;CLEANUP.execute(owner::close);stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);}
    }
    private void watchGatewayParent(EngineApplication.GatewayParentRun owner,int startId){if(destroyed||gatewayParent!=owner)return;try{owner.checkCurrent();JSONObject projection=owner.snapshot();String state=projection.optString("state")+":"+projection.optInt("nativeCallsInProgress")+":"+projection.optInt("remainingModels");if(!state.equals(lastGatewayProjection)){lastGatewayProjection=state;app.refreshGatewayParentProjection(owner.task());getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(app.tasks.get(owner.task(),false)));}}catch(Exception stopped){stopGatewayParent(owner,startId);return;}main.postDelayed(()->watchGatewayParent(owner,startId),500);}
    private void stopGatewayParent(EngineApplication.GatewayParentRun owner,int startId){if(gatewayParent!=owner)return;gatewayParent=null;gatewayOnly=null;runningId=null;runningRunId=null;ownerControl=null;lastGatewayProjection=null;CLEANUP.execute(owner::close);stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);}
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
            startForeground(NOTIFICATION,notification(app.tasks.get(id,false)));
            worker.execute(()->{try{bridge.start();main.post(()->watchGatewayOnly(bridge,startId));}catch(Exception stopped){main.post(()->stopGatewayOnly(bridge,startId));}});
        }catch(Exception unavailable){var bridge=candidate;if(bridge!=null)CLEANUP.execute(bridge::close);gatewayOnly=null;if(gatewayParent==null){runningId=null;runningRunId=null;stopSelfResult(startId);}}
    }
    private void watchGatewayOnly(dev.pocketengine.features.proxy.NativeHarnessGatewayBridge bridge,int startId){
        if(destroyed||gatewayOnly!=bridge)return;
        if(!bridge.running()||bridge.originalControl().isCancelled()||!app.ownsNativeTaskControl(runningId,bridge.originalControl())){stopGatewayOnly(bridge,startId);return;}
        main.postDelayed(()->watchGatewayOnly(bridge,startId),500);
    }
    private void stopGatewayOnly(dev.pocketengine.features.proxy.NativeHarnessGatewayBridge bridge,int startId){
        if(gatewayOnly!=bridge)return;if(gatewayParent!=null){stopGatewayParent(gatewayParent,startId);return;}gatewayOnly=null;CLEANUP.execute(bridge::close);runningId=null;runningRunId=null;ownerControl=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);
    }
    private void run(String id,String runId,int epoch,String goal,ModelConfig config,RunControl control,int startId,boolean manualOnly){

        BackgroundPhonePort backgroundPort=null;
        try{
            PowerManager power=getSystemService(PowerManager.class);wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketEngine:PhoneRun");wakeLock.setReferenceCounted(false);wakeLock.acquire(30_000L);
            PhonePort port=heldPort;heldPort=null;
            if(port instanceof BackgroundPhonePort)backgroundPort=(BackgroundPhonePort)port;
            if(port==null&&"background".equals(app.mode(id))){
                app.setStatus(id,"preparing","正在检查独立后台屏，不会改为主屏操作",control);
                if(!(app.features.module("display") instanceof DisplayModule))throw new IllegalStateException("后台助手不可用");
                DisplayModule display=(DisplayModule)app.features.module("display");display.prepare(control);backgroundPort=new BackgroundPhonePort(display);port=backgroundPort;
            }else if(port==null)port=new AndroidPhonePort(this);
            taskBackgroundPort=backgroundPort;
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
            PhonePort agentPort=new AdaptivePhonePort(port,new AdaptivePhonePort.Scope(id,runId,epoch,sessionId,displayId),config.localCaptureAllowed,config.localOcrAllowed,config.sendScreenshots,config.usesAccount()?dev.pocketengine.runtime.ModelGateway.accountSupportsImageInput(config.accountModel):config.supportsImageInput,android.os.SystemClock::uptimeMillis,app.localOcr);
            RunOutcome result=new AgentRunner(agentPort,app.capabilitiesForTask(id,control,agentPort),dev.pocketengine.runtime.context.ContextBudgetPolicy.currentHostSource(),
                    app.phoneOwnerProjection(id,control),app.phoneOwnerInputs(id,runId,epoch,control)).run(id,goal,config,control,event->app.record(id,event,control),
                    app.journalForTask(id,runId,epoch,control,config));
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
        finally{if(backgroundPort!=null)backgroundPort.closeSession();releaseWakeLock();app.features.sendState();}
    }
    private void holdForOwner(String id,PhonePort port,RunControl control,RunOutcome outcome)throws Exception{
        control.pause();port.clearReferences();heldPort=port;waitingForUser=true;
        boolean unresolved=app.tasks.hasUnresolved(id);String status=unresolved?"reconciling":outcome.status;
        app.tasks.finish(id,status,outcome.summary,outcome.evidence);app.pushTask(id);
        app.publish(EngineApplication.json("type","owner_takeover","taskId",id,"manualAllowed",!unresolved,"status",status));
        ++ownerWaitGeneration; // The same canonical task deadline remains supervised during owner handoff.
    }
    private void startLeaseWatch(String id,String run,int epoch,RunControl original){
        if(leaseWatch!=null)leaseWatch.cancel(false);deadlineReached=false;
        leaseWatch=leaseSupervisor.scheduleWithFixedDelay(()->{
            if(destroyed||!id.equals(runningId)||!run.equals(runningRunId)||original!=ownerControl)return;
            try{if(original.isCancelled()||app.control(id)!=original)throw new SecurityException("原任务控制已变化");long end=app.tasks.taskExecutionDeadline(id,run,epoch);
                if(end!=Long.MAX_VALUE&&System.currentTimeMillis()>=end){deadlineReached=true;throw new SecurityException("任务期限已到");}
                BackgroundPhonePort background=taskBackgroundPort;if(background!=null&&background.isConnected())background.renewTaskLease(id,run,epoch,original,end);
                synchronized(this){if(wakeLock!=null&&!waitingForUser)wakeLock.acquire(30_000L);}
            }catch(Exception stopped){original.cancel();main.post(()->{if(id.equals(runningId)&&original==ownerControl&&waitingForUser)app.stop(id);});}
        },0,1,java.util.concurrent.TimeUnit.SECONDS);
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
            if(id.equals(runningId)){runningId=null;runningRunId=null;taskBackgroundPort=null;if(leaseWatch!=null){leaseWatch.cancel(false);leaseWatch=null;}}
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
        boolean gatewayMode=gatewayParent!=null||gatewayOnly!=null;String text=gatewayParent!=null&&gatewayOnly==null?"原网关会话已登记，等待本轮配置与确认 · 不执行Agent或工具":gatewayOnly!=null?"仅模型推理 · 不执行手机或工具 · 可随时停止":t==null?"正在准备":t.optString("summary");if(gatewayParent!=null)try{String state=app.tasks.gatewayParentSessionForUi(gatewayParent.task()).optString("state");text=switch(state){case "REQUEST_IN_PROGRESS"->"正在处理原模型请求 · 不执行Agent或工具";case "REQUEST_CLAIMED"->"原模型请求已登记，等待确定回执";case "READY_WAITING","REQUEST_RECEIPT_RECORDED"->"网关会话已就绪，等待远端模型请求";case "PREPARING_SERVICE"->"正在登记前台服务，尚不可派发";case "WAITING_CONFIGURATION"->"等待本轮准确配置与主人确认";default->"本轮网关停止、到期或结果待核对";};}catch(Exception unknown){text="网关状态待核对，不推定正在执行";}if(text.isEmpty())text="运行中 · 可随时停止";
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle(gatewayMode?"任务模型网关":t==null?"手机任务":t.optString("goal","手机任务")).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
        if(gatewayParent!=null){var owner=gatewayParent;Intent stopIntent=new Intent(this,PhoneRunService.class).setAction(STOP_GATEWAY_PARENT).setData(android.net.Uri.parse("pocketengine-gateway-parent://stop/"+owner.id())).putExtra("gatewayParentResourceId",owner.id());builder.addAction(new Notification.Action.Builder(null,"停止本轮网关",PendingIntent.getService(this,owner.id().hashCode(),stopIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());return builder.build();}
        if(gatewayOnly!=null){
            var bridge=gatewayOnly;Intent stopIntent=new Intent(this,PhoneRunService.class).setAction(STOP_GATEWAY).setData(android.net.Uri.parse("pocketengine-gateway://stop/"+bridge.leaseId())).putExtra("gatewayLeaseId",bridge.leaseId());
            PendingIntent stop=PendingIntent.getService(this,0,stopIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);builder.addAction(new Notification.Action.Builder(null,"停止模型网关",stop).build());return builder.build();
        }
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
    @Override public void onDestroy(){if(gatewayParent!=null){destroyed=true;var owner=gatewayParent;gatewayParent=null;gatewayOnly=null;main.removeCallbacksAndMessages(null);CLEANUP.execute(owner::close);runningId=null;runningRunId=null;app.removeObserver(observer);worker.shutdownNow();leaseSupervisor.shutdownNow();super.onDestroy();return;}if(gatewayOnly!=null){destroyed=true;var bridge=gatewayOnly;gatewayOnly=null;main.removeCallbacksAndMessages(null);CLEANUP.execute(bridge::close);runningId=null;runningRunId=null;app.removeObserver(observer);worker.shutdownNow();leaseSupervisor.shutdownNow();super.onDestroy();return;}destroyed=true;waitingForUser=false;ownerWaitGeneration++;main.removeCallbacksAndMessages(null);closeHeldAsync();if(runningId!=null){String id=runningId;RunControl owner=app.control(id);app.stop(id);try{if(runningRunId!=null&&!runningRunId.isEmpty())app.tasks.endAttempt(id,runningRunId,"cancelled","执行服务已结束，目标与上下文已保留，可核对后继续原任务",new JSONObject());else app.setStatus(id,"interrupted","执行服务已结束，请检查已有结果",owner);}catch(Exception failure){app.error("任务中断状态保存失败，请核对已有结果");}app.released(id,owner);}app.removeObserver(observer);worker.shutdownNow();leaseSupervisor.shutdownNow();releaseWakeLock();PhoneAccessibilityService phone=PhoneAccessibilityService.get();if(phone!=null)phone.hideControls();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
