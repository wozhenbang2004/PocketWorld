package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.j;

/** Real Runner/native image carrier/prepared/approval; bitmap and provider replies are explicit fixtures, not WebView pixels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserVisualRunnerTest {
    void journey(boolean imageAllowed)throws Exception {
        try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F(false,null,true)){
            AtomicReference<Throwable> failed=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();AtomicBoolean receivedImage=new AtomicBoolean();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService workers=Executors.newFixedThreadPool(2);
            server.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)),answer;
                if(calls.getAndIncrement()==0)answer=j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",BrowserVisualCapture.TOOL,"arguments",j("tabId",x.tab).toString()))));
                else{assertEquals(2,calls.get());JSONArray messages=request.getJSONArray("messages");JSONObject receipt=null;for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if(message.optString("role").equals("tool"))receipt=new JSONObject(message.getString("content"));JSONArray content=message.optJSONArray("content");if(content!=null)for(int n=0;n<content.length();n++){JSONObject part=content.getJSONObject(n);if(part.optString("type").equals("image_url")){assertTrue(part.getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"));receivedImage.set(true);assertTrue(content.toString().contains(x.driver.visualDocument));assertTrue(content.toString().contains(x.f.binding.runId));}}}assertNotNull(receipt);assertFalse(receipt.has("imageBase64"));assertFalse(receipt.has("image_url"));if(imageAllowed){assertEquals("captured",receipt.getString("status"));assertTrue(receivedImage.get());}else{assertEquals("image_input_unavailable",receipt.getString("code"));assertFalse(receivedImage.get());}answer=j("role","assistant","content","仅依据本次真实交付内容回答；图像不可用时不假装已看图。");}
                byte[] body=j("choices",new JSONArray().put(j("message",answer,"finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Throwable error){failed.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            try{
                Future<?> decision=imageAllowed?workers.submit(()->{try{BrowserVisualPortNativeTest.decide(x,true);}catch(Exception error){throw new RuntimeException(error);}}):null;
                ModelConfig selected=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"explicit-local-model-key","page-bound-image-fixture",true,20,10).withOwnerImageInput(imageAllowed);
                ContextBudgetPolicy.Source budgets=config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(200000,4096,1000,4*1024*1024,100,2048),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
                RunOutcome outcome=workers.submit(()->new AgentRunner(null,x.port,budgets).run(x.f.binding.taskId,"只有语义不足时查看一次当前网页图表，不操作网页",selected,x.f.control,event->{try{x.f.tasks.event(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},x.f.journal)).get(25,TimeUnit.SECONDS);
                if(decision!=null)decision.get(2,TimeUnit.SECONDS);if(failed.get()!=null)throw new AssertionError("local model/bitmap interface fixture",failed.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertEquals(2,calls.get());assertEquals(imageAllowed?1:0,x.driver.visualCopies);assertEquals(imageAllowed?1:0,x.f.tasks.get(x.f.binding.taskId,false).getJSONArray("operations").length());assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());String durable=x.f.journal.restore().getJSONArray("messages").toString();assertFalse("pixels are not made historical source/action authority",durable.contains("data:image/png;base64,"));
            }finally{server.stop(0);workers.shutdownNow();workers.awaitTermination(2,TimeUnit.SECONDS);}
        }
    }
    @Test public void actualNativeImageIsConsumedAfterReceiptIntoTheOriginalNextModelRequest()throws Exception {journey(true);}
    @Test public void unsupportedModelIsRejectedBeforeApprovalCaptureOrPreparedEffect()throws Exception {journey(false);}
}
