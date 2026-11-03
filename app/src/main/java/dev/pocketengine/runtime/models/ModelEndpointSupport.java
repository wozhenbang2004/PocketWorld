package dev.pocketengine.runtime.models;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional provider declaration. Endpoint tokens are labels only, never URLs or credential destinations. */
public record ModelEndpointSupport(Set<String> endpointTokens, String sourceRef, long observedAtMillis) {
    private static final Set<String> RECOGNIZED = Set.of("/chat/completions", "/responses", "/messages");
    public ModelEndpointSupport {
        if(endpointTokens==null||endpointTokens.size()>3||!RECOGNIZED.containsAll(endpointTokens)||sourceRef==null||sourceRef.length()>4096||observedAtMillis<1)
            throw new IllegalArgumentException("invalid_endpoint_support_declaration");
        endpointTokens=Set.copyOf(endpointTokens);
    }
    /** Missing, malformed or partly unfamiliar declarations stay unknown and cannot exclude a model. */
    public static ModelEndpointSupport parse(Object raw,String sourceRef,long at) {
        if(!(raw instanceof JSONArray array)||array.length()>3)return null;
        Set<String> tokens=new LinkedHashSet<>();
        for(int i=0;i<array.length();i++){
            Object value=array.opt(i);if(!(value instanceof String token)||!RECOGNIZED.contains(token)||!tokens.add(token))return null;
        }
        return new ModelEndpointSupport(tokens,sourceRef,at);
    }
    public boolean supports(ModelBinding.Protocol protocol) {
        return endpointTokens.contains(switch(protocol){case OPENAI_CHAT->"/chat/completions";case OPENAI_RESPONSES->"/responses";case ANTHROPIC->"/messages";case GEMINI->"/generateContent";});
    }
    public JSONObject toJson()throws org.json.JSONException{return new JSONObject().put("endpointTokens",new JSONArray(new java.util.TreeSet<>(endpointTokens))).put("source","PROVIDER_CATALOG").put("sourceRef",sourceRef).put("observedAtMillis",observedAtMillis);}
    public static ModelEndpointSupport fromJson(JSONObject row)throws org.json.JSONException{
        if(!"PROVIDER_CATALOG".equals(row.getString("source")))throw new IllegalArgumentException("invalid_endpoint_source");
        ModelEndpointSupport parsed=parse(row.get("endpointTokens"),row.getString("sourceRef"),ModelFacts.positiveInteger(row.get("observedAtMillis")));
        if(parsed==null)throw new IllegalArgumentException("invalid_endpoint_support_cache");return parsed;
    }
}
