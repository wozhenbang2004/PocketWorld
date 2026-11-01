package dev.pocketengine.features.plugins;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginRulesTest {
    private JSONObject manifest()throws Exception{return PluginRules.manifest(new JSONObject("{\"id\":\"demo.tool\",\"name\":\"Demo\",\"version\":\"1\",\"tools\":[{\"name\":\"echo\",\"kind\":\"echo\",\"schema\":{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\",\"maxLength\":8}},\"additionalProperties\":false}}]}"));}
    @Test public void invokingNeedsEnablementAndAnExactToolGrant()throws Exception {
        JSONObject manifest=manifest(),plugin=new JSONObject().put("manifest",manifest).put("enabled",false).put("grants",new JSONArray());JSONObject args=new JSONObject().put("text","hello");
        try{PluginRules.permitted(plugin,"echo",args);fail();}catch(IllegalStateException disabled){}
        plugin.put("enabled",true);try{PluginRules.permitted(plugin,"echo",args);fail();}catch(SecurityException denied){}
        plugin.put("grants",PluginRules.grants(manifest,new JSONArray().put("tool:echo")));
        assertEquals("echo",PluginRules.permitted(plugin,"echo",args).getString("kind"));
        try{PluginRules.grants(manifest,new JSONArray().put("phone:*"));fail();}catch(IllegalArgumentException denied){}
    }
    @Test public void toolSchemaRejectsMissingWrongAndUnknownArguments()throws Exception {
        JSONObject plugin=new JSONObject().put("manifest",manifest()).put("enabled",true).put("grants",new JSONArray().put("tool:echo"));
        for(JSONObject args:new JSONObject[]{new JSONObject(),new JSONObject().put("text",42),new JSONObject().put("text","123456789"),new JSONObject().put("text","ok").put("extra",1)})try{PluginRules.permitted(plugin,"echo",args);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void executableKindsAndUnencryptedRemoteEndpointsAreRejected()throws Exception {
        PluginRules.validateEndpoint("http://127.0.0.1:1234/tool");PluginRules.validateEndpoint("https://tools.example/invoke");
        for(String endpoint:new String[]{"http://remote.example/tool","file:///data/app","https://user:secret@tools.example/invoke","https://tools.example/invoke?key=secret"})try{PluginRules.validateEndpoint(endpoint);fail(endpoint);}catch(Exception expected){}
        JSONObject m=manifest();m.getJSONArray("tools").getJSONObject(0).put("kind","javascript");try{PluginRules.manifest(m);fail();}catch(IllegalArgumentException expected){}
        JSONObject unsupported=manifest();unsupported.getJSONArray("tools").getJSONObject(0).getJSONObject("schema").put("pattern",".*");try{PluginRules.manifest(unsupported);fail();}catch(IllegalArgumentException expected){}
    }
}
