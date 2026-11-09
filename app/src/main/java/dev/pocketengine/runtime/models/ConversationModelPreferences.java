package dev.pocketengine.runtime.models;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Private conversation choices. References contain no keys and never grant execution authority. */
public final class ConversationModelPreferences {
    private static final Object PROCESS_LOCK = new Object();
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SCOPES = 4096, MAX_PENDING = 256;
    private final Path file;
    private JSONObject observed;

    public ConversationModelPreferences(Path file) { this.file = file.toAbsolutePath(); }

    public static String conversation(String id) { return "conversation:" + identity(id); }
    public static String draft(String id) { return "draft:" + identity(id); }
    private static String identity(String value) {
        if (value == null || value.isBlank() || value.length() > 220 || value.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("原对话或草稿身份不可用");
        return value;
    }
    private static String scope(String value) {
        identity(value);
        if (!value.startsWith("conversation:") && !value.startsWith("draft:")) throw new IllegalArgumentException("原模型偏好范围不可用");
        return value;
    }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static long revision(JSONObject value) throws Exception {
        if (value == null) return 0;
        Object raw = value.get("revision");
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 1)
            throw new IOException("invalid_conversation_model_revision");
        return ((Number) raw).longValue();
    }
    private static JSONObject project(String scope, JSONObject row) throws Exception {
        JSONObject target = row == null ? null : row.optJSONObject("target");
        return new JSONObject().put("scopeKey", scope).put("revision", revision(row))
                .put("inherited", target == null).put("target", target == null ? JSONObject.NULL : copy(target));
    }

    /** Read local metadata only; the caller resolves and validates this source at task admission. */
    public JSONObject snapshot(String key) throws Exception {
        synchronized (PROCESS_LOCK) {
            if (observed == null) observed = load();
            return project(scope(key), observed.getJSONObject("scopes").optJSONObject(key));
        }
    }

    public JSONObject save(String key, long expectedRevision, JSONObject target) throws Exception {
        scope(key);
        JSONObject checked = target == null ? null : checkedTarget(target);
        return transaction(root -> {
            JSONObject rows = root.getJSONObject("scopes"), current = rows.optJSONObject(key);
            if (expectedRevision < 0 || revision(current) != expectedRevision) throw new IllegalStateException("此对话的模型设置已变化，请重新读取");
            if (current == null && rows.length() >= MAX_SCOPES) throw new IllegalStateException("对话模型偏好已达保存上限");
            JSONObject next = new JSONObject().put("revision", Math.addExact(expectedRevision, 1))
                    .put("target", checked == null ? JSONObject.NULL : checked);
            rows.put(key, next);
            return project(key, next);
        });
    }

    /** Bind the native composer identity before dispatch, retaining the same choice on a receipt retry. */
    public void pinSubmission(String clientId, String key) throws Exception {
        identity(clientId); scope(key);
        transaction(root -> {
            JSONObject pending = root.getJSONObject("pending"), existing = pending.optJSONObject(clientId);
            if (existing != null) {
                if (!key.equals(existing.getString("scopeKey"))) throw new SecurityException("原输入的模型偏好不能移到另一对话");
                return existing;
            }
            if (pending.length() >= MAX_PENDING) throw new IllegalStateException("请先核对尚未确认的原输入回执");
            JSONObject selected = project(key, root.getJSONObject("scopes").optJSONObject(key));
            pending.put(clientId, selected);
            return selected;
        });
    }

    public JSONObject targetForAdmission(String conversationId, String clientId) throws Exception {
        synchronized (PROCESS_LOCK) {
            JSONObject root = load(), pinned = clientId == null || clientId.isBlank() ? null : root.getJSONObject("pending").optJSONObject(clientId);
            if (pinned != null) {
                String original = pinned.getString("scopeKey");
                if (original.startsWith("conversation:") && !original.equals(conversation(conversationId)))
                    throw new SecurityException("原输入和模型偏好不属于同一对话");
                JSONObject target = pinned.optJSONObject("target");
                return target == null ? null : copy(target);
            }
            if (conversationId == null || conversationId.isBlank()) return null;
            JSONObject row = root.getJSONObject("scopes").optJSONObject(conversation(conversationId));
            JSONObject target = row == null ? null : row.optJSONObject("target");
            return target == null ? null : copy(target);
        }
    }

