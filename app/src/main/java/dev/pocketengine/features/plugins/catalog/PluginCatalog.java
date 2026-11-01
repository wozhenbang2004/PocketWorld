package dev.pocketengine.features.plugins.catalog;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.plugins.distribution.PluginPackages;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native catalog leaf. The supplied existing plugins module remains the only installation authority. */
public final class PluginCatalog implements AutoCloseable {
    public static final String FORMAT = "pocketworld.plugin-catalog.v1";
    public static final int MAX_CATALOG_BYTES = 1048576;
    private static final long REVIEW_LIFETIME = TimeUnit.MINUTES.toNanos(10);
    private final EngineModule plugins;
    private final PluginPackages packages;
    private final CatalogSources sources;
    private final CatalogHttp http = new CatalogHttp();
    private final int androidApi;
    private final LongSupplier clock;
    private final Map<String, Long> requests = new HashMap<>();
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private boolean closed;

    public PluginCatalog(EngineModule existingPlugins, File privateSourcesFile, int androidApi) {
        this(existingPlugins, privateSourcesFile, androidApi, System::nanoTime);
    }
    PluginCatalog(EngineModule existingPlugins, File privateSourcesFile, int androidApi, LongSupplier clock) {
        if (androidApi < 1) throw new IllegalArgumentException("需要实际Android API版本");
        plugins = java.util.Objects.requireNonNull(existingPlugins);
        packages = new PluginPackages(plugins); sources = new CatalogSources(privateSourcesFile);
        this.androidApi = androidApi; this.clock = clock;
    }

    public synchronized JSONObject sources() throws Exception { open(); return sources.read(); }
    /** Owner-controlled whole configuration replacement, CAS-bound to the displayed revision. */
    public synchronized JSONObject configure(JSONArray values, String expectedRevision) throws Exception {
        open(); JSONObject result = sources.write(values, expectedRevision);
        snapshots.clear(); requests.clear(); return result;
    }

    public static final class Snapshot {
        public final String sourceId, catalogSha256;
        private final PluginCatalog owner;
        private final String configRevision, sourceJson, entriesJson;
        private final long request, observed;
        private Snapshot(PluginCatalog owner, JSONObject source, String configRevision, long request, byte[] bytes, JSONArray entries) throws Exception {
            this.owner = owner; sourceId = source.getString("id"); sourceJson = source.toString(); this.configRevision = configRevision;
            this.request = request; observed = owner.clock.getAsLong(); catalogSha256 = CatalogJson.hash(bytes); entriesJson = entries.toString();
        }
        /** Catalog claims only. prepare() separately checks every summary against actual package bytes. */
        public JSONObject view() throws Exception {
            return new JSONObject().put("format", FORMAT).put("source", new JSONObject(sourceJson)).put("catalogSha256", catalogSha256)
                    .put("entries", new JSONArray(entriesJson)).put("untrusted", true).put("publisherVerified", false)
                    .put("capabilitiesVerified", false).put("selection", "explicit_version_required");
        }
    }

    /** Synchronous bounded read, to run on the existing native worker; creates no scheduler. */
    public Snapshot refresh(String sourceId, RunControl control) throws Exception {
        final JSONObject state, source; final long request;
        synchronized (this) {
            open(); control.check(); state = sources.read(); source = enabledSource(state, sourceId);
            request = requests.getOrDefault(sourceId, 0L) + 1; requests.put(sourceId, request);
            // Even a failed refresh invalidates the prior review; no stale/offline install fallback.
            snapshots.remove(sourceId);
        }
        byte[] bytes = http.get(source.getString("catalogUrl"), MAX_CATALOG_BYTES, control);
        String digest = CatalogJson.hash(bytes);
        if (source.has("pinnedCatalogSha256") && !source.getString("pinnedCatalogSha256").equals(digest)) throw new SecurityException("目录摘要与主人固定的摘要不符");
        JSONArray entries = entries(CatalogJson.read(bytes), source.getString("catalogUrl"));
        synchronized (this) {
            open(); control.check(); JSONObject now = sources.read();
            if (!state.getString("revision").equals(now.getString("revision")) || !Long.valueOf(request).equals(requests.get(sourceId))) throw stale();
            Snapshot snapshot = new Snapshot(this, source, state.getString("revision"), request, bytes, entries);
            snapshots.put(sourceId, snapshot); return snapshot;
        }
    }

