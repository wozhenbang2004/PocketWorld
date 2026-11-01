package dev.pocketengine.runtime.providers;

import java.util.*;
import org.json.*;

/** Lossless text/function result projection for the two enabled cross-protocol pairs. */
final class ProtocolResponse {
    static JSONObject encode(GatewayRequest request,JSONObject wire,String id)throws Exception {
        if(request.ingressProtocol.equals(request.egressProtocol))return wire;
        if(request.ingressProtocol.equals("anthropic")){
            JSONArray blocks=new JSONArray(),output=wire.getJSONArray("output");boolean tools=false;
            for(int i=0;i<output.length();i++){JSONObject item=output.getJSONObject(i);switch(item.getString("type")){
                case "message":if(!"assistant".equals(item.optString("role","assistant")))throw unsupported();JSONArray content=item.getJSONArray("content");for(int j=0;j<content.length();j++){JSONObject p=content.getJSONObject(j);if(!"output_text".equals(p.optString("type"))||p.optJSONArray("annotations")!=null&&p.getJSONArray("annotations").length()!=0)throw unsupported();blocks.put(new JSONObject().put("type","text").put("text",p.getString("text")));}break;
                case "function_call":tools=true;blocks.put(new JSONObject().put("type","tool_use").put("id",item.getString("call_id")).put("name",item.getString("name")).put("input",ProtocolCodec.jsonObject(item.getString("arguments"))));break;
                default:throw unsupported();
            }}
            JSONObject result=new JSONObject().put("id",id).put("type","message").put("role","assistant").put("model",request.requestedAlias).put("content",blocks).put("stop_reason",tools?"tool_use":"end_turn").put("stop_sequence",JSONObject.NULL);
            result.put("usage",usage(request.egressProtocol,request.ingressProtocol,wire.optJSONObject("usage")));return result;
        }
        JSONArray content=wire.getJSONArray("content"),calls=new JSONArray();StringBuilder text=new StringBuilder();
        for(int i=0;i<content.length();i++){JSONObject p=content.getJSONObject(i);switch(p.getString("type")){
            case "text":if(calls.length()>0)throw unsupported();contentFields(p,"type","text");text.append(p.getString("text"));break;
            case "tool_use":contentFields(p,"type","id","name","input");calls.put(new JSONObject().put("type","function").put("id",p.getString("id")).put("function",new JSONObject().put("name",p.getString("name")).put("arguments",p.getJSONObject("input").toString())));break;
            default:throw unsupported();
        }}
        JSONObject assistant=new JSONObject().put("role","assistant").put("content",text.length()==0?JSONObject.NULL:text.toString());if(calls.length()>0)assistant.put("tool_calls",calls);
        return new JSONObject().put("id",id).put("object","chat.completion").put("created",System.currentTimeMillis()/1000).put("model",request.requestedAlias)
                .put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",assistant).put("finish_reason",calls.length()>0?"tool_calls":"stop")))
                .put("usage",usage(request.egressProtocol,request.ingressProtocol,wire.optJSONObject("usage")));
    }
    static JSONObject usage(String source,String target,JSONObject raw)throws Exception {
        JSONObject out=new JSONObject();if(raw==null)return out;
        if(target.equals("anthropic")){
            // OpenAI input includes cache reads; Messages input excludes them. Missing cache
            // breakdown is unknown, never invent a zero miss or subtract an assumed count.
            Long input=count(raw,"input_tokens"),output=count(raw,"output_tokens");JSONObject details=raw.optJSONObject("input_tokens_details");Long cache=count(details,"cached_tokens");
            if(input!=null&&cache!=null&&cache<=input)out.put("input_tokens",input-cache).put("cache_read_input_tokens",cache);
            else if(input!=null)out.put("input_tokens",JSONObject.NULL);
            if(output!=null)out.put("output_tokens",output);
        }else{
            Long input=count(raw,"input_tokens"),output=count(raw,"output_tokens"),read=count(raw,"cache_read_input_tokens"),write=count(raw,"cache_creation_input_tokens");
            // Anthropic omits cache fields when no caching is requested; absence is still unknown.
            if(input!=null&&read!=null&&write!=null){long prompt=Math.addExact(Math.addExact(input,read),write);out.put("prompt_tokens",prompt);if(output!=null)out.put("total_tokens",Math.addExact(prompt,output));}else if(input!=null)out.put("prompt_tokens",JSONObject.NULL).put("total_tokens",JSONObject.NULL);
            if(output!=null)out.put("completion_tokens",output);
            JSONObject details=new JSONObject();if(read!=null)details.put("cached_tokens",read);if(write!=null)details.put("cache_write_tokens",write);if(details.length()>0)out.put("prompt_tokens_details",details);
        }return out;
    }
    static void contentFields(JSONObject block,String... allowed)throws Exception {Set<String> fields=Set.of(allowed);for(Iterator<String> it=block.keys();it.hasNext();)if(!fields.contains(it.next()))throw unsupported();}
    private static Long count(JSONObject j,String k)throws Exception{if(j==null||!j.has(k)||j.isNull(k))return null;Object v=j.get(k);if(!(v instanceof Number))throw unsupported();long n=new java.math.BigDecimal(v.toString()).longValueExact();if(n<0)throw unsupported();return n;}
    static ProtocolCodec.Rejected unsupported(){return new ProtocolCodec.Rejected("upstream_output_not_representable");}
    private ProtocolResponse(){}
}
