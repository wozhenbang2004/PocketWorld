package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Immutable, bounded SIWC HTTP context. Function declarations are data, never execution authority. */
public final class ResponsesRequest {
    public static final int MAX_REQUEST_BYTES=256*1024,MAX_ITEMS=128,MAX_FUNCTIONS=64;
    static final int MAX_REASONING_BYTES=64*1024,MAX_SUMMARY_PARTS=32;
    public static final int MAX_IMAGES=2,MAX_IMAGE_URL_CHARS=8*1024*1024;
    private final byte[] bytes;
    private final String modelId;
    private final boolean allowImages;
    private final dev.pocketengine.runtime.models.ReasoningOptions reasoning;
    private final int localOutputReserve,localImageReserve;
    private int imageCount,imageChars;
    private boolean textInput;
    private final Set<String> functions=new HashSet<>();

    /** Tools must be namespaces containing synchronous function tools, as required by the SIWC route. */
    public ResponsesRequest(String model,String instructions,JSONArray input,JSONArray tools)throws GatewayFailure {this(model,instructions,input,tools,false);}
    /** Only native screenshot-authorized callers may enable inline image input. The gateway checks catalog evidence again. */
    public ResponsesRequest(String model,String instructions,JSONArray input,JSONArray tools,boolean allowImages)throws GatewayFailure {
        this(model,instructions,input,tools,allowImages,dev.pocketengine.runtime.models.ReasoningOptions.DEFAULT,4096,0);
    }
    /** Native options, rechecked against same-token facts by AccountGateway before every POST. */
    public ResponsesRequest(String model,String instructions,JSONArray input,JSONArray tools,boolean allowImages,
                            dev.pocketengine.runtime.models.ReasoningOptions reasoning,int outputReserve,int imageReserve)throws GatewayFailure {
        this.modelId=model;this.allowImages=allowImages;this.reasoning=java.util.Objects.requireNonNull(reasoning);
        if(reasoning.mode()!=dev.pocketengine.runtime.models.ReasoningOptions.Mode.DEFAULT&&reasoning.mode()!=dev.pocketengine.runtime.models.ReasoningOptions.Mode.OPENAI_EFFORT||outputReserve<1||imageReserve<0||imageReserve>2000000)throw invalid();
        localOutputReserve=outputReserve;localImageReserve=imageReserve;
        try {
            if(model==null||!model.matches("[A-Za-z0-9_.:-]{1,160}")||instructions==null||instructions.length()>32_768||input==null||tools==null)throw invalid();
            textInput=!instructions.isEmpty();unicode(instructions);int[] budget={0,0};int copyLimit=allowImages?MAX_IMAGES*MAX_IMAGE_URL_CHARS+MAX_REQUEST_BYTES:MAX_REQUEST_BYTES;
            JSONArray context=(JSONArray)copy(input,0,budget,copyLimit),declarations=(JSONArray)copy(tools,0,budget,copyLimit);
            validateTools(declarations);validateInput(context);
            JSONObject wire=new JSONObject().put("model",model).put("instructions",instructions).put("input",context).put("tools",declarations)
                .put("store",false).put("stream",true);
            if(reasoning.mode()!=dev.pocketengine.runtime.models.ReasoningOptions.Mode.DEFAULT)wire.put("reasoning",new JSONObject().put("effort",reasoning.level()));
            bytes=wire.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length-imageChars>MAX_REQUEST_BYTES||bytes.length>MAX_REQUEST_BYTES+MAX_IMAGES*MAX_IMAGE_URL_CHARS)throw invalid();
        }catch(GatewayFailure failure){throw failure;}catch(Exception invalid){throw invalid();}
    }
    public boolean hasExplicitReasoning(){return reasoning.mode()!=dev.pocketengine.runtime.models.ReasoningOptions.Mode.DEFAULT;}
    /** Whole actual wire framing is charged. Inline base64 uses an explicit local image reserve. */
    public void admit(dev.pocketengine.runtime.models.AccountCatalogFacts current)throws GatewayFailure {
        if(current==null){if(hasExplicitReasoning())throw new GatewayFailure("ACCOUNT_MODEL_OPTION_UNAVAILABLE");return;}
        if(!modelId.equals(current.modelId))throw new GatewayFailure("STALE_MODEL_CATALOG");
        try{current.validateOptions(reasoning);}catch(dev.pocketengine.runtime.models.ModelRequestPolicy.Rejected unavailable){throw new GatewayFailure("ACCOUNT_MODEL_OPTION_UNAVAILABLE");}
        if(current.inputModalities.known&&(textInput||!functions.isEmpty())&&!current.inputModalities.value.contains("text"))throw new GatewayFailure("ACCOUNT_TEXT_CAPABILITY_UNSUPPORTED");
        if(current.facts.toolCalls().known&&!current.facts.toolCalls().value&&!functions.isEmpty())throw new GatewayFailure("ACCOUNT_TOOL_CAPABILITY_UNSUPPORTED");
        if(current.facts.contextWindow().known){
            long reserve=current.facts.outputTokens().known?Math.max(localOutputReserve,current.facts.outputTokens().value):localOutputReserve;
            long input=current.facts.contextWindow().value.tokens()-reserve;
            if(imageCount>0&&localImageReserve==0)throw new GatewayFailure("ACCOUNT_IMAGE_BUDGET_REQUIRED");
            long charged=(long)bytes.length-imageChars+(long)imageCount*localImageReserve;
            if(input<1||charged>input)throw new GatewayFailure("ACCOUNT_CONTEXT_BUDGET_EXCEEDED");
        }
    }
    public boolean hasImages(){return imageCount>0;}
    public String modelId(){return modelId;}
    byte[] body(){return bytes.clone();}
    boolean declares(String namespace,String name){return functions.contains(namespace+"/"+name);}
    @Override public String toString(){return "ResponsesRequest[private context]";}

    private void validateTools(JSONArray tools)throws Exception {
        if(tools.length()>MAX_FUNCTIONS)throw invalid();Set<String> namespaces=new HashSet<>();
        for(int i=0;i<tools.length();i++){
            JSONObject namespace=tools.getJSONObject(i);
            if(!"namespace".equals(namespace.optString("type")))throw new GatewayFailure("UNSUPPORTED_TOOL_TYPE");
            keys(namespace,"type","name","description","tools");String group=name(namespace,"name",64);
            if(!namespaces.add(group))throw invalid();string(namespace,"description",8192,false);
            JSONArray nested=namespace.getJSONArray("tools");if(nested.length()==0||nested.length()>MAX_FUNCTIONS)throw invalid();
            for(int j=0;j<nested.length();j++){
                JSONObject function=nested.getJSONObject(j);
                if(!"function".equals(function.optString("type")))throw new GatewayFailure("UNSUPPORTED_TOOL_TYPE");
                // Unknown declaration fields fail explicitly, never discard schema/behavior and claim support.
                keys(function,"type","name","description","parameters","strict","output_schema");
                String name=name(function,"name",128);if(!functions.add(group+"/"+name)||functions.size()>MAX_FUNCTIONS)throw invalid();
                optionalString(function,"description",8192);optionalBoolean(function,"strict");
                if(!(function.opt("parameters") instanceof JSONObject))throw invalid();
                if(function.has("output_schema")&&!function.isNull("output_schema")&&!(function.opt("output_schema") instanceof JSONObject))throw invalid();
                // Parameter/output schemas are transported intact. This is not a local JSON Schema validator.
            }
        }
    }
    private void validateInput(JSONArray input)throws Exception {
        if(input.length()==0||input.length()>MAX_ITEMS)throw invalid();
        Set<String> calls=new HashSet<>(),results=new HashSet<>(),ids=new HashSet<>();
        for(int i=0;i<input.length();i++){
            JSONObject item=input.getJSONObject(i);String type=item.has("type")?string(item,"type",64,true):"message";
            if(item.has("id")&&!ids.add(id(item,"id")))throw invalid();
            switch(type){
                case "message":validateMessage(item);break;
                case "reasoning":reasoning(item,true);break;
                case "function_call":
                    keys(item,"type","id","call_id","name","namespace","arguments","status","caller","async");
                    name(item,"name",128);if(item.has("namespace"))name(item,"namespace",64);
                    if(!calls.add(id(item,"call_id")))throw new GatewayFailure("INVALID_FUNCTION_RESULT_LINK");
                    arguments(string(item,"arguments",64*1024,false));completedIfPresent(item);directCall(item);break;
                case "function_call_output":
                    keys(item,"type","id","call_id","output","status");String callId=id(item,"call_id");
                    if(!calls.contains(callId)||!results.add(callId))throw new GatewayFailure("INVALID_FUNCTION_RESULT_LINK");
                    string(item,"output",128*1024,false);completedIfPresent(item);break;
                default:throw new GatewayFailure("UNSUPPORTED_INPUT_TYPE");
            }
        }
        if(!results.equals(calls))throw new GatewayFailure("INVALID_FUNCTION_RESULT_LINK");
    }
    private void validateMessage(JSONObject item)throws Exception {
        keys(item,"type","id","role","content","status","phase");String role=string(item,"role",16,true);
        if(!role.equals("user")&&!role.equals("assistant")&&!role.equals("developer"))throw invalid();
        completedIfPresent(item);phase(item);if(item.has("phase")&&!role.equals("assistant"))throw invalid();
        Object content=item.opt("content");if(content instanceof String){if(((String)content).length()>128*1024)throw invalid();textInput|=!((String)content).isEmpty();return;}
        if(!(content instanceof JSONArray))throw invalid();JSONArray parts=(JSONArray)content;
        // A verified completed assistant may be an empty placeholder beside separate calls.
        // Keep that item's identity/phase for replay; owner input still requires content.
        if(parts.length()>32||parts.length()==0&&!(role.equals("assistant")&&"completed".equals(item.optString("status"))))throw invalid();
        for(int i=0;i<parts.length();i++){
            JSONObject part=parts.getJSONObject(i);String type=string(part,"type",32,true);
            if(type.equals("input_text")){keys(part,"type","text");textInput|=!string(part,"text",128*1024,false).isEmpty();}
            else if(role.equals("user")&&type.equals("input_image")){
                if(!allowImages)throw new GatewayFailure("ACCOUNT_IMAGE_CAPABILITY_REQUIRED");
                keys(part,"type","image_url","detail");String url=string(part,"image_url",MAX_IMAGE_URL_CHARS,true);
                validateImage(url);if(++imageCount>MAX_IMAGES)throw invalid();imageChars+=url.length();
                if(part.has("detail")&&!java.util.Set.of("auto","low","high").contains(part.optString("detail")))throw invalid();
            }
            else if(role.equals("assistant")&&type.equals("output_text")){textPart(part);textInput=true;}
            else if(role.equals("assistant")&&type.equals("refusal")){keys(part,"type","refusal");string(part,"refusal",128*1024,false);textInput=true;}
            else throw new GatewayFailure("UNSUPPORTED_INPUT_TYPE");
        }
    }
    private static void validateImage(String url)throws Exception {
        String mime;int comma=url.indexOf(',');if(comma<0)throw invalid();
        String prefix=url.substring(0,comma);
        if(prefix.equals("data:image/png;base64"))mime="png";else if(prefix.equals("data:image/jpeg;base64"))mime="jpeg";
        else if(prefix.equals("data:image/webp;base64"))mime="webp";else throw new GatewayFailure("UNSUPPORTED_INPUT_TYPE");
        String data=url.substring(comma+1);if(data.isEmpty()||(data.length()&3)!=0)throw invalid();
        byte[] decoded;try{decoded=java.util.Base64.getDecoder().decode(data);}catch(IllegalArgumentException invalid){throw invalid();}
        if(!java.util.Base64.getEncoder().encodeToString(decoded).equals(data))throw invalid();
        boolean png=decoded.length>=8&&(decoded[0]&255)==137&&decoded[1]==80&&decoded[2]==78&&decoded[3]==71&&decoded[4]==13&&decoded[5]==10&&decoded[6]==26&&decoded[7]==10;
        boolean jpeg=decoded.length>=4&&(decoded[0]&255)==255&&(decoded[1]&255)==216&&(decoded[2]&255)==255;
        boolean webp=decoded.length>=12&&decoded[0]==82&&decoded[1]==73&&decoded[2]==70&&decoded[3]==70&&decoded[8]==87&&decoded[9]==69&&decoded[10]==66&&decoded[11]==80;
        if(!(mime.equals("png")&&png||mime.equals("jpeg")&&jpeg||mime.equals("webp")&&webp))throw invalid();
    }
    static void textPart(JSONObject part)throws Exception {
        keys(part,"type","text","annotations","logprobs");string(part,"text",256*1024,false);
        for(String key:new String[]{"annotations","logprobs"})if(part.has(key)&&(!(part.opt(key) instanceof JSONArray)||part.getJSONArray(key).length()!=0))throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
    }
    /** Opaque stateless continuation; no decoding, synthesis, or conversion to a message/tool grant. */
    static JSONObject reasoning(JSONObject item,boolean complete)throws Exception {
        keys(item,"type","id","summary","encrypted_content","status","content");
        if(!"reasoning".equals(item.optString("type")))throw invalid();
        String id=id(item,"id");unicode(id);
        JSONObject result=new JSONObject().put("id",id).put("type","reasoning");
        if(item.has("status")){
            String status=string(item,"status",32,true);
            if(!(complete?"completed":"in_progress").equals(status))throw invalid();
            result.put("status",status);
        }
        JSONArray summary=item.getJSONArray("summary"),saved=new JSONArray();
        if(summary.length()>MAX_SUMMARY_PARTS)throw invalid();
        for(int i=0;i<summary.length();i++){
            JSONObject part=summary.getJSONObject(i);summaryPart(part);
            saved.put(new JSONObject().put("type","summary_text").put("text",part.getString("text")));
        }
        result.put("summary",saved);
        if(item.has("content")){
            // Raw reasoning text is a different protocol surface, not a displayable summary.
            if(!(item.opt("content") instanceof JSONArray)||item.getJSONArray("content").length()!=0)throw new GatewayFailure("UNSUPPORTED_REASONING_CONTENT");
            result.put("content",new JSONArray());
        }
        if(item.has("encrypted_content")&&!item.isNull("encrypted_content")){
            String encrypted=string(item,"encrypted_content",MAX_REASONING_BYTES,complete);unicode(encrypted);
            if(encrypted.getBytes(StandardCharsets.UTF_8).length>MAX_REASONING_BYTES)throw invalid();
            result.put("encrypted_content",encrypted);
        }else if(complete)throw new GatewayFailure("MISSING_REASONING_CONTINUATION");
        else if(item.has("encrypted_content"))result.put("encrypted_content",JSONObject.NULL);
        return result;
    }
    static void summaryPart(JSONObject part)throws Exception {
        keys(part,"type","text");if(!"summary_text".equals(part.optString("type")))throw new GatewayFailure("UNSUPPORTED_REASONING_CONTENT");
        unicode(string(part,"text",256*1024,false));
    }
    static void directCall(JSONObject item)throws Exception {
        if(item.has("async")&&(!(item.opt("async") instanceof Boolean)||item.getBoolean("async")))throw new GatewayFailure("UNSUPPORTED_FUNCTION_MODE");
        if(item.has("caller")&&!item.isNull("caller")){
            JSONObject caller=item.getJSONObject("caller");keys(caller,"type");if(!"direct".equals(caller.optString("type")))throw new GatewayFailure("UNSUPPORTED_FUNCTION_MODE");
        }
    }
    static void phase(JSONObject item)throws Exception {
        if(item.has("phase")&&!item.isNull("phase")){String phase=string(item,"phase",32,true);if(!phase.equals("commentary")&&!phase.equals("final_answer"))throw invalid();}
    }
    static void arguments(String value)throws Exception {object(value);}
    // Bound nesting before org.json recursion. Syntax acceptance remains that of org.json, not strict RFC JSON.
    static JSONObject object(String value)throws Exception {
        unicode(value);int depth=0;boolean quoted=false,escape=false;char quote=0;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c==quote)quoted=false;}
            else if(c=='"'||c=='\''){quoted=true;quote=c;}
            else if(c=='{'||c=='['){if(++depth>32)throw invalid();}
            else if(c=='}'||c==']')depth--;
        }
        JSONTokener tokens=new JSONTokener(value);JSONObject parsed=new JSONObject(tokens);if(tokens.nextClean()!=0)throw invalid();return parsed;
    }
    static void unicode(String value)throws GatewayFailure {
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}
            else if(Character.isLowSurrogate(c))throw invalid();
        }
    }
    private static void completedIfPresent(JSONObject item)throws Exception {if(item.has("status")&&!"completed".equals(string(item,"status",32,true)))throw invalid();}
    static String id(JSONObject object,String key)throws Exception {String value=string(object,key,256,true);for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw invalid();return value;}
    static String name(JSONObject object,String key,int max)throws Exception {String value=string(object,key,max,true);if(!value.matches("[A-Za-z0-9_-]+"))throw invalid();return value;}
    static String string(JSONObject object,String key,int max,boolean nonempty)throws Exception {
        Object raw=object.opt(key);if(!(raw instanceof String))throw invalid();String value=(String)raw;
        if(value.length()>max||(nonempty&&value.isEmpty()))throw invalid();return value;
    }
    static void keys(JSONObject object,String... allowed)throws Exception {
        Set<String> known=new HashSet<>(java.util.Arrays.asList(allowed));Iterator<String> keys=object.keys();
        while(keys.hasNext())if(!known.contains(keys.next()))throw new GatewayFailure("UNSUPPORTED_PROTOCOL_FIELD");
    }
    private static void optionalString(JSONObject object,String key,int max)throws Exception {if(object.has(key)&&!object.isNull(key))string(object,key,max,false);}
    private static void optionalBoolean(JSONObject object,String key)throws Exception {if(object.has(key)&&!object.isNull(key)&&!(object.opt(key) instanceof Boolean))throw invalid();}
    private static Object copy(Object value,int depth,int[] budget,int limit)throws Exception {
        if(depth>32||++budget[0]>16384)throw invalid();
        if(value instanceof JSONObject){JSONObject result=new JSONObject();Iterator<String> keys=((JSONObject)value).keys();while(keys.hasNext()){String key=keys.next();unicode(key);budget[1]+=key.length();if(budget[1]>limit)throw invalid();result.put(key,copy(((JSONObject)value).get(key),depth+1,budget,limit));}return result;}
        if(value instanceof JSONArray){JSONArray result=new JSONArray();for(int i=0;i<((JSONArray)value).length();i++)result.put(copy(((JSONArray)value).get(i),depth+1,budget,limit));return result;}
        if(value instanceof String){unicode((String)value);budget[1]+=((String)value).length();if(budget[1]>limit)throw invalid();return value;}
        if(value==null||value==JSONObject.NULL||value instanceof Boolean)return value;
        if(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()))return value;
        throw invalid();
    }
    private static GatewayFailure invalid(){return new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");}
}
