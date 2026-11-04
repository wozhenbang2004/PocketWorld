package dev.pocketengine.features.local.preview;

import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual immutable bundle fixture. Android dependency closure is required; not a substitute bundle. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class ProjectPreviewDocumentTest {
    static ProjectPreviewBundle.Verified bundle()throws Exception {
        var root=Files.createTempDirectory("native-preview-document");Files.createDirectories(root.resolve("dist"));Files.writeString(root.resolve("dist/index.html"),"<button>original</button>");Files.writeString(root.resolve("dist/other.html"),"<p>other</p>");
        return ProjectPreviewBundle.capture(root.toFile(),"113fa15d-4d97-4a8b-b812-9f56f8331943","264a8d7c-31e6-44cf-aa4e-60944c4f2e5c","a".repeat(64),"dist/index.html");
    }
    static ProjectPreviewDocument.Selection select(ProjectPreviewDocument document)throws Exception {return document.select(document.generation(),document.beginSelection(),document.bundle().entryUrl,PreviewElementRefTest.observation());}
    /** Actual archive writer/decoder and native immutable bundle. This supplies no SQL task authority. */
    static ProjectPreviewBundle.Verified fixedBundle(String artifact)throws Exception {
        var root=Files.createTempDirectory("native-fixed-preview-document");Files.createDirectories(root.resolve("dist"));Files.writeString(root.resolve("dist/index.html"),"<button>fixed original</button>");
        JSONObject source=new JSONObject().put("projectId","264a8d7c-31e6-44cf-aa4e-60944c4f2e5c").put("checkoutId","113fa15d-4d97-4a8b-b812-9f56f8331943").put("sourceDigest","a".repeat(64));
        var frozen=dev.pocketengine.features.local.project.ProjectPackageArchive.capture(root.toFile(),source,"web_output","dist/index.html",true);
        var bytes=new java.io.ByteArrayOutputStream();dev.pocketengine.features.local.project.ProjectPackageArchive.write(frozen,bytes);var archive=Files.createTempFile("native-fixed-preview-document",".zip");Files.write(archive,bytes.toByteArray());
        var verified=dev.pocketengine.features.local.project.ProjectPackageArchive.read(archive.toFile());String sha=dev.pocketengine.features.local.project.ProjectSources.sha256(bytes.toByteArray());
        return ProjectPreviewBundle.fromProjectPackage(verified,artifact,sha,"dist/index.html");
    }
    @Test public void originalReadyDocumentSuppliesIdentityPathAndResourceHash()throws Exception {
        var bundle=bundle();var document=new ProjectPreviewDocument(bundle);assertFalse(document.ready());PreviewElementRefTest.rejected(document::beginSelection);
        document.loading();document.loaded(bundle.entryUrl);var selection=select(document);selection.requireCurrent();var snapshot=selection.snapshot();
        assertSame(bundle,selection.bundle());assertEquals(document.id(),snapshot.getString("documentId"));assertEquals(document.generation(),snapshot.getLong("documentGeneration"));assertEquals("dist/index.html",snapshot.getString("path"));assertEquals(bundle.resource(bundle.entryUrl).sha256,snapshot.getString("resourceSha256"));
    }
    @Test public void hidingDisablesNewCaptureButKeepsAlreadyCapturedSelection()throws Exception {
        var document=new ProjectPreviewDocument(bundle());document.loaded(document.bundle().entryUrl);long ticket=document.beginSelection(),generation=document.generation();
        var selected=document.select(generation,ticket,document.bundle().entryUrl,PreviewElementRefTest.observation());document.endSelection();selected.requireCurrent();
        PreviewElementRefTest.rejected(()->document.select(generation,ticket,document.bundle().entryUrl,PreviewElementRefTest.observation()));
        document.beginSelection();PreviewElementRefTest.rejected(()->document.select(generation,ticket,document.bundle().entryUrl,PreviewElementRefTest.observation()));
    }
    @Test public void sameUrlReloadInvalidatesOldSelectionAndInflightRead()throws Exception {
        var document=new ProjectPreviewDocument(bundle());document.loaded(document.bundle().entryUrl);var selected=select(document);long before=document.generation();document.loading();
        assertFalse(document.ready());PreviewElementRefTest.rejected(selected::requireCurrent);document.loaded(document.bundle().entryUrl);assertTrue(document.generation()>before);PreviewElementRefTest.rejected(selected::requireCurrent);select(document).requireCurrent();
    }
    @Test public void anotherHtmlResourceHasItsOwnCurrentDocumentPath()throws Exception {
        var document=new ProjectPreviewDocument(bundle());document.loaded(document.bundle().entryUrl);var before=select(document);String other=document.bundle().origin+"other.html";document.loaded(other);
        PreviewElementRefTest.rejected(before::requireCurrent);var next=document.select(document.generation(),document.beginSelection(),other,PreviewElementRefTest.observation());assertEquals("dist/other.html",next.snapshot().getString("path"));
    }
    @Test public void rejectedForeignLoadNeverLeavesOldDocumentReady()throws Exception {
        var document=new ProjectPreviewDocument(bundle());document.loaded(document.bundle().entryUrl);var before=select(document);
        PreviewElementRefTest.rejected(()->document.loaded("https://foreign.invalid/index.html"));assertFalse(document.ready());PreviewElementRefTest.rejected(before::requireCurrent);
    }
    @Test public void releaseNeverRevivesFromLateCallbacksOrPersistedJson()throws Exception {
        var document=new ProjectPreviewDocument(bundle());document.loaded(document.bundle().entryUrl);var before=select(document);JSONObject persisted=before.snapshot();document.release();document.loading();
        PreviewElementRefTest.rejected(()->document.loaded(document.bundle().entryUrl));PreviewElementRefTest.rejected(before::requireCurrent);assertFalse(document.ready());
        var replacement=new ProjectPreviewDocument(document.bundle());replacement.loaded(replacement.bundle().entryUrl);assertNotEquals(persisted.getString("documentId"),replacement.id());
    }
    @Test public void pageObservationCannotChooseNativeDocumentFields()throws Exception {
        var document=new ProjectPreviewDocument(bundle());document.loaded(document.bundle().entryUrl);long ticket=document.beginSelection();
        PreviewElementRefTest.rejected(()->document.select(document.generation(),ticket,document.bundle().entryUrl,PreviewElementRefTest.observation().put("sourceDigest","b".repeat(64))));
        PreviewElementRefTest.rejected(()->document.select(document.generation()+1,ticket,document.bundle().entryUrl,PreviewElementRefTest.observation()));
    }
    @Test public void snapshotMutationDoesNotChangeOriginalNativeCapture()throws Exception {
        var document=new ProjectPreviewDocument(bundle());document.loaded(document.bundle().entryUrl);var selection=select(document);var snapshot=selection.snapshot();snapshot.put("path","different.html");snapshot.getJSONObject("bounds").put("width",999);assertEquals("dist/index.html",selection.snapshot().getString("path"));assertEquals(40d,selection.snapshot().getJSONObject("bounds").getDouble("width"),0);selection.requireCurrent();
    }
    @Test public void fixedPackageSelectionUsesActualDecodedResourceAndNativeBundle()throws Exception {
        var bundle=fixedBundle("6948143a-a5bf-48f8-9f76-e290c7ec0265");var document=new ProjectPreviewDocument(bundle);document.loaded(bundle.entryUrl);var selection=select(document);var snapshot=selection.snapshot();
        assertSame(bundle,selection.bundle());assertEquals("6948143a-a5bf-48f8-9f76-e290c7ec0265",selection.bundle().artifactId);assertTrue(selection.bundle().artifactSha256.matches("[a-f0-9]{64}"));
        assertEquals("<button>fixed original</button>",new String(bundle.resource(bundle.entryUrl).bytes(),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(bundle.resource(bundle.entryUrl).sha256,snapshot.getString("resourceSha256"));assertEquals("dist/index.html",snapshot.getString("path"));assertEquals(9,snapshot.length());selection.requireCurrent();
    }
    @Test public void fixedPackageModeOffReloadAndReleaseKeepTheOriginalDocumentRules()throws Exception {
        var document=new ProjectPreviewDocument(fixedBundle("6948143a-a5bf-48f8-9f76-e290c7ec0265"));document.loaded(document.bundle().entryUrl);var first=select(document);JSONObject persisted=first.snapshot();
        document.endSelection();first.requireCurrent();document.loading();PreviewElementRefTest.rejected(first::requireCurrent);document.loaded(document.bundle().entryUrl);
        var second=select(document);assertTrue(second.snapshot().getLong("documentGeneration")>persisted.getLong("documentGeneration"));document.release();PreviewElementRefTest.rejected(second::requireCurrent);
        var replacement=new ProjectPreviewDocument(document.bundle());replacement.loaded(replacement.bundle().entryUrl);assertNotEquals(persisted.getString("documentId"),replacement.id());PreviewElementRefTest.rejected(first::requireCurrent);
    }
    @Test public void fixedPackageObservationCannotChooseArtifactOperationOrAnotherDocument()throws Exception {
        var first=fixedBundle("6948143a-a5bf-48f8-9f76-e290c7ec0265");var other=fixedBundle("b78a9aa2-1742-4796-8dcb-0edc7494733d");var document=new ProjectPreviewDocument(first);document.loaded(first.entryUrl);long ticket=document.beginSelection();
        assertNotEquals(first.identity,other.identity);assertEquals(first.resource(first.entryUrl).sha256,other.resource(other.entryUrl).sha256);
        PreviewElementRefTest.rejected(()->document.select(document.generation(),ticket,other.entryUrl,PreviewElementRefTest.observation()));
        for(String key:new String[]{"sourceKind","artifactId","artifactSha256","packageOperationId"})PreviewElementRefTest.rejected(()->document.select(document.generation(),ticket,first.entryUrl,PreviewElementRefTest.observation().put(key,"page-chosen")));
    }
}
