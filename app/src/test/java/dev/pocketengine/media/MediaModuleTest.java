package dev.pocketengine.media;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class MediaModuleTest {
    private HttpServer server;private MediaModule module;private Context context;
    private MediaModule module()throws Exception{File files=Files.createTempDirectory("pocket-media-module-").toFile();context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};module=new MediaModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));return module;}
    @After public void close(){if(module!=null)module.close();if(server!=null)server.stop(0);}
    @Test public void serviceRequiresRealConfigurationAndHostOperationIdentity()throws Exception{
        MediaModule media=module();assertFalse(media.snapshot().getBoolean("configured"));assertThrows(SecurityException.class,()->media.handle("generateImage",new JSONObject().put("prompt","a mug")));assertThrows(IllegalStateException.class,()->media.handle("generateImage",new JSONObject().put("_operationId","run-1").put("prompt","a mug")));assertFalse(media.snapshot().getBoolean("busy"));
    }
    @Test public void savingConfigurationDoesNotPretendToValidateAService()throws Exception{
        MediaModule media=module();JSONObject saved=media.handle("configure",new JSONObject().put("baseUrl","https://example.com/v1").put("imageModel","configured-model"));assertTrue(saved.getBoolean("configured"));assertFalse(saved.getJSONObject("configuration").getBoolean("hasApiKey"));assertEquals("not_verified_for_current_configuration",saved.getString("serviceVerification"));assertFalse(saved.toString().contains("secretSlot"));assertEquals("idle",media.handle("cancel",new JSONObject().put("operationId","old")).getString("status"));
    }
    @Test public void successfulServiceResponseBecomesActualAttachmentWithoutClaimingTaskSuccess()throws Exception{
        MediaModule media=module();String png="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=";byte[] result=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("b64_json",png))).toString().getBytes(StandardCharsets.UTF_8);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/images/generations",exchange->{exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();});server.start();media.handle("configure",new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("imageModel","test-fixture"));JSONObject reply=media.handle("generateImage",new JSONObject().put("_operationId","run-1").put("prompt","labelled test fixture"));assertEquals("response_received",reply.getString("status"));assertFalse(reply.getBoolean("verified"));JSONObject attachment=reply.getJSONObject("attachment");assertArrayEquals(java.util.Base64.getDecoder().decode(png),Files.readAllBytes(AttachmentRepository.get(context).book.file(attachment.getString("id")).toPath()));assertFalse(attachment.toString().contains("labelled test fixture"));assertFalse(media.snapshot().getBoolean("busy"));
    }
    @Test public void nativeHostControlRequiresExactRunEpochBeforeComfyDispatch()throws Exception{
        MediaModule media=module();java.util.concurrent.atomic.AtomicInteger reached=new java.util.concurrent.atomic.AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/prompt",exchange->{reached.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] response=new JSONObject().put("prompt_id",request.getString("prompt_id")).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
        media.handle("configure",new JSONObject().put("provider","comfyui").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()));
        JSONObject input=new JSONObject().put("_taskId","task-1").put("_runId","run-1").put("_epoch",2).put("_operationId","op-1").put("workflow",new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text","fixture"))));
        RunControl control=new RunControl();control.bindHostRun("task-1","run-1",1);
        assertThrows(SecurityException.class,()->media.handleForHost("submitWorkflow",input,control));assertEquals(0,reached.get());
        input.put("_epoch",1);JSONObject result=media.handleForHost("submitWorkflow",input,control);assertEquals("queued",result.getString("status"));assertTrue(result.getBoolean("liveHostControl"));assertEquals(1,reached.get());
    }
    @Test public void repeatedImportAfterReceiptGapFindsTheOriginalProductionAttachment()throws Exception{
        MediaModule media=module();byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");java.util.concurrent.atomic.AtomicReference<String> id=new java.util.concurrent.atomic.AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/prompt",exchange->{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));id.set(request.getString("prompt_id"));byte[] response=new JSONObject().put("prompt_id",id.get()).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});
        server.createContext("/history/",exchange->{JSONObject entry=new JSONObject().put("status",new JSONObject().put("completed",true).put("status_str","success")).put("outputs",new JSONObject().put("9",new JSONObject().put("images",new JSONArray().put(new JSONObject().put("filename","fixture.png").put("subfolder","").put("type","output")))));byte[] response=new JSONObject().put(id.get(),entry).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});
        server.createContext("/view",exchange->{exchange.sendResponseHeaders(200,png.length);exchange.getResponseBody().write(png);exchange.close();});server.start();
        media.handle("configure",new JSONObject().put("provider","comfyui").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()));
        media.handle("submitWorkflow",new JSONObject().put("_taskId","task-1").put("_runId","run-1").put("_operationId","op-1").put("workflow",new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text","fixture")))));
        JSONObject result=media.handle("fetchResults",new JSONObject().put("operationId","op-1"));String attachment=result.getJSONArray("attachments").getJSONObject(0).getString("id");
        File receiptFile=new File(context.getFilesDir(),"media-receipts-v1.json");JSONObject receipt=new JSONObject(Files.readString(receiptFile.toPath()));receipt.getJSONObject("receipts").getJSONObject("op-1").put("attachments",new JSONArray()).remove("attachment");Files.writeString(receiptFile.toPath(),receipt.toString());media.close();
        module=new MediaModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));JSONObject recovered=module.handle("fetchResults",new JSONObject().put("operationId","op-1"));
        assertEquals(attachment,recovered.getJSONArray("attachments").getJSONObject(0).getString("id"));assertEquals(1,AttachmentRepository.get(context).book.list().length());assertArrayEquals(png,Files.readAllBytes(AttachmentRepository.get(context).book.file(attachment).toPath()));
    }
}
