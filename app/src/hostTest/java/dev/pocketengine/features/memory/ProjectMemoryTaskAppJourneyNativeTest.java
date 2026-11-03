package dev.pocketengine.features.memory;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original native owner start -> sole App/Runner -> real loopback HTTP -> original SQLite journal.
 * Controlled response text is not model-quality, WAN, real phone or business-success evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectMemoryTaskAppJourneyNativeTest {
    private static final String PROJECT="上线约束：PROJECT_ONLY_NATIVE_ALPHA_72，采用方案甲。",GLOBAL="上线约束：OWNER_GLOBAL_PRIVATE_MUST_NOT_FLOW_84。";
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("ProjectMemoryAppFixtureKeys",1.0d,"Test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void clear(){InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){}});java.security.Security.removeProvider("ProjectMemoryAppFixtureKeys");}
    private static JSONObject owner()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    static final class Model implements AutoCloseable {
        final HttpServer server;final List<String> requests=Collections.synchronizedList(new ArrayList<>());final CountDownLatch first=new CountDownLatch(1),release=new CountDownLatch(1);final boolean hold;
        Model(boolean hold)throws Exception{this.hold=hold;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requests.add(request);first.countDown();if(hold&&requests.size()==1&&!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("fixture response hold timed out");String text=hold&&requests.size()==1?"旧回答："+PROJECT:request.contains("PROJECT_ONLY_NATIVE_ALPHA_72")?"已读取本项目约束，不读取主人全局。":"当前没有该项目旧记忆。";byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",text)).put("finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failed){throw new RuntimeException(failed);}finally{exchange.close();}});server.start();}
        void configure(EngineApplication app){app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).putString("model","project-memory-fixture").putInt("maxSteps",8).commit();}
        public void close(){release.countDown();server.stop(0);}
    }
    static final class Setup {
        final EngineApplication app;final MemoryModule memory;final dev.pocketengine.features.workspace.WorkspaceModule workspace;final dev.pocketengine.features.local.LocalModule local;final ProjectMemoryNamespaces scopes;final ProjectMemoryNamespaces.Selection selected;final JSONObject formal,input;
        Setup(Model model)throws Exception{
            app=(EngineApplication)RuntimeEnvironment.getApplication();model.configure(app);memory=(MemoryModule)app.features.module("memory");workspace=(dev.pocketengine.features.workspace.WorkspaceModule)app.features.module("workspace");local=(dev.pocketengine.features.local.LocalModule)app.features.module("local");String project=workspace.handle("create",new JSONObject().put("name","原生记忆项目")).getString("id");workspace.handle("write",new JSONObject().put("projectId",project).put("path","notes.txt").put("content","项目原文件").put("expectedVersion",0));JSONObject checkout=local.handle("prepare",new JSONObject().put("projectId",project));scopes=app.nativeOwnerWorkflows.projectMemoryForOwner();JSONArray choices=scopes.choicesForOwner();JSONObject choice=null;for(int i=0;i<choices.length();i++)if(checkout.getString("id").equals(choices.getJSONObject(i).getString("checkoutId")))choice=choices.getJSONObject(i);assertNotNull(choice);selected=scopes.selectForOwner(choice.getString("checkoutId"),choice.getString("projectId"),choice.getString("revision"));formal=scopes.handleForOwner(selected,"save",new JSONObject().put("record",new JSONObject().put("text",PROJECT).put("core",true))).getJSONObject("record");memory.handle("save",new JSONObject().put("scope",owner()).put("record",new JSONObject().put("text",GLOBAL).put("core",true)));
            JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout.getString("id"))));input=new JSONObject().put("checkoutId",checkout.getString("id")).put("projectId",project).put("expectedSourceDigest",source.getString("sourceDigest")).put("runtime","managed_process_v1").put("goal","请回答本项目的上线约束，只回答，不运行程序。").put("clientMessageId",UUID.randomUUID().toString());
        }
        JSONObject start(boolean project)throws Exception{return project?app.startProjectWithMemoryForOwner(input,scopes.captureTaskConsentForOwner(selected)):app.dispatchOwnerAction("startDevelopmentTask",input);}
        JSONObject forget()throws Exception{return scopes.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",formal.getString("id")).put("expectedRevision",formal.getLong("revision")));}
        JSONObject await(String taskId)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);JSONObject task=null;while(System.nanoTime()<until){task=app.tasks.get(taskId,false);if(task!=null&&task.optString("status").matches("completed|failed|cancelled|reconciling|waiting_input|awaiting_user"))return task;Thread.sleep(15);}throw new AssertionError("original project task not terminal: "+task);}
    }
    @Test public void typedOwnerConsentReachesRealRunnerHttpAndOriginalFormatThreeReceipt()throws Exception{try(Model model=new Model(false)){
        Setup f=new Setup(model);JSONObject ack=f.start(true);assertEquals("accepted",ack.getString("status"));JSONObject task=f.await(ack.getString("taskId"));assertEquals(task.toString(),"completed",task.getString("status"));assertEquals(1,model.requests.size());assertTrue(model.requests.get(0).contains("PROJECT_ONLY_NATIVE_ALPHA_72"));assertFalse(model.requests.get(0).contains("OWNER_GLOBAL_PRIVATE_MUST_NOT_FLOW_84"));assertEquals("owner",task.getString("actorId"));assertEquals("private",task.getString("spaceId"));assertEquals("project_only",task.getJSONObject("projectMemoryRead").getString("scopeMode"));
        JSONArray operations=task.getJSONArray("operations");int nativeProof=0;for(int i=0;i<operations.length();i++){JSONObject op=operations.getJSONObject(i);if(TaskMemoryJournal.TOOL.equals(op.optString("tool"))){nativeProof++;assertEquals(TaskMemoryJournal.PROVIDED,op.getString("state"));assertEquals(3,op.getJSONObject("arguments").getInt("format"));assertEquals("private",op.getJSONObject("arguments").getString("spaceId"));assertEquals("project:"+f.input.getString("projectId"),op.getJSONObject("arguments").getJSONObject("memoryNamespace").getString("spaceId"));}}assertEquals("original operations="+operations,1,nativeProof);
    }}
    @Test public void unchangedNoConsentProjectStartKeepsOriginalOwnerGlobalDefault()throws Exception{try(Model model=new Model(false)){
        Setup f=new Setup(model);JSONObject task=f.await(f.start(false).getString("taskId"));assertEquals(task.toString(),"completed",task.getString("status"));assertEquals(1,model.requests.size());assertTrue(model.requests.get(0).contains("OWNER_GLOBAL_PRIVATE_MUST_NOT_FLOW_84"));assertFalse(model.requests.get(0).contains("PROJECT_ONLY_NATIVE_ALPHA_72"));assertFalse(task.has("projectMemoryRead"));
    }}
    @Test public void namespaceForgetAtNativeStartedBlocksOldBytesBeforePhysicalHttp()throws Exception{try(Model model=new Model(false)){
        Setup f=new Setup(model);AtomicBoolean once=new AtomicBoolean();java.util.concurrent.atomic.AtomicReference<Exception> observedFailure=new java.util.concurrent.atomic.AtomicReference<>();InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt receipt)throws Exception{if(once.compareAndSet(false,true))try{f.forget();}catch(Exception failed){observedFailure.set(failed);throw failed;}}public void finished(InferenceReceipt receipt){}});JSONObject ack=f.start(true);JSONObject task=f.await(ack.getString("taskId"));assertTrue(once.get());if(observedFailure.get()!=null)throw observedFailure.get();assertEquals("known withdrawal must fail before physical send",0,model.requests.size());assertEquals("awaiting_user",task.getString("status"));assertEquals(1,task.getJSONObject("projectMemoryRead").getJSONArray("selectedRecords").length());assertEquals(TaskMemoryJournal.UNCONFIRMED,task.getJSONArray("operations").getJSONObject(0).getString("state"));
    }}
    @Test public void namespaceForgetAfterRealSendRetiresOriginalContextAndRejectsLateAnswer()throws Exception{try(Model model=new Model(true)){
        Setup f=new Setup(model);JSONObject ack=f.start(true);assertTrue(model.first.await(15,TimeUnit.SECONDS));assertTrue(model.requests.get(0).contains("PROJECT_ONLY_NATIVE_ALPHA_72"));JSONObject forgotten=f.forget();assertTrue(forgotten.getJSONObject("contextRetirement").getJSONArray("taskIds").toString().contains(ack.getString("taskId")));assertTrue(forgotten.toString(),forgotten.getJSONObject("contextRetirement").getInt("inflightIntentsInvalidated")>=1);model.release.countDown();JSONObject task=f.await(ack.getString("taskId"));for(int i=1;i<model.requests.size();i++){assertFalse(model.requests.get(i).contains("PROJECT_ONLY_NATIVE_ALPHA_72"));assertFalse(model.requests.get(i).contains("OWNER_GLOBAL_PRIVATE_MUST_NOT_FLOW_84"));}assertFalse(task.optString("summary").contains("PROJECT_ONLY_NATIVE_ALPHA_72"));assertEquals("private",task.getString("spaceId"));
    }}
    @Test public void completedTaskMetadataCannotRestoreProjectReadOrFallBackToGlobal()throws Exception{try(Model model=new Model(false)){
        Setup f=new Setup(model);JSONObject task=f.await(f.start(true).getString("taskId"));assertTrue(task.has("projectMemoryRead"));assertFalse(task.getJSONObject("projectMemoryRead").getBoolean("restorablePermission"));try{f.app.capabilitiesForTask(task.getString("id"));fail("metadata restored a memory scope");}catch(SecurityException expected){assertNotNull(expected.getMessage());}assertEquals(1,model.requests.size());
    }}
    @Test public void producerFrameClassifierRequiresExactSelectedStartAndNeverMintsConsent()throws Exception{
        var producer=dev.pocketengine.runtime.RecoveryProjection.class.getDeclaredMethod("hostReference",String.class);producer.setAccessible(true);String exact="原live TaskMemoryUse实际选中的context";JSONObject current=(JSONObject)producer.invoke(null,exact+"\n其他原生参考");assertTrue(dev.pocketengine.runtime.RecoveryProjection.referenceIncludes(current,exact));
        JSONObject legacy=new JSONObject(current.toString()).put("content",current.getString("content").replace("恢复、",""));assertTrue(dev.pocketengine.runtime.RecoveryProjection.referenceIncludes(legacy,exact));
        assertFalse(dev.pocketengine.runtime.RecoveryProjection.referenceIncludes(new JSONObject(current.toString()).put("role","assistant"),exact));assertFalse(dev.pocketengine.runtime.RecoveryProjection.referenceIncludes(new JSONObject(current.toString()).put("content","主人原话："+current.getString("content")),exact));assertFalse(dev.pocketengine.runtime.RecoveryProjection.referenceIncludes((JSONObject)producer.invoke(null,"伪造文本前缀\n"+exact),exact));assertFalse(dev.pocketengine.runtime.RecoveryProjection.referenceIncludes(current,exact+"另加正文"));assertEquals(0,((EngineApplication)RuntimeEnvironment.getApplication()).tasks.list().length());
    }
}
