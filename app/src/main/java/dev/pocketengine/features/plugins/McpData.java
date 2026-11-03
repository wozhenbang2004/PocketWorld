package dev.pocketengine.features.plugins;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, untrusted MCP declarations. This is not a general JSON Schema engine. */
final class McpData {
    static final String VERSION="2026-07-28", LEGACY="2025-11-25";
    static boolean supportedVersion(String version){return VERSION.equals(version)||LEGACY.equals(version);}
    static final int MAX_TOOLS=96;
    static final int MAX_IMPORTED_TOOLS=12;
    private McpData() { }

    static JSONObject support()throws Exception {
        return new JSONObject().put("protocolVersion",VERSION).put("legacyProtocolVersion",LEGACY).put("transport","streamable_http").put("automaticDowngrade",false)
                .put("transports",new JSONArray().put("streamable_http").put("stdio"))
                .put("stdio",new JSONObject().put("sourceImplemented",true).put("nativeOwnerDiscovery",true).put("separateOwnerInstallationReview",true).put("originalTaskInvocation",true).put("protocolVersion",McpStdioClient.VERSION)
                        .put("executables",new JSONArray().put("node").put("python3")).put("executionWorkspace","per_operation_visible_copy").put("runtimeVerified",false).put("osSandbox",false).put("environmentInputs",false).put("credentials",false)
                        .put("serverLifetime","one_original_tool_operation").put("relativeWritesPublishedBack",false).put("relativeStateAcrossCalls",false).put("packageInstallation",true).put("packageManagers",new JSONArray().put("installed_npm_11_exact_version").put("installed_pnpm_10_exact_version")).put("separateDependencyPreparationConsent",true).put("lifecycleScripts",false).put("automaticManagerDownload",false).put("crossTaskServerPool",false).put("automaticRestart",false).put("resources",false).put("prompts",false).put("oauth",false).put("mcpTasks",false))
                .put("schemaDialect","https://json-schema.org/draft/2020-12/schema")
                .put("validationKeywords",new JSONArray(new String[]{"type","properties","required","additionalProperties","items","maxItems","maxLength","enum","minimum","maximum"}))
                .put("annotationKeywords",new JSONArray(new String[]{"title","description","$comment","default","examples","deprecated","readOnly","writeOnly"})).put("headerKeyword","x-mcp-header")
                .put("structuredContentSupport","object_only_not_output_schema_validated").put("maxImportedTools",MAX_IMPORTED_TOOLS).put("maxDiscoveredTools",MAX_TOOLS).put("maxSchemaCharacters",16000)
                .put("contentTypes",new JSONArray().put("text").put("structuredContent"))
                .put("resources",new JSONObject().put("sourceImplemented",true).put("readOnly",true).put("skillImportRequiresReview",true)
                        .put("sourceOnlyInstallation",true).put("maxPages",8).put("maxResources",96).put("maxSkillCharacters",64000).put("relativeResourceResolver",false))
                .put("prompts",new JSONObject().put("sourceImplemented",true).put("nativeOwnerOnly",true).put("installedOnly",true).put("readOnly",true)
                        .put("sourceOnlyInstallation",true).put("maxPages",8).put("maxPrompts",96).put("maxMessages",64).put("maxTextCharacters",64000).put("automaticConversationInjection",false).put("contentSupport","text_only_with_explicit_omissions"))
                .put("tasks",new JSONObject().put("protocolVersion",VERSION).put("extension","io.modelcontextprotocol/tasks")
                        .put("sourceImplemented",true).put("hostMountRequired",false).put("hostMounted",true).put("originalTaskStore",true).put("nativeOwnerPollCancel",true).put("coldRecovery",true).put("automaticReplay",false))
                .put("subscriptions",new JSONObject().put("protocolVersion",VERSION).put("sourceImplemented",true).put("method","subscriptions/listen").put("nativeOwnerOnly",true).put("boundedObservation",true).put("maxEvents",128).put("maxResponseBytes",262144).put("automaticReconnect",false).put("updatesInvalidateSourceRevision",true))
                .put("oauth",new JSONObject().put("sourceImplemented",true).put("hostMountRequired",true).put("clientType","public_pkce_s256")
                        .put("registration","pre_registered_or_existing_cimd").put("automaticRefresh",false))
                .put("unsupported",new JSONArray().put("stdio environment/credential input").put("automatic package manager installation; unreviewed dependencies/lifecycle scripts").put("stdio resources/prompts/OAuth/tasks").put("legacy HTTP+SSE")
                        .put("SSE reconnect/resumption").put("GET subscriptions").put("legacy experimental tasks").put("sampling").put("elicitation")
                        .put("roots").put("legacy resource subscriptions").put("prompt completion").put("multimodal prompt content").put("tasks/update input").put("binary content"));
    }

