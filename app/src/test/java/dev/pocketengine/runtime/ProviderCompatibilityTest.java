package dev.pocketengine.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelRequestPolicy;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import dev.pocketengine.runtime.providers.ProviderCompatProfile;
import dev.pocketengine.runtime.providers.ProviderCompatibility;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real native codecs/Gateway and loopback HTTP, without claiming live vendor cache hits or Android UI. */
public final class ProviderCompatibilityTest {
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","stable owner instructions")).put(new JSONObject().put("role","user").put("content","inspect current state"));}
    private JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("description","read current source").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path")).put("additionalProperties",false))));}
    private ModelConfig configured(String url,String protocol,JSONObject options)throws Exception{return new ModelConfig(url,"fixture-secret","fixture-model",false,4,10,protocol,512).withProviderCompatibility(ProviderCompatProfile.reviewed(options,protocol,"fixture-model",null));}
    private JSONObject cache()throws Exception{return new JSONObject().put("cacheMode","EPHEMERAL").put("cacheSupport","SUPPORTED");}
    private InferenceCapture.Target receipts(List<InferenceReceipt> rows){return new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){rows.add(r);}public void finished(InferenceReceipt r){rows.add(r);}});}

    @Test public void exactWireIsReproducibleAndOriginalHistorySchemaAndOpaqueSignaturesStayIntact()throws Exception {
        ModelConfig config=configured("https://fixture.invalid/v1","anthropic",cache());JSONArray history=messages(),definitions=tools();
        history.put(new JSONObject().put("role","assistant").put("content","").put("provider_data",new JSONObject().put("provider","anthropic").put("items",new JSONArray().put(new JSONObject().put("type","thinking").put("thinking","opaque").put("signature","unchanged-signature")))));
        String original=history.toString(),schema=definitions.toString();JSONObject wire=ProviderAdapters.selected(config.provider).request(config,history,definitions);String ordinary=wire.toString();
        JSONObject a=ModelRequestPolicy.apply(config,history,definitions,wire),b=ModelRequestPolicy.apply(config,history,definitions,wire);
        assertEquals(a.toString(),b.toString());assertEquals(original,history.toString());assertEquals(schema,definitions.toString());assertEquals(ordinary,wire.toString());
        assertEquals("unchanged-signature",a.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"));
        assertEquals("path",a.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema").getJSONArray("required").getString(0));
        JSONObject diagnostic=ProviderCompatibility.diagnostics(config,a);assertTrue(diagnostic.getBoolean("hintPrepared"));assertFalse(diagnostic.getBoolean("dispatchAttempted"));assertFalse(diagnostic.has("hintSent"));assertFalse(diagnostic.getBoolean("cacheHitKnown"));
        history.getJSONObject(0).put("content","new reviewed source");JSONObject changed=ModelRequestPolicy.apply(config,history,definitions,ProviderAdapters.selected(config.provider).request(config,history,definitions));
        assertNotEquals(diagnostic.getString("prefixHash"),ProviderCompatibility.diagnostics(config,changed).getString("prefixHash"));
    }
    @Test public void actualStreamingGatewayUsesSelectedTokenFieldAndStreamUsageWithoutEditingTools()throws Exception {
        try(Server server=new Server()){
            ModelConfig config=configured(server.base(),"openai_chat",new JSONObject().put("tokenField","MAX_COMPLETION_TOKENS").put("streamUsage","OMIT").put("toolWireShape","ANTHROPIC_INPUT_SCHEMA"));
            JSONArray definitions=tools();String original=definitions.toString();List<InferenceReceipt> rows=new ArrayList<>();
            assertEquals("OK",ModelGateway.completeStreaming(config,messages(),definitions,new RunControl(),event->{},receipts(rows)).getString("content"));
            assertEquals(1,server.calls.get());assertTrue(server.request.getBoolean("stream"));assertEquals(512,server.request.getInt("max_completion_tokens"));assertFalse(server.request.has("max_tokens"));assertFalse(server.request.has("stream_options"));
            assertEquals("path",server.request.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema").getJSONArray("required").getString(0));assertEquals(original,definitions.toString());assertEquals(2,rows.size());
        }
    }
    @Test public void preparedCacheHintAndActualProviderUsageAreSeparateNativeReceiptFacts()throws Exception {
        try(Server server=new Server()){
            server.protocol="anthropic";server.usage=true;ModelConfig config=configured(server.base(),"anthropic",cache());List<InferenceReceipt> rows=new ArrayList<>();
            ModelGateway.complete(config,messages(),tools(),new RunControl(),receipts(rows));assertEquals(1,server.calls.get());assertEquals("ephemeral",server.request.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").getString("type"));
            JSONObject finalReceipt=rows.get(1).toJson(),projection=finalReceipt.getJSONObject("route").getJSONObject("providerProjection");
            assertTrue(projection.getBoolean("hintPrepared"));assertTrue(projection.getBoolean("dispatchAttempted"));assertEquals("unknown",projection.getString("hintDelivery"));assertFalse(projection.has("hintSent"));assertFalse(projection.getBoolean("cacheHitKnown"));
            assertEquals(5,finalReceipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("CACHE_READ").getLong("value"));assertEquals(2,finalReceipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("CACHE_WRITE").getLong("value"));
            assertFalse(finalReceipt.toString().contains("fixture-secret"));assertFalse(finalReceipt.toString().contains("stable owner instructions"));
        }
    }
    @Test public void unknownCacheSupportSendsNoHintAndMissingUsageNeverBecomesZeroOrAHit()throws Exception {
        try(Server server=new Server()){
            server.protocol="anthropic";List<InferenceReceipt> rows=new ArrayList<>();ModelConfig config=configured(server.base(),"anthropic",new JSONObject().put("cacheMode","EPHEMERAL"));
            ModelGateway.complete(config,messages(),tools(),new RunControl(),receipts(rows));assertEquals(1,server.calls.get());assertFalse(server.request.toString().contains("cache_control"));
            JSONObject last=rows.get(1).toJson();assertFalse(last.getJSONObject("route").getJSONObject("providerProjection").getBoolean("hintPrepared"));assertFalse(last.getJSONObject("usage").getJSONObject("counters").has("CACHE_READ"));
        }
    }
    @Test public void unsupportedSchemasAndLocalLimitsRejectBeforeAnyHttpOrPhysicalReceipt()throws Exception {
        try(Server server=new Server()){
            JSONArray bad=tools();bad.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").put("oneOf",new JSONArray().put(new JSONObject().put("type","object")));List<InferenceReceipt> rows=new ArrayList<>();
            ModelConfig config=configured(server.base(),"openai_chat",new JSONObject().put("schemaSubset","CORE_JSON"));
            assertThrows(Exception.class,()->ModelGateway.complete(config,messages(),bad,new RunControl(),receipts(rows)));assertEquals(0,server.calls.get());assertTrue(rows.isEmpty());assertTrue(bad.toString().contains("oneOf"));
            ModelConfig small=configured(server.base(),"openai_chat",new JSONObject().put("maxRequestBytes",256));assertThrows(Exception.class,()->ModelGateway.complete(small,messages(),tools(),new RunControl(),receipts(rows)));assertEquals(0,server.calls.get());assertTrue(rows.isEmpty());
        }
    }
    @Test public void cancellingAtOriginalReceiptAdmissionNeverClaimsHintDeliveryAndMakesNoHttpRequest()throws Exception {
        try(Server server=new Server()){
            ModelConfig config=configured(server.base(),"anthropic",cache());RunControl control=new RunControl();List<InferenceReceipt> rows=new ArrayList<>();
            InferenceCapture.Target target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){rows.add(r);control.cancel();}public void finished(InferenceReceipt r){rows.add(r);}});
            assertThrows(InterruptedException.class,()->ModelGateway.complete(config,messages(),tools(),control,target));assertEquals(0,server.calls.get());assertEquals(2,rows.size());assertEquals(InferenceReceipt.Outcome.CANCELLED,rows.get(1).outcome);
            JSONObject projection=rows.get(1).toJson().getJSONObject("route").getJSONObject("providerProjection");assertTrue(projection.getBoolean("hintPrepared"));assertEquals("unknown",projection.getString("hintDelivery"));assertFalse(projection.has("hintSent"));
        }
    }
    @Test public void rejectedProviderResponseIsSingleDispatchAndNeverRetriesWithCachingDisabled()throws Exception {
        try(Server server=new Server()){
            server.protocol="anthropic";server.status=400;ModelConfig config=configured(server.base(),"anthropic",cache());
            assertThrows(Exception.class,()->ModelGateway.complete(config,messages(),tools(),new RunControl()));assertEquals(1,server.calls.get());assertTrue(server.request.toString().contains("cache_control"));
        }
    }
    @Test public void profileVersionAndCopiesRemainNativeAndChangingItChangesOriginalConfigurationDigest()throws Exception {
        ModelConfig config=configured("https://fixture.invalid","openai_chat",new JSONObject().put("tokenField","MAX_TOKENS"));ProviderCompatProfile old=config.compatibility;
        assertTrue(old.matches(ProviderCompatProfile.fromJson(old.toJson())));assertSame(old,config.withStepCeiling(20).withCleartextConsent("").withOwnerImageInput(false).withRequestOutputLimit(128).withPurposePlans(java.util.Map.of()).compatibility);
        assertNull(config.withModel("another-model").compatibility);assertThrows(IllegalArgumentException.class,()->ModelConfig.fromJson(new JSONObject().put("compatibility",old.toJson())));
        ProviderCompatProfile next=ProviderCompatProfile.reviewed(new JSONObject().put("tokenField","MAX_COMPLETION_TOKENS"),config.provider,config.model,old);assertEquals(old.id,next.id);assertEquals(old.revision+1,next.revision);
        assertNotEquals(ModelRoutePlan.configurationDigest(config),ModelRoutePlan.configurationDigest(config.withProviderCompatibility(next)));
        assertThrows(Exception.class,()->ProviderCompatProfile.reviewed(new JSONObject().put("apiMode","anthropic"),config.provider,config.model,old));
        assertThrows(Exception.class,()->ProviderCompatProfile.reviewed(new JSONObject().put("cacheMode","EPHEMERAL"),config.provider,config.model,old));
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final AtomicInteger calls=new AtomicInteger();volatile JSONObject request;String protocol="openai_chat";int status=200;boolean usage;
        Server()throws Exception{http.createContext("/",exchange->{try{calls.incrementAndGet();request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String response,type="application/json";
            if(status!=200)response="{\"error\":{\"message\":\"cache policy rejected\"}}";
            else if(protocol.equals("anthropic")){JSONObject value=new JSONObject().put("type","message").put("role","assistant").put("stop_reason","end_turn").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","OK")));if(usage)value.put("usage",new JSONObject().put("input_tokens",3).put("output_tokens",1).put("cache_read_input_tokens",5).put("cache_creation_input_tokens",2));response=value.toString();}
            else if(request.optBoolean("stream")){type="text/event-stream";response="data: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";}
            else response="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception ignored){/* deliberate cancellation may close transport */}finally{exchange.close();}});http.start();}
        String base(){return "http://127.0.0.1:"+http.getAddress().getPort()+"/v1";}
        public void close(){http.stop(0);}
    }
}
