package dev.pocketengine.features.devices;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Initial pairing policy. The grant identifier preserves the owner's choice across revocation/rotation.
 * No public flag, client input or cached manifest selects the native disclosure policy. */
public final class DevicePairVisibility {
    private DevicePairVisibility() { }
    public static final String GRANT_PREFIX = "dpv1_";
    static final String OPTION = "pair_identity_only";
    private static final String LABEL = "仅配对 · 公开设备身份";
    private static final String PROJECTION = "initialPairingIdentityReceipt";

    static boolean marked(JSONObject grant) { return grant != null && grant.optString("grantId").startsWith(GRANT_PREFIX); }
    static String newGrantId() { return GRANT_PREFIX + UUID.randomUUID(); }
    static JSONObject option(long now) throws Exception {
        return new JSONObject().put("optionId", OPTION).put("label", LABEL)
                .put("capabilities", new JSONArray().put("device.describe")).put("toolNames", new JSONArray())
                .put("resources", new JSONArray().put(new JSONObject().put("screenMode", "capabilities")))
                .put("toolBudgetMin", 0).put("toolBudgetMax", 0).put("modelBudgetMin", 0).put("modelBudgetMax", 0)
                .put("expiresAtMax", now + 30L * 86_400_000).put("allowDelegation", false).put("maxDelegationDepth", 0)
                .put("canGrantWholeDisplayCapture", false).put("additionalOriginsAllowed", false)
                .put("historyReadExpiresAtMax", 0).put("historyReadMethods", new JSONArray());
    }
    static void requireChoice(NativeDeviceWorkspaceHost.GrantChoice choice) {
        if (!OPTION.equals(choice.optionId) || choice.toolBudget != 0 || choice.modelBudget != 0
                || choice.allowDelegation || choice.maxDelegationDepth != 0 || choice.wholeDisplayCapture
                || choice.historyReadExpiresAt != 0 || !choice.historyReadMethods.isEmpty()
                || !choice.additionalOriginOptionIds.isEmpty() || !choice.forwardingOptionIds.isEmpty()
                || choice.forwardingToolBudget != 0 || choice.forwardingModelBudget != 0)
            throw new SecurityException("仅配对不能同时授予工作、读取或转交权限");
    }
    static boolean identityOnly(JSONObject grant) {
        JSONArray methods = grant.optJSONArray("capabilities"), bindings = grant.optJSONArray("resources"),
                origins = grant.optJSONArray("allowedOrigins"), tools = grant.optJSONArray("toolNames");
        JSONObject binding = bindings == null || bindings.length() != 1 ? null : bindings.optJSONObject(0);
        return marked(grant) && methods != null && methods.length() == 1 && "device.describe".equals(methods.optString(0))
                && tools != null && tools.length() == 0 && binding != null && binding.length() == 1
                && "capabilities".equals(binding.optString("screenMode")) && origins != null && origins.length() == 1
                && grant.optString("peerDeviceId").equals(origins.optString(0))
                && Boolean.FALSE.equals(grant.opt("allowDelegation")) && grant.optInt("maxDelegationDepth", -1) == 0
                && grant.optLong("toolBudget", -1) == 0 && grant.optLong("modelBudget", -1) == 0
                && !grant.has("historyRead") && !grant.has("forwardingScope");
    }
    static JSONObject grantView(JSONObject view) throws Exception {
        return view.put("optionId", OPTION).put("label", LABEL);
    }
    private static SecurityException unavailable() { return new SecurityException("对端没有当前有效的设备披露授权"); }

    /** null means a legacy pair or a separately owner-confirmed, current working revision.
     * A missing/expired narrow authorization is not an invitation to collect ordinary diagnostics. */
    static JSONObject scopedSnapshot(DeviceWorkspaceNativeOwner owner, PairingCoordinator.DiscoveryPermission caller) throws Exception {
        if (caller == null || !owner.trust.hasInitialVisibilityPairing(caller.callerDeviceId())) return null;
        long now = System.currentTimeMillis(); caller.checkCurrent(now);
        JSONArray current = owner.trust.grantsForPeer(caller.callerDeviceId(), 2);
        if (current.length() != 1) throw unavailable();
        JSONObject grant = current.getJSONObject(0);
        if (!marked(grant) || !Boolean.FALSE.equals(grant.opt("revoked")) || grant.optLong("authorizationEpoch") != caller.authorizationEpoch())
            throw unavailable();
        PairingCoordinator.validateGrant(grant, caller.targetDeviceId(), caller.callerDeviceId(), now);
        if (!identityOnly(grant)) {
            // The regular "adjust permissions" owner review retains its grantId and installs the next epoch.
            // A broad-looking first grant, a retained expired revision, or an ambiguous policy never qualifies.
            JSONArray methods = grant.getJSONArray("capabilities");
            if (caller.authorizationEpoch() > 1 && PairingCoordinator.contains(methods, "device.describe")
                    && (PairingCoordinator.contains(methods, "tool.invoke") || PairingCoordinator.contains(methods, "task.submitGoal")))
                return null;
            throw unavailable();
        }
        DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        JSONArray receipts = owner.pairing().discoveryGrantReceipts(local, caller, now);
        if (receipts.length() != 1) throw unavailable();
        JSONObject signed = receipts.getJSONObject(0), body = signed.getJSONObject("body");
        if (!"PairGrantReceipt".equals(signed.optString("messageType")) || !local.deviceId().equals(signed.optString("issuerDeviceId"))
                || !local.certificateFingerprint().equals(signed.optString("issuerCertificateFingerprint"))
                || !local.instanceId().equals(body.optString("targetInstanceId"))
                || !DeviceProtocol.canonical(grant).equals(DeviceProtocol.canonical(body.getJSONObject("grant")))) throw unavailable();
        return new JSONObject().put(PROJECTION, signed);
    }
    static JSONObject description(JSONObject snapshot, PairingCoordinator.DiscoveryPermission caller,
                                  String device, String instance, long now) throws Exception {
        if (!snapshot.has(PROJECTION)) return null;
        if (caller == null || snapshot.length() != 1) throw unavailable();
        caller.checkCurrent(now);
        JSONObject signed = snapshot.getJSONObject(PROJECTION), body = signed.getJSONObject("body"), grant = body.getJSONObject("grant");
        if (!identityOnly(grant) || !caller.callerDeviceId().equals(grant.optString("peerDeviceId"))
                || grant.optLong("authorizationEpoch") != caller.authorizationEpoch() || !device.equals(grant.optString("localDeviceId"))
                || !device.equals(signed.optString("issuerDeviceId")) || !instance.equals(body.optString("targetInstanceId"))) throw unavailable();
        long deadline = Math.min(Math.min(now + 5_000L, caller.deadline()), grant.getLong("expiresAt"));
        if (deadline <= now) throw unavailable();
        JSONObject description = DeviceProtocol.deviceDescription(device, instance, now, deadline, new JSONArray(),
                new JSONArray().put(signed), new JSONObject().put("disclosureScope", OPTION));
        long releasing = System.currentTimeMillis(); if (releasing >= deadline) throw unavailable();
        caller.checkCurrent(releasing);
        return description;
    }
}
