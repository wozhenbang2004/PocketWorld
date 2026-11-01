package dev.pocketengine.features.memory;

import dev.pocketengine.features.skills.ExperienceOrigin;
import dev.pocketengine.features.skills.SkillBook;
import java.io.File;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class MemoryExperienceBridgeTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private SourceRef.State current;
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private SourceRef source(long revision){return new SourceRef("message","message:one",revision,"owner","private");}
    private MemoryBook book()throws Exception{current=new SourceRef.State(source(1),false);return new MemoryBook(new File(temp.getRoot(),"memory.json"),(s,k,id)->current);}
    private JSONObject draft(String text)throws Exception{return new JSONObject().put("layer","procedural").put("text",text).put("reason","真实消息的候选").put("applicability","仅同类备份任务");}
    private JSONObject accepted(MemoryBook book,String text)throws Exception{JSONObject candidate=book.proposeLearning(scope(),draft(text),1,source(1));return book.acceptCandidate(scope(),candidate.getString("id"),1,2).getJSONObject("record");}
    private ExperienceOrigin origin(JSONObject row)throws Exception{return new ExperienceOrigin(new ExperienceOrigin.Ref(row.getString("id"),row.getLong("revision"),"owner","private"),ExperienceOrigin.DraftAuthor.MODEL);}
    private JSONObject skillDraft()throws Exception{return new JSONObject().put("manifest","---\nname: backup-check\ndescription: 备份任务核对\n---\n仅作参考，先核对目标再备份。");}
    private interface Checked {void run()throws Exception;}
    private static void reject(Checked action)throws Exception{try{action.run();fail("must fail closed");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}}

    @Test public void acceptedProcedureResolvesWithFreshEvidenceButNotPendingOrManualClaim()throws Exception{
        MemoryBook book=book();JSONObject candidate=book.proposeLearning(scope(),draft("备份前核对目录"),1,source(1));
        assertNull(book.resolveExperience("owner","private",candidate.getString("id")));
        JSONObject record=book.acceptCandidate(scope(),candidate.getString("id"),1,2).getJSONObject("record");
        ExperienceOrigin.State resolved=book.resolveExperience("owner","private",record.getString("id"));
        assertEquals(ExperienceOrigin.Status.ACTIVE,resolved.status);assertTrue(resolved.acceptedProcedure);
        JSONObject manual=book.save(scope(),new JSONObject().put("layer","procedural").put("text","未审程序").put("formation","reviewed_learning").put("learningCandidateId",candidate.getString("id")),3);
        assertFalse(book.resolveExperience("owner","private",manual.getString("id")).acceptedProcedure);
        assertNull(book.resolveExperience("other","private",record.getString("id")));
    }
    @Test public void sourceChangesBlockSkillsBeforeHostSynchronizerRuns()throws Exception{
        MemoryBook book=book();JSONObject record=accepted(book,"备份前核对目录");SkillBook skills=new SkillBook(new File(temp.getRoot(),"skills"),book::resolveExperience);
        JSONObject inspected=skills.inspectExperience(scope(),origin(record),skillDraft(),new JSONObject(),3);
        JSONObject imported=skills.importExperience(scope(),origin(record),skillDraft(),new JSONObject().put("inspectedDigest",inspected.getString("inspectedDigest")),4);
        skills.approveExperience(scope(),imported.getString("id"),1,imported.getString("digest"),5);
        assertEquals(1,skills.discoverForHost(scope(),"备份",java.util.Set.of(),5,4000,6).getJSONArray("skills").length());
        current=new SourceRef.State(source(2),false);
        assertEquals(ExperienceOrigin.Status.INACTIVE,book.resolveExperience("owner","private",record.getString("id")).status);
        assertEquals(0,skills.discoverForHost(scope(),"备份",java.util.Set.of(),5,4000,7).getJSONArray("skills").length());
        reject(()->book.reviewedProcedure(scope(),record.getString("id"),1));
        assertEquals(1,skills.reconcileExperiences(scope(),8).getInt("revoked"));
    }
    @Test public void unavailableOrWrongSourceIdentityNeverBecomesActiveOrDeletion()throws Exception{
        MemoryBook book=book();JSONObject record=accepted(book,"备份前核对目录");String id=record.getString("id");current=null;
        assertNull(book.resolveExperience("owner","private",id));
        current=new SourceRef.State(new SourceRef("message","other",2,"owner","private"),false);
        assertNull(book.resolveExperience("owner","private",id));
        current=new SourceRef.State(source(2),true);
        assertEquals(ExperienceOrigin.Status.DELETED,book.resolveExperience("owner","private",id).status);
    }
    @Test public void editedProcedureMustBeReviewedAgainAndOldRevisionCannotDraft()throws Exception{
        MemoryBook book=book();JSONObject record=accepted(book,"备份前核对目录");String id=record.getString("id");
        book.save(scope(),new JSONObject().put("id",id).put("revision",1).put("text","改为另一程序"),3);
        assertFalse(book.resolveExperience("owner","private",id).acceptedProcedure);
        reject(()->book.reviewedProcedure(scope(),id,1));reject(()->book.reviewedProcedure(scope(),id,2));
    }
    @Test public void pendingCandidateOnlySourcesAreIncludedForSynchronization()throws Exception{
        MemoryBook book=book();book.proposeLearning(scope(),draft("备份前核对目录"),1,source(1));
        assertEquals(0,book.list(scope()).length());JSONArray refs=book.sourceRefsForSynchronization(scope());
        assertEquals(1,refs.length());assertEquals(source(1),SourceRef.fromJson(refs.getJSONObject(0)));
        current=new SourceRef.State(source(2),true);book.sourceDeleted(scope(),source(2));
        assertEquals("invalidated",book.listCandidates(scope()).getJSONObject(0).getString("status"));
    }
    @Test public void forgetReceiptCapturesSiblingAndTransitiveRecordsBeforeAtomicRemoval()throws Exception{
        MemoryBook book=book();JSONObject first=accepted(book,"备份前核对目录"),second=accepted(book,"备份后核对结果");
        JSONObject summary=book.save(scope(),new JSONObject().put("text","派生总结").put("sourceIds",new JSONArray().put(second.getString("id"))),3);
        final JSONArray[] stored={null};JSONObject result=book.forgetReviewed(scope(),first.getString("id"),1,refs->{
            assertEquals(3,refs.length());assertEquals(3,book.list(scope()).length());stored[0]=new JSONArray(refs.toString());
        });
        assertEquals(3,result.getInt("deleted"));assertEquals(3,stored[0].length());assertEquals(0,book.list(scope()).length());
        assertTrue(stored[0].toString().contains(summary.getString("id")));assertFalse(stored[0].toString().contains("备份"));
    }
    @Test public void failedJournalReceiptLeavesMemoryAndForgetBarriersUnchanged()throws Exception{
        MemoryBook book=book();JSONObject record=accepted(book,"备份前核对目录");String before=new String(java.nio.file.Files.readAllBytes(new File(temp.getRoot(),"memory.json").toPath()),java.nio.charset.StandardCharsets.UTF_8);
        try{book.forgetReviewed(scope(),record.getString("id"),1,refs->{throw new IOException("journal unavailable");});fail();}catch(IOException expected){}
        assertEquals(1,book.list(scope()).length());assertEquals(ExperienceOrigin.Status.ACTIVE,book.resolveExperience("owner","private",record.getString("id")).status);
        assertEquals(before,new String(java.nio.file.Files.readAllBytes(new File(temp.getRoot(),"memory.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void committedForgetCanReplayItsExistingReceiptAfterMemoryRestart()throws Exception{
        MemoryBook book=book();JSONObject record=accepted(book,"备份前核对目录");SkillBook skills=new SkillBook(new File(temp.getRoot(),"skills"),book::resolveExperience);
        JSONObject inspected=skills.inspectExperience(scope(),origin(record),skillDraft(),new JSONObject(),3);
        JSONObject row=skills.importExperience(scope(),origin(record),skillDraft(),new JSONObject().put("inspectedDigest",inspected.getString("inspectedDigest")),4);
        final JSONArray[] receipt={null};book.forgetReviewed(scope(),record.getString("id"),1,refs->receipt[0]=refs);
        MemoryBook restored=new MemoryBook(new File(temp.getRoot(),"memory.json"),(s,k,id)->current);
        assertNull(restored.resolveExperience("owner","private",record.getString("id")));
        SkillBook restarted=new SkillBook(new File(temp.getRoot(),"skills"),restored::resolveExperience);
        JSONObject ref=receipt[0].getJSONObject(0);ExperienceOrigin.Ref removed=new ExperienceOrigin.Ref(ref.getString("recordId"),ref.getLong("revision"),ref.getString("ownerId"),ref.getString("spaceId"));
        assertEquals(1,restarted.removeExperienceSource(scope(),removed).getInt("removed"));
        assertEquals(0,restarted.removeExperienceSource(scope(),removed).getInt("removed"));
        assertEquals(0,restarted.list(scope(),5).length());assertFalse(new File(temp.getRoot(),"skills/"+row.getString("id")).exists());
    }
    @Test public void sourceDeletionReceiptsAreDurableBeforeDerivedRecordsDisappear()throws Exception{
        MemoryBook book=book();JSONObject record=accepted(book,"备份前核对目录");current=new SourceRef.State(source(2),true);
        try{book.sourceDeleted(scope(),source(2),refs->{throw new IOException("journal unavailable");});fail();}catch(IOException expected){}
        assertEquals(1,book.list(scope()).length());
        final JSONArray[] receipt={null};JSONObject result=book.sourceDeleted(scope(),source(2),refs->{assertEquals(1,book.list(scope()).length());receipt[0]=refs;});
        assertEquals(1,result.getInt("invalidated"));assertEquals(record.getString("id"),receipt[0].getJSONObject(0).getString("recordId"));
        assertEquals(source(1),SourceRef.fromJson(receipt[0].getJSONObject(0).getJSONArray("sourceRefs").getJSONObject(0)));
        assertEquals(0,book.list(scope()).length());
    }

}
