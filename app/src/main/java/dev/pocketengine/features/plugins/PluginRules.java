package dev.pocketengine.features.plugins;

import java.net.URI;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Declarative tool manifests and explicit grants; no downloaded executable code. */
public final class PluginRules {
    private static final Set<String> SCHEMA_TYPES=Set.of("object","array","string","boolean","number","integer","null");
    private PluginRules() { }
    /** Read-only diagnostics for this bounded schema subset, not a full JSON Schema implementation. */
    public static JSONObject diagnoseArguments(Object arguments,JSONObject schema)throws Exception {
        validateSchema(schema,0,new int[]{0,0},false);
        if(schema.toString().length()>16000)throw new IllegalArgumentException("工具参数定义过大");
        return PluginArguments.diagnose(arguments,schema);
    }
    static JSONObject diagnoseMcpArguments(Object arguments,JSONObject schema)throws Exception {
        validateSchema(schema,0,new int[]{0,0},true);McpHeaders.validate(schema);
        if(schema.toString().length()>16000)throw new IllegalArgumentException("工具参数定义过大");
        return PluginArguments.diagnose(arguments,schema);
    }
    /** Compatibility entry point: the same diagnostics core remains the execution gate. */
    public static void validateArguments(JSONObject arguments,JSONObject schema)throws Exception {
        JSONObject result=diagnoseArguments(arguments,schema);
        if(!result.getBoolean("valid"))throw new IllegalArgumentException(result.getJSONArray("errors").getJSONObject(0).getString("message"));
    }
    public static JSONObject manifest(JSONObject source)throws Exception {
        String id=text(source,"id",80),name=text(source,"name",80),version=text(source,"version",32);
        if(!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}"))throw new IllegalArgumentException("插件ID格式无效");
        JSONObject mcpSource=source.has("mcpSource")?mcpSource(source.getJSONObject("mcpSource")):null;
        JSONArray tools=source.getJSONArray("tools");if(tools.length()>12||tools.length()==0&&mcpSource==null)throw new IllegalArgumentException("插件需1至12个工具，或原发现核对的MCP资源/提示来源");
        JSONArray normalized=new JSONArray();Set<String> names=new HashSet<>();
        for(int i=0;i<tools.length();i++){
            JSONObject tool=tools.getJSONObject(i);String kind=text(tool,"kind",12),toolName=text(tool,"name",kind.equals("mcp")?128:64);
            if(!(kind.equals("mcp")?mcpToolName(toolName):toolName.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}"))||!names.add(toolName))throw new IllegalArgumentException("工具名称无效或重复");
            if(!kind.equals("echo")&&!kind.equals("http")&&!kind.equals("mcp"))throw new IllegalArgumentException("当前仅支持echo、http和MCP声明工具");
            JSONObject definition=new JSONObject().put("name",toolName).put("kind",kind).put("description",tool.optString("description",toolName));
            String effect=kind.equals("echo")?"read":tool.optString("effect","write");
            if(!effect.equals("read")&&!effect.equals("write"))throw new IllegalArgumentException("工具effect需为read或write");
            definition.put("effect",effect);
            if(definition.getString("description").length()>1000)throw new IllegalArgumentException("工具描述过长");
            String transport=kind.equals("mcp")?tool.optString("transport","streamable_http"):"";
            if(kind.equals("mcp")&&(!Set.of("streamable_http","stdio").contains(transport)||tool.has("transport")&&!(tool.opt("transport") instanceof String)))throw new IllegalArgumentException("未知MCP transport；不能把HTTP与本机程序权限混用");
            boolean stdio=transport.equals("stdio");
            if(mcpSource!=null&&(!kind.equals("mcp")||stdio||!mcpSource.getString("endpoint").equals(tool.opt("endpoint"))||!mcpSource.getString("protocolVersion").equals(tool.opt("protocolVersion"))||!mcpSource.getString("serverDigest").equals(tool.opt("serverDigest"))))throw new IllegalArgumentException("MCP来源与所选工具必须属于同一准确HTTP服务，不能混入其他执行入口");
            if(kind.equals("http")||kind.equals("mcp")&&!stdio){String endpoint=text(tool,"endpoint",2000);validateEndpoint(endpoint);definition.put("endpoint",endpoint);}
            if(kind.equals("mcp")&&tool.has("transport"))definition.put("transport",transport);
            if(kind.equals("mcp")){
                if(!McpData.supportedVersion(tool.optString("protocolVersion")))throw new IllegalArgumentException("当前仅支持MCP 2026-07-28或2025-11-25");
                if(stdio){
                    if(!McpStdioClient.VERSION.equals(tool.optString("protocolVersion"))||tool.has("endpoint"))throw new IllegalArgumentException("本机stdio仅支持实际协商的2025-11-25，不接受HTTP endpoint");
                    String configuration=text(tool,"configurationDigest",64);if(!configuration.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("本机MCP配置身份摘要无效");definition.put("configurationDigest",configuration);
                }
                String digest=text(tool,"descriptorDigest",64);if(!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("MCP工具身份摘要无效");
                String serverDigest=text(tool,"serverDigest",64);if(!serverDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("MCP服务身份摘要无效");
                definition.put("protocolVersion",tool.getString("protocolVersion")).put("serverDigest",serverDigest).put("descriptorDigest",digest).put("effect","write");
                for(String field:new String[]{"annotations","outputSchema","execution"})if(tool.has(field)){
                    JSONObject metadata=tool.getJSONObject(field);if(metadata.toString().length()>16000)throw new IllegalArgumentException("MCP工具元数据过大");
                    definition.put(field,new JSONObject(metadata.toString()));
                }
            }
            JSONObject schema=tool.optJSONObject("schema");if(schema==null)schema=new JSONObject().put("type","object");
            validateSchema(schema,0,new int[]{0,0},kind.equals("mcp"));
            if(schema.toString().length()>16000)throw new IllegalArgumentException("工具参数定义过大");
            Object rootType=schema.opt("type");
            if(rootType!=null&&!"object".equals(rootType)&&!(rootType instanceof JSONArray&&((JSONArray)rootType).length()==1&&"object".equals(((JSONArray)rootType).opt(0))))throw new IllegalArgumentException("工具参数根类型需为object");
            if(kind.equals("mcp")){if(stdio)rejectStdioHeaders(schema);else McpHeaders.validate(schema);}
            definition.put("schema",new JSONObject(schema.toString()));normalized.put(definition);
        }
        JSONObject result=new JSONObject().put("id",id).put("name",name).put("version",version).put("tools",normalized);
        if(mcpSource!=null)result.put("mcpSource",mcpSource);return result;
    }
    /** A source descriptor is data, not discovery/install or tool-execution authority. Native
     * installation still requires the retained live preview, even when its tool list is empty. */
    public static JSONObject mcpSource(JSONObject source)throws Exception {
        Set<String> keys=new HashSet<>();source.keys().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("endpoint","protocolVersion","serverDigest")))throw new IllegalArgumentException("MCP来源只接受准确地址、协议和服务摘要");
        String endpoint=text(source,"endpoint",2000),version=text(source,"protocolVersion",32),digest=text(source,"serverDigest",64);
        if(!endpoint.equals(source.get("endpoint"))||!version.equals(source.get("protocolVersion"))||!digest.equals(source.get("serverDigest")))throw new IllegalArgumentException("MCP来源身份不能含未核对的首尾空白");
        validateEndpoint(endpoint);if(!McpData.supportedVersion(version)||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("MCP来源协议或服务身份无效");
        return new JSONObject().put("endpoint",endpoint).put("protocolVersion",version).put("serverDigest",digest);
    }
    /** Remote names remain exact; the model-facing alias is separately installation-bound. */
    static boolean mcpToolName(String name){return name!=null&&name.matches("[a-zA-Z0-9_.-]{1,128}");}
    private static void rejectStdioHeaders(JSONObject schema)throws Exception {
        // Follow only actual subschemas already bounded by validateSchema, not arbitrary example data.
        if(schema.has("x-mcp-header"))throw new IllegalArgumentException("本机stdio没有HTTP header输入通道");
        JSONObject properties=schema.optJSONObject("properties");if(properties!=null)for(var keys=properties.keys();keys.hasNext();)rejectStdioHeaders(properties.getJSONObject(keys.next()));
        JSONObject items=schema.optJSONObject("items");if(items!=null)rejectStdioHeaders(items);
    }
    public static void validateEndpoint(String endpoint)throws Exception {
        URI uri=new URI(endpoint);String scheme=uri.getScheme();
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("工具地址需为无凭据的完整URL");
        if("https".equalsIgnoreCase(scheme))return;
        if("http".equalsIgnoreCase(scheme)&&(uri.getHost().equals("127.0.0.1")||uri.getHost().equals("localhost")||uri.getHost().equals("[::1]")))return;
        throw new IllegalArgumentException("远程工具需HTTPS；HTTP仅支持本机回环地址");
    }
    public static JSONObject permitted(JSONObject plugin,String toolName,JSONObject arguments)throws Exception {
        if(!plugin.optBoolean("enabled"))throw new IllegalStateException("插件未启用");
        boolean granted=false;JSONArray grants=plugin.optJSONArray("grants");if(grants!=null)for(int i=0;i<grants.length();i++)if(("tool:"+toolName).equals(grants.optString(i)))granted=true;
        if(!granted)throw new SecurityException("尚未授予此工具权限");
        JSONArray tools=plugin.getJSONObject("manifest").getJSONArray("tools");
        for(int i=0;i<tools.length();i++){JSONObject tool=tools.getJSONObject(i);if(toolName.equals(tool.getString("name"))){if("mcp".equals(tool.getString("kind"))){
            JSONObject diagnostic=diagnoseMcpArguments(arguments,tool.getJSONObject("schema"));
            if(!diagnostic.getBoolean("valid"))throw new IllegalArgumentException(diagnostic.getJSONArray("errors").getJSONObject(0).getString("message"));
        }else validateArguments(arguments,tool.getJSONObject("schema"));return new JSONObject(tool.toString());}}
        throw new IllegalArgumentException("插件工具不存在");
    }
    public static JSONArray grants(JSONObject manifest,JSONArray requested)throws Exception {
        if(requested.length()>12)throw new IllegalArgumentException("权限列表过大");Set<String> valid=new HashSet<>();JSONArray tools=manifest.getJSONArray("tools");for(int i=0;i<tools.length();i++)valid.add("tool:"+tools.getJSONObject(i).getString("name"));
        JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();for(int i=0;i<requested.length();i++){String grant=requested.getString(i);if(!valid.contains(grant))throw new IllegalArgumentException("未知工具权限："+grant);if(seen.add(grant))result.put(grant);}return result;
    }
    public static String grantsDigest(JSONArray grants)throws Exception {
        java.util.TreeSet<String> sorted=new java.util.TreeSet<>();for(int i=0;grants!=null&&i<grants.length();i++)sorted.add(grants.getString(i));
        byte[] raw=new JSONArray(sorted).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);StringBuilder out=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(raw))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static void validateSchema(JSONObject schema,int depth,int[] nodes,boolean mcp)throws Exception {
        if(depth>6||++nodes[0]>1024)throw new IllegalArgumentException("参数定义结构过深或过大");
        Set<String> allowed=new HashSet<>(java.util.Arrays.asList("type","properties","required","additionalProperties","items","maxItems","minLength","maxLength","enum","minimum","maximum"));
        if(mcp)allowed.addAll(java.util.Arrays.asList("$schema","title","description","$comment","default","examples","deprecated","readOnly","writeOnly","x-mcp-header"));
        java.util.Iterator<String> keys=schema.keys();while(keys.hasNext()){String key=keys.next();if(!allowed.contains(key))throw new IllegalArgumentException("当前参数定义包含不支持的关键字："+key);}
        // The supported keywords have the same meaning in draft-07 and 2020-12.
        // Exa's official MCP SDK emits draft-07. Keep the original dialect and
        // descriptor identity; this does not enable references or other keywords.
        if(schema.has("$schema")&&!Set.of("https://json-schema.org/draft/2020-12/schema","http://json-schema.org/draft-07/schema#","https://json-schema.org/draft-07/schema#").contains(schema.optString("$schema")))throw new IllegalArgumentException("当前仅支持JSON Schema draft-07与2020-12的有限子集");
        for(String annotation:new String[]{"title","description","$comment"})if(schema.has(annotation)&&!(schema.get(annotation) instanceof String))throw new IllegalArgumentException(annotation+"需为文字");
        for(String annotation:new String[]{"deprecated","readOnly","writeOnly"})if(schema.has(annotation)&&!(schema.get(annotation) instanceof Boolean))throw new IllegalArgumentException(annotation+"需为布尔值");
        if(schema.has("examples")&&!(schema.get("examples") instanceof JSONArray))throw new IllegalArgumentException("examples需为列表");
        if(schema.has("type")){
            Object type=schema.get("type");
            if(type instanceof String){
                if(!SCHEMA_TYPES.contains(type))throw new IllegalArgumentException("参数定义类型无效");schemaSize(nodes,((String)type).length());
            }else if(type instanceof JSONArray){
                JSONArray types=(JSONArray)type;Set<String> seen=new HashSet<>();
                if(types.length()==0||types.length()>SCHEMA_TYPES.size())throw new IllegalArgumentException("参数类型列表需包含1至7种不同的JSON类型");
                for(int i=0;i<types.length();i++){
                    Object member=types.get(i);
                    if(!(member instanceof String)||!SCHEMA_TYPES.contains(member)||!seen.add((String)member))throw new IllegalArgumentException("参数类型列表含无效或重复类型");
                    schemaSize(nodes,((String)member).length());
                }
            }else throw new IllegalArgumentException("参数定义类型需为类型名称或不重复的类型列表");
        }
        for(String key:new String[]{"minLength","maxLength","maxItems"})if(schema.has(key)){
            BigDecimal value=decimal(schema.get(key));
            if(value==null||value.signum()<0||value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))>0||!integer(value))
                throw new IllegalArgumentException(key+"需为0至2147483647之间的整数；执行时仍受平台上限约束");
            schemaSize(nodes,schema.get(key).toString().length());
        }
        if(schema.has("minLength")&&schema.has("maxLength")&&decimal(schema.get("minLength")).compareTo(decimal(schema.get("maxLength")))>0)
            throw new IllegalArgumentException("minLength不可大于maxLength");
        for(String key:new String[]{"minimum","maximum"})if(schema.has(key)){if(decimal(schema.get(key))==null)throw new IllegalArgumentException(key+"需为有限数字");schemaSize(nodes,schema.get(key).toString().length());}
        if(schema.has("minimum")&&schema.has("maximum")&&decimal(schema.get("minimum")).compareTo(decimal(schema.get("maximum")))>0)
            throw new IllegalArgumentException("minimum不可大于maximum");
        if(schema.has("additionalProperties")&&!(schema.get("additionalProperties") instanceof Boolean))throw new IllegalArgumentException("additionalProperties需为布尔值");
        if(schema.has("properties")){JSONObject properties=schema.getJSONObject("properties");if(properties.length()>30)throw new IllegalArgumentException("参数定义字段过多");java.util.Iterator<String> names=properties.keys();while(names.hasNext()){String name=names.next();schemaSize(nodes,name.length());if(name.length()>16000)throw new IllegalArgumentException("参数字段名称过长");validateSchema(properties.getJSONObject(name),depth+1,nodes,mcp);}}
        if(schema.has("items"))validateSchema(schema.getJSONObject("items"),depth+1,nodes,mcp);
        if(schema.has("required")){JSONArray required=schema.getJSONArray("required");if(required.length()>30)throw new IllegalArgumentException("必需字段过多");for(int i=0;i<required.length();i++){if(!(required.get(i) instanceof String))throw new IllegalArgumentException("required需为字段名称列表");schemaSize(nodes,required.getString(i).length());}}
        if(schema.has("enum")){JSONArray values=schema.getJSONArray("enum");if(values.length()>30)throw new IllegalArgumentException("枚举值过多");for(int i=0;i<values.length();i++){Object value=values.get(i);if(value instanceof String||value instanceof Number)schemaSize(nodes,value.toString().length());if(value instanceof Number){if(decimal(value)==null)throw new IllegalArgumentException("枚举数字无效");}else if(value instanceof String){if(((String)value).length()>16000)throw new IllegalArgumentException("枚举文字过长");}else if(!(value instanceof Boolean)&&value!=JSONObject.NULL)throw new IllegalArgumentException("枚举仅支持基本值");}}
    }
    private static void schemaSize(int[] budget,int characters){
        if(characters>16000-budget[1])throw new IllegalArgumentException("工具参数定义过大");
        budget[1]+=characters;
    }
    static BigDecimal decimal(Object value) {
        if(!(value instanceof Number))return null;
        String text=value.toString();if(text.length()>16000)return null;
        try{return new BigDecimal(text);}catch(NumberFormatException invalid){return null;}
    }
    static boolean integer(BigDecimal value) {
        if(value.signum()==0||value.scale()<=0)return true;
        try{return value.stripTrailingZeros().scale()<=0;}catch(ArithmeticException invalid){return false;}
    }
    private static String text(JSONObject source,String key,int limit)throws Exception {Object value=source.opt(key);if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>limit)throw new IllegalArgumentException(key+"无效");return ((String)value).trim();}
}

