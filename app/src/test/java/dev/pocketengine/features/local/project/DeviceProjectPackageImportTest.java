package dev.pocketengine.features.local.project;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original Book/staging/manifest/import CAS. Device receipt metadata here is an explicit
 * fixture; DeviceFileDeliveryTest separately exercises the SQL-authorized bulk/Book factory. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class DeviceProjectPackageImportTest {
    @Test public void explicitReceivedPackageColdReviewAndTargetCasPreserveOriginal() throws Exception {
        var android=RuntimeEnvironment.getApplication();var context=new FeatureContext(android,null,null,event->{});var workspace=new WorkspaceModule(context);
        var book=AttachmentRepository.get(android).book;var source=Files.createTempDirectory("device-package-source");byte[] binary={0,1,-1,2,0};
        try(LocalModule local=new LocalModule(context,workspace)) {
            Files.writeString(source.resolve("index.html"),"device fixed project");Files.write(source.resolve("data.bin"),binary);
            var frozen=ProjectPackageArchive.capture(source.toFile(),ProjectPackageArchiveTest.original(),"source_project","",false);var out=new ByteArrayOutputStream();ProjectPackageArchive.write(frozen,out);
            JSONObject attachment=book.importBytes(new ByteArrayInputStream(out.toByteArray()),"device-project.zip","application/zip",new JSONObject().put("kind","device_received_file").put("sourceDeviceId","pw1_"+"a".repeat(64)).put("artifactId","remote-fixed-project"));
            assertEquals(0,local.listStagedProjectPackagesForOwner().length());assertEquals(0,workspace.snapshot().getJSONArray("projects").length());
            JSONObject staged=local.stageDeviceProjectAttachmentForOwner(attachment.getString("id"),attachment.getString("sha256"),new RunControl());assertEquals(attachment.getString("sha256"),staged.getString("sha256"));assertTrue(staged.has("deviceSource"));assertEquals(0,workspace.snapshot().getJSONArray("projects").length());
            try(LocalModule cold=new LocalModule(context,workspace)) {
                JSONObject reopened=cold.inspectStagedProjectPackageForOwner(staged.getString("selectionId"),staged.getString("sha256"));assertEquals(staged.getJSONObject("deviceSource").toString(),reopened.getJSONObject("deviceSource").toString());
                JSONObject target=cold.createProjectForOwner("original target","web");String checkout=target.getString("id");JSONObject plan=cold.planProjectPackageImportForOwner(staged.getString("selectionId"),staged.getString("sha256"),"received new checkout",checkout).getJSONObject("plan");
                JSONObject read=cold.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","index.html"));cold.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","index.html").put("expectedHash",read.getString("sha256")).put("content","original changed after review"));
                assertThrows(SecurityException.class,()->cold.importProjectPackageForOwner(plan));assertFalse(cold.findProjectPackageImportForOwner(plan).getBoolean("found"));
                JSONObject latest=cold.planProjectPackageImportForOwner(staged.getString("selectionId"),staged.getString("sha256"),"received new checkout",checkout).getJSONObject("plan");JSONObject imported=cold.importProjectPackageForOwner(latest);
                assertNotEquals(checkout,imported.getString("id"));assertEquals(target.getString("projectId"),imported.getString("projectId"));assertFalse(imported.getBoolean("taskOrGrantRestored"));assertEquals(attachment.getString("id"),imported.getJSONObject("sourceImport").getJSONObject("deviceSource").getString("attachmentId"));
                assertEquals("original changed after review",cold.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","index.html")).getString("content"));
                assertArrayEquals(binary,Files.readAllBytes(android.getFilesDir().toPath().resolve("local-development/checkouts/"+imported.getString("id")+"/files/data.bin")));
                JSONObject revokePlan=cold.planProjectPackageImportForOwner(staged.getString("selectionId"),staged.getString("sha256"),"not imported",null).getJSONObject("plan");byte[] changed=out.toByteArray();changed[changed.length/2]^=1;Files.write(book.file(attachment.getString("id")).toPath(),changed);assertThrows(Exception.class,()->cold.importProjectPackageForOwner(revokePlan));
                Files.write(book.file(attachment.getString("id")).toPath(),out.toByteArray());book.remove(attachment.getString("id"));assertThrows(Exception.class,()->cold.importProjectPackageForOwner(revokePlan));assertFalse(cold.findProjectPackageImportForOwner(revokePlan).getBoolean("found"));
            }
        } finally {ProjectPackageArchiveTest.remove(source.toFile());}
    }
    @Test public void arbitraryZipAndWrongAttachmentDigestDoNotBecomeTrustedProjects() throws Exception {
        var android=RuntimeEnvironment.getApplication();var context=new FeatureContext(android,null,null,event->{});var workspace=new WorkspaceModule(context);var book=AttachmentRepository.get(android).book;
        try(LocalModule local=new LocalModule(context,workspace)) {
            var out=new ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(out)){zip.putNextEntry(new java.util.zip.ZipEntry("../../private"));zip.write(new byte[]{1});zip.closeEntry();}
            JSONObject attachment=book.importBytes(new ByteArrayInputStream(out.toByteArray()),"project.zip","application/zip",new JSONObject().put("kind","device_received_file"));
            assertThrows(Exception.class,()->local.stageDeviceProjectAttachmentForOwner(attachment.getString("id"),"b".repeat(64),new RunControl()));
            assertThrows(Exception.class,()->local.stageDeviceProjectAttachmentForOwner(attachment.getString("id"),attachment.getString("sha256"),new RunControl()));
            assertEquals(0,local.listStagedProjectPackagesForOwner().length());assertEquals(0,workspace.snapshot().getJSONArray("projects").length());
        }
    }
}
