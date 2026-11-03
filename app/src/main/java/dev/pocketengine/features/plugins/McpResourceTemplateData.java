package dev.pocketengine.features.plugins;

import com.damnhandy.uri.template.UriTemplate;
import dev.pocketengine.features.plugins.mcp.McpJson;
import java.net.URI;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-selected resource identifiers only. Expansion never opens a URI or grants execution.
 * Uses the pinned Handy URI Templates library; unsupported forms fail before resources/read. */
public final class McpResourceTemplateData {
    static final int MAX_TEMPLATES=96,MAX_TEMPLATE_CHARS=2048,MAX_VARIABLES=32,MAX_ARGUMENT_BYTES=16384,MAX_COLLECTION_ITEMS=32,MAX_URI_CHARS=2048;
    static final String PROFILE="handy-uri-templates-2.1.8-rfc6570-without-prefix-v1";
    private McpResourceTemplateData(){}

    /** Raw definitions stay intact, including unsupported rows. Nothing is silently rewritten. */
    static JSONArray catalogue(JSONArray definitions)throws Exception{
        if(definitions.length()>MAX_TEMPLATES)throw new IllegalArgumentException("MCP资源模板目录超过96项");
        JSONArray rows=new JSONArray();
        for(int i=0;i<definitions.length();i++){
            JSONObject definition=definitions.getJSONObject(i),row=new JSONObject().put("definition",McpData.copy(definition))
                    .put("descriptorDigest",McpData.digest(definition)).put("untrusted",true);
            if(definition.opt("name") instanceof String)row.put("name",definition.getString("name"));
            if(definition.opt("uriTemplate") instanceof String)row.put("uriTemplate",definition.getString("uriTemplate"));
            try{Plan plan=definition(definition);row.put("variables",new JSONArray(plan.variables)).put("supported",true).put("expansionProfile",PROFILE);}
            catch(IllegalArgumentException unsupported){row.put("supported",false).put("unsupportedReason",unsupported.getMessage());}
            rows.put(row);
        }
        return rows;
    }

    /** Local UI preview only; the native read independently re-lists the current descriptor. */
    public static JSONObject preview(JSONObject definition,JSONObject arguments)throws Exception{
        Plan plan=definition(definition);Map<String,Object> values=arguments(plan,arguments);
        String uri=expand(plan,values);
        return new JSONObject().put("uri",uri).put("descriptorDigest",McpData.digest(definition))
                .put("resourceTemplate",provenance(plan.template,McpData.digest(definition),arguments))
                .put("previewOnly",true).put("networkRequested",false).put("executionAuthorized",false);
    }

    /** Optional advanced values use the existing strict JSON reader, including duplicate names. */
    public static JSONObject parseArguments(String text)throws Exception{
        if(text==null||text.length()>MAX_ARGUMENT_BYTES||text.getBytes(StandardCharsets.UTF_8).length>MAX_ARGUMENT_BYTES)
            throw new IllegalArgumentException("模板参数超过16384 UTF8字节");
        return McpJson.parse(text);
    }

    static JSONObject checkedProvenance(JSONObject source,String uri,String descriptorDigest)throws Exception{
        requireKeys(source,Set.of("uriTemplate","descriptorDigest","arguments","argumentsDigest","expansionProfile"));
        if(!PROFILE.equals(source.optString("expansionProfile"))||!descriptorDigest.equals(source.optString("descriptorDigest"))
                ||!descriptorDigest.matches("[a-f0-9]{64}"))throw new SecurityException("模板来源身份或展开范围不一致");
        Plan plan=template(string(source,"uriTemplate",MAX_TEMPLATE_CHARS,false));JSONObject arguments=source.getJSONObject("arguments");
        if(!McpData.digest(arguments).equals(source.optString("argumentsDigest"))||!uri.equals(expand(plan,arguments(plan,arguments))))
            throw new SecurityException("模板参数或已审阅URI与原资源来源不一致");
        return provenance(plan.template,descriptorDigest,arguments);
    }

