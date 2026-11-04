package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.media.voice.RealtimeVoiceProfile;
import dev.pocketengine.media.voice.VoiceAudioListener;
import dev.pocketengine.media.voice.tasks.VoiceTaskHostPort;
import dev.pocketengine.media.voice.tasks.VoiceTaskJourney;
import dev.pocketengine.media.voice.tasks.VoiceTaskReadout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-controlled foreground call. Task execution and its cancellation do not belong to this module. */
public final class RealtimeSessionModule implements EngineModule {
    public static final int AUDIO_PERMISSION_REQUEST=543,CAMERA_PERMISSION_REQUEST=544;
    private final FeatureContext context;
    private final RealtimeMediaSession.Factory factory;
    private RealtimeMediaSession.VoiceFactory naturalVoiceFactory;
    private final AtomicFile configuration;
    interface Credentials {boolean contains(String slot);String get(String slot)throws Exception;void put(String slot,String value)throws Exception;void remove(String slot)throws Exception;}
    private final Credentials credentials;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable permissionCheck=new Runnable(){@Override public void run(){synchronized(RealtimeSessionModule.this){if(closed||!visible()||(!active()&&!accepting&&!voice.active()))return;checkCapturePermissions();main.postDelayed(this,750);}}};
    private WeakReference<Activity> visibleOwner=new WeakReference<>(null);
    private MediaController mediaOwner,leaseMedia;
    private String leaseToken="";
    private RealtimeMediaSession session;
    private RealtimeCallProvider provider;
    private RealtimeCallProvider.EndpointFactory endpointFactory;
    private RealtimeEndpointProfile endpointProfile;
    private String providerError="";
    private RealtimeTurnCommitter turnCommitter;
    private final RealtimeTurnBuffer turns=new RealtimeTurnBuffer();
    private final VoiceCompanionTurn voice=new VoiceCompanionTurn(this::emitState);
    private final VoiceTaskJourney voiceTasks=new VoiceTaskJourney(this::emitState);
    private final AtomicLong taskOwnerEpoch=new AtomicLong(),taskSessionEpoch=new AtomicLong();
    private long snapshotRevision;
    private String snapshotFingerprint="";
    private long lifecycleEpoch,adapterEpoch,leaseAdapterEpoch;
    private String sessionId="",invitationState="idle",invitationMessage="";
    private JSONObject invitation;
    private boolean closed,accepting,changingCapture,lastMicrophonePermission,lastCameraPermission,terminalReceived;
    // A Web owner request keeps its original visible window through queued/native admission.
    // Native callers retain their existing Activity authority; this only adds a restrictive fence.
    private final ThreadLocal<java.util.function.BooleanSupplier> reviewedWindow=new ThreadLocal<>();

    public RealtimeSessionModule(FeatureContext context,RealtimeMediaSession.Factory factory){this(context,factory,new Credentials(){public boolean contains(String slot){return context.secrets.contains(slot);}public String get(String slot)throws Exception{return context.secrets.get(slot);}public void put(String slot,String value)throws Exception{context.secrets.put(slot,value);}public void remove(String slot)throws Exception{context.secrets.remove(slot);}});}
    RealtimeSessionModule(FeatureContext context,RealtimeMediaSession.Factory factory,Credentials credentials){
        this.context=context;this.factory=factory;this.credentials=credentials;
        configuration=new AtomicFile(new File(context.android.getFilesDir(),"realtime-session.json"));
        lastMicrophonePermission=microphoneGranted();lastCameraPermission=cameraGranted();
    }
    @Override public String id(){return "realtime";}

