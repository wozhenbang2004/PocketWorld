package dev.pocketengine.phone;

import android.content.Intent;
import android.os.Handler;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A UI lifecycle, never an executor: only explicit actions reach the existing native host. */
final class QuickPanelCoordinator implements AutoCloseable, QuickPanelSurface.Actions {
    private final PhoneAccessibilityService service;
    private final OwnerScreenContextHost host;
    private final JSONObject expected;
    private final Handler main;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final QuickPanelSurface surface;
    private final QuickPanelDraftStore drafts;
    private final Object draftState = new Object();
    private volatile boolean draftReady;
    private boolean draftSaveQueued;
    private String latestNote = "";
    private long editGeneration;
    private final Object captureState = new Object();
    private OwnerScreenContext capture;
    private OwnerScreenContext.Preview preview;
    private int request;
    private boolean preparingCapture;
    private volatile boolean closed;
    private boolean sending;
    private boolean submitting;
    private String clientMessageId;
    private volatile boolean voiceHandoffPreparing;
    private QuickPanelVoiceHandoff.Offer voiceOffer;
    private boolean voiceHandoffAccepted;
    private volatile boolean audioWorking;

    QuickPanelCoordinator(PhoneAccessibilityService service, OwnerScreenContextHost host,
                          JSONObject task, String location) throws Exception {
        this.service = service; this.host = host; this.main = service.mainHandler();
        expected = new JSONObject().put("taskId", task.getString("id")).put("runId", task.getString("runId"))
                .put("epoch", task.getInt("coordinatorEpoch")).put("executionMode", task.getString("executionMode"));
        drafts = new QuickPanelDraftStore(service.getFilesDir(), expected.getString("taskId"), expected.getString("runId"), expected.getInt("epoch"));
        surface = new QuickPanelSurface(service, task.getString("goal"), location, this);
        surface.audioScope("background".equals(task.getString("executionMode")));
    }

    void show() {
        surface.loadingDraft(); surface.show();
        worker.execute(() -> {
            try {
                QuickPanelDraftStore.State saved = drafts.load();
                main.post(() -> {
                    if (closed) return;
                    synchronized (draftState) { latestNote = saved.note; }
                    draftReady = true; surface.restoreDraft(saved.note);
                    if (!saved.pendingClient.isEmpty()) {
                        clientMessageId = saved.pendingClient;
                        surface.unknown("这份原补充的登记结果仍待核对。已恢复发送身份与草稿，不会重新发送；请查询原回执。");
                    }
                });
            } catch (Exception failed) { main.post(() -> { if (!closed) surface.draftUnavailable(detail(failed, "原草稿暂不可读取；未清空、捕获或发送。")); }); }
        });
    }
    void captureHidden(boolean hidden) { surface.captureHidden(hidden); }
    boolean visible() { return surface.visible(); }
    android.view.View view() { return surface.view(); }

    @Override public void capture(boolean includeScreenshot) {
        final int pending;
        synchronized (captureState) {
            if (closed || !draftReady || audioWorking || voiceHandoffPreparing || sending || clientMessageId != null || preparingCapture || capture != null) return;
            pending = ++request; preparingCapture = true;
        }
        surface.capturing();
        worker.execute(() -> {
            OwnerScreenContext local = null;
            try {
                local = host.openOwnerScreenContext(new JSONObject(expected.toString()));
                synchronized (captureState) {
                    if (closed || pending != request) { local.close(); return; }
                    capture = local; preparingCapture = false;
                }
                OwnerScreenContext.Preview selected = local.capture(includeScreenshot);
                final OwnerScreenContext published = local;
                byte[] bytes = selected.imageBytes();
                QuickPanelSurface.PreviewContent content;
                try { content = new QuickPanelSurface.PreviewContent(selected.appLabel.isBlank() ? selected.packageName : selected.appLabel,
                        selected.text, selected.truncated, bytes, screenshotMessage(selected)); }
                finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
                main.post(() -> {
                    synchronized (captureState) {
                        if (closed || pending != request || capture != published || selected.isClosed()) {
                            content.close(); published.close();
                            if (capture == published) capture = null;
                            return;
                        }
                        preview = selected;
                    }
                    surface.preview(content);
                });
            } catch (Exception failure) {
                if (local != null) local.close();
                main.post(() -> {
                    synchronized (captureState) {
                        if (closed || pending != request) return;
                        capture = null; preview = null; preparingCapture = false;
                    }
                    surface.error(detail(failure, "这次主屏读取未完成，请重新选择。"));
                });
            }
        });
    }

