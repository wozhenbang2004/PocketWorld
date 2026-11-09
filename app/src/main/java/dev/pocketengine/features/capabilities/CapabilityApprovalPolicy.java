package dev.pocketengine.features.capabilities;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/** Host-owned approval preference snapshot; never an execution/resource grant. */
public final class CapabilityApprovalPolicy {
    public enum Decision { ALLOW, ASK, DENY }
    private final String mode;
    private final Map<String, Decision> tools = new HashMap<>();

    public CapabilityApprovalPolicy(JSONObject effective) {
        if (effective == null) { mode = "ask"; return; }
        if (!(effective.opt("version") instanceof Integer || effective.opt("version") instanceof Long)
                || ((Number) effective.opt("version")).longValue() != 1L)
            throw new IllegalArgumentException("Unsupported permission policy version");
        Object rawMode = effective.opt("mode");
        if (!(rawMode instanceof String)
                || !("ask".equals(rawMode) || "controlled".equals(rawMode) || "auto".equals(rawMode)))
            throw new IllegalArgumentException("Invalid permission policy mode");
        mode = (String) rawMode;
        Object rawTools = effective.opt("tools");
        if (rawTools != null && !(rawTools instanceof JSONObject))
            throw new IllegalArgumentException("Invalid permission tool rules");
        if (rawTools instanceof JSONObject) {
            JSONObject rules = (JSONObject) rawTools;
            Iterator<String> keys = rules.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.isEmpty() || !key.equals(key.trim()))
                    throw new IllegalArgumentException("Invalid permission tool name");
                Object value = rules.opt(key);
                Decision decision = "allow".equals(value) ? Decision.ALLOW
                        : "ask".equals(value) ? Decision.ASK
                        : "deny".equals(value) ? Decision.DENY : null;
                if (decision == null) throw new IllegalArgumentException("Invalid permission tool decision");
                tools.put(key, decision);
            }
        }
    }

    public void requireNotDenied(String tool) {
        if (tools.get(tool) == Decision.DENY)
            throw new SecurityException("能力已被用户权限策略禁止：" + tool);
    }

    public Decision decisionFor(CapabilityDescriptor tool) {
        Decision explicit = tools.get(tool.name);
        if (explicit != null) return explicit;
        if (!tool.approvalRequired) return Decision.ALLOW;
        if ("auto".equals(mode)) return Decision.ALLOW;
        // Only new private artifacts. Sharing/export and arbitrary filesystem writes are excluded.
        if ("controlled".equals(mode) && "attachments".equals(tool.feature)
                && (("attachments_create_text".equals(tool.name) && "createText".equals(tool.command))
                    || ("attachments_create_document".equals(tool.name) && "createDocument".equals(tool.command))))
            return Decision.ALLOW;
        return Decision.ASK;
    }
}
