package dev.pocketengine.phone.setup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.phone.AndroidPhoneReadiness;
import dev.pocketengine.phone.access.*;
import dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport;
import dev.pocketengine.phone.display.DisplayModule;
import dev.pocketengine.phone.display.adb.LocalAdbEndpoints;
import org.json.JSONObject;

/** Trusted local-owner setup/settings only. None of its commands are registered as model tools. */
public final class PhoneSetupModule implements EngineModule {
    private static volatile PhoneSetupModule instance;
    private static final int NOTICE=42031;private static final String CHANNEL="phone_pairing_input";
    private final FeatureContext context;private final DisplayModule display;private final AndroidPhoneAccessStore access;private final AccessibilityRecovery recovery;private final PairingEntrySession pairing=new PairingEntrySession(android.os.SystemClock::elapsedRealtime);private final Handler main=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.Callable<JSONObject> originalPractice;
    private volatile boolean closed;
    public PhoneSetupModule(FeatureContext context,DisplayModule display){this(context,display,()->null);}
    public PhoneSetupModule(FeatureContext context,DisplayModule display,java.util.concurrent.Callable<JSONObject> originalPractice){this.context=context;this.display=display;this.originalPractice=java.util.Objects.requireNonNull(originalPractice);access=new AndroidPhoneAccessStore(context.android);recovery=new AccessibilityRecovery(context.android);instance=this;}
    static PhoneSetupModule current(){return instance;}
    @Override public String id(){return "phone_setup";}
    @Override public JSONObject snapshot()throws Exception {return new JSONObject().put("pairing",pairing.snapshot()).put("access",access.snapshot()).put("recovery",recovery.snapshot()).put("clipboardRecoveryPending",AndroidClipboardRecovery.create(context.android).pending()).put("clipboardCompatibilityEnabled",AndroidClipboardRecovery.allowed(context.android)).put("audioSilenceEnabled",AndroidPhoneAudioRecovery.silenceEnabled(context.android)).put("modelCommandsAvailable",false);}
    @Override public synchronized JSONObject handle(String command,JSONObject input)throws Exception {
        if(closed)throw new SecurityException("phone_setup_closed");if(input==null)input=new JSONObject();
        switch(command){
            case "status":return snapshot();
            case "pairing.begin":{String host=LocalAdbEndpoints.current().check(input.optString("host","127.0.0.1"),input.getInt("pairingPort"));JSONObject state=display.snapshot();if(state.optBoolean("taskOwned")||state.optBoolean("bootstrapBusy")||state.optBoolean("sessionActive")||state.optBoolean("releasePending"))throw new SecurityException("phone_setup_resource_busy");PairingEntrySession.Entry entry=pairing.begin(host,input.getInt("pairingPort"));show(entry);main.postDelayed(()->expire(entry.id()),120000);return snapshot();}
            case "apps.list":return new AndroidAppCopies(context.android).list(new dev.pocketengine.core.RunControl());
            case "pairing.cancel":pairing.cancel();cancelNotice();return snapshot();
            case "access.set":access.setOwnerRule(new AppCopyKey(input.getString("packageName"),input.getInt("userId")),input.getBoolean("automation"),input.getBoolean("ownerShare"),input.getLong("expectedRevision"));context.emit(id(),new JSONObject().put("event","phone_app_policy_changed").put("revision",access.current().revision()));return snapshot();
            case "recovery.consent":return recovery.setConsent(input.getBoolean("enabled"),input.getLong("expectedRevision"));
            case "recovery.restore":return recovery.restore();
            case "clipboard.restore":return new JSONObject().put("restored",AndroidClipboardRecovery.create(context.android).recover()).put("containsClipboardContent",false);
            case "clipboard.policy":AndroidClipboardRecovery.setOwnerAllowed(context.android,input.getBoolean("enabled"));return snapshot();
            case "audio.policy":AndroidPhoneAudioRecovery.setOwnerSilenceEnabled(context.android,input.getBoolean("enabled"));return snapshot();
            case "audio.restore":if(display.snapshot().optBoolean("taskOwned"))throw new SecurityException("stop_phone_task_before_audio_recovery");return AndroidPhoneAudioRecovery.get(context.android,display).restoreAll();
            case "diagnostics.preview":return diagnostic();
            default:throw new IllegalArgumentException("phone_setup_command_unavailable");
        }
    }
    private JSONObject diagnostic()throws Exception {
        JSONObject version=new JSONObject();try(java.io.InputStream input=context.android.getAssets().open("phone-build-identity.json")){java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[1024];int n;while((n=input.read(chunk))!=-1){if(buffer.size()+n>4096)throw new SecurityException("build_identity_too_large");buffer.write(chunk,0,n);}JSONObject identity=new JSONObject(new String(buffer.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));version.put("commit",identity.optString("commit")).put("tree",identity.optString("tree"));}catch(Exception unknown){}
        try{version.put("versionCode",context.android.getPackageManager().getPackageInfo(context.android.getPackageName(),0).getLongVersionCode());}catch(Exception unknown){}
        JSONObject practice=null;try{practice=originalPractice.call();}catch(Exception unavailable){}
        return PhoneDiagnosticReport.create(version,new JSONObject().put("sdkInt",android.os.Build.VERSION.SDK_INT).put("abi",android.os.Build.SUPPORTED_ABIS.length==0?"":android.os.Build.SUPPORTED_ABIS[0]),display.snapshot(),AndroidPhoneReadiness.snapshot(context.android,display),practice);
    }
    private synchronized void submitCode(String session,String code)throws Exception {if(closed)throw new SecurityException("phone_setup_closed");JSONObject request=pairing.submit(session,code);cancelNotice();try{display.handle("adb.pair",request);}finally{request.remove("pairingCode");}}
    void submitNotification(String session,String code){main.post(()->{try{submitCode(session,code);context.emit(id(),new JSONObject().put("event","pairing_code_submitted"));}catch(Exception refused){context.emit(id(),new JSONObject().put("event","pairing_entry_refused").put("message","输入窗口已失效、当前码无效或资源正在使用，请回手机设置重新开始。"));}});}
    private void show(PairingEntrySession.Entry entry)throws Exception {
        NotificationManager manager=context.android.getSystemService(NotificationManager.class);if(manager==null||!manager.areNotificationsEnabled())throw new SecurityException("pairing_notification_unavailable");manager.createNotificationChannel(new NotificationChannel(CHANNEL,"手机配对输入",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(context.android,PhoneSetupReceiver.class).setAction("dev.pocketengine.PAIR_CODE").setData(Uri.parse("pocketworld-internal://pair/"+entry.id())).putExtra("pairingSessionId",entry.id());
        PendingIntent pending=PendingIntent.getBroadcast(context.android,NOTICE,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);RemoteInput input=new RemoteInput.Builder("pairing_code").setLabel("系统当前的六位配对码").build();
        Notification.Action action=new Notification.Action.Builder(android.R.drawable.ic_lock_lock,"输入当前配对码",pending).addRemoteInput(input).build();
        manager.notify(NOTICE,new Notification.Builder(context.android,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("手机无线调试配对").setContentText("仅本次两分钟输入窗口有效。配对成功后仍需连接并核对助手。").setVisibility(Notification.VISIBILITY_SECRET).setTimeoutAfter(120000).setOnlyAlertOnce(true).addAction(action).build());
    }
    private synchronized void expire(String expected){try{JSONObject state=pairing.snapshot();if(!state.optBoolean("active")||expected.equals(state.optString("sessionId"))){pairing.cancel();cancelNotice();}}catch(Exception ignored){}}
    private void cancelNotice(){NotificationManager manager=context.android.getSystemService(NotificationManager.class);if(manager!=null)manager.cancel(NOTICE);}
    @Override public synchronized void close(){closed=true;pairing.cancel();cancelNotice();if(instance==this)instance=null;}
}
