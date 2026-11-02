package dev.pocketengine.browser;

import android.app.Application;
import android.os.Looper;
import android.webkit.WebView;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Original Activity, TaskStore journal/approvals, Runner and local model HTTP.
 * Explicit page callback fixtures test the native control flow, not browser rendering or JS behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserSequenceJourneySqlTest {
    enum Mode { FULL, WAIT_MET, WAIT_UNMET, REPLACED_NODE, CANCEL_AFTER_FIRST }
    static JSONObject j(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    static WebView web(BrowserActivity activity)throws Exception {Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);return (WebView)field.get(activity);}
    static JSONObject call(String tool,JSONObject args)throws Exception {return j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",tool,"arguments",args.toString()))));}
    static JSONArray steps(Mode mode)throws Exception {
        if(mode==Mode.WAIT_MET||mode==Mode.WAIT_UNMET)return new JSONArray().put(j("action","wait","condition","text","value","ready","timeoutMs",100)).put(j("action","observe")).put(j("action","click","ref","n0"));
        return new JSONArray().put(j("action","observe")).put(j("action","click","ref","n0")).put(j("action","observe")).put(j("action","click","ref","n0"));
    }
    void journey(Mode mode)throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();
        try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            h.tasks.authorizeTools(h.taskId,h.current.binding.runId,BrowserTaskCapabilityPort.taskToolNames(true));String tab=h.open("bounded-original-sequence"),runId=h.current.binding.runId;var control=h.current.control;
            AtomicInteger rounds=new AtomicInteger(),reads=new AtomicInteger(),writes=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();List<JSONObject> events=new CopyOnWriteArrayList<>();
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)),answer;int round=rounds.getAndIncrement();
                if(round==0)answer=call("browser_task_observe",j("tabId",tab));
                else if(round==1){JSONArray messages=request.getJSONArray("messages");JSONObject delivered=null;for(int i=messages.length()-1;i>=0;i--)if("tool".equals(messages.getJSONObject(i).optString("role"))){delivered=new JSONObject(messages.getJSONObject(i).getString("content"));break;}assertNotNull(delivered);assertEquals("observed",delivered.getString("status"));answer=call(BrowserToolSequence.TOOL,j("tabId",tab,"observationId",delivered.getString("observationId"),"steps",steps(mode)));}
                else answer=j("role","assistant","content","原组合已按实际回执结束，未自动补跑；页面事件不证明站点业务成功。");
                byte[] bytes=j("choices",new JSONArray().put(j("message",answer,"finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){failure.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            try{
                ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"explicit-local-fixture","browser-original-sequence",false,25,12);
                Future<RunOutcome> run=h.worker.submit(()->new AgentRunner(null,h.current.port).run(h.taskId,"在原页面以短组合展开两个明确控件，取消、条件未满足或节点变化即停止，不重试",config,control,event->{
                    try{events.add(new JSONObject(event.toString()));h.tasks.event(h.taskId,runId,h.current.binding.epoch,event);
                        if(mode==Mode.CANCEL_AFTER_FIRST&&"tool".equals(event.optString("type"))&&"completed".equals(event.optString("status"))&&"browser_task_click".equals(event.optString("tool")))control.cancel();
                    }catch(Exception error){throw new IllegalStateException(error);}
                },h.current.journal));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!run.isDone()&&System.nanoTime()<until){
                    if(failure.get()!=null)throw new AssertionError("local model fixture",failure.get());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));WebView view=web(h.browser);
                    if(view!=null){view.getWebViewClient().onPageFinished(view,view.getUrl());var shadow=Shadows.shadowOf(view);var callback=shadow.getLastEvaluatedJavascriptCallback();String script=shadow.getLastEvaluatedJavascript();
                        if(callback!=null&&script!=null&&h.callbacks.add(callback)){JSONObject response;
                            if(script.contains("elements:nodes.map")){int count=reads.incrementAndGet();response=j("url",view.getUrl(),"title","exact original node","text","展开内容","elements",new JSONArray().put(j("ref","n0","nodeKey",mode==Mode.REPLACED_NODE&&count>1?"k2":"k1","tag","BUTTON","type","button","id","toggle","text","展开")));}
                            else if(script.contains("__pw_task_wait"))response=j("matched",mode==Mode.WAIT_MET);
                            else if(script.contains("e.click();")){writes.incrementAndGet();response=j("accepted",true,"domEvidence",j("textChanged",true,"postconditionKind","","postcondition","not_checked"));}
                            else throw new AssertionError("Unexpected JS: "+script.substring(0,Math.min(script.length(),100)));
                            callback.onReceiveValue(JSONObject.quote(response.toString()));
                        }
                    }Thread.sleep(3);
                }
                RunOutcome outcome=run.get(2,TimeUnit.SECONDS);if(failure.get()!=null)throw new AssertionError("local model fixture",failure.get());assertSame(control,h.current.control);assertEquals(runId,h.current.binding.runId);assertEquals(1,h.tasks.list().length());
                int expectedWrites=mode==Mode.FULL?2:mode==Mode.WAIT_MET||mode==Mode.CANCEL_AFTER_FIRST?1:0;assertEquals(expectedWrites,writes.get());if(mode==Mode.CANCEL_AFTER_FIRST)assertEquals("cancelled",outcome.status);
                JSONObject task=h.tasks.get(h.taskId,false);JSONArray operations=task.getJSONArray("operations");Set<String> operationIds=new HashSet<>();int parents=0,clicks=0,waits=0;String parent="";
                for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i);assertTrue(operationIds.add(operation.getString("operationId")));assertEquals(runId,operation.getString("runId"));assertEquals("receipt",operation.getString("state"));String tool=operation.getString("tool");if(tool.equals(BrowserToolSequence.TOOL)){parents++;parent=operation.getString("operationId");assertEquals(0,operation.getJSONObject("receipt").getInt("dispatchEnteredSteps"));}if(tool.equals("browser_task_click"))clicks++;if(tool.equals("browser_task_wait")){waits++;assertEquals(mode==Mode.WAIT_MET,operation.getJSONObject("receipt").getBoolean("matched"));}}
                assertEquals(1,parents);assertEquals(expectedWrites,clicks);assertEquals(mode==Mode.WAIT_MET||mode==Mode.WAIT_UNMET?1:0,waits);int approvedClicks=0;JSONArray approvals=task.getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject approval=approvals.getJSONObject(i);assertEquals("consumed",approval.getString("state"));if(approval.getString("tool").equals("browser_task_click")){approvedClicks++;assertEquals("k1",approval.getJSONObject("capability").getJSONObject("targetPreview").getString("nodeKey"));}}assertEquals(expectedWrites,approvedClicks);
                if(mode==Mode.REPLACED_NODE||mode==Mode.WAIT_UNMET||mode==Mode.CANCEL_AFTER_FIRST){JSONObject remaining=null;for(JSONObject event:events)if("native_sequence".equals(event.optString("type"))&&"not_dispatched".equals(event.optString("status")))remaining=event.getJSONObject("receipt");assertNotNull("unexecuted plan steps must be explicit",remaining);assertEquals(parent,remaining.getString("parentOperationId"));assertTrue(remaining.getInt("remainingSteps")>0);assertFalse(remaining.getBoolean("automaticReplay"));}
            }finally{server.stop(0);}
        }
    }
    @Test public void actualOriginalRunnerJournalsAndApprovesEveryFreshBrowserChild()throws Exception {journey(Mode.FULL);}
    @Test public void matchedOriginalWaitAllowsOnlyTheRemainingPreparedSteps()throws Exception {journey(Mode.WAIT_MET);}
    @Test public void unmetOriginalWaitStopsBeforeAnyFollowingObservationOrWrite()throws Exception {journey(Mode.WAIT_UNMET);}
    @Test public void replacedNodeWithTheSameLabelCannotReceiveAChildWrite()throws Exception {journey(Mode.REPLACED_NODE);}
    @Test public void cancellationAfterACommittedChildKeepsItAndDoesNotReplayTheRest()throws Exception {journey(Mode.CANCEL_AFTER_FIRST);}
}
