package dev.pocketengine.ui.web

import dev.pocketengine.browser.BrowserResearchResult
import dev.pocketengine.browser.BrowserStructuredSource
import dev.pocketengine.store.TaskStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserSourceReaderContractTest {
    private val task = "11111111-1111-1111-1111-111111111111"
    private val sourceId = "22222222-2222-2222-2222-222222222222"
    private val historicalRun = "33333333-3333-3333-3333-333333333333"
    private val target = BrowserSourceReaderTarget.source(task, sourceId)
    private fun source(structured: Boolean = true): JSONObject = JSONObject()
        .put("taskId", task).put("sourceId", sourceId).put("sourceRef", BrowserResearchResult.reference(task, sourceId))
        .put("sourceFingerprint", "a".repeat(64)).put("runId", historicalRun).put("epoch", 2)
        .put("title", "当时的原文").put("url", "https://example.com/article").put("observedAt", 1234L)
        .put("excerpt", "<script>alert('plain text')</script>").put("urlQueryOmitted", true)
        .put("kind", "browser_semantic_observation").put("semantic", JSONObject().put("partial", true))
        .put("privateUrl", "https://example.com/article?token=SECRET").put("profileId", "PRIVATE_PROFILE")
        .put("browserSessionId", "PRIVATE_SESSION").put("executionScope", JSONObject().put("secret", "AUTHORITY"))
        .also { if (structured) it.put("structuredProjection", projection()) }
    private fun projection(): JSONObject = JSONObject().put("version", 1).put("mode", "list")
        .put("basis", "runner_safe_json_delivery").put("deliveredReadRevision", "b".repeat(64))
        .put("deliveredRows", 2).put("omittedDeliveredRows", 0).put("partial", true)
        .put("data", JSONArray().put(JSONObject().put("index", 3).put("kind", "listitem").put("text", "原第四项")
            .put("links", JSONArray().put(JSONObject().put("text", "不可导航").put("href", "https://private.test/?secret=HIDDEN"))).put("partial", true))
            .put(JSONObject().put("index", 7).put("kind", "listitem").put("text", "原第八项").put("links", JSONArray()).put("partial", false)))
        .also { it.put("revision", TaskStore.capabilityFingerprint(it)) }

    @Test fun projectionAllowlistNeverCopiesPrivateNavigationOrAuthority() {
        val dto = BrowserSourceReaderDto.source(target, source())
        val encoded = dto.toString()
        for (forbidden in listOf("privateUrl", "profileId", "browserSessionId", "executionScope", "SECRET", "PRIVATE_PROFILE", "AUTHORITY", "href", "HIDDEN")) assertFalse(forbidden, encoded.contains(forbidden))
        assertEquals("<script>alert('plain text')</script>", dto.getString("excerpt"))
        assertTrue(dto.getBoolean("untrusted")); assertFalse(dto.getBoolean("verified"))
        assertFalse(dto.getJSONObject("coverage").getBoolean("wholePage"))
    }
    @Test fun historicalRunAndOriginalSparseOrderArePreserved() {
        val dto = BrowserSourceReaderDto.source(target, source())
        assertEquals(historicalRun, dto.getString("runId")); assertEquals(2, dto.getInt("epoch"))
        val rows = dto.getJSONObject("projection").getJSONArray("rows")
        assertEquals(3, rows.getJSONObject(0).getInt("index")); assertEquals(7, rows.getJSONObject(1).getInt("index"))
        assertEquals(2, BrowserSourceReaderDto.fieldRefs(dto).size)
    }
    @Test fun eachFieldResolvesOnlyAgainstItsPersistentOriginalRevision() {
        val saved = source(); val dto = BrowserSourceReaderDto.source(target, saved)
        val ref = BrowserSourceReaderDto.fieldRefs(dto).first()
        val selected = BrowserStructuredSource.field(task, ref, saved)
        val field = BrowserSourceReaderDto.field(target, BrowserSourceReaderBinding.capture(target, saved), ref, selected, saved)
        assertEquals("原第四项", field.getString("text")); assertEquals(3, field.getInt("rowIndex"))
        assertFalse(field.toString().contains("href")); assertFalse(field.has("url"))
        assertEquals(target.sourceId, BrowserSourceReaderTarget.field(task, ref).sourceId)
        rejects { BrowserSourceReaderTarget.field("44444444-4444-4444-4444-444444444444", ref) }
        rejects { BrowserSourceReaderDto.field(target, BrowserSourceReaderBinding.capture(target, saved).copy(revision = "c".repeat(64)), ref, selected, saved) }
    }
    @Test fun wrongTaskSourceFingerprintOrMutatedProjectionFailClosed() {
        rejects { BrowserSourceReaderBinding.capture(target, source().put("taskId", "44444444-4444-4444-4444-444444444444")) }
        rejects { BrowserSourceReaderBinding.capture(target, source().put("sourceFingerprint", "")) }
        val changed = source(); changed.getJSONObject("structuredProjection").getJSONArray("data").getJSONObject(0).put("text", "changed")
        rejects { BrowserSourceReaderDto.source(target, changed) }
        assertNotEquals(BrowserSourceReaderBinding.capture(target, source()), BrowserSourceReaderBinding.capture(target, source().put("sourceFingerprint", "c".repeat(64))))
    }
    @Test fun privateUrlEchoesAreRedactedBeforeClippingIncludingProvisionalTitles() {
        val privateUrl = "https://example.com/article?token=SECRET"
        val saved = source(false).put("title", privateUrl.dropLast(3)).put("excerpt", "Original: $privateUrl")
        val dto = BrowserSourceReaderDto.source(target, saved)
        assertEquals("https://example.com/article", dto.getString("title"))
        assertEquals("Original: https://example.com/article", dto.getString("excerpt"))
        saved.put("title", "Source: $privateUrl")
        assertEquals("Source: https://example.com/article", BrowserSourceReaderDto.source(target, saved).getString("title"))
        saved.put("excerpt", "x".repeat(5980) + privateUrl)
        assertFalse(BrowserSourceReaderDto.source(target, saved).getString("excerpt").contains("?token"))
    }
    @Test fun privateUrlEchoesInStructuredTextAndFieldHeadersStayNative() {
        val saved = source(); val projection = saved.getJSONObject("structuredProjection")
        val privateUrl = saved.getString("privateUrl")
        projection.getJSONArray("data").getJSONObject(0).put("text", "Original: $privateUrl")
        projection.remove("revision"); projection.put("revision", TaskStore.capabilityFingerprint(projection))
        val dto = BrowserSourceReaderDto.source(target, saved)
        val ref = BrowserSourceReaderDto.fieldRefs(dto).first()
        val selected = BrowserStructuredSource.field(task, ref, saved)
        selected.getJSONArray("headers").put(JSONObject().put("text", privateUrl))
        val field = BrowserSourceReaderDto.field(target, BrowserSourceReaderBinding.capture(target, saved), ref, selected, saved)
        for (body in listOf(dto, field)) { assertFalse(body.toString().contains("SECRET")); assertFalse(body.toString().contains("?token")) }
    }
    @Test fun onlySanitizedHttpDisplayAddressesCrossTheBoundary() {
        for (url in listOf("javascript:alert(1)", "file:///private", "https://a.test/?secret=value", "https://a.test/#secret", "https://owner:password@a.test/"))
            rejects { BrowserSourceReaderDto.source(target, source(false).put("url", url)) }
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected original-binding validation to reject") } catch (expected: Exception) { /* Expected, never fallback to another source. */ }
    }
}
