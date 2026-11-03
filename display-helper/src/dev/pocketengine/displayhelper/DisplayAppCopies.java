package dev.pocketengine.displayhelper;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import org.json.JSONObject;

/** Rechecks an actually accessible profile at the native call fence; never falls back to user zero. */
final class DisplayAppCopies {
    private record Target(LauncherApps launcher,ComponentName component,UserHandle user) { }
    static JSONObject launch(Context context,int display,JSONObject exact,DisplayTypedIntent.Gate gate)throws Exception {
        Target target=resolve(context,display,exact);int wanted=exact.getInt("userId");String pkg=exact.getString("packageName");
        gate.check();gate.requireAppFree(pkg,wanted);gate.check();target=resolve(context,display,exact);gate.check();
        if(android.os.SystemClock.uptimeMillis()>=exact.getLong("referenceExpiresAtUptimeMs"))throw new SecurityException("app_copy_reference_expired");
        try{target.launcher.startMainActivity(target.component,target.user,null,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
        catch(android.content.ActivityNotFoundException|SecurityException refused){return new JSONObject().put("ok",false).put("accepted",false).put("error","app_copy_platform_refused").put("automaticProfileFallback",false);}
        catch(Exception unknown){return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","app_copy_outcome_unknown").put("requiresReconciliation",true).put("automaticReplayAllowed",false);}
        return new JSONObject().put("ok",true).put("accepted",true).put("requestedUserId",wanted).put("profileSerial",exact.getLong("profileSerial"))
                .put("packageName",pkg).put("component",target.component.flattenToString()).put("displayId",display)
                .put("actualUserVerified",false).put("verifiedBusinessSuccess",false).put("requiresPostconditionCheck",true).put("automaticProfileFallback",false);
    }
    private static Target resolve(Context context,int display,JSONObject exact)throws Exception {
        if(display<=0||display!=exact.getInt("displayId"))throw new SecurityException("background_display_mismatch");
        long expires=exact.getLong("referenceExpiresAtUptimeMs"),now=SystemClock.uptimeMillis();
        if(expires<=now||expires-now>60_000)throw new SecurityException("app_copy_reference_expired");
        int wanted=exact.getInt("userId");String pkg=exact.getString("packageName");
        if(wanted<0||wanted>21474||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new SecurityException("copy_identity_invalid");
        ComponentName component=ComponentName.unflattenFromString(exact.getString("component"));
        if(component==null||!pkg.equals(component.getPackageName()))throw new SecurityException("copy_component_mismatch");
        LauncherApps launcher=context.getSystemService(LauncherApps.class);UserManager users=context.getSystemService(UserManager.class);
        if(launcher==null||users==null)throw new SecurityException("profile_service_unavailable");
        for(UserHandle user:launcher.getProfiles()) {
            if(user.hashCode()!=wanted||!user.equals(UserHandle.getUserHandleForUid(wanted*100000))||users.getSerialNumberForUser(user)!=exact.getLong("profileSerial")
                    ||!(user.equals(android.os.Process.myUserHandle())?users.isUserUnlocked():users.isUserUnlocked(user)))continue;
            for(LauncherActivityInfo activity:launcher.getActivityList(pkg,user)) {
                if(!component.equals(activity.getComponentName())||activity.getApplicationInfo()==null||!activity.getApplicationInfo().enabled)continue;
                int uid=activity.getApplicationInfo().uid;
                if(uid!=exact.getInt("uid")||uid/100000!=wanted||activity.getFirstInstallTime()!=exact.getLong("packageFirstInstallTime")
                        ||!installIdentity(context,activity).equals(exact.getString("installIdentity")))throw new SecurityException("copy_install_identity_changed");
                return new Target(launcher,component,user);
            }
        }
        throw new SecurityException("app_copy_unavailable");
    }
    private static String installIdentity(Context context,LauncherActivityInfo activity)throws Exception {
        android.content.pm.PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(activity.getApplicationInfo().sourceDir,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null||archive.signingInfo==null||!activity.getComponentName().getPackageName().equals(archive.packageName))throw new SecurityException("copy_install_identity_unavailable");
        android.content.pm.Signature[] raw=archive.signingInfo.getApkContentsSigners();if(raw==null||raw.length==0)throw new SecurityException("copy_signer_unavailable");
        java.util.List<String> signers=new java.util.ArrayList<>();for(android.content.pm.Signature signer:raw)signers.add(java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())));signers.sort(String::compareTo);
        android.content.pm.ApplicationInfo app=activity.getApplicationInfo();
        String sourceRevision=java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest((String.valueOf(app.sourceDir)+":"+java.util.Arrays.toString(app.splitSourceDirs)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String data=archive.packageName+":"+archive.getLongVersionCode()+":"+String.join(",",signers)+":"+sourceRevision;return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
