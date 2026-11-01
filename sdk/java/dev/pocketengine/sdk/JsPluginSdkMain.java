package dev.pocketengine.sdk;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.json.JSONObject;

/** Static-only executable_js CLI adapter; it has no dependency on W07 or any script engine. */
public final class JsPluginSdkMain {
    private JsPluginSdkMain() { }
    public static void main(String[] args) throws Exception {
        RunControl control = new RunControl(); Thread stop = new Thread(control::cancel, "pocket-js-sdk-stop");
        Runtime.getRuntime().addShutdownHook(stop);
        try {
            if (args.length < 2) throw new IllegalArgumentException("需要validate/pack/inspect及路径");
            String action = args[0]; JsPluginPackage artifact;
            if ("inspect".equals(action)) {
                if (args.length != 2) throw new IllegalArgumentException("inspect仅接受包路径");
                try (InputStream in = Files.newInputStream(Path.of(args[1]))) { artifact = JsPluginPackage.read(in, control); }
            } else if ("validate".equals(action) || "pack".equals(action)) {
                if (args.length != ("pack".equals(action) ? 6 : 5)) throw new IllegalArgumentException("需要源路径/id/name/version及可选pack目标");
                try (InputStream in = Files.newInputStream(Path.of(args[1]))) { artifact = JsPluginPackage.author(in, args[2], args[3], args[4], control); }
            } else throw new IllegalArgumentException("JS SDK只做静态validate/pack/inspect，不执行脚本");
            JSONObject receipt = artifact.receipt();
            if ("pack".equals(action)) {
                Path target = Path.of(args[5]); boolean created = false;
                try {
                    try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        created = true; artifact.write(out, control); out.flush();
                    }
                } catch (Exception failure) { if (created) Files.deleteIfExists(target); throw failure; }
                receipt.put("saved", true).put("outputPath", target.toAbsolutePath().toString());
            }
            System.out.println(receipt);
        } catch (Exception failure) {
            System.err.println(new JSONObject().put("status", "error").put("staticOnly", true).put("message", failure.getMessage()));
            System.exit(2);
        } finally { try { Runtime.getRuntime().removeShutdownHook(stop); } catch (IllegalStateException shuttingDown) { } }
    }
}
