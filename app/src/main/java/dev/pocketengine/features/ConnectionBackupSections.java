package dev.pocketengine.features;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Closed, non-secret configuration format. No account, execution, trust or network authority. */
public final class ConnectionBackupSections {
    private ConnectionBackupSections() { }
    public static String format(String module) { if (!module.matches("remote|ssh")) throw new IllegalArgumentException("Unknown native connection section"); return "pocketworld." + module + "-connections.v1"; }
    public static JSONObject section(String module, JSONArray profiles) throws Exception {
        return new JSONObject().put("format", format(module)).put("version", 1).put("profiles", new JSONArray(profiles.toString()))
                .put("secretsIncluded", false).put("executionGrantsIncluded", false).put("hostTrustIncluded", false);
    }
    public static JSONObject checked(String module, JSONObject exact) throws Exception {
        if (exact == null || exact.toString().length() > 100000) throw new SecurityException("连接归档段超出本机范围");
        keys(exact, "format", "version", "profiles", "secretsIncluded", "executionGrantsIncluded", "hostTrustIncluded");
        if (!format(module).equals(exact.optString("format")) || !(exact.opt("version") instanceof Number) || ((Number) exact.get("version")).doubleValue() != 1
                || !Boolean.FALSE.equals(exact.opt("secretsIncluded")) || !Boolean.FALSE.equals(exact.opt("executionGrantsIncluded")) || !Boolean.FALSE.equals(exact.opt("hostTrustIncluded"))) throw new SecurityException("归档不是此原生非秘密连接格式；未恢复凭据、授权或主机信任");
        JSONArray rows = exact.getJSONArray("profiles"); if (rows.length() > 20) throw new IllegalArgumentException("连接归档最多20项"); Set<String> seen = new HashSet<>();
        for (int n = 0; n < rows.length(); n++) {
            JSONObject row = rows.getJSONObject(n);
            if (module.equals("remote")) keys(row, "sourceProfileId", "name", "baseUrl");
            else keys(row, "sourceProfileId", "name", "host", "port", "username", "workspace", "authType", "previousHostFingerprint");
            String id = text(row, "sourceProfileId", 80); if (!id.matches("[A-Za-z0-9_-]{1,80}") || !seen.add(id)) throw new SecurityException("原连接编号缺失或重复"); text(row, "name", 80);
            if (module.equals("remote")) dev.pocketengine.features.remote.RemoteTransport.validateBase(text(row, "baseUrl", 2048));
            else {
                JSONObject profile = new JSONObject(row.toString()).put("id", id); dev.pocketengine.features.ssh.SshProfile parsed = new dev.pocketengine.features.ssh.SshProfile(profile);
                for (String key : new String[]{"name", "host", "username", "workspace", "authType"}) if (!parsed.document().getString(key).equals(row.getString(key))) throw new SecurityException("归档SSH目标不是已规范化原配置，请核对后重导出");
                Object fingerprint = row.opt("previousHostFingerprint"); if (!(fingerprint instanceof String) || !((String) fingerprint).isEmpty() && !((String) fingerprint).matches("SHA256:[A-Za-z0-9+/]{43}")) throw new SecurityException("旧指纹仅供对照，格式无效时不恢复");
            }
        }
        return new JSONObject(exact.toString());
    }
    public static String action(String value) { if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new SecurityException("需要原生备份恢复动作编号"); return value; }
    public static String digest(JSONObject value) throws Exception { return dev.pocketengine.store.TaskStore.capabilityFingerprint(value); }
    public static String restoredId(String module, String action, String digest, String oldId) { return UUID.nameUUIDFromBytes(("pocketworld/native-connection-restore/v1/" + module + "\n" + action(action) + "\n" + digest + "\n" + oldId).getBytes(StandardCharsets.UTF_8)).toString(); }
    public static JSONObject origin(String action, String sectionDigest, String sourceId, String configDigest) throws Exception { return new JSONObject().put("format", "pocketworld.native-connection-origin.v1").put("ownerActionId", action(action)).put("sectionDigest", sectionDigest).put("sourceProfileId", sourceId).put("desiredConfigHash", configDigest); }
    public static JSONObject reviewMetadata(String module, String action, JSONObject section) throws Exception {
        return new JSONObject().put("module", module).put("ownerActionId", action).put("sectionDigest", digest(section)).put("count", section.getJSONArray("profiles").length()).put("profiles", new JSONArray(section.getJSONArray("profiles").toString()))
                .put("newConnectionIds", true).put("credentialsRestored", false).put("hostKeyTrustRestored", false).put("executionGrantsRestored", false).put("automaticConnection", false);
    }
    private static String text(JSONObject value, String field, int max) throws Exception { Object raw = value.opt(field); if (!(raw instanceof String) || ((String) raw).trim().isEmpty() || ((String) raw).length() > max) throw new SecurityException("归档连接字段无效：" + field); String text = (String) raw; if (!text.equals(text.trim())) throw new SecurityException("归档字段前后空白不规范"); for (int n = 0; n < text.length(); n++) if (text.charAt(n) < 32 || text.charAt(n) == 127) throw new SecurityException("归档连接含控制字符"); return text; }
    private static void keys(JSONObject value, String... allowed) throws Exception { Set<String> expected = new HashSet<>(java.util.Arrays.asList(allowed)); for (java.util.Iterator<String> keys = value.keys(); keys.hasNext();) if (!expected.remove(keys.next())) throw new SecurityException("归档连接含非白名单字段；不恢复凭据或授权"); if (!expected.isEmpty()) throw new SecurityException("归档连接缺少完整格式字段"); }
}
