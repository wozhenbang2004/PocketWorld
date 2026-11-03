package dev.pocketengine.browser;

import android.content.Context;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentViewSnapshots;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** One original operation, artifact version and native chooser. Unknown delivery is never replayed. */
public final class BrowserArtifactUpload implements AutoCloseable {
    public interface Host {
        void post(Runnable work);
        void later(Runnable work, long delayMs);
        void check() throws Exception;
        void openInput(BrowserArtifactUpload delivery) throws Exception;
        void verifyInput(Consumer<Boolean> reply) throws Exception;
        default void delivered(JSONObject attempt) throws Exception { }
    }
    // Test seams around the original stores, not another upload journal or source of authority.
    interface Files {
        Uri stage() throws Exception;
        void check() throws Exception;
        void verify() throws Exception;
        void discard(Uri stage) throws Exception;
    }
    interface Ledger {
        JSONObject begin(JSONObject identity, JSONObject artifact) throws Exception;
        void event(JSONObject attempt, String state, JSONObject evidence) throws Exception;
    }
    private final RunControl original;
    private final long pauseRevision;
    private final JSONObject identity, artifact;
    private final Executor worker;
    private final Host host;
    private final Files files;
    private final Ledger ledger;
    private final CompletableFuture<JSONObject> result;
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean(), finishing = new AtomicBoolean(), callbackUsed = new AtomicBoolean(), deliveryScheduled = new AtomicBoolean(), nativeDispatch = new AtomicBoolean(), inputActivated = new AtomicBoolean();
    private final Runnable cancelled = this::close;
    private volatile Uri stage;
    private volatile JSONObject attempt;
    private volatile ValueCallback<Uri[]> callback;
    private volatile boolean boundaryAttempted, handedToWebView, chooserSeen, inputRequested;
    private volatile FutureTask<Void> activeWork;
    private volatile Thread activeThread;

    public BrowserArtifactUpload(Context context, WorkspaceModule workspace, TaskStore tasks, RunControl original,
            JSONObject identity, JSONObject artifact, Executor worker, Host host, CompletableFuture<JSONObject> result) throws Exception {
        this(original, identity, artifact, worker, host, new Files() {
            public Uri stage() throws Exception {return BrowserArtifactSource.stage(context, workspace, identity.getString("taskId"), artifact, original);}
            public void check() throws Exception {BrowserArtifactSource.requireCurrent(context, workspace, identity.getString("taskId"), artifact);}
            public void verify() throws Exception {
                check();
                if (artifact.getString("store").equals("attachments"))
                    dev.pocketengine.features.attachments.AttachmentRepository.get(context).privateOriginalForHost(artifact.getString("id"), artifact.getString("sha256"), original).requireCurrent(original);
                else workspace.fileArtifactForHost(artifact.getString("id"), artifact.getString("sha256"));
                check();
            }
            public void discard(Uri uri) throws Exception {new AttachmentViewSnapshots(context).discardUnsent(uri);}
        }, new Ledger() {
            public JSONObject begin(JSONObject pin, JSONObject source) throws Exception {
                synchronized (tasks) {
                    JSONObject task = tasks.get(pin.getString("taskId"), false);
                    if (task == null) throw new SecurityException("原上传任务已删除");
                    BrowserUploadAttempt.requireNewArtifactAttempt(task.optJSONArray("browserUploadAttempts"), pin, source);
                    return tasks.beginBrowserUpload(pin, new JSONArray().put(source));
                }
            }
            public void event(JSONObject row, String state, JSONObject evidence) throws Exception {
                tasks.updateBrowserUpload(row.getJSONObject("pin").getString("taskId"), row.getString("attemptId"), row.getString("fingerprint"), state, evidence);
            }
        }, result);
    }

    BrowserArtifactUpload(RunControl original, JSONObject identity, JSONObject artifact, Executor worker, Host host,
            Files files, Ledger ledger, CompletableFuture<JSONObject> result) throws Exception {
        this.original = original;this.pauseRevision = original.pauseRevision();this.identity = new JSONObject(identity.toString());
        this.artifact = new JSONObject(artifact.toString());this.worker = worker;this.host = host;this.files = files;this.ledger = ledger;this.result = result;
        original.onCancel(cancelled);
        result.whenComplete((value, error) -> {if (!finishing.get()) close();});
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        submit(() -> {
            try {
                checkRun();files.check();
                Uri prepared = files.stage();
                boolean retained;
                synchronized (this) {retained = !closed.get();if (retained) stage = prepared;}
                if (!retained) {files.discard(prepared);return;}
                checkRun();files.check();
                synchronized (this) {if (closed.get()) return;attempt = ledger.begin(identity, artifact);}
                if (closed.get()) return;
                host.post(() -> {
                    try {
                        checkPage();inputRequested = true;host.openInput(this);
                        host.later(() -> {if (!chooserSeen && !finishing.get()) finish("unavailable", "file_chooser_not_opened");}, 5000);
                    } catch (Exception changed) {finish("unavailable", "original_file_input_changed");}
                });
            } catch (Exception failed) {finish("unavailable", "artifact_prepare_unavailable");}
        });
    }

