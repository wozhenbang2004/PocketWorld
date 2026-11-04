package dev.pocketengine;

import android.content.ContentValues;
import android.os.Looper;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real App -> Runner -> loopback Gateway -> SQL approval -> queued App -> Runner ->
 * HostToolSupervisor consumption. Only startup/presentation, queue pickup, the HTTP
 * model response and the final business module are controlled. No external model or
 * real workspace mutation is configured. Fixture receipts are not business results. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,
    shadows=CapabilityApprovalConsumptionNativeTest.PresentationBoundary.class)
public final class CapabilityApprovalConsumptionNativeTest {
    private static final String TOOL="workspace_create",NAME="controlled original approval";
    @Implements(EngineApplication.class)
    public static final class PresentationBoundary extends org.robolectric.shadows.ShadowApplication {
        static Runnable afterPush;
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){Runnable once=afterPush;afterPush=null;if(once!=null)once.run();}
    }
    private static final class Queue extends AbstractExecutorService {
        final List<Runnable> pending=new ArrayList<>();boolean closed;
        @Override public void execute(Runnable run){if(closed)throw new java.util.concurrent.RejectedExecutionException();pending.add(run);}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;var result=new ArrayList<>(pending);pending.clear();return result;}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed&&pending.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
        void pickUp(){assertEquals(1,pending.size());pending.remove(0).run();shadowOf(Looper.getMainLooper()).idle();}
    }
    /** The real native route reaches only this recording endpoint, never a filesystem. */
    private static final class WorkspaceFixture implements EngineModule {
        final List<JSONObject> calls=new ArrayList<>();boolean available=true,unknown;
        public String id(){return "workspace";}
        public JSONObject snapshot()throws Exception{return new JSONObject().put("available",available).put("projects",new JSONArray());}
        public JSONObject handle(String command,JSONObject input)throws Exception{
            if(!"create".equals(command))throw new AssertionError("unexpected business dispatch: "+command);
            calls.add(new JSONObject(input.toString()));
            return unknown?new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("status","unknown").put("code","fixture_unknown").put("message","Controlled unknown receipt, no business effect")
                :new JSONObject().put("ok",true).put("accepted",true).put("code","fixture_recorded").put("message","Controlled recording endpoint, no business effect");
        }
    }
    private static final class ModelFixture implements AutoCloseable {
        final HttpServer server;final List<JSONObject> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.ConcurrentLinkedQueue<JSONArray> replies=new java.util.concurrent.ConcurrentLinkedQueue<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        ModelFixture()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                JSONArray calls=replies.poll();JSONObject message=new JSONObject().put("role","assistant");
                if(calls!=null)message.put("content",JSONObject.NULL).put("tool_calls",calls);else message.put("content","Controlled fixture finished; no real project was created.");
                byte[] encoded=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",calls==null?"stop":"tool_calls")))
                    .put("usage",new JSONObject().put("prompt_tokens",1).put("completion_tokens",1)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,encoded.length);exchange.getResponseBody().write(encoded);
            }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}});server.start();
        }
        void call(String name,int copies)throws Exception{
            JSONArray calls=new JSONArray();for(int i=0;i<copies;i++)calls.put(new JSONObject().put("id","controlled-call-"+requests.size()+"-"+i).put("type","function")
                .put("function",new JSONObject().put("name",TOOL).put("arguments",new JSONObject().put("name",name).toString())));replies.add(calls);
        }
        public void close(){server.stop(0);}
    }
    private EngineApplication app;private Queue queue;private ModelFixture model;private WorkspaceFixture workspace;
    private dev.pocketengine.features.NativePluginHost pluginFixture;private final List<JSONObject> events=new ArrayList<>();
    private static Object get(Object owner,Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void set(EngineApplication app,String name,Object value)throws Exception{Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);field.set(app,value);}
    @Before public void prepare()throws Exception{
        PresentationBoundary.afterPush=null;app=(EngineApplication)RuntimeEnvironment.getApplication();model=new ModelFixture();
        app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+model.server.getAddress().getPort()+"/v1").putString("model","controlled-approval-model")
            .putBoolean("sendScreenshots",false).putInt("maxSteps",8).putInt("requestTimeoutSeconds",10).putInt("maxOutputTokens",64).commit();
        app.tasks=new TaskStore(app);app.settings=new SettingsStore(app);app.features=new FeatureHost(events::add,app.tasks,()->false);app.addObserver(events::add);
        ((ExecutorService)get(app,EngineApplication.class,"capabilityWorkers")).shutdownNow();queue=new Queue();set(app,"capabilityWorkers",queue);
        workspace=new WorkspaceFixture();app.features.register(workspace);
        var plugins=new dev.pocketengine.features.plugins.PluginsModule(new dev.pocketengine.features.FeatureContext(app,app.settings,new dev.pocketengine.store.SecretVault(app),events::add));app.features.register(plugins);
        var live=EngineApplication.class.getDeclaredMethod("checkLiveExtensionControl",dev.pocketengine.runtime.extensions.ExtensionRuntime.Binding.class,RunControl.class);live.setAccessible(true);
        pluginFixture=new dev.pocketengine.features.NativePluginHost(app,plugins,app.tasks,(binding,control)->{
            try{live.invoke(app,binding,control);}catch(InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception exception)throw exception;if(cause instanceof Error error)throw error;throw new AssertionError(cause);}
        },ignored->{});set(app,"pluginHost",pluginFixture);
    }
    @After public void close()throws Exception{
        PresentationBoundary.afterPush=null;if(model!=null)model.close();if(queue!=null)queue.shutdownNow();if(pluginFixture!=null)pluginFixture.close();
        if(app!=null){app.features.close();shadowOf(Looper.getMainLooper()).idle();((android.database.sqlite.SQLiteDatabase)get(app.tasks,TaskStore.class,"db")).close();}
        if(model!=null&&model.failure.get()!=null)throw new AssertionError("controlled HTTP fixture failure",model.failure.get());
    }
    private JSONObject current(String id)throws Exception{return app.tasks.get(id,false);}
    private JSONObject approval(JSONObject task,int index)throws Exception{return task.getJSONArray("approvals").getJSONObject(index);}
    private JSONObject oneOperationForRun(JSONObject task,String run)throws Exception{
        JSONObject found=null;JSONArray operations=task.getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);if(!run.equals(row.getString("runId")))continue;assertNull("more than one operation for this run",found);found=row;}
        assertNotNull("missing original run operation",found);return found;
    }
    private JSONObject startWaiting(int tools,int models)throws Exception{
        String id=app.tasks.createCapabilityTask("请创建本地项目，名称严格为 "+NAME,"").getString("id");
        app.tasks.configureExecutionScope(id,0,0,tools,models);model.call(NAME,1);
        var start=EngineApplication.class.getDeclaredMethod("runCapabilityAttempt",String.class,JSONArray.class,ModelConfig.class,RunControl.class);start.setAccessible(true);
        try{start.invoke(app,id,null,app.settings.get(),new RunControl());}catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception failure)throw failure;if(cause instanceof Error failure)throw failure;throw new AssertionError(cause);}
        JSONObject task=current(id);assertEquals(task.toString(),"awaiting_user",task.getString("status"));assertEquals(1,model.requests.size());assertEquals(0,workspace.calls.size());
        assertEquals(1,task.getJSONArray("approvals").length());JSONObject pending=approval(task,0);assertEquals("pending",pending.getString("state"));assertEquals(TOOL,pending.getString("tool"));
        assertEquals(TaskStore.capabilityFingerprint(new JSONObject().put("name",NAME)),pending.getString("argumentsHash"));
        assertEquals("workspace",pending.getJSONObject("capability").getString("feature"));assertEquals("create",pending.getJSONObject("capability").getString("command"));
        assertEquals(1,task.getJSONArray("operations").length());assertFalse(app.tasks.hasUnresolved(id));
        JSONObject rejected=oneOperationForRun(task,task.getString("runId"));assertEquals(TOOL,rejected.getString("tool"));assertEquals("receipt",rejected.getString("state"));
        assertEquals("approval_required",rejected.getJSONObject("receipt").getString("code"));assertEquals(pending.getString("id"),rejected.getJSONObject("receipt").getString("approvalId"));
        assertEquals(1,task.getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(1,task.getJSONObject("executionScope").getInt("modelCallsUsed"));
        assertEquals(1,app.tasks.inferenceUsage(id).getJSONArray("receipts").length());return task;
    }
    private JSONObject approve(JSONObject original)throws Exception{return app.dispatchOwnerAction("approveCapability",new JSONObject().put("taskId",original.getString("id")).put("approvalId",approval(original,0).getString("id")).put("approved",true));}
    private void queued(JSONObject original)throws Exception{
        JSONObject receipt=approve(original);assertTrue(receipt.getBoolean("approvalDecisionSaved"));assertTrue(receipt.getBoolean("continuationScheduled"));assertFalse(receipt.getBoolean("continuedOriginalRun"));
        assertEquals(original.getString("runId"),current(original.getString("id")).getString("runId"));assertEquals(1,queue.pending.size());
    }
    private void proof(String scenario,JSONObject original,JSONObject settled)throws Exception{
        System.out.println("approval_consumption_journey "+new JSONObject().put("scenario",scenario).put("taskId",original.getString("id")).put("approvalId",approval(original,0).getString("id"))
            .put("originalRunId",original.getString("runId")).put("originalEpoch",original.getInt("coordinatorEpoch")).put("settledRunId",settled.getString("runId")).put("settledEpoch",settled.getInt("coordinatorEpoch"))
            .put("approvalState",approval(settled,0).getString("state")).put("approvalCount",settled.getJSONArray("approvals").length()).put("fixtureCalls",workspace.calls.size()).put("modelHttpCalls",model.requests.size())
            .put("operationCount",settled.getJSONArray("operations").length()).put("status",settled.getString("status")).put("executionScope",settled.getJSONObject("executionScope")).put("realBusinessEffects",false));
    }

    @Test public void ordinaryAssistantDecisionIsConsumedByTheSameToolAndExactArgumentsThroughTheRealRunner()throws Exception{
        JSONObject original=startWaiting(4,4);String id=original.getString("id"),saved=approval(original,0).toString();queued(original);model.call(NAME,1);queue.pickUp();JSONObject task=current(id);
        assertNotEquals(original.getString("runId"),task.getString("runId"));assertEquals(original.getInt("coordinatorEpoch")+1,task.getInt("coordinatorEpoch"));
        assertEquals(1,workspace.calls.size());assertEquals(NAME,workspace.calls.get(0).getString("name"));assertEquals(task.getString("runId"),workspace.calls.get(0).getString("_runId"));
        assertEquals(1,task.getJSONArray("approvals").length());JSONObject consumed=approval(task,0),before=new JSONObject(saved);
        assertEquals(before.getString("id"),consumed.getString("id"));assertEquals("consumed",consumed.getString("state"));assertEquals(task.getString("runId"),consumed.getString("runId"));
        for(String key:new String[]{"tool","argumentsHash","capabilityKey"})assertEquals(key,before.getString(key),consumed.getString(key));assertEquals(before.getLong("expiresAt"),consumed.getLong("expiresAt"));
        assertEquals(2,task.getJSONArray("operations").length());assertFalse(app.tasks.hasUnresolved(id));assertEquals(3,model.requests.size());assertEquals(3,app.tasks.inferenceUsage(id).getJSONArray("receipts").length());
        JSONObject rejected=oneOperationForRun(task,original.getString("runId")),executed=oneOperationForRun(task,task.getString("runId"));
        assertEquals(before.getString("id"),rejected.getJSONObject("receipt").getString("approvalId"));assertEquals("approval_required",rejected.getJSONObject("receipt").getString("code"));
        assertEquals(TOOL,executed.getString("tool"));assertEquals(NAME,executed.getJSONObject("arguments").getString("name"));assertEquals("receipt",executed.getString("state"));assertEquals("fixture_recorded",executed.getJSONObject("receipt").getString("code"));
        assertNotEquals(rejected.getString("operationId"),executed.getString("operationId"));
        assertEquals(2,task.getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(3,task.getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(4,task.getJSONObject("executionScope").getInt("toolLimit"));assertEquals(4,task.getJSONObject("executionScope").getInt("modelCallLimit"));
        assertTrue(queue.pending.isEmpty());proof("exact_consumed",original,task);
    }

    @Test public void changedArgumentsRequireAnotherApprovalWithoutConsumingTheOriginal()throws Exception{
        JSONObject original=startWaiting(4,4);queued(original);model.call("different unapproved name",1);queue.pickUp();JSONObject task=current(original.getString("id"));
        assertEquals("awaiting_user",task.getString("status"));assertEquals(0,workspace.calls.size());assertEquals(2,task.getJSONArray("approvals").length());assertEquals("approved",approval(task,0).getString("state"));assertEquals("pending",approval(task,1).getString("state"));
        assertNotEquals(approval(task,0).getString("argumentsHash"),approval(task,1).getString("argumentsHash"));assertEquals(2,model.requests.size());proof("arguments_changed",original,task);
    }

    @Test public void oneDecisionCannotAuthorizeASecondIdenticalToolCallInTheSameBatch()throws Exception{
        JSONObject original=startWaiting(4,4);queued(original);model.call(NAME,2);queue.pickUp();JSONObject task=current(original.getString("id"));
        assertEquals("awaiting_user",task.getString("status"));assertEquals(1,workspace.calls.size());assertEquals(2,task.getJSONArray("approvals").length());assertEquals("consumed",approval(task,0).getString("state"));assertEquals("pending",approval(task,1).getString("state"));
        assertNotEquals(approval(task,0).getString("id"),approval(task,1).getString("id"));assertEquals(3,task.getJSONObject("executionScope").getInt("toolsUsed"));proof("single_use",original,task);
    }

    @Test public void aSavedApprovalCannotGrantAnUnavailableTool()throws Exception{
        JSONObject original=startWaiting(4,4);queued(original);workspace.available=false;model.call(NAME,1);final boolean[] checked={false};
        PresentationBoundary.afterPush=()->{try{JSONObject admitted=current(original.getString("id"));assertThrows(SecurityException.class,()->app.tasks.checkCapabilityCurrent(admitted.getString("id"),admitted.getString("runId"),admitted.getInt("coordinatorEpoch"),TOOL));checked[0]=true;}catch(Exception error){throw new AssertionError(error);}};
        queue.pickUp();JSONObject task=current(original.getString("id"));assertTrue(checked[0]);assertEquals(0,workspace.calls.size());assertEquals("approved",approval(task,0).getString("state"));assertEquals(1,task.getJSONArray("approvals").length());proof("tool_withdrawn",original,task);
    }

    @Test public void exhaustedModelBudgetCannotBeRefilledByAnApprovedContinuation()throws Exception{exhaustedBudget(4,1,"model_budget");}
    @Test public void exhaustedToolBudgetCannotBeRefilledByAnApprovedContinuation()throws Exception{exhaustedBudget(1,4,"tool_budget");}
    private void exhaustedBudget(int tools,int models,String scenario)throws Exception{
        JSONObject original=startWaiting(tools,models);queued(original);queue.pickUp();JSONObject task=current(original.getString("id"));assertEquals(original.getString("runId"),task.getString("runId"));
        assertEquals(1,model.requests.size());assertEquals(0,workspace.calls.size());assertEquals("approved",approval(task,0).getString("state"));assertEquals(1,task.getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(1,task.getJSONObject("executionScope").getInt("modelCallsUsed"));
        assertTrue(events.stream().anyMatch(event->"error".equals(event.optString("type"))&&event.optString("message").startsWith("审批已保存，但未继续执行")));proof(scenario,original,task);
    }

    @Test public void aQueuedDecisionCannotFollowASuccessorRun()throws Exception{
        JSONObject original=startWaiting(4,4);queued(original);String id=original.getString("id");JSONObject successor=app.tasks.startAttempt(id,"capabilities",4);app.tasks.endAttempt(id,successor.getString("runId"),"awaiting_user","controlled successor has not dispatched",new JSONObject());
        queue.pickUp();JSONObject task=current(id);assertEquals(successor.getString("runId"),task.getString("runId"));assertEquals(successor.getInt("coordinatorEpoch"),task.getInt("coordinatorEpoch"));assertEquals(1,model.requests.size());assertEquals(0,workspace.calls.size());proof("successor_fenced",original,task);
    }

    @Test public void anUnknownToolReceiptRetainsTheOriginalOperationAndNeverAutomaticallyRepeatsIt()throws Exception{
        JSONObject original=startWaiting(4,4);queued(original);workspace.unknown=true;model.call(NAME,1);queue.pickUp();String id=original.getString("id");JSONObject task=current(id);
        assertEquals("reconciling",task.getString("status"));assertTrue(app.tasks.hasUnresolved(id));assertEquals(1,workspace.calls.size());assertEquals(2,model.requests.size());assertEquals("consumed",approval(task,0).getString("state"));
        assertEquals(2,task.getJSONArray("operations").length());String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
        JSONObject unknown=oneOperationForRun(task,run);assertEquals("unknown",unknown.getString("state"));assertEquals("fixture_unknown",unknown.getJSONObject("receipt").getString("code"));
        assertThrows(IllegalStateException.class,()->app.dispatchOwnerAction("resumeTask",new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch)));
        assertEquals(run,current(id).getString("runId"));assertTrue(queue.pending.isEmpty());assertEquals(1,workspace.calls.size());assertEquals(2,model.requests.size());proof("unknown_retained",original,task);
    }

    @Test public void anExpiredQueuedDecisionCannotStartTheRunner()throws Exception{
        JSONObject original=startWaiting(4,4);queued(original);String id=original.getString("id");
        // Test-only durable clock boundary: expire this exact saved row, not the task or grant.
        android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)get(app.tasks,TaskStore.class,"db");JSONObject document;
        try(android.database.Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{id})){assertTrue(row.moveToFirst());document=new JSONObject(row.getString(0));}
        approval(document,0).put("expiresAt",System.currentTimeMillis()-1);ContentValues value=new ContentValues();value.put("document",document.toString());assertEquals(1,db.update("tasks",value,"task_id=?",new String[]{id}));
        queue.pickUp();JSONObject task=current(id);assertEquals(original.getString("runId"),task.getString("runId"));assertEquals(0,workspace.calls.size());assertEquals(1,model.requests.size());assertEquals("approved",approval(task,0).getString("state"));proof("expired_fenced",original,task);
    }
}