    @Override public void cancelPreview() {
        final OwnerScreenContext previous;
        synchronized (captureState) {
            if (closed || voiceHandoffPreparing || sending || clientMessageId != null) return;
            ++request; preparingCapture = false; previous = capture; capture = null; preview = null;
        }
        if (previous != null) previous.close(); surface.ready();
    }

    @Override public void send(String note) {
        final OwnerScreenContext local; final OwnerScreenContext.Preview selected;
        synchronized (captureState) {
            local = capture; selected = preview;
            if (closed || !draftReady || audioWorking || voiceHandoffPreparing || sending || local == null || selected == null || clientMessageId != null) return;
        }
        if (note.length() > 1500) { surface.error("补充说明最多1500字，请编辑后再发送。"); return; }
        clientMessageId = UUID.randomUUID().toString(); sending = true;
        synchronized (captureState) { submitting = true; }
        final String client = clientMessageId; surface.sending();
        worker.execute(() -> {
            try {
                drafts.prepareSubmission(note, client);
                JSONObject receipt = host.submitOwnerScreenContext(local, selected, client, note);
                main.post(() -> { if (!closed && client.equals(clientMessageId)) receive(receipt); });
            } catch (OwnerScreenContextHost.Rejected refused) {
                try {
                    drafts.rejectedBeforeDispatch(client);
                    main.post(() -> { if (!closed && client.equals(clientMessageId)) { sending = false; clientMessageId = null; surface.error(detail(refused, "内容未登记，请核对原任务；本机草稿仍保留。")); } });
                } catch (Exception saveUnknown) {
                    main.post(() -> { if (!closed && client.equals(clientMessageId)) { sending = false; surface.unknown("输入被拒绝，但原草稿状态保存未确认；内容保留，未重发。请回原任务核对。"); } });
                }
            } catch (Exception uncertain) {
                main.post(() -> { if (!closed && client.equals(clientMessageId)) { sending = false; surface.unknown("输入登记结果暂不可确认，请查询原回执。原任务与这份内容的身份已保留。"); } });
            } finally {
                synchronized (captureState) { submitting = false; }
                releaseClosedCapture(local);
            }
        });
    }

    /** Coalesce typing into the existing bounded lifecycle worker; closing never discards queued saves. */
    @Override public void noteChanged(String note) {
        synchronized (draftState) {
            if (closed || !draftReady || voiceHandoffPreparing || clientMessageId != null) return;
            latestNote = note;
            editGeneration++;
            if (draftSaveQueued) return;
            draftSaveQueued = true;
        }
        worker.execute(() -> {
            while (true) {
                final String text;
                synchronized (draftState) { text = latestNote; }
                try {
                    drafts.saveNote(text);
                    synchronized (draftState) {
                        if (!text.equals(latestNote)) continue;
                        draftSaveQueued = false;
                    }
                    main.post(() -> { if (!closed && clientMessageId == null) { synchronized (draftState) { if (!text.equals(latestNote)) return; } surface.draftStored(); } });
                    return;
                } catch (Exception failed) {
                    draftReady = failed instanceof IllegalArgumentException;
                    synchronized (draftState) { draftSaveQueued = false; }
                    main.post(() -> { if (!closed) surface.draftFailure(detail(failed, "草稿保存未确认；请保留当前输入，未发送。")); });
                    return;
                }
            }
        });
    }

