package dev.pocketengine.runtime.providers;

import dev.pocketengine.runtime.usage.InferenceCapture;
import org.json.JSONArray;
import org.json.JSONObject;

/** Projection is synchronous and indexed by actual native block/call identity. The raw
 * InferenceCapture observer remains the authority for egress usage and finish reasons.
 * This per-physical-call projection owns only stream identity mappings and event sequence.
 * It does not authorize output, commit semantic effects, retry, or send wire frames:
 * ProtocolGateway retains those decisions before/after invoking this decoder.
 * The owner calls it serially; these mutable identities must not be shared across calls. */
final class ProtocolEventProjection {
    private final String call,sourceProtocol,wireProtocol;private final InferenceCapture capture;private long sequence;
    private final java.util.Map<String,String> ids=new java.util.HashMap<>();
    ProtocolEventProjection(InferenceCapture capture,String source,String wire){this.capture=capture;call=capture.callId;sourceProtocol=source;wireProtocol=wire;}
    java.util.List<GatewayEvent> decode(long wireSequence,String event,String data)throws Exception {
        java.util.List<GatewayEvent> out=new java.util.ArrayList<>();
        if(data.equals("[DONE]")){add(out,GatewayEvent.Type.COMPLETE,"","","",null);return out;}
        JSONObject j=new JSONObject(data);String type=j.optString("type",event),item=j.optString("item_id");
        if(type.equals("message_start")||type.equals("response.created")){JSONObject message=j.optJSONObject(type.equals("message_start")?"message":"response");add(out,GatewayEvent.Type.BEGIN,message==null?"":message.optString("id"),"","",j);}
        else if(type.equals("content_block_start")){JSONObject block=j.getJSONObject("content_block");String key="block/"+j.getInt("index"),id=block.optString("id");ids.put(key,id);add(out,GatewayEvent.Type.PART_BEGIN,key,id,"",j);}
        else if(type.equals("content_block_delta")){JSONObject d=j.getJSONObject("delta");String key="block/"+j.getInt("index"),kind=d.getString("type");add(out,kind.equals("input_json_delta")?GatewayEvent.Type.TOOL_ARGS_DELTA:kind.equals("thinking_delta")?GatewayEvent.Type.REASONING_DELTA:GatewayEvent.Type.TEXT_DELTA,key,ids.getOrDefault(key,""),d.optString("partial_json",d.optString("text",d.optString("thinking"))),j);}
        else if(type.equals("response.output_item.added")){JSONObject v=j.getJSONObject("item");String key="item/"+j.getInt("output_index");ids.put(key,v.optString("call_id"));add(out,GatewayEvent.Type.PART_BEGIN,v.optString("id"),v.optString("call_id"),"",j);}
        else if(type.equals("response.content_part.added"))add(out,GatewayEvent.Type.PART_BEGIN,item,"","",j);
        else if(type.equals("response.function_call_arguments.delta"))add(out,GatewayEvent.Type.TOOL_ARGS_DELTA,item,ids.getOrDefault("item/"+j.getInt("output_index"),""),j.getString("delta"),j);
        else if(type.equals("response.output_text.delta"))add(out,GatewayEvent.Type.TEXT_DELTA,item,"",j.getString("delta"),j);
        else if(type.contains("reasoning")&&j.opt("delta") instanceof String)add(out,GatewayEvent.Type.REASONING_DELTA,item,"",j.getString("delta"),j);
        else if(type.equals("content_block_stop")){
            // Anthropic closes by block index, not Responses' item_id. Reuse
            // the identity from begin/delta so parallel blocks do not all close
            // an anonymous item. This describes a part, never a tool execution.
            String key="block/"+j.getInt("index");
            add(out,GatewayEvent.Type.PART_END,key,ids.getOrDefault(key,""),"",j);
        }
        else if(type.endsWith(".done"))add(out,GatewayEvent.Type.PART_END,item,"","",j);
        else if(type.equals("message_stop")||type.equals("response.completed"))add(out,GatewayEvent.Type.COMPLETE,item,"","",j);
        else if(type.equals("error"))add(out,GatewayEvent.Type.ERROR,item,"","",j);
        JSONArray choices=j.optJSONArray("choices");for(int i=0;choices!=null&&i<choices.length();i++){
            JSONObject choice=choices.getJSONObject(i),delta=choice.optJSONObject("delta");if(delta==null)continue;
            if(delta.has("role"))add(out,GatewayEvent.Type.BEGIN,j.optString("id"),"","",j);
            if(delta.opt("content") instanceof String)add(out,GatewayEvent.Type.TEXT_DELTA,j.optString("id"),"",delta.getString("content"),j);
            if(delta.opt("reasoning_content") instanceof String)add(out,GatewayEvent.Type.REASONING_DELTA,j.optString("id"),"",delta.getString("reasoning_content"),j);
            JSONArray calls=delta.optJSONArray("tool_calls");for(int t=0;calls!=null&&t<calls.length();t++){
                JSONObject tc=calls.getJSONObject(t);String key="chat/"+choice.optInt("index",0)+"/"+tc.getInt("index");JSONObject fn=tc.optJSONObject("function");
                if(tc.has("id")){boolean fresh=!ids.containsKey(key);ids.put(key,tc.getString("id"));if(fresh)add(out,GatewayEvent.Type.PART_BEGIN,key,tc.getString("id"),"",j);}
                if(fn!=null&&fn.opt("arguments") instanceof String&&!fn.getString("arguments").isEmpty())add(out,GatewayEvent.Type.TOOL_ARGS_DELTA,key,ids.getOrDefault(key,""),fn.getString("arguments"),j);
            }
        }
        if(j.optJSONObject("usage")!=null)add(out,GatewayEvent.Type.USAGE,"","","",j);
        JSONObject response=j.optJSONObject("response");if(response!=null&&response.optJSONObject("usage")!=null)add(out,GatewayEvent.Type.USAGE,response.optString("id"),"","",response);
        return out;
    }
    private void add(java.util.List<GatewayEvent> out,GatewayEvent.Type type,String item,String tool,String delta,JSONObject raw)throws Exception{out.add(new GatewayEvent(++sequence,call,type,item,tool,delta,raw,sourceProtocol,wireProtocol,type==GatewayEvent.Type.USAGE||type==GatewayEvent.Type.COMPLETE?capture.usage.toJson():null));}
}
