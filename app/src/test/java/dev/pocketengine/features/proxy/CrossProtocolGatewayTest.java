package dev.pocketengine.features.proxy;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.providers.*;
import dev.pocketengine.runtime.usage.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Production authenticated ingress -> ModelGateway -> real local HTTP -> caller-owned function. */
public final class CrossProtocolGatewayTest {
    private static final String TOKEN="cross-protocol-client-token-123456",KEY="fixture-vendor-secret-123456";
    private record Reply(int status,String body){}
    private static final class Rig implements AutoCloseable {
        final String ingress,egress;final ModelConfig config;final HttpServer server;final ExecutorService pool=Executors.newCachedThreadPool();final LoopbackRelay relay;
        final AtomicInteger posts=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();final List<JSONObject> sent=new CopyOnWriteArrayList<>();
        final CountDownLatch firstDeltaSeen=new CountDownLatch(1);final AtomicBoolean producerReleased=new AtomicBoolean();volatile boolean broken,error,invalidArgs,duplicate,parallel,omitCacheUsage,invalidUsage,splitUnicode;volatile int status=200;
        Rig(String ingress,String egress)throws Exception {
            this.ingress=ingress;this.egress=egress;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);server.createContext("/",exchange->{try{
                int round=posts.incrementAndGet();JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));sent.add(body);
                assertEquals(egress.equals("anthropic")?"/v1/messages":"/v1/responses",exchange.getRequestURI().getPath());assertEquals("Bearer "+KEY,exchange.getRequestHeaders().getFirst("Authorization"));assertFalse(body.toString().contains(TOKEN));
                boolean stream=body.optBoolean("stream");exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");exchange.sendResponseHeaders(status,0);
                if(status!=200){exchange.getResponseBody().write("{\"error\":{\"code\":\"denied\"}}".getBytes(StandardCharsets.UTF_8));return;}
                if(stream){String[] chunks=splitUnicode?unicodeFrames(egress):parallel?parallelFrames(egress):frames(egress,round==1,broken,error,invalidArgs,duplicate,invalidUsage);exchange.getResponseBody().write(chunks[0].getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
                    if(round==1&&!broken&&!error&&!invalidArgs&&!duplicate){assertTrue("consumer must receive an actual argument delta before producer can continue",firstDeltaSeen.await(4,TimeUnit.SECONDS));producerReleased.set(true);}
                    exchange.getResponseBody().write(chunks[1].getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
                }else {JSONObject response=result(egress,round==1);if(omitCacheUsage){JSONObject usage=response.getJSONObject("usage");usage.remove("cache_read_input_tokens");usage.remove("cache_creation_input_tokens");usage.remove("input_tokens_details");}exchange.getResponseBody().write(response.toString().getBytes(StandardCharsets.UTF_8));}
            }catch(Throwable t){failure.set(t);}finally{exchange.close();}});server.start();
            config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1",KEY,"bound-model",false,4,10,egress,512);
            relay=new LoopbackRelay(0,TOKEN,()->config);
        }
        @Override public void close(){relay.close();server.stop(0);pool.shutdownNow();}
    }
    private static JSONObject schema()throws Exception{return new JSONObject().put("type","object").put("properties",new JSONObject().put("value",new JSONObject().put("type","string")));}
    private static JSONArray tools(String ingress)throws Exception{
        JSONObject f=new JSONObject().put("name","reverse").put("description","Reverse the provided text.");if(ingress.equals("anthropic"))return new JSONArray().put(f.put("input_schema",schema()));
        return new JSONArray().put(new JSONObject().put("type","function").put("function",f.put("parameters",schema())));
    }
    private static JSONArray internalTools()throws Exception{return tools("openai_chat");}
    private static JSONObject request(String ingress,boolean stream)throws Exception{return new JSONObject().put("model","bound-model").put("stream",stream).put("max_tokens",256).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","Reverse 你好."))).put("tools",tools(ingress));}
    private static String path(String ingress){return ingress.equals("anthropic")?"/v1/messages":"/v1/chat/completions";}
    private static Reply send(Rig rig,JSONObject input)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+rig.relay.port()+path(rig.ingress)).openConnection();c.setConnectTimeout(2000);c.setReadTimeout(7000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+TOKEN);c.setRequestProperty("Content-Type","application/json");byte[] bytes=input.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
        try{try(var out=c.getOutputStream()){out.write(bytes);}int status=c.getResponseCode();StringBuilder all=new StringBuilder();try(var in=new BufferedReader(new InputStreamReader(status<400?c.getInputStream():c.getErrorStream(),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null){all.append(line).append('\n');if(line.contains("partial_json")||line.contains("arguments")&&line.contains("value")||line.contains("\\ud83d"))rig.firstDeltaSeen.countDown();}}return new Reply(status,all.toString());}finally{c.disconnect();}
    }
    private static JSONObject assistant(String ingress,boolean stream,Reply reply)throws Exception{
        if(stream)return ApiTurnStream.read(new ByteArrayInputStream(reply.body.getBytes(StandardCharsets.UTF_8)),ingress,ProviderAdapters.selected(ingress),internalTools(),new RunControl(),delta->{});
        return ApiTurnStream.completeJson(ingress,ProviderAdapters.selected(ingress),reply.body,internalTools(),delta->{});
    }
    private static JSONObject followup(String ingress,boolean stream,JSONObject assistant,AtomicInteger executions)throws Exception {
        JSONObject req=request(ingress,stream);JSONArray messages=req.getJSONArray("messages"),calls=assistant.getJSONArray("tool_calls");assertEquals(1,calls.length());JSONObject call=calls.getJSONObject(0);assertEquals("stable_call",call.getString("id"));JSONObject fn=call.getJSONObject("function");assertEquals("reverse",fn.getString("name"));String text=new JSONObject(fn.getString("arguments")).getString("value");assertEquals("你好",text);
        // This actual controlled function runs in the caller, never in gateway or HTTP producer.
        executions.incrementAndGet();String reversed=new StringBuilder(text).reverse().toString();
        if(ingress.equals("anthropic")){messages.put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","tool_use").put("id",call.getString("id")).put("name",fn.getString("name")).put("input",new JSONObject(fn.getString("arguments"))))));messages.put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id",call.getString("id")).put("content",reversed))));}
        else{messages.put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",calls));messages.put(new JSONObject().put("role","tool").put("tool_call_id",call.getString("id")).put("content",reversed));}return req;
    }
    @Test public void bothNonDiagonalPairsCompleteRealFunctionRoundTripInJsonAndIncrementalSse()throws Exception {
        for(String ingress:List.of("anthropic","openai_chat"))for(boolean stream:List.of(false,true))try(Rig rig=new Rig(ingress,ingress.equals("anthropic")?"openai_responses":"anthropic")){
            AtomicInteger executed=new AtomicInteger();Reply first=send(rig,request(ingress,stream));assertEquals(first.body,200,first.status);JSONObject call=assistant(ingress,stream,first);if(stream)assertTrue("not a buffered pseudo-stream",rig.producerReleased.get());
            Reply last=send(rig,followup(ingress,stream,call,executed));assertEquals(last.body,200,last.status);assertEquals("Result: 好你",assistant(ingress,stream,last).getString("content"));assertEquals(1,executed.get());assertEquals(2,rig.posts.get());assertNull(rig.failure.get());
            JSONObject wire=rig.sent.get(1);if(rig.egress.equals("openai_responses")){assertFalse(rig.sent.get(0).getJSONArray("tools").getJSONObject(0).getBoolean("strict"));JSONArray input=wire.getJSONArray("input");assertEquals("function_call_output",input.getJSONObject(input.length()-1).getString("type"));assertEquals("stable_call",input.getJSONObject(input.length()-1).getString("call_id"));assertEquals("好你",input.getJSONObject(input.length()-1).getString("output"));}
            else{JSONArray rows=wire.getJSONArray("messages");JSONObject result=rows.getJSONObject(rows.length()-1).getJSONArray("content").getJSONObject(0);assertEquals("tool_result",result.getString("type"));assertEquals("stable_call",result.getString("tool_use_id"));assertEquals("好你",result.getString("content"));}
            assertTrue("usage reaches native client",last.body.contains(ingress.equals("anthropic")?"output_tokens":"completion_tokens"));
        }
    }
    @Test public void parallelCallIdsAndInterleavedPartialJsonStayIndependent()throws Exception {
        for(String ingress:List.of("anthropic","openai_chat"))try(Rig rig=new Rig(ingress,ingress.equals("anthropic")?"openai_responses":"anthropic")){
            rig.parallel=true;Reply reply=send(rig,request(ingress,true));assertEquals(reply.body,200,reply.status);JSONObject answer=assistant(ingress,true,reply);JSONArray calls=answer.getJSONArray("tool_calls");assertEquals(2,calls.length());
            for(int i=0;i<2;i++){JSONObject c=calls.getJSONObject(i);assertEquals("call_"+i,c.getString("id"));assertEquals(i==0?"A":"B",new JSONObject(c.getJSONObject("function").getString("arguments")).getString("value"));}
            assertTrue(rig.producerReleased.get());assertNull(rig.failure.get());assertEquals(1,rig.posts.get());
        }
    }
    @Test public void unsupportedFieldsStrictMediaOpaqueAndInstructionPositionsSendNothing()throws Exception {
        for(String ingress:List.of("anthropic","openai_chat"))try(Rig rig=new Rig(ingress,ingress.equals("anthropic")?"openai_responses":"anthropic")){
            JSONObject unknown=request(ingress,false).put("future_semantic",true);assertEquals(400,send(rig,unknown).status);
            JSONObject strict=request(ingress,false);JSONObject tool=strict.getJSONArray("tools").getJSONObject(0);(ingress.equals("anthropic")?tool:tool.getJSONObject("function")).put("strict",true);assertEquals(400,send(rig,strict).status);
            JSONObject opaque=request(ingress,false).put("previous_response_id","other-provider-response");assertEquals(400,send(rig,opaque).status);
            JSONObject media=request(ingress,false);media.getJSONArray("messages").getJSONObject(0).put("content",new JSONArray().put(new JSONObject().put("type","image").put("source",new JSONObject().put("type","base64").put("data","AAA="))));assertEquals(400,send(rig,media).status);
            if(ingress.equals("openai_chat")){JSONObject roles=request(ingress,false);roles.getJSONArray("messages").put(new JSONObject().put("role","developer").put("content","do not move me"));assertEquals(400,send(rig,roles).status);}
            assertEquals(0,rig.posts.get());
        }
    }
    @Test public void lateErrorEofIllegalArgumentsAndDuplicateCallIdentityNeverCompleteOrRetry()throws Exception {
        for(String ingress:List.of("anthropic","openai_chat"))for(int mode=0;mode<4;mode++)try(Rig rig=new Rig(ingress,ingress.equals("anthropic")?"openai_responses":"anthropic")){
            rig.broken=mode==0;rig.error=mode==1;rig.invalidArgs=mode==2;rig.duplicate=mode==3;Reply reply=send(rig,request(ingress,true));assertTrue(reply.body,reply.status==200||reply.status==502);assertFalse(reply.body,reply.body.contains(ingress.equals("anthropic")?"event: message_stop":"[DONE]"));assertEquals(1,rig.posts.get());try{assistant(ingress,true,reply);fail("invalid partial stream accepted");}catch(Exception expected){}
        }
    }
    @Test public void escapedUtf16PairAcrossDeltasSurvivesActualNamedAndUnnamedWire()throws Exception {
        for(String ingress:List.of("anthropic","openai_chat"))try(Rig rig=new Rig(ingress,ingress.equals("anthropic")?"openai_responses":"anthropic")){
            rig.splitUnicode=true;Reply reply=send(rig,request(ingress,true));assertEquals(reply.body,200,reply.status);assertTrue(rig.producerReleased.get());assertEquals("🙂",assistant(ingress,true,reply).getString("content"));assertTrue(reply.body,reply.body.contains("\\ud83d"));assertFalse(reply.body,reply.body.contains("�"));assertNull(rig.failure.get());
        }
    }
    @Test public void translatedTerminalMustPassUsageValidationBeforeSuccessMarker()throws Exception {
        try(Rig rig=new Rig("openai_chat","anthropic")){
            rig.invalidUsage=true;Reply reply=send(rig,request("openai_chat",true));assertEquals(reply.body,200,reply.status);assertTrue(rig.producerReleased.get());assertFalse(reply.body,reply.body.contains("[DONE]"));assertEquals(1,rig.posts.get());assertNull(rig.failure.get());
            try{assistant("openai_chat",true,reply);fail("unrepresentable terminal usage became a success");}catch(Exception expected){}
        }
    }
    @Test public void nativeChatCannotBypassClientFunctionAdmissionWithLegacyCatalog()throws Exception {
        try(Rig rig=new Rig("openai_chat","openai_chat")){
            JSONObject hosted=request("openai_chat",false);hosted.getJSONArray("tools").getJSONObject(0).put("type","web_search");assertEquals(400,send(rig,hosted).status);
            JSONObject forced=request("openai_chat",false).put("tool_choice",new JSONObject().put("type","function").put("function",new JSONObject().put("name","undeclared")));assertEquals(400,send(rig,forced).status);
            assertEquals(400,send(rig,request("openai_chat",false).put("tools",new JSONObject())).status);assertEquals(0,rig.posts.get());
        }
    }
    @Test public void absentCacheBreakdownRemainsUnknownRatherThanZeroInClientUsage()throws Exception {
        for(String ingress:List.of("anthropic","openai_chat"))try(Rig rig=new Rig(ingress,ingress.equals("anthropic")?"openai_responses":"anthropic")){
            rig.omitCacheUsage=true;Reply reply=send(rig,request(ingress,false));assertEquals(reply.body,200,reply.status);JSONObject usage=new JSONObject(reply.body).getJSONObject("usage");
            assertTrue(usage.has(ingress.equals("anthropic")?"input_tokens":"prompt_tokens"));assertTrue(usage.isNull(ingress.equals("anthropic")?"input_tokens":"prompt_tokens"));
            assertEquals(3,usage.getInt(ingress.equals("anthropic")?"output_tokens":"completion_tokens"));if(ingress.equals("openai_chat"))assertTrue(usage.isNull("total_tokens"));assertEquals(1,rig.posts.get());assertNull(rig.failure.get());
        }
    }
    @Test public void errorResultDispositionHasActualLosslessSuccessAndNativeErrorBodies()throws Exception {
        for(String egress:List.of("openai_responses","anthropic"))try(Rig rig=new Rig("anthropic",egress)){
            Reply first=send(rig,request("anthropic",false));assertEquals(first.body,200,first.status);JSONObject followup=followup("anthropic",false,assistant("anthropic",false,first),new AtomicInteger());boolean error=egress.equals("anthropic");followup.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0).put("is_error",error);
            Reply last=send(rig,followup);assertEquals(last.body,200,last.status);String text=assistant("anthropic",false,last).getString("content");assertEquals("Result: 好你",text);JSONObject wire=rig.sent.get(1),out;
            if(egress.equals("anthropic")){out=wire.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0);assertEquals("tool_result",out.getString("type"));assertTrue(out.getBoolean("is_error"));assertEquals("stable_call",out.getString("tool_use_id"));assertEquals("好你",out.getString("content"));}
            else{JSONArray input=wire.getJSONArray("input");out=input.getJSONObject(input.length()-1);assertEquals("function_call_output",out.getString("type"));assertFalse(out.has("is_error"));assertEquals("stable_call",out.getString("call_id"));assertEquals("好你",out.getString("output"));}
            assertEquals(2,rig.posts.get());assertNull(rig.failure.get());System.out.println("controlled_error_result_fixture source_is_error="+error+" egress="+egress+" outbound="+out+" client_text="+JSONObject.quote(text));
        }
    }
    @Test public void strictThreeStatesAreNotCollapsedAndErrorResultsRejectBeforeSend()throws Exception {
        JSONObject req=request("openai_chat",false);assertEquals(ProtocolCodec.Strict.UNSPECIFIED,ProtocolCodec.decode("openai_chat",req).tools().get(0).strict());req.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("strict",false);assertEquals(ProtocolCodec.Strict.FALSE,ProtocolCodec.decode("openai_chat",req).tools().get(0).strict());req.getJSONArray("tools").getJSONObject(0).getJSONObject("function").put("strict",true);assertEquals(ProtocolCodec.Strict.TRUE,ProtocolCodec.decode("openai_chat",req).tools().get(0).strict());
        try(Rig rig=new Rig("anthropic","openai_responses")){JSONObject input=followup("anthropic",false,ProviderAdapters.selected("openai_responses").response(result("openai_responses",true)),new AtomicInteger());input.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0).put("is_error",true);assertEquals(400,send(rig,input).status);assertEquals(0,rig.posts.get());System.out.println("controlled_error_result_fixture source_is_error=true egress=openai_responses disposition=UNSUPPORTED http=400 physical_posts=0");}
    }
    @Test public void physicalUsageRetainsEgressSourceAndEveryCanonicalToolFragmentIdentity()throws Exception {
        for(String ingress:List.of("anthropic","openai_chat"))try(Rig rig=new Rig(ingress,ingress.equals("anthropic")?"openai_responses":"anthropic")){
            AtomicInteger commits=new AtomicInteger(),starts=new AtomicInteger(),finishes=new AtomicInteger();AtomicReference<InferenceReceipt> receipt=new AtomicReference<>();List<GatewayEvent> events=new ArrayList<>();
            InferenceCapture.Target target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){starts.incrementAndGet();}public void finished(InferenceReceipt r){finishes.incrementAndGet();receipt.set(r);}});
            GatewayRequest prepared=ProtocolCodec.prepare(rig.config,ingress,request(ingress,true));ModelGateway.completeNative(rig.config,prepared,new RunControl(),target,(call,seq)->commits.incrementAndGet(),new ProtocolGateway.WireSink(){
                public void frame(String event,String data){assertEquals(1,commits.get());if(data.contains("partial_json")||data.contains("arguments")&&data.contains("value"))rig.firstDeltaSeen.countDown();}
                public void canonical(GatewayEvent event){assertEquals(1,commits.get());events.add(event);}
            });
            assertEquals(1,starts.get());assertEquals(1,finishes.get());assertTrue(rig.producerReleased.get());JSONObject actual=receipt.get().toJson();assertEquals("COMPLETED",actual.getString("outcome"));JSONObject usage=actual.getJSONObject("usage"),counters=usage.getJSONObject("counters");assertEquals(rig.egress,usage.getString("protocol"));assertEquals(12,counters.getJSONObject("INPUT").getInt("value"));assertEquals(3,counters.getJSONObject("OUTPUT").getInt("value"));assertEquals("usage.input_tokens",counters.getJSONObject("INPUT").getString("sourcePath"));assertTrue(usage.isNull("actualCost"));
            long seq=0;int fragments=0;for(GatewayEvent e:events){assertTrue(e.eventSeq>seq);seq=e.eventSeq;assertEquals(receipt.get().callId,e.attemptCallId);assertEquals(rig.egress,e.sourceProtocol);assertEquals(rig.ingress,e.wireProtocol);if(e.type==GatewayEvent.Type.USAGE||e.type==GatewayEvent.Type.COMPLETE){assertEquals(rig.egress,e.usageObservation.getString("protocol"));assertEquals(12,e.usageObservation.getJSONObject("counters").getJSONObject("INPUT").getInt("value"));}if(e.type==GatewayEvent.Type.TOOL_ARGS_DELTA){fragments++;assertEquals("stable_call",e.toolCallId);}}
            assertEquals(2,fragments);assertNull(rig.failure.get());
        }
    }
    @Test public void commitFailurePreventsEverySemanticFlushWithOnePhysicalReceipt()throws Exception {
        try(Rig rig=new Rig("anthropic","openai_responses")){
            rig.firstDeltaSeen.countDown();AtomicInteger flushes=new AtomicInteger(),starts=new AtomicInteger(),finishes=new AtomicInteger();InferenceCapture.Target target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){starts.incrementAndGet();}public void finished(InferenceReceipt r){finishes.incrementAndGet();}});
            GatewayRequest prepared=ProtocolCodec.prepare(rig.config,rig.ingress,request(rig.ingress,true));try{ModelGateway.completeNative(rig.config,prepared,new RunControl(),target,(callId,seq)->{throw new IOException("durable commit denied");},(event,data)->flushes.incrementAndGet());fail("commit error swallowed");}catch(Exception expected){}
            assertEquals(0,flushes.get());assertEquals(1,starts.get());assertEquals(1,finishes.get());assertEquals(1,rig.posts.get());
        }
    }
    private static JSONObject result(String protocol,boolean calls)throws Exception {
        JSONObject usage=new JSONObject().put("input_tokens",12).put("output_tokens",calls?3:5);if(protocol.equals("openai_responses"))usage.put("total_tokens",calls?15:17).put("input_tokens_details",new JSONObject().put("cached_tokens",2));else usage.put("cache_read_input_tokens",2).put("cache_creation_input_tokens",0);
        if(protocol.equals("openai_responses")){JSONObject item=calls?new JSONObject().put("id","item_call").put("type","function_call").put("call_id","stable_call").put("name","reverse").put("arguments","{\"value\":\"你好\"}"):
            new JSONObject().put("id","item_text").put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","Result: 好你")));return new JSONObject().put("id","response_one").put("status","completed").put("model","actual-provider-model").put("output",new JSONArray().put(item)).put("usage",usage);}
        JSONObject block=calls?new JSONObject().put("type","tool_use").put("id","stable_call").put("name","reverse").put("input",new JSONObject().put("value","你好")):new JSONObject().put("type","text").put("text","Result: 好你");return new JSONObject().put("id","message_one").put("type","message").put("role","assistant").put("model","actual-provider-model").put("content",new JSONArray().put(block)).put("stop_reason",calls?"tool_use":"end_turn").put("usage",usage);
    }
    private static String event(String type,JSONObject body)throws Exception{return "event: "+type+"\ndata: "+body.put("type",type)+"\n\n";}
    private static String[] unicodeFrames(String protocol)throws Exception {
        JSONObject full=result(protocol,false);String before,after;
        if(protocol.equals("openai_responses")){
            JSONObject item=full.getJSONArray("output").getJSONObject(0);item.getJSONArray("content").getJSONObject(0).put("text","🙂");
            before=event("response.created",new JSONObject().put("response",new JSONObject().put("id","response_one").put("status","in_progress")))+event("response.output_item.added",new JSONObject().put("output_index",0).put("item",new JSONObject(item.toString()).put("content",new JSONArray())))+event("response.content_part.added",new JSONObject().put("output_index",0).put("content_index",0).put("part",new JSONObject().put("type","output_text").put("text","")))+event("response.output_text.delta",new JSONObject().put("output_index",0).put("content_index",0).put("delta","\ud83d"));
            after=event("response.output_text.delta",new JSONObject().put("output_index",0).put("content_index",0).put("delta","\ude42"))+event("response.content_part.done",new JSONObject().put("output_index",0).put("content_index",0).put("part",item.getJSONArray("content").getJSONObject(0)))+event("response.output_item.done",new JSONObject().put("output_index",0).put("item",item))+event("response.completed",new JSONObject().put("response",full));
        }else{
            before=event("message_start",new JSONObject().put("message",new JSONObject(full.toString()).put("content",new JSONArray()).put("stop_reason",JSONObject.NULL)))+event("content_block_start",new JSONObject().put("index",0).put("content_block",new JSONObject().put("type","text").put("text","")))+event("content_block_delta",new JSONObject().put("index",0).put("delta",new JSONObject().put("type","text_delta").put("text","\ud83d")));
            after=event("content_block_delta",new JSONObject().put("index",0).put("delta",new JSONObject().put("type","text_delta").put("text","\ude42")))+event("content_block_stop",new JSONObject().put("index",0))+event("message_delta",new JSONObject().put("delta",new JSONObject().put("stop_reason","end_turn")).put("usage",full.getJSONObject("usage")))+event("message_stop",new JSONObject());
        }
        // JSON escapes, not replacement bytes, carry deliberately incomplete UTF-16 deltas.
        return new String[]{before.replace("\ud83d","\\ud83d").replace("\ude42","\\ude42"),after.replace("\ud83d","\\ud83d").replace("\ude42","\\ude42")};
    }
    private static String[] parallelFrames(String protocol)throws Exception {
        String before,after="";JSONObject full=result(protocol,true);JSONArray finalParts=new JSONArray();
        if(protocol.equals("openai_responses")){
            before=event("response.created",new JSONObject().put("response",new JSONObject().put("id","response_one").put("status","in_progress")));
            for(int i=0;i<2;i++){JSONObject item=new JSONObject(full.getJSONArray("output").getJSONObject(0).toString()).put("id","item_"+i).put("call_id","call_"+i).put("arguments",new JSONObject().put("value",i==0?"A":"B").toString());finalParts.put(item);before+=event("response.output_item.added",new JSONObject().put("output_index",i).put("item",new JSONObject(item.toString()).put("arguments","")));}
            for(int i=0;i<2;i++)before+=event("response.function_call_arguments.delta",new JSONObject().put("output_index",i).put("item_id","item_"+i).put("delta","{\"value\":"));
            for(int i=1;i>=0;i--)after+=event("response.function_call_arguments.delta",new JSONObject().put("output_index",i).put("item_id","item_"+i).put("delta",i==0?"\"A\"}":"\"B\"}"));
            for(int i=0;i<2;i++)after+=event("response.function_call_arguments.done",new JSONObject().put("output_index",i).put("arguments",finalParts.getJSONObject(i).getString("arguments")))+event("response.output_item.done",new JSONObject().put("output_index",i).put("item",finalParts.getJSONObject(i)));
            after+=event("response.completed",new JSONObject().put("response",full.put("output",finalParts)));
        }else{
            before=event("message_start",new JSONObject().put("message",new JSONObject(full.toString()).put("content",new JSONArray()).put("stop_reason",JSONObject.NULL)));
            for(int i=0;i<2;i++)before+=event("content_block_start",new JSONObject().put("index",i).put("content_block",new JSONObject().put("type","tool_use").put("id","call_"+i).put("name","reverse").put("input",new JSONObject())));
            for(int i=0;i<2;i++)before+=event("content_block_delta",new JSONObject().put("index",i).put("delta",new JSONObject().put("type","input_json_delta").put("partial_json","{\"value\":")));
            for(int i=1;i>=0;i--)after+=event("content_block_delta",new JSONObject().put("index",i).put("delta",new JSONObject().put("type","input_json_delta").put("partial_json",i==0?"\"A\"}":"\"B\"}")));
            for(int i=0;i<2;i++)after+=event("content_block_stop",new JSONObject().put("index",i));after+=event("message_delta",new JSONObject().put("delta",new JSONObject().put("stop_reason","tool_use")).put("usage",full.getJSONObject("usage")))+event("message_stop",new JSONObject());
        }return new String[]{before,after};
    }
    private static String[] frames(String protocol,boolean calls,boolean broken,boolean error,boolean invalid,boolean duplicate,boolean invalidUsage)throws Exception {
        JSONObject full=result(protocol,calls);if(invalidUsage)full.getJSONObject("usage").put("cache_read_input_tokens","not-a-counter");String before,after;
        if(protocol.equals("openai_responses")){
            before=event("response.created",new JSONObject().put("response",new JSONObject().put("id","response_one").put("status","in_progress")));
            JSONObject item=full.getJSONArray("output").getJSONObject(0);JSONObject initial=new JSONObject(item.toString());
            if(calls){initial.put("arguments","");before+=event("response.output_item.added",new JSONObject().put("output_index",0).put("item",initial))+event("response.function_call_arguments.delta",new JSONObject().put("output_index",0).put("item_id","item_call").put("delta","{\"value\":"));
                after=event("response.function_call_arguments.delta",new JSONObject().put("output_index",0).put("item_id","item_call").put("delta",invalid?"bad}":"\"你好\"}"));
                if(duplicate)after=event("response.output_item.added",new JSONObject().put("output_index",1).put("item",new JSONObject(initial.toString()).put("id","second_item")))+after;
                after+=event("response.function_call_arguments.done",new JSONObject().put("output_index",0).put("item_id","item_call").put("arguments",invalid?"{\"value\":bad}":"{\"value\":\"你好\"}"));
            }else{initial.put("content",new JSONArray());before+=event("response.output_item.added",new JSONObject().put("output_index",0).put("item",initial))+event("response.content_part.added",new JSONObject().put("output_index",0).put("content_index",0).put("part",new JSONObject().put("type","output_text").put("text","")))+event("response.output_text.delta",new JSONObject().put("output_index",0).put("content_index",0).put("delta","Result: 好你"));after=event("response.content_part.done",new JSONObject().put("output_index",0).put("content_index",0).put("part",new JSONObject().put("type","output_text").put("text","Result: 好你")));}
            after+=event("response.output_item.done",new JSONObject().put("output_index",0).put("item",item))+event("response.completed",new JSONObject().put("response",full));
        }else{
            JSONObject start=new JSONObject(full.toString()).put("content",new JSONArray()).put("stop_reason",JSONObject.NULL);before=event("message_start",new JSONObject().put("message",start));
            JSONObject block=full.getJSONArray("content").getJSONObject(0),initial=new JSONObject(block.toString());if(calls)initial.put("input",new JSONObject());else initial.put("text","");before+=event("content_block_start",new JSONObject().put("index",0).put("content_block",initial));
            before+=event("content_block_delta",new JSONObject().put("index",0).put("delta",calls?new JSONObject().put("type","input_json_delta").put("partial_json","{\"value\":"):new JSONObject().put("type","text_delta").put("text","Result: 好你")));
            after=calls?event("content_block_delta",new JSONObject().put("index",0).put("delta",new JSONObject().put("type","input_json_delta").put("partial_json",invalid?"bad}":"\"你好\"}"))):"";
            if(duplicate)after=event("content_block_start",new JSONObject().put("index",1).put("content_block",initial))+after;
            after+=event("content_block_stop",new JSONObject().put("index",0))+event("message_delta",new JSONObject().put("delta",new JSONObject().put("stop_reason",calls?"tool_use":"end_turn")).put("usage",full.getJSONObject("usage")))+event("message_stop",new JSONObject());
        }
        if(broken)after="";if(error)after=event("error",new JSONObject().put("error",new JSONObject().put("type","api_error").put("message","fixture failure")));return new String[]{before,after};
    }
}
