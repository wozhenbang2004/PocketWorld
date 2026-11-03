package dev.pocketengine.runtime.models;

import dev.pocketengine.runtime.usage.ModelPrice;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** One explicit endpoint/auth scheme. No alternate origin, key, protocol or inference request. */
public final class CatalogHttpClient {
    private static final int PAGE_BYTES = 2 * 1024 * 1024, TOTAL_BYTES = 8 * 1024 * 1024, MAX_PAGES = 16;
    private final Clock clock;
    private final int timeoutMillis;
    private final OkHttpClient transport;

    /** Transient request-only credentials. Never passed to a store or exposed by toString(). */
    public static final class Headers {
        private final Map<String, String> values;
        private Headers(Map<String, String> values) {
            for (Map.Entry<String, String> row : values.entrySet()) {
                if (row.getValue() == null || row.getValue().length() > 16384 || row.getValue().chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("invalid_catalog_header");
                }
            }
            this.values = Map.copyOf(values);
        }
        public static Headers none() { return new Headers(Map.of()); }
        /** Same package native host only; preserve the exact inference auth headers, never infer another scheme. */
        static Headers inference(Map<String,String> values) { return new Headers(values); }
        private static String credential(String key) {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("catalog_credential_required");
            return key;
        }
        public static Headers bearer(String key) { return new Headers(Map.of("Authorization", "Bearer " + credential(key))); }
        public static Headers openAi(String key, String organizationId, String projectId) {
            Map<String, String> values = new LinkedHashMap<>(); values.put("Authorization", "Bearer " + credential(key));
            if (organizationId != null && !organizationId.isEmpty()) values.put("OpenAI-Organization", organizationId);
            if (projectId != null && !projectId.isEmpty()) values.put("OpenAI-Project", projectId);
            return new Headers(values);
        }
        public static Headers anthropic(String key, String apiVersion, String workspaceId) {
            return anthropicAuth("x-api-key", credential(key), apiVersion, workspaceId);
        }
        public static Headers anthropicBearer(String key, String apiVersion, String workspaceId) {
            return anthropicAuth("Authorization", "Bearer " + credential(key), apiVersion, workspaceId);
        }
        private static Headers anthropicAuth(String name, String value, String apiVersion, String workspaceId) {
            if (apiVersion == null || !apiVersion.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("explicit_anthropic_version_required");
            Map<String, String> values = new LinkedHashMap<>(); values.put(name, value); values.put("anthropic-version", apiVersion);
            if (workspaceId != null && !workspaceId.isEmpty()) values.put("anthropic-workspace-id", workspaceId);
            return new Headers(values);
        }
        public static Headers gemini(String key) { return new Headers(Map.of("x-goog-api-key", credential(key))); }
        @Override public String toString() { return "CatalogHeaders[redacted]"; }
    }

    public record Snapshot(ModelBinding binding, long fetchedAtMillis, Map<String, ModelFacts> models,Map<String,ModelPrice> prices,Map<String,ModelEndpointSupport> endpointSupport) {
        public Snapshot(ModelBinding binding,long fetchedAtMillis,Map<String,ModelFacts> models){this(binding,fetchedAtMillis,models,Map.of(),Map.of());}
        public Snapshot(ModelBinding binding,long fetchedAtMillis,Map<String,ModelFacts> models,Map<String,ModelPrice> prices){this(binding,fetchedAtMillis,models,prices,Map.of());}
        public Snapshot { models = Collections.unmodifiableMap(new LinkedHashMap<>(models));prices=Collections.unmodifiableMap(new LinkedHashMap<>(prices));endpointSupport=Collections.unmodifiableMap(new LinkedHashMap<>(endpointSupport)); }
    }

    public CatalogHttpClient(Clock clock, int timeoutMillis) {
        this.clock = java.util.Objects.requireNonNull(clock);
        if (timeoutMillis < 100 || timeoutMillis > 30000) throw new IllegalArgumentException("invalid_catalog_timeout");
        this.timeoutMillis = timeoutMillis;
        transport = new OkHttpClient.Builder().connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS).callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }

