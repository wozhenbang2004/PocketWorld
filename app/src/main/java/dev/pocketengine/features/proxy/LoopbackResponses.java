package dev.pocketengine.features.proxy;

import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native normalized events -> the documented Responses wire protocol, never a tool executor. */
final class LoopbackResponses {
    interface Output {void event(JSONObject event)throws Exception;void complete(JSONObject response)throws Exception;}
    static final class Input {
        final ResponsesRequest request;final boolean streaming;
        Input(ResponsesRequest request,boolean streaming){this.request=request;this.streaming=streaming;}
    }
    static Input input(JSONObject body,String model)throws GatewayFailure {
        try{
            keys(body,"model","instructions","input","tools","stream","store");
            if(!(body.opt("model") instanceof String)||!model.equals(body.getString("model")))throw new GatewayFailure("MODEL_SELECTION_MISMATCH");
            for(String key:new String[]{"stream","store"})if(body.has(key)&&!(body.get(key) instanceof Boolean))throw invalid();
            if(body.optBoolean("store",false))throw new GatewayFailure("UNSUPPORTED_PROTOCOL_FIELD");
            String instructions="";
            if(body.has("instructions")){if(!(body.get("instructions") instanceof String))throw invalid();instructions=body.getString("instructions");}
            Object raw=body.opt("input");JSONArray input;
            if(raw instanceof String)input=new JSONArray().put(new JSONObject().put("role","user").put("content",raw));
            else if(raw instanceof JSONArray)input=(JSONArray)raw;
            else throw invalid();
            JSONArray tools=body.has("tools")?body.getJSONArray("tools"):new JSONArray();
            return new Input(new ResponsesRequest(model,instructions,input,tools),body.optBoolean("stream",false));
        }catch(GatewayFailure failure){throw failure;}catch(Exception invalid){throw invalid();}
    }

    private final String model;private final boolean streaming;private final Output output;
    private final long createdAt=System.currentTimeMillis()/1000;
    private final Map<Integer,Message> messages=new TreeMap<>();
    private String responseId;private long sequence;private int addedItems,wireBytes,deltaBytes;private boolean completed;
    LoopbackResponses(String model,boolean streaming,Output output){this.model=model;this.streaming=streaming;this.output=output;}

