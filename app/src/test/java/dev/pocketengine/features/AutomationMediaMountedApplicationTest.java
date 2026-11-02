package dev.pocketengine.features;

import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.media.MediaModule;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

/** Real production Application -> FeatureHost -> sole TaskStore + HTTP + actual attachment bytes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class AutomationMediaMountedApplicationTest {
    private static final byte[] PNG=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");
    private EngineApplication app;private HttpServer server;
    @Before public void setup(){java.security.Security.addProvider(new java.security.Provider("MountedMediaKeyStore","1","Explicit test-only AndroidKeyStore provider"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});app=(EngineApplication)RuntimeEnvironment.getApplication();}
    @After public void cleanup(){if(server!=null)server.stop(0);if(app!=null&&app.features!=null)app.features.close();java.security.Security.removeProvider("MountedMediaKeyStore");}
    private static JSONObject j(Object... values)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private JSONObject call(String feature,String command,JSONObject payload)throws Exception{
        String request=UUID.randomUUID().toString();CopyOnWriteArrayList<JSONObject> replies=new CopyOnWriteArrayList<>();EngineApplication.Observer observer=event->{if(request.equals(event.optString("requestId")))replies.add(event);};app.addObserver(observer);
        try{app.dispatchOwnerAction("featureRequest",j("feature",feature,"command",command,"requestId",request,"payload",payload));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(replies.isEmpty()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertFalse("actual FeatureHost reply missing: "+feature+"."+command,replies.isEmpty());JSONObject reply=replies.get(0);assertTrue(reply.optString("message"),reply.getBoolean("ok"));return reply.getJSONObject("result");
        }finally{app.removeObserver(observer);}
    }
    private static void respond(HttpServer.Exchange e,JSONObject body)throws Exception{byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();}
    @Test public void mountedComfyWorkflowUsesOneOriginalTaskAndKeepsActualArtifactsAwaitingOwnerResult()throws Exception{
        assertTrue(app.features.module("media") instanceof MediaModule);AtomicInteger posts=new AtomicInteger(),interrupts=new AtomicInteger();AtomicReference<JSONObject> body=new AtomicReference<>();AtomicReference<String> phase=new AtomicReference<>("queued"),taskId=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/prompt",e->{posts.incrementAndGet();JSONObject request=new JSONObject(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));body.set(request);JSONObject nativeTask=app.tasks.get(taskId.get(),false);assertEquals(1,nativeTask.getJSONArray("operations").length());assertEquals("prepared",nativeTask.getJSONArray("operations").getJSONObject(0).getString("state"));assertEquals("consumed",nativeTask.getJSONArray("approvals").getJSONObject(0).getString("state"));respond(e,j("prompt_id",request.getString("prompt_id")));});
        server.createContext("/queue",e->{JSONArray pending=new JSONArray();JSONObject request=body.get();if(request!=null&&phase.get().equals("queued"))pending.put(new JSONArray().put(1).put(request.getString("prompt_id")).put(request.getJSONObject("prompt")).put(new JSONObject(request.getJSONObject("extra_data").toString()).put("client_id",request.getString("client_id"))));respond(e,j("queue_pending",pending,"queue_running",new JSONArray()));});
        server.createContext("/history",e->{JSONObject history=new JSONObject(),request=body.get();if(request!=null&&phase.get().equals("completed"))history.put(request.getString("prompt_id"),j("status",j("completed",true,"status_str","success"),"outputs",j("9",j("images",new JSONArray().put(j("filename","real.png","subfolder","","type","output"))))));respond(e,history);});
        server.createContext("/view",e->{e.sendResponseHeaders(200,PNG.length);e.getResponseBody().write(PNG);e.close();});server.createContext("/interrupt",e->{interrupts.incrementAndGet();respond(e,new JSONObject());});server.start();
        JSONObject configured=call("media","configure",j("provider","comfyui","baseUrl","http://127.0.0.1:"+server.getAddress().getPort()));
        JSONObject args=j("configurationRevision",configured.getString("configurationRevision"),"workflow",j("6",j("class_type","CLIPTextEncode","inputs",j("text","explicit native workflow fixture"))));
        JSONObject prepared=call("media","prepareSubmission",j("submissionCommand","submitWorkflow","arguments",args));taskId.set(prepared.getString("taskId"));assertFalse(prepared.getBoolean("dispatched"));assertEquals(0,posts.get());assertEquals("",app.tasks.get(taskId.get(),false).optString("runId"));
        JSONObject submission=new JSONObject(args.toString()).put("taskId",taskId.get());JSONObject accepted=call("media","submitWorkflow",submission);String operation=accepted.getString("operationId");assertEquals(1,posts.get());assertEquals(1,app.tasks.list().length());assertEquals("awaiting_user",app.tasks.get(taskId.get(),false).getString("status"));assertEquals(taskId.get(),app.tasks.operationTask("media",operation));
        assertTrue(call("media","submitWorkflow",submission).getBoolean("knownOperation"));assertEquals(1,posts.get());JSONObject original=j("operationId",operation,"taskId",taskId.get(),"runId",accepted.getString("runId"),"epoch",accepted.getInt("hostEpoch"),"configurationRevision",accepted.getString("configurationRevision"));
        assertEquals("queued",call("media","getJob",original).getString("status"));phase.set("completed");JSONObject completed=call("media","fetchResults",original);assertEquals("response_received",completed.getString("status"));assertEquals(1,completed.getJSONArray("attachments").length());
        JSONObject file=completed.getJSONArray("attachments").getJSONObject(0);assertArrayEquals(PNG,java.nio.file.Files.readAllBytes(AttachmentRepository.get(app).book.file(file.getString("id")).toPath()));JSONObject task=app.tasks.get(taskId.get(),false);assertEquals("needs_confirmation",task.getString("status"));assertEquals("response_received",task.getJSONArray("operations").getJSONObject(0).getJSONObject("receipt").getString("status"));
        try{app.dispatchOwnerAction("resumeTask",j("taskId",taskId.get()));fail("media task entered generic model resume");}catch(SecurityException|IllegalStateException expected){}assertEquals(1,posts.get());assertEquals(1,app.tasks.list().length());
        app.dispatchOwnerAction("confirmResult",j("taskId",taskId.get(),"resultReviewDigest",app.tasks.get(taskId.get(),false).getString("resultReviewDigest"),"completed",true));assertEquals("completed",app.tasks.get(taskId.get(),false).getString("status"));assertEquals(0,interrupts.get());
    }
    @Test public void mountedPlanBindsExistingTasksAndOnlyActualOwnerConfirmationCompletesIt()throws Exception{
        String id=app.tasks.createCapabilityTask("原任务目标","").getString("id");JSONObject plan=call("automation","createPlan",j("scope",j("ownerId","intruder","spaceId","other"),"plan",j("kind","explicit_user","title","主人明确的原任务计划","sourceRef",j("kind","model","id","fabricated"),"steps",new JSONArray().put(j("title","核对原任务结果","dueAt",System.currentTimeMillis()+60_000,"taskId",id))))).getJSONObject("plan");
        assertEquals("owner",plan.getString("ownerId"));assertEquals("private",plan.getString("spaceId"));JSONObject step=plan.getJSONArray("steps").getJSONObject(0);assertEquals(1,app.tasks.get(id,false).getJSONArray("commitmentBindings").length());
        app.tasks.status(id,"completed","仅是模型/普通状态，不是主人确认");JSONObject unverified=call("automation","getPlan",j("id",plan.getString("id"))).getJSONObject("plan");assertEquals("unverified_result",unverified.getJSONArray("steps").getJSONObject(0).getString("taskState"));assertEquals("pending",unverified.getString("state"));
        app.tasks.finish(id,"needs_confirmation","请主人核对原成果",new JSONObject());app.dispatchOwnerAction("confirmResult",j("taskId",id,"resultReviewDigest",app.tasks.get(id,false).getString("resultReviewDigest"),"completed",true));JSONObject settled=call("automation","getPlan",j("id",plan.getString("id"))).getJSONObject("plan");assertEquals("completed",settled.getString("state"));assertEquals("completed",settled.getJSONArray("steps").getJSONObject(0).getString("taskState"));assertEquals(1,app.tasks.list().length());
        JSONObject other=call("automation","createPlan",j("plan",j("kind","explicit_user","title","停止承诺不取消原执行","steps",new JSONArray().put(j("title","仍保留原任务","dueAt",System.currentTimeMillis()+60_000,"taskId",id))))).getJSONObject("plan");call("automation","cancelPlan",j("id",other.getString("id"),"revision",other.getLong("revision")));assertFalse(app.tasks.get(id,false).optBoolean("cancelRequested"));assertEquals("completed",app.tasks.get(id,false).optString("status"));
    }
}
