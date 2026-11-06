package dev.pocketengine.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded operational metadata, including failures before the task store is ready.
 * Never pass prompts, tool arguments/results, URLs, credentials or exception messages.
 * This is diagnostic evidence, not a task journal or proof that an operation committed.
 */
public final class RuntimeDiagnostics {
    public static final String TAG = "PocketWorld";
    public static final int MAX_EVENTS = 256;
    public static final int FILE_CAP_BYTES = 262144;
    private static final String FORMAT = "pocketworld.runtime-log.v1";
    private static final Object FILE_LOCK = new Object();
    /** The one wire vocabulary for both live records and restored records. */
    public enum Stage {
        BOOTSTRAP, LOGIN, ACCOUNT_REFRESH, MODEL_CATALOG, MODEL_PREPARE, MESSAGE_ADMISSION,
        MODEL_REQUEST, TOOL_EXECUTION, RUN_SETTLEMENT, WEBVIEW, DIAGNOSTICS;
        private final String wireValue = name().toLowerCase(Locale.ROOT);
        public String wireValue() { return wireValue; }
        private static Stage parse(String value) {
            for (Stage stage : values()) if (stage.wireValue.equals(value)) return stage;
            return null;
        }
    }
    /** Scope observations, never task states or permission to continue execution.
     * RUNNING includes an accepted pending tool receipt; it does not mean completion. */
    public enum Outcome {
        STARTED, SUCCEEDED, FAILED, REJECTED, CANCELLED, UNCONFIRMED, ACCEPTED,
        QUEUED, RUNNING, COMPLETED, AWAITING_USER, PAUSED, SKIPPED;
        private final String wireValue = name().toLowerCase(Locale.ROOT);
        public String wireValue() { return wireValue; }
        private static Outcome parse(String value) {
            for (Outcome outcome : values()) if (outcome.wireValue.equals(value)) return outcome;
            return null;
        }
    }
    private static volatile Recorder recorder = new Recorder(null, null);

    private RuntimeDiagnostics() {}

    @FunctionalInterface public interface LineSink { void write(String line); }

    /** Install once in the main application process, before opening business stores. Disk I/O is queued. */
    public static synchronized void install(File directory, LineSink logcat) {
        Recorder previous = recorder;
        try {
            recorder = new Recorder(directory, logcat);
            previous.close();
        } catch (RuntimeException unavailable) {
            previous.record("diagnostics", "failed", "LOG_WRITER_UNAVAILABLE", null, null,
                    UUID.randomUUID().toString(), 0, unavailable);
        }
    }

    @FunctionalInterface public interface Operation<T> { T run() throws Exception; }

    /**
     * Synchronous observation boundary. Return values and exceptions pass through unchanged.
     * A normal return means only that the callback returned, never that a business write committed.
     * Do not wrap asynchronous scheduling: keep its Span until the actual receipt arrives instead.
     * Stage and failureCode must be static metadata, never request bodies or exception messages.
     * The original RunControl links spans to the same task/run/epoch without thread-local state.
     */
    public static <T> T observe(String stage, RunControl control, String failureCode,
                                Operation<T> operation) throws Exception {
        try (Span span = begin(stage, control)) {
            try {
                T result = operation.run();
                span.success();
                return result;
            } catch (Exception | Error failure) {
                span.failure(failureCode, failure);
                throw failure;
            }
        }
    }

    public static Span begin(String stage, RunControl control) { return begin(stage, control, null); }
    public static Span begin(String stage, RunControl control, String reference) {
        return new Span(recorder, stage, control, reference);
    }

    /** Correlates physical attempts only; the native logical ID never becomes an execution grant. */
    public static Span beginModelRequest(RunControl control, String physicalCallId, String logicalRequestId) {
        return new Span(recorder, "model_request", control, physicalCallId,
                uuid(logicalRequestId) ? hash(logicalRequestId) : null);
    }

    public static void event(String stage, String outcome, String code, RunControl control, Throwable failure) {
        recorder.record(stage, outcome, code, control, null, UUID.randomUUID().toString(), 0, failure);
    }

