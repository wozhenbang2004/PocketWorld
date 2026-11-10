package dev.pocketengine.runtime.models;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static dev.pocketengine.runtime.models.ModelFacts.*;

/**
 * App-private public model index. Its content is advisory metadata, not proof
 * that a particular API gateway implements each model option.
 *
 * A failed refresh never replaces the last complete snapshot. No credentials
 * or inference requests are used to retrieve these records.
 */
final class ModelKnowledgeRegistry {
    static final String ENDPOINT = "https://models.dev/api.json?type=all";
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final long REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long RETRY_INTERVAL_MS = 4L * 60 * 60 * 1000;
    private static final Set<String> ALLOWED_EFFORT = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

    private record Entry(String modelId, boolean reasoning, List<String> effort, boolean toggle, boolean budget,
                         String canonicalId, String name) { }
    private record Provider(String id, String apiHost, Map<String, Entry> models) { }
    private record Index(long updatedAt, Map<String, Provider> providers, int modelCount) {
        static Index empty() { return new Index(0, Map.of(), 0); }
    }

    private final Path file;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Index index = Index.empty();
    private volatile long attemptedAt;
    private volatile String status = "not_loaded";

    ModelKnowledgeRegistry(Path file, Clock clock) {
        this.file = file.toAbsolutePath();
        this.clock = clock;
        try {
            if (Files.isRegularFile(this.file)) {
                byte[] bytes;
                try (InputStream in = new GZIPInputStream(Files.newInputStream(this.file))) {
                    bytes = readBounded(in);
                }
                index = parse(bytes, Files.getLastModifiedTime(this.file).toMillis());
                status = "cached";
            }
        } catch (Exception ignored) {
            // Preserve the original unreadable file for owner diagnostics.
            status = "cache_unreadable";
        }
    }

    JSONObject status() throws org.json.JSONException {
        Index value = index;
        return new JSONObject().put("available", value.modelCount > 0)
                .put("models", value.modelCount).put("providers", value.providers.size())
                .put("updatedAt", value.updatedAt).put("state", status)
                .put("refreshing", refreshing.get());
    }

