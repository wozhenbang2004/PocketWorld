package dev.pocketengine.features.assistant;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply;
import dev.pocketengine.features.assistant.notifications.TaskNotificationState;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskExecutionScope;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native ordinary-question data and retained original controls. No executor, DB or second inbox.
 * TaskStore alone commits a reply and claims continuation in its original transaction.
 * A process restart loses retained controls: saved replies remain data, never restored authority. */
public final class OwnerQuestionInputs {
    public static final String FORMAT="pocketworld.owner-question-answer.v1";
    private final LinkedHashMap<String,Waiting> waiting=new LinkedHashMap<>();

    public static final class Waiting {
        private final OwnerQuestionInputs issuer;
        private final RunControl original;
        private final ModelConfig model;
        private final String task,run,request,conversation,grantFingerprint;
        private final int epoch;
        private final long revision,expiresAt;
        private Waiting(OwnerQuestionInputs issuer,JSONObject task,JSONObject question,JSONObject grant,RunControl original,ModelConfig model)throws Exception{
            this.issuer=issuer;this.original=original;this.model=model;this.task=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");
            request=question.getString("requestId");revision=question.getLong("waitRevision");conversation=task.getString("conversationId");expiresAt=grant.getLong("expiresAt");
            grantFingerprint=TaskStore.capabilityFingerprint(grant);
        }
        public String taskId(){return task;}public String runId(){return run;}public int epoch(){return epoch;}
        public String requestId(){return request;}public long waitRevision(){return revision;}public String conversationId(){return conversation;}
        public String grantFingerprint(){return grantFingerprint;}public long expiresAt(){return expiresAt;}
        public RunControl originalControl(){return original;}public ModelConfig capturedModel(){return model;}
        public void requireOriginal()throws Exception{
            if(!issuer.current(this)||original.isCancelled()||original.isPaused()||!original.matchesHostRun(task,run,epoch))throw new SecurityException("原问题执行控制器已停止或改变；回答只保留，不自动续权");
        }
        public boolean matches(String task,String run,int epoch,String request,long revision){return this.task.equals(task)&&this.run.equals(run)&&this.epoch==epoch&&this.request.equals(request)&&this.revision==revision;}
    }

    /** Called only after the original Runner cleanup and original TaskStore settlement. */
    public synchronized Waiting retain(JSONObject actualTask,JSONObject originalGrant,RunControl original,ModelConfig captured)throws Exception{
        requirePrivateTask(actualTask);JSONObject question=OwnerQuestionReply.reference(actualTask);
        if(question==null||original==null||captured==null||original.isCancelled()||original.isPaused()||!original.matchesHostRun(actualTask.getString("id"),actualTask.getString("runId"),actualTask.getInt("coordinatorEpoch")))return null;
        JSONObject evidence=actualTask.getJSONObject("evidence");
        for(String flag:new String[]{"extensionEndUnavailable","extensionCleanupPending","projectServiceDrainPending","projectServiceDrainEvidenceUnavailable"})if(evidence.has(flag)&&!Boolean.FALSE.equals(evidence.opt(flag)))return null;
        JSONObject mailbox=actualTask.optJSONObject("ownerInputMailbox");
        if(actualTask.optString("conversationId").isBlank()||mailbox==null||mailbox.optBoolean("open")||!actualTask.getString("runId").equals(mailbox.optString("runId"))||actualTask.getInt("coordinatorEpoch")!=mailbox.optInt("epoch"))return null;
        if(!"owner".equals(originalGrant.optString("principalId"))||!"private".equals(originalGrant.optString("spaceId"))||!"capabilities".equals(originalGrant.optString("mode"))||integer(originalGrant.opt("expiresAt"))<1||integer(originalGrant.opt("stepCeiling"))<1||integer(originalGrant.opt("remainingOperations"))<0)return null;
        prune(System.currentTimeMillis());Waiting value=new Waiting(this,actualTask,question,originalGrant,original,captured);waiting.put(value.taskId(),value);
        while(waiting.size()>32)waiting.remove(waiting.keySet().iterator().next());return value;
    }
    public synchronized Waiting find(String task,String run,int epoch,String request,long revision){
        prune(System.currentTimeMillis());Waiting value=waiting.get(task);return value!=null&&value.matches(task,run,epoch,request,revision)?value:null;
    }
    private synchronized boolean current(Waiting value){return value.issuer==this&&waiting.get(value.taskId())==value;}
    /** SQL continuation must already have been atomically claimed. No scheduling occurs here. */
    public synchronized boolean take(Waiting value){return value!=null&&value.issuer==this&&waiting.remove(value.taskId(),value);}
    public synchronized void forget(String task){waiting.remove(task);}
    public synchronized void cancel(String task,String run,int epoch){Waiting value=waiting.get(task);if(value!=null&&value.runId().equals(run)&&value.epoch()==epoch){waiting.remove(task);value.originalControl().cancel();}}
    private void prune(long now){waiting.values().removeIf(value->value.expiresAt<=now||value.original.isCancelled());}

