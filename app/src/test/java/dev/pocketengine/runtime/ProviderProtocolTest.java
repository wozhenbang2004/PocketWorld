package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual loopback HTTP, not a real provider/account or a claim of live model acceptance. */
public final class ProviderProtocolTest {
    private static final String KEY="fixture-provider-key-not-a-real-secret";
    private static final String IMAGE="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQZkAAAAASUVORK5CYII=";
    private ModelConfig config(String base,String provider){return new ModelConfig(base,KEY,"fixture-model",true,4,10,provider,512);}
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","主人指令"))
            .put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","实际观察"))
                    .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,"+IMAGE)))));}
    private JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("description","Inspect actual state").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));}
    private JSONObject completion(String text)throws Exception{return new JSONObject().put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text)))));}
    private JSONObject claudeText(String text)throws Exception{return new JSONObject().put("type","message").put("stop_reason","end_turn").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text)));}
    private JSONObject gemini(JSONArray parts)throws Exception{return new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("finishReason","STOP").put("content",new JSONObject().put("role","model").put("parts",parts))));}
    private JSONObject result(String id)throws Exception{return new JSONObject().put("role","tool").put("tool_call_id",id).put("content","{\"actual\":true}");}
    @Test public void responsesPreservesActualImageCallIdentityAndEncryptedReasoningAcrossRequests()throws Exception {
        try(HttpFixture server=new HttpFixture()){
            JSONArray raw=new JSONArray().put(new JSONObject().put("type","reasoning").put("id","rs-fixture").put("summary",new JSONArray()).put("encrypted_content","opaque-reasoning-fixture"))
                    .put(new JSONObject().put("type","function_call").put("id","item-fixture").put("call_id","wire-call").put("name","inspect").put("arguments","{}"));server.reply(new JSONObject().put("status","completed").put("output",raw));server.reply(completion("检查结束"));
            JSONArray history=messages();JSONObject first=ModelGateway.complete(config(server.base(),"openai_responses"),history,tools(),new RunControl());assertEquals("wire-call",first.getJSONArray("tool_calls").getJSONObject(0).getString("id"));assertFalse(first.getString("content").contains("reasoning"));history.put(first).put(result("wire-call"));assertEquals("检查结束",ModelGateway.complete(config(server.base(),"openai_responses"),history,tools(),new RunControl()).getString("content"));
            assertEquals("/v1/responses",server.requests.get(0).path);assertEquals("Bearer "+KEY,server.requests.get(0).headers.get("authorization"));JSONObject body=server.requests.get(0).body;assertFalse(body.getBoolean("store"));assertEquals(512,body.getInt("max_output_tokens"));assertEquals("data:image/png;base64,"+IMAGE,body.getJSONArray("input").getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("image_url"));assertEquals("function",body.getJSONArray("tools").getJSONObject(0).getString("type"));assertFalse(body.getJSONArray("tools").getJSONObject(0).has("function"));
            JSONArray second=server.requests.get(1).body.getJSONArray("input");assertEquals("opaque-reasoning-fixture",second.getJSONObject(2).getString("encrypted_content"));assertEquals("item-fixture",second.getJSONObject(3).getString("id"));assertEquals("wire-call",second.getJSONObject(4).getString("call_id"));assertEquals("function_call_output",second.getJSONObject(4).getString("type"));
        }
    }
    @Test public void anthropicUsesNativeImagesAndOrderedParallelToolResultsWithOpaqueThinking()throws Exception {
        try(HttpFixture server=new HttpFixture()){
            JSONArray content=new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","not-user-visible-fixture").put("signature","opaque-signature"));for(String id:new String[]{"first-call","second-call"})content.put(new JSONObject().put("type","tool_use").put("id",id).put("name","inspect").put("input",new JSONObject()));server.reply(new JSONObject().put("type","message").put("stop_reason","tool_use").put("content",content));server.reply(claudeText("已核对"));
            JSONArray history=messages();JSONObject assistant=ModelGateway.complete(config(server.base(),"anthropic"),history,tools(),new RunControl());history.put(assistant).put(result("first-call")).put(result("second-call")).put(new JSONObject().put("role","user").put("content","新的实际观察"));ModelGateway.complete(config(server.base(),"anthropic"),history,tools(),new RunControl());
            Request first=server.requests.get(0);assertEquals("/v1/messages",first.path);assertEquals("2026-10-21",first.headers.get("anthropic-version"));assertEquals("Bearer "+KEY,first.headers.get("authorization"));assertEquals(512,first.body.getInt("max_tokens"));assertFalse(first.body.has("temperature"));JSONObject source=first.body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getJSONObject("source");assertEquals("image/png",source.getString("media_type"));assertEquals(IMAGE,source.getString("data"));
            JSONArray conversation=server.requests.get(1).body.getJSONArray("messages");assertEquals(3,conversation.length());assertEquals("opaque-signature",conversation.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"));JSONArray results=conversation.getJSONObject(2).getJSONArray("content");assertEquals("first-call",results.getJSONObject(0).getString("tool_use_id"));assertEquals("second-call",results.getJSONObject(1).getString("tool_use_id"));assertEquals("新的实际观察",results.getJSONObject(2).getString("text"));assertFalse(assistant.getString("content").contains("not-user-visible"));
        }
    }
    @Test public void geminiKeepsThoughtSignatureAndMatchesNativeAndMissingCallIds()throws Exception {
        try(HttpFixture server=new HttpFixture()){
            JSONArray parts=new JSONArray().put(new JSONObject().put("thoughtSignature","opaque-gemini-signature").put("functionCall",new JSONObject().put("id","gemini-wire-id").put("name","inspect").put("args",new JSONObject())))
                    .put(new JSONObject().put("functionCall",new JSONObject().put("name","inspect").put("args",new JSONObject())));server.reply(gemini(parts));server.reply(gemini(new JSONArray().put(new JSONObject().put("text","真实答复"))));JSONArray history=messages();JSONObject assistant=ModelGateway.complete(config(server.base(),"gemini"),history,tools(),new RunControl());JSONArray calls=assistant.getJSONArray("tool_calls");String generated=calls.getJSONObject(1).getString("id");assertFalse(generated.isEmpty());history.put(assistant).put(result(calls.getJSONObject(0).getString("id"))).put(result(generated));ModelGateway.complete(config(server.base(),"gemini"),history,tools(),new RunControl());
            Request first=server.requests.get(0);assertEquals("/v1beta/models/fixture-model:generateContent",first.path);assertEquals(KEY,first.headers.get("x-goog-api-key"));assertFalse(first.path.contains(KEY));assertFalse(first.headers.containsKey("authorization"));assertEquals(IMAGE,first.body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("data"));assertTrue(first.body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).has("parametersJsonSchema"));
            JSONArray second=server.requests.get(1).body.getJSONArray("contents");assertEquals("opaque-gemini-signature",second.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"));JSONArray outputs=second.getJSONObject(2).getJSONArray("parts");assertEquals("gemini-wire-id",outputs.getJSONObject(0).getJSONObject("functionResponse").getString("id"));assertFalse(outputs.getJSONObject(1).getJSONObject("functionResponse").has("id"));assertEquals("inspect",outputs.getJSONObject(1).getJSONObject("functionResponse").getString("name"));
        }
    }
    @Test public void nativeProvidersRejectTruncatedRepliesAndDoNotRetryRequests()throws Exception {
        String[] providers={"openai_responses","anthropic","gemini","openai_responses","anthropic"};JSONObject[] replies={new JSONObject().put("status","incomplete").put("output",new JSONArray()),new JSONObject().put("stop_reason","max_tokens").put("content",new JSONArray()),new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("finishReason","MAX_TOKENS"))),new JSONObject().put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","function_call").put("call_id","partial-call").put("name","inspect").put("arguments","{}").put("status","in_progress"))),new JSONObject().put("stop_reason","future_unknown_stop").put("content",new JSONArray())};
        for(int i=0;i<providers.length;i++)try(HttpFixture server=new HttpFixture()){server.reply(replies[i]);try{ModelGateway.complete(config(server.base(),providers[i]),messages(),tools(),new RunControl());fail("Truncated provider reply accepted");}catch(ModelGateway.GatewayException expected){}assertEquals(1,server.requests.size());}
    }
    @Test public void cancellationDisconnectsEachNativeProviderWithoutAnotherRequest()throws Exception {
        for(String provider:new String[]{"openai_responses","anthropic","gemini"})try(HttpFixture server=new HttpFixture()){
            server.replies.add(new Reply(200,"{}",5000));RunControl control=new RunControl();Future<JSONObject> pending=server.threads.submit(()->ModelGateway.complete(config(server.base(),provider),messages(),tools(),control));assertTrue(server.received.await(2,TimeUnit.SECONDS));control.cancel();try{pending.get(2,TimeUnit.SECONDS);fail("Cancelled request returned a model decision");}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof InterruptedException);}assertEquals(1,server.requests.size());
        }
    }
    @Test public void errorBodiesCannotLeakProviderKeys()throws Exception {
        for(String provider:new String[]{"openai_responses","anthropic","gemini"})try(HttpFixture server=new HttpFixture()){
            server.replies.add(new Reply(401,"{\"error\":\""+KEY+"\"}",0));try{ModelGateway.complete(config(server.base(),provider),messages(),null,new RunControl());fail("Authentication failure accepted");}catch(ModelGateway.GatewayException expected){assertFalse(expected.getMessage().contains(KEY));assertTrue(expected.getMessage().contains("认证"));}assertEquals(1,server.requests.size());
        }
    }
    @Test public void nativeProviderRedirectsNeverReceiveCredentialsAtTheirDestination()throws Exception {
        for(String provider:new String[]{"openai_responses","anthropic","gemini"})try(HttpFixture server=new HttpFixture();ServerSocket destination=new ServerSocket(0)){
            destination.setSoTimeout(100);Reply redirect=new Reply(302,"{}",0);redirect.location="http://127.0.0.1:"+destination.getLocalPort()+"/must-not-receive-key";server.replies.add(redirect);
            try{ModelGateway.complete(config(server.base(),provider),messages(),null,new RunControl());fail("Redirect accepted");}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("重定向"));}
            try(Socket ignored=destination.accept()){fail("Provider credentials were sent to the redirect destination");}catch(java.net.SocketTimeoutException expected){}
        }
    }
    @Test public void providerAndTokenBudgetSurviveModelAndHostBudgetChanges()throws Exception {
        ModelConfig old=new ModelConfig("https://model.example/v1","","x",true,3,10);assertEquals("openai_chat",old.provider);ModelConfig selected=config("https://model.example/v1","anthropic").withStepCeiling(80).withModel("another-model");assertEquals("anthropic",selected.provider);assertEquals(512,selected.maxOutputTokens);assertEquals(80,selected.maxSteps);assertEquals("anthropic",selected.publicJson().getString("provider"));assertFalse(selected.publicJson().has("apiKey"));
        try{ModelConfig.fromJson(new JSONObject().put("provider","invented-provider"));fail("Unknown provider silently selected Chat");}catch(IllegalArgumentException expected){}
        assertEquals("/v1/responses",ModelGateway.endpoint(config("https://model.example/v1/responses/","openai_responses")).getPath());try{ModelGateway.endpoint(config("https://model.example/v1/messages","openai_responses"));fail("Mismatched complete endpoint accepted");}catch(ModelGateway.GatewayException expected){}
    }
    private static final class Request{String path;JSONObject body;Map<String,String> headers;Request(String path,JSONObject body,Map<String,String> headers){this.path=path;this.body=body;this.headers=headers;}}
    private static final class Reply{int status;String body,location;long delay;Reply(int status,String body,long delay){this.status=status;this.body=body;this.delay=delay;}}
    private static final class HttpFixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0);final ExecutorService threads=Executors.newCachedThreadPool();final BlockingQueue<Reply> replies=new LinkedBlockingQueue<>();final List<Request> requests=Collections.synchronizedList(new ArrayList<>());final CountDownLatch received=new CountDownLatch(1);
        HttpFixture()throws Exception{threads.submit(()->{while(!server.isClosed())try{Socket socket=server.accept();threads.submit(()->serve(socket));}catch(Exception stopped){return;}});}
        String base(){return "http://127.0.0.1:"+server.getLocalPort();}
        void reply(JSONObject response){replies.add(new Reply(200,response.toString(),0));}
        void serve(Socket socket){try(Socket client=socket){client.setSoTimeout(4000);InputStream input=client.getInputStream();String start=line(input),header;Map<String,String> headers=new HashMap<>();while(!(header=line(input)).isEmpty()){int split=header.indexOf(':');headers.put(header.substring(0,split).toLowerCase(java.util.Locale.ROOT),header.substring(split+1).trim());}int length=Integer.parseInt(headers.getOrDefault("content-length","0"));byte[] data=new byte[length];int offset=0,count;while(offset<length&&(count=input.read(data,offset,length-offset))>0)offset+=count;requests.add(new Request(start.split(" ")[1],new JSONObject(new String(data,StandardCharsets.UTF_8)),headers));received.countDown();Reply response=replies.poll(3,TimeUnit.SECONDS);if(response==null)return;if(response.delay>0)Thread.sleep(response.delay);byte[] bytes=response.body.getBytes(StandardCharsets.UTF_8);client.getOutputStream().write(("HTTP/1.1 "+response.status+" Result\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n"+(response.location==null?"":"Location: "+response.location+"\r\n")+"\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(bytes);client.getOutputStream().flush();}catch(Exception ignored){}}
        private static String line(InputStream input)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();int previous=-1,next;while((next=input.read())>=0){if(previous=='\r'&&next=='\n'){byte[] bytes=out.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}out.write(next);previous=next;if(out.size()>16384)throw new Exception("Header too large");}throw new Exception("Unexpected EOF");}
        public void close()throws Exception{server.close();threads.shutdownNow();threads.awaitTermination(2,TimeUnit.SECONDS);}
    }
}
