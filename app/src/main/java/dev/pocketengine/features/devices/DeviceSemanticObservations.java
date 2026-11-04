package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Structure delivery on the original Runner. A downloaded tree is not proof that the
 * model received it. Only an exact authenticated tool receipt in a completed model
 * request authorizes a subsequent semantic action; history never revives a used ref. */
final class DeviceSemanticObservations implements AutoCloseable {
    @FunctionalInterface interface Current { void require() throws Exception; }
    private record Observation(String route, String operation, String device, String reference,
            String state, long captured, long expires, Current current) { }
    private final RunControl original;
    private final Map<String, Observation> latest = new LinkedHashMap<>();
    private final Map<String, Set<Observation>> requests = new LinkedHashMap<>();
    private final Set<Observation> delivered = new LinkedHashSet<>();
    private final Set<String> retired = new LinkedHashSet<>();
    private final Map<String, Long> latestCaptured = new LinkedHashMap<>();
    private boolean closed;

    DeviceSemanticObservations(RunControl original) { this.original = original; }
    synchronized void invalidate(String route) {
        Observation old = latest.remove(route);
        if (old != null) { delivered.remove(old); retired.add(route + "\n" + old.reference); }
    }
    synchronized void capture(String route, JSONObject wire, JSONObject receipt, Current current) throws Exception {
        current.require();
        JSONObject observation = observation(wire.getString("operationId"), receipt);
        if (observation == null || !"semantic".equals(observation.optString("observationMode"))) return;
        JSONObject ref = observation.getJSONObject("observationRef"), state = observation.getJSONObject("state");
        String canonical = DeviceProtocol.canonical(ref);
        Observation old = latest.get(route);
        if (old != null && old.reference.equals(canonical)) { require(old); return; }
        long captured = ref.getLong("capturedAt"), now = System.currentTimeMillis();
        long expires = Math.min(Math.min(captured + 30000, ref.optLong("expiresAt", captured + 30000)), wire.getLong("deadline"));
        if (closed || retired.size() >= 4096 || retired.contains(route + "\n" + canonical) || captured > now + 1000
                || expires <= now || captured <= latestCaptured.getOrDefault(route, 0L))
            throw new SecurityException("结构观察已过期、已消费或被更新的观察替代");
        invalidate(route);
        while (latest.size() >= 4) invalidate(latest.keySet().iterator().next());
        if (!latestCaptured.containsKey(route) && latestCaptured.size() >= 64) throw new SecurityException("原结构观察路由窗口已满");
        latestCaptured.put(route, captured);
        latest.put(route, new Observation(route, wire.getString("operationId"), wire.getString("targetDeviceId"),
                canonical, DeviceProtocol.canonical(state), captured, expires, current));
    }
    synchronized boolean contains(String route, JSONObject ref) throws Exception {
        Observation value = latest.get(route);
        return ref != null && (value != null && value.reference.equals(DeviceProtocol.canonical(ref)) || retired.contains(route + "\n" + DeviceProtocol.canonical(ref)));
    }
    private void require(Observation value) throws Exception {
        if (closed || latest.get(value.route) != value || System.currentTimeMillis() >= value.expires)
            throw new SecurityException("原结构观察已替换或过期，请重新观察");
        value.current.require();
    }
    synchronized void requireAction(String route, JSONObject ref) throws Exception {
        Observation value = latest.get(route);
        if (value == null || ref == null || !value.reference.equals(DeviceProtocol.canonical(ref)) || !delivered.contains(value))
            throw new SecurityException("语义动作必须引用最新且已实际交给当前模型的结构");
        require(value);
        invalidate(route); // consume before dispatch, including unknown/refused outcomes
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
                    && expected.reference.equals(DeviceProtocol.canonical(actual.getJSONObject("observationRef")))
                    && expected.state.equals(DeviceProtocol.canonical(actual.getJSONObject("state")));
        } catch (org.json.JSONException invalid) { return false; }
    }
    synchronized void beforeRequest(String request, JSONArray messages, RunControl actual) throws Exception {
        if (closed || actual != original || messages.length() > 512) throw new SecurityException("原结构观察请求已关闭或越界");
        Set<Observation> included = new LinkedHashSet<>();
        for (Observation observation : latest.values()) {
            if (System.currentTimeMillis() >= observation.expires) continue;
            require(observation);
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null || !"tool".equals(message.optString("role"))) continue;
                Object content = message.opt("content");
                if (content instanceof String && matches((String) content, observation)) included.add(observation);
                if (content instanceof JSONArray parts) for (int p = 0; p < parts.length(); p++) {
                    JSONObject part = parts.optJSONObject(p);
                    if (part != null && "text".equals(part.optString("type")) && matches(part.optString("text"), observation)) included.add(observation);
                }
            }
        }
        requests.put(request, included);
        while (requests.size() > 2) requests.remove(requests.keySet().iterator().next());
    }
    synchronized JSONObject afterResponse(String request, RunControl actual) throws Exception {
        if (closed || actual != original) throw new SecurityException("原结构观察响应已关闭");
        Set<Observation> included = requests.remove(request);
        int count = 0;
        if (included != null) for (Observation value : included) { require(value); delivered.add(value); count++; }
        return count == 0 ? null : new JSONObject().put("kind", "paired_device_semantic_delivery")
                .put("requestId", request).put("structuresProvided", count).put("businessVerified", false);
    }
    synchronized void abandoned(String request) { requests.remove(request); }
    @Override public synchronized void close() { closed = true; latest.clear(); requests.clear(); delivered.clear(); retired.clear(); latestCaptured.clear(); }
}
