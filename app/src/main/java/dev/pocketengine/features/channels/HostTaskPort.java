package dev.pocketengine.features.channels;

import org.json.JSONObject;

/** Native adapter to the one host TaskStore/worker. Never exposed as an ingress command. */
public interface HostTaskPort {
    /**
     * Persist/admit exactly request.taskId, idempotently, then return promptly; do not run a model
     * on the ingress thread. Retain authority and check it before every later dispatch, including
     * resumed runs. All host tools/approvals/unknown-effect rules still apply.
     */
    TaskState admit(Request request,Authority authority)throws Exception;
    /** Read the same task only; null means not found, never permission to submit it again. */
    TaskState inspect(Request request,Authority authority)throws Exception;
    /** Cancel only request.expectedRunId/expectedCoordinatorEpoch; a delayed old call cannot stop a new run. */
    TaskState cancel(Request request,Authority authority)throws Exception;
    interface Authority {void check()throws Exception;}

    final class Request {
        public final String requestId,taskId,goal,expectedRunId;
        public final boolean phoneScreenClaim;
        public final int expectedCoordinatorEpoch;
        private final JSONObject origin;
        Request(JSONObject stored)throws Exception {this(stored,false);}
        Request(JSONObject stored,boolean cancellation)throws Exception {this(stored,cancellation,false);}
        Request(JSONObject stored,boolean cancellation,boolean phoneScreenClaim)throws Exception {
            this.phoneScreenClaim=phoneScreenClaim;
            requestId=stored.getString("requestId");taskId=stored.getString("taskId");goal=stored.getString("goal");
            expectedRunId=stored.optString(cancellation?"cancellationRunId":"hostRunId","");
            expectedCoordinatorEpoch=stored.optInt(cancellation?"cancellationEpoch":"hostCoordinatorEpoch",0);
            JSONObject event=stored.getJSONObject("originEvent");
            origin=new JSONObject().put("kind","channel").put("requestId",requestId).put("eventId",event.getString("id"))
                    .put("profileId",event.getString("profileId")).put("messageId",event.getString("messageId"))
                    .put("senderId",event.getString("senderId")).put("authenticatedBy",event.getString("authenticatedBy"))
                    .put("bindingEpoch",stored.getString("bindingEpoch")).put("taskGrantEpoch",stored.getString("taskGrantEpoch"))
                    .put("ownerId","owner").put("spaceId","private");
            if("phone".equals(stored.optString("taskKind")))origin.put("taskKind","phone");
            if(event.optString("conversationKind").startsWith("qq_guild_"))origin.put("conversationKind",event.getString("conversationKind")).put("qqGuildId",event.getString("qqGuildId")).put("transportEpoch",event.getString("transportEpoch"));
        }
        public JSONObject origin()throws Exception{return new JSONObject(origin.toString());}
    }
    /** Deliberately excludes checkpoints, credentials, raw tool arguments and arbitrary task JSON. */
    final class TaskState {
        public final String taskId,runId,status,summary;
        public final int coordinatorEpoch;
        public final long revision;
        public TaskState(String taskId,String runId,int coordinatorEpoch,long revision,String status,String summary){
            if(taskId==null||!taskId.matches("[a-f0-9-]{36}")||revision<1)throw new IllegalArgumentException("宿主任务身份或回执版本无效");
            if(runId==null||coordinatorEpoch<0||!(runId.isEmpty()&&coordinatorEpoch==0||runId.matches("[a-f0-9-]{36}")&&coordinatorEpoch>0))throw new IllegalArgumentException("宿主执行代次无效");
            if(status==null||!status.matches("preparing|queued|running|paused|stopping|awaiting_user|needs_confirmation|completed|failed|cancelled|interrupted|reconciling"))throw new IllegalArgumentException("宿主任务状态无效");
            if(summary==null||summary.length()>3000)throw new IllegalArgumentException("宿主结果摘要超出渠道预算");
            this.taskId=taskId;this.runId=runId;this.coordinatorEpoch=coordinatorEpoch;this.revision=revision;this.status=status;this.summary=summary;
        }
    }
}
