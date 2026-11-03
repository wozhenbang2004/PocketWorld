package dev.pocketengine.features.plugins;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Source descriptors are retained data, never install or execution authority. The native
 * discovery/install/browse journey is exercised separately with a real protocol fixture. */
public class McpSourceManifestTest {
    private static final String ENDPOINT="https://reference.example/mcp";
    private interface Action { void run()throws Exception; }
    private void rejected(Action action)throws Exception {try{action.run();fail("Expected exact source rejection");}catch(IllegalArgumentException|SecurityException expected){}}
    private JSONObject server()throws Exception{return new JSONObject().put("name","reference-service").put("version","1.2.3");}
    private JSONObject capabilities(String name)throws Exception{return new JSONObject().put(name,new JSONObject());}
    private JSONObject source()throws Exception{return McpData.sourceDescriptor(ENDPOINT,McpData.VERSION,McpData.digest(server()),server(),capabilities("resources"));}
    private JSONObject reference(String capability)throws Exception{return McpData.manifest("reference","参考来源",ENDPOINT,McpData.VERSION,McpData.digest(server()),new JSONArray(),new JSONArray(),server(),capabilities(capability));}
    private JSONObject definition()throws Exception{return new JSONObject().put("name","lookup").put("description","Untrusted service declaration").put("inputSchema",new JSONObject().put("type","object"));}
    private JSONObject mixed()throws Exception{return McpData.manifest("reference","参考来源",ENDPOINT,McpData.VERSION,McpData.digest(server()),new JSONArray().put(definition()),new JSONArray().put("lookup"),server(),capabilities("resources").put("tools",new JSONObject()));}
    private Set<String> keys(JSONObject object){Set<String> out=new HashSet<>();object.keys().forEachRemaining(out::add);return out;}

    @Test public void resourceAndPromptOnlyManifestsHaveNoExecutableToolsOrGrant()throws Exception {
        for(String capability:new String[]{"resources","prompts"}){
            JSONObject manifest=reference(capability),source=manifest.getJSONObject("mcpSource");
            assertEquals(0,manifest.getJSONArray("tools").length());
            assertEquals(Set.of("endpoint","protocolVersion","serverDigest"),keys(source));
            assertEquals(ENDPOINT,source.getString("endpoint"));assertEquals(McpData.digest(server()),source.getString("serverDigest"));
            assertEquals(0,PluginRules.grants(manifest,new JSONArray()).length());
            rejected(()->PluginRules.grants(manifest,new JSONArray().put("tool:lookup")));
            JSONObject enabled=new JSONObject().put("manifest",manifest).put("enabled",true).put("grants",new JSONArray());
            rejected(()->PluginRules.permitted(enabled,"lookup",new JSONObject()));
            assertEquals(McpData.digest(manifest),McpData.digest(PluginRules.manifest(manifest)));
        }
    }

    @Test public void legacyBuilderAndOrdinaryOrToolsOnlyEmptyManifestsStillReject()throws Exception {
        rejected(()->McpData.manifest("reference","参考来源",ENDPOINT,McpData.VERSION,McpData.digest(server()),new JSONArray(),new JSONArray()));
        rejected(()->PluginRules.manifest(new JSONObject().put("id","ordinary").put("name","ordinary").put("version","1").put("tools",new JSONArray())));
        for(JSONObject caps:new JSONObject[]{new JSONObject(),capabilities("tools"),capabilities("logging")}){
            assertNull(McpData.sourceDescriptor(ENDPOINT,McpData.VERSION,McpData.digest(server()),server(),caps));
            rejected(()->McpData.manifest("reference","参考来源",ENDPOINT,McpData.VERSION,McpData.digest(server()),new JSONArray(),new JSONArray(),server(),caps));
        }
    }