    public static final class Reply {
        private final String row,result;
        private Reply(JSONObject row,JSONObject result){this.row=row.toString();this.result=result.toString();}
        public JSONObject row()throws Exception{return new JSONObject(row);}
        public JSONObject result()throws Exception{return new JSONObject(result);}
        public boolean duplicate()throws Exception{return result().getBoolean("duplicate");}
    }
    /** Pure decision under the caller's original TaskStore transaction. It changes no task or grant. */
    public static Reply reply(JSONObject task,String taskId,String run,int epoch,String request,long revision,String token,String supplied,long now)throws Exception{
        requirePrivateTask(task);uuid(taskId);uuid(run);uuid(request);
        if(!taskId.equals(task.getString("id"))||epoch<1||revision<1||token==null||!token.matches("[a-f0-9]{64}"))throw new SecurityException("原问题答复定位无效");
        String text=text(supplied),digest=TaskStore.capabilityFingerprint(new JSONObject().put("text",text));JSONArray rows=task.optJSONArray("ownerInputs");
        for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),proof=row.optJSONObject("ownerQuestionReply");if(proof==null||!FORMAT.equals(proof.optString("format")))continue;
            if(request.equals(proof.optString("requestId"))&&run.equals(row.optString("acceptedRunId"))&&epoch==row.optInt("acceptedEpoch")){
                if(revision!=proof.optLong("waitRevision")||!token.equals(proof.optString("noticeToken"))||!digest.equals(proof.optString("textDigest")))throw new SecurityException("同一原问题答复不能更换内容或通知身份");
                return new Reply(row,result(taskId,run,epoch,request,revision,row.getString("messageId"),true));
            }
        }
        JSONObject question=OwnerQuestionReply.reference(task),notice=task.optJSONObject("ownerNotification"),current=TaskNotificationState.candidate(task);
        if(question==null||current==null||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||!request.equals(question.optString("requestId"))||revision!=question.optLong("waitRevision")
                ||notice==null||!token.equals(notice.optString("token"))||!token.equals(current.optString("token"))||!Set.of("posting","posted","opened","unconfirmed").contains(notice.optString("phase")))throw new SecurityException("原问题或通知已经改变；未恢复其他执行");
        if(rows!=null&&rows.length()>=64)throw new IllegalStateException("原任务输入留存已满；回答未自动移入新任务");
        long chars=text.length();for(int i=0;rows!=null&&i<rows.length();i++)chars+=rows.getJSONObject(i).optString("content").length();if(chars>240000)throw new IllegalStateException("原任务输入正文已达上限");
        String message=UUID.randomUUID().toString(),client=UUID.randomUUID().toString();JSONObject proof=new JSONObject().put("format",FORMAT).put("requestId",request).put("waitRevision",revision).put("noticeToken",token).put("textDigest",digest).put("phase","saved").put("savedAt",now);
        String payload=TaskStore.capabilityFingerprint(new JSONObject().put("content",text).put("mode","queue").put("attachments",new JSONArray()));
        String fingerprint=TaskStore.capabilityFingerprint(new JSONObject().put("format",FORMAT).put("taskId",taskId).put("runId",run).put("epoch",epoch).put("requestId",request).put("waitRevision",revision).put("messageId",message).put("clientMessageId",client).put("payload",payload));
        JSONObject row=new JSONObject().put("messageId",message).put("clientMessageId",client).put("content",text).put("mode","queue").put("attachments",new JSONArray())
                .put("acceptedTaskId",taskId).put("acceptedRunId",run).put("acceptedEpoch",epoch).put("acceptedAt",now).put("ownerQuestionReply",proof)
                .put("ownerMessageRevision",1L).put("payloadSha256",payload).put("ownerRequestFingerprint",fingerprint);
        return new Reply(row,result(taskId,run,epoch,request,revision,message,false));
    }
    /** Same-run continuation decision only. TaskStore still checks native SQL status, source
     * barriers and outstanding effects before its CAS. The original grant is never rewritten. */
    public static JSONObject requireSavedContinuation(Waiting original,JSONObject task,JSONObject grant,JSONObject checkpoint,long now)throws Exception{
        original.requireOriginal();requirePrivateTask(task);
        if(!original.taskId().equals(task.optString("id"))||!original.runId().equals(task.optString("runId"))||original.epoch()!=task.optInt("coordinatorEpoch")
                ||!original.conversationId().equals(task.optString("conversationId"))||!"awaiting_user".equals(task.optString("status"))||task.optBoolean("cancelRequested"))throw new SecurityException("原等待任务已变化；回答已保存");
        JSONObject evidence=task.getJSONObject("evidence"),question=evidence.getJSONObject("ownerQuestion");
        JSONObject identity=unconsumedIdentity(task,question);
        if(identity==null||!original.requestId().equals(identity.optString("requestId"))||original.waitRevision()!=identity.optLong("waitRevision")||!Boolean.TRUE.equals(question.opt("replyConsumed"))
                ||checkpoint.optJSONArray("messages")==null||checkpoint.getJSONArray("messages").length()!=original.waitRevision())throw new SecurityException("原普通问题没有准确已保存答复或检查点已变化");
        for(String flag:new String[]{"extensionEndUnavailable","extensionCleanupPending","projectServiceDrainPending","projectServiceDrainEvidenceUnavailable"})if(evidence.has(flag)&&!Boolean.FALSE.equals(evidence.opt(flag)))throw new SecurityException("原执行清理状态尚未核对");
        if(!original.grantFingerprint().equals(TaskStore.capabilityFingerprint(grant))||original.expiresAt()<=now||grant.getLong("expiresAt")!=original.expiresAt())throw new SecurityException("原授权已到期或改变；回答不续权");
        JSONObject scope=task.optJSONObject("executionScope");long deadline=TaskExecutionScope.effectiveDeadline(scope);
        if(!original.taskId().equals(scope.getString("taskId"))||!"capabilities".equals(scope.getString("mode"))||deadline!=original.expiresAt()||grant.optLong("executionScopeRevision")!=scope.getLong("revision")
                ||scope.getInt("modelCallsUsed")>=scope.getInt("modelCallLimit")||scope.getInt("toolsUsed")>=scope.getInt("toolLimit")
                ||grant.getInt("remainingOperations")<1||integer(checkpoint.opt("steps"))<0||integer(checkpoint.opt("steps"))>=grant.getInt("stepCeiling"))throw new SecurityException("原任务剩余预算不足或范围换版；回答只保留");
        JSONArray rows=task.getJSONArray("ownerInputs");JSONObject selected=null;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),proof=row.optJSONObject("ownerQuestionReply");
            if(proof!=null&&FORMAT.equals(proof.optString("format"))&&original.requestId().equals(proof.optString("requestId"))&&original.runId().equals(row.optString("acceptedRunId"))&&original.epoch()==row.optInt("acceptedEpoch")){
                if(selected!=null||original.waitRevision()!=proof.optLong("waitRevision")||!"saved".equals(proof.optString("phase"))||!"pending".equals(row.optString("queueState"))
                        ||!row.getString("messageId").equals(question.optString("replyMessageId"))||!TaskStore.capabilityFingerprint(new JSONObject().put("text",row.getString("content"))).equals(proof.optString("textDigest")))throw new SecurityException("原回答已接手、撤回或改变，未重复执行");
                selected=row;
            }
        }
        if(selected==null)throw new SecurityException("原问题没有同一原生邮箱中的已保存答复");return selected;
    }
    private static JSONObject result(String task,String run,int epoch,String request,long revision,String message,boolean duplicate)throws Exception{
        return new JSONObject().put("accepted",true).put("answerSaved",true).put("duplicate",duplicate).put("taskId",task).put("runId",run).put("coordinatorEpoch",epoch).put("requestId",request).put("waitRevision",revision).put("messageId",message).put("continuationRequested",false).put("executionAuthorized",false);
    }
    /** Bounded native owner display only. This never carries a token, controller or grant. */
    public static JSONObject pageQuestion(JSONObject task)throws Exception{
        try{requirePrivateTask(task);}catch(SecurityException differentOrigin){return null;}
        if(!"awaiting_user".equals(task.optString("status")))return null;
        JSONObject evidence=task.optJSONObject("evidence"),question=evidence==null?null:evidence.optJSONObject("ownerQuestion");if(question==null)return null;
        JSONObject identity=unconsumedIdentity(task,question);
        if(identity==null)return null;JSONObject view=new JSONObject().put("identity",identity).put("question",question.getString("question")).put("answered",Boolean.TRUE.equals(question.opt("replyConsumed"))).put("conversationId",task.getString("conversationId")).put("executionAuthorized",false);
        if(question.has("options"))view.put("options",dev.pocketengine.runtime.policy.OwnerQuestionChoices.options(question));
        JSONObject state=task.optJSONObject("ownerQuestionReplyState");if(state!=null&&"pocketworld.owner-question-reply-state.v1".equals(state.optString("format"))&&task.getString("runId").equals(state.optString("runId"))&&task.getInt("coordinatorEpoch")==state.optInt("epoch")
                &&question.getString("requestId").equals(state.optString("requestId"))&&question.optString("replyMessageId").equals(state.optString("messageId")))view.put("continuationState",state.getString("state")).put("ownerAction",state.getString("ownerAction"));return view;
    }
    private static JSONObject unconsumedIdentity(JSONObject task,JSONObject question)throws Exception{
        JSONObject nativeQuestion=new JSONObject(question.toString());nativeQuestion.remove("replyConsumed");
        JSONObject value=new JSONObject().put("id",task.getString("id")).put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch"))
                .put("status",task.getString("status")).put("evidence",new JSONObject().put("ownerQuestion",nativeQuestion));
        for(String key:new String[]{"kind","executionMode","actorId","spaceId","conversationId","ownerInputMailbox","origin"})if(task.has(key))value.put(key,task.get(key));return OwnerQuestionReply.reference(value);
    }
    public static void requirePrivateTask(JSONObject task)throws Exception{
        if(task==null||!"assistant".equals(task.optString("kind"))||!"capabilities".equals(task.optString("executionMode"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.has("sourceLearningJob"))throw new SecurityException("问题答复不属于原主人私人能力任务");
        JSONObject origin=task.optJSONObject("origin");if(origin!=null&&!"owner_reviewed_realtime".equals(origin.optString("kind")))throw new SecurityException("设备、群、渠道或计划任务不能使用私人问题答复入口");
    }
    private static String text(String value){if(value==null||value.isBlank()||value.length()>4000||!StandardCharsets.UTF_8.newEncoder().canEncode(value))throw new IllegalArgumentException("请输入1至4000字的原问题回答");return value.strip();}
    private static long integer(Object value){return value instanceof Integer||value instanceof Long?((Number)value).longValue():-1;}
    private static void uuid(String value){try{if(!UUID.fromString(value).toString().equalsIgnoreCase(value))throw new IllegalArgumentException();}catch(Exception invalid){throw new SecurityException("原问题需要准确native UUID");}}
}
