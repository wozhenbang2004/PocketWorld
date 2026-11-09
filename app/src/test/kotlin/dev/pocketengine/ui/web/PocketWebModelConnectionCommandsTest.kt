package dev.pocketengine.ui.web

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketWebModelConnectionCommandsTest {
    private fun input() = JSONObject().put("name", "我的来源").put("url", "https://api.fixture/v1")
        .put("provider", "openai_chat").put("screenshots", false).put("supportsImageInput", false)
        .put("maxOutputTokens", 4096).put("apiKey", "credential-fixture")

    @Test fun newSaveOnlyCommandAllowsNoModelAndCannotSelect() {
        val prepared = PocketWebModelConnectionCommands.prepare("models.connections.add", input()) as PocketWebModelConnectionCommands.Save
        assertTrue(prepared.adding); assertFalse(prepared.useForNewTasks)
        assertEquals("", prepared.id); assertEquals(0L, prepared.expectedRevision)
        assertEquals("", prepared.input.getString("model"))
        assertFalse(prepared.toString().contains("credential-fixture"))
    }
    @Test fun oldAddRemainsExplicitAddAndUseWithRequiredModel() {
        assertThrows(IllegalArgumentException::class.java) { PocketWebModelConnectionCommands.prepare("models.connection.add", input()) }
        val prepared = PocketWebModelConnectionCommands.prepare("models.connection.add", input().put("model", "model-fixture")) as PocketWebModelConnectionCommands.Save
        assertTrue(prepared.useForNewTasks)
    }
    @Test fun updateRequiresTheExactSavedSourceAndRejectsNativeIdentityInjection() {
        val request = input().put("id", "api-native").put("expectedRevision", 7)
        val prepared = PocketWebModelConnectionCommands.prepare("models.connections.update", request) as PocketWebModelConnectionCommands.Save
        assertFalse(prepared.adding); assertFalse(prepared.useForNewTasks); assertEquals(7L, prepared.expectedRevision)
        assertThrows(IllegalArgumentException::class.java) { PocketWebModelConnectionCommands.prepare("models.connections.update", request.put("capabilityBinding", JSONObject())) }
        assertThrows(IllegalArgumentException::class.java) { PocketWebModelConnectionCommands.prepare("models.connections.add", input().put("model", JSONObject.NULL)) }
    }
}
