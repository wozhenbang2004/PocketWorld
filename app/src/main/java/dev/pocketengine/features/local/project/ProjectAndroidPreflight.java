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
    private static final Pattern ANDROID_PLUGIN = Pattern.compile("com\\.android\\.(?:application|library|dynamic-feature|test)\\b");
    private static final Pattern AGP_COORDINATE = Pattern.compile("com\\.android\\.tools\\.build:gradle:([0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.-]+)?)");
    private static final Pattern AGP_PLUGIN_VERSION = Pattern.compile("[\"']com\\.android\\.(?:application|library|dynamic-feature|test)[\"']\\s*\\)?\\s*version\\s*\\(?\\s*[\"']([0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.-]+)?)[\"']");
    private static final Pattern SDK = Pattern.compile("\\b(compileSdk(?:Version)?|targetSdk(?:Version)?|minSdk(?:Version)?)\\s*(?:=|\\()?\\s*([0-9]{1,3})(?![0-9A-Za-z_.])");
    private static final Pattern BUILD_TOOLS = Pattern.compile("\\bbuildToolsVersion\\s*(?:=|\\()?\\s*[\"']([0-9]+\\.[0-9]+\\.[0-9]+)[\"']");
    private static final Pattern DISTRIBUTION = Pattern.compile("https://(?:services|downloads)\\.gradle\\.org/distributions/gradle-([0-9]+(?:\\.[0-9]+){1,2})-(bin|all)\\.zip");
    private static final Pattern NATIVE_BUILD = Pattern.compile("\\b(?:externalNativeBuild|ndkVersion|ndkPath)\\b");
    private ProjectAndroidPreflight() {}

    public static JSONObject inspect(Map<String,String> files, JSONObject source, JSONArray observedTools) throws Exception {
        TreeSet<String> evidence = new TreeSet<>(), nativeEvidence = new TreeSet<>(), declarations = new TreeSet<>();
        JSONArray sourceFiles = source.getJSONArray("files");
        for (int i = 0; i < sourceFiles.length(); i++) {
            String path = sourceFiles.getJSONObject(i).getString("path");
            if (path.equals("AndroidManifest.xml") || path.endsWith("/AndroidManifest.xml")) evidence.add(path);
            if (path.equals("CMakeLists.txt") || path.endsWith("/CMakeLists.txt") || path.equals("Android.mk") || path.endsWith("/Android.mk") || path.equals("Application.mk") || path.endsWith("/Application.mk")) nativeEvidence.add(path);
        }
        for (var file : files.entrySet()) {
            String path = file.getKey();
            if (!(path.equals("build.gradle") || path.endsWith("/build.gradle") || path.equals("build.gradle.kts") || path.endsWith("/build.gradle.kts") || path.equals("settings.gradle") || path.endsWith("/settings.gradle") || path.equals("settings.gradle.kts") || path.endsWith("/settings.gradle.kts") || path.endsWith(".versions.toml"))) continue;
            String text = file.getValue();
            if (ANDROID_PLUGIN.matcher(text).find() || AGP_COORDINATE.matcher(text).find()) evidence.add(path);
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
                .put("当前只有项目声明与原环境工具观察；尚无完整 Android 工具链安装身份和兼容版本核验，基本环境就绪不代表可构建 APK。")
                .put("需 ARM64/libc 兼容的 JDK、Gradle 执行工具及匹配的 SDK platform/build-tools、AAPT2；AGP 可能下载自己的 AAPT2，不能仅替换 SDK 目录里的同名文件。")
                .put("SDK 许可、实际下载量/展开量/缓存空间和离线依赖完整性未由这次读取确认；不会自动安装、同意条款或切换执行位置。")
                .put("构建会执行项目脚本和插件。通用 assemble 可包含多个模块/变体，不等于只生成 debug APK；它不主动请求 check/test，但项目可自定义任务依赖。同 UID 运行不是安全沙箱。")
                .put("当前 managed 构建成果按原附件流式收集每文件最多 20MiB、总存储 100MiB；旧选择仍按原 2MiB 上限；模块内 build 目录仍可能进入源码预算。没有 APK 签名校验、设备安装或启动成功证明。");
        if (!nativeEvidence.isEmpty()) gaps.put("发现原生构建线索；普通 Java/Kotlin APK 路线不提供 NDK/Clang/CMake 的 ARM64 主机兼容保证，不以假 shim 跳过原生编译。");
        JSONObject result = new JSONObject().put("format", "pocketworld.android-preflight.v1")
                .put("sourceDigest", source.getString("sourceDigest")).put("evidence", first(evidence))
                .put("evidenceCount", evidence.size()).put("literalVersionHints", first(declarations))
                .put("literalVersionHintCount", declarations.size()).put("nativeBuildEvidence", first(nativeEvidence))
                .put("nativeBuildEvidenceCount", nativeEvidence.size()).put("wrapper", wrapper(files))
                .put("toolObservations", toolObservations).put("declarationsOnly", true)
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
    private static JSONObject wrapper(Map<String,String> files) throws Exception {
        String path = "gradle/wrapper/gradle-wrapper.properties", text = files.get(path);
        JSONObject result = new JSONObject().put("sourcePath", path).put("declared", text != null)
                .put("distributionVerified", false).put("wrapperJarVerified", false).put("willExecuteWrapper", false);
        if (text == null) return result;
        Properties properties = new Properties();
        try { properties.load(new StringReader(text)); }
        catch (IllegalArgumentException invalid) { return result.put("state", "invalid_properties"); }
        String url = properties.getProperty("distributionUrl", "");
        Matcher version = DISTRIBUTION.matcher(url);
        result.put("distribution", version.matches() ? "official_url_declaration_only" : "unrecognized_or_dynamic_url_not_exposed");
        if (version.matches()) result.put("declaredVersion", version.group(1)).put("distributionKind", version.group(2));
        result.put("sha256Declared", properties.getProperty("distributionSha256Sum", "").matches("[a-fA-F0-9]{64}"));
        // A project-supplied hash is not provenance, and does not verify the wrapper JAR.
        return result;
    }
}
