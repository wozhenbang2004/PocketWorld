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

/** Real original SQLite/Port/repair/Runner. Physical DOM and provider replies are controlled interfaces, not a device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserReadContextRepairSqlTest {
    private static JSONObject delta(BrowserSemanticReadSqlTest.F x)throws Exception {
        x.driver.deltaFixture=true;x.driver.contents.put(x.tab,"A".repeat(1400)+"|"+"B".repeat(1400));
        JSONObject base=x.invoke("observe",x.args("text"));JSONArray shown=BrowserReadDeltaTest.paired(base);
        // Explicit original model-boundary fixture: production KnownSet only trusts actual safe receipts.
        x.port.beforeModelRequest("original-base-request",shown,x.f.control);x.port.afterModelResponse("original-base-request","fixture response",x.f.control);
        x.driver.contents.put(x.tab,"A".repeat(1400)+"|"+"C".repeat(1400));JSONObject args=x.args("text");args.getJSONObject("read").put("regionRef",base.getJSONObject("read").getJSONObject("facts").getString("regionRef")).put("baseRevision",base.getJSONObject("read").getJSONObject("delivery").getString("revision"));
        JSONObject delta=x.invoke("observe",args);assertEquals("delta",delta.getJSONObject("read").getJSONObject("delivery").getString("format"));return delta;
    }
    @Test public void nativeCarrierRechecksActualReadScopeAndNeverRewritesOriginalReceipt()throws Exception {try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F()){
        JSONObject receipt=delta(x);JSONArray orphan=BrowserReadDeltaTest.paired(receipt);String original=orphan.toString(),operations=x.f.tasks.get(x.f.binding.taskId,false).getJSONArray("operations").toString();
        NativeReadContextRepair repair=x.port.nativeReadContextRepair(orphan,x.f.control);assertNotNull(repair);String scope=receipt.getJSONObject("readAuthorization").getString("scopeId");x.f.tasks.revokeBrowserReadScope(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,scope,"owner_revoked");
        try(repair){assertThrows(SecurityException.class,()->repair.consume(orphan,x.f.control));}assertEquals(original,orphan.toString());assertEquals(operations,x.f.tasks.get(x.f.binding.taskId,false).getJSONArray("operations").toString());assertEquals(2,x.driver.reads);
    }}
    @Test public void changedDocumentAndMissingRamCannotBecomeAHistoryReadOrASecondObservation()throws Exception {try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F()){
        JSONObject receipt=delta(x);JSONArray orphan=BrowserReadDeltaTest.paired(receipt);NativeReadContextRepair repair=x.port.nativeReadContextRepair(orphan,x.f.control);assertNotNull(repair);
        x.ui.call(()->{x.driver.original.pages.get(x.tab).put("navigation",1);return null;});try(repair){assertThrows(SecurityException.class,()->repair.consume(orphan,x.f.control));}
        x.ui.call(()->{x.driver.original.pages.get(x.tab).put("navigation",0);x.driver.deltas.clear();return null;});assertThrows(NativeReadContextRepair.Unavailable.class,()->x.port.nativeReadContextRepair(orphan,x.f.control));assertEquals(2,x.driver.reads);assertEquals(2,x.f.tasks.get(x.f.binding.taskId,false).getJSONArray("operations").length());
    }}
    private void runner(boolean budgetDropsFull)throws Exception {try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F()){
        JSONObject receipt=delta(x);String originalReceipt=receipt.toString(),operation=receipt.getString("operationId");JSONArray checkpoint=new JSONArray().put(j("role","system","content","restored original task")).put(j("role","user","content","比较已读取区域；不得重新派发旧操作。"));JSONArray pair=BrowserReadDeltaTest.paired(receipt);checkpoint.put(pair.getJSONObject(0)).put(pair.getJSONObject(1));
        // A genuine original-store checkpoint intentionally has the paired delta but no full base.
        x.f.journal.checkpoint(checkpoint,2,j("referencesExpired",true));AtomicInteger requests=new AtomicInteger();AtomicReference<Throwable> failed=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService worker=Executors.newSingleThreadExecutor();
        server.createContext("/",exchange->{try{requests.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)),seen=null;JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject row=messages.getJSONObject(i);if(row.optString("role").equals("tool")){JSONObject body=new JSONObject(row.getString("content"));if(operation.equals(body.optString("operationId")))seen=body;}}
            assertFalse("over-budget full may not reach provider",budgetDropsFull);assertNotNull(seen);assertEquals(receipt.getString("observationId"),seen.getString("observationId"));assertEquals(receipt.getJSONObject("read").getString("projectionSha256"),seen.getJSONObject("read").getString("projectionSha256"));assertEquals("full",seen.getJSONObject("read").getJSONObject("delivery").getString("format"));assertEquals("full_resync_missing_base_in_final_context",seen.getJSONObject("read").getJSONObject("delivery").getString("comparison"));assertEquals(2,seen.getJSONObject("read").getJSONArray("data").length());assertEquals("A".repeat(1400)+"|"+"C".repeat(1400),seen.getString("text"));
            byte[] body=j("choices",new JSONArray().put(j("message",j("role","assistant","content","仅比较本次已交付区域，原文和回执未被改写。"),"finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Throwable error){failed.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        try{ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-local-only","same-task-native-full-resync",false,30,5);ContextBudgetPolicy.Source budgets=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(200000,4096,1000,budgetDropsFull&&x.driver.repairsOffered>0?1000:4*1024*1024,100,2048),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            RunOutcome outcome=worker.submit(()->new AgentRunner(null,x.port,budgets).run(x.f.binding.taskId,"比较已读取区域并核对原来源",config,x.f.control,event->{try{x.f.tasks.event(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},x.f.journal)).get(25,TimeUnit.SECONDS);
            if(failed.get()!=null)throw new AssertionError("local provider interface fixture",failed.get());if(budgetDropsFull){assertEquals(outcome.summary,"awaiting_user",outcome.status);assertTrue(outcome.summary.contains("重同步"));assertEquals(0,requests.get());}else{assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertEquals(1,requests.get());}
            assertEquals(2,x.driver.reads);assertEquals(1,x.driver.repairsOffered);assertEquals(0,x.driver.original.mutableDispatches);JSONArray rows=x.f.tasks.get(x.f.binding.taskId,false).getJSONArray("operations");assertEquals(2,rows.length());JSONObject saved=null;for(int i=0;i<rows.length();i++)if(operation.equals(rows.getJSONObject(i).getString("operationId")))saved=rows.getJSONObject(i).getJSONObject("receipt");assertNotNull(saved);assertEquals(dev.pocketengine.store.TaskStore.capabilityFingerprint(new JSONObject(originalReceipt)),dev.pocketengine.store.TaskStore.capabilityFingerprint(saved));assertEquals("delta",saved.getJSONObject("read").getJSONObject("delivery").getString("format"));
        }finally{server.stop(0);worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}
    }}
    @Test public void originalRunnerSendsSameObservationFullOnlyOnTheWireWithoutAnotherToolOrModelLoop()throws Exception {runner(false);}
    @Test public void originalRunnerStopsBeforeMainIfTheOneRebudgetPassCannotKeepTheNativeFull()throws Exception {runner(true);}
}
