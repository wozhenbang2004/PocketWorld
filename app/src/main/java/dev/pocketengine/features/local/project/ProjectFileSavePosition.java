package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import org.json.JSONObject;

/** One previously confirmed ordinary owner-editor location. Not a file version,
 * source artifact, cached draft, approval or promise to be the last physical write. */
public record ProjectFileSavePosition(String projectId, String checkoutId, String path, String sha256) {
    private static final String FORMAT = "pocketworld.owner-checkout-file-save-position.v1";
    private static final String VERSIONED = "pocketworld.owner-checkout-file-save-position.v2";
    private static final String NAME = "owner-file-save-position-v1.json";
    private static final int MAX_BYTES = 4096;

    public ProjectFileSavePosition {
        if (!id(projectId) || !id(checkoutId) || !ProjectSources.sourcePath(VersionedWorkspace.path(path)) ||
                !StandardCharsets.UTF_8.newEncoder().canEncode(path) || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("原副本保存定位不完整");
    }

    private static boolean id(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    public JSONObject view() throws Exception {
        return new JSONObject().put("format", FORMAT).put("projectId", projectId).put("checkoutId", checkoutId)
                .put("path", path).put("sha256", sha256);
    }

    private static File target(File root, String checkout) throws Exception {
        File directory = OwnerDraftCopyShelf.location(root, checkout, false);
        File file = new File(directory, NAME); var location = file.toPath();
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                !file.getAbsoluteFile().equals(file.getCanonicalFile()) || Files.isSymbolicLink(location))
            throw new SecurityException("原副本保存定位不是普通私有记录");
        if (Files.exists(location, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS) || file.length() > MAX_BYTES ||
                 !(Files.getAttribute(location, "unix:nlink", LinkOption.NOFOLLOW_LINKS) instanceof Number links) ||
                 links.longValue() != 1))
            throw new SecurityException("原副本保存定位不是有界独立记录");
        return file;
    }

    /** Caller holds the original checkout writer lock and has already confirmed a real save. */
    public void recordForOwner(File root) throws Exception {
        recordForOwner(root, null);
    }

    /** A Git editor return must still match the exact source revision containing this save. */
    public void recordForOwner(File root, String sourceDigest) throws Exception {
        JSONObject value = view();
        if (sourceDigest != null) {
            if (!sourceDigest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("原副本源码摘要无效");
            value.put("format", VERSIONED).put("sourceDigest", sourceDigest);
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new SecurityException("原副本保存定位超过范围");
        ProjectFileDraftStore.atomic(target(root, checkoutId), bytes);
    }

    public static ProjectFileSavePosition readForOwner(File root, String project, String checkout) throws Exception {
        return readForOwner(root, project, checkout, null);
    }

    public static ProjectFileSavePosition readForOwner(File root, String project, String checkout, String sourceDigest) throws Exception {
        File file = target(root, checkout);
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        JSONObject value = ProjectPrivateRecord.read(file, MAX_BYTES);
        target(root, checkout);
        if (value.length() != (sourceDigest == null ? 5 : 6) ||
                !(sourceDigest == null ? FORMAT : VERSIONED).equals(value.opt("format")) ||
                !project.equals(value.opt("projectId")) || !checkout.equals(value.opt("checkoutId")) ||
                !(value.opt("path") instanceof String path) || !(value.opt("sha256") instanceof String sha))
            throw new SecurityException("原副本保存定位不能核对");
        if (sourceDigest != null && !sourceDigest.equals(value.opt("sourceDigest"))) return null;
        return new ProjectFileSavePosition(project, checkout, path, sha);
    }
}
