package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Responses-only state machine. No function execution, task completion, permissions or retries. */
final class ResponsesStream {
    static final int MAX_ARGUMENT_CHARS=64*1024,MAX_OUTPUT_CHARS=256*1024,MAX_PARTS=32;
    private final GatewayCall call;
    private final ChatGptProvider.EventSink sink;
    private final ResponsesRequest request;
    private final String[] secrets;
    private final Map<Integer,Item> items=new TreeMap<>();
    private final Set<String> ids=new HashSet<>(),callIds=new HashSet<>();
    private String responseId;
    private long sequence=-1;
    private int chars,functionCount;

    private ResponsesStream(ResponsesRequest request,GatewayCall call,ChatGptProvider.EventSink sink,String[] secrets){this.request=request;this.call=call;this.sink=sink;this.secrets=secrets;}
    static void read(InputStream input,ResponsesRequest request,GatewayCall call,ChatGptProvider.EventSink sink,String... secrets)throws Exception {
        ResponsesStream state=new ResponsesStream(request,call,sink,secrets);ResponsesTextStream.Parser parser=new ResponsesTextStream.Parser(input,call);
        while(true){
            String data=parser.next();if(data==null)throw new GatewayFailure("PROVIDER_STREAM_INTERRUPTED");
            JSONObject event;try{event=ResponsesRequest.object(data);}catch(Exception invalid){throw invalid();}
            String type=event.optString("type");if(!parser.event.isEmpty()&&!parser.event.equals(type))throw invalid();
            try{if(state.event(event,type))return;}
            catch(GatewayFailure failure){if(failure.code.equals("UNSUPPORTED_OR_OVERSIZED_INPUT"))throw invalid();throw failure;}
            catch(org.json.JSONException invalid){throw invalid();}
        }
    }
    private boolean event(JSONObject event,String type)throws Exception {
        call.check();long next=integer(event,"sequence_number",Long.MAX_VALUE);if(next<=sequence)throw invalid();sequence=next;
        // Failure terminals never publish pending call arguments, even if all item-level done events arrived.
        if(type.equals("response.failed")||type.equals("response.incomplete")){
            JSONObject response=event.getJSONObject("response");
            if(responseId==null||!responseId.equals(safeId(response,"id")))throw invalid();
            reportUsage(response);
            if(type.equals("response.failed"))throw failure(event);
            throw new GatewayFailure("PROVIDER_RESPONSE_INCOMPLETE");
        }
        if(type.equals("error"))throw failure(event);
        if(type.equals("response.created")){
            if(responseId!=null)throw invalid();JSONObject response=event.getJSONObject("response");responseId=safeId(response,"id");
            if(!"in_progress".equals(response.optString("status")))throw invalid();return false;
        }
        if(responseId==null)throw invalid();
        switch(type){
            case "response.in_progress":checkResponse(event.getJSONObject("response"),"in_progress");break;
            case "response.output_item.added":add(event);break;
            case "response.function_call_arguments.delta":{
                Item item=locate(event,"function_call");if(item.argumentsDone)throw invalid();String delta=text(event,"delta",MAX_ARGUMENT_CHARS);
                addChars(delta.length());if(item.arguments.length()+delta.length()>MAX_ARGUMENT_CHARS)throw tooLarge();item.arguments.append(delta);break;
            }
            case "response.function_call_arguments.done":{
                Item item=locate(event,"function_call");if(item.argumentsDone||!item.arguments.toString().equals(text(event,"arguments",MAX_ARGUMENT_CHARS)))throw invalid();
                ResponsesRequest.arguments(item.arguments.toString());item.argumentsDone=true;break;
            }
            case "response.content_part.added":addPart(event);break;
            case "response.reasoning_summary_part.added":addSummary(event);break;
            case "response.reasoning_summary_text.delta":{
                Part part=summary(event);if(part.valueDone||part.done)throw invalid();
                String delta=text(event,"delta",MAX_OUTPUT_CHARS);addChars(delta.length());part.raw.append(delta);break;
            }
            case "response.reasoning_summary_text.done":{
                Part part=summary(event);if(part.valueDone||part.done||!part.raw.toString().equals(text(event,"text",MAX_OUTPUT_CHARS)))throw invalid();
                ResponsesRequest.unicode(part.raw.toString());part.valueDone=true;break;
            }
            case "response.reasoning_summary_part.done":{
                Part part=summary(event);if(!part.valueDone||part.done||event.has("status"))throw invalid();
                checkSummary(event.getJSONObject("part"),part);part.done=true;break;
            }
            case "response.output_text.delta":case "response.refusal.delta":{
                Part part=part(event);String expected=type.equals("response.refusal.delta")?"refusal":"output_text";
                if(!part.type.equals(expected)||part.valueDone||part.done)throw invalid();String delta=text(event,"delta",MAX_OUTPUT_CHARS);
                addChars(delta.length());part.raw.append(delta);part.filter.text(delta);break;
            }
            case "response.output_text.done":case "response.refusal.done":{
                Part part=part(event);String expected=type.equals("response.refusal.done")?"refusal":"output_text";
                if(!part.type.equals(expected)||part.valueDone||part.done||!part.raw.toString().equals(text(event,part.type.equals("refusal")?"refusal":"text",MAX_OUTPUT_CHARS)))throw invalid();
                part.valueDone=true;part.filter.finish();break;
            }
            case "response.content_part.done":{
                Part part=part(event);if(!part.valueDone||part.done)throw invalid();checkPart(event.getJSONObject("part"),part);part.done=true;break;
            }
            case "response.output_item.done":{
                Item item=byIndex(event);if(item.done)throw invalid();checkItem(event.getJSONObject("item"),item);item.done=true;break;
            }
            case "response.completed":complete(event.getJSONObject("response"));return true;
            default:throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
        }
        return false;
    }
    private void add(JSONObject event)throws Exception {
        int index=index(event,"output_index",ResponsesRequest.MAX_ITEMS);JSONObject raw=event.getJSONObject("item");
        if(items.containsKey(index)||items.size()>=ResponsesRequest.MAX_ITEMS)throw invalid();
        String id=safeId(raw,"id"),type=ResponsesRequest.string(raw,"type",64,true);if(!ids.add(id))throw invalid();
        if(!type.equals("message")&&!type.equals("function_call")&&!type.equals("reasoning"))throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
        if(!type.equals("reasoning")&&!"in_progress".equals(raw.optString("status")))throw invalid();
        Item item=new Item(index,id,type);
        if(type.equals("reasoning")){
            ResponsesRequest.reasoning(raw,false);if(raw.getJSONArray("summary").length()!=0)throw invalid();
            // An added item's ciphertext may be incomplete. Never retain it for replay.
        }else if(type.equals("function_call")){
            functionFields(raw);item.callId=safeId(raw,"call_id");item.name=ResponsesRequest.name(raw,"name",128);item.namespace=ResponsesRequest.name(raw,"namespace",64);
            if(!callIds.add(item.callId))throw invalid();if(++functionCount>ResponsesRequest.MAX_FUNCTIONS)throw tooLarge();
            if(!request.declares(item.namespace,item.name))throw new GatewayFailure("UNDECLARED_FUNCTION_CALL");
            String initial=text(raw,"arguments",MAX_ARGUMENT_CHARS);addChars(initial.length());item.arguments.append(initial);
        }else{
            messageFields(raw);if(raw.getJSONArray("content").length()!=0)throw invalid();
            if(raw.has("phase"))item.phase=raw.get("phase");
        }
        items.put(index,item);
    }
    private void addSummary(JSONObject event)throws Exception {
        Item item=locate(event,"reasoning");int index=index(event,"summary_index",ResponsesRequest.MAX_SUMMARY_PARTS);
        if(item.parts.containsKey(index))throw invalid();JSONObject raw=event.getJSONObject("part");ResponsesRequest.summaryPart(raw);
        if(!raw.getString("text").isEmpty())throw invalid();item.parts.put(index,new Part("summary_text"));
    }
    private Part summary(JSONObject event)throws Exception {
        Part part=locate(event,"reasoning").parts.get(index(event,"summary_index",ResponsesRequest.MAX_SUMMARY_PARTS));
        if(part==null)throw invalid();return part;
    }
    private static void checkSummary(JSONObject raw,Part part)throws Exception {
        ResponsesRequest.summaryPart(raw);if(!part.raw.toString().equals(raw.getString("text")))throw invalid();
    }
    private void addPart(JSONObject event)throws Exception {
        Item item=locate(event,"message");int index=index(event,"content_index",MAX_PARTS);if(item.parts.containsKey(index))throw invalid();
        JSONObject raw=event.getJSONObject("part");String type=ResponsesRequest.string(raw,"type",32,true);
        if(!type.equals("output_text")&&!type.equals("refusal"))throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
        Part part=new Part(type);validatePart(raw);if(!raw.getString(type.equals("refusal")?"refusal":"text").isEmpty())throw invalid();
        part.filter=new ResponsesTextStream.TextFilter(delta->{
            call.check();sink.event(new JSONObject().put("type",type.equals("refusal")?"refusal_delta":"text_delta")
                .put("response_id",responseId).put("item_id",item.id).put("output_index",item.index).put("content_index",index).put("delta",delta.getString("delta")));
        },secrets);
        item.parts.put(index,part);
    }
    private Part part(JSONObject event)throws Exception {
        Item item=locate(event,"message");Part part=item.parts.get(index(event,"content_index",MAX_PARTS));if(part==null)throw invalid();return part;
    }
    private Item byIndex(JSONObject event)throws Exception {Item item=items.get(index(event,"output_index",ResponsesRequest.MAX_ITEMS));if(item==null)throw invalid();return item;}
    private Item locate(JSONObject event,String type)throws Exception {
        Item item=byIndex(event);if(item.done||!item.type.equals(type)||!item.id.equals(safeId(event,"item_id")))throw invalid();return item;
    }
    private void checkItem(JSONObject raw,Item item)throws Exception {
        if(!item.id.equals(safeId(raw,"id"))||!item.type.equals(raw.optString("type")))throw invalid();
        if(item.type.equals("reasoning")){
            JSONObject saved=ResponsesRequest.reasoning(raw,true);JSONArray summary=saved.getJSONArray("summary");
            if(summary.length()!=item.parts.size())throw invalid();
            for(int i=0;i<summary.length();i++){
                Part part=item.parts.get(i);if(part==null||!part.done)throw invalid();checkSummary(summary.getJSONObject(i),part);noSecret(part.raw.toString());
            }
            String encrypted=saved.getString("encrypted_content");noSecret(encrypted);
            String canonical=saved.toString();
            if(item.reasoning==null){addChars(encrypted.length());item.reasoning=canonical;}
            else if(!item.reasoning.equals(canonical))throw invalid();
            return;
        }
        if(!"completed".equals(raw.optString("status")))throw invalid();
        if(item.type.equals("function_call")){
            functionFields(raw);
            if(!item.argumentsDone||!item.callId.equals(safeId(raw,"call_id"))||!item.name.equals(raw.getString("name"))||!item.namespace.equals(raw.getString("namespace"))||!item.arguments.toString().equals(text(raw,"arguments",MAX_ARGUMENT_CHARS)))throw invalid();
        }else{
            messageFields(raw);JSONArray content=raw.getJSONArray("content");if(content.length()!=item.parts.size())throw invalid();
            for(int i=0;i<content.length();i++){Part part=item.parts.get(i);if(part==null||!part.done)throw invalid();checkPart(content.getJSONObject(i),part);}
            Object phase=raw.has("phase")?raw.get("phase"):null;
            if(item.phase!=null&&!item.phase.equals(phase))throw invalid();item.phase=phase;
        }
    }
    private static void functionFields(JSONObject raw)throws Exception {
        ResponsesRequest.keys(raw,"id","type","status","call_id","name","namespace","arguments","caller","async");ResponsesRequest.directCall(raw);
    }
    private static void messageFields(JSONObject raw)throws Exception {
        ResponsesRequest.keys(raw,"id","type","status","role","content","phase");if(!"assistant".equals(raw.optString("role")))throw invalid();ResponsesRequest.phase(raw);
    }
    private static void validatePart(JSONObject raw)throws Exception {
        if("output_text".equals(raw.optString("type")))ResponsesRequest.textPart(raw);
        else if("refusal".equals(raw.optString("type"))){ResponsesRequest.keys(raw,"type","refusal");text(raw,"refusal",MAX_OUTPUT_CHARS);}
        else throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
    }
    private static void checkPart(JSONObject raw,Part part)throws Exception {
        validatePart(raw);if(!part.type.equals(raw.getString("type"))||!part.raw.toString().equals(raw.getString(part.type.equals("refusal")?"refusal":"text")))throw invalid();
    }
    private void complete(JSONObject response)throws Exception {
        // Counter evidence survives an invalid output or a later native presentation revocation.
        if(responseId==null||!responseId.equals(safeId(response,"id")))throw invalid();
        reportUsage(response);
        checkResponse(response,"completed");if(!response.isNull("error")||!response.isNull("incomplete_details"))throw invalid();
        JSONArray output=response.getJSONArray("output");if(output.length()!=items.size())throw invalid();JSONArray safeOutput=new JSONArray();
        for(int i=0;i<output.length();i++){
            Item item=items.get(i);if(item==null||!item.done)throw invalid();checkItem(output.getJSONObject(i),item);
            JSONObject safe=new JSONObject().put("id",item.id).put("type",item.type).put("status","completed");
            if(item.type.equals("reasoning")){
                // Publish only after the whole response terminal agrees with output_item.done.
                safe=new JSONObject(item.reasoning);
            }else if(item.type.equals("function_call")){
                String arguments=item.arguments.toString();noSecret(arguments);noSecret(item.name);noSecret(item.namespace);
                safe.put("call_id",item.callId).put("namespace",item.namespace).put("name",item.name).put("arguments",arguments);
                JSONObject original=output.getJSONObject(i);if(original.has("async"))safe.put("async",false);
                if(original.has("caller"))safe.put("caller",original.isNull("caller")?JSONObject.NULL:new JSONObject().put("type","direct"));
            }else{
                JSONArray content=new JSONArray();for(Part part:item.parts.values())content.put(new JSONObject().put("type",part.type).put(part.type.equals("refusal")?"refusal":"text",redact(part.raw.toString())));
                safe.put("role","assistant").put("content",content);if(item.phase!=null)safe.put("phase",item.phase);
            }
            safeOutput.put(safe);
        }
        JSONObject completed=new JSONObject().put("type","completed").put("response_id",responseId).put("output",safeOutput);
        JSONObject usage=response.optJSONObject("usage");if(response.has("usage")&&!response.isNull("usage")&&usage==null)throw invalid();
        if(usage!=null)completed.put("usage",safeUsage(usage));
        // Completion refers to one model response only. The caller still owns approvals, resource leases and TaskStore.
        call.check();sink.event(completed);
    }
    private void reportUsage(JSONObject response)throws Exception{
        JSONObject usage=response.optJSONObject("usage");
        if(usage!=null){
            JSONObject report=new JSONObject().put("usage",safeUsage(usage));String status=response.optString("status");
            if(Set.of("completed","failed","incomplete","cancelled").contains(status))report.put("status",status);
            Object model=response.opt("model");
            if(model instanceof String&&((String)model).matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,299}")){
                boolean safe=true;for(String secret:secrets)if(secret!=null&&!secret.isEmpty()&&((String)model).contains(secret))safe=false;
                if(safe)report.put("model",model);
            }
            sink.usage(report);
        }
    }
    private static JSONObject safeUsage(JSONObject usage)throws Exception{
        JSONObject counts=new JSONObject();
        for(String key:new String[]{"input_tokens","output_tokens","total_tokens"})
            if(usage.has(key)&&!usage.isNull(key))counts.put(key,integer(usage,key,Long.MAX_VALUE));
        for(String parent:new String[]{"input_tokens_details","output_tokens_details"}){
            JSONObject details=usage.optJSONObject(parent);String key=parent.equals("input_tokens_details")?"cached_tokens":"reasoning_tokens";
            if(details!=null&&details.has(key)&&!details.isNull(key))counts.put(parent,new JSONObject().put(key,integer(details,key,Long.MAX_VALUE)));
        }
        return counts;
    }
    private void checkResponse(JSONObject response,String status)throws Exception {if(!responseId.equals(safeId(response,"id"))||!status.equals(response.optString("status")))throw invalid();}
    private String safeId(JSONObject value,String key)throws Exception {String id=ResponsesRequest.id(value,key);noSecret(id);return id;}
    private void noSecret(String value)throws GatewayFailure {for(String secret:secrets)if(!secret.isEmpty()&&value.contains(secret))throw new GatewayFailure("PROVIDER_CREDENTIAL_ECHO");}
    private String redact(String value){for(String secret:secrets)if(!secret.isEmpty())value=value.replace(secret,"[redacted]");return value;}
    private void addChars(int count)throws GatewayFailure {chars+=count;if(chars>MAX_OUTPUT_CHARS)throw tooLarge();}
    private static String text(JSONObject value,String key,int max)throws Exception {String text=ResponsesRequest.string(value,key,Integer.MAX_VALUE,false);if(text.length()>max)throw tooLarge();return text;}
    private static long integer(JSONObject value,String key,long max)throws Exception {
        Object raw=value.opt(key);if(!(raw instanceof Number))throw invalid();
        try{long n=new java.math.BigDecimal(raw.toString()).longValueExact();if(n<0||n>max)throw invalid();return n;}catch(ArithmeticException error){throw invalid();}
    }
    private static int index(JSONObject value,String key,int bound)throws Exception {return (int)integer(value,key,bound-1);}
    private static GatewayFailure failure(JSONObject event){
        JSONObject error=event.optJSONObject("error"),response=event.optJSONObject("response");if(response!=null)error=response.optJSONObject("error");
        JSONObject safeError=error;if("error".equals(event.optString("type")))safeError=event;
        return dev.pocketengine.features.proxy.accounts.ChatGptRecovery.streamFailure(safeError);
    }
    private static GatewayFailure invalid(){return new GatewayFailure("INVALID_PROVIDER_STREAM");}
    private static GatewayFailure tooLarge(){return new GatewayFailure("PROVIDER_RESPONSE_TOO_LARGE");}
    private static final class Item {
        final int index;final String id,type;final StringBuilder arguments=new StringBuilder();final Map<Integer,Part> parts=new TreeMap<>();
        String callId,name,namespace,reasoning;Object phase;boolean argumentsDone,done;
        Item(int index,String id,String type){this.index=index;this.id=id;this.type=type;}
    }
    private static final class Part {final String type;final StringBuilder raw=new StringBuilder();ResponsesTextStream.TextFilter filter;boolean valueDone,done;Part(String type){this.type=type;}}
}
