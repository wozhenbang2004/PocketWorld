package dev.pocketengine.store;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Data contract of a durable owner task in the original TaskStore. This is neither
 * a scheduler nor a grant; every mutation is admitted by TaskStore's SQL transaction. */
public final class OwnerWorkTask {
    public static final String FORMAT = "pocketworld.owner-work.v1";
    public static final String ROLE = "owner_work";
    public static final String DELIVERY_FORMAT = "pocketworld.owner-work-delivery.v1";
    private OwnerWorkTask() { }

    public static boolean isOwnerWork(JSONObject task) {
        return task != null && ROLE.equals(task.optString("executionRole"))
                && task.optJSONObject("ownerWork") != null
                && FORMAT.equals(task.optJSONObject("ownerWork").optString("format"));
    }
    /** The native private front door only. A damaged work marker never promotes an
     * executor into the butler or gives it cross-conversation reading authority. */
    public static boolean isButlerSource(JSONObject source) {
        return source != null && "owner".equals(source.optString("actorId"))
                && "private".equals(source.optString("spaceId"))
                && "assistant".equals(source.optString("kind"))
                && "capabilities".equals(source.optString("executionMode"))
                && (!source.has("origin") || source.isNull("origin"))
                && (!source.has("executionRole") || source.opt("executionRole") instanceof String && source.optString("executionRole").isEmpty())
                && !source.has("ownerWork") && !source.has("parentTaskId") && !source.has("ownerDeviceWork")
                && !source.has("teamAssignment") && !source.has("sourceLearningJob")
                && !source.has("workProfileRef") && !source.has("workProfileIntent")
                && !source.has("projectMemoryRead") && !source.has("developmentContext")
                && !source.has("teamWriteScope") && !source.optString("conversationId").isBlank();
    }
    static JSONObject require(JSONObject task) throws Exception {
        if (!isOwnerWork(task) || !"assistant".equals(task.optString("kind"))
                || !"capabilities".equals(task.optString("executionMode"))
                || !"owner".equals(task.optString("actorId")) || !"private".equals(task.optString("spaceId"))
                || task.optJSONObject("origin") != null || task.has("parentTaskId")
                || !task.getString("id").equals(task.getJSONObject("ownerWork").getString("workTaskId"))
                || !task.getString("conversationId").equals(task.getJSONObject("ownerWork").getString("conversationId")))
            throw new SecurityException("不是原私人管家接纳的独立工作任务");
        return task.getJSONObject("ownerWork");
    }
    static JSONObject arguments(JSONObject supplied) throws Exception {
        keys(supplied, Set.of("goal", "title", "toolLimit", "modelCallLimit"));
        String goal = text(supplied, "goal", 12000), title = supplied.has("title") ? text(supplied, "title", 200) : goal.substring(0, Math.min(80, goal.length()));
        int tools = (int) TaskExecutionScope.exactLong(supplied, "toolLimit", 1, 400);
        int models = (int) TaskExecutionScope.exactLong(supplied, "modelCallLimit", 1, 512);
        return new JSONObject().put("goal", goal).put("title", title).put("toolLimit", tools).put("modelCallLimit", models);
    }
    static void keys(JSONObject object, Set<String> allowed) {
        if (object == null) throw new IllegalArgumentException("工作参数缺失");
        for (var it = object.keys(); it.hasNext();) if (!allowed.contains(it.next()))
            throw new SecurityException("工作参数不能携带替代身份、权限或正文来源");
    }
    static String text(JSONObject object, String key, int maximum) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).isBlank() || ((String) value).length() > maximum
                || ((String) value).indexOf('\0') >= 0) throw new IllegalArgumentException("工作参数无效：" + key);
        return (String) value;
    }
    static JSONArray messages(JSONObject task, JSONObject source, JSONArray refs) throws Exception {
        JSONArray messages = new JSONArray(), inputs = source.getJSONArray("ownerInputs");
        for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.getJSONObject(i), row = null;
            for (int j = 0; j < inputs.length(); j++) if (ref.getString("sourceId").equals(inputs.getJSONObject(j).optString("messageId"))) { row = inputs.getJSONObject(j); break; }
            if (row == null) throw new SecurityException("原工作来源不在已接收的主人输入中");
            JSONObject message = new JSONObject().put("role", "user").put("content", row.getString("content"))
                    .put("ownerMessageId", row.getString("messageId")).put("ownerMessageRevision", ref.getLong("revision"));
            if (row.optJSONArray("attachments") != null && row.getJSONArray("attachments").length() > 0)
                message.put("ownerAttachmentRefs", new JSONArray(row.getJSONArray("attachments").toString()));
            if (row.optJSONArray("ownerSourceRefs") != null) message.put("ownerSourceRefs", new JSONArray(row.getJSONArray("ownerSourceRefs").toString()));
            // Project element refs bind the original task. They cannot become a different task's edit capability.
            messages.put(message);
        }
        messages.put(new JSONObject().put("role", "user").put("content", "管家已接纳的本工作目标：\n" + task.getString("goal")
                + "\n前面的原主人消息是本目标的来源上下文；本任务应按自己的范围完成工作并返回可核验结果。"));
        return messages;
    }
}