    /** Only the native WebChromeClient for the retained Page may call this. */
    public boolean chooser(ValueCallback<Uri[]> actual, WebChromeClient.FileChooserParams params) {
        if (params == null) {actual.onReceiveValue(null);finish("unavailable", "file_chooser_parameters_missing");return true;}
        return chooser(actual, params.getMode(), params.getAcceptTypes());
    }
    boolean chooser(ValueCallback<Uri[]> actual, int mode, String[] acceptTypes) {
        synchronized (this) {
            if (closed.get() || result.isDone() || chooserSeen || !inputRequested || !inputActivated.get()) {
                if (actual != callback || callbackUsed.compareAndSet(false, true)) actual.onReceiveValue(null);
                return true;
            }
            chooserSeen = true;callback = actual;
        }
        try {
            checkPage();
            if (stage == null || mode != WebChromeClient.FileChooserParams.MODE_OPEN && mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                    || !mimeAccepted(acceptTypes, artifact.getString("mimeType"), artifact.getString("name")))
                throw new SecurityException("原文件控件不接受所选版本");
            host.verifyInput(current -> {
                if (!current) {finish("unavailable", "file_input_identity_changed");return;}
                if (!deliveryScheduled.compareAndSet(false,true)) return;
                submit(() -> prepareDelivery());
            });
        } catch (Exception stale) {finish("unavailable", "file_chooser_rejected_artifact");}
        return true;
    }

    private void prepareDelivery() {
        try {
            checkRun();files.verify();checkRun();
            // Persist before entering the real callback; even a failed write may have committed.
            synchronized(this){if(closed.get())throw new SecurityException("upload_cancelled");boundaryAttempted = true;}
            ledger.event(attempt, "delivery_entered", evidence("native_exact_artifact_file_chooser"));
            host.post(() -> {
                try {
                    checkPage();
                    // Bytes/ledger work may have taken time: recheck the actual input again now.
                    host.verifyInput(current -> {
                        if (!current) {finish("unknown", "file_input_changed_before_delivery");return;}
                        deliver();
                    });
                } catch (Exception changed) {finish("unknown", "artifact_upload_delivery_unknown");}
            });
        } catch (Exception changed) {finish(boundaryAttempted ? "unknown" : "unavailable", "artifact_changed_before_delivery");}
    }

    private void deliver() {
        if (!nativeDispatch.compareAndSet(false, true)) return;
        try {
            checkPage();
            original.dispatchAtPauseRevision(pauseRevision, () -> {
                synchronized (this) {
                    if (closed.get() || !callbackUsed.compareAndSet(false, true)) throw new SecurityException("upload_no_longer_current");
                    handedToWebView = true;callback.onReceiveValue(new Uri[]{stage});
                }
            });
            host.delivered(new JSONObject(attempt.toString()));
            submit(() -> {
                try {ledger.event(attempt, "local_delivered", evidence("native_webview_callback"));finish("local_delivered", "browser_upload_site_check_needed");}
                catch (Exception uncertain) {finish("unknown", "artifact_upload_delivery_unknown");}
            });
        } catch (Exception changed) {finish("unknown", "artifact_upload_delivery_unknown");}
    }

    private void checkRun() throws Exception {
        original.check();
        if (closed.get() || result.isDone() || original.isPaused() || original.pauseRevision() != pauseRevision)
            throw new SecurityException("上传原任务已暂停、取消或更换");
    }
    private void checkPage() throws Exception {checkRun();host.check();files.check();checkRun();}
    /** The host has read back the exact focused file input; dispatch only one native activation. */
    public void activateInput(RunControl.DispatchEntry entry) throws Exception {
        checkPage();
        if (!inputRequested || !inputActivated.compareAndSet(false, true)) throw new SecurityException("原文件控件只能激活一次");
        original.dispatchAtPauseRevision(pauseRevision, entry);
    }
    public boolean chooserSeen() {return chooserSeen;}
    public boolean inputRequested() {return inputRequested;}
    public boolean active() {return !closed.get() && !result.isDone();}
    public void inputNotOpened() {if (!chooserSeen) finish("unavailable", "file_input_did_not_open_chooser");}