    @Override public void clearDraft() {
        if (closed || !draftReady || voiceHandoffPreparing || sending || clientMessageId != null) return;
        final long generation;
        synchronized (draftState) { latestNote = ""; generation = ++editGeneration; }
        worker.execute(() -> {
            synchronized (draftState) { if (generation != editGeneration) return; }
            try { drafts.saveNote(""); main.post(() -> { if (!closed && clientMessageId == null) { synchronized (draftState) { if (generation != editGeneration) return; } surface.draftCleared(); } }); }
            catch (Exception failed) { draftReady = false; main.post(() -> { if (!closed) surface.draftFailure(detail(failed, "原草稿清除未确认，未声称已清除。")); }); }
        });
    }

    @Override public void queryReceipt() {
        if (closed || sending || clientMessageId == null) return;
        final String client = clientMessageId; sending = true;
        surface.querying();
        final OwnerScreenContext queried;
        synchronized (captureState) { queried = capture; }
        worker.execute(() -> {
            try { JSONObject receipt = host.ownerScreenContextReceipt(new JSONObject(expected.toString()), client);
                main.post(() -> { if (!closed && client.equals(clientMessageId)) receive(receipt); });
            } catch (Exception unavailable) {
                main.post(() -> { if (!closed && client.equals(clientMessageId)) { sending = false; surface.unknown("原回执暂不可查询，请保留这份状态并在原任务核对。"); } });
            } finally {
                releaseClosedCapture(queried);
            }
        });
    }

    private void releaseClosedCapture(OwnerScreenContext owned) {
        if (!closed || owned == null) return;
        synchronized (captureState) {
            if (capture == owned) { capture = null; preview = null; }
        }
        owned.close();
    }

    private void receive(JSONObject receipt) {
        sending = false;
        if (receipt != null && clientMessageId.equals(receipt.optString("clientMessageId"))
                && expected.optString("taskId").equals(receipt.optString("taskId"))
                && expected.optString("runId").equals(receipt.optString("runId"))
                && expected.optInt("epoch") == receipt.optInt("epoch")
                && receipt.optString("status").matches("accepted|queued|consumed")) {
            final String client = clientMessageId; sending = true;
            worker.execute(() -> {
                try {
                    drafts.accepted(client);
                    main.post(() -> {
                        if (closed || !client.equals(clientMessageId)) return;
                        final OwnerScreenContext previous;
                        synchronized (captureState) { previous = capture; capture = null; preview = null; }
                        if (previous != null) previous.close(); clientMessageId = null; sending = false;
                        synchronized (draftState) { latestNote = ""; }
                        surface.saved(receipt.optString("status").equals("consumed") ? "原任务已采用这份补充。" : "补充已登记到原任务；暂停中的任务由你明确继续。");
                    });
                } catch (Exception failed) {
                    main.post(() -> { if (!closed && client.equals(clientMessageId)) { sending = false; surface.unknown("原回执已证明输入登记，但本机草稿清理未确认；未重新发送。请核对原任务。"); } });
                }
            });
        } else surface.unknown("原回执尚未证明这份内容已登记。请继续查询或到原任务核对，避免重复发送。");
    }

