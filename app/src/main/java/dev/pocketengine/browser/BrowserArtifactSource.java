package dev.pocketengine.browser;

import android.content.Context;
import android.net.Uri;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.AttachmentViewSnapshots;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import dev.pocketengine.features.workspace.WorkspaceModule;
import java.util.Arrays;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact existing task artifacts. Neither a path nor a model-provided URI is a source. */
public final class BrowserArtifactSource {
    private BrowserArtifactSource() { }

    public static JSONObject schema() throws Exception {
        return new JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", new JSONObject()
                        .put("store", new JSONObject().put("type", "string").put("enum", new JSONArray().put("attachments").put("workspace")))
                        .put("id", new JSONObject().put("type", "string").put("maxLength", 36))
                        .put("sha256", new JSONObject().put("type", "string").put("maxLength", 64))
                        .put("sourceVersion", new JSONObject().put("type", "integer").put("minimum", 1))
                        .put("sourceRevisionId", new JSONObject().put("type", "string").put("maxLength", 36)))
                .put("required", new JSONArray().put("store").put("id").put("sha256"));
    }

    public static void validate(JSONObject selection) throws Exception {
        if (selection == null) throw new IllegalArgumentException("请选择原任务成果及准确版本");
        for (var keys = selection.keys(); keys.hasNext(); )
            if (!Set.of("store", "id", "sha256", "sourceVersion", "sourceRevisionId").contains(keys.next()))
                throw new SecurityException("上传只接受原成果引用，不接受路径、URI或授权字段");
        if (!Set.of("attachments", "workspace").contains(selection.getString("store")))
            throw new IllegalArgumentException("未知原成果目录");
        BrowserTaskSpace.uuid(selection.getString("id"));
        if (!selection.getString("sha256").matches("[a-f0-9]{64}")) throw new IllegalArgumentException("需要原成果SHA-256");
        if (selection.getString("store").equals("workspace")) {
            if (!(selection.opt("sourceVersion") instanceof Integer || selection.opt("sourceVersion") instanceof Long)
                    || selection.getLong("sourceVersion") < 1 || selection.getLong("sourceVersion") > Integer.MAX_VALUE)
                throw new IllegalArgumentException("工作区上传须明确原成果版本");
            BrowserTaskSpace.uuid(selection.getString("sourceRevisionId"));
        } else if (selection.has("sourceVersion") || selection.has("sourceRevisionId")) {
            throw new IllegalArgumentException("附件以不可变ID与SHA固定版本，不接受工作区修订字段");
        }
    }

    /** Exact native metadata; legacy text catalogs use their bounded immutable revision for size. */
    public static JSONObject pin(Context context, WorkspaceModule workspace, String task, JSONObject selection) throws Exception {
        validate(selection);
        JSONObject record;
        if (selection.getString("store").equals("attachments")) {
            record = AttachmentRepository.get(context).book.get(selection.getString("id"));
            return attachmentPin(task, selection, record);
        }
        if (workspace == null) throw new IllegalStateException("原工作区成果宿主不可用");
        JSONArray records = workspace.handle("listArtifacts", new JSONObject()).getJSONArray("artifacts");
        record = null;
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.getJSONObject(i);
            if (selection.getString("id").equals(row.optString("id"))) {
                if (record != null) throw new SecurityException("原成果身份不唯一");
                record = row;
            }
        }
        if (record == null) throw new SecurityException("原工作区成果已撤下");
        if (!record.has("sizeBytes")) {
            record = new JSONObject(record.toString());
            record.put("sizeBytes", workspace.fileArtifactForHost(selection.getString("id"), selection.getString("sha256")).sizeBytes);
        }
        return workspacePin(task, selection, record);
    }

    static JSONObject attachmentPin(String task, JSONObject selected, JSONObject record) throws Exception {
        validate(selected);
        JSONObject source = record.getJSONObject("source");
        String ownerTask = source.optString("taskId");
        JSONObject publication = source.optJSONObject("nativePublication");
        if (ownerTask.isEmpty() && publication != null) ownerTask = publication.optString("taskId");
        if (!selected.getString("store").equals("attachments") || !task.equals(ownerTask)
                || publication != null && !task.equals(publication.optString("taskId"))
                || !"owner".equals(record.optString("principalId")) || !"private".equals(record.optString("spaceId"))
                || record.optBoolean("outputSourceRetired") || source.has("groupSpaceId") || source.optString("kind").startsWith("group_"))
            throw new SecurityException("只能上传本任务当前可用的准确私人附件");
        return commonPin(selected, record, record.getLong("size"), record.getString("name"))
                .put("versionKind", "immutable_attachment_id_sha256");
    }

    static JSONObject workspacePin(String task, JSONObject selected, JSONObject record) throws Exception {
        validate(selected);
        if (!selected.getString("store").equals("workspace") || !task.equals(record.optString("taskId"))
                || !"task_workspace".equals(record.optString("source")) || record.optString("availability").equals("retired")
                || !Set.of("text_file", "binary_file").contains(record.optString("kind"))
                || selected.getInt("sourceVersion") != record.getInt("sourceVersion")
                || !selected.getString("sourceRevisionId").equals(record.getString("sourceRevisionId")))
            throw new SecurityException("工作区上传必须是本任务仍可用的原文件成果修订，不能改用最新文件或删除修订");
        BrowserTaskSpace.uuid(record.getString("projectId"));
        String path = record.getString("path");
        long size = record.getLong("sizeBytes");
        return commonPin(selected, record, size, path.substring(path.lastIndexOf('/') + 1))
                .put("sourceVersion", record.getInt("sourceVersion"))
                .put("sourceRevisionId", record.getString("sourceRevisionId"))
                .put("projectId", record.getString("projectId"))
                .put("versionKind", "immutable_workspace_revision");
    }

    private static JSONObject commonPin(JSONObject selected, JSONObject record, long size, String name) throws Exception {
        if (!selected.getString("id").equals(record.getString("id")) || !selected.getString("sha256").equals(record.getString("sha256"))
                || size < 0 || size > AttachmentBook.MAX_FILE_BYTES) throw new SecurityException("原成果版本或上传大小不匹配");
        return new JSONObject().put("store", selected.getString("store")).put("id", record.getString("id"))
                .put("sha256", record.getString("sha256")).put("size", size)
                .put("name", AttachmentBook.displayName(name)).put("mimeType", AttachmentBook.mimeType(record.getString("mimeType")))
                .put("createdAt", record.getLong("createdAt"));
    }

    public static JSONObject selection(JSONObject pin) throws Exception {
        JSONObject selected = new JSONObject().put("store", pin.getString("store")).put("id", pin.getString("id")).put("sha256", pin.getString("sha256"));
        if (pin.getString("store").equals("workspace")) selected.put("sourceVersion", pin.getInt("sourceVersion")).put("sourceRevisionId", pin.getString("sourceRevisionId"));
        return selected;
    }

    public static void requireCurrent(Context context, WorkspaceModule workspace, String task, JSONObject expected) throws Exception {
        if (!dev.pocketengine.store.TaskStore.capabilityFingerprint(expected).equals(
                dev.pocketengine.store.TaskStore.capabilityFingerprint(pin(context, workspace, task, selection(expected)))))
            throw new SecurityException("原成果已撤下、归属改变或版本漂移");
    }

    public static Uri stage(Context context, WorkspaceModule workspace, String task, JSONObject expected, RunControl original) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) throw new IllegalStateException("成果字节核验不能阻塞页面线程");
        original.check();requireCurrent(context, workspace, task, expected);
        byte[] bytes;
        if (expected.getString("store").equals("attachments")) {
            AttachmentRepository repository = AttachmentRepository.get(context);
            repository.privateOriginalForHost(expected.getString("id"), expected.getString("sha256"), original).requireCurrent(original);
            bytes = repository.book.readVerifiedSnapshot(expected.getString("id"), expected.getString("sha256"), original);
        } else {
            WorkspaceArtifacts.ArtifactRef selected = workspace.fileArtifactForHost(expected.getString("id"), expected.getString("sha256"));
            if (selected.version != expected.getInt("sourceVersion") || !selected.revisionId.equals(expected.getString("sourceRevisionId")))
                throw new SecurityException("原工作区成果修订已变化");
            bytes = workspace.fileArtifactBytesForHost(selected);
        }
        Uri stage = null;
        try {
            original.check();requireCurrent(context, workspace, task, expected);
            if (bytes.length > AttachmentBook.MAX_FILE_BYTES || expected.getLong("size") >= 0 && bytes.length != expected.getLong("size"))
                throw new SecurityException("原文件大小已变化");
            stage = new AttachmentViewSnapshots(context).prepare(expected.getString("name"), expected.getString("mimeType"), expected.getString("sha256"), bytes, original).getData();
            original.check();requireCurrent(context, workspace, task, expected);return stage;
        } catch (Exception failure) {
            if (stage != null) try {new AttachmentViewSnapshots(context).discardUnsent(stage);} catch (Exception cleanup) {failure.addSuppressed(cleanup);}
            throw failure;
        } finally {Arrays.fill(bytes, (byte) 0);}
    }
}