    static JSONObject previewTool(JSONObject remote,String endpoint,String version,String serverDigest)throws Exception {
        JSONObject row=new JSONObject().put("name",remote.optString("name")).put("description",remote.optString("description"))
                .put("untrusted",true).put("definition",copy(remote));
        try {
            row.put("declaration",declaration(remote,endpoint,version,serverDigest)).put("supported",true);
        }catch(Exception unsupported){row.put("supported",false).put("unsupportedReason",unsupported.getMessage());}
        return row;
    }

    static JSONObject declaration(JSONObject remote,String endpoint,String version,String serverDigest)throws Exception {
        if(remote.has("description")&&!(remote.get("description") instanceof String))throw new IllegalArgumentException("工具描述需为文字");
        if(remote.has("execution")&&(!(remote.opt("execution") instanceof JSONObject)||remote.getJSONObject("execution").has("taskSupport")&&!java.util.Set.of("forbidden","optional","required").contains(remote.getJSONObject("execution").optString("taskSupport"))))throw new IllegalArgumentException("MCP工具execution.taskSupport需为明确支持的协议值");
        if(remote.optJSONObject("execution")!=null&&"required".equals(remote.getJSONObject("execution").optString("taskSupport"))&&!VERSION.equals(version))
            throw new IllegalArgumentException("旧协议的实验性MCP Tasks未协商，不能导入必须异步执行的工具");
        String remoteName=remote.getString("name");
        if(!PluginRules.mcpToolName(remoteName))throw new IllegalArgumentException("当前MCP工具名支持1至128个ASCII字母数字或_.-，原名称不会被重写");
        JSONObject schema=remote.getJSONObject("inputSchema");
        JSONObject tool=new JSONObject().put("name",remote.getString("name")).put("kind","mcp").put("effect","write")
                .put("description",remote.optString("description",remote.getString("name"))).put("endpoint",endpoint)
                .put("schema",copy(schema)).put("protocolVersion",version).put("serverDigest",serverDigest).put("descriptorDigest",digest(remote));
        if(remote.has("annotations"))tool.put("annotations",copy(remote.getJSONObject("annotations")));
        if(remote.has("outputSchema"))tool.put("outputSchema",copy(remote.getJSONObject("outputSchema")));
        if(remote.has("execution"))tool.put("execution",copy(remote.getJSONObject("execution")));
        JSONObject manifest=new JSONObject().put("id","probe").put("name","probe").put("version","probe").put("tools",new JSONArray().put(tool));
        return PluginRules.manifest(manifest).getJSONArray("tools").getJSONObject(0);
    }

    static JSONObject manifest(String id,String name,String endpoint,String version,String serverDigest,JSONArray definitions,JSONArray selection)throws Exception {
        return manifest(id,name,endpoint,version,serverDigest,definitions,selection,null);
    }

