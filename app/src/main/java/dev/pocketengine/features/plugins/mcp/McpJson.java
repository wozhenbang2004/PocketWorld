package dev.pocketengine.features.plugins.mcp;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded strict JSON for identity-bearing protocol messages; duplicate names are ambiguous. */
public final class McpJson {
    private static final int MAX_CANONICAL_NUMBER_CHARS=4096;
    private McpJson() { }
    public static JSONObject parse(String source)throws Exception {
        try(JsonReader reader=new JsonReader(new StringReader(source))){
            reader.setLenient(false);
            if(reader.peek()!=JsonToken.BEGIN_OBJECT)throw new IllegalArgumentException("Expected JSON object");
            JSONObject value=(JSONObject)read(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("Trailing JSON");
            return value;
        }
    }
    private static Object read(JsonReader reader,int depth)throws Exception {
        if(depth>32)throw new IllegalArgumentException("JSON nesting limit");
        switch(reader.peek()){
            case BEGIN_OBJECT:
                JSONObject object=new JSONObject();Set<String> names=new HashSet<>();reader.beginObject();
                while(reader.hasNext()){
                    String name=reader.nextName();if(!names.add(name))throw new IllegalArgumentException("Duplicate JSON name");
                    object.put(name,read(reader,depth+1));
                }
                reader.endObject();return object;
            case BEGIN_ARRAY:
                JSONArray array=new JSONArray();reader.beginArray();while(reader.hasNext())array.put(read(reader,depth+1));reader.endArray();return array;
            case STRING:return reader.nextString();
            case NUMBER:return new BigDecimal(reader.nextString());
            case BOOLEAN:return reader.nextBoolean();
            case NULL:reader.nextNull();return JSONObject.NULL;
            default:throw new IllegalArgumentException("Invalid JSON value");
        }
    }
    public static String digest(Object value)throws Exception {return sha256(canonical(value,0));}
    public static String sha256(String text)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();
        for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b));return out.toString();
    }
    private static String canonical(Object value,int depth)throws Exception {
        if(depth>32)throw new IllegalArgumentException("JSON nesting limit");
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key),depth+1));}return out.append('}').toString();
        }
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i),depth+1));}return out.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof Number){
            BigDecimal number=new BigDecimal(value.toString());
            if(number.signum()==0)return "0";
            // Check integer expansion before trailing-zero normalization can underflow its scale.
            if((long)number.precision()-number.scale()>MAX_CANONICAL_NUMBER_CHARS)throw new IllegalArgumentException("Canonical JSON number limit");
            number=number.stripTrailingZeros();
            long precision=number.precision(),scale=number.scale();
            long length=scale<=0?precision-scale:Math.max(precision+1,scale+2);
            if(number.signum()<0)length++;
            if(length>MAX_CANONICAL_NUMBER_CHARS)throw new IllegalArgumentException("Canonical JSON number limit");
            return number.toPlainString();
        }
        if(value instanceof Boolean)return value.toString();
        if(value instanceof String)return JSONObject.quote((String)value);
        throw new IllegalArgumentException("Invalid JSON type");
    }
    public static long interval(Object value,long fallback){
        if(value==null||value==JSONObject.NULL)return fallback;
        try {long n=new BigDecimal(value.toString()).longValueExact();if(!(value instanceof Number)||n<0||n>31_536_000_000L)throw new IllegalArgumentException();return n;}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid or unsupported interval");}
    }
}
