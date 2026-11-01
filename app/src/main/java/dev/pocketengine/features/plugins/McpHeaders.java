package dev.pocketengine.features.plugins;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import okhttp3.Request;
import org.json.JSONObject;

/** 2026-07-28 mirrored parameter headers; only statically reachable primitive properties are eligible. */
final class McpHeaders {
    private McpHeaders() { }
    static void validate(JSONObject schema)throws Exception {walk(schema,null,null,new HashSet<>(),false,true,0);}
    static void apply(JSONObject schema,JSONObject arguments,Request.Builder request)throws Exception {
        walk(schema,arguments,request,new HashSet<>(),false,true,0);
    }
    private static void walk(JSONObject schema,Object value,Request.Builder request,Set<String> names,boolean property,boolean reachable,int depth)throws Exception {
        if(depth>6)throw new IllegalArgumentException("MCP标头定义过深");
        if(schema.has("x-mcp-header")){
            String name=schema.getString("x-mcp-header"),type=schema.optString("type");
            if(!property||!reachable||name.length()>80||!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||!names.add(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("x-mcp-header需为properties路径下唯一且有效的HTTP标头名");
            if(!type.equals("string")&&!type.equals("integer")&&!type.equals("boolean"))throw new IllegalArgumentException("x-mcp-header只支持string、integer和boolean");
            if(request!=null&&value!=null&&value!=JSONObject.NULL){
                String text;
                if(type.equals("integer")){
                    BigDecimal number=PluginRules.decimal(value);
                    if(number==null||!PluginRules.integer(number)||number.abs().compareTo(new BigDecimal("9007199254740991"))>0)
                        throw new IllegalArgumentException("MCP标头整数超出安全整数范围");
                    text=number.toBigIntegerExact().toString();
                }else if(type.equals("string")&&value instanceof String||type.equals("boolean")&&value instanceof Boolean)text=value.toString();
                else throw new IllegalArgumentException("MCP标头参数类型无效");
                String encoded=encode(text);if(encoded.length()>8192)throw new IllegalArgumentException("MCP参数标头过大");
                request.header("Mcp-Param-"+name,encoded);
            }
        }
        JSONObject properties=schema.optJSONObject("properties");
        if(properties!=null){java.util.Iterator<String> keys=properties.keys();while(keys.hasNext()){
            String key=keys.next();Object child=value instanceof JSONObject?((JSONObject)value).opt(key):null;
            walk(properties.getJSONObject(key),child,request,names,true,reachable,depth+1);
        }}
        if(schema.has("items"))walk(schema.getJSONObject("items"),null,request,names,false,false,depth+1);
    }
    static String encode(String value){
        boolean encode=value.startsWith("=?base64?")&&value.endsWith("?=");
        if(!value.equals(value.trim()))encode=true;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if((c<0x20&&c!='\t')||c>0x7e)encode=true;}
        return encode?"=?base64?"+Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))+"?=":value;
    }
}
