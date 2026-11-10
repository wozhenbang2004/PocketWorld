package dev.pocketengine.displayhelper;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Finite shell Binder operations. Every restart stage has one original target and is attempted once. */
final class DisplayAppControl {
    private static final long TTL_MS=300_000;
    private static final Set<String> COMMANDS=Set.of("app_state","app_restart_check","app_restart_stop","app_restart_launch");
    interface Gate {
        void check()throws Exception;
        JSONObject topology()throws Exception;
        void entering();
    }
    private record Identity(String packageName,int userId,int uid,long serial,String component,String installIdentity) { }
    private static final class Target {
        final String id=UUID.randomUUID().toString(),ownerTask,ownerRun,session,originalTasks;
        final int epoch,display;final long pause,expires;final Identity identity;
        String stage="prepared";JSONObject stopReceipt,launchReceipt;
        Target(JSONObject request,Identity identity,JSONArray tasks)throws Exception {
            this.identity=identity;ownerTask=request.getString("ownerTaskId");ownerRun=request.getString("ownerRunId");epoch=integer(request,"ownerEpoch",1,Integer.MAX_VALUE);
            session=text(request,"sessionId",200);display=integer(request,"displayId",0,Integer.MAX_VALUE);pause=whole(request,"pauseRevision",0,Long.MAX_VALUE);
            expires=SystemClock.uptimeMillis()+TTL_MS;originalTasks=tasks.toString();
        }
    }
    private final Context context;
    private final LinkedHashMap<String,Target> retained=new LinkedHashMap<>();
    DisplayAppControl(Context context){this.context=context;}
    static boolean supports(String command){return COMMANDS.contains(command);}
    void close(){retained.clear();}

    JSONObject handle(String command,JSONObject request,Gate gate)throws Exception {
        if(!supports(command))throw new SecurityException("finite_app_control_required");
        requireRun(request);gate.check();prune();
        if("app_state".equals(command))return state(request,gate);
        Target target=retained.get(text(request,"restartRef",36));
        if(target==null)throw refused("app_restart_reference_expired","原冷启动引用已失效，请读取当前应用状态后重新审阅。");
        requireTarget(target,request,gate);
        if("app_restart_check".equals(command))return check(target,gate);
        if("app_restart_stop".equals(command))return stop(target,gate);
        return launch(target,gate);
    }

