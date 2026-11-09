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
    public static JSONObject run(Context context) throws Exception {
        if (!busy.compareAndSet(false, true)) return new JSONObject().put("status", "already_running");
        RemoteInboxStore store = RemoteInboxStore.get(context);
        try {
            if (!store.configured()) return new JSONObject().put("status", "not_configured");
            ArrayList<JSONObject> admitted = new ArrayList<>();
            boolean more = false;
            int pages = 0;
            do {
                if (++pages > 12) break;
                long previous = store.cursor();
                URL url = new URL(store.url() + "/v1/events?after=" + previous);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + store.bearer());
                JSONObject response;
                try {
                    int code = connection.getResponseCode();
                    if (code == 401 || code == 403) throw new SecurityException("通知凭据已失效，请重新配置");
                    if (code != 200) throw new IllegalStateException("通知服务暂不可用 (" + code + ")");
                    try (InputStream input = connection.getInputStream()) {
                        ByteArrayOutputStream body = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
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
                List<JSONObject> batch = store.ingest(rows);
                admitted.addAll(batch);
                if (store.cursor() <= previous && more) throw new IllegalStateException("通知同步游标未推进");
            } while (more);
            for (JSONObject event : admitted) try { RemoteInboxNotifications.post(context, event); }
                catch (RuntimeException failure) { Log.w("PocketRemoteInbox", "Native notification not displayed", failure); }
            return new JSONObject().put("status", more ? "more_pending" : "synced")
                .put("newCount", admitted.size()).put("cursor", store.cursor());
        } finally { busy.set(false); }
    }
}
