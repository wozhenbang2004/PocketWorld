package dev.pocketengine.features.updates;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Private immutable hash-addressed downloads; a partial file is never installable. */
final class UpdateDownloads {
    interface Progress { void update(long bytes, long total); }
    private final Call.Factory client;
    private final Object callLock = new Object();
    private volatile Call active;
    private volatile boolean cancelled;

    UpdateDownloads() {
        this(new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.MINUTES).build());
    }
    UpdateDownloads(Call.Factory client) { this.client = client; }
    void cancel() {
        Call call;
        synchronized (callLock) { cancelled = true; call = active; }
        if (call != null) call.cancel();
    }
    private Call register(Call call) {
        synchronized (callLock) {
            if (!cancelled && !Thread.currentThread().isInterrupted()) {
                active = call;
                return call;
            }
        }
        call.cancel();
        throw new java.util.concurrent.CancellationException("下载已暂停，可稍后继续");
    }
    void check() { if (cancelled || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("下载已暂停，可稍后继续"); }

    byte[] manifest(String url) throws Exception {
        try (Response response = executePublic(url, 0)) {
            if (response.code() != 200 || response.body() == null) throw new IOException("无法读取私有更新服务，请稍后重试");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try (InputStream input = response.body().byteStream()) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    check(); if (output.size() + count > UpdateManifest.MAX_ENVELOPE_BYTES) throw new IOException("更新清单过大");
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        } finally { active = null; }
    }

    File download(UpdateManifest.Artifact artifact, File root, Progress progress) throws Exception {
        try { return downloadInternal(artifact, root, progress); }
        catch (IOException interruptedNetwork) { check(); throw interruptedNetwork; }
    }

    private File downloadInternal(UpdateManifest.Artifact artifact, File root, Progress progress) throws Exception {
        check();
        File complete = new File(root, artifact.sha256 + ".verified"), partial = new File(root, artifact.sha256 + ".part");
        if (complete.isFile()) {
            if (complete.length() == artifact.bytes && sha256(complete).equals(artifact.sha256)) return complete;
            if (!complete.delete()) throw new IOException("无法清理损坏的更新包");
        }
        if (partial.length() > artifact.bytes && !partial.delete()) throw new IOException("无法重置未完成下载");
        long offset = partial.length();
        if (offset < artifact.bytes) {
            try (Response response = executePublic(artifact.url, offset)) {
                if (response.body() == null) throw new IOException("更新服务未返回文件");
                if (response.code() == 206) {
                    String expected = "bytes " + offset + "-" + (artifact.bytes - 1) + "/" + artifact.bytes;
                    if (!expected.equals(response.header("Content-Range"))) throw new IOException("更新服务的断点范围不匹配");
                } else if (response.code() == 200) offset = 0;
                else throw new IOException("更新文件暂不可用，请稍后重试");
                long contentLength = response.body().contentLength();
                if (contentLength != -1 && contentLength != artifact.bytes - offset) throw new IOException("更新包长度不匹配");
                if (root.getUsableSpace() < artifact.bytes - offset + 16L * 1024 * 1024) throw new IOException("存储空间不足，请清理空间后继续");
                try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
                    long received = offset; byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        check(); if (received + count > artifact.bytes) throw new IOException("更新包超出清单大小");
                        output.write(buffer, 0, count); received += count; progress.update(received, artifact.bytes);
                    }
                    output.getFD().sync();
                    if (received != artifact.bytes) throw new IOException("下载中断，点击继续可接着下载");
                }
            } finally { active = null; }
        }
        check();
        if (partial.length() != artifact.bytes || !sha256(partial).equals(artifact.sha256)) {
            if (!partial.delete()) throw new IOException("更新包已损坏且无法清理");
            throw new IOException("更新包校验失败，已丢弃，可重新下载");
        }
        if (!partial.renameTo(complete)) throw new IOException("更新包保存失败，请重试");
        return complete;
    }

    private Response executePublic(String initial, long offset) throws Exception {
        String url = UpdateDownloadUrls.initial(initial);
        java.util.Set<String> visited = new java.util.HashSet<>();
        visited.add(url);
        for (int redirects = 0; ; redirects++) {
            check();
            // Construct every hop afresh: never forward Authorization, Cookie or Referer.
            Request.Builder request = new Request.Builder().url(url).header("Accept-Encoding", "identity");
            if (offset > 0) request.header("Range", "bytes=" + offset + "-");
            Call call = register(client.newCall(request.build()));
            Response response = call.execute();
            int code = response.code();
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return response;
            try {
                url = UpdateDownloadUrls.redirect(url, response.header("Location"), redirects, visited);
            } finally {
                response.close();
            }
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count; while ((count = input.read(buffer)) != -1) { if (Thread.currentThread().isInterrupted()) throw new IOException("更新校验已停止"); digest.update(buffer, 0, count); }
        }
        StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
}