    private JSONObject state(JSONObject request,Gate gate)throws Exception {
        String pkg=text(request,"packageName",250);int user=integer(request,"userId",0,21474),display=integer(request,"displayId",0,Integer.MAX_VALUE);
        JSONObject out=diagnosticState(pkg,user);gate.check();JSONObject topology=gate.topology();JSONArray own;
        try{own=targetTasks(topology,pkg,user);}
        catch(DisplayEngine.Failure unavailable){return out.put("tasksStatus","unavailable").put("tasksUnavailableReason",topology.optString("reason","app_task_topology_unavailable")).put("restartUnavailableReason",unavailable.code);}
        boolean elsewhere=elsewhere(own,display),present=own.length()>0;
        out.put("tasksStatus","observed").put("tasks",own).put("targetOnOriginalDisplay",present&&!elsewhere);
        if(!present||elsewhere)return out.put("restartUnavailableReason",elsewhere?"same_app_profile_used_on_other_display":"target_not_on_original_display");
        Identity identity;
        try{
            identity=identity(pkg,user,request.optString("component",""),display);
            JSONObject currentApplication=application(identity);out.put("target",description(identity)).put("application",currentApplication);
        }catch(DisplayEngine.Failure unavailable){gate.check();return out.put("restartUnavailableReason",unavailable.code).put("restartMessage",unavailable.getMessage());}
        catch(ReflectiveOperationException|SecurityException unavailable){gate.check();return out.put("restartUnavailableReason","app_restart_identity_api_unavailable").put("restartMessage","原资料的冷启动身份接口不可用；已保留实际只读应用状态。");}
        gate.check();out.put("restartAvailable",true).put("forceStopScope","all_tasks_and_processes_of_exact_package_and_profile");
        Target target=new Target(request,identity,own);retained.put(target.id,target);
        return out.put("restartRef",target.id).put("expiresAtUptimeMs",target.expires).put("stage",target.stage);
    }
    /** Read eligibility is deliberately broader than destructive restart eligibility. */
    private JSONObject diagnosticState(String pkg,int user)throws Exception {
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*"))throw refused("app_package_invalid","无法确认原应用包名。");
        UserManager users=context.getSystemService(UserManager.class);LauncherApps launcher=context.getSystemService(LauncherApps.class);
        if(users==null||launcher==null)throw refused("app_profile_unavailable","无法确认原应用用户资料。");
        UserHandle selected=user(user);boolean accessible=false;
        for(UserHandle profile:launcher.getProfiles())if(profile.equals(selected)){accessible=true;break;}
        long serial=accessible?users.getSerialNumberForUser(selected):-1;
        if(!accessible||serial<0)throw refused("app_profile_identity_unavailable","原资料不可访问或序号不可确认，没有回落到其他用户。");
        PackageInfo info=packageInfo(pkg,user);ApplicationInfo app=info.applicationInfo;
        if(app==null||!pkg.equals(info.packageName)||!pkg.equals(app.packageName)||app.uid<0||app.uid/100000!=user)throw refused("app_profile_identity_unavailable","系统没有返回准确包和用户资料的应用身份。");
        JSONObject target=new JSONObject().put("packageName",pkg).put("userId",user).put("uid",app.uid).put("profileSerial",serial);
        try{target.put("installIdentity",installIdentity(info));}catch(DisplayEngine.Failure unknown){target.put("installIdentityStatus","unavailable").put("installIdentityUnavailableReason",unknown.code);}
        return readResult().put("target",target).put("application",application(info))
                .put("profile",new JSONObject().put("userId",user).put("serial",serial).put("unlocked",users.isUserUnlocked(selected)).put("quietMode",users.isQuietModeEnabled(selected)))
                .put("device",PhoneDeviceState.read(context)).put("restartAvailable",false).put("restartRequiresOwnerApproval",true)
                .put("tasksStatus","not_read").put("automaticProfileFallback",false).put("automaticReplayAllowed",false);
    }
    private JSONObject check(Target target,Gate gate)throws Exception {
        Identity fresh=identity(target.identity.packageName,target.identity.userId,target.identity.component,target.display);
        requireIdentity(target.identity,fresh);gate.check();JSONArray tasks=targetTasks(gate.topology(),fresh);
        boolean outside=elsewhere(tasks,target.display),stopped=stopped(fresh);
        boolean current="prepared".equals(target.stage)?!outside&&target.originalTasks.equals(tasks.toString()):"stopped".equals(target.stage)?stopped&&tasks.length()==0:!outside;
        JSONObject out=readResult().put("stage",target.stage).put("target",description(fresh)).put("tasks",tasks)
                .put("targetCurrent",current).put("stopped",stopped).put("automaticReplayAllowed",false);
        if(target.stopReceipt!=null)out.put("stopReceipt",new JSONObject(target.stopReceipt.toString()));
        if(target.launchReceipt!=null)out.put("launchReceipt",new JSONObject(target.launchReceipt.toString()));
        return out;
    }
    private JSONObject stop(Target target,Gate gate)throws Exception {
        if(!"prepared".equals(target.stage))return consumed(target,"stop");
        JSONObject fresh=check(target,gate);
        if(!fresh.getBoolean("targetCurrent"))throw refused("app_restart_target_changed","原包、用户资料或显示任务已变化，未强停。");
        Object manager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Method force=Class.forName("android.app.IActivityManager").getMethod("forceStopPackage",String.class,int.class);
        gate.check();requireIdentity(target.identity,identity(target.identity.packageName,target.identity.userId,target.identity.component,target.display));
        if(!target.originalTasks.equals(targetTasks(gate.topology(),target.identity).toString()))throw refused("app_restart_target_changed","原应用任务在派发前已变化，未强停。");
        gate.check();target.stage="stop_unknown";gate.entering();
        try{force.invoke(manager,target.identity.packageName,target.identity.userId);}
        catch(InvocationTargetException wrapped){
            if(wrapped.getCause() instanceof SecurityException){target.stage="stop_refused";return target.stopReceipt=failed(target,"force_stop_permission_refused",false,false);}
            return target.stopReceipt=failed(target,"force_stop_outcome_unknown",true,false);
        }catch(Exception unknown){return target.stopReceipt=failed(target,"force_stop_outcome_unknown",true,false);}
        try{
            gate.check();requireIdentity(target.identity,identity(target.identity.packageName,target.identity.userId,target.identity.component,target.display));
            boolean flag=stopped(target.identity);JSONArray remaining=targetTasks(gate.topology(),target.identity);gate.check();
            if(!flag||remaining.length()!=0)return target.stopReceipt=failed(target,"force_stop_not_confirmed",true,false)
                    .put("stoppedFlagObserved",flag).put("remainingTaskCount",remaining.length());
            target.stage="stopped";
            return target.stopReceipt=base(target).put("ok",true).put("accepted",true).put("stage","stopped").put("status","completed").put("terminal",true)
                    .put("forceStopConfirmed",true).put("stoppedFlagObserved",true).put("remainingTaskCount",0)
                    .put("launchDispatched",false).put("partial",true).put("verifiedBusinessSuccess",false)
                    .put("message","准确应用资料已强停，尚未执行后续启动；取消或未知时保留此结果，不会自动复启。");
        }catch(Exception unknown){return target.stopReceipt=failed(target,"force_stop_readback_unknown",true,false);}
    }
    private JSONObject launch(Target target,Gate gate)throws Exception {
        if(!"stopped".equals(target.stage))return consumed(target,"launch");
        JSONObject current=check(target,gate);
        if(!current.getBoolean("targetCurrent"))throw refused("app_restart_stop_no_longer_current","应用在原强停后已被外部重新启动或身份变化，未重复强停或启动。");
        LauncherApps launcher=context.getSystemService(LauncherApps.class);
        if(launcher==null)throw refused("app_launcher_unavailable","原资料的启动服务不可用，应用仍保持原强停结果。");
        gate.check();requireIdentity(target.identity,identity(target.identity.packageName,target.identity.userId,target.identity.component,target.display));
        if(!stopped(target.identity)||targetTasks(gate.topology(),target.identity).length()!=0)throw refused("app_restart_stop_no_longer_current","应用停止状态已变化，未继续启动。");
        gate.check();target.stage="launch_unknown";gate.entering();
        try{launcher.startMainActivity(ComponentName.unflattenFromString(target.identity.component),user(target.identity.userId),null,ActivityOptions.makeBasic().setLaunchDisplayId(target.display).toBundle());}
        catch(android.content.ActivityNotFoundException|SecurityException denied){target.stage="launch_refused";return target.launchReceipt=failed(target,"app_restart_launch_refused",false,true);}
        catch(Exception unknown){return target.launchReceipt=failed(target,"app_restart_launch_unknown",true,true);}
        target.stage="launched";
        JSONObject out=base(target).put("ok",true).put("accepted",true).put("stage",target.stage).put("forceStopConfirmed",true)
                .put("launchDispatched",true).put("partial",false).put("verifiedBusinessSuccess",false).put("requiresPostconditionCheck",true);
        try{
            gate.check();JSONArray tasks=targetTasks(gate.topology(),target.identity);boolean outside=elsewhere(tasks,target.display);
            out.put("tasksAfterLaunch",tasks).put("targetLandingVerified",tasks.length()>0&&!outside).put("actualUserVerified",tasks.length()>0&&!outside);
            if(outside){target.stage="launch_unknown";out.put("ok",false).put("accepted",JSONObject.NULL).put("stage",target.stage).put("error","app_restart_landed_outside_original_display").put("requiresReconciliation",true);}
        }catch(Exception unavailable){out.put("targetLandingVerified",false).put("actualUserVerified",false).put("postconditionRead","unavailable");}
        return target.launchReceipt=out;
    }

