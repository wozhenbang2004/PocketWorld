package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceFailure;
import dev.pocketengine.runtime.usage.ProviderUsage;
import dev.pocketengine.features.proxy.NativeRelayStream;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.BufferedSink;
import org.json.*;

/** One physical API request owned by ModelGateway. No routing loop, tool executor or usage ledger. */
public final class ProtocolGateway {
    public interface SemanticCommit { void beforeFlush(String callId,long eventSeq)throws Exception; }
    public interface WireSink {
        void frame(String event,String data)throws Exception;
        default void canonical(GatewayEvent event)throws Exception{}
    }
    public static final class Failure extends IOException {
        private final InferenceFailure observation;
        Failure(String code,InferenceFailure observation){super(code);this.observation=observation;}
        public InferenceFailure observation(){return observation;}
    }
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).cache(null).build();
    public static JSONObject attempt(ModelConfig selected,GatewayRequest request,RunControl control,InferenceCapture capture,
            SemanticCommit commit,WireSink output)throws Exception {
        if(selected.usesAccount()||!selected.provider.equals(request.egressProtocol)||!selected.model.equals(request.config.model))throw new SecurityException("gateway_request_route_mismatch");
        if(commit==null)throw new SecurityException("semantic_commit_required");
        if(request.stream&&output==null)throw new IllegalArgumentException("stream_consumer_required");
        ModelConfig config=request.config;request.revalidate();control.check();config.requireTransportConsent(request.endpoint);
        byte[] bytes=request.wire.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>ModelGateway.MAX_REQUEST_BYTES)throw new ProtocolCodec.Rejected("request_byte_limit");
        RequestBody body=new RequestBody(){public MediaType contentType(){return MediaType.get("application/json; charset=utf-8");}public long contentLength(){return bytes.length;}public boolean isOneShot(){return true;}public void writeTo(BufferedSink sink)throws IOException{sink.write(bytes);}};
        Request.Builder builder=new Request.Builder().url(request.endpoint).post(body).header("Accept",request.stream?"text/event-stream":"application/json").header("Accept-Encoding","identity");
        for(var header:ProviderAdapters.selected(config.provider).headers(config).entrySet())builder.header(header.getKey(),header.getValue());
        java.util.concurrent.atomic.AtomicBoolean exchanged=new java.util.concurrent.atomic.AtomicBoolean();
        Call call=HTTP.newBuilder().addNetworkInterceptor(chain->{if(!exchanged.compareAndSet(false,true))throw new IOException("second_physical_exchange_forbidden");return chain.proceed(chain.request());}).connectTimeout(Math.min(15,config.requestTimeoutSeconds),TimeUnit.SECONDS).readTimeout(config.requestTimeoutSeconds,TimeUnit.SECONDS).writeTimeout(config.requestTimeoutSeconds,TimeUnit.SECONDS).build().newCall(builder.build());
        call.timeout().timeout(capture.remainingMillis(config),TimeUnit.MILLISECONDS);Runnable cancel=call::cancel;control.onCancel(cancel);
        final boolean[] committed={false};final long[] sequence={0};final EventProjection events=new EventProjection(capture,request.egressProtocol,request.ingressProtocol);final String responseId="gw_"+capture.callId.replace("-","");
        WireSink guarded=(event,data)->{
            control.check();request.revalidate();if(event.equals("ping")){output.frame(event,data);return;}long seq=++sequence[0];
            if(!committed[0]){commit.beforeFlush(capture.callId,seq);committed[0]=true;}
            // Native wire frames can carry binding identities; commit them before any callback.
            for(GatewayEvent item:events.decode(seq,event,data))output.canonical(item);output.frame(event,data);
        };
        NativeRelayStream.Redactor redactor=request.stream?new NativeRelayStream.Redactor(request.ingressProtocol,config.apiKey,(event,data)->guarded.frame(event,wireJson(data))):null;
        WireSink safe=request.stream?(event,data)->{if(data.equals("[DONE]")){redactor.finish();guarded.frame(event,data);}else redactor.accept(event,new JSONObject(data));}:null;
        int responseStatus=0;
        try {
            capture.dispatch();control.check();
            try(Response response=call.execute()){
                responseStatus=response.code();
                if(!response.isSuccessful())throw new Failure("upstream_http_"+response.code(),InferenceFailure.classifiedHttp(response.code(),safeHeaders(response.headers("Retry-After"),config.apiKey),safeHeaders(response.headers("Date"),config.apiKey),System.currentTimeMillis(),errorBody(response.body())));
                ResponseBody responseBody=response.body();if(responseBody==null)throw new IOException("upstream_body_missing");
                String encoding=response.header("Content-Encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("upstream_encoding_unsupported");
                JSONObject nativeResult;
                if(request.stream){
                    String content=response.header("Content-Type");if(content==null||!content.split(";",2)[0].trim().equalsIgnoreCase("text/event-stream"))throw new IOException("upstream_stream_type_required");
                    ProtocolStream translator=new ProtocolStream(request,responseId,safe);
                    try(InputStream input=responseBody.byteStream()){nativeResult=ApiTurnStream.readWire(input,config.provider,ProviderAdapters.selected(config.provider),request.tools,control,capture.usage,translator);}
                    redactor.finish();
                }else{
                    byte[] input;try(InputStream stream=responseBody.byteStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1){control.check();if(out.size()+n>ApiTurnStream.MAX_BYTES)throw new IOException("upstream_body_too_large");out.write(buffer,0,n);}input=out.toByteArray();}
                    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
                    ApiTurnStream.completeJson(config.provider,ProviderAdapters.selected(config.provider),text,request.tools,delta->{},capture.usage);nativeResult=new JSONObject(text);
                }
                control.check();request.revalidate();JSONObject result=ProtocolResponse.encode(request,nativeResult,responseId);
                if(!committed[0]){commit.beforeFlush(capture.callId,++sequence[0]);committed[0]=true;}
                return scrub(result,config.apiKey);
            }
        }catch(Exception error){
            InferenceFailure.Kind kind=control.isCancelled()?InferenceFailure.Kind.CANCELLED:error instanceof java.io.InterruptedIOException?InferenceFailure.Kind.TIMEOUT:responseStatus==0&&error instanceof IOException?InferenceFailure.Kind.NETWORK:committed[0]?InferenceFailure.Kind.INCOMPLETE_STREAM:InferenceFailure.Kind.PROTOCOL;
            InferenceFailure failure=(error instanceof Failure?((Failure)error).observation():InferenceFailure.local(kind,System.currentTimeMillis())).withPartialText(committed[0]);capture.failure(failure);
            if(error instanceof IOException&&!(error instanceof Failure)){Failure typed=new Failure("native_transport_failed",failure);typed.initCause(error);throw typed;}throw error;
        }finally{control.removeCancelAction(cancel);call.cancel();}
    }
    /** Projection is synchronous and indexed by actual native block/call identity. The raw
     * InferenceCapture observer remains the authority for egress usage and finish reasons. */
    private static final class EventProjection {
        private final String call,sourceProtocol,wireProtocol;private final InferenceCapture capture;private long sequence;
        private final java.util.Map<String,String> ids=new java.util.HashMap<>();
        EventProjection(InferenceCapture capture,String source,String wire){this.capture=capture;call=capture.callId;sourceProtocol=source;wireProtocol=wire;}
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
            else if(type.equals("content_block_stop")||type.endsWith(".done"))add(out,GatewayEvent.Type.PART_END,item,"","",j);
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
    private static String errorBody(ResponseBody body){
        if(body==null)return "";try(InputStream in=body.byteStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>16384)return "";out.write(buffer,0,n);}return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}catch(Exception ignored){return "";}
    }
    /** A text delta may carry half of a UTF-16 pair as a JSON escape. Keep that escape on
     * the wire; UTF-8 encoding an isolated Java surrogate would silently replace it with '?'. */
    private static String wireJson(JSONObject value){String text=value.toString();StringBuilder out=new StringBuilder();for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)&&i+1<text.length()&&Character.isLowSurrogate(text.charAt(i+1))){out.append(c).append(text.charAt(++i));}else if(Character.isSurrogate(c))out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}return out.toString();}
    private static java.util.List<String> safeHeaders(java.util.List<String> values,String secret){if(!secret.isEmpty())for(String value:values)if(value.contains(secret))return java.util.List.of("invalid_header");return values;}
    private static JSONObject scrub(JSONObject value,String secret)throws Exception{return new JSONObject(secret.isEmpty()?value.toString():value.toString().replace(JSONObject.quote(secret).substring(1,JSONObject.quote(secret).length()-1),"[upstream credential hidden]"));}
    private ProtocolGateway(){}
}
