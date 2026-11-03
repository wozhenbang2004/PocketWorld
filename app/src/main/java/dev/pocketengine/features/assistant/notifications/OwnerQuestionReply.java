package dev.pocketengine.features.assistant.notifications;

import android.app.RemoteInput;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;

/** Ordinary question identity only. Approval and takeover never receive a text-answer route. */
public final class OwnerQuestionReply {
    public static final String ACTION="dev.pocketengine.ANSWER_OWNER_QUESTION",STOP="dev.pocketengine.STOP_OWNER_TASK_NOTICE",KEY="owner_question_reply";
    public interface Host {
        JSONObject answerOwnerQuestionFromNotification(JSONObject exact,String text)throws Exception;
        JSONObject stopOwnerTaskFromNotification(JSONObject exact)throws Exception;
    }
    private OwnerQuestionReply() {}
    public static JSONObject reference(JSONObject task)throws Exception{
        if(task==null||task.optBoolean("cancelRequested")||!"assistant".equals(task.optString("kind"))||!"capabilities".equals(task.optString("executionMode"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))
                ||task.optString("conversationId").isBlank()||task.has("sourceLearningJob")||!"awaiting_user".equals(task.optString("status")))return null;
        JSONObject origin=task.optJSONObject("origin");if(origin!=null&&!"owner_reviewed_realtime".equals(origin.optString("kind")))return null;
        JSONObject mailbox=task.optJSONObject("ownerInputMailbox");if(mailbox==null||!Boolean.FALSE.equals(mailbox.opt("open"))||!task.optString("runId").equals(mailbox.optString("runId"))||task.optInt("coordinatorEpoch")!=mailbox.optInt("epoch")||!task.getString("conversationId").equals(mailbox.optString("conversationId")))return null;
        return identity(task);
    }
    /** Question binding data only. UI/action routing must additionally use reference's real
     * supported native scope; possession of this identity authorizes no continuation. */
    public static JSONObject identity(JSONObject task)throws Exception{
        if(task==null||!"awaiting_user".equals(task.optString("status")))return null;
        JSONObject evidence=task.optJSONObject("evidence"),question=evidence==null?null:evidence.optJSONObject("ownerQuestion");
        if(question==null||!"pocketworld.owner-question.v1".equals(question.optString("format"))||!"answer".equals(question.optString("replyKind"))
                ||!Boolean.FALSE.equals(question.opt("approval"))||!Boolean.FALSE.equals(question.opt("takeover"))||question.has("replyConsumed")&&!Boolean.FALSE.equals(question.opt("replyConsumed")))return null;
        String taskId=task.optString("id"),run=task.optString("runId"),request=question.optString("requestId");int epoch=task.optInt("coordinatorEpoch");Object revision=question.opt("waitRevision");
        if(!taskId.equals(question.optString("taskId"))||!run.equals(question.optString("runId"))||epoch<1||epoch!=question.optInt("coordinatorEpoch")
                ||!taskId.matches("[a-fA-F0-9-]{36}")||!run.matches("[a-fA-F0-9-]{36}")||!request.matches("[a-fA-F0-9-]{36}")
                ||!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1||!(question.opt("question") instanceof String)||question.getString("question").length()>4000)return null;
        return new JSONObject().put("taskId",taskId).put("runId",run).put("coordinatorEpoch",epoch).put("requestId",request).put("waitRevision",revision);
    }
    public static Intent intent(Context context,String action,JSONObject view)throws Exception{
        if(!ACTION.equals(action)&&!STOP.equals(action))throw new IllegalArgumentException("未知原通知动作");
        JSONObject identity=new JSONObject();for(String key:new String[]{"taskId","runId","epoch","token","status"})identity.put(key,view.get(key));
        Intent intent=new Intent(context,OwnerQuestionReplyReceiver.class).setAction(action).setData(uri(action,identity)).putExtra("noticeView",identity.toString());
        if(ACTION.equals(action))intent.putExtra("questionIdentity",view.getJSONObject("ownerQuestion").toString()).putExtra("noticeToken",view.getString("token"));return intent;
    }
    private static Uri uri(String action,JSONObject view)throws Exception{return new Uri.Builder().scheme("pocketworld-task-action").authority("owner").appendPath(action).appendPath(view.getString("taskId")).appendPath(view.getString("runId")).appendPath(Integer.toString(view.getInt("epoch"))).appendPath(view.getString("token")).build();}
    public static JSONObject dispatch(Host host,Intent intent)throws Exception{
        if(intent==null||!ACTION.equals(intent.getAction())&&!STOP.equals(intent.getAction()))throw new SecurityException("不是原任务通知动作");
        JSONObject view=new JSONObject(intent.getStringExtra("noticeView"));
        if(!uri(intent.getAction(),view).equals(intent.getData()))throw new SecurityException("原通知动作定位已改变");
        if(STOP.equals(intent.getAction()))return host.stopOwnerTaskFromNotification(view);
        JSONObject exact=new JSONObject(intent.getStringExtra("questionIdentity"));String token=intent.getStringExtra("noticeToken");
        if(token==null||!token.matches("[a-f0-9]{64}")||!token.equals(view.getString("token"))||!view.getString("taskId").equals(exact.getString("taskId"))||!view.getString("runId").equals(exact.getString("runId"))||view.getInt("epoch")!=exact.getInt("coordinatorEpoch"))throw new SecurityException("通知定位已失效");exact.put("noticeToken",token);
        Bundle result=RemoteInput.getResultsFromIntent(intent);CharSequence supplied=result==null?null:result.getCharSequence(KEY);
        if(supplied==null||supplied.toString().isBlank()||supplied.length()>4000)throw new IllegalArgumentException("请输入1至4000字的回答");
        return host.answerOwnerQuestionFromNotification(exact,supplied.toString().strip());
    }
}
