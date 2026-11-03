package dev.pocketengine.features.attachments.creation;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import java.nio.file.*;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static dev.pocketengine.features.attachments.creation.StructuredOfficeCreationTest.*;

public final class OfficeNativeOperationTest {
    @Test public void nativeOperationPublishesActualOfficeRetainsEditableParentAndReplaysWithoutDuplicateArtifacts()throws Exception {
        Path root=Files.createTempDirectory("office-native-operation-");var book=new AttachmentBook(root.resolve("attachments").toFile());var control=new RunControl();var creator=new NativeDocumentCreation(book,root.resolve("cache").toFile(),(id,sha,c)->book.readVerifiedMetadata(id,sha,c));String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        var first=creator.create(new DocumentDraft(workbook(100)),operation,task,run,control);assertEquals("xlsx",first.getJSONObject("formatCheck").getString("format"));assertEquals(2,book.list().length());
        assertEquals(first.getString("id"),creator.create(new DocumentDraft(workbook(100)),operation,task,run,control).getString("id"));assertEquals(2,book.list().length());
        byte[] old=book.readVerifiedSnapshot(first.getString("id"),first.getString("sha256"),control);var edit=creator.readEditableSource(first.getString("id"),first.getString("sha256"),control);edit.getJSONObject("workbook").getJSONArray("sheets").getJSONObject(0).getJSONArray("cells").getJSONObject(0).put("number",120);
        var next=creator.create(new DocumentDraft(edit),UUID.randomUUID().toString(),task,run,control);assertNotEquals(first.getString("id"),next.getString("id"));assertEquals(first.getString("id"),next.getJSONObject("source").getJSONObject("parentArtifact").getString("id"));assertArrayEquals(old,book.readVerifiedSnapshot(first.getString("id"),first.getString("sha256"),control));assertEquals(4,book.list().length());
        var presentation=creator.create(new DocumentDraft(deck()),UUID.randomUUID().toString(),task,run,control);assertEquals("pptx",presentation.getJSONObject("formatCheck").getString("format"));assertTrue(presentation.getJSONObject("formatCheck").getBoolean("speakerNotesReadBack"));assertEquals(6,book.list().length());
    }
    @Test public void selectedPresentationRevisionKeepsOtherPagesParagraphBoundariesAndObjectGeometry()throws Exception {
        Path root=Files.createTempDirectory("pptx-owner-revision-");var book=new AttachmentBook(root.resolve("attachments").toFile());var control=new RunControl();var creator=new NativeDocumentCreation(book,root.resolve("cache").toFile(),(id,sha,c)->book.readVerifiedMetadata(id,sha,c));String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        JSONObject input=deck();input.getJSONObject("presentation").getJSONArray("slides").getJSONObject(1).put("paragraphs",new JSONArray().put("").put("同一段的第一行\n第二行").put("保留末段")).put("shapes",new JSONArray().put(new JSONObject().put("id","callout").put("text","原注释").put("x",1.25).put("y",5.5).put("width",3.5).put("height",0.75)));
        JSONObject parent=creator.create(new DocumentDraft(input),UUID.randomUUID().toString(),task,run,control),parentSource=parent.getJSONObject("editableSource");
        byte[] parentBytes=book.readVerifiedSnapshot(parent.getString("id"),parent.getString("sha256"),control),parentSourceBytes=book.readVerifiedSnapshot(parentSource.getString("id"),parentSource.getString("sha256"),control);
        JSONObject edit=creator.readEditableSource(parent.getString("id"),parent.getString("sha256"),control);String firstPage=edit.getJSONObject("presentation").getJSONArray("slides").getJSONObject(0).toString();
        JSONObject page=edit.getJSONObject("presentation").getJSONArray("slides").getJSONObject(1);page.put("title","新的结尾").put("notes","新版讲稿\n保留空行\n");page.getJSONArray("paragraphs").put(1,"仍是一段\n更新第二行");page.getJSONArray("shapes").getJSONObject(0).put("text","更新的 <注释> & 😀");
        assertFalse(edit.has("body"));assertFalse(edit.has("fontPoints"));String operation=UUID.randomUUID().toString();var draft=new DocumentDraft(edit);JSONObject saved=creator.create(draft,operation,task,run,control);byte[] bytes=book.readVerifiedSnapshot(saved.getString("id"),saved.getString("sha256"),control);
        assertEquals(parent.getString("id"),saved.getJSONObject("source").getJSONObject("parentArtifact").getString("id"));assertEquals(parent.getString("sha256"),saved.getJSONObject("source").getJSONObject("parentArtifact").getString("sha256"));assertNotEquals(parent.getString("id"),saved.getString("id"));assertFalse(Arrays.equals(parentBytes,bytes));
        JSONObject actual=creator.readEditableSource(saved.getString("id"),saved.getString("sha256"),control);JSONArray pages=actual.getJSONObject("presentation").getJSONArray("slides");assertEquals(2,pages.length());assertEquals(firstPage,pages.getJSONObject(0).toString());assertEquals("closing",pages.getJSONObject(1).getString("id"));assertEquals("",pages.getJSONObject(1).getJSONArray("paragraphs").getString(0));assertEquals("仍是一段\n更新第二行",pages.getJSONObject(1).getJSONArray("paragraphs").getString(1));assertEquals("保留末段",pages.getJSONObject(1).getJSONArray("paragraphs").getString(2));
        JSONObject shape=pages.getJSONObject(1).getJSONArray("shapes").getJSONObject(0);assertEquals("callout",shape.getString("id"));for(String key:List.of("x","y","width","height"))assertEquals(input.getJSONObject("presentation").getJSONArray("slides").getJSONObject(1).getJSONArray("shapes").getJSONObject(0).getDouble(key),shape.getDouble(key),0.0);
        assertEquals(List.of("新的结尾","","仍是一段\n更新第二行","保留末段","更新的 <注释> & 😀"),OfficePackage.drawingParagraphs(bytes,"ppt/slides/slide2.xml",control));assertEquals(List.of("新版讲稿","保留空行",""),OfficePackage.drawingParagraphs(bytes,"ppt/notesSlides/notesSlide2.xml",control));assertTrue(saved.getJSONObject("formatCheck").getBoolean("structuralReadComplete"));assertFalse(saved.getJSONObject("formatCheck").getBoolean("visualLayoutVerified"));
        assertArrayEquals(parentBytes,book.readVerifiedSnapshot(parent.getString("id"),parent.getString("sha256"),control));assertArrayEquals(parentSourceBytes,book.readVerifiedSnapshot(parentSource.getString("id"),parentSource.getString("sha256"),control));
        assertEquals(saved.getString("id"),creator.create(draft,operation,task,run,control).getString("id"));assertEquals(4,book.list().length());
        // A later output does not replace the explicitly selected older revision's source.
        assertEquals("结束",creator.readEditableSource(parent.getString("id"),parent.getString("sha256"),control).getJSONObject("presentation").getJSONArray("slides").getJSONObject(1).getString("title"));
    }
    @Test public void presentationPinsAndStopRejectBeforePublicationAndLateSourceWithdrawalKeepsOnlyPreparedSource()throws Exception {
        Path root=Files.createTempDirectory("pptx-owner-fences-");var book=new AttachmentBook(root.resolve("attachments").toFile());var control=new RunControl();var creator=new NativeDocumentCreation(book,root.resolve("cache").toFile(),(id,sha,c)->book.readVerifiedMetadata(id,sha,c));String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        JSONObject parent=creator.create(new DocumentDraft(deck()),UUID.randomUUID().toString(),task,run,control),edit=creator.readEditableSource(parent.getString("id"),parent.getString("sha256"),control);
        for(String key:List.of("parentSha256","editableSourceSha256")){JSONObject stale=new JSONObject(edit.toString()).put(key,"0".repeat(64));assertThrows(Exception.class,()->creator.create(new DocumentDraft(stale),UUID.randomUUID().toString(),task,run,control));assertEquals(2,book.list().length());}
        RunControl stopped=new RunControl();stopped.cancel();assertThrows(InterruptedException.class,()->creator.create(new DocumentDraft(edit),UUID.randomUUID().toString(),task,run,stopped));assertEquals(2,book.list().length());
        AtomicInteger sourceReads=new AtomicInteger();var withdrawing=new NativeDocumentCreation(book,root.resolve("cache").toFile(),(id,sha,c)->{if(sourceReads.incrementAndGet()==3)throw new SecurityException("fixture original source withdrawn before output publication");book.readVerifiedMetadata(id,sha,c);});String operation=UUID.randomUUID().toString();var draft=new DocumentDraft(edit);
        NativeDocumentCreation.NotPublished failure=assertThrows(NativeDocumentCreation.NotPublished.class,()->withdrawing.create(draft,operation,task,run,control));assertTrue(failure.receipt.getBoolean("outputNotPublished"));assertEquals(operation,failure.receipt.getString("operationId"));assertEquals(3,book.list().length());JSONObject retained=failure.receipt.getJSONObject("editableSource");assertTrue(book.readVerifiedSnapshot(retained.getString("id"),retained.getString("sha256"),control).length>0);assertFalse(book.findNativeResult(operation,"document_output",task,run,draft.identity,control).getBoolean("found"));
    }
}
