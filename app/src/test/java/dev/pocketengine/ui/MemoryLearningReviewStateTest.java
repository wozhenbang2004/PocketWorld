package dev.pocketengine.ui;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;

/** The real page guards, not a separate model of the UI's authorization rules. */
public class MemoryLearningReviewStateTest {
    private JSONObject candidate() throws Exception {
        return new JSONObject().put("id", "candidate-1").put("revision", 3L).put("status", "pending")
                .put("text", "只有机主明确核对后才保存这条长中文候选。").put("layer", "preference")
                .put("reason", "从真实对话中提出，尚未独立核实").put("applicability", "仅用于当前个人偏好")
                .put("confidence", 0.7).put("contentKey", "reviewed-content")
                .put("sourceRefs", new JSONArray().put(new JSONObject().put("kind", "message")
                        .put("sourceId", "message-1").put("revision", 8L).put("ownerId", "owner").put("spaceId", "private")));
    }
    private JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private JSONObject envelope(String request, boolean ok, JSONObject result) throws Exception {
        return new JSONObject().put("requestId", request).put("ok", ok).put("result", result);
    }
    private void blocked(Runnable operation) {
        try { operation.run(); fail("Stale, unsupported or unsafe operation must not produce a payload"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }

    @Test public void exactRequestAndExplicitNativeSuccessAreRequired() throws Exception {
        JSONObject env = envelope("current-read", true, new JSONObject().put("candidate", candidate()));
        assertNotNull(MemoryLearningReviewGuard.candidate("current-read", "candidate-1", env));
        assertNull(MemoryLearningReviewGuard.candidate("earlier-read", "candidate-1", env));
        assertNull(MemoryLearningReviewGuard.candidate(null, "candidate-1", env));
        assertNull(MemoryLearningReviewGuard.candidate("current-read", "another-candidate", env));
        env.put("ok", false).put("error", "denied");
        assertNull(MemoryLearningReviewGuard.candidate("current-read", "candidate-1", env));
        env.put("ok", "true");
        assertNull(MemoryLearningReviewGuard.exactEnvelope("current-read", env));
    }

    @Test public void canceledSelectionCannotUseLateResponseAndResultsAreDetached() throws Exception {
        JSONObject original = candidate();
        JSONObject env = envelope("read", true, new JSONObject().put("candidate", original));
        assertNull(MemoryLearningReviewGuard.candidate("read", null, env));
        JSONObject selected = MemoryLearningReviewGuard.candidate("read", "candidate-1", env);
        selected.put("text", "changed locally");
        assertNotEquals("changed locally", original.getString("text"));
    }

    @Test public void revisionContentAndSourceChangesInvalidateConfirmation() throws Exception {
        JSONObject displayed = candidate();
        assertTrue(MemoryLearningReviewGuard.sameReview(displayed, copy(displayed)));
        assertFalse(MemoryLearningReviewGuard.sameReview(displayed, copy(displayed).put("revision", 4L)));
        assertFalse(MemoryLearningReviewGuard.sameReview(displayed, copy(displayed).put("text", "更新后的正文")));
        assertFalse(MemoryLearningReviewGuard.sameReview(displayed, copy(displayed).put("status", "rejected")));
        JSONObject changedSource = copy(displayed);
        changedSource.getJSONArray("sourceRefs").getJSONObject(0).put("revision", 9L);
        blocked(() -> MemoryLearningReviewGuard.payload("acceptCandidate", displayed, changedSource, new JSONObject()));
    }

    @Test public void largeIntegralRevisionsDoNotCollapseThroughDoubleConversion() throws Exception {
        JSONObject before = candidate().put("revision", 9007199254740992L);
        JSONObject after = copy(before).put("revision", 9007199254740993L);
        assertFalse(MemoryLearningReviewGuard.sameReview(before, after));
        assertEquals(9007199254740993L, MemoryLearningReviewGuard.revision(after));
        assertEquals(0L, MemoryLearningReviewGuard.revision(candidate().put("revision", 3.0)));
        assertEquals(0L, MemoryLearningReviewGuard.revision(candidate().put("revision", "3")));
    }

    @Test public void acceptanceCarriesOnlyDisplayedIdAndRevisionNeverAuthority() throws Exception {
        JSONObject displayed = candidate().put("ownerId", "untrusted-reference");
        JSONObject payload = MemoryLearningReviewGuard.payload("acceptCandidate", displayed, copy(displayed), new JSONObject());
        assertEquals(2, payload.length());
        assertEquals("candidate-1", payload.getString("id"));
        assertEquals(3L, payload.getLong("revision"));
        assertFalse(payload.has("scope"));
        assertFalse(payload.has("core"));
        assertFalse(payload.has("ownerId"));
    }

    @Test public void editingIsBoundToDisplayedInputAndDoesNotMutateTheNativeValue() throws Exception {
        JSONObject displayed = candidate();
        JSONObject changes = MemoryLearningReviewGuard.draft(displayed).put("text", "  更正后的候选  ");
        JSONObject payload = MemoryLearningReviewGuard.payload("editCandidate", displayed, copy(displayed), changes);
        assertEquals("更正后的候选", payload.getJSONObject("changes").getString("text"));
        assertEquals("  更正后的候选  ", changes.getString("text"));
        assertEquals(5, payload.getJSONObject("changes").length());
        assertFalse(payload.getJSONObject("changes").has("sourceRefs"));
        JSONObject changed = copy(displayed).put("text", "另一个版本");
        blocked(() -> MemoryLearningReviewGuard.payload("editCandidate", displayed, changed, changes));
    }

    @Test public void rejectedOrInvalidatedCandidateCannotBeAcceptedOrEdited() throws Exception {
        for (String status : new String[]{"accepted", "rejected", "invalidated", "archived"}) {
            JSONObject terminal = candidate().put("status", status);
            blocked(() -> MemoryLearningReviewGuard.payload("acceptCandidate", terminal, terminal, new JSONObject()));
            blocked(() -> MemoryLearningReviewGuard.payload("editCandidate", terminal, terminal, new JSONObject()));
        }
    }

    @Test public void rejectionRequiresExplicitReasonAndCurrentRevision() throws Exception {
        JSONObject displayed = candidate();
        blocked(() -> MemoryLearningReviewGuard.payload("rejectCandidate", displayed, displayed, new JSONObject()));
        JSONObject reason = new JSONObject().put("reason", "  尚不足以认定为我的长期偏好  ");
        JSONObject payload = MemoryLearningReviewGuard.payload("rejectCandidate", displayed, displayed, reason);
        assertEquals("尚不足以认定为我的长期偏好", payload.getString("reason"));
        assertEquals(3L, payload.getLong("revision"));
        JSONObject denied = envelope("reject-current", false, new JSONObject().put("candidate", displayed)).put("error", "revision changed");
        assertNull(MemoryLearningReviewGuard.result("reject-current", denied));
    }

    @Test public void cleanupCannotRemovePendingOrArchivedAndNeverUsesBulkScope() throws Exception {
        JSONObject pending = candidate();
        blocked(() -> MemoryLearningReviewGuard.payload("archiveCandidates", pending, pending, new JSONObject()));
        JSONObject terminal = candidate().put("status", "rejected");
        JSONObject payload = MemoryLearningReviewGuard.payload("archiveCandidates", terminal, terminal, new JSONObject());
        assertEquals(1, payload.length());
        assertEquals(1, payload.getJSONArray("selections").length());
        assertEquals(2, payload.getJSONArray("selections").getJSONObject(0).length());
        JSONObject archived = terminal.put("status", "archived").put("outcome", "rejected");
        blocked(() -> MemoryLearningReviewGuard.payload("archiveCandidates", archived, archived, new JSONObject()));
    }

    @Test public void reconsiderationUsesActualLastReviewedBodyAndCannotInventArchivedBody() throws Exception {
        JSONObject rejected = candidate().put("status", "rejected");
        JSONObject payload = MemoryLearningReviewGuard.payload("reconsiderRejected", rejected, rejected, new JSONObject().put("text", "forged"));
        assertEquals(rejected.getString("text"), payload.getJSONObject("draft").getString("text"));
        JSONObject archived = copy(rejected).put("status", "archived").put("outcome", "rejected");
        archived.remove("text");
        blocked(() -> MemoryLearningReviewGuard.payload("reconsiderRejected", archived, archived, new JSONObject()));
        blocked(() -> MemoryLearningReviewGuard.draft(archived));
    }

    @Test public void forgetRequiresExactRecordRevisionAndUsesOnlyNativeCasPayload() throws Exception {
        JSONObject record = new JSONObject().put("id", "memory-1").put("revision", 12L).put("text", "当前正式记忆");
        JSONObject payload = MemoryLearningReviewGuard.forgetPayload(record, copy(record));
        assertEquals(2, payload.length());
        assertEquals(12L, payload.getLong("expectedRevision"));
        assertFalse(payload.has("revision"));
        assertFalse(payload.has("scope"));
        JSONObject changed = copy(record).put("revision", 13L);
        blocked(() -> MemoryLearningReviewGuard.forgetPayload(record, changed));
        blocked(() -> MemoryLearningReviewGuard.forgetPayload(record, null));
    }

    @Test public void nativeMutationResultMustMatchInputStatusRevisionAndRecordLink() throws Exception {
        JSONObject displayed = candidate();
        JSONObject payload = MemoryLearningReviewGuard.payload("acceptCandidate", displayed, displayed, new JSONObject());
        JSONObject accepted = copy(displayed).put("status", "accepted").put("revision", 4L).put("acceptedRecordId", "memory-1");
        JSONObject record = new JSONObject().put("id", "memory-1").put("revision", 1L).put("learningCandidateId", "candidate-1");
        JSONObject result = new JSONObject().put("candidate", accepted).put("record", record);
        assertTrue(MemoryLearningReviewGuard.validMutationResult("acceptCandidate", payload, result));
        accepted.put("revision", 3L);
        assertFalse(MemoryLearningReviewGuard.validMutationResult("acceptCandidate", payload, result));
        accepted.put("revision", 4L);
        record.put("learningCandidateId", "another-candidate");
        assertFalse(MemoryLearningReviewGuard.validMutationResult("acceptCandidate", payload, result));
        assertFalse(MemoryLearningReviewGuard.validMutationResult("acceptCandidate", payload, null));
    }

    @Test public void editResultCannotClaimSuccessForDifferentSubmittedText() throws Exception {
        JSONObject displayed = candidate();
        JSONObject changes = MemoryLearningReviewGuard.draft(displayed).put("text", "我明确编辑后的内容");
        JSONObject payload = MemoryLearningReviewGuard.payload("editCandidate", displayed, displayed, changes);
        JSONObject returned = copy(displayed).put("revision", 4L);
        assertFalse(MemoryLearningReviewGuard.validMutationResult("editCandidate", payload, new JSONObject().put("candidate", returned)));
        returned.put("text", changes.getString("text"));
        assertTrue(MemoryLearningReviewGuard.validMutationResult("editCandidate", payload, new JSONObject().put("candidate", returned)));
    }

    @Test public void pendingNativeWriteAndLocalRequestBothBlockRepeatedSubmission() {
        assertTrue(MemoryLearningReviewGuard.canSubmit(null, Set.of("listCandidates")));
        assertFalse(MemoryLearningReviewGuard.canSubmit("just-submitted", Set.of()));
        for (String command : new String[]{"acceptCandidate", "editCandidate", "rejectCandidate", "archiveCandidates", "reconsiderRejected", "forgetReviewed"})
            assertFalse(MemoryLearningReviewGuard.canSubmit(null, Set.of(command)));
        assertTrue(MemoryLearningReviewGuard.canSubmit(null, Set.of()));
    }

    private JSONObject memory(String id, long revision) throws Exception {
        return new JSONObject().put("id", id).put("revision", revision).put("ownerId", "owner").put("spaceId", "private")
                .put("active", true).put("consolidationBlocked", false).put("layer", "fact").put("text", "真实且完整的记忆正文 " + id)
                .put("sourceRefs", new JSONArray()).put("sourceIds", new JSONArray()).put("core", false);
    }
    private JSONArray memories(int count) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < count; i++) rows.put(memory("memory-" + i, i + 1L));
        return rows;
    }
    private JSONObject consolidation(JSONArray parents, String status) throws Exception {
        return candidate().put("sourceRefs", new JSONArray()).put("recordRefs", new JSONArray(parents.toString())).put("status", status);
    }
    private JSONObject proposed(JSONObject candidate) throws Exception {
        return new JSONObject().put("proposed", true).put("reviewRequired", true).put("formalMemorySaved", false)
                .put("outcome", "awaiting_review").put("candidate", candidate);
    }

