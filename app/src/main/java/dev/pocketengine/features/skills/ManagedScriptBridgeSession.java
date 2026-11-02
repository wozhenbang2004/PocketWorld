package dev.pocketengine.features.skills;

import java.util.LinkedHashMap;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** One admitted script's bounded protocol state. Transport data cannot construct a controller,
 * approval, task identity or executor. Native callbacks retain those original objects. */
public final class ManagedScriptBridgeSession implements AutoCloseable {
    public interface Phone {Set<String> allowedTools();JSONObject invoke(String tool,JSONObject args)throws Exception;}
    @FunctionalInterface public interface Current {void check()throws Exception;}
    public interface Outputs {void progress(String text)throws Exception;JSONObject share(String path)throws Exception;}
    private final String token;private final Phone phone;private final Current current;private final Outputs outputs;private final Set<String> allowed;
    private final LinkedHashMap<String,String> answers=new LinkedHashMap<>(),requests=new LinkedHashMap<>();private boolean closed,unknown;private int phoneCalls;
    public ManagedScriptBridgeSession(String token,Phone phone,Current current,Outputs outputs){if(token==null||!token.matches("[A-Za-z0-9_-]{40,100}"))throw new IllegalArgumentException("bridge_token_invalid");this.token=token;this.phone=phone;this.current=java.util.Objects.requireNonNull(current);this.outputs=java.util.Objects.requireNonNull(outputs);allowed=phone==null?Set.of():Set.copyOf(phone.allowedTools());for(String tool:allowed)if(!tool.matches("phone_[a-z_]{1,60}"))throw new IllegalArgumentException("bridge_phone_tool_invalid");}
    public JSONArray allowedTools(){return new JSONArray(allowed.stream().sorted().toList());}
    /** The synchronized section intentionally serializes every native phone request. */
    public synchronized JSONObject handle(String wire)throws Exception{
        if(closed)throw new SecurityException("bridge_closed");current.check();if(wire==null||wire.length()>16384)throw new SecurityException("bridge_request_too_large");
        JSONTokener parser=new JSONTokener(wire);Object raw=parser.nextValue();if(!(raw instanceof JSONObject)||parser.nextClean()!=0)throw new SecurityException("bridge_json_invalid");JSONObject request=(JSONObject)raw;
        if(!(request.opt("token") instanceof String)||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),request.getString("token").getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new SecurityException("bridge_token_invalid");
        String id=request.optString("requestId"),method=request.optString("method");if(!id.matches("[a-zA-Z0-9_-]{1,80}")||!Set.of("phone","progress","share","info").contains(method))throw new SecurityException("bridge_request_invalid");
        for(var keys=request.keys();keys.hasNext();)if(!Set.of("token","requestId","method","tool","args","text","path").contains(keys.next()))throw new SecurityException("bridge_identity_or_field_not_allowed");
        JSONObject clean=new JSONObject(request.toString());clean.remove("token");String fingerprint=canonical(clean);
        if(requests.containsKey(id)){if(!requests.get(id).equals(fingerprint))throw new SecurityException("bridge_request_id_changed");return new JSONObject(answers.get(id));}
        if(requests.size()>=64)throw new SecurityException("bridge_request_budget_exhausted");requests.put(id,fingerprint);
        JSONObject answer;boolean phoneEntered=false;
        try{current.check();switch(method){
            case "info":answer=new JSONObject().put("ok",true).put("phoneTools",allowedTools()).put("maxPhoneCalls",32).put("progress","original_task_only").put("share","approved_output_snapshot_deferred_until_original_receipt");break;
            case "progress":String text=request.getString("text");if(text.isBlank()||text.length()>500)throw new SecurityException("bridge_progress_invalid");outputs.progress(text);answer=new JSONObject().put("ok",true).put("recorded",true);break;
            case "share":String path=request.getString("path");if(path.length()>240)throw new SecurityException("bridge_share_path_invalid");answer=outputs.share(path);break;
            default:
                String tool=request.getString("tool");if(unknown)throw new SecurityException("bridge_original_effect_unknown");if(phone==null||!allowed.contains(tool)||!allowed.equals(phone.allowedTools()))throw new SecurityException("bridge_phone_not_allowed");if(phoneCalls>=32)throw new SecurityException("bridge_phone_budget_exhausted");
                JSONObject args=request.getJSONObject("args");if(args.toString().length()>8192)throw new SecurityException("bridge_phone_args_too_large");for(var keys=args.keys();keys.hasNext();){String key=keys.next();if(key.startsWith("_")||Set.of("taskId","runId","epoch","ownerId","spaceId","scope","control","grant").contains(key))throw new SecurityException("bridge_phone_identity_not_allowed");}
                phoneCalls++;phoneEntered=true;try{answer=phone.invoke(tool,new JSONObject(args.toString()));if(answer==null)throw new IllegalStateException("bridge_phone_receipt_missing");if("unknown".equals(answer.optString("status"))||answer.optBoolean("effectUnknown")){unknown=true;answer.put("ok",false).put("status","unknown").put("replayAllowed",false);}}catch(Exception uncertain){unknown=true;throw uncertain;}break;
        }}catch(SecurityException refusal){answer=new JSONObject().put("ok",false).put("status",phoneEntered?"unknown":"rejected").put("reason",refusal.getMessage()).put("replayAllowed",false);}
        catch(Exception uncertain){answer=new JSONObject().put("ok",false).put("status","unknown").put("replayAllowed",false);}
        if(answer.toString().length()>32000){unknown|=method.equals("phone");answer=new JSONObject().put("ok",false).put("status",method.equals("phone")?"unknown":"rejected").put("reason","bridge_result_budget_exceeded").put("replayAllowed",false);}
        answers.put(id,answer.toString());return new JSONObject(answer.toString());
    }
    private static String canonical(JSONObject input)throws Exception{java.util.List<String> keys=new java.util.ArrayList<>();input.keys().forEachRemaining(keys::add);keys.sort(String::compareTo);JSONArray rows=new JSONArray();for(String key:keys){Object value=input.get(key);rows.put(new JSONArray().put(key).put(value instanceof JSONObject?canonical((JSONObject)value):value));}return rows.toString();}
    @Override public synchronized void close(){closed=true;}
}
