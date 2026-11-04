package dev.pocketengine.features.controlplane;

import dev.pocketengine.runtime.models.ModelTargetRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** References to the original stores, not a credential, permission registry, or executable recipe. */
public final class WorkProfile {
    public static final String FORMAT = "pocketworld.work-profile.v1";
    public static final int MAX_PROFILES = 32, MAX_BYTES = 65536, MAX_ASSETS = 32;
    public final String id, name;
    public final long revision;
    public final boolean restoredPendingReview;
    private final String json;

    private WorkProfile(JSONObject value) throws Exception {
        fields(value, "format", "profileId", "revision", "name", "modelPolicies", "instructionRefs", "skillRefs", "pluginRefs", "extensionRefs", "targetBindings", "teamTemplateRef", "resourcePreferences", "restoredPendingReview", "restoredReferences");
        if (!FORMAT.equals(value.getString("format"))) throw new IllegalArgumentException("unsupported_work_profile_format");
        id = identifier(value, "profileId", 100); revision = integer(value, "revision", 1, Long.MAX_VALUE);
        name = text(value, "name", 80);
        if(value.has("restoredPendingReview")&&!(value.get("restoredPendingReview") instanceof Boolean))throw new IllegalArgumentException("explicit_restore_review_state_required");
        restoredPendingReview=value.optBoolean("restoredPendingReview",false);
        if(restoredPendingReview&&!value.has("restoredReferences"))throw new IllegalArgumentException("restored_reference_review_required");
        if(value.has("restoredReferences"))checkRestoredReferences(value.getJSONArray("restoredReferences"));
        JSONObject models = value.getJSONObject("modelPolicies");
        if (models.length() > 20) throw new IllegalArgumentException("too_many_profile_model_roles");
        for (String purpose : keys(models)) {
            if (!purpose.matches("MAIN|SUMMARY|RELAY|MEMBER(?:\\.[A-Za-z0-9_-]{1,60})?")) throw new IllegalArgumentException("invalid_profile_model_purpose");
            ModelTargetRef.fromJson(models.getJSONObject(purpose));
        }
        skillRefs(value.getJSONArray("instructionRefs")); skillRefs(value.getJSONArray("skillRefs"));
        JSONArray plugins = bounded(value, "pluginRefs"); Set<String> seen = new HashSet<>();
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject row = plugins.getJSONObject(i);
            fields(row, "id", "installationId", "stateRevision", "manifestDigest", "tools", "optional");
            if (!seen.add(identifier(row, "id", 80))) throw new IllegalArgumentException("duplicate_profile_plugin");
            identifier(row, "installationId", 160); integer(row, "stateRevision", 0, Long.MAX_VALUE); digest(row, "manifestDigest"); optional(row);
            JSONArray tools = row.getJSONArray("tools"); if (tools.length() > 64) throw new IllegalArgumentException("too_many_profile_tools");
            Set<String> names = new HashSet<>();
            for (int n = 0; n < tools.length(); n++) {
                Object actual = tools.get(n);
                if (!(actual instanceof String s) || !s.matches("[A-Za-z_][A-Za-z0-9_.-]{0,99}") || !names.add(s)) throw new IllegalArgumentException("invalid_profile_tool_selection");
            }
        }
        JSONArray extensions = bounded(value, "extensionRefs"); seen.clear();
        if (extensions.length() > 16) throw new IllegalArgumentException("too_many_profile_extensions");
        for (int i = 0; i < extensions.length(); i++) {
            JSONObject row = extensions.getJSONObject(i);
            fields(row, "id", "installationId", "version", "approvalRevision", "sourceSha256", "optional");
            if (!seen.add(identifier(row, "id", 80))) throw new IllegalArgumentException("duplicate_profile_extension");
            identifier(row, "installationId", 160); text(row, "version", 32); integer(row, "approvalRevision", 1, Long.MAX_VALUE); digest(row, "sourceSha256"); optional(row);
        }
        JSONArray targets = bounded(value, "targetBindings"); seen.clear();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject row = targets.getJSONObject(i); String kind = row.getString("kind");
            if (kind.equals("remote_harness")) {
                fields(row, "kind", "profileId", "profileBinding", "environmentId", "environmentBinding", "worker", "optional");
                identifier(row, "profileId", 80); digest(row, "profileBinding"); identifier(row, "environmentId", 80); digest(row, "environmentBinding"); identifier(row, "worker", 80);
            } else if (kind.equals("ssh")) {
                fields(row, "kind", "profileId", "bindingHash", "optional"); identifier(row, "profileId", 80); digest(row, "bindingHash");
            } else if (kind.equals("local_runtime")) {
                fields(row, "kind", "packageId", "identity", "optional"); identifier(row, "packageId", 80); digest(row, "identity");
            } else throw new IllegalArgumentException("unsupported_profile_target_kind");
            if (!seen.add(targetKey(row))) throw new IllegalArgumentException("duplicate_profile_target"); optional(row);
        }
        if (!value.isNull("teamTemplateRef")) {
            JSONObject row = value.getJSONObject("teamTemplateRef"); fields(row, "id", "digest", "optional"); identifier(row, "id", 100); digest(row, "digest"); optional(row);
        }
        JSONObject resources = value.getJSONObject("resourcePreferences"); fields(resources, "maxConcurrentModelCalls");
        if (resources.has("maxConcurrentModelCalls")) integer(resources, "maxConcurrentModelCalls", 1, 2);
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("work_profile_too_large");
        json = value.toString();
    }
    public static WorkProfile fromJson(JSONObject value) throws Exception { return new WorkProfile(value); }
    public static JSONObject definition(String id, long revision, String name) throws Exception {
        return new JSONObject().put("format", FORMAT).put("profileId", id).put("revision", revision).put("name", name)
                .put("modelPolicies", new JSONObject()).put("instructionRefs", new JSONArray()).put("skillRefs", new JSONArray())
                .put("pluginRefs", new JSONArray()).put("extensionRefs", new JSONArray()).put("targetBindings", new JSONArray())
                .put("teamTemplateRef", JSONObject.NULL).put("resourcePreferences", new JSONObject());
    }
    public JSONObject toJson() throws Exception { return new JSONObject(json); }
    public String digest() throws Exception { return fingerprint(toJson()); }
    /** A restored definition is editable evidence, never an execution choice or a recovered grant. */
    public void requireExecutable(){if(restoredPendingReview)throw new SecurityException("这份恢复的工作配置尚未重新绑定本机来源，请先在主人页审阅并确认；未启动任务");}
    private static void checkRestoredReferences(JSONArray rows)throws Exception {
        if(rows.length()>200)throw new IllegalArgumentException("too_many_restored_profile_references");
        Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);fields(row,"kind","reference");String kind=row.getString("kind");JSONObject reference=row.getJSONObject("reference");
            if(!seen.add(kind+":"+fingerprint(reference)))throw new IllegalArgumentException("duplicate_restored_profile_reference");
            // Validate historical data with the same strict public-reference schema. It is never read by a resolver.
            JSONObject isolated=definition("historical-reference",1,"待核对原引用");
            if(kind.startsWith("model:"))isolated.getJSONObject("modelPolicies").put(kind.substring(6),reference);
            else if(Set.of("instructionRefs","skillRefs","pluginRefs","extensionRefs","targetBindings").contains(kind))isolated.getJSONArray(kind).put(reference);
            else if(kind.equals("teamTemplateRef"))isolated.put(kind,reference);
            else throw new IllegalArgumentException("unsupported_restored_profile_reference");
            fromJson(isolated);
        }
    }
    /** Display/duplicate key derived from original references, never a new target registry identity. */
    public static String targetKey(JSONObject row) throws Exception {
        return switch (row.getString("kind")) {
            case "remote_harness" -> "remote_harness:" + row.getString("profileId") + "/" + row.getString("environmentId") + "/" + row.getString("worker");
            case "ssh" -> "ssh:" + row.getString("profileId");
            case "local_runtime" -> "local_runtime:" + row.getString("packageId");
            default -> throw new IllegalArgumentException("unsupported_profile_target_kind");
        };
    }
    private static void skillRefs(JSONArray rows) throws Exception {
        if (rows.length() > MAX_ASSETS) throw new IllegalArgumentException("too_many_profile_skills"); Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); fields(row, "id", "revision", "digest", "optional");
            if (!ids.add(identifier(row, "id", 100))) throw new IllegalArgumentException("duplicate_profile_skill");
            integer(row, "revision", 1, Integer.MAX_VALUE); digest(row, "digest"); optional(row);
        }
    }
    private static JSONArray bounded(JSONObject root, String key) throws Exception {
        JSONArray rows = root.getJSONArray(key); if (rows.length() > MAX_ASSETS) throw new IllegalArgumentException("too_many_profile_assets"); return rows;
    }
    private static void optional(JSONObject row) throws Exception { if (!(row.get("optional") instanceof Boolean)) throw new IllegalArgumentException("explicit_optional_boolean_required"); }
    static void fields(JSONObject row, String... allowed) {
        Set<String> fields = Set.of(allowed); for (String key : keys(row)) if (!fields.contains(key)) throw new IllegalArgumentException("unsupported_profile_field:" + key);
    }
    static long integer(JSONObject row, String key, long min, long max) throws Exception {
        Object v = row.get(key); if (!(v instanceof Integer || v instanceof Long) || ((Number)v).longValue() < min || ((Number)v).longValue() > max) throw new IllegalArgumentException("invalid_profile_integer:" + key); return ((Number)v).longValue();
    }
    static String identifier(JSONObject row, String key, int max) throws Exception {
        String value = text(row, key, max); if (!value.matches("[A-Za-z0-9_.:@/-]+")) throw new IllegalArgumentException("invalid_profile_identity:" + key); return value;
    }
    static String text(JSONObject row, String key, int max) throws Exception {
        Object raw = row.get(key); if (!(raw instanceof String value) || value.isBlank() || value.length() > max || value.codePoints().anyMatch(c -> Character.isISOControl(c))) throw new IllegalArgumentException("invalid_profile_text:" + key); return value;
    }
    static String digest(JSONObject row, String key) throws Exception {
        String value = row.getString(key); if (!value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid_profile_digest:" + key); return value;
    }
    static Set<String> keys(JSONObject value) { Set<String> keys = new TreeSet<>(); value.keys().forEachRemaining(keys::add); return keys; }
    /** Stable content digest. Callers pass only explicit public reference data, never settings/credentials. */
    public static String fingerprint(Object value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return hex.toString();
    }
    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject row) {
            StringBuilder result = new StringBuilder("{"); boolean first = true;
            for (String key : keys(row)) { if (!first) result.append(','); first = false; result.append(JSONObject.quote(key)).append(':').append(canonical(row.get(key))); } return result.append('}').toString();
        }
        if (value instanceof JSONArray rows) {
            StringBuilder result = new StringBuilder("["); for (int i = 0; i < rows.length(); i++) { if (i > 0) result.append(','); result.append(canonical(rows.get(i))); } return result.append(']').toString();
        }
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String text) return JSONObject.quote(text);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number number) return JSONObject.numberToString(number);
        throw new IllegalArgumentException("unsupported_profile_value");
    }
}
