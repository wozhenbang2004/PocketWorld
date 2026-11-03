package dev.pocketengine.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inspector.WindowInspector;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.platform.ComposeView;
import androidx.compose.ui.semantics.SemanticsActions;
import androidx.compose.ui.semantics.SemanticsNode;
import androidx.compose.ui.semantics.SemanticsOwner;
import androidx.compose.ui.semantics.SemanticsProperties;
import androidx.compose.ui.text.AnnotatedString;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.creation.DocumentDraft;
import dev.pocketengine.features.attachments.creation.PresentationDocumentWriter;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import org.json.JSONArray;
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
    @Test public void originalPresentationOwnerEditsSecondPageRestoresDraftAndExportsNewActualPptx()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();ActivityController<ComposeActivity> screen=null;var book=AttachmentRepository.get(app).book;
        try{
            JSONObject parent=app.features.createDocumentForOwner(presentationDraft(),new RunControl(),UUID.randomUUID().toString()),source=parent.getJSONObject("editableSource");byte[] old=book.readVerifiedSnapshot(parent.getString("id"),parent.getString("sha256"),new RunControl()),oldSource=book.readVerifiedSnapshot(source.getString("id"),source.getString("sha256"),new RunControl());
            screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();AttachmentOutcomeCoordinator first=flow(controller(screen.get()));first.openTask(parent.getString("hostTaskId"));await(()->!first.getState().getBusy()&&first.getState().getSnapshot()!=null);
            first.selectFile(first.getState().getSnapshot().getJSONArray("artifacts").getJSONObject(0));first.continueDocument();await(()->!first.getState().getBusy()&&first.getState().getDocumentDraft()!=null);advanceEditor(screen.get());
            JSONObject original=new JSONObject(first.getState().getDocumentDraft().toString());String firstPage=original.getJSONObject("presentation").getJSONArray("slides").getJSONObject(0).toString();assertEquals("pptx",original.getString("format"));assertFalse(original.has("body"));assertFalse(original.has("fontPoints"));assertFalse(editorHas(screen.get(),"Word · 可编辑"));
            clickEditor(screen.get(),"第 1 / 2 页 ▾",false);clickEditor(screen.get(),"2. 第二页原题",false);editEditor(screen.get(),"第二页原题","第二页修订标题");clickEditor(screen.get(),"下一段",false);editEditor(screen.get(),"同一段第一行\n第二行","修订一段第一行\n第二行保留");
            clickEditor(screen.get(),"讲稿备注 ·",true);editEditor(screen.get(),"第二页讲稿","修订讲稿\n\n结尾\n");clickEditor(screen.get(),"文本对象 ·",true);editEditor(screen.get(),"第二页对象","修订对象 <文本> & 😀");
            JSONObject edited=first.getState().getDocumentDraft();assertEquals(firstPage,edited.getJSONObject("presentation").getJSONArray("slides").getJSONObject(0).toString());assertEquals("",edited.getJSONObject("presentation").getJSONArray("slides").getJSONObject(1).getJSONArray("paragraphs").getString(0));for(String key:List.of("parentArtifactId","parentSha256","editableSourceId","editableSourceSha256"))assertEquals(original.getString(key),edited.getString(key));
            JSONObject forged=new JSONObject(edited.toString()).put("editableSourceSha256","0".repeat(64));first.createDocument(forged);assertNull(first.getState().getPendingDocumentTask());assertNotNull(first.getState().getError());assertEquals(1,app.tasks.list().length());
            screen.recreate();AttachmentOutcomeCoordinator restored=flow(controller(screen.get()));await(()->!restored.getState().getBusy()&&restored.getState().getDocumentDraft()!=null);assertEquals(edited.toString(),restored.getState().getDocumentDraft().toString());advanceEditor(screen.get());clickEditor(screen.get(),"保存为新的PPTX文件",false);clickEditor(screen.get(),"确认生成",false);
            await(()->!restored.getState().getBusy()&&restored.getState().getDocumentDraft()==null&&restored.getState().getFile()!=null&&!parent.getString("id").equals(restored.getState().getFile().getString("id")));assertNull(restored.getState().getError());assertNull(restored.getState().getPendingDocumentTask());
            JSONObject actual=restored.getState().getFile(),actualSource=actual.getJSONObject("editableSource");byte[] output=book.readVerifiedSnapshot(actual.getString("id"),actual.getString("sha256"),new RunControl());JSONObject sourceReadback=new JSONObject(new String(book.readVerifiedSnapshot(actualSource.getString("id"),actualSource.getString("sha256"),new RunControl()),StandardCharsets.UTF_8));assertFalse(sourceReadback.has("body"));assertFalse(sourceReadback.has("fontPoints"));assertEquals(parent.getString("id"),sourceReadback.getString("parentArtifactId"));assertEquals(source.getString("sha256"),sourceReadback.getString("editableSourceSha256"));
            JSONObject second=sourceReadback.getJSONObject("presentation").getJSONArray("slides").getJSONObject(1);assertEquals("second",second.getString("id"));assertEquals("第二页修订标题",second.getString("title"));assertEquals("修订一段第一行\n第二行保留",second.getJSONArray("paragraphs").getString(1));assertEquals("修订讲稿\n\n结尾\n",second.getString("notes"));JSONObject shape=second.getJSONArray("shapes").getJSONObject(0);assertEquals("second_note",shape.getString("id"));assertEquals("修订对象 <文本> & 😀",shape.getString("text"));for(String key:List.of("x","y","width","height"))assertEquals(original.getJSONObject("presentation").getJSONArray("slides").getJSONObject(1).getJSONArray("shapes").getJSONObject(0).getDouble(key),shape.getDouble(key),0.0);
            JSONObject checked=PresentationDocumentWriter.check(output,new DocumentDraft(DocumentDraft.sourceInput(sourceReadback)),new RunControl());assertTrue(checked.getBoolean("speakerNotesReadBack"));assertFalse(checked.getBoolean("visualLayoutVerified"));assertArrayEquals(old,book.readVerifiedSnapshot(parent.getString("id"),parent.getString("sha256"),new RunControl()));assertArrayEquals(oldSource,book.readVerifiedSnapshot(source.getString("id"),source.getString("sha256"),new RunControl()));assertEquals(2,app.tasks.list().length());assertEquals(4,book.list().length());
            assertTrue(((AttachmentsModule)app.features.module("attachments")).documentCapabilities().getJSONArray("creationFormats").toString().contains("pptx"));
            restored.prepareExport();destination=new File(app.getCacheDir(),"native-presentation-revision.pptx");android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="b4-saf-fixture";info.exported=false;ShadowContentResolver.registerProviderInternal("b4-saf-fixture",Robolectric.buildContentProvider(ExportProvider.class).create(info).get());restored.exportResult(Uri.parse("content://b4-saf-fixture/selected-document"));await(()->!restored.getState().getBusy());assertNull(restored.getState().getError());assertArrayEquals(output,Files.readAllBytes(destination.toPath()));
        }finally{if(screen!=null)screen.pause().stop().destroy();app.features.close();}
    }
    @Test public void stoppedPresentationSaveRestoresOnlyOriginalUnknownQueryAndRechecksWithdrawnSource()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();ActivityController<ComposeActivity> screen=null;AttachmentOutcomeCoordinator first=null,restored=null,withdrawn=null;ComposeView mounted=null;var book=AttachmentRepository.get(app).book;
        try{
            JSONObject parent=app.features.createDocumentForOwner(presentationDraft(),new RunControl(),UUID.randomUUID().toString());screen=Robolectric.buildActivity(ComposeActivity.class).setup().visible();QueuedIo io=new QueuedIo();first=controlledFlow(screen.get(),app,io);first.foreground(true);first.openFile(parent);io.drain();assertNotNull(first.getState().getFile());first.continueDocument();io.drain();JSONObject draft=new JSONObject(first.getState().getDocumentDraft().toString());draft.getJSONObject("presentation").getJSONArray("slides").getJSONObject(1).put("title","待核原稿");
            first.createDocument(draft);String action=first.getState().getPendingDocumentTask();assertNotNull(action);first.cancel();io.drain();assertEquals("unknown",app.tasks.get(action,false).getString("status"));assertEquals(2,app.tasks.list().length());assertEquals(2,book.list().length());
            first.createDocument(draft);first.newDocument();assertEquals(action,first.getState().getPendingDocumentTask());assertTrue(io.empty());Bundle saved=new Bundle();first.save(saved);first.detach();
            QueuedIo restoredIo=new QueuedIo();restored=controlledFlow(screen.get(),app,restoredIo);restored.restore(saved);restored.foreground(true);restoredIo.drain();assertEquals(action,restored.getState().getPendingDocumentTask());assertEquals(draft.toString(),restored.getState().getDocumentDraft().toString());
            mounted=mountOutcome(screen.get(),restored);advanceEditor(screen.get());assertEditorDisabled(screen.get(),"保存为新的PPTX文件");JSONObject changed=new JSONObject(draft.toString()).put("name","不能改未知稿");restored.updateDocumentDraft(changed);assertEquals(draft.toString(),restored.getState().getDocumentDraft().toString());clickEditor(screen.get(),"核对原文档结果",false);restoredIo.drain();assertEquals(action,restored.getState().getPendingDocumentTask());assertEquals("unknown",app.tasks.get(action,false).getString("status"));assertEquals(2,app.tasks.list().length());assertEquals(2,book.list().length());
            mounted.disposeComposition();mounted=null;restored.detach();JSONObject source=parent.getJSONObject("editableSource");java.nio.file.Path sourcePath=book.file(source.getString("id")).toPath();byte[] sourceBytes=Files.readAllBytes(sourcePath);Files.write(sourcePath,"{}".getBytes(StandardCharsets.UTF_8));
            try{QueuedIo withdrawnIo=new QueuedIo();withdrawn=controlledFlow(screen.get(),app,withdrawnIo);withdrawn.restore(saved);withdrawn.foreground(true);withdrawnIo.drain();assertNull(withdrawn.getState().getDocumentDraft());assertNotNull(withdrawn.getState().getError());assertEquals(action,withdrawn.getState().getPendingDocumentTask());withdrawn.newDocument();withdrawn.createDocument(draft);assertTrue(withdrawnIo.empty());withdrawn.queryDocument();withdrawnIo.drain();assertEquals(action,withdrawn.getState().getPendingDocumentTask());assertEquals(2,app.tasks.list().length());assertEquals("Only the intact parent remains visible after source withdrawal",1,book.list().length());}finally{Files.write(sourcePath,sourceBytes);}
        }finally{if(mounted!=null)mounted.disposeComposition();if(first!=null)first.detach();if(restored!=null)restored.detach();if(withdrawn!=null)withdrawn.detach();if(screen!=null)screen.pause().stop().destroy();app.features.close();}
    }
    private static JSONObject presentationDraft()throws Exception {
        return new JSONObject().put("format","pptx").put("name","主人演示原件").put("presentation",new JSONObject().put("version",1).put("slides",new JSONArray()
                .put(new JSONObject().put("id","first").put("title","第一页保持不变").put("paragraphs",new JSONArray().put("原首段").put("")).put("notes","原首页讲稿"))
                .put(new JSONObject().put("id","second").put("title","第二页原题").put("paragraphs",new JSONArray().put("").put("同一段第一行\n第二行").put("末段保持不变")).put("notes","第二页讲稿").put("shapes",new JSONArray().put(new JSONObject().put("id","second_note").put("text","第二页对象").put("x",1.25).put("y",5.5).put("width",3.5).put("height",0.75))))));
    }
    private static final class QueuedIo implements Executor {
        private final ArrayDeque<Runnable> pending=new ArrayDeque<>();public void execute(Runnable job){pending.addLast(job);}boolean empty(){return pending.isEmpty();}
        void drain(){for(int i=0;i<40;i++){if(!pending.isEmpty())pending.removeFirst().run();Shadows.shadowOf(Looper.getMainLooper()).idle();if(pending.isEmpty())return;}throw new AssertionError("Original owner IO did not settle");}
    }
    private static AttachmentOutcomeCoordinator controlledFlow(ComposeActivity activity,EngineApplication app,Executor io){return new AttachmentOutcomeCoordinator(activity,app,io,new Handler(Looper.getMainLooper()),()->{throw new AssertionError("No group owner window");},id->Unit.INSTANCE,()->Unit.INSTANCE);}
    private static ComposeView mountOutcome(ComposeActivity activity,AttachmentOutcomeCoordinator flow)throws Exception {
        var overlay=AttachmentOutcomeOverlayKt.class.getDeclaredMethod("AttachmentOutcomeOverlay",AttachmentOutcomeCoordinator.class,Composer.class,int.class);overlay.setAccessible(true);ComposeView view=new ComposeView(activity);view.setContent(new Function2<Composer,Integer,Unit>(){public Unit invoke(Composer composer,Integer flags){try{overlay.invoke(null,flow,composer,0);return Unit.INSTANCE;}catch(Exception failure){throw new IllegalStateException(failure);}}});activity.setContentView(view);return view;
    }
    private static void advanceEditor(ComposeActivity activity)throws Exception {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));int width=Math.round(activity.getResources().getConfiguration().screenWidthDp*activity.getResources().getDisplayMetrics().density),height=Math.round(activity.getResources().getConfiguration().screenHeightDp*activity.getResources().getDisplayMetrics().density);
        for(int i=0;i<6;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));for(View root:WindowInspector.getGlobalWindowViews()){if(!root.isAttachedToWindow())continue;root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);root.invalidate();}Thread.sleep(5);}
    }
    private static View editorCompose(View view){if(view.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView"))return view;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View child=editorCompose(group.getChildAt(i));if(child!=null)return child;}return null;}
    private static void collectEditor(SemanticsNode node,List<SemanticsNode> nodes){nodes.add(node);for(SemanticsNode child:node.getChildren())collectEditor(child,nodes);}
    private static List<SemanticsNode> editorNodes()throws Exception {List<SemanticsNode> nodes=new ArrayList<>();for(View root:WindowInspector.getGlobalWindowViews()){if(!root.isAttachedToWindow()||root.getVisibility()!=View.VISIBLE)continue;View composed=editorCompose(root);if(composed!=null)collectEditor(((SemanticsOwner)composed.getClass().getMethod("getSemanticsOwner").invoke(composed)).getRootSemanticsNode(),nodes);}return nodes;}
    private static String editorText(SemanticsNode node){var key=SemanticsProperties.INSTANCE.getText();if(!node.getConfig().contains(key))return "";StringBuilder text=new StringBuilder();for(AnnotatedString value:node.getConfig().get(key)){if(text.length()>0)text.append(' ');text.append(value.getText());}return text.toString();}
    private static boolean editorHas(ComposeActivity activity,String label)throws Exception {for(var node:editorNodes())if(editorText(node).contains(label))return true;return false;}
    private static void clickEditor(ComposeActivity activity,String label,boolean prefix)throws Exception {for(var node:editorNodes()){var key=SemanticsActions.INSTANCE.getOnClick();String text=editorText(node);if(!(prefix?text.startsWith(label):text.equals(label))||!node.getConfig().contains(key))continue;assertFalse("Disabled native editor action",node.getConfig().contains(SemanticsProperties.INSTANCE.getDisabled()));assertNotNull(node.getConfig().get(key).getAction());assertEquals(Boolean.TRUE,((Function0<?>)node.getConfig().get(key).getAction()).invoke());advanceEditor(activity);return;}throw new AssertionError("No native editor action "+label);}
    private static void editEditor(ComposeActivity activity,String previous,String value)throws Exception {for(var node:editorNodes()){var key=SemanticsActions.INSTANCE.getSetText();var editable=SemanticsProperties.INSTANCE.getEditableText();if(!node.getConfig().contains(key)||!node.getConfig().contains(editable)||!previous.equals(node.getConfig().get(editable).getText()))continue;assertFalse(node.getConfig().contains(SemanticsProperties.INSTANCE.getDisabled()));assertEquals(Boolean.TRUE,((Function1<AnnotatedString,?>)node.getConfig().get(key).getAction()).invoke(new AnnotatedString(value,java.util.Collections.emptyList(),java.util.Collections.emptyList())));advanceEditor(activity);return;}throw new AssertionError("No native editor field "+previous);}
    private static void assertEditorDisabled(ComposeActivity activity,String label)throws Exception {for(var node:editorNodes())if(editorText(node).equals(label)&&node.getConfig().contains(SemanticsProperties.INSTANCE.getDisabled()))return;throw new AssertionError("Native editor action must be disabled: "+label);}
    static Object field(Object value,String name)throws Exception{var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    static void capture(ComposeActivity activity,String name)throws Exception {
        File directory=new File(System.getProperty("b4.outcome.evidence","/tmp/b4-outcome-evidence"));directory.mkdirs();int width=(int)(activity.getResources().getConfiguration().screenWidthDp*activity.getResources().getDisplayMetrics().density),height=(int)(activity.getResources().getConfiguration().screenHeightDp*activity.getResources().getDisplayMetrics().density);
        Window window=ShadowDialog.getLatestDialog()!=null&&ShadowDialog.getLatestDialog().isShowing()?ShadowDialog.getLatestDialog().getWindow():activity.getWindow();
        for(int i=0;i<12;i++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));View root=window.getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);root.invalidate();Thread.sleep(2);}
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);int[] result={-1};PixelCopy.request(window,bitmap,code->result[0]=code,new Handler(Looper.getMainLooper()));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(PixelCopy.SUCCESS,result[0]);try(FileOutputStream out=new FileOutputStream(new File(directory,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
}
