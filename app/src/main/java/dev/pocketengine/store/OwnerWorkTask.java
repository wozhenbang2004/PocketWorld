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
    public static final String TAKEOVER_FORMAT = "pocketworld.owner-work-takeover.v1";
    public static final String RESULT_HANDLING_FORMAT = "pocketworld.owner-result-handling.v1";
    public static final String CONTRACT_FORMAT = "pocketworld.owner-work-contract.v1";
    public static final String CONTEXT_FORMAT = "pocketworld.owner-work-context.v1";
    static final int CONTEXT_INPUT_LIMIT = 128;
    static final int CONTEXT_PAGE_INPUT_LIMIT = 8;
    public static final int TAKEOVER_TOOL_LIMIT = 3;
    public static final int TAKEOVER_MODEL_LIMIT = 2;
    /** Removal-only: these capabilities may fill evidence gaps, never repeat the
     * original phone/external action or admit another task. Native leaf gates remain. */
    public static final Set<String> RESULT_TOOLS = Set.of("owner_work_get", "workspace_artifacts",
            "workspace_read_artifact", "workspace_diff_artifact", "workspace_create_artifact",
            "attachments_read_text", "attachments_create_text", "attachments_create_document",
            "web_search", "web_fetch", "web_research", "web_sources");
    private OwnerWorkTask() { }

    /** A receipt identity is useful only when matched against the host's native
     * read selection. The digest also binds the exact observed progress and page. */
    public static JSONObject contextReadReference(JSONObject context) throws Exception {
        if (context == null || !CONTEXT_FORMAT.equals(context.optString("format"))
                || context.opt("available") != Boolean.TRUE
                || !context.optString("contextRevision").matches("[a-f0-9]{64}")
                || !context.optString("contextDigest").matches("[a-f0-9]{64}")
                || !context.optString("taskId").matches("[a-fA-F0-9-]{36}")
                || !context.optString("conversationId").matches("[a-fA-F0-9-]{36}")
                || !context.optString("runId").isEmpty()&&!context.optString("runId").matches("[a-fA-F0-9-]{36}")
                || context.has("sourceContextDigest")&&!context.optString("sourceContextDigest").matches("[a-f0-9]{64}"))
            throw new SecurityException("原工作上下文回执身份不完整");
        TaskExecutionScope.exactLong(context,"epoch",0,Integer.MAX_VALUE);
        JSONObject body = new JSONObject(context.toString());
        body.remove("contextDigest");
        if (!TaskStore.capabilityFingerprint(body).equals(context.getString("contextDigest")))
            throw new SecurityException("原工作上下文正文或观察快照已变化");
        JSONObject reference = new JSONObject();
        for (String key : new String[]{"taskId", "conversationId", "runId", "epoch", "contextRevision", "contextDigest"})
            reference.put(key, context.get(key));
        return reference;
    }

    /** Preserve only verified native locator fields across text cleaning. The visible body
     * receives its own digest and keeps the original digest as a link; neither is authority
     * until the host matches it to the read it actually performed in this run. */
    public static JSONObject restoreSafeContextReference(JSONObject original,JSONObject cleaned)throws Exception {
        JSONObject reference=contextReadReference(original);
        if(original.has("sourceContextDigest")||cleaned==null)throw new SecurityException("需要本次原生工作上下文，不能再次认证派生回执");
        JSONObject visible=new JSONObject(cleaned.toString());
        for(String field:new String[]{"taskId","conversationId","runId","epoch","contextRevision"})visible.put(field,reference.get(field));
        visible.put("format",CONTEXT_FORMAT).put("available",true).remove("contextDigest");visible.remove("sourceContextDigest");
        // A cursor is a bounded locator, not source prose. Preserve it only after checking
        // that it names this exact native source revision and an actual input position.
        String cursor=original.getString("nextCursor");
        int count=(int)TaskExecutionScope.exactLong(original,"inputCount",0,CONTEXT_INPUT_LIMIT);
        if(!cursor.isEmpty()){if(count==0)throw new SecurityException("空工作输入不能携带续读游标");contextPosition(cursor,reference,count);}
        visible.put("nextCursor",cursor);
        String sourceDigest=reference.getString("contextDigest");
        if(!sourceDigest.equals(TaskStore.capabilityFingerprint(visible)))visible.put("sourceContextDigest",sourceDigest);
        visible.put("contextDigest",TaskStore.capabilityFingerprint(visible));contextReadReference(visible);return visible;
    }

    /** Stateless source-version cursor. It carries a locator, never a grant. */
    static JSONObject contextPosition(String cursor, JSONObject identity, int count) throws Exception {
        if (cursor == null || cursor.length() > 2048) throw new IllegalArgumentException("工作上下文游标无效");
        if (cursor.isEmpty()) return new JSONObject().put("inputIndex", 0).put("charOffset", 0);
        JSONObject value;
        try {
            value = new JSONObject(new String(android.util.Base64.decode(cursor,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception invalid) { throw new IllegalArgumentException("工作上下文游标不可读取"); }
        keys(value, Set.of("format", "taskId", "conversationId", "runId", "epoch", "contextRevision", "inputIndex", "charOffset"));
        if (!CONTEXT_FORMAT.equals(value.optString("format"))) throw new IllegalArgumentException("工作上下文游标版本无效");
        for (String key : new String[]{"taskId", "conversationId", "runId", "contextRevision"})
            if (!identity.getString(key).equals(value.optString(key)))
                throw new IllegalStateException("原工作或主人输入已变化，请去掉contextCursor重新读取");
        if (TaskExecutionScope.exactLong(value, "epoch", 0, Integer.MAX_VALUE) != identity.getInt("epoch"))
            throw new IllegalStateException("原工作执行代次已变化，请去掉contextCursor重新读取");
        TaskExecutionScope.exactLong(value, "inputIndex", 0, Math.max(0, count - 1));
        TaskExecutionScope.exactLong(value, "charOffset", 0, Integer.MAX_VALUE);
        return value;
    }

    static String contextCursor(JSONObject identity, int index, int offset) throws Exception {
        JSONObject value = new JSONObject().put("format", CONTEXT_FORMAT);
        for (String key : new String[]{"taskId", "conversationId", "runId", "epoch", "contextRevision"})
            value.put(key, identity.get(key));
        value.put("inputIndex", index).put("charOffset", offset);
        return android.util.Base64.encodeToString(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

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
        keys(supplied, Set.of("goal", "title", "toolLimit", "modelCallLimit", "constraints", "deliverable", "completionCriteria"));
        String goal = text(supplied, "goal", 12000), title = supplied.has("title") ? text(supplied, "title", 200) : goal.substring(0, Math.min(80, goal.length()));
        int tools = (int) TaskExecutionScope.exactLong(supplied, "toolLimit", 1, 400);
        int models = (int) TaskExecutionScope.exactLong(supplied, "modelCallLimit", 1, 512);
        JSONObject value = new JSONObject().put("goal", goal).put("title", title).put("toolLimit", tools).put("modelCallLimit", models);
        for (String key : new String[]{"constraints", "deliverable", "completionCriteria"})
            if (supplied.has(key)) value.put(key, text(supplied, key, 4000));
        return value;
    }
    static JSONObject preparationArguments(JSONObject supplied) throws Exception {
        keys(supplied, Set.of("kind", "goal", "constraints", "deliverable", "completionCriteria", "projectId"));
        String kind = text(supplied, "kind", 16);
        if (!Set.of("phone", "project").contains(kind)) throw new IllegalArgumentException("准备提案只能选择手机或项目");
        JSONObject value = new JSONObject().put("kind", kind).put("goal", text(supplied, "goal", kind.equals("phone") ? 4000 : 12000));
        for (String key : new String[]{"constraints", "deliverable", "completionCriteria"})
            if (supplied.has(key)) value.put(key, text(supplied, key, 4000));
        if (supplied.has("projectId")) {
            if (!kind.equals("project")) throw new IllegalArgumentException("手机提案不能附带项目选择");
            value.put("projectId", text(supplied, "projectId", 240));
        }
        return value;
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
    static JSONObject contract(JSONObject arguments) throws Exception {
        JSONObject contract = new JSONObject().put("format", CONTRACT_FORMAT).put("revision", 1)
                .put("goal", arguments.getString("goal"));
        for (String field : new String[]{"constraints", "deliverable", "completionCriteria"})
            if (arguments.has(field)) contract.put(field, arguments.getString(field));
        return contract;
    }
    /** Conservative planning estimate from actual text. Physical request admission
     * still uses InferenceTokenReservation and the original cumulative budget. */
    public static long reportTokenReserve(JSONObject contract, long sourceBytes, long resultBytes, long outputTokens) throws Exception {
        if (sourceBytes < 0 || resultBytes < 0 || outputTokens < 1) throw new IllegalArgumentException("结果整理估计无效");
        long text = Math.addExact(contract.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length, sourceBytes);
        // A compact native report surface plus a bounded result excerpt; later pages
        // are charged at their actual request size, never treated as free context.
        long first = Math.addExact(Math.addExact(text, Math.min(resultBytes, 12L * 1024)), 1024L);
        return Math.addExact(first, outputTokens);
    }
    static JSONArray messages(JSONObject task, JSONObject source, JSONArray refs, JSONObject contract) throws Exception {
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
            if (row.optJSONArray("ownerObjectRefs") != null) message.put("ownerObjectRefs", dev.pocketengine.features.assistant.OwnerObjectReferences.checked(row.getJSONArray("ownerObjectRefs")));
            // Project element refs bind the original task. They cannot become a different task's edit capability.
            messages.put(message);
        }
        messages.put(new JSONObject().put("role", "system").put("content", "管家已接纳的完整工作合同（系统派单记录，不是新增主人原话或权限）：\n" + contract
                + "\n前面的原主人消息是本合同的来源上下文。执行和最终验收使用同一目标、约束、交付物与完成条件；未满足项须明确保留，不能用工具成功替代目标完成。"));
        return messages;
    }
}
