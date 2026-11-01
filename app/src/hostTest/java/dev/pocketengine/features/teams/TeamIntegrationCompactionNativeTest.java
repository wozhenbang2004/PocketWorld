package dev.pocketengine.features.teams;

import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Original native approvals/SQLite/pool + genuine SUMMARY HTTP; saved historical bytes are DATA, not grants. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamIntegrationCompactionNativeTest {
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object invoke(Object object,String name)throws Exception{Method m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);try{return m.invoke(object);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
    private static JSONObject item(String member,String path)throws Exception{return new JSONObject().put("workItemId",UUID.randomUUID().toString()).put("memberId",member).put("title","交付 "+path).put("instruction","只准确交付 "+path).put("acceptance","正文须为 DELIVERED "+path).put("paths",new JSONArray().put(path)).put("dependsOn",new JSONArray());}
    private static JSONObject textTool(String name,JSONObject args)throws Exception{return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",UUID.randomUUID().toString()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));}
    private static List<Integer> indexes(JSONArray messages){List<Integer> result=new ArrayList<>();for(int i=0;i<messages.length();i++)result.add(i);return result;}
    private static final class Fixture implements AutoCloseable {
        Object originalFactory;TaskStore tasks;TaskStoreTeamWriteHost host;TeamCoordinator coordinator;String team;RunControl control=new RunControl();JSONObject binding;TeamIntegrationCompaction session;CapabilityPort port;HttpServer provider;AutoCloseable retained;
        final AtomicReference<Throwable> providerFailure=new AtomicReference<>();final AtomicInteger posts=new AtomicInteger(),captureSequence=new AtomicInteger();volatile boolean cancelDuringSummary;
        JSONArray raw;String rawOriginal;ModelConfig selected;ContextBudgetPolicy.Source narrow;ContextCompactor compactor;RunContextBudget budget;
        Fixture()throws Exception {
            try{
                // Reuse the current actual project fixture's native discussion/owner APIs, but
                // approve only two TEXT work items here. No command or private evidence bridge.
                TeamProjectRunnerDeliveryJourneyTest.Fixture current=new TeamProjectRunnerDeliveryJourneyTest.Fixture(false);
                originalFactory=current;tasks=current.tasks;host=current.host;coordinator=current.coordinator;team=current.run;ModelConfig nativeModel=current.model;
                current.compactionTextAuthors=true;current.extraModelResponse=request->{
                    String member=request.getString("model").equals("author-b")?"b":"a";JSONObject task=current.activeTask(member);assertNotNull(task);JSONObject draft=current.lastRead(task,"team_prepare_draft");
                    if(draft==null)return textTool("team_prepare_draft",new JSONObject());String path=task.getJSONObject("teamAssignment").getJSONArray("files").getJSONObject(0).getString("path");
                    if(current.lastRead(task,"team_read_file")==null)return textTool("team_read_file",new JSONObject().put("path",path));
                    JSONObject actual=current.workspace.handle("read",new JSONObject().put("projectId",draft.getJSONObject("teamDraft").getString("draftProjectId")).put("path",path));String desired="DELIVERED "+path+"\n";
                    if(!desired.equals(actual.getString("content")))return textTool("team_write_file",new JSONObject().put("path",path).put("content",desired).put("expectedVersion",actual.getInt("version")));
                    JSONObject identity=new JSONObject(task.getJSONObject("teamAssignment").toString()).put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch"));
                    if(tasks.teamAssignmentReceipts(identity,"team_freeze_outputs").length()==0)return textTool("team_freeze_outputs",new JSONObject());return new JSONObject().put("role","assistant").put("content","本项准确文字成果已冻结，等待原独审与主人批准。");
                };
                try(TaskStoreTeamWriteHost.WorkPlanPage page=host.newWorkPlanPage(coordinator,team,()->nativeModel)){
                    page.setForeground(true);TaskStore.TeamWorkReview review=page.review(new JSONArray().put(item("a","a.js")).put(item("b","b.js")));page.approve(review,review.view().getString("digest"));
                }
                assertEquals("awaiting_review",((JSONObject)invoke(originalFactory,"settled")).getString("status"));
                JSONObject files=host.review(team,new RunControl()).snapshot();JSONObject independent=host.independentlyReview(team,files.getString("planHash"),coordinator.get(team),nativeModel.withModel("reviewer"));assertEquals("no_blocking_findings",independent.getString("verdict"));assertTrue(independent.getBoolean("completeOriginalFilesRead"));coordinator.recordIndependentReview(team,independent);
                JSONObject approved=host.approve(team,independent.getString("planHash"),new RunControl());binding=tasks.startTeamIntegration(team,approved.getString("reviewId"),control,24);assertFalse(binding.optBoolean("inspectOnly"));
                provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);provider.createContext("/",exchange->{try{
                    JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));posts.incrementAndGet();assertEquals("integration-checkpoint-fixture",request.getString("model"));
                    if(cancelDuringSummary)control.cancel();
                    JSONObject answer=new JSONObject().put("role","assistant").put("content","已结算的原同child历史索引：V1旧包只供核对；V2有准确独审与CAS，组合检查须按原native版本继续。所有原始工具结果保留可回读，本段不授予新执行或主人批准。");
                    byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",answer).put("finish_reason","stop"))).put("usage",new JSONObject().put("prompt_tokens",200).put("completion_tokens",30).put("total_tokens",230)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Throwable failed){providerFailure.compareAndSet(null,failed);}finally{exchange.close();}});provider.start();
                selected=new ModelConfig("http://127.0.0.1:"+provider.getAddress().getPort()+"/v1","summary-fixture-secret","integration-checkpoint-fixture",false,24,10,"openai_chat",512);
                TeamWorkFiles.Workspace workspace=((TeamProjectRunnerDeliveryJourneyTest.Fixture)originalFactory).files;CapabilityPort filesPort=new TeamIntegrationFiles(workspace,new TaskStoreTeamAssignments(tasks,changed->{}).fileHost(binding,control),control,binding);
                session=TeamIntegrationCompaction.open(tasks,binding,control,selected,filesPort);retained=control.retainAuthorityCheck(session::checkCurrent);port=new TaskStoreTeamAssignments(tasks,changed->{}).meter(session.port(),binding);
                Path historical=Path.of(System.getProperty("c6.saved.integration.checkpoint"));byte[] bytes=Files.readAllBytes(historical);assertEquals(108884,bytes.length);assertEquals("88666ea7640a94a9e7dcac5cb890f5af101f738959601f54816b0099f4571cc6",dev.pocketengine.features.local.project.ProjectSources.sha256(bytes));JSONObject checkpoint=new JSONObject(new String(bytes,StandardCharsets.UTF_8));raw=checkpoint.getJSONArray("messages");assertEquals(87,raw.length());rawOriginal=raw.toString();
                session.journal().checkpoint(raw,checkpoint.getInt("steps"),checkpoint.getJSONObject("observation"));
                // Narrow trigger is deliberately SMALLER than the unchanged 128k whole journey.
                narrow=c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(96000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
                budget=new RunContextBudget(narrow);compactor=new ContextCompactor(session.journal(),session.journal().compaction(),narrow);
            }catch(Exception|Error failed){try{close();}catch(Throwable cleanup){failed.addSuppressed(cleanup);}throw failed;}
        }
        RunContextBudget.Projection request()throws Exception{try{return compactor.request(raw,new JSONArray(raw.toString()),indexes(raw),port.tools(),selected,control,budget);}catch(Exception|Error failed){try{captureOriginalCheckpoint("original_request_exception");}catch(Throwable diagnostic){failed.addSuppressed(diagnostic);}throw failed;}}
        JSONObject pool()throws Exception{return tasks.teamBudgetSnapshot(team);}
        void captureOriginalCheckpoint(String stage)throws Exception {
            stage=stage+"-"+captureSequence.incrementAndGet();
            var nativeField=TaskStore.class.getDeclaredField("db");nativeField.setAccessible(true);android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)nativeField.get(tasks);String encoded;
            try(android.database.Cursor rows=db.rawQuery("SELECT checkpoint FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")+""})){assertTrue(rows.moveToFirst());encoded=rows.getString(0);}
            Path directory=Path.of(System.getProperty("a25.evidence"));Files.createDirectories(directory);Path original=directory.resolve(stage+"-"+binding.getString("taskId")+"-original-checkpoint.json");Files.writeString(original,encoded);
            JSONObject stored=new JSONObject(encoded),published=stored.optJSONObject("contextCompaction"),facts=new JSONObject().put("stage",stage).put("binding",binding).put("originalCheckpoint",original.toString()).put("checkpointUtf8Bytes",encoded.getBytes(StandardCharsets.UTF_8).length).put("checkpointSha256",dev.pocketengine.features.local.project.ProjectSources.sha256(encoded.getBytes(StandardCharsets.UTF_8))).put("providerPosts",posts.get()).put("pauseRevision",control.pauseRevision()).put("paused",control.isPaused()).put("cancelled",control.isCancelled()).put("capturedRouteFingerprint",CompactionPlan.routeFingerprint(selected));
            try{facts.put("originalInferenceUsage",tasks.inferenceUsage(binding.getString("taskId")));}catch(Exception unavailable){facts.put("inferenceReadUnavailable",unavailable.getClass().getSimpleName());}
            try{facts.put("parentBudget",pool());}catch(Exception unavailable){facts.put("budgetReadUnavailable",unavailable.getClass().getSimpleName());}
            try{facts.put("currentNativeGuard",tasks.teamIntegrationHistoryGuard(binding,control));}catch(Exception unavailable){facts.put("currentNativeGuardUnavailable",unavailable.getClass().getSimpleName()+":"+unavailable.getMessage());}
            try{if(published!=null)facts.put("rawSourceCoverageCurrent",CompactionCheckpoint.sourcesCurrent(published,stored.getJSONArray("messages")));}catch(Exception unavailable){facts.put("rawCoverageReadUnavailable",unavailable.getClass().getSimpleName());}
            Path path=directory.resolve(stage+"-"+binding.getString("taskId")+".json");Files.writeString(path,facts.toString());System.out.println("C6_COMPACTION_ORIGINAL_CHECKPOINT "+path);
        }
        void assertProvider(){if(providerFailure.get()!=null)throw new AssertionError(providerFailure.get());}
        public void close()throws Exception{control.cancel();if(retained!=null){retained.close();retained=null;}if(port!=null)port.close();if(provider!=null)provider.stop(0);if(originalFactory!=null)((AutoCloseable)originalFactory).close();}
    }
    @Test public void saved108884ByteCheckpointCompactsWithAttributedSummaryThenMainAndOriginalMeteredRawRead()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject before=f.pool();assertThrows(RunContextBudget.Blocked.class,()->new RunContextBudget(f.narrow).request(f.raw,f.port.tools(),f.selected));
            assertThrows(SecurityException.class,()->f.port.validate("memory_raw_query",new JSONObject().put("section","outputs").put("query","")));
            JSONArray schema=f.port.tools();for(int i=0;i<schema.length();i++){JSONObject fn=schema.getJSONObject(i).getJSONObject("function");if(fn.getString("name").equals("memory_raw_query"))assertEquals("[\"messages\",\"operations\"]",fn.getJSONObject("parameters").getJSONObject("properties").getJSONObject("section").getJSONArray("enum").toString());}
            JSONObject altered=new JSONObject(f.binding.toString()).put("planHash","f".repeat(64));assertThrows(SecurityException.class,()->TeamIntegrationCompaction.open(f.tasks,altered,f.control,f.selected,f.session.port()));
            assertEquals(0,f.posts.get());RunContextBudget.Projection projected=f.request();f.assertProvider();assertEquals(1,f.posts.get());assertTrue(projected.diagnostics().getBoolean("summariesGenerated"));assertTrue(projected.plan().canSend());
            JSONObject stored=f.session.journal().restore();if(!stored.has("contextCompaction"))f.captureOriginalCheckpoint("restore_original_projection_unavailable");JSONObject summary=stored.getJSONObject("contextCompaction");assertEquals(f.rawOriginal,stored.getJSONArray("messages").toString());assertFalse(summary.getBoolean("executionAuthorized"));assertEquals(1,summary.getJSONObject("coverage").getInt("summaryLayers"));
            ModelGateway.complete(f.selected,projected.plan().messages(),null,f.control,f.session.journal().inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertEquals(2,f.posts.get());f.assertProvider();
            JSONObject usage=f.tasks.inferenceUsage(f.binding.getString("taskId"));assertEquals(2,usage.getInt("attempts"));assertEquals(2,usage.getInt("completed"));Set<String> purposes=new HashSet<>();JSONArray receipts=usage.getJSONArray("receipts");for(int i=0;i<receipts.length();i++){JSONObject row=receipts.getJSONObject(i);purposes.add(row.getJSONObject("context").getString("purpose"));assertEquals(f.binding.getString("taskId"),row.getJSONObject("context").getString("taskId"));}assertEquals(Set.of("MAIN","SUMMARY"),purposes);
            JSONObject after=f.pool();assertEquals(before.getInt("modelRequestsReservedOrUsed")+2,after.getInt("modelRequestsReservedOrUsed"));assertEquals(before.getInt("remainingModelRequests")-2,after.getInt("remainingModelRequests"));
            JSONObject source=null;JSONArray refs=summary.getJSONArray("sources");int index=-1;for(int i=0;i<refs.length();i++){String id=refs.getJSONObject(i).getString("sourceId");int at=Integer.parseInt(id.substring(id.lastIndexOf(':')+1));if(f.raw.getJSONObject(at).optString("role").equals("tool")){source=refs.getJSONObject(i);index=at;break;}}assertNotNull(source);
            String originalBody=f.raw.getJSONObject(index).toString(2),query=f.raw.getJSONObject(index).getString("tool_call_id");JSONObject page=f.port.execute("memory_raw_query",new JSONObject().put("section","messages").put("query",query).put("limit",20).put("maxChars",16000),f.control);boolean found=false;JSONArray hits=page.getJSONArray("entries");for(int i=0;i<hits.length();i++)found|=source.getString("sourceId").equals(hits.getJSONObject(i).getString("sourceId"));assertTrue("covered original must remain discoverable",found);
            JSONObject citation=new JSONObject().put("sourceId",source.getString("sourceId")).put("revision",source.getString("revision")).put("offset",0).put("maxChars",16000);JSONObject read=f.port.execute("memory_raw_read",citation,f.control);assertEquals("current",read.getString("status"));assertEquals(originalBody,read.getString("body"));assertTrue(read.getJSONObject("readRange").getBoolean("complete"));
            assertEquals(before.getInt("toolOperationsReservedOrUsed")+2,f.pool().getInt("toolOperationsReservedOrUsed"));
            JSONObject foreign=new JSONObject(citation.toString()).put("sourceId",source.getString("sourceId").replace(f.binding.getString("taskId"),UUID.randomUUID().toString()));assertThrows(Exception.class,()->f.port.validate("memory_raw_read",foreign));RunControl stranger=new RunControl();stranger.bindHostRun(f.binding.getString("taskId"),f.binding.getString("runId"),f.binding.getInt("coordinatorEpoch"));assertThrows(SecurityException.class,()->f.session.port().execute("memory_raw_query",new JSONObject().put("section","messages").put("query",""),stranger));
            JSONObject restored=f.session.journal().restore();assertEquals(summary.getString("callId"),restored.getJSONObject("contextCompaction").getString("callId"));assertEquals(f.rawOriginal,restored.getJSONArray("messages").toString());assertEquals(2,f.posts.get());
            f.control.pause();f.control.check();assertTrue(f.control.isPaused());assertFalse(f.control.isCancelled());assertThrows(SecurityException.class,()->f.port.execute("memory_raw_query",new JSONObject().put("section","messages").put("query",""),f.control));f.session.journal().checkpoint(f.raw,38,new JSONObject());assertEquals(f.rawOriginal,f.session.journal().restore().getJSONArray("messages").toString());f.control.resume();f.port.prepareTurn("原同child暂停后重新准入",1,38,f.control);assertFalse(f.session.journal().restore().has("contextCompaction"));assertThrows(SecurityException.class,()->f.port.validate("memory_raw_read",citation));assertEquals(f.rawOriginal,f.session.journal().restore().getJSONArray("messages").toString());assertEquals(2,f.posts.get());
        }
    }
    @Test public void cancellationDuringPhysicalSummaryRetainsSingleChargedAttemptAndNoPublicationOrMainReplay()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject before=f.pool();f.cancelDuringSummary=true;assertThrows(Exception.class,f::request);f.assertProvider();assertEquals(1,f.posts.get());assertEquals(before.getInt("modelRequestsReservedOrUsed")+1,f.pool().getInt("modelRequestsReservedOrUsed"));
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);android.database.sqlite.SQLiteDatabase db=(android.database.sqlite.SQLiteDatabase)field.get(f.tasks);try(android.database.Cursor rows=db.rawQuery("SELECT checkpoint FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{f.binding.getString("taskId"),f.binding.getString("runId"),f.binding.getInt("coordinatorEpoch")+""})){assertTrue(rows.moveToFirst());JSONObject actual=new JSONObject(rows.getString(0));assertFalse(actual.has("contextCompaction"));assertEquals(f.rawOriginal,actual.getJSONArray("messages").toString());}
            assertThrows(Exception.class,f::request);assertEquals(1,f.posts.get());JSONObject usage=f.tasks.inferenceUsage(f.binding.getString("taskId"));assertEquals(1,usage.getInt("attempts"));assertEquals("SUMMARY",usage.getJSONArray("receipts").getJSONObject(0).getJSONObject("context").getString("purpose"));
        }
    }
}
