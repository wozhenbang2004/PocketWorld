package dev.pocketengine.features.plugins;

import dev.pocketengine.features.plugins.mcp.McpJson;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded protocol data only. Remote roles/content never become conversation or system authority. */
final class McpPromptData {
    static final int MAX_PROMPTS=96,MAX_ARGUMENTS=32,MAX_MESSAGES=64,MAX_TEXT_CHARS=64000,MAX_ARGUMENT_BYTES=64000;
    private McpPromptData() {}
    static String name(JSONObject object,String field,int max)throws Exception{
        Object value=object.get(field);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>max)throw new IllegalArgumentException("MCP提示标识无效："+field);
        String text=(String)value;validText(text,max);for(int i=0;i<text.length();i++)if(text.charAt(i)<32)throw new IllegalArgumentException("MCP提示标识不能包含控制字符");return text;
    }
    private static void validText(String text,int max)throws Exception{
        if(text.length()>max||text.indexOf('\0')>=0)throw new IllegalArgumentException("MCP提示文字超过范围或含空字节");StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
    }
    static void definition(JSONObject source)throws Exception{
        name(source,"name",256);for(String field:new String[]{"title","description"})if(source.has(field)){Object raw=source.get(field);if(!(raw instanceof String))throw new IllegalArgumentException("MCP提示说明需为文字");validText((String)raw,4096);}
        if(source.has("arguments")&&!(source.opt("arguments") instanceof JSONArray))throw new IllegalArgumentException("MCP提示arguments需为数组");JSONArray arguments=source.optJSONArray("arguments");if(arguments==null)return;if(arguments.length()>MAX_ARGUMENTS)throw new IllegalArgumentException("MCP提示参数超过32项");
        Set<String> names=new HashSet<>();for(int i=0;i<arguments.length();i++){JSONObject argument=arguments.getJSONObject(i);if(!names.add(name(argument,"name",128)))throw new IllegalArgumentException("MCP提示参数名重复");if(argument.has("required")&&!(argument.opt("required") instanceof Boolean))throw new IllegalArgumentException("MCP提示required需为布尔值");if(argument.has("description")){Object raw=argument.get("description");if(!(raw instanceof String))throw new IllegalArgumentException("MCP提示参数说明需为文字");validText((String)raw,4096);}}
    }
    static String digest(JSONObject definition)throws Exception{return McpJson.digest(definition);}
    static JSONArray catalogue(JSONArray definitions)throws Exception{
        if(definitions.length()>MAX_PROMPTS)throw new IllegalArgumentException("MCP提示目录超过96项");JSONArray result=new JSONArray();for(int i=0;i<definitions.length();i++){JSONObject definition=definitions.getJSONObject(i);definition(definition);result.put(new JSONObject().put("name",definition.getString("name")).put("descriptorDigest",digest(definition)).put("definition",McpData.copy(definition)).put("untrusted",true));}return result;
    }
    static JSONObject arguments(JSONObject definition,JSONObject supplied)throws Exception{
        definition(definition);if(supplied==null)throw new IllegalArgumentException("MCP提示参数需为对象");JSONArray declared=definition.optJSONArray("arguments");Set<String> allowed=new HashSet<>();
        for(int i=0;declared!=null&&i<declared.length();i++){JSONObject argument=declared.getJSONObject(i);String key=argument.getString("name");allowed.add(key);if(argument.optBoolean("required")&&!supplied.has(key))throw new IllegalArgumentException("缺少MCP提示必填参数："+key);}
        for(Iterator<String> keys=supplied.keys();keys.hasNext();){String key=keys.next();Object value=supplied.get(key);if(!allowed.contains(key)||!(value instanceof String))throw new IllegalArgumentException("MCP提示只接受当前声明的字符串参数："+key);validText((String)value,8000);}
        if(supplied.toString().getBytes(StandardCharsets.UTF_8).length>MAX_ARGUMENT_BYTES)throw new IllegalArgumentException("MCP提示参数超过64000 UTF8字节");return McpData.copy(supplied);
    }
    static JSONObject result(JSONObject source,String token)throws Exception{
        JSONArray messages=source.getJSONArray("messages");if(messages.length()>MAX_MESSAGES)throw new IllegalArgumentException("MCP提示消息超过64项");JSONArray text=new JSONArray(),omitted=new JSONArray();int characters=0;boolean redacted=false;
        JSONObject result=new JSONObject().put("untrusted",true).put("instructionsAreData",true).put("automaticConversationInjection",false).put("nonTextContentFetched",false).put("responseBodyIncluded",false);
        if(source.has("description")){Object raw=source.get("description");if(!(raw instanceof String))throw new IllegalArgumentException("MCP提示说明需为文字");String value=(String)raw;validText(value,MAX_TEXT_CHARS);characters+=value.length();String safe=McpData.redact(value,token);redacted|=!value.equals(safe);result.put("description",safe);}
        for(int i=0;i<messages.length();i++){
            JSONObject message=messages.getJSONObject(i);String role=message.getString("role");if(!Set.of("user","assistant").contains(role))throw new IllegalArgumentException("MCP提示仅接收user/assistant来源角色，角色仍只是数据");JSONObject content=message.getJSONObject("content");String type=name(content,"type",64);
            if(type.equals("text")){Object raw=content.get("text");if(!(raw instanceof String))throw new IllegalArgumentException("MCP提示text需为文字");String value=(String)raw;validText(value,MAX_TEXT_CHARS);characters+=value.length();if(characters>MAX_TEXT_CHARS)throw new IllegalArgumentException("MCP提示全部文字超过64000字符，未截断成完整提示");String safe=McpData.redact(value,token);redacted|=!value.equals(safe);text.put(new JSONObject().put("sourceMessageIndex",i).put("sourceRole",role).put("text",safe));}
            else{String safe=McpData.redact(type,token);redacted|=!safe.equals(type);omitted.put(new JSONObject().put("sourceMessageIndex",i).put("sourceRole",role).put("type",safe).put("reason","unsupported_content_not_decoded_or_fetched"));}
        }
        return result.put("sourceMessages",text).put("omittedContent",omitted).put("protocolMessagesReceived",messages.length()).put("projectedTextMessages",text.length()).put("completeTextProjection",omitted.length()==0&&!redacted).put("credentialRedactionsApplied",redacted).put("originalRoleAuthorityAdded",false);
    }
}
