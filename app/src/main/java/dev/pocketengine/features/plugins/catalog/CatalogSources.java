package dev.pocketengine.features.plugins.catalog;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import dev.pocketengine.features.plugins.PluginRules;

/** Native configuration only, separate from the existing plugin installation authority. */
final class CatalogSources {
    static final String FORMAT = "pocketworld.plugin-catalog-sources.v1";
    private final File file;
    CatalogSources(File file) { this.file = java.util.Objects.requireNonNull(file); }

    JSONObject read() throws Exception {
        if (!file.exists()) return new JSONObject().put("format", FORMAT).put("revision", "absent").put("sources", new JSONArray());
        if (file.length() > 65536) throw new IllegalStateException("目录来源配置过大");
        JSONObject state = CatalogJson.read(Files.readAllBytes(file.toPath()));
        CatalogJson.fields(state, "format", "revision", "sources");
        if (!FORMAT.equals(state.getString("format"))) throw new IllegalStateException("目录来源配置版本不支持");
        CatalogJson.text(state, "revision", 64);
        return new JSONObject().put("format", FORMAT).put("revision", state.getString("revision"))
                .put("sources", normalize(state.getJSONArray("sources")));
    }

    JSONObject write(JSONArray supplied, String expectedRevision) throws Exception {
        JSONObject before = read();
        if (!before.getString("revision").equals(expectedRevision)) throw new IllegalStateException("目录来源已变化，请刷新配置");
        JSONObject next = new JSONObject().put("format", FORMAT).put("revision", UUID.randomUUID().toString()).put("sources", normalize(supplied));
        byte[] bytes = CatalogJson.canonical(next).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) throw new IllegalArgumentException("目录来源配置超过64KiB");
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("无法创建目录来源配置位置");
        File temp = File.createTempFile("catalog-sources-", ".tmp", parent);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp)) { stream.write(bytes); stream.getFD().sync(); }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp.toPath()); }
        return next;
    }

    static JSONArray normalize(JSONArray supplied) throws Exception {
        if (supplied.length() > 16) throw new IllegalArgumentException("最多配置16个目录来源");
        JSONArray result = new JSONArray(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < supplied.length(); i++) {
            JSONObject value = supplied.getJSONObject(i);
            CatalogJson.fields(value, "id", "label", "catalogUrl", "enabled", "pinnedCatalogSha256");
            String id = CatalogJson.text(value, "id", 80);
            if (!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}") || !ids.add(id)) throw new IllegalArgumentException("目录来源ID无效或重复");
            String label = CatalogJson.text(value, "label", 240), url = url(CatalogJson.text(value, "catalogUrl", 2000));
            if (!(value.get("enabled") instanceof Boolean)) throw new IllegalArgumentException("目录启用状态无效");
            JSONObject source = new JSONObject().put("id", id).put("label", label).put("catalogUrl", url).put("enabled", value.getBoolean("enabled"));
            if (value.has("pinnedCatalogSha256")) source.put("pinnedCatalogSha256", CatalogJson.digest(value, "pinnedCatalogSha256"));
            result.put(source);
        }
        return result;
    }

    static String url(String value) throws Exception {
        PluginRules.validateEndpoint(value);
        URI uri = new URI(value);
        if (uri.getPort() == 0 || uri.getPort() > 65535 || value.chars().anyMatch(c -> c <= 32 || c == 127)
                || value.indexOf('\\') >= 0) throw new IllegalArgumentException("目录地址无效");
        return value;
    }
    static boolean sameOrigin(String left, String right) throws Exception {
        URI a = new URI(left), b = new URI(right);
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
}
