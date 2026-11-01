package dev.pocketengine.features.groups.host;

import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native read-only display of the original TaskStore; never a grant or an execution result. */
public final class GroupTaskPresentationReader {
    private final TaskStore tasks;
    private final LongSupplier clock;

    public GroupTaskPresentationReader(TaskStore tasks) {this(tasks,System::currentTimeMillis);}
    public GroupTaskPresentationReader(TaskStore tasks,LongSupplier clock) {
        this.tasks=Objects.requireNonNull(tasks);this.clock=Objects.requireNonNull(clock);
    }

    /** Call only within GroupsModule.withOwnerTaskRead and the existing native owner worker. */
    public JSONObject read(GroupHostPort.Request request,GroupHostPort.Authority readAuthority) throws Exception {
        readAuthority.checkRead();JSONObject task=tasks.groupTask(request,readAuthority);
        if(task==null){readAuthority.checkRead();return null;}
        long now=clock.getAsLong();String run=task.optString("runId"),status=task.getString("status");
        JSONArray operations=task.optJSONArray("operations");boolean unknown=false,mediaEstablished=false;
        for(int i=0;operations!=null&&i<operations.length();i++) {
            JSONObject operation=operations.getJSONObject(i);String state=operation.optString("state");
            if(state.matches("prepared|unknown|remote_job_prepared|remote_job_sending|remote_job_unknown|remote_approval_sending|remote_approval_unknown"))unknown=true;
            JSONObject receipt=operation.optJSONObject("receipt");
            if(operation.optString("tool").startsWith("group_media_")&&(receipt==null||!receipt.optString("code").equals("approval_required")))mediaEstablished=true;
        }
        JSONObject result=new JSONObject().put("requestId",request.requestId).put("taskId",request.taskId).put("spaceId",request.spaceId)
                .put("taskRevision",task.getLong("groupStateRevision")).put("runId",run).put("coordinatorEpoch",task.optInt("coordinatorEpoch"))
                .put("status",status).put("observedAt",now).put("progress",progress(task)).put("approval",approval(task,run,now))
                .put("recovery",recovery(status,task.optBoolean("cancelRequested"),unknown,mediaEstablished)).put("artifactHints",artifactHints(request,operations));
        readAuthority.checkRead();return result;
    }

    private static JSONObject progress(JSONObject task) throws Exception {
        JSONArray events=task.optJSONArray("events");String phase="",message="";
        for(int i=events==null?-1:events.length()-1;i>=0;i--) {
            JSONObject event=events.optJSONObject(i);if(event==null)continue;
            if(!task.optString("runId").equals(event.optString("runId"))||task.optInt("coordinatorEpoch")!=event.optInt("coordinatorEpoch",-1))continue;
            String type=event.optString("type");
            if(type.equals("model_stream")||type.equals("operation_intent")||type.equals("operation_receipt"))continue;
            switch(type) {
                case "model":case "context_budget":phase="planning";message="正在整理原群任务的下一步";break;
                case "tool":phase="acting";message=text(event,"message",1000);break;
                case "verification":phase="verifying";message="正在汇总原任务的实际回执";break;
                case "run":message=task.optInt("coordinatorEpoch")>1?"正在恢复同一个群任务":"原群任务已进入原执行器";break;
                case "result":message=text(task,"summary",1000);break;
                default:continue;
            }
            break;
        }
        if(message.isEmpty()&&task.optString("status").matches("completed|failed|interrupted|cancelled"))message=text(task,"summary",1000);
        // A step ceiling is a safety budget, not an accepted plan or a completion denominator.
        return new JSONObject().put("phase",phase).put("message",message);
    }

