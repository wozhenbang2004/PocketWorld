package dev.pocketengine.features.remoteinbox;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** HTTPS pull and offline catch-up. A successful download is never permission to run a task. */
public final class RemoteInboxSync {
    private static final AtomicBoolean busy = new AtomicBoolean();
    private RemoteInboxSync() { }
    private static final class CredentialException extends SecurityException { CredentialException() { super("通知凭据已失效，请重新配置"); } }
    public static JSONObject run(Context context) throws Exception {
        if (!busy.compareAndSet(false, true)) return new JSONObject().put("status", "already_running");
        RemoteInboxStore store = RemoteInboxStore.get(context);
        String configuredIdentity = "";
        long startedAt = System.currentTimeMillis();
        long deadline = android.os.SystemClock.elapsedRealtime() + 45_000;
        try {
            if (!store.configured()) return new JSONObject().put("status", "not_configured");
            JSONObject config = store.connection();
            configuredIdentity = config.getString("configId");
            android.net.ConnectivityManager network = context.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities capabilities = network == null ? null : network.getNetworkCapabilities(network.getActiveNetwork());
            if (capabilities == null || !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                store.recordSync(configuredIdentity, startedAt, "waiting_network");
                RemoteInboxJobService.requestCatchUp(context);
                return new JSONObject().put("status", "waiting_network");
            }
            ArrayList<JSONObject> admitted = new ArrayList<>();
            boolean more = false;
            int pages = 0;
            do {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("通知补收已暂停");
                if (++pages > 12 || android.os.SystemClock.elapsedRealtime() > deadline) break;
                long previous = store.cursor();
                if (!configuredIdentity.equals(store.configId())) throw new SecurityException("通知连接已改变");
                URL url = new URL(config.getString("endpoint") + "/v1/events?after=" + previous);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + config.getString("bearer"));
                JSONObject response;
                try {
                    int code = connection.getResponseCode();
                    if (code == 401 || code == 403) throw new CredentialException();
                    if (code != 200) throw new IllegalStateException("通知服务暂不可用 (" + code + ")");
                    try (InputStream input = connection.getInputStream()) {
                        ByteArrayOutputStream body = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime() > deadline)
                                throw new java.io.InterruptedIOException("本批通知补收已暂停");
                            if (body.size() + n > 256 * 1024) throw new SecurityException("通知响应过大");
                            body.write(buffer, 0, n);
                        }
                        response = new JSONObject(body.toString(StandardCharsets.UTF_8));
                    }
                } finally { connection.disconnect(); }
                if (!"pocketworld.remote-inbox.v1".equals(response.optString("format"))) throw new SecurityException("通知协议不兼容");
                JSONArray rows = response.getJSONArray("events");
                more = response.getBoolean("hasMore");
                if (rows.length() == 0 && more) throw new IllegalStateException("通知分页未推进");
                List<JSONObject> batch = store.ingest(rows, configuredIdentity);
                admitted.addAll(batch);
                if (store.cursor() <= previous && more) throw new IllegalStateException("通知同步游标未推进");
            } while (more);
            synchronized (store) {
                if (!configuredIdentity.equals(store.configId())) throw new SecurityException("通知连接已改变，本次旧消息未推送");
                store.recordSync(configuredIdentity, startedAt, more ? "more_pending" : "synced");
            }
            return new JSONObject().put("status", more ? "more_pending" : "synced")
                .put("newCount", admitted.size()).put("cursor", store.cursor());
        } catch (Exception failure) {
            try { store.recordSync(configuredIdentity, startedAt, failure instanceof CredentialException ? "credentials_required" : "connection_failed"); }
            catch (Exception recording) { failure.addSuppressed(recording); }
            if (!(failure instanceof CredentialException)) {
                try { if (configuredIdentity.equals(store.configId())) RemoteInboxJobService.requestCatchUp(context); }
                catch (Exception ignored) { /* Periodic recovery remains independently registered. */ }
            }
            throw failure;
        } finally {
            // Earlier pages may already be durable even if a later request failed. Native alerts
            // must not wait for a successful network round trip or a working receive credential.
            try { RemoteInboxNotifications.publishPending(context, configuredIdentity.isEmpty() ? store.configId() : configuredIdentity); }
            catch (Exception unavailable) { Log.w("PocketRemoteInbox", "Pending inbox alerts remain recoverable"); }
            busy.set(false);
        }
    }
}
