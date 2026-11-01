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
        JSONObject target=new JSONObject().put("regionRef",doc+":r1").put("state","present").put("selector",new JSONObject().put("role","button").put("name","Visible target"));
        emitted.put("targetPresent",BrowserTargetCondition.script(target,key,"original-native-test-scope",doc));target.put("state","absent");emitted.put("targetAbsent",BrowserTargetCondition.script(target,key,"original-native-test-scope",doc));
        String actionKey="__native_test_action",url="https://embed.example/frame";
        emitted.put("formObserve",BrowserFormControls.observationScript(actionKey,id));
        for(String command:new String[]{"type","set_date","focus","press","click"}){JSONObject a=new JSONObject().put("ref","n0");if(command.equals("type"))a.put("text","中文目标");if(command.equals("set_date"))a.put("date","2026-11-01");if(command.equals("press"))a.put("key","Enter");String script=BrowserDomAction.script(command,a,url,id,actionKey,"readback-token");emitted.put(command+"Action",script);if(command.equals("click"))emitted.put("guardedFrameAction",BrowserFrameObserver.guardScript(script,"__native_document_test",token,"frameAction"));}
        emitted.put("textReadback",BrowserTextControls.readbackScript(actionKey,id,url,"readback-token"));
        emitted.put("liveExpression",BrowserPagePrograms.expression("{\"op\":\"map\",\"input\":{\"op\":\"queryAll\",\"selector\":\"td\"},\"body\":{\"op\":\"text\",\"input\":{\"op\":\"item\"}}}"));
        emitted.put("liveInspect",BrowserPagePrograms.inspect()).put("rawScript",BrowserPagePrograms.script("document.body.textContent='actual mutation'; return {observed:document.body.textContent};","actual-token"));
        emitted.put("rawScriptError",BrowserPagePrograms.script("document.body.textContent='effect before throw'; throw new Error('after effect');","actual-token"));
        emitted.put("networkWitness",BrowserNetworkWitness.script("fixtureNetwork","registration"));
        Files.writeString(Path.of(args[0]),emitted.toString());
    }
}
