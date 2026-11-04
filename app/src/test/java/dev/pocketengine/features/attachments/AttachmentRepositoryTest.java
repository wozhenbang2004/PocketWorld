package dev.pocketengine.features.attachments;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class AttachmentRepositoryTest {
    private Context context()throws Exception{File files=Files.createTempDirectory("pocket-attachment-android-").toFile();return new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};}
    @Test public void sharedTextBecomesRealPrivateAttachmentAndReadOnlyShareGrant()throws Exception{
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject result=repository.importIntent(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"hello 附件"),"task-1","project-1");JSONObject item=result.getJSONArray("attachments").getJSONObject(0);String id=item.getString("id");
        assertEquals("imported",result.getString("status"));assertEquals("hello 附件",repository.book.readText(id).getString("text"));assertEquals("task-1",item.getJSONObject("source").getString("taskId"));Intent share=repository.shareIntent(id);assertEquals(0,share.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);assertNotEquals(0,share.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);assertNotEquals(repository.uri(id),share.getClipData().getItemAt(0).getUri());assertEquals("view",share.getClipData().getItemAt(0).getUri().getPathSegments().get(0));assertEquals(share.getParcelableExtra(Intent.EXTRA_STREAM),share.getClipData().getItemAt(0).getUri());
    }
    @Test public void pickerCannotSmuggleNativePathsOrPrivateAttachmentReferences()throws Exception{
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);
        assertThrows(IllegalArgumentException.class,()->repository.importUri(Uri.parse("file:///data/data/private/tasks.json"),0,"",""));
        assertThrows(SecurityException.class,()->repository.importUri(Uri.parse("content://"+context.getPackageName()+".attachments/00000000-0000-0000-0000-000000000000"),Intent.FLAG_GRANT_READ_URI_PERMISSION,"",""));
    }
    @Test public void providerExposesOnlyValidatedReadOnlyAttachmentUris()throws Exception{
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);String id=repository.importIntent(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT,"private"),"","").getJSONArray("attachments").getJSONObject(0).getString("id");AttachmentProvider provider=new AttachmentProvider();ProviderInfo info=new ProviderInfo();info.authority=context.getPackageName()+".attachments";provider.attachInfo(context,info);Uri uri=repository.uri(id);
        try(android.database.Cursor row=provider.query(uri,null,null,null,null)){assertTrue(row.moveToFirst());assertEquals("shared-text.txt",row.getString(row.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));assertEquals(7,row.getLong(row.getColumnIndexOrThrow(OpenableColumns.SIZE)));}
        assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(uri,"rw"));assertThrows(IllegalArgumentException.class,()->provider.query(Uri.parse("content://"+info.authority+"/../tasks.json"),null,null,null,null));assertThrows(UnsupportedOperationException.class,()->provider.delete(uri,null,null));
    }
    @Test public void reviewedOpenUsesExactPreparedBytesAfterTheCanonicalFileHasChanged()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=repository.book.importBytes(new java.io.ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)),"reviewed.txt","text/plain",new JSONObject());
        Intent intent=repository.verifiedViewIntent(file.getString("id"),file.getString("sha256"),new RunControl());Uri uri=intent.getData();assertEquals(2,uri.getPathSegments().size());assertEquals("view",uri.getPathSegments().get(0));assertNotEquals(repository.uri(file.getString("id")),uri);assertEquals(0,intent.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Files.writeString(repository.book.file(file.getString("id")).toPath(),"replaced",StandardCharsets.UTF_8);AttachmentProvider provider=new AttachmentProvider();ProviderInfo info=new ProviderInfo();info.authority=context.getPackageName()+".attachments";provider.attachInfo(context,info);
        try(var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri,"r"))){assertArrayEquals("original".getBytes(StandardCharsets.UTF_8),input.readAllBytes());}
        File snapshot=new File(new File(context.getCacheDir(),"outcome-views"),uri.getLastPathSegment()+".blob");Files.writeString(snapshot.toPath(),"tampered",StandardCharsets.UTF_8);assertThrows(java.io.FileNotFoundException.class,()->provider.openFile(uri,"r"));
    }
    @Test public void sourceChangeAfterSnapshotPreparationRevokesTheUnissuedCopy()throws Exception {
        Context context=context();AttachmentRepository repository=AttachmentRepository.get(context);JSONObject file=repository.book.importBytes(new java.io.ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)),"source.txt","text/plain",new JSONObject());File snapshots=new File(context.getCacheDir(),"outcome-views");java.util.Set<String> before=new java.util.HashSet<>();File[] existing=snapshots.listFiles();if(existing!=null)for(File entry:existing)before.add(entry.getName());
        RunControl live=new RunControl();AtomicBoolean copied=new AtomicBoolean();live.setAuthorityCheck(()->{File[] records=snapshots.listFiles((d,n)->n.endsWith(".json")&&!before.contains(n));if(records!=null&&records.length>0&&copied.compareAndSet(false,true))Files.writeString(repository.book.file(file.getString("id")).toPath(),"replaced",StandardCharsets.UTF_8);});
        IllegalStateException changed=assertThrows(IllegalStateException.class,()->repository.verifiedViewIntent(file.getString("id"),file.getString("sha256"),live));assertTrue(changed.getMessage().contains("原文件"));assertTrue(copied.get());File[] retained=snapshots.listFiles((d,n)->!before.contains(n));assertTrue(retained==null||retained.length==0);
        RunControl stopped=new RunControl();stopped.cancel();assertThrows(InterruptedException.class,()->repository.verifiedViewIntent(file.getString("id"),file.getString("sha256"),stopped));
    }
}
