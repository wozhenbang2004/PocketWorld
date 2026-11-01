package dev.pocketengine.runtime.models;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.runtime.models.ModelFacts.*;

/** Production Gateway + codecs + catalog, real loopback wire. No live provider or text-generation claim. */
public final class ModelRequestPolicyTest {
    private HttpServer server;
    private ExecutorService worker;
    private ModelCatalog catalog;
    private ModelBinding binding;
    private final AtomicInteger requests=new AtomicInteger();
    private volatile JSONObject received;
    private volatile String reply;
    private volatile String contentType="application/json";
    private volatile int status=200;
    private volatile Runnable onRequest=()->{};
    private volatile Throwable serverFailure;

    @Before public void open() throws Exception {
        catalog=new ModelCatalog(Files.createTempDirectory("model-policy-").resolve("catalog.json"),Clock.systemUTC());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        worker=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"catalog-test-http");t.setDaemon(true);return t;});server.setExecutor(worker);
        server.createContext("/",exchange->{
            try{
                requests.incrementAndGet();received=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));onRequest.run();
                byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",contentType);exchange.sendResponseHeaders(status,bytes.length);
                try(var output=exchange.getResponseBody()){output.write(bytes);}
            }catch(Exception failure){if(!exchange.getRequestURI().getPath().contains("cancel"))serverFailure=failure;}finally{exchange.close();}
        });server.start();
        reply=chatReply("OK").toString();
        ModelRequestPolicy.installHostSource(config->catalog.get(new ModelCatalog.ModelKey(config.capabilityBinding,config.model),3600000));
    }
    @After public void close() {server.stop(0);worker.shutdownNow();}
    private String base(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
    private ModelConfig route(String provider)throws Exception {
        binding=new ModelBinding("primary","fixture",ModelCatalogRoutes.protocol(provider),ModelCatalogRoutes.defaultFormat(provider),URI.create(base()+"/models"),"fixture-credential",1,"fixture-installation",1,false);
        catalog.bind(binding);
        return new ModelConfig(base(),"fixture-key","exact-model",false,4,10,provider,512).withNativeCatalog(binding,ReasoningOptions.DEFAULT);
    }
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","实际请求"));}
    private JSONArray image()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,AA==")))));}
    private JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("parameters",new JSONObject().put("type","object"))));}
    private void owner(Map<Field,Object> values)throws Exception {
        ModelCatalog.ModelKey key=new ModelCatalog.ModelKey(binding,"exact-model");long revision=catalog.get(key,3600000).ownerRevision();
        Map<Field,OwnerValue> set=new EnumMap<>(Field.class);values.forEach((f,v)->set.put(f,OwnerValue.known(v)));catalog.updateOwner(key,revision,set,Set.of());
    }
    private void chatField()throws Exception {owner(Map.of(Field.CHAT_OUTPUT_PARAMETER,ChatOutputParameter.MAX_COMPLETION_TOKENS));}
    private static JSONObject chatReply(String content)throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content",content))));}
    private static String sse(String content)throws Exception{return "data: "+new JSONObject().put("id","reply-one").put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject().put("content",content)).put("finish_reason",JSONObject.NULL)))+"\n\ndata: "+new JSONObject().put("id","reply-one").put("object","chat.completion.chunk").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject()).put("finish_reason","stop")))+"\n\ndata: [DONE]\n\n";}
    private void refused(ModelConfig config,JSONArray messages,JSONArray tools,String fragment)throws Exception {
        int before=requests.get();try{ModelGateway.complete(config,messages,tools,new RunControl());fail("Should reject before HTTP");}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(fragment));}assertEquals(before,requests.get());
    }

    @Test public void nativeChatOutputChoiceReachesBothBufferedAndStreamingWire()throws Exception {
        ModelConfig config=route("openai_chat");chatField();JSONArray original=messages();String unchanged=original.toString();
        assertEquals("OK",ModelGateway.complete(config,original,null,new RunControl()).getString("content"));
        assertEquals(512,received.getInt("max_completion_tokens"));assertFalse(received.has("max_tokens"));assertFalse(received.getBoolean("stream"));
        reply=sse("分片答复");contentType="text/event-stream";List<ModelGateway.StreamEvent> events=new ArrayList<>();
        assertEquals("分片答复",ModelGateway.completeStreaming(config,original,null,new RunControl(),events::add).getString("content"));
        assertTrue(received.getBoolean("stream"));assertEquals(512,received.getInt("max_completion_tokens"));assertFalse(received.has("max_tokens"));
        assertEquals(ModelGateway.StreamKind.COMPLETED,events.get(events.size()-1).kind);assertTrue(events.stream().anyMatch(e->e.kind==ModelGateway.StreamKind.TEXT_DELTA));assertEquals(unchanged,original.toString());assertEquals(2,requests.get());assertNull(serverFailure);
    }
    @Test public void standardModelsUnknownCannotInventAChatOutputField()throws Exception {
        ModelConfig config=route("openai_chat");refused(config,messages(),null,"字段未知");
        List<ModelGateway.StreamEvent> events=new ArrayList<>();try{ModelGateway.completeStreaming(config,messages(),null,new RunControl(),events::add);fail();}catch(ModelGateway.GatewayException expected){}
        assertEquals(0,requests.get());assertEquals(ModelGateway.StreamKind.FAILED,events.get(events.size()-1).kind);
    }
    @Test public void knownCapacityRejectsExcessWithoutClampingAndUnknownStillSendsTheOwnerLimit()throws Exception {
        ModelConfig config=route("openai_chat");chatField();owner(Map.of(Field.OUTPUT_TOKENS,256L));refused(config,messages(),null,"已知容量");
        ModelCatalog.ModelKey key=new ModelCatalog.ModelKey(binding,config.model);catalog.updateOwner(key,catalog.get(key,3600000).ownerRevision(),Map.of(Field.OUTPUT_TOKENS,OwnerValue.unknown()),Set.of());
        ModelGateway.complete(config,messages(),null,new RunControl());assertEquals(512,received.getInt("max_completion_tokens"));assertFalse(catalog.get(key,3600000).facts().outputTokens().known);
    }
    @Test public void unknownAndExplicitFalseToolsOrImagesNeverBecomeSupported()throws Exception {
        ModelConfig config=route("openai_chat");chatField();refused(config,messages(),tools(),"工具调用能力未知");refused(config,image(),null,"图像输入能力未知");
        owner(Map.of(Field.TOOL_CALLS,false,Field.IMAGE_INPUT,false));refused(config,messages(),tools(),"工具调用能力未知或不支持");refused(config,image(),null,"图像输入能力未知或不支持");
        JSONArray history=messages().put(new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call1").put("function",new JSONObject().put("name","inspect").put("arguments","{}")))))
                .put(new JSONObject().put("role","tool").put("tool_call_id","call1").put("content","真实历史收据"));
        refused(config,history,null,"历史工具结果不会被删除");assertEquals(0,requests.get());
    }
    @Test public void declaredImageAndToolsRemainActualWireContentAndOwnerImageReserveIsExplicit()throws Exception {
        ModelConfig config=route("openai_chat");chatField();owner(Map.of(Field.IMAGE_INPUT,true,Field.TOOL_CALLS,true,Field.CONTEXT_WINDOW,new TokenWindow(8192,WindowKind.INPUT_AND_OUTPUT)));
        refused(config,image(),tools(),"图片 token 预留未配置");config=config.withNativeCatalog(binding,ReasoningOptions.DEFAULT,1024);
        ModelGateway.complete(config,image(),tools(),new RunControl());
        assertEquals("data:image/png;base64,AA==",received.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("image_url").getString("url"));
        assertEquals("inspect",received.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"));assertEquals(1,requests.get());
    }
    @Test public void combinedAndInputOnlyWindowsUseDifferentOutputReservesWithoutDroppingHistory()throws Exception {
        ModelConfig config=route("openai_chat");chatField();owner(Map.of(Field.CONTEXT_WINDOW,new TokenWindow(700,WindowKind.INPUT_AND_OUTPUT)));
        JSONArray large=new JSONArray().put(new JSONObject().put("role","user").put("content","a".repeat(400)));String exact=large.toString();refused(config,large,null,"保守文字字节预算");
        owner(Map.of(Field.CONTEXT_WINDOW,new TokenWindow(700,WindowKind.INPUT_ONLY)));ModelGateway.complete(config,large,null,new RunControl());assertEquals(exact,received.getJSONArray("messages").toString());
    }
    @Test public void openAiEffortUsesItsSelectedNativeProtocolField()throws Exception {
        for(String protocol:new String[]{"openai_chat","openai_responses"}){
            ModelConfig config=route(protocol);if(protocol.equals("openai_chat"))chatField();owner(Map.of(Field.REASONING,true,Field.REASONING_LEVELS,List.of("high")));
            config=config.withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0));
            reply=protocol.equals("openai_chat")?chatReply("OK").toString():new JSONObject().put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","OK"))))).toString();
            ModelGateway.complete(config,messages(),null,new RunControl());
            if(protocol.equals("openai_chat")){assertEquals("high",received.getString("reasoning_effort"));assertFalse(received.has("reasoning"));}
            else{assertEquals("high",received.getJSONObject("reasoning").getString("effort"));assertEquals(512,received.getInt("max_output_tokens"));assertFalse(received.has("reasoning_effort"));}
        }
    }
    @Test public void anthropicAdaptiveAndManualBudgetAreExplicitDifferentChoices()throws Exception {
        ModelConfig config=route("anthropic");owner(Map.of(Field.REASONING,true,Field.REASONING_LEVELS,List.of("high")));
        reply=new JSONObject().put("stop_reason","end_turn").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","OK"))).toString();
        ModelGateway.complete(config.withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.ANTHROPIC_ADAPTIVE,"high",0)),messages(),null,new RunControl());
        assertEquals("adaptive",received.getJSONObject("thinking").getString("type"));assertEquals("high",received.getJSONObject("output_config").getString("effort"));assertFalse(received.getJSONObject("thinking").has("budget_tokens"));
        ModelConfig manual=new ModelConfig(base(),"fixture-key","exact-model",false,4,10,"anthropic",4096).withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.ANTHROPIC_BUDGET,"",2048));
        ModelGateway.complete(manual,messages(),null,new RunControl());assertEquals(2048,received.getJSONObject("thinking").getInt("budget_tokens"));assertEquals(4096,received.getInt("max_tokens"));assertFalse(received.has("output_config"));
    }
    @Test public void geminiLevelAndBudgetCannotBeSilentlyCombined()throws Exception {
        ModelConfig config=route("gemini");owner(Map.of(Field.REASONING,true,Field.REASONING_LEVELS,List.of("high")));
        reply=new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("finishReason","STOP").put("content",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text","OK")))))).toString();
        ModelGateway.complete(config.withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.GEMINI_LEVEL,"high",0)),messages(),null,new RunControl());
        JSONObject thinking=received.getJSONObject("generationConfig").getJSONObject("thinkingConfig");assertEquals("high",thinking.getString("thinkingLevel"));assertFalse(thinking.has("thinkingBudget"));
        ModelGateway.complete(config.withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.GEMINI_BUDGET,"",-1)),messages(),null,new RunControl());
        thinking=received.getJSONObject("generationConfig").getJSONObject("thinkingConfig");assertEquals(-1,thinking.getInt("thinkingBudget"));assertFalse(thinking.has("thinkingLevel"));assertEquals(512,received.getJSONObject("generationConfig").getInt("maxOutputTokens"));
        owner(Map.of(Field.REASONING_LEVELS,List.of("HIGH")));ModelGateway.complete(config.withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.GEMINI_LEVEL,"HIGH",0)),messages(),null,new RunControl());
        assertEquals("HIGH",received.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"));
    }
    @Test public void unsupportedReasoningLevelsAndOverBudgetNeverSendOrRaiseOutputCosts()throws Exception {
        ModelConfig config=route("openai_chat");chatField();owner(Map.of(Field.REASONING,true));refused(config.withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0)),messages(),null,"没有声明此推理级别");
        owner(Map.of(Field.REASONING_LEVELS,List.of("low")));refused(config.withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0)),messages(),null,"没有声明此推理级别");
        ModelConfig anthropic=route("anthropic");owner(Map.of(Field.REASONING,true));refused(anthropic.withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.ANTHROPIC_BUDGET,"",1024)),messages(),null,"输出上限不一致");
    }
    @Test public void credentialOrOwnerRevocationAfterTheResponseCannotYieldACompletedDecision()throws Exception {
        ModelConfig config=route("openai_chat");chatField();owner(Map.of(Field.TOOL_CALLS,true));AtomicBoolean revoked=new AtomicBoolean();
        ModelRequestPolicy.installHostSource(selected->revoked.get()?null:catalog.get(new ModelCatalog.ModelKey(binding,selected.model),3600000));
        onRequest=()->revoked.set(true);reply=sse("暂存文字");contentType="text/event-stream";List<ModelGateway.StreamEvent> events=new ArrayList<>();
        try{ModelGateway.completeStreaming(config,messages(),tools(),new RunControl(),events::add);fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("修订")||expected.getMessage().contains("版本"));}
        assertEquals(1,requests.get());assertFalse(events.stream().anyMatch(e->e.kind==ModelGateway.StreamKind.COMPLETED));assertEquals(ModelGateway.StreamKind.FAILED,events.get(events.size()-1).kind);
    }
    @Test public void cancellingTheOriginalControlDuringVisibleStreamReturnsNoDecision()throws Exception {
        ModelConfig config=route("openai_chat");chatField();reply=sse("取消前片段");contentType="text/event-stream";RunControl original=new RunControl();original.bindHostRun("task","run",3);List<ModelGateway.StreamEvent> events=new ArrayList<>();
        try{ModelGateway.completeStreaming(config,messages(),null,original,event->{events.add(event);if(event.kind==ModelGateway.StreamKind.TEXT_DELTA)original.cancel();});fail();}catch(InterruptedException expected){}
        assertTrue(original.matchesHostRun("task","run",3));assertEquals(ModelGateway.StreamKind.CANCELLED,events.get(events.size()-1).kind);assertEquals(1,requests.get());
    }
    @Test public void configCopiesKeepTheBindingAndNativeTuningButAChosenDifferentModelResetsTuning()throws Exception {
        ModelConfig config=route("openai_chat").withNativeCatalog(binding,new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,"high",0),1024);
        ModelConfig same=config.withStepCeiling(40).withCleartextConsent("").withOwnerImageInput(true).withModel("exact-model");assertEquals(binding,same.capabilityBinding);assertEquals(config.reasoning,same.reasoning);assertEquals(1024,same.imageTokenReserve);
        ModelConfig changed=same.withModel("explicit-other");assertEquals(binding,changed.capabilityBinding);assertEquals(ReasoningOptions.DEFAULT,changed.reasoning);assertEquals(0,changed.imageTokenReserve);assertFalse(changed.supportsImageInput);
        try{ModelConfig.fromJson(new JSONObject().put("reasoning",new JSONObject()));fail();}catch(IllegalArgumentException expected){}
        assertFalse(config.publicJson().toString().contains("fixture-key"));
    }
}
