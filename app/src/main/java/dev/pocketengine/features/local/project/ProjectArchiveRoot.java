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

    private static JSONObject find(JSONArray files, JSONArray directories, JSONArray omitted) throws Exception {
        String directory = null, descriptor = null;
        for (int i = 0; i < files.length(); i++) {
            String path = files.getJSONObject(i).getString("path"); int slash = path.indexOf('/');
            if (slash < 1) return null;
            String top = path.substring(0, slash);
            if (directory == null) directory = top;
            if (!directory.equals(top)) return null;
            if (descriptor == null && DESCRIPTORS.contains(path.substring(slash + 1))) descriptor = path;
        }
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
