package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original run-local input anchor. It never changes the raw byte ceiling or the usage ledger. */
public final class ProviderUsageAnchor {
    private record Anchor(String route,String tools,List<String> messages,long input,String call){}
    private Anchor anchor;
    public synchronized void clear(){anchor=null;}

    public InferenceCapture.Target observe(InferenceCapture.Target original,JSONArray messages,JSONArray tools,ModelConfig selected)throws Exception {
        String route=route(selected),schema=tools==null?"[]":tools.toString();List<String> prefix=rows(messages);
        JSONObject compatibility=selected.publicJson().optJSONObject("compatibility");
        return InferenceCapture.observe(original,new InferenceCapture.Sink(){
            public void started(InferenceReceipt receipt){clear();}
            public void finished(InferenceReceipt receipt){
                try{
                    if(receipt.outcome!=InferenceReceipt.Outcome.COMPLETED){clear();return;}
                    JSONObject proof=receipt.toJson(),actual=proof.getJSONObject("route"),usage=proof.getJSONObject("usage");
                    String protocol=selected.usesAccount()?"openai_responses":selected.provider;
                    // No session-wide Realtime counters, aliases, missing model identity or
                    // unproved cache/tool-input accounting may price this physical request.
                    if(!Set.of("openai_chat","openai_responses","anthropic").contains(protocol)
                            ||!(selected.usesAccount()?"account":"api_key").equals(actual.optString("kind"))
                            ||!actual.optString("candidateId").isEmpty()
                            ||selected.capabilityBinding==null&&!actual.optString("connectionId").isEmpty()
                            ||!protocol.equals(usage.optString("protocol"))||!protocol.equals(actual.optString("protocol"))
                            ||!selected.model.equals(actual.optString("requestedModel"))||!selected.model.equals(actual.optString("responseModel"))
                            ||actual.optBoolean("identityRedacted")||!Boolean.TRUE.equals(usage.opt("reported"))){clear();return;}
                    JSONObject projection=actual.optJSONObject("providerProjection");
                    if(compatibility!=null?(projection==null||!compatibility.getString("id").equals(projection.optString("compatProfileId"))
                            ||compatibility.getLong("revision")!=projection.optLong("compatProfileRevision",-1))
                            :projection!=null&&!projection.optString("compatProfileId").isEmpty()){clear();return;}
                    if(selected.usesAccount()&&(!"account".equals(actual.optString("kind"))
                            ||!selected.accountModel.accountId.equals(actual.optString("accountId"))
                            ||selected.accountModel.accountVersion!=actual.optLong("accountVersion",-1))){clear();return;}
                    if(selected.capabilityBinding!=null){
                        JSONObject binding=selected.capabilityBinding.toJson();
                        for(String key:List.of("connectionId","providerId","credentialId","credentialRevision","installationId","installationRevision"))
                            if(!binding.get(key).equals(actual.opt(key))){clear();return;}
                    }
                    JSONObject counters=usage.getJSONObject("counters");long count=counter(counters.optJSONObject("INPUT"));
                    // Claude's INPUT excludes cache reads/writes. An explicit zero
                    // can still describe a nonempty, fully cached request.
                    if(count<0){clear();return;}
                    for(String kind:List.of("Read","Write")){
                        Object included=usage.opt("inputIncludesCache"+kind);if(!(included instanceof Boolean)){clear();return;}
                        if(Boolean.FALSE.equals(included)){
                            long cached=counter(counters.optJSONObject("CACHE_"+kind.toUpperCase(java.util.Locale.ROOT)));
                            if(cached<0){clear();return;}count=Math.addExact(count,cached);
                        }
                    }
                    if(count<1){clear();return;}
                    synchronized(ProviderUsageAnchor.this){anchor=new Anchor(route,schema,List.copyOf(prefix),count,receipt.callId);}
                }catch(Exception unavailable){
                    // This optional estimate must never replace a committed real receipt or
                    // turn successful provider completion into a ledger/transport failure.
                    clear();
                }
            }
        });
    }

    public synchronized ContextWindow.UsageCharge charge(JSONArray messages,JSONArray tools,ModelConfig selected){
        try{
            Anchor current=anchor;
            if(current==null)return null;
            if(!current.route.equals(route(selected))||!current.tools.equals(tools==null?"[]":tools.toString())
                    ||messages.length()<current.messages.size()){clear();return null;}
            for(int i=0;i<current.messages.size();i++)if(!current.messages.get(i).equals(messages.getJSONObject(i).toString())){clear();return null;}
            long added=0;
            for(int i=current.messages.size();i<messages.length();i++){
                JSONObject row=messages.getJSONObject(i);
                // The provider priced images in the exact old prefix, not new pixels.
                // URL bytes cannot price a new image. Drop this optional anchor and
                // let ContextWindow apply its existing image reserve/unknown-budget gate.
                if(hasImage(row)){clear();return null;}
                added=Math.addExact(added,row.toString().getBytes(StandardCharsets.UTF_8).length+1L);
            }
            long reserve=Math.max(128,Math.addExact(current.input,9)/10);
            return new ContextWindow.UsageCharge(Math.addExact(Math.addExact(current.input,added),reserve),current.input,current.call,current.messages.size());
        }catch(Exception unavailable){clear();return null;}
    }
    /** Match ContextWindow's canonical image shape; opaque provider metadata is not an image part. */
    private static boolean hasImage(JSONObject row){
        JSONArray parts=row.optJSONArray("content");
        for(int i=0;parts!=null&&i<parts.length();i++){
            JSONObject part=parts.optJSONObject(i),image=part==null?null:part.optJSONObject("image_url");
            if(part!=null&&"image_url".equals(part.optString("type"))&&image!=null
                    &&image.opt("url") instanceof String)return true;
        }
        return false;
    }
    private static long counter(JSONObject value){
        if(value==null||!"provider_reported".equals(value.optString("source"))||!"FINAL".equals(value.optString("stage")))return -1;
        Object raw=value.opt("value");return raw instanceof Integer||raw instanceof Long?((Number)raw).longValue():-1;
    }
    private static List<String> rows(JSONArray messages)throws Exception{
        List<String> rows=new ArrayList<>();for(int i=0;i<messages.length();i++)rows.add(messages.getJSONObject(i).toString());return rows;
    }
    private static String route(ModelConfig config)throws Exception{
        String credentialDigest=config.usesBedrock()?config.credentialIdentityDigest():java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(config.apiKey.getBytes(StandardCharsets.UTF_8)));
        JSONObject identity=new JSONObject().put("provider",config.provider).put("model",config.model).put("endpoint",config.baseUrl)
                .put("reasoning",config.reasoning.toJson()).put("maxOutputTokens",config.maxOutputTokens).put("credentialDigest",credentialDigest);
        JSONObject compatibility=config.publicJson().optJSONObject("compatibility");
        if(compatibility!=null)identity.put("compatibility",compatibility);
        if(config.capabilityBinding!=null)identity.put("binding",config.capabilityBinding.toJson());
        if(config.usesAccount())identity.put("account",config.accountModel.toJson());return identity.toString();
    }
}
