package dev.pocketengine.store;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import org.json.*;

/** Stateless formatting of native admission facts. The original TaskStore row
 * remains the authority; this class does not read a device, grant or history. */
final class OwnerInputClock {
    private static final String FORMAT="pocketworld.owner-input-clock.v1",SOURCE="native_phone_owner_admission";
    static JSONObject capture(long receivedAt)throws Exception {
        JSONObject fact=new JSONObject().put("format",FORMAT).put("source",SOURCE).put("receivedAtEpochMillis",receivedAt)
                .put("receivedAtUtc",Instant.ofEpochMilli(receivedAt).toString()).put("ianaZoneId",JSONObject.NULL).put("utcOffset",JSONObject.NULL);
        try{ZoneId zone=ZoneId.systemDefault();if(!ZoneId.getAvailableZoneIds().contains(zone.getId()))return fact.put("zoneStatus","missing_iana");
            return fact.put("zoneStatus","available").put("ianaZoneId",zone.getId()).put("utcOffset",Instant.ofEpochMilli(receivedAt).atZone(zone).getOffset().getId());
        }catch(RuntimeException unavailable){return fact.put("zoneStatus","unavailable");}
    }
    static JSONObject project(JSONObject task,List<String> included,boolean phone)throws Exception {
        Set<String> selected=new LinkedHashSet<>(included);Map<String,JSONObject> rows=new HashMap<>();JSONArray inputs=task.optJSONArray("ownerInputs");
        if(inputs!=null&&inputs.length()>64)throw new SecurityException("原输入时间来源超过原邮箱界限");
        for(int i=0;inputs!=null&&i<inputs.length();i++){JSONObject row=inputs.getJSONObject(i);if(selected.contains(row.optString("messageId")))rows.put(row.getString("messageId"),row);}
        Map<String,JSONObject> zones=new TreeMap<>();int missing=0,known=0;
        for(String id:selected){JSONObject row=rows.get(id);if(row!=null&&add(zones,row.optJSONObject("inputOriginClock"),row.optLong("acceptedAt",Long.MIN_VALUE)))known++;else missing++;}
        if(phone){if(add(zones,task.optJSONObject("goalOriginClock"),task.optLong("createdAt",Long.MIN_VALUE)))known++;else missing++;}
        if(selected.isEmpty()&&!phone)missing=1; // No source is never the executor's current zone.
        String status=zones.size()>1?"mixed":missing>0||zones.isEmpty()?"missing":"unique";JSONArray projected=new JSONArray();for(JSONObject zone:zones.values())projected.put(zone);
        return new JSONObject().put("format","pocketworld.input-origin-clock-context.v1").put("source","original_task_native_owner_admissions")
                .put("status",status).put("knownInputCount",known).put("missingInputCount",missing).put("zones",projected)
                .put("canonicalZoneId","unique".equals(status)?zones.keySet().iterator().next():JSONObject.NULL)
                .put("admissionFactsOnly",true).put("executionAuthorized",false).put("schedulesChanged",false);
    }
    private static boolean add(Map<String,JSONObject> zones,JSONObject fact,long acceptedAt)throws Exception {
        if(fact==null||!FORMAT.equals(fact.optString("format"))||!SOURCE.equals(fact.optString("source"))||!"available".equals(fact.optString("zoneStatus"))||acceptedAt<0||fact.optLong("receivedAtEpochMillis",Long.MIN_VALUE)!=acceptedAt)return false;
        String id=fact.optString("ianaZoneId");if(!ZoneId.getAvailableZoneIds().contains(id))return false;
        Instant instant=Instant.ofEpochMilli(acceptedAt);String utc=instant.toString(),offset=instant.atZone(ZoneId.of(id)).getOffset().getId();
        if(!utc.equals(fact.optString("receivedAtUtc"))||!offset.equals(fact.optString("utcOffset")))return false;
        JSONObject zone=zones.get(id);if(zone==null){zone=new JSONObject().put("ianaZoneId",id).put("inputCount",0).put("firstReceivedAtEpochMillis",acceptedAt).put("lastReceivedAtEpochMillis",acceptedAt);zones.put(id,zone);}
        long first=Math.min(zone.getLong("firstReceivedAtEpochMillis"),acceptedAt),last=Math.max(zone.getLong("lastReceivedAtEpochMillis"),acceptedAt);
        zone.put("inputCount",zone.getInt("inputCount")+1).put("firstReceivedAtEpochMillis",first).put("lastReceivedAtEpochMillis",last)
                .put("firstReceivedAtUtc",Instant.ofEpochMilli(first).toString()).put("lastReceivedAtUtc",Instant.ofEpochMilli(last).toString());return true;
    }
}
