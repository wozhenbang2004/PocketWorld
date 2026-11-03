package dev.pocketengine.features.assistant.notifications;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** An immutable projection of an original, owner-approved operation. This is not authority to post. */
public final class ImmediateTaskNotice {
    public static final String TOOL = "post_notice";
    public static final String FORMAT = "pocketworld.native-owner-notice.v1";
    public static final int MAX_NOTICES_PER_RUN = 8;
    public static final long MIN_INTERVAL_MILLIS = 30_000L;
    private static final Set<String> ARGUMENTS = Set.of("title", "body", "dedupeKey");
    private static final String UUID = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";

    private ImmediateTaskNotice() {}

    /** TaskStore supplies the actual task and operation; model arguments cannot supply an identity. */
    public static Notice prepare(JSONObject originalTask, String operationId, JSONObject arguments) throws Exception {
        if (originalTask == null || arguments == null) throw new IllegalArgumentException("原任务与通知参数缺失");
        if (!"owner".equals(originalTask.optString("actorId"))
                || !"private".equals(originalTask.optString("spaceId"))
                || !Set.of("assistant", "phone").contains(originalTask.optString("kind"))
                || originalTask.has("sourceLearningJob")) throw new SecurityException("通知仅用于原私人任务");
        if (originalTask.has("origin")) {
            JSONObject origin = originalTask.optJSONObject("origin");
            if (origin == null || !"owner_reviewed_realtime".equals(origin.optString("kind")))
                throw new SecurityException("原通知来源不支持此操作");
        }
        for (Iterator<String> keys = arguments.keys(); keys.hasNext();) {
            if (!ARGUMENTS.contains(keys.next())) throw new IllegalArgumentException("通知含有不支持的参数");
        }
        String title = text(arguments, "title", 80, false);
        String body = text(arguments, "body", 1_000, true);
        Object rawKey = arguments.opt("dedupeKey");
        if (!(rawKey instanceof String) || !((String) rawKey).matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}"))
            throw new IllegalArgumentException("通知去重键无效");
        String taskId = identity(originalTask.opt("id"));
        String runId = identity(originalTask.opt("runId"));
        String operation = identity(operationId);
        Object rawEpoch = originalTask.opt("coordinatorEpoch");
        if (!(rawEpoch instanceof Integer || rawEpoch instanceof Long)
                || ((Number) rawEpoch).longValue() < 1 || ((Number) rawEpoch).longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("原任务执行代次无效");
        int epoch = ((Number) rawEpoch).intValue();
        String conversationId = originalTask.optString("conversationId", "");
        if (conversationId.length() > 200 || conversationId.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("原对话定位无效");
        String key = (String) rawKey;
        String contentDigest = digest(title, body);
        String token = digest(taskId, runId, Integer.toString(epoch), operation, key, contentDigest);
        return new Notice(taskId, runId, epoch, operation, conversationId, key, contentDigest, token);
    }

    public static void validateNavigation(String taskId, String runId, int epoch, String operationId, String token) {
        identity(taskId); identity(runId); identity(operationId);
        if (epoch < 1 || token == null || !token.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("原通知定位无效");
    }

    private static String identity(Object value) {
        if (!(value instanceof String) || !((String) value).matches(UUID))
            throw new IllegalArgumentException("原通知身份无效");
        return (String) value;
    }

    private static String text(JSONObject arguments, String key, int limit, boolean multiline) {
        Object raw = arguments.opt(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException("通知标题与正文必须是文字");
        String value = (String) raw;
        if (value.trim().isEmpty() || value.codePointCount(0, value.length()) > limit)
            throw new IllegalArgumentException("通知标题或正文长度无效");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isHighSurrogate(c)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index)))
                    throw new IllegalArgumentException("通知文字编码无效");
            } else if (Character.isLowSurrogate(c)
                    || Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\t')))
                throw new IllegalArgumentException("通知文字含有控制字符");
        }
        return value;
    }

    private static String digest(String... values) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            // Length prefixes keep boundaries distinct even when text contains punctuation or newlines.
            hash.update(new byte[]{(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16), (byte) (bytes.length >>> 8), (byte) bytes.length});
            hash.update(bytes);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : hash.digest()) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return result.toString();
    }

    public static final class Notice {
        private final String taskId, runId, operationId, conversationId, dedupeKey, contentDigest, token;
        private final int epoch;

        private Notice(String taskId, String runId, int epoch, String operationId, String conversationId,
                       String dedupeKey, String contentDigest, String token) {
            this.taskId = taskId; this.runId = runId; this.epoch = epoch; this.operationId = operationId;
            this.conversationId = conversationId; this.dedupeKey = dedupeKey; this.contentDigest = contentDigest; this.token = token;
        }

        public String taskId() { return taskId; }
        public String runId() { return runId; }
        public int epoch() { return epoch; }
        public String operationId() { return operationId; }
        public String conversationId() { return conversationId; }
        public String dedupeKey() { return dedupeKey; }
        public String contentDigest() { return contentDigest; }
        public String token() { return token; }

        /** Custom text stays in the original approved operation, never in a lock-screen projection. */
        public JSONObject view() throws Exception {
            return new JSONObject().put("format", FORMAT).put("taskId", taskId).put("runId", runId)
                    .put("epoch", epoch).put("operationId", operationId).put("conversationId", conversationId)
                    .put("dedupeKey", dedupeKey).put("contentDigest", contentDigest).put("token", token)
                    .put("bodyIncluded", false).put("executionAuthorized", false);
        }

        /** A successful binder call confirms API acceptance, not delivery or a human reading it. */
        public JSONObject receipt(String phase, String reason) throws Exception {
            Object accepted;
            String message;
            switch (phase) {
                case "posted": accepted = true; message = "已交给系统通知服务；是否显示或阅读尚未确认。"; break;
                case "posting": case "unknown": case "unconfirmed":
                    accepted = JSONObject.NULL; message = "本次通知结果尚未确认，不会自动重复发送。"; break;
                case "permission_required":
                    accepted = false; message = "通知权限或应用通知开关未开启，本次未发送。"; break;
                case "channel_disabled":
                    accepted = false; message = "任务通知渠道未开启，本次未发送。"; break;
                case "rate_limited":
                    accepted = false; message = "原任务通知过于频繁，本次未发送。"; break;
                case "unavailable": case "not_dispatched":
                    accepted = false; message = "本次未发送通知，请在原任务查看记录。"; break;
                default: throw new IllegalArgumentException("通知回执状态无效");
            }
            if (reason == null || !reason.matches("[a-z][a-z0-9_]{0,79}"))
                throw new IllegalArgumentException("通知回执原因无效");
            return view().put("kind", "native_owner_notice").put("phase", phase).put("reason", reason)
                    .put("accepted", accepted).put("notificationApiAccepted", accepted).put("message", message)
                    .put("deliveryConfirmed", false).put("userReadConfirmed", false)
                    .put("automaticRetry", false).put("replayAllowed", false);
        }
    }
}
