package dev.pocketengine.ui

import org.junit.Assert.*
import org.junit.Test

class PluginPackageTransferPageTest {
    @Test fun requiresExactPreviewExplicitOwnerReviewAndNoPendingSubmission() {
        val digest = "a".repeat(64)
        assertTrue(PluginPackageTransferGuard.canImport(digest, digest, false, true, false))
        assertFalse(PluginPackageTransferGuard.canImport(digest, "b".repeat(64), false, true, false))
        assertFalse(PluginPackageTransferGuard.canImport(digest, digest, false, false, false))
        assertFalse(PluginPackageTransferGuard.canImport(digest, digest, true, true, false))
        assertFalse(PluginPackageTransferGuard.canImport(digest, digest, false, true, true))
        assertFalse(PluginPackageTransferGuard.canImport(null, null, false, true, false))
    }
}
