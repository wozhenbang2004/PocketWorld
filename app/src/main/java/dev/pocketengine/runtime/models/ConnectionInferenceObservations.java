package dev.pocketengine.runtime.models;

import dev.pocketengine.runtime.usage.InferenceFailure;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Set;
import org.json.JSONObject;

/** Finite process-local display projection of committed native terminal receipts; never an admission gate. */
public final class ConnectionInferenceObservations {
    public static final int MAX_ROUTES=32;
    private final Clock clock;
    private final LinkedHashMap<String,Row> rows=new LinkedHashMap<>();
    private record Row(String last,String failure) { }
    public ConnectionInferenceObservations(Clock clock){this.clock=java.util.Objects.requireNonNull(clock);}
    /** Must be called only after the original Sink.finished has returned successfully. */
    public synchronized void committed(InferenceReceipt nativeReceipt)throws Exception {
        if(nativeReceipt.outcome==InferenceReceipt.Outcome.STARTED)return;
        JSONObject receipt=nativeReceipt.toJson(),route=receipt.getJSONObject("route");String key=key(route);
        if(key==null)return;
        JSONObject observation=new JSONObject().put("callId",nativeReceipt.callId).put("outcome",nativeReceipt.outcome.name()).put("at",nativeReceipt.finishedAt)
                .put("context",nativeReceipt.context.toJson()).put("failure",JSONObject.NULL).put("cacheObservation",cacheObservation(receipt));
        JSONObject failure=receipt.optJSONObject("failure");
        if(failure!=null)observation.put("failure",InferenceFailure.checked(failure).toJson());
        Row previous=rows.remove(key);String retainedFailure=nativeReceipt.outcome==InferenceReceipt.Outcome.COMPLETED?(previous==null?null:previous.failure):observation.toString();
        rows.put(key,new Row(observation.toString(),retainedFailure));
        while(rows.size()>MAX_ROUTES)rows.remove(rows.keySet().iterator().next());
    }
    /** Only an already committed native receipt supplies facts; no health probe or desired policy is usage. */
    private static JSONObject cacheObservation(JSONObject receipt)throws Exception {
        JSONObject route=receipt.getJSONObject("route"),projection=route.optJSONObject("providerProjection"),usage=receipt.getJSONObject("usage");
        if(projection==null||!"pocketworld.provider-projection.v1".equals(projection.optString("format")))projection=new JSONObject();
        JSONObject out=new JSONObject().put("format","pocketworld.cache-observation.v1").put("source","committed_native_receipt")
                .put("hintPrepared",observedBoolean(projection,"hintPrepared")).put("dispatchAttempted",observedBoolean(projection,"dispatchAttempted"))
                .put("hintDelivery",choice(projection,"hintDelivery",Set.of("not_requested","not_dispatched","unknown"),"unknown"))
                .put("cacheMode",choice(projection,"cacheMode",Set.of("OFF","EPHEMERAL"),"unknown"))
                .put("cacheSupport",choice(projection,"cacheSupport",Set.of("UNKNOWN","SUPPORTED","UNSUPPORTED"),"UNKNOWN"))
                .put("cacheSupportSource",choice(projection,"cacheSupportSource",Set.of("owner_reviewed_connection_profile"),"unknown"))
                .put("usageReported",observedBoolean(usage,"reported")).put("cacheHitInferred",false).put("usageMeaning","provider_counters_not_a_bill")
                .put("inputIncludesCacheRead",observedBoolean(usage,"inputIncludesCacheRead")).put("inputIncludesCacheWrite",observedBoolean(usage,"inputIncludesCacheWrite"));
        Object id=projection.opt("compatProfileId"),revision=projection.opt("compatProfileRevision"),prefix=projection.opt("prefixHash");
        if(id instanceof String&&((String)id).matches("compat-[a-f0-9-]{36}")&&positiveInteger(revision))out.put("compatProfileId",id).put("compatProfileRevision",revision);
        if(prefix instanceof String&&((String)prefix).matches("[a-f0-9]{64}"))out.put("prefixHash",prefix);
        JSONObject counters=new JSONObject(),original=usage.optJSONObject("counters");
        for(String name:new String[]{"CACHE_READ","CACHE_WRITE","CACHE_WRITE_5M","CACHE_WRITE_1H"})counters.put(name,cacheCounter(name,original==null?null:original.optJSONObject(name)));
        return out.put("counters",counters);
    }
    private static Object observedBoolean(JSONObject object,String key){Object value=object.opt(key);return value instanceof Boolean?value:JSONObject.NULL;}
    private static String choice(JSONObject object,String key,Set<String> allowed,String unknown){Object value=object.opt(key);return value instanceof String&&allowed.contains(value)?(String)value:unknown;}
    private static boolean positiveInteger(Object value){return (value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>0;}
    private static JSONObject cacheCounter(String name,JSONObject row)throws Exception {
        JSONObject out=new JSONObject().put("value",JSONObject.NULL).put("source","unknown").put("sourcePath",JSONObject.NULL).put("stage",JSONObject.NULL).put("observedAt",JSONObject.NULL);
        if(row==null||!"provider_reported".equals(row.opt("source"))||!cachePath(name,row.opt("sourcePath"))||!(row.opt("value") instanceof Number))return out;
        try{long value=new BigDecimal(row.get("value").toString()).longValueExact();if(value<0)return out;
            out.put("value",value).put("source","provider_reported").put("sourcePath",row.get("sourcePath"));
            String stage=choice(row,"stage",Set.of("PARTIAL","FINAL"),"");if(!stage.isEmpty())out.put("stage",stage);
            Object at=row.opt("observedAt");if((at instanceof Integer||at instanceof Long)&&((Number)at).longValue()>=0)out.put("observedAt",at);
        }catch(ArithmeticException|NumberFormatException malformed){/* Keep the explicit unknown row; never substitute zero. */}
        return out;
    }
    private static boolean cachePath(String name,Object path){
        if(!(path instanceof String))return false;
        return switch(name){
            case "CACHE_READ"->Set.of("usage.prompt_tokens_details.cached_tokens","usage.input_tokens_details.cached_tokens","usage.input_token_details.cached_tokens","usage.cache_read_input_tokens","usageMetadata.cachedContentTokenCount").contains(path);
            case "CACHE_WRITE"->Set.of("usage.prompt_tokens_details.cache_write_tokens","usage.cache_creation_input_tokens").contains(path);
            case "CACHE_WRITE_5M"->"usage.cache_creation.ephemeral_5m_input_tokens".equals(path);
            case "CACHE_WRITE_1H"->"usage.cache_creation.ephemeral_1h_input_tokens".equals(path);
            default->false;
        };
    }
    public synchronized JSONObject snapshot(ModelBinding binding,String model)throws Exception {
        JSONObject out=new JSONObject().put("format","pocketworld.connection-inference-observation.v1").put("coverage","committed_terminal_receipts_this_process_only")
                .put("observed",false).put("lastCall",JSONObject.NULL).put("lastFailure",JSONObject.NULL).put("observedAt",clock.millis())
                .put("healthVerified",false).put("quotaKnown",false).put("automaticRetry",false).put("reason","not_observed_this_process_or_evicted_after_restart_unknown");
        if(binding==null||model==null||model.isEmpty())return out;
        String key=key(new JSONObject().put("kind","api_key").put("identityRedacted",false).put("protocol",protocol(binding.protocol())).put("connectionId",binding.connectionId()).put("providerId",binding.providerId())
                .put("credentialId",binding.credentialId()).put("credentialRevision",binding.credentialRevision()).put("installationId",binding.installationId()).put("installationRevision",binding.installationRevision()).put("requestedModel",model));
        Row row=rows.get(key);return row==null?out:out.put("observed",true).put("reason","native_terminal_call_observed_not_a_health_test").put("lastCall",new JSONObject(row.last)).put("lastFailure",row.failure==null?JSONObject.NULL:new JSONObject(row.failure));
    }
    private static String protocol(ModelBinding.Protocol value){return switch(value){case OPENAI_CHAT->"openai_chat";case OPENAI_RESPONSES->"openai_responses";case ANTHROPIC->"anthropic";case GEMINI->"gemini";case BEDROCK_ANTHROPIC->"bedrock_anthropic";};}
    private static String key(JSONObject route) {
        if(!"api_key".equals(route.optString("kind"))||!Boolean.FALSE.equals(route.opt("identityRedacted")))return null;
        StringBuilder key=new StringBuilder();
        for(String name:new String[]{"connectionId","providerId","protocol","credentialId","installationId","requestedModel"}) {
            Object value=route.opt(name);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>300||((String)value).codePoints().anyMatch(Character::isISOControl))return null;
            key.append(((String)value).length()).append(':').append(value).append(';');
        }
        for(String name:new String[]{"credentialRevision","installationRevision"}) {
            Object value=route.opt(name);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)return null;
            key.append(((Number)value).longValue()).append(';');
        }
        return key.toString();
    }
}