    private void submit(Runnable work) {
        if (closed.get()) return;
        FutureTask<Void> job = new FutureTask<>(() -> {
            activeThread = Thread.currentThread();
            try {if (!closed.get()) work.run();}
            finally {activeThread = null;}
            return null;
        });
        activeWork = job;
        try {worker.execute(job);} catch (RuntimeException rejected) {finish("unavailable", "artifact_upload_worker_unavailable");}
    }

    private void finish(String requested, String code) {
        if (!finishing.compareAndSet(false, true)) return;
        closed.set(true);original.removeCancelAction(cancelled);
        FutureTask<Void> running = activeWork;
        if (running != null && activeThread != Thread.currentThread()) running.cancel(true);
        try {host.post(() -> {
            ValueCallback<Uri[]> pending = callback;
            if (pending != null && callbackUsed.compareAndSet(false, true)) try {pending.onReceiveValue(null);} catch (RuntimeException ignored) { }
        });} catch (RuntimeException unavailable) { /* A stopped native host receives no file. */ }
        Runnable finalize = () -> {
            String state = boundaryAttempted && !requested.equals("local_delivered") ? "unknown" : requested;
            boolean cleanupUnknown = false;
            if (!handedToWebView) {
                Uri unsent;
                synchronized (this) {unsent = stage;stage = null;}
                if (unsent != null) try {files.discard(unsent);} catch (Exception cleanup) {cleanupUnknown = true;}
            }
            try {
                if (attempt != null && !state.equals("local_delivered"))
                    ledger.event(attempt, boundaryAttempted ? "site_unknown" : "cancelled_before_delivery", evidence("native_artifact_upload").put("reason", code));
            } catch (Exception uncertain) {state = "unknown";}
            try {
                JSONObject receipt = new JSONObject().put("status", cleanupUnknown ? "cleanup_required" : state)
                        .put("accepted", state.equals("unknown") ? JSONObject.NULL : state.equals("local_delivered"))
                        .put("verified", false).put("siteState", "unknown").put("siteConfirmed", false)
                        .put("automaticReplayAllowed", false).put("artifact", new JSONObject(artifact.toString()))
                        .put("freshObservationRequired", true).put("inputActivation", "native_focused_file_input_space_key")
                        .put("requiresPause", cleanupUnknown || state.equals("unknown") || state.equals("local_delivered"))
                        .put("code", cleanupUnknown ? "artifact_upload_stage_cleanup_required" : code)
                        .put("message", state.equals("local_delivered") ? "准确文件版本已一次交给原WebView；请主人核对站点接收或拒绝，不会自动再次上传。" : "本次原文件交付未获完整确认；请核对原网页和上传记录，不会自动重传。");
                if (attempt != null) receipt.put("uploadAttemptId", attempt.getString("attemptId"));
                try {host.post(() -> result.complete(receipt));} catch(RuntimeException unavailable) {result.complete(receipt);}
            } catch (Exception malformed) {result.completeExceptionally(malformed);}
        };
        try {worker.execute(finalize);} catch (RuntimeException unavailable) {finalize.run();}
    }
    private JSONObject evidence(String source) throws Exception {
        return new JSONObject().put("source", source).put("siteConfirmed", false).put("operationId", identity.getString("operationId"))
                .put("fileInput", identity.getJSONObject("fileInput"));
    }
    static boolean mimeAccepted(String[] accepted, String actual, String name) {
        boolean constrained = false;
        for (String raw : accepted == null ? new String[0] : accepted) if (raw != null) for (String value : raw.split(",")) {
            String type = value.trim();if (type.isEmpty()) continue;constrained = true;
            if (type.equals("*/*") || type.equalsIgnoreCase(actual)
                    || type.endsWith("/*") && actual.toLowerCase(java.util.Locale.ROOT).startsWith(type.substring(0, type.length() - 1).toLowerCase(java.util.Locale.ROOT))
                    || type.matches("\\.[A-Za-z0-9]{1,12}") && name.toLowerCase(java.util.Locale.ROOT).endsWith(type.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return !constrained;
    }
    @Override public void close() {finish(boundaryAttempted ? "unknown" : "unavailable", "upload_cancelled_before_delivery");}
}
