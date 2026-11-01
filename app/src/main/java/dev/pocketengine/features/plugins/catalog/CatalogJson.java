package dev.pocketengine.features.plugins.catalog;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** The catalog has only strings, booleans and exact integers; platform JSON leniency is not a format. */
final class CatalogJson {
    private final String text;
    private int cursor, values;
    private CatalogJson(String text) { this.text = text; }

    static JSONObject read(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        CatalogJson reader = new CatalogJson(text);
        Object result = reader.value(0);
        reader.whitespace();
        if (reader.cursor != text.length() || !(result instanceof JSONObject)) throw invalid();
        return (JSONObject) result;
    }

    private Object value(int depth) throws Exception {
        if (depth > 16 || ++values > 24000) throw invalid();
        whitespace();
        if (cursor == text.length()) throw invalid();
        char start = text.charAt(cursor);
        if (start == '"') return string();
        if (start == '{') {
            cursor++;
            JSONObject object = new JSONObject();
            Set<String> keys = new HashSet<>();
            whitespace();
            if (consume('}')) return object;
            do {
                whitespace();
                String key = string();
                if (!keys.add(key)) throw invalid();
                whitespace(); require(':');
                object.put(key, value(depth + 1));
                whitespace();
                if (consume('}')) return object;
                require(',');
            } while (true);
        }
        if (start == '[') {
            cursor++;
            JSONArray array = new JSONArray();
            whitespace();
            if (consume(']')) return array;
            do {
                array.put(value(depth + 1));
                whitespace();
                if (consume(']')) return array;
                require(',');
            } while (true);
        }
        if (text.startsWith("true", cursor)) { cursor += 4; return true; }
        if (text.startsWith("false", cursor)) { cursor += 5; return false; }
        int begin = cursor;
        if (start == '-') cursor++;
        while (cursor < text.length() && Character.isDigit(text.charAt(cursor))) cursor++;
        String number = text.substring(begin, cursor);
        if (!number.matches("-?(0|[1-9][0-9]*)") || number.length() > 19) throw invalid();
        try { return Long.parseLong(number); } catch (NumberFormatException bad) { throw invalid(); }
    }

    private String string() throws Exception {
        require('"');
        StringBuilder result = new StringBuilder();
        while (cursor < text.length()) {
            char c = text.charAt(cursor++);
            if (c == '"') { unicode(result.toString()); return result.toString(); }
            if (c < 32) throw invalid();
            if (c == '\\') {
                if (cursor == text.length()) throw invalid();
                char escape = text.charAt(cursor++);
                switch (escape) {
                    case '"': case '\\': case '/': c = escape; break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'u':
                        if (cursor + 4 > text.length()) throw invalid();
                        String digits = text.substring(cursor, cursor + 4);
                        if (!digits.matches("[0-9a-fA-F]{4}")) throw invalid();
                        c = (char) Integer.parseInt(digits, 16); cursor += 4; break;
                    default: throw invalid();
                }
            }
            result.append(c);
        }
        throw invalid();
    }

    private void whitespace() { while (cursor < text.length() && " \n\r\t".indexOf(text.charAt(cursor)) >= 0) cursor++; }
    private boolean consume(char c) { if (cursor < text.length() && text.charAt(cursor) == c) { cursor++; return true; } return false; }
    private void require(char c) { if (!consume(c)) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("目录需为严格UTF-8 JSON；数字只允许有界整数"); }

    static void unicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) throw invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }

    static void fields(JSONObject object, String... allowed) {
        Set<String> names = Set.of(allowed);
        object.keys().forEachRemaining(key -> { if (!names.contains(key)) throw new IllegalArgumentException("目录包含不支持的字段: " + key); });
    }
    static String text(JSONObject object, String key, int limit) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).isBlank() || ((String) value).length() > limit) throw invalid();
        unicode((String) value); return (String) value;
    }
    static int integer(JSONObject object, String key, int min, int max) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw invalid();
        return (int) number;
    }
    static String digest(JSONObject object, String key) throws Exception {
        String value = text(object, key, 64);
        if (!value.matches("[a-f0-9]{64}")) throw invalid();
        return value;
    }
    static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            TreeSet<String> keys = new TreeSet<>(); object.keys().forEachRemaining(keys::add);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) { if (out.length() > 1) out.append(','); out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))); }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) out.append(','); out.append(canonical(array.get(i))); }
            return out.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
    static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
