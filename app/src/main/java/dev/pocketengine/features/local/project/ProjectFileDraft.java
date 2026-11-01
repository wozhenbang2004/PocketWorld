package dev.pocketengine.features.local.project;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Owner-only editor text. This is neither a workspace write nor an execution/grant receipt. */
public final class ProjectFileDraft {
    public static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    public final String projectId, checkoutId, path, text, baseContent, baseHash;
    public final long revision;
    public final boolean creating;
    /** Fresh native observation, deliberately not restored from the draft file. */
    private final JSONObject latest;

    private ProjectFileDraft(String project, String checkout, String path, String text,
                             String base, String hash, long revision, boolean creating, JSONObject latest) throws org.json.JSONException {
        this.projectId = identity(project); this.checkoutId = identity(checkout); this.path = path(path);
        this.text = bounded(text); this.baseContent = bounded(base); this.baseHash = hash(hash);
        if (revision < 0) throw new IllegalArgumentException("草稿版本无效");
        this.revision = revision; this.creating = creating;
        this.latest = latest == null ? null : copy(latest);
    }

    public static ProjectFileDraft empty(String project, String checkout, String path, boolean creating) throws org.json.JSONException {
        return new ProjectFileDraft(project, checkout, path, "", "", creating ? "absent" : "", 0, creating, null);
    }
    public boolean dirty() { return !text.equals(baseContent); }
    public boolean hasBaseline() { return !baseHash.isEmpty(); }
    public boolean conflict() { return latest != null && !latest.optString("expectedHash").equals(baseHash); }
    public JSONObject latestReview() throws org.json.JSONException { return latest == null ? null : copy(latest); }

    public ProjectFileDraft edit(String value) throws org.json.JSONException {
        if (value.equals(text)) return this;
        return next(value, baseContent, baseHash, creating, latest);
    }

    /** A read can reveal a conflict, but may never silently replace unsaved owner text/base. */
    public ProjectFileDraft observe(JSONObject review) throws org.json.JSONException {
        requireReview(review);
        if (!hasBaseline() || !dirty())
            return next(review.getString("content"), review.getString("content"), review.getString("expectedHash"), false, review);
        return next(text, baseContent, baseHash, creating, review);
    }

    /** Explicit owner decision after displaying the newly read file. Does not write anything. */
    public ProjectFileDraft keepMineAgainstLatest() throws org.json.JSONException {
        if (latest == null) throw new IllegalStateException("请先读取最新文件");
        return next(text, latest.getString("content"), latest.getString("expectedHash"), false, latest);
    }
    /** Explicit discard. The caller confirms loss of the owner's unsaved text. */
    public ProjectFileDraft useLatest() throws org.json.JSONException {
        if (latest == null) throw new IllegalStateException("请先读取最新文件");
        return next(latest.getString("content"), latest.getString("content"), latest.getString("expectedHash"), false, latest);
    }

    public SaveIntent saveIntent() {
        if (!hasBaseline() || (!creating && latest == null) || conflict()) throw new IllegalStateException("请先读取并处理最新文件，草稿已保留");
        final java.nio.ByteBuffer actualBytes;
        try { actualBytes = strictUtf8(text); }
        catch (java.nio.charset.CharacterCodingException incomplete) { throw new IllegalArgumentException("当前文字含尚未完成的字符，请完成输入后再保存；当前草稿与未保存状态均已保留", incomplete); }
        if (actualBytes.remaining() > 262144) throw new IllegalArgumentException("副本保存上限256KiB；当前草稿未丢弃");
        return new SaveIntent(projectId, checkoutId, path, text, baseHash, revision);
    }

    /** Only the exact submitted bytes acknowledge a write; later editing remains dirty. */
    public ProjectFileDraft saved(SaveIntent submitted, JSONObject receipt) throws org.json.JSONException {
        if (!projectId.equals(submitted.projectId) || !checkoutId.equals(submitted.checkoutId) || !path.equals(submitted.path) ||
            submitted.revision > revision || !baseHash.equals(submitted.expectedHash) || !checkoutId.equals(receipt.optString("checkoutId")) || !path.equals(receipt.optString("path")) ||
            !submitted.text.equals(receipt.optString("content")) || !digest(submitted.text).equals(receipt.optString("sha256")))
            throw new IllegalArgumentException("保存回执与这次原草稿不一致；草稿未清除");
        if (latest != null && !latest.optString("expectedHash").equals(submitted.expectedHash) && !latest.optString("expectedHash").equals(receipt.getString("sha256")))
            throw new IllegalStateException("已读到更新的文件，旧保存回执未清除草稿；请核对最新文件");
        return next(text, submitted.text, receipt.getString("sha256"), false, null);
    }

