package dev.pocketengine.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/**
 * Private rolling store for already-redacted RuntimeDiagnostics records.
 * Segment files are capped at 4 MiB; UI reads at most 200 records at a time.
 * Export streams to a caller-owned destination, never loading all history.
 */
public final class DeveloperLogArchive implements AutoCloseable {
    private static final int SEGMENT_BYTES = 4 * 1024 * 1024;
    private static final String FORMAT = "pocketworld.developer-log.v1";
    private static final Pattern NAME = Pattern.compile("segment-([0-9]{12})\\.jsonl");
    private static final Pattern CURSOR = Pattern.compile("(segment-[0-9]{12}\\.jsonl):([0-9]{1,12})");
    private final File directory;
    private BufferedOutputStream output;
    private File active;
    private long activeSize;
    private long lastFlush;
    private int buffered;
    private int capacityMiB = 512;
    private boolean enabled;
    private long writeFailures, rejectedRows;

    public DeveloperLogArchive(File diagnosticsDirectory) {
        directory = diagnosticsDirectory == null ? null : new File(diagnosticsDirectory, "developer-logs");
        if (directory == null) return;
        try (FileInputStream stream = new FileInputStream(new File(directory, "retention-mib.txt"))) {
            byte[] raw = new byte[16];
            int read = stream.read(raw);
            int value = Integer.parseInt(new String(raw, 0, Math.max(0, read), StandardCharsets.US_ASCII).trim());
            if (supported(value)) capacityMiB = value;
        } catch (Exception ignored) { /* Retain safe default. */ }
        try (FileInputStream stream = new FileInputStream(new File(directory, "capture-enabled.txt"))) {
            enabled = stream.read() == '1';
        } catch (IOException ignored) { enabled = false; }
    }

    public synchronized boolean enabled() { return enabled; }

    /** Capture is opt-in. Disabling keeps the existing archive and lightweight logs intact. */
    public synchronized JSONObject capture(boolean value) throws IOException {
        if (directory == null || !directory.isDirectory() && !directory.mkdirs())
            throw new IOException("archive unavailable");
        File target = new File(directory, "capture-enabled.txt");
        File temporary = new File(directory, "capture-enabled.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(value ? '1' : '0');
            stream.getFD().sync();
        }
        if (target.exists() && !target.delete() || !temporary.renameTo(target))
            throw new IOException("capture preference not persisted");
        if (!value) flush();
        enabled = value;
        return stats();
    }