    @Test public void consolidationRequiresExplicitTwoToTwentyCurrentDistinctRecords() throws Exception {
        for (int count : new int[]{0, 1, 21}) {
            JSONArray rows = memories(count);
            blocked(() -> MemoryLearningReviewGuard.consolidationPayload(rows, rows));
        }
        JSONArray rows = memories(20);
        JSONObject payload = MemoryLearningReviewGuard.consolidationPayload(rows, rows);
        assertEquals(1, payload.length());
        assertEquals(20, payload.getJSONArray("selections").length());
        JSONObject ref = payload.getJSONArray("selections").getJSONObject(0);
        assertEquals(2, ref.length()); assertFalse(ref.has("ownerId")); assertFalse(ref.has("text")); assertFalse(ref.has("core"));
        JSONArray duplicate = new JSONArray().put(rows.getJSONObject(0)).put(rows.getJSONObject(0));
        blocked(() -> MemoryLearningReviewGuard.consolidationPayload(duplicate, rows));
    }

    @Test public void staleDeletedInactiveBlockedOrForeignSelectionsNeverSilentlyUpgrade() throws Exception {
        JSONArray selected = memories(2);
        for (String field : new String[]{"revision", "text", "active", "consolidationBlocked", "spaceId", "ownerId"}) {
            JSONArray current = new JSONArray(selected.toString());
            Object replacement = switch (field) { case "revision" -> 99L; case "active" -> false; case "consolidationBlocked" -> true; default -> "changed"; };
            current.getJSONObject(0).put(field, replacement);
            blocked(() -> MemoryLearningReviewGuard.consolidationPayload(selected, current));
        }
        blocked(() -> MemoryLearningReviewGuard.consolidationPayload(selected, new JSONArray().put(selected.optJSONObject(1))));
        for (Object badRevision : new Object[]{0L, -1L, 1.0, "1"})
            assertFalse(MemoryLearningReviewGuard.eligibleRecord(memory("one", 1L).put("revision", badRevision)));
        JSONObject missing = memory("one", 1L); missing.remove("consolidationBlocked");
        assertFalse(MemoryLearningReviewGuard.eligibleRecord(missing));
    }

