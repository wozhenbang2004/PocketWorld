package dev.pocketengine.phone.setup;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only LauncherApps' accessible related profiles. Private spaces/other users are never scanned. */
public final class AndroidAppCopies {
    private record Copy(String ref,String component,int user,long serial,long listedAt,int uid,String installIdentity,long installedAt) { }
    private final Context context;private final Map<String,Copy> listed=new LinkedHashMap<>();
    public AndroidAppCopies(Context context){this.context=context.getApplicationContext();}
    public synchronized JSONObject list(RunControl control)throws Exception {
        control.check();listed.clear();LauncherApps launcher=context.getSystemService(LauncherApps.class);UserManager users=context.getSystemService(UserManager.class);
        if(launcher==null||users==null)return result(new JSONArray(),false,"profile_service_unavailable");JSONArray out=new JSONArray();boolean complete=true;
        for(UserHandle user:launcher.getProfiles()){
            control.check();long serial=users.getSerialNumberForUser(user);if(serial<0){complete=false;continue;}int id=id(user);
            try{if(!users.isUserUnlocked(user)){complete=false;continue;}for(LauncherActivityInfo activity:launcher.getActivityList(null,user)){
                control.check();if(out.length()>=200){complete=false;break;}ComponentName component=activity.getComponentName();String ref=UUID.randomUUID().toString();Copy copy=new Copy(ref,component.flattenToString(),id,serial,SystemClock.uptimeMillis(),activity.getApplicationInfo().uid,installIdentity(activity),activity.getFirstInstallTime());listed.put(ref,copy);
                String label=String.valueOf(activity.getLabel());if(label.length()>200)label=label.substring(0,200);out.put(new JSONObject().put("copyRef",ref).put("packageName",component.getPackageName()).put("component",component.flattenToString()).put("userId",id).put("profileSerial",serial).put("label",label).put("labelIsUntrusted",true));}
            }catch(SecurityException unavailable){complete=false;}
        }
        return result(out,complete,complete?"accessible_related_profiles":"some_profiles_unavailable");
    }
    public synchronized JSONObject require(JSONObject args)throws Exception {
        Copy copy=listed.get(args.optString("copyRef"));if(copy==null||SystemClock.uptimeMillis()-copy.listedAt>60000||!copy.component.startsWith(args.getString("packageName")+"/")||copy.user!=args.getInt("userId"))throw new SecurityException("app_copy_reference_expired");
        LauncherApps launcher=context.getSystemService(LauncherApps.class);UserManager users=context.getSystemService(UserManager.class);if(launcher==null||users==null)throw new SecurityException("profile_service_unavailable");
        for(UserHandle user:launcher.getProfiles())if(id(user)==copy.user&&users.getSerialNumberForUser(user)==copy.serial&&users.isUserUnlocked(user))for(LauncherActivityInfo current:launcher.getActivityList(args.getString("packageName"),user))if(current.getComponentName().flattenToString().equals(copy.component)&&current.getApplicationInfo().uid==copy.uid&&installIdentity(current).equals(copy.installIdentity)&&current.getFirstInstallTime()==copy.installedAt)return new JSONObject().put("packageName",args.getString("packageName")).put("userId",copy.user).put("profileSerial",copy.serial).put("component",copy.component).put("uid",copy.uid).put("installIdentity",copy.installIdentity).put("firstInstallTime",copy.installedAt);
        throw new SecurityException("app_copy_no_longer_accessible");
    }
    public JSONObject launch(JSONObject args,int display,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        JSONObject exact=require(args);long revision=control.pauseRevision();LauncherApps launcher=context.getSystemService(LauncherApps.class);UserHandle target=null;for(UserHandle user:launcher.getProfiles())if(id(user)==exact.getInt("userId"))target=user;if(target==null)throw new SecurityException("app_copy_unavailable");final UserHandle user=target;
        return NativePhoneEntry.call(control,revision,admission,()->{require(args);launcher.startMainActivity(ComponentName.unflattenFromString(exact.getString("component")),user,null,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());return new JSONObject().put("ok",true).put("accepted",true).put("verified",false).put("requestedUserId",exact.getInt("userId")).put("packageName",exact.getString("packageName")).put("displayId",display).put("requiresPostconditionCheck",true);});
    }
    private String installIdentity(LauncherActivityInfo activity)throws Exception {
        android.content.pm.PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(activity.getApplicationInfo().sourceDir,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null||archive.signingInfo==null||!activity.getComponentName().getPackageName().equals(archive.packageName))throw new SecurityException("app_copy_install_identity_unavailable");
        java.util.List<String> signers=new java.util.ArrayList<>();for(android.content.pm.Signature signer:archive.signingInfo.getApkContentsSigners())signers.add(java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())));signers.sort(String::compareTo);
        if(signers.isEmpty())throw new SecurityException("app_copy_signer_unavailable");String raw=archive.packageName+":"+archive.getLongVersionCode()+":"+String.join(",",signers);return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static JSONObject result(JSONArray rows,boolean complete,String reason)throws Exception{return new JSONObject().put("ok",true).put("apps",rows).put("complete",complete).put("reason",reason).put("scope","android_accessible_related_launcher_profiles").put("expiresInMs",60000).put("automaticProfileFallback",false);}
    /** SDK35 does not expose getIdentifier. Verify a hash candidate by public UserHandle value equality. */
    public static int id(UserHandle handle){int id=handle.hashCode();if(id<0||id>21474||!handle.equals(UserHandle.getUserHandleForUid(id*100000)))throw new SecurityException("android_profile_identity_unavailable");return id;}
}
