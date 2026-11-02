package dev.pocketengine.features.assistant.notifications;

import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;

/** Ordinary question identity only. Approval and takeover never receive a text-answer route. */
public final class OwnerQuestionReply {
    public static final String ACTION="dev.pocketengine.ANSWER_OWNER_QUESTION",KEY="owner_question_reply";
    public interface Host { JSONObject answerOwnerQuestionFromNotification(JSONObject exact,String text)throws Exception; }
    private OwnerQuestionReply() {}
    public static JSONObject reference(JSONObject task)throws Exception{
        if(!"awaiting_user".equals(task.optString("status")))return null;
        JSONObject evidence=task.optJSONObject("evidence"),question=evidence==null?null:evidence.optJSONObject("ownerQuestion");
        if(question==null||!"pocketworld.owner-question.v1".equals(question.optString("format"))||!"answer".equals(question.optString("replyKind"))
                ||!Boolean.FALSE.equals(question.opt("approval"))||!Boolean.FALSE.equals(question.opt("takeover"))||question.has("replyConsumed")&&!Boolean.FALSE.equals(question.opt("replyConsumed")))return null;
        String taskId=task.optString("id"),run=task.optString("runId"),request=question.optString("requestId");int epoch=task.optInt("coordinatorEpoch");Object revision=question.opt("waitRevision");
        if(!taskId.equals(question.optString("taskId"))||!run.equals(question.optString("runId"))||epoch<1||epoch!=question.optInt("coordinatorEpoch")
                ||!taskId.matches("[a-fA-F0-9-]{36}")||!run.matches("[a-fA-F0-9-]{36}")||!request.matches("[a-fA-F0-9-]{36}")
                ||!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1||!(question.opt("question") instanceof String)||question.getString("question").length()>4000)return null;
        return new JSONObject().put("taskId",taskId).put("runId",run).put("coordinatorEpoch",epoch).put("requestId",request).put("waitRevision",revision);
    }
    public static JSONObject dispatch(Host host,Intent intent)throws Exception{
        if(intent==null||!ACTION.equals(intent.getAction()))throw new SecurityException("不是原问题的通知答复");
        JSONObject exact=new JSONObject(intent.getStringExtra("questionIdentity"));String token=intent.getStringExtra("noticeToken");
        if(token==null||!token.matches("[a-f0-9]{64}"))throw new SecurityException("通知定位已失效");exact.put("noticeToken",token);
        Bundle result=RemoteInput.getResultsFromIntent(intent);CharSequence supplied=result==null?null:result.getCharSequence(KEY);
        if(supplied==null||supplied.toString().isBlank()||supplied.length()>4000)throw new IllegalArgumentException("请输入1至4000字的回答");
        return host.answerOwnerQuestionFromNotification(exact,supplied.toString().strip());
    }
}
