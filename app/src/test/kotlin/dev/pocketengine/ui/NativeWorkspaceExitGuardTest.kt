package dev.pocketengine.ui

import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceExitGuardTest {
    @Test fun switchIsForbiddenWhileAnyOwnerLayerOrWorkRemains() {
        assertTrue(nativeWorkspaceExitAllowed(false, false))
        assertFalse(nativeWorkspaceExitAllowed(true, false))
        assertFalse(nativeWorkspaceExitAllowed(false, true))
        assertFalse(nativeWorkspaceExitAllowed(true, true))
    }
    @Test fun pendingWebCommandOrModelSelectionBlocksSwitch() {
        fun allowed(pending: Boolean, modelSelecting: Boolean) =
            nativeWorkspaceExitAllowed(false, pending || modelSelecting)
        assertTrue(allowed(false, false))
        assertFalse(allowed(true, false)); assertFalse(allowed(false, true))
    }
    @Test fun eachVoiceLeaseReviewOrUnknownStateBlocksSwitch() {
        assertFalse(nativeVoiceBusy(false, false, false, false, false, false, false))
        repeat(7) { blocked ->
            val inputs = BooleanArray(7) { it == blocked }
            assertTrue(nativeVoiceBusy(inputs[0], inputs[1], inputs[2], inputs[3], inputs[4], inputs[5], inputs[6]))
        }
    }
    @Test fun busyComposerConsumesBackWithoutClosingOrFallingThrough() {
        var closed = 0
        nativeBusyBack(true) { closed++ }
        assertEquals(0, closed)
        nativeBusyBack(false) { closed++ }
        assertEquals(1, closed)
    }
}
