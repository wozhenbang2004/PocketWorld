package dev.pocketengine.features.conversation;

import org.json.JSONObject;

/** Owner presentation preferences. They never alter message sources or authorize execution. */
public final class ConversationMetadata {
    private ConversationMetadata() {}
    public static long revision(JSONObject value) { return Math.max(1, value.optLong("metadataRevision", 1)); }
    public static JSONObject changed(JSONObject current, long expected, String title, Boolean pinned, long now) throws Exception {
        long revision = revision(current);
        if (expected != revision || revision == Long.MAX_VALUE) throw new IllegalStateException("对话设置已变化，请刷新后再试");
        if ((title == null) == (pinned == null)) throw new IllegalArgumentException("一次只修改名称或置顶状态");
        JSONObject next = new JSONObject(current.toString());
        if (title != null) {
            String clean = title.strip();
            if (clean.isEmpty() || clean.length() > 120 || clean.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("对话名称应为1至120字，不能包含控制字符");
            next.put("userTitle", clean).put("title", clean);
        } else {
            next.put("pinnedAt", pinned ? Math.max(1, now) : 0);
        }
        return next.put("metadataRevision", revision + 1);
    }
    public static JSONObject project(JSONObject header, JSONObject preferences) throws Exception {
        JSONObject result = new JSONObject(header.toString());
        if (preferences != null) {
            if (preferences.has("userTitle")) result.put("userTitle", preferences.getString("userTitle")).put("title", preferences.getString("userTitle"));
            result.put("pinnedAt", preferences.optLong("pinnedAt")).put("metadataRevision", revision(preferences));
        } else result.put("pinnedAt", 0).put("metadataRevision", 1);
        return result;
    }
}
