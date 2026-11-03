package dev.pocketengine.ui.web

import org.junit.Assert.*
import org.junit.Test

class PocketWebDocumentFenceTest {
    @Test fun tokenCannotBeUsedFromChildFrameOrForeignOrigin() {
        val fence = PocketWebDocumentFence { "native-token" }
        assertTrue(fence.beginDocument()); assertEquals("native-token", fence.loaded())
        assertNull(fence.capture(false, "https", "pocketengine.local", -1, "native-token", true))
        assertNull(fence.capture(true, "http", "pocketengine.local", -1, "native-token", true))
        assertNull(fence.capture(true, "https", "foreign.local", -1, "native-token", true))
        assertNull(fence.capture(true, "https", "pocketengine.local", 444, "native-token", true))
        assertNull(fence.capture(true, "https", "pocketengine.local", -1, "old-token", true))
        assertNull(fence.capture(true, "https", "pocketengine.local", -1, "native-token", false)) // posted while background
        val ticket = fence.capture(true, "https", "pocketengine.local", -1, "native-token", true)!!
        assertTrue(fence.accepts(ticket, "native-token", true))
        assertFalse(fence.accepts(ticket, "native-token", false))
    }
    @Test fun pauseRotatesTokenAndSecondNavigationInvalidatesQueuedCommandsAndLateFinish() {
        var serial=0; val fence = PocketWebDocumentFence { "token-${++serial}" }
        assertTrue(fence.beginDocument()); val first = fence.loaded()
        val ticket = fence.capture(true, "https", "pocketengine.local", -1, first, true)!!
        fence.pause(); assertFalse(fence.accepts(ticket, first, true))
        assertNull(fence.capture(true, "https", "pocketengine.local", -1, first, true))
        val second = fence.loaded(); assertNotEquals(first, second)
        assertNull(fence.capture(true, "https", "pocketengine.local", -1, first, true))
        val resumed = fence.capture(true, "https", "pocketengine.local", -1, second, true)!!
        assertTrue(fence.accepts(resumed, second, true))
        assertFalse(fence.beginDocument()) // includes reload of the same URL
        assertFalse(fence.accepts(resumed, second, true))
        assertNull(fence.capture(true, "https", "pocketengine.local", -1, second, true))
        assertThrows(IllegalStateException::class.java) { fence.loaded() } // late old finish
        fence.close()
    }
    @Test fun duplicateFinishCannotRotateTokenAndCloseCannotReopen() {
        var serial = 0; val fence = PocketWebDocumentFence { "token-${++serial}" }
        assertTrue(fence.beginDocument()); assertEquals("token-1", fence.loaded()); assertEquals("token-1", fence.loaded())
        fence.close(); assertFalse(fence.beginDocument()); assertThrows(IllegalStateException::class.java) { fence.loaded() }
        assertNull(fence.capture(true, "https", "pocketengine.local", -1, "token-1", true))
    }
    @Test fun pauseInvalidatesBothHostAndAlreadyQueuedBridgeCommands() {
        val doc = PocketWebDocumentFence { "token" }; val bridge = PocketWebCommandFence()
        assertTrue(doc.beginDocument()); doc.loaded()
        val hostQueued = doc.capture(true, "https", "pocketengine.local", -1, "token", true)!!
        val bridgeQueued = bridge.ticket()
        doc.pause(); bridge.invalidate()
        assertFalse(doc.accepts(hostQueued, "token", true))
        assertFalse(bridge.accepts(bridgeQueued))
        val current = bridge.ticket(); assertTrue(bridge.accepts(current))
    }
    @Test fun ownerWindowChangeAcrossBothQueuesRejectsOldTicketEvenAfterReturningToA() {
        val bridge = PocketWebCommandFence()
        val aBefore = PocketWebWindowTicket(bridge.ticket(), Pair(Triple(1L, 1, 1L), 1L))
        assertTrue(aBefore.accepts(bridge.ticket(), Pair(Triple(1L, 1, 1L), 1L)))
        // Controller increments its owner epoch on both A -> B and B -> A.
        assertFalse(aBefore.accepts(bridge.ticket(), Pair(Triple(2L, 1, 1L), 1L)))
        assertFalse(aBefore.accepts(bridge.ticket(), Pair(Triple(3L, 1, 1L), 1L)))
        val aAfter = PocketWebWindowTicket(bridge.ticket(), Pair(Triple(3L, 1, 1L), 1L))
        assertTrue(aAfter.accepts(bridge.ticket(), Pair(Triple(3L, 1, 1L), 1L)))
        // A change after outer delivery must also fail the inner queue.
        bridge.invalidate()
        assertFalse(aAfter.accepts(bridge.ticket(), Pair(Triple(3L, 1, 1L), 1L)))
        assertFalse(aAfter.accepts(aAfter.bridge, Pair(Triple(3L, 2, 1L), 1L)))
        assertFalse(aAfter.accepts(aAfter.bridge, Pair(Triple(3L, 1, 2L), 1L)))
        // Reopening the same file also increments the file-window generation.
        assertFalse(aAfter.accepts(aAfter.bridge, Pair(Triple(3L, 1, 1L), 2L)))
    }
}
