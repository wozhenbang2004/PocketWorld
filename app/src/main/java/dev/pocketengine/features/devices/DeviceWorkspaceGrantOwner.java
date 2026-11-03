package dev.pocketengine.features.devices;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local owner-only grant selection over the ORIGINAL trust and live native downstream handles.
 * OptionIds/amounts are input, never permissions. No wire carrier, database or executor is added. */
final class DeviceWorkspaceGrantOwner {
    private final DeviceWorkspaceNativeOwner owner;
    DeviceWorkspaceGrantOwner(DeviceWorkspaceNativeOwner owner) { this.owner = owner; }
    private static final class Forward {
        final PairingCoordinator.OutgoingGrant grant; final JSONObject resource; final JSONArray tools, capabilities;
        Forward(PairingCoordinator.OutgoingGrant grant, JSONObject resource, JSONArray tools, JSONArray capabilities) throws Exception {
            this.grant = grant; this.resource = new JSONObject(resource.toString()); this.tools = new JSONArray(tools.toString()); this.capabilities = new JSONArray(capabilities.toString());
        }
    }
    private static final class Catalog {
        final Map<String, JSONObject> origins = new LinkedHashMap<>(); final Map<String, Forward> forwards = new LinkedHashMap<>();
        boolean partialOrigins, partialForwards;
    }
    /** Private construction: the UI only ever sees the safe view and an enclosing native action. */
    static final class Review {
        private final DeviceWorkspaceGrantOwner source; private final JSONObject grant; private final Map<String, JSONObject> origins; private final List<PairingCoordinator.OutgoingGrant> forwards;
        private final String localId, instance;
        private Review(DeviceWorkspaceGrantOwner source, JSONObject grant, Map<String, JSONObject> origins, List<PairingCoordinator.OutgoingGrant> forwards) throws Exception {
            this.source = source; this.grant = new JSONObject(grant.toString()); this.origins = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(origins)); this.forwards = java.util.Collections.unmodifiableList(new ArrayList<>(forwards));
            DeviceIdentity.LocalIdentity local = source.owner.existingIdentity(); localId = local.deviceId(); instance = local.instanceId();
        }
        JSONObject grant() throws Exception { return new JSONObject(grant.toString()); }
        void requireCurrent() throws Exception {
            DeviceIdentity.LocalIdentity local = source.owner.existingIdentity(); long now = System.currentTimeMillis();
            if (local == null || !localId.equals(local.deviceId()) || !instance.equals(local.instanceId()) || grant.getLong("expiresAt") <= now) throw new SecurityException("本机身份或已核对授权已变化，请重新选择");
            for (JSONObject expected : origins.values()) {
                JSONObject actual = source.owner.trust.peer(expected.getString("deviceId"));
                if (actual == null || actual.optBoolean("revoked") || !expected.getString("fingerprint").equals(actual.optString("fingerprint")) || !expected.getString("instanceId").equals(actual.optString("instanceId"))) throw new SecurityException("所选转交来源已撤销或身份已变化");
            }
            for (PairingCoordinator.OutgoingGrant downstream : forwards) downstream.checkCurrent(now);
        }
    }
    JSONObject inputs(String peerId) throws Exception {
        Catalog catalog = catalog(peerId); JSONArray options = owner.localGrantOptions();
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.getJSONObject(i); boolean goal = PairingCoordinator.contains(option.getJSONArray("capabilities"), "task.submitGoal");
            option.put("allowDelegation", goal && !catalog.forwards.isEmpty()).put("maxDelegationDepth", "schedule_drafts".equals(option.optString("optionId"))?0:2);
        }
        JSONArray origins = new JSONArray(), forwards = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : catalog.origins.entrySet()) { JSONObject peer = entry.getValue(); origins.put(new JSONObject().put("optionId", entry.getKey()).put("deviceId", peer.getString("deviceId")).put("label", label(peer.getString("deviceId"))).put("certificateFingerprint", peer.getString("fingerprint"))); }
        for (Map.Entry<String, Forward> entry : catalog.forwards.entrySet()) { Forward item = entry.getValue(); forwards.put(new JSONObject().put("optionId", entry.getKey()).put("targetDeviceId", item.grant.targetDeviceId()).put("label", label(item.grant.targetDeviceId())).put("resourceBinding", item.resource).put("toolNames", item.tools).put("capabilities", item.capabilities).put("toolBudgetMax", item.grant.grant().getLong("toolBudget")).put("modelBudgetMax", item.grant.grant().getLong("modelBudget")).put("expiresAtMax", item.grant.deadline())); }
        return new JSONObject().put("grantOptions", options).put("originOptions", origins).put("forwardingOptions", forwards).put("hasMoreOriginOptions", catalog.partialOrigins).put("hasMoreForwardingOptions", catalog.partialForwards);
    }
    JSONObject decorate(JSONObject view, String peerId) throws Exception { JSONObject inputs = inputs(peerId); for (String key : DeviceProtocol.objectKeys(inputs)) view.put(key, inputs.get(key)); return view; }
    private Catalog catalog(String peerId) throws Exception {
        Catalog result = new Catalog(); DeviceIdentity.LocalIdentity local = owner.existingIdentity();
        JSONObject snapshot = owner.trust.workspaceSnapshot(System.currentTimeMillis()); result.partialOrigins = snapshot.optBoolean("hasMore"); JSONArray peers = snapshot.getJSONArray("devices");
        for (int i = 0; i < peers.length(); i++) {
            JSONObject safe = peers.getJSONObject(i); String id = safe.getString("deviceId"); if (safe.optBoolean("revoked") || id.equals(peerId) || local != null && id.equals(local.deviceId())) continue;
            JSONObject original = owner.trust.peer(id); if (original == null || original.optBoolean("revoked")) continue;
            try { DeviceIdentity.certificate(java.util.Base64.getDecoder().decode(original.getString("certificateDer"))).checkValidity(); } catch (java.security.cert.CertificateException expired) { continue; }
            String option = "origin_" + DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONObject().put("deviceId", id).put("fingerprint", original.getString("fingerprint")).put("instanceId", original.getString("instanceId")))); result.origins.put(option, original);
        }
        result.partialForwards = owner.network.hasMoreOutgoingGrants();
        // The original phone-domain producer owns capability/version/effect matching. It emits
        // private native RouteOptions, not UI JSON, and never constructs a second task/Runner.
        for (NativeDeviceTaskScopeHost.RouteOption route : owner.taskScopeHost().forwardingOptions()) {
            PairingCoordinator.OutgoingGrant grant = route.receivingGrant(); JSONObject body = grant.grant();
            if (grant.targetDeviceId().equals(peerId) || !PairingCoordinator.contains(body.getJSONArray("allowedOrigins"), peerId) || !body.optBoolean("allowDelegation") || body.optInt("maxDelegationDepth") < 2) continue;
            JSONObject resource = route.resourceBinding();
            String option = "forward_" + DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONObject().put("targetDeviceId", grant.targetDeviceId()).put("resourceBinding", resource).put("grantId", grant.grantId()).put("epoch", grant.authorizationEpoch())));
            if (!result.forwards.containsKey(option) && result.forwards.size() >= 16) { result.partialForwards = true; continue; }
            Set<String> tools = new LinkedHashSet<>(), methods = new LinkedHashSet<>(); Forward previous = result.forwards.get(option);
            if (previous != null) { for (int i = 0; i < previous.tools.length(); i++) tools.add(previous.tools.getString(i)); for (int i = 0; i < previous.capabilities.length(); i++) methods.add(previous.capabilities.getString(i)); }
            tools.addAll(route.toolEffects().keySet()); methods.add(route.kind().equals("goal") ? "task.submitGoal" : "tool.invoke");
            result.forwards.put(option, new Forward(grant, resource, new JSONArray(tools), new JSONArray(methods)));
        }
        return result;
    }
    Review review(String peerId, NativeDeviceWorkspaceHost.GrantChoice choice) throws Exception {
        // Local resource/options resolve independently; extras below cannot create local capability.
        NativeDeviceWorkspaceHost.GrantChoice local = new NativeDeviceWorkspaceHost.GrantChoice(choice.optionId, choice.toolBudget, choice.modelBudget, choice.expiresAt, false, 0, choice.historyReadExpiresAt, choice.historyReadMethods, java.util.Collections.emptyList(), java.util.Collections.emptyList(), 0, 0, choice.wholeDisplayCapture);
        JSONObject grant = owner.reviewLocalGrantFor(peerId, local); Catalog catalog = catalog(peerId); Map<String, JSONObject> origins = new LinkedHashMap<>(); JSONArray allowed = grant.getJSONArray("allowedOrigins");
        if(PairingCoordinator.contains(grant.getJSONArray("toolNames"),DeviceScheduleDrafts.PROPOSE)&&(!choice.additionalOriginOptionIds.isEmpty()||!choice.forwardingOptionIds.isEmpty()))throw new SecurityException("计划草稿只授予本次直接配对设备，不接受转交或下游范围");
        for (String id : choice.additionalOriginOptionIds) { JSONObject peer = catalog.origins.get(id); if (peer == null) throw new SecurityException("转交来源不再是本机已认证的选项"); origins.put(id, peer); allowed.put(peer.getString("deviceId")); }
        List<PairingCoordinator.OutgoingGrant> nativeGrants = new ArrayList<>();
        if (!choice.forwardingOptionIds.isEmpty()) {
            if (!PairingCoordinator.contains(grant.getJSONArray("capabilities"), "task.submitGoal") || choice.toolBudget < 1 || choice.modelBudget < 1 || choice.forwardingToolBudget + choice.forwardingModelBudget < 1) throw new SecurityException("继续委派需要本机目标处理、协调工具和有限次数");
            JSONArray pairs = new JSONArray(); Set<String> tools = new LinkedHashSet<>(), methods = new LinkedHashSet<>();
            for (String id : choice.forwardingOptionIds) {
                Forward target = catalog.forwards.get(id); if (target == null) throw new SecurityException("下游设备或来源授权已变化，请重新核对"); target.grant.checkCurrent(System.currentTimeMillis()); nativeGrants.add(target.grant);
                if (choice.expiresAt > target.grant.deadline() || choice.forwardingToolBudget > target.grant.grant().getLong("toolBudget") || choice.forwardingModelBudget > target.grant.grant().getLong("modelBudget")) throw new SecurityException("继续委派的次数或期限超过所选下游授权");
                pairs.put(new JSONObject().put("targetDeviceId", target.grant.targetDeviceId()).put("resourceBinding", target.resource));
                for (int i = 0; i < target.tools.length(); i++) tools.add(target.tools.getString(i)); for (int i = 0; i < target.capabilities.length(); i++) methods.add(target.capabilities.getString(i));
            }
            grant.put("forwardingScope", new JSONObject().put("targetResources", pairs).put("capabilities", new JSONArray(methods)).put("toolNames", new JSONArray(tools)).put("toolBudget", choice.forwardingToolBudget).put("modelBudget", choice.forwardingModelBudget).put("maxDelegationDepth", 2));
            JSONArray localTools = grant.getJSONArray("toolNames"), routes = DeviceCapabilityRegistry.supportedDeviceGoalToolNames(); for (int i = 0; i < routes.length(); i++) if (!PairingCoordinator.contains(localTools, routes.getString(i))) localTools.put(routes.getString(i));
        }
        boolean delegated = !origins.isEmpty() || !choice.forwardingOptionIds.isEmpty();
        if (choice.allowDelegation != delegated || delegated && choice.maxDelegationDepth != 2) throw new SecurityException("转交来源和继续委派必须与本机选择完全一致");
        grant.put("allowDelegation", delegated).put("maxDelegationDepth", delegated ? 2 : 0); PairingCoordinator.validateGrant(grant, grant.getString("localDeviceId"), peerId, System.currentTimeMillis());
        return new Review(this, grant, origins, nativeGrants);
    }
    JSONObject view(Review review) throws Exception { JSONObject grant = review.grant(); JSONObject safe = owner.localGrantView(grant); safe.put("allowedOriginSummary", "允许来源：" + labels(grant.getJSONArray("allowedOrigins"))); return safe; }
    NativeDeviceWorkspaceHost.GrantReviewAction edit(String peerId) throws Exception {
        DeviceIdentity.LocalIdentity local = owner.existingIdentity(); if (local == null) throw new SecurityException("本机身份已丢失，旧权限不能继承，请重新核对配对");
        JSONObject peer = owner.trust.peer(peerId); if (peer == null || peer.optBoolean("revoked")) throw new SecurityException("这台设备的信任已失效，不能修改旧授权");
        JSONArray grants = owner.trust.grantsForPeer(peerId, 16); JSONObject current = null;
        for (int i = 0; i < grants.length(); i++) { JSONObject grant = grants.getJSONObject(i); if (!grant.optBoolean("revoked") && grant.optLong("authorizationEpoch") == peer.getLong("authorizationEpoch")) { if (current != null) throw new SecurityException("这台设备有多份当前授权，需要分别核对后才能修改"); current = grant; } }
        if (current == null || !local.deviceId().equals(current.optString("localDeviceId"))) throw new SecurityException("没有属于本机当前身份的设备授权");
        return editAction(peerId, peer, current, null);
    }
    private NativeDeviceWorkspaceHost.GrantReviewAction editAction(String id, JSONObject expectedPeer, JSONObject expectedGrant, Review reviewed) {
        return new NativeDeviceWorkspaceHost.GrantReviewAction() {
            private void current() throws Exception {
                JSONObject peer = owner.trust.peer(id), grant = owner.trust.grant(expectedGrant.getString("grantId"));
                if (peer == null || peer.optBoolean("revoked") || peer.optLong("authorizationEpoch") != expectedPeer.getLong("authorizationEpoch") || !peer.optString("fingerprint").equals(expectedPeer.getString("fingerprint")) || !peer.optString("instanceId").equals(expectedPeer.getString("instanceId")) || grant == null || !DeviceProtocol.canonical(grant).equals(DeviceProtocol.canonical(expectedGrant))) throw new SecurityException("设备权限已变化，请重新打开核对");
            }
            public JSONObject view() throws Exception { current(); JSONObject safe = new JSONObject().put("reviewKind", "grant_revision").put("deviceId", id).put("certificateFingerprint", expectedPeer.getString("fingerprint")).put("currentEpoch", expectedPeer.getLong("authorizationEpoch")); if (reviewed == null) return decorate(safe.put("currentGrant", owner.localGrantView(expectedGrant)), id); JSONObject proposed = DeviceWorkspaceGrantOwner.this.view(reviewed);
                JSONObject budget = owner.tasks.deviceTasks().grantBudgetStatus(id, expectedGrant.getString("grantId"));
                if ("known".equals(budget.optString("budgetState"))) proposed.put("retainedToolsReservedOrUsed", budget.optLong("toolsReservedOrUsed")).put("retainedModelsReservedOrUsed", budget.optLong("modelsReservedOrUsed"));
                return safe.put("grantReview", proposed); }
            public NativeDeviceWorkspaceHost.GrantReviewAction reviewGrant(NativeDeviceWorkspaceHost.GrantChoice choice) throws Exception { current(); Review selected = review(id, choice); selected.grant.put("grantId", expectedGrant.getString("grantId")).put("authorizationEpoch", Math.addExact(expectedPeer.getLong("authorizationEpoch"), 1)); return editAction(id, expectedPeer, expectedGrant, selected); }
            public void confirm() throws Exception { current(); if (reviewed == null) throw new SecurityException("尚未核对准确权限修改"); reviewed.requireCurrent(); owner.pairing().replaceGrantLocally(reviewed.grant(), System.currentTimeMillis()); owner.changed(); }
        };
    }
    private static String label(String id) { return "设备 " + id.substring(Math.max(0, id.length() - 4)); }
    private static String labels(JSONArray ids) throws Exception { List<String> labels = new ArrayList<>(); for (int i = 0; i < ids.length(); i++) labels.add(label(ids.getString(i))); return String.join("、", labels); }
}
