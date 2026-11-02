package dev.pocketengine.features.attachments.creation;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import java.nio.file.*;
import java.util.UUID;
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
}
