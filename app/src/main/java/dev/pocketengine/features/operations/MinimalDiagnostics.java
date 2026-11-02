package dev.pocketengine.features.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

/** Whitelisted support facts. Raw task logs, URLs, account values and file bodies are never copied. */
public final class MinimalDiagnostics {
    private static final Set<String> FLAGS=Set.of("enabled","available","installed","canExecute","nativeSupervisorAvailable","configured","connected","permissionGranted","listening","acceptingNewRequests","busy");
    private static final Set<String> CATEGORIES=Set.of("status","state","runtimeId","lastErrorCategory","sourceCommit","versionName","versionCode","sdk","abi");
    public static final class Snapshot {
        public final String text,sha256;
        Snapshot(String text)throws Exception{this.text=text;byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));sha256=hex.toString();}
    }
    private MinimalDiagnostics() {}
    public static Snapshot capture(Map<String,Callable<JSONObject>> sources,long now)throws Exception{
        if(sources.size()>12)throw new IllegalArgumentException("诊断来源过多");JSONObject sections=new JSONObject();
        for(Map.Entry<String,Callable<JSONObject>> source:new LinkedHashMap<>(sources).entrySet()){
            if(!source.getKey().matches("[a-z][a-z_]{0,31}"))throw new IllegalArgumentException("诊断分项名称无效");
            try{sections.put(source.getKey(),safe(source.getValue().call()));}
            catch(Exception failure){sections.put(source.getKey(),new JSONObject().put("status","collection_failed").put("lastErrorCategory",failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]","")));}
        }
        JSONObject body=new JSONObject().put("format","pocketworld.minimal-diagnostics.v1").put("generatedAtMinute",now/60000*60000).put("sections",sections)
                .put("excluded",new JSONArray().put("task_and_chat_text").put("credentials_and_cookies").put("network_addresses_and_urls").put("file_contents").put("screenshots")).put("automaticSend",false);
        String rendered=body.toString(2);if(rendered.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalStateException("诊断内容超过限定大小，未改为导出原日志");return new Snapshot(rendered+"\n");
    }
    private static JSONObject safe(JSONObject input)throws Exception{
        JSONObject output=new JSONObject();if(input==null)return output.put("status","unavailable");
        for(String key:FLAGS)if(input.opt(key) instanceof Boolean)output.put(key,input.getBoolean(key));
        for(String key:CATEGORIES){Object value=input.opt(key);if(value instanceof Integer||value instanceof Long)output.put(key,value);else if(value instanceof String&&((String)value).matches("[A-Za-z0-9_-]{1,64}(?:\\.[A-Za-z0-9_-]{1,32}){0,3}")&&!((String)value).matches("[0-9]+(?:\\.[0-9]+){3}"))output.put(key,value);}
        JSONArray states=input.optJSONArray("recentStates");if(states!=null){JSONArray accepted=new JSONArray();for(int i=0;i<Math.min(5,states.length());i++){String state=states.optString(i);if(state.matches("queued|running|paused|stopping|cancelled|failed|completed|interrupted|awaiting_user|needs_confirmation|reconciling"))accepted.put(state);}output.put("recentStates",accepted);}
        return output;
    }
}
