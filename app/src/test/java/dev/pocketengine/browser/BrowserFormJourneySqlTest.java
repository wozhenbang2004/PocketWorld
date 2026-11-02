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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserFormControlsTest.*;

/** Real Activity timer, TaskStore approvals/journal and original Runner/model HTTP.
 * Page JS callback/state is explicit fixture data; actual emitted JS has a separate DOM-interface test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserFormJourneySqlTest {
    static WebView web(BrowserActivity activity)throws Exception {Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);return (WebView)field.get(activity);}
    static JSONObject call(String name,JSONObject args)throws Exception {return j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",name,"arguments",args.toString()))));}
    static final class Page {
        int selected,writes,dispatches,reads;boolean checked,radio;final boolean revert;
        Page(boolean revert){this.revert=revert;}
        JSONObject observation(String url)throws Exception {
            JSONObject select=select(2).put("selectedIndex",selected);select.getJSONArray("options").getJSONObject(0).put("value","normal").put("label","普通配送").put("selected",selected==0);select.getJSONArray("options").getJSONObject(1).put("value","express").put("label","加急配送").put("selected",selected==1);
            JSONArray controls=new JSONArray().put(preview(select).put("type","select-one")).put(preview(check("checkbox",checked)).put("ref","n1").put("text","接收更新")).put(preview(check("radio",radio)).put("ref","n2").put("text","工作地址"));
            return j("url",url,"title","同一配送表单","text","选择配送和更新偏好；不提交订单。","elements",controls);
        }
        JSONObject response(String script,String url)throws Exception {
            if(script.contains("elements:nodes.map"))return observation(url);
            if(script.contains("state.formReadback={token:")){
                dispatches++;boolean noOp;
                if(script.contains("kind:\"select_option\"")){noOp=selected==1;if(!noOp){selected=1;writes++;}}
                else if(script.contains("const e=state.nodes[1]")){noOp=checked;if(!noOp){checked=true;writes++;}}
                else if(script.contains("const e=state.nodes[2]")){noOp=radio;if(!noOp){radio=true;writes++;}}
                else throw new AssertionError("Unexpected form target");
                return j("accepted",true,"noOp",noOp,"domEvidence",j("checkedChanged",!noOp,"valueChanged",!noOp,"postconditionKind","","postcondition","not_checked"));
            }
            if(script.contains("formReadback.token")){
                reads++;if(revert){checked=false;return j("status","observed","kind","checkbox","checked",false,"indeterminate",false,"matched",false);}
                // The model sequence below knows which original node was just operated. No real renderer is claimed.
                if(dispatches==1)return j("status","observed","kind","select","selectedIndex",selected,"value",selected==1?"express":"normal","valueTruncated",false,"matched",selected==1);
                boolean isRadio=dispatches==3,value=isRadio?radio:checked;
                return j("status","observed","kind",isRadio?"radio":"checkbox","checked",value,"indeterminate",false,"matched",value);
            }
            throw new AssertionError("Unexpected JS in form journey: "+script.substring(0,Math.min(100,script.length())));
        }
    }
    void journey(boolean controlledRevert)throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();
        try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            h.tasks.authorizeTools(h.taskId,h.current.binding.runId,BrowserTaskCapabilityPort.taskToolNames(true));String tab=h.open("delivery-form"),runId=h.current.binding.runId;var control=h.current.control;Page page=new Page(controlledRevert);
            AtomicReference<Throwable> failed=new AtomicReference<>();int[] step={0};String[] observation={null};JSONObject[] lastForm={null};
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)),receipt=null;JSONArray messages=request.getJSONArray("messages");for(int i=messages.length()-1;i>=0;i--)if(messages.getJSONObject(i).optString("role").equals("tool")){receipt=new JSONObject(messages.getJSONObject(i).getString("content"));break;}
                JSONArray tools=request.optJSONArray("tools");if(!controlledRevert&&step[0]>=9)assertTrue("Ended browser space still exposes a browser tool",tools==null||!tools.toString().contains("browser_task_"));else {assertNotNull("Active original browser tools missing",tools);assertTrue(tools.toString().contains("browser_task_set_checked"));}JSONObject answer;int current=step[0]++;
                if(controlledRevert){
                    if(current==0)answer=call("browser_task_observe",j("tabId",tab));
                    else if(current==1){observation[0]=receipt.getString("observationId");answer=call("browser_task_set_checked",j("tabId",tab,"observationId",observation[0],"ref","n1","checked",true));}
                    else {lastForm[0]=receipt;assertFalse(receipt.toString(),receipt.getBoolean("ok"));assertEquals("unmet",receipt.getJSONObject("controlEvidence").getString("state"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));answer=j("role","assistant","content","页面取消了刚才的勾选，目标未达到；我没有重试，也没有提交订单，请主人查看原页面。");}
                }else if(current==0||current==2||current==4||current==6)answer=call("browser_task_observe",j("tabId",tab));
                else if(current==1||current==3||current==5||current==7){
                    assertTrue(receipt.toString(),receipt.getBoolean("ok"));observation[0]=receipt.getString("observationId");String name=current==1?"browser_task_select_option":"browser_task_set_checked";JSONObject args=j("tabId",tab,"observationId",observation[0],"ref",current==1?"n0":current==5?"n2":"n1");if(current==1)args.put("optionRef","o1");else args.put("checked",true);answer=call(name,args);
                }else if(current==8){lastForm[0]=receipt;assertEquals("no_op",receipt.getString("status"));assertEquals("not_needed",receipt.getJSONObject("actionReceipt").getJSONObject("dispatch").getString("state"));answer=call("browser_task_finish",new JSONObject());}
                else answer=j("role","assistant","content","已在同一页面选择加急配送、勾选更新和工作地址，并回读控件状态；没有点击提交，服务器保存结果未核实。");
                byte[] bytes=j("choices",new JSONArray().put(j("message",answer,"finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){failed.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            try{
                ModelConfig model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"explicit-local-fixture","forms-original-runner",false,25,14);
                Future<RunOutcome> run=h.worker.submit(()->new AgentRunner(null,h.current.port).run(h.taskId,"在原页面调整配送偏好并回读，不提交订单；未知或不匹配不要重复操作",model,control,event->{try{h.tasks.event(h.taskId,runId,h.current.binding.epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},h.current.journal));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(!run.isDone()&&System.nanoTime()<until){
                    if(failed.get()!=null)throw new AssertionError("explicit model fixture",failed.get());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));WebView selected=web(h.browser);
                    if(selected!=null){selected.getWebViewClient().onPageFinished(selected,selected.getUrl());var shadow=Shadows.shadowOf(selected);var callback=shadow.getLastEvaluatedJavascriptCallback();String script=shadow.getLastEvaluatedJavascript();if(callback!=null&&script!=null&&h.callbacks.add(callback))callback.onReceiveValue(JSONObject.quote(page.response(script,selected.getUrl()).toString()));}Thread.sleep(3);
                }
                RunOutcome outcome=run.get(2,TimeUnit.SECONDS);if(failed.get()!=null)throw new AssertionError("explicit model fixture",failed.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertNotNull(lastForm[0]);assertSame(control,h.current.control);assertEquals(runId,h.current.binding.runId);assertEquals(1,h.tasks.list().length());assertEquals(controlledRevert?1:4,page.dispatches);assertEquals(controlledRevert?1:4,page.reads);assertEquals(controlledRevert?1:3,page.writes);
                TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{
                    JSONObject task=reopened.get(h.taskId,false);JSONArray ops=task.getJSONArray("operations");int forms=0;for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);assertEquals(runId,op.getString("runId"));if(op.optString("tool").matches("browser_task_select_option|browser_task_set_checked")){forms++;assertEquals("receipt",op.getString("state"));JSONObject receipt=op.getJSONObject("receipt");assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));assertEquals(controlledRevert?"unmet":"met",receipt.getJSONObject("controlEvidence").getString("state"));}}
                    assertEquals(controlledRevert?1:4,forms);int approvedForms=0;JSONArray approvals=task.getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject a=approvals.getJSONObject(i);if(a.getString("tool").matches("browser_task_select_option|browser_task_set_checked")){approvedForms++;assertEquals("consumed",a.getString("state"));assertTrue(a.getJSONObject("capability").getJSONObject("targetPreview").has("control"));}}assertEquals(forms,approvedForms);assertTrue(h.current.journal.restore().getJSONArray("messages").toString().contains("controlEvidence"));
                }finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
            }finally{server.stop(0);}
        }
    }
    @Test public void originalRunnerSelectsChecksRadioAndReadbacksWithoutSubmittingOrRepeatingNoOp()throws Exception {journey(false);}
    @Test public void originalRunnerReportsControlledStateRevertWithoutRepeatingTheWrite()throws Exception {journey(true);}
    @Test public void cancelAfterOneDispatchBeforeDelayedReadbackKeepsUnknownWithoutAnotherWrite()throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();
        try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            h.tasks.authorizeTools(h.taskId,h.current.binding.runId,BrowserTaskCapabilityPort.taskToolNames(true));String tab=h.open("cancel-during-readback");Page page=new Page(false);String observedOp=h.current.journal.beginOperation("browser_task_observe",j("tabId",tab));
            Future<JSONObject> read=h.worker.submit(()->h.current.port.execute("browser_task_observe",j("tabId",tab,"_hostOperationId",observedOp),h.current.control));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!read.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();WebView selected=web(h.browser);if(selected!=null){selected.getWebViewClient().onPageFinished(selected,selected.getUrl());var shadow=Shadows.shadowOf(selected);var cb=shadow.getLastEvaluatedJavascriptCallback();String js=shadow.getLastEvaluatedJavascript();if(cb!=null&&js!=null&&h.callbacks.add(cb))cb.onReceiveValue(JSONObject.quote(page.response(js,selected.getUrl()).toString()));}Thread.sleep(3);}
            JSONObject observed=read.get(2,TimeUnit.SECONDS);h.current.journal.finishOperation(observedOp,observed);JSONObject args=j("tabId",tab,"observationId",observed.getString("observationId"),"ref","n1","checked",true);String operation=h.current.journal.beginOperation("browser_task_set_checked",args);
            Future<JSONObject> write=h.worker.submit(()->h.current.port.execute("browser_task_set_checked",new JSONObject(args.toString()).put("_hostOperationId",operation),h.current.control));until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(page.dispatches==0&&!write.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();WebView selected=web(h.browser);if(selected!=null){var shadow=Shadows.shadowOf(selected);var cb=shadow.getLastEvaluatedJavascriptCallback();String js=shadow.getLastEvaluatedJavascript();if(cb!=null&&js!=null&&h.callbacks.add(cb))cb.onReceiveValue(JSONObject.quote(page.response(js,selected.getUrl()).toString()));}Thread.sleep(3);}
            assertEquals(1,page.dispatches);assertEquals(1,page.writes);h.current.control.cancel();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));JSONObject receipt=write.get(3,TimeUnit.SECONDS);assertEquals(receipt.toString(),"unknown",receipt.getString("status"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));h.current.journal.finishOperation(operation,receipt);assertEquals(0,page.reads);assertEquals(1,page.dispatches);JSONArray operations=h.tasks.get(h.taskId,false).getJSONArray("operations");JSONObject actual=null;for(int i=0;i<operations.length();i++)if(operation.equals(operations.getJSONObject(i).getString("operationId")))actual=operations.getJSONObject(i);assertNotNull(actual);assertEquals("unknown",actual.getString("state"));
        }
    }
}
