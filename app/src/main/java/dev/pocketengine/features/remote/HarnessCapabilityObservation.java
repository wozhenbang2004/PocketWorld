package dev.pocketengine.features.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** A bounded observation of the original Companion probe, never an execution or apply grant. */
public final class HarnessCapabilityObservation {
    public enum Truth { TRUE, FALSE, UNKNOWN }
    public enum Fact {
        AVAILABLE("available"), PROTOCOL_VERIFIED("protocolVerified"), ACCOUNT_CONFIGURED("accountConfigured"),
        CONFIG_READ_VERIFIED("configReadVerified"), CLI_FLAGS_VERIFIED("cliFlagsVerified"),
        SELECTED_MODEL_AVAILABLE("selectedModelAvailable"), MODEL_INFERENCE_VERIFIED("modelInferenceVerified");
        final String key;
        Fact(String key){this.key=key;}
    }
    private final EnumMap<Fact,Truth> facts;
    private final List<String> models;
    private final String value,revision;

    private HarnessCapabilityObservation(EnumMap<Fact,Truth> facts,List<String> models,JSONObject body)throws Exception {
        this.facts=new EnumMap<>(facts);this.models=List.copyOf(models);
        revision=digest(canonical(body));body.put("capabilityRevision",revision);value=body.toString();
    }

    /** The caller supplies its captured native environment identity, not identity from model text.
     * A missing probe remains unknown. A received probe must match all of that exact scope. */
    public static HarnessCapabilityObservation fromProbe(String profileId,String environmentId,String worker,
                                                         String environmentBinding,JSONObject probe)throws Exception {
        identifier(profileId);identifier(environmentId);identifier(worker);
        if(environmentBinding==null||!environmentBinding.matches("[a-f0-9]{64}"))throw new SecurityException("harness_environment_binding_required");
        if(probe!=null){
            if(probe.length()>64)throw new SecurityException("harness_probe_bound_exceeded");
            for(var expected:java.util.Map.of("environmentId",environmentId,"worker",worker,"environmentBinding",environmentBinding).entrySet())
                if(!(probe.opt(expected.getKey()) instanceof String)||!expected.getValue().equals(probe.opt(expected.getKey())))throw new SecurityException("harness_probe_identity_mismatch");
            if(Boolean.TRUE.equals(probe.opt("modelInferenceVerified")))throw new SecurityException("worker_probe_cannot_verify_inference");
        }
        boolean codex=worker.equals("codex_app_server"),claude=worker.equals("claude"),implemented=codex||claude;
        EnumMap<Fact,Truth> facts=new EnumMap<>(Fact.class);
        for(Fact fact:Fact.values())facts.put(fact,implemented?read(probe,fact.key):Truth.UNKNOWN);
        // A failed protocol probe cannot establish whether an account is absent or merely unreadable.
        if(codex&&facts.get(Fact.PROTOCOL_VERIFIED)!=Truth.TRUE){
            facts.put(Fact.ACCOUNT_CONFIGURED,Truth.UNKNOWN);facts.put(Fact.CONFIG_READ_VERIFIED,Truth.UNKNOWN);
            facts.put(Fact.SELECTED_MODEL_AVAILABLE,Truth.UNKNOWN);
        }
        // The original Claude probe reads version/help only, not account, catalog or stream handshake.
        if(claude){
            facts.put(Fact.ACCOUNT_CONFIGURED,Truth.UNKNOWN);facts.put(Fact.CONFIG_READ_VERIFIED,Truth.UNKNOWN);
            facts.put(Fact.SELECTED_MODEL_AVAILABLE,Truth.UNKNOWN);
            if(facts.get(Fact.PROTOCOL_VERIFIED)==Truth.TRUE)throw new SecurityException("claude_cli_probe_cannot_verify_stream_protocol");
        }
        if(codex&&facts.get(Fact.SELECTED_MODEL_AVAILABLE)==Truth.FALSE&&read(probe,"modelCatalogTruncated")!=Truth.FALSE)
            facts.put(Fact.SELECTED_MODEL_AVAILABLE,Truth.UNKNOWN);
        List<String> models=List.of();String catalogState=implemented?"unknown":"adapter_unimplemented";
        Truth complete=Truth.UNKNOWN;
        if(codex&&facts.get(Fact.PROTOCOL_VERIFIED)==Truth.TRUE&&facts.get(Fact.CONFIG_READ_VERIFIED)==Truth.TRUE){
            List<String> observed=catalog(probe==null?null:probe.opt("models"));
            if(observed!=null){models=observed;catalogState="observed_catalog";Truth truncated=read(probe,"modelCatalogTruncated");complete=truncated==Truth.TRUE?Truth.FALSE:truncated==Truth.FALSE?Truth.TRUE:Truth.UNKNOWN;}
        }
        JSONObject identity=new JSONObject().put("profileId",profileId).put("environmentId",environmentId).put("worker",worker).put("environmentBinding",environmentBinding);
        JSONObject observed=new JSONObject();for(Fact fact:Fact.values())observed.put(fact.key,facts.get(fact).name());
        Object cliVersion=JSONObject.NULL;
        if(claude&&probe!=null&&probe.opt("cliVersion") instanceof String){String version=probe.getString("cliVersion");if(version.length()<=80&&version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9._-]+)?"))cliVersion=version;}
        JSONObject option=new JSONObject().put("optionId","model").put("valueType","string").put("state",catalogState)
                .put("choices",new JSONArray(models)).put("catalogComplete",complete.name()).put("observedValuesOnly",true)
                .put("supportsChangeWhileRunning",false).put("applicationSupport","adapter_unimplemented");
        JSONObject session=new JSONObject().put("activeSession","unknown").put("continuationGate","original_settled_job_receipt")
                .put("reattachSupport","adapter_unimplemented").put("processReuse",implemented?Truth.FALSE.name():Truth.UNKNOWN.name());
        JSONObject body=new JSONObject().put("format","pocketworld.harness-capability-observation.v1").put("source","original_companion_worker_probe")
                .put("identity",identity).put("adapterSupport",implemented?"implemented":"adapter_unimplemented")
                .put("protocolVersion","unknown").put("installationState","unknown").put("observedCliVersion",cliVersion)
                .put("facts",observed).put("modelOption",option).put("session",session)
                .put("effectiveHostTools","unknown").put("reverseTerminalSupport","adapter_unimplemented")
                .put("executionAuthorized",false).put("configurationState","unknown").put("runtimeState","unknown");
        return new HarnessCapabilityObservation(facts,models,body);
    }
    public Truth fact(Fact fact){return facts.get(java.util.Objects.requireNonNull(fact));}
    public List<String> observedModels(){return models;}
    public boolean hasObservedModel(String model){return models.contains(model);}
    public String revision(){return revision;}
    public JSONObject view()throws Exception{return new JSONObject(value);}

