package dev.pocketengine.sdk.examples;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.plugins.sdk.PluginDocuments;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;

/** Compiled phone-side example. The native owner supplies SAF streams and the live plugins module. */
public final class PhonePluginAuthoringExample {
    private PhonePluginAuthoringExample() { }

    /** Run on the existing native capability worker after the owner has selected the destination. */
    public static JSONObject packToOwnerDocument(EngineModule existingPlugins, InputStream authoredManifest,
            JSONObject source, OutputStream ownerSelectedDocument, RunControl control) throws Exception {
        return PluginDocuments.packToOwnerDocument(existingPlugins, authoredManifest, source, ownerSelectedDocument, control);
    }
}
