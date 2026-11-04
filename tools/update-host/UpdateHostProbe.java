package dev.pocketengine.features.updates;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in read-only network check using the actual production downloader, not an APK installer. */
public final class UpdateHostProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected HTTPS_URL EXACT_LOCAL_FILE CANCEL_AFTER_BYTES KIND");
        String url = UpdateManifest.https(args[0]);
        if (new java.net.URI(url).getQuery() != null) throw new IllegalArgumentException("Probe URL must not contain query credentials");
        File expected = Path.of(args[1]).toFile();
        long cancelAfter = Long.parseLong(args[2]);
        if (!expected.isFile() || expected.length() > UpdateManifest.MAX_APK_BYTES
                || cancelAfter <= 0 || cancelAfter >= expected.length()) throw new IllegalArgumentException("Invalid probe file or interruption offset");
        String digest = UpdateDownloads.sha256(expected);
        UpdateManifest.Artifact artifact = new UpdateManifest.Artifact(new JSONObject().put("url", url)
                .put("bytes", expected.length()).put("sha256", digest), UpdateManifest.MAX_APK_BYTES);
        List<JSONObject> observations = new ArrayList<>();
        // Same transport policy as UpdateDownloads' production constructor; observation only.
        OkHttpClient client = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.MINUTES)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    JSONObject row = new JSONObject().put("status", response.code());
                    row.put("rangeRequest", chain.request().header("Range"));
                    row.put("acceptEncoding", chain.request().header("Accept-Encoding"));
                    for (String header : new String[] {"Content-Length", "Content-Range", "Content-Encoding", "Accept-Ranges", "ETag"}) {
                        row.put(header, response.header(header));
                    }
                    observations.add(row);
                    return response;
                }).build();
        Path temporary = Files.createTempDirectory("pocketworld-live-update-probe-");
        try {
            UpdateDownloads interrupted = new UpdateDownloads(client);
            boolean cancelled = false;
            try {
                interrupted.download(artifact, temporary.toFile(), (bytes, total) -> {
                    if (bytes >= cancelAfter) interrupted.cancel();
                });
            } catch (CancellationException expectedCancellation) { cancelled = true; }
            File partial = temporary.resolve(digest + ".part").toFile();
            long partialBytes = partial.length();
            if (!cancelled || partialBytes < cancelAfter || partialBytes >= artifact.bytes) {
                throw new IllegalStateException("Production download did not pause with a resumable partial");
            }
            File complete = new UpdateDownloads(client).download(artifact, temporary.toFile(), (bytes, total) -> {});
            if (complete.length() != artifact.bytes || !UpdateDownloads.sha256(complete).equals(digest)
                    || observations.size() != 2 || observations.get(0).getInt("status") != 200
                    || observations.get(1).getInt("status") != 206
                    || !observations.get(1).optString("rangeRequest").equals("bytes=" + partialBytes + "-")) {
                throw new IllegalStateException("Host did not complete an exact 206 resume with matching whole-file SHA-256");
            }
            System.out.println(new JSONObject().put("kind", "pocketworld-production-download-probe")
                    .put("artifactKind", args[3]).put("url", url).put("bytes", artifact.bytes).put("sha256", digest)
                    .put("interruptedAfterBytes", partialBytes).put("resumedByteExact", true)
                    .put("signedManifestVerified", false).put("deviceInstallationVerified", false)
                    .put("requests", new JSONArray(observations)).toString(2));
        } finally {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
