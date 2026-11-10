package dev.pocketengine.phone;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Private unsent text only, keyed to one original task/run/epoch. Never stores a screen or dispatches work. */
final class QuickPanelDraftStore {
    private static final Object DISK = new Object();
    private static final String FORMAT = "pocketworld.quick-panel-draft.v1";
    private final File file;
    private final String task, run;
    private final int epoch;
    private State current;
    private boolean healthy;

    static final class State {
        final long revision;
        final String note, pendingClient;
        State(long revision, String note, String pendingClient) {
            this.revision = revision; this.note = note; this.pendingClient = pendingClient;
        }
        boolean same(State other) {
            return other != null && revision == other.revision && note.equals(other.note) && pendingClient.equals(other.pendingClient);
        }
    }

    QuickPanelDraftStore(File privateFiles, String task, String run, int epoch) {
        if (task == null || !task.matches("[A-Za-z0-9_-]{1,128}") || run == null || !run.matches("[A-Za-z0-9_-]{1,128}") || epoch < 1)
            throw new IllegalArgumentException("原快捷补充草稿身份无效");
        this.task = task; this.run = run; this.epoch = epoch;
        String key = UUID.nameUUIDFromBytes((FORMAT + "\n" + task + "\n" + run + "\n" + epoch).getBytes(StandardCharsets.UTF_8)).toString();
        file = new File(new File(privateFiles, "quick-panel-drafts"), key + ".json");
    }

    State load() throws Exception {
        synchronized (DISK) { current = read(); healthy = true; return current; }
    }

    State saveNote(String note) throws Exception {
        synchronized (DISK) {
            requireReady();
            if (!current.pendingClient.isEmpty()) throw new IllegalStateException("原输入登记结果待核对，不能改写或再次发送");
            return save(note, "");
        }
    }

    /** A foreground handoff may write only the exact draft that the original panel saved. */
    void requireSnapshot(State expected) throws Exception {
        synchronized (DISK) {
            requireReady();
            if (expected == null || !expected.pendingClient.isEmpty() || !expected.same(current) || !expected.same(read()))
                throw new IllegalStateException("原浮层草稿或发送状态已变化，请返回原浮层核对；未覆盖内容");
        }
    }

    State saveReviewed(State expected, String note) throws Exception {
        synchronized (DISK) { requireSnapshot(expected); return save(note, ""); }
    }

    State prepareSubmission(String note, String client) throws Exception {
        synchronized (DISK) {
            requireReady();
            if (!current.pendingClient.isEmpty()) throw new IllegalStateException("已有原输入回执待核对，未重新发送");
            if (note.length() > 1500 || !UUID.fromString(client).toString().equals(client))
                throw new IllegalArgumentException("原补充说明或发送身份无效");
            return save(note, client);
        }
    }

    State accepted(String client) throws Exception {
        synchronized (DISK) {
            if (!healthy && current != null && client != null && !client.isEmpty() && client.equals(current.pendingClient)) {
                // The original host has confirmed this send again. Reconcile only the local
                // cleanup that failed; never reopen submission or overwrite a newer draft.
                State stored = read();
                State cleared = new State(Math.addExact(current.revision, 1), "", "");
                if (cleared.same(stored)) {
                    current = stored; healthy = true; return current;
                }
                if (!current.same(stored)) throw new IllegalStateException("原草稿已变化，未覆盖新内容；请核对已登记回执");
                healthy = true;
            }
            requirePending(client); return save("", "");
        }
    }

    State rejectedBeforeDispatch(String client) throws Exception {
        synchronized (DISK) { requirePending(client); return save(current.note, ""); }
    }

    private void requirePending(String client) {
        requireReady();
        if (client == null || client.isEmpty() || !client.equals(current.pendingClient))
            throw new IllegalStateException("回执不属于这份原草稿，未清除内容");
    }
    private void requireReady() {
        if (!healthy || current == null) throw new IllegalStateException("原草稿尚未读取或保存未确认，未覆盖或发送");
    }
    private State save(String note, String client) throws Exception {
        if (note == null || note.length() > 8192 || !StandardCharsets.UTF_8.newEncoder().canEncode(note))
            throw new IllegalArgumentException("草稿超过8192字或含不完整Unicode；原文件保留，未发送");
        try {
            if (!current.same(read())) throw new IllegalStateException("另一原窗口已改变草稿；未覆盖新版本或发送");
            State next = new State(Math.addExact(current.revision, 1), note, client);
            JsonFiles.write(file, new JSONObject().put("format", FORMAT).put("taskId", task).put("runId", run)
                    .put("epoch", epoch).put("revision", next.revision).put("note", note).put("pendingClient", client));
            if (!next.same(read())) throw new IllegalStateException("原草稿保存未能读回确认，未发送");
            current = next; return current;
        } catch (Exception failed) { healthy = false; throw failed; }
    }
    private State read() throws Exception {
        if (!file.exists()) {
            if (current != null && current.revision != 0) throw new IllegalStateException("原草稿文件已消失，未重建或发送");
            return new State(0, "", "");
        }
        if (!file.isFile() || file.length() > 65536) throw new IllegalStateException("原草稿文件不可核对，未清空或覆盖");
        JSONObject value = JsonFiles.read(file);
        if (!FORMAT.equals(value.getString("format")) || !task.equals(value.getString("taskId")) || !run.equals(value.getString("runId"))
                || !(value.get("epoch") instanceof Integer || value.get("epoch") instanceof Long) || value.getLong("epoch") != epoch
                || !(value.get("revision") instanceof Integer || value.get("revision") instanceof Long) || value.getLong("revision") < 1)
            throw new SecurityException("原草稿身份不一致，未读取其他任务内容");
        String note = value.getString("note"), pending = value.getString("pendingClient");
        if (note.length() > 8192 || !StandardCharsets.UTF_8.newEncoder().canEncode(note)
                || !pending.isEmpty() && !UUID.fromString(pending).toString().equals(pending))
            throw new IllegalStateException("原草稿记录无效，未重置");
        return new State(value.getLong("revision"), note, pending);
    }
}