    int totalBudgetMillis() { return timeoutMillis; }

    public Snapshot fetch(ModelBinding binding, Headers headers, BooleanSupplier stillCurrent) throws CatalogException {
        Map<String, ModelFacts> models = new LinkedHashMap<>();Map<String,ModelPrice> prices=new LinkedHashMap<>();Map<String,ModelEndpointSupport> endpointSupport=new LinkedHashMap<>(); Set<URI> visited = new HashSet<>();
        URI at = binding.modelsEndpoint(); int bytes = 0;
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        for (int page = 1; page <= MAX_PAGES; page++) {
            requireCurrent(stillCurrent);
            if (!visited.add(at)) throw new CatalogException(CatalogException.Code.PAGINATION_INCOMPLETE);
            remainingMillis(deadline);
            byte[] body = request(at, headers, deadline, stillCurrent);
            bytes += body.length;
            if (bytes > TOTAL_BYTES) throw new CatalogException(CatalogException.Code.TOO_LARGE);
            final String decoded;
            try { decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString(); }
            catch (Exception malformed) { throw new CatalogException(CatalogException.Code.INVALID_CATALOG); }
            CatalogParser.Page parsed = CatalogParser.parse(binding, decoded, clock.millis(), page);
            for (Map.Entry<String, ModelFacts> row : parsed.models().entrySet()) {
                if (models.put(row.getKey(), row.getValue()) != null) throw new CatalogException(CatalogException.Code.PAGINATION_INCOMPLETE);
            }
            prices.putAll(parsed.prices());
            endpointSupport.putAll(parsed.endpointSupport());
            if (models.size() > 10000) throw new CatalogException(CatalogException.Code.TOO_LARGE);
            requireCurrent(stillCurrent);
            if (parsed.pagination() == CatalogParser.Pagination.NONE) { remainingMillis(deadline); return new Snapshot(binding, clock.millis(), models,prices,endpointSupport); }
            at = next(binding.modelsEndpoint(), parsed);
        }
        throw new CatalogException(CatalogException.Code.PAGINATION_INCOMPLETE);
    }

