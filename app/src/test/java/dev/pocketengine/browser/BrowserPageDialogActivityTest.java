package dev.pocketengine.browser;

import android.app.AlertDialog;
import android.os.Looper;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.webkit.WebView;
import android.widget.EditText;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual Activity/JsResult/Host/SQLite/Runner; the renderer DOM and model replies are controlled fixtures, not physical WebView rendering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=dev.pocketengine.EngineApplication.class,shadows={BrowserPageDialogActivityTest.NoUnrelatedStartup.class,BrowserPageDialogActivityTest.FrameworkJsResult.class,BrowserPageDialogActivityTest.FrameworkJsPromptResult.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserPageDialogActivityTest {
    @Implements(dev.pocketengine.EngineApplication.class) public static class NoUnrelatedStartup extends org.robolectric.shadows.ShadowApplication { @org.robolectric.annotation.Implementation protected void onCreate(){} }
    // Test runtime adaptation only: Robolectric's default JS-result shadows skip the actual
    // ResultReceiver. These empty shadows run the fixed SDK35 methods, not invented callbacks.
    @Implements(value=JsResult.class,callThroughByDefault=true) public static class FrameworkJsResult { }
    @Implements(value=JsPromptResult.class,callThroughByDefault=true) public static class FrameworkJsPromptResult { }
    static void click(android.view.View button){assertTrue(button.performClick());Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static JSONObject j(Object...p)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<p.length;i+=2)out.put((String)p[i],p[i+1]);return out;}
    static WebView web(BrowserActivity a)throws Exception{Field f=BrowserActivity.class.getDeclaredField("web");f.setAccessible(true);return (WebView)f.get(a);}
    static final class Reply {
        final JsResult handle;int calls;boolean confirmed;String prompt;Runnable onComplete=()->{};
        Reply(boolean text)throws Exception {
            Class<?> receiver=Class.forName("android.webkit.JsResult$ResultReceiver");
            Object callback=Proxy.newProxyInstance(receiver.getClassLoader(),new Class<?>[]{receiver},(proxy,method,args)->{
                if(method.getName().equals("onJsResultComplete")){calls++;confirmed=(Boolean)JsResult.class.getDeclaredMethod("getResult").invoke(args[0]);if(text)prompt=(String)JsPromptResult.class.getDeclaredMethod("getStringResult").invoke(args[0]);onComplete.run();return null;}
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return proxy==args[0];return "original-native-result-receiver-fixture";
            });
            Constructor<?> constructor=(text?JsPromptResult.class:JsResult.class).getDeclaredConstructor(receiver);constructor.setAccessible(true);handle=(JsResult)constructor.newInstance(callback);
        }
    }
    static final class F implements AutoCloseable {
        final org.robolectric.android.controller.ActivityController<BrowserActivity> activity;
        final BrowserActivity browser;final TaskStore tasks;final ExecutionResources resources=new ExecutionResources();
        final ExecutorService worker=Executors.newFixedThreadPool(2);final List<JSONObject> events=new CopyOnWriteArrayList<>();
        final BrowserModule module;final BrowserTaskHost host;final Set<Object> observedCallbacks=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> decisions=new HashSet<>();String taskId="";RunControl control;ModelCapabilityAdapters.Binding binding;RunJournal journal;BrowserTaskCapabilityPort port;
        F()throws Exception {
            BrowserActivity.setOwnerPanelInstaller(null);activity=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();browser=activity.get();browser.openForOwner("https://owner.example/original");tasks=new TaskStore(RuntimeEnvironment.getApplication());if(RuntimeEnvironment.getApplication() instanceof dev.pocketengine.EngineApplication)((dev.pocketengine.EngineApplication)RuntimeEnvironment.getApplication()).tasks=tasks;
            module=new BrowserModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,events::add));
            host=new BrowserTaskHost(module,tasks,resources,new BrowserTaskHost.OriginalTasks(){public void checkModelConfigured(){}public void launch(String id){taskId=id;}public RunControl currentControl(String id){return taskId.equals(id)?control:null;}},events::add);
            JSONObject selected=browser.snapshot();pump(worker.submit(()->host.startForOwner("处理网页提示后继续同一研究",selected,true,BrowserReadScope.forOwner(true,false,""))),true);
            JSONObject task=tasks.startAttempt(taskId,"capabilities",40);control=new RunControl();control.bindHostRun(taskId,task.getString("runId"),task.getInt("coordinatorEpoch"));tasks.status(taskId,"running","fixture admitted original native run");binding=new ModelCapabilityAdapters.Binding(taskId,task.getString("runId"),task.getInt("coordinatorEpoch"),control,true,"");journal=tasks.journal(taskId,binding.runId,binding.epoch,control);
            CapabilityPort base=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return false;}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("no alternate tool engine");}};
            port=(BrowserTaskCapabilityPort)pump(worker.submit(()->host.wrapIfRequested(binding,control,base)),true);
        }
        void approve()throws Exception {
            if(taskId.isEmpty())return;JSONArray rows=tasks.get(taskId,false).optJSONArray("approvals");
            for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String id=row.getString("id");if(row.optString("state").equals("pending")&&!decisions.contains(id)){JSONObject done=host.decideIfWaiting(taskId,id,true);if(done!=null)decisions.add(id);}}
        }
        void tick(boolean dom)throws Exception {
            Shadows.shadowOf(Looper.getMainLooper()).idle();approve();WebView page=web(browser);if(page==null)return;page.getWebViewClient().onPageFinished(page,page.getUrl());
            var shadow=Shadows.shadowOf(page);var callback=shadow.getLastEvaluatedJavascriptCallback();String script=shadow.getLastEvaluatedJavascript();
            if(dom&&callback!=null&&script!=null&&script.contains("elements:nodes.map")&&observedCallbacks.add(callback))callback.onReceiveValue(JSONObject.quote(j("url",page.getUrl(),"title","controlled page","text","fresh native DOM after page dialog","elements",new JSONArray().put(j("ref","n0","tag","BUTTON","type","button","text","continue","id","continue","name","","href",""))).toString()));
        }
        <T>T pump(Future<T> future,boolean dom)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!future.isDone()&&System.nanoTime()<end){tick(dom);Thread.sleep(3);}tick(dom);return future.get(2,TimeUnit.SECONDS);}
        JSONObject tool(String command,JSONObject args)throws Exception {
            String name="browser_task_"+command,op=journal.beginOperation(name,args);return pump(worker.submit(()->{JSONObject value=port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",op),control);journal.finishOperation(op,value);port.afterTool(name,args,value,control);return value;}),true);
        }
        String open()throws Exception {return tool("open",j("url","https://example.com/dialog")).getString("tabId");}
        JSONObject observe(String tab)throws Exception {return tool("observe",j("tabId",tab));}
        AlertDialog dialog()throws Exception {Field f=BrowserActivity.class.getDeclaredField("pageDialog");f.setAccessible(true);Object item=f.get(browser);assertNotNull(item);Field window=item.getClass().getDeclaredField("window");window.setAccessible(true);return (AlertDialog)window.get(item);}
        Future<JSONObject> click(String tab,String observed)throws Exception {
            JSONObject args=j("tabId",tab,"observationId",observed,"ref","n0");String op=journal.beginOperation("browser_task_click",args);Object previous=Shadows.shadowOf(web(browser)).getLastEvaluatedJavascriptCallback();Future<JSONObject> result=worker.submit(()->{JSONObject value=port.execute("browser_task_click",new JSONObject(args.toString()).put("_hostOperationId",op),control);journal.finishOperation(op,value);port.afterTool("browser_task_click",args,value,control);return value;});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(Shadows.shadowOf(web(browser)).getLastEvaluatedJavascriptCallback()==previous&&System.nanoTime()<end){tick(false);Thread.sleep(3);}assertNotSame(previous,Shadows.shadowOf(web(browser)).getLastEvaluatedJavascriptCallback());return result;
        }
        void actualDomActionCallback()throws Exception {Shadows.shadowOf(web(browser)).getLastEvaluatedJavascriptCallback().onReceiveValue(JSONObject.quote(j("accepted",true,"status","clicked","verified",false).toString()));}
        public void close()throws Exception {if(control!=null)control.cancel();for(int i=0;i<3;i++)Shadows.shadowOf(Looper.getMainLooper()).idle();try{pump(worker.submit(host::endForOwner),false);}catch(Exception ignored){}module.close();worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);activity.pause().stop().destroy();BrowserActivity.setEvents(null);BrowserActivity.setOwnerPanelInstaller(null);Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(tasks)).close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void realConfirmCallbackFinishesOriginalJournalBeforeNativeHandoffAndFreshSameTaskReturn()throws Exception {
        try(F f=new F()){
            String tab=f.open();JSONObject before=f.observe(tab);WebView page=web(f.browser);Future<JSONObject> click=f.click(tab,before.getString("observationId"));Reply reply=new Reply(false);
            assertTrue(page.getWebChromeClient().onJsConfirm(page,page.getUrl(),"untrusted page confirmation",reply.handle));assertEquals(0,reply.calls);assertFalse(click.isDone());assertFalse(f.control.isPaused());assertEquals("pending",f.browser.snapshot().getJSONObject("pageDialog").getString("status"));
            click(f.dialog().getButton(AlertDialog.BUTTON_POSITIVE));assertEquals(1,reply.calls);assertTrue(reply.confirmed);assertFalse("decision alone is not an original action receipt",click.isDone());assertFalse(f.control.isPaused());
            f.actualDomActionCallback();JSONObject receipt=f.pump(click,false);assertTrue(receipt.toString(),receipt.getBoolean("accepted"));assertTrue(f.control.isPaused());assertFalse(f.tasks.hasUnresolved(f.taskId));
            JSONArray operations=f.tasks.get(f.taskId,false).getJSONArray("operations");for(int i=0;i<operations.length();i++)assertEquals("receipt",operations.getJSONObject(i).getString("state"));
            JSONObject owner=f.browser.tabsForOwner().getJSONObject("taskSpace");assertEquals("owner_control",owner.getString("status"));JSONObject pin=owner.getJSONArray("handoffPages").getJSONObject(0);
            f.pump(f.worker.submit(()->f.host.returnForOwner(pin)),false);JSONObject fresh=f.pump(f.worker.submit(()->f.port.contextSnapshot(BrowserTaskCapabilityPort.OWNER_RETURN_CONTEXT)),true).getJSONObject("browserReturnObservation");
            assertSame(page,web(f.browser));assertEquals(f.taskId,fresh.getString("taskId"));assertEquals(f.binding.runId,fresh.getString("runId"));assertNotEquals(before.getString("observationId"),fresh.getString("observationId"));assertTrue(fresh.getString("text").contains("fresh native DOM"));assertFalse(f.control.isPaused());
            String all=f.tasks.get(f.taskId,false).getJSONArray("events").toString();assertTrue(all.contains("browser_page_dialog_pending"));assertTrue(all.contains("browser_page_dialog_resolved"));assertTrue(all.contains("browser_page_dialog_operation_observed"));assertFalse(all.contains("untrusted page confirmation"));
        }
    }
    @Test public void promptInputOnlyReachesOriginalJsPromptResultNotTaskEventsOrToolReceipt()throws Exception {
        try(F f=new F()){String tab=f.open();JSONObject read=f.observe(tab);WebView page=web(f.browser);Future<JSONObject> call=f.click(tab,read.getString("observationId"));Reply reply=new Reply(true);String secret="private-owner-prompt-canary-173947";
            assertTrue(page.getWebChromeClient().onJsPrompt(page,page.getUrl(),"private-page-message-canary","default-canary",(JsPromptResult)reply.handle));EditText input=f.dialog().getWindow().getDecorView().findViewWithTag("browser_page_prompt");assertNotNull(input);input.setText(secret);click(f.dialog().getButton(AlertDialog.BUTTON_POSITIVE));assertEquals(1,reply.calls);assertEquals(secret,reply.prompt);
            f.actualDomActionCallback();JSONObject receipt=f.pump(call,false);assertFalse(receipt.toString().contains(secret));JSONObject task=f.tasks.get(f.taskId,false);assertFalse(task.toString().contains(secret));assertFalse(task.toString().contains("default-canary"));assertFalse(task.toString().contains("private-page-message-canary"));assertFalse(f.events.toString().contains(secret));assertFalse(f.browser.snapshot().toString().contains(secret));
        }
    }
    @Test public void backgroundCancellationClosesOldJsHandleWithoutConfirmingOrClearingUnknownEffect()throws Exception {
        try(F f=new F()){String tab=f.open();JSONObject read=f.observe(tab);WebView page=web(f.browser);Future<JSONObject> call=f.click(tab,read.getString("observationId"));Reply reply=new Reply(false);page.getWebChromeClient().onJsConfirm(page,page.getUrl(),"page confirmation",reply.handle);AlertDialog old=f.dialog();f.activity.pause();assertEquals(1,reply.calls);assertFalse(reply.confirmed);assertFalse(old.isShowing());JSONObject unknown=f.pump(call,false);assertEquals("unknown",unknown.getString("status"));assertTrue(f.tasks.hasUnresolved(f.taskId));assertThrows(SecurityException.class,f.port::requireOwnerReturnReady);f.activity.resume();}
    }
    private static JSONObject returnedObservation(JSONArray messages)throws Exception {for(int i=messages.length()-1;i>=0;i--){JSONObject message=messages.getJSONObject(i);if(!"user".equals(message.optString("role")))continue;JSONArray parts=message.optJSONArray("content");for(int k=0;parts!=null&&k<parts.length();k++){String text=parts.getJSONObject(k).optString("text");int start=text.indexOf('{');if(start>=0&&text.contains("browser_owner_return_observation")){JSONObject value=new JSONObject(text.substring(start));if(value.optString("kind").equals("browser_owner_return_observation"))return value;}}}return null;}
    private static JSONObject call(String tool,JSONObject args)throws Exception {return j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",tool,"arguments",args.toString()))));}
    @Test public void originalRunnerWaitsForOwnerThenUsesDurableFreshObservationBeforeNextModelRequest()throws Exception {
        try(F f=new F()){
            java.util.concurrent.atomic.AtomicInteger step=new java.util.concurrent.atomic.AtomicInteger();AtomicReference<Throwable> failed=new AtomicReference<>();String[] tab={""},observed={""};List<JSONObject> requests=new CopyOnWriteArrayList<>();
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);JSONArray messages=request.getJSONArray("messages");JSONObject receipt=null;
                for(int k=messages.length()-1;k>=0;k--)if(messages.getJSONObject(k).optString("role").equals("tool")){receipt=new JSONObject(messages.getJSONObject(k).getString("content"));break;}
                JSONObject answer;switch(step.getAndIncrement()){
                    case 0:answer=call("browser_task_open",j("url","https://example.com/confirm-flow"));break;
                    case 1:assertNotNull(receipt);tab[0]=receipt.getString("tabId");answer=call("browser_task_observe",j("tabId",tab[0]));break;
                    case 2:assertNotNull(receipt);observed[0]=receipt.getString("observationId");answer=call("browser_task_click",j("tabId",tab[0],"observationId",observed[0],"ref","n0"));break;
                    case 3:
                        assertFalse(f.control.isPaused());JSONObject fresh=returnedObservation(messages);
                        assertNotNull("next real HTTP request requires the original native fresh-return observation",fresh);assertEquals(f.taskId,fresh.getString("taskId"));assertEquals(f.binding.runId,fresh.getString("runId"));assertNotEquals(observed[0],fresh.getString("observationId"));
                        assertTrue("same fresh body must already be durable before model request",f.journal.restore().getJSONArray("messages").toString().contains(fresh.getString("observationId")));answer=call("browser_task_finish",new JSONObject());break;
                    default:answer=j("role","assistant","content","已在原任务重新读取确认后的网页；网站业务结果仍待核实。");
                }
                byte[] data=j("choices",new JSONArray().put(j("message",answer,"finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
            }catch(Throwable error){failed.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            try{
                ModelConfig model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-dialog-model-key","dialog-original-runner",false,30,8);
                Future<RunOutcome> run=f.worker.submit(()->new AgentRunner(null,f.port).run(f.taskId,"处理网页提示并继续同一研究",model,f.control,event->{try{f.tasks.event(f.taskId,f.binding.runId,f.binding.epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},f.journal));
                boolean answered=false,returned=false;Reply reply=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!run.isDone()&&System.nanoTime()<end){f.tick(true);WebView page=web(f.browser);String script=page==null?null:Shadows.shadowOf(page).getLastEvaluatedJavascript();
                    if(!answered&&script!=null&&script.contains("e.click();")){
                        reply=new Reply(false);assertTrue(page.getWebChromeClient().onJsConfirm(page,page.getUrl(),"page-owner-confirmation-canary",reply.handle));assertEquals(3,requests.size());assertFalse(f.control.isPaused());click(f.dialog().getButton(AlertDialog.BUTTON_POSITIVE));assertEquals(1,reply.calls);assertTrue(reply.confirmed);assertEquals(3,requests.size());f.actualDomActionCallback();answered=true;
                    }
                    if(answered&&!returned&&f.control.isPaused()&&!f.tasks.hasUnresolved(f.taskId)){
                        assertEquals("no model continuation before original owner return",3,requests.size());JSONObject pin=f.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("handoffPages").getJSONObject(0);f.pump(f.worker.submit(()->f.host.returnForOwner(pin)),false);returned=true;
                    }
                    Thread.sleep(3);
                }
                RunOutcome outcome=f.pump(run,true);if(failed.get()!=null)throw new AssertionError("controlled model fixture",failed.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertTrue(answered);assertTrue(returned);assertEquals(1,reply.calls);assertEquals(5,requests.size());
                assertFalse(requests.toString().contains("page-owner-confirmation-canary"));JSONArray rows=f.tasks.get(f.taskId,false).getJSONArray("operations");int clicks=0;for(int k=0;k<rows.length();k++){assertEquals("receipt",rows.getJSONObject(k).getString("state"));if(rows.getJSONObject(k).getString("tool").equals("browser_task_click"))clicks++;}assertEquals(1,clicks);
            }finally{server.stop(0);}
        }
    }

    @Test public void navigationCancelsOldHandleAndOldNativeButtonsCannotAnswerNewDocumentDialog()throws Exception {
        try(F f=new F()){f.open();WebView page=web(f.browser);Reply oldReply=new Reply(false);page.getWebChromeClient().onJsConfirm(page,page.getUrl(),"old document",oldReply.handle);android.widget.Button oldButton=f.dialog().getButton(AlertDialog.BUTTON_POSITIVE);String oldId=f.browser.snapshot().getJSONObject("pageDialog").getString("dialogId");
            page.getWebViewClient().onPageStarted(page,page.getUrl(),null);page.getWebViewClient().onPageFinished(page,page.getUrl());assertEquals(1,oldReply.calls);assertFalse(oldReply.confirmed);
            Reply fresh=new Reply(false);page.getWebChromeClient().onJsConfirm(page,page.getUrl(),"new document",fresh.handle);assertNotEquals(oldId,f.browser.snapshot().getJSONObject("pageDialog").getString("dialogId"));click(oldButton);assertEquals(0,fresh.calls);click(f.dialog().getButton(AlertDialog.BUTTON_NEGATIVE));assertEquals(1,fresh.calls);assertFalse(fresh.confirmed);
        }
    }
    @Test public void actualSessionRecreationCancelsDialogAndCannotConfirmItsReplacementPage()throws Exception {
        try(F f=new F()){f.open();WebView before=web(f.browser);String session=f.browser.snapshot().getString("browserSessionId");Reply reply=new Reply(false);before.getWebChromeClient().onJsConfirm(before,before.getUrl(),"session-bound request",reply.handle);android.widget.Button stale=f.dialog().getButton(AlertDialog.BUTTON_POSITIVE);
            f.browser.profileChangedForOwner();assertEquals(1,reply.calls);assertFalse(reply.confirmed);assertNotSame(before,web(f.browser));assertNotEquals(session,f.browser.snapshot().getString("browserSessionId"));click(stale);assertEquals(1,reply.calls);assertTrue(f.control.isCancelled());
        }
    }
    @Test public void realCallbacksExplainUnsupportedPopupAndDenySitePermissionWithoutCreatingWindowOrGrant()throws Exception {
        try(F f=new F()){f.open();WebView page=web(f.browser);int pages=f.browser.tabsForOwner().getJSONArray("tabs").length();android.os.Message message=android.os.Message.obtain();try{assertFalse(page.getWebChromeClient().onCreateWindow(page,true,true,message));}finally{message.recycle();}assertEquals(pages,f.browser.tabsForOwner().getJSONArray("tabs").length());assertFalse(page.getSettings().supportMultipleWindows());assertEquals("popup_not_supported",f.browser.snapshot().getJSONObject("siteNotice").getString("reason"));
            int[] denied={0},granted={0};page.getWebChromeClient().onPermissionRequest(new android.webkit.PermissionRequest(){public android.net.Uri getOrigin(){return android.net.Uri.parse("https://example.com");}public String[] getResources(){return new String[]{RESOURCE_AUDIO_CAPTURE};}public void grant(String[] resources){granted[0]++;}public void deny(){denied[0]++;}});assertEquals(1,denied[0]);assertEquals(0,granted[0]);assertEquals("site_permission_denied",f.browser.snapshot().getJSONObject("siteNotice").getString("reason"));assertEquals("not_supported",f.browser.snapshot().getJSONObject("pageCapabilities").getString("oauthPopup"));
        }
    }

    @Test public void dialogArrivingOnlyAfterBackgroundCannotSilentlyResumeTheInFlightPageAction()throws Exception {
        try(F f=new F()){String tab=f.open();JSONObject read=f.observe(tab);WebView page=web(f.browser);Future<JSONObject> call=f.click(tab,read.getString("observationId"));f.activity.pause();Reply reply=new Reply(false);assertTrue(page.getWebChromeClient().onJsConfirm(page,page.getUrl(),"late background request",reply.handle));assertEquals(1,reply.calls);assertFalse(reply.confirmed);assertTrue(f.control.isPaused());JSONObject result=f.pump(call,false);assertEquals("unknown",result.getString("status"));assertTrue(f.tasks.hasUnresolved(f.taskId));f.activity.resume();}
    }

    @Test public void oneOriginalActionCanAskConfirmThenPromptWithoutOldCleanupOrButtonAnsweringTheNewDialog()throws Exception {
        try(F f=new F()){String tab=f.open();JSONObject read=f.observe(tab);WebView page=web(f.browser);Future<JSONObject> call=f.click(tab,read.getString("observationId"));Reply confirm=new Reply(false),prompt=new Reply(true);
            confirm.onComplete=()->assertTrue(page.getWebChromeClient().onJsPrompt(page,page.getUrl(),"second private request","",(JsPromptResult)prompt.handle));
            assertTrue(page.getWebChromeClient().onJsConfirm(page,page.getUrl(),"first private request",confirm.handle));AlertDialog old=f.dialog();android.widget.Button oldButton=old.getButton(AlertDialog.BUTTON_POSITIVE);String first=f.browser.snapshot().getJSONObject("pageDialog").getString("dialogId");click(oldButton);assertEquals(1,confirm.calls);assertTrue(confirm.confirmed);assertFalse(old.isShowing());assertFalse(call.isDone());assertFalse(f.control.isPaused());
            AlertDialog next=f.dialog();assertTrue(next.isShowing());assertNotEquals(first,f.browser.snapshot().getJSONObject("pageDialog").getString("dialogId"));assertEquals("pending",f.browser.snapshot().getJSONObject("pageDialog").getString("status"));click(oldButton);assertEquals(0,prompt.calls);EditText input=next.getWindow().getDecorView().findViewWithTag("browser_page_prompt");input.setText("sequential-private-input");click(next.getButton(AlertDialog.BUTTON_POSITIVE));assertEquals(1,prompt.calls);assertEquals("sequential-private-input",prompt.prompt);assertFalse(call.isDone());f.actualDomActionCallback();JSONObject result=f.pump(call,false);assertTrue(result.getBoolean("accepted"));assertTrue(f.control.isPaused());assertFalse(f.tasks.hasUnresolved(f.taskId));String saved=f.tasks.get(f.taskId,false).toString();assertFalse(saved.contains("sequential-private-input"));assertFalse(saved.contains("first private request"));assertFalse(saved.contains("second private request"));
        }
    }

}
