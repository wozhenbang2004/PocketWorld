package dev.pocketengine.features;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.runtime.usage.*;
import dev.pocketengine.store.*;
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

/** Actual native owner -> reviewed Book/source -> Gateway -> original SQLite receipts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeSkillDraftUsageJourneyTest {
 private static final String SOURCE_TEXT="SOURCE-PRIVATE-CONTENT-MUST-NOT-ENTER-USAGE-AUDIT";
 private static String id(){return UUID.randomUUID().toString();}
 private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
 private static JSONObject draft()throws Exception{return new JSONObject().put("manifest","---\nname: native-backup-check\ndescription: 核对备份目录\n---\n适用相同目录。先核对，再操作；异常停止。效果未经验证。");}
 private static final class Rig implements AutoCloseable {
  final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final FeatureHost host;final AssistantModule assistant;final MemoryModule memory;final SkillsModule skills;final NativeOwnerWorkflows owner;final JSONObject record;
  final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final ExecutorService worker=Executors.newCachedThreadPool();final AtomicInteger posts=new AtomicInteger();final AtomicReference<Throwable> serverFailure=new AtomicReference<>();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);volatile boolean hold;
  Rig()throws Exception{
   android.deleteDatabase("pocket-world.db");java.nio.file.Files.deleteIfExists(new java.io.File(android.getFilesDir(),"memory/records.json").toPath());java.nio.file.Files.deleteIfExists(new java.io.File(android.getFilesDir(),"private_conversations.json").toPath());
   server.setExecutor(worker);server.createContext("/",exchange->{try{posts.incrementAndGet();exchange.getRequestBody().readAllBytes();entered.countDown();if(hold&&!release.await(10,TimeUnit.SECONDS))throw new AssertionError("native HTTP response gate timed out");JSONObject answer=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",draft().toString())).put("finish_reason","stop"))).put("usage",new JSONObject().put("prompt_tokens",2).put("completion_tokens",1).put("total_tokens",3));byte[] bytes=answer.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Throwable failure){serverFailure.compareAndSet(null,failure);}finally{exchange.close();}});server.start();
   android.getSharedPreferences("private_settings",0).edit().clear().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("provider","openai_chat").putString("model","native-skill-draft-model").putBoolean("sendScreenshots",false).putInt("maxSteps",4).putInt("requestTimeoutSeconds",10).commit();
   tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});host=new FeatureHost(event->{},tasks,()->false);
   assistant=new AssistantModule(context,null,null,tasks);host.register(assistant);MemorySourceBridge bridge=new MemorySourceBridge(host,tasks);bridge.bindAssistant(assistant);memory=new MemoryModule(context,bridge::resolve);skills=new SkillsModule(context,memory::resolveExperience);host.register(memory);host.register(skills);host.setMemorySynchronizer(bridge::synchronize);owner=new NativeOwnerWorkflows(host,context,tasks,bridge);
   String conversation=id(),message=id();SourceRef ref=new SourceRef("message",message,1L,"owner","private");var repository=tasks.conversationRepository();repository.transaction(()->{repository.saveConversation(new JSONObject().put("conversationId",conversation).put("title","真实来源").put("createdAt",1L));repository.saveSource(ref.toJson().put("conversationId",conversation).put("role","user").put("deleted",false));repository.saveMessage(conversation,new JSONObject().put("id",message).put("revision",1L).put("role","user").put("content",SOURCE_TEXT).put("at",1L));return null;});
   JSONObject candidate=memory.proposeLearning(scope(),new JSONObject().put("layer","procedural").put("text",SOURCE_TEXT).put("reason","主人选中的实际来源").put("applicability","同类备份任务"),ref).getJSONObject("candidate");record=memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");
  }
  JSONObject payload()throws Exception{return new JSONObject().put("recordId",record.getString("id")).put("expectedRevision",record.getLong("revision"));}
  JSONObject run(RunControl original)throws Exception{return owner.learning("draftExperience",payload(),original);}
  void forget()throws Exception{memory.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"),removed->{});}
  JSONObject onlyNative()throws Exception{JSONArray rows=tasks.nativeInferenceUsagePage("",40).getJSONArray("tasks");assertEquals(1,rows.length());return tasks.get(rows.getJSONObject(0).getString("id"),false);}
  public void close()throws Exception{release.countDown();server.stop(0);worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));host.close();db.close();if(serverFailure.get()!=null)throw new AssertionError("actual native fixture upstream failed",serverFailure.get());}
 }
 @Test public void actualNativeDraftUsesOneCapturedModelAndDurablePurposeWithoutImportOrToolGrant()throws Exception{
  try(Rig r=new Rig()){
   JSONObject result=r.run(new RunControl()),reference=result.getJSONObject("inferenceReference");assertEquals(1,r.posts.get());assertFalse(result.getBoolean("imported"));assertFalse(result.getBoolean("enabled"));assertFalse(reference.getBoolean("executionAuthorized"));assertTrue(reference.getBoolean("accountingOnly"));
   JSONObject task=r.tasks.get(reference.getString("taskId"),false),receipt=r.tasks.inferenceUsage(reference.getString("taskId")).getJSONArray("receipts").getJSONObject(0);assertEquals("inference",task.getString("kind"));assertEquals(0,task.getJSONArray("operations").length());assertEquals("SKILL_DRAFT",receipt.getJSONObject("context").getString("purpose"));assertEquals("native-skill-draft-model",receipt.getJSONObject("route").getString("requestedModel"));assertEquals(3,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("TOTAL").getLong("value"));assertEquals("COMPLETED",receipt.getString("outcome"));assertFalse(task.toString().contains(SOURCE_TEXT));assertEquals(0,r.tasks.taskSummaryPage("",40).getJSONArray("tasks").length());assertThrows(SecurityException.class,()->r.tasks.startAttempt(reference.getString("taskId"),"capabilities",8));assertTrue(TaskInferencePresentation.fromTask(task).calls().get(0).purpose().contains("技能草稿"));
   r.owner.learning("inspectExperience",r.payload().put("draft",result.getJSONObject("draft")).put("options",new JSONObject()),new RunControl());assertEquals(1,r.posts.get());assertEquals(1,r.tasks.nativeInferenceUsagePage("",40).getJSONArray("tasks").length());assertEquals(0,r.skills.handle("list",new JSONObject()).getJSONArray("skills").length());
  }
 }
 @Test public void existingOriginalTaskRemainsTheOnlyTaskAndOwnsTheActualDraftReceipt()throws Exception{
  try(Rig r=new Rig()){
   JSONObject task=r.tasks.startAttempt(r.tasks.createCapabilityTask("主人明确的同一任务","").getString("id"),"capabilities",8);String beforeStatus=r.tasks.get(task.getString("id"),false).getString("status");assertEquals("preparing",beforeStatus);RunControl original=new RunControl();original.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
   JSONObject result=r.run(original),reference=result.getJSONObject("inferenceReference");assertFalse(reference.getBoolean("accountingOnly"));assertEquals(task.getString("id"),reference.getString("taskId"));assertEquals(task.getString("runId"),reference.getString("runId"));assertEquals(1,r.tasks.list().length());assertEquals(0,r.tasks.nativeInferenceUsagePage("",40).getJSONArray("tasks").length());assertEquals(beforeStatus,r.tasks.get(task.getString("id"),false).getString("status"));assertEquals("SKILL_DRAFT",r.tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").getJSONObject(0).getJSONObject("context").getString("purpose"));assertEquals(1,r.posts.get());
  }
 }
 @Test public void sourceRetiredAfterActualStartedBlocksWireWithoutRefundingOrCancellingTheOriginalControl()throws Exception{
  try(Rig r=new Rig()){
   AtomicBoolean retired=new AtomicBoolean();InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt receipt)throws Exception{if(receipt.context.purpose==InferenceReceipt.Purpose.SKILL_DRAFT&&retired.compareAndSet(false,true))r.forget();}public void finished(InferenceReceipt receipt){}});
   try{RunControl original=new RunControl();assertThrows(Exception.class,()->r.run(original));assertTrue(retired.get());assertFalse(original.isCancelled());assertEquals(0,r.posts.get());JSONObject task=r.onlyNative(),usage=r.tasks.inferenceUsage(task.getString("id"));assertEquals(1,usage.getInt("attempts"));assertEquals(1,usage.getInt("failed"));assertTrue(usage.getJSONArray("receipts").getJSONObject(0).getJSONObject("usage").isNull("actualCost"));assertEquals(0,r.skills.handle("list",new JSONObject()).getJSONArray("skills").length());}
   finally{InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){}});}
  }
 }
 @Test public void sourceRetiredDuringPhysicalHttpRejectsTheLateDraftButKeepsItsCompletedUsage()throws Exception{
  try(Rig r=new Rig()){
   r.hold=true;ExecutorService owner=Executors.newSingleThreadExecutor();RunControl original=new RunControl();try{Future<JSONObject> result=owner.submit(()->r.run(original));assertTrue(r.entered.await(5,TimeUnit.SECONDS));r.forget();r.release.countDown();assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));assertFalse(original.isCancelled());assertEquals(1,r.posts.get());JSONObject task=r.onlyNative(),usage=r.tasks.inferenceUsage(task.getString("id"));assertEquals("failed",task.getString("status"));assertEquals(1,usage.getInt("attempts"));assertEquals(1,usage.getInt("completed"));assertEquals(3,usage.getJSONArray("receipts").getJSONObject(0).getJSONObject("usage").getJSONObject("counters").getJSONObject("TOTAL").getLong("value"));assertEquals(0,r.skills.handle("list",new JSONObject()).getJSONArray("skills").length());}
   finally{r.release.countDown();owner.shutdownNow();assertTrue(owner.awaitTermination(5,TimeUnit.SECONDS));}
  }
 }
 @Test public void cancelledOwnerOrAlreadyRetiredSourceCannotCreateAnyInferenceAttemptOrHttp()throws Exception{
  try(Rig r=new Rig()){
   RunControl stopped=new RunControl();stopped.cancel();assertThrows(InterruptedException.class,()->r.run(stopped));r.forget();assertThrows(IllegalArgumentException.class,()->r.run(new RunControl()));assertEquals(0,r.posts.get());assertEquals(0,r.tasks.nativeInferenceUsagePage("",40).getJSONArray("tasks").length());assertEquals(0,r.skills.handle("list",new JSONObject()).getJSONArray("skills").length());
  }
 }
}
