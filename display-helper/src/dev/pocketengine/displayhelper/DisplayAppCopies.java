package dev.pocketengine.displayhelper;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;
import android.os.UserManager;
import org.json.JSONObject;

/** Rechecks an actually accessible profile; user zero is never an automatic fallback. */
final class DisplayAppCopies {
    static JSONObject launch(Context context,int display,JSONObject exact,DisplayTypedIntent.Gate gate)throws Exception {
        if(display<=0)throw new SecurityException("background_display_required");int wanted=exact.getInt("userId");String pkg=exact.getString("packageName");if(wanted<0||wanted>21474||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new SecurityException("copy_identity_invalid");ComponentName component=ComponentName.unflattenFromString(exact.getString("component"));if(component==null||!pkg.equals(component.getPackageName()))throw new SecurityException("copy_component_mismatch");
        LauncherApps launcher=context.getSystemService(LauncherApps.class);UserManager users=context.getSystemService(UserManager.class);if(launcher==null||users==null)throw new SecurityException("profile_service_unavailable");
        for(UserHandle user:launcher.getProfiles())if(user.hashCode()==wanted&&user.equals(UserHandle.getUserHandleForUid(wanted*100000))&&users.getSerialNumberForUser(user)==exact.getLong("profileSerial")&&users.isUserUnlocked(user))for(LauncherActivityInfo activity:launcher.getActivityList(pkg,user))if(component.equals(activity.getComponentName())&&activity.getApplicationInfo().uid==exact.getInt("uid")&&installIdentity(context,activity).equals(exact.getString("installIdentity"))&&activity.getFirstInstallTime()==exact.getLong("firstInstallTime")){
            gate.check();gate.requireAppFree(pkg,wanted);gate.check();launcher.startMainActivity(component,user,null,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());return new JSONObject().put("ok",true).put("accepted",true).put("requestedUserId",wanted).put("packageName",pkg).put("verifiedBusinessSuccess",false).put("requiresPostconditionCheck",true);}
        return new JSONObject().put("ok",false).put("accepted",false).put("error","app_copy_unavailable").put("automaticProfileFallback",false);
    }
    private static String installIdentity(Context context,LauncherActivityInfo activity)throws Exception {
        android.content.pm.PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(activity.getApplicationInfo().sourceDir,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);if(archive==null||archive.signingInfo==null||!activity.getComponentName().getPackageName().equals(archive.packageName))throw new SecurityException("copy_install_identity_unavailable");java.util.List<String> signers=new java.util.ArrayList<>();for(android.content.pm.Signature signer:archive.signingInfo.getApkContentsSigners())signers.add(java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())));signers.sort(String::compareTo);if(signers.isEmpty())throw new SecurityException("copy_signer_unavailable");String raw=archive.packageName+":"+archive.getLongVersionCode()+":"+String.join(",",signers);return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    static JSONObject practice(Context context,int display,int ownerUid,JSONObject request,DisplayTypedIntent.Gate gate)throws Exception {
        String pkg=request.getString("packageName");if(display<=0||request.getInt("userId")!=ownerUid/100000||context.getPackageManager().getApplicationInfo(pkg,0).uid!=ownerUid)throw new SecurityException("practice_owner_mismatch");
        ComponentName component=new ComponentName(pkg,"dev.pocketengine.phone.diagnostics.PhonePracticeActivity");android.content.pm.ActivityInfo activity=context.getPackageManager().getActivityInfo(component,0);if(activity.launchMode!=0||!activity.enabled)throw new SecurityException("practice_target_unavailable");
        gate.check();context.startActivity(new Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());return new JSONObject().put("ok",true).put("accepted",true).put("practiceTarget",true).put("verifiedBusinessSuccess",false);
    }
}