    void refreshIfDueAsync() {
        long now = clock.millis();
        if (index.updatedAt > 0 && now - index.updatedAt < REFRESH_INTERVAL_MS) return;
        if (attemptedAt > 0 && now - attemptedAt < RETRY_INTERVAL_MS) return;
        if (!refreshing.compareAndSet(false, true)) return;
        attemptedAt = now;
        Thread worker = new Thread(() -> {
            try {
                byte[] bytes = download();
                Index downloaded = parse(bytes, clock.millis());
                if (downloaded.modelCount == 0) throw new IllegalStateException("empty_model_index");
                persist(bytes);
                index = downloaded;
                status = "ready";
            } catch (Exception ignored) {
                status = index.modelCount > 0 ? "offline_cache" : "unavailable";
            } finally {
                refreshing.set(false);
            }
        }, "model-index-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /** Fill only facts that are unknown to the model's own provider. */
    ModelFacts enrich(ModelBinding binding, String modelId, ModelFacts facts) {
        ProviderMatch match = resolve(binding, modelId);
        if (match == null) return facts;
        Entry entry = match.entry;
        EnumMap<Field, Fact<?>> inferred = new EnumMap<>(Field.class);
        String reference = "public-model-index:" + match.provider.id + "/" + entry.modelId;
        if (!facts.reasoning().known)
            inferred.put(Field.REASONING, Fact.known(entry.reasoning, Source.PUBLIC_REGISTRY, reference, index.updatedAt));
        if (entry.reasoning && !facts.reasoningLevels().known) {
            List<String> levels = entry.effort;
            if (entry.toggle && ModelReasoningProfiles.supportsThinkingSwitch(binding, modelId)
                    && !levels.contains("none")) {
                List<String> expanded = new ArrayList<>();
                expanded.add("disabled");
                expanded.addAll(levels);
                if (levels.isEmpty()) expanded.add("enabled");
                levels = expanded;
            }
            if (!levels.isEmpty())
                inferred.put(Field.REASONING_LEVELS, Fact.known(levels, Source.PUBLIC_REGISTRY, reference, index.updatedAt));
        }
        return inferred.isEmpty() ? facts : facts.withOverrides(inferred);
    }

    private record ProviderMatch(Provider provider, Entry entry) { }
    private ProviderMatch resolve(ModelBinding binding, String requestedId) {
        Index data = index;
        if (data.providers.isEmpty()) return null;
        String bare = requestedId;
        String explicitProvider = "";
        int slash = requestedId.indexOf('/');
        if (slash > 0 && slash < 80 && data.providers.containsKey(requestedId.substring(0, slash))) {
            explicitProvider = requestedId.substring(0, slash);
            bare = requestedId.substring(slash + 1);
        }
        if (!explicitProvider.isEmpty()) {
            Provider provider = data.providers.get(explicitProvider);
            Entry entry = provider.models.get(bare);
            if (entry != null) return new ProviderMatch(provider, entry);
        }
        String endpointHost = binding.modelsEndpoint().getHost();
        endpointHost = endpointHost == null ? "" : endpointHost.toLowerCase(Locale.ROOT);
        String preferred = firstParty(endpointHost, binding.protocol(), bare);
        if (!preferred.isEmpty()) {
            Provider provider = data.providers.get(preferred);
            Entry entry = provider == null ? null : provider.models.get(bare);
            if (entry != null) return new ProviderMatch(provider, entry);
        }
        for (Provider provider : data.providers.values()) {
            if (provider.apiHost.isEmpty() || !provider.apiHost.equals(endpointHost)) continue;
            Entry entry = provider.models.get(bare);
            if (entry != null) return new ProviderMatch(provider, entry);
        }
        // A named model from a well-defined family can use its exact native
        // registry record through a compatible gateway, even if the gateway
        // hostname is not the model creator's official endpoint.
        for (String providerId : familyProviders(bare, binding.protocol())) {
            Provider provider = data.providers.get(providerId);
            Entry entry = provider == null ? null : provider.models.get(bare);
            if (entry != null) return new ProviderMatch(provider, entry);
        }
        return null;
    }

    private static List<String> familyProviders(String id, ModelBinding.Protocol protocol) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (protocol == ModelBinding.Protocol.GEMINI) return List.of("google");
        if (lower.startsWith("deepseek-")) return List.of("deepseek");
        if (lower.startsWith("kimi-") || lower.startsWith("moonshot")) return List.of("moonshotai", "moonshotai-cn");
        if (lower.startsWith("qwen") || lower.startsWith("qwq") || lower.startsWith("qvq"))
            return List.of("alibaba", "alibaba-cn");
        if (lower.startsWith("glm-")) return List.of("zai", "zhipuai");
        if (lower.startsWith("minimax-")) return List.of("minimax", "minimax-cn");
        if (lower.startsWith("doubao-") || lower.startsWith("seed-")) return List.of("volcengine");
        if (lower.startsWith("gpt-") || lower.startsWith("o1-") || lower.startsWith("o3-") || lower.startsWith("o4-"))
            return List.of("openai");
        if (lower.startsWith("claude-")) return List.of("anthropic");
        return List.of();
    }

    private static String firstParty(String host, ModelBinding.Protocol protocol, String model) {
        if (host.equals("api.openai.com")) return "openai";
        if (host.equals("api.anthropic.com")) return "anthropic";
        if (host.equals("generativelanguage.googleapis.com")) return "google";
        if (host.equals("api.deepseek.com")) return "deepseek";
        if (host.equals("api.moonshot.ai")) return "moonshotai";
        if (host.equals("api.moonshot.cn")) return "moonshotai-cn";
        if (host.equals("api.minimax.io")) return "minimax";
        if (host.equals("api.minimaxi.com")) return "minimax-cn";
        if (host.equals("api.z.ai")) return "zai";
        if (host.equals("open.bigmodel.cn")) return "zhipuai";
        if (host.equals("dashscope.aliyuncs.com") || host.endsWith(".dashscope.aliyuncs.com")) return "alibaba-cn";
        if (host.equals("ark.cn-beijing.volces.com")) return "volcengine";
        if (protocol == ModelBinding.Protocol.GEMINI) return "google";
        if (protocol == ModelBinding.Protocol.ANTHROPIC && model.startsWith("claude-")) return "anthropic";
        // Other endpoints must use their exact declared provider instead of
        // being treated as an official API merely because their model IDs match.
        return "";
    }

    private static Index parse(byte[] bytes, long refreshedAt) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("model_index_size");
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        JSONTokener reader = new JSONTokener(json);
        Object parsed = reader.nextValue();
        if (!(parsed instanceof JSONObject root) || reader.nextClean() != 0) throw new IllegalArgumentException("model_index_json");
        // Files cached locally use the source document as-is, so a full schema
        // refresh does not require an application update.
        Map<String, Provider> providers = new LinkedHashMap<>();
        int count = 0;
        for (java.util.Iterator<String> keys = root.keys(); keys.hasNext();) {
            String providerId = keys.next();
            JSONObject provider = root.optJSONObject(providerId);
            JSONObject models = provider == null ? null : provider.optJSONObject("models");
            if (models == null || providerId.length() > 100) continue;
            String apiHost = "";
            String api = provider.optString("api", "");
            if (!api.isEmpty()) try {
                URI uri = URI.create(api);
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null)
                    apiHost = uri.getHost().toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) { }
            Map<String, Entry> entries = new HashMap<>();
            for (java.util.Iterator<String> keys = models.keys(); keys.hasNext();) {
                String modelId = keys.next();
                if (modelId.isEmpty() || modelId.length() > 300) continue;
                JSONObject row = models.optJSONObject(modelId);
                if (row == null || !row.has("reasoning") || !(row.opt("reasoning") instanceof Boolean)) continue;
                boolean reasoning = row.optBoolean("reasoning", false);
                List<String> efforts = new ArrayList<>();
                boolean toggle = false, budget = false;
                JSONArray options = row.optJSONArray("reasoning_options");
                if (options != null) for (int k = 0; k < options.length() && k < 20; k++) {
                    JSONObject option = options.optJSONObject(k);
                    if (option == null) continue;
                    switch (option.optString("type", "")) {
                        case "effort" -> {
                            JSONArray levels = option.optJSONArray("values");
                            if (levels == null) break;
                            for (int n = 0; n < levels.length() && n < 32; n++) {
                                Object value = levels.opt(n);
                                if (value instanceof String level && ALLOWED_EFFORT.contains(level) && !efforts.contains(level))
                                    efforts.add(level);
                            }
                        }
                        case "toggle" -> toggle = true;
                        case "budget_tokens" -> budget = true;
                    }
                }
                entries.put(modelId, new Entry(modelId, reasoning, List.copyOf(efforts), toggle, budget,
                        row.optString("canonical_model_id", ""), row.optString("name", "")));
                count++;
            }
            if (!entries.isEmpty()) providers.put(providerId, new Provider(providerId, apiHost, Map.copyOf(entries)));
        }
        if (count == 0) throw new IllegalArgumentException("model_index_empty");
        return new Index(refreshedAt, Collections.unmodifiableMap(providers), count);
    }

    private static byte[] download() throws Exception {
        URL source = URI.create(ENDPOINT).toURL();
        HttpURLConnection connection = (HttpURLConnection) source.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("model_index_http");
            String encoding = connection.getHeaderField("Content-Encoding");
            try (InputStream stream = "gzip".equalsIgnoreCase(encoding)
                    ? new GZIPInputStream(connection.getInputStream()) : connection.getInputStream()) {
                return readBounded(stream);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream stream) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream(1024 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int len;
        while ((len = stream.read(buffer)) != -1) {
            if (result.size() + len > MAX_BYTES) throw new IllegalArgumentException("model_index_too_large");
            result.write(buffer, 0, len);
        }
        return result.toByteArray();
    }

    private void persist(byte[] bytes) throws Exception {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "model-index-", ".tmp");
        try {
            try (GZIPOutputStream zipped = new GZIPOutputStream(Files.newOutputStream(temp))) {
                zipped.write(bytes);
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
