package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.capabilities.AgentPreferencePort;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Original SettingsStore, TaskStore grants/journal and RunControl; no device or model call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AgentPreferenceBoundaryTest {
    private static final class Fixture implements AutoCloseable {
        final SettingsStore settings;
        final TaskStore tasks;
        final RunControl control=new RunControl();
        final AgentPreferencePort port;
        final String id,run;
        final int epoch;
        final AtomicInteger downstreamEntries=new AtomicInteger();
        Fixture(String pace,boolean grant)throws Exception {
            this(pace,grant,false);
        }
        Fixture(String pace,boolean grant,boolean phoneTask)throws Exception {
            var app=RuntimeEnvironment.getApplication();
            app.getSharedPreferences("private_settings",0).edit().clear().commit();
            app.deleteDatabase("pocket-world.db");
            settings=new SettingsStore(app);tasks=new TaskStore(app);
            JSONObject pending=phoneTask?tasks.create("preferences"):tasks.createCapabilityTask("preferences","");
            JSONObject task=tasks.startAttempt(pending.getString("id"),phoneTask?"main_screen":"capabilities",16);
            id=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");
            control.bindHostRun(id,run,epoch);
            settings.saveAgentPreferences(true,"standard",pace,1);
            CapabilityPort empty=new CapabilityPort(){
                public JSONArray tools(){return new JSONArray();}
                public boolean supports(String name){return false;}
                public boolean sideEffecting(String name){return false;}
                public JSONObject beforeTool(String name,JSONObject args,RunControl live){downstreamEntries.incrementAndGet();return null;}
                public JSONObject execute(String name,JSONObject args,RunControl live){throw new AssertionError("No unrelated capability");}
            };
            var binding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,"");
            port=new AgentPreferencePort(settings,empty,binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control);
            JSONArray names=new JSONArray();
            if(grant)for(int i=0;i<port.tools().length();i++)names.put(port.tools().getJSONObject(i).getJSONObject("function").getString("name"));
            tasks.authorizeTools(id,run,names);
        }
        RunJournal journal(){return tasks.journal(id,run,epoch,control);}
        public void close(){port.close();}
    }
    private static JSONObject args(String key,String value,long revision)throws Exception {
        return new JSONObject().put("key",key).put("value",value).put("expectedRevision",revision);
    }
    private static JSONObject prepared(JSONObject args,String operation)throws Exception {
        return new JSONObject(args.toString()).put("_hostOperationId",operation);
    }
    @Test public void enabledFlagCannotReplaceOriginalGrantOrControl()throws Exception {
        try(var f=new Fixture("standard",false)){
            assertEquals(2,f.port.tools().length());
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.LIST,new JSONObject(),f.control));
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.SET,args("response_detail","concise",2),f.control));
            assertEquals("standard",f.settings.agentPreferences().getString("response_detail"));
        }
        try(var f=new Fixture("standard",true)){
            RunControl replacement=new RunControl();replacement.bindHostRun(f.id,f.run,f.epoch);
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.LIST,new JSONObject(),replacement));
            f.control.cancel();
            assertThrows(InterruptedException.class,()->f.port.execute(AgentPreferencePort.LIST,new JSONObject(),f.control));
            assertEquals(2,f.settings.agentPreferences().getLong("revision"));
        }
    }
    @Test public void onlyExactPreparedOperationWritesAndCannotReplay()throws Exception {
        try(var f=new Fixture("standard",true)){
            JSONObject exact=args("response_detail","concise",2);
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.SET,exact,f.control));
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.SET,prepared(exact,"unknown-operation"),f.control));
            RunJournal journal=f.journal();String operation=journal.beginOperation(AgentPreferencePort.SET,exact);
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.SET,prepared(args("response_detail","detailed",2),operation),f.control));
            assertEquals("standard",f.settings.agentPreferences().getString("response_detail"));
            JSONObject receipt=f.port.execute(AgentPreferencePort.SET,prepared(exact,operation),f.control);
            journal.finishOperation(operation,receipt);
            assertTrue(receipt.getBoolean("reversible"));
            assertEquals(2,receipt.getJSONObject("previous").getLong("revision"));
            assertEquals(3,receipt.getJSONObject("current").getLong("revision"));
            assertEquals("standard",receipt.getJSONObject("previous").getString("response_detail"));
            assertEquals("concise",receipt.getJSONObject("current").getString("response_detail"));
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.SET,prepared(exact,operation),f.control));
            assertEquals(3,f.settings.agentPreferences().getLong("revision"));
        }
    }
    @Test public void ownerCasAndWithdrawalWinAndIllegalScopeCannotWrite()throws Exception {
        try(var f=new Fixture("standard",true)){
            for(String key:new String[]{"apiKey","modelRoute","permissions","network","maxSteps","delete"})
                assertThrows(IllegalArgumentException.class,()->f.port.validate(AgentPreferencePort.SET,args(key,"concise",2)));
            assertThrows(IllegalArgumentException.class,()->f.port.validate(AgentPreferencePort.SET,args("response_detail","fast",2)));
            assertThrows(IllegalArgumentException.class,()->f.port.validate(AgentPreferencePort.SET,args("operation_pace","detailed",2)));
            assertThrows(IllegalArgumentException.class,()->f.port.validate(AgentPreferencePort.SET,args("response_detail","concise",2).put("enabled",true)));
            JSONObject stale=args("response_detail","concise",2);String operation=f.journal().beginOperation(AgentPreferencePort.SET,stale);
            f.settings.saveAgentPreferences(true,"detailed","careful",2);
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.SET,prepared(stale,operation),f.control));
            assertEquals("detailed",f.settings.agentPreferences().getString("response_detail"));
            assertEquals("careful",f.settings.agentPreferences().getString("operation_pace"));
            f.settings.saveAgentPreferences(false,"detailed","careful",3);
            assertEquals(0,f.port.tools().length());assertFalse(f.port.supports(AgentPreferencePort.LIST));
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.LIST,new JSONObject(),f.control));
            assertThrows(SecurityException.class,()->f.port.execute(AgentPreferencePort.SET,prepared(args("response_detail","concise",4),operation),f.control));
            assertEquals(4,f.settings.agentPreferences().getLong("revision"));
        }
    }
    @Test public void carefulPauseUsesOriginalCancellationBeforeDownstreamEntry()throws Exception {
        try(var f=new Fixture("careful",true)){
            CountDownLatch enteredSleep=new CountDownLatch(1);
            f.control.setAuthorityCheck(enteredSleep::countDown);
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread worker=new Thread(()->{try{f.port.beforeTool("phone_click_node",new JSONObject(),f.control);}catch(Throwable stopped){failure.set(stopped);}},"preference-careful-cancel");
            worker.start();
            try {
                assertTrue("Original sleep checkpoint was reached",enteredSleep.await(2,TimeUnit.SECONDS));
                f.control.cancel();worker.join(2000);
                assertFalse("Cancellation must unblock the original pause",worker.isAlive());
                assertTrue(failure.get() instanceof InterruptedException);
                assertEquals("No downstream phone dispatch after cancellation",0,f.downstreamEntries.get());
            } finally { f.control.cancel();worker.interrupt();worker.join(2000); }
        }
    }
    private static JSONObject call(String id,String name,JSONObject args)throws Exception {
        return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()));
    }
    private static void respond(dev.pocketengine.testutil.HttpServer.Exchange exchange,JSONObject answer)throws Exception {
        byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
    }
    private static dev.pocketengine.runtime.context.ContextBudgetPolicy.Source budget(){
        return config->dev.pocketengine.runtime.context.ContextBudgetPolicy.RouteBudget.forRoute(config,
                new dev.pocketengine.runtime.context.ContextWindow.Budget(200000,4096,1000,4*1024*1024,100,2048),
                dev.pocketengine.runtime.context.ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
    }
    @Test public void realRunnerWritesPreparedPreferenceAndDelaysAllSevenDirectPhoneActions()throws Exception {
        try(var f=new Fixture("standard",true,true)){
            f.settings.saveAgentPreferences(true,"concise","standard",2);
            String goal="本次请详细说明真实观察，不按默认简洁偏好省略；只操作此测试手机，不提交业务。";
            String[] actions={"phone_launch_app","phone_click_node","phone_tap","phone_type","phone_swipe","phone_back","phone_home"};
            AtomicInteger rounds=new AtomicInteger(),observations=new AtomicInteger(),effects=new AtomicInteger();
            var ready=new java.util.concurrent.atomic.AtomicLong();
            java.util.List<String> actualActions=new java.util.ArrayList<>();
            java.util.List<Long> actualDelays=new java.util.ArrayList<>();
            dev.pocketengine.core.PhonePort phone=new dev.pocketengine.core.PhonePort(){
                public boolean isConnected(){return true;}
                public void clearReferences(){}
                public JSONObject observe(boolean image,RunControl original)throws Exception {
                    assertSame(f.control,original);original.check();
                    return new JSONObject().put("ok",true).put("observationId","pref-observation-"+observations.incrementAndGet())
                            .put("nodes",new JSONArray().put(new JSONObject().put("id","n0").put("text","fixture button")));
                }
                public JSONObject execute(String name,JSONObject supplied,RunControl original)throws Exception {
                    assertSame(f.control,original);original.check();actualActions.add(name);effects.incrementAndGet();
                    actualDelays.add(System.nanoTime()-ready.get());
                    return new JSONObject().put("ok",true).put("accepted",true).put("code","fixture_phone_action_done");
                }
            };
            AtomicReference<Throwable> problem=new AtomicReference<>();
            var server=dev.pocketengine.testutil.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                int round=rounds.incrementAndGet();JSONArray messages=request.getJSONArray("messages");int reference=-1,owner=-1;
                for(int i=0;i<messages.length();i++){
                    JSONObject row=messages.getJSONObject(i);String text=row.optString("content");
                    if(text.contains("主人回复详略偏好：concise")){reference=i;assertEquals("user",row.getString("role"));assertTrue(text.contains("不是主人新要求"));assertTrue(text.contains("主人本次明确要求优先"));}
                    if(text.equals(goal))owner=i;
                }
                assertTrue("Actual request must retain the owner instruction and label the default preference as subordinate reference data",reference>=0&&owner>=0);
                JSONObject answer=new JSONObject().put("role","assistant");
                if(round==1){
                    assertTrue(request.getJSONArray("tools").toString().contains(AgentPreferencePort.SET));
                    answer.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(call("set-pace",AgentPreferencePort.SET,args("operation_pace","careful",3))));
                }else if(round<=8){
                    String name=actions[round-2];JSONObject arguments=new JSONObject();
                    if(name.equals("phone_launch_app"))arguments.put("packageName","dev.pocketengine.fixture");
                    else arguments.put("observationId","pref-observation-"+observations.get());
                    if(name.equals("phone_click_node")||name.equals("phone_type"))arguments.put("nodeId","n0");
                    if(name.equals("phone_tap"))arguments.put("x",500).put("y",500);
                    if(name.equals("phone_type"))arguments.put("text","fixture text");
                    if(name.equals("phone_swipe"))arguments.put("x1",500).put("y1",800).put("x2",500).put("y2",200).put("durationMs",300);
                    answer.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(call("ordinary-"+round,name,arguments)));
                }else{assertEquals(9,round);answer.put("content","已按真实回执完成测试操作；没有真实业务或手机验收结论。");}
                respond(exchange,answer);
            }catch(Throwable failure){problem.compareAndSet(null,failure);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});
            server.start();
            try{
                var model=new dev.pocketengine.core.ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"explicit-loopback-fixture","preferences-fixture",false,20,10);
                var outcome=new dev.pocketengine.runtime.AgentRunner(phone,f.port,budget()).run(f.id,goal,model,f.control,event->{
                    if("tool".equals(event.optString("type"))&&"running".equals(event.optString("status"))&&java.util.Arrays.asList(actions).contains(event.optString("tool")))ready.set(System.nanoTime());
                    try{f.tasks.event(f.id,f.run,f.epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}
                },f.journal());
                if(problem.get()!=null)throw new AssertionError(problem.get());
                assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));
                assertEquals(java.util.Arrays.asList(actions),actualActions);assertEquals(7,effects.get());assertEquals(9,rounds.get());
                for(int i=0;i<actions.length;i++)assertTrue(actions[i]+" must pass the real careful pause before direct PhonePort.execute",actualDelays.get(i)>=200_000_000L);
                assertEquals("careful",f.settings.agentPreferences().getString("operation_pace"));assertEquals(4,f.settings.agentPreferences().getLong("revision"));
                JSONArray operations=f.tasks.get(f.id,false).getJSONArray("operations");assertEquals(8,operations.length());
                JSONObject preference=null;for(int i=0;i<operations.length();i++)if(AgentPreferencePort.SET.equals(operations.getJSONObject(i).getString("tool")))preference=operations.getJSONObject(i);
                assertNotNull(preference);assertEquals("receipt",preference.getString("state"));
                assertEquals("standard",preference.getJSONObject("receipt").getJSONObject("previous").getString("operation_pace"));
                assertEquals("careful",preference.getJSONObject("receipt").getJSONObject("current").getString("operation_pace"));
                assertFalse(f.tasks.hasUnresolved(f.id));
            }finally{server.stop(0);}
        }
    }
    @Test public void realRunnerCancellationInsideCarefulPausePreventsPhoneDispatch()throws Exception {
        try(var f=new Fixture("careful",true,true)){
            AtomicInteger effects=new AtomicInteger(),rounds=new AtomicInteger();CountDownLatch reachedSleep=new CountDownLatch(1);
            AtomicReference<Throwable> problem=new AtomicReference<>();
            f.control.setAuthorityCheck(()->{
                for(StackTraceElement frame:Thread.currentThread().getStackTrace())
                    if(frame.getClassName().equals(RunControl.class.getName())&&frame.getMethodName().equals("sleep"))reachedSleep.countDown();
            });
            dev.pocketengine.core.PhonePort phone=new dev.pocketengine.core.PhonePort(){
                public boolean isConnected(){return true;}public void clearReferences(){}
                public JSONObject observe(boolean image,RunControl original)throws Exception{return new JSONObject().put("ok",true).put("observationId","cancel-observation").put("nodes",new JSONArray().put(new JSONObject().put("id","n0")));}
                public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{effects.incrementAndGet();return new JSONObject().put("ok",true).put("accepted",true);}
            };
            var server=dev.pocketengine.testutil.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{rounds.incrementAndGet();respond(exchange,new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(call("cancel-click","phone_click_node",new JSONObject().put("observationId","cancel-observation").put("nodeId","n0")))));}catch(Throwable failure){problem.set(failure);}finally{exchange.close();}});
            server.start();java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{
                var model=new dev.pocketengine.core.ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"explicit-loopback-fixture","preferences-fixture",false,4,10);
                var future=worker.submit(()->new dev.pocketengine.runtime.AgentRunner(phone,f.port,budget()).run(f.id,"取消测试，不进行真实业务",model,f.control,event->{},f.journal()));
                assertTrue("Original Runner reached RunControl.sleep before dispatch",reachedSleep.await(5,TimeUnit.SECONDS));
                f.control.cancel();var outcome=future.get(5,TimeUnit.SECONDS);
                if(problem.get()!=null)throw new AssertionError(problem.get());
                assertEquals("cancelled",outcome.status);assertEquals(0,effects.get());assertEquals(1,rounds.get());
                assertEquals("No operation may be prepared after cancellation during beforeTool",0,f.tasks.get(f.id,false).getJSONArray("operations").length());
            }finally{f.control.cancel();worker.shutdownNow();server.stop(0);}
        }
    }
}
