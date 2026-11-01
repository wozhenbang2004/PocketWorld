package dev.pocketengine.features.plugins.sdk;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.plugins.HttpToolClient;
import dev.pocketengine.features.plugins.PluginRevisions;
import dev.pocketengine.features.plugins.PluginRules;
import dev.pocketengine.features.plugins.distribution.PluginPackages;
import dev.pocketengine.features.plugins.distribution.SdkPackageCodec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native-owner authoring API. Not an EngineModule, installer, permission grant, or execution runtime. */
public final class PluginSdk {
    private final PluginPackages packages;
    private final boolean installedStateCompared;

    /** Use the host's existing plugins module on a worker thread to compare the real current installation. */
    public PluginSdk(EngineModule plugins) { this(plugins, true); }
    private PluginSdk(EngineModule plugins, boolean installedStateCompared) {
        packages = new PluginPackages(plugins);
        this.installedStateCompared = installedStateCompared;
    }

    /** No registry or install state is invented: this context can only preview a new declaration. */
    public static PluginSdk offline() {
        return new PluginSdk(new EngineModule() {
            public String id() { return "plugins"; }
            public JSONObject snapshot() throws Exception { return new JSONObject().put("plugins", new JSONArray()); }
            public JSONObject handle(String command, JSONObject input) throws Exception {
                if (!"previewInstall".equals(command)) throw new SecurityException("离线SDK仅校验声明，不能安装或调用插件");
                return PluginRevisions.preview(null, PluginRules.manifest(input.getJSONObject("manifest")));
            }
        }, false);
    }

    public Prepared prepare(InputStream manifest, JSONObject source, RunControl control) throws Exception {
        JSONObject authored = SdkPackageCodec.readJson(manifest, control);
        control.check();
        byte[] bytes = SdkPackageCodec.declaration(authored, source);
        return inspect(new ByteArrayInputStream(bytes), control);
    }

    public Prepared inspect(InputStream input, RunControl control) throws Exception {
        // Retain the exact final file bytes; the real W07 reader performs all portable-field checks.
        byte[] bytes = boundedBytes(input, control);
        PluginPackages.Inspection inspection = packages.inspect(new ByteArrayInputStream(bytes), control);
        return new Prepared(bytes, inspection, installedStateCompared);
    }

    public JSONObject write(Prepared prepared, OutputStream destination, RunControl control) throws Exception {
        if (prepared == null || destination == null) throw new IllegalArgumentException("需要已检查的包和目标流");
        byte[] bytes = prepared.bytes;
        for (int offset = 0; offset < bytes.length; offset += 4096) {
            control.check();
            destination.write(bytes, offset, Math.min(4096, bytes.length - offset));
        }
        control.check();
        return prepared.receipt().put("requiresDestinationClose", true);
    }

