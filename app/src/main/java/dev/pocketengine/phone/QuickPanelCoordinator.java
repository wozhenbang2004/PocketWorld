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
    private final Object captureState = new Object();
    private OwnerScreenContext capture;
    private OwnerScreenContext.Preview preview;
    private int request;
    private boolean preparingCapture;
    private volatile boolean closed;
    private boolean sending;
    private boolean submitting;
    private String clientMessageId;

    QuickPanelCoordinator(PhoneAccessibilityService service, OwnerScreenContextHost host,
                          JSONObject task, String location) throws Exception {
        this.service = service; this.host = host; this.main = service.mainHandler();
        expected = new JSONObject().put("taskId", task.getString("id")).put("runId", task.getString("runId"))
                .put("epoch", task.getInt("coordinatorEpoch")).put("executionMode", task.getString("executionMode"));
        surface = new QuickPanelSurface(service, task.getString("goal"), location, this);
    }

    void show() { surface.show(); }
    void captureHidden(boolean hidden) { surface.captureHidden(hidden); }
    boolean visible() { return surface.visible(); }
    android.view.View view() { return surface.view(); }

    @Override public void capture(boolean includeScreenshot) {
        final int pending;
        synchronized (captureState) {
            if (closed || sending || preparingCapture || capture != null) return;
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
            if (closed || sending || clientMessageId != null) return;
            ++request; preparingCapture = false; previous = capture; capture = null; preview = null;
        }
        if (previous != null) previous.close(); surface.ready();
    }

    @Override public void send(String note) {
        final OwnerScreenContext local; final OwnerScreenContext.Preview selected;
        synchronized (captureState) {
            local = capture; selected = preview;
            if (closed || sending || local == null || selected == null || clientMessageId != null) return;
        }
        if (note.length() > 1500) { surface.error("补充说明最多1500字，请编辑后再发送。"); return; }
        clientMessageId = UUID.randomUUID().toString(); sending = true;
        synchronized (captureState) { submitting = true; }
        final String client = clientMessageId; surface.sending();
        worker.execute(() -> {
            try {
                JSONObject receipt = host.submitOwnerScreenContext(local, selected, client, note);
                main.post(() -> { if (!closed && client.equals(clientMessageId)) receive(receipt); });
            } catch (OwnerScreenContextHost.Rejected refused) {
                main.post(() -> { if (!closed && client.equals(clientMessageId)) { sending = false; clientMessageId = null; surface.error(detail(refused, "内容未登记，请核对原任务。")); } });
            } catch (Exception uncertain) {
                main.post(() -> { if (!closed && client.equals(clientMessageId)) { sending = false; surface.unknown("输入登记结果暂不可确认，请查询原回执。原任务与这份内容的身份已保留。"); } });
            } finally {
                synchronized (captureState) { submitting = false; }
                releaseClosedCapture(local);
            }
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
            final OwnerScreenContext previous;
            synchronized (captureState) { previous = capture; capture = null; preview = null; }
            if (previous != null) previous.close(); clientMessageId = null;
            surface.saved(receipt.optString("status").equals("consumed") ? "原任务已采用这份补充。" : "补充已登记到原任务；暂停中的任务由你明确继续。");
        } else surface.unknown("原回执尚未证明这份内容已登记。请继续查询或到原任务核对，避免重复发送。");
    }

    @Override public void openOriginalTask() {
        Intent launch = new Intent(service, dev.pocketengine.ui.ComposeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("quickPanelTaskId", expected.optString("taskId"));
        service.startActivity(launch);
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
