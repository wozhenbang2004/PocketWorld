package dev.pocketengine.ui.setup;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** First-use choices only. A saved choice never installs tools, grants access or starts inference. */
public final class SetupPreferences {
    public static final String PREFERENCES_NAME = "first_use_setup";
    private static final String STATE = "state";
    private static final String MODEL_TEST = "model_test";
    private static final String FINGERPRINT_KEY = "model_test_key";
    private static final Object LOCK = new Object();
    private static final Set<SharedPreferences> UNCERTAIN = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<String> STEPS = Set.of("welcome", "model", "capabilities", "finish");
    private static final Set<String> STATUSES = Set.of("new", "in_progress", "completed", "skipped");
    private static final Set<String> CAPABILITIES = Set.of("phone", "tools", "voice", "background");
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private final SharedPreferences prefs;

    /** Call in the main Application process before creating any business store. Never clears old data. */
    public static void initialize(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            requireConfirmed(prefs);
            if (prefs.contains(STATE)) { checkedState(prefs.getString(STATE, "")); return; }
            boolean existing = !context.getSharedPreferences("private_settings", Context.MODE_PRIVATE).getAll().isEmpty();
            // TaskStore's business database, not an SDK/provider cache that may be created
            // before Application.onCreate even during a clean install.
            File database = context.getDatabasePath("pocket-world.db");
            existing = existing || database.isFile() && database.length() > 0;
            initialize(prefs, existing);
        }
    }

    public SetupPreferences(Context context) {
        this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    SetupPreferences(SharedPreferences prefs) { this.prefs = java.util.Objects.requireNonNull(prefs); }

    static void initialize(SharedPreferences prefs, boolean existing) throws Exception {
        synchronized (LOCK) {
            requireConfirmed(prefs);
            if (prefs.contains(STATE)) { checkedState(prefs.getString(STATE, "")); return; }
            JSONObject choices = new JSONObject().put("step", "welcome").put("capabilities", new JSONArray())
                    .put("status", existing ? "skipped" : "new");
            JSONObject state = new JSONObject().put("version", 1).put("revision", 1).put("preferences", choices)
                    .put("vendorBackgroundVisited", false).put("entry", existing ? "existing_install" : "clean_install");
            commit(prefs, prefs.edit().putString(STATE, state.toString()));
        }
    }

    public JSONObject read() throws Exception {
        synchronized (LOCK) { return publicState(readState()); }
    }

    public JSONObject save(JSONObject input) throws Exception {
        synchronized (LOCK) {
            JSONObject current = readState();
            long expected = integer(input.opt("expectedRevision"), "请先读取首次使用设置的当前版本");
            if (current.getLong("revision") != expected) throw new IllegalStateException("首次使用设置已在另一处变化，请刷新后继续");
            String step = requiredString(input, "step"), status = requiredString(input, "status");
            if (!STEPS.contains(step) || !STATUSES.contains(status) || "new".equals(status))
                throw new IllegalArgumentException("请选择有效的首次使用步骤和进度");
            if ("completed".equals(status) && !"finish".equals(step))
                throw new IllegalArgumentException("请先进入最后核对步骤，再完成首次使用配置");
            JSONArray capabilities = checkedCapabilities(input.getJSONArray("capabilities"));
            JSONObject next = new JSONObject(current.toString()).put("revision", nextRevision(expected))
                    .put("preferences", new JSONObject().put("step", step).put("capabilities", capabilities).put("status", status));
            commit(prefs, prefs.edit().putString(STATE, next.toString()));
            return publicState(next);
        }
    }

    /** This records opening a settings entry, never a manufacturer grant we cannot observe. */
    public void markVendorBackgroundVisited() throws Exception {
        synchronized (LOCK) {
            JSONObject current = readState();
            if (current.getBoolean("vendorBackgroundVisited")) return;
            current.put("vendorBackgroundVisited", true).put("revision", nextRevision(current.getLong("revision")));
            commit(prefs, prefs.edit().putString(STATE, current.toString()));
        }
    }

    public boolean vendorBackgroundVisited() throws Exception {
        synchronized (LOCK) { return readState().getBoolean("vendorBackgroundVisited"); }
    }

    boolean permissionRequested(String kind) throws Exception {
        synchronized (LOCK) { readState(); return prefs.getBoolean("permission_requested_" + permissionKind(kind), false); }
    }

    void markPermissionRequested(String kind) throws Exception {
        synchronized (LOCK) {
            readState(); commit(prefs, prefs.edit().putBoolean("permission_requested_" + permissionKind(kind), true));
        }
    }

    private static String permissionKind(String kind) {
        if (!Set.of("notifications", "microphone").contains(kind)) throw new IllegalArgumentException("无效的系统权限请求");
        return kind;
    }

    /** Native-only HMAC; neither the key nor a credential-derived fingerprint enters Web state. */
    String fingerprint(String nativeMaterial) throws Exception {
        synchronized (LOCK) {
            readState();
            String encoded = prefs.getString(FINGERPRINT_KEY, "");
            byte[] key;
            if (encoded.isEmpty()) {
                key = new byte[32]; new SecureRandom().nextBytes(key);
                commit(prefs, prefs.edit().putString(FINGERPRINT_KEY, Base64.getEncoder().encodeToString(key)));
            } else {
                key = Base64.getDecoder().decode(encoded);
                if (key.length != 32) throw new IllegalStateException("首次使用模型验证标识不可读取，请核对本机存储");
            }
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(nativeMaterial.getBytes(StandardCharsets.UTF_8)));
        }
    }

    JSONObject modelTest() throws Exception {
        synchronized (LOCK) {
            readState();
            String raw = prefs.getString(MODEL_TEST, "");
            if (raw.isEmpty()) return new JSONObject();
            JSONObject record = new JSONObject(raw);
            if (record.optInt("version") != 1 || !(record.opt("fingerprint") instanceof String)
                    || !Set.of("testing", "passed", "failed", "unknown").contains(record.optString("outcome")))
                throw new IllegalStateException("上次模型验证回执不可读取，未视为通过");
            if ("passed".equals(record.getString("outcome"))) requireTestReference(record.optJSONObject("inferenceReference"));
            return record;
        }
    }

    void saveModelTest(String fingerprint, String outcome, String message, JSONObject reference) throws Exception {
        synchronized (LOCK) {
            readState();
            if (fingerprint == null || fingerprint.isEmpty() || !Set.of("testing", "passed", "failed", "unknown").contains(outcome))
                throw new IllegalArgumentException("模型验证状态无效");
            if (reference != null || "passed".equals(outcome)) requireTestReference(reference);
            JSONObject record = new JSONObject().put("version", 1).put("fingerprint", fingerprint).put("outcome", outcome)
                    .put("message", message).put("time", System.currentTimeMillis())
                    .put("inferenceReference", reference == null ? JSONObject.NULL : new JSONObject(reference.toString()));
            commit(prefs, prefs.edit().putString(MODEL_TEST, record.toString()));
        }
    }

    /** A pending result after process death is unknown. Reads never repeat a billable request. */
    JSONObject modelEvidence(String currentFingerprint, boolean running) throws Exception {
        synchronized (LOCK) {
            JSONObject stored = modelTest();
            JSONObject evidence = new JSONObject().put("tested", false).put("testing", false);
            if (stored.length() == 0) return evidence;
            if (!stored.getString("fingerprint").equals(currentFingerprint))
                return evidence.put("message", "模型配置或应用会话已变化，需要重新实测；保存设置不代表连接成功。");
            String outcome = stored.getString("outcome");
            if ("testing".equals(outcome))
                return evidence.put("testing", running).put("message", running ? "正在发送这一次测试请求…" : "上次测试未取得完整回执，结果未知；没有自动重试。");
            evidence.put("tested", "passed".equals(outcome)).put("message", stored.optString("message"));
            if (stored.optJSONObject("inferenceReference") != null)
                evidence.put("inferenceReference", new JSONObject(stored.getJSONObject("inferenceReference").toString()));
            return evidence;
        }
    }

    private static void requireTestReference(JSONObject reference) {
        if (reference == null || !"CONNECTION_TEST".equals(reference.optString("purpose"))
                || !reference.optBoolean("accountingOnly") || reference.optBoolean("executionAuthorized", true)
                || !uuid(reference.optString("taskId")) || !uuid(reference.optString("runId")) || reference.optInt("epoch") < 1)
            throw new IllegalArgumentException("缺少本次真实推理回执，未视为通过");
    }

    private static boolean uuid(String value) {
        return value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    }

    private JSONObject readState() throws Exception {
        requireConfirmed(prefs);
        if (!prefs.contains(STATE)) throw new IllegalStateException("首次使用状态尚未初始化，请重新打开应用");
        return checkedState(prefs.getString(STATE, ""));
    }

    private static JSONObject checkedState(String raw) throws Exception {
        JSONObject state = new JSONObject(raw);
        if (state.optInt("version") != 1) throw new IllegalStateException("首次使用设置版本不可读取，未覆盖原记录");
        integer(state.opt("revision"), "首次使用设置版本无效");
        JSONObject choices = state.getJSONObject("preferences");
        if (!STEPS.contains(requiredString(choices, "step")) || !STATUSES.contains(requiredString(choices, "status")))
            throw new IllegalStateException("首次使用进度不可读取，未重置原记录");
        checkedCapabilities(choices.getJSONArray("capabilities"));
        if (!(state.opt("vendorBackgroundVisited") instanceof Boolean)) throw new IllegalStateException("系统设置访问记录不可读取");
        return state;
    }

    private static JSONObject publicState(JSONObject stored) throws Exception {
        return new JSONObject().put("version", 1).put("revision", stored.getLong("revision"))
                .put("preferences", new JSONObject(stored.getJSONObject("preferences").toString()));
    }

    private static JSONArray checkedCapabilities(JSONArray values) throws Exception {
        if (values.length() > CAPABILITIES.size()) throw new IllegalArgumentException("首次使用能力选择过多");
        Set<String> unique = new LinkedHashSet<>();
        for (int index = 0; index < values.length(); index++) {
            Object value = values.get(index);
            if (!(value instanceof String) || !CAPABILITIES.contains(value) || !unique.add((String)value))
                throw new IllegalArgumentException("首次使用能力标识无效或重复");
        }
        return new JSONArray(unique);
    }

    private static String requiredString(JSONObject input, String name) throws Exception {
        Object value = input.get(name);
        if (!(value instanceof String)) throw new IllegalArgumentException("首次使用设置字段无效：" + name);
        return (String)value;
    }

    private static long integer(Object value, String message) {
        if (!(value instanceof Number)) throw new IllegalArgumentException(message);
        double exact = ((Number)value).doubleValue(); long number = ((Number)value).longValue();
        if (!Double.isFinite(exact) || exact != number || number < 1 || number > MAX_REVISION) throw new IllegalArgumentException(message);
        return number;
    }

    private static long nextRevision(long current) {
        if (current >= MAX_REVISION) throw new IllegalStateException("首次使用设置版本已超出可保存范围");
        return current + 1;
    }

    private static void requireConfirmed(SharedPreferences prefs) {
        if (UNCERTAIN.contains(prefs)) throw new IllegalStateException("首次使用设置写入结果尚未确认，请重新打开应用核对，未把内存值当作已保存");
    }

    private static void commit(SharedPreferences prefs, SharedPreferences.Editor editor) {
        requireConfirmed(prefs); UNCERTAIN.add(prefs);
        try { if (editor.commit()) { UNCERTAIN.remove(prefs); return; } }
        catch (RuntimeException unavailable) { throw new IllegalStateException("首次使用设置未取得可靠保存回执，请重新打开应用核对", unavailable); }
        throw new IllegalStateException("首次使用设置未取得可靠保存回执，请重新打开应用核对");
    }
}
