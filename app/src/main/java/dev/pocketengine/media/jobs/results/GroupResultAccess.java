package dev.pocketengine.media.jobs.results;

import dev.pocketengine.core.RunControl;
import java.io.InputStream;
import java.util.Locale;
import org.json.JSONObject;

/** Native root adapters only. These methods consume real TaskStore/artifact/outbox evidence, never model JSON. */
public interface GroupResultAccess {
    /** Authenticate the current reader and resolve the exact original task/output/operation from native stores. */
    Context inspect(Selection selection, RunControl control) throws Exception;
    /** Recheck current reader, space/audience, immutable output and original-operation association. Not execution authority. */
    void checkRead(Context expected, RunControl control) throws Exception;
    /** Read the existing live MediaJobs/MediaModule local getOperation receipt. Null means unavailable, not permission to submit. */
    JSONObject mediaReceipt(Context expected, RunControl control) throws Exception;
    Artifact artifact(Context expected, RunControl control) throws Exception;
    /** Only the imported scoped artifact selected by Context, never a path, URL or remote download. Caller closes it. */
    InputStream openArtifact(Context expected, Artifact expectedArtifact, RunControl control) throws Exception;
    /** Query the original existing outbox/GroupDeliveryPort record. This must never send/upload/copy. */
    Delivery delivery(Context expected, RunControl control) throws Exception;
    /** Delegate only to existing getJob/fetchResults with the original operation/configuration/live native authority. */
    void refreshOriginalMedia(Context expected, Refresh refresh, RunControl control) throws Exception;

    enum Refresh { PROVIDER_STATUS, ORIGINAL_ARTIFACTS }

    /** UI selection is a locator, never provenance or authorization. No path/URL is accepted. */
    final class Selection {
        public final String requestId, taskId, spaceId, outputId, artifactId, audience, visibility;
        public final String sourceOperationHint, sourceRunHint;
        public Selection(String requestId, String taskId, String spaceId, String outputId, String artifactId,
                String audience, String visibility, String sourceOperationHint, String sourceRunHint) {
            this.requestId = id(requestId); this.taskId = id(taskId); this.spaceId = id(spaceId);
            this.outputId = optionalId(outputId); this.artifactId = optionalId(artifactId);
            this.audience = one(audience, "group", "requester"); this.visibility = one(visibility, "members", "requester");
            this.sourceOperationHint = optionalId(sourceOperationHint); this.sourceRunHint = optionalId(sourceRunHint);
            if (this.spaceId.equals("private")) throw new SecurityException("群结果不能引用私人空间");
            if (!this.artifactId.isEmpty() && this.outputId.isEmpty()) throw new IllegalArgumentException("产物必须属于真实output");
        }
        /** Return to the same original operation's list; removing a locator never grants read/send authority. */
        public Selection withoutArtifact() {
            return new Selection(requestId, taskId, spaceId, "", "", audience, visibility, sourceOperationHint, sourceRunHint);
        }
    }

    /** Constructed by root only after reading authenticated task origin/output and original TaskStore operation. */
    final class Context {
        public final Selection actual;
        public final String taskRunId, taskStatus, approvalState, sourceOperationId, sourceTaskRunId, sourceJobId;
        public final int coordinatorEpoch, sourceCoordinatorEpoch;
        public final long receiptRevision;
        public final boolean sourceJobIdConfirmed;
        public final boolean mediaSource;
        public Context(Selection actual, String taskRunId, int coordinatorEpoch, long receiptRevision, String taskStatus, String approvalState,
                String sourceOperationId, String sourceTaskRunId, int sourceCoordinatorEpoch, String sourceJobId, boolean sourceJobIdConfirmed, boolean mediaSource) {
            this.actual = java.util.Objects.requireNonNull(actual); this.taskRunId = optionalId(taskRunId);
            if (receiptRevision < 1 || coordinatorEpoch < 0 || this.taskRunId.isEmpty() != (coordinatorEpoch == 0)) throw new IllegalArgumentException("群任务读取版本无效");
            this.coordinatorEpoch = coordinatorEpoch; this.receiptRevision = receiptRevision;
            this.taskStatus = one(taskStatus, "preparing", "queued", "running", "paused", "stopping", "awaiting_user", "needs_confirmation", "completed", "failed", "cancelled", "interrupted", "reconciling");
            this.approvalState = one(approvalState, "unreported", "not_required", "required", "pending", "approved", "denied", "revoked");
            this.sourceOperationId = optionalId(sourceOperationId); this.sourceTaskRunId = optionalId(sourceTaskRunId);
            this.sourceJobId = opaque(sourceJobId, 240); this.sourceJobIdConfirmed = sourceJobIdConfirmed;
            this.mediaSource = mediaSource;
            this.sourceCoordinatorEpoch = sourceCoordinatorEpoch;
            if (!this.sourceOperationId.isEmpty() && (this.sourceTaskRunId.isEmpty() || sourceCoordinatorEpoch < 1)) throw new IllegalArgumentException("媒体来源必须保留原operation/run/epoch");
            if (this.sourceOperationId.isEmpty() && (!this.sourceTaskRunId.isEmpty() || sourceCoordinatorEpoch != 0 || !this.sourceJobId.isEmpty())) throw new IllegalArgumentException("不能猜测媒体来源");
            if (sourceJobIdConfirmed && this.sourceJobId.isEmpty()) throw new IllegalArgumentException("已确认job必须有原服务身份");
            if (mediaSource && this.sourceOperationId.isEmpty() || !mediaSource && (!this.sourceJobId.isEmpty() || sourceJobIdConfirmed)) throw new IllegalArgumentException("媒体与普通任务文件来源不能混用");
        }
    }

