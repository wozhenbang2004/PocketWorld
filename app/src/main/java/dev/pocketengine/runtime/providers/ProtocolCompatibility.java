package dev.pocketengine.runtime.providers;

import java.util.Map;
import java.util.Set;

/** Versioned, conservative support registry. A pair is not a claim about every API field. */
public final class ProtocolCompatibility {
    public static final String REVISION="pocket-native-codec-1";
    public enum Disposition { NATIVE_LOSSLESS, TRANSLATED_LOSSLESS, UNSUPPORTED }
    public static final Set<String> PROTOCOLS=Set.of("openai_chat","openai_responses","anthropic","gemini");
    private static final Map<String,Set<String>> FIELDS=Map.of(
        "openai_chat",Set.of("model","messages","tools","tool_choice","max_tokens","max_completion_tokens","stream","stream_options","temperature","top_p","stop","parallel_tool_calls"),
        "openai_responses",Set.of("model","input","instructions","tools","tool_choice","max_output_tokens","stream","temperature","top_p","parallel_tool_calls","store"),
        "anthropic",Set.of("model","messages","system","tools","tool_choice","max_tokens","stream","temperature","top_p","stop_sequences"),
        "gemini",Set.of("contents","systemInstruction","tools","toolConfig","generationConfig"));
    private static final Map<String,Set<String>> NATIVE_FIELDS=Map.of(
        "openai_chat",Set.of("model","messages","tools","tool_choice","max_tokens","max_completion_tokens","stream","stream_options","temperature","top_p","stop","parallel_tool_calls","reasoning_effort","seed","presence_penalty","frequency_penalty","user","metadata","store","n"),
        "openai_responses",Set.of("model","input","instructions","tools","tool_choice","max_output_tokens","stream","temperature","top_p","parallel_tool_calls","store","reasoning","text","metadata","service_tier","user","truncation"),
        "anthropic",Set.of("model","messages","system","tools","tool_choice","max_tokens","stream","temperature","top_p","top_k","stop_sequences","thinking","output_config","metadata","service_tier"),
        "gemini",Set.of("contents","systemInstruction","tools","toolConfig","generationConfig","safetySettings"));
    public enum Feature { TEXT, FUNCTIONS, PARALLEL_FUNCTIONS, USAGE, IMAGE, AUDIO, FILE, REASONING, OPAQUE_HISTORY, STRICT_SCHEMA }
    public static Disposition support(String ingress,String egress,Feature feature,boolean streaming){
        if(!PROTOCOLS.contains(ingress)||!PROTOCOLS.contains(egress))return Disposition.UNSUPPORTED;
        if(Set.of(Feature.OPAQUE_HISTORY,Feature.AUDIO,Feature.FILE,Feature.STRICT_SCHEMA).contains(feature))return Disposition.UNSUPPORTED;
        if(ingress.equals(egress))return Disposition.NATIVE_LOSSLESS;
        return crossPair(ingress,egress)&&Set.of(Feature.TEXT,Feature.FUNCTIONS,Feature.PARALLEL_FUNCTIONS,Feature.USAGE).contains(feature)?Disposition.TRANSLATED_LOSSLESS:Disposition.UNSUPPORTED;
    }
    public static boolean crossPair(String ingress,String egress){return ingress.equals("anthropic")&&egress.equals("openai_responses")||ingress.equals("openai_chat")&&egress.equals("anthropic");}
    /** Per-request decoding still validates nested fields, roles, identities, strict and values. */
    public static Disposition field(String ingress,String egress,String field){
        if(!PROTOCOLS.contains(ingress)||!PROTOCOLS.contains(egress))return Disposition.UNSUPPORTED;
        if(ingress.equals(egress))return NATIVE_FIELDS.get(ingress).contains(field)?Disposition.NATIVE_LOSSLESS:Disposition.UNSUPPORTED;
        if(!FIELDS.get(ingress).contains(field))return Disposition.UNSUPPORTED;
        if(!crossPair(ingress,egress))return Disposition.UNSUPPORTED;
        if(egress.equals("openai_responses")&&field.equals("stop_sequences"))return Disposition.UNSUPPORTED;
        return Disposition.TRANSLATED_LOSSLESS;
    }
    public static Set<String> coreFields(String ingress){return FIELDS.getOrDefault(ingress,Set.of());}
    private ProtocolCompatibility(){}
}
