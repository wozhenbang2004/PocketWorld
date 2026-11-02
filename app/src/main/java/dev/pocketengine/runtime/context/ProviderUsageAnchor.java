package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One run-local, retractable provider input anchor. Raw byte limits and receipts remain unchanged. */
public final class ProviderUsageAnchor {
    private record Anchor(String route,String tools,List<String> messages,long input,String call){}
    private Anchor anchor;
    public synchronized void clear(){anchor=null;}
    public InferenceCapture.Target observe(InferenceCapture.Target original,JSONArray messages,JSONArray tools,ModelConfig selected)throws Exception {
        String route=route(selected),schema=tools==null?"[]":tools.toString();List<String> prefix=rows(messages);
        return InferenceCapture.observe(original,new InferenceCapture.Sink(){
            public void started(InferenceReceipt receipt){}
            public void finished(InferenceReceipt receipt)throws Exception {
                if(receipt.outcome!=InferenceReceipt.Outcome.COMPLETED)return;
                JSONObject nativeReceipt=receipt.toJson(),actualRoute=nativeReceipt.getJSONObject("route"),usage=nativeReceipt.getJSONObject("usage");
                // A group/alias that actually selected another model cannot seed the captured route.
                if(!selected.model.equals(actualRoute.optString("requestedModel"))||!selected.model.equals(actualRoute.optString("responseModel"))||actualRoute.optBoolean("identityRedacted"))return;
                if(selected.usesAccount()&&(!selected.accountModel.accountId.equals(actualRoute.optString("accountId"))||selected.accountModel.accountVersion!=actualRoute.optLong("accountVersion",-1)))return;
                JSONObject input=usage.optJSONObject("counters");input=input==null?null:input.optJSONObject("INPUT");
                long count=counter(input);if(count<1)return;
                if(!usage.optBoolean("inputIncludesCacheRead",true)){long cached=counter(usage.getJSONObject("counters").optJSONObject("CACHE_READ"));if(cached<0)return;count=Math.addExact(count,cached);}
                if(!usage.optBoolean("inputIncludesCacheWrite",true)){long cached=counter(usage.getJSONObject("counters").optJSONObject("CACHE_WRITE"));if(cached<0)return;count=Math.addExact(count,cached);}
                synchronized(ProviderUsageAnchor.this){anchor=new Anchor(route,schema,List.copyOf(prefix),count,receipt.callId);}
            }
        });
    }
    public synchronized ContextWindow.UsageCharge charge(JSONArray messages,JSONArray tools,ModelConfig selected)throws Exception {
        Anchor current=anchor;if(current==null||!current.route.equals(route(selected))||!current.tools.equals(tools==null?"[]":tools.toString())||messages.length()<current.messages.size())return null;
        for(int i=0;i<current.messages.size();i++)if(!current.messages.get(i).equals(messages.getJSONObject(i).toString()))return null;
        long added=0;for(int i=current.messages.size();i<messages.length();i++)added=Math.addExact(added,messages.getJSONObject(i).toString().getBytes(StandardCharsets.UTF_8).length+1L);
        long reserve=Math.max(128,(current.input+9)/10);return new ContextWindow.UsageCharge(Math.addExact(Math.addExact(current.input,added),reserve),current.input,current.call,current.messages.size());
    }
    private static long counter(JSONObject value){if(value==null||!"provider_reported".equals(value.optString("source"))||!"FINAL".equals(value.optString("stage")))return -1;Object n=value.opt("value");return n instanceof Integer||n instanceof Long?((Number)n).longValue():-1;}
    private static List<String> rows(JSONArray messages)throws Exception {List<String> rows=new ArrayList<>();for(int i=0;i<messages.length();i++)rows.add(messages.getJSONObject(i).toString());return rows;}
    private static String route(ModelConfig config)throws Exception {
        JSONObject identity=new JSONObject().put("provider",config.provider).put("model",config.model).put("endpoint",config.baseUrl).put("credentialDigest",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(config.apiKey.getBytes(StandardCharsets.UTF_8))));
        if(config.capabilityBinding!=null)identity.put("binding",config.capabilityBinding.toJson());if(config.usesAccount())identity.put("account",config.accountModel.toJson());
        return identity.toString();
    }
}