    private static JSONObject approval(JSONObject task,String run,long now) throws Exception {
        JSONArray approvals=task.optJSONArray("approvals");String chosen="not_required",message="";int priority=0;
        for(int i=0;approvals!=null&&i<approvals.length();i++) {
            JSONObject approval=approvals.getJSONObject(i);if(!run.equals(approval.optString("runId")))continue;
            String state=approval.optString("state"),mapped,description;int rank;
            if(state.equals("pending")&&approval.optLong("expiresAt")>now){mapped="pending";description="原任务有一项待主人核对的敏感操作";rank=5;}
            else if(state.equals("pending")||state.equals("approved")&&approval.optLong("expiresAt")<=now){mapped="required";description="原审批已过期；需要在原任务中重新核对，未自动批准";rank=4;}
            else if(state.equals("denied")){mapped="denied";description="主人已拒绝原操作；刷新或重启不会改为批准";rank=3;}
            else if(state.equals("invalidated")){mapped="revoked";description="原操作能力已变化，旧审批已失效";rank=2;}
            else if(state.matches("approved|consumed")){mapped="approved";description="原操作已有主人审批记录；不代表任务结果已完成或群消息已送达";rank=1;}
            else continue;
            if(rank>=priority){priority=rank;chosen=mapped;message=description;}
        }
        return new JSONObject().put("state",chosen).put("message",message);
    }

    private static JSONObject recovery(String status,boolean cancelled,boolean unknown,boolean mediaEstablished) throws Exception {
        boolean canReview=!cancelled&&!unknown&&!mediaEstablished&&status.matches("paused|awaiting_user|needs_confirmation|interrupted");
        String message;
        if(unknown)message="原操作结果仍未知。先查询并核对原编号；不会重新提交任务、生成或发送。";
        else if(mediaEstablished)message="原媒体操作已经建立。请查看或查询原成果与原投递，不会重新生成。";
        else if(cancelled||status.equals("cancelled"))message="停止请求已保存，已有结果保留。查询不会重新启动任务。";
        else if(status.equals("interrupted"))message="原任务与已有回执已保留。打开原任务核对同一执行版本；恢复会重新核验群授权、并发和当天预留，累计预算不重置。";
        else if(status.equals("failed"))message="原任务失败记录已保留。查看失败原因与已有操作，不会因刷新自动重做。";
        else if(canReview)message="打开原任务核对当前群授权、已有操作及待主人事项；此入口只导航，不直接继续。";
        else message="状态来自原任务记录；外部效果与消息投递以各自实际回执为准。";
        return new JSONObject().put("canReviewContinue",canReview).put("message",message);
    }

    private static JSONArray artifactHints(GroupHostPort.Request request,JSONArray operations) throws Exception {
        JSONArray hints=new JSONArray();java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;operations!=null&&i<operations.length()&&hints.length()<8;i++) {
            JSONObject operation=operations.getJSONObject(i),receipt=operation.optJSONObject("receipt");
            if(!operation.optString("state").equals("receipt")||receipt==null)continue;
            JSONArray artifacts=receipt.optJSONArray("groupArtifacts");
            for(int j=0;artifacts!=null&&j<artifacts.length()&&hints.length()<8;j++) {
                JSONObject artifact=artifacts.getJSONObject(j);String id=artifact.optString("artifactId");
                if(!id.matches("[a-f0-9-]{36}")||!request.spaceId.equals(artifact.optString("spaceId"))
                        ||!request.taskId.equals(artifact.optString("taskId"))||!request.audience.equals(artifact.optString("audience"))||!seen.add(id))continue;
                JSONObject hint=new JSONObject().put("outputId",id).put("sourceOperationId",operation.getString("operationId"))
                        .put("sourceTaskRunId",operation.getString("runId"));
                // Original source epoch/job must come from original native evidence, never current run.
                if(!text(artifact,"displayName",200).isEmpty())hint.put("displayName",text(artifact,"displayName",200));
                hints.put(hint);
            }
        }
        return hints;
    }
    private static String text(JSONObject value,String key,int limit) {
        Object raw=value.opt(key);if(!(raw instanceof String))return "";String text=(String)raw;return text.length()>limit?text.substring(0,limit):text;
    }
}
