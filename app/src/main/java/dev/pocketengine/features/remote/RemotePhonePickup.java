package dev.pocketengine.features.remote;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.store.CloudWorkspaceJobs;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Repair the local presentation link, not the remote job, its retention, or its contents. */
public final class RemotePhonePickup {
    private final TaskStore tasks;
    private final AttachmentBook book;
    public RemotePhonePickup(TaskStore tasks,AttachmentBook book){this.tasks=tasks;this.book=book;}
    private static SecurityException changed(){return new SecurityException("不能核对当前私人任务、原主机作业和所选冻结版本；没有改用其他文件");}
    private static void same(JSONObject a,JSONObject b,String key)throws Exception{
        Object v=a.opt(key);if(!(v instanceof String s)||s.isBlank()||!s.equals(b.opt(key)))throw changed();
    }
    private static long number(JSONObject o,String k){Object n=o.opt(k);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<0)throw changed();return ((Number)n).longValue();}
    /** Called by the owning TaskStore with its own raw row and persisted job identity, not a UI projection. */
    public static JSONObject selected(JSONObject task,JSONObject target,JSONObject pick)throws Exception{
        if(!"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))||!"remote".equals(task.opt("feature"))
                ||!"remote".equals(task.opt("kind"))||!"submitJob".equals(task.opt("command"))||!task.isNull("parentTaskId")||!task.isNull("teamRunId")
                ||!task.isNull("memberId")||Boolean.FALSE.equals(task.opt("contextAvailable"))||task.optBoolean("contextWithdrawn")||task.optBoolean("sourceCurrentReadRequired")
                ||"source_unavailable".equals(task.opt("taskProjection"))||!task.isNull("origin")&&(task.optJSONObject("origin")==null
                ||!"native_remote_private_inputs".equals(task.getJSONObject("origin").opt("kind"))))throw changed();
        if(!task.getString("id").equals(pick.opt("taskId"))||!task.getString("id").equals(target.opt("taskId"))
                ||!task.getString("runId").equals(task.opt("operationId"))||number(task,"coordinatorEpoch")!=number(pick,"coordinatorEpoch")
                ||number(task,"coordinatorEpoch")!=number(target,"coordinatorEpoch"))throw changed();
        same(pick,task,"runId");same(pick,target,"runId");same(pick,target,"jobId");
        if(!task.getString("operationId").equals(target.opt("jobOperationId")))throw changed();
        JSONObject snapshot=null;JSONArray versions=task.optJSONArray("remoteResultSnapshots");
        for(int i=0;versions!=null&&i<versions.length();i++){JSONObject v=versions.getJSONObject(i);
            if(!pick.getString("snapshotId").equals(v.opt("snapshotId")))continue;if(snapshot!=null)throw changed();snapshot=v;}
        if(snapshot==null||!Boolean.TRUE.equals(snapshot.opt("immutableArtifact"))||!Boolean.FALSE.equals(snapshot.opt("authorshipVerified"))
                ||!"completed".equals(snapshot.opt("status"))||!"selected_current_workspace_after_settled_original_job".equals(snapshot.opt("captureSource")))throw changed();
        JSONObject source=snapshot.getJSONObject("source");same(pick,snapshot,"snapshotVersion");
        for(String k:new String[]{"taskId","runId","profileId","serviceBase","jobOperationId","jobId","environmentId","worker"})same(target,source,k);
        for(String k:new String[]{"jobId","jobOperationId","environmentId","worker"})same(snapshot,source,k);
        if(number(source,"coordinatorEpoch")!=number(target,"coordinatorEpoch")||!snapshot.getString("workspaceIdentity").matches("[0-9a-f]{64}"))throw changed();
        // An absent current location proof is never substituted for an explicit, different one.
        for(String k:new String[]{"profileBinding","environmentBinding"})if(source.has(k)||target.has(k))same(source,target,k);
        if(source.has("workspaceIdentity"))same(source,snapshot,"workspaceIdentity");
        if(target.has("workspaceIdentity"))same(target,snapshot,"workspaceIdentity");
        if((source.has("hostWorkspace")||target.has("hostWorkspace"))&&!CloudWorkspaceJobs.same(source.optJSONObject("hostWorkspace"),target.optJSONObject("hostWorkspace")))throw changed();
        JSONArray files=snapshot.getJSONArray("files");if(files.length()<1||files.length()>8)throw changed();JSONObject exact=null;
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if(!pick.getString("path").equals(file.opt("path")))continue;if(exact!=null)throw changed();exact=file;}
        if(exact==null||number(exact,"bytes")>262144||number(exact,"bytes")!=number(pick,"bytes"))throw changed();
        for(String k:new String[]{"sha256","fileVersion"}){if(!exact.getString(k).matches("[a-f0-9]{64}"))throw changed();same(exact,pick,k);}
        return new JSONObject(snapshot.toString());
    }
    /** No serializable authority. Only the object minted by this local full byte check can be confirmed. */
    public static final class Review {
        private final RemotePhonePickup issuer;private final String selection,attachment,sourceDigest;
        private Review(RemotePhonePickup host,JSONObject pick,JSONObject file)throws Exception {
            issuer=host;selection=pick.toString();attachment=file.getString("id");sourceDigest=TaskStore.capabilityFingerprint(file.getJSONObject("source"));
        }
        public JSONObject receipt()throws Exception{return new JSONObject(selection).put("attachmentId",attachment).put("state","verified_on_phone").put("networkUsed",false);}
    }
    public Review review(JSONObject selection,RunControl current)throws Exception{
        JSONObject fixed=new JSONObject(selection.toString());
        RemoteResultFiles.Saved found=RemoteResultFiles.onPhone(tasks,book,fixed,current);
        return found==null?null:new Review(this,fixed,found.view().getJSONObject("attachment"));
    }
    public JSONObject confirm(Review review,RunControl current)throws Exception{
        if(review==null||review.issuer!=this)throw changed();JSONObject pick=new JSONObject(review.selection);String task=pick.getString("taskId");
        // Book I/O completes before taking Tasks. The final Book generation lookup is lock-free.
        RemoteResultFiles.Saved verified=RemoteResultFiles.onPhone(tasks,book,pick,current);if(verified==null)throw changed();
        JSONObject actual=verified.view().getJSONObject("attachment");String digest=pick.getString("sha256");
        if(!review.attachment.equals(actual.opt("id"))||!review.sourceDigest.equals(TaskStore.capabilityFingerprint(actual.getJSONObject("source"))))throw changed();
        AttachmentBook.OutputSourceEligibility live=book.outputSourceEligibility(review.attachment,digest);if(live==null)throw changed();
        synchronized(tasks){
            tasks.offlineRemoteFileSnapshot(pick);JSONArray refs=tasks.remoteSnapshotAttachments(task);boolean already=false;
            if(refs.length()>64)throw changed();
            for(int i=0;i<refs.length();i++){
                JSONObject r=refs.getJSONObject(i),a=r.getJSONObject("attachment");
                boolean tuple=pick.getString("snapshotId").equals(r.opt("snapshotId"))&&pick.getString("path").equals(r.opt("path"));
                boolean id=review.attachment.equals(a.opt("id"));if(!id&&!tuple)continue;if(already||!tuple||!id)throw changed();
                for(String key:new String[]{"taskId","snapshotVersion","fileVersion","sha256"})same(pick,r,key);
                if(number(r,"bytes")!=number(pick,"bytes")||!review.sourceDigest.equals(TaskStore.capabilityFingerprint(a.getJSONObject("source"))))throw changed();
                already=true;
            }
            current.check();if(!book.outputSourceCurrent(review.attachment,digest,live.generation))throw changed();
            if(!already)tasks.recordRemoteSnapshotAttachment(task,verified);
            return review.receipt().put("state",already?"already_linked":"linked_on_phone").put("remoteExecution",false);
        }
    }
}
