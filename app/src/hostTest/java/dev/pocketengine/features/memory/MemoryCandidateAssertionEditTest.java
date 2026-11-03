package dev.pocketengine.features.memory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real Book lifecycle with a deterministic native proof-port fixture, not a provider or device proof. */
public final class MemoryCandidateAssertionEditTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private static final String ORIGINAL="我住在A城。",EDITED="这是同事的新B城情况，不是主人偏好。";
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Host implements SourceRef.AssertionResolver {
        final SourceRef source=new SourceRef("message","original-user-message",1,"owner","private");boolean deleted;
        public SourceRef.State resolve(MemoryBook.Scope scope,String kind,String id){return new SourceRef.State(source,deleted);}
        public void requireUserSaid(JSONObject assertion,String text)throws Exception {
            if(deleted||!ORIGINAL.equals(text)||!ORIGINAL.equals(assertion.getString("object")))throw new SecurityException("not the original literal user statement");
        }
    }
    private static JSONObject draft(Host host,String kind)throws Exception {
        JSONObject assertion=new JSONObject().put("version",1).put("kind",kind).put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人").put("predicate","居住城市").put("object",ORIGINAL).put("eventTime",100).put("validFrom",100).put("evidenceSpans",new JSONArray());
        if("user_said".equals(kind))assertion.getJSONArray("evidenceSpans").put(new JSONObject().put("sourceRef",host.source.toJson()).put("offset",0).put("chars",ORIGINAL.length()).put("sha256",dev.pocketengine.features.local.project.ProjectSources.sha256(ORIGINAL.getBytes(StandardCharsets.UTF_8))));
        return new JSONObject().put("text",ORIGINAL).put("layer","fact").put("reason","来自原来源，仍需主人审阅").put("applicability","").put("confidence",0.5).put("assertion",assertion);
    }
    @Test public void bodyOnlyEditsWithdrawOldTypedMeaningInsteadOfKeepingTheWrongOwnerFact()throws Exception {
        for(String kind:new String[]{"told","inferred","imagined"}){
            Host host=new Host();MemoryBook book=new MemoryBook(new File(temporary.getRoot(),kind+".json"),host);JSONObject original=book.proposeLearning(scope(),draft(host,kind),200,host.source);
            JSONObject edited=book.editCandidate(scope(),original.getString("id"),1,new JSONObject().put("text",EDITED),300);
            assertFalse("Text edits must not retain an unreviewed old subject/object: "+kind,edited.has("assertion"));assertEquals(original.getJSONArray("sourceRefs").toString(),edited.getJSONArray("sourceRefs").toString());assertEquals(original.getString("dedupKey"),edited.getString("dedupKey"));assertEquals(2,edited.getLong("revision"));
            JSONObject record=book.acceptCandidate(scope(),edited.getString("id"),2,400).getJSONObject("record");assertEquals(EDITED,record.getString("text"));assertFalse(record.has("assertion"));assertEquals(0,book.cognitionProjection(scope(),"owner",500).getJSONArray("current").length());
        }
    }
    @Test public void editingALiteralQuoteIsAnUntypedReviewDraftNotAFalseOriginalQuoteOrAnUneditableTrap()throws Exception {
        Host host=new Host();MemoryBook book=new MemoryBook(new File(temporary.getRoot(),"quote.json"),host);JSONObject candidate=book.proposeLearning(scope(),draft(host,"user_said"),200,host.source);
        JSONObject edited=book.editCandidate(scope(),candidate.getString("id"),1,new JSONObject().put("text",EDITED),300);assertEquals(EDITED,edited.getString("text"));assertFalse(edited.has("assertion"));assertEquals(host.source,SourceRef.fromJson(edited.getJSONArray("sourceRefs").getJSONObject(0)));assertEquals("unverified",edited.getString("validationStatus"));
    }
    @Test public void unchangedBodyAndAncillaryReviewEditsKeepTheExactTypedEvidence()throws Exception {
        Host host=new Host();MemoryBook book=new MemoryBook(new File(temporary.getRoot(),"ancillary.json"),host);JSONObject candidate=book.proposeLearning(scope(),draft(host,"user_said"),200,host.source);
        JSONObject edited=book.editCandidate(scope(),candidate.getString("id"),1,new JSONObject().put("text","  "+ORIGINAL+"  ").put("reason","主人补充审核理由").put("confidence",0.6),300);assertEquals(candidate.getJSONObject("assertion").toString(),edited.getJSONObject("assertion").toString());assertEquals(ORIGINAL,book.acceptCandidate(scope(),edited.getString("id"),2,400).getJSONObject("record").getJSONObject("assertion").getString("object"));
    }
    @Test public void coldRetryOfTheOldModelDraftCannotRestoreWithdrawnStructureOrOverwriteOwnerEdits()throws Exception {
        Host host=new Host();File file=new File(temporary.getRoot(),"cold.json");MemoryBook book=new MemoryBook(file,host);JSONObject input=draft(host,"inferred"),candidate=book.proposeLearning(scope(),input,200,host.source);book.editCandidate(scope(),candidate.getString("id"),1,new JSONObject().put("text",EDITED),300);
        MemoryBook cold=new MemoryBook(file,host);JSONObject retry=cold.proposeLearning(scope(),input,400,host.source);assertEquals(candidate.getString("id"),retry.getString("id"));assertEquals(EDITED,retry.getString("text"));assertFalse(retry.has("assertion"));assertEquals(2,retry.getLong("revision"));assertFalse(cold.acceptCandidate(scope(),retry.getString("id"),2,500).getJSONObject("record").has("assertion"));
    }
    @Test public void anExplicitNewTypedDraftStillNeedsOriginalValidationAndCannotChangeHostSources()throws Exception {
        Host host=new Host();File file=new File(temporary.getRoot(),"explicit.json");MemoryBook book=new MemoryBook(file,host);JSONObject candidate=book.proposeLearning(scope(),draft(host,"inferred"),200,host.source),typed=draft(host,"inferred").getJSONObject("assertion").put("object","明确核对的新B城");
        JSONObject edited=book.editCandidate(scope(),candidate.getString("id"),1,new JSONObject().put("text","新B城").put("assertion",typed),300);assertEquals("明确核对的新B城",edited.getJSONObject("assertion").getString("object"));assertEquals(candidate.getJSONArray("sourceRefs").toString(),edited.getJSONArray("sourceRefs").toString());
        byte[] before=Files.readAllBytes(file.toPath());assertThrows(IllegalStateException.class,()->book.editCandidate(scope(),candidate.getString("id"),1,new JSONObject().put("text","旧CAS"),400));host.deleted=true;assertThrows(Exception.class,()->book.editCandidate(scope(),candidate.getString("id"),2,new JSONObject().put("text","已撤回源"),400));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }
}
