package dev.pocketengine.features.plugins.sdk.js;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Static executable_js artifact codec. No engine, initialize, describe, eval, install or grant path. */
public final class JsPluginPackage {
    public static final String FORMAT = "pocketworld.executable-js.v1";
    public static final int MAX_SOURCE_BYTES = 131072, MAX_PACKAGE_BYTES = 1048576;
    private static final Set<String> FIELDS = Set.of("format", "id", "name", "version", "sourceSha256", "source");
    public final String id, name, version, sourceSha256, packageSha256;
    public final int byteSize;
    private final byte[] sourceBytes, packageBytes;
    private final String source;

    private JsPluginPackage(Map<String, String> fields, byte[] original) throws Exception {
        if (!fields.keySet().equals(FIELDS) || !FORMAT.equals(fields.get("format"))) throw invalid();
        id = fields.get("id"); name = fields.get("name"); version = fields.get("version");
        metadata(id, name, version);
        source = fields.get("source"); sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        if (sourceBytes.length == 0 || sourceBytes.length > MAX_SOURCE_BYTES) throw new IllegalArgumentException("JS源需1至128KiB UTF8字节");
        sourceSha256 = fields.get("sourceSha256");
        if (!sourceSha256.matches("[a-f0-9]{64}") || !sourceSha256.equals(hash(sourceBytes))) throw new SecurityException("精确JS源摘要不匹配");
        packageBytes = original.clone(); byteSize = original.length; packageSha256 = hash(original);
    }

    public static JsPluginPackage read(InputStream input, RunControl control) throws Exception {
        byte[] original = bounded(input, MAX_PACKAGE_BYTES, control);
        String json = utf8(original);
        Map<String, String> fields = new Strings(json).object();
        control.check();
        return new JsPluginPackage(fields, original);
    }

    public static JsPluginPackage author(InputStream exactSource, String id, String name, String version, RunControl control) throws Exception {
        metadata(id, name, version);
        byte[] bytes = bounded(exactSource, MAX_SOURCE_BYTES, control);
        if (bytes.length == 0) throw new IllegalArgumentException("JS源不能为空");
        String source = utf8(bytes); // No newline, whitespace, BOM or Unicode normalization.
        String json = "{\"format\":" + quote(FORMAT) + ",\"id\":" + quote(id) + ",\"name\":" + quote(name)
                + ",\"version\":" + quote(version) + ",\"sourceSha256\":" + quote(hash(bytes)) + ",\"source\":" + quote(source) + "}";
        return read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), control);
    }

    public byte[] exactSourceBytes() { return sourceBytes.clone(); }
    public byte[] packageBytes() { return packageBytes.clone(); }
    /** Exact source as inert text; callers must display plain text, never HTML or executable markup. */
    public String sourceDisplay() { return source; }
    public JSONObject receipt() throws Exception {
        return new JSONObject().put("format", FORMAT).put("kind", "executable_js").put("id", id).put("name", name).put("version", version)
                .put("sourceSha256", sourceSha256).put("packageSha256", packageSha256).put("sourceByteSize", sourceBytes.length)
                .put("byteSize", byteSize).put("staticOnly", true).put("initialized", false).put("described", false)
                .put("installed", false).put("approved", false).put("enabled", false).put("untrusted", true);
    }
    public JSONObject write(OutputStream destination, RunControl control) throws Exception {
        if (destination == null || control == null) throw new IllegalArgumentException("需要主人选择的目标流和取消控制");
        for (int offset = 0; offset < packageBytes.length; offset += 4096) {
            control.check(); destination.write(packageBytes, offset, Math.min(4096, packageBytes.length - offset));
        }
        control.check(); return receipt().put("requiresDestinationClose", true);
    }

    private static void metadata(String id, String name, String version) {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")) throw new IllegalArgumentException("JS插件ID格式无效");
        text(name, 80); text(version, 32);
    }
    private static void text(String value, int limit) {
        if (value == null || value.trim().isEmpty() || value.length() > limit || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("JS插件名称或版本无效");
        unicode(value);
    }
    private static byte[] bounded(InputStream input, int maximum, RunControl control) throws Exception {
        if (input == null || control == null) throw new IllegalArgumentException("需要输入流和取消控制");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
        while (true) {
            control.check(); int count = input.read(buffer, 0, Math.min(buffer.length, maximum + 1 - bytes.size()));
            if (count < 0) break;
            if (count == 0) throw new IllegalArgumentException("输入流未提供数据");
            bytes.write(buffer, 0, count);
            if (bytes.size() > maximum) throw new IllegalArgumentException("JS包或源超过字节上限");
        }
        control.check(); return bytes.toByteArray();
    }
    private static String utf8(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static String quote(String text) {
        unicode(text); StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '"' || value == '\\') result.append('\\').append(value);
            else if (value < 32) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) value));
            else result.append(value);
        }
        return result.append('"').toString();
    }
    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isSurrogate(c) && (!Character.isHighSurrogate(c) || i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))) throw invalid();
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("不是受支持的严格executable_js包"); }

    /** This format has six strings only. Nested/number/boolean/null values and unknown keys are invalid. */
    private static final class Strings {
        private final String text; private int at;
        Strings(String text) { this.text = text; }
        Map<String, String> object() {
            Map<String, String> result = new HashMap<>(); space(); expect('{'); space();
            if (!take('}')) while (true) {
                space(); String key = string();
                if (!FIELDS.contains(key) || result.containsKey(key)) throw invalid();
                space(); expect(':'); space(); result.put(key, string()); space();
                if (take('}')) break;
                expect(',');
            }
            space(); if (at != text.length()) throw invalid();
            return result;
        }
        private String string() {
            expect('"'); StringBuilder result = new StringBuilder(); boolean ended = false;
            while (at < text.length()) {
                char value = text.charAt(at++);
                if (value == '"') { ended = true; break; }
                if (value < 32) throw invalid();
                if (value == '\\') {
                    if (at == text.length()) throw invalid(); char escape = text.charAt(at++);
                    switch (escape) {
                        case '"': case '\\': case '/': value = escape; break;
                        case 'b': value = '\b'; break;
                        case 'f': value = '\f'; break;
                        case 'n': value = '\n'; break;
                        case 'r': value = '\r'; break;
                        case 't': value = '\t'; break;
                        case 'u':
                            if (at + 4 > text.length()) throw invalid(); String hex = text.substring(at, at + 4);
                            if (!hex.matches("[a-fA-F0-9]{4}")) throw invalid(); value = (char) Integer.parseInt(hex, 16); at += 4; break;
                        default: throw invalid();
                    }
                }
                result.append(value);
            }
            if (!ended) throw invalid(); String value = result.toString(); unicode(value); return value;
        }
        private void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        private boolean take(char value) { if (at < text.length() && text.charAt(at) == value) { at++; return true; } return false; }
        private void expect(char value) { if (!take(value)) throw invalid(); }
    }
}
