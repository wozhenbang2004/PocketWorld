package dev.pocketengine.store;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayDeque;

/** Migration-only structural cursor. Long quoted values are decoded and encoded in bounded
 * pieces by the platform JSON APIs; neither a whole body string nor a whole event is retained.
 * This is not a runtime JSON reader and does not relax runtime evidence limits. */
final class MigrationJsonInput implements AutoCloseable {
    private static final int SCALAR_LIMIT = 1048576, PIECE = 4096;
    private enum Position { ROOT, FINISHED, FIRST_KEY, KEY, VALUE, NEXT_KEY, FIRST_ITEM, ITEM, NEXT_ITEM }
    private final BufferedReader input;
    private final ArrayDeque<Position> positions = new ArrayDeque<>();
    private int ahead = -2;
    private boolean firstRead = true;
    private JsonToken token;
    private String literal;

    MigrationJsonInput(Reader source) { input = new BufferedReader(source, PIECE); positions.push(Position.ROOT); }
    private IOException invalid() { return new IOException("旧任务JSON不可读；保留原事务与正文"); }
    private int look() throws IOException { if (ahead == -2) ahead = input.read(); return ahead; }
    private int take() throws IOException { int result = look(); ahead = -2; return result; }
    private void space() throws IOException { while (look() == ' ' || look() == '\t' || look() == '\r' || look() == '\n') take(); }
    private void replace(Position position) { positions.pop(); positions.push(position); }
    private void consumed() throws IOException {
        Position p = positions.peek();
        if (p == Position.ROOT) replace(Position.FINISHED);
        else if (p == Position.VALUE) replace(Position.NEXT_KEY);
        else if (p == Position.FIRST_ITEM || p == Position.ITEM) replace(Position.NEXT_ITEM);
        else throw invalid();
        token = null; literal = null;
    }
    JsonToken peek() throws IOException {
        if (token != null) return token;
        if (firstRead) { firstRead = false; if (look() == 0xfeff) take(); }
        space(); Position p = positions.peek();
        if (p == Position.FINISHED) { if (look() != -1) throw invalid(); return JsonToken.END_DOCUMENT; }
        if (p == Position.NEXT_KEY) {
            if (look() == '}') return JsonToken.END_OBJECT;
            if (take() != ',') throw invalid(); replace(Position.KEY); space(); p = Position.KEY;
        }
        if (p == Position.FIRST_KEY || p == Position.KEY) {
            if (p == Position.FIRST_KEY && look() == '}') return JsonToken.END_OBJECT;
            if (look() != '"') throw invalid(); return JsonToken.NAME;
        }
        if (p == Position.NEXT_ITEM) {
            if (look() == ']') return JsonToken.END_ARRAY;
            if (take() != ',') throw invalid(); replace(Position.ITEM); space(); p = Position.ITEM;
        }
        if (p == Position.FIRST_ITEM && look() == ']') return JsonToken.END_ARRAY;
        if (look() == '{') return token = JsonToken.BEGIN_OBJECT;
        if (look() == '[') return token = JsonToken.BEGIN_ARRAY;
        // The platform's non-lenient root contract accepts containers only.
        if (p == Position.ROOT) throw invalid();
        if (look() == '"') return token = JsonToken.STRING;
        StringBuilder value = new StringBuilder();
        while (look() != -1 && look() != ',' && look() != ']' && look() != '}' &&
                look() != ' ' && look() != '\t' && look() != '\r' && look() != '\n') {
            if (value.length() == SCALAR_LIMIT) throw invalid(); value.append((char) take());
        }
        literal = value.toString();
        try (JsonReader scalar = new JsonReader(new StringReader("[" + literal + "]"))) {
            scalar.beginArray(); token = scalar.peek();
            if (token == JsonToken.NUMBER) scalar.nextString();
            else if (token == JsonToken.BOOLEAN) scalar.nextBoolean();
            else if (token == JsonToken.NULL) scalar.nextNull();
            else throw invalid();
            scalar.endArray(); if (scalar.peek() != JsonToken.END_DOCUMENT) throw invalid();
        }
        return token;
    }
    boolean hasNext() throws IOException { JsonToken t = peek(); return t != JsonToken.END_OBJECT && t != JsonToken.END_ARRAY && t != JsonToken.END_DOCUMENT; }
    void beginObject() throws IOException { if (peek() != JsonToken.BEGIN_OBJECT) throw invalid(); take(); consumed(); positions.push(Position.FIRST_KEY); }
    void endObject() throws IOException { if (peek() != JsonToken.END_OBJECT) throw invalid(); take(); positions.pop(); token = null; }
    void beginArray() throws IOException { if (peek() != JsonToken.BEGIN_ARRAY) throw invalid(); take(); consumed(); positions.push(Position.FIRST_ITEM); }
    void endArray() throws IOException { if (peek() != JsonToken.END_ARRAY) throw invalid(); take(); positions.pop(); token = null; }
    String nextName() throws IOException {
        if (peek() != JsonToken.NAME) throw invalid();
        LimitedText text = new LimitedText(SCALAR_LIMIT); quoted(text, false, SCALAR_LIMIT);
        if (text.overflow) throw invalid(); space(); if (take() != ':') throw invalid(); replace(Position.VALUE); token = null;
        return text.toString();
    }
    /** A null return means an oversized optional metadata string, not an absent token.
     * It is still fully parsed before advancing, so invalid trailing input cannot be skipped. */
    String boundedString(int limit) throws IOException {
        if (peek() != JsonToken.STRING) throw invalid();
        LimitedText text = new LimitedText(limit); quoted(text, false, 0); consumed();
        return text.overflow ? null : text.toString();
    }
    String nextNumber() throws IOException { if (peek() != JsonToken.NUMBER) throw invalid(); String result = literal; consumed(); return result; }
    boolean nextBoolean() throws IOException { if (peek() != JsonToken.BOOLEAN) throw invalid(); boolean result = Boolean.parseBoolean(literal); consumed(); return result; }
    private void nextNull() throws IOException { if (peek() != JsonToken.NULL) throw invalid(); consumed(); }

