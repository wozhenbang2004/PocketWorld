package dev.pocketengine.runtime.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
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
            if(fromInclusive<1||toExclusive<=fromInclusive||toExclusive-fromInclusive>MAX_RANGE_MILLIS)
                throw new IllegalArgumentException("请选择不超过90天的明确用量范围");
            ZoneId.of(zoneId);
        }
        public JSONObject toJson()throws Exception{return new JSONObject().put("fromInclusive",fromInclusive)
                .put("toExclusive",toExclusive).put("zoneId",zoneId).put("timeBasis","physical_request_started_at");}
    }

    public static JSONObject fromReceipts(JSONArray original,Query query)throws Exception {
        JSONObject canonical=InferenceProjection.fromEvents(original);
        JSONArray retained=canonical.getJSONArray("receipts"),included=new JSONArray(),references=new JSONArray();
        Map<String,JSONArray> buckets=new java.util.TreeMap<>();Map<String,JSONObject> identities=new LinkedHashMap<>();
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
            included.put(receipt);taskIds.add(context.getString("taskId"));
            if(references.length()<128)references.put(new JSONObject().put("taskId",context.getString("taskId"))
                    .put("runId",context.getString("runId")).put("epoch",context.getInt("epoch"))
                    .put("callId",receipt.getString("callId")).put("outcome",receipt.getString("outcome")));
        }
        JSONArray rows=new JSONArray();for(var entry:buckets.entrySet())rows.put(summary(entry.getValue())
                .put("identity",identities.get(entry.getKey())));
        return new JSONObject().put("format","pocketworld.usage-trends.v1").put("query",query.toJson())
                .put("scope","owner/private_supported_local_origins_only")
                .put("totals",summary(included)).put("days",rows).put("taskCount",taskIds.size())
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
        JSONObject counts=new JSONObject();
        for(ProviderUsage.Counter counter:ProviderUsage.Counter.values()){
            long sum=0;int known=0,unknown=0,partial=0;boolean overflow=false;
            for(int i=0;i<receipts.length();i++){
                JSONObject usage=receipts.getJSONObject(i).optJSONObject("usage"),counters=usage==null?null:usage.optJSONObject("counters");
                JSONObject item=counters==null?null:counters.optJSONObject(counter.name());
                Long value=item!=null&&"provider_reported".equals(item.optString("source"))?nonnegative(item.opt("value")):null;
                if(value==null){unknown++;continue;}known++;if(!"FINAL".equals(item.optString("stage")))partial++;
                try{sum=Math.addExact(sum,value);}catch(ArithmeticException tooLarge){overflow=true;}
            }
            counts.put(counter.name(),new JSONObject().put("knownSubtotal",known==0||overflow?JSONObject.NULL:sum)
                    .put("reportedCalls",known).put("unknownCalls",unknown).put("partialCalls",partial).put("overflow",overflow));
        }
        JSONObject outcomes=new JSONObject();for(String state:new String[]{"COMPLETED","FAILED","CANCELLED","STARTED"})outcomes.put(state,0);
        for(int i=0;i<receipts.length();i++){String state=receipts.getJSONObject(i).getString("outcome");outcomes.put(state,outcomes.getInt(state)+1);}
        return new JSONObject().put("attempts",receipts.length()).put("counters",counts).put("outcomes",outcomes)
                .put("money",InferenceMoneyProjection.fromReceipts(receipts));
    }
    private static String historical(JSONObject route,String key,String fallback){Object value=route.opt(key);return value instanceof String&&((String)value).length()<=200&&!((String)value).isEmpty()?(String)value:fallback;}
    private static Long nonnegative(Object raw){try{if(!(raw instanceof Number))return null;long value=new BigDecimal(raw.toString()).longValueExact();return value<0?null:value;}catch(ArithmeticException|NumberFormatException invalid){return null;}}
}