    private byte[] request(URI endpoint, Headers headers, long deadline, BooleanSupplier current) throws CatalogException {
        return request(endpoint,headers,deadline,current,PAGE_BYTES);
    }
    /** Explicit public metadata fetch. The fixed origin never receives model IDs, API keys or account headers. */
    public String modelsDev(BooleanSupplier current)throws CatalogException{
        long deadline=System.nanoTime()+timeoutMillis*1_000_000L;
        byte[] bytes=request(URI.create(ModelMetadataSuggestions.SOURCE),Headers.none(),deadline,current,ModelMetadataSuggestions.MAX_DOCUMENT_BYTES);
        requireCurrent(current);remainingMillis(deadline);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(Exception invalid){throw new CatalogException(CatalogException.Code.INVALID_CATALOG);}
    }
    private byte[] request(URI endpoint, Headers headers, long deadline, BooleanSupplier current,int maxBytes) throws CatalogException {
        Call call = null;
        Thread cancellation = null;
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean invalidated = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            Request.Builder request = new Request.Builder().url(endpoint.toURL()).get()
                    .header("Accept", "application/json").header("Accept-Encoding", "identity");
            for (Map.Entry<String, String> header : headers.values.entrySet()) request.header(header.getKey(), header.getValue());
            requireCurrent(current);
            call = transport.newCall(request.build());
            // A socket read timeout alone resets on every timely header byte. The call's
            // watchdog covers connect, response headers and body against one fetch deadline.
            call.timeout().timeout(remainingMillis(deadline), TimeUnit.MILLISECONDS).deadlineNanoTime(deadline);
            Call pending = call; Thread caller = Thread.currentThread();
            cancellation = new Thread(() -> {
                try {
                    while (!finished.get()) {
                        if (caller.isInterrupted() || !current.getAsBoolean()) {
                            invalidated.set(true); pending.cancel(); return;
                        }
                        Thread.sleep(50);
                    }
                } catch (InterruptedException completed) { /* This page has finished. */ }
                catch (RuntimeException unavailable) { invalidated.set(true); pending.cancel(); }
            }, "pocket-catalog-cancel");
            cancellation.setDaemon(true); cancellation.start();
            try (Response response = call.execute()) {
                requireCurrent(current); remainingMillis(deadline);
                int status = response.code();
                if (status == 401 || status == 403) throw new CatalogException(CatalogException.Code.AUTH_REQUIRED, status);
                if (status == 429) throw new CatalogException(CatalogException.Code.RATE_LIMITED, status);
                if (status >= 300 && status < 400) throw new CatalogException(CatalogException.Code.REDIRECT_REFUSED, status);
                if (status < 200 || status >= 300) throw new CatalogException(CatalogException.Code.HTTP_ERROR, status);
                ResponseBody body = response.body();
                if (body == null) throw new CatalogException(CatalogException.Code.INVALID_CATALOG);
                if (body.contentLength() > maxBytes) throw new CatalogException(CatalogException.Code.TOO_LARGE);
                String type = response.header("Content-Type");
                if (type != null && !type.toLowerCase(java.util.Locale.ROOT).matches("application/(json|[a-z0-9.+-]+\\+json)(;.*)?")) {
                    throw new CatalogException(CatalogException.Code.INVALID_CATALOG);
                }
                try (InputStream input = body.byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while (true) {
                        requireCurrent(current); remainingMillis(deadline);
                        count = input.read(buffer);
                        if (count == -1) break;
                        if (count > maxBytes - out.size()) throw new CatalogException(CatalogException.Code.TOO_LARGE);
                        out.write(buffer, 0, count);
                    }
                    if (invalidated.get()) throw new CatalogException(CatalogException.Code.CANCELLED);
                    return out.toByteArray();
                }
            }
        } catch (CatalogException error) { throw error; }
        catch (Exception failure) {
            if (invalidated.get()) throw new CatalogException(CatalogException.Code.CANCELLED);
            requireCurrent(current); throw new CatalogException(CatalogException.Code.NETWORK_ERROR);
        }
        finally {
            finished.set(true);
            if (cancellation != null) cancellation.interrupt();
            if (call != null) call.cancel();
        }
    }

    private static int remainingMillis(long deadline) throws CatalogException {
        long remaining = (deadline - System.nanoTime()) / 1_000_000L;
        if (remaining < 1) throw new CatalogException(CatalogException.Code.NETWORK_ERROR);
        return (int) Math.min(30000, remaining);
    }

    private static void requireCurrent(BooleanSupplier current) throws CatalogException {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) throw new CatalogException(CatalogException.Code.CANCELLED);
    }

    private static URI next(URI base, CatalogParser.Page page) throws CatalogException {
        try {
            if (page.pagination() == CatalogParser.Pagination.SAME_ENDPOINT_LINK) {
                // URI.resolve("?page=2") can replace the final path segment on JDK/Android.
                URI link = page.cursor().startsWith("?") ? URI.create(base.toASCIIString() + page.cursor()) : base.resolve(page.cursor());
                if (link.getUserInfo() != null || link.getFragment() != null || !base.getScheme().equalsIgnoreCase(link.getScheme())
                        || !base.getHost().equalsIgnoreCase(link.getHost()) || port(base) != port(link) || !base.getRawPath().equals(link.getRawPath())) {
                    throw new IllegalArgumentException();
                }
                return link;
            }
            String cursor = URLEncoder.encode(page.cursor(), "UTF-8");
            return URI.create(base.toASCIIString() + (page.pagination() == CatalogParser.Pagination.GEMINI_TOKEN
                    ? "?pageSize=1000&pageToken=" + cursor : "?limit=1000&after_id=" + cursor));
        } catch (Exception invalid) { throw new CatalogException(CatalogException.Code.PAGINATION_INCOMPLETE); }
    }

    private static int port(URI uri) { return uri.getPort() < 0 ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80) : uri.getPort(); }
}
