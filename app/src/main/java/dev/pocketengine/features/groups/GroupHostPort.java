package dev.pocketengine.features.groups;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adapter to the existing TaskStore/runner. Group metadata never owns task progress. */
public interface GroupHostPort {
    TaskReceipt admit(Request request,Authority authority) throws Exception;
    /** Query only. Null is not authorization to resubmit. */
    TaskReceipt inspect(Request request,Authority authority) throws Exception;
    /** Native TaskStore projection, without model text or approval parameters. */
    default Progress inspectProgress(Request request,Authority authority)throws Exception {
        return new Progress(inspect(request,authority),"unreported",false);
    }
    /** Revalidate original input dependencies at the physical output boundary. Never an execution grant. */
    default void checkInputSources(Request request,Authority read)throws Exception {read.checkRead();}
    /** Authenticated original group input, not a new task or a private owner message. */
    default TaskReceipt input(Request request,GroupTaskInput input,Authority authority)throws Exception {throw new IllegalStateException("原群补充输入尚未挂载");}
    /** Explicit native continuation only; never read-only recovery or a second admission. */
    default TaskReceipt resume(Request request,Authority authority) throws Exception {throw new IllegalStateException("原群任务续接尚未挂载");}
    /**
     * Native reviewed restart of the same interrupted task. Commit the original TaskStore
     * attempt before returning/throwing; admission must never be deferred to another worker.
     * A lost acknowledgement is reconciled by inspect, never by another admission.
     */
    default TaskReceipt recover(Request request,String expectedRunId,int expectedEpoch,long expectedRevision,Authority authority) throws Exception {
        throw new RecoveryNotStarted("原群任务中断恢复尚未挂载");
    }
    /** Native host proof that no new attempt was committed or can be admitted later by this call. */
    final class RecoveryNotStarted extends Exception {
        public RecoveryNotStarted(String message){super(message);}
        public RecoveryNotStarted(String message,Throwable cause){super(message,cause);}
    }
    TaskReceipt cancel(Request request,String expectedRunId,int expectedCoordinatorEpoch,Authority authority) throws Exception;
    interface Authority {
        /** Required before every new model/tool/effect dispatch; terminal reservations cannot resume. */
        void check() throws Exception;
        /** Required for TaskStore inspection/output reads. Does not authorize execution. */
        default void checkRead() throws Exception {check();}
        /** Verify retained contributor identity/role/epoch again immediately before consumption. */
        default void checkInput(GroupTaskInput input)throws Exception {throw new SecurityException("原群输入来源核对尚未挂载");}
        /** Physical delivery admission, invoked only outside channel/registry monitors. */
        default void beforeDelivery()throws Exception {check();}
    }

    final class Progress {
        public final TaskReceipt task;
        public final String approvalState;
        public final boolean unresolved;
        public final JSONObject question;
        public Progress(TaskReceipt task,String approvalState,boolean unresolved){
            this(task,approvalState,unresolved,null);
        }
        public Progress(TaskReceipt task,String approvalState,boolean unresolved,JSONObject question){
            this.question=question;this.task=task;this.approvalState=GroupJson.one(approvalState,"审批状态","none","pending","approved","denied","expired","unreported");this.unresolved=unresolved;
        }
    }

    final class Request {
        public final String requestId,taskId,groupId,spaceId,actorId,goal,capability,resource,audience,visibility,policyEpoch;
        public final long maxTokens,maxMediaBytes;
        public final int maxMediaItems;
        public final GroupIngressPort.Event event;
        private final JSONArray artifacts;
        Request(JSONObject row) throws Exception {
            requestId=row.getString("requestId");taskId=row.getString("taskId");groupId=row.getString("groupId");spaceId=row.getString("spaceId");
            actorId=row.getString("actorId");goal=row.getString("goal");capability=row.getString("capability");resource=row.getString("resource");
            audience=row.getString("audience");visibility=row.getString("visibility");policyEpoch=row.getString("policyEpoch");
            maxTokens=row.getLong("maxTokens");maxMediaBytes=row.getLong("maxMediaBytes");maxMediaItems=row.getInt("mediaItems");
            event=GroupIngressPort.Event.from(row.getJSONObject("event"));artifacts=event.artifacts();
        }
        public JSONArray inputArtifacts() throws Exception {return GroupJson.copy(artifacts);}
        public JSONObject origin() throws Exception {JSONObject value=new JSONObject().put("kind","group").put("requestId",requestId).put("groupId",groupId)
                .put("spaceId",spaceId).put("actorId",actorId).put("eventId",event.eventId).put("provider",event.provider)
                .put("accountId",event.accountId).put("roomId",event.roomId).put("senderId",event.senderId).put("messageId",event.messageId)
                .put("transportEpoch",event.transportEpoch).put("policyEpoch",policyEpoch).put("capability",capability).put("resource",resource)
                .put("audience",audience).put("visibility",visibility).put("maxTokens",maxTokens).put("maxMediaItems",maxMediaItems).put("maxMediaBytes",maxMediaBytes);if(!event.conversationKind.equals("group"))value.put("conversationKind",event.conversationKind).put("conversationParentId",event.conversationParentId);return value;}
    }

