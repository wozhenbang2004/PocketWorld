package dev.pocketengine.browser;

import android.app.Application;
import android.os.Looper;
import android.webkit.WebView;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Original Activity, wait timers, native adapter, SQLite approvals/journal and Runner.
 * JS callbacks/model responses are explicit interface fixtures; no rendered DOM, provider or real server-save claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserTargetWaitSqlTest {
    static WebView web(BrowserActivity activity)throws Exception {Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);return (WebView)field.get(activity);}
    static JSONObject call(String name,JSONObject args)throws Exception{return j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",name,"arguments",args.toString()))));}
    void journey(boolean succeeds)throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();
        try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            h.tasks.authorizeTools(h.taskId,h.current.binding.runId,BrowserTaskCapabilityPort.taskToolNames(true));String tab=h.open("async-save");String originalRun=h.current.binding.runId;var originalControl=h.current.control;
            AtomicReference<Throwable> failed=new AtomicReference<>();String[] region={null};JSONObject[] waited={null};int[] step={0},clicks={0},probes={0};
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));JSONArray messages=request.getJSONArray("messages");JSONObject receipt=null;for(int i=messages.length()-1;i>=0;i--)if(messages.getJSONObject(i).optString("role").equals("tool")){receipt=new JSONObject(messages.getJSONObject(i).getString("content"));break;}
                JSONObject answer;switch(step[0]++){
                    case 0:answer=call("browser_task_observe",j("tabId",tab,"read",j("mode","outline","query","保存表单")));break;
                    case 1:assertTrue(receipt.toString(),receipt.optBoolean("ok"));region[0]=receipt.getJSONObject("read").getJSONArray("data").getJSONObject(0).getString("regionRef");answer=call("browser_task_observe",j("tabId",tab));break;
                    case 2:assertTrue(receipt.toString(),receipt.optBoolean("ok"));answer=call("browser_task_click",j("tabId",tab,"observationId",receipt.getString("observationId"),"ref","n0"));break;
                    case 3:assertTrue(receipt.toString(),receipt.optBoolean("accepted"));assertFalse(receipt.optBoolean("verifiedBusinessSuccess"));answer=call("browser_task_wait",j("tabId",tab,"condition","target","timeoutMs",800,"target",j("regionRef",region[0],"selector",j("role","textbox","name","方案"),"state","value","value","已保存","stableMs",200)));break;
                    default:waited[0]=receipt;assertEquals(succeeds,receipt.getBoolean("conditionMet"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertFalse(receipt.getBoolean("inputDispatched"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));assertTrue(receipt.getInt("samples")>=3);assertEquals(region[0],receipt.getJSONObject("targetEvidence").getString("regionRef"));if(succeeds){assertTrue(receipt.toString(),receipt.getBoolean("ok"));assertEquals("已保存",receipt.getJSONObject("targetEvidence").getJSONObject("actual").getString("value"));}else{assertFalse(receipt.toString(),receipt.getBoolean("ok"));assertEquals("condition_timeout",receipt.getString("code"));}answer=j("role","assistant","content",succeeds?"只观察到原页面字段已显示已保存；服务器业务结果仍未独立核实，未再次点击。":"原页面状态未在限定窗口满足，结果未确认；没有重复点击保存。");break;
                }
                byte[] bytes=j("choices",new JSONArray().put(j("message",answer,"finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){failed.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            try{
                ModelConfig model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"explicit-local-fixture","target-state-original-runner",false,25,8);
                Future<RunOutcome> run=h.worker.submit(()->new AgentRunner(null,h.current.port).run(h.taskId,"在原页面保存一次，等待实际字段状态并如实回报，不重复提交",model,h.current.control,event->{try{h.tasks.event(h.taskId,originalRun,h.current.binding.epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},h.current.journal));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(22);
                while(!run.isDone()&&System.nanoTime()<deadline){
                    if(failed.get()!=null)throw new AssertionError("explicit model fixture",failed.get());if(probes[0]>0)Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));else Shadows.shadowOf(Looper.getMainLooper()).idle();WebView selected=web(h.browser);
                    if(selected!=null){selected.getWebViewClient().onPageFinished(selected,selected.getUrl());var shadow=Shadows.shadowOf(selected);var callback=shadow.getLastEvaluatedJavascriptCallback();String script=shadow.getLastEvaluatedJavascript();if(callback!=null&&script!=null&&h.callbacks.add(callback)){
                        JSONObject response;
                        if(script.contains("const request=")&&script.contains("regionNamespace=")){var namespace=Pattern.compile("regionNamespace=\"([a-f0-9-]{36})\"").matcher(script);assertTrue(namespace.find());response=raw("outline").put("url",selected.getUrl()).put("text","当前方案待保存");response.getJSONObject("read").put("data",new JSONArray().put(j("regionRef",namespace.group(1)+":r1","role","form","name","保存表单","preview","当前方案待保存","readOnlyEvidence",true)));}
                        else if(script.contains("targetProbe:true")){int start=script.indexOf("const request=")+"const request=".length(),end=script.indexOf(",nativeKey=",start);JSONObject target=new JSONObject(script.substring(start,end));assertEquals(region[0],target.getString("regionRef"));probes[0]++;boolean met=succeeds&&probes[0]>=2;String value=met?"已保存":"保存中";response=j("targetProbe",true,"status","observed","code","target_state_read","matched",met,"actual",j("present",true,"matchCount",1,"instance","1","role","textbox","name","方案","value",value,"valueLength",value.length(),"valueTruncated",false),"coverage",j("nodesVisited",4,"textCharsVisited",3,"queuedPeak",1,"layoutChecks",2,"partial",false,"reasons",new JSONArray()));}
                        else if(script.contains("elements:nodes.map"))response=j("url",selected.getUrl(),"title","原保存表单","text","当前方案待保存","elements",new JSONArray().put(j("ref","n0","tag","BUTTON","type","button","text","保存","href","")));
                        else if(script.contains("e.click();")){clicks[0]++;response=j("accepted",true,"verified",false,"domEvidence",j("urlChanged",false,"textChanged",false,"scrollChanged",false,"valueChanged",false,"postconditionKind","","postcondition","not_checked"));}
                        else throw new AssertionError("Unexpected JS operation in original target wait: "+script.substring(0,Math.min(100,script.length())));
                        callback.onReceiveValue(JSONObject.quote(response.toString()));
                    }}Thread.sleep(3);
                }
                RunOutcome outcome=run.get(2,TimeUnit.SECONDS);if(failed.get()!=null)throw new AssertionError("explicit model fixture",failed.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertNotNull(waited[0]);assertEquals(5,step[0]);assertEquals(1,clicks[0]);assertEquals(h.current.binding.runId,originalRun);assertSame(originalControl,h.current.control);assertEquals(1,h.tasks.list().length());
                TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{JSONObject task=reopened.get(h.taskId,false);JSONArray ops=task.getJSONArray("operations");int waits=0,inputs=0;for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);assertEquals(originalRun,op.getString("runId"));if(op.optString("tool").equals("browser_task_wait")){waits++;assertEquals(succeeds,op.getJSONObject("receipt").getBoolean("conditionMet"));assertEquals(region[0],op.getJSONObject("receipt").getJSONObject("targetEvidence").getString("regionRef"));}if(op.optString("tool").equals("browser_task_click"))inputs++;}assertEquals(1,waits);assertEquals(1,inputs);JSONArray approvals=task.getJSONArray("approvals");int waitApprovals=0;for(int i=0;i<approvals.length();i++)if(approvals.getJSONObject(i).getString("tool").equals("browser_task_wait")){waitApprovals++;assertEquals("consumed",approvals.getJSONObject(i).getString("state"));}assertEquals(1,waitApprovals);assertTrue(h.current.journal.restore().getJSONArray("messages").toString().contains("evidenceSha256"));}finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
            }finally{server.stop(0);}
        }
    }
    @Test public void originalActivityRunnerWaitsForDelayedSameRegionStateWithoutRepeatingTheClick()throws Exception {journey(true);}
    @Test public void originalActivityRunnerTimeoutRemainsUnconfirmedAndNeverRepeatsTheSave()throws Exception {journey(false);}
}
