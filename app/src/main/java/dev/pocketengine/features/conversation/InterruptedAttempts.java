package dev.pocketengine.features.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only reconstruction of the original interrupted display prefix. Never an assistant answer. */
public final class InterruptedAttempts {
    public static final class Attempt {
        public final String callId,runId,text;
        public final int epoch,retainedChars,prefixLimit;
        public final boolean contiguous,conflicting,windowPartial;
        Attempt(String call,String run,int epoch,String text,int chars,int limit,boolean contiguous,boolean conflicting,boolean partial){this.callId=call;this.runId=run;this.epoch=epoch;this.text=text;this.retainedChars=chars;this.prefixLimit=limit;this.contiguous=contiguous;this.conflicting=conflicting;this.windowPartial=partial;}
    }
    private static final class Parts {
        String call,run;int epoch,limit=32768;boolean bad;
        final TreeMap<Integer,String> chunks=new TreeMap<>();
    }
    private InterruptedAttempts() {}
    public static List<Attempt> fromEvents(JSONArray events,boolean partial){
        Map<String,Parts> groups=new LinkedHashMap<>();
        for(int i=0;events!=null&&i<events.length();i++){
            JSONObject event=events.optJSONObject(i);if(event==null||!"model_attempt_interrupted".equals(event.optString("type")))continue;
            JSONObject receipt=event.optJSONObject("receipt");if(receipt==null||!Boolean.TRUE.equals(receipt.opt("provisional"))||!Boolean.FALSE.equals(receipt.opt("attemptCompleted")))continue;
            String call=receipt.optString("callId"),run=event.optString("runId");int epoch=event.optInt("coordinatorEpoch");
            String key=run+":"+epoch+":"+(call.isEmpty()?"unknown-event-"+i:call);Parts parts=groups.computeIfAbsent(key,k->new Parts());parts.call=call;parts.run=run;parts.epoch=epoch;
            Object raw=receipt.opt("offset"),body=receipt.opt("text");int limit=receipt.optInt("displayPrefixLimit",32768);
            if(!(raw instanceof Integer||raw instanceof Long)||!(body instanceof String)||limit<1||limit>32768||((Number)raw).longValue()<0||((Number)raw).longValue()>limit){parts.bad=true;continue;}
            int offset=((Number)raw).intValue();String text=(String)body;parts.limit=limit;
            if(text.length()>8000||text.length()>limit-offset){parts.bad=true;continue;}
            String previous=parts.chunks.putIfAbsent(offset,text);if(previous!=null&&!previous.equals(text))parts.bad=true;
        }
        List<Attempt> result=new ArrayList<>();
        for(Parts parts:groups.values()){
            StringBuilder text=new StringBuilder();int next=0,chars=0;boolean contiguous=!parts.bad;
            for(Map.Entry<Integer,String> chunk:parts.chunks.entrySet()){
                if(chunk.getKey()!=next){contiguous=false;text.append("\n〔… ").append(next).append("–").append(chunk.getKey()).append(" …〕\n");}
                if(chunk.getKey()<next){parts.bad=true;contiguous=false;continue;}
                text.append(chunk.getValue());chars+=chunk.getValue().length();next=chunk.getKey()+chunk.getValue().length();
            }
            result.add(new Attempt(parts.call,parts.run,parts.epoch,text.toString(),chars,parts.limit,contiguous,parts.bad,partial));
        }
        return List.copyOf(result);
    }
    public static JSONArray eventsFromAuditPage(JSONObject page)throws Exception{
        if(!"pocketworld.task-audit-page.v1".equals(page.optString("format"))||!"events".equals(page.optString("section")))throw new IllegalArgumentException("需要原任务事件页");
        JSONArray events=new JSONArray(),entries=page.getJSONArray("entries");
        for(int i=entries.length()-1;i>=0;i--){JSONObject entry=entries.getJSONObject(i);if("available".equals(entry.optString("status"))&&entry.optJSONObject("event")!=null)events.put(entry.getJSONObject("event"));}
        return events;
    }
}