    /** A confirmed original receipt binds the draft preference to the newly allocated conversation. */
    public void confirmSubmission(String clientId, String conversationId) throws Exception {
        identity(clientId); String destination = conversation(conversationId);
        transaction(root -> {
            JSONObject pending = root.getJSONObject("pending"), pinned = pending.optJSONObject(clientId);
            if (pinned == null) return new JSONObject();
            bind(root, pinned, destination);
            String source = pinned.getString("scopeKey"); JSONObject rows = root.getJSONObject("scopes");
            if (source.startsWith("draft:")) {
                JSONObject draft = rows.optJSONObject(source);
                JSONObject current = rows.optJSONObject(destination);
                // The submitted task uses its pinned M. The migrated editor may already prefer N.
                // Only the destination created by this same draft, still unedited, may follow it.
                boolean ownDestination = current != null && source.equals(current.optString("draftOrigin")) && revision(current) == 1;
                if (draft != null && (current == null || ownDestination)) {
                    if (current == null && rows.length() >= MAX_SCOPES) throw new IllegalStateException("对话模型偏好已达保存上限");
                    rows.put(destination, new JSONObject().put("revision", current == null ? 1 : 2)
                            .put("target", draft.isNull("target") ? JSONObject.NULL : copy(draft.getJSONObject("target"))));
                    rows.remove(source);
                } else if (revision(draft) == pinned.getLong("revision")) rows.remove(source);
            }
            pending.remove(clientId);
            return new JSONObject();
        });
    }

    /** Called before the original task commit; keep the pin until its durable receipt is confirmed. */
    public void bindAllocatedConversation(String clientId, String conversationId) throws Exception {
        identity(clientId); String destination = conversation(conversationId);
        transaction(root -> {
            JSONObject pinned = root.getJSONObject("pending").optJSONObject(clientId);
            if (pinned != null) bind(root, pinned, destination);
            return new JSONObject();
        });
    }
    private static void bind(JSONObject root, JSONObject pinned, String destination) throws Exception {
        String source = pinned.getString("scopeKey"); JSONObject rows = root.getJSONObject("scopes");
        if (source.startsWith("conversation:") && !source.equals(destination)) throw new SecurityException("原回执返回了其他对话");
        if (source.startsWith("draft:") && pinned.optJSONObject("target") != null && !rows.has(destination)) {
            if (rows.length() >= MAX_SCOPES) throw new IllegalStateException("对话模型偏好已达保存上限");
            rows.put(destination, new JSONObject().put("revision", 1).put("target", copy(pinned.getJSONObject("target"))).put("draftOrigin", source));
        }
    }

    /** Only a verified, definite not-admitted receipt may release this pending identity. */
    public void rejectSubmission(String clientId, String conversationId) throws Exception {
        identity(clientId);
        transaction(root -> {
            JSONObject pending = root.getJSONObject("pending"), pinned = pending.optJSONObject(clientId);
            if (pinned == null) return new JSONObject();
            String source = pinned.getString("scopeKey");
            if (source.startsWith("conversation:") && !source.equals(conversation(conversationId))) throw new SecurityException("原拒绝回执不属于此对话");
            pending.remove(clientId); return new JSONObject();
        });
    }

    /** Retain options only for an explicit recheck of the same native choice. The caller must capture a fresh binding and validate its current capabilities. */
    public static JSONObject apiReselectionTarget(JSONObject preference, String expectedScope, long expectedRevision,
                                                   String connectionId, String modelId) throws Exception {
        if (!scope(expectedScope).equals(preference.getString("scopeKey")) || expectedRevision < 0
                || positive(preference, "revision", 0, Long.MAX_VALUE) != expectedRevision)
            throw new IllegalStateException("此对话的模型设置已变化，请重新读取");
        if (preference.getBoolean("inherited")) return null;
        JSONObject target = checkedTarget(preference.getJSONObject("target"));
        return "api".equals(target.getString("kind")) && connectionId.equals(target.getString("connectionId"))
                && modelId.equals(target.getString("modelId")) ? target : null;
    }