    /** Actual Activity lifecycle only. A JSON foregroundOwner field cannot authorize capture. */
    public synchronized void setForeground(Activity activity){setForeground(activity,activity==null?null:mediaOwner);}
    public synchronized void setForeground(Activity activity,MediaController media){
        Activity previous=visibleOwner.get();
        visibleOwner=new WeakReference<>(activity);mediaOwner=activity==null?null:media;
        if(activity==null||previous!=null&&previous!=activity){taskOwnerEpoch.incrementAndGet();voiceTasks.detach();cancelProviderAccept();main.removeCallbacks(permissionCheck);if(accepting)invitationState="cancelled";voice.cancelForLifecycle(activity==null?"owner_backgrounded":"owner_replaced");stopSession(activity==null?"owner_backgrounded":"owner_replaced");}
        if(activity!=null)checkCapturePermissions();
        emitState();
    }
    /** Old Activity callbacks cannot clear a newer Activity's capture owner. */
    public synchronized void clearForeground(Activity expectedOwner){if(expectedOwner!=null&&visibleOwner.get()==expectedOwner)setForeground(null,null);}
    public synchronized void installTurnCommitter(RealtimeTurnCommitter committer){turnCommitter=java.util.Objects.requireNonNull(committer);emitState();}
    /** Original TaskStore/native admission only; no task runner is installed in this media module. */
    public synchronized void installVoiceTaskHostPort(VoiceTaskHostPort port){voiceTasks.install(port);emitState();}
    /** Local-only route preparation, followed by one text-model request with no task/tool authority. */
    public synchronized void installVoiceConversationPort(VoiceConversationPort port){voice.install(port);emitState();}
    /** Trusted native host registration. No microphone, credential read or network starts here. */
    public synchronized void installNaturalVoiceFactory(RealtimeMediaSession.VoiceFactory next){
        if(closed||active()||accepting||voice.active())throw new IllegalStateException("请先停止语音会话");
        naturalVoiceFactory=java.util.Objects.requireNonNull(next);emitState();
    }
    /** Trusted host installation only; reads local configuration but performs no network request. */
    public synchronized void installEndpointProviderFactory(RealtimeCallProvider.EndpointFactory factory){
        if(active()||accepting)throw new IllegalStateException("请先结束通话再安装服务适配器");endpointFactory=java.util.Objects.requireNonNull(factory);try{restoreEndpointProvider();}catch(Exception invalid){endpointProfile=null;providerError="configuration_invalid";}emitState();
    }
    private void restoreEndpointProvider()throws Exception{
        JSONObject saved=read().optJSONObject("endpointProvider");if(saved==null||endpointFactory==null)return;
        RealtimeEndpointProfile profile=new RealtimeEndpointProfile(saved.getString("endpointUrl"),saved.getString("serverUrl"),saved.getString("agentName"),saved.getLong("revision"));
        String slot=saved.getString("secretSlot");RealtimeCallProvider next=endpointFactory.create(profile,()->credentials.get(slot));
        if(provider!=null)provider.close();provider=java.util.Objects.requireNonNull(next);endpointProfile=profile;
    }
    private void cancelProviderAccept(){if(accepting&&provider!=null&&invitation!=null&&invitation.optString("source").matches("provider_invitation|owner_prepared_provider"))provider.cancelAccept(invitation.optString("id"));}
    /** Permission denial never auto-accepts an invitation or auto-starts capture. */
    public synchronized void checkCapturePermissions(){
        boolean mic=microphoneGranted(),camera=cameraGranted();
        boolean changed=mic!=lastMicrophonePermission||camera!=lastCameraPermission;
        lastMicrophonePermission=mic;lastCameraPermission=camera;
        if(voice.active()&&!mic){voice.cancelForLifecycle("permission_revoked");changed=true;}
        if(session!=null){JSONObject state=session.snapshot();if(state.optBoolean("microphoneEnabled")&&!mic||state.optBoolean("cameraEnabled")&&!camera){
            stopSession("permission_revoked");invitationMessage="采集权限已撤销，本次通话已停止；已收到的文字保留供你复核";changed=true;
        }}if(changed)emitState();
    }
    /** A real provider is injected by trusted app code; no endpoint or fake remote agent is invented. */
    public synchronized void installProvider(RealtimeCallProvider next){
        if(closed||active()||accepting)throw new IllegalStateException("请先结束通话再更换服务");
        if(provider!=null&&provider!=next)provider.close();provider=next;endpointProfile=null;invitation=null;invitationState="idle";lifecycleEpoch++;emitState();
    }
    /** Authenticated provider callback. Generic feature payloads cannot create incoming callers. */
    public synchronized JSONObject offerInvitation(RealtimeCallProvider source,String id,String displayName,long expiresAt)throws Exception{
        if(closed||source==null||source!=provider||!source.supportsIncomingInvitations())throw new SecurityException("邀请不属于已安装的可信服务");
        if(active()||accepting||pendingInvitation()||voice.active())throw new IllegalStateException("已有待处理邀请、通话或语音轮");
        if(id==null||id.isBlank()||id.length()>200||expiresAt<=System.currentTimeMillis()||expiresAt>System.currentTimeMillis()+300_000)throw new IllegalArgumentException("邀请标识或有效期无效");
        invitation=new JSONObject().put("id",id).put("source","provider_invitation").put("providerId",source.id()).put("displayName",bounded(displayName,120)).put("expiresAt",expiresAt);
        invitationState="awaiting_owner";invitationMessage="收到通话邀请；接受前不会开启摄像头或麦克风";emitState();return snapshot();
    }
    @Override public synchronized JSONObject snapshot()throws Exception{
        expireInvitation();JSONObject config=read();String slot=config.optString("secretSlot");boolean hasToken=!slot.isEmpty()&&credentials.contains(slot);config.remove("secretSlot");config.put("hasToken",hasToken);
        JSONObject endpointConfig=config.optJSONObject("endpointProvider");config.remove("endpointProvider");
        JSONObject naturalConfig=config.optJSONObject("naturalVoiceProvider");config.remove("naturalVoiceProvider");
        if(naturalConfig!=null){String authSlot=naturalConfig.optString("secretSlot");naturalConfig.remove("secretSlot");naturalConfig.put("hasAuthentication",!authSlot.isEmpty()&&credentials.contains(authSlot));}
        if(endpointConfig!=null){String authSlot=endpointConfig.optString("secretSlot");endpointConfig.remove("secretSlot");endpointConfig.put("hasAuthentication",!authSlot.isEmpty()&&credentials.contains(authSlot));}
        JSONObject current=session==null?new JSONObject().put("state","idle").put("microphoneEnabled",false).put("cameraEnabled",false).put("remoteAudioSubscribed",false).put("remoteVideos",new JSONArray()):session.snapshot();
        JSONObject value=new JSONObject().put("configuration",config).put("configured",hasToken&&!config.optString("serverUrl").isEmpty()).put("sessionId",sessionId)
            .put("foregroundOwner",visible()).put("captureCoordinatorReady",mediaOwner!=null).put("microphonePermission",microphoneGranted()).put("cameraPermission",cameraGranted())
            .put("providerConfiguration",endpointConfig==null?JSONObject.NULL:endpointConfig).put("providerConfigured",endpointProfile!=null&&provider!=null&&endpointConfig!=null&&endpointProfile.revision==endpointConfig.optLong("revision")&&endpointConfig.optBoolean("hasAuthentication")).put("providerError",providerError)
            .put("naturalVoiceConfiguration",naturalConfig==null?JSONObject.NULL:naturalConfig).put("naturalVoiceSupported",naturalVoiceFactory!=null)
            .put("naturalVoiceConfigured",naturalVoiceFactory!=null&&naturalConfig!=null&&naturalConfig.optBoolean("hasAuthentication"))
            .put("session",current).put("invitation",invitation==null?JSONObject.NULL:new JSONObject(invitation.toString())).put("invitationState",invitationState).put("invitationMessage",invitationMessage)
            .put("transcription",turns.snapshot(turnCommitter!=null,visible()&&reviewSessionReady())).put("voiceTurn",voice.snapshot()).put("voiceTask",voiceTasks.snapshot()).put("accepting",accepting).put("changingCapture",changingCapture).put("audioLeaseHeld",!leaseToken.isEmpty()||voice.leaseHeld()||voiceTasks.leaseHeld())
            .put("capabilities",new JSONObject().put("transport","livekit").put("microphone",true).put("remoteAudio",true).put("camera",true).put("remoteVideo",true)
                .put("screenShare",false).put("avatar",false).put("backgroundCall",false).put("incomingInvitations",provider!=null&&provider.supportsIncomingInvitations()).put("endpointProviderSupported",endpointFactory!=null).put("providerId",provider==null?"":provider.id())
                .put("tokenSource",provider==null?"literal_participant_token":"literal_or_native_provider").put("tokenMinting",false).put("tokenEndpointConfigured",provider!=null).put("voiceCompanion",true)
                .put("naturalVoiceSupported",naturalVoiceFactory!=null).put("fullDuplexVoice",current.optBoolean("fullDuplexVoice")).put("naturalTaskDispatch",false).put("voiceTaskHost",voiceTasks.installed()).put("transcriptReview",true).put("reviewedTaskCommit",turnCommitter!=null))
            .put("commands",new JSONArray("[\"snapshot\"]"));
        String fingerprint=value.toString();if(!fingerprint.equals(snapshotFingerprint)){snapshotFingerprint=fingerprint;snapshotRevision++;}
        return value.put("snapshotRevision",snapshotRevision);
    }
    /** Read-only module surface: neither model tools nor legacy featureRequest can grant call consent. */
    @Override public JSONObject handle(String command,JSONObject input)throws Exception{
        if(command.equals("snapshot"))return snapshot();
        throw new SecurityException("通话操作只能从原生主人界面确认");
    }
    public JSONObject handleOwner(Activity owner,String command,JSONObject input)throws Exception{
        if(java.util.Set.of("prepareCall","prepareProviderCall","prepareNaturalVoice","startVoiceTurn","acceptInvitation","startVoiceTask","commitVoiceTask","commitTurn","readVoiceTask").contains(command))context.requireExecutionEnabled();
        synchronized(this){if(owner==null||visibleOwner.get()!=owner)throw new SecurityException("通话界面已变化，请重新打开");requireVisible();}
        switch(command){
            case "snapshot":return snapshot();
            case "configure":return configure(owner,input);
            case "prepareCall":return prepareCall(owner);
            case "prepareProviderCall":return prepareProviderCall(owner);
            case "prepareNaturalVoice":return prepareNaturalVoice(owner);
            case "configureNaturalVoice":return configureNaturalVoice(owner,input);
            case "clearNaturalVoice":return clearNaturalVoice(owner);
            case "interruptOutput":if(input.has("readoutId")){synchronized(this){requireOwner(owner);}voiceTasks.stopReading(input.getString("readoutId"));emitState();return snapshot();}synchronized(this){requireOwner(owner);requireSession(input);if(session==null||!session.snapshot().optString("state").equals("connected"))throw new IllegalStateException("通话尚未连接");session.interruptOutput().get(2,TimeUnit.SECONDS);emitState();return snapshot();}
            case "startVoiceTurn":synchronized(this){requireOwner(owner);if(active()||accepting||pendingInvitation()||!leaseToken.isEmpty()||voice.active())throw new IllegalStateException("请先处理邀请或挂断通话，再开始一轮语音");voice.start(owner,mediaOwner);main.removeCallbacks(permissionCheck);main.post(permissionCheck);emitState();return snapshot();}
            case "stopVoiceTurn":synchronized(this){requireOwner(owner);voice.cancel(input.getString("voiceTurnId"));emitState();return snapshot();}
            case "configureEndpointProvider":return configureEndpointProvider(owner,input);
            case "clearEndpointProvider":return clearEndpointProvider(owner);
            case "reviewTurn":synchronized(this){requireOwner(owner);requireReviewSession(input);if(turnCommitter==null)throw new IllegalStateException("任务存储尚未接入");JSONObject review=turns.review(sessionId,input.getString("turnId"),input.getLong("revision"));emitState();return review;}
            case "commitTurn":return commitTurn(owner,input,false);
            case "reconcileTurn":return commitTurn(owner,input,true);
            case "commitVoiceTask":return commitTurn(owner,input,false,true);
            case "listVoiceTasks":{VoiceTaskHostPort.Authority current;synchronized(this){requireOwner(owner);current=taskAuthority(owner);}voiceTasks.listSaved(current);return snapshot();}
            case "recoverVoiceTask":{VoiceTaskHostPort.Authority current;synchronized(this){requireOwner(owner);current=taskAuthority(owner);}voiceTasks.recoverSaved(input.getString("taskId"),current);return snapshot();}
            case "inspectVoiceTask":synchronized(this){requireOwner(owner);}voiceTasks.inspect(input.getString("bindingId"));emitState();return snapshot();
            case "startVoiceTask":synchronized(this){requireOwner(owner);}voiceTasks.startOriginal(input.getString("bindingId"),input.getString("viewToken"));emitState();return snapshot();
            case "cancelVoiceTask":synchronized(this){requireOwner(owner);}voiceTasks.cancelExactRun(input.getString("bindingId"),input.getString("viewToken"));emitState();return snapshot();
            case "readVoiceTask":return readVoiceTask(owner,input);
            case "acceptInvitation":return accept(owner,input);
            case "rejectInvitation":return reject(owner,input);
            case "requestMicrophonePermission":return requestPermission(owner,Manifest.permission.RECORD_AUDIO,AUDIO_PERMISSION_REQUEST);
            case "requestCameraPermission":return requestPermission(owner,Manifest.permission.CAMERA,CAMERA_PERMISSION_REQUEST);
            case "mute":return changeCapture(owner,!input.getBoolean("muted"),false,input);
            case "setCamera":return changeCapture(owner,input.getBoolean("enabled"),true,input);
            case "disconnect":synchronized(this){requireOwner(owner);requireSession(input);cancelProviderAccept();invitationState="ended";invitationMessage="通话已挂断，已有任务继续运行；已收到的文字保留供你复核";stopSession("owner_hangup");return snapshot();}
            default:throw new IllegalArgumentException("不支持的原生通话操作");
        }
    }
    /** Reviewed local presentation only. Configuration/prepare compare-and-use shares the
     * original module lock; network calls retain their original cancellable lock boundaries. */
    public JSONObject handleReviewedOwner(Activity owner,String command,JSONObject input,long expectedConfigurationRevision,
            java.util.function.BooleanSupplier window)throws Exception {
        java.util.Objects.requireNonNull(window);
        if(reviewedWindow.get()!=null)throw new IllegalStateException("已有原通话请求正在处理");
        reviewedWindow.set(window);
        try {
            if(java.util.Set.of("configure","configureNaturalVoice","clearNaturalVoice","configureEndpointProvider","clearEndpointProvider",
                    "prepareCall","prepareProviderCall","prepareNaturalVoice").contains(command)) {
                synchronized(this){
                    requireOwner(owner);
                    if(expectedConfigurationRevision<0||read().optLong("revision")!=expectedConfigurationRevision)
                        throw new IllegalStateException("原通话配置已经变化，请重新读取并核对");
                    return handleOwner(owner,command,input);
                }
            }
            requireReviewedWindow();
            return handleOwner(owner,command,input);
        } finally {reviewedWindow.remove();}
    }
    /** Native global owner stop only. Keep the module reusable; never replay a voice turn on resume. */
    public synchronized JSONObject stopForOwnerDisable()throws Exception{
        if(!(context.android instanceof dev.pocketengine.EngineApplication app)||app.executionAllowed())throw new SecurityException("需要原本机已保存的总停用状态");
        cancelProviderAccept();voice.cancelForLifecycle("owner_disabled");invitationState="cancelled";
        invitationMessage="PocketWorld 已停用；原文字和任务记录保留，没有自动续听";stopSession("owner_disabled");emitState();return snapshot();
    }
    public synchronized RealtimeMediaSession.VideoBinding bindVideo(Activity owner,String expectedSessionId,String trackSid){
        if(owner!=visibleOwner.get()||!visible()||session==null||!sessionId.equals(expectedSessionId)||!session.snapshot().optString("state").equals("connected"))throw new IllegalStateException("通话或视频已变化");
        return session.bindVideo(owner,trackSid);
    }
    public synchronized RealtimeMediaSession.AudioBinding bindAudio(Activity owner,String expectedSessionId,VoiceAudioListener listener){
        if(owner!=visibleOwner.get()||!visible()||session==null||!sessionId.equals(expectedSessionId)||!session.snapshot().optString("state").equals("connected"))throw new IllegalStateException("语音播放会话已变化");
        return session.listenAudio(listener);
    }
    private JSONObject requestPermission(Activity owner,String permission,int request)throws Exception{
        java.util.function.BooleanSupplier window=reviewedWindow.get();
        main.post(()->{synchronized(this){if(!closed&&visibleOwner.get()==owner&&visible()&&(window==null||window.getAsBoolean()))owner.requestPermissions(new String[]{permission},request);}});
        return new JSONObject().put("status","permission_requested").put("automaticallyConnects",false);
    }
    private synchronized JSONObject prepareCall(Activity owner)throws Exception{
        requireOwner(owner);if(active()||accepting||pendingInvitation()||!leaseToken.isEmpty()||voice.active())throw new IllegalStateException("请先处理已有邀请或挂断通话");
        JSONObject config=read();if(config.optString("serverUrl").isEmpty()||!credentials.contains(config.optString("secretSlot")))throw new IllegalStateException("请先配置实际房间的参与者令牌");
        invitation=new JSONObject().put("id",UUID.randomUUID().toString()).put("source","owner_prepared_room").put("displayName","加入已配置的房间")
            .put("serverUrl",config.getString("serverUrl")).put("configurationRevision",config.optLong("revision")).put("expiresAt",System.currentTimeMillis()+120_000);
        invitationState="awaiting_owner";invitationMessage="选择本次通话的摄像头和麦克风；这不是远端来电";emitState();return snapshot();
    }
    private synchronized JSONObject prepareProviderCall(Activity owner)throws Exception{
        requireOwner(owner);if(active()||accepting||pendingInvitation()||!leaseToken.isEmpty()||voice.active())throw new IllegalStateException("请先处理已有邀请或挂断通话");
        JSONObject saved=read().optJSONObject("endpointProvider");if(endpointProfile==null||provider==null||saved==null||saved.optLong("revision")!=endpointProfile.revision||!credentials.contains(saved.optString("secretSlot")))throw new IllegalStateException("请先配置你自己的可信token与agent服务；应用不会代建AI服务");
        invitation=new JSONObject().put("id",UUID.randomUUID().toString()).put("source","owner_prepared_provider").put("providerId",provider.id()).put("displayName","连接已配置的通话服务")
            .put("serverUrl",endpointProfile.serverUrl).put("tokenEndpoint",endpointProfile.endpointUrl).put("agentName",endpointProfile.agentName).put("configurationRevision",endpointProfile.revision).put("expiresAt",System.currentTimeMillis()+120_000);
        providerError="";invitationState="awaiting_owner";invitationMessage="这是你准备发起的服务通话，不是远端来电；接受后才请求令牌与agent";emitState();return snapshot();
    }
    private void requireConfigurationIdle(Activity owner){requireOwner(owner);if(active()||accepting||!leaseToken.isEmpty()||voice.active())throw new IllegalStateException("请先挂断，再更改通话配置");}
    private synchronized JSONObject prepareNaturalVoice(Activity owner)throws Exception{
        requireConfigurationIdle(owner);if(pendingInvitation())throw new IllegalStateException("请先处理当前邀请");
        JSONObject saved=read().optJSONObject("naturalVoiceProvider");
        if(naturalVoiceFactory==null||saved==null||!credentials.contains(saved.optString("secretSlot")))throw new IllegalStateException("请先配置实际支持Realtime GA协议的语音服务");
        RealtimeVoiceProfile profile=voiceProfile(saved);
        invitation=new JSONObject().put("id",UUID.randomUUID().toString()).put("source","owner_prepared_natural_voice").put("displayName","开始自然语音对话")
            .put("serverUrl",profile.endpointUrl).put("model",profile.model).put("voice",profile.voice).put("transcriptionModel",profile.transcriptionModel).put("configurationRevision",profile.revision).put("expiresAt",System.currentTimeMillis()+120_000);
        invitationState="awaiting_owner";invitationMessage="接受后才连接；麦克风原始声音将发送到选定语音服务";emitState();return snapshot();
    }
    private synchronized JSONObject configureNaturalVoice(Activity owner,JSONObject input)throws Exception{
        requireConfigurationIdle(owner);if(naturalVoiceFactory==null)throw new IllegalStateException("自然语音适配器尚未接入");
        JSONObject config=read(),previous=config.optJSONObject("naturalVoiceProvider");long revision=previous==null?1:previous.optLong("revision")+1;
        RealtimeVoiceProfile profile=new RealtimeVoiceProfile(input.getString("endpointUrl"),input.getString("model"),input.getString("voice"),input.optString("transcriptionModel"),revision);
        String oldSlot=previous==null?"":previous.optString("secretSlot"),slot="",fresh=null,token=input.optString("authenticationToken");
        if(!token.isEmpty()){validateToken(token);if(token.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("语音认证令牌无效");fresh="realtime.voice."+UUID.randomUUID();credentials.put(fresh,token);slot=fresh;}
        else if(previous!=null&&RealtimeEndpointProfile.origin(previous.getString("endpointUrl")).equals(RealtimeEndpointProfile.origin(profile.endpointUrl)))slot=oldSlot;
        if(slot.isEmpty()||!credentials.contains(slot))throw new IllegalArgumentException("新服务origin须提供自己的语音令牌，不会借用文字模型密钥");
        config.put("naturalVoiceProvider",new JSONObject().put("endpointUrl",profile.endpointUrl).put("model",profile.model).put("voice",profile.voice).put("transcriptionModel",profile.transcriptionModel).put("revision",revision).put("secretSlot",slot));config.put("revision",config.optLong("revision")+1);
        try{writeConfiguration(config);}catch(Exception failure){if(fresh!=null)credentials.remove(fresh);throw failure;}
        lifecycleEpoch++;invitation=null;invitationState="idle";if(!oldSlot.isEmpty()&&!oldSlot.equals(slot))credentials.remove(oldSlot);emitState();return snapshot();
    }
    private synchronized JSONObject clearNaturalVoice(Activity owner)throws Exception{
        requireConfigurationIdle(owner);JSONObject config=read(),saved=config.optJSONObject("naturalVoiceProvider");String slot=saved==null?"":saved.optString("secretSlot");
        config.remove("naturalVoiceProvider");config.put("revision",config.optLong("revision")+1);writeConfiguration(config);lifecycleEpoch++;invitation=null;invitationState="idle";
        if(!slot.isEmpty())credentials.remove(slot);emitState();return snapshot();
    }
    private static RealtimeVoiceProfile voiceProfile(JSONObject saved)throws Exception{return new RealtimeVoiceProfile(saved.getString("endpointUrl"),saved.getString("model"),saved.getString("voice"),saved.optString("transcriptionModel"),saved.getLong("revision"));}
    private synchronized JSONObject configureEndpointProvider(Activity owner,JSONObject input)throws Exception{
        requireConfigurationIdle(owner);if(endpointFactory==null)throw new IllegalStateException("正式服务端点适配器尚未接入");
        JSONObject config=read(),previous=config.optJSONObject("endpointProvider");long revision=previous==null?1:previous.optLong("revision")+1;
        RealtimeEndpointProfile profile=new RealtimeEndpointProfile(input.getString("endpointUrl"),input.getString("serverUrl"),input.getString("agentName"),revision);
        String oldSlot=previous==null?"":previous.optString("secretSlot"),slot="",fresh=null,auth=input.optString("authenticationToken");
        if(!auth.isEmpty()){validateToken(auth);fresh="realtime.endpoint."+UUID.randomUUID();credentials.put(fresh,auth);slot=fresh;}
        else if(previous!=null&&RealtimeEndpointProfile.origin(previous.getString("endpointUrl")).equals(RealtimeEndpointProfile.origin(profile.endpointUrl)))slot=oldSlot;
        if(slot.isEmpty()||!credentials.contains(slot))throw new IllegalArgumentException("更换认证服务origin后必须重新提供该服务的认证令牌；不会借用模型API key");
        config.put("endpointProvider",new JSONObject().put("endpointUrl",profile.endpointUrl).put("serverUrl",profile.serverUrl).put("agentName",profile.agentName).put("revision",revision).put("secretSlot",slot));config.put("revision",config.optLong("revision")+1);
        try{writeConfiguration(config);}catch(Exception failure){if(fresh!=null)credentials.remove(fresh);throw failure;}
        lifecycleEpoch++;invitation=null;invitationState="idle";providerError="";restoreEndpointProvider();emitState();
        if(!oldSlot.isEmpty()&&!oldSlot.equals(slot))credentials.remove(oldSlot);return snapshot();
    }
    private synchronized JSONObject clearEndpointProvider(Activity owner)throws Exception{
        requireConfigurationIdle(owner);JSONObject config=read(),saved=config.optJSONObject("endpointProvider");String slot=saved==null?"":saved.optString("secretSlot");config.remove("endpointProvider");config.put("revision",config.optLong("revision")+1);writeConfiguration(config);
        if(endpointProfile!=null&&provider!=null){provider.close();provider=null;}endpointProfile=null;invitation=null;invitationState="idle";providerError="";lifecycleEpoch++;emitState();if(!slot.isEmpty())credentials.remove(slot);return snapshot();
    }
    private void writeConfiguration(JSONObject value)throws Exception{FileOutputStream output=null;try{output=configuration.startWrite();output.write(value.toString().getBytes(StandardCharsets.UTF_8));configuration.finishWrite(output);}catch(Exception failure){if(output!=null)configuration.failWrite(output);throw failure;}}
    private JSONObject reject(Activity owner,JSONObject input)throws Exception{
        final RealtimeCallProvider source;final String id;
        synchronized(this){requireOwner(owner);requireInvitation(input);id=invitation.getString("id");source=invitation.optString("source").equals("provider_invitation")?provider:null;invitationState="rejected";invitationMessage="已拒绝，本次未启动采集";lifecycleEpoch++;emitState();}
        if(source!=null)try{source.reject(id).get(8,TimeUnit.SECONDS);}catch(Exception unavailable){synchronized(this){invitationMessage="已在本机拒绝；远端拒绝回执未确认";emitState();}}
        return snapshot();
    }
    private JSONObject accept(Activity owner,JSONObject input)throws Exception{
        final boolean mic=input.getBoolean("microphone"),camera=input.getBoolean("camera");
        final long epoch;final String inviteId,token;final RealtimeCallProvider source;final MediaController media;final JSONObject config;final RealtimeVoiceProfile natural;
        synchronized(this){
            requireOwner(owner);requireInvitation(input);requireCapturePermission(mic,camera);
            if(active()||accepting||!leaseToken.isEmpty()||voice.active())throw new IllegalStateException("另一个通话或语音轮仍在连接或释放");
            if(mediaOwner==null)throw new IllegalStateException("原生音频资源协调器未接入，未开始通话");
            media=mediaOwner;source=invitation.optString("source").matches("provider_invitation|owner_prepared_provider")?provider:null;
            if(invitation.optString("source").equals("owner_prepared_provider")&&(source==null||endpointProfile==null||invitation.optLong("configurationRevision")!=endpointProfile.revision))throw new IllegalStateException("通话服务配置已变化，请重新确认");
            config=read();natural=invitation.optString("source").equals("owner_prepared_natural_voice")?voiceProfile(config.getJSONObject("naturalVoiceProvider")):null;
            if(natural!=null){if(camera||naturalVoiceFactory==null||invitation.optLong("configurationRevision")!=natural.revision)throw new IllegalStateException("自然语音配置已变化或不支持摄像头");}
            else if(source==null&&invitation.optLong("configurationRevision")!=config.optLong("revision"))throw new IllegalStateException("连接配置已变化，请重新准备通话");
            token=natural!=null?credentials.get(config.getJSONObject("naturalVoiceProvider").getString("secretSlot")):source==null?credentials.get(config.getString("secretSlot")):"";inviteId=invitation.getString("id");
            providerError="";accepting=true;terminalReceived=false;invitationState="accepting";epoch=++lifecycleEpoch;sessionId=UUID.randomUUID().toString();taskSessionEpoch.incrementAndGet();voiceTasks.sessionChanged(sessionId);turns.start(sessionId);leaseToken=sessionId;leaseMedia=media;leaseAdapterEpoch=++adapterEpoch;
            main.removeCallbacks(permissionCheck);main.post(permissionCheck);emitState();
        }
        RealtimeMediaSession current=null;boolean transportStarted=false;String ownedLease;
        synchronized(this){ownedLease=leaseToken;}
        try{
            media.acquireRealtimeAudio(ownedLease).get(8,TimeUnit.SECONDS);
            String server=natural==null?config.optString("serverUrl"):natural.endpointUrl,participantToken=token;
            if(source!=null){
                RealtimeCallProvider.JoinCredentials resolved=source.accept(inviteId,mic,camera).get(20,TimeUnit.SECONDS);
                if(resolved==null||resolved.expiresAt<System.currentTimeMillis()+10_000)throw new IllegalStateException("服务未返回有效的短期参与者令牌");
                server=resolved.serverUrl;participantToken=resolved.participantToken;
            }
            validateServer(server,source==null&&config.optBoolean("allowLoopbackWs"));validateToken(participantToken);
            synchronized(this){
                checkEpoch(epoch);requireCapturePermission(mic,camera);
                if(session!=null)session.close();long generation=leaseAdapterEpoch;current=natural==null?factory.create(context.android,event->onSessionEvent(generation,event)):naturalVoiceFactory.create(context.android,event->onSessionEvent(generation,event),natural);session=current;
            }
            transportStarted=true;current.connect(server,participantToken,mic,camera).get(35,TimeUnit.SECONDS);
            synchronized(this){checkEpoch(epoch);invitationState="accepted";invitationMessage=natural==null?"已加入实际房间；远端身份与媒体按真实订阅显示":"自然语音已连接；打断只停止说话，不取消已有任务";return snapshot();}
        }catch(Exception failure){
            if(current!=null)current.disconnect("connect_incomplete");
            synchronized(this){if(epoch==lifecycleEpoch){turns.stop();invitationState="failed";invitationMessage="通话未建立，请检查服务、权限与网络；未启动模拟通话";Throwable cause=failure;for(int i=0;i<4&&cause!=null;i++,cause=cause.getCause())if(cause instanceof RealtimeCallProvider.ProviderFailure){providerError=((RealtimeCallProvider.ProviderFailure)cause).code;invitationMessage=cause.getMessage();break;}}}
            throw new IllegalStateException("通话未建立，请检查服务、令牌、权限与网络；没有自动重试");
        }finally{
            synchronized(this){if(!transportStarted)releaseLease(ownedLease,true);if(epoch==lifecycleEpoch||ownedLease.equals(sessionId))accepting=false;emitState();}
        }
    }
    private JSONObject changeCapture(Activity owner,boolean enabled,boolean camera,JSONObject input)throws Exception{
        if(enabled)context.requireExecutionEnabled();
        final RealtimeMediaSession current;final long epoch;
        synchronized(this){requireOwner(owner);requireSession(input);if(session==null||!session.snapshot().optString("state").equals("connected")||accepting||changingCapture)throw new IllegalStateException("通话尚未就绪或采集操作正在处理");if(enabled)requireCapturePermission(!camera,camera);current=session;epoch=lifecycleEpoch;changingCapture=true;}
        try{
            (camera?current.setCameraEnabled(enabled):current.setMicrophoneEnabled(enabled)).get(8,TimeUnit.SECONDS);
            synchronized(this){checkEpoch(epoch);return snapshot();}
        }catch(Exception failure){synchronized(this){if(session==current)stopSession("capture_change_failed");else current.disconnect("capture_change_failed");}throw new IllegalStateException("采集设置未确认，本次通话已停止；已收到的文字保留供你复核");}
        finally{synchronized(this){changingCapture=false;emitState();}}
    }
    private synchronized JSONObject configure(Activity owner,JSONObject input)throws Exception{
        requireOwner(owner);if(closed||active()||accepting||!leaseToken.isEmpty())throw new IllegalStateException("请先挂断，再更改通话配置");
        String server=input.getString("serverUrl").trim();boolean loopback=input.optBoolean("allowLoopbackWs");URI target=validateServer(server,loopback);
        JSONObject old=read();String previous=old.optString("secretSlot"),slot="",newSlot=null,token=input.optString("participantToken").trim();
        if(!token.isEmpty())validateToken(token);
        if(!token.isEmpty()){newSlot="realtime."+UUID.randomUUID();credentials.put(newSlot,token);slot=newSlot;}
        else if(!input.optBoolean("clearToken")&&!old.optString("serverUrl").isEmpty()&&sameOrigin(validateServer(old.getString("serverUrl"),old.optBoolean("allowLoopbackWs")),target))slot=previous;
        JSONObject next=new JSONObject().put("endpointProvider",old.optJSONObject("endpointProvider")).put("naturalVoiceProvider",old.optJSONObject("naturalVoiceProvider")).put("serverUrl",server).put("allowLoopbackWs",loopback).put("secretSlot",slot).put("revision",old.optLong("revision")+1);
        FileOutputStream output=null;try{output=configuration.startWrite();output.write(next.toString().getBytes(StandardCharsets.UTF_8));configuration.finishWrite(output);}catch(Exception error){if(output!=null)configuration.failWrite(output);if(newSlot!=null)credentials.remove(newSlot);throw error;}
        if(!previous.isEmpty()&&!previous.equals(slot))credentials.remove(previous);lifecycleEpoch++;invitation=null;invitationState="idle";return snapshot();
    }
    private synchronized void onSessionEvent(long generation,JSONObject event){
        if(event.optString("state").matches("disconnected|failed")){
            if(generation!=leaseAdapterEpoch)return;
            if(generation==adapterEpoch){adapterEpoch++;lifecycleEpoch++;}
            terminalReceived=true;releaseLease(leaseToken,!event.optBoolean("resourceReleaseUnknown"));turns.stop();
            if(!closed)emitState();return;
        }
        if(closed||generation!=adapterEpoch)return;
        if(event.optString("kind").equals("transcription")){
            if(!visible()||!active())return;
            try{if(turns.accept(event))emitState();}catch(Exception malformed){/* Untrusted incomplete captions cannot enter the owner task path. */}
            return;
        }
        if(event.optString("state").equals("reconnecting"))turns.reconnecting();
        else if(event.optString("state").equals("connected"))turns.reconnected();
        if(!visible()&&(event.optBoolean("microphoneEnabled")||event.optBoolean("cameraEnabled")||event.optString("state").matches("connecting|connected|reconnecting"))){if(session!=null)session.disconnect("owner_not_visible");return;}
        emitState();
    }
    private JSONObject commitTurn(Activity owner,JSONObject input,boolean reconcile)throws Exception{
        return commitTurn(owner,input,reconcile,false);
    }
    private JSONObject commitTurn(Activity owner,JSONObject input,boolean reconcile,boolean startTask)throws Exception{
        final RealtimeTurnCommitter committer;final RealtimeReviewedTurn reviewed;
        final VoiceTaskHostPort.Authority taskAuthority;
        synchronized(this){
            requireOwner(owner);committer=turnCommitter;if(committer==null)throw new IllegalStateException("任务存储尚未接入");
            if(startTask&&!voiceTasks.installed())throw new IllegalStateException("语音任务宿主尚未接入，可以先保存原目标");
            if(reconcile)reviewed=turns.pending(input.getString("commitId"));
            else{requireReviewSession(input);reviewed=turns.beginCommit(sessionId,input.getString("reviewId"),input.getString("contentHash"));}
            taskAuthority=taskAuthority(owner);
            emitState();
        }
        final JSONObject task;
        try{
            // This callback persists only a waiting task. Hangup after this acceptance never cancels that task.
            task=reconcile?committer.lookup(reviewed):committer.create(reviewed);
            synchronized(this){if(task==null){turns.unknown(reviewed);return new JSONObject().put("status","unconfirmed").put("commitId",reviewed.commitId).put("message","原提交未查到，不会自动重建或执行，请检查任务存储");}turns.completed(reviewed,task);}
        }catch(Exception uncertain){synchronized(this){turns.unknown(reviewed);}throw new IllegalStateException("语音任务保存结果未确认，请核对原提交，不要重复创建");}
        finally{synchronized(this){emitState();}}
        String taskNotice="";
        if(voiceTasks.installed())try{
            taskAuthority.check();synchronized(this){if(!reviewed.sessionId.equals(sessionId))throw new IllegalStateException("原语音会话已变化");}
            JSONObject existing=voiceTasks.snapshot();
            if(reviewed.commitId.equals(existing.optString("lastTaskId"))&&!existing.optString("bindingId").isEmpty())voiceTasks.inspect(existing.getString("bindingId"));
            else voiceTasks.attachSaved(reviewed,taskAuthority);
            if(startTask){JSONObject view=voiceTasks.snapshot();voiceTasks.startOriginal(view.getString("bindingId"),view.getString("viewToken"));}
        }catch(Exception unavailable){taskNotice="目标已保存；开始或查看结果未确认，请打开原任务核对，没有自动重跑";}
        emitState();return new JSONObject().put("task",task).put("status","saved_waiting_for_owner").put("automaticallyExecutes",false).put("ownerRequestedTaskStart",startTask).put("message",taskNotice).put("sourceSessionId",reviewed.sessionId).put("voiceTask",voiceTasks.snapshot());
    }
    private VoiceTaskHostPort.Authority taskAuthority(Activity owner){
        final long ownerEpoch=taskOwnerEpoch.get(),sessionEpoch=taskSessionEpoch.get();final WeakReference<Activity> captured=new WeakReference<>(owner);
        final java.util.function.BooleanSupplier window=reviewedWindow.get();
        return ()->{Activity current=captured.get();if(current==null||current.isDestroyed()||current.isFinishing()||ownerEpoch!=taskOwnerEpoch.get()||sessionEpoch!=taskSessionEpoch.get()||window!=null&&!window.getAsBoolean())throw new SecurityException("原生语音主人或会话已变化");};
    }
    private JSONObject readVoiceTask(Activity owner,JSONObject input)throws Exception{
        final MediaController media;synchronized(this){requireOwner(owner);if(active()||accepting||pendingInvitation()||!leaseToken.isEmpty()||voice.active()||mediaOwner==null)throw new IllegalStateException("请先结束当前语音并等待音频释放，再朗读原任务记录");media=mediaOwner;}
        voiceTasks.readResult(input.getString("bindingId"),input.getString("viewToken"),new VoiceTaskReadout.Audio(){
            public java.util.concurrent.CompletableFuture<String> acquire(String token){return media.acquireRealtimeAudio(token);}
            public java.util.concurrent.CompletableFuture<Void> speak(String token,String text,dev.pocketengine.core.RunControl authority){return media.speakOwnedTaskResult(token,text,authority);}
            public java.util.concurrent.CompletableFuture<Void> stop(String token){return media.stopRealtimeTurn(token);}
            public void release(String token,boolean confirmed){media.releaseRealtimeAudio(token,confirmed);}
            public boolean releaseUnknown(){return media.audioReleaseUnknown();}
        });emitState();return snapshot();
    }
    private boolean reviewSessionReady(){
        if(accepting||session==null)return false;String state=session.snapshot().optString("state");
        return state.equals("connected")&&adapterEpoch==leaseAdapterEpoch||terminalReceived&&state.matches("disconnected|failed");
    }
    private void requireReviewSession(JSONObject input)throws Exception{requireSession(input);if(!reviewSessionReady())throw new IllegalStateException("当前通话正在连接、恢复或收尾，请稍后重新复核保留的最终文字");}
    private void stopSession(String reason){lifecycleEpoch++;adapterEpoch++;turns.stop();if(session!=null)session.disconnect(reason);}
    private void releaseLease(String owner,boolean confirmed){if(owner.isEmpty()||!owner.equals(leaseToken))return;MediaController media=leaseMedia;if(media!=null)media.releaseRealtimeAudio(owner,confirmed);if(confirmed){leaseToken="";leaseMedia=null;}}
    private void requireReviewedWindow(){java.util.function.BooleanSupplier window=reviewedWindow.get();if(window!=null&&!window.getAsBoolean())throw new SecurityException("原通话窗口已关闭，请重新核对");}
    private void requireOwner(Activity owner){requireReviewedWindow();if(owner==null||owner!=visibleOwner.get())throw new SecurityException("原生主人界面已变化");requireVisible();}
    private void checkEpoch(long epoch){requireReviewedWindow();if(epoch!=lifecycleEpoch||!visible()||closed)throw new IllegalStateException("通话确认已失效");}
    private void requireSession(JSONObject input)throws Exception{if(sessionId.isEmpty()||!sessionId.equals(input.getString("sessionId")))throw new IllegalStateException("通话已变化，请操作当前通话");}
    private void requireInvitation(JSONObject input)throws Exception{requireVisible();expireInvitation();if(!pendingInvitation()||!invitation.getString("id").equals(input.getString("invitationId")))throw new IllegalStateException("邀请已变化、处理或过期，请重新确认");}
    private boolean pendingInvitation(){return invitation!=null&&invitationState.equals("awaiting_owner");}
    private void expireInvitation(){if(pendingInvitation()&&invitation.optLong("expiresAt")<=System.currentTimeMillis()){invitationState="expired";invitationMessage="通话邀请已过期，没有开始采集";}}
    private boolean active(){return session!=null&&session.snapshot().optString("state").matches("connecting|connected|reconnecting|disconnecting");}
    private void requireCapturePermission(boolean mic,boolean camera){requireVisible();if(mic&&!microphoneGranted())throw new SecurityException("请先授予麦克风权限，再接受通话");if(camera&&!cameraGranted())throw new SecurityException("请先授予摄像头权限，再接受通话");}
    private synchronized void emitState(){snapshotRevision++;try{context.emit(id(),new JSONObject().put("kind","realtime_state").put("snapshot",snapshot()));}catch(Exception ignored){}}
    private Activity requireVisible(){if(closed)throw new IllegalStateException("实时通话模块已关闭");Activity owner=visibleOwner.get();if(!visible())throw new SecurityException("请在可见的原生通话界面确认");return owner;}
    private boolean visible(){Activity owner=visibleOwner.get();return owner!=null&&!owner.isDestroyed()&&!owner.isFinishing();}
    private boolean microphoneGranted(){return context.android.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private boolean cameraGranted(){return context.android.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}
    private JSONObject read()throws Exception{try{return new JSONObject(new String(configuration.readFully(),StandardCharsets.UTF_8));}catch(java.io.FileNotFoundException absent){return new JSONObject();}}
    private static void validateToken(String token){if(token==null||token.isBlank()||token.length()>16384||token.chars().anyMatch(Character::isWhitespace))throw new IllegalArgumentException("参与者令牌无效");}
    private static String bounded(String value,int max){return value==null?"":value.substring(0,Math.min(value.length(),max));}
    static URI validateServer(String value,boolean allowLoopback)throws Exception{URI uri=new URI(value);String host=uri.getHost();boolean local=host!=null&&(host.equalsIgnoreCase("localhost")||host.equals("127.0.0.1")||host.equals("[::1]")||host.equals("::1"));if(host==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null||!("wss".equalsIgnoreCase(uri.getScheme())||allowLoopback&&local&&"ws".equalsIgnoreCase(uri.getScheme())))throw new IllegalArgumentException("通话需要wss服务器地址；仅明确选择的本机回环测试允许ws");return uri;}
    private static boolean sameOrigin(URI a,URI b){return a.getScheme().equalsIgnoreCase(b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b);}
    private static int port(URI uri){return uri.getPort()<0?(uri.getScheme().equalsIgnoreCase("wss")?443:80):uri.getPort();}
    @Override public synchronized void close(){taskOwnerEpoch.incrementAndGet();voiceTasks.close();cancelProviderAccept();voice.close();closed=true;lifecycleEpoch++;turns.stop();if(provider!=null)provider.close();visibleOwner.clear();mediaOwner=null;if(session!=null){session.disconnect("host_closed");session.close();}main.removeCallbacksAndMessages(null);}
}
