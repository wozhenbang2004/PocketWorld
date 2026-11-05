package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Bounded native Responses text events; no relay buffering, model execution, tools or automatic replay. */
final class ResponsesTextStream {
    static final int MAX_EVENT_BYTES=64*1024,MAX_STREAM_BYTES=2*1024*1024,MAX_TEXT_CHARS=256*1024;
    static void read(InputStream input,GatewayCall call,ChatGptProvider.EventSink sink,String... secrets)throws Exception {
        Parser parser=new Parser(input,call);TextFilter filter=new TextFilter(sink,secrets);TextFilter refusal=new TextFilter(event->{event.put("type","refusal_delta");sink.event(event);},secrets);int textChars=0;long sequence=-1;
        while(true){
            String data=parser.next();if(data==null)throw new GatewayFailure("PROVIDER_STREAM_INTERRUPTED");
            JSONObject event;
            try{event=ResponsesRequest.object(data);}catch(Exception invalid){throw new GatewayFailure("INVALID_PROVIDER_STREAM");}
            String type=event.optString("type");if(!parser.event.isEmpty()&&!parser.event.equals(type))throw new GatewayFailure("INVALID_PROVIDER_STREAM");
            if(event.has("sequence_number")){long current=event.getLong("sequence_number");if(current<=sequence)throw new GatewayFailure("INVALID_PROVIDER_STREAM");sequence=current;}
            call.check();
            switch(type){
                case "response.output_text.delta":{
                    Object value=event.opt("delta");if(!(value instanceof String))throw new GatewayFailure("INVALID_PROVIDER_STREAM");String delta=(String)value;
                    textChars+=delta.length();if(textChars>MAX_TEXT_CHARS)throw new GatewayFailure("PROVIDER_RESPONSE_TOO_LARGE");filter.text(delta);break;
                }
                case "response.refusal.delta":{
                    Object value=event.opt("delta");if(!(value instanceof String))throw new GatewayFailure("INVALID_PROVIDER_STREAM");String delta=(String)value;
                    textChars+=delta.length();if(textChars>MAX_TEXT_CHARS)throw new GatewayFailure("PROVIDER_RESPONSE_TOO_LARGE");refusal.text(delta);break;
                }
                case "response.refusal.done":break;
                case "response.output_item.added":case "response.output_item.done":{
                    JSONObject item=event.optJSONObject("item");if(item==null||!"message".equals(item.optString("type")))throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");break;
                }
                case "response.content_part.added":case "response.content_part.done":{
                    JSONObject part=event.optJSONObject("part");if(part==null||(!"output_text".equals(part.optString("type"))&&!"refusal".equals(part.optString("type"))))throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");break;
                }
                case "response.created":case "response.in_progress":case "response.output_text.done":case "response.metadata":break;
                case "response.completed":{
                    JSONObject response=event.optJSONObject("response");if(response==null||!"completed".equals(response.optString("status")))throw new GatewayFailure("INVALID_PROVIDER_STREAM");
                    filter.finish();refusal.finish();JSONObject safe=new JSONObject().put("type","completed");JSONObject usage=response.optJSONObject("usage");
                    if(usage!=null){JSONObject counts=new JSONObject();for(String key:new String[]{"input_tokens","output_tokens","total_tokens"})if(usage.has(key)){Object value=usage.get(key);if(!(value instanceof Number)||((Number)value).longValue()<0)throw new GatewayFailure("INVALID_PROVIDER_STREAM");counts.put(key,((Number)value).longValue());}safe.put("usage",counts);}
                    call.check();sink.event(safe);return;
                }
                case "response.incomplete":throw new GatewayFailure("PROVIDER_RESPONSE_INCOMPLETE");
                case "response.failed":case "error":{
                    JSONObject failure=event.optJSONObject("error");JSONObject response=event.optJSONObject("response");if(response!=null)failure=response.optJSONObject("error");
                    throw dev.pocketengine.features.proxy.accounts.ChatGptRecovery.streamFailure(type.equals("error")?event:failure);
                }
                default:throw new GatewayFailure("UNSUPPORTED_PROVIDER_OUTPUT");
            }
        }
    }
    static final class TextFilter {
        final ChatGptProvider.EventSink sink;final String[] secrets;final int[][] prefixes;String pending="";
        TextFilter(ChatGptProvider.EventSink sink,String[] secrets){
            this.sink=sink;this.secrets=secrets;prefixes=new int[secrets.length][];
            for(int i=0;i<secrets.length;i++){
                String secret=secrets[i];int[] table=new int[secret.length()];
                for(int n=1,j=0;n<secret.length();n++){while(j>0&&secret.charAt(n)!=secret.charAt(j))j=table[j-1];if(secret.charAt(n)==secret.charAt(j))j++;table[n]=j;}prefixes[i]=table;
            }
        }
        void text(String delta)throws Exception {
            pending+=delta;
            for(String secret:secrets)if(!secret.isEmpty())pending=pending.replace(secret,"[redacted]");
            int retained=0;
            for(int i=0;i<secrets.length;i++){
                String secret=secrets[i];if(secret.isEmpty())continue;int matched=0;
                for(int n=0;n<pending.length();n++){while(matched>0&&(matched==secret.length()||pending.charAt(n)!=secret.charAt(matched)))matched=prefixes[i][matched-1];if(pending.charAt(n)==secret.charAt(matched))matched++;}
                retained=Math.max(retained,matched);
            }
            int send=pending.length()-retained;if(send>0&&Character.isHighSurrogate(pending.charAt(send-1)))send--;if(send>0){emit(pending.substring(0,send));pending=pending.substring(send);}
        }
        void finish()throws Exception{if(!pending.isEmpty()){emit(pending);pending="";}}
        void emit(String value)throws Exception{try{ResponsesRequest.unicode(value);}catch(GatewayFailure invalid){throw new GatewayFailure("INVALID_PROVIDER_STREAM");}sink.event(new JSONObject().put("type","text_delta").put("delta",value));}
    }
    static final class Parser {
        final InputStream input;final GatewayCall call;int total,bytes;boolean skipLf,first=true;String event="";
        Parser(InputStream input,GatewayCall call){this.input=new BufferedInputStream(input,8192);this.call=call;}
        String next()throws Exception {
            bytes=0;event="";StringBuilder data=new StringBuilder();boolean hasData=false;
            while(true){String line=line();if(line==null)return null;if(first){first=false;if(line.startsWith("\uFEFF"))line=line.substring(1);}
                if(line.isEmpty()){if(hasData)return data.toString();bytes=0;event="";continue;}
                int colon=line.indexOf(':');String key=colon<0?line:line.substring(0,colon),value=colon<0?"":line.substring(colon+1);if(value.startsWith(" "))value=value.substring(1);
                if(key.equals("data")){if(hasData)data.append('\n');data.append(value);hasData=true;}else if(key.equals("event"))event=value;
            }
        }
        String line()throws Exception {
            ByteArrayOutputStream line=new ByteArrayOutputStream();
            while(true){call.check();int b=input.read();if(b==-1)return null;if(++total>MAX_STREAM_BYTES||++bytes>MAX_EVENT_BYTES)throw new GatewayFailure("PROVIDER_RESPONSE_TOO_LARGE");
                if(skipLf){skipLf=false;if(b=='\n')continue;}if(b=='\n')break;if(b=='\r'){skipLf=true;break;}line.write(b);}
            try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString();}
            catch(Exception invalid){throw new GatewayFailure("INVALID_PROVIDER_STREAM");}
        }
    }
}
