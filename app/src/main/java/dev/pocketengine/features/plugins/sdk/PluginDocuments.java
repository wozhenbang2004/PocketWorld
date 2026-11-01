package dev.pocketengine.features.plugins.sdk;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;

/** Phone-side save adapter over owner-selected document streams; no file or URI authority is inferred. */
public final class PluginDocuments {
    private PluginDocuments() { }
    public static JSONObject packToOwnerDocument(EngineModule existingPlugins, InputStream authoredManifest,
            JSONObject source, OutputStream ownerSelectedDocument, RunControl control) throws Exception {
        PluginSdk sdk = new PluginSdk(existingPlugins);
        JSONObject receipt;
        try (OutputStream destination = ownerSelectedDocument) {
            PluginSdk.Prepared prepared = sdk.prepare(authoredManifest, source, control);
            receipt = sdk.write(prepared, destination, control);
            destination.flush();
        }
        receipt.remove("requiresDestinationClose");
        return receipt.put("saved", true);
    }
}