    final class Output {
        public final String outputId,spaceId,audience,mimeType,artifactId,text;
        public final long bytes;
        public Output(String outputId,String spaceId,String audience,String mimeType,String artifactId,String text,long bytes) {
            this.outputId=GroupJson.id(outputId,"outputId");this.spaceId=GroupJson.id(spaceId,"spaceId");
            this.audience=GroupJson.one(audience,"结果受众","group","requester");
            this.mimeType=GroupJson.text(mimeType,120,"mimeType").toLowerCase(java.util.Locale.ROOT);
            this.artifactId=artifactId==null?"":artifactId;this.text=text==null?"":text;
            if(bytes<0||bytes>20*1024*1024L)throw new IllegalArgumentException("产物超出群媒体大小限制");
            this.bytes=bytes;
            if(this.mimeType.equals("text/plain")) {
                GroupJson.text(this.text,4000,"结果正文");if(!this.artifactId.isEmpty())throw new IllegalArgumentException("文字结果不能带文件路径");
            } else {GroupJson.id(this.artifactId,"artifactId");if(!this.text.isEmpty())throw new IllegalArgumentException("媒体通过产物引用传输");}
        }
        JSONObject json() throws Exception {return new JSONObject().put("outputId",outputId).put("spaceId",spaceId).put("audience",audience)
                .put("mimeType",mimeType).put("artifactId",artifactId).put("text",text).put("bytes",bytes);}
        static Output from(JSONObject v) throws Exception {return new Output(v.getString("outputId"),v.getString("spaceId"),v.getString("audience"),
                v.getString("mimeType"),v.getString("artifactId"),v.getString("text"),v.getLong("bytes"));}
    }
    final class TaskReceipt {
        public final String taskId,runId,status,summary;
        public final int coordinatorEpoch;
        public final long revision;
        public final List<Output> outputs;
        public TaskReceipt(String taskId,String runId,int coordinatorEpoch,long revision,String status,String summary,List<Output> outputs) {
            if(taskId==null||!taskId.matches("[a-f0-9-]{36}")||revision<1)throw new IllegalArgumentException("任务回执身份无效");
            if(runId==null||!(runId.isEmpty()&&coordinatorEpoch==0||runId.matches("[a-f0-9-]{36}")&&coordinatorEpoch>0))throw new IllegalArgumentException("执行代次无效");
            this.status=GroupJson.one(status,"任务状态","preparing","queued","running","paused","stopping","awaiting_user","needs_confirmation",
                    "completed","failed","cancelled","interrupted","reconciling");
            if(summary==null||summary.length()>3000)throw new IllegalArgumentException("群任务摘要过长");
            this.taskId=taskId;this.runId=runId;this.coordinatorEpoch=coordinatorEpoch;this.revision=revision;this.summary=summary;
            if(outputs!=null&&outputs.size()>72)throw new IllegalArgumentException("群产物数超限");
            this.outputs=Collections.unmodifiableList(outputs==null?new ArrayList<>():new ArrayList<>(outputs));
            java.util.HashSet<String> ids=new java.util.HashSet<>();for(Output output:this.outputs)if(output==null||!ids.add(output.outputId))throw new IllegalArgumentException("群产物身份重复");
        }
        public boolean terminal() {return status.equals("completed")||status.equals("failed")||status.equals("cancelled")||status.equals("interrupted");}
        public JSONObject json() throws Exception {JSONArray result=new JSONArray();for(Output output:outputs)result.put(output.json());
            return new JSONObject().put("taskId",taskId).put("runId",runId).put("coordinatorEpoch",coordinatorEpoch).put("revision",revision)
                    .put("status",status).put("summary",summary).put("outputs",result);}
    }
}
