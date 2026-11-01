package dev.pocketengine.features.attachments;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.assistant.AttachmentRequestProjection;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real private bytes, UTF-8 parser and SQLite. No provider/weight, PDF/OCR fixture or device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AttachmentOutcomeJourneyTest {
    @Test public void longSourceIsSelectedThenReadByRangeAndContinuesAsTheSamePersistedTask()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app);AttachmentRepository repo=AttachmentRepository.get(app);
        AttachmentsModule docs=new AttachmentsModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),new DocumentReader(null,null));
        AttachmentOutcomeHost host=new AttachmentOutcomeHost(tasks,repo,docs,(command,payload,control)->{throw new AssertionError("No media effect expected");});
        String content=("原始来源 "+"abcdefgh".repeat(20)+"\n").repeat(14000);JSONObject file=repo.book.importBytes(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),"真实长文档.txt","text/plain",new JSONObject());assertTrue(file.getLong("size")>2*1024*1024);
        JSONObject task=tasks.createCapabilityTask("只查原文第一行，保留来源并继续同一目标","conversation-source");String id=task.getString("id");host.selectForTask(id,file.getString("id"),file.getString("sha256"),new RunControl());
        task=tasks.startAttempt(id,"capabilities",8);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl live=new RunControl();live.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("attachments_read_document"));
        AttachmentRequestProjection projection=new AttachmentRequestProjection(repo.book,docs,(ref,model,control)->{if(control.hasHostRun()){host.requireReference(id,ref.getString("id"),ref.getString("sha256"));tasks.noteOwnerDocumentReference(id,run,epoch,ref,control);}});
        ModelConfig model=new ModelConfig("https://example.invalid/v1","","text-model",false,8,30);JSONArray refs=projection.select(new JSONArray().put(new JSONObject().put("id",file.getString("id")).put("sha256",file.getString("sha256"))),model,new RunControl());
        JSONArray wire=projection.project(new JSONArray().put(new JSONObject().put("role","user").put("content","按当前目标查第一行").put("ownerAttachmentRefs",refs)),model,live);
        assertTrue(wire.toString().contains("not_read_by_model"));assertTrue(wire.toString().contains("attachments_read_document"));assertFalse(wire.toString().contains("abcdefgh"));assertTrue(tasks.hasUnreadOwnerDocumentReference(id,run));
        JSONObject read=host.readTaskDocument(id,run,epoch,file.getString("id"),file.getString("sha256"),new DocumentContent.Request(1,0,1,2048,8,"text"),live);
        assertEquals(file.getString("sha256"),read.getString("sha256"));assertEquals("1",read.getJSONArray("chunks").getJSONObject(0).getJSONObject("locator").getString("line"));assertEquals(2,read.getInt("nextUnit"));assertTrue(read.getBoolean("truncated"));assertFalse(tasks.hasUnreadOwnerDocumentReference(id,run));
        assertEquals(id,tasks.get(id,false).getString("id"));assertEquals(run,tasks.get(id,false).getString("runId"));assertEquals(0,tasks.get(id,false).getJSONArray("operations").length());
        tasks.endAttempt(id,run,"needs_confirmation","保留按范围读取的原文，不声明全文已读",new JSONObject());TaskStore reopened=new TaskStore(app);JSONObject durable=reopened.get(id,false).getJSONArray("documentSources").getJSONObject(0);
        assertEquals(file.getString("sha256"),durable.getString("sha256"));assertEquals("partially_read_by_native_tool",durable.getString("readState"));assertEquals(run,durable.getJSONArray("nativeReadRanges").getJSONObject(0).getString("runId"));assertEquals(id,reopened.get(id,false).getString("id"));
        assertThrows(IllegalArgumentException.class,()->new AttachmentRequestProjection(repo.book,docs).select(new JSONArray().put(new JSONObject().put("id",file.getString("id")).put("sha256",file.getString("sha256"))),model,new RunControl()));assertTrue(repo.book.file(file.getString("id")).isFile());
    }
    @Test public void replacedBytesNeverProduceReadEvidenceOrReleaseAnUnreadReference()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(app);AttachmentRepository repo=AttachmentRepository.get(app);AttachmentsModule docs=new AttachmentsModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));
        AttachmentOutcomeHost host=new AttachmentOutcomeHost(tasks,repo,docs,(a,b,c)->{throw new AssertionError();});JSONObject file=repo.book.importBytes(new ByteArrayInputStream("original line".getBytes(StandardCharsets.UTF_8)),"source.txt","text/plain",new JSONObject());
        JSONObject task=tasks.createCapabilityTask("read selected source","source-version");String id=task.getString("id");host.selectForTask(id,file.getString("id"),file.getString("sha256"),new RunControl());task=tasks.startAttempt(id,"capabilities",4);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl live=new RunControl();live.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("attachments_read_document"));
        tasks.noteOwnerDocumentReference(id,run,epoch,file,live);Files.write(repo.book.file(file.getString("id")).toPath(),"replaced line".getBytes(StandardCharsets.UTF_8));JSONObject result=host.readTaskDocument(id,run,epoch,file.getString("id"),file.getString("sha256"),DocumentContent.Request.defaults(),live);
        assertEquals("input_changed",result.getString("status"));assertEquals(0,result.getJSONArray("chunks").length());assertTrue(tasks.hasUnreadOwnerDocumentReference(id,run));assertEquals("reference_only",tasks.get(id,false).getJSONArray("documentSources").getJSONObject(0).getString("readState"));
    }
}
