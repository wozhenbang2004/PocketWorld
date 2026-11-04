package dev.pocketengine.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable provenance and result values on the original TaskStore, never execution authority. */
final class ConversationPhoneHandoff {
    static final String REVIEW="pocketworld.conversation-phone-review.v1";
    static final String LINK="pocketworld.conversation-phone-handoff.v1";
    static final String DELIVERY="pocketworld.conversation-phone-delivery.v1";
    static final String RESULT_PREFIX="conversation_phone_result:";
    static final String PENDING_PREFIX="conversation_phone_pending:";
    private static final Set<String> ENDED=Set.of("completed","needs_confirmation","awaiting_user","failed","cancelled","interrupted","reconciling","paused");
    private static final int MAX_CONTENT_CHARS=24000;
    private ConversationPhoneHandoff() { }

    static JSONObject review(String requestId,JSONObject source,String goal,String mode,JSONObject facts)throws Exception {
        uuid(requestId);fields(source,Set.of("format","generation","conversationId","sourceMessageId","sourceMessageRevision","bodyFingerprint","sourceSequence","conversationTitle"));
        if(!"pocketworld.phone-handoff-source.v1".equals(source.optString("format")))throw new SecurityException("缺少原会话手机任务来源");
        nativeId(source.getString("generation"));nativeId(source.getString("conversationId"));nativeId(source.getString("sourceMessageId"));
        TaskExecutionScope.exactLong(source,"sourceMessageRevision",1,Long.MAX_VALUE);
        TaskExecutionScope.exactLong(source,"sourceSequence",1,Long.MAX_VALUE);
        if(!source.getString("bodyFingerprint").matches("sha256:[a-f0-9]{64}"))throw new SecurityException("原消息指纹无效");
        if(source.has("conversationTitle"))text(source,"conversationTitle",0,200);
        if(goal==null||goal.trim().isEmpty()||goal.trim().length()>4000||!StandardCharsets.UTF_8.newEncoder().canEncode(goal))throw new IllegalArgumentException("手机任务目标需要1–4000字完整文字");
        if(!Set.of("main_screen","background").contains(mode))throw new IllegalArgumentException("请选择原手机执行位置");
        fields(facts,Set.of("modelName","provider","toolLimit","modelCallLimit","scopeMinutes","modelSelectionRevision"));
        text(facts,"modelName",1,2000);text(facts,"provider",1,100);
        TaskExecutionScope.exactInt(facts,"toolLimit",1,TaskExecutionScope.MAX_TOOLS);
        TaskExecutionScope.exactInt(facts,"modelCallLimit",1,TaskExecutionScope.MAX_MODEL_CALLS);
        int minutes=TaskExecutionScope.exactInt(facts,"scopeMinutes",0,120);
        if(minutes!=0&&minutes!=30&&minutes!=120)throw new IllegalArgumentException("任务期限只能使用本次审阅选项");
        TaskExecutionScope.exactLong(facts,"modelSelectionRevision",0,Long.MAX_VALUE);
        JSONObject result=new JSONObject().put("format",REVIEW).put("requestId",requestId).put("taskId",requestId)
                .put("conversationId",source.getString("conversationId")).put("source",new JSONObject(source.toString()))
                .put("goal",goal.trim()).put("executionMode",mode).put("reviewFacts",new JSONObject(facts.toString()));
        return result.put("reviewDigest",digest(result));
    }

    static JSONObject link(JSONObject review,long createdAt)throws Exception {
        if(!REVIEW.equals(review.optString("format"))||createdAt<1)throw new SecurityException("缺少原生手机任务审阅");
        JSONObject result=new JSONObject(review.toString());result.put("format",LINK).put("createdAt",createdAt);return result;
    }

    static JSONObject association(JSONObject task)throws Exception {
        JSONObject link=task.optJSONObject("conversationPhoneHandoff");
        if(link==null||!LINK.equals(link.optString("format"))||!"phone".equals(task.optString("kind"))
                ||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.has("origin")||task.has("parentTaskId"))
            throw new SecurityException("不是原私人对话关联的独立手机任务");
        uuid(task.getString("id"));
        if(!task.getString("id").equals(link.getString("requestId"))||!task.getString("id").equals(link.getString("taskId"))
                ||!task.getString("goal").equals(link.getString("goal"))
                ||task.getLong("createdAt")!=link.getLong("createdAt"))throw new SecurityException("原手机创建身份已改变");
        // The original reviewed mode is fixed in the digest; an owner may later resume this task in another phone mode.
        JSONObject original=new JSONObject(link.toString());original.remove("createdAt");original.remove("reviewDigest");original.put("format",REVIEW);
        if(!digest(original).equals(link.getString("reviewDigest")))throw new SecurityException("原手机创建审阅不可核对");
        return new JSONObject(link.toString());
    }

    static void requireSameReview(JSONObject task,JSONObject review)throws Exception {
        JSONObject original=association(task);
        if(!original.getString("reviewDigest").equals(review.getString("reviewDigest"))
                ||!original.getString("requestId").equals(review.getString("requestId")))throw new SecurityException("同一个手机请求不能更换审阅范围");
    }

    static JSONObject creation(JSONObject task,boolean duplicate)throws Exception {
        JSONObject link=association(task);
        return new JSONObject().put("accepted",true).put("created",!duplicate).put("duplicate",duplicate)
                .put("admitted",!task.optString("runId").isEmpty()&&task.optInt("coordinatorEpoch")>0)
                .put("taskId",task.getString("id")).put("requestId",link.getString("requestId"))
                .put("conversationId",link.getString("conversationId")).put("createdAt",task.getLong("createdAt"))
                .put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch"))
                .put("coordinatorEpoch",task.optInt("coordinatorEpoch")).put("status",task.getString("status"));
    }

