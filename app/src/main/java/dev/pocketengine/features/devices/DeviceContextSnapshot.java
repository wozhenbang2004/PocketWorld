package dev.pocketengine.features.devices;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A bounded, explicitly selected data snapshot, never executable instructions or ambient memory. */
public final class DeviceContextSnapshot {
    private DeviceContextSnapshot() { }
    public static JSONObject validate(JSONObject source, String originDeviceId) throws Exception {
        if (source == null) return new JSONObject().put("format", "pocketworld.device-context.v1")
                .put("originDeviceId", originDeviceId).put("summary", "").put("artifacts", new JSONArray())
                .put("retention", "task_only").put("untrusted", true).put("authorityTransferred", false);
        if (source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32_768)
            throw new SecurityException("所选上下文超出接收上限");
        Set<String> allowed = Set.of("format", "originDeviceId", "snapshotId", "revision", "summary", "artifacts", "constraints", "retention");
        for (String key : DeviceProtocol.objectKeys(source)) if (!allowed.contains(key)) throw new SecurityException("上下文包含未选择或敏感字段");
        if (!"pocketworld.device-context.v1".equals(source.optString("format"))
                || !originDeviceId.equals(source.optString("originDeviceId")) || !"task_only".equals(source.optString("retention"))
                || source.optString("snapshotId").isEmpty() || source.optLong("revision") < 1
                || source.optString("summary").length() > 8_000) throw new SecurityException("上下文来源、版本或保留策略无效");
        JSONArray artifacts = source.optJSONArray("artifacts");
        if (artifacts == null || artifacts.length() > 32) throw new SecurityException("所选文件清单无效");
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject ref = artifacts.getJSONObject(i);
            for (String key : DeviceProtocol.objectKeys(ref)) if (!Set.of("artifactId", "deviceId", "taskId", "sha256", "size", "version").contains(key))
                throw new SecurityException("文件引用不能包含路径、授权或运行句柄");
            if (!originDeviceId.equals(ref.optString("deviceId")) || ref.optString("artifactId").isEmpty()
                    || ref.optString("taskId").isEmpty() || !ref.optString("sha256").matches("[a-f0-9]{64}")
                    || ref.optLong("size", -1) < 0 || ref.optLong("version") < 1) throw new SecurityException("所选文件内容身份无效");
        }
        JSONArray constraints = source.optJSONArray("constraints");
        if (constraints != null) {
            if (constraints.length() > 32) throw new SecurityException("约束过多");
            for (int i = 0; i < constraints.length(); i++) if (!(constraints.get(i) instanceof String) || constraints.getString(i).length() > 1_000)
                throw new SecurityException("约束只接受有界数据文本");
        }
        return new JSONObject(source.toString()).put("untrusted", true).put("authorityTransferred", false);
    }
}
