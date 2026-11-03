package dev.pocketengine.runtime.policy;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Answer data for one native question. Options never represent an approval or a tool call. */
public final class OwnerQuestionChoices {
    public static final int MAX_OPTIONS = 5;
    public static final int MAX_LABEL = 300;
    private OwnerQuestionChoices() {}

    public static JSONArray options(JSONObject question) throws Exception {
        if (!question.has("options")) return new JSONArray();
        Object supplied = question.opt("options");
        if (!(supplied instanceof JSONArray rows) || rows.length() < 2 || rows.length() > MAX_OPTIONS)
            throw new IllegalArgumentException("普通问题需要2至5个明确选项，或省略options使用自由回答");
        JSONArray result = new JSONArray();
        Set<String> ids = new HashSet<>(), labels = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            Object item = rows.opt(i);
            if (!(item instanceof JSONObject row) || row.length() != 2 || !row.has("id") || !row.has("label")
                    || !(row.opt("id") instanceof String id) || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                    || !(row.opt("label") instanceof String label) || label.isBlank() || label.length() > MAX_LABEL
                    || !ids.add(id) || !labels.add(label.strip()))
                throw new IllegalArgumentException("选项只接受不重复的稳定id与简短label，不接受权限或执行字段");
            result.put(new JSONObject().put("id", id).put("label", label.strip()));
        }
        return result;
    }

    /** Resolve against stored question data; never trust an answer label supplied by the UI. */
    public static String answer(JSONObject question, JSONObject exact, String optionId) throws Exception {
        if (question == null || exact == null || optionId == null
                || !"pocketworld.owner-question.v1".equals(question.optString("format"))
                || !"answer".equals(question.optString("replyKind"))
                || !Boolean.FALSE.equals(question.opt("approval")) || !Boolean.FALSE.equals(question.opt("takeover")))
            throw new SecurityException("只有原普通问题可以选择答案");
        for (String key : new String[]{"taskId", "runId", "requestId"})
            if (!(exact.opt(key) instanceof String value) || value.isBlank() || !value.equals(question.optString(key)))
                throw new SecurityException("选项所属原问题已改变");
        for (String key : new String[]{"coordinatorEpoch", "waitRevision"}) {
            Object expected = exact.opt(key), actual = question.opt(key);
            if (!(expected instanceof Integer || expected instanceof Long) || !(actual instanceof Integer || actual instanceof Long)
                    || ((Number) expected).longValue() < 1 || ((Number) expected).longValue() != ((Number) actual).longValue())
                throw new SecurityException("选项所属原等待版本已改变");
        }
        JSONArray rows = options(question);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (optionId.equals(row.getString("id"))) return row.getString("label");
        }
        throw new SecurityException("原问题中没有这个选项；可自由填写答案");
    }

    public static JSONObject schema() throws Exception {
        JSONObject item = new JSONObject().put("type", "object").put("additionalProperties", false)
                .put("required", new JSONArray(new String[]{"id", "label"}))
                .put("properties", new JSONObject()
                        .put("id", new JSONObject().put("type", "string").put("pattern", "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$").put("maxLength", 64))
                        .put("label", new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", MAX_LABEL)));
        return new JSONObject().put("type", "array").put("minItems", 2).put("maxItems", MAX_OPTIONS).put("items", item);
    }
}
