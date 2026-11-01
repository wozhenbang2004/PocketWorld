package dev.pocketengine.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Emits the actual current production scripts for the bounded Node DOM fixture, not a copied implementation. */
public final class EmitBrowserFrameRead {
    public static void main(String[] args)throws Exception {
        String id="a0000000-0000-0000-0000-000000000001",doc="b0000000-0000-0000-0000-000000000001",key="__native_test_read",token="a".repeat(32);
        JSONObject text=new JSONObject().put("mode","text"),outline=new JSONObject().put("mode","outline");
        String child=BrowserSemanticRead.script(text,key,"original-native-test-scope",id,doc,true);
        JSONObject emitted=new JSONObject().put("mainText",BrowserSemanticRead.script(text,key,"original-native-test-scope",id,doc,false))
                .put("childText",child).put("childTextB",BrowserSemanticRead.script(text,key,"original-native-test-scope",id,"b0000000-0000-0000-0000-000000000002",true)).put("childOutline",BrowserSemanticRead.script(outline,key,"original-native-test-scope",id,doc,true))
                .put("guardedChild",BrowserFrameObserver.guardScript(child,"__native_document_test",token,"semanticRead")).put("visualProbe",BrowserVisualCapture.privacyScript("__native_test_visual"));
        Files.writeString(Path.of(args[0]),emitted.toString());
    }
}
