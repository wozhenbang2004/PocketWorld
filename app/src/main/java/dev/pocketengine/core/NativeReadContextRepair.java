package dev.pocketengine.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** One request-only repair from already delivered native safe data. No tool, approval or history write. */
public final class NativeReadContextRepair implements AutoCloseable {
    public static final int MAX_REPLACEMENTS=8,MAX_BYTES=128*1024;
    public record Identity(String taskId,String runId,int epoch) {
        public Identity {if(taskId==null||taskId.isEmpty()||taskId.length()>200||runId==null||runId.isEmpty()||runId.length()>200||epoch<1)throw new IllegalArgumentException("native_read_context_identity_invalid");}
    }
    public static final class Replacement {
        public final String toolCallId,expectedContentSha256;private final String fullSafeContent;
        public Replacement(String toolCallId,String expectedContentSha256,String fullSafeContent){
            if(toolCallId==null||toolCallId.isEmpty()||toolCallId.length()>200||expectedContentSha256==null||!expectedContentSha256.matches("[a-f0-9]{64}")||fullSafeContent==null||fullSafeContent.length()>MAX_BYTES||fullSafeContent.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("native_read_context_replacement_invalid");
            this.toolCallId=toolCallId;this.expectedContentSha256=expectedContentSha256;this.fullSafeContent=fullSafeContent;
        }
    }
    /** C5 owns the exact native full/delta/base schema. This callback may only validate a copy. */
    @FunctionalInterface public interface FinalCheck {void verify(JSONArray finalMessages,RunControl original)throws Exception;}
    public static final class Unavailable extends IllegalStateException {
        public Unavailable(){super("当前模型上下文无法完整核对这次网页差分的基线；请缩小范围或用完整读取重新同步。本次主模型请求未派发；已有整理调用与真实工具回执保留，没有重读网页或重放动作。");}
    }
    private final RunControl original;private final Identity identity;private final long pause;private final RunControl.AuthorityCheck current;private final FinalCheck finalCheck;
    private List<Replacement> replacements;private boolean closed,taken,validated;
    private NativeReadContextRepair(RunControl original,Identity identity,List<Replacement> supplied,RunControl.AuthorityCheck current,FinalCheck finalCheck)throws Exception{
        this.original=Objects.requireNonNull(original);this.identity=Objects.requireNonNull(identity);this.current=Objects.requireNonNull(current);this.finalCheck=Objects.requireNonNull(finalCheck);pause=original.pauseRevision();
        if(supplied==null||supplied.isEmpty()||supplied.size()>MAX_REPLACEMENTS)throw new IllegalArgumentException("native_read_context_replacement_limit");
        List<Replacement> copy=new ArrayList<>(supplied.size());Set<String> ids=new HashSet<>();int bytes=0;
        for(Replacement item:supplied){Objects.requireNonNull(item);if(!ids.add(item.toolCallId)||(bytes=Math.addExact(bytes,item.fullSafeContent.getBytes(StandardCharsets.UTF_8).length))>MAX_BYTES)throw new IllegalArgumentException("native_read_context_replacement_limit");copy.add(item);}
        replacements=Collections.unmodifiableList(copy);require(original);
    }
    public static NativeReadContextRepair capture(RunControl original,Identity identity,List<Replacement> replacements,RunControl.AuthorityCheck current,FinalCheck finalCheck)throws Exception{return new NativeReadContextRepair(original,identity,replacements,current,finalCheck);}
    private void require(RunControl actual)throws Exception{
        if(actual!=original||!actual.matchesHostRun(identity.taskId(),identity.runId(),identity.epoch())||actual.isPaused()||actual.pauseRevision()!=pause)throw new Unavailable();
        actual.check();current.check();actual.check();if(actual.isPaused()||actual.pauseRevision()!=pause)throw new Unavailable();synchronized(this){if(closed)throw new Unavailable();}
    }
    /** Only original assistant/tool pairs may change, and only their content string. */
    public JSONArray consume(JSONArray finalMessages,RunControl actual)throws Exception{
        require(actual);final List<Replacement> chosen;synchronized(this){if(taken||closed)throw new Unavailable();taken=true;chosen=replacements;}
        try{
            JSONArray out=boundedCopy(finalMessages);Map<String,String> tools=new HashMap<>();Set<String> repeated=new HashSet<>();Map<String,JSONObject> rows=new HashMap<>();
            for(int i=0;i<out.length();i++){JSONObject row=out.getJSONObject(i);
                if("assistant".equals(row.optString("role"))){JSONArray calls=row.optJSONArray("tool_calls");for(int j=0;calls!=null&&j<calls.length();j++){JSONObject call=calls.getJSONObject(j);String id=call.getString("id");if(tools.putIfAbsent(id,call.getJSONObject("function").getString("name"))!=null)repeated.add(id);}}
                else if("tool".equals(row.optString("role"))){String id=row.getString("tool_call_id");if(!tools.containsKey(id)||rows.putIfAbsent(id,row)!=null)repeated.add(id);}
            }
            for(Replacement item:chosen){JSONObject row=rows.get(item.toolCallId);if(row==null||repeated.contains(item.toolCallId)||!"browser_task_observe".equals(tools.get(item.toolCallId))||!(row.opt("content") instanceof String)||!item.expectedContentSha256.equals(sha256(row.getString("content"))))throw new Unavailable();row.put("content",item.fullSafeContent);}
            require(actual);return out;
        }catch(Exception invalid){close();throw invalid;}
    }
    /** Exactly once after the original budget has selected the repaired final wire. */
    public void validateFinal(JSONArray finalMessages,RunControl actual)throws Exception{
        require(actual);synchronized(this){if(!taken||validated||closed)throw new Unavailable();validated=true;}
        try{finalCheck.verify(boundedCopy(finalMessages),actual);require(actual);}catch(Exception invalid){close();throw invalid;}
    }
    private static JSONArray boundedCopy(JSONArray messages)throws Exception{
        if(messages==null||messages.length()>1024)throw new Unavailable();String text=messages.toString();if(text.length()>2*1024*1024||text.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new Unavailable();return new JSONArray(text);
    }
    private static String sha256(String text)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder(64);for(byte value:digest)hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();}
    @Override public synchronized void close(){closed=true;replacements=Collections.emptyList();}
}
