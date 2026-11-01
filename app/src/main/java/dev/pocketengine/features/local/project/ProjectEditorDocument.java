package dev.pocketengine.features.local.project;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Bounded, literal operations on an owner's original file draft. No IO, save or execution authority. */
public final class ProjectEditorDocument {
    public static final int MAX_TEXT_BYTES = 262144, MAX_QUERY_CHARACTERS = 512, MAX_MATCHES = 4096;
    @FunctionalInterface public interface Current { void check(); }
    private static final Current THREAD_CURRENT = () -> { if (Thread.currentThread().isInterrupted()) throw new CancellationException("查找已取消"); };
    public final String text, sha256;
    private final int[] lines;

    public ProjectEditorDocument(String text) {
        bytes(text, MAX_TEXT_BYTES, "编辑文档"); this.text = text; this.sha256 = ProjectFileDraft.digest(text);
        List<Integer> starts = new ArrayList<>(); starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') { if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++; starts.add(i + 1); }
            else if (c == '\n') starts.add(i + 1);
        }
        lines = new int[starts.size()]; for (int i = 0; i < lines.length; i++) lines[i] = starts.get(i);
    }
    public int lineCount() { return lines.length; }
    public int lineStart(int oneBasedLine) {
        if (oneBasedLine < 1 || oneBasedLine > lines.length) throw new IllegalArgumentException("行号应在1–" + lines.length + "之间");
        return lines[oneBasedLine - 1];
    }
    public int lineAt(int offset) {
        boundary(text, offset); int found = Arrays.binarySearch(lines, offset);
        return found >= 0 ? found + 1 : -found - 1;
    }
    public int columnAt(int offset) { return text.codePointCount(lineStart(lineAt(offset)), offset) + 1; }
    public static boolean codePointBoundary(String text, int offset) {
        return offset >= 0 && offset <= text.length() && !(offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)));
    }
    private static void boundary(String text, int offset) {
        if (!codePointBoundary(text, offset)) throw new IllegalArgumentException("选区落在文字编码中间，未执行查替");
    }

    public static final class Match {
        public final int start, end;
        private Match(int start, int end) { this.start = start; this.end = end; }
    }
    public static final class Search {
        private final ProjectEditorDocument original;
        public final String query;
        public final boolean caseSensitive, complete;
        public final int scopeStart, scopeEnd, totalMatches;
        private final Match[] matches;
        private Search(ProjectEditorDocument original, String query, boolean sensitive, int start, int end, int count, List<Match> matches) {
            this.original = original; this.query = query; this.caseSensitive = sensitive;
            scopeStart = start; scopeEnd = end; totalMatches = count; this.matches = matches.toArray(new Match[0]); complete = count == this.matches.length;
        }
        public int visibleMatches() { return matches.length; }
        public Match match(int index) { if (index < 0 || index >= matches.length) throw new IllegalArgumentException("请重新选择当前查找结果"); return matches[index]; }
        public boolean belongsTo(String actual) { return original.text.equals(actual); }
        public String sourceSha256() { return original.sha256; }
        /** Stable non-overlapping literal matches; a later search never edits the draft or selection. */
        public int next(int offset, boolean backwards) {
            if (matches.length == 0) return -1;
            if (backwards) { for (int i = matches.length - 1; i >= 0; i--) if (matches[i].start < offset) return i; return matches.length - 1; }
            for (int i = 0; i < matches.length; i++) if (matches[i].start >= offset) return i;
            return 0;
        }
    }
    public Search search(String query, boolean caseSensitive, int scopeStart, int scopeEnd) { return search(query, caseSensitive, scopeStart, scopeEnd, THREAD_CURRENT); }
    public Search search(String query, boolean caseSensitive, int scopeStart, int scopeEnd, Current current) {
        if (query == null || query.length() > MAX_QUERY_CHARACTERS) throw new IllegalArgumentException("查找文字上限512字符");
        bytes(query, MAX_QUERY_CHARACTERS * 4, "查找文字"); boundary(text, scopeStart); boundary(text, scopeEnd);
        if (scopeEnd < scopeStart) throw new IllegalArgumentException("查找范围无效");
        List<Match> matches = new ArrayList<>(); int count = 0; current.check();
        if (!query.isEmpty()) for (int start = scopeStart; start <= scopeEnd - query.length();) {
            if ((start & 127) == 0) current.check();
            int end = start + query.length();
            if (codePointBoundary(text, start) && codePointBoundary(text, end) && text.regionMatches(!caseSensitive, start, query, 0, query.length())) {
                if (matches.size() < MAX_MATCHES) matches.add(new Match(start, end)); count++; start = end;
            } else start++;
        }
        current.check(); return new Search(this, query, caseSensitive, scopeStart, scopeEnd, count, matches);
    }
    public static final class Replacement {
        public final String text;
        public final int selectionStart, selectionEnd, replaced;
        private Replacement(String text, int start, int end, int count) { this.text = text; selectionStart = start; selectionEnd = end; replaced = count; }
    }
    public static Replacement replace(Search search, int matchIndex, String replacement, String actual) {
        requireCurrent(search, replacement, actual); Match match = search.match(matchIndex);
        String result = actual.substring(0, match.start) + replacement + actual.substring(match.end);
        bytes(result, MAX_TEXT_BYTES, "替换后草稿"); return new Replacement(result, match.start, match.start + replacement.length(), 1);
    }
    public static Replacement replaceAll(Search search, String replacement, String actual, Current current) {
        requireCurrent(search, replacement, actual); current.check();
        if (!search.complete) throw new IllegalStateException("命中超过4096处，请缩小查找范围后再全部替换");
        if (search.totalMatches == 0) throw new IllegalStateException("没有可替换的命中");
        long expectedLength = (long) actual.length() + (long) search.totalMatches * (replacement.length() - search.query.length());
        if (expectedLength > MAX_TEXT_BYTES) throw new IllegalArgumentException("替换后草稿超过256KiB，原文未变");
        StringBuilder result = new StringBuilder((int) Math.max(0, expectedLength)); int previous = 0;
        for (Match match : search.matches) { current.check(); result.append(actual, previous, match.start).append(replacement); previous = match.end; }
        result.append(actual, previous, actual.length()); String changed = result.toString(); bytes(changed, MAX_TEXT_BYTES, "替换后草稿");
        Match first = search.matches[0]; current.check(); return new Replacement(changed, first.start, first.start + replacement.length(), search.totalMatches);
    }
    private static void requireCurrent(Search search, String replacement, String actual) {
        if (search == null || !search.belongsTo(actual)) throw new IllegalStateException("草稿在查找后已变化，请重新查找；没有替换任何文字");
        bytes(replacement, MAX_TEXT_BYTES, "替换文字");
    }
    private static void bytes(String text, int max, String what) {
        if (text == null || text.indexOf('\0') >= 0) throw new IllegalArgumentException(what + "必须为无NUL文字");
        try { if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text)).remaining() > max) throw new IllegalArgumentException(what + "超过字节上限；原文未变"); }
        catch (CharacterCodingException invalid) { throw new IllegalArgumentException(what + "包含不完整Unicode文字，原文未变", invalid); }
    }
}