    private static Truth read(JSONObject value,String key){Object raw=value==null?null:value.opt(key);return Boolean.TRUE.equals(raw)?Truth.TRUE:Boolean.FALSE.equals(raw)?Truth.FALSE:Truth.UNKNOWN;}
    private static void identifier(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("harness_native_identity_required");}
    /** An invalid or truncated wire shape never becomes a partially trusted option list. */
    private static List<String> catalog(Object raw)throws Exception {
        if(!(raw instanceof JSONArray)||((JSONArray)raw).length()>100)return null;
        TreeSet<String> unique=new TreeSet<>();JSONArray rows=(JSONArray)raw;
        for(int i=0;i<rows.length();i++){
            Object row=rows.get(i);if(!(row instanceof JSONObject))return null;
            Object id=((JSONObject)row).opt("id");if(!(id instanceof String)||!modelText((String)id)||!unique.add((String)id))return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
    private static boolean modelText(String value){
        if(value.isBlank()||value.length()>200||value.codePoints().anyMatch(Character::isISOControl))return false;
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}
        return true;
    }
    private static String canonical(Object value)throws Exception {
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;Set<String> keys=new TreeSet<>();object.keys().forEachRemaining(keys::add);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):value.toString();
    }
    private static String digest(String value)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);
        for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();
    }
}
