package dev.pocketengine.features.updates;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateDownloadsTest {
    private static final byte[] CONTENT = "signed APK bytes fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private UpdateManifest.Artifact artifact() throws Exception {
        StringBuilder hash = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(CONTENT)) hash.append(String.format("%02x", value & 255));
        return new UpdateManifest.Artifact(new JSONObject().put("url", "https://private.example/app.apk").put("sha256", hash.toString()).put("bytes", CONTENT.length), 10000);
    }
    private static class FakeCall implements Call {
        final Request request; final Response response; boolean cancelled;
        FakeCall(Request request, int code, byte[] content, String range) {
            this.request = request;
            Response.Builder builder = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(ResponseBody.create(content, null));
            if (range != null) builder.header("Content-Range", range); response = builder.build();
        }
        public Request request() { return request; }
        public Response execute() throws IOException { if (cancelled) throw new IOException("cancelled"); return response; }
        public void enqueue(Callback callback) { throw new UnsupportedOperationException(); }
        public void cancel() { cancelled = true; }
        public boolean isExecuted() { return false; }
        public boolean isCanceled() { return cancelled; }
        public Timeout timeout() { return Timeout.NONE; }
        public Call clone() { return new FakeCall(request, response.code(), CONTENT, response.header("Content-Range")); }
    }
    @Test public void fullDownloadVerifiesAndCaches() throws Exception {
        File root = Files.createTempDirectory("pw-update-download").toFile(); UpdateManifest.Artifact artifact = artifact();
        try {
            UpdateDownloads downloads = new UpdateDownloads(request -> new FakeCall(request, 200, CONTENT, null));
            File result = downloads.download(artifact, root, (received, total) -> {});
            assertArrayEquals(CONTENT, Files.readAllBytes(result.toPath())); assertTrue(result.getName().endsWith(".verified"));
            UpdateDownloads offline = new UpdateDownloads(request -> { throw new AssertionError("verified cache must not fetch"); });
            assertEquals(result, offline.download(artifact, root, (received, total) -> {}));
        } finally { for (File file : root.listFiles()) file.delete(); root.delete(); }
    }
    @Test public void resumesOnlyExactRange() throws Exception {
        File root = Files.createTempDirectory("pw-update-range").toFile(); UpdateManifest.Artifact artifact = artifact();
        try {
            Files.write(new File(root, artifact.sha256 + ".part").toPath(), java.util.Arrays.copyOf(CONTENT, 5));
            AtomicReference<Request> request = new AtomicReference<>();
            UpdateDownloads downloads = new UpdateDownloads(value -> { request.set(value); return new FakeCall(value, 206, java.util.Arrays.copyOfRange(CONTENT, 5, CONTENT.length), "bytes 5-" + (CONTENT.length - 1) + "/" + CONTENT.length); });
            assertArrayEquals(CONTENT, Files.readAllBytes(downloads.download(artifact, root, (received, total) -> {}).toPath()));
            assertEquals("bytes=5-", request.get().header("Range"));
        } finally { for (File file : root.listFiles()) file.delete(); root.delete(); }
    }
    @Test public void serverIgnoringRangeRestartsSafely() throws Exception {
        File root = Files.createTempDirectory("pw-update-restart").toFile(); UpdateManifest.Artifact artifact = artifact();
        try {
            Files.write(new File(root, artifact.sha256 + ".part").toPath(), new byte[]{1, 2, 3});
            UpdateDownloads downloads = new UpdateDownloads(value -> new FakeCall(value, 200, CONTENT, null));
            assertArrayEquals(CONTENT, Files.readAllBytes(downloads.download(artifact, root, (received, total) -> {}).toPath()));
        } finally { for (File file : root.listFiles()) file.delete(); root.delete(); }
    }
    @Test public void wrongRangeAndRedirectAndCorruptionNeverPromote() throws Exception {
        for (int code : new int[]{206, 302, 200}) {
            File root = Files.createTempDirectory("pw-update-invalid").toFile(); UpdateManifest.Artifact artifact = artifact();
            try {
                byte[] bad = CONTENT.clone(); bad[0] ^= 1;
                UpdateDownloads downloads = new UpdateDownloads(value -> new FakeCall(value, code, bad, "bytes 3-23/24"));
                assertThrows(Exception.class, () -> downloads.download(artifact, root, (received, total) -> {}));
                assertFalse(new File(root, artifact.sha256 + ".verified").exists());
            } finally { for (File file : root.listFiles()) file.delete(); root.delete(); }
        }
    }
    @Test public void cancellationDoesNotPromote() throws Exception {
        File root = Files.createTempDirectory("pw-update-cancel").toFile(); UpdateManifest.Artifact artifact = artifact();
        try {
            UpdateDownloads downloads = new UpdateDownloads(value -> new FakeCall(value, 200, CONTENT, null)); downloads.cancel();
            assertThrows(java.util.concurrent.CancellationException.class, () -> downloads.download(artifact, root, (received, total) -> {}));
            assertFalse(new File(root, artifact.sha256 + ".verified").exists());
        } finally { for (File file : root.listFiles()) file.delete(); root.delete(); }
    }
    @Test public void cancellationDuringBlockedNetworkIsPausedNotFailed() throws Exception {
        File root = Files.createTempDirectory("pw-update-blocked-cancel").toFile(); UpdateManifest.Artifact artifact = artifact();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1), cancellationSignal = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            UpdateDownloads downloads = new UpdateDownloads(value -> new FakeCall(value, 200, CONTENT, null) {
                @Override public Response execute() throws IOException {
                    started.countDown();
                    try { if (!cancellationSignal.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    throw new IOException("Canceled");
                }
                @Override public void cancel() { super.cancel(); cancellationSignal.countDown(); }
            });
            java.util.concurrent.Future<?> result = worker.submit(() -> {
                assertThrows(java.util.concurrent.CancellationException.class, () -> downloads.download(artifact, root, (received, total) -> {}));
            });
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS)); downloads.cancel(); result.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(new File(root, artifact.sha256 + ".verified").exists());
        } finally { worker.shutdownNow(); for (File file : root.listFiles()) file.delete(); root.delete(); }
    }
}
