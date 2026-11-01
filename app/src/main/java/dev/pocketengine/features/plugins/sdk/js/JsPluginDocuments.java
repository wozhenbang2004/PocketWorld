package dev.pocketengine.features.plugins.sdk.js;

import dev.pocketengine.core.RunControl;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;

/** Native phone authoring save; source is only treated as bytes, never initialized or described. */
public final class JsPluginDocuments {
    private JsPluginDocuments() { }
    public static JSONObject packToOwnerDocument(InputStream exactSource, String id, String name, String version,
            OutputStream ownerSelectedDestination, RunControl control) throws Exception {
        JSONObject receipt;
        try (OutputStream destination = ownerSelectedDestination) {
            JsPluginPackage artifact = JsPluginPackage.author(exactSource, id, name, version, control);
            receipt = artifact.write(destination, control); destination.flush();
        }
        receipt.remove("requiresDestinationClose");
        return receipt.put("saved", true);
    }
}
