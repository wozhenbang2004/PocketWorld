package dev.pocketengine.features;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import dev.pocketengine.features.automation.AutomationModule;
import dev.pocketengine.store.TaskStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Existing-alarm host adapter. No scheduler, model work, phone action or second task log lives here. */
public class HostAutomationCoordinator implements AutomationModule.AlarmHost {
    private static final String CHANNEL="personal_reminders";
    private static final int MAX_SCAN=100,MAX_DISPATCH=20;
    private static final long RETRY_DELAY=60_000L,CLAIM_LEASE=60_000L;
    private final Context context;
    private final FeatureHost features;
    private final TaskStore tasks;
    private final AutomationPlanHost plans;
    private final AtomicInteger nextCursor=new AtomicInteger(),dueCursor=new AtomicInteger();

    public HostAutomationCoordinator(Context context,FeatureHost features,TaskStore tasks){
        this(context,features,tasks,null);
    }
    public HostAutomationCoordinator(Context context,FeatureHost features,TaskStore tasks,AutomationPlanHost plans){
        this.context=context.getApplicationContext();this.features=java.util.Objects.requireNonNull(features);this.tasks=java.util.Objects.requireNonNull(tasks);
        this.plans=plans;
    }
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private AutomationModule module(){EngineModule module=features.module("automation");return module instanceof AutomationModule?(AutomationModule)module:null;}

    @Override public long nextAutomationAt(long now)throws Exception {
        if(Thread.currentThread().isInterrupted())return -1;
        AutomationModule automation=module();if(automation==null)return -1;
        JSONObject scope=scope();long next=plans==null?-1:automation.nextPlanTaskAt(scope,now);if(plans!=null)next=earlier(next,plans.nextSemanticWake(automation,now));
        if(!notificationsAllowed()||!automation.getFollowUpPolicy(scope).optBoolean("enabled",false))return next;
        List<JSONObject> records=records(automation,scope),due=new ArrayList<>();
        for(JSONObject record:records){if(Thread.currentThread().isInterrupted())return -1;long at=record.getLong("followUpAt");if(at>now)next=earlier(next,at);else due.add(record);}
        if(due.isEmpty())return next;
        int start=Math.floorMod(nextCursor.get(),due.size()),scanned=0;long budget=System.nanoTime()+350_000_000L;
        for(;scanned<Math.min(MAX_SCAN,due.size());scanned++){
            if(Thread.currentThread().isInterrupted())return -1;
            JSONObject record=due.get((start+scanned)%due.size());JSONObject decision=evaluate(automation,scope,record);
            if(decision.optBoolean("eligible")){next=earlier(next,now);scanned++;break;}
            if(!decision.isNull("nextEligibleAt")){long eligible=decision.optLong("nextEligibleAt",-1);if(eligible>now)next=earlier(next,eligible);}
            if(System.nanoTime()>=budget){scanned++;break;}
        }
        nextCursor.set((start+scanned)%due.size());
        // Rotate a bounded read window rather than letting old consumed candidates
        // permanently hide later work. The existing alarm owns this coarse recheck.
        if(scanned<due.size())next=earlier(next,safeLater(now,RETRY_DELAY));
        return next;
    }

    @Override public void onAutomationDue(long now,int maxCandidates)throws Exception {
        if(maxCandidates<=0||Thread.currentThread().isInterrupted())return;
        AutomationModule automation=module();if(automation==null)return;
        int planAdmissions=plans==null?0:plans.onDue(automation,now,maxCandidates);maxCandidates-=planAdmissions;
        if(maxCandidates<=0||!notificationsAllowed())return;
        JSONObject scope=scope();if(!automation.getFollowUpPolicy(scope).optBoolean("enabled",false))return;
        List<JSONObject> due=new ArrayList<>();for(JSONObject record:records(automation,scope))if(record.getLong("followUpAt")<=now)due.add(record);
        if(due.isEmpty())return;
        int start=Math.floorMod(dueCursor.get(),due.size()),scanned=0,admitted=0,limit=Math.min(MAX_DISPATCH,maxCandidates);
        long budget=System.nanoTime()+1_500_000_000L;
        try{
            for(;scanned<Math.min(MAX_SCAN,due.size())&&admitted<limit;scanned++){
                if(Thread.currentThread().isInterrupted()||!notificationsAllowed())return;
                JSONObject record=due.get((start+scanned)%due.size());JSONObject decision=evaluate(automation,scope,record);
                if(decision.optBoolean("eligible")){
                    admitted++;
                    if(Thread.currentThread().isInterrupted()||!notificationsAllowed())return;
                    JSONObject claim=automation.claimFollowUp(scope,record.getString("id"),record.getLong("revision"),record.getString("candidateKey"),CLAIM_LEASE);
                    if(!claim.optBoolean("claimed"))continue;
                    JSONObject receipt=claim.getJSONObject("receipt");String claimId=receipt.getString("claimId");
                    String tag=notificationTag(claimId);int id=notificationId(claimId);
                    // Persist the exact platform identity before entering the one-shot
                    // transition. A post that succeeds and then throws stays reconcilable.
                    tasks.recordFollowUpNotificationLink(claimId,record.getString("id"),record.getLong("revision"),tag,id);
                    if(Thread.currentThread().isInterrupted()||!notificationsAllowed())return;
                    JSONObject begin=automation.beginFollowUpDelivery(scope,claimId);
                    if(!begin.optBoolean("dispatchGranted"))continue;
                    // begin persisted delivery_unknown. Every exception, interruption or
                    // timeout from this point leaves that barrier; never infer not_sent.
                    if(Thread.currentThread().isInterrupted()||!notificationsAllowed())return;
                    post(tag,id,notification(record,claimId,id));
                    automation.recordFollowUpDelivery(scope,claimId,"notified");
                }
                if(System.nanoTime()>=budget){scanned++;break;}
            }
        }finally{dueCursor.set((start+Math.max(1,scanned))%due.size());}
    }