    @Test public void onlyExactDiscoveryIdentityAndObjectCapabilitiesCanCreateSources()throws Exception {
        JSONObject different=server().put("version","1.2.4");
        rejected(()->McpData.sourceDescriptor(ENDPOINT,McpData.VERSION,McpData.digest(server()),different,capabilities("resources")));
        rejected(()->McpData.sourceDescriptor(ENDPOINT,McpData.VERSION,McpData.digest(server()),null,capabilities("resources")));
        rejected(()->McpData.sourceDescriptor(ENDPOINT,McpData.VERSION,McpData.digest(server()),server(),null));
        for(String name:new String[]{"tools","resources","prompts"})for(Object malformed:new Object[]{true,"supported",new JSONArray(),JSONObject.NULL}){
            JSONObject caps=capabilities("resources").put(name,malformed);
            rejected(()->McpData.sourceDescriptor(ENDPOINT,McpData.VERSION,McpData.digest(server()),server(),caps));
        }
        rejected(()->McpData.manifest("reference","参考来源",ENDPOINT,McpData.VERSION,McpData.digest(server()),new JSONArray().put(definition()),new JSONArray().put("lookup"),server(),capabilities("resources")));
    }

    @Test public void sourceDescriptorRejectsSecretsUnknownFieldsAndIdentityNormalization()throws Exception {
        for(String field:new String[]{"token","headers","transport","grants","capabilities","installationId"}){
            JSONObject changed=source().put(field,"unreviewed");rejected(()->PluginRules.mcpSource(changed));
        }
        for(String field:new String[]{"endpoint","protocolVersion","serverDigest"}){
            JSONObject changed=source().put(field," "+source().getString(field));rejected(()->PluginRules.mcpSource(changed));
            JSONObject missing=source();missing.remove(field);rejected(()->PluginRules.mcpSource(missing));
        }
        for(String endpoint:new String[]{"http://remote.example/mcp","https://user:secret@reference.example/mcp","https://reference.example/mcp?token=secret","https://reference.example/mcp#key"}){
            JSONObject changed=source().put("endpoint",endpoint);rejected(()->PluginRules.mcpSource(changed));
        }
        rejected(()->PluginRules.mcpSource(source().put("protocolVersion","auto")));
        rejected(()->PluginRules.mcpSource(source().put("serverDigest","A".repeat(64))));
    }

    @Test public void sourceAndSelectedToolsMustBindToTheSameExactHttpServer()throws Exception {
        JSONObject manifest=mixed();assertEquals(1,manifest.getJSONArray("tools").length());assertTrue(manifest.has("mcpSource"));
        assertEquals("tool:lookup",PluginRules.grants(manifest,new JSONArray().put("tool:lookup")).getString(0));
        for(String field:new String[]{"endpoint","protocolVersion","serverDigest","kind","transport"}){
            JSONObject changed=mixed(),tool=changed.getJSONArray("tools").getJSONObject(0);
            tool.put(field,field.equals("endpoint")?"https://another.example/mcp":field.equals("protocolVersion")?McpData.LEGACY:field.equals("serverDigest")?"c".repeat(64):field.equals("kind")?"echo":"stdio");
            rejected(()->PluginRules.manifest(changed));
        }
        JSONObject sourceChanged=mixed();sourceChanged.getJSONObject("mcpSource").put("serverDigest","c".repeat(64));
        rejected(()->PluginRules.manifest(sourceChanged));
        JSONObject legacy=McpData.manifest("legacy","legacy",ENDPOINT,McpData.LEGACY,McpData.digest(server()),new JSONArray().put(definition()),new JSONArray().put("lookup"));
        assertFalse(legacy.has("mcpSource"));assertEquals(1,PluginRules.manifest(legacy).getJSONArray("tools").length());
    }

    @Test public void emptySelectionVersionIncludesSourceAddressProtocolAndServerIdentity()throws Exception {
        String original=reference("resources").getString("version");
        JSONObject updated=server().put("version","1.2.4");
        JSONObject[] changed={
            McpData.manifest("reference","参考来源","https://another.example/mcp",McpData.VERSION,McpData.digest(server()),new JSONArray(),new JSONArray(),server(),capabilities("resources")),
            McpData.manifest("reference","参考来源",ENDPOINT,McpData.LEGACY,McpData.digest(server()),new JSONArray(),new JSONArray(),server(),capabilities("resources")),
            McpData.manifest("reference","参考来源",ENDPOINT,McpData.VERSION,McpData.digest(updated),new JSONArray(),new JSONArray(),updated,capabilities("resources"))
        };
        for(JSONObject manifest:changed)assertNotEquals(original,manifest.getString("version"));
        assertEquals(original,reference("resources").getString("version"));
    }
}
