package dev.pocketengine.features.attachments;

import android.app.Application;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual repository, module and original TaskStore/OutcomeHost. Not a mounted Activity or device lifecycle proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class OwnerAttachmentImportTest {
    private static final class Fixture {
        final Application app=RuntimeEnvironment.getApplication();
        final TaskStore tasks=new TaskStore(app);
        final AttachmentRepository repo=AttachmentRepository.get(app);
        final AttachmentsModule module=new AttachmentsModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));
        final AttachmentOutcomeHost host=new AttachmentOutcomeHost(tasks,repo,module,(a,b,c)->{throw new AssertionError("No external media work");});
        String task()throws Exception{return tasks.createCapabilityTask("保留原来源","import-source").getString("id");}
        JSONObject link(String task,JSONObject file)throws Exception{return host.selectForTask(task,file.getString("id"),file.getString("sha256"),new RunControl());}
        JSONObject only()throws Exception{JSONArray files=repo.book.list();assertEquals(1,files.length());return files.getJSONObject(0);}
    }
    private static Intent text(){return new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,"original bytes 原件");}
    private static Intent files(int count){
        ClipData clips=null;
        for(int i=0;i<count;i++){
            Uri uri=Uri.parse("content://original-import-fixture/file-"+i);Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(("original-"+i).getBytes(StandardCharsets.UTF_8)));
            if(clips==null)clips=ClipData.newRawUri("chosen",uri);else clips.addItem(new ClipData.Item(uri));
        }
        Intent result=new Intent();result.setClipData(clips);return result;
    }
    @Test public void anExplicitLibraryTargetIsNotMistakenForLostContext()throws Exception {
        Fixture f=new Fixture();var receipt=OwnerAttachmentImport.receive(f.module,text(),new OwnerAttachmentImport.Target(null,null),(task,file)->{throw new AssertionError("No task was selected");});
        assertEquals(1,receipt.imported);assertEquals(0,receipt.linked);assertEquals(0,receipt.associationUnconfirmed);assertNull(receipt.error);assertTrue(receipt.notice.contains("附件库"));
        JSONObject file=f.only();assertFalse(file.getJSONObject("source").has("taskId"));assertFalse(file.getJSONObject("source").has("projectId"));assertEquals("original bytes 原件",f.repo.book.readText(file.getString("id")).getString("text"));
    }
    @Test public void aLostLegacyContextKeepsBytesWithoutBorrowingIntentOrCurrentTaskIdentity()throws Exception {
        Fixture f=new Fixture();String current=f.task();Intent incoming=text().putExtra("taskId",current).putExtra("projectId","later-project");
        var receipt=OwnerAttachmentImport.receive(f.module,incoming,null,(task,file)->{throw new AssertionError("Missing provenance cannot link");});
        assertEquals(1,receipt.imported);assertEquals(0,receipt.linked);assertTrue(receipt.error.contains("上下文已丢失"));assertTrue(receipt.error.contains("未自动关联"));assertTrue(receipt.notice.contains("已保存 1"));
        JSONObject file=f.only();assertFalse(file.getJSONObject("source").has("taskId"));assertFalse(file.getJSONObject("source").has("projectId"));assertEquals(0,f.tasks.ownerAttachmentReferences(current).length());assertEquals(1,f.tasks.list().length());
    }
    @Test public void frozenOriginalTaskAndProjectAreTheOnlyAssociationTargets()throws Exception {
        Fixture f=new Fixture();String original=f.task();var pin=new OwnerAttachmentImport.Target(original,"original-project");String later=f.task();
        var receipt=OwnerAttachmentImport.receive(f.module,text(),pin,f::link);JSONObject file=f.only();
        assertEquals(1,receipt.imported);assertEquals(1,receipt.linked);assertNull(receipt.error);assertTrue(receipt.notice.contains("已确认关联原任务"));assertEquals("original-project",file.getJSONObject("source").getString("projectId"));assertFalse(file.getJSONObject("source").has("taskId"));
        assertEquals(file.getString("id"),new TaskStore(f.app).ownerAttachmentReferences(original).getJSONObject(0).getString("id"));assertEquals(0,f.tasks.ownerAttachmentReferences(later).length());assertEquals(2,f.tasks.list().length());
    }
    @Test public void aLinkFailureBeforeCommitStillReportsAndRetainsTheImportedOriginal()throws Exception {
        Fixture f=new Fixture();String original=f.task();AtomicInteger calls=new AtomicInteger();
        var receipt=OwnerAttachmentImport.receive(f.module,text(),new OwnerAttachmentImport.Target(original,null),(task,file)->{calls.incrementAndGet();throw new SecurityException("Original route unavailable");});
        assertEquals(1,calls.get());assertEquals(1,receipt.imported);assertEquals(0,receipt.linked);assertEquals(1,receipt.associationUnconfirmed);assertTrue(receipt.notice.contains("已保存 1"));assertTrue(receipt.error.contains("关联未确认"));assertFalse(receipt.error.contains("导入失败"));assertEquals(0,f.tasks.ownerAttachmentReferences(original).length());assertTrue(f.repo.book.file(f.only().getString("id")).isFile());
    }
    @Test public void aPostCommitExceptionIsUnknownAssociationAndNeverAutomaticallyRelinks()throws Exception {
        Fixture f=new Fixture();String original=f.task();AtomicInteger calls=new AtomicInteger();
        var receipt=OwnerAttachmentImport.receive(f.module,text(),new OwnerAttachmentImport.Target(original,null),(task,file)->{calls.incrementAndGet();f.link(task,file);throw new IllegalStateException("After durable original link");});
        assertEquals(1,calls.get());assertEquals(1,receipt.imported);assertEquals(0,receipt.linked);assertEquals(1,receipt.associationUnconfirmed);assertTrue(receipt.error.contains("关联未确认"));assertEquals(1,new TaskStore(f.app).ownerAttachmentReferences(original).length());assertEquals(1,f.repo.book.list().length());
    }
    @Test public void partialLinksContinueWithinTheSameBoundedBatchAndNeverDeleteSavedBytes()throws Exception {
        Fixture f=new Fixture();String original=f.task(),later=f.task();AtomicInteger calls=new AtomicInteger();
        var receipt=OwnerAttachmentImport.receive(f.module,files(3),new OwnerAttachmentImport.Target(original,"original-project"),(task,file)->{assertEquals(original,task);if(calls.incrementAndGet()==2)throw new SecurityException("one unavailable association");return f.link(task,file);});
        assertEquals(3,calls.get());assertEquals(3,receipt.imported);assertEquals(2,receipt.linked);assertEquals(1,receipt.associationUnconfirmed);assertEquals(3,f.repo.book.list().length());assertEquals(2,f.tasks.ownerAttachmentReferences(original).length());assertEquals(0,f.tasks.ownerAttachmentReferences(later).length());assertTrue(receipt.notice.contains("已保存 3"));assertTrue(receipt.notice.contains("2 个已确认"));assertTrue(receipt.error.contains("另有 1"));
    }
    @Test public void repositoryPartialImportAndAssociationAreIndependentFacts()throws Exception {
        Fixture f=new Fixture();String task=f.task();Intent chosen=files(1);chosen.getClipData().addItem(new ClipData.Item(Uri.parse("file:///not-an-authorized-document")));
        var receipt=OwnerAttachmentImport.receive(f.module,chosen,new OwnerAttachmentImport.Target(task,null),f::link);
        assertEquals(1,receipt.imported);assertEquals(1,receipt.linked);assertEquals(0,receipt.associationUnconfirmed);assertNotNull(receipt.error);assertTrue(receipt.notice.contains("已保存 1"));assertEquals(1,f.repo.book.list().length());assertEquals(1,f.tasks.ownerAttachmentReferences(task).length());assertEquals(0,chosen.getFlags());assertTrue(f.app.getContentResolver().getPersistedUriPermissions().isEmpty());
    }
    @Test public void mismatchedReturnedReferenceCannotConfirmAFileLink()throws Exception {
        Fixture f=new Fixture();var receipt=OwnerAttachmentImport.receive(f.module,text(),new OwnerAttachmentImport.Target(f.task(),null),(task,file)->new JSONObject().put("id","other").put("sha256",file.getString("sha256")));
        assertEquals(1,receipt.imported);assertEquals(0,receipt.linked);assertEquals(1,receipt.associationUnconfirmed);assertTrue(receipt.error.contains("关联未确认"));assertEquals(1,f.repo.book.list().length());
    }
    @Test public void aModuleEventFailureDoesNotRollBackAlreadyDurableBytes()throws Exception {
        Fixture f=new Fixture();AttachmentsModule eventsFail=new AttachmentsModule(new FeatureContext(f.app,new SettingsStore(f.app),new SecretVault(f.app),event->{throw new IllegalStateException("display event failed after import");}));
        assertThrows(IllegalStateException.class,()->OwnerAttachmentImport.receive(eventsFail,text(),new OwnerAttachmentImport.Target(null,null),(task,file)->{throw new AssertionError();}));
        assertEquals("original bytes 原件",f.repo.book.readText(f.only().getString("id")).getString("text"));assertEquals(0,f.tasks.list().length());
    }
    @Test public void moreThanTenSelectionsFailBeforeAnyBytesOrLinksArePublished()throws Exception {
        Fixture f=new Fixture();assertThrows(IllegalArgumentException.class,()->OwnerAttachmentImport.receive(f.module,files(11),new OwnerAttachmentImport.Target(f.task(),null),(task,file)->{throw new AssertionError("No link after input bound");}));assertEquals(0,f.repo.book.list().length());
    }
    @Test public void targetSaveRestorePreservesExplicitNullsAndDoesNotRetainDestinationOrContent() {
        var original=new OwnerAttachmentImport.Target("original-task","original-project");Bundle saved=original.save();var restored=OwnerAttachmentImport.Target.restore(saved);
        assertNotNull(restored);assertEquals(original.requestId,restored.requestId);assertEquals(original.taskId,restored.taskId);assertEquals(original.projectId,restored.projectId);assertEquals(6,saved.keySet().size());
        saved.putString("taskId","later-task");assertEquals("original-task",original.taskId);assertEquals("original-task",restored.taskId);
        var global=OwnerAttachmentImport.Target.restore(new OwnerAttachmentImport.Target(null,null).save());assertNotNull(global);assertNull(global.taskId);assertNull(global.projectId);
    }
    @Test public void incompleteContextCannotBecomeAnExplicitLibraryGrant() {
        var original=new OwnerAttachmentImport.Target("original-task","original-project");Bundle missing=original.save();missing.remove("taskId");assertNull(OwnerAttachmentImport.Target.restore(missing));
        Bundle invalid=original.save();invalid.remove("hasProject");assertNull(OwnerAttachmentImport.Target.restore(invalid));Bundle nonce=original.save();nonce.putString("requestId","foreign");assertNull(OwnerAttachmentImport.Target.restore(nonce));assertNull(OwnerAttachmentImport.Target.restore(new Bundle()));assertNull(OwnerAttachmentImport.Target.restore(null));
        assertThrows(IllegalArgumentException.class,()->new OwnerAttachmentImport.Target("x".repeat(101),null));
    }
}
