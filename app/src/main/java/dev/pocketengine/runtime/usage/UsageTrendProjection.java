package dev.pocketengine.runtime.usage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** A disposable read projection over original physical receipts. Never a usage or billing ledger. */
public final class UsageTrendProjection {
    public static final long MAX_RANGE_MILLIS=90L*24*60*60*1000;
    private UsageTrendProjection() { }

    public record Query(long fromInclusive,long toExclusive,String zoneId) {
        public Query {
            if(fromInclusive<1||toExclusive<=fromInclusive||toExclusive-fromInclusive>MAX_RANGE_MILLIS+2L*24*60*60*1000)
                throw new IllegalArgumentException("请选择不超过90天的明确用量范围");
            ZoneId zone=ZoneId.of(zoneId);
            if(ChronoUnit.DAYS.between(Instant.ofEpochMilli(fromInclusive).atZone(zone).toLocalDate(),
                    Instant.ofEpochMilli(toExclusive-1).atZone(zone).toLocalDate())>=90)
                throw new IllegalArgumentException("请选择不超过90个本地自然日的用量范围");
        }
        public JSONObject toJson()throws Exception{return new JSONObject().put("fromInclusive",fromInclusive)
                .put("toExclusive",toExclusive).put("zoneId",zoneId).put("timeBasis","physical_request_started_at");}
    }