    private static boolean supported(int n) { return n == 256 || n == 512 || n == 1024 || n == 2048; }
    private static String name(long n) { return String.format(Locale.ROOT, "segment-%012d.jsonl", n); }
    private static long sequence(File file) {
        Matcher matcher = NAME.matcher(file.getName());
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : 0;
    }
    private File[] segments() {
        if (directory == null) return new File[0];
        File[] files = directory.listFiles(file -> file.isFile() && NAME.matcher(file.getName()).matches());
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private void ensureOpen() throws IOException {
        if (output != null) return;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("archive unavailable");
        File[] all = segments();
        active = all.length > 0 && all[all.length - 1].length() < SEGMENT_BYTES
            ? all[all.length - 1]
            : new File(directory, name(all.length == 0 ? 1 : sequence(all[all.length - 1]) + 1));
        activeSize = active.length();
        output = new BufferedOutputStream(new FileOutputStream(active, true), 65536);
        lastFlush = System.currentTimeMillis();
        prune();
    }

    private void flush() throws IOException {
        if (output != null && buffered > 0) output.flush();
        buffered = 0;
        lastFlush = System.currentTimeMillis();
    }
    private void rotate() throws IOException {
        flush();
        output.close();
        output = null;
        active = new File(directory, name(sequence(active) + 1));
        activeSize = 0;
        output = new BufferedOutputStream(new FileOutputStream(active, true), 65536);
        lastFlush = System.currentTimeMillis();
        prune();
    }
    private void prune() {
        File[] all = segments();
        long used = 0;
        for (File file : all) used += file.length();
        long allowed = capacityMiB * 1024L * 1024L;
        for (File file : all) {
            if (used <= allowed || file.equals(active)) break;
            long size = file.length();
            if (file.delete()) used -= size;
            else { writeFailures++; break; }
        }
    }

    /** Called on the existing single disk writer, never on the main thread. */
    public synchronized void append(String sanitizedJson) {
        if (directory == null || !enabled) return;
        try {
            JSONObject row = new JSONObject(sanitizedJson);
            if (!RuntimeDiagnostics.safeStoredRow(row)) { rejectedRows++; return; }
            byte[] bytes = (row.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length >= SEGMENT_BYTES) { rejectedRows++; return; }
            ensureOpen();
            if (activeSize + bytes.length > SEGMENT_BYTES) rotate();
            output.write(bytes);
            activeSize += bytes.length;
            buffered += bytes.length;
            if (buffered >= 65536 || System.currentTimeMillis() - lastFlush > 1000) flush();
        } catch (Exception failure) { writeFailures++; }
    }

    public synchronized JSONObject capacity(int miB) throws IOException {
        if (!supported(miB)) throw new IllegalArgumentException("unsupported cap");
        if (directory == null || !directory.isDirectory() && !directory.mkdirs()) throw new IOException("archive unavailable");
        File target = new File(directory, "retention-mib.txt");
        File temporary = new File(directory, "retention-mib.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(Integer.toString(miB).getBytes(StandardCharsets.US_ASCII));
            stream.getFD().sync();
        }
        if (target.exists() && !target.delete() || !temporary.renameTo(target)) throw new IOException("retention not persisted");
        capacityMiB = miB;
        prune();
        return stats();
    }

    public synchronized JSONObject stats() {
        File[] all = segments();
        long bytes = buffered;
        for (File file : all) bytes += file.length();
        return new JSONObject().put("format", FORMAT).put("bytes", bytes).put("capacityMiB", capacityMiB)
            .put("segments", all.length).put("writeFailures", writeFailures).put("rejectedRows", rejectedRows)
            .put("available", directory != null).put("enabled", enabled);
    }

    private static final class Position {
        final String filename; final long offset;
        Position(String filename, long offset) { this.filename = filename; this.offset = offset; }
        String token() { return filename + ":" + offset; }
    }
    private static final class Row {
        final JSONObject json; final Position start, end;
        Row(JSONObject json, File file, long start, long end) {
            this.json = json;
            this.start = new Position(file.getName(), start);
            this.end = new Position(file.getName(), end);
        }
    }
    private static Position decode(String cursor) {
        if (cursor == null) return null;
        Matcher match = CURSOR.matcher(cursor);
        if (!match.matches()) return null;
        try { return new Position(match.group(1), Long.parseLong(match.group(2))); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static int locate(File[] all, Position pos) {
        if (pos == null) return -1;
        for (int i = 0; i < all.length; i++)
            if (all[i].getName().equals(pos.filename) && pos.offset <= all[i].length()) return i;
        return -1;
    }
    private static JSONObject parseRow(String encoded) {
        try {
            String utf8 = new String(encoded.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            JSONObject value = new JSONObject(utf8);
            return RuntimeDiagnostics.safeStoredRow(value) ? value : null;
        } catch (Exception unavailable) { return null; }
    }

    /** Seek backwards through 64 KiB blocks, then parse only the newest page. */
    private static List<Row> preceding(File file, long until, int count) throws IOException {
        ArrayDeque<Row> rows = new ArrayDeque<>();
        try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
            long limit = Math.min(reader.length(), until);
            if (limit <= 0) return new ArrayList<>();
            byte[] chunk = new byte[65536];
            long cursor = limit;
            long start = 0;
            int newlines = 0;
            boolean skipTrailingNewline = true;
            boolean located = false;
            while (cursor > 0 && !located) {
                int n = (int) Math.min(chunk.length, cursor);
                cursor -= n;
                reader.seek(cursor);
                reader.readFully(chunk, 0, n);
                for (int i = n - 1; i >= 0; i--) {
                    if (chunk[i] != '\n') { skipTrailingNewline = false; continue; }
                    if (skipTrailingNewline) { skipTrailingNewline = false; continue; }
                    if (++newlines == count) { start = cursor + i + 1; located = true; break; }
                }
            }
            reader.seek(start);
            while (reader.getFilePointer() < limit) {
                long from = reader.getFilePointer();
                String line = reader.readLine();
                if (line == null || reader.getFilePointer() > limit) break;
                JSONObject value = parseRow(line);
                if (value != null) {
                    rows.addLast(new Row(value, file, from, reader.getFilePointer()));
                    while (rows.size() > count) rows.removeFirst();
                }
            }
        }
        return new ArrayList<>(rows);
    }
    private static void following(File file, long after, List<Row> list, int count) throws IOException {
        try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
            reader.seek(Math.min(after, reader.length()));
            while (list.size() < count) {
                long start = reader.getFilePointer();
                String line = reader.readLine();
                if (line == null) break;
                JSONObject json = parseRow(line);
                if (json != null) list.add(new Row(json, file, start, reader.getFilePointer()));
            }
        }
    }

    /** tail=latest, before=older, after=incremental live updates. */
    public JSONObject page(String mode, String cursor, int requested) throws IOException {
        if (!Set.of("tail", "before", "after").contains(mode)) throw new IllegalArgumentException("invalid mode");
        int count = Math.max(1, Math.min(200, requested));
        File[] all;
        synchronized (this) { flush(); all = segments(); }
        Position original = decode(cursor);
        int from = locate(all, original);
        boolean expired = !"tail".equals(mode) && from < 0;
        if (expired) mode = "tail";
        List<Row> rows = new ArrayList<>();
        if ("tail".equals(mode) || "before".equals(mode)) {
            int last = "tail".equals(mode) ? all.length - 1 : from;
            for (int i = last; i >= 0 && rows.size() < count; i--) {
                long end = "before".equals(mode) && i == from ? original.offset : all[i].length();
                rows.addAll(0, preceding(all[i], end, count - rows.size()));
            }
        } else {
            for (int i = from; i < all.length && rows.size() < count; i++)
                following(all[i], i == from ? original.offset : 0, rows, count);
        }
        JSONArray events = new JSONArray();
        for (Row row : rows) events.put(row.json);
        Position first = rows.isEmpty() ? null : rows.get(0).start;
        Position last = rows.isEmpty() ? null : rows.get(rows.size() - 1).end;
        return new JSONObject().put("format", FORMAT).put("entries", events).put("expired", expired)
            .put("older", first == null ? JSONObject.NULL : first.token())
            .put("next", last == null ? original == null ? JSONObject.NULL : original.token() : last.token())
            .put("hasOlder", first != null && (first.offset > 0 || locate(all, first) > 0))
            .put("storage", stats());
    }

    /** No giant String, byte[], or clipboard payload; open small file streams sequentially. */
    public void export(OutputStream destination) throws IOException {
        File[] originals;
        synchronized (this) { flush(); originals = segments(); }
        byte[] chunk = new byte[65536];
        for (File file : originals) {
            try (InputStream stream = new FileInputStream(file)) {
                int size;
                while ((size = stream.read(chunk)) != -1) destination.write(chunk, 0, size);
            }
        }
        destination.flush();
    }

    @Override public synchronized void close() {
        try { flush(); if (output != null) output.close(); }
        catch (IOException failure) { writeFailures++; }
        finally { output = null; }
    }
}
