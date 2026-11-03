package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.store.*;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Original owner page -> frozen source -> SQL child/Runner/read/pool. Model replies
 * are an independent loopback peer, not vendor inference or a public-web success. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamResearchScopedReadNativeJourneyTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static SQLiteDatabase sql(TaskStore tasks)throws Exception{var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return(SQLiteDatabase)f.get(tasks);}
    private static JSONObject text(String content)throws Exception{return new JSONObject().put("role","assistant").put("content",content);}
    private static JSONObject readCall()throws Exception{return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",id()).put("type","function").put("function",new JSONObject().put("name",TeamResearchReadAccess.READ).put("arguments",new JSONObject().put("fileIndex",0).toString()))));}
    private interface NativeReadCase {JSONObject run(TaskStore.TeamResearchBinding binding,TeamCoordinator.ResearchCall call,RunControl control)throws Exception;}
    private static TaskStore issuer(TaskStore.TeamResearchBinding binding)throws Exception{var f=TaskStore.TeamResearchBinding.class.getDeclaredField("issuer");f.setAccessible(true);return(TaskStore)f.get(binding);}
    private static Throwable workerError(TaskStore.TeamMemberRead exact)throws Exception {
        CompletableFuture<Throwable> result=new CompletableFuture<>();Thread worker=new Thread(()->{try{exact.checkNativeObservationCurrent();result.complete(null);}catch(Throwable error){result.complete(error);}},"native-research-read-proof");worker.setDaemon(true);worker.start();
        try{return result.get(3,TimeUnit.SECONDS);}finally{worker.interrupt();worker.join(3000);assertFalse("native proof worker must really exit",worker.isAlive());}
    }

    private static final class Fixture implements AutoCloseable {
        TaskStore tasks;FeatureHost features;WorkspaceModule workspace;
        TeamMemberEvidenceHost materials;TeamResearchHost research;TeamCoordinator coordinator;
        HttpServer server;ModelConfig model;final String run=id();String project;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicReference<JSONObject> manualResult=new AtomicReference<>();
        final AtomicInteger http=new AtomicInteger(),bCalls=new AtomicInteger();final boolean peer;
        TeamMemberEvidenceHost.Page page;
        Fixture(boolean peer,NativeReadCase nativeCase)throws Exception {
            this.peer=peer;try{var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);
            features=new FeatureHost(event->{},tasks,()->false);workspace=new WorkspaceModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));features.register(workspace);
            project=workspace.handle("create",new JSONObject().put("name","explicit research "+id())).getString("id");
            workspace.handle("write",new JSONObject().put("projectId",project).put("path","notes.txt").put("expectedVersion",0).put("content","PROJECT_SELECTED_VALUE_原文件"));
            materials=new TeamMemberEvidenceHost(features,tasks,source->{throw new AssertionError("private body reader was not shared");},source->{throw new AssertionError("private SourceRef was not shared");});
            research=new TeamResearchHost(tasks,materials);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));http.incrementAndGet();
                String selected=request.getString("model");JSONObject answer;
                if(selected.equals("member-b")){bCalls.incrementAndGet();throw new AssertionError("unshared project body must not reach B");}
                if(selected.equals("member-a")&&!request.getJSONArray("tools").toString().contains("team_read_output")){
                    JSONArray history=request.getJSONArray("messages");JSONObject receipt=null;
                    for(int i=0;i<history.length();i++)if("tool".equals(history.getJSONObject(i).optString("role"))){JSONObject value=new JSONObject(history.getJSONObject(i).getString("content"));if(value.has("fileIndex"))receipt=value;}
                    if(receipt==null){String offered=request.getJSONArray("tools").toString();assertTrue("project read absent from actual tools: "+offered+"; scope="+tasks.teamMemberMaterialScope(run,"a"),offered.contains(TeamResearchReadAccess.READ));assertTrue(offered,offered.contains(TeamResearchReadAccess.FILES));answer=readCall();}
                    else{assertEquals("PROJECT_SELECTED_VALUE_原文件",receipt.getString("content"));assertEquals("untrusted_selected_project_file",receipt.getString("sourceRole"));assertFalse(receipt.getBoolean("executionAuthorized"));answer=text("核对 PROJECT_SELECTED_VALUE_原文件；这是所选原文件的观察，不是执行或业务证明。");}
                }else answer=text("原父整合已取得的只读观察；保留来源和未确定项，不新增执行权限。");
                byte[] bytes=new JSONObject().put("model",selected).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",12).put("completion_tokens",6).put("total_tokens",18)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){failure.compareAndSet(null,error);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-local-key","coordinator",false,12,30,"openai_chat",512);
            ContextBudgetPolicy.installHostSource(config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(128000,config.maxOutputTokens,1024,1048576,128,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
            coordinator=new TeamCoordinator(new File(app.getFilesDir(),"scoped-research-"+id()+".json"),new TeamCoordinator.ToolGateway(){
                public JSONObject complete(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control)throws Exception{throw new AssertionError("no original inference attribution");}
                public JSONObject complete(ModelConfig config,JSONArray messages,JSONArray tools,RunControl control,TeamCoordinator.InferenceCall call)throws Exception{return ModelGateway.complete(config,messages,tools,control,InferenceCapture.withAdmission(tasks.teamInferenceTarget(call.nativeRunId,call.purpose,call.memberId,call.site,control,call::checkCurrent),call::checkSourcesCurrent));}
            },event->{try{if(event.optJSONObject("run")!=null)tasks.updateExternal(run,event.getJSONObject("run"));}catch(Throwable error){failure.compareAndSet(null,error);}});
            coordinator.setBudgetHost(new TaskStoreTeamWriteHost(tasks,new TeamWorkspaceAccess(workspace),changed->{}));
            coordinator.setResearchFactory(new TeamCoordinator.ResearchFactory(){
                public JSONObject execute(TeamCoordinator.ResearchCall call,ModelConfig captured,RunControl control,java.util.function.Consumer<JSONObject> progress)throws Exception{
                    if(call.memberId.equals("a")){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}
                    if(nativeCase==null)try{return research.execute(call,captured,control,progress);}catch(Error error){failure.compareAndSet(null,error);throw error;}
                    TaskStore.TeamResearchBinding binding=tasks.beginTeamResearch(call,control);
                    try{JSONObject result=nativeCase.run(binding,call,control);manualResult.set(result);tasks.interruptTeamResearch(binding,"interrupted");return tasks.teamResearchResult(binding);}
                    catch(Exception|Error error){failure.compareAndSet(null,error);tasks.interruptTeamResearch(binding,"interrupted");throw error;}
                }
                public boolean hasActiveWork(String team){return research.hasActiveWork(team);}
                public void stop(String team,String member){research.stop(team,member);}
                public JSONObject admitPeer(TeamCoordinator.ResearchCall call,String message)throws Exception{return research.admitPeer(call,message);}
                public void checkOutputs(TeamCoordinator.ResearchSources source)throws Exception{research.checkOutputs(source);}
            });
            tasks.beginExternal("teams","start",run,new JSONObject().put("goal","明确只读网页与项目文件"));
            JSONArray members=new JSONArray().put(new JSONObject().put("id","a").put("name","原研究A").put("role","核对显选资料").put("model","member-a"));
            if(peer)members.put(new JSONObject().put("id","b").put("name","原研究B").put("role","没有分享文件的核对者").put("model","member-b").put("dependsOn",new JSONArray().put("a")));
            coordinator.start(new JSONObject().put("id",id()).put("name","原研究范围").put("mode","collaborate").put("leaderId","a").put("members",members),"只读核对准确原文件，不继承私人资料",1,model,run,materials,null);
            assertTrue(entered.await(8,TimeUnit.SECONDS));page=materials.open(coordinator,run,"a");page.setForeground(true);
            JSONObject input=new JSONObject().put("allowedRecords",new JSONArray()).put("allowedSources",new JSONArray()).put("skills",new JSONArray()).put("resources",new JSONArray()).put("researchReadAccess",new JSONObject().put("webOrigins",new JSONArray().put("https://example.org")).put("projectFiles",new JSONArray().put(new JSONObject().put("projectId",project).put("path","notes.txt"))));
            var review=page.review(input);page.approve(review,review.view().getString("digest"));release.countDown();
            await();resumeNative();
            }catch(Exception|Error error){try{close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}throw error;}
        }
        void await()throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<until){if(failure.get()!=null)throw new AssertionError(failure.get());JSONObject view=coordinator.get(run);if(idle()&&!view.optString("status").matches("running|queued"))return;Thread.sleep(15);}throw new AssertionError("original research did not settle "+coordinator.get(run));
        }
        boolean idle()throws Exception {
            var runs=TeamCoordinator.class.getDeclaredField("runs");runs.setAccessible(true);Object nativeRun=((java.util.Map<?,?>)runs.get(coordinator)).get(run);
            var children=nativeRun.getClass().getDeclaredField("children");children.setAccessible(true);synchronized(coordinator){return ((java.util.Map<?,?>)children.get(nativeRun)).isEmpty();}
        }
        void resumeNative()throws Exception {
            JSONObject view=coordinator.get(run),member=TeamWorkPlan.find(view.getJSONArray("members"),"a"),input=new JSONObject().put("runId",run).put("expectedRevision",view.getLong("revision")).put("expectedGeneration",view.getInt("generation"));
            if(!peer)input.put("memberId","a").put("expectedMemberGeneration",member.getInt("generation"));
            String operation=tasks.requireReviewedTeamControl(input);JSONObject receipt=coordinator.reviewedControl("resume",input,model,materials,null);tasks.updateReviewedTeamControl(operation,input,receipt);
        }
        JSONObject child(String member)throws Exception{return tasks.get(TeamWorkPlan.find(coordinator.get(run).getJSONArray("members"),member).getJSONArray("roundStates").getJSONObject(0).getString("researchTaskId"),false);}
        public void close()throws Exception {
            release.countDown();if(page!=null)page.close();if(coordinator!=null)coordinator.close();
            if(coordinator!=null)for(String name:new String[]{"coordinator","members"}){var field=TeamCoordinator.class.getDeclaredField(name);field.setAccessible(true);assertTrue(((ExecutorService)field.get(coordinator)).awaitTermination(5,TimeUnit.SECONDS));}
            if(server!=null)server.stop(0);if(features!=null)features.close();if(tasks!=null)sql(tasks).close();ContextBudgetPolicy.installHostSource(config->null);
        }
    }

    @Test public void actualOwnerSelectionReachesOriginalRunnerSqlReadAndSingleParentPool()throws Exception {
        try(Fixture f=new Fixture(false,null)){
            f.await();JSONObject child=f.child("a"),pool=f.tasks.teamBudgetSnapshot(f.run);assertEquals(f.coordinator.get(f.run).toString(),"completed",child.getString("status"));assertEquals("completed",f.coordinator.get(f.run).getString("status"));
            assertEquals(1,pool.getInt("toolOperationsReservedOrUsed"));assertEquals(f.http.get(),pool.getInt("modelRequestsReservedOrUsed"));assertFalse(f.tasks.hasUnresolved(child.getString("id")));
            assertEquals(f.project,child.getJSONObject("teamResearch").getJSONObject("materialScope").getJSONObject("researchReadAccess").getJSONArray("projectFiles").getJSONObject(0).getString("projectId"));
            try(Cursor rows=sql(f.tasks).rawQuery("SELECT COUNT(*),COUNT(DISTINCT operation_id) FROM operations WHERE task_id=? AND tool='team_project_read' AND state='receipt'",new String[]{child.getString("id")})){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));assertEquals(1,rows.getInt(1));}
        }
    }
    @Test public void exactProjectProducerScopeDoesNotLeakIntoUnsharedDependentMember()throws Exception {
        try(Fixture f=new Fixture(true,null)){
            f.await();assertEquals(f.coordinator.get(f.run).toString(),"completed",f.child("a").getString("status"));assertEquals("partial",f.coordinator.get(f.run).getString("status"));assertEquals(0,f.bCalls.get());
            assertEquals(0,f.tasks.teamMemberMaterialScope(f.run,"b").getLong("revision"));assertEquals(1,f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed"));
            JSONObject b=TeamWorkPlan.find(f.coordinator.get(f.run).getJSONArray("members"),"b");assertEquals("TeamResearchInputDenied",b.getJSONArray("roundStates").getJSONObject(0).getString("nativeErrorType"));
        }
    }
    @Test public void nativeWorkerOnlyChecksTheExactAlreadyObservingReadAndCannotReplayAfterSettlement()throws Exception {
        AtomicReference<TaskStore.TeamMemberRead> kept=new AtomicReference<>();
        try(Fixture f=new Fixture(false,(binding,call,control)->{
            TaskStore tasks=issuer(binding);JSONObject args=new JSONObject().put("url","https://example.org/article");
            TaskStore.TeamResearchRead operation=tasks.beginTeamResearchRead(binding,TeamResearchReadAccess.WEB,args,control);
            JSONObject value=operation.observe(()->{
                TaskStore.TeamMemberRead read=tasks.currentTeamResearchMemberRead(binding,call.evidenceCaller(),TeamResearchReadAccess.WEB,args,control);kept.set(read);
                assertNotNull("unstarted body observation cannot grant a worker",workerError(read));
                assertThrows(SecurityException.class,()->tasks.currentTeamResearchMemberRead(binding,call.evidenceCaller(),TeamResearchReadAccess.WEB,new JSONObject().put("url","https://example.org/different"),control));
                JSONObject actual=read.observe(()->{assertNull(workerError(read));return new JSONObject().put("status","native_worker_scope_checked").put("networkPerformed",false);});
                read.markReturned();return actual;
            });
            tasks.finishTeamNativeRead(operation.view(),value,false);assertNotNull(workerError(kept.get()));return value;
        })){
            f.await();assertFalse(f.manualResult.get().getBoolean("networkPerformed"));assertEquals(1,f.tasks.teamBudgetSnapshot(f.run).getInt("toolOperationsReservedOrUsed"));
            assertFalse(f.tasks.hasUnresolved(f.child("a").getString("id")));assertNotNull(workerError(kept.get()));
        }
    }
    @Test public void nativeWorkerStopsOnPauseResumeRevokeCancelAndAnotherUnknownWithoutRefundOrReplay()throws Exception {
        for(String mode:new String[]{"pause_resume","revoke","cancel","other_unknown"}){
            AtomicReference<TaskStore.TeamMemberRead> kept=new AtomicReference<>();
            try(Fixture f=new Fixture(false,(binding,call,control)->{
                TaskStore tasks=issuer(binding);JSONObject args=new JSONObject().put("url","https://example.org/article");
                TaskStore.TeamResearchRead operation=tasks.beginTeamResearchRead(binding,TeamResearchReadAccess.WEB,args,control);
                try{
                    operation.observe(()->{
                        TaskStore.TeamMemberRead read=tasks.currentTeamResearchMemberRead(binding,call.evidenceCaller(),TeamResearchReadAccess.WEB,args,control);kept.set(read);
                        return read.observe(()->{
                            assertNull(workerError(read));
                            if(mode.equals("pause_resume")){control.pause();assertNotNull(workerError(read));control.resume();}
                            if(mode.equals("cancel"))control.cancel();
                            if(mode.equals("revoke"))try(TaskStore.TeamMemberMaterialWindow window=tasks.openTeamMemberMaterialWindow(call.teamRunId)){window.setForeground(true);tasks.revokeTeamMemberMaterials(window,call.memberId,tasks.teamMemberMaterialScope(call.teamRunId,call.memberId).getLong("revision"));}
                            if(mode.equals("other_unknown"))tasks.beginTeamResearchRead(binding,TeamResearchReadAccess.WEB,new JSONObject().put("url","https://example.org/other"),control);
                            assertNotNull(mode,workerError(read));throw new SecurityException("expected stopped native observation: "+mode);
                        });
                    });fail("stopped observation cannot publish or repeat a read");
                }catch(SecurityException|InterruptedException expected){
                    tasks.finishTeamNativeRead(operation.view(),new JSONObject().put("status","unknown").put("networkPerformed",false),true);
                }
                assertNotNull(workerError(kept.get()));return new JSONObject().put("denied",mode).put("networkPerformed",false);
            })){
                f.await();assertEquals(mode,f.manualResult.get().getString("denied"));JSONObject child=f.child("a"),pool=f.tasks.teamBudgetSnapshot(f.run);
                assertTrue(mode,f.tasks.hasUnresolved(child.getString("id")));assertEquals(mode,mode.equals("other_unknown")?2:1,pool.getInt("toolOperationsReservedOrUsed"));
                assertNotEquals("completed",child.getString("status"));assertNotNull(workerError(kept.get()));
            }
        }
    }
}
