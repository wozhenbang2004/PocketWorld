package dev.pocketengine.features.local.project;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only owner guidance. Project hints, recorded tools and permission to run are separate facts. */
public final class ProjectCheckoutTooling {
    private static final Set<String> GRADLE = Set.of("build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts");
    private static final Set<String> TOOLS = Set.of("node","npm","pnpm","sh","python3","java","javac","gradle","git");
    private ProjectCheckoutTooling() {}
    static boolean js(String p) { return p.endsWith(".js") || p.endsWith(".mjs") || p.endsWith(".cjs"); }
    static boolean gradle(String p) { return GRADLE.contains(p); }
    static boolean python(String p) { return p.endsWith(".py") || p.equals("requirements.txt") || p.equals("pyproject.toml"); }

    // Shared with ProjectBuildPlan: a lock file cannot override a conflicting declaration.
    static String manager(Set<String> paths, String declaration) {
        String name = declaration.split("@",2)[0];
        boolean npm = paths.contains("package-lock.json") || paths.contains("npm-shrinkwrap.json"), pnpm = paths.contains("pnpm-lock.yaml");
        if (npm && pnpm || !name.isEmpty() && !Set.of("npm","pnpm").contains(name) || name.equals("npm") && pnpm || name.equals("pnpm") && npm) return "unsupported_or_conflicting";
        return !name.isEmpty() ? name : pnpm ? "pnpm" : "npm";
    }
    private static String manifestManager(Set<String> paths, String manifest) {
        if (manifest.length() > 65536) return "not_checked";
        // Stream the top-level declaration. No dependency spec, script or source text leaves the checkout.
        try (JsonReader in = new JsonReader(new StringReader(manifest))) {
            String declaration = ""; boolean seen = false;
            in.beginObject();
            while (in.hasNext()) {
                if (!"packageManager".equals(in.nextName())) { in.skipValue(); continue; }
                if (seen || in.peek() != JsonToken.STRING) return "not_checked";
                seen = true; declaration = in.nextString();
            }
            in.endObject();
            return in.peek() == JsonToken.END_DOCUMENT && !declaration.startsWith("@") ? manager(paths, declaration) : "not_checked";
        } catch (Exception invalid) { return "not_checked"; }
    }
    private static JSONArray observed(JSONObject runtime) throws Exception {
        Set<String> names = new LinkedHashSet<>(); JSONArray tools = runtime.optJSONArray("tools");
        for (int i = 0; tools != null && i < tools.length(); i++) {
            JSONObject item = tools.optJSONObject(i); if (item == null) continue;
            String name = item.optString("name");
            if (TOOLS.contains(name)
                && (Boolean.TRUE.equals(item.opt("probeVerified")) || Boolean.TRUE.equals(item.opt("hashVerified")))) names.add(name);
        }
        return new JSONArray(names);
    }
    static JSONObject describe(JSONObject snapshot, JSONObject source, JSONObject runtime) throws Exception {
        JSONObject checkout = snapshot.getJSONObject("checkout"); String id = source.getString("checkoutId"), project = source.getString("projectId");
        if (!id.equals(checkout.getString("id")) || !project.equals(checkout.getString("projectId")) || !"managed_process_v1".equals(runtime.getString("runtimeId")))
            throw new IllegalArgumentException("原副本或本机环境身份不一致");
        Set<String> paths = new LinkedHashSet<>(); String manifest = null; JSONArray files = snapshot.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject row = files.getJSONObject(i); if (row.optBoolean("binary")) continue;
            String path = row.getString("path"); paths.add(path); if ("package.json".equals(path)) manifest = row.getString("content");
        }
        JSONArray kinds = new JSONArray();
        if (manifest != null || paths.stream().anyMatch(ProjectCheckoutTooling::js)) kinds.put("node");
        boolean gradle = paths.stream().anyMatch(ProjectCheckoutTooling::gradle); if (gradle) kinds.put("gradle");
        if (paths.stream().anyMatch(ProjectCheckoutTooling::python)) kinds.put("python");
        if (!gradle && paths.stream().anyMatch(p -> p.endsWith(".java"))) kinds.put("java");
        if (paths.contains("index.html")) kinds.put("static");
        return new JSONObject().put("projectId",project).put("checkoutId",id).put("sourceDigest",source.getString("sourceDigest"))
            .put("runtimeId",runtime.getString("runtimeId")).put("kinds",kinds).put("manager",manifest == null ? JSONObject.NULL : manifestManager(paths,manifest))
            .put("observedTools",observed(runtime)).put("runtimeReady",Boolean.TRUE.equals(runtime.opt("canExecute"))).put("readOnly",true);
    }
}