    @Test public void recordOnlyCandidateCanBeAcceptedWithoutInventedMessageSource() throws Exception {
        JSONArray rows = memories(2);
        JSONArray parents = MemoryLearningReviewGuard.consolidationPayload(rows, rows).getJSONArray("selections");
        JSONObject candidate = consolidation(parents, "pending");
        assertTrue(MemoryLearningReviewGuard.hasReviewEvidence(candidate));
        assertEquals(0, candidate.getJSONArray("sourceRefs").length());
        JSONObject payload = MemoryLearningReviewGuard.payload("acceptCandidate", candidate, copy(candidate), new JSONObject());
        assertEquals(2, payload.length());
        JSONObject orphan = copy(candidate); orphan.remove("recordRefs");
        assertFalse(MemoryLearningReviewGuard.hasReviewEvidence(orphan));
        blocked(() -> MemoryLearningReviewGuard.payload("acceptCandidate", orphan, orphan, new JSONObject()));
    }

    @Test public void exactParentSetsRejectMissingDuplicatesAndNonIntegralVersions() throws Exception {
        JSONArray rows = memories(2);
        JSONArray refs = MemoryLearningReviewGuard.consolidationPayload(rows, rows).getJSONArray("selections");
        assertTrue(MemoryLearningReviewGuard.sameRecordRefs(refs, new JSONArray().put(refs.getJSONObject(1)).put(refs.getJSONObject(0))));
        assertFalse(MemoryLearningReviewGuard.validRecordRefs(new JSONArray().put(refs.getJSONObject(0))));
        assertFalse(MemoryLearningReviewGuard.validRecordRefs(new JSONArray().put(refs.getJSONObject(0)).put(refs.getJSONObject(0))));
        JSONArray changed = new JSONArray(refs.toString()); changed.getJSONObject(0).put("revision", "1");
        assertFalse(MemoryLearningReviewGuard.validRecordRefs(changed));
    }

