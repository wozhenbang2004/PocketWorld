package dev.pocketengine.features.skills;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A deliberately bounded YAML subset, not a general YAML evaluator or script loader. */
public final class SkillManifest {
    private SkillManifest(){}
    public static JSONObject parse(String text)throws Exception {
        if(text==null||text.length()>64000||text.indexOf('\0')>=0)throw new IllegalArgumentException("SKILL.md内容无效或超过64000字");
        String[] lines=text.replace("\r\n","\n").split("\n",-1);if(lines.length<3||!lines[0].equals("---"))throw new IllegalArgumentException("SKILL.md必须以YAML frontmatter开始");
        int end=1;while(end<lines.length&&!lines[end].equals("---"))end++;
        if(end==lines.length||end>200)throw new IllegalArgumentException("技能元数据缺少结束标记或超过200行");
        Map<String,String> fields=new LinkedHashMap<>();JSONObject metadata=new JSONObject();boolean inMetadata=false;
        for(int i=1;i<end;i++){
            String line=lines[i];if(line.trim().isEmpty()||line.trim().startsWith("#"))continue;
            if(line.startsWith("  ")){
                if(!inMetadata||line.startsWith("   "))throw unsupported();int split=line.indexOf(':');if(split<3)throw unsupported();
                String key=line.substring(2,split).trim();if(!key.matches("[A-Za-z0-9_.-]{1,80}")||metadata.has(key))throw unsupported();metadata.put(key,scalar(line.substring(split+1).trim()));continue;
            }
            inMetadata=false;int split=line.indexOf(':');if(split<1)throw unsupported();String key=line.substring(0,split),raw=line.substring(split+1).trim();
            if(!Set.of("name","description","license","compatibility","allowed-tools","required-capabilities","metadata","disable-model-invocation","user-invocable").contains(key)||fields.containsKey(key))throw unsupported();
            if(key.equals("metadata")){if(!raw.isEmpty())throw unsupported();inMetadata=true;fields.put(key,"");continue;}
            if(key.equals("required-capabilities")){
                JSONArray required=new JSONArray();
                if(raw.isEmpty()){
                    while(i+1<end&&(lines[i+1].startsWith("  ")||lines[i+1].trim().isEmpty())){
                        String next=lines[++i];if(next.trim().isEmpty()||next.trim().startsWith("#"))continue;
                        if(!next.startsWith("  - "))throw unsupported();required.put(scalar(next.substring(4).trim()));
                    }
                }else if(raw.startsWith("[")){
                    if(!raw.endsWith("]"))throw unsupported();String entries=raw.substring(1,raw.length()-1).trim();
                    if(!entries.isEmpty())for(String entry:entries.split(",",-1))required.put(scalar(entry.trim()));
                }
                else{
                    String value=scalar(raw);if(!value.isEmpty())for(String capability:value.split("[,\\s]+"))required.put(capability);
                }
                fields.put(key,packageCapabilities(required).toString());continue;
            }
            if(raw.equals("|")||raw.equals(">")||raw.equals("|-")||raw.equals(">-")){
                boolean folded=raw.charAt(0)=='>';StringBuilder block=new StringBuilder();
                while(i+1<end&&(lines[i+1].startsWith("  ")||lines[i+1].trim().isEmpty())){
                    String next=lines[++i];String part=next.length()>=2?next.substring(2):"";
                    if(block.length()>0)block.append(folded&&!part.isEmpty()?' ':'\n');block.append(part);
                }fields.put(key,block.toString().trim());
            }else fields.put(key,scalar(raw));
        }
        String name=fields.getOrDefault("name",""),description=fields.getOrDefault("description","");
        if(name.length()>64||!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))throw new IllegalArgumentException("技能名称需为1至64位小写字母、数字和单个连字符");
        if(description.trim().isEmpty()||description.length()>1024)throw new IllegalArgumentException("技能描述需为1至1024字");
        if(fields.getOrDefault("compatibility","").length()>500||fields.getOrDefault("license","").length()>1000||fields.getOrDefault("allowed-tools","").length()>1000)throw new IllegalArgumentException("技能元数据超出支持范围");
        JSONObject result=new JSONObject().put("name",name).put("description",description).put("metadata",metadata);
        for(String key:new String[]{"license","compatibility","allowed-tools"})if(fields.containsKey(key))result.put(key,fields.get(key));
        if(fields.containsKey("required-capabilities"))result.put("required-capabilities",new JSONArray(fields.get("required-capabilities")));
        for(String key:new String[]{"disable-model-invocation","user-invocable"})if(fields.containsKey(key)){
            String value=fields.get(key);if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException(key+"只能为true或false");
            result.put(key,Boolean.parseBoolean(value));
        }
        return result;
    }
    /** Package declarations are requirements, never installed tools or owner grants. */
    static JSONArray declaredCapabilities(JSONObject metadata)throws Exception {
        if(!metadata.has("required-capabilities"))return new JSONArray();
        if(!(metadata.get("required-capabilities") instanceof JSONArray))throw new IllegalArgumentException("技能能力依赖必须为文本列表");
        return packageCapabilities(metadata.getJSONArray("required-capabilities"));
    }
    /** Provider function names are presentation aliases, not stable dependency selections. */
    public static boolean isPluginFunctionAlias(String capability){return capability!=null&&capability.matches("plugin_[a-f0-9]{24}");}
    private static JSONArray packageCapabilities(JSONArray input)throws Exception {
        JSONArray required=capabilities(input);
        for(int i=0;i<required.length();i++)if(isPluginFunctionAlias(required.getString(i)))throw new IllegalArgumentException("包内 required-capabilities 不能使用 plugin_<hash> 函数别名；请改用准确的 plugin:已登记插件标识:工具名，再重新审阅包内容");
        return required;
    }
    /** Legacy owner requirements remain readable; native profile preflight diagnoses aliases. */
    static JSONArray capabilities(JSONArray input)throws Exception {
        JSONArray result=new JSONArray();Set<String> seen=new LinkedHashSet<>();if(input==null)return result;
        if(input.length()>32)throw new IllegalArgumentException("最多声明32项能力依赖");
        for(int i=0;i<input.length();i++){
            Object raw=input.get(i);if(!(raw instanceof String capability)||!capability.matches("[a-z][a-z0-9_.:-]{0,79}"))throw new IllegalArgumentException("能力依赖须使用已有能力标识；插件工具格式为 plugin:已登记插件标识:工具名");
            if(seen.add(capability))result.put(capability);
        }
        return result;
    }
    private static String scalar(String raw)throws Exception {
        if(raw.isEmpty())return "";
        if(raw.startsWith("\"")){try{Object value=new JSONObject("{\"value\":"+raw+"}").get("value");if(!(value instanceof String))throw unsupported();return (String)value;}catch(Exception invalid){throw unsupported();}}
        if(raw.startsWith("'")){if(raw.length()<2||!raw.endsWith("'"))throw unsupported();return raw.substring(1,raw.length()-1).replace("''","'");}
        if(raw.startsWith("!")||raw.startsWith("&")||raw.startsWith("*")||raw.startsWith("[")||raw.startsWith("{")||raw.startsWith("|")||raw.startsWith(">")||raw.contains("\t"))throw unsupported();
        int comment=raw.indexOf(" #");return (comment<0?raw:raw.substring(0,comment)).trim();
    }
    private static IllegalArgumentException unsupported(){return new IllegalArgumentException("技能元数据支持文本、调用策略布尔值、能力依赖列表和字符串metadata；不支持YAML标签、锚点或可执行安装定义");}
}
