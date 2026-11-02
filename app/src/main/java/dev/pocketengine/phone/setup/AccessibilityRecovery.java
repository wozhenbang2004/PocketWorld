package dev.pocketengine.phone.setup;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.provider.Settings;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Owner-revocable repair, only with an already granted Android secure-settings permission. No task replay. */
public final class AccessibilityRecovery {
    private static final String PERMISSION="android.permission.WRITE_SECURE_SETTINGS";
    private final Context context;private final SharedPreferences preferences;
    public AccessibilityRecovery(Context context){this.context=context.getApplicationContext();preferences=this.context.getSharedPreferences("phone_connection_recovery",0);}
    public synchronized JSONObject setConsent(boolean enabled,long expectedRevision)throws Exception {
        if(expectedRevision!=preferences.getLong("revision",0))throw new SecurityException("recovery_consent_changed");
        if(!preferences.edit().putBoolean("consent",enabled).putLong("revision",Math.addExact(expectedRevision,1)).commit())throw new IllegalStateException("recovery_consent_save_failed");return snapshot();
    }
    public synchronized JSONObject snapshot()throws Exception {return new JSONObject().put("consent",preferences.getBoolean("consent",false)).put("revision",preferences.getLong("revision",0)).put("secureSettingsGranted",context.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED).put("accessibilityConnected",PhoneAccessibilityService.get()!=null).put("lastOutcome",preferences.getString("lastOutcome","not_requested")).put("restoresBusinessActions",false).put("changesWirelessDebugging",false);}
    public synchronized JSONObject restore()throws Exception {
        if(!preferences.getBoolean("consent",false))return outcome("consent_required",false);
        UserManager users=context.getSystemService(UserManager.class);if(users==null||!users.isUserUnlocked())return outcome("owner_unlock_required",false);
        if(context.checkSelfPermission(PERMISSION)!=PackageManager.PERMISSION_GRANTED)return outcome("secure_settings_permission_missing",false);
        if(PhoneAccessibilityService.get()!=null)return outcome("already_connected",true);
        long consentRevision=preferences.getLong("revision",0);
        String own=new ComponentName(context,PhoneAccessibilityService.class).flattenToString();
        boolean removed=false;
        try{
            List<String> entries=read();boolean present=entries.removeIf(value->same(value,own));
            if(present){requireConsent(consentRevision);removed=true;if(!write(entries,consentRevision))return outcome("remove_unconfirmed",false);}
            // Read again after every write, preserving services added by Android or another owner.
            List<String> fresh=read();if(fresh.stream().noneMatch(value->same(value,own)))fresh.add(own);
            if(!write(fresh,consentRevision))return outcome("restore_unconfirmed",false);
            boolean registered=read().stream().anyMatch(value->same(value,own));return outcome(registered?"registered_waiting_for_binding":"restore_unconfirmed",registered);
        }catch(Exception unavailable){return outcome("platform_refused",false);}
        finally{if(removed)try{List<String> current=read();if(current.stream().noneMatch(value->same(value,own))){current.add(own);writeCompensation(current);}}catch(Exception pending){/* The stored failure remains visible; no other service entry is replaced. */}}
    }
    private List<String> read(){String encoded=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);List<String> out=new ArrayList<>();if(encoded!=null&&!encoded.isEmpty())for(String entry:encoded.split(":"))if(!entry.isEmpty())out.add(entry);return out;}
    private void requireConsent(long expected){if(!preferences.getBoolean("consent",false)||preferences.getLong("revision",0)!=expected)throw new SecurityException("recovery_consent_revoked");}
    private boolean write(List<String> entries,long consentRevision){requireConsent(consentRevision);String encoded=String.join(":",entries);if(!Settings.Secure.putString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,encoded))return false;requireConsent(consentRevision);return Settings.Secure.putInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,entries.isEmpty()?0:1);}
    /** Only undo this repair's prior removal, even if later work was revoked. */
    private boolean writeCompensation(List<String> entries){String encoded=String.join(":",entries);if(!Settings.Secure.putString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,encoded))return false;return Settings.Secure.putInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,entries.isEmpty()?0:1);}
    private static boolean same(String a,String b){ComponentName left=ComponentName.unflattenFromString(a),right=ComponentName.unflattenFromString(b);return left!=null&&left.equals(right);}
    private JSONObject outcome(String state,boolean registered)throws Exception {if(!preferences.edit().putString("lastOutcome",state).commit())throw new IllegalStateException("recovery_result_save_failed");return snapshot().put("registrationVerified",registered).put("bindingVerified",PhoneAccessibilityService.get()!=null);}
}
