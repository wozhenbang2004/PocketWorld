package dev.pocketengine.phone.observation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.PhoneCollectionFixtures.*;

/** Original TaskStore/RunJournal/AgentRunner and local model HTTP; physical phone is explicitly an interface fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneActionResultJournalTest {
    @Test public void originalRunnerPersistsOneTypedActionAndPostObservationEvidenceWithoutAnotherExecutor()throws Exception {
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());RunControl control=new RunControl();AdaptiveActionEffectsTest.Driver driver=new AdaptiveActionEffectsTest.Driver();ExecutorService worker=Executors.newSingleThreadExecutor();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicReference<Throwable> failed=new AtomicReference<>();AtomicReference<JSONObject> actualReceipt=new AtomicReference<>();int[] requests={0};
        JSONObject task=tasks.startAttempt(tasks.create("原任务输入并回读","main_screen").getString("id"),"main_screen",20);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("phone_type"));tasks.status(id,"running","original native phone fixture");RunJournal journal=tasks.journal(id,run,epoch,control);
        AdaptivePhonePort phone=new AdaptivePhonePort(driver,new AdaptivePhonePort.Scope(id,run,epoch,"s",5),false,false,driver.clock::get);driver.adapter=phone;driver.enforcePostReadMonitor=false;
        // Only the actual original execute call contains the new post-action sampler.
        // The Runner's later, separate legacy observe still has its original monitor contract.
        PhonePort entry=new PhonePort(){
            public boolean isConnected(){return phone.isConnected();}public void clearReferences(){phone.clearReferences();}
            public JSONObject observe(boolean image,RunControl original)throws Exception{return phone.observe(image,original);}
            public JSONObject execute(String tool,JSONObject args,RunControl original)throws Exception{driver.enforcePostReadMonitor=true;try{return phone.execute(tool,args,original);}finally{driver.enforcePostReadMonitor=false;}}
        };String wanted="真实中文🙂输入".repeat(80);
        server.createContext("/",exchange->{try{
            JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests[0]++;JSONObject answer;if(requests[0]==1)answer=j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name","phone_type","arguments",j("observationId",driver.last,"nodeId","n2","text",wanted).toString()))));
            else{JSONArray messages=request.getJSONArray("messages");JSONObject receipt=null;for(int i=messages.length()-1;i>=0;i--)if("tool".equals(messages.getJSONObject(i).optString("role"))){receipt=new JSONObject(messages.getJSONObject(i).getString("content"));break;}assertNotNull(receipt);assertTrue(receipt.toString(),receipt.getJSONObject("nativeTextReadback").getBoolean("exactMatch"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertTrue(receipt.getJSONObject("actionEffects").getBoolean("metadataOnly"));actualReceipt.set(receipt);answer=j("role","assistant","content","已输入且原节点回读一致；界面变化不等于业务已经完成。");}
            byte[] body=j("choices",new JSONArray().put(j("message",answer,"finish_reason",requests[0]==1?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Throwable error){failed.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        try{
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-local-only","phone-action-result",false,20,5);RunOutcome outcome=worker.submit(()->new AgentRunner(entry).run(id,"输入指定文字并依据真实回读继续",config,control,event->{try{tasks.event(id,run,epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},journal)).get(20,TimeUnit.SECONDS);if(failed.get()!=null)throw new AssertionError("local model fixture",failed.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertNotNull(actualReceipt.get());assertEquals(2,requests[0]);assertEquals(1,driver.effects);assertTrue(driver.postReadMonitorChecks>=3);assertFalse(driver.images.contains(true));JSONObject stored=tasks.get(id,false);assertEquals(run,stored.getString("runId"));assertEquals(1,stored.getJSONArray("operations").length());JSONObject operation=stored.getJSONArray("operations").getJSONObject(0);assertEquals("phone_type",operation.getString("tool"));assertEquals("receipt",operation.getString("state"));assertTrue(operation.getJSONObject("receipt").getJSONObject("nativeTextReadback").getBoolean("exactMatch"));assertTrue(journal.restore().getJSONArray("messages").toString().contains("actionEffects"));
        }finally{control.cancel();server.stop(0);worker.shutdownNow();worker.awaitTermination(1,TimeUnit.SECONDS);Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
}
