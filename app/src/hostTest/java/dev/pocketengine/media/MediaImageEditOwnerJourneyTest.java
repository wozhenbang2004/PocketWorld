package dev.pocketengine.media;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.store.*;
import dev.pocketengine.testutil.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class MediaImageEditOwnerJourneyTest {
    @Test public void actualOwnerSelectedImageAndTransparentMaskHaveOriginalApprovalAndIndependentNativeOutput()throws Exception{
        Application app=RuntimeEnvironment.getApplication();var tasks=new TaskStore(app);var host=new MediaWorkflowHost(tasks);var failed=new AtomicReference<Throwable>();var media=new MediaModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{try{host.recordEvent(event);}catch(Throwable bad){failed.set(bad);}}));var book=AttachmentRepository.get(app).book;var count=new AtomicInteger();var posted=new AtomicReference<byte[]>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] original=png(2,2,false),mask=png(2,2,true),wrongMask=png(3,2,true),output=png(2,3,false);server.createContext("/v1/images/edits",exchange->{count.incrementAndGet();posted.set(exchange.getRequestBody().readAllBytes());byte[] reply=new JSONObject().put("data",new JSONArray().put(new JSONObject().put("b64_json",java.util.Base64.getEncoder().encodeToString(output)))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();});server.start();
        try{
            media.handle("configure",new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("imageModel","gpt-image-1"));JSONObject image=book.importBytes(new ByteArrayInputStream(original),"image.png","image/png",new JSONObject().put("kind","created_text")),maskRef=book.importBytes(new ByteArrayInputStream(mask),"mask.png","image/png",new JSONObject().put("kind","created_text")),wrong=book.importBytes(new ByteArrayInputStream(wrongMask),"wrong.png","image/png",new JSONObject().put("kind","created_text"));
            JSONObject args=new JSONObject().put("configurationRevision",media.snapshot().getString("configurationRevision")).put("prompt","Only change the transparent region").put("imageId",image.getString("id")).put("imageSha256",image.getString("sha256")).put("maskId",maskRef.getString("id")).put("maskSha256",maskRef.getString("sha256"));
            assertThrows(IllegalArgumentException.class,()->host.prepareFromOwner(media,"editImage",new JSONObject(args.toString()).put("maskId",wrong.getString("id")).put("maskSha256",wrong.getString("sha256"))));assertEquals(0,count.get());
            JSONObject preview=host.prepareFromOwner(media,"editImage",args);String task=preview.getString("taskId");assertFalse(preview.getBoolean("executionAuthorized"));assertEquals(2,tasks.ownerAttachmentReferences(task).length());assertEquals(0,count.get());
            JSONObject receipt=host.submitFromOwner(media,"editImage",new JSONObject(args.toString()).put("taskId",task));assertEquals(new JSONObject().put("status",receipt.optString("status")).put("message",receipt.optString("message")).put("httpStatus",receipt.opt("httpStatus")).put("fixturePosts",count.get()).toString(),"response_received",receipt.getString("status"));assertEquals(1,count.get());String wire=new String(posted.get(),StandardCharsets.ISO_8859_1);assertTrue(wire.contains("name=\"image[]\""));assertTrue(wire.contains("name=\"mask\""));assertFalse(wire.contains(image.getString("id")));
            JSONObject actual=receipt.getJSONObject("attachment"),source=actual.getJSONObject("source"),stored=tasks.get(task,false);assertArrayEquals(output,book.readVerifiedSnapshot(actual.getString("id"),actual.getString("sha256"),new RunControl()));assertArrayEquals(original,book.readVerifiedSnapshot(image.getString("id"),image.getString("sha256"),new RunControl()));assertEquals(image.getString("id"),source.getJSONObject("parentArtifact").getString("id"));assertEquals(2,source.getJSONArray("inputArtifacts").length());assertEquals("needs_confirmation",stored.getString("status"));assertEquals("consumed",stored.getJSONArray("approvals").getJSONObject(0).getString("state"));assertEquals(2,stored.getJSONArray("operations").getJSONObject(0).getJSONObject("arguments").getJSONArray("inputArtifacts").length());
            assertTrue(host.submitFromOwner(media,"editImage",new JSONObject(args.toString()).put("taskId",task)).getBoolean("knownOperation"));assertEquals(1,count.get());assertNull(failed.get());
        }finally{host.close();media.close();server.stop(0);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    private static byte[] png(int width,int height,boolean transparent)throws Exception{Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);try{bitmap.eraseColor(Color.BLUE);if(transparent)bitmap.setPixel(0,0,Color.TRANSPARENT);ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes));return bytes.toByteArray();}finally{bitmap.recycle();}}
}