    void accept(JSONObject event)throws Exception {
        if(completed)throw protocol();
        String type=event.getString("type");
        if(type.equals("text_delta")||type.equals("refusal_delta")){
            keys(event,"type","response_id","item_id","output_index","content_index","delta");
            identity(event);int index=index(event,"output_index",128),partIndex=index(event,"content_index",32);
            String id=event.getString("item_id"),kind=type.equals("text_delta")?"output_text":"refusal";
            Message message=messages.get(index);
            if(message==null){message=new Message(id);messages.put(index,message);}
            if(!message.id.equals(id))throw protocol();
            Part part=message.parts.get(partIndex);
            if(part==null){part=new Part(kind);message.parts.put(partIndex,part);}
            if(!part.type.equals(kind))throw protocol();
            String delta=event.getString("delta");deltaBytes+=delta.getBytes(StandardCharsets.UTF_8).length;
            if(deltaBytes>1024*1024)throw new GatewayFailure("PROVIDER_RESPONSE_TOO_LARGE");
            part.value.append(delta);
            if(streaming){start();flushAvailable();}
        }else if(type.equals("completed")){
            keys(event,"type","response_id","output","usage");identity(event);
            JSONArray items=new JSONArray(event.getJSONArray("output").toString());
            if(items.length()>128)throw protocol();
            validateFinal(items);
            if(event.has("usage")&&!event.isNull("usage")&&!(event.get("usage") instanceof JSONObject))throw protocol();
            JSONObject result=response("completed",items,event.optJSONObject("usage"));
            if(result.toString().getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new GatewayFailure("PROVIDER_RESPONSE_TOO_LARGE");
            if(streaming){
                start();
                for(int i=0;i<items.length();i++)finishItem(i,items.getJSONObject(i));
                emit(new JSONObject().put("type","response.completed").put("response",result));
            }else output.complete(result);
            completed=true;
        }else throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
    }
    void requireCompleted()throws GatewayFailure {if(!completed)throw new GatewayFailure("PROVIDER_STREAM_INTERRUPTED");}
    boolean isCompleted(){return completed;}
    private void identity(JSONObject event)throws Exception {
        String id=event.getString("response_id");if(id.isEmpty()||id.length()>256)throw protocol();
        if(responseId==null)responseId=id;else if(!responseId.equals(id))throw protocol();
    }
    private void start()throws Exception {
        if(sequence==0)emit(new JSONObject().put("type","response.created").put("response",response("in_progress",new JSONArray(),null)));
    }
    /** Do not invent unseen reasoning/function/empty items to fill index gaps. Hold later text instead. */
    private void flushAvailable()throws Exception {
        while(messages.containsKey(addedItems)){
            Message next=messages.get(addedItems);if(next.added)break;
            addMessage(addedItems,next);addedItems++;
        }
        for(Map.Entry<Integer,Message> entry:messages.entrySet())if(entry.getValue().added)flushParts(entry.getKey(),entry.getValue());
    }
    private void addMessage(int index,Message message)throws Exception {
        emit(new JSONObject().put("type","response.output_item.added").put("output_index",index).put("item",new JSONObject().put("id",message.id).put("type","message").put("role","assistant").put("status","in_progress").put("content",new JSONArray())));
        message.added=true;
    }
    private void flushParts(int index,Message message)throws Exception {
        for(Map.Entry<Integer,Part> entry:message.parts.entrySet()){
            int partIndex=entry.getKey();Part part=entry.getValue();if(partIndex>message.addedParts)break;
            if(!part.added){emit(located("response.content_part.added",index,message.id).put("content_index",partIndex).put("part",textPart(part.type,"")));part.added=true;message.addedParts++;}
            if(part.sent<part.value.length()){
                JSONObject delta=located(part.type.equals("refusal")?"response.refusal.delta":"response.output_text.delta",index,message.id).put("content_index",partIndex).put("delta",part.value.substring(part.sent));
                if(part.type.equals("output_text"))delta.put("logprobs",new JSONArray());emit(delta);part.sent=part.value.length();
            }
        }
    }
    private void validateFinal(JSONArray items)throws Exception {
        Set<String> ids=new HashSet<>();
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);String type=item.getString("type"),id=item.getString("id");
            if(!ids.add(id))throw protocol();
            Message message=messages.get(i);
            if(type.equals("message")){
                keys(item,"id","type","status","role","content","phase");
                if(!"assistant".equals(item.getString("role"))||!"completed".equals(item.getString("status")))throw protocol();
                JSONArray parts=item.getJSONArray("content");if(parts.length()>32)throw protocol();
                if(message!=null&&(!message.id.equals(id)||message.parts.size()>parts.length()))throw protocol();
                for(int j=0;j<parts.length();j++){
                    JSONObject part=parts.getJSONObject(j);String kind=part.getString("type");
                    if(!kind.equals("output_text")&&!kind.equals("refusal"))throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
                    keys(part,"type",kind.equals("refusal")?"refusal":"text");
                    String value=part.getString(kind.equals("refusal")?"refusal":"text");
                    if(message!=null&&message.parts.containsKey(j)){
                        Part seen=message.parts.get(j);if(!seen.type.equals(kind)||!seen.value.toString().equals(value))throw protocol();
                    }
                    // Official output_text objects include empty annotations/logprobs in this narrow route.
                    if(kind.equals("output_text"))part.put("annotations",new JSONArray()).put("logprobs",new JSONArray());
                }
                if(message!=null)for(int j:message.parts.keySet())if(j>=parts.length())throw protocol();
            }else if(type.equals("function_call")){
                if(message!=null)throw protocol();keys(item,"id","type","status","call_id","namespace","name","arguments","caller","async");
                if(!"completed".equals(item.getString("status")))throw protocol();
            }else if(type.equals("reasoning")){
                if(message!=null)throw protocol();keys(item,"id","type","status","summary","encrypted_content","content");
                if(!item.has("encrypted_content")||item.isNull("encrypted_content"))throw protocol();
            }else throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
        }
        for(int index:messages.keySet())if(index>=items.length())throw protocol();
    }
    private void finishItem(int index,JSONObject item)throws Exception {
        String type=item.getString("type"),id=item.getString("id");
        if(type.equals("message")){
            Message message=messages.get(index);if(message==null){message=new Message(id);messages.put(index,message);}
            if(!message.added){if(index!=addedItems)throw protocol();addMessage(index,message);addedItems++;}
            JSONArray parts=item.getJSONArray("content");
            for(int j=0;j<parts.length();j++){
                JSONObject finalPart=parts.getJSONObject(j);String kind=finalPart.getString("type"),field=kind.equals("refusal")?"refusal":"text";
                if(!message.parts.containsKey(j)){Part part=new Part(kind);part.value.append(finalPart.getString(field));message.parts.put(j,part);}
            }
            flushParts(index,message);
            for(int j=0;j<parts.length();j++){
                JSONObject part=parts.getJSONObject(j);String kind=part.getString("type"),field=kind.equals("refusal")?"refusal":"text";
                JSONObject done=located(kind.equals("refusal")?"response.refusal.done":"response.output_text.done",index,id).put("content_index",j).put(field,part.getString(field));
                if(kind.equals("output_text"))done.put("logprobs",new JSONArray());emit(done);
                emit(located("response.content_part.done",index,id).put("content_index",j).put("part",part));
            }
        }else{
            if(index!=addedItems)throw protocol();JSONObject added=new JSONObject(item.toString());
            if(type.equals("function_call"))added.put("arguments","").put("status","in_progress");
            else {added.put("summary",new JSONArray()).put("encrypted_content",JSONObject.NULL);if(added.has("status"))added.put("status","in_progress");}
            emit(new JSONObject().put("type","response.output_item.added").put("output_index",index).put("item",added));addedItems++;
            if(type.equals("function_call")){
                emit(located("response.function_call_arguments.delta",index,id).put("delta",item.getString("arguments")));
                emit(located("response.function_call_arguments.done",index,id).put("arguments",item.getString("arguments")));
            }else{
                JSONArray summary=item.getJSONArray("summary");
                for(int j=0;j<summary.length();j++){
                    JSONObject part=summary.getJSONObject(j);
                    emit(located("response.reasoning_summary_part.added",index,id).put("summary_index",j).put("part",new JSONObject().put("type","summary_text").put("text","")));
                    emit(located("response.reasoning_summary_text.delta",index,id).put("summary_index",j).put("delta",part.getString("text")));
                    emit(located("response.reasoning_summary_text.done",index,id).put("summary_index",j).put("text",part.getString("text")));
                    emit(located("response.reasoning_summary_part.done",index,id).put("summary_index",j).put("part",part));
                }
            }
        }
        emit(new JSONObject().put("type","response.output_item.done").put("output_index",index).put("item",item));
    }
    private JSONObject response(String status,JSONArray items,JSONObject usage)throws Exception {
        JSONObject result=new JSONObject().put("id",responseId).put("object","response").put("created_at",createdAt).put("status",status)
            .put("completed_at",status.equals("completed")?System.currentTimeMillis()/1000:JSONObject.NULL)
            .put("error",JSONObject.NULL).put("incomplete_details",JSONObject.NULL).put("model",model).put("store",false).put("output",items);
        if(usage==null)result.put("usage",JSONObject.NULL);
        else{
            keys(usage,"input_tokens","output_tokens","total_tokens");JSONObject counts=new JSONObject();
            for(String key:new String[]{"input_tokens","output_tokens","total_tokens"})if(usage.has(key)){
                Object raw=usage.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0)throw protocol();counts.put(key,raw);
            }
            // Missing counters/details stay missing; never manufacture token accounting.
            result.put("usage",counts);
        }
        return result;
    }
    private void emit(JSONObject event)throws Exception {
        event.put("sequence_number",sequence++);int bytes=event.toString().getBytes(StandardCharsets.UTF_8).length;
        if(bytes>2*1024*1024||wireBytes+bytes>8*1024*1024)throw new GatewayFailure("PROVIDER_RESPONSE_TOO_LARGE");
        wireBytes+=bytes;output.event(event);
    }
    JSONObject failure(GatewayFailure failure)throws Exception {
        JSONObject error=error(failure).getJSONObject("error");return new JSONObject().put("type","error").put("sequence_number",sequence++)
            .put("code",error.getString("code")).put("message",error.getString("message")).put("param",JSONObject.NULL);
    }
    static int status(GatewayFailure failure){
        String code=failure.code;
        if(failure.httpStatus>=400&&failure.httpStatus<=599)return failure.httpStatus;
        if(code.equals("CHATGPT_USAGE_LIMIT_REACHED"))return 429;
        if(code.equals("CHATGPT_ACCOUNT_PERMISSION_REJECTED"))return 401;
        if(code.equals("CHATGPT_POLICY_RESTRICTED")||code.equals("CHATGPT_DIRECT_PERMISSION_REJECTED")||code.equals("CHATGPT_PLAN_NOT_ELIGIBLE")||code.equals("CHATGPT_ROUTE_NOT_SUPPORTED"))return 403;
        if(code.equals("CHATGPT_UNSUPPORTED_CAPABILITY")||code.equals("ACCOUNT_IMAGE_CAPABILITY_REQUIRED"))return 400;
        if(code.equals("CANCELLED"))return 499;if(code.equals("DEADLINE_EXCEEDED"))return 504;
        if(code.equals("REAUTHORIZATION_REQUIRED"))return 401;
        if(code.equals("PROVIDER_RATE_LIMITED")||code.equals("ACCOUNT_COOLDOWN"))return 429;
        if(code.equals("PLAN_USAGE_PERMISSION_REQUIRED")||code.equals("PROVIDER_PERMISSION_DENIED"))return 403;
        if(code.equals("MODEL_SELECTION_MISMATCH")||code.startsWith("UNSUPPORTED_")&&!code.equals("UNSUPPORTED_PROVIDER_OUTPUT")||code.equals("INVALID_FUNCTION_RESULT_LINK"))return 400;
        if(code.startsWith("ACCOUNT_")||code.equals("STALE_ACCOUNT_VERSION")||code.equals("GATEWAY_BUSY")||code.equals("RELAY_ROUTE_CHANGED")||code.equals("GATEWAY_CLOSED")||code.equals("REFRESH_QUEUE_FULL")||code.equals("PROVIDER_CLIENT_REJECTED"))return 503;
        return 502;
    }
    static JSONObject error(GatewayFailure failure)throws Exception {
        // Only our fixed public codes are reflected. Provider message/body/headers never cross this API.
        Set<String> known=new HashSet<>(Arrays.asList("CANCELLED","DEADLINE_EXCEEDED","PROVIDER_RATE_LIMITED","ACCOUNT_COOLDOWN","PLAN_USAGE_PERMISSION_REQUIRED",
            "MODEL_SELECTION_MISMATCH","UNSUPPORTED_OR_OVERSIZED_INPUT","UNSUPPORTED_PROTOCOL_FIELD","UNSUPPORTED_INPUT_TYPE","UNSUPPORTED_TOOL_TYPE","UNSUPPORTED_FUNCTION_MODE","UNSUPPORTED_REASONING_CONTENT","INVALID_FUNCTION_RESULT_LINK","MISSING_REASONING_CONTINUATION",
            "ACCOUNT_ROUTE_UNAVAILABLE","ACCOUNT_MODEL_UNAVAILABLE","ACCOUNT_SELECTION_REQUIRED","ACCOUNT_DISABLED","ACCOUNT_REFRESH_REQUIRED","ACCOUNT_SELECTION_STORE_UNAVAILABLE","STALE_ACCOUNT_VERSION","GATEWAY_BUSY","RELAY_ROUTE_CHANGED",
            "PROVIDER_STREAM_INTERRUPTED","PROVIDER_RESPONSE_INCOMPLETE","PROVIDER_RESPONSE_TOO_LARGE","UNSUPPORTED_PROVIDER_OUTPUT","PROVIDER_CREDENTIAL_ECHO","REAUTHORIZATION_REQUIRED","PROVIDER_UNAVAILABLE","PROVIDER_PERMISSION_DENIED","UNSUPPORTED_PROVIDER_REQUEST","PROVIDER_CLIENT_REJECTED","GATEWAY_CLOSED","REFRESH_QUEUE_FULL"));
        known.addAll(java.util.Set.of("CHATGPT_USAGE_LIMIT_REACHED","CHATGPT_PLAN_NOT_ELIGIBLE","CHATGPT_POLICY_RESTRICTED","CHATGPT_ACCOUNT_PERMISSION_REJECTED","CHATGPT_DIRECT_PERMISSION_REJECTED","CHATGPT_UNSUPPORTED_CAPABILITY","CHATGPT_ROUTE_NOT_SUPPORTED","ACCOUNT_IMAGE_CAPABILITY_REQUIRED"));
        String code=known.contains(failure.code)?failure.code:"ACCOUNT_REQUEST_FAILED";
        return new JSONObject().put("error",new JSONObject().put("type",status(failure)==400?"invalid_request_error":"pocketengine_account_error")
            .put("code",code).put("param",JSONObject.NULL).put("message","账号模型请求未完成；未自动换账号、降级或重试。("+code+")"));
    }
    private static JSONObject located(String type,int index,String id)throws Exception{return new JSONObject().put("type",type).put("output_index",index).put("item_id",id);}
    private static JSONObject textPart(String kind,String value)throws Exception {JSONObject part=new JSONObject().put("type",kind).put(kind.equals("refusal")?"refusal":"text",value);if(kind.equals("output_text"))part.put("annotations",new JSONArray()).put("logprobs",new JSONArray());return part;}
    private static int index(JSONObject event,String name,int bound)throws Exception{Object raw=event.get(name);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0||((Number)raw).longValue()>=bound)throw protocol();return ((Number)raw).intValue();}
    private static void keys(JSONObject object,String...allowed)throws GatewayFailure {Set<String> names=new HashSet<>(Arrays.asList(allowed));Iterator<String> keys=object.keys();while(keys.hasNext())if(!names.contains(keys.next()))throw new GatewayFailure("UNSUPPORTED_PROTOCOL_FIELD");}
    private static GatewayFailure invalid(){return new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");}
    private static GatewayFailure protocol(){return new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");}
    private static final class Message {final String id;final Map<Integer,Part> parts=new TreeMap<>();boolean added;int addedParts;Message(String id){this.id=id;}}
    private static final class Part {final String type;final StringBuilder value=new StringBuilder();boolean added;int sent;Part(String type){this.type=type;}}
}
