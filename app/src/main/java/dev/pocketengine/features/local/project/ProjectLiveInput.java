package dev.pocketengine.features.local.project;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** One project-page feedback send to the existing assistant mailbox, not a second composer or grant. */
public final class ProjectLiveInput {
    private ProjectLiveInput(){}
    public static JSONObject prepare(JSONObject task,String checkout,String text)throws Exception {
        if(!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("origin")!=null||!task.optString("status").matches("preparing|running")||!checkout.equals(task.getJSONObject("developmentContext").getString("checkoutId")))throw new SecurityException("请选择本机原活动项目任务，不能把补充发往其他任务");
        String message=text==null?"":text.trim();if(message.isBlank()||message.length()>8000||message.indexOf('\0')>=0)throw new IllegalArgumentException("项目补充须为1至8000字明确内容");
        JSONObject target=new JSONObject().put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("epoch",task.getInt("coordinatorEpoch")).put("conversationId",task.getString("conversationId"));
        for(String key:new String[]{"taskId","runId","conversationId"})ProjectBuildPlan.identity(target.getString(key));if(target.getInt("epoch")<1)throw new SecurityException("原执行代次无效");
        JSONObject record=new JSONObject().put("format","pocketworld.project-live-input.v1").put("checkoutId",ProjectBuildPlan.identity(checkout)).put("clientMessageId",UUID.randomUUID().toString()).put("target",target).put("message",message).put("createdAt",System.currentTimeMillis());record.put("identity",ProjectSources.digest(record));return record;
    }
    public static JSONObject verified(JSONObject original)throws Exception {
        JSONObject record=ProjectSources.copy(original);String digest=record.getString("identity");record.remove("identity");if(!"pocketworld.project-live-input.v1".equals(record.optString("format"))||!digest.equals(ProjectSources.digest(record))||record.toString().length()>50000)throw new SecurityException("原项目补充身份已变化，未重发");
        ProjectBuildPlan.identity(record.getString("checkoutId"));ProjectBuildPlan.identity(record.getString("clientMessageId"));JSONObject target=record.getJSONObject("target");for(String key:new String[]{"taskId","runId","conversationId"})ProjectBuildPlan.identity(target.getString(key));if(target.getInt("epoch")<1||record.getString("message").isBlank()||record.getString("message").length()>8000)throw new SecurityException("原项目补充范围无效");record.put("identity",digest);return record;
    }
    public static JSONObject payload(JSONObject original)throws Exception {
        JSONObject record=verified(original),input=ProjectSources.copy(record.getJSONObject("target"));return input.put("clientMessageId",record.getString("clientMessageId")).put("message",record.getString("message")).put("mode","steer").put("attachments",new JSONArray());
    }
    public static boolean acknowledged(JSONObject original,JSONObject receipt)throws Exception {
        JSONObject record=verified(original),target=record.getJSONObject("target");if(receipt==null||!receipt.optString("status").matches("accepted|queued|consumed")||!record.getString("clientMessageId").equals(receipt.optString("clientMessageId")))return false;
        for(String key:new String[]{"taskId","runId","conversationId"})if(!target.getString(key).equals(receipt.optString(key)))return false;return receipt.opt("epoch") instanceof Number&&target.getInt("epoch")==receipt.getInt("epoch");
    }
}
