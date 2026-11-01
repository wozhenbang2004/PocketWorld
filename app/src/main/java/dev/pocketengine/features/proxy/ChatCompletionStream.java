package dev.pocketengine.features.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, data-only Chat Completions SSE. Never forwards arbitrary SSE control fields. */
final class ChatCompletionStream {
    static final int MAX_EVENT_BYTES = 64 * 1024;
    static final int MAX_STREAM_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PENDING_BYTES = 256 * 1024;
    private static final int MAX_PENDING_EVENTS = 256;
    interface Sink { void data(String json) throws Exception; }

    static void relay(InputStream source, String credential, Sink sink) throws Exception {
        Parser parser = new Parser(source);
        Redactor redactor = new Redactor(credential, sink);
        while (true) {
            String data = parser.next();
            if (data == null) throw new IOException("上游流在[DONE]之前断开");
            if (data.equals("[DONE]")) {
                redactor.finish();
                sink.data("[DONE]");
                return;
            }
            JSONObject chunk = parseObject(data);
            if (chunk.has("error")) throw new IOException("上游流报告错误");
            if (!"chat.completion.chunk".equals(chunk.optString("object")) || chunk.optJSONArray("choices") == null)
                throw new IOException("上游没有返回Chat Completions分片");
            redactor.accept(chunk, parser.eventBytes);
        }
    }

