package dev.pocketengine.features.assistant;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply;
import dev.pocketengine.store.TaskExecutionScope;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original normal-phone hold only. This retains one physical owner; it owns no executor,
 * inbox, grant, attempt or conversation. Device-goal origins are categorically excluded. */
public final class OwnerPhoneQuestions {
    public static final String HOLD="pocketworld.phone-owner-question-hold.v1";
    public interface PhysicalOwner {
        void requireHeld(Held original)throws Exception;
        /** Posts to the original service main/worker. The callback rechecks the same Held. */
        void requestContinuation(Held original)throws Exception;
    }
    @FunctionalInterface public interface Dispatch { void run()throws Exception; }
    private Held held;
    public static final class Held {
        private final OwnerPhoneQuestions issuer;private final RunControl control;private final PhonePort physical;private final ModelConfig model;private final PhysicalOwner owner;
        private final String task,run,request,holdId,grantFingerprint;private final int epoch;private final long revision,pauseRevision,generation,expiresAt;
        private Held(OwnerPhoneQuestions issuer,JSONObject task,JSONObject question,JSONObject grant,RunControl original,PhonePort physical,ModelConfig model,long generation,PhysicalOwner owner)throws Exception{
            this.issuer=issuer;this.control=original;this.physical=physical;this.model=model;this.owner=owner;this.task=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");request=question.getString("requestId");revision=question.getLong("waitRevision");
            pauseRevision=original.pauseRevision();this.generation=generation;expiresAt=grant.getLong("expiresAt");grantFingerprint=TaskStore.capabilityFingerprint(grant);holdId=UUID.randomUUID().toString();
        }
        public String taskId(){return task;}public String runId(){return run;}public int epoch(){return epoch;}public String requestId(){return request;}public long waitRevision(){return revision;}
        public long pauseRevision(){return pauseRevision;}public long waitGeneration(){return generation;}public long expiresAt(){return expiresAt;}public String grantFingerprint(){return grantFingerprint;}
        public RunControl originalControl(){return control;}public PhonePort physicalPort(){return physical;}public ModelConfig capturedModel(){return model;}
        public JSONObject view()throws Exception{return new JSONObject().put("format",HOLD).put("taskId",task).put("runId",run).put("epoch",epoch).put("requestId",request).put("waitRevision",revision).put("pauseRevision",pauseRevision).put("waitGeneration",generation).put("holdId",holdId).put("executionAuthorized",false);}
        public void requireOriginal()throws Exception{if(!issuer.current(this)||control.isCancelled()||!control.isPaused()||control.pauseRevision()!=pauseRevision||!control.matchesHostRun(task,run,epoch))throw new SecurityException("原手机问题暂停控制已变化");owner.requireHeld(this);}
        public void requestContinuation()throws Exception{requireOriginal();owner.requestContinuation(this);}
        public boolean matches(String task,String run,int epoch,String request,long revision){return this.task.equals(task)&&this.run.equals(run)&&this.epoch==epoch&&this.request.equals(request)&&this.revision==revision;}
    }
    public synchronized Held retain(JSONObject task,JSONObject grant,RunControl original,PhonePort physical,ModelConfig model,long generation,PhysicalOwner owner)throws Exception{
        requirePhoneTask(task);JSONObject question=OwnerQuestionReply.identity(task);if(question==null||original==null||physical==null||model==null||owner==null||generation<1||original.isCancelled()||!original.isPaused()||!original.matchesHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch")))return null;
        JSONObject mailbox=task.optJSONObject("ownerInputMailbox");if(mailbox==null||!Boolean.FALSE.equals(mailbox.opt("open"))||!task.getString("runId").equals(mailbox.optString("runId"))||task.getInt("coordinatorEpoch")!=mailbox.optInt("epoch")||!mailbox.optString("conversationId").isEmpty())return null;
        JSONObject evidence=task.getJSONObject("evidence");for(String key:new String[]{"extensionEndUnavailable","extensionCleanupPending","projectServiceDrainPending","projectServiceDrainEvidenceUnavailable"})if(evidence.has(key)&&!Boolean.FALSE.equals(evidence.opt(key)))return null;
        Held value=new Held(this,task,question,grant,original,physical,model,generation,owner);held=value;try{value.requireOriginal();return value;}catch(Exception changed){held=null;throw changed;}
    }
    private synchronized boolean current(Held original){return held==original&&original.issuer==this;}
    public synchronized Held find(String task,String run,int epoch,String request,long revision){Held value=held;return value!=null&&value.matches(task,run,epoch,request,revision)?value:null;}
    public synchronized boolean take(Held original){if(!current(original))return false;held=null;return true;}
    public synchronized void released(String task,RunControl original){if(held!=null&&held.taskId().equals(task)&&held.originalControl()==original)held=null;}
    public static void requirePhoneTask(JSONObject task){
        if(task==null||!"phone".equals(task.optString("kind"))||!task.optString("executionMode").matches("main_screen|background")||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||task.has("sourceLearningJob"))throw new SecurityException("不是主人本机原手机问题；设备、渠道与计划任务不使用此入口");
    }
    public static JSONObject reference(JSONObject task)throws Exception{
        try{requirePhoneTask(task);}catch(SecurityException other){return null;}if(task.optBoolean("cancelRequested"))return null;JSONObject question=OwnerQuestionReply.identity(task),hold=task.optJSONObject("ownerPhoneQuestionHold"),mailbox=task.optJSONObject("ownerInputMailbox");
        if(question==null||hold==null||!HOLD.equals(hold.optString("format"))||!task.getString("id").equals(hold.optString("taskId"))||!task.getString("runId").equals(hold.optString("runId"))||task.getInt("coordinatorEpoch")!=hold.optInt("epoch")||!question.getString("requestId").equals(hold.optString("requestId"))||question.getLong("waitRevision")!=hold.optLong("waitRevision")
                ||hold.optLong("pauseRevision")<1||hold.optLong("waitGeneration")<1||!hold.optString("holdId").matches("[a-f0-9-]{36}")||mailbox==null||!Boolean.FALSE.equals(mailbox.opt("open"))||!task.getString("runId").equals(mailbox.optString("runId"))||task.getInt("coordinatorEpoch")!=mailbox.optInt("epoch")||!mailbox.optString("conversationId").isEmpty())return null;return question;
    }
    public static JSONObject noticeView(JSONObject task)throws Exception{
        JSONObject question=reference(task);if(question==null)return null;JSONObject hold=task.getJSONObject("ownerPhoneQuestionHold");
        JSONObject view=new JSONObject().put("noticeKind","phone_hold").put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("epoch",task.getInt("coordinatorEpoch")).put("status","awaiting_user").put("holdId",hold.getString("holdId")).put("ownerQuestion",question);
        return view.put("token",TaskStore.capabilityFingerprint(view)).put("executionAuthorized",false);
    }
    public static final class Reply {
        private final String row,result;private Reply(JSONObject row,JSONObject result){this.row=row.toString();this.result=result.toString();}
        public JSONObject row()throws Exception{return new JSONObject(row);}public JSONObject result()throws Exception{return new JSONObject(result);}public boolean duplicate()throws Exception{return result().getBoolean("duplicate");}
    }
    public static Reply reply(JSONObject task,String id,String run,int epoch,String request,long revision,String token,String supplied,long now)throws Exception{
        requirePhoneTask(task);uuid(id);uuid(run);uuid(request);if(!id.equals(task.getString("id"))||epoch<1||revision<1||token==null||!token.matches("[a-f0-9]{64}"))throw new SecurityException("原手机问题定位无效");
        if(supplied==null||supplied.isBlank()||supplied.length()>4000||!StandardCharsets.UTF_8.newEncoder().canEncode(supplied))throw new IllegalArgumentException("请输入1至4000字的原手机问题回答");String text=supplied.strip(),digest=TaskStore.capabilityFingerprint(new JSONObject().put("text",text));JSONArray rows=task.optJSONArray("ownerInputs");
        for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i),proof=row.optJSONObject("ownerQuestionReply");if(proof!=null&&OwnerQuestionInputs.FORMAT.equals(proof.optString("format"))&&"native_phone_owner_input".equals(proof.optString("sourceKind"))&&request.equals(proof.optString("requestId"))&&run.equals(row.optString("acceptedRunId"))&&epoch==row.optInt("acceptedEpoch")){
            if(revision!=proof.optLong("waitRevision")||!token.equals(proof.optString("noticeToken"))||!digest.equals(proof.optString("textDigest")))throw new SecurityException("原手机问题答复不能改写身份或内容");return new Reply(row,result(id,run,epoch,request,revision,row.getString("messageId"),true));}}
        JSONObject question=reference(task),view=noticeView(task),notice=task.optJSONObject("ownerPhoneQuestionNotice");if(question==null||view==null||notice==null||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||!request.equals(question.optString("requestId"))||revision!=question.optLong("waitRevision")||!token.equals(view.optString("token"))||!token.equals(notice.optString("token"))||!"available".equals(notice.optString("phase")))throw new SecurityException("原手机问题或持有会话已变化");
        long chars=text.length();for(int i=0;rows!=null&&i<rows.length();i++)chars+=rows.getJSONObject(i).optString("content").length();if(rows!=null&&rows.length()>=64||chars>240000)throw new IllegalStateException("原手机输入留存已满；没有新建任务");
        String message=UUID.randomUUID().toString(),client=UUID.randomUUID().toString();JSONObject proof=new JSONObject().put("format",OwnerQuestionInputs.FORMAT).put("sourceKind","native_phone_owner_input").put("requestId",request).put("waitRevision",revision).put("noticeToken",token).put("textDigest",digest).put("phase","saved").put("savedAt",now);
        JSONObject payload=new JSONObject().put("content",text).put("mode","steer").put("attachments",new JSONArray());JSONObject row=new JSONObject(payload.toString()).put("messageId",message).put("clientMessageId",client).put("payloadSha256",TaskStore.capabilityFingerprint(payload)).put("acceptedTaskId",id).put("acceptedRunId",run).put("acceptedEpoch",epoch).put("acceptedAt",now).put("ownerQuestionReply",proof);
        return new Reply(row,result(id,run,epoch,request,revision,message,false));
    }
    private static JSONObject result(String task,String run,int epoch,String request,long revision,String message,boolean duplicate)throws Exception{return new JSONObject().put("accepted",true).put("answerSaved",true).put("duplicate",duplicate).put("taskId",task).put("runId",run).put("coordinatorEpoch",epoch).put("requestId",request).put("waitRevision",revision).put("messageId",message).put("sourceKind","native_phone_owner_input").put("formalSourceRefAvailable",false).put("continuationRequested",false).put("executionAuthorized",false);}
    public static JSONObject requireSavedContinuation(Held held,JSONObject task,JSONObject grant,JSONObject checkpoint,long now)throws Exception{
        held.requireOriginal();requirePhoneTask(task);JSONObject question=task.getJSONObject("evidence").getJSONObject("ownerQuestion");
        JSONObject q=new JSONObject(question.toString());q.remove("replyConsumed");JSONObject bound=new JSONObject().put("id",task.getString("id")).put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch")).put("status",task.getString("status")).put("evidence",new JSONObject().put("ownerQuestion",q));
        if(OwnerQuestionReply.identity(bound)==null)throw new SecurityException("原手机等待不再是普通问题");
        JSONObject evidence=task.getJSONObject("evidence");for(String key:new String[]{"extensionEndUnavailable","extensionCleanupPending","projectServiceDrainPending","projectServiceDrainEvidenceUnavailable"})if(evidence.has(key)&&!Boolean.FALSE.equals(evidence.opt(key)))throw new SecurityException("原手机扩展清理尚未核对");
        if(!held.taskId().equals(task.optString("id"))||!held.runId().equals(task.optString("runId"))||held.epoch()!=task.optInt("coordinatorEpoch")||!"awaiting_user".equals(task.optString("status"))||task.optBoolean("cancelRequested")||!Boolean.TRUE.equals(question.opt("replyConsumed"))||!held.requestId().equals(question.optString("requestId"))||held.waitRevision()!=question.optLong("waitRevision")||checkpoint.optJSONArray("messages")==null||checkpoint.getJSONArray("messages").length()!=held.waitRevision())throw new SecurityException("原手机问题或检查点已变化");
        JSONObject hold=task.getJSONObject("ownerPhoneQuestionHold");if(!TaskStore.capabilityFingerprint(held.view()).equals(TaskStore.capabilityFingerprint(hold))||!held.grantFingerprint().equals(TaskStore.capabilityFingerprint(grant))||held.expiresAt()<=now)throw new SecurityException("原手机授权、物理持有或预算已变化；回答不续权");
        JSONObject scope=task.getJSONObject("executionScope");if(TaskExecutionScope.effectiveDeadline(scope)!=held.expiresAt()||!held.taskId().equals(scope.getString("taskId"))||!task.getString("executionMode").equals(scope.getString("mode"))||scope.getLong("revision")!=grant.optLong("executionScopeRevision")||scope.getInt("modelCallsUsed")>=scope.getInt("modelCallLimit")||scope.getInt("toolsUsed")>=scope.getInt("toolLimit")||grant.getInt("remainingOperations")<1||integer(checkpoint.opt("steps"))<0||integer(checkpoint.opt("steps"))>=grant.getInt("stepCeiling"))throw new SecurityException("原手机剩余预算不足或授权已到期");
        JSONObject notice=task.getJSONObject("ownerPhoneQuestionNotice");
        if(!"answered".equals(notice.optString("phase"))||!notice.optString("token").matches("[a-f0-9]{64}"))throw new SecurityException("原手机问题通知回执已改变");
        JSONArray rows=task.getJSONArray("ownerInputs");JSONObject found=null;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),proof=row.optJSONObject("ownerQuestionReply");if(proof!=null&&held.requestId().equals(proof.optString("requestId"))&&held.runId().equals(row.optString("acceptedRunId"))&&held.epoch()==row.optInt("acceptedEpoch")){
            if(found!=null||held.waitRevision()!=integer(proof.opt("waitRevision"))||!notice.getString("token").equals(proof.optString("noticeToken"))||!held.taskId().equals(row.optString("acceptedTaskId"))
                    ||!"steer".equals(row.optString("mode"))||row.optJSONArray("attachments")==null||row.getJSONArray("attachments").length()!=0||row.has("queueState")&&!"pending".equals(row.optString("queueState"))
                    ||!TaskStore.capabilityFingerprint(new JSONObject().put("content",row.getString("content")).put("mode","steer").put("attachments",new JSONArray())).equals(row.optString("payloadSha256"))
                    ||!OwnerQuestionInputs.FORMAT.equals(proof.optString("format"))||!"native_phone_owner_input".equals(proof.optString("sourceKind"))||!"saved".equals(proof.optString("phase"))||!row.getString("messageId").equals(question.optString("replyMessageId"))||!TaskStore.capabilityFingerprint(new JSONObject().put("text",row.getString("content"))).equals(proof.optString("textDigest")))throw new SecurityException("原手机回答已改变或被接手");found=row;}}
        if(found==null)throw new SecurityException("原手机邮箱没有准确已保存回答");return found;
    }
    public static JSONObject pageQuestion(JSONObject task)throws Exception{
        try{requirePhoneTask(task);}catch(SecurityException other){return null;}if(!"awaiting_user".equals(task.optString("status")))return null;JSONObject evidence=task.optJSONObject("evidence"),question=evidence==null?null:evidence.optJSONObject("ownerQuestion");if(question==null||task.optJSONObject("ownerPhoneQuestionHold")==null||!Boolean.TRUE.equals(question.opt("replyConsumed"))&&reference(task)==null)return null;
        JSONObject copy=new JSONObject().put("id",task.getString("id")).put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch")).put("status","awaiting_user");JSONObject q=new JSONObject(question.toString());q.remove("replyConsumed");copy.put("evidence",new JSONObject().put("ownerQuestion",q));JSONObject identity=OwnerQuestionReply.identity(copy);if(identity==null)return null;
        JSONObject view=new JSONObject().put("identity",identity).put("question",question.getString("question")).put("answered",Boolean.TRUE.equals(question.opt("replyConsumed"))).put("sourceKind","native_phone_owner_input").put("formalSourceRefAvailable",false).put("executionAuthorized",false);
        if(question.has("options"))view.put("options",dev.pocketengine.runtime.policy.OwnerQuestionChoices.options(question));
        JSONArray rows=task.optJSONArray("ownerInputs");for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optString("messageId").equals(question.optString("replyMessageId"))&&row.optJSONObject("ownerQuestionReply")!=null&&question.getString("requestId").equals(row.getJSONObject("ownerQuestionReply").optString("requestId")))view.put("answerText",row.getString("content"));}
        JSONObject state=task.optJSONObject("ownerQuestionReplyState");if(state!=null&&task.getString("runId").equals(state.optString("runId"))&&task.getInt("coordinatorEpoch")==state.optInt("epoch")&&question.getString("requestId").equals(state.optString("requestId"))&&question.optString("replyMessageId").equals(state.optString("messageId")))view.put("continuationState",state.optString("state")).put("ownerAction",state.optString("ownerAction"));return view;
    }
    private static long integer(Object value){if(!(value instanceof Integer||value instanceof Long))throw new SecurityException("原手机检查点计数不可核对");return ((Number)value).longValue();}
    private static void uuid(String value){try{if(!UUID.fromString(value).toString().equalsIgnoreCase(value))throw new IllegalArgumentException();}catch(Exception bad){throw new SecurityException("原手机问题需要准确native UUID");}}
}
