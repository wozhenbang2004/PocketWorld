package dev.pocketengine.media.jobs.results;

import dev.pocketengine.core.RunControl;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.pocketengine.media.jobs.results.GroupResultAccess.*;

/** Joins existing native evidence for a group result. No store, scheduler, submit, copy, upload or send path. */
public final class GroupMediaResults {
    private final GroupResultAccess nativeAccess;
    public GroupMediaResults(GroupResultAccess nativeAccess) { this.nativeAccess = java.util.Objects.requireNonNull(nativeAccess); }

    public JSONObject inspect(Selection selected, RunControl control) throws Exception {
        Context context = resolve(selected, control);
        JSONObject job = job(context, control);
        Artifact artifact = null;
        String artifactState = context.actual.artifactId.isEmpty() ? "not_ready" : "missing";
        try {
            artifact = artifact(context, job, control);
            if (artifact != null) artifactState = "saved";
        } catch (SecurityException mismatch) { throw mismatch; }
        catch (InterruptedException stopped) { throw stopped; }
        catch (Exception unavailable) { check(context, control); artifactState = "unavailable"; }
        JSONObject delivery;
        try { delivery = delivery(context, control); }
        catch (SecurityException mismatch) { throw mismatch; }
        catch (InterruptedException stopped) { throw stopped; }
        catch (Exception unavailable) {
            check(context, control);
            delivery = new JSONObject().put("state", "unknown").put("uploadState", "unreported").put("querySupported", false)
                    .put("message", "原投递回执暂不可读，没有重发").put("queryOnly", true)
                    .put("error", error("delivery_receipt_unavailable", "原投递回执暂不可读，请查询原记录"));
        }
        String status = job == null ? context.mediaSource ? "receipt_unavailable" : "not_media" : job.optString("status", "unknown");
        if (job == null && context.mediaSource && context.approvalState.matches("required|pending")) status = "awaiting_approval";
        if (!status.matches("not_media|awaiting_approval|receipt_unavailable|prepared|submitting|queued|running|stopping|unknown|response_received|failed|cancelled")) status = "unknown";
        JSONObject result = identity(context).put("kind", "group_media_result").put("taskStatus", context.taskStatus)
                .put("approvalState", context.approvalState)
                .put("mediaState", status).put("artifactState", artifactState).put("artifactReady", artifact != null)
                .put("mediaMessage", mediaMessage(status, artifact != null, job)).put("delivery", delivery)
                .put("providerQuerySupported", job != null && job.optString("provider").equals("comfyui"))
                .put("sourceReceiptAvailable", job != null).put("verified", false).put("readConfirmed", false)
                .put("message", artifact != null ? "产物已保存；内容质量与消息回传状态分别核对" : "查看原任务与产物回执，不会重新生成或重发");
        if (artifact != null) result.put("artifact", artifactJson(artifact));
        else if (artifactState.equals("missing") || artifactState.equals("unavailable")) result.put("artifactError", error(artifactState.equals("missing") ? "artifact_missing" : "artifact_read_unavailable", "原产物当前不可读，请核对原任务文件；未重新生成"));
        if (job != null) result.put("providerState", providerState(job)).put("sourceJobId", job.optString("providerJobId"))
                .put("sourceJobIdConfirmed", job.optBoolean("providerJobIdConfirmed")).put("resultState", job.optString("resultStatus", "not_saved"));
        check(context, control);
        return result;
    }

