package dev.pocketengine.browser;

import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.memory.TaskMemoryJournal;
import dev.pocketengine.features.memory.TaskMemoryUse;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual App/AgentRunner/ModelGateway HTTP/TaskStore/Activity. JS and keystore are explicit fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class BrowserTaskHostJourneyTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("BrowserJourneyKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    static JSONObject j(Object... pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static WebView view(BrowserActivity activity)throws Exception {Field f=BrowserActivity.class.getDeclaredField("web");f.setAccessible(true);return (WebView)f.get(activity);}
    interface Check {boolean done()throws Exception;}
    final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final ExecutorService ownerWorker=Executors.newSingleThreadExecutor();
        final org.robolectric.android.controller.ActivityController<BrowserActivity> activity;
        final BrowserActivity browser;final WebView owner;final String ownerTab;
        final HttpServer server;final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        final List<JSONObject> requests=new CopyOnWriteArrayList<>();
        final Set<Object> callbacks=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> decisions=new HashSet<>();
        final List<String> tabs=new ArrayList<>();final Map<String,String> observations=new HashMap<>();
        final CountDownLatch firstRequest=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        final CountDownLatch finishHeld=new CountDownLatch(1),releaseFinish=new CountDownLatch(1);
        final AtomicReference<String> downloadCookie=new AtomicReference<>();
        final boolean existing,holdApproval,loginHandoff;volatile boolean autoApprove=true,holdFinish,scopedReads;
        volatile String taskId="",runId="",downloadId="",previous="",lastToolCall="";
        volatile RunControl original;volatile JSONObject downloadReceipt;volatile int stage,typed,polls;
        volatile JSONObject nativeReturnObservation;volatile long preLoginGeneration;volatile int postReturnDomReads;
        JSONObject previousArgs=new JSONObject();
        final String video="https://www.youtube.com/watch?v=retained-video&t=30#detail";
        final String scholar="https://scholar.google.com/scholar?cluster=retained-cluster&hl=zh-CN#result";
        Fixture(boolean existing,boolean holdApproval)throws Exception {this(existing,holdApproval,false);}
        Fixture(boolean existing,boolean holdApproval,boolean loginHandoff)throws Exception {
            this.existing=existing;this.holdApproval=holdApproval;this.loginHandoff=loginHandoff;autoApprove=!holdApproval;
            // Resource linking/Compose rendering is outside this JVM fixture. This is the real typed panel host.
            BrowserActivity.setOwnerPanelInstaller(null);
            activity=Robolectric.buildActivity(BrowserActivity.class).create().start().resume();browser=activity.get();
            browser.openForOwner("https://owner.example/already-open");owner=view(browser);ownerTab=browser.tabsForOwner().getJSONArray("tabs").getJSONObject(0).getString("tabId");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/file",exchange->{try{downloadCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));byte[] bytes="downloaded research file".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/plain");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});
            server.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);firstRequest.countDown();if(this.existing&&requests.size()==1)assertTrue(releaseFirst.await(8,TimeUnit.SECONDS));JSONObject answer=next(request);if(holdFinish&&"browser_task_finish".equals(previous)){finishHeld.countDown();assertTrue(releaseFinish.await(8,TimeUnit.SECONDS));}byte[] bytes=j("choices",new JSONArray().put(j("message",answer,"finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Throwable failure){serverFailure.compareAndSet(null,failure);}finally{exchange.close();}});
            server.start();
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("provider","openai_chat").putString("model","browser-journey-fixture").putInt("maxSteps",40).putInt("maxOutputTokens",2048).putBoolean("sendScreenshots",false).commit();
            CookieManager.getInstance().setCookie(fileUrl(),"session=original-profile");
        }
        String fileUrl(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/file";}
        boolean hasBrowserTools(JSONObject request)throws Exception {JSONArray tools=request.optJSONArray("tools");for(int i=0;tools!=null&&i<tools.length();i++)if(tools.getJSONObject(i).getJSONObject("function").getString("name").equals("browser_task_open"))return true;return false;}
        JSONObject next(JSONObject request)throws Exception {
            if(existing&&requests.size()==1){assertFalse(hasBrowserTools(request));return call("attachments_list",new JSONObject());}
            JSONArray messages=request.getJSONArray("messages");
            for(int i=messages.length()-1;i>=0;i--){JSONObject message=messages.getJSONObject(i);if(!"tool".equals(message.optString("role")))continue;String id=message.getString("tool_call_id");if(!id.equals(lastToolCall)){lastToolCall=id;JSONObject receipt=new JSONObject(message.getString("content"));if(previous.startsWith("browser_task_")){assertFalse(receipt.toString(),receipt.optString("status").equals("unknown"));assertFalse(receipt.toString(),receipt.optString("code").equals("approval_required"));if(previous.endsWith("open")){assertTrue(receipt.toString(),receipt.optBoolean("ok"));tabs.add(receipt.getString("tabId"));}if(previous.endsWith("observe")){assertEquals(receipt.toString(),"observed",receipt.optString("status"));observations.put(previousArgs.getString("tabId"),receipt.getString("observationId"));if(loginHandoff&&stage<=2)preLoginGeneration=receipt.getLong("tabGeneration");}if(previous.endsWith("retain_source"))assertTrue(receipt.toString(),receipt.getBoolean("sourceRetained"));if(previous.endsWith("download"))downloadId=receipt.getString("id");if(previous.endsWith("download_status"))downloadReceipt=receipt;}}break;}
            if(loginHandoff){
                if(stage<5)assertTrue("original login journey lacks browser tools",hasBrowserTools(request));
                switch(stage++){
                    case 0:return call("browser_task_open",j("url","https://account.example/login"));
                    case 1:return observe(0);
                    case 2:assertTrue("model must have the explicit owner handoff route",request.getJSONArray("tools").toString().contains("browser_task_request_user"));assertEmptyExtensionWrapperAndRealAdapter();return call("browser_task_request_user",j("tabId",tabs.get(0),"reason","请在当前网页完成登录，完成后交还此页面继续原研究。"));
                    case 3:assertFalse(original.isPaused());JSONObject actual=ownerReturnObservation(messages);assertNotNull("The next HTTP request must already contain a real native post-login observation; the model does not call observe",actual);assertEquals("observed",actual.getString("status"));assertEquals(taskId,actual.getString("taskId"));assertEquals(runId,actual.getString("runId"));assertEquals(1,actual.getInt("epoch"));assertEquals(tabs.get(0),actual.getString("tabId"));assertNotEquals(preLoginGeneration,actual.getLong("tabGeneration"));assertEquals("https://account.example/workspace?view=original#saved",actual.getString("url"));assertEquals(BrowserUploads.destinationIdentity(actual.getString("url")).getString("destinationDigest"),actual.getString("pageIdentityDigest"));assertTrue(actual.getString("text").contains("return-document-2026"));assertTrue(actual.getBoolean("freshObservationAvailable"));assertTrue(postReturnDomReads>0);JSONObject durable=ownerReturnObservation(app.tasks.journal(taskId,runId,1,original).restore().getJSONArray("messages"));assertNotNull("The exact fresh page body must already be durable before the HTTP request",durable);for(var keys=actual.keys();keys.hasNext();){String key=keys.next();assertEquals(key,String.valueOf(actual.get(key)),String.valueOf(durable.opt(key)));}nativeReturnObservation=actual;observations.put(tabs.get(0),actual.getString("observationId"));return retain(0);
                    case 4:return call("browser_task_finish",new JSONObject());
                    default:assertFalse(hasBrowserTools(request));return j("role","assistant","content","已在原任务中重新读取登录后的页面并保留来源，网页登录结果仍需主人核对。");
                }
            }
            if(stage<19)assertTrue("original model turn lacks browser tools",hasBrowserTools(request));
            switch(stage++){
                case 0:return call("browser_task_open",j("url",video));
                case 1:return call("browser_task_wait",j("tabId",tabs.get(0),"condition","load"));
                case 2:return observe(0);
                case 3:return call("browser_task_type",j("tabId",tabs.get(0),"observationId",observations.get(tabs.get(0)),"ref","n0","text","研究关键字"));
                case 4:return call("browser_task_wait",j("tabId",tabs.get(0),"condition","text","value","研究关键字"));
                case 5:return observe(0);
                case 6:return retain(0);
                case 7:return call("browser_task_open",j("url",scholar));
                case 8:return call("browser_task_wait",j("tabId",tabs.get(1),"condition","url","value",scholar));
                case 9:return observe(1);
                case 10:return retain(1);
                case 11:return call("browser_task_open",j("url","https://docs.example/reference?edition=2026#evidence"));
                case 12:return call("browser_task_wait",j("tabId",tabs.get(2),"condition","load"));
                case 13:return observe(2);
                case 14:return retain(2);
                case 15:return observe(0);
                case 16:return call("browser_task_download",j("tabId",tabs.get(0),"observationId",observations.get(tabs.get(0)),"url",fileUrl()));
                case 17:if(downloadReceipt==null||!"downloaded".equals(downloadReceipt.optString("status"))){assertTrue("download never settled",++polls<12);stage=17;return call("browser_task_download_status",j("downloadId",downloadId));}stage=19;return call("browser_task_finish",new JSONObject());
                default:assertFalse("ended space still offers browser actions",hasBrowserTools(request));return j("role","assistant","content","已比较三个网页来源，保留三份原文依据与下载资料；结论仍需主人核对。");
            }
        }
        JSONObject observe(int index)throws Exception{return call("browser_task_observe",j("tabId",tabs.get(index)));}
        JSONObject retain(int index)throws Exception{return call("browser_task_retain_source",j("tabId",tabs.get(index),"observationId",observations.get(tabs.get(index)),"excerpt","研究关键字 · 证据摘录"));}
        JSONObject call(String name,JSONObject args)throws Exception {previous=name;previousArgs=args;return j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",name,"arguments",args.toString()))));}
        JSONObject ownerReturnObservation(JSONArray messages)throws Exception {for(int i=messages.length()-1;i>=0;i--){JSONObject message=messages.getJSONObject(i);if(!"user".equals(message.optString("role")))continue;JSONArray content=message.optJSONArray("content");for(int j=0;content!=null&&j<content.length();j++){String text=content.getJSONObject(j).optString("text");int start=text.indexOf('{');if(start<0||!text.contains("browser_owner_return_observation"))continue;JSONObject observation=new JSONObject(text.substring(start));if("browser_owner_return_observation".equals(observation.optString("kind")))return observation;}}return null;}
        void assertEmptyExtensionWrapperAndRealAdapter()throws Exception {assertNotNull(app.pluginHost);Field owned=dev.pocketengine.features.NativePluginHost.class.getDeclaredField("extensions");owned.setAccessible(true);Object host=owned.get(app.pluginHost);Field live=host.getClass().getDeclaredField("live");live.setAccessible(true);Set<?> wrappers=(Set<?>)live.get(host);assertEquals(1,wrappers.size());Object wrapper=wrappers.iterator().next();assertEquals("dev.pocketengine.runtime.extensions.ExtensionCapabilityPort",wrapper.getClass().getName());Field mounted=wrapper.getClass().getDeclaredField("mounted");mounted.setAccessible(true);assertTrue(((Map<?,?>)mounted.get(wrapper)).isEmpty());Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<Object> pending=new ArrayDeque<>();pending.add(wrapper);boolean adapter=false;while(!pending.isEmpty()){Object port=pending.removeFirst();if(!visited.add(port))continue;if(port.getClass().getName().equals("dev.pocketengine.features.capabilities.ModelCapabilityAdapters"))adapter=true;for(Field field:port.getClass().getDeclaredFields())if(dev.pocketengine.core.CapabilityPort.class.isAssignableFrom(field.getType())){field.setAccessible(true);Object next=field.get(port);if(next!=null)pending.add(next);}}assertTrue("Real ModelCapabilityAdapters authorization must remain under the empty extension wrapper",adapter);}
        @SuppressWarnings("unchecked") RunControl current()throws Exception {Field field=EngineApplication.class.getDeclaredField("capabilityControls");field.setAccessible(true);return ((Map<String,RunControl>)field.get(app)).get(taskId);}
        void start()throws Exception {
            JSONObject pinned=browser.snapshot();
            if(existing){taskId=app.tasks.createCapabilityTask("原助手任务继续完成三来源浏览研究","").getString("id");owner(()->app.dispatchOwnerAction("resumeTask",j("taskId",taskId)));await(()->firstRequest.getCount()==0);original=current();assertNotNull(original);runId=app.tasks.get(taskId,false).getString("runId");JSONObject admitted=owner(()->scopedReads?app.browserTaskHost.allowExistingForOwner(taskId,pinned,true,BrowserReadScope.forOwner(true,true,"")):app.browserTaskHost.allowExistingForOwner(taskId,pinned,true));assertEquals("pending_original_turn",admitted.getString("status"));assertEquals(1,app.tasks.list().length());assertSame(original,current());releaseFirst.countDown();}
            else{JSONObject task=owner(()->scopedReads?app.browserTaskHost.startForOwner("比较三类网页来源，填写查询，保留证据与下载资料",pinned,true,BrowserReadScope.forOwner(true,true,"")):app.browserTaskHost.startForOwner("比较三类网页来源，填写查询，保留证据与下载资料",pinned,true));taskId=task.getString("id");await(()->current()!=null);original=current();runId=app.tasks.get(taskId,false).getString("runId");}
        }
        JSONObject owner(Callable<JSONObject> action)throws Exception {Future<JSONObject> result=ownerWorker.submit(action);await(()->result.isDone());return result.get(1,TimeUnit.SECONDS);}
        void pump()throws Exception {
            if(serverFailure.get()!=null)throw new AssertionError("HTTP fixture failed",serverFailure.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();WebView selected=view(browser);
            if(selected!=null){selected.getWebViewClient().onPageFinished(selected,selected.getUrl());var shadow=Shadows.shadowOf(selected);var callback=shadow.getLastEvaluatedJavascriptCallback();String script=shadow.getLastEvaluatedJavascript();if(callback!=null&&script!=null&&callbacks.add(callback)){
                if(script.contains("elements:nodes.map")){boolean returned=loginHandoff&&selected.getUrl().startsWith("https://account.example/workspace");if(returned)postReturnDomReads++;callback.onReceiveValue(JSONObject.quote(j("url",selected.getUrl(),"title","实际宿主中的网页回调 fixture","text",returned?"return-document-2026 · 研究关键字 · 证据摘录：交还后的实际新文档":"研究关键字 · 证据摘录：三个页面的观察文本","elements",new JSONArray().put(j("ref","n0","tag","INPUT","type","text","text","查询输入","href",""))).toString()));}
                else if(script.contains("__pw_task_wait"))callback.onReceiveValue(JSONObject.quote(j("matched",true).toString()));
                else {if(script.contains("研究关键字"))typed++;callback.onReceiveValue(JSONObject.quote(j("accepted",true,"verified",false).toString()));}
            }}
            if(!taskId.isEmpty()&&autoApprove){JSONObject task=app.tasks.get(taskId,false);JSONArray approvals=task.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);String id=approval.getString("id");if("pending".equals(approval.optString("state"))&&decisions.add(id)){RunControl actual=current();assertNotNull(actual);if(original!=null)assertSame(original,actual);ownerWorker.submit(()->app.dispatchOwnerAction("approveCapability",j("taskId",taskId,"approvalId",id,"approved",true)));}}}
            Thread.sleep(4);
        }
        void await(Check check)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!check.done()&&System.nanoTime()<until)pump();pump();assertTrue("missing browser host condition; task="+(taskId.isEmpty()?"not-created":app.tasks.get(taskId,false)),check.done());}
        void completed()throws Exception {await(()->{JSONObject task=app.tasks.get(taskId,false);return task.optString("status").matches("completed|needs_confirmation|failed|cancelled|reconciling")&&current()==null;});assertTrue(app.tasks.get(taskId,false).toString(),app.tasks.get(taskId,false).optString("status").matches("completed|needs_confirmation"));}
        public void close()throws Exception {releaseFirst.countDown();releaseFinish.countDown();app.browserTaskHost.cancelForOwner();Shadows.shadowOf(Looper.getMainLooper()).idle();ownerWorker.shutdownNow();Field workers=EngineApplication.class.getDeclaredField("capabilityWorkers");workers.setAccessible(true);((ExecutorService)workers.get(app)).shutdownNow();app.features.close();server.stop(0);BrowserDownloads.closeFor(browser);activity.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();BrowserActivity.setOwnerPanelInstaller(null);java.security.Security.removeProvider("BrowserJourneyKeyStore");ownerWorker.awaitTermination(2,TimeUnit.SECONDS);}
    }
    private boolean assertNativeReference(JSONObject task,JSONObject operation,String runId)throws Exception {
        if(!TaskMemoryJournal.TOOL.equals(operation.getString("tool")))return false;
        // The original native model-context proof is not a browser dispatch or a tool approval.
        assertEquals(TaskMemoryJournal.PROVIDED,operation.getString("state"));JSONObject receipt=operation.getJSONObject("receipt");
        TaskMemoryUse.ReceiptRef reference=TaskMemoryJournal.receiptRef(task,runId,receipt.getString("requestId"),receipt.getString("answerId"));assertNotNull(reference);
        TaskMemoryUse.Provided provided=TaskMemoryJournal.readProvided(task,reference);assertNotNull(provided);assertFalse(provided.revoked);
        assertFalse(receipt.getBoolean("executionAuthorized"));assertFalse(receipt.getBoolean("modelUseVerified"));assertFalse(receipt.has("readAuthorization"));assertFalse(receipt.has("approvalId"));return true;
    }
    private void assertJourney(Fixture f)throws Exception {
        f.start();f.completed();JSONObject task=f.app.tasks.get(f.taskId,false);assertEquals("assistant",task.getString("kind"));assertEquals(1,task.getInt("coordinatorEpoch"));assertEquals(f.runId,task.getString("runId"));assertTrue(task.toString(),task.getJSONObject("evidence").getString("modelSummary").contains("三个网页来源"));
        JSONArray operations=task.getJSONArray("operations");int nativeReferences=0;for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i);assertEquals(f.runId,operation.getString("runId"));if(assertNativeReference(task,operation,f.runId)){nativeReferences++;continue;}assertEquals("receipt",operation.getString("state"));}assertTrue(operations.length()-nativeReferences>=19);
        JSONArray approvals=task.getJSONArray("approvals");assertEquals(operations.length()-nativeReferences,approvals.length());for(int i=0;i<approvals.length();i++)assertEquals("consumed",approvals.getJSONObject(i).getString("state"));
        assertTrue(f.requests.size()>=20);assertTrue("type JS was dispatched",f.typed>0);assertEquals("session=original-profile",f.downloadCookie.get());assertEquals("downloaded",f.downloadReceipt.getString("status"));JSONObject attachment=f.downloadReceipt.getJSONObject("attachment");assertEquals("downloaded research file",AttachmentRepository.get(f.browser).book.readText(attachment.getString("id")).getString("text"));assertEquals(f.runId,attachment.getJSONObject("source").getString("runId"));
        TaskStore reopened=new TaskStore(f.app);try{JSONArray sources=BrowserTaskSourcePort.sourcesForOwner(reopened,f.taskId);assertEquals(3,sources.length());assertEquals(f.video,sources.getJSONObject(0).getString("privateUrl"));assertEquals(f.scholar,sources.getJSONObject(1).getString("privateUrl"));assertTrue(sources.getJSONObject(0).getBoolean("untrustedPageContent"));JSONObject projection=BrowserTaskSourcePort.projectReferenceForOwner(reopened,f.taskId,sources.getJSONObject(0).getString("sourceId"));assertFalse(projection.toString(),projection.toString().contains("retained-video"));assertFalse(projection.getBoolean("privateUrlIncluded"));assertEquals(1,f.browser.tabsForOwner().getJSONArray("tabs").length());assertSame(f.owner,view(f.browser));assertEquals("https://owner.example/already-open",f.owner.getUrl());f.owner(()->f.app.browserTaskHost.openSourceForOwner(f.taskId,sources.getJSONObject(0).getString("sourceId")));assertEquals(f.video,view(f.browser).getUrl());f.owner(()->f.app.browserTaskHost.openSourceForOwner(f.taskId,sources.getJSONObject(1).getString("sourceId")));assertEquals(f.scholar,view(f.browser).getUrl());assertEquals(3,f.browser.tabsForOwner().getJSONArray("tabs").length());}finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
    }
    private void assertReadScopedJourney(Fixture f)throws Exception {
        f.scopedReads=true;f.start();f.completed();JSONObject task=f.app.tasks.get(f.taskId,false);assertEquals(1,task.getInt("coordinatorEpoch"));assertEquals(f.runId,task.getString("runId"));assertEquals(1,f.app.tasks.list().length());
        JSONArray operations=task.getJSONArray("operations");int scoped=0;
        for(int i=0;i<operations.length();i++){
            JSONObject operation=operations.getJSONObject(i);assertEquals(f.runId,operation.getString("runId"));JSONObject receipt=operation.getJSONObject("receipt");
            if(assertNativeReference(task,operation,f.runId))continue;
            assertEquals("receipt",operation.getString("state"));
            if(receipt.has("readAuthorization")){scoped++;assertTrue(Set.of("browser_task_observe","browser_task_wait","browser_task_download_status","browser_task_retain_source").contains(operation.getString("tool")));assertEquals("native_read_scope",receipt.getJSONObject("readAuthorization").getString("mode"));assertEquals(operation.getString("operationId"),receipt.getJSONObject("readAuthorization").getString("operationId"));assertFalse(receipt.has("approvalId"));}
        }
        assertTrue("owned-page reads and real source retention must be scoped",scoped>=10);JSONArray approvals=task.getJSONArray("approvals");Set<String> precise=new HashSet<>();for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);assertEquals("consumed",approval.getString("state"));String tool=approval.getString("tool");assertFalse(tool,Set.of("browser_task_observe","browser_task_wait","browser_task_download_status","browser_task_retain_source").contains(tool));precise.add(tool);}assertTrue(precise.contains("browser_task_open"));assertTrue(precise.contains("browser_task_type"));assertTrue(precise.contains("browser_task_download"));assertTrue(approvals.length()<operations.length());
        assertEquals(3,BrowserTaskSourcePort.sourcesForOwner(f.app.tasks,f.taskId).length());assertEquals("downloaded",f.downloadReceipt.getString("status"));assertEquals("session=original-profile",f.downloadCookie.get());JSONObject state=f.app.tasks.browserReadScopeForOwner(f.taskId,f.runId,1,f.original.pauseRevision());assertEquals("revoked",state.getString("state"));assertEquals(32-scoped,state.getInt("remainingReads"));assertEquals(1048576L,state.getLong("remainingPublicBytes"));
    }
    @Test public void nativeOwnerReadScopeSkipsRepeatedReadsButKeepsExactActionsAndDurableSources()throws Exception {try(Fixture f=new Fixture(false,false)){assertReadScopedJourney(f);}}
    @Test public void existingOriginalRunGetsOnlyItsExplicitReadScopeWithoutAnotherTaskOrRun()throws Exception {try(Fixture f=new Fixture(true,false)){assertReadScopedJourney(f);assertFalse(f.hasBrowserTools(f.requests.get(0)));assertTrue(f.hasBrowserTools(f.requests.get(1)));}}
    @Test public void nativeGoalUsesActualAppRunnerHttpApprovalsSourcesDownloadsAndScopedFinish()throws Exception {try(Fixture f=new Fixture(false,false)){assertJourney(f);}}
    @Test public void runningOrdinaryAssistantOptsInAtItsNextOriginalTurnWithoutAnotherTaskOrRun()throws Exception {try(Fixture f=new Fixture(true,false)){assertJourney(f);assertEquals(1,f.app.tasks.list().length());assertFalse(f.hasBrowserTools(f.requests.get(0)));assertTrue(f.hasBrowserTools(f.requests.get(1)));}}
    @Test public void loginHandoffReturnsTheSamePostLoginPageSpaceTaskAndRunThroughActualRunner()throws Exception {try(Fixture f=new Fixture(false,false,true)){
        f.start();f.await(()->f.original.isPaused()&&!f.app.tasks.hasUnresolved(f.taskId));String task=f.taskId,run=f.runId;RunControl original=f.original;String tab=f.tabs.get(0);WebView login=view(f.browser);JSONObject before=f.browser.tabsForOwner().getJSONObject("taskSpace");String space=before.getString("browserSpaceId");JSONObject stale=before.getJSONArray("handoffPages").getJSONObject(0);assertEquals("owner_control",before.getString("status"));assertFalse(f.app.resources.ownsBrowser(task+":"+run+":1"));assertEquals(1,f.app.tasks.list().length());
        // This is an explicit manual login/document fixture, not a real website/device authentication claim.
        CookieManager.getInstance().setCookie("https://account.example/","session=manual-login-fixture");f.browser.openForOwner("https://account.example/workspace?view=original#saved");login.getWebViewClient().onPageStarted(login,login.getUrl(),null);login.getWebViewClient().onPageFinished(login,login.getUrl());assertSame(login,view(f.browser));assertTrue(CookieManager.getInstance().getCookie("https://account.example/").contains("manual-login-fixture"));
        assertThrows(java.util.concurrent.ExecutionException.class,()->f.owner(()->f.app.browserTaskHost.returnForOwner(stale)));assertTrue(original.isPaused());assertSame(original,f.current());assertFalse(f.app.resources.ownsBrowser(task+":"+run+":1"));
        JSONObject pin=f.browser.tabsForOwner().getJSONObject("taskSpace").getJSONArray("handoffPages").getJSONObject(0);assertEquals(tab,pin.getString("tabId"));assertEquals(space,pin.getString("browserSpaceId"));
        String unknown=UUID.randomUUID().toString();android.content.ContentValues lost=new android.content.ContentValues();lost.put("operation_id",unknown);lost.put("task_id",task);lost.put("run_id",run);lost.put("tool","browser_task_click");lost.put("arguments",j("tabId",tab,"observationId",UUID.randomUUID().toString(),"ref","n0").toString());lost.put("state","unknown");lost.put("receipt",j("status","unknown","accepted",JSONObject.NULL).toString());lost.put("created_at",System.currentTimeMillis());Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((android.database.sqlite.SQLiteDatabase)database.get(f.app.tasks)).insertOrThrow("operations",null,lost);assertThrows(java.util.concurrent.ExecutionException.class,()->f.owner(()->f.app.browserTaskHost.returnForOwner(pin)));assertTrue(original.isPaused());f.app.tasks.resolveOperation(task,unknown,"not_occurred");
        JSONObject returned=f.owner(()->f.app.browserTaskHost.returnForOwner(pin));assertEquals("returned",returned.getString("status"));assertEquals(space,returned.getString("browserSpaceId"));assertEquals(run,returned.getString("runId"));assertEquals(tab,returned.getString("tabId"));assertTrue(returned.getBoolean("freshObservationRequired"));assertSame(original,f.current());assertSame(login,view(f.browser));f.completed();JSONObject settled=f.app.tasks.get(task,false);assertNotNull(f.nativeReturnObservation);assertEquals(space,f.nativeReturnObservation.getString("browserSpaceId"));assertEquals(pin.getString("profileId"),f.nativeReturnObservation.getString("profileId"));assertEquals(pin.getLong("profileRevision"),f.nativeReturnObservation.getLong("profileRevision"));assertEquals(pin.getString("browserSessionId"),f.nativeReturnObservation.getString("browserSessionId"));assertEquals(1,f.app.tasks.list().length());assertEquals(run,settled.getString("runId"));assertEquals(1,settled.getInt("coordinatorEpoch"));JSONArray sources=BrowserTaskSourcePort.sourcesForOwner(f.app.tasks,task);assertEquals(1,sources.length());assertEquals("https://account.example/workspace?view=original#saved",sources.getJSONObject(0).getString("privateUrl"));assertEquals(space,sources.getJSONObject(0).getString("browserSpaceId"));assertEquals(tab,sources.getJSONObject(0).getString("tabId"));assertEquals(1,f.browser.tabsForOwner().getJSONArray("tabs").length());assertSame(f.owner,view(f.browser));
    }}
    @Test public void ownerTouchBeforeFirstTaskPagePausesOriginalAssistantAndFencesWaitingApproval()throws Exception {try(Fixture f=new Fixture(false,true)){f.start();f.await(()->f.app.tasks.get(f.taskId,false).optJSONArray("approvals")!=null&&f.app.tasks.get(f.taskId,false).getJSONArray("approvals").length()==1);assertEquals(1,f.browser.tabsForOwner().getJSONArray("tabs").length());String token=f.taskId+":"+f.runId+":1";assertTrue(f.app.resources.ownsBrowser(token));f.browser.takeover("主人直接操作已有页");assertTrue(f.original.isPaused());assertFalse(f.app.resources.ownsBrowser(token));f.owner(()->f.app.browserTaskHost.endForOwner());f.await(()->f.current()==null);assertTrue(f.original.isCancelled());assertSame(f.owner,view(f.browser));assertEquals("https://owner.example/already-open",f.owner.getUrl());assertEquals(1,f.browser.tabsForOwner().getJSONArray("tabs").length());assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(f.app.tasks,f.taskId).length());assertEquals(1,f.app.tasks.get(f.taskId,false).getInt("coordinatorEpoch"));}}
    @Test public void takeoverKeepsAdoptedPageAndArtifactsWhileCancellingOriginalModelAndOnlyClosingTaskPages()throws Exception {try(Fixture f=new Fixture(false,false)){f.holdFinish=true;f.start();f.await(()->f.finishHeld.getCount()==0);assertEquals(3,BrowserTaskSourcePort.sourcesForOwner(f.app.tasks,f.taskId).length());assertEquals("downloaded",f.downloadReceipt.getString("status"));String adopted=f.tabs.get(1);f.owner(()->{f.app.browserTaskHost.takeOverPage(adopted);return new JSONObject();});assertTrue(f.original.isPaused());assertFalse(f.app.resources.ownsBrowser(f.taskId+":"+f.runId+":1"));f.owner(()->f.app.browserTaskHost.endForOwner());f.releaseFinish.countDown();f.await(()->f.current()==null);assertTrue(f.original.isCancelled());JSONArray pages=f.browser.tabsForOwner().getJSONArray("tabs");assertEquals(2,pages.length());Set<String> ids=new HashSet<>();for(int i=0;i<pages.length();i++){assertEquals("owner",pages.getJSONObject(i).getString("openedBy"));ids.add(pages.getJSONObject(i).getString("tabId"));}assertTrue(ids.contains(f.ownerTab));assertTrue(ids.contains(adopted));assertEquals("https://owner.example/already-open",f.owner.getUrl());assertEquals(3,BrowserTaskSourcePort.sourcesForOwner(f.app.tasks,f.taskId).length());assertEquals("downloaded research file",AttachmentRepository.get(f.browser).book.readText(f.downloadReceipt.getJSONObject("attachment").getString("id")).getString("text"));assertEquals(1,f.app.tasks.get(f.taskId,false).getInt("coordinatorEpoch"));}}
}
