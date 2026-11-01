package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FollowUpUiStateTest {
    @Test fun anOlderReadCannotReplaceExplicitlySavedPolicyRevision() {
        val read = JSONObject().put("policy", JSONObject().put("revision", 1).put("enabled", false))
        val saved = JSONObject().put("policy", JSONObject().put("revision", 2).put("enabled", true))
        val state = PocketUiState(moduleResults = mapOf("automation.getFollowUpPolicy" to read, "owner.setFollowUpPolicy" to saved))
        assertTrue(latestFollowUpPolicy(state)!!.getBoolean("enabled"))
        assertNull(latestFollowUpPolicy(PocketUiState()))
    }

    @Test fun newReadRevisionSupersedesSavedPolicyAndMalformedQuietTimeIsRejected() {
        val state = PocketUiState(moduleResults = mapOf("automation.getFollowUpPolicy" to JSONObject().put("policy", JSONObject().put("revision", 5).put("enabled", false)), "owner.setFollowUpPolicy" to JSONObject().put("policy", JSONObject().put("revision", 4).put("enabled", true))))
        assertFalse(latestFollowUpPolicy(state)!!.getBoolean("enabled"))
        assertTrue(validFollowUpClock("00:00"))
        assertTrue(validFollowUpClock("23:59"))
        assertFalse(validFollowUpClock("24:00"))
        assertFalse(validFollowUpClock("8:00"))
        assertFalse(validFollowUpClock("22:60"))
    }
}
