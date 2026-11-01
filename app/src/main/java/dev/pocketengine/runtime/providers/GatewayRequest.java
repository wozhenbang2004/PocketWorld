package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Prepared model input only. Tools remain owned by the original Runner or calling Harness. */
public final class GatewayRequest {
    public interface Reprepare { GatewayRequest prepare(ModelConfig candidate)throws Exception; }
    public interface Admission { void check()throws Exception; }
    public final String ingressProtocol,egressProtocol,requestDigest,requestedAlias;
    public final ModelConfig config;
    public final JSONObject client,wire;
    public final JSONArray messages,tools;
    public final URL endpoint;
    public final boolean stream,functionHistory;
    public final ProtocolCompatibility.Disposition disposition;
    private final Reprepare reprepare;
    private final Admission admission;
    public GatewayRequest(ModelConfig config,String ingress,JSONObject client,JSONObject wire,JSONArray messages,
            JSONArray tools,URL endpoint,boolean stream,boolean history,Admission admission,Reprepare reprepare)throws Exception {
        this.config=config;ingressProtocol=ingress;egressProtocol=config.provider;
        this.client=new JSONObject(client.toString());this.wire=new JSONObject(wire.toString());
        this.messages=new JSONArray(messages.toString());this.tools=new JSONArray(tools.toString());
        this.endpoint=endpoint;this.stream=stream;functionHistory=history;this.admission=admission;this.reprepare=reprepare;
        requestedAlias=client.optString("model",config.model);
        disposition=ingress.equals(config.provider)?ProtocolCompatibility.Disposition.NATIVE_LOSSLESS:ProtocolCompatibility.Disposition.TRANSLATED_LOSSLESS;
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((ingress+"\n"+canonical(this.client)).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));requestDigest=hex.toString();
    }
    public GatewayRequest forCandidate(ModelConfig candidate)throws Exception{return reprepare.prepare(candidate);}
    public void revalidate()throws Exception{admission.check();}
    private static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){JSONObject j=(JSONObject)value;java.util.List<String> keys=new java.util.ArrayList<>();j.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder b=new StringBuilder("{");for(String key:keys){if(b.length()>1)b.append(',');b.append(JSONObject.quote(key)).append(':').append(canonical(j.get(key)));}return b.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)b.append(',');b.append(canonical(a.get(i)));}return b.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
}
