package dev.pocketengine.features.proxy;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.models.ModelCatalog;
import dev.pocketengine.runtime.models.ModelFacts;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.models.ReasoningOptions;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.runtime.models.ModelFacts.*;

/** Original authenticated loopback server, actual HTTP and catalog source. No provider/device/billing claim. */
public final class ProxyCatalogWireTest {
    private static final String TOKEN="catalog-local-token-123456789";
    private static final class Rig implements AutoCloseable {
        final HttpServer http;final AtomicInteger posts=new AtomicInteger();final ModelCatalog catalog;
        final ModelBinding binding;final ModelConfig selected;final AtomicReference<ModelConfig> current;
        final AtomicReference<Throwable> handlerFailure=new AtomicReference<>();
        volatile JSONObject received;volatile Runnable onRequest=()->{};volatile String replyOverride;
        Rig()throws Exception{
            http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{try{
                posts.incrementAndGet();received=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));onRequest.run();
                boolean stream=received.optBoolean("stream");String reply=replyOverride!=null?replyOverride:stream
                        ?"data: {\"id\":\"proxy-reply\",\"object\":\"chat.completion.chunk\",\"model\":\"configured-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"actual proxy delta\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        :"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"actual proxy answer\"}}]}";
                byte[] result=reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
            }catch(Exception|AssertionError failure){
                handlerFailure.compareAndSet(null,failure);
                try{exchange.sendResponseHeaders(500,-1);}catch(java.io.IOException ignored){}
            }finally{exchange.close();}});http.start();
            String base="http://127.0.0.1:"+http.getAddress().getPort()+"/v1";
            binding=new ModelBinding("primary","fixture",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,URI.create(base+"/models"),"credential",1,"installation",1,false);
            catalog=new ModelCatalog(Files.createTempDirectory("proxy-catalog-").resolve("catalog.json"),Clock.systemUTC());catalog.bind(binding);
            selected=new ModelConfig(base,"fixture-upstream-key","configured-model",false,4,10,"openai_chat",512).withNativeCatalog(binding,ReasoningOptions.DEFAULT);
            current=new AtomicReference<>(selected);ModelRequestPolicy.installHostSource(config->catalog.get(new ModelCatalog.ModelKey(config.capabilityBinding,config.model),3600000));
        }
        void owner(Map<Field,Object> values)throws Exception{
            var key=new ModelCatalog.ModelKey(binding,selected.model);Map<Field,OwnerValue> changes=new EnumMap<>(Field.class);values.forEach((field,value)->changes.put(field,OwnerValue.known(value)));
            catalog.updateOwner(key,catalog.get(key,3600000).ownerRevision(),changes,Set.of());
        }
        void output()throws Exception{owner(Map.of(Field.CHAT_OUTPUT_PARAMETER,ChatOutputParameter.MAX_COMPLETION_TOKENS));}
        LoopbackRelay relay()throws Exception{return new LoopbackRelay(0,TOKEN,current::get,null,selected);}
        public void close(){http.stop(0);Throwable failure=handlerFailure.get();if(failure!=null)throw new AssertionError("Fixture HTTP handler failed",failure);}
    }
    private static JSONObject input()throws Exception{return new JSONObject().put("model","configured-model").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","client input")));}
    private static int send(LoopbackRelay relay,JSONObject body)throws Exception{
        return sendReply(relay,body).status;
    }
    private record Reply(int status,String body){}
    private static Reply sendReply(LoopbackRelay relay,JSONObject body)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+relay.port()+"/v1/chat/completions").openConnection();
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setConnectTimeout(2000);connection.setReadTimeout(3000);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer "+TOKEN);
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
        try{try(var output=connection.getOutputStream()){output.write(bytes);}int status=connection.getResponseCode();var input=status<400?connection.getInputStream():connection.getErrorStream();String result="";if(input!=null)try(input){result=new String(input.readAllBytes(),StandardCharsets.UTF_8);}return new Reply(status,result);}finally{connection.disconnect();}
    }
    @Test public void exactCatalogOutputFieldAndClientOneTokenLimitReachTheRealWire()throws Exception{
        try(Rig rig=new Rig()){rig.output();rig.owner(Map.of(Field.CONTEXT_WINDOW,new TokenWindow(4096,WindowKind.INPUT_AND_OUTPUT)));
            try(LoopbackRelay relay=rig.relay()){
                assertEquals(200,send(relay,input().put("max_tokens",1)));assertEquals(1,rig.posts.get());
                assertEquals(1,rig.received.getInt("max_completion_tokens"));assertFalse(rig.received.has("max_tokens"));assertEquals(512,rig.current.get().maxOutputTokens);
                assertEquals("configured-model",rig.received.getString("model"));
            }
        }
    }
    @Test public void unknownChatFieldAndIncreasedBudgetAreRejectedBeforeHttp()throws Exception{
        try(Rig rig=new Rig();LoopbackRelay relay=rig.relay()){
            assertEquals(400,send(relay,input()));assertEquals(0,rig.posts.get());rig.output();
            assertEquals(400,send(relay,input().put("max_completion_tokens",513)));assertEquals(400,send(relay,input().put("max_tokens",2).put("max_completion_tokens",2)));
            assertEquals(0,rig.posts.get());
        }
    }
    @Test public void clientReasoningRequiresKnownExactLevelsAndDoesNotSaveSettings()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            assertEquals(400,send(relay,input().put("reasoning_effort","high")));assertEquals(0,rig.posts.get());
            rig.owner(Map.of(Field.REASONING,true,Field.REASONING_LEVELS,java.util.List.of("low","high")));
            assertEquals(200,send(relay,input().put("reasoning_effort","high")));assertEquals("high",rig.received.getString("reasoning_effort"));assertEquals(ReasoningOptions.DEFAULT,rig.current.get().reasoning);
            assertEquals(400,send(relay,input().put("reasoning_effort","unsupported")));assertEquals(1,rig.posts.get());
        }}
    }
    @Test public void imagesAndFunctionToolsCannotBypassTheOriginalCapabilityPolicy()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            JSONObject image=input().put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,AA=="))))));
            assertEquals(400,send(relay,image));
            JSONObject tool=input().put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("parameters",new JSONObject().put("type","object")))));
            assertEquals(400,send(relay,tool));assertEquals(0,rig.posts.get());rig.owner(Map.of(Field.TOOL_CALLS,true,Field.IMAGE_INPUT,true));
            assertEquals(200,send(relay,image));assertEquals(200,send(relay,tool));assertEquals(2,rig.posts.get());assertEquals(1,rig.received.getJSONArray("tools").length());
        }}
    }
    @Test public void explicitDifferentModelCannotBeReplacedByConfiguredModel()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            assertEquals(400,send(relay,input().put("model","other-model")));assertEquals(0,rig.posts.get());
        }}
    }
    @Test public void observedCredentialChangePermanentlyFencesThisServiceEvenWhenRestored()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            assertEquals(200,send(relay,input()));
            rig.current.set(new ModelConfig(rig.selected.baseUrl,"different-credential",rig.selected.model,false,4,10,"openai_chat",512).withNativeCatalog(rig.binding,ReasoningOptions.DEFAULT));
            assertEquals(503,send(relay,input()));assertFalse(relay.routeCurrent());rig.current.set(rig.selected);
            assertEquals(503,send(relay,input()));assertEquals(1,rig.posts.get());
        }}
    }
    @Test public void changedRouteOrRevokedCapabilityCannotReturnTheOldCompletedJson()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            rig.onRequest=()->rig.current.set(rig.selected.withModel("replacement-model"));assertEquals(503,send(relay,input()));assertEquals(1,rig.posts.get());
        }}
        try(Rig rig=new Rig()){rig.output();rig.owner(Map.of(Field.TOOL_CALLS,true));try(LoopbackRelay relay=rig.relay()){
            rig.onRequest=()->{try{rig.owner(Map.of(Field.TOOL_CALLS,false));}catch(Exception failure){throw new RuntimeException(failure);}};
            JSONObject request=input().put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect"))));
            assertEquals(409,send(relay,request));assertEquals(1,rig.posts.get());
        }}
    }
    @Test public void catalogPolicyAlsoControlsTheActualStreamAndOneTokenWireLimit()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            Reply reply=sendReply(relay,input().put("stream",true).put("max_tokens",1));assertEquals(200,reply.status);
            assertTrue(reply.body.contains("actual proxy delta"));assertTrue(reply.body.contains("data: [DONE]"));
            assertEquals(1,rig.received.getInt("max_completion_tokens"));assertFalse(rig.received.has("max_tokens"));assertEquals(1,rig.posts.get());
        }}
    }
    @Test public void capabilityRevocationPreventsTheStreamFinishFrameAndDoneMarker()throws Exception{
        try(Rig rig=new Rig()){rig.output();rig.owner(Map.of(Field.TOOL_CALLS,true));try(LoopbackRelay relay=rig.relay()){
            rig.onRequest=()->{try{rig.owner(Map.of(Field.TOOL_CALLS,false));}catch(Exception failure){throw new RuntimeException(failure);}};
            Reply reply=sendReply(relay,input().put("stream",true).put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect")))));
            assertEquals(200,reply.status);assertTrue(reply.body.contains("error"));assertFalse(reply.body.contains("finish_reason"));assertFalse(reply.body.contains("[DONE]"));assertEquals(1,rig.posts.get());
        }}
    }
    @Test public void aNewCredentialRevisionCannotReuseTheOldBindingEvenWithTheSameKeyValue()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            ModelBinding next=new ModelBinding(rig.binding.connectionId(),rig.binding.providerId(),rig.binding.protocol(),rig.binding.format(),rig.binding.modelsEndpoint(),rig.binding.credentialId(),2,rig.binding.installationId(),rig.binding.installationRevision(),false);
            rig.current.set(rig.selected.withNativeCatalog(next,ReasoningOptions.DEFAULT));
            assertEquals(503,send(relay,input()));assertEquals(0,rig.posts.get());
        }}
    }
    private static JSONObject functionChoice(String name)throws Exception{
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name));
    }
    private static JSONArray functionTools()throws Exception{
        JSONArray definitions=new JSONArray();for(String name:new String[]{"inspect","lookup"})
            definitions.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name)
                    .put("parameters",new JSONObject().put("type","object"))));
        return definitions;
    }
    private static JSONObject allowed(String mode,JSONArray choices)throws Exception{
        return new JSONObject().put("type","allowed_tools").put("allowed_tools",new JSONObject().put("mode",mode).put("tools",choices));
    }
    @Test public void forcedToolMustBeAnExactDeclaredFunctionBeforeAnyUpstreamPost()throws Exception{
        try(Rig rig=new Rig()){rig.output();rig.owner(Map.of(Field.TOOL_CALLS,true));try(LoopbackRelay relay=rig.relay()){
            Object[] invalid={functionChoice("not_declared"),new JSONObject().put("type","web_search"),
                    new JSONObject().put("type","function").put("name","inspect"),functionChoice("inspect").put("namespace","other"),
                    new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("arguments","{}"))};
            for(Object choice:invalid)assertEquals(400,send(relay,input().put("tools",functionTools()).put("tool_choice",choice)));
            assertEquals(0,rig.posts.get());
        }}
    }
    @Test public void requiredAndMalformedChoicesCannotSendWithoutAValidToolSelection()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            Object[] invalid={"required","unsupported",true,7,JSONObject.NULL,new JSONArray()};
            for(Object choice:invalid)assertEquals(400,send(relay,input().put("tool_choice",choice)));
            assertEquals(0,rig.posts.get());
        }}
    }
    @Test public void allowedToolSubsetMustContainOnlyDistinctDeclaredFunctions()throws Exception{
        try(Rig rig=new Rig()){rig.output();rig.owner(Map.of(Field.TOOL_CALLS,true));try(LoopbackRelay relay=rig.relay()){
            JSONObject[] invalid={allowed("auto",new JSONArray().put(functionChoice("not_declared"))),
                    allowed("auto",new JSONArray().put(functionChoice("inspect")).put(functionChoice("inspect"))),
                    allowed("required",new JSONArray()),allowed("none",new JSONArray().put(functionChoice("inspect"))),
                    allowed("auto",new JSONArray().put(new JSONObject().put("type","custom").put("custom",new JSONObject().put("name","inspect"))))};
            for(JSONObject choice:invalid)assertEquals(400,send(relay,input().put("tools",functionTools()).put("tool_choice",choice)));
            assertEquals(0,rig.posts.get());
        }}
    }
    @Test public void validForcedRequiredAndAllowedChoicesKeepTheirNativeWireInJsonAndSse()throws Exception{
        try(Rig rig=new Rig()){rig.output();rig.owner(Map.of(Field.TOOL_CALLS,true));try(LoopbackRelay relay=rig.relay()){
            Object[] choices={functionChoice("inspect"),"required",allowed("auto",new JSONArray().put(functionChoice("inspect"))),
                    allowed("required",new JSONArray().put(functionChoice("inspect")))};
            for(boolean streaming:new boolean[]{false,true})for(Object choice:choices){
                JSONObject tool=new JSONObject().put("id","call-one").put("type","function").put("function",new JSONObject().put("name","inspect").put("arguments","{}"));
                if(streaming){tool.put("index",0);rig.replyOverride="data: "+new JSONObject().put("id","reply").put("object","chat.completion.chunk")
                        .put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject().put("tool_calls",new JSONArray().put(tool)))
                                .put("finish_reason","tool_calls")))+"\n\ndata: [DONE]\n\n";}
                else rig.replyOverride=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","tool_calls")
                        .put("message",new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(tool))))).toString();
                Reply reply=sendReply(relay,input().put("stream",streaming).put("tools",functionTools()).put("tool_choice",choice));
                assertEquals(200,reply.status);assertTrue(reply.body.contains("call-one"));assertTrue(reply.body.contains("inspect"));
                assertEquals(choice.toString(),rig.received.get("tool_choice").toString());assertEquals(2,rig.received.getJSONArray("tools").length());
                if(streaming)assertTrue(reply.body.contains("data: [DONE]"));
            }
            assertEquals(8,rig.posts.get());
        }}
    }
    @Test public void anExplicitChoiceDoesNotInventUnknownOrUnsupportedModelToolCapability()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            JSONObject request=input().put("tools",functionTools()).put("tool_choice",functionChoice("inspect"));
            assertEquals(400,send(relay,request));rig.owner(Map.of(Field.TOOL_CALLS,false));assertEquals(400,send(relay,request));
            assertEquals(0,rig.posts.get());rig.owner(Map.of(Field.TOOL_CALLS,true));assertEquals(200,send(relay,request));assertEquals(1,rig.posts.get());
        }}
    }
    @Test public void autoAndNoneWithoutToolDefinitionsPreserveUnknownCapability()throws Exception{
        try(Rig rig=new Rig()){rig.output();try(LoopbackRelay relay=rig.relay()){
            for(String mode:new String[]{"auto","none"}){assertEquals(200,send(relay,input().put("tool_choice",mode)));assertEquals(mode,rig.received.getString("tool_choice"));}
            assertFalse(rig.catalog.get(new ModelCatalog.ModelKey(rig.binding,rig.selected.model),3600000).facts().toolCalls().known);assertEquals(2,rig.posts.get());
        }}
    }
}
