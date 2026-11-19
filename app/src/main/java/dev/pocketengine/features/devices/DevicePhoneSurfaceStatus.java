package dev.pocketengine.features.devices;

import org.json.JSONArray;
import org.json.JSONObject;

/** Display-only metadata for an existing tool grant. Neither side requests a frame or opens a session. */
final class DevicePhoneSurfaceStatus {
    private static final String FORMAT = "pocketworld.phone-surface-readiness.v1";
    private DevicePhoneSurfaceStatus() {}

    // Receiver: disclose only preparation categories for the exact tools this particular caller already has.
    static JSONObject forCaller(PairingCoordinator.DiscoveryPermission caller, JSONObject grant, JSONObject binding,
                                JSONObject readiness, long now) throws Exception {
        if (caller == null || grant == null || binding == null || caller.deadline() <= now || grant.optLong("issuedAt") > now || grant.optLong("expiresAt") <= now
                || !caller.callerDeviceId().equals(grant.optString("peerDeviceId")) || !caller.targetDeviceId().equals(grant.optString("localDeviceId"))
                || caller.authorizationEpoch() != grant.optLong("authorizationEpoch")
                || !contains(grant, "allowedOrigins", caller.callerDeviceId()) || !contains(grant, "capabilities", "tool.invoke")) return null;
        boolean background = "background".equals(binding.optString("screenMode"));
        JSONArray tools = DeviceToolsRuntime.toolDeclarations(grant.getJSONArray("toolNames"), binding);
        boolean observe = tool(tools, "phone_observe"), launch = tool(tools, "phone_launch_app");
        boolean node = observe && (tool(tools, "phone_click_node") || tool(tools, "phone_type"));
        boolean keys = observe && (tool(tools, "phone_back") || !background && tool(tools, "phone_home"));
        boolean gesture = observe && (tool(tools, "phone_tap") || tool(tools, "phone_swipe"));
        boolean capture = observe && captureScope(grant, binding);
        if (tools.length() == 0) return null;
        JSONObject out = new JSONObject().put("format", FORMAT).put("grantId", grant.getString("grantId"))
                .put("authorizationEpoch", caller.authorizationEpoch()).put("grantDigest", digest(grant)).put("resourceDigest", digest(binding));
        String observation = group(readiness, background ? "backgroundExecution" : "mainObservation");
        String action = group(readiness, background ? "backgroundExecution" : "mainExecution");
        String input = background ? both(action, group(readiness, "backgroundInput")) : action;
        String image = capture ? both(observation, group(readiness, background ? "backgroundCapture" : "mainScreenshot")) : "outside";
        if (observe) out.put("read", observation);
        if (launch) out.put("launch", action);
        if (node) out.put("node", both(observation, action));
        if (keys) out.put("keys", both(observation, input));
        if (gesture && capture) out.put("gesture", both(image, input));
        if (capture) out.put("capture", image);
        return out;
    }

