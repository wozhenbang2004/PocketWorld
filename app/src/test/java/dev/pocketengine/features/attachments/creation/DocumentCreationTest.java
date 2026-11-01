package dev.pocketengine.features.attachments.creation;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.features.attachments.documents.*;
import java.nio.file.*;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

public final class DocumentCreationTest {
    @Test public void actualEditableWordPackageContainsUnicodeTablesAndNoMacroOrExternalPart()throws Exception{
        var draft=new DocumentDraft(new JSONObject().put("format","docx").put("name","报价").put("title","项目报价").put("body","# 金额\n名称\t金额\n真实项目\t40.00\n<保留 & 字符> 😀"));byte[] bytes=WordDocumentWriter.write(draft,new RunControl());assertEquals('P',bytes[0]);assertEquals('K',bytes[1]);var parsed=new DocumentReader(null,null).read(bytes,new DocumentContent.Request(1,0,32,65536,256),new RunControl());assertEquals("docx",parsed.format);String actual=parsed.chunks.stream().map(c->c.text).reduce("",(a,b)->a+b);assertTrue(actual.contains("项目报价"));assertTrue(actual.contains("40.00"));assertTrue(actual.contains("<保留 & 字符> 😀"));assertTrue(WordDocumentWriter.check(bytes,draft,new RunControl()).getBoolean("structuralReadComplete"));
    }
    @Test public void originalEditableSourceAndExactParentProduceIndependentVersionAndOldBytesRemain()throws Exception{
        Path root=Files.createTempDirectory("c2-document-versions-");AttachmentBook book=new AttachmentBook(root.resolve("attachments").toFile());var control=new RunControl();var creator=new NativeDocumentCreation(book,root.resolve("cache").toFile(),(id,sha,c)->book.readVerifiedMetadata(id,sha,c));String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();var first=new DocumentDraft(new JSONObject().put("format","docx").put("title","金额").put("body","40.00"));var one=creator.create(first,UUID.randomUUID().toString(),task,run,control);byte[] old=book.readVerifiedSnapshot(one.getString("id"),one.getString("sha256"),control);JSONObject edit=creator.readEditableSource(one.getString("id"),one.getString("sha256"),control).put("body","45.00");var second=new DocumentDraft(edit);String operation=UUID.randomUUID().toString();var two=creator.create(second,operation,task,run,control);assertNotEquals(one.getString("id"),two.getString("id"));assertEquals(one.getString("id"),two.getJSONObject("source").getJSONObject("parentArtifact").getString("id"));assertArrayEquals(old,book.readVerifiedSnapshot(one.getString("id"),one.getString("sha256"),control));assertEquals(two.getString("id"),creator.create(second,operation,task,run,control).getString("id"));assertEquals(4,book.list().length());
    }
    @Test public void incompleteTextOversizeAndForgedParentSourceFailBeforePublication()throws Exception{
        assertThrows(Exception.class,()->new DocumentDraft(new JSONObject().put("format","docx").put("body","\ud800")));assertThrows(Exception.class,()->new DocumentDraft(new JSONObject().put("format","xlsx").put("body","not a spreadsheet")));assertThrows(Exception.class,()->new DocumentDraft(new JSONObject().put("format","pdf").put("body","x".repeat(16001))));
        Path root=Files.createTempDirectory("c2-document-forged-");var book=new AttachmentBook(root.toFile());var creator=new NativeDocumentCreation(book,root.toFile(),(id,sha,c)->book.readVerifiedMetadata(id,sha,c));var draft=new DocumentDraft(new JSONObject().put("format","docx").put("body","private").put("parentArtifactId",UUID.randomUUID().toString()).put("parentSha256","a".repeat(64)).put("editableSourceId",UUID.randomUUID().toString()).put("editableSourceSha256","b".repeat(64)));assertThrows(Exception.class,()->creator.create(draft,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),new RunControl()));assertEquals(0,book.list().length());
    }
}
