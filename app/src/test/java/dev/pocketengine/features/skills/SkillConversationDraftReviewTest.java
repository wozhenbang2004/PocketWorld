package dev.pocketengine.features.skills;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real package files, original atomic index and retained versions. No replacement store. */
public final class SkillConversationDraftReviewTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static String manifest(String version){return "---\nname: reviewed-notes\ndescription: 核对实际材料后整理说明\nmetadata:\n  version: '"+version+"'\n---\n只依据真实材料，不虚构完成。\n";}
    private static JSONObject draft(String version)throws Exception{return new JSONObject().put("manifest",manifest(version)).put("resources",new JSONObject().put("references/check.md","原文件保留完整文字🙂\r\n"));}
    private static JSONObject origin()throws Exception{return new JSONObject().put("taskId",UUID.randomUUID().toString()).put("runId",UUID.randomUUID().toString()).put("epoch",1).put("operationId",UUID.randomUUID().toString()).put("proposalDigest","a".repeat(64));}
    private static JSONObject pin(JSONObject row)throws Exception{return new JSONObject().put("id",row.getString("id")).put("revision",row.getInt("revision")).put("digest",row.getString("digest"));}
    private static JSONObject install(SkillBook book)throws Exception{return book.install(scope(),draft("1").put("source","local:synthetic-owner-fixture").put("requiredCapabilities",new JSONArray().put("phone_observe")).put("expiresAt",50000),1000);}

    @Test public void inspectAndReadDoNotInstallThenExactAdoptionCreatesOneDisabledPackage()throws Exception {
        SkillBook book=new SkillBook(new File(temp.getRoot(),"book"));JSONObject from=origin();SkillBook.SourceReview review=book.inspectConversationDraft(scope(),draft("1"),null,from,1000);JSONObject view=review.view();
        assertEquals(0,book.list(scope(),1000).length());assertEquals(0,view.getJSONArray("beforeFiles").length());assertEquals(2,view.getJSONArray("afterFiles").length());assertTrue(view.getBoolean("savedAsIndependentSkill"));assertFalse(view.getBoolean("formalSkillSaved"));
        JSONObject text=book.readConversationDraftFile(scope(),review,"after","references/check.md",0,12000,1001);assertEquals("原文件保留完整文字🙂\r\n",text.getString("content"));assertFalse(text.getBoolean("hasMore"));assertEquals(0,book.list(scope(),1001).length());
        assertThrows(SecurityException.class,()->book.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,true,1002));JSONObject saved=book.applyConversationDraft(scope(),review,1002);assertFalse(saved.getBoolean("enabled"));assertFalse(saved.getBoolean("active"));assertEquals("",saved.getString("approvedDigest"));assertEquals(1,saved.getInt("revision"));
        assertThrows(SkillBook.SourceUnavailable.class,()->book.applyConversationDraft(scope(),review,1003));assertEquals(1,book.list(scope(),1003).length());JSONObject readback=new SkillBook(new File(temp.getRoot(),"book")).findConversationDraftResult(scope(),from,saved.getString("digest"),1004);assertTrue(readback.getBoolean("found"));assertEquals(saved.getString("id"),readback.getJSONObject("skill").getString("id"));
    }
    @Test public void updateShowsAllBeforeAfterChangesAndPreservesHostRestrictions()throws Exception {
        SkillBook book=new SkillBook(new File(temp.getRoot(),"book"));JSONObject old=install(book);book.setEnabled(scope(),old.getString("id"),true,old.getString("digest"),true,1001);JSONObject proposed=draft("2");proposed.getJSONObject("resources").remove("references/check.md");proposed.getJSONObject("resources").put("references/new.md","明确的新资源");
        SkillBook.SourceReview review=book.inspectConversationDraft(scope(),proposed,pin(old),origin(),1002);JSONObject view=review.view(),comparison=view.getJSONObject("comparison");assertEquals("references/check.md",comparison.getJSONArray("removed").getString(0));assertEquals("references/new.md",comparison.getJSONArray("added").getString(0));assertEquals("SKILL.md",comparison.getJSONArray("modified").getString(0));
        assertEquals("原文件保留完整文字🙂\r\n",book.readConversationDraftFile(scope(),review,"before","references/check.md",0,12000,1003).getString("content"));assertEquals(2,view.getJSONArray("beforeFiles").length());assertEquals(2,view.getJSONArray("afterFiles").length());JSONObject updated=book.applyConversationDraft(scope(),review,1004);
        assertEquals(old.getString("id"),updated.getString("id"));assertEquals(2,updated.getInt("revision"));assertFalse(updated.getBoolean("enabled"));assertEquals("phone_observe",updated.getJSONArray("requiredCapabilities").getString(0));assertEquals(50000,updated.getLong("expiresAt"));assertEquals("USER",updated.getJSONObject("sourcePolicy").getString("layer"));assertTrue(book.retainedRevisions(scope(),old.getString("id"),0,20,1005).getJSONArray("revisions").length()>=2);
    }
    @Test public void enabledStateAbaAndOutOfBandBytesRejectTheOldReview()throws Exception {
        File directory=new File(temp.getRoot(),"book");SkillBook book=new SkillBook(directory);JSONObject old=install(book);SkillBook.SourceReview stale=book.inspectConversationDraft(scope(),draft("2"),pin(old),origin(),1001);
        book.setEnabled(scope(),old.getString("id"),true,old.getString("digest"),true,1002);book.setEnabled(scope(),old.getString("id"),false,"",false,1003);assertThrows(SkillBook.SourceUnavailable.class,()->book.applyConversationDraft(scope(),stale,1004));assertEquals(1,book.get(scope(),old.getString("id"),1004).getInt("revision"));
        SkillBook.SourceReview current=book.inspectConversationDraft(scope(),draft("2"),pin(old),origin(),1005);Files.writeString(new File(directory,old.getString("id")+"/1/reviewed-notes/references/check.md").toPath(),"外部新改动");assertThrows(SkillBook.SourceUnavailable.class,()->book.applyConversationDraft(scope(),current,1006));assertThrows(SkillBook.SourceUnavailable.class,()->book.readConversationDraftFile(scope(),current,"before","SKILL.md",0,100,1006));
    }
    @Test public void bundledAndWrongProjectTargetsAreNotConversationOverwriteCandidates()throws Exception {
        SkillBook book=new SkillBook(new File(temp.getRoot(),"book"));JSONObject old=install(book);book.configureSourceForOwner(scope(),old.getString("id"),1,old.getString("digest"),0,SkillSourcePolicy.Layer.BUNDLED,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,1001);
        assertThrows(SecurityException.class,()->book.inspectConversationDraft(scope(),draft("2"),pin(old),origin(),1002));assertEquals(1,book.get(scope(),old.getString("id"),1003).getInt("revision"));
        JSONObject project=new JSONObject(scope().toString()).put("projectId",UUID.randomUUID().toString()).put("checkoutId",UUID.randomUUID().toString()).put("projectRevision","b".repeat(64));SkillBook.SourceReview created=book.inspectConversationDraft(project,draft("1"),null,origin(),1004);JSONObject saved=book.applyConversationDraft(project,created,1005);assertEquals("PROJECT",saved.getJSONObject("sourcePolicy").getString("layer"));assertThrows(SecurityException.class,()->book.inspectConversationDraft(scope(),draft("2"),pin(saved),origin(),1006));
    }
    @Test public void sourceLabelsAndGenericJsonCannotForgeAnOriginalSaveReceipt()throws Exception {
        SkillBook book=new SkillBook(new File(temp.getRoot(),"book"));JSONObject from=origin(),ordinary=draft("1").put("source","conversation-draft:"+from.getString("taskId")+":"+from.getString("runId")+":"+from.getString("operationId")).put("sourceVersion",from.getString("proposalDigest"));JSONObject copy=book.install(scope(),ordinary,1000);
        assertFalse(book.findConversationDraftResult(scope(),from,copy.getString("digest"),1001).getBoolean("found"));assertThrows(SecurityException.class,()->book.install(scope(),new JSONObject(ordinary.toString()).put("conversationDraftReceipt",from),1002));
        SkillBook.SourceReview exact=book.inspectConversationDraft(scope(),draft("1"),null,from,1003);JSONObject saved=book.applyConversationDraft(scope(),exact,1004);assertTrue(book.findConversationDraftResult(scope(),from,saved.getString("digest"),1005).getBoolean("found"));JSONObject wrong=new JSONObject(from.toString()).put("operationId",UUID.randomUUID().toString());assertFalse(book.findConversationDraftResult(scope(),wrong,saved.getString("digest"),1005).getBoolean("found"));
    }
    @Test public void failedVersionWriteDoesNotOverwriteOldFilesOrAllowReuseOfConsumedReview()throws Exception {
        File directory=new File(temp.getRoot(),"book");SkillBook book=new SkillBook(directory);JSONObject old=install(book),from=origin();SkillBook.SourceReview review=book.inspectConversationDraft(scope(),draft("2"),pin(old),from,1001);Files.createDirectories(new File(directory,old.getString("id")+"/2/reviewed-notes").toPath());
        assertThrows(Exception.class,()->book.applyConversationDraft(scope(),review,1002));assertThrows(SkillBook.SourceUnavailable.class,()->book.applyConversationDraft(scope(),review,1003));assertEquals(old.getString("digest"),book.get(scope(),old.getString("id"),1004).getString("digest"));assertEquals("原文件保留完整文字🙂\r\n",book.read(scope(),old.getString("id"),"references/check.md",0,12000,1004).getString("content"));assertFalse(book.findConversationDraftResult(scope(),from,review.view().getJSONObject("package").getString("digest"),1004).getBoolean("found"));
    }
    @Test public void missingFilesTraversalAndExpiredReviewNeverImport()throws Exception {
        SkillBook book=new SkillBook(new File(temp.getRoot(),"book"));JSONObject escaped=draft("1");escaped.getJSONObject("resources").put("../escaped.md","not allowed");assertThrows(Exception.class,()->book.inspectConversationDraft(scope(),escaped,null,origin(),1000));
        JSONObject directoryConflict=draft("1");directoryConflict.getJSONObject("resources").put("references","not a directory");assertThrows(Exception.class,()->book.inspectConversationDraft(scope(),directoryConflict,null,origin(),1000));SkillBook.SourceReview review=book.inspectConversationDraft(scope(),draft("1"),null,origin(),1000);assertThrows(SkillBook.SourceUnavailable.class,()->book.applyConversationDraft(scope(),review,601001));assertEquals(0,book.list(scope(),601002).length());
    }
}