    @Test public void proposalNeedsExactRequestParentsAndExplicitUnacceptedReviewState() throws Exception {
        JSONArray rows = memories(2);
        JSONObject payload = MemoryLearningReviewGuard.consolidationPayload(rows, rows);
        JSONObject candidate = consolidation(payload.getJSONArray("selections"), "pending");
        JSONObject result = proposed(candidate);
        JSONObject reply = envelope("proposal-current", true, result);
        assertNotNull(MemoryLearningReviewGuard.consolidationCandidate("proposal-current", payload, reply));
        assertNull(MemoryLearningReviewGuard.consolidationCandidate("proposal-old", payload, reply));
        for (String field : new String[]{"proposed", "reviewRequired", "formalMemorySaved"}) {
            JSONObject bad = copy(result).put(field, field.equals("formalMemorySaved"));
            assertNull(MemoryLearningReviewGuard.consolidationCandidate("proposal-current", payload, envelope("proposal-current", true, bad)));
        }
        candidate.getJSONArray("recordRefs").getJSONObject(0).put("revision", 88L);
        assertNull(MemoryLearningReviewGuard.consolidationCandidate("proposal-current", payload, reply));
    }

    @Test public void previousRejectedOrArchivedResultIsABarrierNeverNewPending() throws Exception {
        JSONArray rows = memories(2);
        JSONObject payload = MemoryLearningReviewGuard.consolidationPayload(rows, rows);
        for (String status : new String[]{"accepted", "rejected", "invalidated", "archived"}) {
            JSONObject candidate = consolidation(payload.getJSONArray("selections"), status);
            JSONObject result = new JSONObject().put("candidate", candidate).put("proposed", false).put("reviewRequired", false)
                    .put("formalMemorySaved", false).put("outcome", "existing_candidate_barrier");
            assertEquals(status, MemoryLearningReviewGuard.consolidationCandidate("proposal", payload, envelope("proposal", true, result)).getString("status"));
            result.put("proposed", true);
            assertNull(MemoryLearningReviewGuard.consolidationCandidate("proposal", payload, envelope("proposal", true, result)));
        }
    }

