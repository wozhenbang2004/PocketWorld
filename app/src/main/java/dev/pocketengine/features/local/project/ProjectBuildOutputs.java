package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.runtime.RuntimeWorkspace;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.json.*;

/** Metadata only, below the generated roots that are already outside the source revision.
 * A name observed after a build is not proof the build created it, nor permission to collect it. */
public final class ProjectBuildOutputs {
    private static final List<String> ROOTS = List.of("build", "dist");
    private static final int MAX_PAGE = 24, PAGE_BYTES = 10000;
    private ProjectBuildOutputs() {}

    public static void requireDirectory(String directory) {
        if (directory.equals(".")) return;
        VersionedWorkspace.path(directory);
        if (!ROOTS.contains(directory.split("/", 2)[0]) || !visiblePath(directory))
            throw new SecurityException("只可在本次构建的 build、dist 输出目录内查看；不读取源码、隐藏目录或私有配置。");
    }

    private static boolean visiblePath(String path) {
        String prefix = "";
        for (String part : path.split("/")) {
            prefix = prefix.isEmpty() ? part : prefix + "/" + part;
            if (part.startsWith(".") || ProjectPackageArchive.excluded(prefix, false, true) != null) return false;
        }
        return true;
    }

    private record Entry(String path, String kind, long size, String modified, String key) {
        JSONObject visible() throws JSONException {
            return new JSONObject().put("path", path).put("kind", kind).put("bytes", kind.equals("file") ? size : JSONObject.NULL);
        }
    }
    private record Page(TreeMap<String, Entry> entries, String listingId, int omitted) {}

    private static void observe(Path child, String path, boolean rootsOnly, TreeMap<String, Entry> entries) throws Exception {
        try { VersionedWorkspace.path(path); } catch (IllegalArgumentException invalid) { return; }
        if (!visiblePath(path)) return;
        BasicFileAttributes a = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isDirectory() && (rootsOnly || !a.isRegularFile() || a.size() < 0 || a.size() > ProjectManagedOutput.MAX_STREAM_BYTES)) return;
        if (a.isRegularFile() && (!(Files.getAttribute(child, "unix:nlink", LinkOption.NOFOLLOW_LINKS) instanceof Number n) || n.longValue() != 1)) return;
        entries.put(path, new Entry(path, a.isDirectory() ? "directory" : "file", a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toString(), String.valueOf(a.fileKey())));
    }

    private static Page scan(RuntimeWorkspace workspace, String directory, String build, RunControl run) throws Exception {
        run.check(); Path base = workspace.resolve(directory, false).toPath();
        BasicFileAttributes before = Files.readAttributes(base, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isDirectory()) throw new IllegalArgumentException("所选原输出目录已不存在或不是目录。");
        var entries = new TreeMap<String, Entry>(); int examined = 0;
        if (directory.equals(".")) {
            // Do not enumerate or describe the rest of the checkout.
            for (String name : ROOTS) {
                run.check();
                try { observe(base.resolve(name), name, true, entries); examined++; }
                catch (NoSuchFileException absent) { /* A conventional root need not exist. */ }
            }
        } else {
            try (DirectoryStream<Path> listed = Files.newDirectoryStream(base)) {
                for (Path child : listed) {
                    run.check();
                    if (++examined > ProjectDirectoryQuery.MAX_ENTRIES) throw new IllegalArgumentException("单层目录超过 2000 项；请指定更小的原输出子目录，未返回混合或不完整分页。");
                    observe(child, directory + "/" + child.getFileName(), false, entries);
                }
            }
        }
        BasicFileAttributes after = Files.readAttributes(workspace.resolve(directory, false).toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isDirectory() || !Objects.equals(before.fileKey(), after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IllegalStateException("原输出目录在读取时已变化，请从这个目录重新查看。");
        JSONArray facts = new JSONArray();
        for (Entry row : entries.values()) facts.put(new JSONArray().put(row.path).put(row.kind).put(row.size).put(row.modified).put(row.key));
        String id = ProjectSources.digest(new JSONObject().put("format", "pocketworld.build-output-directory.v1").put("build", build)
                .put("directory", directory).put("fileKey", String.valueOf(after.fileKey())).put("modified", after.lastModifiedTime().toString()).put("examined", examined).put("entries", facts));
        return new Page(entries, id, examined - entries.size());
    }

    public static JSONObject list(RuntimeWorkspace workspace, String directory, JSONObject cursor, JSONObject proof, RunControl original) throws Exception {
        requireDirectory(directory); String build = proof.getString("receiptIdentity"); Page page;
        try {
            page = scan(workspace, directory, build, original);
            if (!page.listingId.equals(scan(workspace, directory, build, original).listingId))
                throw new IllegalStateException("原输出目录的内容或大小已变化，请重新查看。");
        } catch (IOException | DirectoryIteratorException failure) {
            throw new IllegalStateException("原输出目录当前不可读取；没有读取或收集文件正文。");
        }
        String after = "";
        if (cursor != null) {
            after = cursor.getString("afterPath");
            if (cursor.length() != 2 || !page.listingId.equals(cursor.getString("listingId")) || !page.entries.containsKey(after))
                throw new IllegalArgumentException("原构建、目录或分页位置已变化，请不带 cursor 重新查看。");
        }
        JSONArray rows = new JSONArray(); String last = after; int bytes = 0;
        for (Entry entry : page.entries.tailMap(after, false).values()) {
            JSONObject row = entry.visible(); int cost = row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (rows.length() == MAX_PAGE || bytes + cost > PAGE_BYTES) break;
            rows.put(row); bytes += cost; last = entry.path;
        }
        boolean more = page.entries.higherKey(last) != null; original.check();
        return new JSONObject().put("code", "project_build_outputs_observed").put("status", "observed").put("ok", true)
                .put("buildExecutionId", proof.getString("jobId")).put("projectId", workspace.projectId()).put("checkoutId", workspace.checkoutId())
                .put("directory", directory).put("entries", rows).put("omittedCount", page.omitted).put("hasMore", more)
                .put("nextCursor", more ? new JSONObject().put("listingId", page.listingId).put("afterPath", last) : JSONObject.NULL)
                .put("fileBodiesRead", false).put("planCreated", false).put("artifactCollected", false).put("buildOutputAttribution", "observed_after_build_only")
                .put("message", directory.equals(".") ? "仅列出当前实际存在的 build/dist 输出目录；空列表不代表其他位置没有输出。"
                        : "只列原目录内可见子目录及可收集大小的普通文件。名称与大小不是文件摘要，也不能证明由本次构建产生。");
    }
}