    /** Decode only a complete bounded event, so split UTF-8/network reads never alter text. */
    private static final class Parser {
        private final InputStream input;
        private int totalBytes;
        private boolean skipLf;
        private boolean firstLine = true;
        int eventBytes;
        Parser(InputStream input) { this.input = new java.io.BufferedInputStream(input, 8192); }
        String next() throws Exception {
            eventBytes = 0;
            StringBuilder data = new StringBuilder();
            boolean hasData = false;
            while (true) {
                String line = line();
                if (line == null) return null; // Unterminated events are not dispatched by SSE.
                if (firstLine) { firstLine = false; if (line.startsWith("\uFEFF")) line = line.substring(1); }
                if (line.isEmpty()) {
                    if (hasData) return data.toString();
                    eventBytes = 0; // Bounded comments/empty keepalive events, not forwarded.
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if (field.equals("data")) {
                    if (hasData) data.append('\n');
                    data.append(value); hasData = true;
                } else if (field.equals("event") && !value.isEmpty() && !value.equals("message")) {
                    throw new IOException("不支持的上游SSE事件类型");
                }
                // Ignore comments, id, retry and unknown fields; none may inject downstream SSE.
            }
        }
        private String line() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int next = read();
                if (next < 0) return null;
                if (skipLf) { skipLf = false; if (next == '\n') continue; }
                if (next == '\n') break;
                if (next == '\r') { skipLf = true; break; }
                bytes.write(next);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            } catch (CharacterCodingException invalid) { throw new IOException("上游SSE不是UTF-8", invalid); }
        }
        private int read() throws IOException {
            int value = input.read();
            if (value >= 0 && (++totalBytes > MAX_STREAM_BYTES || ++eventBytes > MAX_EVENT_BYTES))
                throw new IOException("上游流超过大小限制");
            return value;
        }
    }

    /**
     * Hold only events with a possible credential suffix. Redact across logical delta fields,
     * retaining choice/tool indexes and the original JSON event boundaries. Unrelated streams
     * cannot be concatenated to rewrite tool arguments. Bounded look-behind fails closed.
     */
    private static final class Redactor {
        private final String key;
        private final Sink sink;
        private final ArrayDeque<Event> pending = new ArrayDeque<>();
        private final Map<String, List<Text>> tails = new HashMap<>();
        private int pendingBytes;
        private boolean sawChunk;
        private final java.util.Set<Integer> finished = new java.util.HashSet<>();
        Redactor(String key, Sink sink) { this.key = key; this.sink = sink; }
        void accept(JSONObject original, int bytes) throws Exception {
            JSONObject json = (JSONObject) scrub(original, key);
            if (!"chat.completion.chunk".equals(json.optString("object"))) throw new IOException("凭据与协议字段冲突");
            Event event = new Event(json, bytes);
            pending.add(event); pendingBytes += bytes; sawChunk = true;
            JSONArray choices = json.getJSONArray("choices");
            Map<Integer, Boolean> indexes = new HashMap<>();
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.optJSONObject(i);
                if (choice == null || !(choice.opt("index") instanceof Number)) throw new IOException("分片缺少choice index");
                int index = index(choice);
                if (finished.contains(index)) throw new IOException("已结束的choice再次返回分片");
                if (choice.has("logprobs") && !choice.isNull("logprobs")) throw new IOException("当前不支持logprobs流");
                if (index < 0 || indexes.put(index, true) != null) throw new IOException("无效choice index");
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) throw new IOException("分片缺少delta");
                String path = index + "/";
                // Work from the original text: individual-event scrub must not hide cross-event matches.
                JSONObject source = original.getJSONArray("choices").getJSONObject(i).getJSONObject("delta");
                deltas(event, delta, source, path);
                if (!choice.isNull("finish_reason") && choice.has("finish_reason")) { clearChoice(path); finished.add(index); }
            }
            drain();
            if (pendingBytes > MAX_PENDING_BYTES || pending.size() > MAX_PENDING_EVENTS || tails.size() > 128)
                throw new IOException("凭据保护的分片缓冲超过限制");
        }
        private void deltas(Event event, JSONObject target, JSONObject source, String path) throws Exception {
            for (Iterator<String> it = source.keys(); it.hasNext();) {
                String name = it.next(); Object value = source.get(name);
                if (name.equals("role")) {
                    if (!"assistant".equals(value) || !"assistant".equals(target.optString(name))) throw new IOException("无效流角色");
                    continue;
                }
                if ((name.equals("content") || name.equals("refusal")) && value instanceof String) {
                    text(event, target, name, (String) value, path + name);
                } else if (name.equals("function_call") && value instanceof JSONObject) {
                    function(event, target.getJSONObject(name), (JSONObject) value, path + name + "/");
                } else if (name.equals("tool_calls") && value instanceof JSONArray) {
                    JSONArray calls = (JSONArray) value, output = target.getJSONArray(name);
                    Map<Integer, Boolean> indexes = new HashMap<>();
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.optJSONObject(i);
                        if (call == null || !(call.opt("index") instanceof Number)) throw new IOException("工具分片缺少index");
                        int index = index(call);
                        if (call.has("type") && !"function".equals(call.get("type"))) throw new IOException("不支持的工具类型");
                        if (index < 0 || indexes.put(index, true) != null) throw new IOException("无效工具index");
                        JSONObject out = output.getJSONObject(i); String p = path + "tool/" + index + "/";
                        if (call.opt("id") instanceof String) text(event, out, "id", call.getString("id"), p + "id");
                        if (call.optJSONObject("function") != null) function(event, out.getJSONObject("function"), call.getJSONObject("function"), p + "function/");
                        for (Iterator<String> keys = call.keys(); keys.hasNext();) {
                            String field = keys.next();
                            if (!field.equals("index") && !field.equals("id") && !field.equals("type") && !field.equals("function"))
                                throw new IOException("不支持的工具流字段");
                        }
                    }
                } else if (!(value == JSONObject.NULL && (name.equals("content") || name.equals("refusal")))) {
                    throw new IOException("不支持的Chat Completions流字段");
                }
            }
        }
        private static int index(JSONObject object) throws IOException {
            Object value = object.opt("index");
            if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue() || ((Number) value).intValue() < 0)
                throw new IOException("无效分片index");
            return ((Number) value).intValue();
        }
        private void function(Event event, JSONObject target, JSONObject source, String path) throws Exception {
            for (Iterator<String> it = source.keys(); it.hasNext();) {
                String name = it.next();
                if ((!name.equals("name") && !name.equals("arguments")) || !(source.get(name) instanceof String))
                    throw new IOException("不支持的函数流字段");
                text(event, target, name, source.getString(name), path + name);
            }
        }
        private void text(Event event, JSONObject target, String name, String value, String path) throws Exception {
            if (key.isEmpty()) return;
            target.put(name, value);
            Text current = new Text(event, target, name);
            List<Text> parts = tails.remove(path);
            if (parts == null) parts = new ArrayList<>();
            for (Text part : parts) part.event.blocked--;
            parts.add(current);
            StringBuilder combined = new StringBuilder();
            for (Text part : parts) combined.append(part.value());
            String raw = combined.toString();
            // Apply matches to their original fragments; replacements never become SSE syntax.
            List<Integer> matches = new ArrayList<>();
            for (int at = raw.indexOf(key); at >= 0; at = raw.indexOf(key, at + key.length())) matches.add(at);
            int offset = 0;
            String marker = "[upstream credential hidden]";
            if (marker.contains(key)) marker = "";
            for (Text part : parts) {
                String original = part.value(); StringBuilder safe = new StringBuilder(); int cursor = 0;
                for (int start : matches) {
                    int from = Math.max(0, start - offset), to = Math.min(original.length(), start + key.length() - offset);
                    if (from < to) { safe.append(original, cursor, from); if (start >= offset) safe.append(marker); cursor = to; }
                }
                safe.append(original, cursor, original.length()); part.target.put(part.name, safe.toString()); offset += original.length();
            }
            combined.setLength(0); for (Text part : parts) combined.append(part.value());
            int suffix = Math.min(key.length() - 1, combined.length());
            while (suffix > 0 && !combined.substring(combined.length() - suffix).equals(key.substring(0, suffix))) suffix--;
            if (suffix > 0) {
                List<Text> held = new ArrayList<>(); int remaining = suffix;
                for (int i = parts.size() - 1; i >= 0 && remaining > 0; i--) {
                    Text part = parts.get(i); if (part.value().isEmpty()) continue;
                    held.add(0, part); part.event.blocked++; remaining -= part.value().length();
                }
                tails.put(path, held);
            }
        }
        private void clearChoice(String prefix) {
            for (Iterator<Map.Entry<String, List<Text>>> it = tails.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, List<Text>> entry = it.next();
                if (entry.getKey().startsWith(prefix)) { for (Text part : entry.getValue()) part.event.blocked--; it.remove(); }
            }
        }
        private void drain() throws Exception {
            while (!pending.isEmpty() && pending.peek().blocked == 0) {
                Event event = pending.remove(); pendingBytes -= event.bytes; sink.data(event.json.toString());
            }
        }
        void finish() throws Exception {
            if (!sawChunk) throw new IOException("上游流没有返回任何分片");
            for (List<Text> parts : tails.values()) for (Text part : parts) part.event.blocked--;
            tails.clear(); drain();
        }
    }
    static JSONObject parseObject(String text) throws IOException {
        try{return dev.pocketengine.runtime.providers.ProtocolJson.parse(text);}
        catch(Exception invalid){throw new IOException("不是有效JSON对象",invalid);}
    }

    static Object scrub(Object value, String key) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject(), source = (JSONObject) value;
            for (Iterator<String> it = source.keys(); it.hasNext();) {
                String name = it.next();
                if (!key.isEmpty() && name.contains(key)) throw new IOException("上游字段包含凭据");
                result.put(name, scrub(source.get(name), key));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray result = new JSONArray(), source = (JSONArray) value;
            for (int i = 0; i < source.length(); i++) result.put(scrub(source.get(i), key));
            return result;
        }
        String marker = "[upstream credential hidden]";
        if (!key.isEmpty() && marker.contains(key)) marker = "";
        return value instanceof String && !key.isEmpty() ? ((String) value).replace(key, marker) : value;
    }
    private static final class Event {
        final JSONObject json; final int bytes; int blocked;
        Event(JSONObject json, int bytes) { this.json = json; this.bytes = bytes; }
    }
    private static final class Text {
        final Event event; final JSONObject target; final String name;
        Text(Event event, JSONObject target, String name) { this.event = event; this.target = target; this.name = name; }
        String value() { return target.optString(name); }
    }
}
