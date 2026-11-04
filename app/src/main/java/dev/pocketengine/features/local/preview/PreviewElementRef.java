package dev.pocketengine.features.local.preview;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded owner observations. JSON never creates a native document, source grant or write authority. */
public final class PreviewElementRef {
    public static final int MAX_REFERENCES=4,MAX_SELECTOR=240,MAX_TEXT=1200,MAX_HTML=4000,MAX_TOTAL_CHARS=12000,MAX_UTF8_BYTES=65536;
    private static final Set<String> FIELDS=Set.of("referenceId","taskId","runId","epoch","conversationId","projectId","checkoutId","previewId","sourceDigest","documentId","documentGeneration","path","resourceSha256","selectorHint","text","htmlSnippet","bounds","truncated","untrusted","selectorIsSourceLocation");
    private static final Set<String> OBSERVATION=Set.of("selectorHint","text","htmlSnippet","bounds","truncated");
    private static final String[] OBSERVATION_FIELDS={"selectorHint","text","htmlSnippet","bounds","truncated"};
    private PreviewElementRef(){}

    public static JSONObject checked(JSONObject supplied)throws Exception {return checked(new JSONArray().put(supplied)).getJSONObject(0);}
    public static JSONArray checked(JSONArray supplied)throws Exception {
        if(supplied==null||supplied.length()>MAX_REFERENCES)throw new IllegalArgumentException("每条输入最多保留4个预览元素引用");
        JSONArray result=new JSONArray();Set<String> ids=new HashSet<>();int chars=0;
        for(int i=0;i<supplied.length();i++){
            Object value=supplied.opt(i);if(!(value instanceof JSONObject))throw invalid();JSONObject row=(JSONObject)value;keys(row,FIELDS);
            JSONObject copy=new JSONObject();
            for(String key:new String[]{"referenceId","taskId","runId","conversationId","projectId","checkoutId","documentId"})copy.put(key,string(row,key,2048,false));
            if(!ids.add(copy.getString("referenceId")))throw new IllegalArgumentException("相同预览引用不能重复加入");
            copy.put("epoch",integer(row,"epoch",Integer.MAX_VALUE)).put("documentGeneration",integer(row,"documentGeneration",Long.MAX_VALUE));
            for(String key:new String[]{"previewId","sourceDigest","resourceSha256"}){String hash=string(row,key,64,false);if(!hash.matches("[a-f0-9]{64}"))throw invalid();copy.put(key,hash);}
            copy.put("path",path(string(row,"path",240,false)));
            JSONObject observation=new JSONObject();for(String key:OBSERVATION_FIELDS)observation.put(key,row.get(key));
            JSONObject bounded=checkedObservation(observation);for(String key:OBSERVATION_FIELDS)copy.put(key,bounded.get(key));
            if(!Boolean.TRUE.equals(row.opt("untrusted"))||!Boolean.FALSE.equals(row.opt("selectorIsSourceLocation")))throw invalid();
            copy.put("untrusted",true).put("selectorIsSourceLocation",false);
            chars+=bounded.getString("selectorHint").length()+bounded.getString("text").length()+bounded.getString("htmlSnippet").length();
            if(chars>MAX_TOTAL_CHARS)throw new IllegalArgumentException("预览引用片段总长度超过12000字符");result.put(copy);
        }
        if(result.toString().getBytes(StandardCharsets.UTF_8).length>MAX_UTF8_BYTES)throw new IllegalArgumentException("预览引用数据超过64KiB");
        return result;
    }

    static JSONObject checkedObservation(JSONObject supplied)throws Exception {
        keys(supplied,OBSERVATION);
        JSONObject result=new JSONObject().put("selectorHint",string(supplied,"selectorHint",MAX_SELECTOR,true)).put("text",string(supplied,"text",MAX_TEXT,true)).put("htmlSnippet",string(supplied,"htmlSnippet",MAX_HTML,true));
        if(!(supplied.opt("bounds") instanceof JSONObject)||!(supplied.opt("truncated") instanceof JSONObject))throw invalid();
        JSONObject bounds=(JSONObject)supplied.get("bounds"),truncated=(JSONObject)supplied.get("truncated");
        keys(bounds,Set.of("x","y","width","height"));keys(truncated,Set.of("selectorHint","text","htmlSnippet"));
        JSONObject checkedBounds=new JSONObject(),checkedTruncated=new JSONObject();
        for(String key:new String[]{"x","y","width","height"}){
            Object raw=bounds.opt(key);if(!(raw instanceof Number))throw invalid();double number=((Number)raw).doubleValue();
            if(!Double.isFinite(number)||Math.abs(number)>10000000||(key.equals("width")||key.equals("height"))&&number<0)throw invalid();
            checkedBounds.put(key,number==0?0d:number);
        }
        for(String key:new String[]{"selectorHint","text","htmlSnippet"}){Object raw=truncated.opt(key);if(!(raw instanceof Boolean))throw invalid();checkedTruncated.put(key,raw);}
        return result.put("bounds",checkedBounds).put("truncated",checkedTruncated);
    }
    private static String string(JSONObject row,String key,int maximum,boolean empty){
        Object raw=row.opt(key);if(!(raw instanceof String))throw invalid();String text=(String)raw;
        if(text.length()>maximum||text.indexOf('\0')>=0||!StandardCharsets.UTF_8.newEncoder().canEncode(text)||!empty&&(text.isEmpty()||text.codePoints().allMatch(Character::isWhitespace)||text.codePoints().anyMatch(c->c<32)))throw invalid();return text;
    }
    private static long integer(JSONObject row,String key,long maximum){Object raw=row.opt(key);if(!(raw instanceof Integer||raw instanceof Long))throw invalid();long number=((Number)raw).longValue();if(number<1||number>maximum)throw invalid();return number;}
    /** Same relative-path value rules as VersionedWorkspace.path; this value type owns no workspace IO. */
    private static String path(String value){
        if(value.startsWith("/")||value.startsWith("\\")||value.contains("\\")||value.contains(":"))throw invalid();
        for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.chars().anyMatch(c->c<32))throw invalid();return value;
    }
    private static void keys(JSONObject row,Set<String> allowed){if(row==null||row.length()!=allowed.size())throw invalid();for(var keys=row.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("预览引用不是当前约定的有限原文元数据");}
}
