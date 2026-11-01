package dev.pocketengine.features.plugins.catalog;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact JS source and native approvals only. This class never creates a JS isolate or calls describe/hooks. */
public final class ExecutablePluginCatalog implements AutoCloseable {
    public static final String FORMAT = "pocketworld.executable-js-catalog.v1";
    private static final String STORE = "pocketworld.executable-js-installations.v1";
    private final PluginCatalog configuredSources;
    private final File stateFile;
    private final File packageDirectory;
    private static final int HISTORY_PER_PLUGIN = 3;
    private final CatalogHttp http = new CatalogHttp();
    private final Map<String, Long> requests = new HashMap<>();
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private boolean closed;
    public ExecutablePluginCatalog(PluginCatalog configuredSources, File privateStateFile) {
        this.configuredSources = java.util.Objects.requireNonNull(configuredSources);
        stateFile = java.util.Objects.requireNonNull(privateStateFile);
        packageDirectory = new File(stateFile.getParentFile(), stateFile.getName() + ".packages");
    }

    public static final class Snapshot {
        private final ExecutablePluginCatalog owner;
        private final String sourceId, sourceJson, configRevision, entriesJson;
        private final long request, at = System.nanoTime();
        public final String catalogSha256;
        private Snapshot(ExecutablePluginCatalog owner, JSONObject source, String revision, long request, JSONArray entries, byte[] bytes) throws Exception {
            this.owner = owner; sourceId = source.getString("id"); sourceJson = source.toString(); configRevision = revision;
            this.request = request; entriesJson = entries.toString(); catalogSha256 = CatalogJson.hash(bytes);
        }
        public JSONObject view() throws Exception {
            return new JSONObject().put("format", FORMAT).put("kind", "executable_js").put("source", new JSONObject(sourceJson))
                    .put("catalogSha256", catalogSha256).put("entries", new JSONArray(entriesJson)).put("untrusted", true)
                    .put("executed", false).put("capabilitiesVerified", false).put("publisherVerified", false);
        }
    }

