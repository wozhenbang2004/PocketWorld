package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginVersionsStateTest {
    private fun manifest() = JSONObject().put("id", "weather").put("version", "2")
    private fun preview() = JSONObject().put("id", "weather").put("manifest", manifest()).put("expectedInstallationId", "install-1").put("expectedStateRevision", 3).put("targetVersion", "2")
    private fun draft() = PluginPreviewDraft("request-2", manifest().toString(), "weather")
    private fun metadata() = JSONObject().put("_requestId", "request-2")

    @Test fun editedManifestOrLateOtherRequestCannotAuthorizeInstall() {
        assertNotNull(reviewedPluginPreview(draft(), manifest().toString(), metadata(), preview()))
        assertNull(reviewedPluginPreview(draft(), manifest().put("version", "3").toString(), metadata(), preview()))
        assertNull(reviewedPluginPreview(draft(), manifest().toString(), JSONObject().put("_requestId", "old-request"), preview()))
        assertNull(reviewedPluginPreview(draft(), manifest().toString(), metadata(), preview().put("id", "other")))
    }

    @Test fun installUsesNormalizedReviewedManifestAndMandatoryCompareIdentity() {
        val result = preview().put("manifest", manifest().put("normalized", true))
        val payload = pluginInstallPayload(draft(), manifest().toString(), metadata(), result, "one-time")
        assertTrue(payload.getJSONObject("manifest").getBoolean("normalized"))
        assertEquals("install-1", payload.getString("expectedInstallationId"))
        assertEquals(3L, payload.getLong("expectedStateRevision"))
        assertEquals("one-time", payload.getString("token"))
        result.getJSONObject("manifest").put("normalized", false)
        assertTrue(payload.getJSONObject("manifest").getBoolean("normalized"))
    }

    @Test fun firstInstallRequiresExplicitEmptyCompareIdentityAndDoesNotInventToken() {
        val result = preview().put("expectedInstallationId", "")
        val payload = pluginInstallPayload(draft(), manifest().toString(), metadata(), result, "")
        assertEquals("", payload.getString("expectedInstallationId"))
        assertFalse(payload.has("token"))
        result.remove("expectedInstallationId")
        assertNull(reviewedPluginPreview(draft(), manifest().toString(), metadata(), result))
    }

    @Test fun historyIsBoundToRequestedPluginAndNativeRequest() {
        val history = JSONObject().put("id", "weather").put("currentInstallationId", "install-2").put("currentStateRevision", 3).put("versions", JSONArray())
        val meta = JSONObject().put("_requestId", "history-1").put("id", "weather")
        assertNotNull(reviewedPluginHistory("weather", "history-1", meta, history))
        assertNull(reviewedPluginHistory("other", "history-1", meta, history))
        assertNull(reviewedPluginHistory("weather", "history-2", meta, history))
        assertNull(reviewedPluginHistory("weather", "history-1", meta, JSONObject(history.toString()).put("id", "other")))
    }

    @Test fun restoreRejectsChangedCurrentIdentityAndCarriesOnlyNewToken() {
        val target = PluginRestoreDraft("weather", "install-old", "install-current", "1", 3, "restore-preview-1")
        val metadata = JSONObject().put("_requestId", target.requestId).put("id", "weather").put("targetInstallationId", "install-old")
        val reviewed = restorePreview(target)
        val payload = pluginRestorePayload(target, "install-current", 3, metadata, reviewed, "")
        assertEquals("install-old", payload.getString("targetInstallationId"))
        assertEquals("install-current", payload.getString("expectedInstallationId"))
        assertEquals(3L, payload.getLong("expectedStateRevision"))
        assertFalse(payload.has("token"))
        try { pluginRestorePayload(target, "install-newer", 3, metadata, reviewed, "unused"); fail("stale restore must fail") } catch (_: IllegalStateException) { }
        try { pluginRestorePayload(target, "install-current", 4, metadata, reviewed, "unused"); fail("new owner state must invalidate restore") } catch (_: IllegalStateException) { }
        try { pluginRestorePayload(target.copy(targetInstallationId = "install-current"), "install-current", 3, metadata, reviewed, ""); fail("current is not history") } catch (_: IllegalStateException) { }
    }

    private fun restorePreview(target: PluginRestoreDraft) = JSONObject().put("id", target.pluginId).put("targetInstallationId", target.targetInstallationId)
        .put("expectedInstallationId", target.expectedInstallationId).put("expectedStateRevision", target.expectedStateRevision)
        .put("restoration", true).put("targetVersion", target.version).put("manifest", manifest().put("version", target.version))

    @Test fun restoreRequiresItsActualTargetDifferenceReplyAndRejectsOldOrForeignReplies() {
        val target = PluginRestoreDraft("weather", "install-old", "install-current", "1", 3, "restore-preview-1")
        val metadata = JSONObject().put("_requestId", target.requestId).put("id", "weather").put("targetInstallationId", "install-old")
        assertNotNull(reviewedPluginRestorePreview(target, metadata, restorePreview(target)))
        assertNull(reviewedPluginRestorePreview(target, JSONObject(metadata.toString()).put("_requestId", "old"), restorePreview(target)))
        assertNull(reviewedPluginRestorePreview(target, metadata, restorePreview(target).put("expectedStateRevision", 4)))
        assertNull(reviewedPluginRestorePreview(target, metadata, restorePreview(target).put("targetInstallationId", "another-old-version")))
        assertNull(reviewedPluginRestorePreview(target, metadata, restorePreview(target).put("manifest", manifest())))
        try { pluginRestorePayload(target, "install-current", 3, metadata, null, "unused"); fail("a version label does not authorize restoration") } catch (_: IllegalStateException) { }
    }

    @Test fun oldStateLessRepliesCannotAuthorizeInstallOrRestore() {
        val old = preview(); old.remove("expectedStateRevision")
        assertNull(reviewedPluginPreview(draft(), manifest().toString(), metadata(), old))
        val history = JSONObject().put("id", "weather").put("currentInstallationId", "install-2").put("versions", JSONArray())
        assertNull(reviewedPluginHistory("weather", "history-1", JSONObject().put("_requestId", "history-1").put("id", "weather"), history))
        for (invalid in listOf("3", -1, 3.5)) assertNull(reviewedPluginPreview(draft(), manifest().toString(), metadata(), preview().put("expectedStateRevision", invalid)))
    }
}
