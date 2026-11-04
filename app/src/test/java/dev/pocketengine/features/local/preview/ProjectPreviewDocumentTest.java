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
}
