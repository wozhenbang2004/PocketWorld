package dev.pocketengine.media.jobs;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.MediaTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

/** One bounded, query-only observation of an existing ComfyUI job. No reconnect or generation. */
public final class ComfyUiProgressObserver implements AutoCloseable {
    public interface Listener { void onProgress(JSONObject event); }
    private static final int MAX_TEXT_BYTES = 65536, MAX_BINARY_BYTES = 1024 * 1024;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_MESSAGES = 2048;
    private final Object gate = new Object();
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(32);
    private final AtomicBoolean started = new AtomicBoolean();
    private final JSONObject identity;
    private final String base, key, clientId, providerJobId, observationId = UUID.randomUUID().toString();
    private final RunControl control;
    private final Listener listener;
    private final long durationMillis;
    private final OkHttpClient client;
    private volatile WebSocket socket;
    private volatile boolean stopped;
    private String endReason = "";
    private boolean clientConfirmed, providerTerminal;
    private long sequence, messages, bytes, ignoredUnbound, ignoredForeign, ignoredBinary;

    ComfyUiProgressObserver(JSONObject receipt, JSONObject config, String key, RunControl control,
            Listener listener, long durationMillis) throws Exception {
        if (durationMillis < 250 || durationMillis > 60000) throw new IllegalArgumentException("进度观察时限应为250–60000毫秒");
        this.base = config.getString("baseUrl"); MediaTransport.base(base);
        this.key = key == null ? "" : key;
        if (this.key.length() > 8192 || this.key.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("媒体凭据格式无效");
        clientId = receipt.getString("_clientId"); providerJobId = receipt.getString("providerJobId");
        String uuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
        if (!clientId.matches(uuid) || !providerJobId.matches(uuid)) throw new SecurityException("ComfyUI原任务绑定无效");
        identity = new JSONObject();
        for (String field : new String[]{"operationId", "taskId", "runId", "providerJobId", "configurationRevision"}) identity.put(field, receipt.getString(field));
        this.control = java.util.Objects.requireNonNull(control); this.listener = java.util.Objects.requireNonNull(listener);
        this.durationMillis = durationMillis;
        client = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build();
    }

    /** Runs on the existing host worker. Listener delivery is serialized with close admission. */
    public JSONObject observe() throws Exception {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("进度观察不能重新连接或重复使用");
        Runnable cancel = this::close;
        control.onCancel(cancel);
        long start = System.nanoTime(), deadline = start + durationMillis * 1_000_000L;
        try {
            control.check();
            synchronized (gate) {
                if (!stopped) {
                    Request.Builder request = new Request.Builder().url(socketUri().toASCIIString()).header("Cache-Control", "no-store");
                    if (!key.isEmpty()) request.header("Authorization", "Bearer " + key);
                    socket = client.newWebSocket(request.build(), new WebSocketListener() {
                        @Override public void onMessage(WebSocket connection, String text) { incoming(text); }
                        @Override public void onMessage(WebSocket connection, ByteString data) { binary(data.size()); }
                        @Override public void onFailure(WebSocket connection, Throwable failure, Response response) { stop("transport_unconfirmed"); }
                        @Override public void onClosing(WebSocket connection, int code, String reason) { stop("transport_closed"); }
                        @Override public void onClosed(WebSocket connection, int code, String reason) { stop("transport_closed"); }
                    });
                }
            }
            while (!stopped) {
                control.check();
                long now = System.nanoTime();
                if (now >= deadline) { stop("observation_expired"); break; }
                if (!clientConfirmed && now - start > 10_000_000_000L) { stop("client_binding_timeout"); break; }
                String text = pending.poll(Math.max(1, Math.min(200, (deadline - now) / 1_000_000L)), TimeUnit.MILLISECONDS);
                if (text == null) continue;
                control.check();
                try { accept(text); } catch (InterruptedException revoked) { throw revoked; }
                catch (Exception malformed) { stop("message_not_supported"); }
            }
        } catch (InterruptedException revoked) { stop("observation_cancelled"); }
        catch (RuntimeException unavailable) { stop("transport_unconfirmed"); }
        finally {
            control.removeCancelAction(cancel);
            stop("observation_ended");
            client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
        }
        synchronized (gate) {
            return envelope("media_progress_observation").put("observationState", "ended").put("endReason", endReason)
                    .put("providerTerminalObserved", providerTerminal).put("historyReconciliationRequired", true)
                    .put("observedEvents", sequence).put("clientBindingConfirmed", clientConfirmed).put("messagesReceived", messages)
                    .put("ignoredUnboundEvents", ignoredUnbound).put("ignoredForeignEvents", ignoredForeign).put("ignoredBinaryPreviews", ignoredBinary);
        }
    }

    private URI socketUri() throws Exception {
        URI route = MediaTransport.route(base, "/ws", null);
        return new URI(route.getScheme().equalsIgnoreCase("https") ? "wss" : "ws", null, route.getHost(), route.getPort(),
                route.getPath(), "clientId=" + clientId, null);
    }

    private void incoming(String text) {
        if (text == null || text.length() > MAX_TEXT_BYTES) { stop("message_limit"); return; }
        int size = text.getBytes(StandardCharsets.UTF_8).length;
        synchronized (gate) {
            if (stopped) return;
            if (!budget(size) || size > MAX_TEXT_BYTES) { stop("message_limit"); return; }
            if (!pending.offer(text)) stop("pending_message_limit");
        }
    }
    private void binary(int size) {
        synchronized (gate) {
            if (stopped) return;
            if (!budget(size) || size > MAX_BINARY_BYTES) { stop("binary_preview_limit"); return; }
            ignoredBinary++; // Preview bytes never become job progress or artifacts.
        }
    }
    private boolean budget(int size) { messages++; bytes += size; return messages <= MAX_MESSAGES && bytes <= MAX_BYTES; }

    private void accept(String raw) throws Exception {
        JSONObject message = new JSONObject(raw);
        String type = message.getString("type");
        JSONObject data = message.optJSONObject("data");
        if (type.equals("status")) {
            if (data == null || !data.has("sid")) return; // Subsequent global queue status has no job identity.
            synchronized (gate) {
                if (!clientId.equals(data.optString("sid"))) { stop("client_binding_mismatch"); return; }
                clientConfirmed = true;
            }
            return;
        }
        if (!type.matches("progress|execution_start|executing|execution_success|execution_error|execution_interrupted")) return;
        if (data == null) throw new IllegalStateException("进度事件没有数据");
        synchronized (gate) {
            if (stopped) return;
            if (!clientConfirmed || !data.has("prompt_id") || data.isNull("prompt_id")) { ignoredUnbound++; return; }
            if (!providerJobId.equals(data.optString("prompt_id"))) { ignoredForeign++; return; }
        }
        JSONObject event = envelope("media_progress").put("providerPhase", type);
        boolean terminal = type.matches("execution_success|execution_error|execution_interrupted");
        if (data.has("node") && !data.isNull("node")) {
            Object node = data.get("node");
            if (!(node instanceof String) || ((String) node).length() > 200 || ((String) node).chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalStateException("进度节点标识无效");
            event.put("node", node);
        }
        if (type.equals("executing") && data.has("node") && data.isNull("node")) {
            terminal = true; event.put("providerPhase", "executor_finished_observed");
        }
        if (type.equals("progress")) {
            Object value = data.get("value"), max = data.get("max");
            if (!(value instanceof Number) || !(max instanceof Number)) throw new IllegalStateException("进度计数无效");
            double current = ((Number) value).doubleValue(), total = ((Number) max).doubleValue();
            if (!Double.isFinite(current) || !Double.isFinite(total) || total <= 0 || total > 1_000_000_000L || current < 0 || current > total) throw new IllegalStateException("进度计数越界");
            event.put("value", value).put("max", max).put("progressScope", "provider_node_only");
        }
        event.put("historyReconciliationRequired", terminal);
        control.check();
        synchronized (gate) {
            if (stopped) return;
            event.put("sequence", ++sequence);
            listener.onProgress(event); // Trusted native callback must return promptly, as with existing EventSink.
            if (terminal) { providerTerminal = true; stop("provider_terminal_observed"); }
        }
    }

    private JSONObject envelope(String kind) throws Exception {
        return new JSONObject(identity.toString()).put("kind", kind).put("observationId", observationId)
                .put("providerProgressOnly", true).put("verified", false);
    }
    private void stop(String reason) {
        synchronized (gate) {
            if (!stopped) { stopped = true; endReason = reason; pending.clear(); }
            WebSocket current = socket;
            if (current != null) { socket = null; current.cancel(); }
        }
    }
    @Override public void close() { stop("observation_cancelled"); }
}
