package dev.pocketengine.browser;

import android.app.Application;
import android.os.Looper;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.phone.observation.AdaptivePhonePort;
import dev.pocketengine.phone.observation.ObservationReport;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual BrowserTaskHost/Activity/SQLite + original phone adapter. Phone nodes are explicit
 * fixtures; no claim of device focus, WebView pixels, or third-party App compatibility. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=dev.pocketengine.EngineApplication.class,shadows=PhoneBrowserSurfaceJourneyTest.NoUnrelatedStartup.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneBrowserSurfaceJourneyTest {
    @org.robolectric.annotation.Implements(dev.pocketengine.EngineApplication.class) public static class NoUnrelatedStartup extends org.robolectric.shadows.ShadowApplication { @org.robolectric.annotation.Implementation protected void onCreate(){} }
    static JSONObject j(Object... pairs)throws Exception {JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    static final class F implements AutoCloseable {
        final org.robolectric.android.controller.ActivityController<BrowserActivity> activity;
        final BrowserActivity browser;final TaskStore tasks;final BrowserModule module;final BrowserTaskHost host;
        final ExecutionResources resources=new ExecutionResources();final RunControl control=new RunControl();
        final ExecutorService worker=Executors.newSingleThreadExecutor();final String task,run,phoneToken="original-phone-input";final int epoch;
        final AtomicInteger phoneEffects=new AtomicInteger(),reads=new AtomicInteger();final AdaptivePhonePort phone;final CapabilityPort port;final RunJournal journal;
        boolean failRelease;
        F()throws Exception {
            BrowserActivity.setOwnerPanelInstaller(null);activity=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();browser=activity.get();browser.openForOwner("https://owner.example/original");
            tasks=new TaskStore(RuntimeEnvironment.getApplication());((dev.pocketengine.EngineApplication)RuntimeEnvironment.getApplication()).tasks=tasks;JSONObject initial=tasks.create("手机取资料，网页研究，再回原App","main_screen");task=initial.getString("id");JSONObject attempt=tasks.startAttempt(task,"main_screen",40);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);tasks.status(task,"running","fixture original phone run");assertTrue(resources.acquirePhone(phoneToken));
            PhonePort physical=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){}public JSONObject execute(String name,JSONObject args,RunControl c)throws Exception{phoneEffects.incrementAndGet();return j("ok",true,"accepted",true,"verified",false);}public JSONObject observe(boolean image,RunControl c)throws Exception{return ObservationReport.attach(j("ok",true,"observationId","phone-"+reads.incrementAndGet(),"displayId",0,"sessionId","original-screen","observedAtUptimeMs",1000,"pauseRevision",c.pauseRevision(),"truncated",false,"nodes",new JSONArray().put(j("id","n0","text","继续","clickable",true,"enabled",true))),true,"accessibility_tree",false,"not_requested");}};
            phone=new AdaptivePhonePort(physical,new AdaptivePhonePort.Scope(task,run,epoch,"original-screen",0),false,false,()->1000);
            module=new BrowserModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));
            host=new BrowserTaskHost(module,tasks,resources,new BrowserTaskHost.OriginalTasks(){
                public void checkModelConfigured(){}public void launch(String id){throw new AssertionError("surface switch must never launch another task/run");}
                public RunControl currentControl(String id){return task.equals(id)?control:null;}
                public void releasePhoneInput(String id,RunControl c)throws Exception {assertSame(control,c);assertEquals(task,id);assertEquals("transition",c.executionSurface());if(failRelease)throw new IllegalStateException("physical release result unknown");resources.releasePhone(phoneToken);assertFalse(resources.ownsPhone(phoneToken));}
                public void acquirePhoneInput(String id,RunControl c){assertSame(control,c);assertEquals(task,id);assertTrue(resources.acquirePhone(phoneToken));}
            },event->{});
            CapabilityPort base=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return false;}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError(n);}};
            ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(task,run,epoch,control,true,"");port=host.wrapIfRequested(binding,control,base,null,phone);tasks.authorizeTools(task,run,new JSONArray().put("task_surface_switch"));journal=tasks.journal(task,run,epoch,control);
        }
        <T>T pump(Future<T> future)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!future.isDone()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(3);}Shadows.shadowOf(Looper.getMainLooper()).idle();return future.get(1,TimeUnit.SECONDS);}
        void consent()throws Exception {JSONObject profile=browser.snapshot();pump(worker.submit(()->host.allowExistingForOwner(task,profile,true)));}
        JSONObject change(String target)throws Exception {return pump(worker.submit(()->{JSONObject args=j("surface",target);assertNull(port.beforeTool("task_surface_switch",args,control));String op=journal.beginOperation("task_surface_switch",args);JSONObject result=port.execute("task_surface_switch",new JSONObject(args.toString()).put("_hostOperationId",op),control);journal.finishOperation(op,result);return result;}));}
        public void close()throws Exception {control.cancel();port.close();for(int i=0;i<3;i++)Shadows.shadowOf(Looper.getMainLooper()).idle();module.close();worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);activity.pause().stop().destroy();BrowserActivity.setEvents(null);BrowserActivity.setOwnerPanelInstaller(null);java.lang.reflect.Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(tasks)).close();}
    }
    @Test public void originalPhoneTaskSwitchesBrowserAndBackWithFreshObservationAndNoSecondExecutor()throws Exception {try(F f=new F()){
        JSONObject schema=f.port.tools().getJSONObject(0).getJSONObject("function");assertEquals("surface",schema.getJSONObject("parameters").getJSONArray("required").getString(0));
        JSONObject before=f.phone.observe(false,f.control);f.consent();JSONObject browser=f.change("browser");assertEquals("switched",browser.getString("status"));assertEquals(f.run,browser.getString("runId"));assertFalse(f.resources.ownsPhone(f.phoneToken));assertTrue(f.resources.ownsBrowser(f.task+":"+f.run+":"+f.epoch));assertTrue(f.port.supports("browser_task_open"));
        assertEquals("phone_surface_inactive",f.phone.execute("phone_click_node",j("observationId",before.getString("observationId"),"nodeId","n0"),f.control).optString("error"));assertEquals(0,f.phoneEffects.get());
        JSONObject returned=f.change("phone");assertEquals(f.task,returned.getString("taskId"));assertEquals(f.run,returned.getString("runId"));assertTrue(f.resources.ownsPhone(f.phoneToken));assertFalse(f.resources.ownsBrowser(f.task+":"+f.run+":"+f.epoch));assertFalse(f.port.supports("browser_task_open"));
        assertEquals("stale_observation",f.phone.execute("phone_click_node",j("observationId",before.getString("observationId"),"nodeId","n0"),f.control).optString("error"));JSONObject after=f.phone.observe(false,f.control);assertNotEquals(before.getString("observationId"),after.getString("observationId"));assertTrue(f.phone.execute("phone_click_node",j("observationId",after.getString("observationId"),"nodeId","n0"),f.control).getBoolean("accepted"));assertEquals(1,f.phoneEffects.get());
        JSONObject stored=f.tasks.get(f.task,false);assertEquals(f.run,stored.getString("runId"));assertEquals(f.epoch,stored.getInt("coordinatorEpoch"));assertTrue(stored.getJSONArray("events").toString().contains("task_surface_transition"));assertFalse(f.tasks.hasUnresolved(f.task));
    }}
    @Test public void modelCannotSupplyOwnerConsentOrSwitchWhileOwnerPaused()throws Exception {try(F f=new F()){
        assertEquals("browser_owner_consent_required",f.change("browser").getString("code"));assertTrue(f.resources.ownsPhone(f.phoneToken));assertTrue(f.control.phoneSurfaceActive());f.consent();f.control.pause();assertThrows(ExecutionException.class,()->f.change("browser"));assertTrue(f.resources.ownsPhone(f.phoneToken));assertEquals(0,f.phoneEffects.get());
    }}
    @Test public void unknownPhoneReleaseDoesNotAcquireBrowserOrRestoreOldRefs()throws Exception {try(F f=new F()){
        f.phone.observe(false,f.control);f.consent();f.failRelease=true;assertThrows(ExecutionException.class,()->f.change("browser"));assertTrue(f.control.isPaused());assertEquals("transition",f.control.executionSurface());assertFalse(f.resources.ownsBrowser(f.task+":"+f.run+":"+f.epoch));assertEquals(0,f.phoneEffects.get());
    }}
    @Test public void realRunnerModelToolsKeepSameRunAndDiscardOldBatchAcrossBothSurfaces()throws Exception {try(F f=new F()){
        f.consent();AtomicInteger modelCalls=new AtomicInteger();java.util.concurrent.atomic.AtomicReference<Throwable> problem=new java.util.concurrent.atomic.AtomicReference<>();
        dev.pocketengine.testutil.HttpServer server=dev.pocketengine.testutil.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));int round=modelCalls.incrementAndGet();JSONObject answer;
            if(round==1){assertTrue(request.getJSONArray("tools").toString().contains("phone_click_node"));answer=j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(call("switch-web","task_surface_switch",j("surface","browser"))).put(call("old-phone","phone_click_node",j("observationId","phone-1","nodeId","n0"))));}
            else if(round==2){String tools=request.getJSONArray("tools").toString();assertTrue(tools.contains("browser_task_open"));assertFalse(tools.contains("phone_click_node"));assertTrue(request.getJSONArray("messages").toString().contains("stale_surface"));answer=j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(call("switch-phone","task_surface_switch",j("surface","phone"))));}
            else if(round==3){assertTrue(request.getJSONArray("tools").toString().contains("phone_click_node"));assertFalse(request.getJSONArray("tools").toString().contains("browser_task_open"));assertTrue(f.reads.get()>=2);answer=j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(call("fresh-phone","phone_click_node",j("observationId","phone-"+f.reads.get(),"nodeId","n0"))));}
            else{assertEquals(4,round);answer=j("role","assistant","content","已切回手机并依据新观察点击；真实业务结果仍待主人核对。");}
            byte[] bytes=j("choices",new JSONArray().put(j("message",answer,"finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Throwable error){problem.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        try{ModelConfig model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"explicit-local-fixture-key","surface-fixture",false,20,10);
            dev.pocketengine.runtime.context.ContextBudgetPolicy.Source budget=config->dev.pocketengine.runtime.context.ContextBudgetPolicy.RouteBudget.forRoute(config,new dev.pocketengine.runtime.context.ContextWindow.Budget(200000,4096,1000,4*1024*1024,100,2048),dev.pocketengine.runtime.context.ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            RunOutcome outcome=f.pump(f.worker.submit(()->new dev.pocketengine.runtime.AgentRunner(f.phone,f.port,budget).run(f.task,"切网页再回手机，不提交业务",model,f.control,event->{try{f.tasks.event(f.task,f.run,f.epoch,event);}catch(Exception unavailable){throw new IllegalStateException(unavailable);}},f.journal)));
            if(problem.get()!=null)throw new AssertionError(problem.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertEquals(4,modelCalls.get());assertEquals(1,f.phoneEffects.get());assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));assertFalse(f.tasks.hasUnresolved(f.task));
        }finally{server.stop(0);}
    }}
    private static JSONObject call(String id,String name,JSONObject args)throws Exception{return j("id",id,"type","function","function",j("name",name,"arguments",args.toString()));}
    @Test public void directUnjournaledOrChangedSurfaceArgumentsCannotDispatch()throws Exception {try(F f=new F()){
        f.consent();assertThrows(ExecutionException.class,()->f.pump(f.worker.submit(()->f.port.execute("task_surface_switch",j("surface","browser","_hostOperationId","invented"),f.control))));assertTrue(f.resources.ownsPhone(f.phoneToken));
        String op=f.journal.beginOperation("task_surface_switch",j("surface","phone"));assertThrows(ExecutionException.class,()->f.pump(f.worker.submit(()->f.port.execute("task_surface_switch",j("surface","browser","_hostOperationId",op),f.control))));assertTrue(f.control.phoneSurfaceActive());assertTrue(f.resources.ownsPhone(f.phoneToken));
    }}
    @Test public void cancelDuringBrowserNeverReacquiresPhone()throws Exception {try(F f=new F()){
        f.consent();f.change("browser");f.control.cancel();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(f.resources.ownsPhone(f.phoneToken));assertFalse(f.resources.ownsBrowser(f.task+":"+f.run+":"+f.epoch));assertThrows(ExecutionException.class,()->f.change("phone"));assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));
    }}
}