    static void writeString(Writer out, String text) throws IOException {
        StringWriter encoded = new StringWriter();
        try (JsonWriter writer = new JsonWriter(encoded)) { writer.setLenient(true); writer.value(text); }
        out.write(encoded.toString());
    }
    /** Never divide a JSON escape between platform decoders. Raw surrogate code units
     * remain adjacent in the destination; SqlWriter retains a trailing high surrogate. */
    private void quoted(Writer destination, boolean encoded, int rawLimit) throws IOException {
        if (take() != '"') throw invalid();
        if (encoded) destination.write('"');
        StringBuilder piece = new StringBuilder(PIECE + 6);
        long rawCount = 0;
        for (;;) {
            int c = take(); if (c == -1) throw invalid();
            if (rawLimit > 0 && ++rawCount > rawLimit) throw invalid();
            if (c == '"') { emitPiece(piece, destination, encoded); break; }
            piece.append((char)c);
            if (c == '\\') {
                int escape = take(); if (escape == -1) throw invalid(); if (rawLimit > 0 && ++rawCount > rawLimit) throw invalid(); piece.append((char)escape);
                if (escape == 'u') for (int n = 0; n < 4; n++) { int digit = take(); if (digit == -1) throw invalid(); if (rawLimit > 0 && ++rawCount > rawLimit) throw invalid(); piece.append((char)digit); }
            }
            if (piece.length() >= PIECE) emitPiece(piece, destination, encoded);
        }
        if (encoded) destination.write('"');
    }
    private void emitPiece(StringBuilder piece, Writer destination, boolean encoded) throws IOException {
        if (piece.length() == 0) return;
        String value;
        try (JsonReader reader = new JsonReader(new StringReader("[\"" + piece + "\"]"))) {
            reader.beginArray(); value = reader.nextString(); reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
        }
        if (encoded) {
            StringWriter output = new StringWriter(); writeString(output, value);
            String quoted = output.toString(); destination.write(quoted, 1, quoted.length() - 2);
        } else destination.write(value);
        piece.setLength(0);
    }
    private static Writer both(Writer first, Writer second) {
        if (second == null) return first;
        return new Writer() {
            public void write(char[] text, int offset, int count) throws IOException { first.write(text, offset, count); second.write(text, offset, count); }
            public void flush() {} public void close() {}
        };
    }
    void copyValue(Writer out, Writer capture, int depth) throws IOException {
        if (depth > 100) throw invalid(); Writer target = both(out, capture);
        switch (peek()) {
            case BEGIN_OBJECT:
                beginObject(); target.write('{'); boolean firstKey = true;
                while (hasNext()) { if (!firstKey) target.write(','); firstKey = false; writeString(target, nextName()); target.write(':'); copyValue(target, null, depth + 1); }
                endObject(); target.write('}'); break;
            case BEGIN_ARRAY:
                beginArray(); target.write('['); boolean firstItem = true;
                while (hasNext()) { if (!firstItem) target.write(','); firstItem = false; copyValue(target, null, depth + 1); }
                endArray(); target.write(']'); break;
            case STRING: quoted(target, true, 0); consumed(); break;
            case NUMBER: target.write(new BigDecimal(nextNumber()).toString()); break;
            case BOOLEAN: target.write(nextBoolean() ? "true" : "false"); break;
            case NULL: nextNull(); target.write("null"); break;
            default: throw invalid();
        }
    }
    void skipValue() throws IOException { copyValue(new Writer() { public void write(char[] c,int o,int n) {} public void flush() {} public void close() {} }, null, 0); }
    @Override public void close() throws IOException { input.close(); }
    private static final class LimitedText extends Writer {
        private final int limit; private final StringBuilder text = new StringBuilder(); boolean overflow;
        LimitedText(int limit) { this.limit = limit; }
        public void write(char[] value,int offset,int count) { if (overflow) return; if (count > limit - text.length()) { overflow = true; return; } text.append(value,offset,count); }
        public void flush() {} public void close() {} public String toString() { return text.toString(); }
    }
}
