package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Views of the original persisted team text. This adds no output store or execution authority. */
final class TeamOutputText {
    private TeamOutputText() { }

    static String received(String text, String secret) {
        if (text == null || text.codePoints().allMatch(Character::isWhitespace))
            throw new IllegalArgumentException("成员没有返回文字");
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException("原成员输出含不完整Unicode，未替换或截断保存");
        // Preserve the entire received value, including whitespace. Redaction is the only transform.
        return secret == null || secret.isEmpty() ? text : text.replace(secret, "[密钥已隐藏]");
    }

    static JSONObject preview(String text, String turnId, int limit) throws Exception {
        if (limit < 0) throw new IllegalArgumentException("原文预览范围无效");
        int end = completeEnd(text, Math.min(text.length(), limit));
        return metadata(text, turnId).put("content", text.substring(0, end))
                .put("characters", text.length()).put("returnedChars", end)
                .put("truncated", end < text.length());
    }

    static JSONObject page(String text, String turnId, long offset, long length) throws Exception {
        if (offset < 0 || offset > text.length() || length < 1 || length > 8000)
            throw new IllegalArgumentException("原文回读范围无效");
        int start = (int) offset, end = completeEnd(text, (int) Math.min(text.length(), offset + length));
        if (start > 0 && start < text.length() && Character.isHighSurrogate(text.charAt(start - 1)) && Character.isLowSurrogate(text.charAt(start)))
            throw new IllegalArgumentException("原文回读位置不能拆开Unicode字符，请使用原nextOffset");
        if (start < text.length() && end == start) throw new IllegalArgumentException("原文回读长度不足以包含一个完整Unicode字符");
        return metadata(text, turnId).put("offset", start).put("characters", end - start)
                .put("returnedChars", end - start).put("totalCharacters", text.length())
                .put("nextOffset", end < text.length() ? end : JSONObject.NULL)
                .put("truncated", start != 0 || end < text.length()).put("content", text.substring(start, end));
    }

    private static int completeEnd(String text, int end) {
        return end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    private static JSONObject metadata(String text, String turnId) throws Exception {
        String sha = hash(text);
        JSONObject result = new JSONObject().put("originalChars", text.length()).put("sha256", sha)
                .put("characterUnit", "utf16_code_units").put("source", "persisted_received_text_after_redaction")
                // A complete local receipt cannot prove that the provider/runner did not truncate upstream.
                .put("upstreamOriginalChars", JSONObject.NULL).put("upstreamTruncated", JSONObject.NULL);
        if (turnId != null && !turnId.isEmpty()) result.put("readRef", new JSONObject()
                .put("tool", "team_read_output").put("turnId", turnId).put("sha256", sha));
        else result.put("readRef", JSONObject.NULL);
        return result;
    }

    static String hash(String text) throws Exception {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw new IllegalArgumentException("原成员正文包含不完整Unicode");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(64);
        for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
}
