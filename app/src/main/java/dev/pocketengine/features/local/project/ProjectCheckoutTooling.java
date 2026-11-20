package dev.pocketengine.features.local.project;

import android.util.JsonReader;
import android.util.JsonToken;
import dev.pocketengine.features.EngineModule;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only owner guidance. Project hints, recorded tools and permission to run are separate facts. */
public final class ProjectCheckoutTooling {
    private static final Set<String> GRADLE = Set.of("build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts");
    static final String MAVEN_MANIFEST = "pom.xml";
    private static final Set<String> TOOLS = Set.of("node","npm","pnpm","sh","python3","java","javac","gradle","git");
    static final Set<String> JVM_TOOLS=Set.of("java","javac","gradle");
    static final String NO_MAVEN="此副本根目录有 pom.xml。此 App 尚无原生 Maven 构建通道，不会用裸 javac 或 java 代替 Maven。仍可编辑这个私人副本。";
    private ProjectCheckoutTooling() {}
    static boolean js(String p) { return p.endsWith(".js") || p.endsWith(".mjs") || p.endsWith(".cjs"); }
    static boolean gradle(String p) { return GRADLE.contains(p); }
    /** Presence in the exact byte manifest, not XML evaluation or dependency resolution. */
    static boolean mavenDeclared(JSONArray manifest) throws Exception {
        for (int i = 0; i < manifest.length(); i++)
            if (MAVEN_MANIFEST.equals(manifest.getJSONObject(i).getString("path"))) return true;
        return false;
    }
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
    record ToolObservation(JSONArray tools,String id,long createdAt){}
    /** Only recorded backend facts; reading them does not run a probe. */
    static ToolObservation toolObservation(EngineModule local,String runtimeId,JSONObject runtime)throws Exception{
        JSONArray observed=new JSONArray();String probeId="";long newest=-1;
        if(!"termux".equals(runtimeId)){
            JSONArray facts=runtime.optJSONArray("tools");
            for(int i=0;facts!=null&&i<facts.length();i++){
                JSONObject fact=facts.getJSONObject(i);
                if(Boolean.TRUE.equals(fact.opt("hashVerified"))||Boolean.TRUE.equals(fact.opt("probeVerified")))observed.put(fact.getString("name"));
            }
        }else{
            JSONArray jobs=local.handle("listJobs",new JSONObject()).optJSONArray("termuxJobs");
            if(jobs!=null)for(int i=0;i<jobs.length();i++){
                JSONObject summary=jobs.getJSONObject(i);
                if(!"tool_probe".equals(summary.optString("kind"))||!"completed".equals(summary.optString("status"))||summary.optLong("createdAt")<newest)continue;
                JSONObject receipt=local.handle("getJob",new JSONObject().put("jobId",summary.getString("id")).put("termux",true));
                Object exit=receipt.opt("exitCode");if(!Boolean.TRUE.equals(receipt.opt("receiptReceived"))||!(exit instanceof Integer||exit instanceof Long)||((Number)exit).longValue()!=0||receipt.optBoolean("outputTruncated"))continue;
                observed=new JSONArray();for(String line:receipt.optString("stdout").split("\\R")){
                    int equals=line.indexOf('=');if(equals<1)continue;String name=line.substring(0,equals),path=line.substring(equals+1);
                    if(Set.of("node","npm","pnpm","python","python3","java","javac","git","gradle").contains(name)&&path.equals("/data/data/com.termux/files/usr/bin/"+name))observed.put(name);
                }
                newest=summary.optLong("createdAt");probeId=receipt.getString("id");
            }
        }
        return new ToolObservation(observed,probeId,newest);
    }
    static String jvmToolBlocker(String tool,String runtime,Set<String> tools){
        if(!JVM_TOOLS.contains(tool))return "";
        boolean compilerMissing=!tools.contains(tool),jvmMissing="gradle".equals(tool)&&!tools.contains("java");
        if(!compilerMissing&&!jvmMissing)return "";
        String missing=jvmMissing?(compilerMissing?"java、gradle":"java"):tool;
        String place="termux".equals(runtime)?"所选 Termux":"所选手机托管环境";
        return place+"尚未核对到 "+missing+" 工具；这一步没有运行，也未切换环境。";
    }
    /** Stops an earlier pending or compatibility plan before any fresh JVM command approval. */
    static String hostJvmToolBlocker(EngineModule local,JSONObject plan,JSONObject exact,JSONObject runtime)throws Exception{
        String command=exact.getString("command"),tool=exact.getJSONObject("payload").optString("executable");
        if(!Set.of("termux.run","project.runtime.run").contains(command)||!JVM_TOOLS.contains(tool)||"command".equals(exact.opt("intent")))return "";
        if(!"gradle".equals(tool)&&mavenDeclared(plan.getJSONArray("sourceManifest")))return NO_MAVEN;
        String runtimeId=plan.optString("runtimeId","termux");Set<String> known;
        try{known=toolNames(toolObservation(local,runtimeId,runtime).tools());}
        catch(Exception unreadable){return "所选运行位置的 Java 工具记录暂不可核对；没有开始或切换环境。";}
        return jvmToolBlocker(tool,runtimeId,known);
    }
    static Set<String> toolNames(JSONArray observed)throws Exception{
        Set<String> known=new java.util.HashSet<>();
        for(int i=0;i<observed.length();i++)known.add(observed.getString(i));
        return known;
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
        boolean maven = mavenDeclared(source.getJSONArray("files")); if (maven) kinds.put("maven");
        if (paths.stream().anyMatch(ProjectCheckoutTooling::python)) kinds.put("python");
        if (!gradle && !maven && paths.stream().anyMatch(p -> p.endsWith(".java"))) kinds.put("java");
        if (paths.contains("index.html")) kinds.put("static");
        return new JSONObject().put("projectId",project).put("checkoutId",id).put("sourceDigest",source.getString("sourceDigest"))
            .put("runtimeId",runtime.getString("runtimeId")).put("kinds",kinds).put("manager",manifest == null ? JSONObject.NULL : manifestManager(paths,manifest))
            .put("observedTools",observed(runtime)).put("runtimeReady",Boolean.TRUE.equals(runtime.opt("canExecute"))).put("readOnly",true);
    }
}
