package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Color;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.runtime.usage.*;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Original SQL/Local/Runner/Gateway with real PNG/codec and loopback HTTP body capture.
 * Provider replies are fixtures; this does not claim a real provider or Android-device run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectImageRunnerNativeTest {
    enum Mode {FILE,ARTIFACT,TWO,THREE,UNSUPPORTED,METADATA,DRIFT,CANCEL,REVOKE,PAUSE_RESUME,REPEATED}
    private static JSONArray images(JSONObject request)throws Exception{JSONArray found=new JSONArray(),messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject row=messages.getJSONObject(i);JSONArray parts=row.optJSONArray("content");if(parts!=null)for(int j=0;j<parts.length();j++){JSONObject part=parts.getJSONObject(j);if("image_url".equals(part.optString("type")))found.put(part.getJSONObject("image_url").getString("url"));}}return found;}
    private void journey(Mode mode)throws Exception{
        try(ProjectImageSourceNativeTest.F f=new ProjectImageSourceNativeTest.F()){
            JSONObject selected=mode==Mode.ARTIFACT?f.artifact():null;JSONObject args=selected==null?f.args():new JSONObject().put("artifactId",selected.getString("id")).put("sourceSha",selected.getString("sha256"));
            AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger requests=new AtomicInteger(),physicalStarts=new AtomicInteger(),delivered=new AtomicInteger();byte[] changed=ProjectImageSourceNativeTest.png(Color.BLUE);
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService workers=Executors.newSingleThreadExecutor();
            var observerField=InferenceCapture.class.getDeclaredField("presentationObserver");observerField.setAccessible(true);Object priorObserver=observerField.get(null);
            // This native callback runs after the first physical admission and durable STARTED
            // receipt, but before its second admission and the actual HTTP call.
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt)throws Exception{if(physicalStarts.incrementAndGet()!=2)return;try{switch(mode){case DRIFT:Files.write(f.files.resolve("asset.png"),changed);break;case CANCEL:f.control.cancel();break;case REVOKE:f.tasks.authorizeTools(f.task,f.run,new JSONArray().put("project_inspect"));break;case PAUSE_RESUME:f.control.pause();f.control.resume();break;default:break;}}catch(Throwable invalid){failure.compareAndSet(null,invalid);throw invalid instanceof Exception?(Exception)invalid:new IllegalStateException(invalid);}}
                public void finished(InferenceReceipt receipt){}
            });
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int number=requests.incrementAndGet();JSONArray actual=images(request);delivered.set(actual.length());
                for(int i=0;i<actual.length();i++){String url=actual.getString(i);assertTrue(url.startsWith("data:image/png;base64,"));assertArrayEquals(f.pixels,Base64.getDecoder().decode(url.substring(url.indexOf(',')+1)));}
                JSONObject answer=new JSONObject().put("role","assistant");
                if(number==1||mode==Mode.REPEATED&&number<4){
                    if(number==1)assertEquals(0,actual.length());else assertEquals(Math.min(2,number-1),actual.length());int count=mode==Mode.TWO?2:mode==Mode.THREE?3:1;JSONArray calls=new JSONArray();
                    for(int i=0;i<count;i++){String tool=mode==Mode.METADATA?"project_read_file":"project_view_image";JSONObject input=mode==Mode.METADATA?new JSONObject().put("path","asset.png").put("metadataOnly",true):new JSONObject(args.toString());calls.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",input.toString())));}
                    answer.put("content",JSONObject.NULL).put("tool_calls",calls);
                }else{
                    assertFalse("A revoked/cancelled/source-changed image must never reach HTTP",mode==Mode.DRIFT||mode==Mode.CANCEL||mode==Mode.REVOKE||mode==Mode.PAUSE_RESUME);
                    int expected=mode==Mode.UNSUPPORTED||mode==Mode.THREE||mode==Mode.METADATA?0:mode==Mode.TWO||mode==Mode.REPEATED?2:1;assertEquals(expected,actual.length());JSONArray messages=request.getJSONArray("messages");boolean rejection=false;
                    for(int i=0;i<messages.length();i++){JSONObject row=messages.getJSONObject(i);if(!"tool".equals(row.optString("role")))continue;JSONObject tool=new JSONObject(row.getString("content"));assertFalse(tool.has("imageBase64"));assertFalse(tool.has("image_url"));rejection|="image_input_unavailable".equals(tool.optString("code"));}
                    if(mode==Mode.UNSUPPORTED||mode==Mode.THREE)assertTrue("Original image preflight must reject",rejection);answer.put("content","仅依据实际收到的图片回答；只有文字来源时不声称已看图。");
                }
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable invalid){failure.compareAndSet(null,invalid);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            try{
                ModelConfig model=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"explicit-image-fixture-key","project-image-fixture",true,20,10).withOwnerImageInput(mode!=Mode.UNSUPPORTED);
                ContextBudgetPolicy.Source budgets=config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(200000,4096,1000,4*1024*1024,100,2048),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
                RunOutcome outcome=workers.submit(()->new AgentRunner(null,f.port,budgets).run(f.task,"查看当前已选项目的准确图片；不能用预览或metadata代替图片",model,f.control,event->{try{f.tasks.event(f.task,f.run,f.epoch,event);}catch(Exception invalid){throw new IllegalStateException(invalid);}},f.journal)).get(25,TimeUnit.SECONDS);
                if(failure.get()!=null)throw new AssertionError("Original image request fixture",failure.get());boolean blocked=mode==Mode.DRIFT||mode==Mode.CANCEL||mode==Mode.REVOKE||mode==Mode.PAUSE_RESUME;
                assertEquals(blocked?1:mode==Mode.REPEATED?4:2,requests.get());if(blocked){assertEquals("Mutation must occur inside physical dispatch, not just before STARTED",2,physicalStarts.get());assertFalse(outcome.summary,outcome.status.matches("completed|needs_confirmation"));}else assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));
                String durable=f.journal.restore().getJSONArray("messages").toString();assertFalse("No image bytes survive restart",durable.contains("data:image/png;base64,"));assertFalse(durable.contains("imageBase64"));assertFalse("Restored text must not claim an attached project image",durable.contains("\\\"actualImageAttached\\\":true"));assertEquals(0,f.tasks.get(f.task,false).getJSONArray("operations").length());
            }finally{server.stop(0);workers.shutdownNow();workers.awaitTermination(2,TimeUnit.SECONDS);observerField.set(null,priorObserver);}
        }
    }
    @Test public void exactCheckoutBytesReachTheOriginalNextPhysicalRequest()throws Exception{journey(Mode.FILE);}
    @Test public void originalFixedArtifactBytesReachTheSameRequestPipeline()throws Exception{journey(Mode.ARTIFACT);}
    @Test public void twoImagesFitTheOriginalBatchAndThirdRejectsWithoutLateImages()throws Exception{journey(Mode.TWO);journey(Mode.THREE);}
    @Test public void unsupportedModelAndMetadataDoNotBecomeImageInput()throws Exception{journey(Mode.UNSUPPORTED);journey(Mode.METADATA);}
    @Test public void sourceShaChangeAfterStartedPreventsActualHttpDispatch()throws Exception{journey(Mode.DRIFT);}
    @Test public void cancellationAfterStartedPreventsActualHttpDispatch()throws Exception{journey(Mode.CANCEL);}
    @Test public void revokedGrantAndPauseResumeAfterStartedPreventActualHttpDispatch()throws Exception{journey(Mode.REVOKE);journey(Mode.PAUSE_RESUME);}
    @Test public void repeatedSamePixelsKeepOnlyCurrentNativeObservationPins()throws Exception{journey(Mode.REPEATED);}
}
