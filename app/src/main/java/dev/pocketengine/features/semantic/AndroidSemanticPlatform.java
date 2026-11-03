package dev.pocketengine.features.semantic;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.AlarmClock;
import android.view.Display;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneAccessibilityService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Uses only public Android APIs. No application-context launch, hidden API or shell fallback. */
public final class AndroidSemanticPlatform implements SemanticPlatform {
    static final String CLOCK_PERMISSION="com.android.alarm.permission.SET_ALARM";
    // The MIME value is public in Android's calendar-intent contract; Events.CONTENT_TYPE
    // itself is not exposed by the public SDK35 jar.
    private static final String CALENDAR_EVENT_MIME="vnd.android.cursor.dir/event";
    private final Context context;
    private final PhoneAccessibilityService service;
    private final Runnable invalidatePhoneReferences;
    public AndroidSemanticPlatform(Context context,PhoneAccessibilityService capturedMainScreenService,Runnable invalidatePhoneReferences) {
        this.context=context.getApplicationContext();this.service=capturedMainScreenService;this.invalidatePhoneReferences=invalidatePhoneReferences;
        if(service!=null&&invalidatePhoneReferences==null)throw new IllegalArgumentException("主屏语义派发必须失效原PhonePort节点引用");
    }
    @Override public List<Target> discover(SemanticRequest request,RunControl control) throws Exception {
        control.check();requireClockPermission(request);PackageManager pm=context.getPackageManager();
        List<ResolveInfo> matches=pm.queryIntentActivities(intent(request),PackageManager.MATCH_DEFAULT_ONLY|PackageManager.GET_RESOLVED_FILTER);
        List<Target> targets=new ArrayList<>();Set<String> seen=new HashSet<>();int visited=0;
        String selectedPackage=request.kind.equals("app_link")||request.kind.equals("share_text")?request.parameters().getString("packageName"):"";
        for(ResolveInfo match:matches) {
            control.check();if(++visited>256)break;ActivityInfo a=match.activityInfo;
            if(a==null||!a.exported||!a.enabled||a.applicationInfo==null||!a.applicationInfo.enabled||context.getPackageName().equals(a.packageName)
                    ||!selectedPackage.isEmpty()&&!selectedPackage.equals(a.packageName)
                    ||a.permission!=null&&!a.permission.isEmpty()&&context.checkSelfPermission(a.permission)!=PackageManager.PERMISSION_GRANTED)continue;
            String component=new ComponentName(a.packageName,a.name).flattenToString();if(!seen.add(component))continue;
            try {
                PackageInfo pkg=pm.getPackageInfo(a.packageName,PackageManager.GET_SIGNING_CERTIFICATES);
                if(pkg.signingInfo==null)continue;Signature[] signatures=pkg.signingInfo.getApkContentsSigners();if(signatures==null||signatures.length==0)continue;
                ArrayList<String> certificates=new ArrayList<>();for(Signature signature:signatures)certificates.add(SemanticJson.hash(signature.toByteArray()));certificates.sort(String::compareTo);
                String route=request.kind.equals("web")||request.kind.equals("app_link")?webRoute(match,request.host):"official_intent";
                // Explicit components bypass normal App Links routing, so unapproved non-browser
                // handlers are deliberately not offered as eligible HTTPS targets.
                if(route==null)continue;
                JSONObject identity=new JSONObject().put("component",component).put("packageName",a.packageName).put("versionCode",pkg.getLongVersionCode())
                        .put("lastUpdateTime",pkg.lastUpdateTime).put("uid",a.applicationInfo.uid).put("certificates",new JSONArray(certificates));
                if(request.clock())identity.put("deviceTimeZone",java.util.TimeZone.getDefault().getID());
                targets.add(new Target(identity,String.valueOf(match.loadLabel(pm)),route));
            } catch(PackageManager.NameNotFoundException disappeared) { /* Removed/replaced while querying; not an eligible snapshot. */ }
        }
        targets.sort(Comparator.comparingInt((Target target)->priority(target.route)).thenComparing(target->target.component));
        return targets;
    }
    private static int priority(String route) {return switch(route){case "verified_app_link"->0;case "user_selected_app_link"->1;case "official_intent"->2;default->3;};}
    private String webRoute(ResolveInfo match,String host) {
        // A general HTTPS/BROWSABLE filter has no authority/path restriction. ResolveInfo's
        // internal browser-classification field is not part of the public Android SDK.
        if(match.filter!=null&&match.filter.hasDataScheme("https")&&match.filter.hasCategory(Intent.CATEGORY_BROWSABLE)
                &&match.filter.countDataAuthorities()==0&&match.filter.countDataPaths()==0&&match.filter.countDataSchemeSpecificParts()==0)return "browser";
        if(Build.VERSION.SDK_INT<31)return null;
        try {
            DomainVerificationManager manager=context.getSystemService(DomainVerificationManager.class);
            DomainVerificationUserState state=manager==null?null:manager.getDomainVerificationUserState(match.activityInfo.packageName);
            if(state==null||!state.isLinkHandlingAllowed())return null;
            int strongest=0;
            for(Map.Entry<String,Integer> entry:state.getHostToStateMap().entrySet()) {
                String domain=entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if(host.equals(domain)||domain.startsWith("*.")&&host.endsWith(domain.substring(1))&&host.length()>domain.length()-1) {
                    if(entry.getValue()==DomainVerificationUserState.DOMAIN_STATE_VERIFIED)return "verified_app_link";
                    if(entry.getValue()==DomainVerificationUserState.DOMAIN_STATE_SELECTED)strongest=1;
                }
            }
            return strongest==1?"user_selected_app_link":null;
        } catch(PackageManager.NameNotFoundException|SecurityException unavailable) {return null;}
    }
    static Intent intent(SemanticRequest request) throws Exception {
        if(request.kind.equals("share_text")){JSONObject p=request.parameters();return new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(p.getString("packageName")).putExtra(Intent.EXTRA_TEXT,p.getString("text"));}
        if(request.kind.equals("app_link"))return new Intent(Intent.ACTION_VIEW,Uri.parse(request.data)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(request.parameters().getString("packageName"));
        if(request.clock()) {
            JSONObject p=request.parameters();Intent intent=new Intent(request.kind.equals("alarm")?AlarmClock.ACTION_SET_ALARM:AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI,false);
            if(request.kind.equals("alarm"))intent.putExtra(AlarmClock.EXTRA_HOUR,p.getInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES,p.getInt("minutes"));
            else intent.putExtra(AlarmClock.EXTRA_LENGTH,p.getInt("seconds"));
            if(!p.getString("message").isEmpty())intent.putExtra(AlarmClock.EXTRA_MESSAGE,p.getString("message"));
            return intent;
        }
        if(request.kind.equals("calendar_draft")) {
            JSONObject p=request.parameters();
            return new Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI,CALENDAR_EVENT_MIME)
                    .putExtra(CalendarContract.Events.TITLE,p.getString("title"))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,p.getLong("start"))
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,p.getLong("end"))
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY,false)
                    .putExtra(CalendarContract.Events.EVENT_TIMEZONE,p.getString("timeZone"))
                    .putExtra(CalendarContract.Events.EVENT_END_TIMEZONE,p.getString("timeZone"))
                    .putExtra(CalendarContract.Events.EVENT_LOCATION,p.getString("location"))
                    .putExtra(CalendarContract.Events.DESCRIPTION,p.getString("description"));
        }
        Intent intent=new Intent(request.action,Uri.parse(request.data));
        if(request.kind.equals("web"))intent.addCategory(Intent.CATEGORY_BROWSABLE);
        if(request.kind.equals("email_draft")) {
            JSONObject p=request.parameters();intent.putExtra(Intent.EXTRA_EMAIL,new String[]{p.getString("to")});
            intent.putExtra(Intent.EXTRA_SUBJECT,p.getString("subject")).putExtra(Intent.EXTRA_TEXT,p.getString("body"));
        }
        return intent;
    }
    private void requireClockPermission(SemanticRequest request) {
        if(request.clock()&&context.checkSelfPermission(CLOCK_PERMISSION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("缺少系统时钟入口权限，尚未派发；不会切换到其他屏幕代办");
    }
    private void requireClockContext(SemanticRequest request,Target target)throws Exception {
        requireClockPermission(request);if(request.clock()&&!java.util.TimeZone.getDefault().getID().equals(target.identity().getString("deviceTimeZone")))throw new SecurityException("设备时区已变化，请重新发现并确认时钟参数");
    }
    private void requireService(RunControl control) throws Exception {
        control.check();if(control.isPaused())throw new SecurityException("任务已暂停");
        if(service==null||PhoneAccessibilityService.get()!=service||service.observationSafetyFailure()!=null)
            throw new SecurityException("原主屏服务、权限或解锁状态已失效");
    }
    @Override public JSONObject resourceIdentity() throws Exception {
        if(service==null||PhoneAccessibilityService.get()!=service)throw new SecurityException("未绑定主屏手机服务");
        return new JSONObject().put("executionMode","main_screen").put("displayId",Display.DEFAULT_DISPLAY).put("serviceSessionId",service.observationSessionId());
    }
    @Override public JSONObject dispatch(SemanticRequest request,Target target,RunControl control,Admission admission) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())return notDispatched("worker_required",false);
        final long admittedPauseRevision=control.pauseRevision();
        SemanticDispatchGate gate=new SemanticDispatchGate();CountDownLatch done=new CountDownLatch(1);AtomicReference<JSONObject> receipt=new AtomicReference<>();
        // The original control and the main callback compete on the same CAS. Polling the
        // worker cannot fence a cancellation that completes during the final service check.
        Runnable cancelBeforeEntry=gate::cancelBeforeEntry;
        control.onCancel(cancelBeforeEntry);
        try {
            Handler main=new Handler(Looper.getMainLooper());
            boolean posted=main.post(()->{
                try {
                    requireService(control);admission.check();requireClockContext(request,target);Intent selected=intent(request).setComponent(ComponentName.unflattenFromString(target.component));
                    selected.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions options=ActivityOptions.makeBasic();options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
                    // This is the actual call boundary, after all queued work. The closure retains
                    // the original TaskStore operation, control, service identity and package snapshot.
                    requireService(control);admission.check();invalidatePhoneReferences.run();requireService(control);
                    control.dispatchAtPauseRevision(admittedPauseRevision,()->{
                        requireClockContext(request,target);
                        if(!gate.enter()) {receipt.set(notDispatched("semantic_queue_cancelled",false));return;}
                        service.startActivity(selected,options.toBundle());
                        receipt.set(new JSONObject().put("accepted",true).put("status","launch_requested"));
                    });
                } catch(ActivityNotFoundException missing) {setReceipt(receipt,"app_unavailable",false);}
                catch(SecurityException denied) {setReceipt(receipt,"permission_or_authority_denied",true);}
                catch(Exception failure) {
                    if(gate.entered())setUnknown(receipt);else setReceipt(receipt,"semantic_admission_refused",true);
                } finally {done.countDown();}
            });
            if(!posted) {gate.cancelBeforeEntry();return notDispatched("main_queue_unavailable",false);}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            try {
                while(!done.await(40,TimeUnit.MILLISECONDS)) {
                    control.check();if(control.isPaused()||System.nanoTime()>=deadline)return abandon(gate,receipt);
                }
            } catch(InterruptedException stopped) {Thread.currentThread().interrupt();return abandon(gate,receipt);}
            JSONObject result=receipt.get();return result==null?new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown"):result;
        } finally {control.removeCancelAction(cancelBeforeEntry);}
    }
    private static JSONObject abandon(SemanticDispatchGate gate,AtomicReference<JSONObject> receipt) throws Exception {
        if(gate.cancelBeforeEntry())return notDispatched("semantic_queue_cancelled",true);
        JSONObject actual=receipt.get();return actual==null?new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown"):actual;
    }
    private static JSONObject notDispatched(String code,boolean pause) throws Exception {return new JSONObject().put("accepted",false).put("code",code).put("requiresPause",pause);}
    private static void setReceipt(AtomicReference<JSONObject> receipt,String code,boolean pause) {try{receipt.set(notDispatched(code,pause));}catch(Exception ignored){}}
    private static void setUnknown(AtomicReference<JSONObject> receipt) {try{receipt.set(new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown"));}catch(Exception ignored){}}
}
