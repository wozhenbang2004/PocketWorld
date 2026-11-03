package dev.pocketengine.sdk;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.distribution.SdkPackageCodec;
import dev.pocketengine.features.plugins.sdk.PluginSdk;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.json.JSONObject;

/** Thin desktop adapter. All manifest, argument, package, and debug semantics stay in production Java. */
public final class PluginSdkMain {
    private PluginSdkMain() { }
    public static void main(String[] args) throws Exception {
        RunControl control = new RunControl();
        Thread stop = new Thread(control::cancel, "pocket-sdk-stop"); Runtime.getRuntime().addShutdownHook(stop);
        try {
            JSONObject input = SdkPackageCodec.readJson(System.in, control);
            JSONObject result = execute(input, control);
            byte[] output = result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (output.length > 1048576) throw new IllegalStateException("SDK报告超过1MiB");
            System.out.write(output); System.out.println();
        } catch (Exception failure) {
            System.err.println(new JSONObject().put("status", "error").put("message", failure.getMessage()));
            System.exit(2);
        } finally { try { Runtime.getRuntime().removeShutdownHook(stop); } catch (IllegalStateException shuttingDown) { } }
    }
    private static JSONObject execute(JSONObject input, RunControl control) throws Exception {
        PluginSdk sdk = PluginSdk.offline(); String action = input.getString("action");
        if ("template".equals(action)) return PluginSdk.template(input.getString("kind"), input.getString("id"),
                input.getString("name"), input.getString("version"), input.optString("endpoint", null));
        PluginSdk.Prepared prepared;
        if ("inspect".equals(action)) {
            try (InputStream in = Files.newInputStream(Path.of(input.getString("packagePath")))) { prepared = sdk.inspect(in, control); }
        } else {
            JSONObject source;
            try (InputStream in = Files.newInputStream(Path.of(input.getString("sourcePath")))) { source = SdkPackageCodec.readJson(in, control); }
            try (InputStream in = Files.newInputStream(Path.of(input.getString("manifestPath")))) { prepared = sdk.prepare(in, source, control); }
        }
        if ("diagnose".equals(action) || "review-debug".equals(action) || "debug".equals(action)) {
            JSONObject arguments;
            try (InputStream in = Files.newInputStream(Path.of(input.getString("argumentsPath")))) { arguments = SdkPackageCodec.readJson(in, control); }
            if ("diagnose".equals(action)) return sdk.diagnose(prepared, input.getString("tool"), arguments);
            PluginSdk.DebugReview review = sdk.reviewDebug(prepared, input.getString("tool"), arguments, input.getString("operationId"));
            if ("review-debug".equals(action)) return review.review();
            return sdk.debugReviewed(prepared, review, input.getString("acceptedDigest"), control);
        }
        if (!"validate".equals(action) && !"pack".equals(action) && !"inspect".equals(action)) throw new IllegalArgumentException("未知SDK动作");
        JSONObject result = new JSONObject().put("receipt", prepared.receipt()).put("inspection", prepared.inspection().review());
        if (input.has("packageUrl")) result.put("catalogEntry", prepared.catalogEntry(input.getString("packageUrl"), input.getInt("minAndroidApi")));
        if ("pack".equals(action)) {
            Path target = Path.of(input.getString("outputPath"));
            pack(sdk, prepared, target, control);
            result.put("saved", true).put("outputPath", target.toAbsolutePath().toString());
        }
        return result;
    }

    static void pack(PluginSdk sdk, PluginSdk.Prepared prepared, Path target, RunControl control) throws Exception {
        boolean created = false;
        try {
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true; sdk.write(prepared, out, control); out.flush();
            }
            control.check();
        } catch (Exception failure) {
            if (!created) throw failure;
            // The path may now name another process's file. There is no atomic
            // compare-and-delete here, so retain any output rather than unlink it.
            throw new IOException("Package creation failed; no output was removed. Output path may contain partial or replaced data: "
                    + target.toAbsolutePath() + ". Inspect it before cleanup or retry. Cause: " + failure.getMessage(), failure);
        }
    }
}