    public Snapshot refresh(String sourceId, RunControl control) throws Exception {
        JSONObject config = configuredSources.sources(), source = source(config, sourceId); final long request;
        synchronized (this) { open(); control.check(); request = requests.getOrDefault(sourceId, 0L) + 1; requests.put(sourceId, request); snapshots.remove(sourceId); }
        byte[] bytes = http.get(source.getString("catalogUrl"), PluginCatalog.MAX_CATALOG_BYTES, control);
        if (source.has("pinnedCatalogSha256") && !source.getString("pinnedCatalogSha256").equals(CatalogJson.hash(bytes))) throw new SecurityException("JS目录摘要与主人固定摘要不符");
        JSONObject data = CatalogJson.read(bytes); CatalogJson.fields(data, "format", "entries");
        if (!FORMAT.equals(data.getString("format"))) throw new IllegalArgumentException("请选择独立的JavaScript插件目录，声明包目录保持原流程");
        JSONArray entries = data.getJSONArray("entries"), normalized = new JSONArray(); Set<String> ids = new HashSet<>();
        if (entries.length() > 100) throw new IllegalArgumentException("JS目录最多100个明确版本");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i); CatalogJson.fields(entry, "id", "version", "packageUrl", "packageSha256", "sourceSha256", "byteSize");
            String id = CatalogJson.text(entry, "id", 80), version = CatalogJson.text(entry, "version", 32);
            if (!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}") || !ids.add(id + "\n" + version)) throw new IllegalArgumentException("JS目录ID/版本无效或重复");
            String url = CatalogSources.url(CatalogJson.text(entry, "packageUrl", 2000));
            if (!CatalogSources.sameOrigin(source.getString("catalogUrl"), url)) throw new SecurityException("JS包需与所选目录同源");
            normalized.put(new JSONObject().put("id", id).put("version", version).put("packageUrl", url)
                    .put("packageSha256", CatalogJson.digest(entry, "packageSha256")).put("sourceSha256", CatalogJson.digest(entry, "sourceSha256"))
                    .put("byteSize", CatalogJson.integer(entry, "byteSize", 1, JsPluginPackage.MAX_PACKAGE_BYTES)));
        }
        synchronized (this) {
            open(); control.check();
            if (!config.getString("revision").equals(configuredSources.sources().getString("revision")) || !Long.valueOf(request).equals(requests.get(sourceId))) throw stale();
            Snapshot snapshot = new Snapshot(this, source, config.getString("revision"), request, normalized, bytes); snapshots.put(sourceId, snapshot); return snapshot;
        }
    }

    public static final class Inspection {
        public final String reviewDigest;
        private final ExecutablePluginCatalog owner;
        private final JsPluginPackage packaged;
        private final Snapshot snapshot;
        private final String expectedInstallationId, json, restoreInstallation;
        private final long expectedRevision;
        private boolean consumed;
        private Inspection(ExecutablePluginCatalog owner, JsPluginPackage packaged, Snapshot snapshot, JSONObject previous) throws Exception {
            this(owner, packaged, snapshot, previous, null);
        }
        private Inspection(ExecutablePluginCatalog owner, JsPluginPackage packaged, Snapshot snapshot, JSONObject previous, JSONObject restoring) throws Exception {
            this.owner = owner; this.packaged = packaged; this.snapshot = snapshot;
            restoreInstallation = restoring == null ? "" : restoring.getString("installationId");
            expectedInstallationId = previous == null ? "" : previous.getString("installationId"); expectedRevision = previous == null ? 0 : previous.getLong("approvalRevision");
            JSONObject data = new JSONObject().put("kind", "executable_js").put("id", packaged.id).put("name", packaged.name).put("version", packaged.version)
                    .put("sourceSha256", packaged.sourceSha256).put("packageSha256", packaged.packageSha256).put("byteSize", packaged.byteSize)
                    .put("source", packaged.sourceDisplay()).put("executed", false).put("approved", false).put("enabled", false)
                    .put("current", previous == null ? JSONObject.NULL : publicRow(previous));
            if (restoring != null) data.put("restoring", true).put("restoredFrom", new JSONObject(restoring.toString())).put("permissionsRestored", false);
            if (snapshot != null) data.put("catalogSha256", snapshot.catalogSha256).put("catalogSource", new JSONObject(snapshot.sourceJson));
            reviewDigest = CatalogJson.hash(CatalogJson.canonical(data).getBytes(StandardCharsets.UTF_8)); json = data.put("reviewDigest", reviewDigest).toString();
        }
        public JSONObject view() throws Exception { return new JSONObject(json); }
    }
    public Inspection prepare(Snapshot snapshot, String id, String version, RunControl control) throws Exception {
        JSONObject entry = null;
        synchronized (this) {
            current(snapshot); JSONArray entries = new JSONArray(snapshot.entriesJson);
            for (int i = 0; i < entries.length(); i++) if (id.equals(entries.getJSONObject(i).getString("id")) && version.equals(entries.getJSONObject(i).getString("version"))) entry = entries.getJSONObject(i);
        }
        if (entry == null) throw new IllegalArgumentException("请选择目录中的明确JS版本");
        byte[] bytes = http.get(entry.getString("packageUrl"), JsPluginPackage.MAX_PACKAGE_BYTES, control);
        if (bytes.length != entry.getInt("byteSize") || !entry.getString("packageSha256").equals(CatalogJson.hash(bytes))) throw new SecurityException("JS包字节或摘要与目录不符");
        JsPluginPackage pkg = JsPluginPackage.read(new ByteArrayInputStream(bytes), control);
        if (!id.equals(pkg.id) || !version.equals(pkg.version) || !entry.getString("sourceSha256").equals(pkg.sourceSha256)) throw new SecurityException("JS包身份或源码摘要与目录不符");
        synchronized (this) { current(snapshot); control.check(); return new Inspection(this, pkg, snapshot, find(read(), id)); }
    }
    /** A native SAF result may also be reviewed, still without executing any source. */
    public synchronized Inspection inspect(JsPluginPackage packaged, RunControl control) throws Exception {
        open(); control.check(); return new Inspection(this, packaged, null, find(read(), packaged.id));
    }
    public synchronized JSONObject installReviewed(Inspection inspected, String digest, RunControl control) throws Exception {
        open(); control.check();
        if (inspected == null || inspected.owner != this || inspected.consumed || !inspected.reviewDigest.equals(digest)) throw new SecurityException("需要当前JS源码审阅");
        if (inspected.snapshot != null) current(inspected.snapshot);
        JSONObject state = read(), old = find(state, inspected.packaged.id); expected(old, inspected.expectedInstallationId, inspected.expectedRevision);
        JSONArray rows = state.getJSONArray("plugins");
        if (old == null && rows.length() >= 30) throw new IllegalStateException("最多安装30个JS扩展");
        JsPluginPackage pkg = inspected.packaged;
        if (!inspected.restoreInstallation.isEmpty()) {
            JSONObject retained = historyRow(state, pkg.id, inspected.restoreInstallation);
            if (retained == null || !pkg.packageSha256.equals(retained.optString("packageSha256"))) throw stale();
            readPackage(retained, control); // Exact retained bytes must still exist at the commit boundary.
        }
        cachePackage(pkg, control);
        if (old != null) archive(state, old);
        long revision = next(state);
        JSONObject row = new JSONObject().put("id", pkg.id).put("name", pkg.name).put("version", pkg.version).put("source", pkg.sourceDisplay())
                .put("sourceSha256", pkg.sourceSha256).put("packageSha256", pkg.packageSha256).put("packageByteSize", pkg.byteSize).put("installationId", UUID.randomUUID().toString()).put("approvalRevision", revision)
                .put("enabled", false).put("approved", false);
        replace(state, row); control.check(); inspected.consumed = true;
        try { JsonFiles.write(stateFile, state); }
        catch (Exception uncertain) { return unknown(); }
        cleanupPackages(state);
        return new JSONObject().put("status", "installed_unapproved").put("installed", true).put("plugin", publicRow(row))
                .put("restored", !inspected.restoreInstallation.isEmpty()).put("permissionsRestored", false).put("executed", false).put("replayAllowed", false);
    }

    public static final class InstalledReview {
        public final String reviewDigest;
        private final ExecutablePluginCatalog owner;
        private final String rowJson;
        private boolean consumed;
        private InstalledReview(ExecutablePluginCatalog owner, JSONObject row) throws Exception {
            this.owner = owner; rowJson = row.toString(); reviewDigest = CatalogJson.hash(CatalogJson.canonical(row).getBytes(StandardCharsets.UTF_8));
        }
        public JSONObject view() throws Exception { return new JSONObject(rowJson).put("reviewDigest", reviewDigest).put("executed", false).put("kind", "executable_js"); }
    }
    public synchronized InstalledReview reviewInstalled(String id, RunControl control) throws Exception {
        open(); control.check(); JSONObject row = find(read(), id);
        if (row == null) throw new IllegalArgumentException("JS插件不存在");
        return new InstalledReview(this, row);
    }
    public synchronized JSONObject approveReviewed(InstalledReview reviewed, String digest, boolean approved, RunControl control) throws Exception {
        return change(reviewed, digest, approved, null, control);
    }
    public synchronized JSONObject setEnabledReviewed(InstalledReview reviewed, String digest, boolean enabled, RunControl control) throws Exception {
        return change(reviewed, digest, null, enabled, control);
    }
    /** Exact native uninstall; old approvals and run-bound source objects cannot become current again. */
    public synchronized JSONObject removeReviewed(InstalledReview reviewed, String digest, RunControl control) throws Exception {
        open(); control.check();
        if (reviewed == null || reviewed.owner != this || reviewed.consumed || !reviewed.reviewDigest.equals(digest))
            throw new SecurityException("需要当前安装的原生卸载审阅对象");
        JSONObject state = read(), selected = new JSONObject(reviewed.rowJson), row = find(state, selected.getString("id"));
        expected(row, selected.getString("installationId"), selected.getLong("approvalRevision"));
        JSONArray retained = new JSONArray(), rows = state.getJSONArray("plugins");
        for (int i = 0; i < rows.length(); i++) if (!row.getString("id").equals(rows.getJSONObject(i).getString("id"))) retained.put(rows.getJSONObject(i));
        JSONArray history = new JSONArray(), previousHistory = state.getJSONArray("history");
        for (int i = 0; i < previousHistory.length(); i++) if (!row.getString("id").equals(previousHistory.getJSONObject(i).getString("id"))) history.put(previousHistory.getJSONObject(i));
        state.put("history", history);
        long revision = next(state); state.put("plugins", retained); control.check(); reviewed.consumed = true;
        try { JsonFiles.write(stateFile, state); } catch (Exception uncertain) { return unknown(); }
        cleanupPackages(state);
        return new JSONObject().put("status", "removed").put("removed", true).put("id", row.getString("id"))
                .put("installationId", row.getString("installationId")).put("revision", revision).put("executed", false).put("replayAllowed", false);
    }
    private JSONObject change(InstalledReview reviewed, String digest, Boolean approved, Boolean enabled, RunControl control) throws Exception {
        open(); control.check();
        if (reviewed == null || reviewed.owner != this || reviewed.consumed || !reviewed.reviewDigest.equals(digest)) throw new SecurityException("需要当前安装的源码审阅");
        JSONObject state = read(), selected = new JSONObject(reviewed.rowJson), row = find(state, selected.getString("id"));
        expected(row, selected.getString("installationId"), selected.getLong("approvalRevision"));
        if (approved != null) { row.put("approved", approved); if (!approved) row.put("enabled", false); }
        if (enabled != null) { if (enabled && !row.getBoolean("approved")) throw new SecurityException("先阅读源码并批准，再启用"); row.put("enabled", enabled); }
        row.put("approvalRevision", next(state)); control.check(); reviewed.consumed = true;
        try { JsonFiles.write(stateFile, state); } catch (Exception uncertain) { return unknown(); }
        return new JSONObject().put("status", "saved").put("plugin", publicRow(row)).put("executed", false).put("replayAllowed", false);
    }
    public synchronized JSONObject snapshot() throws Exception {
        open(); JSONObject state = read(); JSONArray result = new JSONArray(), rows = state.getJSONArray("plugins");
        for (int i = 0; i < rows.length(); i++) result.put(publicRow(rows.getJSONObject(i)));
        return new JSONObject().put("kind", "executable_js").put("revision", state.getLong("revision")).put("plugins", result).put("executed", false);
    }
    /** Exact approved source data for the real runtime adapter; deliberately not a fabricated ExtensionProgram. */
    public static final class ApprovedSource {
        public final String id, name, version, installationId, sourceSha256;
        public final long approvalRevision;
        private final ExecutablePluginCatalog owner;
        private final byte[] bytes;
        private ApprovedSource(ExecutablePluginCatalog owner, JSONObject row) throws Exception {
            this.owner = owner; id = row.getString("id"); name = row.getString("name"); version = row.getString("version");
            installationId = row.getString("installationId"); sourceSha256 = row.getString("sourceSha256"); approvalRevision = row.getLong("approvalRevision");
            bytes = row.getString("source").getBytes(StandardCharsets.UTF_8);
        }
        public byte[] exactSourceBytes() { return bytes.clone(); }
    }
    public synchronized java.util.List<ApprovedSource> approvedSources(RunControl control) throws Exception {
        open(); control.check(); java.util.List<ApprovedSource> result = new java.util.ArrayList<>(); JSONArray rows = read().getJSONArray("plugins");
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.getJSONObject(i); if (row.getBoolean("approved") && row.getBoolean("enabled")) result.add(new ApprovedSource(this, row)); }
        control.check(); return java.util.List.copyOf(result);
    }
    /** Re-read actual durable approval/source state at every real runtime authority boundary. */
    public synchronized void checkCurrent(ApprovedSource source, RunControl control) throws Exception {
        open(); control.check(); if (source == null || source.owner != this) throw new SecurityException("缺少当前原生JS源码身份");
        JSONObject row = find(read(), source.id);
        if (row == null || !row.getBoolean("approved") || !row.getBoolean("enabled") || !source.version.equals(row.getString("version"))
                || !source.installationId.equals(row.getString("installationId")) || source.approvalRevision != row.getLong("approvalRevision")
                || !source.sourceSha256.equals(row.getString("sourceSha256")) || !java.security.MessageDigest.isEqual(source.bytes, row.getString("source").getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("JS源码、安装或审批已变化；旧任务不能沿用该程序");
        control.check();
    }
    private JSONObject read() throws Exception {
        JSONObject state = JsonFiles.read(stateFile);
        if (state.length() == 0) return new JSONObject().put("format", STORE).put("revision", 0L).put("plugins", new JSONArray()).put("history", new JSONArray());
        if (!state.has("history")) state.put("history", new JSONArray());
        if (!STORE.equals(state.getString("format")) || state.getLong("revision") < 0 || state.getJSONArray("plugins").length() > 30) throw new IllegalStateException("JS安装记录无效");
        Set<String> ids = new HashSet<>(); JSONArray rows = state.getJSONArray("plugins");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); byte[] bytes = row.getString("source").getBytes(StandardCharsets.UTF_8);
            if (!ids.add(row.getString("id")) || !row.getString("id").matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}") || bytes.length < 1 || bytes.length > JsPluginPackage.MAX_SOURCE_BYTES || !CatalogJson.hash(bytes).equals(row.getString("sourceSha256"))
                    || row.getLong("approvalRevision") < 1 || row.getLong("approvalRevision") > state.getLong("revision") || row.getString("installationId").isBlank()
                    || !(row.get("enabled") instanceof Boolean) || !(row.get("approved") instanceof Boolean) || row.getBoolean("enabled") && !row.getBoolean("approved")) throw new SecurityException("JS源码或审批记录不匹配");
            String source = row.getString("source");
            for (int at = 0; at < source.length(); at++) if (Character.isSurrogate(source.charAt(at))
                    && (!Character.isHighSurrogate(source.charAt(at)) || at + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(++at)))) throw new SecurityException("JS源码Unicode无效");
        }
        JSONArray history = state.getJSONArray("history"); Map<String, Integer> counts = new HashMap<>(); Set<String> installations = new HashSet<>();
        if (history.length() > 30 * HISTORY_PER_PLUGIN) throw new SecurityException("JS历史超过保留上限");
        for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.getJSONObject(i); String id = row.getString("id"); int count = counts.getOrDefault(id, 0) + 1; counts.put(id, count);
            if (!ids.contains(id) || count > HISTORY_PER_PLUGIN || !installations.add(row.getString("installationId")) || row.has("source") || row.has("approved") || row.has("enabled")) throw new SecurityException("JS历史身份无效");
            CatalogJson.digest(row, "sourceSha256");
            if (row.has("packageSha256")) { CatalogJson.digest(row, "packageSha256"); CatalogJson.integer(row, "packageByteSize", 1, JsPluginPackage.MAX_PACKAGE_BYTES); }
        }
        return state;
    }
    /** Last three replaced installations. No permission, credential, source evaluation or runtime is restored. */
    public synchronized JSONObject history(String id, RunControl control) throws Exception {
        open(); control.check(); JSONObject state = read(); if (find(state, id) == null) throw new IllegalArgumentException("JS插件不存在");
        JSONArray result = new JSONArray(), rows = state.getJSONArray("history");
        for (int i = rows.length() - 1; i >= 0; i--) if (id.equals(rows.getJSONObject(i).getString("id"))) {
            JSONObject row = new JSONObject(rows.getJSONObject(i).toString()); File file = row.has("packageSha256") ? packageFile(row.getString("packageSha256")) : null;
            boolean available = file != null && file.isFile() && file.length() == row.getLong("packageByteSize");
            result.put(row.put("restorable", available).put("message", available ? "原包文件在本机；审阅时核对准确字节，回退保持未批准、停用" : "旧安装没有可核验的原包字节，不能据源码重造历史包"));
        }
        return new JSONObject().put("id", id).put("versions", result).put("retainedLimit", HISTORY_PER_PLUGIN).put("executed", false);
    }
    public synchronized Inspection prepareRestore(String id, String installationId, RunControl control) throws Exception {
        open(); control.check(); JSONObject state = read(), previous = find(state, id), row = historyRow(state, id, installationId);
        if (previous == null || row == null) throw stale();
        return new Inspection(this, readPackage(row, control), null, previous, row);
    }
    private static JSONObject historyRow(JSONObject state, String id, String installation) throws Exception {
        JSONArray rows = state.getJSONArray("history");
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.getJSONObject(i); if (id.equals(row.getString("id")) && installation.equals(row.getString("installationId"))) return row; }
        return null;
    }
    private void archive(JSONObject state, JSONObject old) throws Exception {
        JSONObject row = new JSONObject().put("replacedAt", System.currentTimeMillis());
        for (String key : new String[]{"id", "name", "version", "sourceSha256", "installationId", "approvalRevision"}) row.put(key, old.get(key));
        if (old.has("packageSha256")) row.put("packageSha256", old.getString("packageSha256")).put("packageByteSize", old.getLong("packageByteSize"));
        JSONArray rows = state.getJSONArray("history"), result = new JSONArray(); int kept = 0;
        // Retain the most recent two predecessors plus this exact replaced installation.
        for (int i = rows.length() - 1; i >= 0; i--) if (old.getString("id").equals(rows.getJSONObject(i).getString("id"))) kept++;
        int drop = Math.max(0, kept - (HISTORY_PER_PLUGIN - 1));
        for (int i = 0; i < rows.length(); i++) { JSONObject previous = rows.getJSONObject(i); if (old.getString("id").equals(previous.getString("id")) && drop > 0) { drop--; continue; } result.put(previous); }
        result.put(row); state.put("history", result);
    }
    private File packageFile(String digest) throws Exception {
        if (!digest.matches("[0-9a-f]{64}")) throw new SecurityException("JS原包摘要无效");
        if (Files.isSymbolicLink(packageDirectory.toPath())) throw new SecurityException("JS原包目录不能是链接");
        File file = new File(packageDirectory, digest + ".pwjs");
        if (Files.isSymbolicLink(file.toPath())) throw new SecurityException("JS原包不能是链接"); return file;
    }
    private JsPluginPackage readPackage(JSONObject row, RunControl control) throws Exception {
        if (!row.has("packageSha256")) throw new IllegalStateException("此旧安装未保留准确原包，不能回退");
        File file = packageFile(row.getString("packageSha256"));
        if (!file.isFile() || file.length() != row.getLong("packageByteSize") || file.length() > JsPluginPackage.MAX_PACKAGE_BYTES) throw new SecurityException("JS历史原包缺失或大小已变化");
        control.check(); JsPluginPackage pkg;
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) { pkg = JsPluginPackage.read(input, control); }
        catch (InterruptedException stopped) { throw stopped; }
        catch (Exception changed) { throw new SecurityException("JS历史原包格式或字节已变化", changed); }
        if (pkg.byteSize != row.getLong("packageByteSize") || !pkg.packageSha256.equals(row.getString("packageSha256"))) throw new SecurityException("JS历史原包摘要已变化");
        if (!pkg.id.equals(row.getString("id")) || !pkg.version.equals(row.getString("version")) || !pkg.sourceSha256.equals(row.getString("sourceSha256"))) throw new SecurityException("JS历史原包身份已变化");
        return pkg;
    }
    private void cachePackage(JsPluginPackage pkg, RunControl control) throws Exception {
        File file = packageFile(pkg.packageSha256); byte[] bytes = pkg.packageBytes(); control.check();
        if (file.isFile()) {
            if (file.length() != bytes.length) throw new SecurityException("JS原包缓存大小不匹配");
            try (java.io.InputStream input = Files.newInputStream(file.toPath())) { if (!JsPluginPackage.read(input, control).packageSha256.equals(pkg.packageSha256)) throw new SecurityException("JS原包缓存摘要不匹配"); }
            return;
        }
        if (!packageDirectory.isDirectory() && !packageDirectory.mkdirs()) throw new java.io.IOException("无法保留JS准确原包");
        File[] files = packageDirectory.listFiles(); long size = 0; if (files != null) for (File existing : files) if (existing.isFile()) size = Math.addExact(size, existing.length());
        if (size + bytes.length > 128L * 1024 * 1024) throw new IllegalStateException("JS历史原包缓存已满；请核对原安装写入结果后再清理");
        File pending = File.createTempFile("pending-", ".tmp", packageDirectory);
        try { try (FileOutputStream out = new FileOutputStream(pending)) { out.write(bytes); out.getFD().sync(); } control.check();
            try { Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE); } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) { Files.move(pending.toPath(), file.toPath()); }
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
    /** Only after a confirmed state write, and only this catalog's named immutable package files. */
    private void cleanupPackages(JSONObject state) {
        try { Set<String> retained = new HashSet<>(); for (String key : new String[]{"plugins", "history"}) { JSONArray rows = state.getJSONArray(key); for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).has("packageSha256")) retained.add(rows.getJSONObject(i).getString("packageSha256") + ".pwjs"); }
            File[] files = packageDirectory.listFiles(); if (files != null) for (File file : files) if (file.getName().matches("[0-9a-f]{64}\\.pwjs") && file.isFile() && !retained.contains(file.getName()) && !Files.isSymbolicLink(file.toPath())) Files.deleteIfExists(file.toPath());
        } catch (Exception ignored) { /* A cache cleanup failure cannot turn a confirmed installation into a retryable write. */ }
    }
    private static JSONObject find(JSONObject state, String id) throws Exception { JSONArray rows = state.getJSONArray("plugins"); for (int i = 0; i < rows.length(); i++) if (id.equals(rows.getJSONObject(i).getString("id"))) return rows.getJSONObject(i); return null; }
    private static void replace(JSONObject state, JSONObject row) throws Exception { JSONArray rows = state.getJSONArray("plugins"), result = new JSONArray(); for (int i = 0; i < rows.length(); i++) if (!row.getString("id").equals(rows.getJSONObject(i).getString("id"))) result.put(rows.getJSONObject(i)); result.put(row); state.put("plugins", result); }
    private static long next(JSONObject state) throws Exception { long next = Math.addExact(state.getLong("revision"), 1L); state.put("revision", next); return next; }
    private static void expected(JSONObject row, String installation, long revision) throws Exception { if (!(row == null ? installation.isEmpty() && revision == 0 : installation.equals(row.getString("installationId")) && revision == row.getLong("approvalRevision"))) throw stale(); }
    private static JSONObject publicRow(JSONObject row) throws Exception { JSONObject result = new JSONObject(row.toString()); result.remove("source"); return result.put("kind", "executable_js"); }
    private static JSONObject unknown() throws Exception { return new JSONObject().put("status", "unknown").put("installed", JSONObject.NULL).put("replayAllowed", false).put("executed", false).put("message", "本地记录回执未确认，请读取当前安装和审批记录，勿重复提交"); }
    private static JSONObject source(JSONObject state, String id) throws Exception { JSONArray rows = state.getJSONArray("sources"); for (int i = 0; i < rows.length(); i++) if (id.equals(rows.getJSONObject(i).getString("id")) && rows.getJSONObject(i).getBoolean("enabled")) return rows.getJSONObject(i); throw new IllegalArgumentException("JS目录来源不存在或已停用"); }
    private void current(Snapshot snapshot) throws Exception { open(); if (snapshot == null || snapshot.owner != this || snapshots.get(snapshot.sourceId) != snapshot || !Long.valueOf(snapshot.request).equals(requests.get(snapshot.sourceId)) || System.nanoTime() - snapshot.at >= TimeUnit.MINUTES.toNanos(10) || !snapshot.configRevision.equals(configuredSources.sources().getString("revision"))) throw stale(); }
    private void open() { if (closed) throw new IllegalStateException("JS目录已关闭"); }
    private static IllegalStateException stale() { return new IllegalStateException("JS安装、审批或目录已变化，请重新读取并审阅"); }
    @Override public synchronized void close() { closed = true; snapshots.clear(); http.close(); }
}
