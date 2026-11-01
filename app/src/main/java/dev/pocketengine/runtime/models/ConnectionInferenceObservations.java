package dev.pocketengine.runtime.models;

import dev.pocketengine.runtime.usage.InferenceFailure;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.time.Clock;
import java.util.LinkedHashMap;
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
                .put("context",nativeReceipt.context.toJson()).put("failure",JSONObject.NULL);
        JSONObject failure=receipt.optJSONObject("failure");
        if(failure!=null)observation.put("failure",InferenceFailure.checked(failure).toJson());
        Row previous=rows.remove(key);String retainedFailure=nativeReceipt.outcome==InferenceReceipt.Outcome.COMPLETED?(previous==null?null:previous.failure):observation.toString();
        rows.put(key,new Row(observation.toString(),retainedFailure));
        while(rows.size()>MAX_ROUTES)rows.remove(rows.keySet().iterator().next());
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
    private static String protocol(ModelBinding.Protocol value){return switch(value){case OPENAI_CHAT->"openai_chat";case OPENAI_RESPONSES->"openai_responses";case ANTHROPIC->"anthropic";case GEMINI->"gemini";};}
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
