package dev.pocketengine.ui.web;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PocketWebBundleTest {
    private JSONObject receipt() throws Exception {
        JSONArray files = new JSONArray();
        for (String file : new String[]{"index.html", "assets/main.js", "fonts/main.woff2", "licenses/main.txt"}) {
            files.put(new JSONObject().put("path", file).put("bytes", 1).put("sha256", "a".repeat(64)));
        }
        return new JSONObject().put("kind", "pocketworld-library-web").put("files", files);
    }
    private PocketWebBundle load(JSONObject receipt) throws Exception {
        return PocketWebBundle.read(new ByteArrayInputStream(receipt.toString().getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void onlyListedResourcesOnExactOriginAreServed() throws Exception {
        PocketWebBundle bundle = load(receipt());
        assertTrue(bundle.allows("https://pocketengine.local/assets/pocket-ui/index.html"));
        assertTrue(bundle.allows("https://pocketengine.local:443/assets/pocket-ui/assets/main.js"));
        for (String address : new String[]{
                "https://pocketengine.local/assets/pocket-ui/extra.js",
                "https://pocketengine.local/assets/pocket-ui/build-identity.json",
                "https://pocketengine.local:444/assets/pocket-ui/index.html",
                "https://user@pocketengine.local/assets/pocket-ui/index.html",
                "http://pocketengine.local/assets/pocket-ui/index.html",
                "https://other.local/assets/pocket-ui/index.html",
                "https://pocketengine.local/assets/pocket-ui/assets/../index.html",
                "https://pocketengine.local/assets/pocket-ui/%69ndex.html",
                "https://pocketengine.local/assets/pocket-ui//index.html",
                "https://pocketengine.local/assets/pocket-ui/assets%2fmain.js"}) assertFalse(address, bundle.allows(address));
    }
    @Test public void invalidOrDuplicateReceiptsCannotEnableTheHost() throws Exception {
        JSONObject receipt = receipt(); JSONArray files = receipt.getJSONArray("files");
        files.put(files.getJSONObject(0));
        assertThrows(IllegalArgumentException.class, () -> load(receipt));
        for (String path : new String[]{"../index.html", "assets/%2e/file", "assets\\file", "build-identity.json"}) {
            JSONObject changed = receipt(); changed.getJSONArray("files").getJSONObject(0).put("path", path);
            assertThrows(IllegalArgumentException.class, () -> load(changed));
        }
    }
    @Test public void oversizedReceiptIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> PocketWebBundle.read(new ByteArrayInputStream(new byte[1024 * 1024 + 1])));
    }
}
