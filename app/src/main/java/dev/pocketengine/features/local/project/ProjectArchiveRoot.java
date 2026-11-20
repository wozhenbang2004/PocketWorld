package dev.pocketengine.features.local.project;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-only path guidance, never a build plan or a request to move files. */
public final class ProjectArchiveRoot {
    private static final Set<String> DESCRIPTORS = Set.of("package.json", "pom.xml",
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
    private ProjectArchiveRoot() {}

    public static JSONObject staged(JSONObject manifest) throws Exception {
        if (!"source_project".equals(manifest.opt("mode"))) return null;
        return find(manifest.getJSONArray("files"), manifest.getJSONArray("directories"), manifest.getJSONArray("omitted"));
    }

    static JSONObject checkout(JSONObject metadata, JSONArray files) throws Exception {
        JSONObject origin = metadata.optJSONObject("sourceImport");
        if (origin == null || !"external_zip".equals(origin.opt("sourceKind")) ||
            !Boolean.FALSE.equals(origin.opt("executionAuthorityRestored"))) return null;
        // This verifies the current source paths, not the original ZIP layout or a permission to reuse its URI.
        return find(files, new JSONArray(), new JSONArray());
    }

    /** Check only two conventional files in the already established root.
     * Input is the verified staging manifest or the existing checkout read.
     * No paths are traversed and no file contents are projected. */
    public static JSONObject firstWebFile(JSONArray files, String root) throws Exception {
        String prefix = root.isEmpty() ? "" : root + "/";
        JSONObject selected = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject row = files.getJSONObject(i);
            String path = row.getString("path");
            boolean html = path.equals(prefix + "index.html");
            if (!html && !path.equals(prefix + "package.json")) continue;
            boolean text = row.has("kind") ? "text".equals(row.opt("kind")) :
                !row.optBoolean("binary") && row.opt("content") instanceof String;
            if (!text || row.getLong("bytes") < 0 || row.getLong("bytes") > 262144 ||
                !row.getString("sha256").matches("[a-f0-9]{64}")) continue;
            JSONObject pin = new JSONObject().put("root", root).put("path", path).put("sha256", row.getString("sha256"));
            if (html) return pin;
            selected = pin;
        }
        return selected;
    }

    private static JSONObject find(JSONArray files, JSONArray directories, JSONArray omitted) throws Exception {
        String directory = null, descriptor = null, htmlEntry = null;
        for (int i = 0; i < files.length(); i++) {
            String path = files.getJSONObject(i).getString("path"); int slash = path.indexOf('/');
            if (slash < 1) return null;
            String top = path.substring(0, slash);
            if (directory == null) directory = top;
            if (!directory.equals(top)) return null;
            String member = path.substring(slash + 1);
            if (descriptor == null && DESCRIPTORS.contains(member)) descriptor = path;
            if ("index.html".equals(member)) htmlEntry = path;
        }
        if (descriptor == null) descriptor = htmlEntry;
        if (descriptor == null) return null;
        // For a staged ZIP, empty directories and skipped siblings also count.
        // Do not promise the existing "strip single root" action on a mixed archive.
        String prefix = directory + "/";
        for (int i = 0; i < directories.length(); i++)
            if (!directory.equals(directories.getString(i)) && !directories.getString(i).startsWith(prefix)) return null;
        for (int i = 0; i < omitted.length(); i++)
            if (!omitted.getJSONObject(i).getString("path").startsWith(prefix)) return null;
        return new JSONObject().put("directory", directory).put("descriptor", descriptor);
    }
}
