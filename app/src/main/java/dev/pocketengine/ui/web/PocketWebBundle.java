package dev.pocketengine.ui.web;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runtime allowlist from the exact bundle checked before APK packaging. */
public final class PocketWebBundle {
    private static final int MAX_RECEIPT_BYTES = 1024 * 1024;
    private static final String PREFIX = "/assets/pocket-ui/";
    private final Set<String> files;

    private PocketWebBundle(Set<String> files) { this.files = Set.copyOf(files); }

    public static PocketWebBundle read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count == 0) continue;
            if (bytes.size() + count > MAX_RECEIPT_BYTES) throw new IllegalArgumentException("Web UI receipt is too large");
            bytes.write(buffer, 0, count);
        }
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        JSONObject receipt = new JSONObject(text);
        if (!"pocketworld-library-web".equals(receipt.getString("kind"))) throw new IllegalArgumentException("Invalid Web UI identity");
        JSONArray entries = receipt.getJSONArray("files");
        if (entries.length() == 0 || entries.length() > 4096) throw new IllegalArgumentException("Invalid Web UI file count");
        Set<String> files = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String file = entry.getString("path");
            Object size = entry.get("bytes");
            if (!canonicalPath(file) || "build-identity.json".equals(file) || !files.add(file)
                    || !(size instanceof Number) || ((Number) size).longValue() < 0
                    || ((Number) size).doubleValue() != ((Number) size).longValue()
                    || !entry.getString("sha256").matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid Web UI resource");
            }
        }
        if (!files.contains("index.html")) throw new IllegalArgumentException("Missing Web UI entry");
        for (String prefix : new String[]{"assets/", "fonts/", "licenses/"}) {
            if (files.stream().noneMatch(file -> file.startsWith(prefix))) throw new IllegalArgumentException("Incomplete Web UI bundle");
        }
        return new PocketWebBundle(files);
    }

    public boolean allows(String address) {
        try {
            URI uri = new URI(address);
            String raw = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || !"pocketengine.local".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || raw == null || !raw.startsWith(PREFIX)) return false;
            String file = raw.substring(PREFIX.length());
            return canonicalPath(file) && files.contains(file);
        } catch (Exception invalid) { return false; }
    }

    private static boolean canonicalPath(String file) {
        if (file == null || file.isEmpty() || file.length() > 512) return false;
        for (int i = 0; i < file.length(); i++) {
            char c = file.charAt(i);
            if (c <= 32 || c == 127 || c == '\\' || c == '%' || c == '?' || c == '#') return false;
        }
        for (String part : file.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
}
