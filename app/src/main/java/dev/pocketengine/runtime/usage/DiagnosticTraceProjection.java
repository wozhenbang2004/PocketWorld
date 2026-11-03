package dev.pocketengine.runtime.usage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit allowlist into OTLP/HTTP JSON. No prompt, response, file, URI, key or memory body is copied. */
public final class DiagnosticTraceProjection {
    public static final String PROTOCOL="OTLP-1.11.1-http-json";
    private static final Set<String> PROTOCOLS=Set.of("openai","openai_chat","openai_responses","openai_realtime",
            "openai_realtime_transcription","anthropic","gemini");
    private DiagnosticTraceProjection() { }

    public static JSONObject fromSource(JSONObject source,int maxBytes)throws Exception {
        if(!"pocketworld.trace-source.v1".equals(source.optString("format"))||!source.optBoolean("readOnly"))throw new SecurityException("没有原任务只读诊断来源");
        if(maxBytes<16384||maxBytes>1048576)throw new IllegalArgumentException("诊断快照大小超出范围");
        String task=source.getString("taskId"),run=source.getString("runId");int epoch=source.getInt("epoch");
        String traceId=hash(task+":"+run+":"+epoch).substring(0,32);
        JSONArray spans=new JSONArray(),callRefs=new JSONArray();int skipped=0,unknown=0,usedBytes=4096;long captured=source.getLong("capturedAt");
        JSONObject projection=InferenceProjection.fromEvents(source.getJSONArray("receipts"));JSONArray receipts=projection.getJSONArray("receipts");
        for(int i=0;i<receipts.length();i++){
            JSONObject receipt=receipts.getJSONObject(i),identity=receipt.getJSONObject("context");
            if(!task.equals(identity.optString("taskId"))||!run.equals(identity.optString("runId"))||epoch!=identity.optInt("epoch",-1))throw new SecurityException("诊断调用不属于原执行");
            if(receipt.optBoolean("projectionConflict")){skipped++;continue;}
            String call=receipt.getString("callId"),outcome=receipt.getString("outcome");long started=receipt.optLong("startedAt",0),finished=receipt.optLong("finishedAt",0);
            if(started<1||started>captured||finished>captured||finished>0&&finished<started){skipped++;continue;}
            boolean terminal=finished>0&&!"STARTED".equals(outcome);if(!terminal)unknown++;
            JSONObject span=span(traceId,"call:"+call,"model.request",started,terminal?finished:captured,outcome,terminal);
            JSONArray attributes=span.getJSONArray("attributes");JSONObject route=receipt.getJSONObject("route");
            attr(attributes,"pocketworld.call.ref",hash(call));attr(attributes,"pocketworld.run.epoch",epoch);
            attr(attributes,"pocketworld.route.ref",hash(route.toString()));
            String protocol=route.optString("protocol");if(PROTOCOLS.contains(protocol))attr(attributes,"pocketworld.protocol",protocol);
            String logical=route.optString("logicalRequestId");if(logical.matches("[a-fA-F0-9-]{36}"))attr(attributes,"pocketworld.logical_request.ref",hash(logical));
            int attempt=route.optInt("attemptNo",0);if(attempt>0&&attempt<=3)attr(attributes,"pocketworld.attempt",attempt);
            attr(attributes,"pocketworld.cancelled","CANCELLED".equals(outcome));
            JSONObject usage=receipt.getJSONObject("usage"),counts=usage.optJSONObject("counters");
            for(ProviderUsage.Counter key:ProviderUsage.Counter.values()){
                JSONObject count=counts==null?null:counts.optJSONObject(key.name());Object value=count==null?null:count.opt("value");
                if(count!=null&&"provider_reported".equals(count.optString("source"))&&value instanceof Number){
                    try{long number=new java.math.BigDecimal(value.toString()).longValueExact();if(number>=0){attr(attributes,"pocketworld.usage."+key.name().toLowerCase(java.util.Locale.ROOT),number);attr(attributes,"pocketworld.usage."+key.name().toLowerCase(java.util.Locale.ROOT)+".final","FINAL".equals(count.optString("stage")));}}catch(ArithmeticException ignored){/* Unknown stays absent. */}
                }
            }
            JSONObject ref=new JSONObject().put("callId",call).put("spanId",span.getString("spanId"));
            int size=span.toString().getBytes(StandardCharsets.UTF_8).length+ref.toString().getBytes(StandardCharsets.UTF_8).length+4;
            if(usedBytes+size>maxBytes){skipped++;continue;}
            usedBytes+=size;spans.put(span);callRefs.put(ref);
        }
        JSONArray operations=source.getJSONArray("operations");
        for(int i=0;i<operations.length();i++){
            JSONObject operation=operations.getJSONObject(i);String op=operation.getString("operationId");long start=operation.optLong("startedAt",0);
            if(start<1||start>captured){skipped++;continue;}
            String outcome=operation.optString("outcome");if(!Set.of("COMPLETED","FAILED","CANCELLED","TIMED_OUT","REJECTED").contains(outcome))outcome="UNKNOWN";
            // Original operation rows do not record a finish clock; end is an observation window, never tool latency.
            JSONObject span=span(traceId,"operation:"+op,"tool.operation.observation",start,captured,outcome,false);
            JSONArray attrs=span.getJSONArray("attributes");attr(attrs,"pocketworld.operation.ref",hash(op));
            attr(attrs,"pocketworld.tool.ref",hash(operation.optString("tool")));attr(attrs,"pocketworld.effect_receipt_observed",operation.optBoolean("terminalReceiptObserved"));
            attr(attrs,"pocketworld.duration_meaning","observation_window_not_tool_execution_duration");
            int size=span.toString().getBytes(StandardCharsets.UTF_8).length+2;
            if(usedBytes+size>maxBytes){skipped++;continue;}
            usedBytes+=size;spans.put(span);if("UNKNOWN".equals(outcome))unknown++;
        }
        JSONObject result=new JSONObject().put("format","pocketworld.redacted-trace.v1").put("protocol",PROTOCOL)
                .put("taskId",task).put("runId",run).put("epoch",epoch).put("traceId",traceId)
                .put("sourceRevision",source.getString("sourceRevision")).put("capturedAt",captured)
                .put("spanCount",spans.length()).put("unknownOutcomes",unknown).put("skippedSpans",skipped)
                .put("sourceLimited",source.optBoolean("limited")||projection.optInt("invalidReceipts")>0)
                .put("bodyIncluded",false).put("pseudonymousExport",true).put("callReferences",callRefs)
                .put("otlp",payload(spans,null));
        byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>maxBytes)throw new IllegalStateException("脱敏诊断超过本次字节上限");
        return result.put("snapshotId",hash(result.toString()));
    }
    private static JSONObject span(String traceId,String identity,String name,long start,long end,String outcome,boolean terminal)throws Exception {
        JSONArray attributes=new JSONArray();attr(attributes,"pocketworld.outcome",outcome);attr(attributes,"pocketworld.terminal_time_observed",terminal);
        if(!terminal)attr(attributes,"pocketworld.end_meaning","snapshot_observation_not_completion");
        return new JSONObject().put("traceId",traceId).put("spanId",hash(identity).substring(0,16)).put("name",name)
                .put("kind",1).put("startTimeUnixNano",nanos(start)).put("endTimeUnixNano",nanos(end)).put("attributes",attributes)
                .put("status",new JSONObject().put("code",Set.of("FAILED","TIMED_OUT","REJECTED").contains(outcome)?2:terminal&&"COMPLETED".equals(outcome)?1:0));
    }
    private static JSONObject payload(JSONArray current,JSONObject next)throws Exception {
        JSONArray spans=new JSONArray();for(int i=0;i<current.length();i++)spans.put(current.get(i));if(next!=null)spans.put(next);
        JSONArray resourceAttributes=new JSONArray();attr(resourceAttributes,"service.name","PocketWorld");
        return new JSONObject().put("resourceSpans",new JSONArray().put(new JSONObject().put("resource",new JSONObject().put("attributes",resourceAttributes))
                .put("scopeSpans",new JSONArray().put(new JSONObject().put("scope",new JSONObject().put("name","pocketworld.native.audit").put("version","1"))
                        .put("spans",spans)))));
    }
    private static void attr(JSONArray into,String key,Object value)throws Exception {
        JSONObject typed=new JSONObject();if(value instanceof Boolean)typed.put("boolValue",value);else if(value instanceof Number)typed.put("intValue",value.toString());else typed.put("stringValue",value);
        into.put(new JSONObject().put("key",key).put("value",typed));
    }
    private static String nanos(long millis){return java.math.BigInteger.valueOf(millis).multiply(java.math.BigInteger.valueOf(1_000_000)).toString();}
    public static String hash(String text)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
