package dev.pocketengine.features.teams;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.*;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.store.*;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** One real original host-JNI/Node service prefix, then the actual parent Runner observes and
 * pauses its existing team. This is not ARM/UI/provider or the previous 36-stage development test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamParentOwnedServiceJourneyTest {
    private static Object call(Object fixture,String name,Class<?>[] types,Object...args)throws Exception{Method method=ProjectManagedRunnerJourneyTest.class.getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(fixture,args);}catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw error;}}
    private static Object field(Object fixture,String name)throws Exception{var f=ProjectManagedRunnerJourneyTest.class.getDeclaredField(name);f.setAccessible(true);return f.get(fixture);}
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject tool(String name,JSONObject args)throws Exception{return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",id()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));}
    private static JSONObject text(String value)throws Exception{return new JSONObject().put("role","assistant").put("content",value);}
    private static JSONObject last(JSONArray history)throws Exception{for(int i=history.length()-1;i>=0;i--)if("tool".equals(history.getJSONObject(i).optString("role")))return new JSONObject(history.getJSONObject(i).getString("content"));return new JSONObject();}
    private static boolean offered(JSONObject request,String name)throws Exception{JSONArray tools=request.getJSONArray("tools");for(int i=0;i<tools.length();i++)if(name.equals(tools.getJSONObject(i).getJSONObject("function").getString("name")))return true;return false;}
    private static void denied(java.util.concurrent.Callable<Boolean> operation)throws Exception{try{assertFalse(operation.call());}catch(SecurityException|IllegalStateException expected){}}
    private static JSONObject effect(CapabilityPort port,RunJournal journal,String name,JSONObject args,RunControl original)throws Exception{port.validate(name,args);String op=journal.beginOperation(name,args);JSONObject result=port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",op),original);journal.finishOperation(op,result);return result;}
    private static final class ModelPeer implements AutoCloseable {
        final HttpServer wire;final AtomicReference<Throwable> failure=new AtomicReference<>();final CountDownLatch childEntered=new CountDownLatch(1),releaseChild=new CountDownLatch(1);final AtomicInteger stage=new AtomicInteger();
        ProjectManagedRunnerJourneyTest fixture;TaskStore tasks;TeamCoordinator coordinator;String team,parentRun,parent;int epoch;JSONObject serviceBinding;RunJournal journal;
        ModelPeer()throws Exception{wire=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);wire.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)),reply=response(request);byte[] bytes=new JSONObject().put("id",id()).put("object","chat.completion").put("model",request.getString("model")).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",reply).put("finish_reason",reply.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",11).put("completion_tokens",4).put("total_tokens",15)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Throwable error){if(!(error instanceof java.io.IOException))failure.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});wire.start();}
        String base(){return "http://127.0.0.1:"+wire.getAddress().getPort()+"/v1";}
        JSONObject response(JSONObject request)throws Exception {
            if(offered(request,"team_prepare_draft")){childEntered.countDown();releaseChild.await(20,TimeUnit.SECONDS);return text("原请求已被本机暂停；没有写文件或声称成果。");}
            int next=stage.getAndIncrement();JSONObject latest=last(request.getJSONArray("messages"));
            if(next==0)return tool("teams_list",new JSONObject());
            if(next==1)return tool("teams_get",new JSONObject().put("teamRunId",team));
            if(next==2)return tool("teams_wait",new JSONObject().put("teamRunId",team).put("afterEvent",latest.getLong("eventCursor")).put("maxWait",300));
            if(next==3){assertTrue(latest.toString(),latest.optBoolean("timedOut")||latest.has("events"));call(fixture,"probeControlledService",new Class<?>[]{String.class},"First service result");JSONObject run=coordinator.get(team);return tool("teams_control",new JSONObject().put("teamRunId",team).put("action","pause").put("expectedTeamRevision",run.getLong("revision")).put("expectedGeneration",run.getInt("generation")));}
            if(next==4){assertTrue(latest.getBoolean("stopRequested"));releaseChild.countDown();assertEquals("paused",coordinator.get(team).getString("status"));call(fixture,"probeControlledService",new Class<?>[]{String.class},"First service result");checkForgedAndUnknownRows();JSONObject run=coordinator.get(team),item=run.getJSONObject("workPlan").getJSONArray("items").getJSONObject(0),member=TeamWorkPlan.find(run.getJSONArray("members"),item.getString("memberId"));return tool("teams_control",new JSONObject().put("teamRunId",team).put("action","resume").put("expectedTeamRevision",run.getLong("revision")).put("expectedGeneration",run.getInt("generation")).put("memberId",member.getString("id")).put("expectedMemberGeneration",member.getInt("generation")).put("workItemId",item.getString("workItemId")).put("itemRevision",item.getInt("itemRevision")).put("ownerGeneration",item.getInt("ownerGeneration")));}
            if(next==5){assertEquals("project_service_conflict",latest.getString("code"));call(fixture,"probeControlledService",new Class<?>[]{String.class},"First service result");return text("同一活服务仍真实响应；仅观察和暂停原团队。恢复/写入未派发，下一原run结束必须真实排空。");}
            throw new AssertionError("unexpected parent model request "+next);
        }
        void checkForgedAndUnknownRows()throws Exception {
            synchronized(tasks){var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)f.get(tasks);db.beginTransaction();try {
                JSONArray operations=tasks.get(parent,false).getJSONArray("operations");JSONObject read=null,stop=null;for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);if("teams_wait".equals(row.optString("tool")))read=row;if("teams_control".equals(row.optString("tool")))stop=row;}assertNotNull(read);assertNotNull(stop);
                ContentValues value=new ContentValues();value.put("state","unknown");db.update("operations",value,"operation_id=?",new String[]{read.getString("operationId")});read.put("state","unknown");assertTrue("only native recorded read + its original pool reservation is safe",tasks.projectServiceAllowsTeamObservation(serviceBinding,read));
                JSONObject forged=new JSONObject(read.toString());forged.getJSONObject("arguments").put("teamRunId",id());denied(()->tasks.projectServiceAllowsTeamObservation(serviceBinding,forged));JSONObject otherRun=new JSONObject(serviceBinding.toString()).put("runId",id());JSONObject originalRead=read;denied(()->tasks.projectServiceAllowsTeamObservation(otherRun,originalRead));
                db.update("operations",value,"operation_id=?",new String[]{stop.getString("operationId")});stop.put("state","unknown");JSONObject finalStop=stop;denied(()->tasks.projectServiceAllowsTeamObservation(serviceBinding,finalStop));
                // Roll the control fault back locally, then inject one unrelated uncertain write.
                ContentValues settled=new ContentValues();settled.put("state","receipt");db.update("operations",settled,"operation_id=?",new String[]{stop.getString("operationId")});String fault=id();ContentValues unknown=new ContentValues();unknown.put("operation_id",fault);unknown.put("task_id",parent);unknown.put("run_id",parentRun);unknown.put("tool","workspace_write");unknown.put("arguments","{}");unknown.put("state","unknown");unknown.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,unknown);JSONObject finalRead=read;denied(()->tasks.projectServiceAllowsTeamObservation(serviceBinding,finalRead));assertThrows(IllegalStateException.class,journal::restore);
            }finally{db.endTransaction();} // Only explicitly injected fault rows/states roll back; no real process/effect is hidden.
            }
        }
        @Override public void close(){releaseChild.countDown();wire.stop(0);}
    }
    @Test public void originalNativeOwnedServiceRemainsAliveDuringParentTeamReadWaitAndPauseButResumeAndUnknownWritersStayDenied()throws Exception {
        ProjectManagedRunnerJourneyTest factory=new ProjectManagedRunnerJourneyTest();FeatureHost features=null;ProjectCapabilityPort port=null;factory.create();
        try(ModelPeer model=new ModelPeer()){
            call(factory,"selectedProject",new Class<?>[]{String.class},"Actual service and original parent team");call(factory,"file",new Class<?>[]{String.class,String.class},"main.mjs",call(null,"serviceScript",new Class<?>[]{String.class},id()));call(factory,"file",new Class<?>[]{String.class,String.class},"message.mjs","export const title = 'First service result';\n");call(factory,"file",new Class<?>[]{String.class,String.class},"a.txt","ORIGINAL a.txt\n");call(factory,"startTask",new Class<?>[]{String.class},"Observe and pause this existing original team while its original native project service runs");JSONObject task=(JSONObject)call(factory,"startFixtureAttempt",new Class<?>[]{});
            TaskStore tasks=(TaskStore)field(factory,"tasks");LocalModule local=(LocalModule)field(factory,"local");WorkspaceModule workspace=(WorkspaceModule)field(factory,"workspace");String parent=task.getString("id"),run=task.getString("runId"),project=(String)field(factory,"project"),checkout=(String)field(factory,"checkout");int epoch=task.getInt("coordinatorEpoch");@SuppressWarnings("unchecked") RunControl original=((AtomicReference<RunControl>)field(factory,"live")).get();
            SettingsStore settings=new SettingsStore(RuntimeEnvironment.getApplication());settings.save(new JSONObject().put("baseUrl",model.base()).put("apiKey","").put("model","parent-service-observer").put("maxSteps",40).put("sendScreenshots",false).put("requestTimeoutSeconds",25).put("maxOutputTokens",512).put("provider","openai_chat"));
            features=new FeatureHost(event->{},tasks,()->false);features.register(workspace);TeamsModule teams=new TeamsModule(new FeatureContext(RuntimeEnvironment.getApplication(),settings,new SecretVault(RuntimeEnvironment.getApplication()),event->{}));features.register(teams);teams.setInferenceFactory((call,control)->tasks.teamInferenceTarget(call.nativeRunId,call.purpose,call.memberId,call.site,control,call::checkCurrent));var f=TeamsModule.class.getDeclaredField("coordinator");f.setAccessible(true);TeamCoordinator coordinator=(TeamCoordinator)f.get(teams);
            String teamId=teams.handle("save",new JSONObject().put("name","Single original paused author").put("mode","collaborate").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","原作者").put("role","只在原草稿工作").put("runner","model").put("device","phone").put("model","author-a")))).getString("id");
            HostToolSupervisor existing=new HostToolSupervisor(features,tasks,parent,run,epoch,true);var binding=new ModelCapabilityAdapters.Binding(parent,run,epoch,original,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,existing,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(parent,run,names);RunJournal journal=tasks.journal(parent,run,epoch,original);
            JSONObject item=new JSONObject().put("key","first").put("title","原文字文件").put("instruction","只修改a.txt的原隔离草稿").put("acceptance","最终仍须独审和owner准确批准").put("memberId","a").put("paths",new JSONArray().put("a.txt")).put("dependsOn",new JSONArray());JSONObject plan=effect(port,journal,"teams_plan",new JSONObject().put("teamId",teamId).put("goal","在原权限内制作文字文件").put("workItems",new JSONArray().put(item)).put("modelRequestLimit",50).put("toolOperationLimit",32),original);JSONObject startArgs=new JSONObject().put("planRef",plan.getString("planRef")).put("planHash",plan.getString("planHash"));JSONObject pending=effect(port,journal,"teams_start",startArgs,original);tasks.teamParentPlanForOwner(parent,pending.getString("approvalId"));tasks.decideCapabilityApproval(parent,pending.getString("approvalId"),true);JSONObject started=effect(port,journal,"teams_start",startArgs,original);String team=started.getString("teamRunId");assertTrue(model.childEntered.await(8,TimeUnit.SECONDS));
            JSONObject inspection=port.execute("project_inspect",new JSONObject(),original),planned=port.execute("project_plan",new JSONObject().put("intent","run").put("runMode","owned_service").put("ecosystem","node").put("expectedSourceDigest",inspection.getString("sourceDigest")),original),nativePlan=planned.getJSONObject("plan");call(factory,"approveControlledOwnerPlan",new Class<?>[]{JSONObject.class},nativePlan);JSONObject ack=effect(port,journal,"project_execute",new JSONObject().put("plan",nativePlan).put("stepId",nativePlan.getJSONArray("steps").getJSONObject(0).getString("id")),original);assertEquals("project_running",ack.getString("code"));assertTrue(journal.hasNativeProjectRunningAck(ack.getString("operationId"),ack));call(factory,"probeControlledService",new Class<?>[]{String.class},"First service result");
            model.fixture=factory;model.tasks=tasks;model.coordinator=coordinator;model.team=team;model.parent=parent;model.parentRun=run;model.epoch=epoch;model.serviceBinding=ack.getJSONObject("binding");model.journal=journal;ContextBudgetPolicy.installHostSource(c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
            RunOutcome outcome=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource()).run(parent,task.getString("goal"),settings.get(),original,event->{try{tasks.event(parent,run,epoch,event);}catch(Exception error){throw new IllegalStateException(error);}},journal);if(model.failure.get()!=null)throw new AssertionError(model.failure.get());assertEquals(6,model.stage.get());assertEquals("paused",coordinator.get(team).getString("status"));assertFalse(original.isCancelled());assertFalse("normal original run end must really release the service",tasks.hasUnresolved(parent));JSONObject exits=journal.projectServiceExitEvidence(new JSONArray().put(ack.getString("operationId")));assertNotNull(exits);assertTrue(exits.getBoolean("allReleased"));assertNotEquals("completed",outcome.status);port.close();port=null;
        }finally{if(port!=null)port.close();if(features!=null)features.close();factory.close();ContextBudgetPolicy.installHostSource(c->null);}
    }
}
