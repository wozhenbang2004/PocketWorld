package dev.pocketengine.features.semantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class SemanticJson {
    private SemanticJson() { }
    static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static void keys(JSONObject value, Set<String> allowed) {
        for (java.util.Iterator<String> it=value.keys();it.hasNext();) {
            if (!allowed.contains(it.next())) throw new IllegalArgumentException("语义入口不接受额外参数或宿主身份");
        }
    }
    static String text(JSONObject value,String key,int limit,boolean empty) throws Exception {
        Object raw=value.opt(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException("语义入口缺少文字参数："+key);
        String text=(String)raw;
        if ((!empty&&text.trim().isEmpty())||text.length()>limit||text.matches("(?s).*[\\p{Cntrl}&&[^\\n\\t]].*"))
            throw new IllegalArgumentException("语义入口文字参数无效："+key);
        return text;
    }
    static void uuid(String value) {
        if (value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("需要当前执行发现的入口引用");
    }
    static String hash(JSONObject value) throws Exception { return hash(canonical(value).getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] value) throws Exception {
        StringBuilder out=new StringBuilder();
        for (byte b:MessageDigest.getInstance("SHA-256").digest(value)) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");
            for (String key:keys) { if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))); }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");
            for (int i=0;i<array.length();i++) { if(i>0)out.append(',');out.append(canonical(array.get(i))); }
            return out.append(']').toString();
        }
        return value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
}
