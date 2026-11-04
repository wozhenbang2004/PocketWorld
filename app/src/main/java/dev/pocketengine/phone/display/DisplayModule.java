package dev.pocketengine.phone.display;

import android.content.SharedPreferences;
import android.os.Build;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.display.adb.AdbBootstrap;
import dev.pocketengine.phone.display.adb.AdbBootstrapClient;
import dev.pocketengine.phone.display.adb.LocalAdbEndpoints;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-only setup of the separate display. Connection state is never inferred from setup alone. */
public final class DisplayModule implements EngineModule {
    void attachInstalledAppEvidence(JSONObject observation)throws Exception{dev.pocketengine.phone.observation.InstalledAppEvidence.attach(context.android,observation);}
    private final FeatureContext context;
    private final SharedPreferences prefs;
    private final ShizukuBootstrap shizuku;
    private final AdbBootstrapClient adb;
    private final Configuration configured;
    private final StatusProbe statusProbe;
    private volatile long bootstrapEpoch;
    private volatile long verifiedAdbEpoch=-1;
    private volatile JSONObject verifiedAdbStatus;
    private volatile boolean connected;
    private volatile boolean releasePending;
    private volatile String lastReleasedSession="";
    private volatile String ownerTaskId="";
    private volatile JSONObject state=new JSONObject();
    private volatile String message="手机控制尚未启用。跟随内置引导完成系统授权，无需电脑或另装应用。";
    public DisplayModule(FeatureContext context){
        this(context,changed->new AdbBootstrap(context.android,context.secrets,changed),null,null);
    }
    @FunctionalInterface interface AdbFactory{AdbBootstrapClient create(java.util.function.Consumer<JSONObject> changed);}
    @FunctionalInterface interface Configuration{JSONObject load()throws Exception;}
    @FunctionalInterface interface StatusProbe{JSONObject check()throws Exception;}
    DisplayModule(FeatureContext context,AdbFactory factory,Configuration configured,StatusProbe statusProbe){
        this.context=context;prefs=context.android.getSharedPreferences("display_backend",0);
        this.configured=configured;
        this.statusProbe=statusProbe;
        shizuku=new ShizukuBootstrap(context.android,value->{
            if("shizuku".equals(backend())&&(!value.optBoolean("binderAvailable")||!value.optBoolean("permissionGranted")||!value.optBoolean("userServiceBound")))connected=false;
            context.emit("display",value);
        });
        adb=factory.create(this::adbChanged);
    }
    @Override public String id(){return "display";}
    @Override public JSONObject snapshot()throws Exception {
        JSONObject result=new JSONObject(state.toString());
        return result.put("configured",!prefs.getString("socketName","").isEmpty()).put("helperConnected",isConnected())
                .put("sessionActive",isConnected()&&result.optBoolean("sessionActive",false)).put("releasePending",releasePending)
                .put("setupRevision",bootstrapEpoch).put("appAccess",new dev.pocketengine.phone.access.AndroidPhoneAccessStore(context.android).snapshot()).put("clipboardPasteAllowed",dev.pocketengine.phone.setup.AndroidClipboardRecovery.allowed(context.android))
                .put("authorizationBackend",backend()).put("requiresAdbBootstrap","adb".equals(backend())).put("shizuku",shizuku.snapshot()).put("adb",adb.snapshot())
                .put("onDeviceAdbSupported",Build.VERSION.SDK_INT>=34).put("bootstrapBusy",bootstrapBusy())
                .put("parallelPhone",PhoneParallelCapabilities.current()).put("backgroundOnly",true).put("minimumSdk",34).put("supportedAndroid",Build.VERSION.SDK_INT>=34)
                .put("readyForTask",hasSession()).put("taskOwned",!ownerTaskId.isEmpty()).put("deviceVerified",false).put("message",message)
                .put("audioSilenceEnabled",dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.silenceEnabled(context.android));
    }
    public synchronized void pinOwner(String taskId){
        if(taskId==null||taskId.isEmpty()||taskId.length()>200)throw new IllegalArgumentException("无效手机任务身份");
        if(!ownerTaskId.isEmpty()&&!ownerTaskId.equals(taskId))throw new IllegalStateException("后台执行资源由其他任务持有");
        if(ownerTaskId.isEmpty()&&bootstrapBusy())throw new IllegalStateException("后台授权启动或进程回收尚未结束，请等待后再开始任务");
        if(ownerTaskId.isEmpty()&&adb.snapshot().optBoolean("recoveryRequired"))throw new IllegalStateException("上次无线ADB结果未确认，请先由主人连接核对原助手");
        if(ownerTaskId.isEmpty())bootstrapEpoch++;
        ownerTaskId=taskId;
    }
    public synchronized void unpinOwner(String taskId){
        if(ownerTaskId.equals(taskId)){ownerTaskId="";if(!context.executionAllowed())try{stopForOwnerDisable();}catch(Exception unknown){message="总停用后原后台资源释放尚未确认，请核对原连接";}}
    }
    /** Only our original helper bindings are stopped; pending display/audio recovery stays explicit. */
    public synchronized JSONObject stopForOwnerDisable()throws Exception {
        if(context.executionAllowed())throw new SecurityException("总运行状态已恢复，旧清理失效");
        if(!ownerTaskId.isEmpty()||releasePending||state.optBoolean("sessionActive"))
            return new JSONObject().put("state","original_display_cleanup_pending").put("releaseConfirmed",false).put("helperStopRequested",false);
        bootstrapEpoch++;verifiedAdbStatus=null;verifiedAdbEpoch=-1;connected=false;
        adb.cancel();JSONObject actual=shizuku.stop();
        return new JSONObject().put("state","stop_requested").put("helperStopRequested",true).put("physicalCleanupVerified",false).put("shizuku",actual);
    }
    synchronized boolean ownedBy(String taskId){return !ownerTaskId.isEmpty()&&ownerTaskId.equals(taskId);}
    @Override public synchronized JSONObject handle(String command,JSONObject input)throws Exception {
        if(input==null)input=new JSONObject();
        if(java.util.Set.of("adb.pair","adb.connect","shizuku.start","create").contains(command))context.requireExecutionEnabled();
        // Native owner controls remain available during a run so revocation reaches its live fences.
        if("access.set".equals(command)){
            new dev.pocketengine.phone.access.AndroidPhoneAccessStore(context.android).setOwnerRule(
                    new dev.pocketengine.phone.access.AppCopyKey(input.getString("packageName"),input.getInt("userId")),
                    input.getBoolean("automation"),input.getBoolean("ownerShare"),input.getLong("expectedRevision"));
            return snapshot();
        }
        if("clipboard.consent".equals(command)){
            dev.pocketengine.phone.setup.AndroidClipboardRecovery.setOwnerAllowed(context.android,input.getBoolean("enabled"));return snapshot();
        }
        if("clipboard.restore".equals(command)){
            if(!ownerTaskId.isEmpty())throw new IllegalStateException("请先停止输入操作，再恢复原剪贴板");
            boolean restored=dev.pocketengine.phone.setup.AndroidClipboardRecovery.create(context.android).recover();return new JSONObject().put("ok",restored).put("clipboardRestored",restored).put("automaticReplayAllowed",false);
        }
        if(!ownerTaskId.isEmpty())throw new IllegalStateException("手机任务正在使用后台资源，请先停止任务再更改或探测连接");
        if(bootstrapBusy()&&!java.util.Set.of("adb.status","adb.discover","adb.cancel","status","shizuku.status").contains(command))
            throw new IllegalStateException("后台授权启动或回收正在进行，请先等待或取消");
        if(adb.snapshot().optBoolean("recoveryRequired")&&!java.util.Set.of("adb.status","adb.discover","adb.connect","adb.cancel","adb.forget","status","shizuku.status").contains(command))
            throw new IllegalStateException("上次无线ADB结果未确认，请先连接核对或明确遗忘，不能重复配对或更换身份");
        switch(command){
            case "adb.status":return snapshot();
            case "adb.discover":adb.discover();return snapshot();
            case "adb.pair":{
                requireIdleDisplay();LocalAdbEndpoints.current().check(input.optString("host","127.0.0.1"),input.getInt("pairingPort"));LocalAdbEndpoints.pairingCode(input.optString("pairingCode"));
                selectWirelessBackend();adb.pair(input);return snapshot();
            }
            case "adb.connect":{
                LocalAdbEndpoints.current().check(input.optString("host","127.0.0.1"),input.getInt("connectPort"));LocalAdbEndpoints.distinctPorts(input.optInt("pairingPort",-1),input.getInt("connectPort"));
                if(!"wireless_adb".equals(backend())){requireIdleDisplay();selectWirelessBackend();}
                long epoch=++bootstrapEpoch;verifiedAdbStatus=null;verifiedAdbEpoch=-1;connected=false;
                adb.connect(input,configuration(),()->authenticateAdb(epoch));return snapshot();
            }
            case "adb.cancel":{
                bootstrapEpoch++;verifiedAdbStatus=null;verifiedAdbEpoch=-1;
                if("wireless_adb".equals(backend())){
                    if(state.optBoolean("sessionActive")||releasePending)try{release();}catch(Exception unknown){/* Keep the release barrier while hard cancellation proceeds. */}
                    connected=false;
                }
                adb.cancel();return snapshot();
            }
            case "adb.forget":{
                requireIdleDisplay();bootstrapEpoch++;verifiedAdbStatus=null;verifiedAdbEpoch=-1;if("wireless_adb".equals(backend()))connected=false;adb.forgetIdentity();return snapshot();
            }
            case "shizuku.status":return snapshot();
            case "shizuku.permission":return shizuku.requestPermission();
            case "shizuku.start":{
                if(Build.VERSION.SDK_INT<34)throw new IllegalStateException("后台隔离焦点模式需要Android14或以上");
                JSONObject authorization=shizuku.snapshot();
                if(!authorization.optBoolean("binderAvailable")||authorization.optInt("serverVersion",-1)<13||!authorization.optBoolean("permissionGranted"))
                    return shizuku.start(new JSONObject(),()->{});
                if((state.optBoolean("sessionActive")||releasePending)&&(!"shizuku".equals(backend())||isConnected()))
                    throw new IllegalStateException("请先释放已有后台屏再更换启动方式");
                if(!"shizuku".equals(backend())){
                    bootstrapEpoch++;adb.cancel();
                    context.secrets.put("display.token",randomHex(32));
                    if(!prefs.edit().putString("socketName","pocketengine.display."+randomHex(16)).putString("backend","shizuku").commit())throw new IllegalStateException("后台配置保存失败");
                    connected=false;state=new JSONObject();
                }
                JSONObject config=configuration();
                return shizuku.start(config,()->{try{connect(null);context.emit("display",snapshot());}catch(Exception failed){connected=false;throw new IllegalStateException("后台连接核验失败");}});
            }
            case "shizuku.stop":{
                if(state.optBoolean("sessionActive")||releasePending)release();connected=false;return shizuku.stop();
            }
            case "setup":return setup();
            case "connect":return connect(null);
            case "status":try{return connect(null);}catch(Exception unavailable){return snapshot();}
            case "create":return create(input,null);
            case "observe":return request("frame",new JSONObject(),null);
            case "release":return release();
            case "reset":{
                if(state.optInt("displayId",-1)>0&&!state.optString("sessionId").isEmpty())release();
                if(releasePending)throw new IllegalStateException("后台释放结果未确认，不能清除原连接身份");
                bootstrapEpoch++;adb.cancel();
                if(!prefs.edit().clear().commit())throw new IllegalStateException("后台配置清除失败");context.secrets.remove("display.token");connected=false;state=new JSONObject();
                shizuku.stop();
                new File(context.android.getFilesDir(),"display-bootstrap.json").delete();message="后台授权配置已清除，现有助手将按会话时限释放。";return snapshot();
            }
            default:throw new IllegalArgumentException("不支持的后台屏操作");
        }
    }
    private synchronized JSONObject setup()throws Exception {
        if(Build.VERSION.SDK_INT<34)throw new IllegalStateException("后台隔离焦点模式需要 Android14 或以上");
        if(android.os.Process.myUid()>=100000)throw new IllegalStateException("当前后台模块仅支持 Android 主用户安装");
        if(!"adb".equals(backend())){
            if(state.optBoolean("sessionActive")||releasePending)throw new IllegalStateException("请先释放后台屏再更换启动方式");
            bootstrapEpoch++;shizuku.stop();adb.cancel();connected=false;state=new JSONObject();
            context.secrets.put("display.token",randomHex(32));
            if(!prefs.edit().putString("socketName","pocketengine.display."+randomHex(16)).putString("backend","adb").commit())throw new IllegalStateException("后台配置保存失败");
        }
        JSONObject bootstrap=configuration();
        try(FileOutputStream output=context.android.openFileOutput("display-bootstrap.json",0)){output.write((bootstrap.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        boolean asset=true;
        try(InputStream source=context.android.getAssets().open("pocketengine-display-helper.jar");FileOutputStream target=context.android.openFileOutput("display-helper.jar",0)){
            byte[] buffer=new byte[8192];int n;while((n=source.read(buffer))!=-1)target.write(buffer,0,n);
        }catch(java.io.FileNotFoundException missing){asset=false;}
        String pkg=context.android.getPackageName();
        JSONArray commands=new JSONArray()
                .put("adb exec-out run-as "+pkg+" cat files/display-helper.jar > pocketengine-display-helper.jar")
                .put("adb push pocketengine-display-helper.jar /data/local/tmp/pocketengine-display-helper.jar")
                .put("adb exec-out run-as "+pkg+" cat files/display-bootstrap.json | adb shell 'CLASSPATH=/data/local/tmp/pocketengine-display-helper.jar app_process /system/bin dev.pocketengine.displayhelper.Main'");
        message="ADB兼容配置已准备；在受信任电脑执行命令后连接。也可返回选择手机端Shizuku启动。";
        return snapshot().put("helperAssetIncluded",asset).put("setupCommands",commands).put("setupMethod","adb_debug_run_as_stdin");
    }
    private synchronized JSONObject configuration()throws Exception {
        if(configured!=null)return new JSONObject(configured.load().toString());
        if(android.os.Process.myUid()>=100000)throw new IllegalStateException("当前后台模块仅支持Android主用户安装");
        String socket=prefs.getString("socketName","");String secret=context.secrets.get("display.token");
        if(socket.isEmpty()||secret==null||secret.isEmpty()){
            if(state.optBoolean("sessionActive")||releasePending)throw new IllegalStateException("原后台会话身份缺失，不能生成新身份绕过释放核对");
            String selectedBackend=backend();
            socket="pocketengine.display."+randomHex(16);secret=randomHex(32);context.secrets.put("display.token",secret);
            if(!prefs.edit().putString("socketName",socket).putString("backend",selectedBackend).commit())throw new IllegalStateException("后台配置保存失败");
        }
        return new JSONObject().put("socketName",socket).put("token",secret).put("ownerUid",android.os.Process.myUid());
    }
    /** Metadata only: no new display, authorization or business operation is started. */
    public JSONObject primaryOwnership(RunControl original,boolean ownerPausedRead)throws Exception {
        if(original==null||!original.hasHostRun()||!isConnected())throw new SecurityException("phone_owner_identity_unavailable");
        JSONObject answer=client().call("primary_ownership",new JSONObject(),original,original.pauseRevision(),ownerPausedRead);
        requireOk(answer);return answer.getJSONObject("ownership");
    }
    public android.content.Context hostContext(){return context.android;}
    public synchronized JSONObject connect(RunControl control)throws Exception {
        try{
            if("wireless_adb".equals(backend())&&(bootstrapBusy()||adb.snapshot().optBoolean("recoveryRequired")))throw new IllegalStateException("无线ADB启动尚未确认，请先在本机连接入口核对原助手");
            if("shizuku".equals(backend())&&!shizuku.authorized())throw new SecurityException("Shizuku授权或服务已失效");
            JSONObject answer=client().call("status",new JSONObject(),control);
            acceptStatus(answer);
            new File(context.android.getFilesDir(),"display-bootstrap.json").delete();
            message=state.optBoolean("recoveryRequired")?"发现上次遗留的后台会话，新任务开始前将先确认释放。":answer.optBoolean("sessionActive")?"后台独立屏已建立，仍需真机验证操作兼容性。":"后台助手已连接，可建立独立显示。";
            return snapshot();
        }catch(Exception failure){connected=false;message="后台助手未连接或授权已失效，不会切换为主屏操作。";throw failure;}
    }
    // Called only after the authenticated socket handshake. Keeping reconciliation in one
    // place also covers a release whose successful reply was lost before reaching the app.
    synchronized void acceptStatus(JSONObject answer)throws Exception {
        validateStatus(answer);
        dev.pocketengine.displayhelper.DisplayGeometry incoming=dev.pocketengine.displayhelper.DisplayGeometry.read(answer);
        if(answer.optBoolean("sessionActive")&&answer.optString("sessionId").equals(state.optString("sessionId"))
                &&answer.optInt("displayId",-1)==state.optInt("displayId",-1))requireGeometryAdvance(state,incoming);
        boolean active=answer.optBoolean("sessionActive");
        boolean recovery=releasePending||state.optBoolean("recoveryRequired")
                ||active&&!answer.optString("sessionId").equals(state.optString("sessionId"));
        if(!active){
            String previous=state.optString("sessionId");if(!previous.isEmpty()&&!state.isNull("sessionId"))lastReleasedSession=previous;
            releasePending=false;
        }
        connected=true;state=publicState(answer);
        if(recovery&&active)state.put("recoveryRequired",true);
    }
    private static void validateStatus(JSONObject answer)throws Exception {
        if(!answer.optBoolean("ok")||answer.optInt("protocol")!=1||answer.optInt("uid")!=2000&&answer.optInt("uid")!=0)
            throw new IllegalStateException("后台助手不支持当前协议或身份无效");
        if(answer.optInt("sdk")<34)throw new IllegalStateException("后台助手需要 Android14 或以上");
        boolean active=answer.optBoolean("sessionActive");
        if(active&&(answer.optInt("displayId",-1)<=0||answer.optString("sessionId").isEmpty()||answer.isNull("sessionId")))
            throw new SecurityException("后台助手返回的会话身份无效");
    }
    public synchronized JSONObject create(JSONObject input,RunControl control)throws Exception {
        context.requireExecutionEnabled();
        PhoneParallelCapabilities.require(input);
        connect(control);
        if(state.optBoolean("sessionActive"))throw new IllegalStateException("后台屏已有会话，请先释放");
        JSONObject answer=client().call("create",input,control);
        requireOk(answer);if(answer.optInt("displayId",-1)<=0)throw new SecurityException("后台助手返回了无效显示");
        dev.pocketengine.displayhelper.DisplayGeometry.read(answer);
        state=publicState(answer).put("sessionActive",true);connected=true;message="独立后台屏已创建；主屏不会作为备用执行位置。";
        return snapshot();
    }
    public synchronized JSONObject prepare(RunControl control)throws Exception {
        connect(control);if(releasePending||state.optBoolean("recoveryRequired"))release();
        if(!state.optBoolean("sessionActive"))create(new JSONObject(),control);return snapshot();
    }
    public boolean isConnected(){
        if(!connected)return false;
        String selected=backend();if("shizuku".equals(selected))return shizuku.authorized();
        if("wireless_adb".equals(selected)){JSONObject current=adb.snapshot();return !current.optBoolean("busy")&&!current.optBoolean("recoveryRequired");}
        return true;
    }
    public boolean hasSession(){return isConnected()&&!releasePending&&!state.optBoolean("recoveryRequired")&&state.optBoolean("sessionActive")&&state.optInt("displayId",-1)>0;}
    public JSONObject session()throws Exception{return new JSONObject(state.toString());}
    /** Local admission barrier before async cleanup. Never acquire the network-operation monitor here. */
    public void noteReleasePending(String sessionId,int displayId){
        JSONObject known=state;
        if(displayId>0&&displayId==known.optInt("displayId",-1)&&sessionId!=null&&sessionId.equals(known.optString("sessionId")))
            releasePending=true;
    }
    public JSONObject request(String command,JSONObject payload,RunControl control)throws Exception {
        return request(command,payload,control,control==null?-1:control.pauseRevision());
    }
    public JSONObject request(String command,JSONObject payload,RunControl control,long expectedRevision)throws Exception {
        return request(command,payload,control,expectedRevision,false);
    }
    JSONObject request(String command,JSONObject payload,RunControl control,long expectedRevision,boolean ownerTakeover)throws Exception {
        return request(command,payload,control,expectedRevision,ownerTakeover,null);
    }
    JSONObject requestOcrTap(JSONObject payload,RunControl control,long expectedRevision,String expectedSession,int expectedDisplay,
            dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
        JSONObject pinned=new JSONObject(payload.toString()).put("sessionId",expectedSession).put("displayId",expectedDisplay);
        return request("tap",pinned,control,expectedRevision,false,java.util.Objects.requireNonNull(admission));
    }
    /** Extensions keep the same native transport admission and exact original display. */
    JSONObject requestAdmitted(String command,JSONObject payload,RunControl control,long revision,String expectedSession,int expectedDisplay,
            dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
        JSONObject pinned=new JSONObject(payload.toString()).put("sessionId",expectedSession).put("displayId",expectedDisplay);
        return request(command,pinned,control,revision,false,java.util.Objects.requireNonNull(admission));
    }
    /** Native ledger calls only. Active mutations retain the original admitted wire write;
     * cleanup can only read the pinned original session's audio state. */
    synchronized JSONObject audioForTask(String command,JSONObject payload,String task,String expectedSession,int expectedDisplay,RunControl control,long revision,dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
        JSONObject current=session();if(!java.util.Set.of("audio.get","audio.set").contains(command)||!ownedBy(task)||expectedDisplay!=current.optInt("displayId",-1)||!expectedSession.equals(current.optString("sessionId")))throw new SecurityException("original_audio_session_required");
        if(control==null&&!"audio.get".equals(command))throw new SecurityException("automatic_audio_restore_not_atomic");
        if(control!=null&&(control.hostRunIdentity()==null||!task.equals(control.hostRunIdentity().taskId())||admission==null))throw new SecurityException("original_audio_admission_required");
        JSONObject pinned=new JSONObject(payload.toString()).put("sessionId",expectedSession).put("displayId",expectedDisplay);
        return request(command,pinned,control,revision,false,control==null?null:admission);
    }
    /** Native owner audio review only. Its admission is rechecked at the original helper write;
     * the application and TaskStore monitors are not held while waiting for the helper reply. */
    JSONObject ownerAudioForTask(String command,JSONObject payload,String task,String run,int epoch,String expectedSession,int expectedDisplay,RunControl original,long revision,boolean paused,dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
        if(!java.util.Set.of("ownership","audio.get","audio.set").contains(command)||original==null||admission==null)throw new SecurityException("original_owner_audio_admission_required");
        dev.pocketengine.phone.observation.NativePhoneEntry.Admission checked=()->{
            admission.check();
            if(!original.matchesHostRun(task,run,epoch)||original.isCancelled()||original.isPaused()!=paused||original.pauseRevision()!=revision)throw new SecurityException("original_owner_audio_run_changed");
            synchronized(DisplayModule.this){JSONObject current=session();if(!ownedBy(task)||expectedDisplay!=current.optInt("displayId",-1)||!expectedSession.equals(current.optString("sessionId")))throw new SecurityException("original_owner_audio_session_changed");}
        };
        checked.check();JSONObject pinned=new JSONObject(payload.toString()).put("sessionId",expectedSession).put("displayId",expectedDisplay);
        return request(command,pinned,original,revision,paused,checked);
    }
    /** Explicit native owner setting; no model tool exposes it and no live session is displaced. */
    public synchronized JSONObject audioRecovery(String command,JSONObject payload)throws Exception {
        if(!java.util.Set.of("audio.get","audio.set").contains(command)||!ownerTaskId.isEmpty()||bootstrapBusy()||!isConnected())throw new SecurityException("owner_audio_recovery_unavailable");
        JSONObject answer=client().call("audio_recovery."+command.substring("audio.".length()),new JSONObject(payload.toString()),null);requireOk(answer);return answer;
    }
    /** Native private-owner settings only; deliberately not added to handle/model tools. */
    public synchronized JSONObject setAudioPolicyForOwner(boolean enabled)throws Exception {
        if(enabled&&(!ownerTaskId.isEmpty()||bootstrapBusy()))throw new SecurityException("stop_phone_task_before_enabling_audio_policy");
        dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.setOwnerSilenceEnabled(context.android,enabled);
        return new JSONObject().put("enabled",dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.silenceEnabled(context.android)).put("persisted",true).put("systemPermissionGranted",false).put("audioMutation",false);
    }
    private JSONObject request(String command,JSONObject payload,RunControl control,long expectedRevision,boolean ownerTakeover,dev.pocketengine.phone.observation.NativePhoneEntry.Admission admission)throws Exception {
        if(command.startsWith("owner_handoff_")&&(!ownerTakeover||control==null||!control.isPaused()))throw new SecurityException("应用移屏仅供原生主人接管");
        JSONObject current=session();String sessionId=current.optString("sessionId");int display=current.optInt("displayId",-1);
        if(!isConnected()||!current.optBoolean("sessionActive")||display<=0||sessionId.isEmpty())throw new IllegalStateException("后台屏未就绪，请先连接并建立独立屏幕");
        if(admission!=null&&(!sessionId.equals(payload.optString("sessionId"))||display!=payload.optInt("displayId",-1)))
            throw new SecurityException("OCR目标的原后台会话已变化");
        JSONObject args=new JSONObject(payload.toString()).put("displayId",display).put("sessionId",sessionId);
        JSONObject result;
        try{result=client().call(command,args,control,expectedRevision,ownerTakeover,admission);}catch(InterruptedException stopped){throw stopped;}
        catch(dev.pocketengine.phone.observation.NativePhoneEntry.Refused refused){throw refused;}
        catch(ShellBridgeClient.StaleRequestException stale){throw stale;}
        catch(ShellBridgeClient.NotDispatchedException refused){throw refused;}
        catch(Exception unavailable){connected=false;message="后台连接中断，停止后续操作。";throw unavailable;}
        if(result.optBoolean("ok")&&(result.optInt("displayId",-1)!=display||!sessionId.equals(result.optString("sessionId"))))
            throw new SecurityException("后台显示或会话回执身份不匹配");
        if("frame".equals(command)&&result.optBoolean("ok"))adoptFrameGeometry(result,sessionId,display);
        return result;
    }
    /** Only the authenticated original helper may advance this session's geometry. */
    private synchronized void adoptFrameGeometry(JSONObject frame,String originalSession,int originalDisplay)throws Exception {
        if(!originalSession.equals(state.optString("sessionId"))||originalDisplay!=state.optInt("displayId",-1))
            throw new SecurityException("原后台帧返回时会话已变化");
        dev.pocketengine.displayhelper.DisplayGeometry incoming=dev.pocketengine.displayhelper.DisplayGeometry.read(frame);
        requireGeometryAdvance(state,incoming);
        if(incoming!=null){
            JSONObject next=new JSONObject(state.toString());incoming.writeTo(next,frame.optBoolean("geometryReady"));
            state=next.put("captureAvailable",!frame.optBoolean("empty"));
        }
    }
    private static void requireGeometryAdvance(JSONObject previous,dev.pocketengine.displayhelper.DisplayGeometry incoming) {
        dev.pocketengine.displayhelper.DisplayGeometry known=dev.pocketengine.displayhelper.DisplayGeometry.read(previous);
        if(known!=null&&(incoming==null||!incoming.canFollow(known)))
            throw new SecurityException("后台几何身份已过期或缺失，未恢复旧尺寸");
    }
    /** Native task supervisor only. It supplies a freshly checked canonical deadline, never a model duration. */
    public synchronized JSONObject renewTaskLease(String taskId,String runId,int epoch,RunControl control,String expectedSession,int expectedDisplay,long taskDeadline)throws Exception {
        if(!ownedBy(taskId)||control==null||control.isCancelled()||!control.matchesHostRun(taskId,runId,epoch)||!hasSession())throw new SecurityException("原后台任务已停止");
        JSONObject current=session();if(expectedDisplay!=current.optInt("displayId",-1)||!expectedSession.equals(current.optString("sessionId")))throw new SecurityException("不能续期其他后台会话");
        long now=System.currentTimeMillis();if(taskDeadline<=now)throw new SecurityException("原任务范围已到期");long duration=taskDeadline==Long.MAX_VALUE?30_000:Math.min(30_000,taskDeadline-now);
        JSONObject result=client().call("heartbeat",new JSONObject().put("sessionId",expectedSession).put("displayId",expectedDisplay).put("lifetimeMs",duration),null);
        requireOk(result);if(!result.optBoolean("renewed")||expectedDisplay!=result.optInt("displayId",-1)||!expectedSession.equals(result.optString("sessionId")))throw new SecurityException("原后台续期回执不匹配");return result;
    }
    public synchronized JSONObject releaseSession(String sessionId,int displayId)throws Exception {
        JSONObject owned=session();
        if(sessionId.equals(owned.optString("sessionId"))&&displayId==owned.optInt("displayId",-1))return release();
        return new JSONObject().put("ok",lastReleasedSession.equals(sessionId)).put("releaseConfirmed",lastReleasedSession.equals(sessionId))
                .put("superseded",true).put("message","旧会话不再拥有当前显示，未释放其他会话。");
    }
    public synchronized JSONObject release()throws Exception {
        JSONObject owned=session();String id=owned.optString("sessionId");int display=owned.optInt("displayId",-1);
        if(display>0&&!id.isEmpty()){
            try{
                JSONObject result=client().call("release",new JSONObject().put("sessionId",id).put("displayId",display),null);requireOk(result);
                lastReleasedSession=id;releasePending=false;state=new JSONObject();message="后台屏已确认释放。";
            }catch(Exception unknown){connected=false;releasePending=true;message="后台屏释放结果未确认；不会派发新操作，请重新连接核对。助手仍有会话时限。";throw unknown;}
        }
        return snapshot().put("ok",true).put("releaseConfirmed",!releasePending);
    }
    @Override public void close(){bootstrapEpoch++;try{release();}catch(Exception ignored){connected=false;}adb.close();shizuku.close();}
    private String backend(){return prefs.getString("backend",prefs.contains("socketName")?"adb":"wireless_adb");}
    private boolean bootstrapBusy(){return adb.snapshot().optBoolean("busy")||shizuku.snapshot().optBoolean("starting");}
    private void requireIdleDisplay(){if(state.optBoolean("sessionActive")||releasePending||state.optBoolean("recoveryRequired"))throw new IllegalStateException("请先连接核对并释放已有后台屏，再更改授权或身份");}
    private void selectWirelessBackend()throws Exception {
        if("wireless_adb".equals(backend()))return;
        String previous=backend();bootstrapEpoch++;
        if(previous.equals("shizuku")){
            shizuku.stop();context.secrets.put("display.token",randomHex(32));
            if(!prefs.edit().putString("socketName","pocketengine.display."+randomHex(16)).putString("backend","wireless_adb").commit())throw new IllegalStateException("后台配置保存失败");
        }else if(!prefs.edit().putString("backend","wireless_adb").commit())throw new IllegalStateException("后台配置保存失败");
        connected=false;state=new JSONObject();
    }
    private void authenticateAdb(long epoch)throws Exception {
        JSONObject answer=statusProbe==null?client().call("status",new JSONObject(),null):statusProbe.check();validateStatus(answer);
        synchronized(this){
            if(epoch!=bootstrapEpoch||!"wireless_adb".equals(backend())||!ownerTaskId.isEmpty())throw new IllegalStateException("无线ADB启动身份已过期");
            verifiedAdbStatus=new JSONObject(answer.toString());verifiedAdbEpoch=epoch;
        }
    }
    private synchronized void adbChanged(JSONObject update){
        if("wireless_adb".equals(backend())){
            if(update.optBoolean("helperAuthenticated")&&!update.optBoolean("busy")&&verifiedAdbEpoch==bootstrapEpoch&&verifiedAdbStatus!=null){
                try{acceptStatus(verifiedAdbStatus);message=state.optBoolean("recoveryRequired")?"已核对原后台助手；需先释放遗留会话。":"无线ADB后台助手已通过真实认证，可以建立独立显示。";}
                catch(Exception invalid){connected=false;message="无线ADB返回的后台身份未通过核对。";}
                finally{verifiedAdbStatus=null;verifiedAdbEpoch=-1;}
            }else if(update.optBoolean("busy")||update.optBoolean("recoveryRequired")||update.optString("phase").matches(".*unknown|.*exited|identity_forgotten|forget_failed")){
                connected=false;message=update.optString("message",message);
            }else if(update.optString("phase").matches("paired|needs_pairing|service_unavailable|bind_timeout|forget_blocked"))message=update.optString("message",message);
        }
        context.emit("display",jsonEvent(update));
    }
    private static JSONObject jsonEvent(JSONObject update){JSONObject event=new JSONObject();try{event.put("event","adb_bootstrap_changed").put("adb",new JSONObject(update.toString()));}catch(Exception ignored){}return event;}
    private ShellBridgeClient client()throws Exception {
        String socket=prefs.getString("socketName","");String secret=context.secrets.get("display.token");
        if(socket.isEmpty()||secret==null||secret.isEmpty())throw new IllegalStateException("请先准备后台助手连接配置");
        return new ShellBridgeClient(socket,secret);
    }
    static void requireOk(JSONObject result){if(!result.optBoolean("ok"))throw new IllegalStateException(result.optString("message","后台请求未成功"));}
    private static JSONObject publicState(JSONObject input)throws Exception {
        JSONObject out=new JSONObject();for(String key:new String[]{"sessionActive","displayId","sessionId","width","height","density","sdk","requestedPackageName","backgroundOnly","focusIsolationRequested","displayFlagsConfirmed","recoveryRequired","frameMetadataOnly","imeIsolation","imeReason","inputInjectionAvailable","captureAvailable","geometryTracking","geometryReady","geometryGeneration","rotation","captureWidth","captureHeight"})if(input.has(key))out.put(key,input.get(key));return out;
    }
    private static String randomHex(int count){byte[] data=new byte[count];new SecureRandom().nextBytes(data);StringBuilder result=new StringBuilder();for(byte b:data)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
}