    /** Native owner recovery; absence is not proof that a notification was never posted. */
    public JSONObject reconcileNotification(String claimId)throws Exception {
        AutomationModule automation=module();if(automation==null)throw new IllegalStateException("跟进模块尚未准备");
        JSONObject scope=scope(),receipt=automation.getFollowUpReceipt(scope,claimId);
        if(!receipt.optString("status").equals("delivery_unknown"))return reconciliation(receipt,false,"durable_receipt");
        JSONObject link=tasks.followUpNotificationLink(claimId);
        if(link==null||!claimId.equals(link.optString("claimId"))||!receipt.getString("commitmentId").equals(link.optString("commitmentId"))
                ||receipt.getLong("commitmentRevision")!=link.optLong("revision"))return reconciliation(receipt,false,"notification_link_unavailable");
        StatusBarNotification[] active;
        try{NotificationManager manager=context.getSystemService(NotificationManager.class);active=manager==null?null:manager.getActiveNotifications();}
        catch(RuntimeException unavailable){return reconciliation(receipt,false,"system_evidence_unavailable");}
        if(active!=null)for(StatusBarNotification posted:active){
            if(!context.getPackageName().equals(posted.getPackageName())||posted.getId()!=link.getInt("notificationId")||!link.getString("tag").equals(posted.getTag()))continue;
            Bundle extras=posted.getNotification().extras;
            if(extras==null||!claimId.equals(extras.getString("followUpClaimId"))||!link.getString("commitmentId").equals(extras.getString("commitmentId"))||link.getLong("revision")!=extras.getLong("commitmentRevision"))continue;
            return reconciliation(automation.recordFollowUpDelivery(scope,claimId,"notified"),true,"active_system_notification");
        }
        return reconciliation(receipt,false,"active_notification_not_found");
    }

    protected void post(String tag,int id,Notification notification)throws Exception {
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null)throw new IllegalStateException("通知服务不可用");manager.notify(tag,id,notification);
    }
    private boolean notificationsAllowed(){
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager manager=context.getSystemService(NotificationManager.class);if(manager==null||!manager.areNotificationsEnabled())return false;
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL);if(channel==null||channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;
        String group=channel.getGroup();if(group!=null){NotificationChannelGroup parent=manager.getNotificationChannelGroup(group);if(parent!=null&&parent.isBlocked())return false;}return true;
    }
    private Notification notification(JSONObject record,String claimId,int id)throws Exception {
        String commitment=record.getString("id");Intent open=new Intent().setClassName(context.getPackageName(),"dev.pocketengine.ui.ComposeActivity")
                .setAction("dev.pocketengine.OPEN_COMMITMENT").setData(Uri.parse("pocketworld://commitment/"+Uri.encode(commitment)+"?claim="+Uri.encode(claimId)))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("commitmentId",commitment).putExtra("followUpClaimId",claimId);
        PendingIntent tap=PendingIntent.getActivity(context,id,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Bundle evidence=new Bundle();evidence.putString("commitmentId",commitment);evidence.putString("followUpClaimId",claimId);evidence.putLong("commitmentRevision",record.getLong("revision"));
        int icon=context.getApplicationInfo().icon;if(icon==0)icon=android.R.drawable.ic_dialog_info;
        return new Notification.Builder(context,CHANNEL).setSmallIcon(icon).setContentTitle(record.optString("title","承诺待跟进"))
                .setContentText("这项承诺已到跟进时间。点击查看详情，由你决定下一步。")
                .setContentIntent(tap).setExtras(evidence).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).build();
    }
    private static List<JSONObject> records(AutomationModule module,JSONObject scope)throws Exception {
        JSONArray all=module.handle("listCommitments",new JSONObject().put("scope",scope)).getJSONArray("commitments");List<JSONObject> result=new ArrayList<>();
        for(int i=0;i<all.length();i++){
            if(Thread.currentThread().isInterrupted())break;JSONObject record=all.getJSONObject(i);
            if(!"owner".equals(record.optString("ownerId"))||!"private".equals(record.optString("spaceId"))||record.isNull("followUpAt")||record.isNull("candidateKey"))continue;
            if(record.optString("status").matches("completed|cancelled"))continue;result.add(record);
        }
        result.sort(Comparator.comparingLong((JSONObject record)->record.optLong("followUpAt")).thenComparing(record->record.optString("id")));return result;
    }
    private static JSONObject evaluate(AutomationModule module,JSONObject scope,JSONObject record)throws Exception {return module.evaluateFollowUp(scope,record.getString("id"),record.getLong("revision"),record.getString("candidateKey"));}
    private static JSONObject reconciliation(JSONObject receipt,boolean applied,String source)throws Exception{return new JSONObject().put("receipt",receipt).put("reconciled",applied).put("source",source);}
    private static String notificationTag(String claimId){return "pocketworld.followup."+claimId;}
    private static int notificationId(String claimId){return 0x10000000|(claimId.hashCode()&0x0fffffff);}
    private static long earlier(long current,long candidate){return candidate<0?current:current<0?candidate:Math.min(current,candidate);}
    private static long safeLater(long now,long delay){return now>Long.MAX_VALUE-delay?Long.MAX_VALUE:now+delay;}
}