    /** Real scoped artifact metadata after root verifies TaskStore association and repository ownership. */
    final class Artifact {
        public final String artifactId, spaceId, taskId, outputId, audience, mimeType, sha256;
        public final String originArtifactId, sourceOperationId, sourceTaskRunId, sourceJobId;
        public final long bytes;
        public Artifact(String artifactId, String spaceId, String taskId, String outputId, String audience, String mimeType,
                long bytes, String sha256, String originArtifactId, String sourceOperationId, String sourceTaskRunId, String sourceJobId) {
            this.artifactId = id(artifactId); this.spaceId = id(spaceId); this.taskId = id(taskId); this.outputId = id(outputId);
            this.audience = one(audience, "group", "requester");
            if (mimeType == null || !mimeType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) throw new IllegalArgumentException("产物MIME无效");
            this.mimeType = mimeType.toLowerCase(Locale.ROOT);
            if (bytes < 0 || bytes > 20L * 1024 * 1024 || sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("产物大小或digest无效");
            this.bytes = bytes; this.sha256 = sha256; this.originArtifactId = optionalId(originArtifactId);
            this.sourceOperationId = optionalId(sourceOperationId); this.sourceTaskRunId = optionalId(sourceTaskRunId); this.sourceJobId = opaque(sourceJobId, 240);
        }
    }

    /** Root's existing native delivery evidence. Upload acknowledgment is separate from message acknowledgment. */
    final class Delivery {
        public final String deliveryId, taskId, spaceId, outputId, artifactId, audience, state, uploadState, platformMessageId, errorCode;
        public final boolean nativeMessageAcknowledged, querySupported;
        public Delivery(String deliveryId, String taskId, String spaceId, String outputId, String artifactId, String audience,
                String state, String uploadState, String platformMessageId, boolean nativeMessageAcknowledged, boolean querySupported, String errorCode) {
            this.deliveryId = id(deliveryId); this.taskId = id(taskId); this.spaceId = id(spaceId); this.outputId = id(outputId); this.artifactId = id(artifactId);
            this.audience = one(audience, "group", "requester");
            this.state = one(state, "prepared", "sending", "accepted", "delivered", "unknown", "failed");
            this.uploadState = one(uploadState, "unreported", "not_uploaded", "uploading", "uploaded", "unknown", "failed", "not_applicable");
            this.platformMessageId = opaque(platformMessageId, 240); this.nativeMessageAcknowledged = nativeMessageAcknowledged;
            this.querySupported = querySupported;
            this.errorCode = errorCode == null || errorCode.isEmpty() ? "" : one(errorCode, "reply_window_expired", "authorization_revoked", "unsupported_mime", "artifact_missing", "artifact_changed", "transport_unavailable", "upload_unknown", "send_unknown", "platform_rejected", "other");
            if (state.equals("delivered") && (!nativeMessageAcknowledged || this.platformMessageId.isEmpty())) throw new IllegalArgumentException("投递完成需真实消息回执，不是上传回执");
        }
    }

    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_:.@+\\-]{1,240}")) throw new IllegalArgumentException("群结果引用无效");
        return value;
    }
    static String optionalId(String value) { return value == null || value.isEmpty() ? "" : id(value); }
    static String opaque(String value, int max) {
        if (value == null) return "";
        if (value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("群结果回执身份无效");
        return value;
    }
    static String one(String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        throw new IllegalArgumentException("群结果状态或范围无效");
    }
}
