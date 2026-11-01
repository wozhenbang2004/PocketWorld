package dev.pocketengine.features.proxy;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real authenticated local client -> original relay -> loopback upstream; not a live provider. */
public final class NativeProtocolRelayTest {
    private static final String TOKEN="native-protocol-local-token-123456",KEY="sk_native_private_123456789";
    private static final List<String> PROTOCOLS=List.of("openai_responses","anthropic","gemini");
    private record Reply(int status,String contentType,String body){}
    private static final class Rig implements AutoCloseable {
        final HttpServer upstream;final ExecutorService workers=Executors.newCachedThreadPool();final AtomicInteger posts=new AtomicInteger();final AtomicReference<ModelConfig> current;
        final AtomicLong selection=new AtomicLong(1);final AtomicReference<Throwable> failure=new AtomicReference<>();final String protocol;volatile JSONObject input;volatile String path,credential,replyOverride;volatile Runnable onRequest=()->{};
        Rig(String protocol)throws Exception{this.protocol=protocol;upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.setExecutor(workers);upstream.createContext("/",exchange->{try{
            posts.incrementAndGet();path=exchange.getRequestURI().toString();credential=exchange.getRequestHeaders().getFirst(protocol.equals("gemini")?"x-goog-api-key":"Authorization");input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));onRequest.run();boolean streaming=protocol.equals("gemini")?path.contains(":streamGenerateContent"):input.optBoolean("stream");
            String result=replyOverride==null?streaming?stream(protocol,"ACTUAL-NATIVE-ANSWER"):result(protocol,"ACTUAL-NATIVE-ANSWER").toString():replyOverride;byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",streaming?"text/event-stream":"application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Throwable bad){failure.compareAndSet(null,bad);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});upstream.start();current=new AtomicReference<>(new ModelConfig("http://127.0.0.1:"+upstream.getAddress().getPort()+(protocol.equals("gemini")?"/v1beta":"/v1"),KEY,"configured-model",false,4,10,protocol,512));}
        LoopbackRelay relay()throws Exception{return new LoopbackRelay(0,TOKEN,new LoopbackRelay.ConfigSource(){public ModelConfig get(){return current.get();}public long selectionRevision(){return selection.get();}},null,current.get());}
        public void close()throws Exception{upstream.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError("actual fixture HTTP handler",failure.get());}
    }
    private static JSONObject request(String protocol,boolean stream)throws Exception{
        if(protocol.equals("openai_responses"))return new JSONObject().put("model","configured-model").put("input","client native input").put("stream",stream);
        if(protocol.equals("anthropic"))return new JSONObject().put("model","configured-model").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","client native input"))).put("stream",stream);
        return new JSONObject().put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text","client native input")))));
    }
    private static String path(String protocol,boolean stream){return switch(protocol){case "openai_responses"->"/v1/responses";case "anthropic"->"/v1/messages";default->"/v1beta/models/configured-model:"+(stream?"streamGenerateContent?alt=sse":"generateContent");};}
    private static JSONObject result(String protocol,String text)throws Exception{
        if(protocol.equals("openai_responses"))return new JSONObject().put("id","response-one").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("id","message-one").put("type","message").put("role","assistant").put("status","completed").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text)))));
        if(protocol.equals("anthropic"))return new JSONObject().put("id","message-one").put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text))).put("stop_reason","end_turn");
        return new JSONObject().put("responseId","response-one").put("candidates",new JSONArray().put(new JSONObject().put("index",0).put("content",new JSONObject().put("role","model").put("parts",new JSONArray().put(new JSONObject().put("text",text)))).put("finishReason","STOP")));
    }
    private static String frame(String type,JSONObject data)throws Exception{return "event: "+type+"\ndata: "+data.put("type",type)+"\n\n";}
    private static String stream(String protocol,String text)throws Exception{
        if(protocol.equals("openai_responses"))return frame("response.created",new JSONObject().put("response",new JSONObject().put("id","response-one").put("status","in_progress")))+frame("response.completed",new JSONObject().put("response",result(protocol,text)));
        if(protocol.equals("anthropic"))return anthropicStart()+frame("content_block_start",new JSONObject().put("index",0).put("content_block",new JSONObject().put("type","text").put("text","")))+frame("content_block_delta",new JSONObject().put("index",0).put("delta",new JSONObject().put("type","text_delta").put("text",text)))+anthropicEnd();
        return "data: "+result(protocol,text)+"\n\n";
    }
    private static String anthropicStart()throws Exception{return frame("message_start",new JSONObject().put("message",new JSONObject().put("id","message-one").put("type","message").put("role","assistant").put("content",new JSONArray())));}
    private static String anthropicEnd()throws Exception{return frame("content_block_stop",new JSONObject().put("index",0))+frame("message_delta",new JSONObject().put("delta",new JSONObject().put("stop_reason","end_turn")))+frame("message_stop",new JSONObject());}
    private static Reply send(LoopbackRelay relay,String path,JSONObject body,String authHeader,String credential)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+relay.port()+path).openConnection();connection.setConnectTimeout(2000);connection.setReadTimeout(5000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");if(authHeader!=null)connection.setRequestProperty(authHeader,credential);byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
        try{try(var out=connection.getOutputStream()){out.write(bytes);}int code=connection.getResponseCode();var input=code<400?connection.getInputStream():connection.getErrorStream();return new Reply(code,connection.getContentType(),input==null?"":new String(input.readAllBytes(),StandardCharsets.UTF_8));}finally{connection.disconnect();}
    }
    private static Reply send(LoopbackRelay relay,String protocol,boolean streaming,JSONObject body)throws Exception{return send(relay,path(protocol,streaming),body,"Authorization","Bearer "+TOKEN);}
    @Test public void allThreeNativeJsonClientsReachOnlyTheirConfiguredProtocolAndCredential()throws Exception{
        for(String protocol:PROTOCOLS)try(Rig rig=new Rig(protocol);LoopbackRelay relay=rig.relay()){
            Reply reply=send(relay,protocol,false,request(protocol,false));assertEquals(protocol,200,reply.status);assertTrue(reply.body.contains("ACTUAL-NATIVE-ANSWER"));assertEquals(1,rig.posts.get());assertEquals(protocol.equals("gemini")?KEY:"Bearer "+KEY,rig.credential);
            assertFalse(rig.input.toString().contains(TOKEN));assertEquals(path(protocol,false),rig.path);assertEquals(512,protocol.equals("gemini")?rig.input.getJSONObject("generationConfig").getInt("maxOutputTokens"):rig.input.getInt(protocol.equals("anthropic")?"max_tokens":"max_output_tokens"));
            if(protocol.equals("openai_responses")){assertEquals("client native input",rig.input.getString("input"));assertFalse(rig.input.getBoolean("store"));}
        }
    }
    @Test public void allThreeNativeSseClientsReceiveTheirRealNamedTerminalNeverSyntheticDone()throws Exception{
        for(String protocol:PROTOCOLS)try(Rig rig=new Rig(protocol);LoopbackRelay relay=rig.relay()){
            Reply reply=send(relay,protocol,true,request(protocol,true));assertEquals(protocol,200,reply.status);assertTrue(reply.contentType.startsWith("text/event-stream"));assertTrue(reply.body.contains("ACTUAL-NATIVE-ANSWER"));assertFalse(reply.body.contains("[DONE]"));
            assertTrue(reply.body.contains(protocol.equals("openai_responses")?"event: response.completed":protocol.equals("anthropic")?"event: message_stop":"\"finishReason\":\"STOP\""));assertEquals(1,rig.posts.get());
        }
    }
    @Test public void protocolMismatchAndModelReplacementAreRejectedBeforeAnyUpstream()throws Exception{
        for(String protocol:PROTOCOLS)try(Rig rig=new Rig(protocol);LoopbackRelay relay=rig.relay()){
            assertEquals(400,send(relay,"/v1/chat/completions",request("openai_responses",false),"Authorization","Bearer "+TOKEN).status);
            JSONObject changed=request(protocol,false);if(protocol.equals("gemini"))assertEquals(400,send(relay,"/v1beta/models/another:generateContent",changed,"Authorization","Bearer "+TOKEN).status);else assertEquals(400,send(relay,protocol,false,changed.put("model","another")).status);assertEquals(0,rig.posts.get());
        }
    }
    @Test public void nativeHeaderCarriesOnlyLocalTokenAndCannotExecutePhoneOrHostedTools()throws Exception{
        try(Rig rig=new Rig("anthropic");LoopbackRelay relay=rig.relay()){
            assertEquals(401,send(relay,path("anthropic",false),request("anthropic",false),"x-api-key","wrong").status);assertEquals(200,send(relay,path("anthropic",false),request("anthropic",false),"x-api-key",TOKEN).status);
            assertEquals(400,send(relay,"anthropic",false,request("anthropic",false).put("tools",new JSONArray().put(new JSONObject().put("type","computer_20250124").put("name","computer")))).status);assertEquals(1,rig.posts.get());
            assertFalse(relay.routeCurrent()==false);assertEquals(404,send(relay,"/phone/click",new JSONObject(),"Authorization","Bearer "+TOKEN).status);
        }
    }
    @Test public void outputCeilingAndStatefulBackgroundFieldsDoNotBypassHostBudget()throws Exception{
        for(String protocol:PROTOCOLS)try(Rig rig=new Rig(protocol);LoopbackRelay relay=rig.relay()){
            JSONObject input=request(protocol,false);if(protocol.equals("gemini"))input.put("generationConfig",new JSONObject().put("maxOutputTokens",513));else input.put(protocol.equals("anthropic")?"max_tokens":"max_output_tokens",513);assertEquals(400,send(relay,protocol,false,input).status);assertEquals(0,rig.posts.get());
        }
        try(Rig rig=new Rig("openai_responses");LoopbackRelay relay=rig.relay()){assertEquals(400,send(relay,"openai_responses",false,request("openai_responses",false).put("background",true)).status);assertEquals(400,send(relay,"openai_responses",false,request("openai_responses",false).put("store",true)).status);assertEquals(0,rig.posts.get());}
    }
    @Test public void lateNativeErrorAndEofAreVisibleWithoutCompleteTerminalOrReplay()throws Exception{
        for(String protocol:PROTOCOLS)try(Rig rig=new Rig(protocol);LoopbackRelay relay=rig.relay()){
            String prefix=protocol.equals("anthropic")?anthropicStart()+frame("content_block_start",new JSONObject().put("index",0).put("content_block",new JSONObject().put("type","text").put("text","PARTIAL"))):protocol.equals("openai_responses")?frame("response.created",new JSONObject().put("response",new JSONObject().put("id","response-one").put("status","in_progress"))):"data: "+new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","PARTIAL"))))))+"\n\n";
            rig.replyOverride=prefix;Reply reply=send(relay,protocol,true,request(protocol,true));assertEquals(200,reply.status);assertTrue(reply.body.contains("error"));assertFalse(reply.body.contains("response.completed"));assertFalse(reply.body.contains("message_stop"));assertFalse(reply.body.contains("[DONE]"));assertEquals(1,rig.posts.get());
        }
    }
    @Test public void splitCredentialAcrossAnthropicDeltasIsRedactedBeforeClientReceivesIt()throws Exception{
        try(Rig rig=new Rig("anthropic");LoopbackRelay relay=rig.relay()){
            rig.replyOverride=anthropicStart()+frame("content_block_start",new JSONObject().put("index",0).put("content_block",new JSONObject().put("type","text").put("text","")))+frame("content_block_delta",new JSONObject().put("index",0).put("delta",new JSONObject().put("type","text_delta").put("text","prefix "+KEY.substring(0,10))))+frame("content_block_delta",new JSONObject().put("index",0).put("delta",new JSONObject().put("type","text_delta").put("text",KEY.substring(10)+" suffix")))+anthropicEnd();
            Reply reply=send(relay,"anthropic",true,request("anthropic",true));StringBuilder actual=new StringBuilder();for(String line:reply.body.split("\\n"))if(line.startsWith("data: ")){JSONObject frame=new JSONObject(line.substring(6));if(frame.optString("type").equals("content_block_delta"))actual.append(frame.getJSONObject("delta").getString("text"));}
            assertEquals(200,reply.status);assertTrue(actual.toString().contains("prefix "));assertTrue(actual.toString().contains(" suffix"));assertFalse(actual.toString().contains(KEY));assertTrue(reply.body.contains("message_stop"));assertEquals(1,rig.posts.get());
        }
    }
    @Test public void sameRouteAfterSelectionAtoBtoAStillRejectsOldReplyAndRequiresExplicitRestart()throws Exception{
        try(Rig rig=new Rig("openai_responses");LoopbackRelay relay=rig.relay()){
            rig.onRequest=()->rig.selection.addAndGet(2);Reply first=send(relay,"openai_responses",false,request("openai_responses",false));assertEquals(503,first.status);assertFalse(first.body.contains("ACTUAL-NATIVE-ANSWER"));assertFalse(relay.routeCurrent());assertEquals(503,send(relay,"openai_responses",false,request("openai_responses",false)).status);assertEquals(1,rig.posts.get());
        }
    }
    @Test public void nativeVisibleTextFlagsNeverTreatThinkingOrFunctionArgumentsAsAnswerText()throws Exception{
        assertTrue(NativeRelayStream.hasVisibleText("openai_responses",new JSONObject().put("type","response.output_text.delta").put("delta","actual text")));
        assertFalse(NativeRelayStream.hasVisibleText("openai_responses",new JSONObject().put("type","response.function_call_arguments.delta").put("delta","{args}")));
        assertFalse(NativeRelayStream.hasVisibleText("anthropic",new JSONObject().put("type","content_block_delta").put("delta",new JSONObject().put("type","thinking_delta").put("thinking","private reasoning"))));
        assertTrue(NativeRelayStream.hasVisibleText("anthropic",new JSONObject().put("type","content_block_delta").put("delta",new JSONObject().put("type","text_delta").put("text","actual text"))));
        JSONObject thought=new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","reasoning").put("thought",true))))));assertFalse(NativeRelayStream.hasVisibleText("gemini",thought));thought.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").put(new JSONObject().put("text","actual text"));assertTrue(NativeRelayStream.hasVisibleText("gemini",thought));
    }
    @Test public void geminiMixedOrUnknownPartCannotHideUnbudgetedMediaBehindText()throws Exception{
        try(Rig rig=new Rig("gemini");LoopbackRelay relay=rig.relay()){
            JSONObject input=request("gemini",false);JSONObject part=input.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0);part.put("fileData",new JSONObject().put("fileUri","https://fixture.invalid/private-resource"));assertEquals(400,send(relay,"gemini",false,input).status);assertEquals(0,rig.posts.get());
            part.remove("fileData");part.put("inlineData",new JSONObject().put("mimeType","image/png").put("data","AAAA"));assertEquals(400,send(relay,"gemini",false,input).status);assertEquals(0,rig.posts.get());
        }
    }

}