    public static JSONObject checkedTarget(JSONObject source) throws Exception {
        JSONObject value = copy(source); String kind = value.getString("kind");
        Set<String> keys = new HashSet<>(); value.keys().forEachRemaining(keys::add);
        Set<String> allowed = switch (kind) {
            case "api" -> Set.of("kind", "connectionId", "connectionRevision", "binding", "modelId", "outputTokens", "reasoning", "imageTokenReserve");
            case "account" -> Set.of("kind", "nativeRef", "outputTokens", "reasoning", "imageTokenReserve");
            case "group" -> Set.of("kind", "groupId", "groupRevision");
            default -> throw new IllegalArgumentException("不支持的对话模型目标");
        };
        if (!keys.equals(allowed)) throw new IllegalArgumentException("对话模型偏好只能包含原生引用和参数");
        if (kind.equals("api")) {
            ModelTargetRef.exact(value.getString("connectionId"), value.getString("modelId"));
            positive(value, "connectionRevision", 1, Long.MAX_VALUE);
            JSONObject rawBinding = value.getJSONObject("binding");
            ModelBinding binding = ModelBinding.fromJson(rawBinding);
            Set<String> bindingKeys = new HashSet<>(); rawBinding.keys().forEachRemaining(bindingKeys::add);
            Set<String> publicKeys = new HashSet<>(); binding.toJson().keys().forEachRemaining(publicKeys::add);
            if (!bindingKeys.equals(publicKeys) || !binding.connectionId().equals(value.getString("connectionId")))
                throw new IllegalArgumentException("对话模型只能保留同一来源的公开绑定");
        } else if (kind.equals("account")) {
            dev.pocketengine.core.ModelConfig.AccountModelRef.fromJson(value.getJSONObject("nativeRef"));
        } else {
            ModelTargetRef.group(value.getString("groupId"), positive(value, "groupRevision", 1, Long.MAX_VALUE));
            return value;
        }
        positive(value, "outputTokens", 128, 32768); positive(value, "imageTokenReserve", 0, 2_000_000);
        JSONObject reasoning = value.getJSONObject("reasoning");
        Set<String> reasoningKeys = new HashSet<>(); reasoning.keys().forEachRemaining(reasoningKeys::add);
        if (!reasoningKeys.equals(Set.of("mode", "level", "budgetTokens")))
            throw new IllegalArgumentException("对话推理参数只能包含原生参数");
        ReasoningOptions.fromJson(reasoning);
        return value;
    }
    private static long positive(JSONObject value, String key, long min, long max) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number)raw).longValue() < min || ((Number)raw).longValue() > max)
            throw new IllegalArgumentException("原模型参数或修订无效");
        return ((Number)raw).longValue();
    }
    @FunctionalInterface private interface Change { JSONObject apply(JSONObject root) throws Exception; }
    private JSONObject transaction(Change action) throws Exception {
        synchronized (PROCESS_LOCK) {
            Files.createDirectories(file.getParent());
            Path lock = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 java.nio.channels.FileLock ignored = channel.lock()) {
                JSONObject root = load(), result = action.apply(root); save(root); observed = root; return copy(result);
            }
        }
    }
    private JSONObject load() throws Exception {
        if (Files.notExists(file)) return new JSONObject().put("format", "pocketworld.conversation-models.v1").put("scopes", new JSONObject()).put("pending", new JSONObject());
        if (Files.size(file) > MAX_BYTES) throw new IOException("conversation_models_too_large");
        JSONTokener parser = new JSONTokener(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)); Object parsed = parser.nextValue();
        if (!(parsed instanceof JSONObject root) || parser.nextClean() != 0 || !"pocketworld.conversation-models.v1".equals(root.getString("format")))
            throw new IOException("原对话模型偏好暂不可读取，原记录已保留");
        JSONObject rows = root.getJSONObject("scopes"), pending = root.getJSONObject("pending");
        if (rows.length() > MAX_SCOPES || pending.length() > MAX_PENDING) throw new IOException("conversation_models_too_large");
        for (var names = rows.keys(); names.hasNext();) {
            String key = names.next(); scope(key); JSONObject row = rows.getJSONObject(key); revision(row);
            if (!row.isNull("target")) checkedTarget(row.getJSONObject("target"));
        }
        for (var names = pending.keys(); names.hasNext();) {
            String id = names.next(); identity(id); JSONObject pinned = pending.getJSONObject(id);
            scope(pinned.getString("scopeKey")); positive(pinned, "revision", 0, Long.MAX_VALUE);
            if (!pinned.isNull("target")) checkedTarget(pinned.getJSONObject("target"));
        }
        return root;
    }
    private void save(JSONObject root) throws Exception {
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("conversation_models_too_large");
        Path temporary = Files.createTempFile(file.getParent(), "conversation-models-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
