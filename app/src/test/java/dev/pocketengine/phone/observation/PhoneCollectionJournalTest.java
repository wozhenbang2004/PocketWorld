package dev.pocketengine.phone.observation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

/** Real TaskStore/RunJournal/AgentRunner/local model HTTP. Only physical accessibility is an explicit fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneCollectionJournalTest {
    static final class F implements AutoCloseable {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());final RunControl control=new RunControl();
        final AdaptiveTreeCollectionTest.Driver driver=new AdaptiveTreeCollectionTest.Driver();final AdaptivePhonePort phone;final CapabilityPort cap;final RunJournal journal;final ModelCapabilityAdapters.Binding binding;
        F()throws Exception {
            JSONObject task=tasks.startAttempt(tasks.create("采集当前原手机列表","main_screen").getString("id"),"main_screen",30);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);binding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,"");
            phone=new AdaptivePhonePort(driver,new AdaptivePhonePort.Scope(id,run,epoch,"s",5),false,false,driver.clock::get);
            CapabilityPort base=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl original){throw new AssertionError("No parallel/coordinate executor");}};
            cap=PhoneTreeCapabilityPort.bindIfAvailable(base,tasks,binding,control,phone);JSONArray names=new JSONArray(),defs=cap.tools();for(int i=0;i<defs.length();i++)names.put(defs.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(id,run,names);tasks.status(id,"running","real original phone fixture");journal=tasks.journal(id,run,epoch,control);
        }
        JSONObject observe()throws Exception{return phone.observe(false,control);}
        JSONObject invokeScroll(JSONObject args)throws Exception {String op=journal.beginOperation(NativeTreeScrollPort.TOOL,args);JSONObject receipt=cap.execute(NativeTreeScrollPort.TOOL,new JSONObject(args.toString()).put("_hostOperationId",op),control);journal.finishOperation(op,receipt);return receipt;}
        JSONObject collect(JSONObject args)throws Exception{return cap.execute(PhoneTreeCollection.TOOL,args,control);}
        public void close()throws Exception {cap.close();control.cancel();Field f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);((SQLiteDatabase)f.get(tasks)).close();}
    }
    @Test public void realPreparedJournalIsMandatoryAndOneOperationCannotScrollTwice()throws Exception {try(F f=new F()){
        f.observe();JSONObject args=j("observationId",f.driver.last,"nodeId","n0","direction","forward");assertFalse(f.cap.execute(NativeTreeScrollPort.TOOL,args,f.control).getBoolean("ok"));assertEquals(0,f.driver.effects);
        String operation=f.journal.beginOperation(NativeTreeScrollPort.TOOL,args);JSONObject injected=new JSONObject(args.toString()).put("_hostOperationId",operation);JSONObject receipt=f.cap.execute(NativeTreeScrollPort.TOOL,injected,f.control);assertEquals(1,f.driver.effects);assertEquals(operation,receipt.getString("operationId"));assertFalse(f.cap.execute(NativeTreeScrollPort.TOOL,injected,f.control).getBoolean("ok"));assertEquals(1,f.driver.effects);f.journal.finishOperation(operation,receipt);
        JSONObject durable=f.tasks.get(f.binding.taskId,false).getJSONArray("operations").getJSONObject(0);assertEquals("receipt",durable.getString("state"));assertEquals("[]",durable.getJSONObject("receipt").getJSONObject("targetRef").getJSONArray("path").toString());assertNotEquals(args.getString("observationId"),durable.getJSONObject("receipt").getString("afterObservationId"));
    }}
    @Test public void onlyTheOriginalRunReceiptCanAdvanceAndReadonlyRefreshCreatesNoOperation()throws Exception {try(F f=new F()){
        f.observe();JSONObject first=f.collect(start(f.driver.last));String cursor=first.getString("cursorId");assertEquals(0,f.tasks.get(f.binding.taskId,false).getJSONArray("operations").length());
        JSONObject fake=f.collect(j("action","advance","cursorId",cursor,"afterOperationId",UUID.randomUUID().toString()));assertFalse(fake.getBoolean("ok"));assertEquals(0,f.driver.effects);
        JSONObject scroll=f.invokeScroll(first.getJSONObject("nextScroll"));JSONObject out=f.collect(j("action","advance","cursorId",cursor,"afterOperationId",scroll.getString("operationId")));assertEquals(4,out.getInt("rowCount"));assertEquals(1,f.tasks.get(f.binding.taskId,false).getJSONArray("operations").length());
        out=f.collect(j("action","read","cursorId",cursor));assertEquals(4,out.getInt("rowCount"));assertEquals(1,f.driver.effects);assertFalse(f.driver.images.contains(true));
    }}
    @Test public void nativeCallbacksNeverRunUnderTaskStoreMonitorAndRevokedPhoneLeaseCannotScroll()throws Exception {try(F f=new F()){
        f.observe();RunControl.AuthorityCheck unlocked=()->assertFalse("native callback under TaskStore monitor",Thread.holdsLock(f.tasks));JSONObject first;
        try(AutoCloseable checked=f.control.retainAuthorityCheck(unlocked)){first=f.collect(start(f.driver.last));}
        // The original journal prepares under its own monitor. This slice's assertion covers native
        // read/dispatch callbacks, not a claim that the unmodified core journal was refactored.
        JSONObject args=first.getJSONObject("nextScroll");String op=f.journal.beginOperation(NativeTreeScrollPort.TOOL,args);JSONObject receipt;
        try(AutoCloseable checked=f.control.retainAuthorityCheck(unlocked)){receipt=f.cap.execute(NativeTreeScrollPort.TOOL,new JSONObject(args.toString()).put("_hostOperationId",op),f.control);assertTrue(receipt.toString(),receipt.getBoolean("accepted"));}
        f.journal.finishOperation(op,receipt);assertEquals(1,f.driver.effects);JSONObject next;
        try(AutoCloseable checked=f.control.retainAuthorityCheck(unlocked)){next=f.collect(j("action","advance","cursorId",first.getString("cursorId"),"afterOperationId",op));}
        JSONObject second=next.getJSONObject("nextScroll");String revoked=f.journal.beginOperation(NativeTreeScrollPort.TOOL,second);
        Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(f.tasks)).delete("leases","run_id=?",new String[]{f.binding.runId});
        try(AutoCloseable checked=f.control.retainAuthorityCheck(unlocked)){assertThrows(SecurityException.class,()->f.cap.execute(NativeTreeScrollPort.TOOL,new JSONObject(second.toString()).put("_hostOperationId",revoked),f.control));}assertEquals(1,f.driver.effects);
    }}
    @Test public void originalRunnerCollectsThreePagesAndPersistsEveryScrollAndResultWithoutNestedExecutor()throws Exception {try(F f=new F()){
        List<JSONObject> requests=new CopyOnWriteArrayList<>();AtomicReference<Throwable> failed=new AtomicReference<>();AtomicReference<JSONObject> last=new AtomicReference<>();String[] previous={""},cursor={""};int[] stage={0};
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);JSONArray messages=request.getJSONArray("messages");JSONObject receipt=null;
            for(int i=messages.length()-1;i>=0;i--)if("tool".equals(messages.getJSONObject(i).optString("role"))){receipt=new JSONObject(messages.getJSONObject(i).getString("content"));break;}
            if(receipt!=null){assertTrue(receipt.toString(),receipt.optBoolean("ok"));if(previous[0].equals(PhoneTreeCollection.TOOL)){cursor[0]=receipt.getString("cursorId");last.set(receipt);}}
            JSONObject answer;String tool="";JSONObject arguments=null;
            switch(stage[0]++){
                case 0:tool=PhoneTreeCollection.TOOL;arguments=start(f.driver.last).put("maxPages",3);break;
                case 1:case 3:tool=NativeTreeScrollPort.TOOL;arguments=last.get().getJSONObject("nextScroll");break;
                case 2:case 4:tool=PhoneTreeCollection.TOOL;arguments=j("action","advance","cursorId",cursor[0],"afterOperationId",receipt.getString("operationId"));break;
                default:assertEquals(5,last.get().getInt("rowCount"));assertEquals("page_limit",last.get().getString("stoppedBecause"));answer=j("role","assistant","content","实际采集5行，达到3屏上限；未证明完整列表或业务成功。");byte[] data=j("choices",new JSONArray().put(j("message",answer,"finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);return;
            }
            previous[0]=tool;answer=j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",tool,"arguments",arguments.toString()))));byte[] data=j("choices",new JSONArray().put(j("message",answer,"finish_reason","tool_calls"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
        }catch(Throwable error){failed.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});server.start();ExecutorService worker=Executors.newSingleThreadExecutor();
        try{ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-local-only","phone-collection-fixture",false,30,10);
            Future<RunOutcome> future=worker.submit(()->new AgentRunner(f.phone,f.cap).run(f.binding.taskId,"采集当前明确列表，最多3屏，不操作其他应用",config,f.control,event->{try{f.tasks.event(f.binding.taskId,f.binding.runId,f.binding.epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},f.journal));RunOutcome outcome=future.get(25,TimeUnit.SECONDS);if(failed.get()!=null)throw new AssertionError("local model fixture",failed.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertEquals(2,f.driver.effects);assertFalse(f.driver.images.contains(true));assertEquals(6,requests.size());
            JSONObject actual=f.tasks.get(f.binding.taskId,false);assertEquals(f.binding.runId,actual.getString("runId"));JSONArray operations=actual.getJSONArray("operations");assertEquals(2,operations.length());for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);assertEquals(NativeTreeScrollPort.TOOL,op.getString("tool"));assertEquals("receipt",op.getString("state"));assertTrue(op.getJSONObject("receipt").getBoolean("accepted"));assertFalse(op.getJSONObject("receipt").getBoolean("verifiedBusinessSuccess"));}
            JSONObject saved=f.journal.restore();assertTrue(saved.toString(),saved.getJSONArray("messages").toString().contains("page_limit"));assertTrue(saved.getJSONArray("messages").toString().contains("sourceObservationId"));TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{assertTrue(reopened.get(f.binding.taskId,false).getJSONArray("events").toString().contains("overlapEvidence"));}finally{Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(reopened)).close();}
        }finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);server.stop(0);}
    }}
}
