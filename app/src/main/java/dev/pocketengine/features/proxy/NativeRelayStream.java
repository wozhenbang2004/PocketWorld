package dev.pocketengine.features.proxy;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.providers.ApiTurnStream;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Bounded native frames through the existing provider state machines, not a second completion parser. */
public final class NativeRelayStream {
    public interface Sink {void frame(String event,JSONObject data)throws Exception;}
    static void relay(InputStream input,NativeRelayPolicy.Prepared request,RunControl original,String secret,dev.pocketengine.runtime.usage.ProviderUsage.Observer usage,Sink sink)throws Exception{
        Redactor redactor=new Redactor(request.config.provider,secret,sink);
        ApiTurnStream.relayNative(input,request.config.provider,ProviderAdapters.selected(request.config.provider),request.tools,original,usage,(event,frame)->{
            request.revalidate();redactor.accept(event,frame);
        });
        redactor.finish(); // Only after the native parser validated the actual complete terminal.
    }
    /** Text actually present in a validated native frame, not reasoning or function arguments. */
    static boolean hasVisibleText(String protocol,JSONObject data){
        if("openai_responses".equals(protocol)){
            String type=data.optString("type");
            if(type.equals("response.output_text.delta"))return nonempty(data.opt("delta"));
            if(type.equals("response.content_part.added")){JSONObject part=data.optJSONObject("part");return part!=null&&"output_text".equals(part.optString("type"))&&nonempty(part.opt("text"));}
            JSONObject response=data.optJSONObject("response");JSONArray output=response==null?null:response.optJSONArray("output");
            for(int i=0;output!=null&&i<output.length();i++){JSONObject item=output.optJSONObject(i);JSONArray content=item==null||!"message".equals(item.optString("type"))?null:item.optJSONArray("content");for(int j=0;content!=null&&j<content.length();j++){JSONObject part=content.optJSONObject(j);if(part!=null&&"output_text".equals(part.optString("type"))&&nonempty(part.opt("text")))return true;}}
        }else if("anthropic".equals(protocol)){
            JSONObject block="content_block_start".equals(data.optString("type"))?data.optJSONObject("content_block"):"content_block_delta".equals(data.optString("type"))?data.optJSONObject("delta"):null;
            return block!=null&&("text".equals(block.optString("type"))||"text_delta".equals(block.optString("type")))&&nonempty(block.opt("text"));
        }else if("gemini".equals(protocol)){
            JSONArray candidates=data.optJSONArray("candidates");for(int i=0;candidates!=null&&i<candidates.length();i++){JSONObject candidate=candidates.optJSONObject(i),content=candidate==null?null:candidate.optJSONObject("content");JSONArray parts=content==null?null:content.optJSONArray("parts");for(int j=0;parts!=null&&j<parts.length();j++){JSONObject part=parts.optJSONObject(j);if(part!=null&&!part.optBoolean("thought",false)&&nonempty(part.opt("text")))return true;}}
        }
        return false;
    }
    private static boolean nonempty(Object value){return value instanceof String&&!((String)value).isEmpty();}
    private static final class Frame {
        final String event;final JSONObject json;final int bytes;int blocked;
        Frame(String event,JSONObject json,int bytes){this.event=event;this.json=json;this.bytes=bytes;}
    }
    private static final class Text {
        final Frame frame;final JSONObject object;final String key;
        Text(Frame frame,JSONObject object,String key){this.frame=frame;this.object=object;this.key=key;}
        String value(){return object.optString(key);}
    }
    /** Keep only possible credential suffixes; unrelated block/item streams never join. */
    public static final class Redactor {
        final String protocol,secret;final Sink sink;final Deque<Frame> pending=new ArrayDeque<>();final Map<String,List<Text>> tails=new HashMap<>();int bytes;
        public Redactor(String protocol,String secret,Sink sink){this.protocol=protocol;this.secret=secret;this.sink=sink;}
        public void accept(String event,JSONObject original)throws Exception{
            if(!event.isEmpty()&&!event.matches("[A-Za-z0-9_.-]{1,160}"))throw new java.io.IOException("native event 名称无效");
            if(!secret.isEmpty()&&event.contains(secret))throw new java.io.IOException("native event 与凭据冲突");
            Frame frame=new Frame(event,(JSONObject)ChatCompletionStream.scrub(original,secret),original.toString().getBytes(StandardCharsets.UTF_8).length);pending.add(frame);bytes+=frame.bytes;
            switch(protocol){
                case "openai_chat":{
                    JSONArray choices=original.optJSONArray("choices"),targets=frame.json.optJSONArray("choices");
                    for(int c=0;choices!=null&&c<choices.length();c++){
                        JSONObject delta=choices.getJSONObject(c).optJSONObject("delta"),target=targets.getJSONObject(c).optJSONObject("delta");
                        if(delta==null||target==null)continue;
                        for(String field:new String[]{"content","reasoning_content"})if(delta.opt(field) instanceof String)text(frame,target,field,delta.getString(field),"chat/"+c+"/"+field);
                        JSONArray calls=delta.optJSONArray("tool_calls"),out=target.optJSONArray("tool_calls");
                        for(int i=0;calls!=null&&i<calls.length();i++){JSONObject call=calls.getJSONObject(i),fn=call.optJSONObject("function"),dest=out.getJSONObject(i).optJSONObject("function");
                            if(fn!=null&&dest!=null&&fn.opt("arguments") instanceof String)text(frame,dest,"arguments",fn.getString("arguments"),"chat/"+c+"/tool/"+call.optInt("index",-1));}
                    }break;
                }
                case "openai_responses":{
                    String type=original.optString("type"),path="responses/"+original.optInt("output_index",-1)+"/"+original.optInt("content_index",-1)+"/";
                    if(type.endsWith(".delta")&&original.opt("delta") instanceof String)text(frame,frame.json,"delta",original.getString("delta"),path+type);
                    else if(type.equals("response.output_item.added")){JSONObject item=original.getJSONObject("item"),target=frame.json.getJSONObject("item");if(item.opt("arguments") instanceof String)text(frame,target,"arguments",item.getString("arguments"),"responses/"+original.optInt("output_index",-1)+"/-1/response.function_call_arguments.delta");}
                    else if(type.equals("response.content_part.added")){JSONObject part=original.getJSONObject("part"),target=frame.json.getJSONObject("part");if(part.opt("text") instanceof String)text(frame,target,"text",part.getString("text"),path+"response.output_text.delta");}
                    // Callers validate lifecycle and identity before passing a frame here. A closed
                    // text path cannot complete a credential prefix; unrelated paths stay held.
                    else if(type.equals("response.output_text.done")||type.equals("response.content_part.done"))release(path+"response.output_text.delta");
                    else if(type.equals("response.function_call_arguments.done"))release("responses/"+original.getInt("output_index")+"/-1/response.function_call_arguments.delta");
                    else if(type.equals("response.output_item.done")){
                        JSONObject item=original.getJSONObject("item");String prefix="responses/"+original.getInt("output_index")+"/";
                        if("function_call".equals(item.optString("type")))release(prefix+"-1/response.function_call_arguments.delta");
                        else if("message".equals(item.optString("type"))){JSONArray content=item.optJSONArray("content");for(int i=0;content!=null&&i<content.length();i++)release(prefix+i+"/response.output_text.delta");}
                    }
                    else if(type.equals("response.completed"))releaseAll();
                    break;
                }
                case "anthropic":{
                    String type=original.optString("type"),path="anthropic/"+original.optInt("index",-1)+"/";
                    JSONObject delta=original.optJSONObject(type.equals("content_block_start")?"content_block":"delta"),target=frame.json.optJSONObject(type.equals("content_block_start")?"content_block":"delta");
                    if(delta!=null&&target!=null&&(type.equals("content_block_start")||type.equals("content_block_delta")))for(String field:new String[]{"text","partial_json","thinking","signature"})if(delta.opt(field) instanceof String)text(frame,target,field,delta.getString(field),path+field);
                    break;
                }
                case "gemini":{
                    JSONArray candidates=original.optJSONArray("candidates"),targets=frame.json.optJSONArray("candidates");
                    for(int c=0;candidates!=null&&c<candidates.length();c++){JSONObject content=candidates.getJSONObject(c).optJSONObject("content");JSONArray parts=content==null?null:content.optJSONArray("parts");
                        for(int i=0;parts!=null&&i<parts.length();i++){JSONObject part=parts.getJSONObject(i),target=targets.getJSONObject(c).getJSONObject("content").getJSONArray("parts").getJSONObject(i);if(part.opt("text") instanceof String)text(frame,target,"text",part.getString("text"),"gemini/"+c+"/"+part.optBoolean("thought",false));}
                    }break;
                }
                default:throw new IllegalArgumentException("native relay protocol");
            }
            drain();if(bytes>256*1024||pending.size()>256||tails.size()>256)throw new java.io.IOException("凭据分片保护达到本机有界缓冲限制，流未标完整");
        }
        void text(Frame frame,JSONObject target,String field,String raw,String path)throws Exception{
            if(secret.isEmpty())return;target.put(field,raw);List<Text> fragments=tails.remove(path);if(fragments==null)fragments=new ArrayList<>();for(Text prior:fragments)prior.frame.blocked--;fragments.add(new Text(frame,target,field));
            StringBuilder combined=new StringBuilder();for(Text fragment:fragments)combined.append(fragment.value());String value=combined.toString();List<Integer> matches=new ArrayList<>();for(int p=value.indexOf(secret);p>=0;p=value.indexOf(secret,p+secret.length()))matches.add(p);
            String replacement="[upstream credential hidden]";if(replacement.contains(secret))replacement="";int offset=0;
            for(Text fragment:fragments){String text=fragment.value();StringBuilder safe=new StringBuilder();int cursor=0;for(int match:matches){int from=Math.max(0,match-offset),to=Math.min(text.length(),match+secret.length()-offset);if(from<to){safe.append(text,cursor,from);if(match>=offset)safe.append(replacement);cursor=to;}}safe.append(text,cursor,text.length());fragment.object.put(fragment.key,safe.toString());offset+=text.length();}
            combined.setLength(0);for(Text fragment:fragments)combined.append(fragment.value());int suffix=Math.min(secret.length()-1,combined.length());while(suffix>0&&!combined.substring(combined.length()-suffix).equals(secret.substring(0,suffix)))suffix--;
            if(suffix>0){List<Text> held=new ArrayList<>();int remaining=suffix;for(int i=fragments.size()-1;i>=0&&remaining>0;i--){Text fragment=fragments.get(i);if(!fragment.value().isEmpty()){held.add(0,fragment);fragment.frame.blocked++;remaining-=fragment.value().length();}}tails.put(path,held);}
        }
        void release(String path){List<Text> held=tails.remove(path);if(held!=null)for(Text fragment:held)fragment.frame.blocked--;}
        void releaseAll(){for(List<Text> held:tails.values())for(Text fragment:held)fragment.frame.blocked--;tails.clear();}
        void drain()throws Exception{while(!pending.isEmpty()&&pending.peek().blocked==0){Frame frame=pending.remove();bytes-=frame.bytes;sink.frame(frame.event,frame.json);}}
        public void finish()throws Exception{releaseAll();drain();}
    }
    private NativeRelayStream(){}
}