    private Identity identity(String pkg,int user,String requestedComponent,int display)throws Exception {
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||Set.of("android","com.android.shell","dev.pocketengine").contains(pkg))throw refused("app_restart_target_forbidden","不能将系统、助手或本应用作为冷启动目标。");
        UserManager users=context.getSystemService(UserManager.class);LauncherApps launcher=context.getSystemService(LauncherApps.class);
        if(users==null||launcher==null)throw refused("app_profile_unavailable","无法确认原应用用户资料。");
        UserHandle selected=user(user);boolean accessible=false;
        for(UserHandle profile:launcher.getProfiles())if(profile.equals(selected)){accessible=true;break;}
        if(!accessible||!users.isUserUnlocked(selected)||users.isQuietModeEnabled(selected))throw refused("app_profile_unavailable","原资料不可访问、已锁定或暂停，没有回落到其他用户。");
        long serial=users.getSerialNumberForUser(selected);if(serial<0)throw refused("app_profile_identity_unavailable","原用户资料序号不可确认。");
        PackageInfo installed=packageInfo(pkg,user);ApplicationInfo app=installed.applicationInfo;
        if(app==null||app.uid/100000!=user||app.uid%100000<10000||!app.enabled||(app.flags&(ApplicationInfo.FLAG_SUSPENDED|ApplicationInfo.FLAG_PERSISTENT))!=0||(app.flags&ApplicationInfo.FLAG_INSTALLED)==0)
            throw refused("app_restart_target_unavailable","准确应用资料不可启动。");
        String[] shares=(String[])packageServiceType().getMethod("getPackagesForUid",int.class).invoke(packageService(),app.uid);
        if(shares==null||shares.length!=1||!pkg.equals(shares[0]))throw refused("app_restart_shared_uid","此应用共享系统进程身份，未强停其他应用。");
        ComponentName component=requestedComponent.isEmpty()?null:ComponentName.unflattenFromString(requestedComponent);
        if(!requestedComponent.isEmpty()&&(component==null||!pkg.equals(component.getPackageName())))throw refused("app_restart_component_invalid","启动组件不属于原应用。");
        List<LauncherActivityInfo> activities=launcher.getActivityList(pkg,selected);LauncherActivityInfo chosen=null;
        for(LauncherActivityInfo activity:activities){if(component!=null&&!component.equals(activity.getComponentName()))continue;if(chosen!=null)throw refused("app_restart_launcher_ambiguous","此应用有多个启动入口，请指定实际列出的准确组件。");chosen=activity;}
        if(chosen==null||chosen.getApplicationInfo()==null||chosen.getApplicationInfo().uid!=app.uid)throw refused("app_restart_launcher_unavailable","准确资料没有可确认的启动入口。");
        component=chosen.getComponentName();ActivityInfo activity=(ActivityInfo)packageServiceType().getMethod("getActivityInfo",ComponentName.class,long.class,int.class).invoke(packageService(),component,0L,user);
        if(activity==null||!activity.enabled||!activity.exported||activity.applicationInfo==null||activity.applicationInfo.uid!=app.uid
                ||display>0&&activity.launchMode>ActivityInfo.LAUNCH_SINGLE_TOP)throw refused("app_restart_launch_mode","该入口不能保证留在原显示，未执行冷启动。");
        return new Identity(pkg,user,app.uid,serial,component.flattenToString(),installIdentity(installed));
    }
    private static UserHandle user(int id){return UserHandle.getUserHandleForUid(Math.multiplyExact(id,100000));}
    private static Class<?> packageServiceType()throws Exception{return Class.forName("android.content.pm.IPackageManager");}
    private static Object packageService()throws Exception{return Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);}
    private static PackageInfo packageInfo(String pkg,int user)throws Exception {
        Object value=packageServiceType().getMethod("getPackageInfo",String.class,long.class,int.class).invoke(packageService(),pkg,(long)PackageManager.GET_SIGNING_CERTIFICATES,user);
        if(!(value instanceof PackageInfo))throw refused("app_install_identity_unavailable","系统未返回准确资料的应用安装身份。");return(PackageInfo)value;
    }
    private static String installIdentity(PackageInfo info)throws Exception {
        if(info.signingInfo==null)throw refused("app_signer_unavailable","原应用签名不可确认。");
        android.content.pm.Signature[] raw=info.signingInfo.getApkContentsSigners();if(raw==null||raw.length==0)throw refused("app_signer_unavailable","原应用签名不可确认。");
        List<String> signers=new ArrayList<>();for(var signature:raw)signers.add(hash(signature.toByteArray()));signers.sort(String::compareTo);
        ApplicationInfo app=info.applicationInfo;return hash((info.packageName+"\n"+info.getLongVersionCode()+"\n"+info.firstInstallTime+"\n"+info.lastUpdateTime+"\n"+app.uid+"\n"+app.sourceDir+"\n"+java.util.Arrays.toString(app.splitSourceDirs)+"\n"+String.join(",",signers)).getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(byte[] bytes)throws Exception{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void requireIdentity(Identity expected,Identity actual)throws Exception {if(!expected.equals(actual))throw refused("app_install_or_profile_changed","原应用安装、组件或用户资料已改变，未继续操作。");}
    private static JSONObject description(Identity target)throws Exception {return new JSONObject().put("packageName",target.packageName).put("userId",target.userId).put("uid",target.uid).put("profileSerial",target.serial).put("component",target.component).put("installIdentity",target.installIdentity);}
    private static JSONObject application(Identity target)throws Exception {PackageInfo info=packageInfo(target.packageName,target.userId);if(info.applicationInfo==null||info.applicationInfo.uid!=target.uid||!target.installIdentity.equals(installIdentity(info)))throw refused("app_install_changed","读取时应用安装身份已变化。");return application(info);}
    private static JSONObject application(PackageInfo info)throws Exception {ApplicationInfo app=info.applicationInfo;return new JSONObject().put("status","observed").put("versionCode",info.getLongVersionCode()).put("versionName",info.versionName==null?JSONObject.NULL:info.versionName).put("enabled",app.enabled).put("stopped",(app.flags&ApplicationInfo.FLAG_STOPPED)!=0).put("suspended",(app.flags&ApplicationInfo.FLAG_SUSPENDED)!=0).put("installed",(app.flags&ApplicationInfo.FLAG_INSTALLED)!=0).put("persistent",(app.flags&ApplicationInfo.FLAG_PERSISTENT)!=0);}
    private static boolean stopped(Identity target)throws Exception {PackageInfo info=packageInfo(target.packageName,target.userId);if(info.applicationInfo==null||info.applicationInfo.uid!=target.uid||!target.installIdentity.equals(installIdentity(info)))throw refused("app_install_changed","原应用身份已变化。");return(info.applicationInfo.flags&ApplicationInfo.FLAG_STOPPED)!=0;}
    private static JSONArray targetTasks(JSONObject topology,Identity target)throws Exception {
        return targetTasks(topology,target.packageName,target.userId);
    }
    private static JSONArray targetTasks(JSONObject topology,String packageName,int userId)throws Exception {
        if(!"observed".equals(topology.optString("status"))||!Boolean.TRUE.equals(topology.opt("complete")))throw refused("app_task_topology_unavailable","无法完整确认应用跨显示任务归属，未强停。");
        JSONArray all=topology.getJSONArray("tasks");List<JSONObject> selected=new ArrayList<>();
        for(int i=0;i<all.length();i++){JSONObject task=all.getJSONObject(i);if(packageName.equals(task.getString("packageName"))&&userId==task.getInt("androidUserId"))selected.add(new JSONObject(task.toString()));}
        selected.sort(Comparator.comparingInt(task->task.optInt("taskId",-1)));JSONArray out=new JSONArray();for(JSONObject task:selected)out.put(task);return out;
    }
    private static boolean elsewhere(JSONArray tasks,int display)throws Exception {for(int i=0;i<tasks.length();i++)if(tasks.getJSONObject(i).getInt("displayId")!=display)return true;return false;}
    private static JSONObject readResult()throws Exception{return new JSONObject().put("ok",true).put("readOnly",true).put("source","authenticated_shell_binder");}
    private static JSONObject base(Target target)throws Exception {return new JSONObject().put("restartRef",target.id).put("target",description(target.identity)).put("displayId",target.display).put("sessionId",target.session).put("automaticReplayAllowed",false).put("automaticProfileFallback",false);}
    private static JSONObject failed(Target target,String code,boolean unknown,boolean stopped)throws Exception {return base(target).put("ok",false).put("accepted",unknown?JSONObject.NULL:false).put("stage",target.stage).put("error",code).put("requiresReconciliation",unknown).put("forceStopConfirmed",stopped).put("launchDispatched",stopped&&target.stage.startsWith("launch_")).put("partial",stopped).put("verifiedBusinessSuccess",false);}
    private static JSONObject consumed(Target target,String phase)throws Exception {return base(target).put("ok",false).put("accepted",false).put("error","app_restart_stage_already_attempted_or_not_ready").put("stage",target.stage).put("requestedStage",phase).put("phaseNotReplayed",true).put("originalStopReceipt",target.stopReceipt==null?JSONObject.NULL:new JSONObject(target.stopReceipt.toString())).put("originalLaunchReceipt",target.launchReceipt==null?JSONObject.NULL:new JSONObject(target.launchReceipt.toString()));}
    private static void requireRun(JSONObject request)throws Exception {for(String key:new String[]{"ownerTaskId","ownerRunId"})if(!text(request,key,200).matches("[0-9a-f-]{36}"))throw new SecurityException("original_app_run_required");integer(request,"ownerEpoch",1,Integer.MAX_VALUE);integer(request,"displayId",0,Integer.MAX_VALUE);whole(request,"pauseRevision",0,Long.MAX_VALUE);text(request,"sessionId",200);}
    private void requireTarget(Target target,JSONObject request,Gate gate)throws Exception {
        if(target.expires<=SystemClock.uptimeMillis()||target.epoch!=request.getInt("ownerEpoch")||target.display!=request.getInt("displayId")||target.pause!=request.getLong("pauseRevision")||!target.ownerTask.equals(request.getString("ownerTaskId"))||!target.ownerRun.equals(request.getString("ownerRunId"))||!target.session.equals(request.getString("sessionId")))throw refused("app_restart_scope_changed","原冷启动任务、暂停版本或显示会话已变化。");gate.check();
    }
    private void prune(){long now=SystemClock.uptimeMillis();retained.values().removeIf(target->target.expires<=now);while(retained.size()>=64)retained.remove(retained.keySet().iterator().next());}
    private static String text(JSONObject input,String key,int max)throws Exception {Object raw=input.opt(key);if(!(raw instanceof String)||((String)raw).isEmpty()||((String)raw).length()>max)throw new IllegalArgumentException("invalid_"+key);return(String)raw;}
    private static int integer(JSONObject input,String key,int min,int max)throws Exception{return(int)whole(input,key,min,max);}
    private static long whole(JSONObject input,String key,long min,long max)throws Exception {Object raw=input.opt(key);if(!(raw instanceof Number))throw new IllegalArgumentException("invalid_"+key);long value;try{value=new java.math.BigDecimal(raw.toString()).longValueExact();}catch(RuntimeException invalid){throw new IllegalArgumentException("invalid_"+key);}if(value<min||value>max)throw new IllegalArgumentException("invalid_"+key);return value;}
    private static DisplayEngine.Failure refused(String code,String message){return new DisplayEngine.Failure(code,message,false);}

}