    @Override public void openOriginalTask() {
        Intent launch = new Intent(service, dev.pocketengine.ui.ComposeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("quickPanelTaskId", expected.optString("taskId"));
        service.startActivity(launch);
    }

    @Override public void reviewAudio() { audio(null,false); }
    @Override public void setAudio(String reviewId,boolean muted) { if(reviewId!=null&&!reviewId.isBlank())audio(reviewId,muted); }
    private void audio(String reviewId,boolean muted) {
        synchronized(captureState){
            if(closed||audioWorking||sending||submitting||preparingCapture||voiceHandoffPreparing)return;
            audioWorking=true;
        }
        surface.audioLoading(reviewId!=null);
        worker.execute(()->{
            if(closed)return;
            try {
                if(!(service.getApplication() instanceof dev.pocketengine.EngineApplication app))throw new SecurityException("原任务宿主已变化");
                JSONObject exact=new JSONObject().put("taskId",expected.getString("taskId")).put("runId",expected.getString("runId")).put("epoch",expected.getInt("epoch"));
                JSONObject result=reviewId==null?app.reviewTaskAudio(exact):app.setTaskAudio(exact,reviewId,muted);
                if(!exact.getString("taskId").equals(result.optString("taskId"))||!exact.getString("runId").equals(result.optString("runId"))||exact.getInt("epoch")!=result.optInt("epoch"))
                    throw new SecurityException("音频回执不属于原任务，请回原任务核对");
                main.post(()->{if(!closed){audioWorking=false;surface.audioResult(result,reviewId==null);}});
            } catch(Exception unavailable){main.post(()->{if(!closed){audioWorking=false;surface.audioFailure(detail(unavailable,reviewId==null?"原 App 的声音暂不可核对。":"这次音频结果暂不能确认；请重新读取状态，不会重复派发。"));}});}
        });
    }

    @Override public void voice() {
        synchronized (captureState) {
            if (closed || !draftReady || audioWorking || voiceHandoffPreparing || sending || submitting || preparingCapture || clientMessageId != null) return;
            voiceHandoffPreparing = true;
        }
        final String note;
        synchronized (draftState) { note = latestNote; }
        surface.openingVoice();
        worker.execute(() -> {
            QuickPanelVoiceHandoff.Offer offered = null;
            boolean draftSaved = false;
            try {
                QuickPanelDraftStore.State saved = drafts.saveNote(note);
                draftSaved = true;
                offered = QuickPanelVoiceHandoff.offer(drafts,saved,expected,taskId -> {
                    // Reuse the existing original task/control check. Opening it reads no screen.
                    try (OwnerScreenContext checked = host.openOwnerScreenContext(new JSONObject(expected.toString()))) {
                        if (!(service.getApplication() instanceof dev.pocketengine.EngineApplication app)) throw new SecurityException("原快捷补充宿主已变化");
                        return app.tasks.getForUi(taskId,false);
                    }
                },() -> main.post(() -> {
                    if (closed) return;
                    voiceHandoffAccepted = true; close();
                }));
                final QuickPanelVoiceHandoff.Offer selected = offered;
                main.post(() -> {
                    if (closed) { selected.close();return; }
                    voiceOffer = selected;
                    try {
                        service.startActivity(new Intent(service,dev.pocketengine.ui.ComposeActivity.class)
                                .setAction(QuickPanelVoiceHandoff.ACTION)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                .putExtra(QuickPanelVoiceHandoff.TOKEN_EXTRA,selected.token));
                    } catch (RuntimeException unavailable) {
                        selected.close();voiceOffer=null;voiceHandoffPreparing=false;
                        surface.error("前台听写窗口暂不可打开；原说明仍在本机，没有录音或发送。");
                    }
                });
            } catch (Exception unavailable) {
                if (offered != null) offered.close();
                final boolean storageFailed = !draftSaved;
                if (storageFailed) draftReady = unavailable instanceof IllegalArgumentException;
                main.post(() -> {
                    if (closed) return;
                    voiceHandoffPreparing=false;
                    if (storageFailed) surface.draftFailure(detail(unavailable,"原草稿保存尚未确认，未打开听写。"));
                    surface.error(detail(unavailable,"原草稿或任务暂不可核对，没有打开听写或发送。"));
                });
            }
        });
    }

    @Override public void close() {
        final OwnerScreenContext previous;
        synchronized (captureState) {
            if (closed) return;
            closed = true; ++request; preparingCapture = false;
            previous = submitting ? null : capture;
            if (!submitting) { capture = null; preview = null; }
        }
        // A dispatched durable input write may already have won. Closing the surface does not
        // cancel the original task or manufacture a second send; its receipt stays in TaskStore.
        if (previous != null) previous.close();
        if (!voiceHandoffAccepted && voiceOffer != null) voiceOffer.close();
        worker.shutdown();
        surface.close(); service.quickPanelClosed(this);
    }

    private static String screenshotMessage(OwnerScreenContext.Preview preview) {
        if (preview.hasScreenshot()) return "已包含这次明确捕获的截图。";
        if ("not_requested".equals(preview.screenshotStatus)) return "这次只读取文字，没有截图。";
        return "截图未取得（" + preview.screenshotReason + "）；下面保留实际读到的文字。";
    }
    private static String detail(Exception failure, String fallback) {
        return failure.getMessage() == null || failure.getMessage().isBlank() ? fallback : failure.getMessage();
    }
}