    public static final class Prepared {
        private final byte[] bytes;
        private final PluginPackages.Inspection inspection;
        private final String manifestJson;
        private final boolean installedStateCompared;
        private Prepared(byte[] bytes, PluginPackages.Inspection inspection, boolean compared) throws Exception {
            this.bytes = bytes.clone(); this.inspection = inspection; installedStateCompared = compared;
            manifestJson = inspection.review().getJSONObject("preview").getJSONObject("manifest").toString();
        }
        public byte[] bytes() { return bytes.clone(); }
        public InputStream openPackage() { return new ByteArrayInputStream(bytes); }
        public JSONObject manifest() throws Exception { return new JSONObject(manifestJson); }
        public PluginPackages.Inspection inspection() { return inspection; }
        public JSONObject receipt() throws Exception {
            JSONObject manifest = manifest();
            return new JSONObject().put("id", manifest.getString("id")).put("version", manifest.getString("version"))
                    .put("format", PluginPackages.FORMAT).put("packageSha256", inspection.packageDigest)
                    .put("declarationDigest", inspection.declarationDigest).put("byteSize", bytes.length)
                    .put("context", installedStateCompared ? "native_installed_state" : "offline_authoring")
                    .put("installedStateCompared", installedStateCompared).put("installed", false)
                    .put("signature", "not_present").put("containsHostCredentials", false).put("containsHostGrants", false);
        }
        /** W12 producer projection only. No catalog fetching, version ordering, publishing, or installation. */
        public JSONObject catalogEntry(String packageUrl, int minAndroidApi) throws Exception {
            validateCatalogUrl(packageUrl);
            if (minAndroidApi < 1) throw new IllegalArgumentException("minAndroidApi需为正整数");
            JSONObject manifest = manifest(); JSONArray tools = manifest.getJSONArray("tools"), capabilities = new JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i), row = new JSONObject().put("name", tool.getString("name"))
                        .put("kind", tool.getString("kind")).put("effect", tool.getString("effect"));
                if (tool.has("endpoint")) validateCatalogUrl(tool.getString("endpoint"));
                for (String field : new String[]{"endpoint", "protocolVersion"}) if (tool.has(field)) row.put(field, tool.get(field));
                capabilities.put(row);
            }
            JSONObject receipt = receipt();
            return new JSONObject().put("id", manifest.getString("id")).put("version", manifest.getString("version"))
                    .put("packageUrl", packageUrl).put("packageSha256", receipt.getString("packageSha256"))
                    .put("declarationDigest", receipt.getString("declarationDigest")).put("byteSize", bytes.length)
                    .put("compatibility", new JSONObject().put("packageFormat", PluginPackages.FORMAT).put("minAndroidApi", minAndroidApi))
                    .put("capabilities", capabilities);
        }
    }

    private static void validateCatalogUrl(String url) throws Exception {
        PluginRules.validateEndpoint(url); URI uri = new URI(url);
        if (url.length() > 2000 || uri.getPort() == 0 || uri.getPort() > 65535 || url.indexOf('\\') >= 0
                || url.chars().anyMatch(c -> c <= 32 || c == 127)) throw new IllegalArgumentException("W12目录地址无效");
    }

    public JSONObject diagnose(Prepared prepared, String toolName, JSONObject arguments) throws Exception {
        JSONObject tool = tool(prepared, toolName);
        if ("mcp".equals(tool.getString("kind"))) return new JSONObject().put("supported", false).put("valid", false)
                .put("reason", "MCP请走手机原生重新发现、认证和参数诊断；SDK不模拟远端身份或标头授权");
        return PluginRules.diagnoseArguments(arguments, tool.getJSONObject("schema")).put("supported", true);
    }

    /** Build an exact native review. This method performs no request and approves nothing. */
    public DebugReview reviewDebug(Prepared prepared, String toolName, JSONObject arguments, String operationId) throws Exception {
        JSONObject tool = tool(prepared, toolName);
        if (operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("需要稳定operationId，最多128个ASCII字符");
        JSONObject diagnostic = diagnose(prepared, toolName, arguments);
        if (!diagnostic.getBoolean("valid")) throw new IllegalArgumentException("参数未通过实际插件诊断：" + diagnostic);
        String kind = tool.getString("kind");
        if ("http".equals(kind)) {
            URI uri = new URI(tool.getString("endpoint")); String host = uri.getHost();
            if (!("127.0.0.1".equals(host) || "[::1]".equals(host))) throw new SecurityException("SDK调试仅允许字面回环地址；远端调用走手机安装授权流程");
        } else if (!"echo".equals(kind)) throw new SecurityException("SDK不执行此种工具");
        JSONObject review = new JSONObject().put("packageSha256", prepared.inspection.packageDigest)
                .put("tool", toolName).put("kind", kind).put("effect", tool.getString("effect"))
                .put("arguments", arguments).put("operationId", operationId).put("endpoint", tool.optString("endpoint"))
                .put("mayHaveSideEffects", "http".equals(kind)).put("installed", false).put("grantsChanged", false)
                .put("automaticReplay", false).put("untrustedOutput", true);
        return new DebugReview(review);
    }

    public static final class DebugReview {
        public final String digest;
        private final String json;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private DebugReview(JSONObject review) throws Exception {
            byte[] bytes = SdkPackageCodec.jsonBytes(review);
            json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            digest = SdkPackageCodec.digest(review);
        }
        public JSONObject review() throws Exception { return new JSONObject(json).put("reviewDigest", digest); }
    }

    /** Native owner only, after displaying this exact review; never expose as an agent/model tool. */
    public JSONObject debugReviewed(Prepared prepared, DebugReview review, String acceptedDigest, RunControl control) throws Exception {
        if (review == null || !review.digest.equals(acceptedDigest)) throw new SecurityException("请明确批准当前调试摘要");
        JSONObject request = review.review();
        if (!prepared.inspection.packageDigest.equals(request.getString("packageSha256"))) throw new SecurityException("调试包已变化");
        // A native host must additionally reserve its original operation in TaskStore before this call.
        if (!review.consumed.compareAndSet(false, true)) throw new IllegalStateException("此调试请求已消费；请查看原operationId结果，不自动重放");
        JSONObject receipt = new JSONObject().put("operationId", request.getString("operationId"))
                .put("reviewDigest", review.digest).put("replayAllowed", false).put("untrusted", true);
        try { control.check(); } catch (InterruptedException stopped) { return receipt.put("status", "not_dispatched"); }
        if ("echo".equals(request.getString("kind"))) return receipt.put("status", "local_echo").put("output", request.getJSONObject("arguments"));
        try {
            JSONObject output = HttpToolClient.invoke(request.getString("endpoint"), "", request.getString("tool"),
                    request.getJSONObject("arguments"), request.getString("operationId"), control);
            control.check();
            // A server's returned JSON is data, not proof that a business action succeeded.
            return receipt.put("status", "returned").put("output", output);
        } catch (Exception uncertain) {
            return receipt.put("status", "unknown").put("message", "请求结果未确认；取消不会撤销服务端效果，请按原operationId查询，未自动重试");
        }
    }

    public static JSONObject template(String kind, String id, String name, String version, String endpoint) throws Exception {
        if (!"echo".equals(kind) && !"http".equals(kind)) throw new IllegalArgumentException("模板仅支持echo/http；MCP需真实发现");
        JSONObject schema = new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put("text", new JSONObject().put("type", "string").put("maxLength", 4000)))
                .put("required", new JSONArray().put("text")).put("additionalProperties", false);
        JSONObject tool = new JSONObject().put("name", "echo".equals(kind) ? "echo" : "normalize_text")
                .put("kind", kind).put("description", "echo".equals(kind) ? "返回提供的文本参数" : "在明确启动的本地服务中整理文本空白")
                .put("effect", "read").put("schema", schema);
        if ("http".equals(kind)) { if (endpoint == null) throw new IllegalArgumentException("HTTP模板需要明确endpoint"); tool.put("endpoint", endpoint); }
        return PluginRules.manifest(new JSONObject().put("id", id).put("name", name).put("version", version).put("tools", new JSONArray().put(tool)));
    }

    private static JSONObject tool(Prepared prepared, String name) throws Exception {
        if (prepared == null) throw new IllegalArgumentException("请先校验并打包声明");
        JSONArray tools = prepared.manifest().getJSONArray("tools");
        for (int i = 0; i < tools.length(); i++) if (name.equals(tools.getJSONObject(i).getString("name"))) return tools.getJSONObject(i);
        throw new IllegalArgumentException("声明中没有此工具");
    }
    private static byte[] boundedBytes(InputStream input, RunControl control) throws Exception {
        if (input == null || control == null) throw new IllegalArgumentException("需要输入流和取消控制");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096];
        while (true) {
            control.check(); int count = input.read(buffer, 0, Math.min(buffer.length, PluginPackages.MAX_BYTES + 1 - bytes.size()));
            if (count < 0) break;
            if (count == 0) throw new IllegalArgumentException("输入流未提供数据");
            bytes.write(buffer, 0, count);
            if (bytes.size() > PluginPackages.MAX_BYTES) throw new IllegalArgumentException("声明包超过256KiB");
        }
        control.check(); return bytes.toByteArray();
    }
}
