package dev.pocketengine.runtime.usage;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Comparable, disposable display totals over canonical physical-call receipts. */
public final class UsageSummary {
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private UsageSummary() { }
    private record Value(long tokens,boolean partial,String source) {
        Value(long tokens,boolean partial){this(tokens,partial,"provider_reported");}
    }

    public static JSONObject fromReceipts(JSONArray receipts)throws Exception {
        Map<String,Sum> raw=new LinkedHashMap<>(),normalized=new LinkedHashMap<>();
        for(ProviderUsage.Counter key:ProviderUsage.Counter.values())raw.put(key.name(),new Sum());
        for(String key:new String[]{"input","output","total","cacheRead","cacheWrite","reasoning"})normalized.put(key,new Sum());
        JSONObject outcomes=new JSONObject();for(String key:new String[]{"COMPLETED","FAILED","CANCELLED","STARTED"})outcomes.put(key,0);
        long cacheRead=0,cacheInput=0;int eligible=0,unknownCache=0,partialCache=0,attempts=0,conflicts=0;boolean cacheOverflow=false;
        for(int i=0;i<receipts.length();i++){
            JSONObject receipt=receipts.getJSONObject(i);
            if(receipt.optBoolean("projectionConflict")){conflicts++;continue;}
            attempts++;String state=receipt.optString("outcome");if(outcomes.has(state))outcomes.put(state,outcomes.getInt(state)+1);
            JSONObject usage=receipt.optJSONObject("usage");
            for(var entry:raw.entrySet())entry.getValue().add(counter(usage,entry.getKey()));
            Value input=input(usage),output=output(usage),read=counter(usage,"CACHE_READ"),write=counter(usage,"CACHE_WRITE"),reasoning=counter(usage,"REASONING");
            Value total=counter(usage,"TOTAL");if(total==null)total=add(input,output);
            normalized.get("input").add(input);normalized.get("output").add(output);normalized.get("total").add(total);
            normalized.get("cacheRead").add(read);normalized.get("cacheWrite").add(write);normalized.get("reasoning").add(reasoning);
            // Weighted by input tokens in the same observed calls; missing cache reports
            // never turn into misses, and an invalid numerator is not silently clamped.
            if(input==null||read==null||read.tokens>input.tokens){unknownCache++;continue;}
            eligible++;if(input.partial||read.partial)partialCache++;
            try{cacheRead=Math.addExact(cacheRead,read.tokens);cacheInput=Math.addExact(cacheInput,input.tokens);
                if(cacheRead>MAX_SAFE_INTEGER||cacheInput>MAX_SAFE_INTEGER)cacheOverflow=true;
            }catch(ArithmeticException tooLarge){cacheOverflow=true;}
        }
        JSONObject rawJson=new JSONObject(),normalizedJson=new JSONObject();
        for(var entry:raw.entrySet())rawJson.put(entry.getKey(),entry.getValue().json());
        for(var entry:normalized.entrySet())normalizedJson.put(entry.getKey(),entry.getValue().json());
        JSONObject cache=new JSONObject().put("rate",eligible==0||cacheOverflow||cacheInput==0?JSONObject.NULL:(double)cacheRead/cacheInput)
                .put("readTokens",eligible==0||cacheOverflow?JSONObject.NULL:cacheRead).put("inputTokens",eligible==0||cacheOverflow?JSONObject.NULL:cacheInput)
                .put("eligibleCalls",eligible).put("unknownCalls",unknownCache).put("partialCalls",partialCache).put("overflow",cacheOverflow)
                .put("meaning","cache_read_tokens_over_complete_input_tokens_in_the_same_observed_calls");
        return new JSONObject().put("attempts",attempts).put("conflictingCalls",conflicts).put("counters",rawJson)
                .put("normalized",normalizedJson).put("cache",cache).put("outcomes",outcomes)
                .put("money",InferenceMoneyProjection.fromReceipts(receipts))
                .put("normalization","input_includes_cache_reads_and_writes_output_includes_reported_reasoning_total_prefers_provider_total")
                .put("coverage","retained_original_physical_receipts_only");
    }

    private static Value input(JSONObject usage){
        Value value=counter(usage,"INPUT");if(usage==null)return null;
        for(String kind:new String[]{"Read","Write"}){
            Object included=usage.opt("inputIncludesCache"+kind);
            if(Boolean.FALSE.equals(included))value=add(value,counter(usage,"CACHE_"+kind.toUpperCase(java.util.Locale.ROOT)));
            else if(!Boolean.TRUE.equals(included))return null;
        }
        return value;
    }
    private static Value output(JSONObject usage){
        if(usage==null)return null;Value value=counter(usage,"OUTPUT");Object included=usage.opt("outputIncludesReasoning");
        if(Boolean.FALSE.equals(included)){
            Value combined=add(value,counter(usage,"REASONING"));if(combined!=null)return combined;
            // Gemini defines total as prompt + candidates + thoughts. Optional
            // thoughts may be absent for non-thinking output; its own total/input
            // still establish complete output without inventing a reasoning zero.
            if("gemini".equals(usage.optString("protocol"))){
                Value total=counter(usage,"TOTAL"),input=counter(usage,"INPUT");
                if(total!=null&&input!=null&&total.tokens>=input.tokens){
                    long output=total.tokens-input.tokens;
                    if(value==null||output>=value.tokens)return new Value(output,total.partial||input.partial||value!=null&&value.partial,"gemini_total_minus_input");
                }
            }
            return null;
        }
        // Realtime output is the provider's combined text/audio count; no reasoning
        // relationship is claimed, and its modality details are never added again.
        return Boolean.TRUE.equals(included)||usage.optString("protocol").startsWith("openai_realtime")?value:null;
    }
    private static Value add(Value a,Value b){
        if(a==null||b==null)return null;
        try{long total=Math.addExact(a.tokens,b.tokens);return total<=MAX_SAFE_INTEGER?new Value(total,a.partial||b.partial,"normalized_provider_components"):null;}
        catch(ArithmeticException tooLarge){return null;}
    }
    private static Value counter(JSONObject usage,String name){
        JSONObject counters=usage==null?null:usage.optJSONObject("counters"),value=counters==null?null:counters.optJSONObject(name);
        if(value==null||!"provider_reported".equals(value.optString("source")))return null;
        Long tokens=nonnegative(value.opt("value"));return tokens==null||tokens>MAX_SAFE_INTEGER?null:new Value(tokens,!"FINAL".equals(value.optString("stage")));
    }
    private static Long nonnegative(Object raw){
        try{if(!(raw instanceof Number))return null;long value=new BigDecimal(raw.toString()).longValueExact();return value<0?null:value;}
        catch(ArithmeticException|NumberFormatException invalid){return null;}
    }
    private static final class Sum {
        long total;int known,unknown,partial;boolean overflow;
        final Map<String,Integer> sources=new LinkedHashMap<>();
        void add(Value value){
            if(value==null){unknown++;return;}known++;if(value.partial)partial++;sources.merge(value.source,1,Integer::sum);
            try{total=Math.addExact(total,value.tokens);if(total>MAX_SAFE_INTEGER)overflow=true;}catch(ArithmeticException tooLarge){overflow=true;}
        }
        JSONObject json()throws Exception{return new JSONObject().put("knownSubtotal",known==0||overflow?JSONObject.NULL:total)
                .put("reportedCalls",known).put("unknownCalls",unknown).put("partialCalls",partial).put("overflow",overflow).put("sources",new JSONObject(sources));}
    }
}
