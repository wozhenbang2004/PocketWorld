package dev.pocketengine.features.plugins;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Data projection only. Digests are references, never a discovery, installation or launch grant. */
final class McpStdioData {
    private McpStdioData() {}
    static JSONObject previewTool(JSONObject remote,String configurationDigest,String version,String serverDigest)throws Exception {
        JSONObject row=new JSONObject().put("name",remote.optString("name")).put("description",remote.optString("description")).put("untrusted",true).put("definition",McpData.copy(remote));
        try{row.put("declaration",declaration(remote,configurationDigest,version,serverDigest)).put("supported",true);}
        catch(Exception unsupported){row.put("supported",false).put("unsupportedReason",unsupported.getMessage());}return row;
    }
    static JSONObject declaration(JSONObject remote,String configurationDigest,String version,String serverDigest)throws Exception {
        if(remote.has("description")&&!(remote.get("description") instanceof String))throw new IllegalArgumentException("工具描述需为文字");
        if(remote.optJSONObject("execution")!=null&&"required".equals(remote.getJSONObject("execution").optString("taskSupport")))throw new IllegalArgumentException("当前不支持必须通过MCP tasks执行的工具");
        JSONObject tool=new JSONObject().put("name",remote.getString("name")).put("kind","mcp").put("transport","stdio").put("effect","write")
                .put("description",remote.optString("description",remote.getString("name"))).put("configurationDigest",configurationDigest).put("protocolVersion",version).put("serverDigest",serverDigest).put("descriptorDigest",dev.pocketengine.features.plugins.mcp.McpJson.digest(remote)).put("schema",McpData.copy(remote.getJSONObject("inputSchema")));
        for(String field:new String[]{"annotations","outputSchema"})if(remote.has(field))tool.put(field,McpData.copy(remote.getJSONObject(field)));
        return PluginRules.manifest(new JSONObject().put("id","probe").put("name","probe").put("version","probe").put("tools",new JSONArray().put(tool))).getJSONArray("tools").getJSONObject(0);
    }
    static JSONObject manifest(String id,String name,String configurationDigest,String version,String serverDigest,JSONArray definitions,JSONArray selection)throws Exception {
        if(selection.length()<1||selection.length()>McpData.MAX_IMPORTED_TOOLS)throw new IllegalArgumentException("请选择1至"+McpData.MAX_IMPORTED_TOOLS+"个此次发现中支持的本机MCP工具");JSONArray tools=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<selection.length();i++){String selected=selection.getString(i);if(!seen.add(selected))throw new IllegalArgumentException("工具选择重复");JSONObject remote=McpData.find(definitions,selected);if(remote==null)throw new IllegalArgumentException("工具不在原本机程序发现结果内");tools.put(declaration(remote,configurationDigest,version,serverDigest));}
        return PluginRules.manifest(new JSONObject().put("id",id).put("name",name).put("version","mcp-"+McpData.digest(tools).substring(0,24)).put("tools",tools));
    }
}