    // Sender: bind this authenticated-channel observation to one original, still current receipt and resource.
    static JSONArray forWorkspace(JSONObject manifest, PairingCoordinator.OutgoingGrant original,
                                  String localDeviceId, long now) throws Exception {
        JSONArray out = new JSONArray();
        if (manifest == null || localDeviceId == null || original.deadline() <= now
                || !original.targetDeviceId().equals(manifest.optString("deviceId")) || !original.targetInstanceId().equals(manifest.optString("instanceId"))
                || manifest.optLong("capturedAt") > now || manifest.optLong("validUntil") <= now) return out;
        JSONObject grant = original.grant();
        if (manifest.optLong("capturedAt") < grant.optLong("issuedAt") || !localDeviceId.equals(grant.optString("peerDeviceId"))
                || !original.targetDeviceId().equals(grant.optString("localDeviceId")) || !contains(grant, "allowedOrigins", localDeviceId)
                || !contains(grant, "capabilities", "tool.invoke")) return out;
        JSONArray caps = manifest.optJSONArray("capabilities"), resources = grant.getJSONArray("resources");
        for (String mode : new String[]{"main_screen", "background"}) {
            boolean background = "background".equals(mode); String id = background ? "phone.tools.background" : "phone.tools.main";
            JSONObject profile = null; boolean ambiguous = false;
            for (int i = 0; caps != null && i < caps.length(); i++) {
                JSONObject candidate = caps.optJSONObject(i);
                if (candidate != null && id.equals(candidate.optString("id"))) {
                    if (profile != null) { ambiguous = true; break; }
                    profile = candidate;
                }
            }
            if (ambiguous || profile == null || !DeviceProtocol.VERSION.equals(profile.opt("version")) || !mode.equals(profile.opt("screenMode"))
                    || !Boolean.TRUE.equals(profile.opt("implemented")) || !Boolean.TRUE.equals(profile.opt("installed"))
                    || !Boolean.TRUE.equals(profile.opt("authorized")) || !(profile.opt("busy") instanceof Boolean)) continue;
            JSONObject fact = profile.optJSONObject("phoneScopeFact");
            if (fact == null || !FORMAT.equals(fact.opt("format")) || !original.grantId().equals(fact.opt("grantId"))
                    || original.authorizationEpoch() != fact.optLong("authorizationEpoch", -1) || !digest(grant).equals(fact.opt("grantDigest"))) continue;
            JSONArray declared = profile.optJSONArray("toolDeclarations");
            for (int i = 0; i < resources.length(); i++) {
                JSONObject binding = resources.getJSONObject(i);
                if (!mode.equals(binding.opt("screenMode")) || !digest(binding).equals(fact.opt("resourceDigest"))) continue;
                JSONArray allowed = DeviceToolsRuntime.toolDeclarations(grant.getJSONArray("toolNames"), binding);
                boolean observe = tool(allowed, declared, "phone_observe");
                boolean capture = observe && captureScope(grant, binding);
                boolean busy = profile.getBoolean("busy");
                JSONObject row = new JSONObject().put("screenMode", mode).put("resourceIndex", i)
                        .put("validUntil", Math.min(original.deadline(), manifest.getLong("validUntil")));
                boolean node = tool(allowed, declared, "phone_click_node") || tool(allowed, declared, "phone_type");
                boolean keys = tool(allowed, declared, "phone_back") || !background && tool(allowed, declared, "phone_home");
                boolean gesture = tool(allowed, declared, "phone_tap") || tool(allowed, declared, "phone_swipe");
                row.put("read", status(fact, "read", observe, busy))
                        .put("launch", status(fact, "launch", tool(allowed, declared, "phone_launch_app"), busy))
                        .put("node", node && !observe ? "needs_observation_scope" : status(fact, "node", node, busy))
                        .put("keys", keys && !observe ? "needs_observation_scope" : status(fact, "keys", keys, busy))
                        .put("gesture", gesture && !observe ? "needs_observation_scope" : gesture && !capture ? "needs_capture_scope" : status(fact, "gesture", gesture, busy))
                        .put("capture", observe && Boolean.TRUE.equals(binding.opt("allowWholeDisplayCapture")) && !capture
                                ? "needs_artifact_scope" : status(fact, "capture", capture, busy));
                out.put(row); break;
            }
        }
        return out;
    }

    private static String group(JSONObject readiness, String key) {
        JSONObject group = readiness.optJSONObject(key);
        if (group == null) return "unknown";
        String state = group.optString("state");
        if ("ready".equals(state) && Boolean.TRUE.equals(group.opt("ready"))) return "ready";
        return "blocked".equals(state) || "unsupported".equals(state) ? state : "unknown";
    }
    private static String both(String a, String b) {
        if ("unsupported".equals(a) || "unsupported".equals(b)) return "unsupported";
        if ("blocked".equals(a) || "blocked".equals(b)) return "blocked";
        return "ready".equals(a) && "ready".equals(b) ? "ready" : "unknown";
    }
    private static String status(JSONObject fact, String key, boolean inScope, boolean busy) {
        if (!inScope) return "outside";
        String state = fact.optString(key);
        if (!("ready".equals(state) || "blocked".equals(state) || "unsupported".equals(state))) return "unknown";
        return busy ? "busy" : state;
    }
    private static boolean tool(JSONArray tools, String name) {
        for (int i = 0; tools != null && i < tools.length(); i++) {
            JSONObject item = tools.optJSONObject(i);
            if (item != null && name.equals(item.opt("toolName")) && DeviceProtocol.VERSION.equals(item.opt("capabilityVersion"))
                    && DeviceToolsRuntime.effect(name).equals(item.opt("effect"))) return true;
        }
        return false;
    }
    private static boolean tool(JSONArray granted, JSONArray declared, String name) { return tool(granted, name) && tool(declared, name); }
    private static boolean contains(JSONObject grant, String key, String name) { return PairingCoordinator.contains(grant.optJSONArray(key), name); }
    private static boolean captureScope(JSONObject grant, JSONObject binding) {
        return Boolean.TRUE.equals(binding.opt("allowWholeDisplayCapture")) && contains(grant, "capabilities", "artifact.describe")
                && contains(grant, "capabilities", "artifact.readChunk");
    }
    private static String digest(JSONObject value) { return DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(value)); }
}
