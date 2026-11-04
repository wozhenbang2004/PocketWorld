package dev.pocketengine.runtime.usage;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Allowlisted provider counters, separate from assistant content and from local estimates. */
public final class ProviderUsage {
    public enum Counter { INPUT, OUTPUT, TOTAL, CACHE_READ, CACHE_WRITE, CACHE_WRITE_5M, CACHE_WRITE_1H, REASONING, TOOL_PROMPT }
    public enum Stage { PARTIAL, FINAL }
    public static final class Count {
        public final long value, observedAt;
        public final String sourcePath;
        public final Stage stage;
        private Count(long value,String path,Stage stage){this(value,path,stage,System.currentTimeMillis());}
        private Count(long value,String path,Stage stage,long observedAt){this.value=value;sourcePath=path;this.stage=stage;this.observedAt=observedAt;}
        JSONObject json()throws Exception{return new JSONObject().put("value",value).put("source","provider_reported")
                .put("sourcePath",sourcePath).put("observedAt",observedAt).put("stage",stage.name());}
    }
    public interface Observer {
        void observe(JSONObject wire,Stage stage);
        default void completed(){}
        default void terminal(String reason){}
    }
    public static final Observer NONE=(wire,stage)->{};

    /** Each observation is a cumulative snapshot, never an additive token delta. */
    public static final class Collector implements Observer {
        private final String protocol;
        private final Map<Counter,Count> counts=new LinkedHashMap<>();
        private final Map<String,Count> media=new LinkedHashMap<>();
        private final Set<String> invalid=new LinkedHashSet<>();
        private boolean observed;
        private String reportedModel,finishReason;private JSONObject providerMeasures=new JSONObject();
        public Collector(String protocol){
            if(!Set.of("openai_chat","openai_responses","anthropic","bedrock_anthropic","gemini","openai_realtime","openai_realtime_transcription").contains(protocol))throw new IllegalArgumentException("unsupported_usage_protocol");
            this.protocol=protocol;
        }
        @Override public synchronized void observe(JSONObject wire,Stage stage){
            Object rawModel=wire.opt(protocol.equals("gemini")?"modelVersion":"model");
            if(rawModel instanceof String&&((String)rawModel).matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,299}"))reportedModel=(String)rawModel;
            JSONObject usage=wire.optJSONObject(protocol.equals("gemini")?"usageMetadata":"usage");
            if(usage==null)return;
            observed=true;
            switch(protocol){
                case "openai_chat":
                    count(usage,"prompt_tokens",Counter.INPUT,"usage.prompt_tokens",stage);
                    count(usage,"completion_tokens",Counter.OUTPUT,"usage.completion_tokens",stage);
                    count(usage,"total_tokens",Counter.TOTAL,"usage.total_tokens",stage);
                    detail(usage,"prompt_tokens_details","cached_tokens",Counter.CACHE_READ,stage);
                    // OpenRouter-compatible documented extension; absent never means zero.
                    detail(usage,"prompt_tokens_details","cache_write_tokens",Counter.CACHE_WRITE,stage);
                    detail(usage,"completion_tokens_details","reasoning_tokens",Counter.REASONING,stage);
                    break;
                case "openai_responses":
                    count(usage,"input_tokens",Counter.INPUT,"usage.input_tokens",stage);
                    count(usage,"output_tokens",Counter.OUTPUT,"usage.output_tokens",stage);
                    count(usage,"total_tokens",Counter.TOTAL,"usage.total_tokens",stage);
                    detail(usage,"input_tokens_details","cached_tokens",Counter.CACHE_READ,stage);
                    detail(usage,"output_tokens_details","reasoning_tokens",Counter.REASONING,stage);
                    break;
                case "openai_realtime_transcription":
                    if("tokens".equals(usage.optString("type"))){
                        count(usage,"input_tokens",Counter.INPUT,"usage.input_tokens",stage);count(usage,"output_tokens",Counter.OUTPUT,"usage.output_tokens",stage);count(usage,"total_tokens",Counter.TOTAL,"usage.total_tokens",stage);
                        media(usage,"input_token_details","text_tokens","INPUT_TEXT",stage);media(usage,"input_token_details","audio_tokens","INPUT_AUDIO",stage);
                    }else if("duration".equals(usage.optString("type")))duration(usage,stage);else invalid.add("usage.type");
                    break;
                case "openai_realtime":
                    count(usage,"input_tokens",Counter.INPUT,"usage.input_tokens",stage);
                    count(usage,"output_tokens",Counter.OUTPUT,"usage.output_tokens",stage);
                    count(usage,"total_tokens",Counter.TOTAL,"usage.total_tokens",stage);
                    detail(usage,"input_token_details","cached_tokens",Counter.CACHE_READ,stage);
                    media(usage,"input_token_details","text_tokens","INPUT_TEXT",stage);
                    media(usage,"input_token_details","audio_tokens","INPUT_AUDIO",stage);
                    media(usage,"output_token_details","text_tokens","OUTPUT_TEXT",stage);
                    media(usage,"output_token_details","audio_tokens","OUTPUT_AUDIO",stage);
                    break;
                case "anthropic":case "bedrock_anthropic":
                    count(usage,"input_tokens",Counter.INPUT,"usage.input_tokens",stage);
                    count(usage,"output_tokens",Counter.OUTPUT,"usage.output_tokens",stage);
                    count(usage,"cache_read_input_tokens",Counter.CACHE_READ,"usage.cache_read_input_tokens",stage);
                    count(usage,"cache_creation_input_tokens",Counter.CACHE_WRITE,"usage.cache_creation_input_tokens",stage);
                    detail(usage,"cache_creation","ephemeral_5m_input_tokens",Counter.CACHE_WRITE_5M,stage);
                    detail(usage,"cache_creation","ephemeral_1h_input_tokens",Counter.CACHE_WRITE_1H,stage);
                    detail(usage,"output_tokens_details","thinking_tokens",Counter.REASONING,stage);
                    break;
                case "gemini":
                    count(usage,"promptTokenCount",Counter.INPUT,"usageMetadata.promptTokenCount",stage);
                    count(usage,"candidatesTokenCount",Counter.OUTPUT,"usageMetadata.candidatesTokenCount",stage);
                    count(usage,"totalTokenCount",Counter.TOTAL,"usageMetadata.totalTokenCount",stage);
                    count(usage,"cachedContentTokenCount",Counter.CACHE_READ,"usageMetadata.cachedContentTokenCount",stage);
                    count(usage,"thoughtsTokenCount",Counter.REASONING,"usageMetadata.thoughtsTokenCount",stage);
                    count(usage,"toolUsePromptTokenCount",Counter.TOOL_PROMPT,"usageMetadata.toolUsePromptTokenCount",stage);
                    break;
                default:throw new AssertionError();
            }
        }
        private void duration(JSONObject usage,Stage stage){
            try{Object raw=usage.opt("seconds");if(!(raw instanceof Number))throw new ArithmeticException();BigDecimal value=new BigDecimal(raw.toString());if(value.signum()<0||value.precision()>24||value.scale()<-6||value.scale()>6)throw new ArithmeticException();
                providerMeasures.put("AUDIO_SECONDS",new JSONObject().put("value",value).put("unit","seconds").put("source","provider_reported").put("sourcePath","usage.seconds").put("stage",stage.name()).put("notConvertedToTokensOrMoney",true));invalid.remove("usage.seconds");
            }catch(Exception invalidValue){providerMeasures.remove("AUDIO_SECONDS");invalid.add("usage.seconds");}
        }
        private void media(JSONObject usage,String parent,String key,String label,Stage stage){
            JSONObject details=usage.optJSONObject(parent);if(details==null||!details.has(key))return;String path="usage."+parent+"."+key;
            try{Object raw=details.opt(key);if(!(raw instanceof Number))throw new ArithmeticException();long value=new BigDecimal(raw.toString()).longValueExact();if(value<0)throw new ArithmeticException();media.put(label,new Count(value,path,stage));invalid.remove(path);}
            catch(ArithmeticException|NumberFormatException malformed){media.remove(label);invalid.add(path);}
        }
        private void detail(JSONObject usage,String parent,String key,Counter counter,Stage stage){
            JSONObject object=usage.optJSONObject(parent);
            if(object!=null)count(object,key,counter,"usage."+parent+"."+key,stage);
        }
        private void count(JSONObject object,String key,Counter counter,String path,Stage stage){
            if(!object.has(key))return;
            if(object.isNull(key)){counts.remove(counter);invalid.remove(path);return;}
            Object raw=object.opt(key);
            try{
                if(!(raw instanceof Number))throw new ArithmeticException();
                long value=new BigDecimal(raw.toString()).longValueExact();
                if(value<0)throw new ArithmeticException();
                counts.put(counter,new Count(value,path,stage));invalid.remove(path);
            }catch(NumberFormatException|ArithmeticException malformed){invalid.add(path);counts.remove(counter);}
        }
        public synchronized Count get(Counter counter){return counts.get(counter);}
        public synchronized String reportedModel(){return reportedModel;}
        @Override public synchronized void terminal(String reason){if(reason!=null&&reason.matches("[A-Za-z0-9_.:-]{1,64}"))finishReason=reason;}
        @Override public synchronized void completed(){
            for(Map.Entry<Counter,Count> entry:counts.entrySet()){
                Count count=entry.getValue();entry.setValue(new Count(count.value,count.sourcePath,Stage.FINAL,count.observedAt));
            }
        }
        public synchronized Map<Counter,Count> counts(){return Collections.unmodifiableMap(new LinkedHashMap<>(counts));}
        public synchronized JSONObject toJson()throws Exception{
            boolean exclusiveCacheInput=protocol.equals("anthropic")||protocol.equals("bedrock_anthropic");
            JSONObject values=new JSONObject();
            for(Counter counter:Counter.values()){
                Count value=counts.get(counter);
                values.put(counter.name(),value==null?new JSONObject().put("value",JSONObject.NULL).put("source","unknown")
                        .put("sourcePath",JSONObject.NULL).put("observedAt",JSONObject.NULL).put("stage",JSONObject.NULL):value.json());
            }
            JSONArray bad=new JSONArray();for(String path:invalid)bad.put(path);
            JSONObject mediaValues=new JSONObject();if(protocol.startsWith("openai_realtime"))for(String key:new String[]{"INPUT_TEXT","INPUT_AUDIO","OUTPUT_TEXT","OUTPUT_AUDIO"}){Count count=media.get(key);mediaValues.put(key,count==null?new JSONObject().put("value",JSONObject.NULL).put("source","unknown"):count.json());}
            return new JSONObject().put("providerMeasures",new JSONObject(providerMeasures.toString())).put("mediaCounters",mediaValues).put("protocol",protocol).put("reported",observed).put("counters",values).put("invalidFields",bad)
                    .put("finishReason",finishReason==null?JSONObject.NULL:finishReason)
                    .put("inputIncludesCacheRead",!exclusiveCacheInput)
                    .put("inputIncludesCacheWrite",!exclusiveCacheInput)
                    .put("outputIncludesReasoning",protocol.startsWith("openai_realtime")?JSONObject.NULL:!protocol.equals("gemini"))
                    .put("outputMeaning",protocol.equals("openai_realtime_transcription")?"separate_asr_report_do_not_add_to_realtime_response":protocol.equals("openai_realtime")?"reported_text_and_audio_total_do_not_add_media_details":protocol.equals("gemini")?"response_candidates":"generated_output_including_reasoning")
                    .put("actualCost",JSONObject.NULL).put("actualCostSource","unknown")
                    .put("estimatedCost",JSONObject.NULL).put("estimatedCostSource","not_calculated")
                    .put("priceVersion",JSONObject.NULL)
                    .put("subscriptionRemaining",JSONObject.NULL);
        }
    }
    private ProviderUsage(){}
}
