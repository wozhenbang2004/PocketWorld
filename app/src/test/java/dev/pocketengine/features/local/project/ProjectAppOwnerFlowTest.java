package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.local.LocalModule;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Actual production App.onCreate/owner admission. No replacement Host, runtime or TaskStore. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectAppOwnerFlowTest {
    @After public void closeActualApplicationResources()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();app.onTerminate();
        var workers=EngineApplication.class.getDeclaredField("capabilityWorkers");workers.setAccessible(true);((ExecutorService)workers.get(app)).awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);
        app.features.close();var db=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(app.tasks)).close();
    }
    @Test public void nativeCreateOpenAndOriginalModelToolsAreMountedWithHonestUnconfiguredEnvironment()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();assertNotNull(app.features.module("local"));
        LocalModule local=(LocalModule)app.features.module("local");assertNotNull(local.managedRuntimeForOwner());assertEquals("not_configured",local.managedRuntimeForOwner().describe().getString("status"));
        JSONObject selected=app.dispatchOwnerAction("createDevelopmentProject",new JSONObject().put("name","First native owner Web project").put("template","web"));String checkout=selected.getString("id"),project=selected.getString("projectId");
        assertEquals(checkout,app.dispatchOwnerAction("openDevelopmentProject",new JSONObject().put("projectId",project)).getString("id"));JSONObject exported=local.handle("export",new JSONObject().put("checkoutId",checkout));assertTrue(exported.getJSONArray("files").length()>4);String digest=ProjectSources.describe(exported).getString("sourceDigest");
        try(Model model=new Model()){
            app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+model.server.getLocalPort()+"/v1").put("model","fixture-native-owner").put("clearApiKey",true).put("sendScreenshots",false).put("maxSteps",10));
            String client=UUID.randomUUID().toString();JSONObject input=new JSONObject().put("projectId",project).put("checkoutId",checkout).put("expectedSourceDigest",digest).put("runtime","managed_process_v1").put("clientMessageId",client).put("goal","Inspect this selected project and report the actual preparation result; do not pretend missing tools are installed.");
            JSONObject acknowledged=app.dispatchOwnerAction("startDevelopmentTask",input);String taskId=acknowledged.getString("taskId");long until=System.nanoTime()+20_000_000_000L;JSONObject task=null;
            while(System.nanoTime()<until){task=app.tasks.get(taskId,false);if(model.completed&&!task.optString("status").matches("preparing|running"))break;Thread.sleep(20);}
            assertTrue("Actual production model did not finish",model.completed);assertNotNull(task);assertEquals(checkout,task.getJSONObject("developmentContext").getString("checkoutId"));assertEquals("managed_process_v1",task.getJSONObject("developmentContext").getString("runtime"));assertTrue(model.sawNativeTools);assertTrue(model.sawUnavailableInHistory);assertFalse(app.tasks.hasUnresolved(taskId));assertEquals(1,task.getJSONArray("operations").length());assertEquals("receipt",task.getJSONArray("operations").getJSONObject(0).getString("state"));
            assertEquals(taskId,app.dispatchOwnerAction("startDevelopmentTask",input).getString("taskId"));
            var doctor=app.newSshDoctorPage();assertFalse(doctor.running());doctor.close();
            System.out.println("A26D actualProductionAppOnCreate=true; nativeTemplateAndReopenSameCheckout=true; originalNativeAdmissionAndRunner=true; projectToolsActuallyAdvertised=true; unconfiguredToolResultReturnedToSameHistory=true; noTermuxFallback=true; AndroidInstalledTools=false");
        }
    }
    @Test public void lateDoctorPageCancelCannotRewriteAnAlreadySettledOriginalTask()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();String operation=UUID.randomUUID().toString();JSONObject profile=new JSONObject().put("hostFingerprint","SHA256:metadata-only-fixture").put("username","dummy");
        JSONObject task=app.tasks.beginSshDoctorTask(operation,new JSONObject().put("profileId","fixture").put("_profileBinding","a".repeat(64)),profile);String id=task.getString("id"),run=task.getString("runId");
        // Metadata-only store fixture: no SSH socket or diagnosis is represented as successful.
        app.tasks.updateExternal(id,new JSONObject().put("status","failed").put("accepted",false).put("verified",false).put("readOnly",true).put("persistReport",false));String settled=app.tasks.get(id,false).getString("status");
        app.tasks.cancelSshDoctorIfRunning(id,run);assertEquals(settled,app.tasks.get(id,false).getString("status"));assertFalse(app.tasks.get(id,false).getBoolean("cancelRequested"));assertFalse(app.tasks.get(id,false).toString().contains("doctor_body_fixture"));
    }
    private static final class Model implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread thread;final List<JSONObject> requests=new CopyOnWriteArrayList<>();volatile Throwable failure;volatile boolean completed,sawNativeTools,sawUnavailableInHistory;int stage;JSONObject plan;String emitted="";
        Model()throws Exception {server.bind(new InetSocketAddress("127.0.0.1",0));thread=new Thread(this::serve,"a26-real-app-model-fixture");thread.setDaemon(true);thread.start();}
        private JSONObject answer(JSONObject request)throws Exception {
            JSONArray tools=request.getJSONArray("tools");for(int i=0;i<tools.length();i++)if("project_execute".equals(tools.getJSONObject(i).getJSONObject("function").getString("name")))sawNativeTools=true;
            JSONArray messages=request.getJSONArray("messages");JSONObject receipt=null;for(int i=messages.length()-1;i>=0;i--){JSONObject message=messages.getJSONObject(i);if("tool".equals(message.optString("role"))&&emitted.equals(message.optString("tool_call_id"))){receipt=new JSONObject(message.getString("content"));break;}}
            JSONObject args=new JSONObject();String name;
            if(stage==0){name="project_inspect";}
            else if(stage==1){assertNotNull(receipt);args.put("intent","probe").put("expectedSourceDigest",receipt.getString("sourceDigest"));name="project_plan";}
            else if(stage==2){assertNotNull(receipt);plan=receipt.getJSONObject("plan");args.put("plan",plan).put("stepId","probe");name="project_execute";}
            else {assertNotNull(receipt);assertEquals("runtime_unavailable",receipt.getString("code"));assertFalse(receipt.getBoolean("accepted"));sawUnavailableInHistory=true;completed=true;return new JSONObject().put("content","The real selected phone environment is not configured; no command was dispatched and no other runtime was chosen.");}
            emitted="native-app-"+stage++;return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
        }
        private void serve(){while(!server.isClosed())try(Socket socket=server.accept()){InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int length=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(length<1||length>2*1024*1024)throw new IllegalArgumentException("fixture request bound");JSONObject request=new JSONObject(new String(input.readNBytes(length),StandardCharsets.UTF_8));requests.add(request);JSONObject message=answer(request).put("role","assistant");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}catch(Throwable error){if(!server.isClosed()){failure=error;try{server.close();}catch(Exception ignored){}}}}
        @Override public void close()throws Exception {server.close();thread.join(2000);if(failure!=null)throw new AssertionError("native App model fixture",failure);}
        private String line(InputStream input)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(int value;(value=input.read())!=-1;){if(value=='\n')return out.toString(StandardCharsets.US_ASCII).replace("\r","");if(out.size()>8192)throw new IllegalArgumentException("fixture header bound");out.write(value);}throw new IllegalStateException("fixture EOF");}
    }
}
