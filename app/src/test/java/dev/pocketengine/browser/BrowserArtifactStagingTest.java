package dev.pocketengine.browser;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.AttachmentViewSnapshots;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original AttachmentBook bytes and the provider's bounded verified-copy format. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class)
public final class BrowserArtifactStagingTest {
    @Test public void stagedCopyUsesThePinnedOriginalBytesAndRevokedSourceCannotBeRestaged()throws Exception {
        File root=Files.createTempDirectory("browser-artifact-stage-").toFile();Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return new File(root,"files");}
            @Override public File getCacheDir(){return new File(root,"cache");}
        };
        String task=UUID.randomUUID().toString();RunControl original=new RunControl();original.bindHostRun(task,UUID.randomUUID().toString(),1);
        byte[] content="原任务准确文件版本".getBytes(StandardCharsets.UTF_8);AttachmentRepository repository=AttachmentRepository.get(context);
        JSONObject record=repository.book.importBytes(new ByteArrayInputStream(content),"original.txt","text/plain",new JSONObject().put("taskId",task));
        JSONObject selected=new JSONObject().put("store","attachments").put("id",record.getString("id")).put("sha256",record.getString("sha256"));
        JSONObject pinned=BrowserArtifactSource.pin(context,null,task,selected);var worker=Executors.newSingleThreadExecutor();
        try {
            Uri uri=worker.submit(()->BrowserArtifactSource.stage(context,null,task,pinned,original)).get(4,TimeUnit.SECONDS);
            assertEquals(context.getPackageName()+".attachments",uri.getAuthority());assertEquals("view",uri.getPathSegments().get(0));
            File exactCopy=new File(context.getCacheDir(),"outcome-views/"+uri.getLastPathSegment()+".blob");assertArrayEquals(content,Files.readAllBytes(exactCopy.toPath()));
            repository.remove(record.getString("id"));assertThrows(Exception.class,()->BrowserArtifactSource.requireCurrent(context,null,task,pinned));
            assertThrows(java.util.concurrent.ExecutionException.class,()->worker.submit(()->BrowserArtifactSource.stage(context,null,task,pinned,original)).get(4,TimeUnit.SECONDS));
            new AttachmentViewSnapshots(context).discardUnsent(uri);assertFalse(exactCopy.exists());
            assertThrows(SecurityException.class,()->new AttachmentViewSnapshots(context).discardUnsent(Uri.parse("content://other.provider/view/"+UUID.randomUUID())));
        } finally {worker.shutdownNow();}
    }
}