    @Test public void parentDetailRequiresExactRequestCandidateAndReferencedVersion() throws Exception {
        JSONArray rows = memories(2);
        JSONObject payload = MemoryLearningReviewGuard.consolidationPayload(rows, rows);
        JSONObject candidate = consolidation(payload.getJSONArray("selections"), "pending");
        JSONObject ref = candidate.getJSONArray("recordRefs").getJSONObject(0);
        JSONObject record = memory(ref.getString("id"), ref.getLong("revision"));
        JSONObject reply = envelope("parent-current", true, new JSONObject().put("record", record));
        assertNotNull(MemoryLearningReviewGuard.parentRecord("parent-current", candidate, copy(candidate), ref, reply));
        assertNull(MemoryLearningReviewGuard.parentRecord("parent-old", candidate, candidate, ref, reply));
        assertNull(MemoryLearningReviewGuard.parentRecord("parent-current", candidate, copy(candidate).put("revision", 4L), ref, reply));
        assertNull(MemoryLearningReviewGuard.parentRecord("parent-current", candidate, null, ref, reply));
        assertNull(MemoryLearningReviewGuard.parentRecord("parent-current", candidate, candidate, new JSONObject().put("id", "foreign").put("revision", 1L), reply));
        record.put("revision", ref.getLong("revision") + 1);
        assertNull(MemoryLearningReviewGuard.parentRecord("parent-current", candidate, candidate, ref, reply));
    }

    @Test public void cancellationAcknowledgementCannotClaimAnotherRunOrUndoSettledWork() throws Exception {
        JSONObject result = new JSONObject().put("cancelled", true).put("requestId", "proposal-original").put("outcome", "stopped");
        JSONObject reply = envelope("cancel-current", true, result);
        assertEquals("stopped", MemoryLearningReviewGuard.cancelResult("cancel-current", "proposal-original", reply));
        assertNull(MemoryLearningReviewGuard.cancelResult("cancel-old", "proposal-original", reply));
        assertNull(MemoryLearningReviewGuard.cancelResult("cancel-current", "different-proposal", reply));
        result.put("cancelled", false).put("outcome", "already_settled");
        assertEquals("already_settled", MemoryLearningReviewGuard.cancelResult("cancel-current", "proposal-original", reply));
        result.put("cancelled", true);
        assertNull(MemoryLearningReviewGuard.cancelResult("cancel-current", "proposal-original", reply));
        result.put("cancelled", "true").put("outcome", "stopped");
        assertNull(MemoryLearningReviewGuard.cancelResult("cancel-current", "proposal-original", reply));
        assertFalse(MemoryLearningReviewGuard.canSubmit(null, Set.of("proposeConsolidation")));
    }

    @Test public void proceduralDraftRequiresApplicabilityAndAuthorityFieldsAreRejected() throws Exception {
        JSONObject draft = MemoryLearningReviewGuard.draft(candidate()).put("layer", "procedural").put("applicability", "");
        blocked(() -> MemoryLearningReviewGuard.validateDraft(draft));
        draft.put("applicability", "仅在已有验证条件下作为候选参考");
        MemoryLearningReviewGuard.validateDraft(draft);
        draft.put("core", true);
        blocked(() -> MemoryLearningReviewGuard.validateDraft(draft));
    }
}
