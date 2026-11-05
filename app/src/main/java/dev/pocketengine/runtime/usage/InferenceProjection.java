package dev.pocketengine.runtime.usage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only derivation from the original TaskStore events. No cache, counter ledger or task mutation. */
public final class InferenceProjection {
    private InferenceProjection(){}
    public static JSONObject fromEvents(JSONArray events)throws Exception{
        Map<String,JSONObject> unique=new LinkedHashMap<>();Set<String> conflict=new java.util.HashSet<>();
        int invalid=0;
        if(events!=null)for(int i=0;i<events.length();i++){
            JSONObject event=events.optJSONObject(i);
            if(event==null||!"inference_receipt".equals(event.optString("type")))continue;
            String id=event.optString("callId");
            if(event.optInt("receiptVersion")!=1||!id.matches("[a-f0-9-]{36}")||!event.optBoolean("evidenceOnly")
                    ||event.optJSONObject("context")==null||event.optJSONObject("route")==null||event.optJSONObject("usage")==null
                    ||!Set.of("STARTED","COMPLETED","FAILED","CANCELLED").contains(event.optString("outcome"))){invalid++;continue;}
            JSONObject previous=unique.get(id);
            if(previous!=null){
                if(!same(previous.optJSONObject("context"),event.optJSONObject("context"))
                        ||!sameSelectedRoute(previous.optJSONObject("route"),event.optJSONObject("route"))
                        ||previous.optLong("startedAt")!=event.optLong("startedAt")){conflict.add(id);continue;}
                if(!"STARTED".equals(previous.optString("outcome"))){
                    if(!"STARTED".equals(event.optString("outcome"))&&(!same(previous.getJSONObject("route"),event.getJSONObject("route"))||!previous.optString("outcome").equals(event.optString("outcome"))
                            ||!same(previous.optJSONObject("usage"),event.optJSONObject("usage"))||!canonical(previous.opt("failure")).equals(canonical(event.opt("failure")))))conflict.add(id);
                    continue;
                }
            }
            unique.put(id,new JSONObject(event.toString()));
        }
        JSONArray receipts=new JSONArray(),groups=new JSONArray();Map<String,Group> byGroup=new LinkedHashMap<>();
        int completed=0,failed=0,cancelled=0,started=0;
        for(Map.Entry<String,JSONObject> entry:unique.entrySet()){
            JSONObject receipt=entry.getValue();
            if(conflict.contains(entry.getKey())){receipt.put("projectionConflict",true);receipts.put(receipt);continue;}
            receipts.put(receipt);
            switch(receipt.getString("outcome")){case "COMPLETED":completed++;break;case "FAILED":failed++;break;case "CANCELLED":cancelled++;break;default:started++;}
            JSONObject context=receipt.getJSONObject("context"),route=receipt.getJSONObject("route"),usage=receipt.getJSONObject("usage");
            JSONObject groupedRoute=new JSONObject(route.toString());groupedRoute.remove("providerResponseId");groupedRoute.remove("providerItemId");
            // Logical control does not fragment or duplicate the original physical route totals.
            for(String field:new String[]{"logicalRequestId","attemptNo","candidateId","requestedAlias","ingressProtocol","egressProtocol","retryReason","deadlineAt","historyDigest","requestOptions","requestOptionsSource"})groupedRoute.remove(field);
            JSONObject identity=new JSONObject().put("route",groupedRoute).put("purpose",context.optString("purpose"))
                    .put("memberId",context.optString("memberId")).put("protocol",usage.optString("protocol"));
            String key=canonical(identity);Group group=byGroup.get(key);
            if(group==null){group=new Group(identity);byGroup.put(key,group);}group.add(usage);
        }
        for(Group group:byGroup.values())groups.put(group.json());
        return new JSONObject().put("version",1).put("attempts",unique.size()).put("completed",completed).put("failed",failed)
                .put("coverage","observed_receipts_only").put("emptyMeaning","no_accounting_records_not_zero_actual_usage")
                .put("cancelled",cancelled).put("unfinished",started).put("conflictingCalls",conflict.size()).put("invalidReceipts",invalid)
                .put("receipts",receipts).put("groups",groups).put("actualCost",JSONObject.NULL).put("actualCostSource","unknown")
                .put("estimatedCost",JSONObject.NULL).put("estimatedCostSource","not_calculated")
                .put("subscriptionRemaining",JSONObject.NULL).put("money",InferenceMoneyProjection.fromReceipts(receipts)).put("evidenceOnly",true)
                .put("meaning","provider_reported_best_effort_not_a_bill_or_context_occupancy");
    }
    private static boolean same(JSONObject first,JSONObject second)throws Exception{return canonical(first).equals(canonical(second));}
    private static boolean sameSelectedRoute(JSONObject first,JSONObject second)throws Exception{
        JSONObject left=new JSONObject(first.toString()),right=new JSONObject(second.toString());left.remove("responseModel");right.remove("responseModel");left.remove("historyDigest");right.remove("historyDigest");return same(left,right);
    }
    private static String canonical(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");for(String key:keys)out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');return out.append('}').toString();}
        if(value instanceof JSONArray){StringBuilder out=new StringBuilder("[");JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)out.append(canonical(array.get(i))).append(',');return out.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof String)return JSONObject.quote((String)value);
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        throw new IllegalArgumentException("unsupported_receipt_value");
    }
    private static final class Group {
        final JSONObject identity;
        final Map<ProviderUsage.Counter,Sum> sums=new LinkedHashMap<>();int calls;
        Group(JSONObject identity){this.identity=identity;for(ProviderUsage.Counter key:ProviderUsage.Counter.values())sums.put(key,new Sum());}
        void add(JSONObject usage){
            calls++;JSONObject counters=usage.optJSONObject("counters");
            for(Map.Entry<ProviderUsage.Counter,Sum> entry:sums.entrySet()){
                JSONObject count=counters==null?null:counters.optJSONObject(entry.getKey().name());Sum sum=entry.getValue();
                Object value=count==null?null:count.opt("value");
                if(count==null||!"provider_reported".equals(count.optString("source"))||!(value instanceof Number)){sum.unknown++;continue;}
                try{long number=new java.math.BigDecimal(value.toString()).longValueExact();if(number<0)throw new ArithmeticException();
                    sum.total=Math.addExact(sum.total,number);sum.known++;
                    if(!"FINAL".equals(count.optString("stage")))sum.partial++;
                }catch(ArithmeticException|NumberFormatException invalid){sum.overflow=true;sum.unknown++;}
            }
        }
        JSONObject json()throws Exception{
            JSONObject counts=new JSONObject();for(Map.Entry<ProviderUsage.Counter,Sum> entry:sums.entrySet()){
                Sum sum=entry.getValue();counts.put(entry.getKey().name(),new JSONObject().put("knownSubtotal",sum.known==0||sum.overflow?JSONObject.NULL:sum.total)
                        .put("reportedCalls",sum.known).put("unknownCalls",sum.unknown).put("partialCalls",sum.partial).put("overflow",sum.overflow));
            }
            String protocol=identity.optString("protocol");
            return new JSONObject(identity.toString()).put("attempts",calls).put("counters",counts)
                    .put("inputIncludesCacheRead",!protocol.equals("anthropic")).put("inputIncludesCacheWrite",!protocol.equals("anthropic"))
                    .put("outputIncludesReasoning",protocol.startsWith("openai_realtime")?JSONObject.NULL:!protocol.equals("gemini"));
        }
    }
    private static final class Sum {long total;int known,unknown,partial;boolean overflow;}
}
