package dev.pocketengine.ui

import dev.pocketengine.runtime.models.ModelGroup
import dev.pocketengine.runtime.models.ModelTargetRef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelGroupEditPolicyTest {
    private fun candidate(id: String, connection: String, purposes: List<String>, audiences: List<String>, enabled: Boolean = true) =
        JSONObject().put("candidateId", id).put("target", ModelTargetRef.exact(connection, "model-$id").toJson())
            .put("enabled", enabled).put("allowedPurposes", JSONArray(purposes)).put("allowedAudiences", JSONArray(audiences))
    private fun original(): JSONObject = JSONObject().put("groupId", "group-original").put("revision", 4).put("name", "Original")
        .put("allowedAccounts", JSONObject().put("mode", "EXPLICIT").put("refs", JSONArray(listOf("connection-a", "unselected-allowed"))))
        .put("candidates", JSONArray(listOf(
            candidate("a", "connection-a", listOf("MAIN", "MEMBER.author", "VOICE_TURN"), listOf("APP_OWNER", "EXTERNAL_GROUP")),
            candidate("b", "connection-b", listOf("CONNECTION_TEST", "RELAY"), listOf("EXTERNAL_RELAY"), false)
        )))
        .put("attemptPolicy", JSONObject().put("maxAttempts", 3).put("maxRouteSwitches", 1))
    private fun chosen(original: JSONObject) = (0 until original.getJSONArray("candidates").length()).map { original.getJSONArray("candidates").getJSONObject(it) }
    private fun definition(original: JSONObject?, rows: List<JSONObject>, external: Boolean = true, edited: Boolean = false) =
        modelGroupEditDefinition(original, rows, 3, true, external, edited)
    private fun strings(array: JSONArray): Set<String> = (0 until array.length()).map { array.getString(it) }.toSet()
    private fun row(definition: JSONObject, id: String) = (0 until definition.getJSONArray("candidates").length())
        .map { definition.getJSONArray("candidates").getJSONObject(it) }.first { it.getString("candidateId") == id }
    private fun checked(definition: JSONObject): ModelGroup = ModelGroup.fromJson(JSONObject(definition.toString())
        .put("groupId", "group-original").put("revision", 5).put("name", "Renamed"))
    private fun assertPermissionsSame(expected: JSONObject, actual: JSONObject) {
        for (field in listOf("allowedPurposes", "allowedAudiences")) assertEquals(field, strings(expected.getJSONArray(field)), strings(actual.getJSONArray(field)))
        assertEquals(expected.getBoolean("enabled"), actual.getBoolean("enabled"))
        assertEquals(ModelTargetRef.fromJson(expected.getJSONObject("target")), ModelTargetRef.fromJson(actual.getJSONObject("target")))
    }
    @Test fun renameAndReorderPreserveEveryCandidatePermissionAndOriginalAllowlist() {
        val original = original(); val before = original.toString(); val rows = chosen(original).reversed()
        val saved = definition(original, rows)
        assertEquals(listOf("b", "a"), (0 until saved.getJSONArray("candidates").length()).map { saved.getJSONArray("candidates").getJSONObject(it).getString("candidateId") })
        for (candidate in rows) assertPermissionsSame(candidate, row(saved, candidate.getString("candidateId")))
        assertEquals(setOf("connection-a", "unselected-allowed"), strings(saved.getJSONObject("allowedAccounts").getJSONArray("refs")))
        assertFalse(checked(saved).allowedAccounts.contains("connection-b")); assertEquals(before, original.toString())
    }
    @Test fun mixedExternalInitialCheckboxDoesNotGrantExternalUseToOwnerOnlyCandidate() {
        val original = original(); val saved = definition(original, chosen(original))
        assertFalse(strings(row(saved, "a").getJSONArray("allowedAudiences")).contains("EXTERNAL_RELAY"))
        assertFalse(strings(row(saved, "a").getJSONArray("allowedPurposes")).contains("RELAY"))
        assertEquals(setOf("EXTERNAL_RELAY"), strings(row(saved, "b").getJSONArray("allowedAudiences")))
    }
    @Test fun explicitRelayOffPreservesAllUnrelatedAudienceAndPurposePermissions() {
        val original = original(); val saved = definition(original, chosen(original), external = false, edited = true)
        assertEquals(setOf("APP_OWNER", "EXTERNAL_GROUP"), strings(row(saved, "a").getJSONArray("allowedAudiences")))
        assertEquals(setOf("MAIN", "MEMBER.author", "VOICE_TURN"), strings(row(saved, "a").getJSONArray("allowedPurposes")))
        assertEquals(setOf("CONNECTION_TEST"), strings(row(saved, "b").getJSONArray("allowedPurposes")))
        assertTrue(strings(row(saved, "b").getJSONArray("allowedAudiences")).isEmpty())
        assertFalse(checked(saved).allowedAccounts.contains("connection-b"))
    }
    @Test fun explicitRelayOnDoesNotAddMainOrOwnerOrRestoreExcludedAccounts() {
        val original = original(); val saved = definition(original, chosen(original), external = true, edited = true)
        assertEquals(setOf("CONNECTION_TEST", "RELAY"), strings(row(saved, "b").getJSONArray("allowedPurposes")))
        assertEquals(setOf("EXTERNAL_RELAY"), strings(row(saved, "b").getJSONArray("allowedAudiences")))
        assertEquals(setOf("APP_OWNER", "EXTERNAL_GROUP", "EXTERNAL_RELAY"), strings(row(saved, "a").getJSONArray("allowedAudiences")))
        assertFalse(checked(saved).allowedAccounts.contains("connection-b"))
    }
    @Test fun removingCandidateDoesNotRebuildOriginalAccountAllowlistOrMutateRemainingPolicy() {
        val original = original(); val remaining = listOf(chosen(original)[1]); val saved = definition(original, remaining)
        assertPermissionsSame(remaining.single(), row(saved, "b"))
        assertEquals(setOf("connection-a", "unselected-allowed"), checked(saved).allowedAccounts)
    }
    @Test fun newlyAddedCandidateGetsOnlyCurrentExplicitDefaultAndItsSourceAccount() {
        val original = original(); val added = JSONObject().put("candidateId", "new").put("target", ModelTargetRef.exact("connection-new", "model-new").toJson()).put("enabled", true)
        val saved = definition(original, chosen(original) + added)
        assertPermissionsSame(chosen(original)[0], row(saved, "a")); assertPermissionsSame(chosen(original)[1], row(saved, "b"))
        assertEquals(setOf("MAIN", "SUMMARY", "MEMBER", "RELAY"), strings(row(saved, "new").getJSONArray("allowedPurposes")))
        assertEquals(setOf("APP_OWNER", "EXTERNAL_RELAY"), strings(row(saved, "new").getJSONArray("allowedAudiences")))
        assertEquals(setOf("connection-a", "unselected-allowed", "connection-new"), checked(saved).allowedAccounts)
        assertFalse(added.has("allowedPurposes")); assertFalse(added.has("allowedAudiences"))
    }
    @Test fun addingAnotherModelFromAnExcludedSourceCannotReauthorizeItsRetainedCandidate() {
        val original = original()
        original.getJSONArray("candidates").getJSONObject(1).put("enabled", true)
        val added = JSONObject().put("candidateId", "new-b").put("target", ModelTargetRef.exact("connection-b", "different-model").toJson()).put("enabled", true)
        assertThrows(IllegalArgumentException::class.java) { definition(original, chosen(original) + added) }
        val replacement = definition(original, listOf(chosen(original)[0], added))
        assertTrue(checked(replacement).allowedAccounts.contains("connection-b"))
        assertEquals(2, replacement.getJSONArray("candidates").length())
        assertPermissionsSame(chosen(original)[0], row(replacement, "a"))
    }
    @Test fun originalCandidateIdentityCannotBeReusedForAnotherTarget() {
        val original = original(); val edited = chosen(original).map { JSONObject(it.toString()) }
        edited.first().put("target", ModelTargetRef.exact("connection-other", "model-other").toJson())
        assertThrows(IllegalArgumentException::class.java) { definition(original, edited) }
    }
    @Test fun retainedCandidatePermissionsComeFromOriginalNotAnEditedRowCopy() {
        val original = original(); val edited = chosen(original).map { JSONObject(it.toString()) }
        edited.first().put("allowedAudiences", JSONArray(listOf("EXTERNAL_RELAY")))
        edited.first().put("allowedPurposes", JSONArray(listOf("RELAY")))
        assertPermissionsSame(chosen(original).first(), row(definition(original, edited), "a"))
    }
    @Test fun unsupportedOrMissingAccountPolicyIsRejectedWithoutChangingItsMeaning() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("allowedAccounts").put("mode", "ANY") },
            { it.getJSONObject("allowedAccounts").remove("refs") },
            { it.remove("allowedAccounts") }
        )) {
            val original = original().also(change); val before = original.toString()
            assertThrows(Exception::class.java) { ModelGroup.fromJson(original) }
            assertThrows(IllegalArgumentException::class.java) { definition(original, chosen(original)) }
            assertEquals(before, original.toString())
        }
    }
    @Test fun newGroupUsesExistingOwnerOnlyDefaultsWithoutExternalOptIn() {
        val added = JSONObject().put("candidateId", "new").put("target", ModelTargetRef.exact("connection-new", "model-new").toJson()).put("enabled", true)
        val saved = definition(null, listOf(added), external = false)
        assertEquals(setOf("MAIN", "SUMMARY", "MEMBER"), strings(row(saved, "new").getJSONArray("allowedPurposes")))
        assertEquals(setOf("APP_OWNER"), strings(row(saved, "new").getJSONArray("allowedAudiences")))
        assertEquals(setOf("connection-new"), checked(saved).allowedAccounts)
    }
}