    static boolean ended(String status){return ENDED.contains(status);}

    static JSONObject result(JSONObject task,String run,int epoch,String status,long endedAt)throws Exception {
        JSONObject link=association(task);uuid(run);
        if(epoch<1||endedAt<1||!ended(status)||!run.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("原手机执行尚无确切结束结果");
        String summary=task.optString("summary");
        JSONObject evidence=task.optJSONObject("evidence");
        JSONObject reference=new JSONObject().put("taskId",task.getString("id")).put("runId",run).put("epoch",epoch)
                .put("resultRevision",task.optLong("resultRevision")).put("evidenceDigest",digest(evidence==null?new JSONObject():evidence));
        JSONObject result=new JSONObject().put("format",DELIVERY).put("requestId",task.getString("id")).put("taskId",task.getString("id"))
                .put("runId",run).put("epoch",epoch).put("deliveryId",deliveryId(task.getString("id"),run,epoch))
                .put("generation",link.getJSONObject("source").getString("generation")).put("conversationId",link.getString("conversationId"))
                .put("status",status).put("content",summaryContent(summary)).put("summaryTruncated",summary.length()>MAX_CONTENT_CHARS)
                .put("summaryChars",summary.length()).put("summaryDigest",summaryDigest(summary)).put("evidence",reference).put("endedAt",endedAt)
                .put("executionAuthorized",false).put("resultVerified",false);
        return result.put("resultDigest",digest(result));
    }

    static void requireResult(JSONObject result,String task,String run,int epoch)throws Exception {
        if(!DELIVERY.equals(result.optString("format"))||!task.equals(result.optString("taskId"))||!task.equals(result.optString("requestId"))
                ||!run.equals(result.optString("runId"))||epoch!=result.optInt("epoch")||!deliveryId(task,run,epoch).equals(result.optString("deliveryId"))
                ||!ended(result.optString("status"))||!Boolean.FALSE.equals(result.opt("executionAuthorized"))||!Boolean.FALSE.equals(result.opt("resultVerified")))
            throw new SecurityException("原手机结果身份已改变");
        JSONObject facts=new JSONObject(result.toString());facts.remove("resultDigest");
        if(!digest(facts).equals(result.getString("resultDigest")))throw new SecurityException("原手机结果指纹已改变");
    }

    static void requireOriginalSummary(JSONObject result,String summary)throws Exception {
        if(summary==null||!summaryDigest(summary).equals(result.getString("summaryDigest"))||summary.length()!=result.getInt("summaryChars")
                ||(summary.length()>MAX_CONTENT_CHARS)!=result.getBoolean("summaryTruncated")||!summaryContent(summary).equals(result.getString("content")))throw new SecurityException("原手机结果正文与冻结指纹不一致");
    }
    private static String summaryContent(String summary){
        if(summary.isBlank())return "本轮执行已结束，未提供文字总结；请查看原手机任务。";
        if(summary.length()<=MAX_CONTENT_CHARS)return summary;
        String notice="\n\n（结果较长；完整结果请查看原手机任务。）";int end=MAX_CONTENT_CHARS-notice.length();
        if(Character.isHighSurrogate(summary.charAt(end-1)))end--;
        return summary.substring(0,end)+notice;
    }
    private static String summaryDigest(String summary)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(summary.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}

    static String deliveryId(String task,String run,int epoch){uuid(task);uuid(run);if(epoch<1)throw new IllegalArgumentException("原执行代次无效");return "phone_handoff:"+task+":"+run+":"+epoch;}
    static String resultKey(String task,String run,int epoch){deliveryId(task,run,epoch);return RESULT_PREFIX+task+":"+run+":"+epoch;}
    static String pendingKey(JSONObject result)throws Exception {return PENDING_PREFIX+String.format(java.util.Locale.ROOT,"%020d",result.getLong("endedAt"))+":"+result.getString("taskId")+":"+String.format(java.util.Locale.ROOT,"%010d",result.getInt("epoch"))+":"+result.getString("runId");}
    static void cursor(String value){if(value==null||!value.isEmpty()&&!value.matches(PENDING_PREFIX+"[0-9]{20}:[a-f0-9-]{36}:[0-9]{10}:[a-f0-9-]{36}"))throw new IllegalArgumentException("原手机结果分页位置无效");}
    private static void uuid(String value){if(value==null||!value.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原手机关联身份无效");}
    static void nativeId(String value){if(value==null||value.isBlank()||value.length()>240||!StandardCharsets.UTF_8.newEncoder().canEncode(value))throw new IllegalArgumentException("原会话来源身份无效");}
    private static void fields(JSONObject value,Set<String> allowed){if(value==null||!allowed.containsAll(keys(value)))throw new SecurityException("原手机审阅包含未允许字段");}
    private static ArrayList<String> keys(JSONObject value){ArrayList<String> result=new ArrayList<>();value.keys().forEachRemaining(result::add);return result;}
    private static void text(JSONObject value,String field,int minimum,int maximum)throws Exception {Object raw=value.get(field);if(!(raw instanceof String)||((String)raw).length()<minimum||((String)raw).length()>maximum||!StandardCharsets.UTF_8.newEncoder().canEncode((String)raw))throw new IllegalArgumentException("原手机审阅文字无效");}
    static String digest(JSONObject value)throws Exception {StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)))result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject object){ArrayList<String> keys=keys(object);Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray array){StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
}
