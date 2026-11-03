package dev.pocketengine.phone.setup;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Short lived references to LauncherApps' actually accessible related profiles only. */
public final class AndroidAppCopies implements AutoCloseable {
    static final long TTL_MS=60_000;
    private record Copy(JSONObject identity,long listedAt) { }
    private final Context context;
    private final int displayId;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    // This fence contains only cached reference mutations and the short native entry.
    // Metadata/authority work uses this object's monitor and never holds this fence.
    private final Object referenceFence=new Object();
    private final Map<String,Copy> listed=new LinkedHashMap<>();
    private RunControl listedControl;
    private long listedPauseRevision;
    private RunControl lifetimeControl;
    private Runnable cancelCleanup;
    private boolean closed;
    private LauncherApps watched;
    private final LauncherApps.Callback changes=new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg,UserHandle user){invalidate(pkg,user);}
        @Override public void onPackageRemoved(String pkg,UserHandle user){invalidate(pkg,user);}
        @Override public void onPackageChanged(String pkg,UserHandle user){invalidate(pkg,user);}
        @Override public void onPackagesAvailable(String[] packages,UserHandle user,boolean replacing){for(String pkg:packages)invalidate(pkg,user);}
        @Override public void onPackagesUnavailable(String[] packages,UserHandle user,boolean replacing){for(String pkg:packages)invalidate(pkg,user);}
        @Override public void onPackagesSuspended(String[] packages,UserHandle user){for(String pkg:packages)invalidate(pkg,user);}
        @Override public void onPackagesUnsuspended(String[] packages,UserHandle user){for(String pkg:packages)invalidate(pkg,user);}
    };
    public AndroidAppCopies(Context context){this(context,0);}
    public AndroidAppCopies(Context context,int displayId){
        if(displayId<0)throw new IllegalArgumentException("copy_display_invalid");
        this.context=context.getApplicationContext();this.displayId=displayId;
    }
    public synchronized JSONObject list(RunControl control)throws Exception {
        requireControl(control);bindLifetime(control);requireControl(control);
        synchronized(referenceFence){listed.clear();listedControl=control;listedPauseRevision=control.pauseRevision();}
        LauncherApps launcher=context.getSystemService(LauncherApps.class);UserManager users=context.getSystemService(UserManager.class);
        if(launcher==null||users==null)return result(new JSONArray(),false,"profile_service_unavailable");
        if(watched!=launcher){stopWatching();launcher.registerCallback(changes,main);watched=launcher;}
        JSONArray out=new JSONArray();boolean complete=true;
        for(UserHandle user:launcher.getProfiles()) {
            control.check();
            try {
                long serial=users.getSerialNumberForUser(user);
                if(serial<0||!unlocked(users,user)){complete=false;continue;}
                int nativeUser=id(user);
                for(LauncherActivityInfo activity:launcher.getActivityList(null,user)) {
                    requireControl(control);if(control.pauseRevision()!=listedPauseRevision)throw new SecurityException("app_copy_run_changed");
                    if(out.length()>=200){complete=false;break;}
                    if(activity.getApplicationInfo()==null||!activity.getApplicationInfo().enabled||activity.getApplicationInfo().uid/100000!=nativeUser){complete=false;continue;}
                    JSONObject identity;
                    try {identity=identity(activity,nativeUser,serial);}catch(SecurityException unavailable){complete=false;continue;}
                    String ref=UUID.randomUUID().toString();synchronized(referenceFence){listed.put(ref,new Copy(identity,SystemClock.uptimeMillis()));}
                    String label=String.valueOf(activity.getLabel()).replaceAll("[\\p{Cntrl}]"," ");if(label.length()>200)label=label.substring(0,200);
                    out.put(new JSONObject(identity.toString()).put("copyRef",ref).put("label",label).put("labelIsUntrusted",true)
                            .put("profileKind",user.equals(android.os.Process.myUserHandle())?"current":"related")
                            .put("displayId",displayId));
                }
            }catch(SecurityException unavailable){complete=false;}
        }
        requireControl(control);
        if(control.pauseRevision()!=listedPauseRevision){synchronized(referenceFence){listed.clear();}throw new SecurityException("app_copy_run_changed");}
        return result(out,complete,complete?"accessible_related_profiles":"some_profiles_unavailable");
    }
    /** Requires the original native run which produced this reference, including its pause revision. */
    public synchronized JSONObject require(JSONObject args,RunControl control)throws Exception {
        if(control!=listedControl)throw new SecurityException("app_copy_other_run");return require(args);
    }
    public synchronized JSONObject require(JSONObject args)throws Exception {
        requireControl(listedControl);
        Copy copy;synchronized(referenceFence){copy=reference(args,listedControl);}JSONObject exact=copy.identity;
        LauncherApps launcher=context.getSystemService(LauncherApps.class);UserManager users=context.getSystemService(UserManager.class);
        if(launcher==null||users==null)throw new SecurityException("profile_service_unavailable");
        for(UserHandle user:launcher.getProfiles()) {
            if(id(user)!=exact.getInt("userId")||users.getSerialNumberForUser(user)!=exact.getLong("profileSerial")||!unlocked(users,user))continue;
            for(LauncherActivityInfo current:launcher.getActivityList(exact.getString("packageName"),user)) {
                if(!current.getComponentName().flattenToString().equals(exact.getString("component")))continue;
                JSONObject actual=identity(current,id(user),users.getSerialNumberForUser(user));
                if(actual.toString().equals(exact.toString()))return new JSONObject(exact.toString()).put("displayId",displayId)
                        .put("referenceExpiresAtUptimeMs",copy.listedAt+TTL_MS);
            }
        }
        throw new SecurityException("app_copy_no_longer_accessible");
    }
    public JSONObject launch(JSONObject args,int display,RunControl control,NativePhoneEntry.Admission admission)throws Exception {
        if(display!=displayId)throw new SecurityException("app_copy_other_display");
        JSONObject exact=require(args,control);long revision=control.pauseRevision();
        LauncherApps launcher=context.getSystemService(LauncherApps.class);
        try {
            return NativePhoneEntry.call(control,revision,()->{admission.check();require(args,control);},()->{
                synchronized(referenceFence) {
                    // No RunControl/TaskStore callback while its native-entry monitor is held.
                    // The metadata was re-read by admission; invalidate/expiry can still win here.
                    reference(args,control);
                    launcher.startMainActivity(ComponentName.unflattenFromString(exact.getString("component")),
                            UserHandle.getUserHandleForUid(exact.getInt("uid")),null,
                            ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
                }
                return new JSONObject().put("ok",true).put("accepted",true).put("verified",false).put("requestedUserId",exact.getInt("userId"))
                    .put("profileSerial",exact.getLong("profileSerial")).put("packageName",exact.getString("packageName"))
                    .put("component",exact.getString("component")).put("displayId",display).put("requiresPostconditionCheck",true)
                    .put("requiresPause",true).put("actualUserVerified",false).put("automaticProfileFallback",false)
                    .put("message","Android已接受所选资料的启动请求；主屏尚无实际task/user回执，请主人核对账号后交还，不会假定主账号或继续旧引用。");
            });
        }catch(NativePhoneEntry.Refused refused) {return new JSONObject().put("ok",false).put("accepted",false).put("error","app_copy_admission_changed").put("requiresPause",true);}
        catch(android.content.ActivityNotFoundException|SecurityException refused) {return new JSONObject().put("ok",false).put("accepted",false).put("error","app_copy_platform_refused").put("automaticProfileFallback",false);}
        catch(Exception unknown) {return new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","app_copy_outcome_unknown").put("requiresReconciliation",true).put("automaticReplayAllowed",false);}
    }
    private Copy reference(JSONObject args,RunControl control)throws Exception {
        if(control==null||control!=listedControl)throw new SecurityException("app_copy_other_run");
        if(control.isCancelled()||control.isPaused()||control.pauseRevision()!=listedPauseRevision)throw new SecurityException("app_copy_run_changed");
        Copy copy=listed.get(args.optString("copyRef"));long now=SystemClock.uptimeMillis();
        if(copy==null||now<copy.listedAt||now-copy.listedAt>=TTL_MS)throw new SecurityException("app_copy_reference_expired");
        if(!copy.identity.getString("packageName").equals(args.getString("packageName"))||!(args.opt("userId") instanceof Number)
                ||new java.math.BigDecimal(args.get("userId").toString()).intValueExact()!=copy.identity.getInt("userId"))throw new SecurityException("app_copy_reference_mismatch");
        return copy;
    }
    private void bindLifetime(RunControl control){
        if(closed||lifetimeControl!=null&&lifetimeControl!=control)throw new SecurityException("app_copy_original_port_required");
        lifetimeControl=control;
        // Cancellation may hold the original TaskStore lock while list/require awaits its authority.
        // The cancelled flag fences actions immediately; never acquire this monitor on that caller.
        if(cancelCleanup==null){cancelCleanup=()->main.post(()->closeOriginal(control));control.onCancel(cancelCleanup);}
    }
    private synchronized void closeOriginal(RunControl control){if(lifetimeControl==control)close();}
    public synchronized void clear(){
        synchronized(referenceFence){listed.clear();listedControl=null;}
        if(lifetimeControl!=null&&cancelCleanup!=null)lifetimeControl.removeCancelAction(cancelCleanup);
        cancelCleanup=null;stopWatching();
    }
    @Override public synchronized void close(){closed=true;clear();}
    private void stopWatching(){LauncherApps old=watched;watched=null;if(old!=null)old.unregisterCallback(changes);}
    private synchronized void invalidate(String pkg,UserHandle user){synchronized(referenceFence){listed.entrySet().removeIf(entry->entry.getValue().identity.optString("packageName").equals(pkg)&&entry.getValue().identity.optInt("userId",-1)==id(user));}}
    private static boolean unlocked(UserManager users,UserHandle user){return user.equals(android.os.Process.myUserHandle())?users.isUserUnlocked():users.isUserUnlocked(user);}
    private static void requireControl(RunControl control)throws Exception {
        if(control==null||!control.hasHostRun())throw new SecurityException("app_copy_original_run_required");
        control.check();if(control.isPaused())throw new SecurityException("app_copy_run_paused");
    }
    private JSONObject identity(LauncherActivityInfo activity,int user,long serial)throws Exception {
        String pkg=activity.getComponentName().getPackageName();int uid=activity.getApplicationInfo().uid;
        if(uid<0||uid/100000!=user||!activity.getApplicationInfo().enabled)throw new SecurityException("app_copy_uid_changed");
        PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(activity.getApplicationInfo().sourceDir,PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null||archive.signingInfo==null||!pkg.equals(archive.packageName))throw new SecurityException("app_copy_install_identity_unavailable");
        Signature[] raw=archive.signingInfo.getApkContentsSigners();if(raw==null||raw.length==0)throw new SecurityException("app_copy_signer_unavailable");
        ArrayList<String> signers=new ArrayList<>();for(Signature signer:raw)signers.add(hash(signer.toByteArray()));signers.sort(String::compareTo);
        android.content.pm.ApplicationInfo app=activity.getApplicationInfo();
        String sourceRevision=hash((String.valueOf(app.sourceDir)+":"+java.util.Arrays.toString(app.splitSourceDirs)).getBytes(StandardCharsets.UTF_8));
        String install=hash((pkg+":"+archive.getLongVersionCode()+":"+String.join(",",signers)+":"+sourceRevision).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("packageName",pkg).put("component",activity.getComponentName().flattenToString()).put("userId",user)
                .put("profileSerial",serial).put("uid",uid).put("versionCode",archive.getLongVersionCode()).put("certificates",new JSONArray(signers))
                .put("installIdentity",install).put("sourceRevision",sourceRevision).put("packageFirstInstallTime",activity.getFirstInstallTime())
                .put("profileInstallTimeVerified",user==id(android.os.Process.myUserHandle()));
    }
    private static String hash(byte[] bytes)throws Exception {
        return java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static JSONObject result(JSONArray rows,boolean complete,String reason)throws Exception {
        return new JSONObject().put("ok",true).put("apps",rows).put("complete",complete).put("reason",reason)
                .put("scope","android_accessible_related_launcher_profiles").put("expiresInMs",TTL_MS).put("packageEventsInvalidateReferences",true).put("automaticProfileFallback",false);
    }
    /** SDK35 does not expose getIdentifier. Verify a candidate by public UserHandle value equality. */
    public static int id(UserHandle handle){int id=handle.hashCode();if(id<0||id>21474||!handle.equals(UserHandle.getUserHandleForUid(id*100000)))throw new SecurityException("android_profile_identity_unavailable");return id;}
}