    /** Lists exact versions and compatibility beside actual current installation metadata; no invented version ordering. */
    public synchronized JSONObject candidates(Snapshot snapshot, RunControl control) throws Exception {
        current(snapshot); control.check(); JSONArray installed = plugins.snapshot().getJSONArray("plugins");
        JSONArray result = new JSONArray(), entries = new JSONArray(snapshot.entriesJson);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject row = entries.getJSONObject(i), compatibility = compatibility(row); JSONObject old = null;
            for (int j = 0; j < installed.length(); j++) if (row.getString("id").equals(installed.getJSONObject(j).getString("id"))) old = installed.getJSONObject(j);
            row.put("compatibilityResult", compatibility).put("capabilitiesVerified", false);
            if (old != null) row.put("currentInstallationId", old.optString("installationId")).put("currentVersion", old.getJSONObject("manifest").getString("version"))
                    .put("comparison", row.getString("version").equals(old.getJSONObject("manifest").getString("version")) ? "same_version_label_content_unchecked" : "different_version_label");
            else row.put("comparison", "not_installed");
            result.put(row);
        }
        return snapshot.view().put("entries", result);
    }

    public static final class Review {
        public final String reviewDigest;
        private final PluginCatalog owner;
        private final Snapshot snapshot;
        private final PluginPackages.Inspection inspection;
        private final byte[] bytes;
        private final String json;
        private final AtomicBoolean submitted = new AtomicBoolean();
        private Review(PluginCatalog owner, Snapshot snapshot, JSONObject entry, byte[] bytes, PluginPackages.Inspection inspection) throws Exception {
            this.owner = owner; this.snapshot = snapshot; this.inspection = inspection; this.bytes = bytes.clone();
            JSONObject view = new JSONObject().put("source", new JSONObject(snapshot.sourceJson)).put("catalogSha256", snapshot.catalogSha256)
                    .put("entry", entry).put("packageReview", inspection.review()).put("capabilitiesVerified", true).put("publisherVerified", false)
                    .put("requiresOwnerApproval", true).put("replayAllowed", false);
            reviewDigest = CatalogJson.hash(CatalogJson.canonical(view).getBytes(StandardCharsets.UTF_8));
            json = view.put("reviewDigest", reviewDigest).toString();
        }
        public JSONObject view() throws Exception { return new JSONObject(json).put("submitted", submitted.get()); }
    }

    /** Explicit source/id/version selection; downloads once and retains exactly the verified bytes for installation. */
    public Review prepare(Snapshot snapshot, String id, String version, RunControl control) throws Exception {
        JSONObject entry;
        synchronized (this) {
            current(snapshot); control.check(); entry = find(snapshot, id, version);
            if (!compatibility(entry).getBoolean("compatible")) throw new IllegalStateException("所选版本与当前运行环境不兼容");
        }
        byte[] bytes = http.get(entry.getString("packageUrl"), PluginPackages.MAX_BYTES, control);
        if (bytes.length != entry.getInt("byteSize") || !entry.getString("packageSha256").equals(CatalogJson.hash(bytes))) throw new SecurityException("声明包字节数或摘要与目录不符");
        PluginPackages.Inspection inspection = packages.inspect(new ByteArrayInputStream(bytes), control);
        JSONObject manifest = inspection.review().getJSONObject("preview").getJSONObject("manifest");
        if (!id.equals(manifest.getString("id")) || !version.equals(manifest.getString("version"))
                || !entry.getString("declarationDigest").equals(inspection.declarationDigest)
                || !CatalogJson.canonical(entry.getJSONArray("capabilities")).equals(CatalogJson.canonical(capabilities(manifest)))) throw new SecurityException("目录身份、声明摘要或能力摘要与实际声明包不符");
        synchronized (this) {
            current(snapshot); control.check(); packages.validateInspection(inspection, control);
            return new Review(this, snapshot, entry, bytes, inspection);
        }
    }

    public synchronized String validateReview(Review review, RunControl control) throws Exception {
        review(review); control.check(); packages.validateInspection(review.inspection, control); return review.reviewDigest;
    }

    /** Native owner action after displaying this exact review. No model command, approval boolean or auto-update path. */
    public synchronized JSONObject installReviewed(Review review, String acceptedDigest, RunControl control) throws Exception {
        review(review);
        if (!review.reviewDigest.equals(acceptedDigest)) throw new SecurityException("请审阅并批准当前目录安装差异");
        control.check(); packages.validateInspection(review.inspection, control);
        if (!review.submitted.compareAndSet(false, true)) throw new IllegalStateException("该安装已提交，请核对原结果");
        JSONObject receipt;
        try {
            receipt = packages.importReviewed(new ByteArrayInputStream(review.bytes), review.inspection, review.inspection.reviewDigest, control);
        } catch (Exception uncertain) {
            receipt = new JSONObject().put("status", "unknown").put("installed", JSONObject.NULL).put("replayAllowed", false)
                    .put("message", "安装未取得确定回执，请读取现有插件安装身份；不会再次提交此审阅");
        }
        // An actual receipt wins over cancellation after commit. Preserve unknown; never resubmit a consumed Review.
        return receipt.put("catalogSourceId", review.snapshot.sourceId).put("catalogSha256", review.snapshot.catalogSha256).put("catalogReviewDigest", review.reviewDigest);
    }

    private void review(Review review) throws Exception {
        if (review == null || review.owner != this) throw new SecurityException("缺少当前原生审阅对象");
        current(review.snapshot);
        if (review.submitted.get()) throw new IllegalStateException("该安装已提交，请核对原结果");
    }
    private void current(Snapshot snapshot) throws Exception {
        open();
        if (snapshot == null || snapshot.owner != this || snapshots.get(snapshot.sourceId) != snapshot
                || !Long.valueOf(snapshot.request).equals(requests.get(snapshot.sourceId))
                || clock.getAsLong() - snapshot.observed >= REVIEW_LIFETIME) throw stale();
        JSONObject state = sources.read();
        if (!snapshot.configRevision.equals(state.getString("revision"))) throw stale();
        enabledSource(state, snapshot.sourceId);
    }
    private static JSONObject enabledSource(JSONObject state, String id) throws Exception {
        JSONArray list = state.getJSONArray("sources");
        for (int i = 0; i < list.length(); i++) if (id.equals(list.getJSONObject(i).getString("id"))) {
            JSONObject source = list.getJSONObject(i);
            if (!source.getBoolean("enabled")) throw new IllegalStateException("该目录来源已停用");
            return source;
        }
        throw new IllegalArgumentException("目录来源不存在");
    }
    private static JSONObject find(Snapshot snapshot, String id, String version) throws Exception {
        JSONArray entries = new JSONArray(snapshot.entriesJson);
        for (int i = 0; i < entries.length(); i++) { JSONObject entry = entries.getJSONObject(i); if (id.equals(entry.getString("id")) && version.equals(entry.getString("version"))) return entry; }
        throw new IllegalArgumentException("目录中没有明确选择的插件版本");
    }
    private JSONObject compatibility(JSONObject entry) throws Exception {
        JSONObject required = entry.getJSONObject("compatibility"); JSONArray reasons = new JSONArray();
        if (!PluginPackages.FORMAT.equals(required.getString("packageFormat"))) reasons.put("unsupported_package_format");
        if (required.getInt("minAndroidApi") > androidApi) reasons.put("android_api_too_low");
        JSONArray capabilities = entry.getJSONArray("capabilities");
        for (int i = 0; i < capabilities.length(); i++) {
            JSONObject tool = capabilities.getJSONObject(i); String kind = tool.getString("kind");
            if (!Set.of("echo", "http", "mcp").contains(kind)) reasons.put("unsupported_kind:" + kind);
            if ("mcp".equals(kind) && !Set.of("2026-07-28", "2025-11-25").contains(tool.getString("protocolVersion"))) reasons.put("unsupported_mcp_protocol");
        }
        return new JSONObject().put("compatible", reasons.length() == 0).put("reasons", reasons).put("androidApi", androidApi);
    }

    private static JSONArray entries(JSONObject catalog, String catalogUrl) throws Exception {
        CatalogJson.fields(catalog, "format", "entries");
        if (!FORMAT.equals(catalog.getString("format"))) throw new IllegalArgumentException("不支持此目录版本");
        JSONArray entries = catalog.getJSONArray("entries"), normalized = new JSONArray(); Set<String> identities = new HashSet<>();
        if (entries.length() > 100) throw new IllegalArgumentException("每个目录最多100个明确版本");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            CatalogJson.fields(entry, "id", "version", "packageUrl", "packageSha256", "declarationDigest", "byteSize", "compatibility", "capabilities");
            String id = CatalogJson.text(entry, "id", 80), version = CatalogJson.text(entry, "version", 32);
            if (!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}") || !identities.add(id + "\n" + version)) throw new IllegalArgumentException("目录插件ID或版本重复");
            String url = CatalogSources.url(CatalogJson.text(entry, "packageUrl", 2000));
            if (!CatalogSources.sameOrigin(catalogUrl, url)) throw new SecurityException("声明包需与主人配置的目录同源");
            JSONObject required = entry.getJSONObject("compatibility"); CatalogJson.fields(required, "packageFormat", "minAndroidApi");
            JSONObject compatibility = new JSONObject().put("packageFormat", CatalogJson.text(required, "packageFormat", 100))
                    .put("minAndroidApi", CatalogJson.integer(required, "minAndroidApi", 1, Integer.MAX_VALUE));
            JSONArray tools = entry.getJSONArray("capabilities"), claims = new JSONArray(); Set<String> names = new HashSet<>();
            if (tools.length() < 1 || tools.length() > 12) throw new IllegalArgumentException("目录能力需1至12项");
            for (int j = 0; j < tools.length(); j++) {
                JSONObject tool = tools.getJSONObject(j); CatalogJson.fields(tool, "name", "kind", "effect", "endpoint", "protocolVersion");
                String name = CatalogJson.text(tool, "name", 64), kind = CatalogJson.text(tool, "kind", 32), effect = CatalogJson.text(tool, "effect", 5);
                if (!name.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}") || !names.add(name) || !Set.of("read", "write").contains(effect)) throw new IllegalArgumentException("目录能力摘要无效");
                if (("echo".equals(kind) && !"read".equals(effect)) || ("mcp".equals(kind) && !"write".equals(effect))) throw new IllegalArgumentException("echo/MCP能力效果声明无效");
                JSONObject claim = new JSONObject().put("name", name).put("kind", kind).put("effect", effect);
                if (Set.of("http", "mcp").contains(kind)) claim.put("endpoint", CatalogSources.url(CatalogJson.text(tool, "endpoint", 2000)));
                else if (tool.has("endpoint")) throw new IllegalArgumentException("目录能力含多余地址");
                if ("mcp".equals(kind)) claim.put("protocolVersion", CatalogJson.text(tool, "protocolVersion", 32));
                else if (tool.has("protocolVersion")) throw new IllegalArgumentException("目录能力含多余协议版本");
                claims.put(claim);
            }
            normalized.put(new JSONObject().put("id", id).put("version", version).put("packageUrl", url).put("packageSha256", CatalogJson.digest(entry, "packageSha256"))
                    .put("declarationDigest", CatalogJson.digest(entry, "declarationDigest")).put("byteSize", CatalogJson.integer(entry, "byteSize", 1, PluginPackages.MAX_BYTES))
                    .put("compatibility", compatibility).put("capabilities", claims));
        }
        return normalized;
    }

    private static JSONArray capabilities(JSONObject manifest) throws Exception {
        JSONArray result = new JSONArray(), tools = manifest.getJSONArray("tools");
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i), summary = new JSONObject();
            for (String field : new String[]{"name", "kind", "effect", "endpoint", "protocolVersion"}) if (tool.has(field)) summary.put(field, tool.get(field));
            result.put(summary);
        }
        return result;
    }
    private void open() { if (closed) throw new IllegalStateException("目录已关闭"); }
    private static IllegalStateException stale() { return new IllegalStateException("目录、来源或审阅已变化/过期，请刷新并重新审阅"); }
    @Override public synchronized void close() { closed = true; snapshots.clear(); requests.clear(); http.close(); }
}
