package dev.pocketengine.features.local.project;

import java.io.StringReader;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only hints from the same source snapshot. Not a Gradle evaluator, SDK resolver,
 * tool installer, license acceptance, or authority to execute/sign/install an APK. */
public final class ProjectAndroidPreflight {
    private static final int MAX_EVIDENCE = 20;
    private static final Set<String> GRADLE_FILES = Set.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
    private static final Pattern ANDROID_PLUGIN = Pattern.compile("com\\.android\\.(?:application|library|dynamic-feature|test)\\b");
    private static final Pattern AGP_COORDINATE = Pattern.compile("com\\.android\\.tools\\.build:gradle:([0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.-]+)?)");
    private static final Pattern AGP_PLUGIN_VERSION = Pattern.compile("[\"']com\\.android\\.(?:application|library|dynamic-feature|test)[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"']([0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.-]+)?)[\"']");
    private static final Pattern SDK = Pattern.compile("\\b(compileSdk(?:Version)?|targetSdk(?:Version)?|minSdk(?:Version)?)\\s*(?:=|\\()?\\s*([0-9]{1,3})(?![0-9A-Za-z_.])");
    private static final Pattern BUILD_TOOLS = Pattern.compile("\\bbuildToolsVersion\\s*(?:=|\\()?\\s*[\"']([0-9]+\\.[0-9]+\\.[0-9]+)[\"']");
    private static final Pattern DISTRIBUTION = Pattern.compile("https://(?:services|downloads)\\.gradle\\.org/distributions/gradle-([0-9]+(?:\\.[0-9]+){1,2})-(bin|all)\\.zip");
    private static final Pattern NATIVE_BUILD = Pattern.compile("\\b(?:externalNativeBuild|ndkVersion|ndkPath)\\b");
    // Official Gradle 8.9 release-checksums. These identify a local JAR and a *declared*
    // archive pin separately. They never grant trust to a mirror or prove a ZIP is installed.
    private static final String WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties", WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar";
    private static final String JAR_89 = "498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17";
    private static final String BIN_89 = "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab";
    private static final String ALL_89 = "258e722ec21e955201e31447b0aed14201765a3bfbae296a46cf60b70e66db70";
    private ProjectAndroidPreflight() {}

