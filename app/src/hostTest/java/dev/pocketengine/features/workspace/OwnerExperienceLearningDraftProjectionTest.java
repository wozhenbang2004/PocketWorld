package dev.pocketengine.features.workspace;

import dev.pocketengine.features.memory.MemoryBook;
import dev.pocketengine.features.memory.SourceLearningJob;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.OwnerExperienceProjection;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Exact native operation projection contract. Arbitrary JSON source labels grant nothing. */
public final class OwnerExperienceLearningDraftProjectionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final String BODY="来自原来源、现应退役的模型候选";
    private static SourceRef source(){return new SourceRef("message","original-native-user",1,"owner","private");}
    private static JSONObject operation()throws Exception {
        String operation=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),call=UUID.randomUUID().toString(),fingerprint="sha256:"+"a".repeat(64);
        JSONObject recorded=new JSONObject().put("format","pocketworld.source-learning-draft.v1").put("sourceRef",source().toJson()).put("sourceFingerprint",fingerprint)
                .put("draft",new JSONObject().put("layer","fact").put("text",BODY).put("reason","待审").put("applicability","").put("confidence",0.5));
        JSONObject input=new JSONObject().put("sourceRef",source().toJson()).put("sourceFingerprint",fingerprint).put("title","主人原消息");
        return new JSONObject().put("operationId",operation).put("runId",run).put("tool",SourceLearningJob.TOOL).put("state","receipt").put("status","available").put("ownerGoal","原主人输入与已发生效果不全删")
                .put("arguments",new JSONObject().put("operationId",operation).put("runId",run).put("epoch",1).put("index",0).put("manifestHash","b".repeat(64)).put("input",input))
                .put("receipt",new JSONObject().put("kind","native_source_learning_draft").put("outcome","model_draft_received").put("index",0).put("callId",call)
                        .put("recordedDraft",recorded).put("draftFingerprint",TaskStore.capabilityFingerprint(recorded)).put("candidateBindingKey","c".repeat(64)).put("formalMemorySaved",false));
    }
    @Test public void knownWithdrawalRetiresOnlyDerivedBodyAndPreservesOriginalOperationCallAndHashes()throws Exception {
        JSONObject original=operation();String exact=original.toString();JSONObject projected=OwnerExperienceProjection.projectNativeLearningDraft(original,ref->{assertEquals(source(),ref);return true;});
        assertFalse(projected.toString().contains(BODY));assertTrue(projected.getJSONObject("receipt").getBoolean("recordedDraftRetired"));assertFalse(projected.getBoolean("derivedBodyIncluded"));assertTrue(projected.getBoolean("occurrenceRetained"));
        for(String field:new String[]{"operationId","runId","state","ownerGoal"})assertEquals(original.get(field),projected.get(field));
        for(String field:new String[]{"callId","draftFingerprint","candidateBindingKey","outcome"})assertEquals(original.getJSONObject("receipt").get(field),projected.getJSONObject("receipt").get(field));
        assertEquals(exact,original.toString());assertEquals("source_withdrawn",projected.getString("status"));
    }
    @Test public void unknownLaterDeletionStateKeepsOldEvidenceReadOnlyWithoutInventingWithdrawal()throws Exception {
        JSONObject original=operation();int[] checks={0};assertSame(original,OwnerExperienceProjection.projectNativeLearningDraft(original,ref->{checks[0]++;return false;}));assertEquals(1,checks[0]);assertTrue(original.toString().contains(BODY));assertFalse(original.has("sourceCurrent"));
    }
    @Test public void arbitraryToolDataAndJsonSourceLabelsNeverBecomeNativeLearningAuthority()throws Exception {
        JSONObject ordinary=operation().put("tool","ordinary_read");ordinary.put("sourceRef",source().toJson());assertSame(ordinary,OwnerExperienceProjection.projectNativeLearningDraft(ordinary,ref->{throw new AssertionError("arbitrary JSON obtained a native source decision");}));assertTrue(ordinary.toString().contains(BODY));
        JSONObject otherReceipt=operation();otherReceipt.getJSONObject("receipt").put("kind","ordinary_native_receipt");assertSame(otherReceipt,OwnerExperienceProjection.projectNativeLearningDraft(otherReceipt,ref->{throw new AssertionError("not a recorded learning draft");}));
    }
    @Test public void substitutedNativeInputOperationOrDraftHashIsRejectedBeforeConsultingAnySource()throws Exception {
        for(int change=0;change<3;change++){JSONObject bad=operation();if(change==0)bad.getJSONObject("receipt").getJSONObject("recordedDraft").put("sourceRef",new SourceRef("message","substituted-source",1,"owner","private").toJson());else if(change==1)bad.getJSONObject("arguments").put("operationId",UUID.randomUUID().toString());else bad.getJSONObject("receipt").put("draftFingerprint","d".repeat(64));
            assertThrows(SecurityException.class,()->OwnerExperienceProjection.projectNativeLearningDraft(bad,ref->{throw new AssertionError("unverified native JSON reached the source gate");}));}
    }
    @Test public void alreadyRetiredArchiveCannotReinflateBodyOrChangeItsExactSourceOnAnotherInstance()throws Exception {
        JSONObject retired=OwnerExperienceProjection.projectNativeLearningDraft(operation(),ref->true);assertSame(retired,OwnerExperienceProjection.projectNativeLearningDraft(retired,ref->{throw new AssertionError("no missing local history can revive a retired body");}));assertFalse(retired.toString().contains(BODY));
        retired.getJSONObject("receipt").put("recordedDraft",operation().getJSONObject("receipt").getJSONObject("recordedDraft"));assertThrows(SecurityException.class,()->OwnerExperienceProjection.projectNativeLearningDraft(retired,ref->false));
        JSONObject substituted=OwnerExperienceProjection.projectNativeLearningDraft(operation(),ref->true);substituted.getJSONObject("receipt").put("sourceRef",new SourceRef("message","changed",1,"owner","private").toJson());assertThrows(SecurityException.class,()->OwnerExperienceProjection.projectNativeLearningDraft(substituted,ref->false));
    }
    @Test public void originalBookKnownDeleteAndForgetAreStickyWhileAbsentSourcesStayUnknown()throws Exception {
        Map<String,SourceRef.State> actual=new HashMap<>();SourceRef deleted=source(),forgotten=new SourceRef("message","formally-forgotten",1,"owner","private");actual.put(deleted.sourceId,new SourceRef.State(deleted,false));actual.put(forgotten.sourceId,new SourceRef.State(forgotten,false));File file=new File(temp.getRoot(),"records.json");MemoryBook book=new MemoryBook(file,(identity,kind,id)->actual.get(id));
        book.sourceUpdated(scope(),deleted);SourceRef tombstone=new SourceRef("message",deleted.sourceId,2,"owner","private");actual.put(deleted.sourceId,new SourceRef.State(tombstone,true));book.sourceDeleted(scope(),tombstone);
        JSONObject record=book.save(scope(),new JSONObject().put("text","即将遗忘").put("sourceRefs",new JSONArray().put(forgotten.toJson())),1);book.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));String persisted=new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(book.isOwnerExperienceSourceBlocked(scope(),deleted));assertTrue(book.isOwnerExperienceSourceBlocked(scope(),forgotten));assertFalse(book.isOwnerExperienceSourceBlocked(scope(),new SourceRef("message","unknown-later-history",1,"owner","private")));
        assertTrue(new MemoryBook(file).isOwnerExperienceSourceBlocked(scope(),deleted));assertEquals(persisted,new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));assertThrows(SecurityException.class,()->book.isOwnerExperienceSourceBlocked(new JSONObject().put("ownerId","owner").put("spaceId","group:other"),deleted));
    }
}
