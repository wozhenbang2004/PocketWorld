package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.*;
import java.nio.file.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Original workspace heads -> original physical checkout -> real byte source digest/package. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ProjectWorkspaceBytesJourneyTest {
    @Test public void originalBinaryVersionsPrepareExactCheckoutAndPackageWithoutTextRoundTrip()throws Exception {
        var android=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(android,null,null,event->{});WorkspaceModule workspace=new WorkspaceModule(context);String project=workspace.handle("create",new JSONObject().put("name","Binary retained versions")).getString("id");
        byte[] image={(byte)0x89,80,78,71,13,10,0,(byte)0xff};var one=workspace.writeBytesForHost(workspace.fileSnapshotForHost(project,"assets/image.png",0),image);workspace.handle("write",new JSONObject().put("projectId",project).put("path","index.html").put("content","<img src=\"assets/image.png\">").put("expectedVersion",0));
        try(LocalModule local=new LocalModule(context,workspace)){
            JSONObject checkout=local.handle("prepare",new JSONObject().put("projectId",project));String id=checkout.getString("id");Path root=android.getFilesDir().toPath().resolve("local-development/checkouts/"+id+"/files");assertArrayEquals(image,Files.readAllBytes(root.resolve("assets/image.png")));JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",id)));assertEquals(2,source.getInt("fileCount"));
            JSONObject plan=local.handle("project.package.plan",new JSONObject().put("checkoutId",id).put("mode","source_project").put("includeGeneratedRoots",false));JSONObject saved=local.handle("project.package.freeze",new JSONObject().put("checkoutId",id).put("expectedSourceDigest",plan.getString("sourceDigest")).put("expectedManifestIdentity",plan.getString("manifestIdentity")).put("mode","source_project").put("entryPath","").put("includeGeneratedRoots",false).put("clientSnapshotId",java.util.UUID.randomUUID().toString()));
            assertArrayEquals(image,workspace.projectPackage(saved.getString("id"),saved.getString("sha256")).file("assets/image.png"));workspace.deleteForHost(one);assertArrayEquals(image,Files.readAllBytes(root.resolve("assets/image.png")));
        }
    }
}
