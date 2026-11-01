package dev.pocketengine.features.groups;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class GroupJson {
    private GroupJson() { }
    static JSONObject copy(JSONObject v) throws Exception { return new JSONObject(v.toString()); }
    static JSONArray copy(JSONArray v) throws Exception { return new JSONArray(v.toString()); }
    static String text(JSONObject v,String key,int max) throws Exception {
        Object raw=v.opt(key);
        if(!(raw instanceof String))throw new IllegalArgumentException("字段必须为文本："+key);
        return text((String)raw,max,key);
    }
    static String text(String value,int max,String key) {
        if(value==null||value.trim().isEmpty()||value.length()>max||value.indexOf('\0')>=0)
            throw new IllegalArgumentException("字段为空或过长："+key);
        return value.trim();
    }
    static String id(JSONObject v,String key) throws Exception {return id(text(v,key,240),key);}
    static String id(String value,String key) {
        String result=text(value,240,key);
        if(!result.matches("[A-Za-z0-9_:.@+\\-]{1,240}"))throw new IllegalArgumentException("身份字段格式无效："+key);
        return result;
    }
    static long number(JSONObject v,String key,long min,long max) throws Exception {
        Object raw=v.opt(key);
        if(!(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long))
            throw new IllegalArgumentException("字段必须为整数："+key);
        long result=((Number)raw).longValue();
        if(result<min||result>max)throw new IllegalArgumentException("字段超出范围："+key);
        return result;
    }
    static boolean bool(JSONObject v,String key) throws Exception {
        if(!(v.opt(key) instanceof Boolean))throw new IllegalArgumentException("字段必须为开关："+key);
        return v.getBoolean(key);
    }
    static String one(String value,String key,String... choices) {
        for(String choice:choices)if(choice.equals(value))return value;
        throw new IllegalArgumentException("未知"+key);
    }
    static String uuid(String seed) {return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();}
    static String hash(String value) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    static JSONObject find(JSONArray rows,String key,String value) throws Exception {
        for(int i=0;i<rows.length();i++)if(value.equals(rows.getJSONObject(i).optString(key)))return rows.getJSONObject(i);
        return null;
    }
    static String canonical(Object value) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();
            java.util.Iterator<String> it=object.keys();while(it.hasNext())keys.add(it.next());java.util.Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");for(String key:keys)out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');return out.append('}').toString();
        }
        if(value instanceof JSONArray) {StringBuilder out=new StringBuilder("[");JSONArray rows=(JSONArray)value;for(int i=0;i<rows.length();i++)out.append(canonical(rows.get(i))).append(',');return out.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof String)return JSONObject.quote((String)value);
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number)return new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        throw new IllegalArgumentException("无法规范化的群数据类型");
    }
}