    public ProjectFileDraft deleted(JSONObject receipt) throws org.json.JSONException {
        if (dirty() || !checkoutId.equals(receipt.optString("checkoutId")) || !path.equals(receipt.optString("path")) || !"absent".equals(receipt.optString("sha256")))
            throw new IllegalArgumentException("删除回执与原文件不一致，草稿未清除");
        return next("", "", "absent", false, null);
    }

    public JSONObject toJson() throws org.json.JSONException {
        // No approval, expected project version, process, preview or runtime authority is persisted.
        return new JSONObject().put("format", "pocketworld.project-file-draft.v1")
            .put("projectId", projectId).put("checkoutId", checkoutId).put("path", path)
            .put("text", text).put("draftHash", draftIntegrity(text)).put("baseContent", baseContent).put("baseHash", baseHash)
            .put("revision", revision).put("creating", creating);
    }
    public static ProjectFileDraft restore(JSONObject value, String project, String checkout, String path) throws org.json.JSONException {
        if (!"pocketworld.project-file-draft.v1".equals(value.optString("format")) || !project.equals(value.optString("projectId")) ||
            !checkout.equals(value.optString("checkoutId")) || !path.equals(value.optString("path")))
            throw new IllegalArgumentException("草稿不属于原项目文件，未换用其他文件");
        ProjectFileDraft result = new ProjectFileDraft(project, checkout, path, value.getString("text"), value.getString("baseContent"),
            value.getString("baseHash"), value.getLong("revision"), value.getBoolean("creating"), null);
        if (!draftIntegrity(result.text).equals(value.getString("draftHash"))) throw new IllegalArgumentException("草稿文字摘要损坏，未使用空白替代");
        if (result.hasBaseline() && !"absent".equals(result.baseHash) && !digest(result.baseContent).equals(result.baseHash))
            throw new IllegalArgumentException("草稿原文件摘要损坏；未用磁盘内容覆盖草稿");
        if ("absent".equals(result.baseHash) && !result.baseContent.isEmpty()) throw new IllegalArgumentException("新增草稿基线不一致");
        if (result.baseHash.isEmpty() && (!result.baseContent.isEmpty() || !result.text.isEmpty() || result.creating)) throw new IllegalArgumentException("缺少可核对基线的草稿缓存");
        return result;
    }

    private ProjectFileDraft next(String text, String base, String hash, boolean creating, JSONObject latest) throws org.json.JSONException {
        return new ProjectFileDraft(projectId, checkoutId, path, text, base, hash, revision + 1, creating, latest);
    }
    private void requireReview(JSONObject value) throws org.json.JSONException {
        if (!projectId.equals(value.optString("projectId")) || !checkoutId.equals(value.optString("checkoutId")) || !path.equals(value.optString("path")) ||
            !(value.opt("content") instanceof String)) throw new IllegalArgumentException("读取结果不属于原草稿文件");
        String actual = value.getString("expectedHash");
        if (!("absent".equals(actual) && value.getString("content").isEmpty()) && !digest(value.getString("content")).equals(actual))
            throw new IllegalArgumentException("最新文件摘要不一致，草稿未变化");
    }
    private static String bounded(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES || text.indexOf('\0') >= 0)
            throw new IllegalArgumentException("项目文件草稿上限2MiB，且必须是无NUL文字");
        return text;
    }
    static String identity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,80}") || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("原项目或副本身份无效");
        return value;
    }
    static String path(String value) {
        if (value == null || value.length() > 240 || value.isEmpty() || value.startsWith("/") || value.contains("\\") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("请输入有效项目相对路径");
        for (String part : value.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("项目路径不能越界");
        return value;
    }
    private static String hash(String value) {
        if (value == null || !(value.isEmpty() || value.equals("absent") || value.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("草稿基线摘要无效");
        return value;
    }
    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    /** Typed integrity only for an unfinished private draft, never a source-file CAS. */
    private static String draftIntegrity(String value) {
        try { strictUtf8(value); return digest(value); }
        catch (java.nio.charset.CharacterCodingException incomplete) {
            try {
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                for (int i = 0; i < value.length(); i++) { char unit = value.charAt(i); hash.update((byte)(unit >>> 8)); hash.update((byte)unit); }
                StringBuilder result = new StringBuilder("utf16-v1:");
                for (byte item : hash.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
                return result.toString();
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }
    private static java.nio.ByteBuffer strictUtf8(String value) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
    }
    private static JSONObject copy(JSONObject value) throws org.json.JSONException { return new JSONObject(value.toString()); }

    public static final class SaveIntent {
        public final String projectId, checkoutId, path, text, expectedHash;
        public final long revision;
        private SaveIntent(String project, String checkout, String path, String text, String hash, long revision) {
            this.projectId = project; this.checkoutId = checkout; this.path = path; this.text = text; this.expectedHash = hash; this.revision = revision;
        }
        public JSONObject input() throws org.json.JSONException { return new JSONObject().put("checkoutId", checkoutId).put("path", path).put("content", text).put("expectedHash", expectedHash); }
    }
}