    /** Observe a native task snapshot only when it still belongs to this original run.
     * This does not settle the task or prove persistence; never pass a summary or receipt body. */
    public static void taskStateObserved(RunControl original, String taskId, String runId, int epoch, String status) {
        RunControl.HostRunIdentity expected = original == null ? null : original.hostRunIdentity();
        if (expected == null || !java.util.Objects.equals(expected.taskId(), taskId)
                || !java.util.Objects.equals(expected.runId(), runId) || expected.epoch() != epoch) {
            event("run_settlement", "unconfirmed", "TASK_STATE_IDENTITY_CHANGED", original, null);
            return;
        }
        String outcome, code;
        switch (status == null ? "" : status) {
            case "completed": outcome = "completed"; code = "TASK_STATE_COMPLETED"; break;
            case "failed": outcome = "failed"; code = "TASK_STATE_FAILED"; break;
            case "cancelled": outcome = "cancelled"; code = "TASK_STATE_CANCELLED"; break;
            case "paused": outcome = "paused"; code = "TASK_STATE_PAUSED"; break;
            case "awaiting_user": outcome = "awaiting_user"; code = "TASK_STATE_AWAITING_USER"; break;
            case "needs_confirmation": outcome = "awaiting_user"; code = "TASK_STATE_NEEDS_CONFIRMATION"; break;
            case "queued": outcome = "queued"; code = "TASK_STATE_QUEUED"; break;
            case "preparing": outcome = "unconfirmed"; code = "TASK_STATE_PREPARING"; break;
            case "running": outcome = "unconfirmed"; code = "TASK_STATE_RUNNING"; break;
            case "reconciling": outcome = "unconfirmed"; code = "TASK_STATE_RECONCILING"; break;
            default: outcome = "unconfirmed"; code = "TASK_STATE_UNRECOGNIZED";
        }
        event("run_settlement", outcome, code, original, null);
    }

    /** Owner UI only. Callers must retain their original visible native owner-window guard. */
    public static JSONObject snapshot() { return recorder.snapshot(); }

    public static final class Span implements AutoCloseable {
        private final Recorder owner;
        private final String stage, reference, operationId = UUID.randomUUID().toString();
        private final RunControl control;
        private final String logicalRequestHash;
        private String nativeOperationHash;
        private final long started = System.nanoTime();
        private final AtomicBoolean settled = new AtomicBoolean();

