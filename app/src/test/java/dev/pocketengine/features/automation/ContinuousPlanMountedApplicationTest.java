package dev.pocketengine.features.automation;

import android.os.Looper;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.HostAutomationCoordinator;
import dev.pocketengine.features.workspace.WorkspaceModule;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Production native Application/AlarmHost/Runner/SQLite/file chain; only the model is loopback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class ContinuousPlanMountedApplicationTest {
    private EngineApplication app;private HttpServer model;private String project,planId;
    private final CountDownLatch firstRequest=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
    private final List<JSONObject> requests=new CopyOnWriteArrayList<>();
    private static JSONObject j(Object... values)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    @Before public void setup()throws Exception{
        java.security.Security.addProvider(new java.security.Provider("ContinuousPlanKeyStore","1","Explicit test-only AndroidKeyStore fixture"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        app=(EngineApplication)RuntimeEnvironment.getApplication();
        project=workspace().handle("create",j("name","Native continuous goal files")).getString("id");
        workspace().handle("write",j("projectId",project,"path","reports/source.md","content","# Source\nActual source bytes from the original workspace.\n","expectedVersion",0));
        workspace().handle("write",j("projectId",project,"path","reports/delivery.md","content","# Delivery\nActual delivery bytes from the original workspace.\n","expectedVersion",0));
    }
    @After public void cleanup(){releaseFirst.countDown();if(model!=null)model.stop(0);if(app!=null&&app.features!=null)app.features.close();java.security.Security.removeProvider("ContinuousPlanKeyStore");}
    private WorkspaceModule workspace(){return (WorkspaceModule)app.features.module("workspace");}
    private AutomationModule automation(){return (AutomationModule)app.features.module("automation");}
    private JSONObject call(String command,JSONObject input)throws Exception{return app.dispatchOwnerAction("automationPlan",j("command",command,"payload",input));}
    private JSONObject plan()throws Exception{return automation().handle("getPlan",j("scope",j("ownerId","owner","spaceId","private"),"id",planId)).getJSONObject("plan");}
    private JSONObject ref(JSONObject plan)throws Exception{return j("id",plan.getString("id"),"revision",plan.getLong("revision"));}
    private JSONObject settings()throws Exception{return j("mode","automatic","maxTaskAdmissions",3,"modelObservationConsent",true,"allowPrivateArtifactFreeze",true,"requiresNetwork",false,"expiresAt",System.currentTimeMillis()+3_600_000);}
    private JSONObject step(String title,String path,String heading)throws Exception{return j("title",title,"goal",title+"，核实后冻结指定项目文件 "+path,"executor","workspace","dueAt",System.currentTimeMillis()-1000,"verification",j("kind","workspace_artifact","projectId",project,"path",path,"requiredHeading",heading,"minBytes",16));}
    private void create(JSONArray steps)throws Exception{JSONObject created=call("create",j("plan",j("kind","explicit_user","title","交付两份已核验项目文件","goal","按依赖交付真实文件，并将原任务成果写回同一持续目标","steps",steps))).getJSONObject("plan");planId=created.getString("id");}
    private void due()throws Exception{Field field=EngineApplication.class.getDeclaredField("automationHost");field.setAccessible(true);((HostAutomationCoordinator)field.get(app)).onAutomationDue(System.currentTimeMillis(),4);}
    private interface Check {boolean ok()throws Exception;}
    private void await(String label,Check check)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(check.ok())return;Thread.sleep(10);}
        throw new AssertionError(label+"; plan="+(planId==null?"none":plan())+" tasks="+app.tasks.list()+" requestCount="+requests.size());
    }
    private void serve(boolean holdFirst,boolean failFirst)throws Exception{
        AtomicInteger sequence=new AtomicInteger();model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/v1/chat/completions",exchange->{try{
            JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);int n=sequence.incrementAndGet();
            if(n==1){firstRequest.countDown();if(holdFirst)assertTrue(releaseFirst.await(10,TimeUnit.SECONDS));}
            int status=200;JSONObject response;
            if(n==1&&failFirst){status=500;response=j("error",j("message","controlled first native task failure"));}
            else{
                JSONArray history=request.getJSONArray("messages");boolean frozen=false;for(int i=0;i<history.length();i++){JSONObject message=history.getJSONObject(i);if(message.optString("role").equals("tool")&&message.optString("content").contains("task_workspace"))frozen=true;}
                JSONObject message=j("role","assistant","content",frozen?"实际文件已冻结，交给原生核验标准核对。":"");
                if(!frozen){String path="reports/source.md";for(int i=0;i<history.length();i++)if(history.getJSONObject(i).optString("content").contains("reports/delivery.md"))path="reports/delivery.md";message.put("tool_calls",new JSONArray().put(j("id","freeze-"+n,"type","function","function",j("name","workspace_create_artifact","arguments",j("projectId",project,"path",path,"version",0).toString()))));}
                response=j("choices",new JSONArray().put(j("message",message,"finish_reason",frozen?"stop":"tool_calls")));
            }
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failure){throw new IllegalStateException(failure);}finally{exchange.close();}});model.start();
        app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+model.getAddress().getPort()+"/v1").putString("provider","openai_chat").putString("model","continuous-plan-controlled-model").putInt("maxSteps",6).putInt("maxOutputTokens",512).putBoolean("sendScreenshots",false).commit();
    }
    private JSONObject original(int index)throws Exception{return app.tasks.get(plan().getJSONArray("steps").getJSONObject(index).getString("taskId"),false);}
    private static void assertMachineResult(JSONObject task)throws Exception{
        assertEquals("completed",task.getString("status"));boolean proof=false;JSONArray events=task.getJSONArray("events");for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);assertNotEquals("user_verification",event.optString("type"));if(event.optString("type").equals("native_plan_verification"))proof=true;}assertTrue("actual native result proof missing",proof);
        assertEquals(1,task.getJSONArray("operations").length());assertEquals("receipt",task.getJSONArray("operations").getJSONObject(0).getString("state"));
    }
    @Test public void pausedEditedAndResumedGoalUsesOriginalRunAndMachineEvidenceForItsSuccessor()throws Exception{
        serve(true,false);create(new JSONArray().put(step("核实源文件","reports/source.md","# Source")).put(step("交付后继文件","reports/delivery.md","# Delivery")));
        call("approve",ref(plan()).put("settings",settings()));assertTrue(firstRequest.await(8,TimeUnit.SECONDS));JSONObject before=original(0);String id=before.getString("id"),run=before.getString("runId"),occurrence=before.getJSONObject("origin").getString("occurrenceId");int epoch=before.getInt("coordinatorEpoch");
        assertTrue(assertThrows(IllegalStateException.class,()->call("approve",ref(plan()).put("settings",settings()))).getMessage().contains("先暂停"));
        JSONObject pausedReceipt=call("pause",ref(plan())),paused=pausedReceipt.getJSONObject("plan");assertEquals("paused",paused.getJSONObject("execution").getString("state"));assertEquals(paused.getLong("revision"),pausedReceipt.getJSONObject("snapshot").getJSONArray("plans").getJSONObject(0).getLong("revision"));assertNotEquals("completed",original(0).getString("status"));assertEquals(1,app.tasks.list().length());
        paused=plan();JSONObject second=paused.getJSONArray("steps").getJSONObject(1);call("editStep",ref(paused).put("stepId",second.getString("id")).put("stepRevision",second.getLong("revision")).put("patch",j("goal","审阅修改后的交付后继文件 reports/delivery.md")));
        assertFalse(plan().getBoolean("approvalValid"));call("approve",ref(plan()).put("settings",settings()));assertEquals("paused",plan().getJSONObject("execution").getString("state"));assertEquals(1,app.tasks.list().length());
        call("resume",ref(plan()));releaseFirst.countDown();await("first original machine result",()->original(0).optString("status").equals("completed"));
        JSONObject finished=app.tasks.get(id,false);assertEquals(run,finished.getString("runId"));assertEquals(epoch,finished.getInt("coordinatorEpoch"));assertEquals(occurrence,finished.getJSONObject("origin").getString("occurrenceId"));assertMachineResult(finished);
        due();await("second original machine result",()->plan().optString("state").equals("completed"));assertMachineResult(original(1));assertEquals(2,app.tasks.list().length());assertNotEquals(id,original(1).getString("id"));
        for(JSONObject request:requests){JSONArray tools=request.getJSONArray("tools");for(int i=0;i<tools.length();i++)assertTrue(tools.getJSONObject(i).getJSONObject("function").getString("name").startsWith("workspace_"));assertFalse(request.toString().contains("PRIVATE-UNRELATED-SENTINEL"));}
        assertEquals(2,plan().getJSONObject("execution").getInt("admissions"));
    }
    @Test public void onlyClosedFailureSelectsRecoveryBranchAndTheGoalRetainsItsFailure()throws Exception{
        serve(false,true);JSONArray steps=new JSONArray().put(step("先尝试原任务","reports/source.md","# Source"));steps.put(step("成功路径不会被误触发","reports/source.md","# Source").put("dependsOn",new JSONArray().put(j("step",0,"outcome","completed"))));steps.put(step("失败后形成恢复文件","reports/delivery.md","# Delivery").put("dependsOn",new JSONArray().put(j("step",0,"outcome","failed"))));create(steps);
        call("approve",ref(plan()).put("settings",settings()));await("first original native failed",()->original(0).optString("status").equals("failed"));due();await("verified recovery branch",()->plan().optString("state").equals("finished_with_failures"));
        assertEquals("failed",plan().getJSONArray("steps").getJSONObject(0).getString("status"));assertEquals("skipped",plan().getJSONArray("steps").getJSONObject(1).getString("status"));assertEquals("",plan().getJSONArray("steps").getJSONObject(1).optString("taskId"));assertMachineResult(original(2));assertEquals(2,app.tasks.list().length());assertFalse(app.tasks.hasUnresolved(original(0).getString("id")));
    }
}