    public static JSONObject fromReceipts(JSONArray original,Query query)throws Exception {
        return fromReceipts(original,query,Map.of());
    }
    /** Labels are optional metadata from the same verified private task headers. */
    public static JSONObject fromReceipts(JSONArray original,Query query,Map<String,JSONObject> taskHeaders)throws Exception {
        JSONObject canonical=InferenceProjection.fromEvents(original);
        JSONArray retained=canonical.getJSONArray("receipts"),included=new JSONArray(),references=new JSONArray();
        Map<String,JSONArray> buckets=new java.util.TreeMap<>();Map<String,JSONObject> identities=new LinkedHashMap<>();
        Map<String,JSONArray> daily=new java.util.TreeMap<>(),models=new LinkedHashMap<>(),purposes=new LinkedHashMap<>(),conversations=new LinkedHashMap<>();
        Map<String,JSONObject> modelIds=new LinkedHashMap<>(),conversationIds=new LinkedHashMap<>();
        java.util.Set<String> taskIds=new java.util.LinkedHashSet<>();
        int invalidTimes=0,conflicts=0;
        for(int i=0;i<retained.length();i++){
            JSONObject receipt=retained.getJSONObject(i);Long at=nonnegative(receipt.opt("startedAt"));
            if(at==null||at==0){invalidTimes++;continue;}
            if(at<query.fromInclusive||at>=query.toExclusive)continue;
            if(receipt.optBoolean("projectionConflict")){conflicts++;continue;}
            JSONObject route=receipt.getJSONObject("route"),context=receipt.getJSONObject("context");
            String day=Instant.ofEpochMilli(at).atZone(ZoneId.of(query.zoneId)).toLocalDate().toString();
            String provider=historical(route,"providerId",historical(route,"protocol","unknown"));
            String model=historical(route,"requestedModel","unknown");
            String key=new JSONArray().put(day).put(provider).put(model).toString();
            buckets.computeIfAbsent(key,k->new JSONArray()).put(receipt);
            identities.putIfAbsent(key,new JSONObject().put("day",day).put("provider",provider).put("model",model));
            daily.computeIfAbsent(day,k->new JSONArray()).put(receipt);
            String modelKey=new JSONArray().put(provider).put(model).toString();models.computeIfAbsent(modelKey,k->new JSONArray()).put(receipt);
            modelIds.putIfAbsent(modelKey,new JSONObject().put("provider",provider).put("model",model));
            String purpose=historical(context,"purpose","UNKNOWN");purposes.computeIfAbsent(purpose,k->new JSONArray()).put(receipt);
            JSONObject header=taskHeaders.get(context.getString("taskId"));
            String conversation=header==null?"":header.optString("conversationId");
            if(conversation.matches("[a-fA-F0-9-]{36}")){
                conversations.computeIfAbsent(conversation,k->new JSONArray()).put(receipt);
                JSONObject existing=conversationIds.get(conversation);
                if(existing==null||at>existing.optLong("lastCallAt"))conversationIds.put(conversation,new JSONObject().put("conversationId",conversation)
                        .put("taskId",context.getString("taskId")).put("title",JSONObject.NULL)
                        .put("latestTaskLabel",label(header.optString("goal"))).put("lastCallAt",at));
            }
            included.put(receipt);taskIds.add(context.getString("taskId"));
            if(references.length()<128)references.put(new JSONObject().put("taskId",context.getString("taskId"))
                    .put("runId",context.getString("runId")).put("epoch",context.getInt("epoch"))
                    .put("callId",receipt.getString("callId")).put("outcome",receipt.getString("outcome")));
        }
        JSONArray rows=new JSONArray();for(var entry:buckets.entrySet())rows.put(summary(entry.getValue())
                .put("identity",identities.get(entry.getKey())));
        JSONArray dailyRows=new JSONArray(),modelRows=new JSONArray(),purposeRows=new JSONArray(),conversationRows=new JSONArray();
        for(var entry:daily.entrySet())dailyRows.put(summary(entry.getValue()).put("day",entry.getKey()).put("identity",new JSONObject().put("day",entry.getKey())));
        for(var entry:models.entrySet()){JSONObject identity=modelIds.get(entry.getKey());modelRows.put(summary(entry.getValue()).put("identity",identity).put("provider",identity.getString("provider")).put("model",identity.getString("model")));}
        for(var entry:purposes.entrySet())purposeRows.put(summary(entry.getValue()).put("purpose",entry.getKey()).put("identity",new JSONObject().put("purpose",entry.getKey())));
        java.util.List<JSONObject> orderedConversations=new java.util.ArrayList<>();
        for(var entry:conversations.entrySet()){JSONObject identity=conversationIds.get(entry.getKey());orderedConversations.add(summary(entry.getValue()).put("identity",identity)
                .put("conversationId",identity.getString("conversationId")).put("taskId",identity.getString("taskId")).put("title",JSONObject.NULL)
                .put("latestTaskLabel",identity.getString("latestTaskLabel")).put("lastCallAt",identity.getLong("lastCallAt")));}
        orderedConversations.sort((a,b)->Long.compare(knownTotal(b),knownTotal(a)));
        for(int i=0;i<Math.min(50,orderedConversations.size());i++)conversationRows.put(orderedConversations.get(i));
        return new JSONObject().put("format","pocketworld.usage-trends.v1").put("query",query.toJson())
                .put("scope","owner/private_supported_local_origins_only")
                .put("totals",summary(included)).put("days",rows).put("daily",dailyRows).put("byModel",modelRows).put("byPurpose",purposeRows)
                .put("byConversation",conversationRows).put("conversationsLimited",orderedConversations.size()>50).put("taskCount",taskIds.size())
                .put("callReferences",references).put("referencesLimited",included.length()>references.length())
                .put("conflictingCalls",conflicts).put("invalidReceipts",canonical.getInt("invalidReceipts"))
                .put("invalidTimestamps",invalidTimes).put("actualCost",JSONObject.NULL)
                .put("actualCostSource","not_observed").put("subscriptionRemaining",JSONObject.NULL)
                .put("coverage","retained_original_physical_receipts_only")
                .put("emptyMeaning","no_observed_receipts_not_zero_actual_usage")
                .put("cacheMeaning","cache_and_reasoning_are_details_never_added_to_provider_totals")
                .put("readOnly",true).put("executionAuthorized",false);
    }

    private static JSONObject summary(JSONArray receipts)throws Exception {
        return UsageSummary.fromReceipts(receipts);
    }
    private static String label(String value){return value.codePointCount(0,value.length())>100?value.substring(0,value.offsetByCodePoints(0,100))+"…":value;}
    private static long knownTotal(JSONObject row){JSONObject normalized=row.optJSONObject("normalized"),total=normalized==null?null:normalized.optJSONObject("total");return total==null?0:total.optLong("knownSubtotal");}
    private static String historical(JSONObject route,String key,String fallback){Object value=route.opt(key);return value instanceof String&&((String)value).length()<=200&&!((String)value).isEmpty()?(String)value:fallback;}
    private static Long nonnegative(Object raw){try{if(!(raw instanceof Number))return null;long value=new java.math.BigDecimal(raw.toString()).longValueExact();return value<0?null:value;}catch(ArithmeticException|NumberFormatException invalid){return null;}}
}
