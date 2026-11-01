package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExperienceSkillReviewGuardTest {
    private fun record() = JSONObject().put("id", "record-id").put("revision", 2).put("ownerId", "owner").put("spaceId", "private")
        .put("layer", "procedural").put("formation", "reviewed_learning").put("active", true).put("text", "备份前核对目录").put("applicability", "仅同类任务").put("sourceRefs", JSONArray())
    private fun origin() = JSONObject().put("kind", "accepted_procedure").put("ref", JSONObject().put("recordId", "record-id").put("revision", 2).put("ownerId", "owner").put("spaceId", "private"))
        .put("generated", true).put("draftAuthor", "MODEL").put("validationStatus", "unverified")
    private fun inspection() = JSONObject().put("origin", origin()).put("originStatus", "current").put("inspectedDigest", "b".repeat(64)).put("package", JSONObject().put("digest", "a".repeat(64)))
        .put("policy", JSONObject().put("requiredCapabilities", JSONArray()).put("expiresAt", JSONObject.NULL))
    private fun draft() = JSONObject().put("manifest", "---\nname: backup\ndescription: 备份参考\n---\n核对目录")
    private fun skill() = JSONObject().put("id", "skill-id").put("revision", 3).put("digest", "a".repeat(64)).put("origin", origin())
        .put("enabled", false).put("approvedDigest", "").put("contentVerified", true).put("originStatus", "current").put("statusReasons", JSONArray().put("disabled"))
    private fun read() = JSONObject().put("id", "skill-id").put("revision", 3).put("digest", "a".repeat(64)).put("path", "SKILL.md").put("hasMore", false)
    private fun rejects(action: () -> Unit) { try { action(); fail("stale review must fail closed") } catch (_: IllegalStateException) {} }

    @Test fun onlyActiveReviewedProceduresCanEnterDrafting() {
        assertTrue(ExperienceSkillReviewGuard.eligible(record()))
        for (invalid in listOf(record().put("active", false), record().put("formation", "explicit_save"), record().put("layer", "fact"), record().put("revision", 0))) assertFalse(ExperienceSkillReviewGuard.eligible(invalid))
    }
    @Test fun currentProcedurePinIncludesBodyAndSourcesNotJustIdentity() {
        assertTrue(ExperienceSkillReviewGuard.sameProcedure(record(), record()))
        assertFalse(ExperienceSkillReviewGuard.sameProcedure(record(), record().put("text", "已变更")))
        assertFalse(ExperienceSkillReviewGuard.sameProcedure(record(), record().put("sourceRefs", JSONArray().put("changed"))))
        assertFalse(ExperienceSkillReviewGuard.sameProcedure(record(), null))
    }
    @Test fun importPinsOnlyReviewedSourceDraftAndHostPolicy() {
        val payload = ExperienceSkillReviewGuard.importPayload(record(), record(), draft(), draft(), inspection())
        assertEquals("record-id", payload.getString("recordId")); assertEquals(2, payload.getLong("expectedRevision"))
        assertFalse(payload.has("scope")); assertFalse(payload.has("confirmed")); assertFalse(payload.has("enabled"))
        assertEquals("b".repeat(64), payload.getJSONObject("options").getString("inspectedDigest"))
        assertEquals(0, payload.getJSONObject("options").getJSONArray("requiredCapabilities").length())
    }
    @Test fun editingOrChangingSourceInvalidatesPriorInspection() {
        rejects { ExperienceSkillReviewGuard.importPayload(record(), record(), draft().put("manifest", "changed"), draft(), inspection()) }
        rejects { ExperienceSkillReviewGuard.importPayload(record(), record().put("revision", 3), draft(), draft(), inspection()) }
        val changed = inspection(); changed.getJSONObject("origin").getJSONObject("ref").put("spaceId", "other")
        rejects { ExperienceSkillReviewGuard.importPayload(record(), record(), draft(), draft(), changed) }
    }
    @Test fun importedResultMustRemainDisabledAndMatchExactDigestAndOrigin() {
        assertTrue(ExperienceSkillReviewGuard.validImport(record(), inspection(), JSONObject().put("skill", skill())))
        for (changed in listOf(skill().put("enabled", true), skill().put("approvedDigest", "a".repeat(64)), skill().put("digest", "c".repeat(64)), skill().put("origin", JSONObject())))
            assertFalse(ExperienceSkillReviewGuard.validImport(record(), inspection(), JSONObject().put("skill", changed)))
    }
    @Test fun approvalNeedsCurrentSourceAndWholeManifestRead() {
        val payload = ExperienceSkillReviewGuard.approvalPayload(skill(), skill(), read())
        assertEquals(3, payload.getInt("revision")); assertEquals("a".repeat(64), payload.getString("digest")); assertEquals(3, payload.length())
        rejects { ExperienceSkillReviewGuard.approvalPayload(skill(), skill(), read().put("hasMore", true)) }
        rejects { ExperienceSkillReviewGuard.approvalPayload(skill(), skill(), read().put("path", "notes.txt")) }
        rejects { ExperienceSkillReviewGuard.approvalPayload(skill(), skill().put("originStatus", "sourceUnavailable"), read()) }
        rejects { ExperienceSkillReviewGuard.approvalPayload(skill(), skill().put("contentVerified", false), read()) }
    }
    @Test fun approvalCannotUsePreviousVersionOrExpiredContent() {
        rejects { ExperienceSkillReviewGuard.approvalPayload(skill(), skill().put("revision", 4), read()) }
        rejects { ExperienceSkillReviewGuard.approvalPayload(skill(), skill().put("digest", "b".repeat(64)), read()) }
        rejects { ExperienceSkillReviewGuard.approvalPayload(skill(), skill().put("statusReasons", JSONArray().put("expired")), read()) }
        rejects { ExperienceSkillReviewGuard.approvalPayload(skill(), skill(), read().put("revision", 2)) }
    }
    @Test fun requestIdentityIgnoresLateOrWrongNativeResponse() {
        val response = JSONObject().put("requestId", "old").put("ok", true).put("result", JSONObject().put("draft", draft()))
        assertNull(MemoryLearningReviewGuard.exactEnvelope("new", response))
        assertNotNull(MemoryLearningReviewGuard.exactEnvelope("old", response))
    }
}
