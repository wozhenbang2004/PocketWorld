package dev.pocketengine.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

/** Real App/Controller/Compose, original task/book, UTF8 parser and OS stream writes. The SAF destination is an explicit local provider fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class,qualifiers="w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AttachmentOutcomeNativeControllerTest {
    static File destination;
    public static final class ExportProvider extends ContentProvider {
        public boolean onCreate(){return true;}public String getType(Uri uri){return "text/plain";}
        public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException{if(!"/selected-document".equals(uri.getPath()))throw new java.io.FileNotFoundException();return ParcelFileDescriptor.open(destination,ParcelFileDescriptor.parseMode(mode));}
        public Cursor query(Uri u,String[]p,String s,String[]a,String sort){return null;}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[]a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[]a){throw new UnsupportedOperationException();}
    }
    interface Condition{boolean ready()throws Exception;}
    static void await(Condition condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!condition.ready()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));Thread.sleep(5);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("actual mounted outcome condition not reached",condition.ready());}
    static PocketWorldController controller(ComposeActivity activity)throws Exception{var field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);return (PocketWorldController)field.get(activity);}
    static AttachmentOutcomeCoordinator flow(PocketWorldController controller)throws Exception{var field=PocketWorldController.class.getDeclaredField("attachmentOutcomes");field.setAccessible(true);return (AttachmentOutcomeCoordinator)field.get(controller);}
    @Test public void reviewedRangeBecomesAnActualSameTaskFileThenPreviewsAndExportsItsOwnBytes()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();ActivityController<ComposeActivity> screen=null;
        byte[] original="本次已查看的来源文字。\n这行尚未查看，不能进入摘录。\n".getBytes(StandardCharsets.UTF_8);
        try{
            String task=app.tasks.createCapabilityTask("读取所需范围并把摘录保存为文件","native-document-extract").getString("id");AttachmentRepository repository=AttachmentRepository.get(app);
            JSONObject source=repository.book.importBytes(new ByteArrayInputStream(original),"来源原文.txt","text/plain",new JSONObject().put("kind","content_uri").put("taskId",task));
            screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();AttachmentOutcomeCoordinator first=flow(controller(screen.get()));first.openTask(task);await(()->!first.getState().getBusy()&&first.getState().getSnapshot()!=null);
            first.selectFile(first.getState().getSnapshot().getJSONArray("sources").getJSONObject(0));assertFalse(first.canSaveRead());first.read(new DocumentContent.Request(1,0,1,2048,8,"text"));await(()->!first.getState().getBusy()&&first.getState().getDocument()!=null);assertTrue(first.canSaveRead());capture(screen.get(),"native-read-range-ready-to-save.png");
            first.saveReadAsFile();await(()->!first.getState().getBusy()&&!first.getState().getDocumentOpen()&&first.getState().getNotice()!=null);assertNull(first.getState().getError());JSONObject saved=first.getState().getFile();assertNotEquals(source.getString("id"),saved.getString("id"));assertEquals("document_extract",saved.getString("sourceKind"));assertEquals(task,saved.getString("sourceTaskId"));assertEquals(source.getString("sha256"),saved.getString("sourceSha256"));assertEquals(1,first.getState().getSnapshot().getJSONArray("artifacts").length());
            byte[] actual=Files.readAllBytes(repository.book.file(saved.getString("id")).toPath());String text=new String(actual,StandardCharsets.UTF_8);assertTrue(text.contains("本次已查看的来源文字"));assertFalse(text.contains("这行尚未查看"));assertArrayEquals(original,Files.readAllBytes(repository.book.file(source.getString("id")).toPath()));
            first.read(new DocumentContent.Request(1,0,32,12000,48,"text"));await(()->!first.getState().getBusy()&&first.getState().getDocument()!=null);StringBuilder preview=new StringBuilder();var chunks=first.getState().getDocument().getJSONArray("chunks");for(int i=0;i<chunks.length();i++)preview.append(chunks.getJSONObject(i).getString("text"));assertTrue(preview.toString().contains("本次已查看的来源文字"));capture(screen.get(),"native-saved-extract-preview.png");first.backFromDocument();first.prepareExport();
            screen.recreate();AttachmentOutcomeCoordinator restored=flow(controller(screen.get()));await(()->!restored.getState().getBusy()&&restored.getState().getFile()!=null);assertEquals(saved.getString("id"),restored.getState().getFile().getString("id"));assertEquals(saved.getString("sha256"),restored.getState().getFile().getString("sha256"));
            destination=new File(app.getCacheDir(),"native-document-extract-export.txt");android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="b4-saf-fixture";info.exported=false;ShadowContentResolver.registerProviderInternal("b4-saf-fixture",Robolectric.buildContentProvider(ExportProvider.class).create(info).get());restored.exportResult(Uri.parse("content://b4-saf-fixture/selected-document"));await(()->!restored.getState().getBusy()&&(restored.getState().getNotice()!=null||restored.getState().getError()!=null));assertNull(restored.getState().getError());assertArrayEquals(actual,Files.readAllBytes(destination.toPath()));
            assertEquals(1,app.tasks.list().length());assertEquals(0,app.tasks.get(task,false).getJSONArray("operations").length());capture(screen.get(),"native-saved-extract-exported.png");
        }finally{if(screen!=null)screen.pause().stop().destroy();app.features.close();}
    }
    @Test public void originalFilePagesOpenThroughActualUiAndSafTargetRestoresTheSameVersion()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();ActivityController<ComposeActivity> screen=null;
        byte[] bytes="来源第一行：只查看所需范围。\n来源第二行：仍属于同一个原任务。\n".getBytes(StandardCharsets.UTF_8);
        try {
            String task=app.tasks.createCapabilityTask("查看来源页并导出这份原文件","native-file-goal").getString("id");JSONObject file=AttachmentRepository.get(app).book.importBytes(new ByteArrayInputStream(bytes),"原任务来源.txt","text/plain",new JSONObject().put("kind","content_uri").put("taskId",task));
            screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();PocketWorldController controller=controller(screen.get());AttachmentOutcomeCoordinator flow=flow(controller);controller.toggleChatAttachment(file);assertEquals(1,controller.getState().getSelectedChatAttachments().size());controller.openTaskOutcomes(task);
            final AttachmentOutcomeCoordinator first=flow;await(()->!first.getState().getBusy()&&first.getState().getSnapshot()!=null);JSONObject actual=flow.getState().getSnapshot().getJSONArray("sources").getJSONObject(0);assertEquals(file.getString("id"),actual.getString("id"));flow.selectFile(actual);flow.read(new DocumentContent.Request(1,0,1,2048,8,"text"));
            await(()->!first.getState().getBusy()&&first.getState().getDocument()!=null);JSONObject read=flow.getState().getDocument();assertTrue(read.getJSONArray("chunks").getJSONObject(0).getString("text").contains("来源第一行"));assertEquals(2,read.getInt("nextUnit"));assertEquals(file.getString("sha256"),read.getString("sha256"));assertEquals(1,controller.getState().getSelectedChatAttachments().size());capture(screen.get(),"native-document-page.png");
            flow.backFromDocument();flow.prepareExport();assertNotNull("native export must retain exact selected version before picker",field(flow,"export"));screen.recreate();controller=controller(screen.get());flow=flow(controller);final AttachmentOutcomeCoordinator restored=flow;await(()->!restored.getState().getBusy()&&restored.getState().getFile()!=null);assertEquals(task,flow.getState().getTaskId());assertEquals(file.getString("id"),flow.getState().getFile().getString("id"));assertEquals(file.getString("sha256"),flow.getState().getFile().getString("sha256"));
            assertNotNull("native export pin must survive Activity recreation",field(flow,"export"));assertEquals(Boolean.TRUE,field(flow,"resumed"));destination=new File(app.getCacheDir(),"native-outcome-export.txt");android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="b4-saf-fixture";info.exported=false;ExportProvider provider=Robolectric.buildContentProvider(ExportProvider.class).create(info).get();ShadowContentResolver.registerProviderInternal("b4-saf-fixture",provider);flow.exportResult(Uri.parse("content://b4-saf-fixture/selected-document"));await(()->!restored.getState().getBusy()&&(restored.getState().getNotice()!=null||restored.getState().getError()!=null));assertNull("SAF export error",restored.getState().getError());assertNotNull(restored.getState().getNotice());assertArrayEquals(bytes,Files.readAllBytes(destination.toPath()));assertEquals(1,app.tasks.list().length());assertEquals(task,app.tasks.list().getJSONObject(0).getString("id"));assertEquals(0,app.tasks.get(task,false).getJSONArray("operations").length());capture(screen.get(),"native-fixed-file-exported.png");
            flow.close();assertFalse(flow.getState().getOpen());assertTrue(AttachmentRepository.get(app).book.file(file.getString("id")).isFile());
        } finally {if(screen!=null)screen.pause().stop().destroy();app.features.close();}
    }
    static Object field(Object value,String name)throws Exception{var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    static void capture(ComposeActivity activity,String name)throws Exception {
        File directory=new File(System.getProperty("b4.outcome.evidence","/tmp/b4-outcome-evidence"));directory.mkdirs();int width=(int)(activity.getResources().getConfiguration().screenWidthDp*activity.getResources().getDisplayMetrics().density),height=(int)(activity.getResources().getConfiguration().screenHeightDp*activity.getResources().getDisplayMetrics().density);
        Window window=ShadowDialog.getLatestDialog()!=null&&ShadowDialog.getLatestDialog().isShowing()?ShadowDialog.getLatestDialog().getWindow():activity.getWindow();
        for(int i=0;i<12;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));View root=window.getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);root.invalidate();Thread.sleep(2);}
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);int[] result={-1};PixelCopy.request(window,bitmap,code->result[0]=code,new Handler(Looper.getMainLooper()));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(PixelCopy.SUCCESS,result[0]);try(FileOutputStream out=new FileOutputStream(new File(directory,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
}