    /** Index metadata carries the exact template/argument identity, never the parameter values. */
    static JSONObject index(JSONObject provenance)throws Exception{
        return new JSONObject().put("uriTemplate",provenance.getString("uriTemplate")).put("descriptorDigest",provenance.getString("descriptorDigest"))
                .put("argumentsDigest",provenance.getString("argumentsDigest")).put("expansionProfile",provenance.getString("expansionProfile"));
    }
    static void checkIndex(JSONObject index,String descriptorDigest)throws Exception{
        requireKeys(index,Set.of("uriTemplate","descriptorDigest","argumentsDigest","expansionProfile"));
        if(!PROFILE.equals(index.optString("expansionProfile"))||!descriptorDigest.equals(index.optString("descriptorDigest"))
                ||!index.optString("argumentsDigest").matches("[a-f0-9]{64}"))throw new SecurityException("原资源模板索引身份不一致");
        template(string(index,"uriTemplate",MAX_TEMPLATE_CHARS,false));
    }
    private static JSONObject provenance(String template,String digest,JSONObject arguments)throws Exception{
        return new JSONObject().put("uriTemplate",template).put("descriptorDigest",digest).put("arguments",McpData.copy(arguments))
                .put("argumentsDigest",McpData.digest(arguments)).put("expansionProfile",PROFILE);
    }
    private static void requireKeys(JSONObject value,Set<String> allowed){
        if(value==null||value.length()!=allowed.size())throw new SecurityException("资源模板来源字段不完整");
        for(Iterator<String> keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("资源模板来源含非原生字段");
    }

    private static Plan definition(JSONObject definition)throws Exception{
        if(definition==null)throw new IllegalArgumentException("模板声明不是对象");
        string(definition,"name",256,false);
        for(String key:new String[]{"title","description","mimeType"})if(definition.has(key))string(definition,key,key.equals("mimeType")?256:4096,true);
        return template(string(definition,"uriTemplate",MAX_TEMPLATE_CHARS,false));
    }
    private static final class Plan {
        final String template;final Set<String> variables=new LinkedHashSet<>(),reserved=new LinkedHashSet<>(),matrix=new LinkedHashSet<>();
        final List<ExpressionValues> expressions=new ArrayList<>();
        Plan(String template){this.template=template;}
    }
    private static final class ExpressionValues {
        final char operator;final List<VariableUse> variables=new ArrayList<>();
        ExpressionValues(char operator){this.operator=operator;}
    }
    private static final class VariableUse {
        final String name;final boolean explode;
        VariableUse(String name,boolean explode){this.name=name;this.explode=explode;}
    }
    /** Validates the standard grammar before a library with extension support sees untrusted input. */
    private static Plan template(String text){
        Plan plan=new Plan(text);int occurrences=0;
        for(int at=0;at<text.length();){
            char c=text.charAt(at);
            if(c!='{'){
                if(c=='}'||c<0x21||c==0x7f||"\"'<>\\^`|".indexOf(c)>=0)throw new IllegalArgumentException("模板文字含RFC6570不允许的字符");
                if(c=='%'){if(at+2>=text.length()||!hex(text.charAt(at+1))||!hex(text.charAt(at+2)))throw new IllegalArgumentException("模板文字百分号编码无效");at+=3;}else at++;
                continue;
            }
            int end=text.indexOf('}',at+1);if(end<0)throw new IllegalArgumentException("模板表达式没有闭合");
            String expression=text.substring(at+1,end);if(expression.isEmpty())throw new IllegalArgumentException("模板表达式为空");
            char operator=expression.charAt(0);int start="+#./;?&".indexOf(operator)>=0?1:0;
            ExpressionValues group=new ExpressionValues(start==1?operator:'\0');plan.expressions.add(group);
            String[] specs=expression.substring(start).split(",",-1);
            for(String spec:specs){
                if(++occurrences>64)throw new IllegalArgumentException("模板变量出现次数超过64项");
                if(spec.indexOf(':')>=0)throw new IllegalArgumentException("当前模板展开不支持prefix（:长度）修饰；保留原声明，没有删改或读取URI");
                String name=spec.endsWith("*")?spec.substring(0,spec.length()-1):spec;
                if(name.isEmpty()||name.length()>128)throw new IllegalArgumentException("模板变量名为空或超过128字符");
                boolean segment=false;
                for(int p=0;p<name.length();p++){
                    char n=name.charAt(p);
                    if(n=='.'){if(!segment)throw new IllegalArgumentException("模板变量名的点分段无效");segment=false;continue;}
                    if(n=='%'){if(p+2>=name.length()||!hex(name.charAt(p+1))||!hex(name.charAt(p+2)))throw new IllegalArgumentException("模板变量名百分号编码无效");p+=2;}
                    else if(!(n>='a'&&n<='z'||n>='A'&&n<='Z'||n>='0'&&n<='9'||n=='_'))throw new IllegalArgumentException("模板变量名或操作符不属于RFC6570支持范围");
                    segment=true;
                }
                if(!segment)throw new IllegalArgumentException("模板变量名不能以点结束");
                plan.variables.add(name);if(plan.variables.size()>MAX_VARIABLES)throw new IllegalArgumentException("模板参数超过32项");
                group.variables.add(new VariableUse(name,spec.endsWith("*")));
                if(start==1&&(operator=='+'||operator=='#'))plan.reserved.add(name);
                if(start==1&&operator==';')plan.matrix.add(name);
            }
            at=end+1;
        }
        return plan;
    }
    private static Map<String,Object> arguments(Plan plan,JSONObject supplied)throws Exception{
        if(supplied==null||supplied.length()>MAX_VARIABLES||supplied.toString().getBytes(StandardCharsets.UTF_8).length>MAX_ARGUMENT_BYTES)
            throw new IllegalArgumentException("模板参数不是对象或超过16384 UTF8字节/32项");
        Map<String,Object> values=new LinkedHashMap<>();
        for(Iterator<String> keys=supplied.keys();keys.hasNext();){
            String key=keys.next();if(!plan.variables.contains(key))throw new IllegalArgumentException("参数不在当前模板声明内："+key);
            Object raw=supplied.get(key),value;
            if(raw==JSONObject.NULL)value=null;
            else if(raw instanceof String)value=argumentText((String)raw);
            else if(raw instanceof JSONArray){
                JSONArray array=(JSONArray)raw;if(array.length()>MAX_COLLECTION_ITEMS)throw new IllegalArgumentException("模板参数列表超过32项");List<String> list=new ArrayList<>();
                for(int i=0;i<array.length();i++){if(!(array.get(i) instanceof String))throw new IllegalArgumentException("模板列表只接受字符串，不能嵌套");list.add(argumentText(array.getString(i)));}value=list;
            }else if(raw instanceof JSONObject){
                JSONObject object=(JSONObject)raw;if(object.length()>MAX_COLLECTION_ITEMS)throw new IllegalArgumentException("模板参数映射超过32项");Map<String,Object> map=new LinkedHashMap<>();
                List<String> names=new ArrayList<>();object.keys().forEachRemaining(names::add);Collections.sort(names);
                for(String name:names){if(!(object.get(name) instanceof String))throw new IllegalArgumentException("模板映射只接受字符串值，不能嵌套");map.put(argumentText(name),argumentText(object.getString(name)));}value=map;
            }else throw new IllegalArgumentException("模板参数只接受字符串、字符串列表/映射，或null（未提供）");
            // The pinned library escapes square brackets even in reserved expansion and
            // mishandles empty composite matrix members. Refuse these exact known limits.
            if(plan.reserved.contains(key)&&(contains(raw,"[",false)||contains(raw,"]",false)))throw new IllegalArgumentException("当前库不支持reserved/fragment变量中的方括号值；未读取不同URI");
            if(plan.matrix.contains(key)&&(raw instanceof JSONArray||raw instanceof JSONObject)&&contains(raw,"",true))throw new IllegalArgumentException("当前库不支持matrix参数中的空列表成员或空映射键值；未读取不同URI");
            values.put(key,value);
        }
        checkEmptySeparators(plan,supplied);
        return values;
    }
    /** The library's joinParts skips non-final empty strings. Detect only affected
     * value shapes; the original expander remains the sole producer of URI bytes. */
    private static void checkEmptySeparators(Plan plan,JSONObject supplied)throws Exception{
        for(ExpressionValues group:plan.expressions){
            List<Object> defined=new ArrayList<>();
            for(VariableUse use:group.variables){
                Object value=supplied.opt(use.name);
                if(value==null||value==JSONObject.NULL||value instanceof JSONArray&&((JSONArray)value).length()==0
                        ||value instanceof JSONObject&&((JSONObject)value).length()==0)continue;
                defined.add(value);
                if(group.operator==';'&&use.explode&&value instanceof String&&((String)value).isEmpty())
                    throw new IllegalArgumentException("当前库不支持matrix展开修饰中的空文字；不能把*留在参数名中读取其他URI");
                if(value instanceof JSONObject&&use.explode&&group.operator!='?'&&group.operator!='&'){
                    JSONObject map=(JSONObject)value;
                    for(Iterator<String> keys=map.keys();keys.hasNext();)if(map.getString(keys.next()).isEmpty())
                        throw new IllegalArgumentException("当前库不支持非query映射展开中的空值；不能增加=读取其他URI");
                }
                if(value instanceof JSONArray&&!(use.explode&&(group.operator=='?'||group.operator=='&'))){
                    JSONArray list=(JSONArray)value;
                    for(int i=0;i+1<list.length();i++)if(list.getString(i).isEmpty())
                        throw new IllegalArgumentException("当前库不支持此列表非末位的空文字；不能省略原分隔符读取其他URI");
                }
            }
            if(group.operator==';'||group.operator=='?'||group.operator=='&')continue;
            for(int i=0;i+1<defined.size();i++){
                Object value=defined.get(i);
                if(value instanceof String&&((String)value).isEmpty()||value instanceof JSONArray&&((JSONArray)value).length()==1&&((JSONArray)value).getString(0).isEmpty())
                    throw new IllegalArgumentException("当前库不支持同一表达式非末位变量展开为空文字；不能省略原分隔符读取其他URI");
            }
        }
    }
    private static boolean contains(Object value,String needle,boolean exact)throws Exception{
        if(value instanceof String)return exact?value.equals(needle):((String)value).contains(needle);
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)if(contains(array.get(i),needle,exact))return true;}
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(Iterator<String> keys=object.keys();keys.hasNext();){String key=keys.next();if(contains(key,needle,exact)||contains(object.get(key),needle,exact))return true;}}
        return false;
    }
    private static String expand(Plan plan,Map<String,Object> values){
        final String expanded;
        try{expanded=UriTemplate.fromTemplate(plan.template).expand(values);}
        catch(RuntimeException failure){throw new IllegalArgumentException("当前模板或参数无法在已声明范围内展开；没有请求资源正文");}
        // URI conversion only encodes valid literal Unicode. It never resolves or opens a URI.
        final String uri;
        try{uri=new URI(expanded).toASCIIString();}
        catch(Exception invalid){throw new IllegalArgumentException("模板展开结果不是有效URI；没有请求资源正文");}
        if(uri.isEmpty()||uri.length()>MAX_URI_CHARS)throw new IllegalArgumentException("模板展开URI为空或超过2048字符；没有截断读取");
        return uri;
    }
    private static String string(JSONObject object,String key,int max,boolean empty){
        Object value=object.opt(key);if(!(value instanceof String))throw new IllegalArgumentException("模板字段需为文字："+key);
        String text=(String)value;if(!empty&&text.isEmpty()||text.length()>max)throw new IllegalArgumentException("模板字段为空或超过范围："+key);
        if(key.equals("description")||key.equals("title")){if(text.indexOf('\0')>=0)throw new IllegalArgumentException("模板说明不能包含空字节");validUnicode(text);}
        else validText(text);return text;
    }
    private static String argumentText(String text){if(text.length()>2048)throw new IllegalArgumentException("单个模板参数超过2048字符");validText(text);return text;}
    private static void validText(String text){
        for(int i=0;i<text.length();i++)if(text.charAt(i)<32||text.charAt(i)==127)throw new IllegalArgumentException("模板文字不能包含控制字符");
        validUnicode(text);
    }
    private static void validUnicode(String text){
        try{StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));}catch(Exception invalid){throw new IllegalArgumentException("模板文字包含不完整Unicode");}
    }
    private static boolean hex(char c){return c>='0'&&c<='9'||c>='a'&&c<='f'||c>='A'&&c<='F';}
}
