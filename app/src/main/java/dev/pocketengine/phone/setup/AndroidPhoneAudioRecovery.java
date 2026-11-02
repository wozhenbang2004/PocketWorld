package dev.pocketengine.phone.setup;

import android.content.Context;
import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.display.DisplayModule;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** Single private restoration ledger shared by all original phone sessions. */
public final class AndroidPhoneAudioRecovery {
    private static final Map<Context,PhoneAudioRecovery> INSTANCES=new WeakHashMap<>();
    @FunctionalInterface public interface Commands {JSONObject call(String command,JSONObject payload)throws Exception;}
    @FunctionalInterface public interface Body<T> {T run()throws Exception;}
    private static final ThreadLocal<Commands> ADMITTED=new ThreadLocal<>();
    private AndroidPhoneAudioRecovery() { }
    public static synchronized PhoneAudioRecovery get(Context context,DisplayModule display)throws Exception {
        Context app=context.getApplicationContext();PhoneAudioRecovery existing=INSTANCES.get(app);if(existing!=null)return existing;
        android.content.SharedPreferences preferences=app.getSharedPreferences("phone_audio_recovery",0);
        PhoneAudioRecovery.Store storage=new PhoneAudioRecovery.Store(){public String read(){return preferences.getString("ledger","");}public void write(String value){if(!preferences.edit().putString("ledger",value).commit())throw new IllegalStateException("audio_restoration_save_failed");}};
        PhoneAudioRecovery.Audio audio=new PhoneAudioRecovery.Audio(){private JSONObject call(String command,JSONObject args)throws Exception {Commands admitted=ADMITTED.get();return admitted==null?display.request(command,args,null):admitted.call(command,args);}public String read(AppCopyKey app)throws Exception {JSONObject result=call("audio.get",arguments(app));if(!result.optBoolean("ok")||result.isNull("mode"))throw new SecurityException("audio_mode_unavailable");return result.getString("mode");}public void write(AppCopyKey app,String mode)throws Exception {JSONObject result=call("audio.set",arguments(app).put("mode",mode));if(!result.optBoolean("ok"))throw new SecurityException("audio_write_unconfirmed");}private JSONObject arguments(AppCopyKey app)throws Exception{return new JSONObject().put("packageName",app.packageName()).put("userId",app.userId());}};
        PhoneAudioRecovery created=new PhoneAudioRecovery(storage,audio);INSTANCES.put(app,created);return created;
    }
    public static boolean silenceEnabled(Context context){return context.getApplicationContext().getSharedPreferences("phone_audio_policy",0).getBoolean("enabled",false);}
    public static <T>T withCommands(Commands commands,Body<T> body)throws Exception {Commands old=ADMITTED.get();ADMITTED.set(commands);try{return body.run();}finally{if(old==null)ADMITTED.remove();else ADMITTED.set(old);}}
    public static void setOwnerSilenceEnabled(Context context,boolean enabled){if(!context.getApplicationContext().getSharedPreferences("phone_audio_policy",0).edit().putBoolean("enabled",enabled).commit())throw new IllegalStateException("phone_audio_policy_save_failed");}
}
