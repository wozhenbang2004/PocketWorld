package dev.pocketengine.features.proxy;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.usage.NativeInferenceWork;
import dev.pocketengine.store.TaskStore;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual relay producer -> same SQLite/Capture. No provider account, invoice or audio hardware. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeRelayUsageJourneyTest {
 private static final String TOKEN="native-relay-usage-local-token-123456",KEY="fixture-private-api-key";
 private static final class Rig implements AutoCloseable {
  final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;
  final HttpServer upstream;final ExecutorService workers=Executors.newCachedThreadPool();final AtomicInteger posts=new AtomicInteger();final AtomicReference<JSONObject> reference=new AtomicReference<>();
  final String protocol;final ModelConfig config;final LoopbackRelay relay;volatile String reply;volatile boolean streaming;volatile int status=200;
  Rig(String protocol)throws Exception{
   this.protocol=protocol;android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
   upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.setExecutor(workers);upstream.createContext("/",exchange->{try{posts.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type",streaming?"text/event-stream":"application/json");if(status==429)exchange.getResponseHeaders().set("Retry-After","12");byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});upstream.start();
   config=new ModelConfig("http://127.0.0.1:"+upstream.getAddress().getPort()+(protocol.equals("gemini")?"/v1beta":"/v1"),KEY,"selected-model",false,4,10,protocol,512);
   relay=new LoopbackRelay(0,TOKEN,()->config,null,config,(captured,original,session)->{assertSame(config,captured);NativeInferenceWork work=NativeInferenceWork.open(tasks,TaskStore.NativeInferenceKind.RELAY,session,original);reference.set(work.reference());return work;});
  }
  JSONObject request()throws Exception{
   if(protocol.equals("openai_chat"))return new JSONObject().put("model","selected-model").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","CLIENT-PRIVATE-TEXT"))).put("stream",streaming);
   if(protocol.equals("openai_responses"))return new JSONObject().put("model","selected-model").put("input","CLIENT-PRIVATE-TEXT").put("stream",streaming);
   if(protocol.equals("anthropic"))return new JSONObject().put("model","selected-model").put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","CLIENT-PRIVATE-TEXT"))).put("stream",streaming);
   return new JSONObject().put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text","CLIENT-PRIVATE-TEXT")))));
  }
  String send()throws Exception{
   String path=protocol.equals("openai_chat")?"/v1/chat/completions":protocol.equals("openai_responses")?"/v1/responses":protocol.equals("anthropic")?"/v1/messages":"/v1beta/models/selected-model:"+(streaming?"streamGenerateContent?alt=sse":"generateContent");
   HttpURLConnection client=(HttpURLConnection)new URL("http://127.0.0.1:"+relay.port()+path).openConnection();client.setConnectTimeout(2000);client.setReadTimeout(5000);client.setRequestMethod("POST");client.setDoOutput(true);client.setRequestProperty("Authorization","Bearer "+TOKEN);client.setRequestProperty("Content-Type","application/json");byte[] bytes=request().toString().getBytes(StandardCharsets.UTF_8);client.setFixedLengthStreamingMode(bytes.length);
   try{try(var out=client.getOutputStream()){out.write(bytes);}int actual=client.getResponseCode();try(var input=actual<400?client.getInputStream():client.getErrorStream()){return input==null?"":new String(input.readAllBytes(),StandardCharsets.UTF_8);}}finally{client.disconnect();}
  }
  JSONObject receipt()throws Exception{
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){JSONObject locator=reference.get();if(locator!=null){JSONObject usage=tasks.inferenceUsage(locator.getString("taskId"));if(usage.getJSONArray("receipts").length()==1){JSONObject receipt=usage.getJSONArray("receipts").getJSONObject(0);if(!"STARTED".equals(receipt.getString("outcome")))return receipt;}}Thread.sleep(10);}throw new AssertionError("actual relay receipt did not settle");
  }
  public void close()throws Exception{relay.close();upstream.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));db.close();}
 }
 private static String frame(String type,JSONObject body)throws Exception{return "event: "+type+"\ndata: "+body.put("type",type)+"\n\n";}
 private static JSONObject result(String protocol)throws Exception{
  if(protocol.equals("openai_chat"))return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","ACTUAL-ANSWER")))).put("usage",new JSONObject().put("prompt_tokens",4).put("completion_tokens",2).put("total_tokens",6));
  if(protocol.equals("openai_responses"))return new JSONObject().put("id","response_actual").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("id","message_actual").put("role","assistant").put("status","completed").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","ACTUAL-ANSWER"))))).put("usage",new JSONObject().put("input_tokens",4).put("output_tokens",2).put("total_tokens",6));
  if(protocol.equals("anthropic"))return new JSONObject().put("id","message_actual").put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","ACTUAL-ANSWER"))).put("stop_reason","end_turn").put("usage",new JSONObject().put("input_tokens",4).put("output_tokens",2));
  return new JSONObject().put("responseId","response_actual").put("candidates",new JSONArray().put(new JSONObject().put("index",0).put("content",new JSONObject().put("role","model").put("parts",new JSONArray().put(new JSONObject().put("text","ACTUAL-ANSWER")))).put("finishReason","STOP"))).put("usageMetadata",new JSONObject().put("promptTokenCount",4).put("candidatesTokenCount",2).put("totalTokenCount",6));
 }
 @Test public void everyNativeProtocolBindsItsActualApiRequestToOriginalDurableRelayUsage()throws Exception{
  for(String protocol:List.of("openai_chat","openai_responses","anthropic","gemini"))try(Rig r=new Rig(protocol)){
   r.reply=result(protocol).toString();assertTrue(r.send().contains("ACTUAL-ANSWER"));JSONObject receipt=r.receipt();assertEquals("COMPLETED",receipt.getString("outcome"));assertEquals("RELAY",receipt.getJSONObject("context").getString("purpose"));assertEquals(protocol,receipt.getJSONObject("route").getString("protocol"));assertEquals(4,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));assertEquals(2,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("OUTPUT").getLong("value"));assertEquals(1,r.posts.get());
   JSONObject task=r.tasks.get(r.reference.get().getString("taskId"),false);assertEquals("inference",task.getString("kind"));assertEquals(0,task.getJSONArray("operations").length());assertFalse(task.toString().contains("CLIENT-PRIVATE-TEXT"));assertTrue(receipt.getJSONObject("usage").isNull("actualCost"));assertFalse(r.reference.get().getBoolean("executionAuthorized"));assertEquals(0,r.tasks.taskSummaryPage("",40).getJSONArray("tasks").length());
  }
 }
 @Test public void bufferedRejectedAndMissingTerminalsPreserveActualUsageWithoutClientDelivery()throws Exception{
  for(String protocol:List.of("openai_chat","openai_responses","anthropic","gemini"))for(boolean missing:new boolean[]{false,true})try(Rig r=new Rig(protocol)){
   JSONObject wire=result(protocol),terminal=protocol.equals("openai_chat")?wire.getJSONArray("choices").getJSONObject(0):protocol.equals("gemini")?wire.getJSONArray("candidates").getJSONObject(0):wire;
   String key=protocol.equals("openai_chat")?"finish_reason":protocol.equals("openai_responses")?"status":protocol.equals("anthropic")?"stop_reason":"finishReason";
   String rejected=protocol.equals("openai_chat")?"content_filter":protocol.equals("openai_responses")?"incomplete":protocol.equals("anthropic")?"max_tokens":"SAFETY";
   if(missing)terminal.remove(key);else terminal.put(key,rejected);r.reply=wire.toString();String delivered=r.send();assertTrue(delivered.contains("error"));assertFalse(delivered.contains("ACTUAL-ANSWER"));
   JSONObject receipt=r.receipt();assertEquals("FAILED",receipt.getString("outcome"));assertEquals(1,r.posts.get());assertEquals(1,r.tasks.inferenceUsage(r.reference.get().getString("taskId")).getInt("attempts"));
   assertEquals(4,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));assertEquals(2,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("OUTPUT").getLong("value"));
   if(missing)assertTrue(receipt.getJSONObject("usage").isNull("finishReason"));else assertEquals(rejected,receipt.getJSONObject("usage").getString("finishReason"));
   assertEquals(200,receipt.getJSONObject("failure").getInt("httpStatus"));assertFalse(receipt.getJSONObject("failure").getBoolean("automaticRetry"));
  }
 }
 @Test public void bufferedChatCannotRelayWrongRolesMultipleChoicesOrUnrequestedTools()throws Exception{
  for(String invalid:List.of("role","choices","tool"))try(Rig r=new Rig("openai_chat")){
   JSONObject wire=result("openai_chat"),choice=wire.getJSONArray("choices").getJSONObject(0),message=choice.getJSONObject("message");
   if(invalid.equals("role"))message.put("role","user");else if(invalid.equals("choices"))wire.getJSONArray("choices").put(new JSONObject(choice.toString()));
   else{choice.put("finish_reason","tool_calls");message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-one").put("type","function").put("function",new JSONObject().put("name","undeclared").put("arguments","{}"))));}
   r.reply=wire.toString();assertFalse(r.send().contains("ACTUAL-ANSWER"));assertEquals("FAILED",r.receipt().getString("outcome"));assertEquals(1,r.posts.get());
  }
 }
 @Test public void nativePartialTextAndRealUsageSurviveMissingTerminalWithoutReplayOrFakeCompletion()throws Exception{
  for(String protocol:List.of("openai_responses","anthropic","gemini"))try(Rig r=new Rig(protocol)){
   r.streaming=true;
   if(protocol.equals("openai_responses"))r.reply=frame("response.created",new JSONObject().put("response",new JSONObject().put("id","partial_response").put("status","in_progress")))+frame("response.output_item.added",new JSONObject().put("output_index",0).put("item",new JSONObject().put("id","partial_message").put("type","message").put("role","assistant").put("status","in_progress").put("content",new JSONArray())))+frame("response.content_part.added",new JSONObject().put("output_index",0).put("content_index",0).put("item_id","partial_message").put("part",new JSONObject().put("type","output_text").put("text","")))+frame("response.output_text.delta",new JSONObject().put("output_index",0).put("content_index",0).put("item_id","partial_message").put("delta","PARTIAL-ACTUAL"));
   else if(protocol.equals("anthropic"))r.reply=frame("message_start",new JSONObject().put("message",new JSONObject().put("id","partial_message").put("type","message").put("role","assistant").put("content",new JSONArray()).put("usage",new JSONObject().put("input_tokens",4))))+frame("content_block_start",new JSONObject().put("index",0).put("content_block",new JSONObject().put("type","text").put("text","PARTIAL-ACTUAL")));
   else r.reply="data: "+new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("index",0).put("content",new JSONObject().put("role","model").put("parts",new JSONArray().put(new JSONObject().put("text","PARTIAL-ACTUAL")))))).put("usageMetadata",new JSONObject().put("promptTokenCount",4))+"\n\n";
   String reply=r.send();assertTrue(protocol,reply.contains("PARTIAL-ACTUAL"));assertTrue(reply.contains("error"));assertFalse(reply.contains("response.completed"));assertFalse(reply.contains("message_stop"));assertFalse(reply.contains("[DONE]"));JSONObject receipt=r.receipt();assertEquals("FAILED",receipt.getString("outcome"));JSONObject failure=receipt.getJSONObject("failure");assertTrue(protocol,failure.getBoolean("partialTextReceived"));assertEquals(200,failure.getInt("httpStatus"));assertFalse(failure.getBoolean("automaticRetry"));assertEquals(1,r.posts.get());assertEquals(1,r.tasks.inferenceUsage(r.reference.get().getString("taskId")).getInt("attempts"));assertTrue(receipt.getJSONObject("usage").isNull("actualCost"));
  }
 }
 @Test public void actualNativeHttpThrottlePreservesHeaderEvidenceWithoutCooldownGuessOrRetry()throws Exception{
  try(Rig r=new Rig("openai_responses")){r.status=429;r.reply="{\"error\":\"fixture-throttled\"}";assertFalse(r.send().contains("ACTUAL-ANSWER"));JSONObject receipt=r.receipt(),failure=receipt.getJSONObject("failure");assertEquals("FAILED",receipt.getString("outcome"));assertEquals("HTTP",failure.getString("kind"));assertEquals(429,failure.getInt("httpStatus"));assertEquals("VALID",failure.getJSONObject("retry").getString("status"));assertEquals(12,failure.getJSONObject("retry").getLong("delaySeconds"));assertFalse(failure.getBoolean("automaticRetry"));assertFalse(failure.getBoolean("healthVerified"));assertEquals(1,r.posts.get());assertTrue(receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("TOTAL").isNull("value"));}
 }
}
