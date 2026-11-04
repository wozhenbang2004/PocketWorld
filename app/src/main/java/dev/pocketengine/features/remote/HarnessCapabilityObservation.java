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
    public static final String GEMINI_WORKER="gemini_acp", GEMINI_BACKEND="gemini_cli_acp_v1", GEMINI_VERSION="0.66.0", GEMINI_PROTOCOL="gemini_acp_v1";
    private static final String SELECTION_FORMAT="pocketworld.remote-backend-selection.v1";
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
        if(GEMINI_WORKER.equals(worker))return gemini(profileId,environmentId,environmentBinding,probe);
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

    /** Value projection only. RemoteModule must still own the original, unexpired native probe. */
    public JSONObject backendSelection()throws Exception {
        JSONObject observed=view().optJSONObject("backendObservation");
        if(observed==null||observed.opt("available")!=Boolean.TRUE)throw new SecurityException("gemini_backend_observation_unavailable");
        return checkedBackendSelection(new JSONObject().put("format",SELECTION_FORMAT).put("backendId",GEMINI_BACKEND).put("backendVersion",GEMINI_VERSION).put("protocolVersion",1)
            .put("installObservation",observed.getJSONObject("installObservation")).put("configurationRevision",observed.getString("configurationRevision"))
            .put("serviceCapabilityRevision",observed.getString("serviceCapabilityRevision")).put("capabilityRevision",revision)
            .put("model",observed.getString("requestedModel")).put("modeId","default").put("readOnly",false));
    }
    /** Strict public tuple validation, not authority reconstruction from JSON. */
    public static JSONObject checkedBackendSelection(JSONObject raw)throws Exception {
        exact(raw,"format","backendId","backendVersion","protocolVersion","installObservation","configurationRevision","serviceCapabilityRevision","capabilityRevision","model","modeId","readOnly");
        fixed(raw,"format",SELECTION_FORMAT);fixed(raw,"backendId",GEMINI_BACKEND);fixed(raw,"backendVersion",GEMINI_VERSION);integer(raw,"protocolVersion",1,1);
        fixed(raw,"modeId","default");bool(raw,"readOnly",false);bounded(raw,"model",200,true);
        for(String key:new String[]{"configurationRevision","serviceCapabilityRevision","capabilityRevision"})hash(raw,key,false);
        JSONObject install=installation(raw.getJSONObject("installObservation"));if(!"observed".equals(install.getString("state")))throw new SecurityException("gemini_installation_unverified");
        return new JSONObject(raw.toString());
    }
    public static void requireSameBackendSelection(JSONObject expected,JSONObject actual)throws Exception {
        if(expected==null||actual==null||!canonical(checkedBackendSelection(expected)).equals(canonical(checkedBackendSelection(actual))))throw new SecurityException("原后端选择已变化，未采用另一回执或执行配置");
    }
    /** Complete original permission data. Its service hash is opaque; the native SQL claim binds this whole body. */
    public static JSONObject checkedBackendApproval(JSONObject source,JSONObject approval)throws Exception {
        checkedBackendSelection(source.getJSONObject("backendSelection"));fixed(source,"worker",GEMINI_WORKER);fixed(source,"protocol",GEMINI_PROTOCOL);bool(source,"readOnly",false);
        bool(source,"protocolVerified",true);bool(source,"controlProtocolVerified",true);bool(source,"sessionIdentityConfirmed",true);
        fixed(approval,"protocol",GEMINI_PROTOCOL);fixed(approval,"method","acp/session/request_permission");fixed(approval,"backendId",GEMINI_BACKEND);
        String session=bounded(source,"sessionId",200,false),prompt=bounded(source,"promptRequestId",200,false);fixed(approval,"sessionId",session);fixed(approval,"promptRequestId",prompt);
        String request=bounded(approval,"requestId",36,false);if(!request.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new SecurityException("gemini_permission_native_request_invalid");hash(approval,"requestHash",false);
        JSONObject tool=approval.getJSONObject("toolCall");String toolId=bounded(approval,"toolCallId",200,false);fixed(tool,"toolCallId",toolId);
        JSONArray options=approval.getJSONArray("options");if(options.length()<1||options.length()>8)throw new SecurityException("gemini_permission_options_invalid");
        Set<String> ids=new TreeSet<>();String allow="",reject="";int allows=0,rejects=0;
        for(int i=0;i<options.length();i++){
            JSONObject option=options.getJSONObject(i);exact(option,"optionId","kind","name");String id=bounded(option,"optionId",200,false);bounded(option,"name",200,false);
            if(!ids.add(id))throw new SecurityException("gemini_permission_option_duplicate");String kind=oneOf(option,"kind","allow_once","reject_once","allow_always","reject_always");
            if(kind.equals("allow_once")){allow=id;allows++;}if(kind.equals("reject_once")){reject=id;rejects++;}
        }
        bool(approval,"canAccept",allows==1);fixed(approval,"allowOnceOptionId",allows==1?allow:"");fixed(approval,"rejectOnceOptionId",rejects==1?reject:"");bool(approval,"scopeEnforced",false);bool(approval,"osReadIsolationVerified",false);
        JSONObject params=new JSONObject().put("sessionId",session).put("toolCall",tool).put("options",options);
        if(params.toString().getBytes(StandardCharsets.UTF_8).length>32768)throw new SecurityException("gemini_permission_body_too_large");
        return new JSONObject(approval.toString());
    }
    private static HarnessCapabilityObservation gemini(String profileId,String environmentId,String binding,JSONObject probe)throws Exception {
        EnumMap<Fact,Truth> facts=new EnumMap<>(Fact.class);for(Fact fact:Fact.values())facts.put(fact,Truth.UNKNOWN);
        JSONObject identity=new JSONObject().put("profileId",profileId).put("environmentId",environmentId).put("worker",GEMINI_WORKER).put("environmentBinding",binding);
        JSONObject observed=null;
        if(probe!=null){
            String profileBinding=hash(probe,"profileBinding",false);identity.put("profileBinding",profileBinding);
            observed=geminiObservation(probe.getJSONObject("backendObservation"),environmentId,binding);
            if(!observed.get("available").equals(probe.opt("available"))||!Boolean.valueOf("verified".equals(observed.getJSONObject("protocol").getString("state"))).equals(probe.opt("protocolVerified")))throw new SecurityException("gemini_probe_facts_disagree");
            facts.put(Fact.AVAILABLE,Boolean.TRUE.equals(observed.opt("available"))?Truth.TRUE:Truth.FALSE);
            String protocol=observed.getJSONObject("protocol").getString("state");facts.put(Fact.PROTOCOL_VERIFIED,protocol.equals("verified")?Truth.TRUE:protocol.equals("mismatch")?Truth.FALSE:Truth.UNKNOWN);
            facts.put(Fact.CONFIG_READ_VERIFIED,observed.getString("configurationRevision").isEmpty()?Truth.UNKNOWN:Truth.TRUE);
            facts.put(Fact.MODEL_INFERENCE_VERIFIED,Truth.FALSE);
        }
        JSONObject truth=new JSONObject();for(Fact fact:Fact.values())truth.put(fact.key,facts.get(fact).name());
        JSONObject body=new JSONObject().put("format","pocketworld.harness-capability-observation.v1").put("source","original_companion_worker_probe").put("identity",identity)
            .put("adapterSupport","implemented").put("protocolVersion",observed!=null&&"verified".equals(observed.getJSONObject("protocol").getString("state"))?1:"unknown")
            .put("installationState",observed==null?"unknown":observed.getJSONObject("installObservation").getString("state")).put("observedCliVersion",observed==null?JSONObject.NULL:observed.getJSONObject("installObservation").getString("packageVersion"))
            .put("facts",truth).put("modelOption",new JSONObject().put("optionId","model").put("valueType","string").put("state","not_checked").put("choices",new JSONArray()).put("catalogComplete",Truth.UNKNOWN.name()).put("supportsChangeWhileRunning",false).put("applicationSupport","initial_request_only"))
            .put("session",new JSONObject().put("activeSession","unknown").put("reattachSupport","unsupported").put("processReuse",Truth.FALSE.name()))
            .put("effectiveHostTools","none").put("reverseTerminalSupport","unsupported").put("executionAuthorized",false).put("configurationState",observed==null?"unknown":observed.getString("configurationRevision").isEmpty()?"unknown":"observed")
            .put("runtimeState","unknown");
        if(observed!=null)body.put("backendObservation",observed);
        return new HarnessCapabilityObservation(facts,List.of(),body);
    }
    private static JSONObject geminiObservation(JSONObject raw,String environmentId,String binding)throws Exception {
        exact(raw,"format","backendId","worker","backendVersion","protocolVersion","environmentId","environmentBinding","requestedModel","modeId","available","observedAt","expiresAt","installObservation","configurationRevision","profileRevision","serviceCapabilityRevision","protocol","authentication","model","mode","reason");
        if(raw.toString().getBytes(StandardCharsets.UTF_8).length>16384)throw new SecurityException("gemini_probe_bound_exceeded");
        fixed(raw,"format","pocketworld.remote-backend-observation.v1");fixed(raw,"backendId",GEMINI_BACKEND);fixed(raw,"worker",GEMINI_WORKER);fixed(raw,"backendVersion",GEMINI_VERSION);integer(raw,"protocolVersion",1,1);
        fixed(raw,"environmentId",environmentId);fixed(raw,"environmentBinding",binding);fixed(raw,"modeId","default");String model=bounded(raw,"requestedModel",200,true);
        long at=integer(raw,"observedAt",0,Long.MAX_VALUE-300000),expires=integer(raw,"expiresAt",0,Long.MAX_VALUE);if(expires-at!=300000)throw new SecurityException("gemini_probe_expiry_invalid");
        JSONObject install=installation(raw.getJSONObject("installObservation"));String config=hash(raw,"configurationRevision",true);fixed(raw,"profileRevision",config);String service=hash(raw,"serviceCapabilityRevision",true);
        JSONObject protocol=raw.getJSONObject("protocol");exact(protocol,"state","agentName","agentVersion","wireVersion");String protocolState=oneOf(protocol,"state","not_checked","verified","mismatch","unknown");
        boolean verified=protocolState.equals("verified");fixed(protocol,"agentName",verified?"gemini-cli":"");fixed(protocol,"agentVersion",verified?GEMINI_VERSION:"");integer(protocol,"wireVersion",verified?1:0,verified?1:0);
        JSONObject auth=raw.getJSONObject("authentication");exact(auth,"state","authenticated","source");String authentication=oneOf(auth,"state","credential_present","credential_missing","unknown");bool(auth,"authenticated",false);fixed(auth,"source","owner_environment_presence_only");
        JSONObject selected=raw.getJSONObject("model");exact(selected,"state","requested","current","inferenceVerified");fixed(selected,"state","not_checked");fixed(selected,"requested",model);fixed(selected,"current","");bool(selected,"inferenceVerified",false);
        JSONObject mode=raw.getJSONObject("mode");exact(mode,"state","requested","current");fixed(mode,"state","not_checked");fixed(mode,"requested","default");fixed(mode,"current","");
        JSONObject reason=raw.getJSONObject("reason");exact(reason,"code","message");code(reason,"code");bounded(reason,"message",1000,true);
        boolean available=booleanValue(raw,"available");if(available&&(!"observed".equals(install.getString("state"))||!verified||!authentication.equals("credential_present")||config.isEmpty()||service.isEmpty())||!available&&!service.isEmpty())throw new SecurityException("gemini_available_without_observed_source");
        return new JSONObject(raw.toString());
    }
    private static JSONObject installation(JSONObject raw)throws Exception {
        exact(raw,"format","state","identity","kind","packageVersion","nodeContentVerified","packageContentVerified","executionChainVerified","osSandboxVerified","reason");
        fixed(raw,"format","pocketworld.remote-install-observation.v1");fixed(raw,"kind","fixed_node_package_bytes_v1");String state=oneOf(raw,"state","observed","missing","unsupported","changed","unavailable");
        boolean observed=state.equals("observed");String identity=hash(raw,"identity",!observed);if(!observed&&!identity.isEmpty())throw new SecurityException("gemini_installation_identity_without_observation");fixed(raw,"packageVersion",observed?GEMINI_VERSION:"");
        boolean node=booleanValue(raw,"nodeContentVerified"),pkg=booleanValue(raw,"packageContentVerified");if(observed&&(!node||!pkg))throw new SecurityException("gemini_installation_bytes_unverified");
        bool(raw,"executionChainVerified",false);bool(raw,"osSandboxVerified",false);code(raw,"reason");return new JSONObject(raw.toString());
    }
    private static void exact(JSONObject raw,String... fields){if(raw==null)throw new SecurityException("gemini_observation_shape_invalid");Set<String> keys=new TreeSet<>();raw.keys().forEachRemaining(keys::add);if(!keys.equals(Set.of(fields)))throw new SecurityException("gemini_observation_shape_invalid");}
    private static void fixed(JSONObject raw,String key,String expected){if(!(raw.opt(key) instanceof String)||!expected.equals(raw.opt(key)))throw new SecurityException("gemini_observation_identity_invalid:"+key);}
    private static boolean booleanValue(JSONObject raw,String key){if(!(raw.opt(key) instanceof Boolean))throw new SecurityException("gemini_observation_boolean_invalid:"+key);return (Boolean)raw.opt(key);}
    private static void bool(JSONObject raw,String key,boolean expected){if(booleanValue(raw,key)!=expected)throw new SecurityException("gemini_observation_claim_unsupported:"+key);}
    private static long integer(JSONObject raw,String key,long min,long max){Object value=raw.opt(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min||((Number)value).longValue()>max)throw new SecurityException("gemini_observation_integer_invalid:"+key);return ((Number)value).longValue();}
    private static String bounded(JSONObject raw,String key,int max,boolean empty){Object value=raw.opt(key);if(!(value instanceof String)||((String)value).length()>max||!empty&&((String)value).isBlank()||!((String)value).isEmpty()&&!modelTextLimit((String)value,max))throw new SecurityException("gemini_observation_text_invalid:"+key);return (String)value;}
    private static boolean modelTextLimit(String value,int max){if(value.length()>max||value.codePoints().anyMatch(Character::isISOControl))return false;for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    private static String hash(JSONObject raw,String key,boolean empty){String value=bounded(raw,key,64,empty);if(!(empty&&value.isEmpty())&&!value.matches("[a-f0-9]{64}"))throw new SecurityException("gemini_observation_digest_invalid:"+key);return value;}
    private static String code(JSONObject raw,String key){String value=bounded(raw,key,120,false);if(!value.matches("[A-Za-z0-9_.:-]{1,120}"))throw new SecurityException("gemini_observation_reason_invalid");return value;}
    private static String oneOf(JSONObject raw,String key,String... allowed){String value=bounded(raw,key,80,false);if(!Set.of(allowed).contains(value))throw new SecurityException("gemini_observation_state_invalid:"+key);return value;}

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
