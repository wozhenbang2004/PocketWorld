package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** One already witnessed compiler output, saved by the owner from its ended Work.
 * This carries no build authority and can never select a different output. */
public final class OriginalNodeCompilerDelivery {
    public static final String TOOL = "owner_node_compiler_save", PENDING = "compiler_output_pending";
    public static final String ROLE = "owner_node_compiler_output", PROVENANCE = "original_node_compiler_output";
    private static final String MIME = "text/javascript";
    private final OriginalNodeCompilerOutput.Witness witness;
    private final JSONObject facts;
    private final String operation, digest, name;

    public OriginalNodeCompilerDelivery(OriginalNodeCompilerOutput.Witness witness) throws Exception {
        this.witness = java.util.Objects.requireNonNull(witness);
        facts = witness.pin();
        operation = UUID.nameUUIDFromBytes(("owner-node-compiler-save-v1\0" + taskId() + "\0" + runId()
                + "\0" + facts.getInt("epoch") + "\0" + facts.getString("buildOperationId")).getBytes(StandardCharsets.UTF_8)).toString();
        digest = ProjectSources.digest(pin());
        String path = witness.path();
        name = path.substring(path.lastIndexOf('/') + 1);
    }

    public OriginalNodeCompilerOutput.Witness witness() { return witness; }
    public String taskId() throws Exception { return facts.getString("taskId"); }
    public String runId() throws Exception { return facts.getString("runId"); }
    public String operationId() { return operation; }
    public JSONObject pin() throws Exception { return ProjectSources.copy(facts).put("saveOperationId", operation); }
    public JSONObject view(String state) throws Exception { return ProjectSources.copy(facts).put("state", state); }
    public boolean matches(JSONObject pin) throws Exception { return ProjectSources.canonical(pin()).equals(ProjectSources.canonical(pin)); }

    /** Detect even a partial compiler marker; callers must then verify the whole original. */
    public static boolean claims(JSONObject source) {
        JSONObject publication = source == null ? null : source.optJSONObject("nativePublication");
        return source != null && (source.has("originalCompilerWitness") || PROVENANCE.equals(source.opt("buildOutputAttribution")) ||
                publication != null && ROLE.equals(publication.opt("role")));
    }

    /** Body-free original result row already proven by the local catalog. */
    public static boolean resultRow(JSONObject row) {
        return row != null && ProjectManagedOutput.ROLE.equals(row.opt("sourceKind")) && PROVENANCE.equals(row.opt("sourceRunEvidence"));
    }

    private JSONObject source() throws Exception {
        return new JSONObject().put("kind", ProjectManagedOutput.ROLE).put("taskId", taskId()).put("runId", runId())
                .put("operationId", operation).put("planDigest", facts.getString("planDigest")).put("artifactPlanDigest", digest)
                .put("buildOutputAttribution", PROVENANCE).put("businessVerified", false)
                .put("originalCompilerWitness", ProjectSources.copy(facts));
    }

    /** Compare the same native publication and exact original bytes; safe to retain only its header. */
    public JSONObject checkedArtifact(JSONObject actual) throws Exception {
        JSONObject expected = source().put("nativePublication", new JSONObject().put("operationId", operation)
                .put("role", ROLE).put("taskId", taskId()).put("runId", runId()).put("planDigest", digest));
        String id = actual.getString("id");
        Object size = actual.opt("size");
        if (!"owner".equals(actual.opt("principalId")) || !"private".equals(actual.opt("spaceId")) ||
                actual.optBoolean("outputSourceRetired") || !MIME.equals(actual.opt("mimeType")) || !name.equals(actual.opt("name")) ||
                !(size instanceof Integer || size instanceof Long) || ((Number)size).longValue() != witness.bytes() ||
                !witness.sha256().equals(actual.opt("sha256")) ||
                !ProjectSources.canonical(expected).equals(ProjectSources.canonical(actual.optJSONObject("source"))) ||
                !AttachmentBook.isOriginalPublicationId(id, operation, ROLE, taskId(), runId(), digest))
            throw new SecurityException("固定文件不属于这次原构建和保存");
        return new JSONObject().put("id", id).put("sha256", witness.sha256()).put("size", witness.bytes())
                .put("status", "completed").put("code", "owner_compiler_file_saved")
                .put("nativeFileOperation", true).put("businessVerified", false);
    }

    /** The pre-verified bounded buffer is the sole publication input. */
    public JSONObject save(AttachmentBook book, byte[] bytes, RunControl control) throws Exception {
        if (bytes.length != witness.bytes() || !witness.sha256().equals(ProjectSources.sha256(bytes)))
            throw new SecurityException("文件字节与原构建退出不同，没有保存");
        JSONObject result = book.importNativeResult(new ByteArrayInputStream(bytes), name, MIME, source(),
                operation, ROLE, taskId(), runId(), digest, control);
        checkedArtifact(result);
        // The import return is not the durable readback: verify the exact catalog and blob.
        JSONObject verified = find(book, control);
        if (verified == null) throw new IllegalStateException("原文件保存尚待核对，不会再次写入");
        return verified;
    }

    public JSONObject find(AttachmentBook book, RunControl control) throws Exception {
        JSONObject result = book.findNativeResult(operation, ROLE, taskId(), runId(), digest, control);
        if (!Boolean.TRUE.equals(result.opt("found"))) return null;
        checkedArtifact(result);
        return result;
    }
}
