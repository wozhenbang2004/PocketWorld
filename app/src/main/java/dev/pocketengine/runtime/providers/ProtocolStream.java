package dev.pocketengine.runtime.providers;

import java.util.*;
import org.json.*;

/** Translates each already-validated native frame synchronously, never buffers a whole turn. */
final class ProtocolStream implements ApiTurnStream.WireFrameSink {
    private final GatewayRequest request;
    private final String id;
    private final ProtocolGateway.WireSink sink;
    private final Map<String,Integer> indexes=new LinkedHashMap<>();
    private final Map<Integer,JSONObject> blocks=new LinkedHashMap<>();
    private final Map<Integer,Integer> chatCalls=new LinkedHashMap<>();
    private final Set<Integer> ended=new HashSet<>();
    private final JSONObject usage=new JSONObject();
    private boolean started,tool;
    ProtocolStream(GatewayRequest request,String id,ProtocolGateway.WireSink sink){this.request=request;this.id=id;this.sink=sink;}
    @Override public void beforeTerminal(JSONObject nativeResult)throws Exception {
        // No translated success marker may escape before complete output representability checks.
        ProtocolResponse.encode(request,nativeResult,id);
    }
    public void frame(String event,String data)throws Exception {
        if(request.ingressProtocol.equals(request.egressProtocol)){sink.frame(event,data);return;}
        JSONObject raw=new JSONObject(data);
        if(request.ingressProtocol.equals("anthropic"))fromResponses(raw);else fromMessages(raw);
    }
    private void fromResponses(JSONObject e)throws Exception {
        String type=e.getString("type");
        switch(type){
            case "response.created":started=true;emit("message_start",new JSONObject().put("message",new JSONObject().put("id",id).put("type","message").put("role","assistant").put("model",request.requestedAlias).put("content",new JSONArray()).put("stop_reason",JSONObject.NULL).put("stop_sequence",JSONObject.NULL).put("usage",new JSONObject())));break;
            case "response.in_progress":break;
            case "response.output_item.added":{
                JSONObject item=e.getJSONObject("item");String kind=item.getString("type");if(kind.equals("function_call")){tool=true;int index=begin("tool/"+e.getInt("output_index"),new JSONObject().put("type","tool_use").put("id",item.getString("call_id")).put("name",item.getString("name")).put("input",new JSONObject()));String initial=item.optString("arguments");if(!initial.isEmpty())delta(index,"input_json_delta","partial_json",initial);}
                else if(!kind.equals("message"))throw ProtocolResponse.unsupported();break;
            }
            case "response.content_part.added":{JSONObject part=e.getJSONObject("part");if(!"output_text".equals(part.optString("type"))||part.optJSONArray("annotations")!=null&&part.getJSONArray("annotations").length()!=0)throw ProtocolResponse.unsupported();begin(textKey(e),new JSONObject().put("type","text").put("text",part.optString("text")));break;}
            case "response.output_text.delta":delta(index(textKey(e)),"text_delta","text",e.getString("delta"));break;
            case "response.function_call_arguments.delta":delta(index("tool/"+e.getInt("output_index")),"input_json_delta","partial_json",e.getString("delta"));break;
            case "response.function_call_arguments.done":end(index("tool/"+e.getInt("output_index")));break;
            case "response.output_text.done":break;
            case "response.content_part.done":end(index(textKey(e)));break;
            case "response.output_item.done":{JSONObject item=e.getJSONObject("item");if(item.getString("type").equals("function_call"))end(index("tool/"+e.getInt("output_index")));break;}
            case "response.completed":{
                JSONObject response=e.getJSONObject("response");JSONObject projected=ProtocolResponse.encode(request,response,id);
                // A complete-only upstream item is supported without inventing earlier deltas.
                JSONArray output=response.getJSONArray("output");for(int i=0;i<output.length();i++){JSONObject item=output.getJSONObject(i);if(item.getString("type").equals("function_call")){String key="tool/"+i;if(!indexes.containsKey(key)){tool=true;int n=begin(key,new JSONObject().put("type","tool_use").put("id",item.getString("call_id")).put("name",item.getString("name")).put("input",new JSONObject()));delta(n,"input_json_delta","partial_json",item.getString("arguments"));}}
                    else{JSONArray parts=item.getJSONArray("content");for(int j=0;j<parts.length();j++){String key="text/"+i+"/"+j;if(!indexes.containsKey(key))begin(key,new JSONObject().put("type","text").put("text",parts.getJSONObject(j).getString("text")));}}}
                for(int index:new ArrayList<>(blocks.keySet()))end(index);
                emit("message_delta",new JSONObject().put("delta",new JSONObject().put("stop_reason",projected.getString("stop_reason")).put("stop_sequence",JSONObject.NULL)).put("usage",projected.getJSONObject("usage")));
                emit("message_stop",new JSONObject());break;
            }
            default:throw ProtocolResponse.unsupported();
        }
    }
    private void fromMessages(JSONObject e)throws Exception {
        String type=e.getString("type");switch(type){
            case "ping":break;
            case "message_start":started=true;mergeUsage(e.getJSONObject("message").optJSONObject("usage"));chat(new JSONObject().put("role","assistant"),JSONObject.NULL);break;
            case "content_block_start":{int index=e.getInt("index");JSONObject block=e.getJSONObject("content_block");blocks.put(index,block);String kind=block.getString("type");
                if(kind.equals("text")){if(tool)throw ProtocolResponse.unsupported();ProtocolResponse.contentFields(block,"type","text");String text=block.optString("text");if(!text.isEmpty())chat(new JSONObject().put("content",text),JSONObject.NULL);}
                else if(kind.equals("tool_use")){ProtocolResponse.contentFields(block,"type","id","name","input");tool=true;int call=chatCalls.size();chatCalls.put(index,call);JSONObject fn=new JSONObject().put("name",block.getString("name")).put("arguments","");chat(new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",call).put("id",block.getString("id")).put("type","function").put("function",fn))),JSONObject.NULL);}
                else throw ProtocolResponse.unsupported();break;}
            case "content_block_delta":{int index=e.getInt("index");JSONObject delta=e.getJSONObject("delta");switch(delta.getString("type")){
                case "text_delta":chat(new JSONObject().put("content",delta.getString("text")),JSONObject.NULL);break;
                case "input_json_delta":JSONObject block=blocks.get(index);chat(new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",chatCalls.get(index)).put("id",block.getString("id")).put("function",new JSONObject().put("arguments",delta.getString("partial_json"))))),JSONObject.NULL);ended.add(index);break;
                default:throw ProtocolResponse.unsupported();}break;}
            case "content_block_stop":{int index=e.getInt("index");JSONObject block=blocks.get(index);if("tool_use".equals(block.optString("type"))&&!ended.contains(index))chat(new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",chatCalls.get(index)).put("function",new JSONObject().put("arguments",block.getJSONObject("input").toString())))),JSONObject.NULL);break;}
            case "message_delta":mergeUsage(e.optJSONObject("usage"));break; // Hold finish until real message_stop.
            case "message_stop":chat(new JSONObject(),tool?"tool_calls":"stop");JSONObject counters=ProtocolResponse.usage("anthropic","openai_chat",usage);if(counters.length()>0)sink.frame("",new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",System.currentTimeMillis()/1000).put("model",request.requestedAlias).put("choices",new JSONArray()).put("usage",counters).toString());sink.frame("","[DONE]");break;
            default:throw ProtocolResponse.unsupported();
        }
    }
    private void mergeUsage(JSONObject value)throws Exception{if(value!=null)for(Iterator<String> it=value.keys();it.hasNext();){String k=it.next();usage.put(k,value.get(k));}}
    private void chat(JSONObject delta,Object finish)throws Exception {sink.frame("",new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",System.currentTimeMillis()/1000).put("model",request.requestedAlias).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish))).toString());}
    private String textKey(JSONObject e)throws Exception{return "text/"+e.getInt("output_index")+"/"+e.getInt("content_index");}
    private int index(String key)throws Exception{Integer n=indexes.get(key);if(n==null)throw ProtocolResponse.unsupported();return n;}
    private int begin(String key,JSONObject block)throws Exception{if(!started||indexes.containsKey(key)||indexes.size()>=128)throw ProtocolResponse.unsupported();int n=indexes.size();indexes.put(key,n);blocks.put(n,block);emit("content_block_start",new JSONObject().put("index",n).put("content_block",block));return n;}
    private void delta(int index,String type,String field,String value)throws Exception{emit("content_block_delta",new JSONObject().put("index",index).put("delta",new JSONObject().put("type",type).put(field,value)));}
    private void end(int index)throws Exception{if(ended.add(index))emit("content_block_stop",new JSONObject().put("index",index));}
    private void emit(String type,JSONObject value)throws Exception{value.put("type",type);sink.frame(type,value.toString());}
}
