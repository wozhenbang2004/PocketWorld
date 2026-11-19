package dev.pocketengine.features.assistant.notifications;

import dev.pocketengine.store.TaskStore;
import org.json.JSONObject;
import java.util.Set;

/** Body-free view of an actual private single-Agent result. Neither a receipt nor a permission. */
public final class TaskNotificationState {
    private TaskNotificationState(){}
    public static JSONObject candidate(JSONObject task)throws Exception{
        if(task==null||!"assistant".equals(task.optString("kind"))||!"capabilities".equals(task.optString("executionMode"))
                ||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.has("sourceLearningJob"))return null;
        JSONObject origin=task.optJSONObject("origin");if(origin!=null&&!"owner_reviewed_realtime".equals(origin.optString("kind")))return null;
        String status=task.optString("status");if(!Set.of("completed","needs_confirmation","awaiting_user","reconciling","failed","interrupted","waiting_approval","awaiting_approval").contains(status))return null;
        JSONObject work=task.optJSONObject("ownerWork"),takeover=work==null?null:work.optJSONObject("takeover");
        // One user-facing result notification: while the original allowance is
        // reserved/claimed, the native report task will deliver the result notice.
        // Decisions and unknown effects still keep their own actionable notice.
        if(takeover!=null&&status.matches("completed|failed")&&takeover.optString("state").matches("reserved|claimed"))return null;
        String taskId=task.getString("id"),run=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");if(!taskId.matches("[a-fA-F0-9-]{36}")||!run.matches("[a-fA-F0-9-]{36}")||epoch<1)return null;
        String anchor=status.equals("completed")||status.equals("needs_confirmation")?"result":status.equals("reconciling")?"reconciliation":status.matches("waiting_approval|awaiting_approval")?"approval":"decision";
        JSONObject value=new JSONObject().put("taskId",taskId).put("runId",run).put("epoch",epoch).put("status",status).put("anchor",anchor).put("conversationId",task.optString("conversationId"));
        JSONObject question=OwnerQuestionReply.reference(task);if(question!=null)value.put("ownerQuestion",question);
        return value.put("token",TaskStore.capabilityFingerprint(value)).put("executionAuthorized",false).put("deliveryConfirmed",false);
    }
    public static String title(String status) {
        return switch (status) {
            case "completed" -> "助手已有新结果";
            case "needs_confirmation" -> "结果待你确认";
            case "reconciling" -> "有一项操作待核对";
            case "waiting_approval", "awaiting_approval" -> "有一项操作待授权";
            case "failed" -> "任务执行失败";
            case "interrupted" -> "任务执行已中断";
            case "awaiting_user" -> "任务正等你回应";
            default -> "助手任务有更新";
        };
    }

    public static String summary(String status) {
        return switch (status) {
            case "completed" -> "打开原任务查看成果";
            case "needs_confirmation" -> "打开原任务核对结果，再决定是否确认";
            case "reconciling" -> "打开原任务核对已发生的操作";
            case "waiting_approval", "awaiting_approval" -> "打开原任务查看申请的操作与范围";
            case "failed" -> "打开原任务查看失败原因和执行记录";
            case "interrupted" -> "打开原任务核对中断前的进展";
            case "awaiting_user" -> "打开原任务查看需要你处理的事项";
            default -> "打开原任务查看最新记录";
        };
    }
}
