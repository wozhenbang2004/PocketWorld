package dev.pocketengine.ui.web

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure protocol tests only. They do not render the toolbar, load a website, or prove device UX. */
class BrowserChromeContractTest {
    private fun args(action: String = "back") = JSONObject().put("windowId", "window").put("generation", 7L)
        .put("contextToken", "context").put("action", action)
    @Test fun exactContextAndClosedVocabularyAreRequired() {
        assertEquals(BrowserChromeAction.BACK, BrowserChromeContract.parse(args(), "window", 7, "context").action)
        for (bad in listOf(args().put("generation", "7"), args().put("generation", 7.5), args().put("windowId", "other"),
            args().put("contextToken", "stale"), args("evaluate"), args().put("taskId", "injected"), args().put("navigationReviewRevision", 9L), args().put("input", "extra"))) {
            assertThrows(IllegalArgumentException::class.java) { BrowserChromeContract.parse(bad, "window", 7, "context") }
        }
    }
    @Test fun unknownRecreationCannotRestoreHistoryOrFallbackToASavedUrl() {
        assertFalse(BrowserChromeRestorePolicy.automaticRestoreAllowed(true))
        assertTrue(BrowserChromeRestorePolicy.automaticRestoreAllowed(false))
        val requests = mutableListOf<String>()
        for (hasHistory in listOf(false, true)) {
            if (BrowserChromeRestorePolicy.automaticRestoreAllowed(true)) {
                if (hasHistory) requests.add("restoreState") else requests.add("savedUrlFallback")
            }
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun onlyNavigationAcceptsBoundedInput() {
        assertEquals("https://example.com/?private=yes", BrowserChromeContract.parse(args("navigate").put("input", "https://example.com/?private=yes"), "window", 7, "context").input)
        for (value in listOf("", " ", "x".repeat(8193), "https://example.com/\nnext", "bad\u0000input")) {
            assertThrows(IllegalArgumentException::class.java) { BrowserChromeContract.parse(args("navigate").put("input", value), "window", 7, "context") }
        }
    }
    @Test fun projectionAllowsLiveAddressButNeverNativeIdentityOrCapabilities() {
        val projected = BrowserChromeContract.project(BrowserChromeState(Any(), "https://example.com/?private=yes", "t".repeat(500),
            "s".repeat(1000), false, 200, true, false, 20, true, true, false, 4L))
        assertEquals("https://example.com/?private=yes", projected.getString("address"))
        assertEquals(240, projected.getString("title").length); assertEquals(500, projected.getString("status").length)
        assertEquals(4L, projected.getLong("navigationReviewRevision")); assertEquals(100, projected.getInt("progress")); assertEquals(8, projected.getInt("pageCount"))
        assertEquals(setOf("address", "title", "status", "loading", "progress", "canBack", "canForward", "pageCount", "researchAvailable", "pageAvailable", "navigationUnconfirmed", "navigationReviewRevision"), projected.keys().asSequence().toSet())
    }
    private class Owner : BrowserChromeOwnerUi {
        var context: Any = Any(); var interactive = true; var calls = 0; var accepts = true
        var throwsAfterEntry = false; var changesContext = false; var unknownNotices = 0; var reviewRevision = 0L
        override fun canInteract() = interactive
        override fun nativeState() = BrowserChromeState(context, "https://example.com", "Example", "", false, 0, true, true, 1, true, true, unknownNotices > 0, reviewRevision)
        override fun perform(context: Any, action: BrowserChromeAction, input: String?): Boolean {
            check(this.context == context); calls++
            if (throwsAfterEntry) throw IllegalStateException("Unknown after entry")
            if (changesContext) this.context = Any()
            return accepts
        }
        override fun unavailable() = Unit
        override fun outcomeUnknown() { unknownNotices++ }
    }
    private class Harness {
        val owner = Owner(); val messages = mutableListOf<JSONObject>()
        val endpoint = PocketWebBrowserChrome(owner, messages::add)
        init { endpoint.foreground(true); send("ready", "app.ready", JSONObject()); messages.clear() }
        fun state(): JSONObject { endpoint.refresh(force = true); return messages.last { it.optString("kind") == "state" }.getJSONObject("snapshot").getJSONObject("browserChrome") }
        fun request(id: String, action: String = "back"): String {
            val state = state()
            return envelope(id, "browserChrome.action", JSONObject().put("windowId", state.getString("windowId"))
                .put("generation", state.getLong("generation")).put("contextToken", state.getString("contextToken")).put("action", action))
        }
        private fun envelope(id: String, action: String, args: JSONObject) = JSONObject().put("v", 1).put("id", id)
            .put("documentId", "document").put("action", action).put("args", args).toString()
        fun send(id: String, action: String, args: JSONObject) { endpoint.captureDispatch(envelope(id, action, args)) {}.invoke() }
    }
    @Test fun staleQueuedNativeContextCannotPerformAGesture() {
        val h = Harness(); val queued = h.endpoint.captureDispatch(h.request("action")) {}
        h.owner.context = Any(); queued(); assertEquals(0, h.owner.calls)
    }
    @Test fun foregroundLossInvalidatesQueuedCommandsAndRequiresNewBinding() {
        val h = Harness(); val before = h.state(); val queued = h.endpoint.captureDispatch(h.request("action")) {}
        h.endpoint.foreground(false); h.endpoint.foreground(true); queued()
        assertEquals(0, h.owner.calls); assertFalse(h.state().getBoolean("ready"))
        assertNotEquals(before.getLong("generation"), h.state().getLong("generation"))
        assertNotEquals(before.getString("contextToken"), h.state().getString("contextToken"))
    }
    @Test fun rejectedActionsAreNotSuccessAndDuplicateIdsNeverReplay() {
        val h = Harness(); h.owner.accepts = false; val request = h.request("action")
        h.endpoint.captureDispatch(request) {}.invoke(); h.endpoint.captureDispatch(request) {}.invoke()
        assertEquals(1, h.owner.calls)
        assertTrue(h.messages.filter { it.optString("kind") == "result" }.all { !it.getBoolean("ok") })
        assertEquals(0, h.endpoint.savePresentationState().length())
    }
    @Test fun acceptedNavigationOnlyMeansRequestedAndNeverVerifiedLoaded() {
        val h = Harness(); h.endpoint.captureDispatch(h.request("action", "reload")) {}.invoke()
        val result = h.messages.last { it.optString("kind") == "result" }.getJSONObject("value")
        assertTrue(result.getBoolean("accepted")); assertFalse(result.getBoolean("verified"))
        assertEquals("navigation_requested", result.getString("status")); assertEquals(1, h.owner.calls)
    }
    @Test fun differentCommandIdDoesNotReuseAnOldPageContext() {
        val h = Harness(); h.owner.changesContext = true
        val first = h.request("first"); val second = JSONObject(first).put("id", "second").toString()
        h.endpoint.captureDispatch(first) {}.invoke(); h.endpoint.captureDispatch(second) {}.invoke()
        assertEquals(1, h.owner.calls)
        assertEquals("not_sent", h.messages.last { it.optString("id") == "second" }.getJSONObject("error").getString("code"))
    }
    @Test fun exceptionAfterEntryIsUnknownAndNeverRetried() {
        val h = Harness(); h.owner.throwsAfterEntry = true; val request = h.request("once")
        h.endpoint.captureDispatch(request) {}.invoke()
        val unknown = h.messages.last { it.optString("kind") == "result" }
        assertFalse(unknown.getBoolean("ok")); assertEquals("outcome_unknown", unknown.getJSONObject("error").getString("code"))
        h.endpoint.captureDispatch(request) {}.invoke(); assertEquals(1, h.owner.calls); assertEquals(1, h.owner.unknownNotices); assertTrue(h.state().getBoolean("navigationUnconfirmed"))
        h.endpoint.captureDispatch(h.request("different-navigation")) {}.invoke(); assertEquals(1, h.owner.calls)
        h.endpoint.foreground(false); h.endpoint.foreground(true); h.send("rebound", "app.ready", JSONObject())
        assertTrue(h.state().getBoolean("navigationUnconfirmed")); assertEquals(1, h.owner.unknownNotices)
    }
    @Test fun documentReplacementRetiresGenerationTokenAndOldCommands() {
        val h = Harness(); val original = h.state(); val old = h.request("old")
        h.endpoint.invalidatePresentation(documentReplaced = true)
        assertFalse(h.state().getBoolean("ready"))
        h.send("new-ready", "app.ready", JSONObject())
        val fresh = h.state(); assertTrue(fresh.getBoolean("ready"))
        assertTrue(fresh.getLong("generation") > original.getLong("generation"))
        assertNotEquals(original.getString("contextToken"), fresh.getString("contextToken"))
        h.endpoint.captureDispatch(old) {}.invoke(); assertEquals(0, h.owner.calls)
        val wrongDocument = JSONObject(h.request("wrong-document")).put("documentId", "other").toString()
        h.endpoint.captureDispatch(wrongDocument) {}.invoke(); assertEquals(0, h.owner.calls)
        h.endpoint.captureDispatch(h.request("fresh")) {}.invoke(); assertEquals(1, h.owner.calls)
    }
    @Test fun readyBindingCanSurviveAnUnrelatedWebsiteNavigationWhileQueued() {
        val h = Harness(); h.endpoint.invalidatePresentation(documentReplaced = true)
        val raw = JSONObject().put("v", 1).put("id", "ready-again").put("documentId", "document").put("action", "app.ready").toString()
        val queued = h.endpoint.captureDispatch(raw) {}; h.owner.context = Any(); queued()
        assertTrue(h.state().getBoolean("ready")); assertEquals(0, h.owner.calls)
    }

    @Test fun ordinaryStateAndDocumentRebindingNeverInventAnExplicitReview() {
        val h = Harness(); assertEquals(0L, h.state().getLong("navigationReviewRevision"))
        h.endpoint.captureDispatch(h.request("navigation", "reload")) {}.invoke()
        assertEquals(0L, h.state().getLong("navigationReviewRevision"))
        h.owner.reviewRevision = 4L // Native More-menu owner acknowledgement, not a JS command.
        assertEquals(4L, h.state().getLong("navigationReviewRevision"))
        h.owner.context = Any(); h.endpoint.refresh(force = true)
        h.endpoint.foreground(false); h.endpoint.foreground(true)
        h.send("rebound-after-review", "app.ready", JSONObject())
        assertEquals(4L, h.state().getLong("navigationReviewRevision"))
        assertEquals(0, h.endpoint.savePresentationState().length())
    }

}
