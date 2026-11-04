package dev.pocketengine;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.phone.AndroidPhonePort;
import dev.pocketengine.phone.AndroidPhoneReadiness;
import dev.pocketengine.phone.display.BackgroundPhonePort;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.observation.AdaptivePhonePort;
import dev.pocketengine.runtime.AgentRunner;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** One retained native background execution. TaskStore is its only durable ledger.
 * Each run has its own worker, physical port, held answer, deadline and cleanup.
 * No code here may select a session through the service's foreground task id. */
final class BackgroundPhoneRun {
    final String runningId,runningRunId;
    final int runningEpoch,notificationId;
    final RunControl ownerControl;
    private final ModelConfig runningConfig;
    private final String runningGoal;
    private final EngineApplication app;
    private final PhoneRunService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final ScheduledExecutorService leaseSupervisor;
    private final Executor cleanup;
    private final AtomicBoolean finishing=new AtomicBoolean();
    private final AtomicBoolean started=new AtomicBoolean();
    private volatile boolean destroyed,waitingForUser,deadlineReached;
    private volatile long ownerWaitGeneration;
    private volatile PhonePort heldPort;
    private volatile BackgroundPhonePort taskBackgroundPort;
    private volatile ScheduledFuture<?> leaseWatch;
    private volatile String pendingDeviceApproval;
    private boolean devicePhysicalCreated;
    private final dev.pocketengine.features.devices.DeviceGoalSession deviceGoal;
    private final dev.pocketengine.features.teams.TeamPhoneHost.Admission teamAdmission;
    private int lastStartId;
    private PowerManager.WakeLock wakeLock;
    private final Runnable cancelAction=()->main.post(this::cancelIfHeld);