    private static boolean manifest(String path) { return path.equals("AndroidManifest.xml") || path.endsWith("/AndroidManifest.xml"); }
    private static boolean gradleFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return GRADLE_FILES.contains(name) || name.endsWith(".versions.toml");
    }
    private static boolean androidPlugin(String text) { return ANDROID_PLUGIN.matcher(text).find() || AGP_COORDINATE.matcher(text).find(); }

    /** Same byte manifest used by the model, without evaluating Gradle or exporting any source text. */
    static JSONObject ownerWrapper(JSONObject snapshot, JSONObject source) throws Exception {
        JSONArray items = snapshot.getJSONArray("files"); boolean android = false; String properties = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject file = items.getJSONObject(i); String path = file.getString("path");
            if (manifest(path) || !android && gradleFile(path) && !file.optBoolean("binary") && androidPlugin(file.optString("content"))) android = true;
            if (path.equals(WRAPPER_PROPERTIES) && !file.optBoolean("binary")) properties = file.getString("content");
        }
        return android ? wrapper(properties, source) : null;
    }

    public static JSONObject inspect(Map<String,String> files, JSONObject source, JSONArray observedTools) throws Exception {
        TreeSet<String> evidence = new TreeSet<>(), nativeEvidence = new TreeSet<>(), declarations = new TreeSet<>();
        JSONArray sourceFiles = source.getJSONArray("files");
        for (int i = 0; i < sourceFiles.length(); i++) {
            String path = sourceFiles.getJSONObject(i).getString("path");
            if (manifest(path)) evidence.add(path);
            if (path.equals("CMakeLists.txt") || path.endsWith("/CMakeLists.txt") || path.equals("Android.mk") || path.endsWith("/Android.mk") || path.equals("Application.mk") || path.endsWith("/Application.mk")) nativeEvidence.add(path);
        }
        for (var file : files.entrySet()) {
            String path = file.getKey();
            if (!gradleFile(path)) continue;
            String text = file.getValue();
            if (androidPlugin(text)) evidence.add(path);
            if (NATIVE_BUILD.matcher(text).find()) nativeEvidence.add(path);
            // Literal hints only. No source snippets, credential values, host paths or arbitrary URLs.
            literals(AGP_COORDINATE, text, "agp:", declarations);
            literals(AGP_PLUGIN_VERSION, text, "agp:", declarations);
            literals(BUILD_TOOLS, text, "buildTools:", declarations);
            Matcher sdk = SDK.matcher(text);
            while (sdk.find()) if (declarations.size() < 40) declarations.add(sdk.group(1) + ":" + sdk.group(2));
        }
        if (evidence.isEmpty()) return null; // Absence of these hints does not prove a non-Android project.

        TreeSet<String> present = new TreeSet<>();
        for (int i = 0; i < observedTools.length(); i++) present.add(observedTools.getString(i));
        JSONArray toolObservations = new JSONArray();
        for (String name : new String[]{"java", "javac", "gradle"}) toolObservations.put(new JSONObject()
                .put("name", name).put("state", present.contains(name) ? "observed_in_selected_runtime" : "not_observed")
                .put("compatibleVersionVerified", false));

        JSONArray gaps = new JSONArray()
                .put("项目版本声明、Wrapper JAR 字节与原环境工具观察各自独立；尚无完整 Android 工具链安装身份和兼容版本核验，基本环境就绪不代表可构建 APK。")
                .put("需 ARM64/libc 兼容的 JDK、Gradle 执行工具及匹配的 SDK platform/build-tools、AAPT2；AGP 可能下载自己的 AAPT2，不能仅替换 SDK 目录里的同名文件。")
                .put("SDK 许可、实际下载量/展开量/缓存空间和离线依赖完整性未由这次读取确认；不会自动安装、同意条款或切换执行位置。")
                .put("构建会执行项目脚本和插件。通用 assemble 可包含多个模块/变体，不等于只生成 debug APK；它不主动请求 check/test，但项目可自定义任务依赖。同 UID 运行不是安全沙箱。")
                .put("当前 managed 构建成果按原附件流式收集每文件最多 20MiB、总存储 100MiB；旧选择仍按原 2MiB 上限；模块内 build 目录仍可能进入源码预算。没有 APK 签名校验、设备安装或启动成功证明。");
        if (!nativeEvidence.isEmpty()) gaps.put("发现原生构建线索；普通 Java/Kotlin APK 路线不提供 NDK/Clang/CMake 的 ARM64 主机兼容保证，不以假 shim 跳过原生编译。");
        JSONObject result = new JSONObject().put("format", "pocketworld.android-preflight.v1")
                .put("sourceDigest", source.getString("sourceDigest")).put("evidence", first(evidence))
                .put("evidenceCount", evidence.size()).put("literalVersionHints", first(declarations))
                .put("literalVersionHintCount", declarations.size()).put("nativeBuildEvidence", first(nativeEvidence))
                .put("nativeBuildEvidenceCount", nativeEvidence.size()).put("wrapper", wrapper(files.get(WRAPPER_PROPERTIES), source))
                .put("toolObservations", toolObservations).put("declarationsOnly", true).put("declarationsOnlyScope", "build_script_and_version_hints")
                .put("buildGraphResolved", false).put("apkCapabilityVerified", false)
                .put("buildScriptsEvaluated", false).put("toolExecutionPerformed", false)
                .put("gaps", gaps).put("signing", new JSONObject()
                        .put("ownerKeyCustodyAvailable", false).put("hostSigningImplemented", false)
                        .put("warning", "密钥库、私钥和密码不能放进项目/Git/团队资料、模型参数或日志。不要复用网上下载的 debug.pk8；未签名/未验签 APK 不当成可安装成果。"))
                .put("interpretation", "文字匹配仅是线索，注释/别名/动态配置会影响它；不作为已安装、已解析、构建成功或授权的证据。");
        return result;
    }

    private static void literals(Pattern pattern, String text, String prefix, Set<String> values) {
        Matcher match = pattern.matcher(text);
        while (match.find()) if (values.size() < 40) values.add(prefix + match.group(1));
    }
    private static JSONArray first(Set<String> values) {
        JSONArray result = new JSONArray();
        for (String value : values) { if (result.length() == MAX_EVIDENCE) break; result.put(value); }
        return result;
    }
    private static JSONObject wrapper(String text, JSONObject source) throws Exception {
        JSONArray files = source.getJSONArray("files"); String jarSha = null; boolean hasProperties = false;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i); String path = file.getString("path");
            if (WRAPPER_JAR.equals(path)) jarSha = file.getString("sha256"); // Actual bytes from ProjectSources.describe, not project-authored metadata.
            if (WRAPPER_PROPERTIES.equals(path)) hasProperties = true;
        }
        boolean jarMatches = JAR_89.equals(jarSha);
        JSONObject result = new JSONObject().put("sourcePath", WRAPPER_PROPERTIES).put("declared", hasProperties)
                .put("sourceDigest", source.getString("sourceDigest")).put("referenceVersion", "8.9")
                .put("wrapperJarState", jarSha == null ? "not_in_snapshot" : jarMatches ? "matches_published_reference" : "not_matched_by_reference")
                .put("wrapperJarVerified", jarMatches).put("distributionPinState", "not_declared")
                .put("distributionVerified", false).put("downloadPerformed", false).put("willExecuteWrapper", false);
        if (text == null) return result.put("state", hasProperties ? "unreadable_properties" : "no_properties");
        if (text.length() > 65536) return result.put("state", "properties_too_large");
        Properties properties = new Properties();
        try { properties.load(new StringReader(text)); }
        catch (IllegalArgumentException invalid) { return result.put("state", "invalid_properties"); }
        Matcher version = DISTRIBUTION.matcher(properties.getProperty("distributionUrl", ""));
        boolean official = version.matches();
        result.put("distribution", official ? "official_url_declaration_only" : "unrecognized_or_dynamic_url_not_exposed");
        if (official) result.put("declaredVersion", version.group(1)).put("distributionKind", version.group(2));
        String pin = properties.getProperty("distributionSha256Sum", "");
        boolean wellFormed = pin.matches("[a-fA-F0-9]{64}");
        String matchedKind = BIN_89.equalsIgnoreCase(pin) ? "bin" : ALL_89.equalsIgnoreCase(pin) ? "all" : "";
        result.put("sha256Declared", wellFormed);
        if (!matchedKind.isEmpty()) result.put("pinReferenceVersion", "8.9").put("pinReferenceKind", matchedKind);
        boolean conflict = wellFormed && official && ("8.9".equals(version.group(1)) ? !version.group(2).equals(matchedKind) : !matchedKind.isEmpty());
        return result.put("distributionPinState", conflict ? "conflicts_with_official_declaration" : !matchedKind.isEmpty() ? "matches_published_reference" :
                pin.isEmpty() ? "not_declared" : !wellFormed ? "invalid_format" : "not_matched_by_reference");
    }
}