    /** Only the live discovery owner supplies these server facts; an empty tool selection must
     * retain a usable resource/prompt source, never create an empty arbitrary plugin. */
    static JSONObject manifest(String id,String name,String endpoint,String version,String serverDigest,JSONArray definitions,JSONArray selection,JSONObject serverInfo,JSONObject serverCapabilities)throws Exception {
        JSONObject source=sourceDescriptor(endpoint,version,serverDigest,serverInfo,serverCapabilities);
        if(selection.length()>0&&serverCapabilities.optJSONObject("tools")==null)throw new IllegalArgumentException("此服务未声明tools能力，不能导入工具");
        return manifest(id,name,endpoint,version,serverDigest,definitions,selection,source);
    }
    static JSONObject sourceDescriptor(String endpoint,String version,String serverDigest,JSONObject serverInfo,JSONObject serverCapabilities)throws Exception {
        if(serverInfo==null||serverCapabilities==null||!digest(serverInfo).equals(serverDigest))throw new SecurityException("MCP来源缺少此次真实发现的准确服务身份");
        for(String capability:new String[]{"tools","resources","prompts"})if(serverCapabilities.has(capability)&&serverCapabilities.optJSONObject(capability)==null)throw new IllegalArgumentException("MCP能力声明格式无效");
        if(serverCapabilities.optJSONObject("resources")==null&&serverCapabilities.optJSONObject("prompts")==null)return null;
        return PluginRules.mcpSource(new JSONObject().put("endpoint",endpoint).put("protocolVersion",version).put("serverDigest",serverDigest));
    }
    private static JSONObject manifest(String id,String name,String endpoint,String version,String serverDigest,JSONArray definitions,JSONArray selection,JSONObject source)throws Exception {
        if(selection.length()>MAX_IMPORTED_TOOLS||selection.length()==0&&source==null)throw new IllegalArgumentException("请选择1至"+MAX_IMPORTED_TOOLS+"个支持的MCP工具，或明确安装此次已发现的资源/提示来源");
        JSONArray tools=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<selection.length();i++){
            String selected=selection.getString(i);if(!seen.add(selected))throw new IllegalArgumentException("工具选择重复");
            JSONObject remote=find(definitions,selected);if(remote==null)throw new IllegalArgumentException("工具不在此次发现结果内");
            tools.put(declaration(remote,endpoint,version,serverDigest));
        }
        JSONObject manifest=new JSONObject().put("id",id).put("name",name).put("tools",tools);
        if(source!=null)manifest.put("mcpSource",copy(source));
        manifest.put("version","mcp-"+digest(source==null?tools:new JSONObject().put("tools",tools).put("mcpSource",source)).substring(0,24));
        return PluginRules.manifest(manifest);
    }

    static JSONObject find(JSONArray definitions,String name)throws Exception {
        for(int i=0;i<definitions.length();i++){JSONObject tool=definitions.getJSONObject(i);if(name.equals(tool.optString("name")))return tool;}
        return null;
    }

    static JSONObject result(JSONObject source,String token)throws Exception {return result(source,token,true);}
    /** Full supported returned data, not a model view. The transport has already bounded the
     * matched RPC body. Unsupported content remains explicitly omitted, never fetched/decoded. */
    static JSONObject resultForCapture(JSONObject source,String token)throws Exception {return result(source,token,false);}
    private static JSONObject result(JSONObject source,String token,boolean viewLimits)throws Exception {
        if(source.has("isError")&&!(source.get("isError") instanceof Boolean))throw new IllegalArgumentException("MCP isError格式无效");
        JSONArray content=source.getJSONArray("content");if(viewLimits&&content.length()>64)throw new IllegalArgumentException("MCP结果块过多");
        JSONArray text=new JSONArray(),omitted=new JSONArray();int characters=0;
        for(int i=0;i<content.length();i++){
            JSONObject block=content.getJSONObject(i);String type=block.getString("type");
            if(type.equals("text")){
                String value=block.getString("text");characters+=value.length();if(viewLimits&&characters>32768)throw new IllegalArgumentException("MCP文字结果超过32Ki字符");
                text.put(new JSONObject().put("type","text").put("text",redact(value,token)));
            }else omitted.put(new JSONObject().put("index",i).put("type",redact(type,token)).put("reason","当前仅接收文本；未读取或执行二进制、资源或链接内容"));
        }
        JSONObject result=new JSONObject().put("isError",source.optBoolean("isError",false)).put("content",text)
                .put("omittedContent",omitted).put("untrusted",true).put("instructionsAreData",true);
        if(source.has("structuredContent")){
            JSONObject structured=source.getJSONObject("structuredContent");
            if(viewLimits&&structured.toString().length()>32768)throw new IllegalArgumentException("MCP结构化结果超过32Ki字符");
            result.put("structuredContent",scrub(structured,token,0)).put("outputSchemaValidated",false);
        }
        return result;
    }

    static Object scrub(Object value,String token,int depth)throws Exception {
        if(depth>32)throw new IllegalArgumentException("MCP回复嵌套过深");
        if(value instanceof JSONObject){JSONObject out=new JSONObject(),object=(JSONObject)value;java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){
            String key=keys.next();if(key.equalsIgnoreCase("authorization")||key.equalsIgnoreCase("token")||key.equalsIgnoreCase("apiKey"))continue;
            out.put(redact(key,token),scrub(object.get(key),token,depth+1));}return out;}
        if(value instanceof JSONArray){JSONArray out=new JSONArray(),array=(JSONArray)value;for(int i=0;i<array.length();i++)out.put(scrub(array.get(i),token,depth+1));return out;}
        return value instanceof String?redact((String)value,token):value;
    }
    static boolean containsSecret(Object value,String token,int depth)throws Exception {
        if(token.isEmpty())return false;
        if(depth>32)throw new IllegalArgumentException("MCP定义嵌套过深");
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){
            String key=keys.next();if(key.contains(token)||containsSecret(object.get(key),token,depth+1))return true;
        }return false;}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)if(containsSecret(array.get(i),token,depth+1))return true;return false;}
        return value instanceof String&&((String)value).contains(token)||value instanceof Number&&value.toString().contains(token);
    }
    static String redact(String value,String token){return token.isEmpty()?value:value.replace(token,"[凭据已隐藏]");}
    static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    static String digest(Object value)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b));return result.toString();
    }
    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");for(String key:keys)out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');return out.append('}').toString();}
        if(value instanceof JSONArray){StringBuilder out=new StringBuilder("[");JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)out.append(canonical(array.get(i))).append(',');return out.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
}
