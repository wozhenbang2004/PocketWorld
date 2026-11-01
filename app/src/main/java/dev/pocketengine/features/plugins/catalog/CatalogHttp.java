package dev.pocketengine.features.plugins.catalog;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded credential-free GETs. Redirects and connection retry cannot change the selected origin/content. */
final class CatalogHttp implements AutoCloseable {
    private final java.util.Set<Call> active = new java.util.HashSet<>();
    private boolean closed;
    private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build();

    byte[] get(String url, int ceiling, RunControl control) throws Exception {
        CatalogSources.url(url); control.check();
        Call call = client.newCall(new Request.Builder().url(url).header("Accept", "application/json")
                .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache").get().build());
        synchronized (this) {
            if (closed) throw new IllegalStateException("目录下载器已关闭");
            if (active.size() >= 4) throw new IllegalStateException("已有4个目录下载，请等待或停止");
            active.add(call);
        }
        Runnable stop = call::cancel; control.onCancel(stop);
        try {
            control.check();
            try (Response response = call.execute()) {
                if (response.code() != 200) throw new IllegalStateException("目录下载需HTTP200；状态 " + response.code());
                String encoding = response.header("Content-Encoding", "identity");
                if (!"identity".equalsIgnoreCase(encoding)) throw new IllegalArgumentException("目录不支持压缩响应");
                ResponseBody body = response.body();
                if (body == null || body.contentLength() > ceiling) throw new IllegalArgumentException("目录或声明包超过下载上限");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream stream = body.byteStream()) {
                    byte[] block = new byte[4096];
                    while (true) {
                        control.check(); int count = stream.read(block, 0, Math.min(block.length, ceiling + 1 - bytes.size()));
                        if (count < 0) break;
                        if (count == 0) throw new IllegalStateException("目录下载没有提供数据");
                        bytes.write(block, 0, count);
                        if (bytes.size() > ceiling) throw new IllegalArgumentException("目录或声明包超过下载上限");
                    }
                }
                control.check(); return bytes.toByteArray();
            }
        } finally { control.removeCancelAction(stop); synchronized (this) { active.remove(call); } }
    }
    @Override public synchronized void close() {
        closed = true; for (Call call : active) call.cancel();
        client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
    }
}
