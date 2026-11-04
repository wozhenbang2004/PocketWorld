package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Structure delivery on the original Runner. A downloaded tree is not proof that the
 * model received it. Only an exact authenticated tool receipt in a completed model
 * request authorizes a subsequent semantic action; history never revives a used ref. */
final class DeviceSemanticObservations implements AutoCloseable {
    @FunctionalInterface interface Current { void require() throws Exception; }
    private record Observation(String route, String operation, String device, String reference,
            String state, long expires, long pauseRevision, Current current) { }
    private record Request(String id, long pauseRevision, long replanRevision, Set<Observation> included) { }
    private final RunControl original;
    private final LongSupplier clock;
    private final Map<String, Observation> latest = new LinkedHashMap<>();
    private final Map<String, Long> latestCaptured = new LinkedHashMap<>();
    private final Set<Observation> delivered = new LinkedHashSet<>();
    private final Set<String> retired = new LinkedHashSet<>();
    private Request request;
    private boolean responseReceived, closed;

    DeviceSemanticObservations(RunControl original) { this(original, System::currentTimeMillis); }
    DeviceSemanticObservations(RunControl original, LongSupplier clock) {
        this.original = Objects.requireNonNull(original);
        this.clock = Objects.requireNonNull(clock);
    }
    synchronized void invalidate(String route) {
        Observation old = latest.remove(route);
        if (old != null) { delivered.remove(old); retired.add(route + "\n" + old.reference); }
    }
    synchronized void capture(String route, JSONObject wire, JSONObject receipt, Current current) throws Exception {
        requireRun(original);
        current.require();
        JSONObject observation = observation(wire.getString("operationId"), receipt);
        if (observation == null || !"semantic".equals(observation.optString("observationMode"))) return;
        JSONObject typed = receipt.optJSONObject("deviceReceipt");
        if (typed == null) typed = receipt;
        if (!wire.getString("targetDeviceId").equals(typed.optString("executorDeviceId")))
            throw new SecurityException("结构观察不属于原目标设备");
        JSONObject ref = observation.getJSONObject("observationRef"), state = observation.getJSONObject("state");
        if (!complete(state)) throw new SecurityException("结构观察不完整或原生目标身份未确认，请重新观察");
        String canonical = DeviceProtocol.canonical(ref), frozenState = DeviceProtocol.canonical(state);
        Observation old = latest.get(route);
        if (old != null && old.reference.equals(canonical)) {
            if (!old.state.equals(frozenState) || !old.operation.equals(wire.getString("operationId"))
                    || !old.device.equals(wire.getString("targetDeviceId")))
                throw new SecurityException("同一结构观察的原操作或内容已改变");
            require(old);
            return;
        }
        long captured = DeviceProtocol.integer(ref.opt("capturedAt"), "capturedAt", 1, DeviceProtocol.MAX_SAFE_INTEGER);
        long expires = Math.min(Math.min(captured + 30000, wire.getLong("deadline")),
                DeviceProtocol.integer(ref.opt("expiresAt"), "expiresAt", 1, DeviceProtocol.MAX_SAFE_INTEGER));
        long now = clock.getAsLong();
        if (closed || retired.size() >= 4096 || retired.contains(route + "\n" + canonical) || captured > now + 1000
                || now - captured >= 30000 || expires <= now || expires <= captured
                || captured <= latestCaptured.getOrDefault(route, 0L) || original.isPaused())
            throw new SecurityException("结构观察已过期、已消费或被更新的观察替代");
        invalidate(route);
        while (latest.size() >= 4) invalidate(latest.keySet().iterator().next());
        latestCaptured.put(route, captured);
        latest.put(route, new Observation(route, wire.getString("operationId"), wire.getString("targetDeviceId"),
                canonical, frozenState, expires, original.pauseRevision(), current));
    }
    /** Routing recognition only. Retired refs must still reach the semantic rejection
     * gate, rather than falling back to the independent visual-input route. */
    synchronized boolean contains(String route, JSONObject ref) throws Exception {
        if (ref == null) return false;
        Observation value = latest.get(route);
        String canonical = DeviceProtocol.canonical(ref);
        return value != null && value.reference.equals(canonical) || retired.contains(route + "\n" + canonical);
    }
    private void requireRun(RunControl actual) throws Exception {
        if (closed || actual != original) throw new SecurityException("原结构观察执行控制已关闭或改变");
        original.requireExecution();
        original.check();
    }
    private void require(Observation value) throws Exception {
        requireFresh(value);
        value.current.require();
        original.check();
        requireFresh(value);
    }
    private void requireFresh(Observation value) {
        if (closed || latest.get(value.route) != value || clock.getAsLong() >= value.expires
                || original.isPaused() || original.pauseRevision() != value.pauseRevision)
            throw new SecurityException("原结构观察已替换或过期，请重新观察");
    }
    synchronized void requireAction(String route, JSONObject ref) throws Exception {
        requireRun(original);
        Observation value = latest.get(route);
        if (value == null || ref == null || !value.reference.equals(DeviceProtocol.canonical(ref))
                || !delivered.contains(value) || !responseReceived || !requestCurrent())
            throw new SecurityException("语义动作必须引用最新且已实际交给当前模型的结构");
        require(value);
        if (!requestCurrent()) throw new SecurityException("原模型决定已被主人补充替代，请重新推理");
        invalidate(route); // consume before dispatch, including unknown/refused outcomes
    }
    private static boolean complete(JSONObject state) {
        if (state == null || !Boolean.TRUE.equals(state.opt("elements_complete"))
                || state.optJSONArray("elements") == null || state.optString("snapshot_id").isEmpty()) return false;
        for (String flag : new String[]{"truncated", "adapter_truncated", "identity_unproven", "hasMore"})
            if (state.has(flag) && !Boolean.FALSE.equals(state.opt(flag))) return false;
        return !state.has("complete") || Boolean.TRUE.equals(state.opt("complete"));
    }
    private static JSONObject observation(String operation, JSONObject result) throws Exception {
        JSONObject typed = result.optJSONObject("deviceReceipt");
        if (typed == null) typed = result;
        if (!"OperationReceipt".equals(typed.optString("messageType")) || !operation.equals(typed.optString("operationId"))) return null;
        JSONObject body = typed.optJSONObject("payload");
        if (body == null) return null;
        JSONObject action = body.optJSONObject("desktopReceipt");
        return action == null ? body : action.optJSONObject("afterObservation");
    }
    private boolean matches(String content, Observation expected) throws Exception {
        if (content == null || content.length() > 262144) return false;
        try {
            JSONObject result = new JSONObject(content), typed = result.optJSONObject("deviceReceipt");
            if (typed == null) typed = result;
            JSONObject actual = observation(expected.operation, result);
            return actual != null && expected.device.equals(typed.optString("executorDeviceId"))
                    && "semantic".equals(actual.optString("observationMode"))
                    && complete(actual.optJSONObject("state"))
                    && expected.reference.equals(DeviceProtocol.canonical(actual.getJSONObject("observationRef")))
                    && expected.state.equals(DeviceProtocol.canonical(actual.getJSONObject("state")));
        } catch (org.json.JSONException | DeviceProtocol.ProtocolException invalid) { return false; }
    }
    private boolean currentForDelivery(Observation value) throws Exception {
        try { require(value); return true; }
        catch (InterruptedException stopped) { throw stopped; }
        catch (Exception expiredOrRevoked) {
            if (latest.get(value.route) == value) invalidate(value.route);
            return false;
        }
    }
    private boolean requestCurrent() {
        return request != null && !original.isPaused() && request.pauseRevision == original.pauseRevision()
                && request.replanRevision == original.modelReplanRevision();
    }
    synchronized void beforeRequest(String requestId, JSONArray messages, RunControl actual) throws Exception {
        requireRun(actual);
        // Delivery belongs to the next accepted model answer, not to all later
        // answers in the same run after compaction or request replacement.
        delivered.clear();
        request = null;
        responseReceived = false;
        if (requestId == null || requestId.isBlank() || messages == null || messages.length() > 512)
            throw new SecurityException("原结构观察请求标识或消息越界");
        long pause = original.pauseRevision(), replan = original.modelReplanRevision();
        Set<Observation> included = new LinkedHashSet<>();
        for (Observation observation : new LinkedHashSet<>(latest.values())) {
            boolean found = false;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null || !"tool".equals(message.optString("role"))) continue;
                Object content = message.opt("content");
                if (content instanceof String && matches((String) content, observation)) found = true;
                if (content instanceof JSONArray parts && parts.length() <= 512) for (int p = 0; p < parts.length() && !found; p++) {
                    JSONObject part = parts.optJSONObject(p);
                    if (part != null && "text".equals(part.optString("type")) && matches(part.optString("text"), observation)) found = true;
                }
                if (found) break;
            }
            // A stale route absent from the actual compacted request must not
            // reject an otherwise unrelated model request or final answer.
            if (found && currentForDelivery(observation)) included.add(observation);
        }
        request = new Request(requestId, pause, replan, Set.copyOf(included));
    }
    synchronized JSONObject afterResponse(String requestId, RunControl actual) throws Exception {
        requireRun(actual);
        if (request == null || !request.id.equals(requestId) || responseReceived) return null;
        responseReceived = true;
        if (!requestCurrent()) return null;
        int count = 0;
        for (Observation value : request.included) if (currentForDelivery(value)) { delivered.add(value); count++; }
        original.check();
        if (!requestCurrent()) { delivered.clear(); return null; }
        return count == 0 ? null : new JSONObject().put("kind", "paired_device_semantic_delivery")
                .put("requestId", requestId).put("structuresProvided", count).put("businessVerified", false);
    }
    synchronized void abandoned(String requestId) {
        if (request != null && request.id.equals(requestId)) {
            request = null;
            responseReceived = false;
            delivered.clear();
        }
    }
    @Override public synchronized void close() {
        closed = true;
        request = null;
        latest.clear();
        latestCaptured.clear();
        delivered.clear();
        retired.clear();
    }
}