        private Span(Recorder owner, String stage, RunControl control, String reference) {
            this(owner, stage, control, reference, null);
        }
        private Span(Recorder owner, String stage, RunControl control, String reference, String logicalRequestHash) {
            this.owner = owner; this.stage = stage; this.control = control; this.reference = reference;
            this.logicalRequestHash = logicalRequestHash;
            owner.record(stage, "started", "STARTED", control, reference, operationId, 0, null, logicalRequestHash, null);
        }
        /** Correlation only: bind a successful native journal/read claim, never a provider call ID.
         * The first valid UUID wins. Late/invalid bindings cannot rewrite a settled observation. */
        public synchronized void linkNativeOperation(String nativeOperationId) {
            if (!"tool_execution".equals(stage) || settled.get() || nativeOperationHash != null || !uuid(nativeOperationId)) return;
            try { nativeOperationHash = hash(nativeOperationId); }
            catch (RuntimeException unavailable) { /* Diagnostics must not affect native operation admission. */ }
        }
        public void success() { finish("succeeded", "OK"); }
        public void finish(String outcome, String code) { settle(outcome, code, null); }
        /** A native adapter observed cancellation of this scope without cancelling its run.
         * The adapter must classify its own trusted failure, never provider text or JSON.
         * Does not prove that remote effects stopped or that task cancellation committed. */
        public void cancelled(String code, Throwable failure) { settle(Outcome.CANCELLED.wireValue(), code, failure); }
        public void failure(String code, Throwable failure) {
            // Observe the scope's interruption, not a committed task state. A request can
            // be superseded while its original run remains live; waiting is resumable.
            // Inspect native types only, never exception text or caller-provided codes.
            if (control != null && control.isCancelled()) {
                settle("cancelled", code, failure);
            } else if (failure instanceof RunControl.ConditionWaitException) {
                settle("paused", "CONDITION_WAIT", failure);
            } else if (failure instanceof RunControl.ModelReplanRequested) {
                settle("cancelled", "MODEL_REQUEST_SUPERSEDED", failure);
            } else if (failure instanceof InterruptedException
                    || failure instanceof java.util.concurrent.CancellationException) {
                settle("cancelled", code, failure);
            } else {
                settle("failed", code, failure);
            }
        }
        private void settle(String outcome, String code, Throwable failure) {
            String nativeHash;
            synchronized (this) {
                if (!settled.compareAndSet(false, true)) return;
                nativeHash = nativeOperationHash;
            }
            // Never invoke an external sink while holding the span association lock.
            owner.record(stage, outcome, code, control, reference, operationId,
                    Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)), failure, logicalRequestHash, nativeHash);
        }
        @Override public void close() {
            if (!settled.get()) settle(control != null && control.isCancelled() ? "cancelled" : "unconfirmed",
                    "SCOPE_EXIT_WITHOUT_RECEIPT", null);
        }
    }

    static final class Recorder implements AutoCloseable {
        private final String session = UUID.randomUUID().toString();
        private final ArrayDeque<String> events = new ArrayDeque<>();
        private final File directory;
        private final LineSink sink;
        private final ThreadPoolExecutor writer;
        private final AtomicLong droppedWrites = new AtomicLong(), writeFailures = new AtomicLong(), unreadableRecords = new AtomicLong();
        private final AtomicLong invalidMetadataEvents = new AtomicLong();

        Recorder(File directory, LineSink sink) {
            this.directory = directory; this.sink = sink;
            if (directory == null) writer = null;
            else {
                writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_EVENTS), work -> {
                    Thread thread = new Thread(work, "runtime-diagnostics"); thread.setDaemon(true); return thread;
                }, (work, pool) -> droppedWrites.incrementAndGet());
                writer.execute(this::restore);
            }
        }

        void record(String stage, String outcome, String code, RunControl control, String reference,
                    String operation, long duration, Throwable failure) {
            record(stage, outcome, code, control, reference, operation, duration, failure, null, null);
        }

        private void record(String stage, String outcome, String code, RunControl control, String reference,
                    String operation, long duration, Throwable failure, String logicalRequestHash, String nativeOperationHash) {
            try {
                Stage observedStage = Stage.parse(stage);
                Outcome observedOutcome = Outcome.parse(outcome);
                boolean invalidMetadata = observedStage == null || observedOutcome == null;
                if (invalidMetadata) invalidMetadataEvents.incrementAndGet();
                // An unknown string is a schema defect, not ordinary uncertainty.
                // Never retain the unknown value, or silently relabel it as success.
                JSONObject row = new JSONObject().put("format", FORMAT).put("sessionId", session)
                        .put("eventId", UUID.randomUUID().toString()).put("operationId", operation)
                        .put("time", System.currentTimeMillis()).put("stage", (observedStage == null ? Stage.DIAGNOSTICS : observedStage).wireValue())
                        .put("outcome", (invalidMetadata ? Outcome.UNCONFIRMED : observedOutcome).wireValue())
                        .put("code", invalidMetadata ? "INVALID_DIAGNOSTIC_METADATA" : safeCode(code)).put("durationMs", Math.max(0, duration));
                if (logicalRequestHash != null) row.put("logicalRequestHash", logicalRequestHash);
                if (nativeOperationHash != null) row.put("nativeOperationHash", nativeOperationHash);
                if (reference != null && !reference.isEmpty()) row.put("referenceHash", hash(reference));
                RunControl.HostRunIdentity run = control == null ? null : control.hostRunIdentity();
                if (run != null) row.put("taskId", identifier(run.taskId())).put("runId", identifier(run.runId())).put("epoch", run.epoch());
                if (failure != null) {
                    JSONArray causes = new JSONArray(), suppressed = new JSONArray(), frames = new JSONArray();
                    Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    Set<String> recordedTypes = new java.util.HashSet<>();
                    for (Throwable cause = failure; cause != null && causes.length() < 4 && seen.add(cause); cause = cause.getCause()) {
                        causes.put(cause.getClass().getName());
                        recordedTypes.add(cause.getClass().getName());
                        // Native storage/cleanup errors may be suppressed to preserve the
                        // primary failure. Inspect only bounded immediate children, not a graph.
                        Throwable[] secondary = cause.getSuppressed();
                        for (int i = 0; i < Math.min(secondary.length, 4) && suppressed.length() < 4; i++)
                            suppressed.put(secondary[i].getClass().getName());
                    }
                    // Preserve primary/cause precedence and the old four-type wire limit.
                    // This is a type inventory, not a reconstructed exception tree.
                    for (int i = 0; i < suppressed.length() && causes.length() < 4; i++)
                        if (recordedTypes.add(suppressed.getString(i))) causes.put(suppressed.getString(i));
                    for (StackTraceElement frame : failure.getStackTrace()) {
                        if (frames.length() == 8) break;
                        String position = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                        if (position.length() <= 320 && position.matches("dev\\.pocketengine\\.[a-zA-Z0-9_.$<>]+:-?[0-9]{1,10}"))
                            frames.put(position);
                    }
                    row.put("exceptionTypes", causes).put("frames", frames);
                }
                String line = row.toString();
                synchronized (events) { retain(line); }
                // A caller-provided sink is observational, including when it throws an Error.
                // Never let it suppress a host recovery callback or replace the original failure.
                if (sink != null) try { sink.write(line); } catch (Throwable ignored) { /* Never log a sink failure through itself. */ }
                if (writer != null) writer.execute(() -> append(line));
            } catch (Exception ignored) {
                // In particular, never replace the original failure with a diagnostic encoding failure.
                writeFailures.incrementAndGet();
            }
        }

        JSONObject snapshot() {
            try {
                JSONArray rows = new JSONArray();
                synchronized (events) { for (String line : events) rows.put(new JSONObject(line)); }
                return new JSONObject().put("format", FORMAT).put("sessionId", session).put("events", rows)
                    .put("retainedEvents", rows.length()).put("maxEvents", MAX_EVENTS)
                    .put("fileCapBytes", FILE_CAP_BYTES * 2).put("droppedFileWrites", droppedWrites.get())
                    .put("fileWriteFailures", writeFailures.get()).put("unreadableRecords", unreadableRecords.get())
                    .put("invalidMetadataEvents", invalidMetadataEvents.get())
                    .put("bodyIncluded", false).put("automaticExport", false);
            } catch (Exception unavailable) { return new JSONObject(); }
        }

        private void retain(String line) { events.addLast(line); while (events.size() > MAX_EVENTS) events.removeFirst(); }

        private void restore() {
            ArrayDeque<String> prior = new ArrayDeque<>();
            synchronized (FILE_LOCK) {
                for (String name : new String[]{"runtime.previous.jsonl", "runtime.jsonl"}) {
                    File source = new File(directory, name);
                    try { discardOversized(source); } catch (Exception unavailable) { writeFailures.incrementAndGet(); continue; }
                    if (!source.isFile()) continue;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            try {
                                JSONObject row = new JSONObject(line);
                                if (!safeStoredRow(row)) { unreadableRecords.incrementAndGet(); continue; }
                                prior.addLast(row.toString()); while (prior.size() > MAX_EVENTS) prior.removeFirst();
                            } catch (Exception invalid) { unreadableRecords.incrementAndGet(); }
                        }
                    } catch (Exception unavailable) { writeFailures.incrementAndGet(); }
                }
            }
            synchronized (events) {
                prior.addAll(events); events.clear(); for (String line : prior) retain(line);
            }
        }

        private void append(String line) {
            try {
                synchronized (FILE_LOCK) {
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("directory_unavailable");
                    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > FILE_CAP_BYTES) { droppedWrites.incrementAndGet(); return; }
                    File active = new File(directory, "runtime.jsonl"), previous = new File(directory, "runtime.previous.jsonl");
                    discardOversized(active); discardOversized(previous);
                    if (active.length() + bytes.length > FILE_CAP_BYTES) {
                        if (previous.exists() && !previous.delete()) throw new java.io.IOException("rotation_unavailable");
                        if (active.exists() && !active.renameTo(previous)) throw new java.io.IOException("rotation_unavailable");
                    }
                    try (FileOutputStream output = new FileOutputStream(active, true)) { output.write(bytes); }
                }
            } catch (Exception unavailable) { writeFailures.incrementAndGet(); }
        }

        private void discardOversized(File file) throws java.io.IOException {
            if (file.length() <= FILE_CAP_BYTES) return;
            unreadableRecords.incrementAndGet();
            // These two files contain only disposable diagnostic metadata, never task or account state.
            if (!file.delete()) throw new java.io.IOException("oversized_log_unavailable");
        }

        /** Test/process teardown only; ordinary reads never wait for the writer. */
        @Override public void close() { if (writer != null) writer.shutdown(); }
        boolean awaitWrites(long milliseconds) throws InterruptedException {
            return writer == null || writer.awaitTermination(milliseconds, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean safeStoredRow(JSONObject row) {
        Set<String> keys = Set.of("format", "sessionId", "eventId", "operationId", "time", "stage", "outcome", "code",
                "durationMs", "referenceHash", "logicalRequestHash", "nativeOperationHash", "taskId", "runId", "epoch", "exceptionTypes", "frames");
        for (java.util.Iterator<String> names = row.keys(); names.hasNext();) if (!keys.contains(names.next())) return false;
        if (!FORMAT.equals(row.optString("format"))
                || !uuid(row.optString("sessionId")) || !uuid(row.optString("eventId")) || !uuid(row.optString("operationId"))
                || Stage.parse(row.optString("stage")) == null || Outcome.parse(row.optString("outcome")) == null
                || !row.optString("code").matches("[A-Z][A-Z0-9_]{0,63}")
                || !(row.opt("time") instanceof Number) || !(row.opt("durationMs") instanceof Number)) return false;
        if (row.has("referenceHash") && !row.optString("referenceHash").matches("[a-f0-9]{32}")) return false;
        if (row.has("logicalRequestHash") && (!Stage.MODEL_REQUEST.wireValue().equals(row.optString("stage"))
                || !row.optString("logicalRequestHash").matches("[a-f0-9]{32}"))) return false;
        if (row.has("nativeOperationHash") && (!Stage.TOOL_EXECUTION.wireValue().equals(row.optString("stage"))
                || !row.optString("nativeOperationHash").matches("[a-f0-9]{32}"))) return false;
        for (String key : new String[]{"taskId", "runId"})
            if (row.has(key) && !uuid(row.optString(key)) && !row.optString(key).matches("hash:[a-f0-9]{32}")) return false;
        if (row.has("epoch") && !(row.opt("epoch") instanceof Number)) return false;
        JSONArray types = row.optJSONArray("exceptionTypes"), frames = row.optJSONArray("frames");
        if (row.has("exceptionTypes") && (types == null || types.length() > 4)) return false;
        if (row.has("frames") && (frames == null || frames.length() > 8)) return false;
        if (types != null) for (int i = 0; i < types.length(); i++)
            if (!types.optString(i).matches("[a-zA-Z0-9_.$]{1,240}")) return false;
        if (frames != null) for (int i = 0; i < frames.length(); i++)
            if (frames.optString(i).length() > 320 || !frames.optString(i).matches("dev\\.pocketengine\\.[a-zA-Z0-9_.$<>]+:-?[0-9]{1,10}")) return false;
        return true;
    }

    private static String safeCode(String code) {
        String value = code == null ? "UNSPECIFIED" : code.toUpperCase(Locale.ROOT);
        return value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : "UNSPECIFIED";
    }
    private static boolean uuid(String value) { return value != null && value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"); }
    private static String identifier(String value) { return uuid(value) ? value : "hash:" + hash(value == null ? "" : value); }
    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int i = 0; i < 16; i++) result.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
