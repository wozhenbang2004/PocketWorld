package dev.pocketengine.ui;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentOutcomeHost;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentFixtures;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import dev.pocketengine.store.SecretVault;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static dev.pocketengine.ui.AttachmentOutcomeNativeControllerTest.*;
import static org.junit.Assert.*;

/** Actual native coordinator, SQLite, verified source bytes and parser. OCR output is an explicit test port, not a device-engine acceptance claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class,qualifiers="w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AttachmentOutcomeOcrNativeControllerTest {
    private static final class Screen implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final AttachmentRepository repository=AttachmentRepository.get(app);
        final String task;
        final ActivityController<ComposeActivity> screen;
        final AttachmentOutcomeCoordinator flow;
        Screen(DocumentReader.OcrPort ocr)throws Exception {
            task=app.tasks.createCapabilityTask("查看原图并识别当前需要的文字","native-image-ocr").getString("id");
            AttachmentsModule documents=new AttachmentsModule(new FeatureContext(app,app.settings,new SecretVault(app),event->{}),new DocumentReader(null,ocr));
            app.attachmentOutcomes=new AttachmentOutcomeHost(app.tasks,repository,documents,(a,b,c)->{throw new AssertionError("OCR must not submit media or create another task");});
            screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();
            flow=flow(controller(screen.get()));
        }
        JSONObject select(byte[] bytes,String mime)throws Exception {
            JSONObject file=repository.book.importBytes(new ByteArrayInputStream(bytes),"来源没有图片后缀.txt",mime,new JSONObject().put("kind","content_uri").put("taskId",task));
            flow.openTask(task);await(()->!flow.getState().getBusy()&&flow.getState().getSnapshot()!=null);
            JSONObject selected=null;var rows=flow.getState().getSnapshot().getJSONArray("sources");
            for(int i=0;i<rows.length();i++)if(file.getString("id").equals(rows.getJSONObject(i).getString("id")))selected=rows.getJSONObject(i);
            assertNotNull("exact saved source must be visible in its original task",selected);flow.selectFile(selected);await(()->!flow.getState().getBusy());return file;
        }
        public void close(){screen.pause().stop().destroy();app.features.close();}
    }
    private static DocumentReader.OcrPort ocr(byte[] expected,AtomicInteger calls){return new DocumentReader.OcrPort(){
        public boolean available(){return true;}
        public String engineVersion(){return "explicit-ocr-test-port";}
        public void recognize(byte[] input,String format,DocumentReader.RequestSink sink,RunControl control)throws Exception {
            calls.incrementAndGet();assertArrayEquals(expected,input);assertEquals("png",format);assertEquals("ocr",sink.request().extraction);
            sink.totalUnits(1);sink.text(1,"text",DocumentContent.location("image","1","boundsPixels","0,0,300,100"),"本条文字来自显式OCR测试端口");
        }
    };}
    @Test public void imageDetailRequestsExplicitOcrAndShowsRecognizedResultForTheSamePinnedFile()throws Exception {
        byte[] image=DocumentFixtures.actual("scan.png");AtomicInteger calls=new AtomicInteger();
        try(Screen f=new Screen(ocr(image,calls))){
            JSONObject file=f.select(image,"image/png");assertTrue(f.flow.canRequestOcr());f.flow.readImageText();
            await(()->!f.flow.getState().getBusy()&&f.flow.getState().getDocument()!=null);
            JSONObject result=f.flow.getState().getDocument();assertNull(f.flow.getState().getError());assertEquals(1,calls.get());assertEquals("ocr",result.getString("extraction"));assertEquals("ocr",result.getString("coverage"));
            assertEquals(file.getString("id"),result.getString("attachmentId"));assertEquals(file.getString("sha256"),result.getString("sha256"));assertEquals("ocr_text",result.getJSONArray("chunks").getJSONObject(0).getString("kind"));assertEquals("explicit-ocr-test-port",result.getString("engineVersion"));
            f.flow.backFromDocument();assertEquals(file.getString("id"),f.flow.getState().getFile().getString("id"));
            assertArrayEquals(image,Files.readAllBytes(f.repository.book.file(file.getString("id")).toPath()));assertEquals(1,f.app.tasks.list().length());assertEquals(0,f.app.tasks.get(f.task,false).getJSONArray("operations").length());
        }
    }
    @Test public void missingOcrDoesNotStartTheImageRead()throws Exception {
        byte[] image=DocumentFixtures.actual("scan.png");
        try(Screen f=new Screen(null)){
            f.select(image,"image/png");assertFalse(f.flow.canRequestOcr());f.flow.readImageText();assertFalse(f.flow.getState().getBusy());assertFalse(f.flow.getState().getDocumentOpen());assertNull(f.flow.getState().getDocument());assertNotNull(f.flow.getState().getError());
        }
    }
    @Test public void imageOnlyCapabilityDoesNotOfferUnavailablePdfOcr()throws Exception {
        byte[] image=DocumentFixtures.actual("scan.png");AtomicInteger calls=new AtomicInteger();
        try(Screen f=new Screen(ocr(image,calls))){
            f.select(DocumentFixtures.actual("text.pdf"),"application/pdf");assertTrue(f.flow.getState().getSnapshot().getJSONObject("documentCapabilities").getBoolean("imageOcr"));assertFalse(f.flow.canRequestOcr());f.flow.readImageText();assertEquals(0,calls.get());assertNull(f.flow.getState().getDocument());
        }
    }
    @Test public void changedImageBytesNeverReachTheOcrPort()throws Exception {
        byte[] image=DocumentFixtures.actual("scan.png");AtomicInteger calls=new AtomicInteger();
        try(Screen f=new Screen(ocr(image,calls))){
            JSONObject file=f.select(image,"image/png");byte[] changed=image.clone();changed[changed.length-1]^=1;Files.write(f.repository.book.file(file.getString("id")).toPath(),changed);f.flow.readImageText();
            await(()->!f.flow.getState().getBusy()&&f.flow.getState().getDocument()!=null);assertEquals("input_changed",f.flow.getState().getDocument().getString("status"));assertEquals(0,calls.get());assertEquals(0,f.flow.getState().getDocument().getJSONArray("chunks").length());
        }
    }
    @Test public void imageMetadataDoesNotAuthorizeImageOcrForPdfBytes()throws Exception {
        byte[] image=DocumentFixtures.actual("scan.png");AtomicInteger calls=new AtomicInteger();
        try(Screen f=new Screen(ocr(image,calls))){
            f.select(DocumentFixtures.actual("text.pdf"),"image/png");f.flow.readImageText();await(()->!f.flow.getState().getBusy()&&f.flow.getState().getDocument()!=null);
            JSONObject result=f.flow.getState().getDocument();assertEquals("pdf",result.getString("format"));assertEquals("unavailable",result.getString("status"));assertEquals(0,calls.get());assertFalse(f.flow.canRequestOcr());assertEquals(0,result.getJSONArray("chunks").length());
        }
    }
    @Test public void stoppingTheReadDiscardsLateRecognitionAndKeepsOriginalFile()throws Exception {
        byte[] image=DocumentFixtures.actual("scan.png");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        DocumentReader.OcrPort waiting=new DocumentReader.OcrPort(){
            public boolean available(){return true;}public String engineVersion(){return "blocked-test-port";}
            public void recognize(byte[] bytes,String format,DocumentReader.RequestSink sink,RunControl control)throws Exception {entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));control.checkpoint();sink.text(1,"text",DocumentContent.location("image","1"),"must not appear");}finally{finished.countDown();}}
        };
        try(Screen f=new Screen(waiting)){
            JSONObject file=f.select(image,"image/png");f.flow.readImageText();assertTrue(entered.await(5,TimeUnit.SECONDS));f.flow.cancel();release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS));await(()->!f.flow.getState().getBusy());assertNull(f.flow.getState().getDocument());assertArrayEquals(image,Files.readAllBytes(f.repository.book.file(file.getString("id")).toPath()));
        }finally{release.countDown();}
    }
}