    BackgroundPhoneRun(PhoneRunService service,EngineApplication app,String taskId,RunControl original,int notificationId,int startId,
                       ScheduledExecutorService leases,Executor cleanup)throws Exception {
        this.service=service;this.app=app;runningId=taskId;ownerControl=original;
        var identity=original.hostRunIdentity();if(identity==null||!taskId.equals(identity.taskId())||!app.isBackgroundPhoneRun(taskId,original))throw new SecurityException("没有原后台运行身份");
        runningRunId=identity.runId();runningEpoch=identity.epoch();this.notificationId=notificationId;lastStartId=startId;
        JSONObject task=app.tasks.get(taskId,false);if(!original.matchesHostRun(taskId,task.optString("runId"),task.optInt("coordinatorEpoch")))throw new SecurityException("原后台任务已变化");
        runningGoal=task.getString("goal");runningConfig=app.config(taskId);if(runningConfig==null)throw new SecurityException("原后台模型未冻结");
        deviceGoal=app.originalDeviceGoalSession(taskId);teamAdmission=app.originalTeamPhoneAdmission(taskId);
        leaseSupervisor=leases;this.cleanup=cleanup;
        worker=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"pocket-phone-"+runningRunId.substring(0,Math.min(8,runningRunId.length())));thread.setDaemon(true);return thread;});
    }
    boolean matches(String task,String run,int epoch,RunControl control){return runningId.equals(task)&&runningRunId.equals(run)&&runningEpoch==epoch&&ownerControl==control;}
    /** A duplicate service intent owns no run resources and must never cancel its retained owner. */
    void discardUnstarted(){if(started.compareAndSet(false,true)){finishing.set(true);destroyed=true;worker.shutdownNow();}}
    void start(boolean manualOnly){
        if(!started.compareAndSet(false,true))return;
        ownerControl.onCancel(cancelAction);
        try{
            if(!AndroidPhoneReadiness.snapshot(service,null).getJSONObject("taskNotifications").optBoolean("ready"))throw new SecurityException("停止入口不可用，请开启任务通知或重新启用手机操作服务");
            startLeaseWatch(runningId,runningRunId,runningEpoch,ownerControl);
            worker.execute(()->{if(deviceGoal!=null)runDeviceGoal(lastStartId);else run(runningId,runningRunId,runningEpoch,runningGoal,runningConfig,ownerControl,lastStartId,manualOnly);});
        }catch(Exception unavailable){finishAttempt(runningId,runningRunId,ownerControl,new RunOutcome("awaiting_user",safeMessage(unavailable),new JSONObject()),lastStartId);}
    }
    void onTaskEvent(JSONObject task){
        if(task==null||!matches(task.optString("id"),task.optString("runId"),task.optInt("coordinatorEpoch"),app.control(runningId)))return;
        if("stopping".equals(task.optString("status")))cancelIfHeld();
    }
    void continueOriginal(Intent intent,int startId){
        if(destroyed||finishing.get()||app.control(runningId)!=ownerControl||ownerControl.isCancelled())return;
        String run=intent.getStringExtra(deviceGoal==null?"phoneRunId":"deviceRunId");
        int epoch=intent.getIntExtra(deviceGoal==null?"phoneEpoch":"deviceEpoch",-1);
        if(!matches(intent.getStringExtra("taskId"),run,epoch,ownerControl))return;
        if(deviceGoal!=null){String approval=intent.getStringExtra("deviceApprovalId");if(approval!=null&&!approval.isEmpty())pendingDeviceApproval=approval;}
        if(!waitingForUser||ownerControl.isPaused())return;
        if(heldPort==null){ownerControl.pause();app.setStatus(runningId,"awaiting_user","原手机物理会话已不可核对，请停止并检查原任务；未创建替代执行",ownerControl);return;}
        waitingForUser=false;++ownerWaitGeneration;lastStartId=startId;
        try{worker.execute(()->{if(deviceGoal!=null)runDeviceGoal(startId);else run(runningId,runningRunId,runningEpoch,runningGoal,runningConfig,ownerControl,startId,false);});}
        catch(java.util.concurrent.RejectedExecutionException closed){/* The owning shutdown path releases the exact held port. */}
    }
    private synchronized void cancelIfHeld(){
        if(destroyed||finishing.get()||!waitingForUser||!ownerControl.isCancelled()||deviceGoal!=null&&deviceGoal.ownerResultClosed())return;
        waitingForUser=false;++ownerWaitGeneration;
        try{worker.execute(()->{JSONObject evidence=closeHeld(runningId,runningRunId,runningEpoch);finishAttempt(runningId,runningRunId,ownerControl,new RunOutcome("cancelled","已停止原后台任务，其他任务继续；资源释放以原回执为准",withOriginalEvidence(runningId,runningRunId,runningEpoch,evidence)),lastStartId);});}
        catch(java.util.concurrent.RejectedExecutionException closed){/* The owning destroy/finish path is already closing this run. */}
    }
    void closeReviewedDeviceResult(Intent intent,int startId){
        if(deviceGoal==null||!deviceGoal.ownerResultClosed()||!waitingForUser||!matches(intent.getStringExtra("taskId"),intent.getStringExtra("deviceRunId"),intent.getIntExtra("deviceEpoch",-1),ownerControl))return;
        if(!finishing.compareAndSet(false,true))return;waitingForUser=false;++ownerWaitGeneration;
        worker.execute(()->{JSONObject evidence=closeHeld(runningId,runningRunId,runningEpoch);recordRelease(evidence);main.post(()->completeRuntime(startId));});
    }
    void destroy(){
        if(!finishing.compareAndSet(false,true))return;
        synchronized(this){destroyed=true;waitingForUser=false;++ownerWaitGeneration;}
        ownerControl.removeCancelAction(cancelAction);ownerControl.cancel();
        if(leaseWatch!=null)leaseWatch.cancel(false);
        JSONObject pending=closeHeldAsync();
        try{app.tasks.endAttempt(runningId,runningRunId,"cancelled","执行服务已结束；原后台资源清理以回执为准",withOriginalEvidence(runningId,runningRunId,runningEpoch,pending));}
        catch(Exception unavailable){app.error("原后台任务中断状态保存失败，请核对已有结果");}
        app.released(runningId,ownerControl);worker.shutdownNow();releaseWakeLock();
    }
    private void recordRelease(JSONObject evidence){
        if(!evidence.has("phoneResources"))return;
        try{app.tasks.event(runningId,EngineApplication.json("type","phone_resource_release","runId",runningRunId,"coordinatorEpoch",runningEpoch,"phoneResources",evidence.optJSONObject("phoneResources"),"displayRelease",evidence.optJSONObject("displayRelease")));app.pushTask(runningId);}
        catch(Exception unavailable){app.error("原后台屏清理回执保存尚未确认，请核对原任务");}
    }
    private void runDeviceGoal(int startId){
        PhonePort physical=takeHeldPort();BackgroundPhonePort background=physical instanceof BackgroundPhonePort?(BackgroundPhonePort)physical:null;
        try{
            deviceGoal.checkCurrent();if(destroyed||finishing.get()||ownerControl!=app.control(runningId))throw new SecurityException("原设备后台目标已变化");
            if(!AndroidPhoneReadiness.snapshot(service,null).getJSONObject("taskNotifications").optBoolean("ready"))throw new SecurityException("本机可见停止入口不可用");
            if(physical==null){
                if(devicePhysicalCreated)throw new SecurityException("原设备后台物理会话已释放，不创建替代屏幕");
                if(!(app.features.module("display") instanceof DisplayModule display))throw new SecurityException("本机后台屏助手未就绪");
                display.prepare(ownerControl);background=new BackgroundPhonePort(display,ownerControl);physical=background;devicePhysicalCreated=true;
            }
            if(background==null)throw new SecurityException("设备后台目标不能借用主屏");
            background.bindLifecycle(service,runningId,runningRunId,runningEpoch,ownerControl);taskBackgroundPort=background;
            if(!app.attachPort(runningId,physical,ownerControl))throw new SecurityException("原设备屏幕未获准入");
            PowerManager power=service.getSystemService(PowerManager.class);wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketEngine:DeviceGoal");wakeLock.setReferenceCounted(false);wakeLock.acquire(30_000L);
            PhonePort scoped=new dev.pocketengine.features.devices.DeviceGoalPhonePort(app.phoneAgentSource(runningId,ownerControl,physical),deviceGoal.admission(),ownerControl);
            JSONObject identity=background.executionIdentity();
            AdaptivePhonePort agentPort=new AdaptivePhonePort(scoped,new AdaptivePhonePort.Scope(runningId,runningRunId,runningEpoch,identity.getString("sessionId"),identity.getInt("displayId")),runningConfig.localCaptureAllowed,runningConfig.localOcrAllowed,runningConfig.sendScreenshots,runningConfig.supportsImageInput,android.os.SystemClock::uptimeMillis,app.localOcr);
            app.setStatus(runningId,"running","原设备目标正在已授权独立后台屏执行",ownerControl);
            deviceGoal.runnerStarted();
            RunOutcome result=new AgentRunner(agentPort,app.deviceGoalCapabilities(deviceGoal),dev.pocketengine.runtime.context.ContextBudgetPolicy.currentHostSource(),null,app.deviceOwnerInputs().runnerInputs(deviceGoal)).run(runningId,runningGoal,runningConfig,ownerControl,event->app.record(runningId,event,ownerControl),app.tasks.journal(runningId,runningRunId,runningEpoch,ownerControl));
            if(deadlineReached||ownerControl.isCancelled())result=new RunOutcome("cancelled","原设备后台目标已停止后续派发；已有动作仍须核对",result.evidence);
            boolean wait=!ownerControl.isCancelled()&&result.status.matches("awaiting_user|paused")&&!app.tasks.hasUnresolved(runningId);
            if(wait){physical.clearReferences();try{result.evidence.put("audioRestore",background.restoreTaskAudio());}catch(Exception unknown){result.evidence.put("audioRestore",EngineApplication.json("status","audio_recovery_pending","pending",true));}}
            else{JSONObject released=background.closeSession();result.evidence.put("displayRelease",released);background=null;taskBackgroundPort=null;}
            var finished=deviceGoal.runnerFinished(result);
            if(result.status.equals("needs_confirmation")&&!ownerControl.isCancelled()){
                synchronized(this){requireRetained();app.tasks.deviceTasks().recordGoalIdle(finished);ownerControl.pause();waitingForUser=true;++ownerWaitGeneration;pendingDeviceApproval=null;}
                app.pushTask(runningId);main.post(this::cancelIfHeld);return;
            }
            if(wait){
                synchronized(this){requireRetained();ownerControl.pause();heldPort=physical;waitingForUser=true;++ownerWaitGeneration;
                    app.tasks.status(runningId,"awaiting_user",result.summary);app.recordDeviceOrdinaryQuestionWait(finished);}
                background=null;app.pushTask(runningId);main.post(this::cancelIfHeld);
                String decided=pendingDeviceApproval;if(decided!=null)main.post(()->{try{if(waitingForUser&&app.originalDeviceGoalSession(runningId)==deviceGoal)app.resumeDeviceExecution(runningId,decided);}catch(Exception changed){pendingDeviceApproval=null;app.error("原设备审批续接条件已变化，请核对原任务");}});
                return;
            }
            finishAttempt(runningId,runningRunId,ownerControl,result,startId);
        }catch(Exception unavailable){
            deviceGoal.runnerFailed();JSONObject evidence=new JSONObject();if(background!=null){try{evidence.put("displayRelease",background.closeSession());}catch(Exception unknown){/* Original cleanup remains unconfirmed. */}background=null;taskBackgroundPort=null;}
            finishAttempt(runningId,runningRunId,ownerControl,new RunOutcome(ownerControl.isCancelled()?"cancelled":"awaiting_user","原设备后台目标已停止派发，请核对原屏幕、模型与授权；未换主屏或创建替代执行",evidence),startId);
        }finally{if(background!=null)background.closeSession();releaseWakeLock();app.features.sendState();}
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
            port=requiredHold==null?takeHeldPort():continuationPort[0];
            if(requiredHold!=null&&port!=requiredHold.physicalPort())throw new SecurityException("原问题物理口已释放，不能回落新建");
            if(port instanceof BackgroundPhonePort)backgroundPort=(BackgroundPhonePort)port;
            requireRetained();
            PowerManager power=service.getSystemService(PowerManager.class);wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketEngine:PhoneRun");wakeLock.setReferenceCounted(false);wakeLock.acquire(30_000L);
            if(port==null&&"background".equals(app.mode(id))){
                app.setStatus(id,"preparing","正在检查独立后台屏，不会改为主屏操作",control);
                if(!(app.features.module("display") instanceof DisplayModule))throw new IllegalStateException("后台助手不可用");
                DisplayModule display=(DisplayModule)app.features.module("display");display.prepare(control);backgroundPort=new BackgroundPhonePort(display,control);port=backgroundPort;
            }else if(port==null)throw new SecurityException("原后台会话缺失，不会回落主屏");
            if(backgroundPort!=null)backgroundPort.bindLifecycle(service,id,runId,epoch,control);
            taskBackgroundPort=backgroundPort;
            if(!app.attachPort(id,port,control)){if(backgroundPort!=null)backgroundPort.closeSession();throw new InterruptedException("Task stopped before phone admission");}
            if(manualOnly){
                JSONObject frame=port.observe(true,control);holdForOwner(id,port,control,new RunOutcome("paused","独立屏已由主人接管，可操作后交回原任务",frame));backgroundPort=null;return;
            }
            if(backgroundPort==null||!backgroundPort.isConnected())throw new SecurityException("原后台物理屏已变化，不会采用主屏或另一任务的屏幕");
            JSONObject identity=backgroundPort.executionIdentity();String sessionId=identity.getString("sessionId");int displayId=identity.getInt("displayId");
            PhonePort source=app.phoneAgentSource(id,control,port);
            if(teamAdmission!=null)source=teamAdmission.scopedPhone(source);
            if(teamAdmission!=null)teamAdmission.checkCurrent();
            AdaptivePhonePort agentPort=new AdaptivePhonePort(source,new AdaptivePhonePort.Scope(id,runId,epoch,sessionId,displayId),config.localCaptureAllowed,config.localOcrAllowed,config.sendScreenshots,config.usesAccount()?dev.pocketengine.runtime.ModelGateway.accountSupportsImageInput(config.accountModel):config.supportsImageInput,android.os.SystemClock::uptimeMillis,app.localOcr);
            if(teamAdmission==null)app.retainPhonePractice(id,control,agentPort);
            dev.pocketengine.core.CapabilityPort capabilities=teamAdmission==null?app.capabilitiesForTask(id,control,agentPort):null;
            app.setStatus(id,"running","正在观察原独立后台屏",control);
            RunOutcome result=new AgentRunner(agentPort,capabilities,dev.pocketengine.runtime.context.ContextBudgetPolicy.currentHostSource(),
                    teamAdmission==null?app.phoneOwnerProjection(id,control):null,teamAdmission==null?app.phoneOwnerInputs(id,runId,epoch,control):null).run(id,goal,config,control,event->app.record(id,event,control),
                    teamAdmission==null?app.journalForTask(id,runId,epoch,control,config):app.tasks.journal(id,runId,epoch,control));
            app.snapshotPhonePractice(id,control,result.evidence);
            if(result.evidence.has("phonePractice"))result.evidence.put("practiceCleanup",dev.pocketengine.phone.diagnostics.PhonePracticeActivity.closeOriginal(control));
            if(deadlineReached)result=new RunOutcome("cancelled","已达到主人设定的原任务期限，停止后续派发；请检查已发生的动作与结果",result.evidence);
            else if(control.isCancelled()&&!"cancelled".equals(result.status))result=new RunOutcome("cancelled","已停止后续操作，请检查已发生的动作与结果",result.evidence);
            if(teamAdmission==null&&("awaiting_user".equals(result.status)||"paused".equals(result.status)&&control.conditionWait()==null)&&!control.isCancelled()){
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
    private void requireRetained()throws Exception{
        if(destroyed||finishing.get()||app.control(runningId)!=ownerControl||ownerControl.isCancelled())throw new InterruptedException("原后台执行已停止，未重新创建屏幕");
    }
    private void holdForOwner(String id,PhonePort port,RunControl control,RunOutcome outcome)throws Exception{
        control.pause();port.clearReferences();
        if(port instanceof BackgroundPhonePort)try{outcome.evidence.put("audioRestore",((BackgroundPhonePort)port).restoreTaskAudio());}catch(Exception pending){outcome.evidence.put("audioRestore",new JSONObject().put("status","audio_recovery_pending").put("pending",true));}
        boolean unresolved=app.tasks.hasUnresolved(id);String status=unresolved?"reconciling":outcome.status;
        if(port instanceof BackgroundPhonePort){
            var original=control.hostRunIdentity();if(original==null||!original.taskId().equals(id))throw new SecurityException("original_phone_hold_required");
            Long deadline=null;try{deadline=app.tasks.taskExecutionDeadline(id,original.runId(),original.epoch());}catch(Exception unverified){/* Unknown is not an unlimited lease or successful cleanup. */}
            outcome.evidence.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.held(id,original.runId(),original.epoch(),unresolved?"reconciliation":outcome.status.equals("paused")?"manual_takeover":"awaiting_owner",deadline,outcome.evidence.optJSONObject("audioRestore")));
        }
        synchronized(this){
            requireRetained();
            app.tasks.finish(id,status,outcome.summary,outcome.evidence);
            heldPort=port;waitingForUser=true;
            ++ownerWaitGeneration; // The same canonical task deadline remains supervised during owner handoff.
        }
        try{app.retainOwnerPhoneQuestion(id,control,port,runningConfig,ownerWaitGeneration,ownerPhoneQuestionHost());}catch(Exception unavailable){/* Manual/approval/changed holds have no ordinary-answer authority. */}
        app.pushTask(id);
        app.publish(EngineApplication.json("type","owner_takeover","taskId",id,"manualAllowed",!unresolved,"status",status));
        main.post(this::cancelIfHeld);
    }
    private dev.pocketengine.features.assistant.OwnerPhoneQuestions.PhysicalOwner ownerPhoneQuestionHost(){
        return new dev.pocketengine.features.assistant.OwnerPhoneQuestions.PhysicalOwner(){
            @Override public void requireHeld(dev.pocketengine.features.assistant.OwnerPhoneQuestions.Held expected)throws Exception{
                if(destroyed||deviceGoal!=null||teamAdmission!=null||!waitingForUser||heldPort!=expected.physicalPort()
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
    private synchronized PhonePort takeHeldPort(){PhonePort port=heldPort;heldPort=null;return port;}
    private JSONObject closeHeld(String id,String run,int epoch){return releaseOriginalPort(id,run,epoch,takeHeldPort());}
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
        PhonePort port=takeHeldPort();String id=runningId,run=runningRunId;int epoch=runningEpoch;JSONObject pending=new JSONObject();
        if(port==null){
            // An executing worker still owns this port and performs its existing finally/catch cleanup.
            // Record pending without starting a second release operation.
            if(taskBackgroundPort!=null)try{pending.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.pending(id,run,epoch));}catch(Exception unavailable){/* Unknown remains unconfirmed. */}
            return pending;
        }
        if(port instanceof BackgroundPhonePort background){background.noteReleasePending();try{pending.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.pending(id,run,epoch));}catch(Exception unavailable){/* The original resource stays behind its existing release barrier. */}}
        cleanup.execute(()->{
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
    private void finishAttempt(String id,String runId,RunControl owner,RunOutcome outcome,int startId){
        if(owner!=ownerControl||!runningId.equals(id)||!runningRunId.equals(runId))return;
        if(!finishing.compareAndSet(false,true)){if(destroyed&&outcome.evidence.optJSONObject("displayRelease")!=null)try{outcome.evidence.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.release(id,runId,runningEpoch,outcome.evidence.getJSONObject("displayRelease")));recordRelease(outcome.evidence);}catch(Exception unavailable){/* Only the captured release receipt may be appended. */}return;}
        try{app.snapshotPhonePractice(id,owner,outcome.evidence);}catch(Exception unavailable){/* Keep the original result. */}
        if(outcome.evidence.optJSONObject("displayRelease")!=null)try{outcome.evidence.put("phoneResources",dev.pocketengine.phone.PhoneResourceDisposition.release(id,runId,runningEpoch,outcome.evidence.getJSONObject("displayRelease")));}catch(Exception unavailable){/* Preserve the actual release receipt. */}
        try{
            if(outcome.status.equals("paused")&&owner.conditionWait()!=null&&!owner.isCancelled())app.tasks.endAutomationConditionWait(id,runId,owner,outcome.summary,outcome.evidence);
            else app.tasks.endAttempt(id,runId,outcome.status,outcome.summary,outcome.evidence);
        }catch(Exception unavailable){app.setStatus(id,"failed","无法保存原运行结果，请检查可用空间",owner);}
        main.post(()->completeRuntime(startId));
    }
    private void completeRuntime(int startId){
        destroyed=true;waitingForUser=false;++ownerWaitGeneration;
        if(leaseWatch!=null){leaseWatch.cancel(false);leaseWatch=null;}
        ownerControl.removeCancelAction(cancelAction);releaseWakeLock();worker.shutdown();
        // compare-and-release prevents a late callback from removing the same task's next run.
        app.released(runningId,ownerControl);app.pushTask(runningId);service.backgroundFinished(this,startId);
    }
    private static String safeMessage(Exception error){String message=error.getMessage();return message==null?"后台任务执行异常，请检查原步骤":message.length()>250?message.substring(0,250):message;}
    private synchronized void releaseWakeLock(){if(wakeLock!=null){if(wakeLock.isHeld())wakeLock.release();wakeLock=null;}}
}
