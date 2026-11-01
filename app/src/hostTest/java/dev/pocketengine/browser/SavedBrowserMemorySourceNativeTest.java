package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.core.ExecutionResources;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Original SQLite/Runner/approval/source path; physical DOM and model responses are explicit fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SavedBrowserMemorySourceNativeTest {
    @Test public void actualRunnerSafeDeliveryPersistsOnlyItsPinnedMemoryProjectionAcrossReopen()throws Exception {
        try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F(true,null)){
            String canary="fictional-model-key-structured-cell-684102",original="费用 "+canary+" ¥42";
            x.driver.contents.put(x.tab,original);AtomicReference<Throwable> failed=new AtomicReference<>();JSONObject[] savedReceipt={null};int[] step={0};
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                String wire=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertFalse("raw native secret must not be delivered",wire.contains(canary));
                JSONObject request=new JSONObject(wire),receipt=null;JSONArray messages=request.getJSONArray("messages");
                for(int i=messages.length()-1;i>=0;i--)if(messages.getJSONObject(i).optString("role").equals("tool")){receipt=new JSONObject(messages.getJSONObject(i).getString("content"));break;}
                JSONObject answer;
                if(step[0]++==0)answer=call("browser_task_observe",x.args("table"));
                else if(step[0]==2){assertNotNull(receipt);assertTrue(receipt.toString(),receipt.getBoolean("ok"));assertTrue(receipt.getJSONObject("read").getJSONArray("data").toString().contains("¥42"));answer=call("browser_task_retain_source",j("tabId",x.tab,"observationId",receipt.getString("observationId")));}
                else{savedReceipt[0]=receipt;assertNotNull(receipt);assertTrue(receipt.toString(),receipt.getBoolean("sourceRetained"));assertEquals("partial",receipt.getJSONObject("outputCapture").getString("status"));assertEquals("native_output_upstream_completeness_unknown",receipt.getJSONObject("outputCapture").getString("reason"));assertFalse(receipt.getJSONObject("outputCapture").getBoolean("bodyIncluded"));String ref=receipt.getJSONArray("fieldRefs").getString(0);answer=j("role","assistant","content","费用依据：[¥42]("+ref+")；原网页业务保存状态未核实。");}
                byte[] body=j("choices",new JSONArray().put(j("message",answer,"finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Throwable failure){failed.compareAndSet(null,failure);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                ModelConfig model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),canary,"structured-source-original-runner",false,30,8);
                RunOutcome outcome=worker.submit(()->new AgentRunner(null,x.port).run(x.f.binding.taskId,"读取原页费用表并保留字段依据",model,x.f.control,event->{try{x.f.tasks.event(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},x.f.journal)).get(25,TimeUnit.SECONDS);
                if(failed.get()!=null)throw new AssertionError("local model fixture",failed.get());assertEquals(3,step[0]);assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertNotNull(savedReceipt[0]);
                JSONObject before=x.f.tasks.get(x.f.binding.taskId,false);int operations=before.getJSONArray("operations").length();assertEquals(2,operations);assertEquals(x.f.binding.runId,before.getString("runId"));assertEquals(x.f.binding.epoch,before.getInt("coordinatorEpoch"));
                TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());BrowserModule module=new BrowserModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));
                try{
                    JSONArray sources=BrowserTaskSourcePort.sourcesForOwner(reopened,x.f.binding.taskId);assertEquals(1,sources.length());JSONObject source=sources.getJSONObject(0),projection=source.getJSONObject("structuredProjection");assertFalse(source.toString().contains(canary));
                    java.lang.reflect.Field dbField=TaskStore.class.getDeclaredField("db");dbField.setAccessible(true);android.database.sqlite.SQLiteDatabase originalDb=(android.database.sqlite.SQLiteDatabase)dbField.get(reopened);
                    try(android.database.Cursor rows=originalDb.rawQuery("SELECT c.body FROM task_output_chunks c JOIN task_output_sources s ON c.output_id=s.output_id WHERE s.task_id=? AND s.run_id=? ORDER BY c.chunk_index",new String[]{x.f.binding.taskId,x.f.binding.runId})){StringBuilder body=new StringBuilder();while(rows.moveToNext())body.append(rows.getString(0));String safeBody=body.toString();assertFalse("actual retained output body",safeBody.isEmpty());assertFalse(safeBody.contains(canary));assertFalse(safeBody.contains("privateUrl"));assertFalse(safeBody.contains("owner=private"));assertFalse(safeBody.contains("\"title\":"));assertTrue(safeBody.contains("browser_semantic_retained_delivered_subset"));assertTrue(safeBody.contains("structuredProjection"));}
                    JSONObject nativeSaved;long originalSeq;
                    try(android.database.Cursor rows=originalDb.rawQuery("SELECT native_seq,event_json FROM task_events WHERE task_id=? AND event_type='browser_source_retained' ORDER BY event_order DESC LIMIT 1",new String[]{x.f.binding.taskId})){assertTrue(rows.moveToFirst());originalSeq=rows.getLong(0);nativeSaved=new JSONObject(rows.getString(1));}
                    assertEquals("pocketworld.browser-retained-safe-delivery.v1",nativeSaved.getString("safeProjectionFormat"));assertTrue(nativeSaved.getString("safeProjectionFingerprint").matches("[0-9a-f]{64}"));
                    JSONObject pinned=BrowserTaskSpace.savedMemoryProjection(nativeSaved);assertFalse(pinned.toString().contains("privateUrl"));assertFalse(pinned.has("title"));assertFalse(pinned.toString().contains(canary));assertFalse(pinned.toString().contains("owner=private"));assertFalse(pinned.getBoolean("wholePageIncluded"));
                    JSONObject old=new JSONObject(nativeSaved.toString());old.remove("safeProjectionFingerprint");assertThrows(SecurityException.class,()->BrowserTaskSpace.savedMemoryProjection(old));
                    JSONObject tampered=new JSONObject(nativeSaved.toString());tampered.getJSONObject("source").put("excerpt","undelivered substitute");assertThrows(SecurityException.class,()->BrowserTaskSpace.savedMemoryProjection(tampered));
                    var memoryRef=reopened.selectSavedMemorySource(x.f.binding.taskId,x.f.binding.runId,originalSeq,0,24000);
                    dev.pocketengine.features.FeatureHost memoryHost=new dev.pocketengine.features.FeatureHost(e->{});var bridge=new dev.pocketengine.features.MemorySourceBridge(memoryHost,reopened);
                    x.driver.contents.put(x.tab,"different live page must not replace saved evidence");
                    var memoryBody=worker.submit(()->bridge.read(memoryRef)).get(3,TimeUnit.SECONDS);assertTrue(memoryBody.text.contains("¥42"));assertFalse(memoryBody.text.contains(canary));assertFalse(memoryBody.text.contains("privateUrl"));assertFalse(memoryBody.text.contains("owner=private"));assertFalse(memoryBody.text.contains("different live page"));assertEquals(memoryRef,memoryBody.ref);assertEquals(originalSeq,memoryRef.revision);memoryHost.close();
                    assertEquals("runner_safe_json_delivery",projection.getString("basis"));assertTrue(projection.toString().contains("¥42"));assertNotEquals(original,projection.getJSONArray("data").getJSONObject(0).getJSONArray("cells").getJSONObject(0).getString("text"));
                    String ref=savedReceipt[0].getJSONArray("fieldRefs").getString(0);BrowserTaskHost host=new BrowserTaskHost(module,reopened,new ExecutionResources(),new BrowserTaskHost.OriginalTasks(){public void checkModelConfigured(){throw new AssertionError("historical read cannot start a model");}public void launch(String id){throw new AssertionError("historical read cannot start a task");}public RunControl currentControl(String id){return null;}},event->{});
                    x.driver.contents.put(x.tab,"different current page, not historical evidence");JSONObject field=worker.submit(()->host.savedFieldForOwner(x.f.binding.taskId,ref)).get(3,TimeUnit.SECONDS);assertEquals(projection.getJSONArray("data").getJSONObject(0).getJSONArray("cells").getJSONObject(0).getString("text"),field.getString("text"));assertTrue(field.getBoolean("historicalSavedProjection"));assertFalse(field.getBoolean("verifiedBusinessSuccess"));
                    assertThrows(ExecutionException.class,()->worker.submit(()->host.savedFieldForOwner(UUID.randomUUID().toString(),ref)).get(3,TimeUnit.SECONDS));
                    assertEquals("reopened native projection must expose the exact retained field token",ref,source.getJSONArray("fieldRefs").getString(0));System.out.println("AF12_FIELD_BOUNDARY "+j("fieldRef",ref,"savedRevision",projection.getString("revision"),"savedSourceRef",source.getString("sourceRef"),"inputSummary",outcome.summary));JSONObject result=BrowserResearchResult.project(x.f.binding.taskId,outcome.summary,sources);assertTrue(result.toString(),result.toString().contains(ref));assertEquals(operations,reopened.get(x.f.binding.taskId,false).getJSONArray("operations").length());assertEquals(0,x.driver.original.mutableDispatches);
                }finally{module.close();BrowserTaskPersistenceTest.closeDatabase(reopened);}
            }finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);server.stop(0);}
        }
    }
    @Test public void withoutOriginalSaveConsentNoSavedMemoryEvidenceOrAutomaticRetentionIsMinted()throws Exception {
        try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F()){
            JSONObject identity=x.ui.call(()->x.space.identity().put("pauseRevision",x.f.control.pauseRevision()));x.f.tasks.grantBrowserReadScope(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,x.f.control,identity,BrowserReadScope.forOwner(true,false,""));
            JSONObject args=x.args("table"),read=x.invoke("observe",args);assertTrue(read.toString(),read.getBoolean("ok"));x.port.afterTool("browser_task_observe",args,read,x.f.control);
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                Future<JSONObject> attempt=worker.submit(()->x.invoke("retain_source",j("tabId",x.tab,"observationId",read.getString("observationId"))));JSONObject approval=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<deadline){JSONObject event=x.f.events.poll(100,TimeUnit.MILLISECONDS);if(event!=null&&event.has("approvalId")){approval=event;break;}}
                assertNotNull("source saving must ask instead of inheriting plain read consent",approval);assertEquals("browser_task_retain_source",approval.getString("tool"));assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());String id=approval.getString("approvalId");x.f.tasks.decideCapabilityApproval(x.f.binding.taskId,id,false);x.approvals.nativeDecisionChanged(x.f.binding.taskId,id,x.f.control);JSONObject denied=attempt.get(3,TimeUnit.SECONDS);assertFalse(denied.getBoolean("accepted"));assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());assertEquals(0,x.driver.original.mutableDispatches);
            }finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}
        }
    }
    private static JSONObject call(String tool,JSONObject args)throws Exception{return j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",tool,"arguments",args.toString()))));}
}
