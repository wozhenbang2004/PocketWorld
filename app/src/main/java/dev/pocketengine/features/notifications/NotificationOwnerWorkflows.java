package dev.pocketengine.features.notifications;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Visible native owner review only. Saving is an immutable file selection, never task input,
 * model permission, a new task/run, or an automatic notification trigger. */
public final class NotificationOwnerWorkflows {
    private final Activity activity;
    private final NotificationsModule module;
    private final TaskStore tasks;
    private final AttachmentBook book;
    public NotificationOwnerWorkflows(Activity activity,NotificationsModule module,TaskStore tasks,AttachmentBook book){
        this.activity=java.util.Objects.requireNonNull(activity);this.module=java.util.Objects.requireNonNull(module);
        this.tasks=java.util.Objects.requireNonNull(tasks);this.book=java.util.Objects.requireNonNull(book);
    }
    public JSONObject state()throws Exception{return module.ownerState(activity);}
    public void openSystemSettings(){module.openSystemSettingsFromVisibleActivity(activity);}
    public JSONObject enable(String revision,Set<String> packages,boolean confirmed)throws Exception{
        // Confirm real installed package identities, not arbitrary labels copied from a notice.
        module.ownerState(activity);for(String pkg:NotificationInbox.validatePackages(packages))activity.getPackageManager().getApplicationInfo(pkg,0);
        return module.enableFromVisibleActivity(activity,revision,packages,confirmed);
    }
    public JSONObject disable(String revision)throws Exception{return module.disableFromVisibleActivity(activity,revision);}
    public JSONObject reconnect(String revision)throws Exception{return module.reconnectFromVisibleActivity(activity,revision);}
    public JSONObject read(String revision)throws Exception{return module.readFromVisibleActivity(activity,revision);}
    /** Package visibility is Android-limited; this never requests QUERY_ALL_PACKAGES. */
    public JSONArray packages()throws Exception{
        JSONObject state=module.ownerState(activity);var manager=activity.getPackageManager();var names=new java.util.LinkedHashSet<String>();
        for(var resolved:manager.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))if(resolved.activityInfo!=null)names.add(resolved.activityInfo.packageName);
        JSONArray selected=state.getJSONArray("packages");for(int i=0;i<selected.length();i++)names.add(selected.getString(i));
        var rows=new ArrayList<JSONObject>();for(String name:names){if(name.equals(activity.getPackageName()))continue;
            try{ApplicationInfo app=manager.getApplicationInfo(name,0);if(app.enabled&&!manager.isPackageSuspended(name))rows.add(new JSONObject().put("packageName",name).put("label",String.valueOf(manager.getApplicationLabel(app))));}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        }
        rows.sort(Comparator.comparing(row->row.optString("label"),String.CASE_INSENSITIVE_ORDER));return new JSONArray(rows);
    }
    /** Only existing personal phone/assistant tasks without delegated/channel origins. */
    public JSONObject targets(String cursor)throws Exception{
        module.ownerState(activity);JSONObject page=tasks.taskSummaryPage(cursor,40);JSONArray rows=new JSONArray(),all=page.getJSONArray("tasks");
        for(int i=0;i<all.length();i++){JSONObject task=tasks.get(all.getJSONObject(i).getString("id"),false);if(personal(task))rows.put(new JSONObject().put("taskId",task.getString("id")).put("goal",task.optString("goal")).put("status",task.optString("status")));}
        return page.put("tasks",rows);
    }
    public static final class Review {
        private final NotificationOwnerWorkflows owner;
        private final String revision,ref,evidence,target;
        private final long version;
        private Review(NotificationOwnerWorkflows owner,String revision,String ref,long version,JSONObject evidence,JSONObject target){
            this.owner=owner;this.revision=revision;this.ref=ref;this.version=version;this.evidence=evidence.toString();this.target=target.toString();
        }
        public JSONObject view()throws Exception{return new JSONObject().put("evidence",new JSONObject(evidence)).put("target",new JSONObject(target));}
    }
    public Review review(String revision,String ref,long version,String taskId)throws Exception{
        final Review[] selected=new Review[1];module.withReviewedOwnerEvidence(activity,revision,ref,version,evidence->{
            synchronized(tasks){selected[0]=new Review(this,revision,ref,version,evidence,target(taskId));}return new JSONObject();
        });return selected[0];
    }
    /** Recheck the exact live notice under its original revocation lock. TaskStore's monitor
     * fences the owner/task/run check and its existing idempotent attachment link together. */
    public JSONObject save(Review reviewed,boolean confirmed)throws Exception{
        if(!confirmed||reviewed==null||reviewed.owner!=this)throw new SecurityException("请在原本机页面明确确认这一条通知和原任务");
        return module.withReviewedOwnerEvidence(activity,reviewed.revision,reviewed.ref,reviewed.version,evidence->{
            if(!TaskStore.capabilityFingerprint(evidence).equals(TaskStore.capabilityFingerprint(new JSONObject(reviewed.evidence))))throw new SecurityException("原通知内容已变化，请重新审阅");
            synchronized(tasks){
                JSONObject expected=new JSONObject(reviewed.target),current=target(expected.getString("taskId"));
                if(!TaskStore.capabilityFingerprint(expected).equals(TaskStore.capabilityFingerprint(current)))throw new SecurityException("原任务、主人或执行代次已变化，请重新选择");
                String key=current.getString("taskId")+"\0"+current.optString("runId")+"\0"+current.getInt("epoch")+"\0"+reviewed.revision+"\0"+reviewed.ref+"\0"+reviewed.version;
                String operation=UUID.nameUUIDFromBytes(("native-notification-evidence-v1\0"+key).getBytes(StandardCharsets.UTF_8)).toString();
                JSONObject saved=new JSONObject(evidence.toString()).put("format","pocketworld.notification-evidence.v1").put("taskId",current.getString("taskId")).put("sourceRunId",current.optString("runId")).put("sourceEpoch",current.getInt("epoch"));
                byte[] bytes=saved.toString(2).getBytes(StandardCharsets.UTF_8);String digest=hex(MessageDigest.getInstance("SHA-256").digest(bytes));
                JSONObject source=new JSONObject().put("kind","owner_reviewed_notification").put("taskId",current.getString("taskId")).put("consentRevision",reviewed.revision).put("notificationRef",reviewed.ref).put("version",reviewed.version).put("trust","low").put("authority","none").put("mayExecute",false).put("automaticModelDelivery",false);
                // A file-save cancellation fence only: this unbound control owns no run or tools.
                RunControl storage=new RunControl();JSONObject file=book.importNativeResult(new ByteArrayInputStream(bytes),"notification-preview.json","application/json",source,operation,"notification_evidence","","",digest,storage);
                return module.withReviewedOwnerEvidence(activity,reviewed.revision,reviewed.ref,reviewed.version,stillCurrent->{
                    JSONObject reference=tasks.linkOwnerAttachment(current.getString("taskId"),file);
                    return new JSONObject().put("status","saved").put("taskId",current.getString("taskId")).put("attachment",reference).put("modelDispatched",false).put("executionAuthorized",false);
                });
            }
        });
    }
    private JSONObject target(String id)throws Exception{
        JSONObject task=tasks.get(id,false);if(!personal(task))throw new SecurityException("只能另存到已有的本机私人手机或助手任务；不向群、渠道或委派任务附加通知");
        return new JSONObject().put("taskId",task.getString("id")).put("goal",task.optString("goal")).put("kind",task.getString("kind")).put("actorId",task.getString("actorId")).put("spaceId",task.getString("spaceId")).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch"));
    }
    private static boolean personal(JSONObject task){return task!=null&&"owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))&&task.optString("kind").matches("phone|assistant")&&!task.has("origin")&&!task.has("parentTaskId");}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
