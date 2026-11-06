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
            "openai_realtime_transcription","anthropic","bedrock_anthropic","gemini");
    private DiagnosticTraceProjection() { }

    public static JSONObject fromSource(JSONObject source,int maxBytes)throws Exception {
        if(!"pocketworld.trace-source.v1".equals(source.optString("format"))||!source.optBoolean("readOnly"))throw new SecurityException("没有原任务只读诊断来源");
        if(maxBytes<16384||maxBytes>1048576)throw new IllegalArgumentException("诊断快照大小超出范围");
        String task=source.getString("taskId"),run=source.getString("runId");int epoch=source.getInt("epoch");
        String traceId=hash(task+":"+run+":"+epoch).substring(0,32);
        JSONArray spans=new JSONArray(),callRefs=new JSONArray();int skipped=0,unknown=0,usedBytes=4096;long captured=source.getLong("capturedAt");
        java.util.Map<String,JSONObject> exported=new java.util.LinkedHashMap<>();
        java.util.Map<String,String> parents=new java.util.LinkedHashMap<>();
        java.util.Set<String> representedOperations=new java.util.HashSet<>();
        JSONObject projection=InferenceProjection.fromEvents(source.getJSONArray("receipts"));JSONArray receipts=projection.getJSONArray("receipts");
        for(int i=0;i<receipts.length();i++){
            JSONObject receipt=receipts.getJSONObject(i),identity=receipt.getJSONObject("context");
            if(!task.equals(identity.optString("taskId"))||!run.equals(identity.optString("runId"))||epoch!=identity.optInt("epoch",-1))throw new SecurityException("诊断调用不属于原执行");
            if(receipt.optBoolean("projectionConflict")){skipped++;continue;}
            String call=receipt.getString("callId"),outcome=receipt.getString("outcome");long started=receipt.optLong("startedAt",0),finished=receipt.optLong("finishedAt",0);
            Long elapsed=ExecutionTraceProjection.elapsedNanos(receipt);
            // OTLP spans require a real end. A still-open call remains an explicit unknown count;
            // capture time is never used to manufacture execution duration.
            if("STARTED".equals(outcome)||finished<=0){unknown++;skipped++;continue;}
            if(started<=0||elapsed==null){skipped++;continue;}
            JSONObject span=span(traceId,"call:"+call,"model.request",started,elapsed,outcome);
            JSONArray attributes=span.getJSONArray("attributes");JSONObject route=receipt.getJSONObject("route");
            attr(attributes,"openinference.span.kind","LLM");attr(attributes,"gen_ai.operation.name","chat");
            attr(attributes,"pocketworld.call.ref",hash(call));attr(attributes,"pocketworld.run.epoch",epoch);
            attr(attributes,"pocketworld.route.ref",hash(route.toString()));
            String protocol=route.optString("protocol");if(PROTOCOLS.contains(protocol))attr(attributes,"pocketworld.protocol",protocol);
            String provider=protocol.startsWith("openai")?"openai":protocol.equals("anthropic")?"anthropic":protocol.equals("bedrock_anthropic")?"aws.bedrock":protocol.equals("gemini")?"gcp.gemini":"";
            if(!provider.isEmpty())attr(attributes,"gen_ai.provider.name",provider);
            String logical=route.optString("logicalRequestId");if(logical.matches("[a-fA-F0-9-]{36}"))attr(attributes,"pocketworld.logical_request.ref",hash(logical));
            int attempt=route.optInt("attemptNo",0);if(attempt>0)attr(attributes,"pocketworld.attempt",attempt);
            attr(attributes,"pocketworld.cancelled","CANCELLED".equals(outcome));
            if(!identity.optString("memberId").isEmpty())attr(attributes,"pocketworld.member.ref",hash(identity.getString("memberId")));
            JSONObject site=identity.optJSONObject("callSite");if(site!=null&&!site.optString("turnId").isEmpty())attr(attributes,"pocketworld.turn.ref",hash(site.getString("turnId")));
            if("provider_text_delta".equals(receipt.optString("timingEvidence"))&&!receipt.isNull("firstVisibleTextElapsedNanos")){
                try{long first=new java.math.BigDecimal(receipt.get("firstVisibleTextElapsedNanos").toString()).longValueExact();if(first>=0&&first<=elapsed){attr(attributes,"pocketworld.first_visible_text.elapsed_nanos",first);attr(attributes,"pocketworld.first_visible_text.evidence","provider_text_delta");}}catch(ArithmeticException|NumberFormatException ignored){}
            }
            JSONObject usage=receipt.getJSONObject("usage"),counts=usage.optJSONObject("counters");
            for(ProviderUsage.Counter key:ProviderUsage.Counter.values()){
                JSONObject count=counts==null?null:counts.optJSONObject(key.name());Long number=counter(count,false);
                if(number!=null){attr(attributes,"pocketworld.usage."+key.name().toLowerCase(java.util.Locale.ROOT),number);attr(attributes,"pocketworld.usage."+key.name().toLowerCase(java.util.Locale.ROOT)+".final","FINAL".equals(count.optString("stage")));}
            }
            standardUsage(attributes,usage);
            JSONObject ref=new JSONObject().put("callId",call).put("spanId",span.getString("spanId"));
            int size=span.toString().getBytes(StandardCharsets.UTF_8).length+ref.toString().getBytes(StandardCharsets.UTF_8).length+128;
            if(usedBytes+size>maxBytes){skipped++;continue;}
            usedBytes+=size;spans.put(span);callRefs.put(ref);exported.put("model:"+call,span);
        }
        JSONArray tools=source.optJSONArray("toolCalls");
        for(int i=0;tools!=null&&i<tools.length();i++){
            JSONObject tool=tools.getJSONObject(i);
            if(!task.equals(tool.optString("taskId"))||!run.equals(tool.optString("runId"))||epoch!=tool.optInt("epoch",-1))throw new SecurityException("诊断工具不属于原执行");
            if(!tool.optString("operationId").isEmpty())representedOperations.add(tool.getString("operationId"));
            Long elapsed=ExecutionTraceProjection.elapsedNanos(tool);long start=tool.optLong("startedAt",0);
            String outcome=tool.optString("status","unknown").toUpperCase(java.util.Locale.ROOT);
            if(Set.of("RUNNING","UNKNOWN").contains(outcome))unknown++;
            if(!tool.optBoolean("timingKnown")||tool.isNull("finishedAt")||start<=0||elapsed==null){skipped++;continue;}
            String id=tool.getString("id");JSONObject span=span(traceId,id,"tool.call",start,elapsed,outcome);JSONArray attrs=span.getJSONArray("attributes");
            attr(attrs,"openinference.span.kind","TOOL");attr(attrs,"pocketworld.tool.ref",hash(tool.optString("name")));
            attr(attrs,"pocketworld.duration_meaning","native_tool_handling_interval_including_checks_and_receipt_persistence");
            attr(attrs,"pocketworld.dispatch_entered",tool.optBoolean("dispatchEntered"));attr(attrs,"pocketworld.effect_pending",tool.optBoolean("effectPending"));
            if(tool.has("operationId"))attr(attrs,"pocketworld.operation.ref",hash(tool.getString("operationId")));
            if(!tool.optString("memberId").isEmpty())attr(attrs,"pocketworld.member.ref",hash(tool.getString("memberId")));
            if(!tool.optString("turnId").isEmpty())attr(attrs,"pocketworld.turn.ref",hash(tool.getString("turnId")));
            int size=span.toString().getBytes(StandardCharsets.UTF_8).length+192;
            if(usedBytes+size>maxBytes){skipped++;continue;}
            usedBytes+=size;spans.put(span);exported.put(id,span);parents.put(id,tool.optString("parentId"));
        }
        for(java.util.Map.Entry<String,String> relation:parents.entrySet()){
            JSONObject child=exported.get(relation.getKey()),parent=exported.get(relation.getValue());
            if(parent!=null)child.put("parentSpanId",parent.getString("spanId"));
            else if(!relation.getValue().isEmpty())attr(child.getJSONArray("attributes"),"pocketworld.parent_not_exported",true);
        }
        JSONArray operations=source.optJSONArray("operations");
        for(int i=0;operations!=null&&i<operations.length();i++){
            JSONObject operation=operations.getJSONObject(i);if(representedOperations.contains(operation.optString("operationId")))continue;
            // Legacy operation rows have no monotonic finish measurement. They remain in the
            // original local audit, but do not masquerade as timed spans in a collector.
            skipped++;if("UNKNOWN".equals(operation.optString("outcome")))unknown++;
        }
        JSONObject result=new JSONObject().put("format","pocketworld.redacted-trace.v1").put("protocol",PROTOCOL)
                .put("taskId",task).put("runId",run).put("epoch",epoch).put("traceId",traceId)
                .put("sourceRevision",source.getString("sourceRevision")).put("capturedAt",captured)
                .put("spanCount",spans.length()).put("unknownOutcomes",unknown).put("skippedSpans",skipped)
                .put("sourceLimited",source.optBoolean("limited")||projection.optInt("invalidReceipts")>0)
                .put("bodyIncluded",false).put("pseudonymousExport",true).put("callReferences",callRefs)
                .put("timingMeaning","finished_monotonic_intervals_only").put("otlp",payload(spans,null));
        byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>maxBytes)throw new IllegalStateException("脱敏诊断超过本次字节上限");
        return result.put("snapshotId",hash(result.toString()));
    }
    private static JSONObject span(String traceId,String identity,String name,long start,long elapsed,String outcome)throws Exception {
        JSONArray attributes=new JSONArray();attr(attributes,"pocketworld.outcome",outcome);attr(attributes,"pocketworld.terminal_time_observed",true);
        attr(attributes,"pocketworld.duration_source","monotonic");
        String startNanos=nanos(start),endNanos=new java.math.BigInteger(startNanos).add(java.math.BigInteger.valueOf(elapsed)).toString();
        return new JSONObject().put("traceId",traceId).put("spanId",hash(identity).substring(0,16)).put("name",name)
                .put("kind",1).put("startTimeUnixNano",startNanos).put("endTimeUnixNano",endNanos).put("attributes",attributes)
                .put("status",new JSONObject().put("code",Set.of("FAILED","TIMED_OUT","REJECTED").contains(outcome)?2:"COMPLETED".equals(outcome)?1:0));
    }
    private static Long counter(JSONObject count,boolean requireFinal){
        if(count==null||!"provider_reported".equals(count.optString("source"))||requireFinal&&!"FINAL".equals(count.optString("stage"))||!(count.opt("value") instanceof Number))return null;
        try{long value=new java.math.BigDecimal(count.get("value").toString()).longValueExact();return value>=0?value:null;}catch(Exception invalid){return null;}
    }
    /** gen_ai.* is a development convention. Normalize only fully known final provider fields;
     * Anthropic cache and Gemini reasoning must not disappear through a naive field rename. */
    private static void standardUsage(JSONArray attrs,JSONObject usage)throws Exception {
        JSONObject counters=usage.optJSONObject("counters");if(counters==null)return;
        Long input=counter(counters.optJSONObject("INPUT"),true),output=counter(counters.optJSONObject("OUTPUT"),true);
        Long read=counter(counters.optJSONObject("CACHE_READ"),true),write=counter(counters.optJSONObject("CACHE_WRITE"),true),reasoning=counter(counters.optJSONObject("REASONING"),true);
        try{
            if(!Boolean.TRUE.equals(usage.opt("inputIncludesCacheRead")))input=Boolean.FALSE.equals(usage.opt("inputIncludesCacheRead"))&&input!=null&&read!=null?Math.addExact(input,read):null;
            if(!Boolean.TRUE.equals(usage.opt("inputIncludesCacheWrite")))input=Boolean.FALSE.equals(usage.opt("inputIncludesCacheWrite"))&&input!=null&&write!=null?Math.addExact(input,write):null;
            if(!Boolean.TRUE.equals(usage.opt("outputIncludesReasoning")))output=Boolean.FALSE.equals(usage.opt("outputIncludesReasoning"))&&output!=null&&reasoning!=null?Math.addExact(output,reasoning):null;
        }catch(ArithmeticException overflow){input=null;output=null;}
        if(input!=null)attr(attrs,"gen_ai.usage.input_tokens",input);
        if(output!=null)attr(attrs,"gen_ai.usage.output_tokens",output);
        if(read!=null)attr(attrs,"gen_ai.usage.cache_read.input_tokens",read);
        if(write!=null)attr(attrs,"gen_ai.usage.cache_creation.input_tokens",write);
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