    /** Explicit query/fetch of the original known media operation only; never creates or resubmits one. */
    public JSONObject refreshOriginalMedia(Selection selected, Refresh refresh, RunControl control) throws Exception {
        java.util.Objects.requireNonNull(refresh);
        Context context = resolve(selected, control);
        JSONObject receipt = job(context, control);
        if (receipt == null || !receipt.optString("provider").equals("comfyui")) {
            return inspect(selected, control).put("refreshPerformed", false).put("refreshReason", "original_provider_query_unavailable");
        }
        check(context, control);
        nativeAccess.refreshOriginalMedia(context, refresh, control);
        check(context, control);
        return inspect(selected, control).put("refreshPerformed", true).put("refreshKind", refresh.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Read one real scoped artifact into a bounded immutable snapshot. This is neither upload nor delivery. */
    public OpenedArtifact readArtifact(Selection selected, RunControl control) throws Exception {
        if (selected.outputId.isEmpty() || selected.artifactId.isEmpty()) throw new IllegalArgumentException("请先选择实际任务输出中的产物");
        Context context = resolve(selected, control);
        Artifact artifact = artifact(context, job(context, control), control);
        if (artifact == null) throw new IOException("原产物当前不可读；未重新生成");
        check(context, control);
        byte[] bytes = new byte[(int) artifact.bytes];
        boolean completed = false;
        try {
            InputStream opened = nativeAccess.openArtifact(context, artifact, control);
            if (opened == null) throw new IOException("原产物无法打开");
            Runnable close = () -> { try { opened.close(); } catch (Exception ignored) { } };
            control.onCancel(close);
            try (InputStream input = opened) {
                int offset = 0;
                long deadline = System.nanoTime() + 30_000_000_000L;
                while (offset < bytes.length) {
                    check(context, control);
                    if (System.nanoTime() >= deadline) throw new IOException("本地产物读取超过时限");
                    int n = input.read(bytes, offset, bytes.length - offset);
                    if (n < 0) throw new IOException("原产物字节不完整");
                    offset += n;
                }
                check(context, control);
                if (input.read() != -1) throw new IOException("原产物大小已变化");
                if (!sha256(bytes).equals(artifact.sha256)) throw new SecurityException("原产物digest已变化");
            } finally { control.removeCancelAction(close); }
            check(context, control);
            Artifact current = artifact(context, job(context, control), control);
            if (current == null || !sameArtifact(artifact, current)) throw new SecurityException("产物关联或内容已变化");
            check(context, control);
            completed = true;
            return new OpenedArtifact(identity(context).put("artifact", artifactJson(artifact)).put("artifactContentVerified", true)
                    .put("deliveryConfirmed", false).put("verified", false), bytes);
        } finally { if (!completed) Arrays.fill(bytes, (byte) 0); }
    }

    public static final class OpenedArtifact {
        private final String metadata;
        private final byte[] bytes;
        private OpenedArtifact(JSONObject metadata, byte[] bytes) { this.metadata = metadata.toString(); this.bytes = bytes; }
        public JSONObject metadata() throws Exception { return new JSONObject(metadata); }
        public byte[] copyBytes() { return bytes.clone(); }
    }

    private Context resolve(Selection selected, RunControl control) throws Exception {
        java.util.Objects.requireNonNull(selected); java.util.Objects.requireNonNull(control).check();
        Context context = nativeAccess.inspect(selected, control);
        if (context == null) throw new SecurityException("原群任务或结果没有可用的原生归属证明");
        Selection actual = context.actual;
        if (!selected.requestId.equals(actual.requestId) || !selected.taskId.equals(actual.taskId) || !selected.spaceId.equals(actual.spaceId)
                || !selected.audience.equals(actual.audience) || !selected.visibility.equals(actual.visibility)
                || !selected.outputId.isEmpty() && !selected.outputId.equals(actual.outputId)
                || !selected.artifactId.isEmpty() && !selected.artifactId.equals(actual.artifactId)
                || !selected.sourceOperationHint.isEmpty() && !selected.sourceOperationHint.equals(context.sourceOperationId)
                || !selected.sourceRunHint.isEmpty() && !selected.sourceRunHint.equals(context.sourceTaskRunId)) {
            throw new SecurityException("群结果选择已变化或不属于原任务/空间/受众");
        }
        check(context, control);
        return context;
    }
    private void check(Context context, RunControl control) throws Exception { control.check(); nativeAccess.checkRead(context, control); control.check(); }

    private JSONObject job(Context context, RunControl control) throws Exception {
        if (!context.mediaSource) return null;
        check(context, control);
        JSONObject receipt = nativeAccess.mediaReceipt(context, control);
        check(context, control);
        if (receipt == null) return null;
        if (receipt.toString().getBytes(StandardCharsets.UTF_8).length > 512 * 1024) throw new IOException("原媒体回执超过读取上限");
        receipt = new JSONObject(receipt.toString());
        if (!context.sourceOperationId.equals(receipt.optString("operationId")) || !context.actual.taskId.equals(receipt.optString("taskId"))
                || !context.sourceTaskRunId.equals(receipt.optString("runId"))
                || context.sourceJobIdConfirmed && (!receipt.optBoolean("providerJobIdConfirmed") || !context.sourceJobId.equals(receipt.optString("providerJobId")))) {
            throw new SecurityException("媒体回执与原task/operation/run/job不一致");
        }
        return receipt;
    }

    private Artifact artifact(Context context, JSONObject job, RunControl control) throws Exception {
        if (context.actual.artifactId.isEmpty()) return null;
        check(context, control);
        Artifact artifact = nativeAccess.artifact(context, control);
        check(context, control);
        if (artifact == null) return null;
        if (!context.actual.artifactId.equals(artifact.artifactId) || !context.actual.taskId.equals(artifact.taskId)
                || !context.actual.spaceId.equals(artifact.spaceId) || !context.actual.outputId.equals(artifact.outputId)
                || !context.actual.audience.equals(artifact.audience)
                || !context.sourceOperationId.equals(artifact.sourceOperationId) || !context.sourceTaskRunId.equals(artifact.sourceTaskRunId)) {
            throw new SecurityException("产物不是原群任务、空间、受众或原操作的产物");
        }
        if (job != null) {
            if (job.optBoolean("providerJobIdConfirmed") && !job.optString("providerJobId").equals(artifact.sourceJobId)) throw new SecurityException("产物来源job已变化");
            String origin = artifact.originArtifactId.isEmpty() ? artifact.artifactId : artifact.originArtifactId;
            JSONArray attachments = job.optJSONArray("attachments");
            if (attachments != null) for (int i = 0; i < attachments.length(); i++) {
                JSONObject source = attachments.getJSONObject(i);
                if (!origin.equals(source.optString("id"))) continue;
                if (!artifact.sha256.equals(source.optString("sha256")) || artifact.bytes != source.optLong("size", -1)
                        || !artifact.mimeType.equals(source.optString("mimeType"))) throw new SecurityException("群产物与原媒体附件digest/大小/MIME不一致");
            }
        }
        return artifact;
    }

    private JSONObject delivery(Context context, RunControl control) throws Exception {
        check(context, control);
        Delivery receipt = nativeAccess.delivery(context, control);
        check(context, control);
        if (receipt == null) return new JSONObject().put("state", "none").put("uploadState", "unreported").put("querySupported", false)
                .put("message", "产物尚无投递记录").put("messageAcknowledged", false).put("readConfirmed", false).put("queryOnly", true);
        if (!context.actual.taskId.equals(receipt.taskId) || !context.actual.spaceId.equals(receipt.spaceId)
                || !context.actual.outputId.equals(receipt.outputId) || !context.actual.artifactId.equals(receipt.artifactId)
                || !context.actual.audience.equals(receipt.audience)) throw new SecurityException("投递回执不属于原任务产物与受众");
        JSONObject result = new JSONObject().put("deliveryId", receipt.deliveryId).put("state", receipt.state).put("uploadState", receipt.uploadState)
                .put("platformMessageId", receipt.platformMessageId).put("messageAcknowledged", receipt.nativeMessageAcknowledged)
                .put("readConfirmed", false).put("querySupported", receipt.querySupported).put("queryOnly", true)
                .put("message", deliveryMessage(receipt));
        if (!receipt.errorCode.isEmpty()) result.put("error", error(receipt.errorCode, deliveryError(receipt.errorCode)));
        return result;
    }

    private static JSONObject identity(Context context) throws Exception {
        return new JSONObject().put("requestId", context.actual.requestId).put("taskId", context.actual.taskId).put("spaceId", context.actual.spaceId)
                .put("outputId", context.actual.outputId).put("artifactId", context.actual.artifactId).put("audience", context.actual.audience).put("visibility", context.actual.visibility)
                .put("taskRunId", context.taskRunId).put("coordinatorEpoch", context.coordinatorEpoch).put("receiptRevision", context.receiptRevision)
                .put("sourceOperationId", context.sourceOperationId).put("sourceTaskRunId", context.sourceTaskRunId).put("sourceCoordinatorEpoch", context.sourceCoordinatorEpoch)
                .put("sourceKind", context.mediaSource ? "media" : "task_file")
                .put("sourceJobId", context.sourceJobId).put("sourceJobIdConfirmed", context.sourceJobIdConfirmed);
    }
    private static JSONObject artifactJson(Artifact artifact) throws Exception {
        return new JSONObject().put("artifactId", artifact.artifactId).put("mimeType", artifact.mimeType).put("bytes", artifact.bytes).put("sha256", artifact.sha256)
                .put("spaceId", artifact.spaceId).put("audience", artifact.audience);
    }
    private static boolean sameArtifact(Artifact a, Artifact b) {
        return a.artifactId.equals(b.artifactId) && a.spaceId.equals(b.spaceId) && a.taskId.equals(b.taskId) && a.outputId.equals(b.outputId)
                && a.audience.equals(b.audience) && a.mimeType.equals(b.mimeType) && a.bytes == b.bytes && a.sha256.equals(b.sha256)
                && a.originArtifactId.equals(b.originArtifactId) && a.sourceOperationId.equals(b.sourceOperationId)
                && a.sourceTaskRunId.equals(b.sourceTaskRunId) && a.sourceJobId.equals(b.sourceJobId);
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static String providerState(JSONObject job) {
        String state = job.optString("providerState", "unknown");
        return state.matches("accepted|queued|running|completed|response_received|failed|cancelled|unknown") ? state : "unknown";
    }
    private static String mediaMessage(String status, boolean saved, JSONObject job) {
        if (saved) return "原群产物已保存，可查看；回传状态见投递回执";
        switch (status) {
            case "not_media": return "这是原任务文件，未推断为媒体生成结果";
            case "awaiting_approval": return "原任务等待必要审批；尚无媒体提交回执";
            case "receipt_unavailable": return "原媒体回执暂不可用，没有重新生成";
            case "prepared": return "原媒体操作已准备，尚无远端受理证明";
            case "submitting": case "unknown": return "原媒体操作结果未知；查询原操作，不重新生成或扣费";
            case "queued": return "原媒体任务已受理，正在排队";
            case "running": return "原媒体任务正在生成，尚无已保存产物";
            case "stopping": return "原取消请求已记录，远端停止仍需核对";
            case "response_received": return job != null && job.optString("resultStatus").equals("partial") ? "原生成回执已到，部分产物未保存；只获取原产物" : "原生成回执已到；仍需核对产物保存和群结果关联";
            case "cancelled": return "原媒体任务有取消回执；已有产物仍分别核对";
            default: return "原媒体任务未完成；保留原回执和已有文件，没有重新提交";
        }
    }
    private static String deliveryMessage(Delivery receipt) {
        if (receipt.state.equals("delivered")) return receipt.audience.equals("group") ? "平台已确认消息回到原群；未证明成员已读" : "平台已确认消息发送给原请求人；未证明已读";
        if (receipt.state.equals("failed")) return "原投递未完成，产物和回执仍可核对；没有重发";
        if (receipt.uploadState.equals("uploaded")) return "文件已上传，结果消息尚未确认送达";
        if (receipt.state.equals("accepted")) return "平台已受理消息，送达尚未确认";
        if (receipt.state.equals("unknown")) return "原投递结果未知，只查询原记录，没有重发";
        return "原投递处理中，尚无消息送达回执";
    }
    private static JSONObject error(String code, String message) throws Exception { return new JSONObject().put("code", code).put("message", message).put("queryOnly", true); }
    private static String deliveryError(String code) {
        switch (code) {
            case "reply_window_expired": return "回复窗口已过期，原产物仍保留；没有自动重发";
            case "authorization_revoked": return "群授权或受众权限已变化，停止新投递";
            case "unsupported_mime": return "当前群渠道不支持此文件格式";
            case "artifact_missing": case "artifact_changed": return "原文件缺失或内容已变化，请核对原产物";
            case "upload_unknown": return "文件上传结果未知，不能据此认定消息已发出";
            case "send_unknown": return "原消息发送结果未知，只查询原投递";
            case "platform_rejected": return "平台拒绝原投递，未自动改投或重发";
            default: return "原投递回执未完整确认，请核对原记录";
        }
    }
}
