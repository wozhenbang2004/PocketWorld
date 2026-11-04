package dev.pocketengine.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SkillDocumentIntentsTest {
    private fun selected(value: String = "content://synthetic.provider/selected/SKILL.md") = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(value), "text/markdown")

    @Test fun receivingOnlyRetainsAUriAndBodyFreeRequestUntilExplicitOwnerInspection() {
        val host = SkillDocumentIntents()
        // No provider exists for this URI. Receiving must not try to resolve or read it.
        assertTrue(host.acceptView(selected()))
        val metadata = host.snapshot(); val request = metadata.getString("requestId")
        assertTrue(metadata.getBoolean("pending")); assertFalse(metadata.getBoolean("bodyIncluded")); assertFalse(metadata.getBoolean("imported"))
        assertFalse(metadata.toString().contains("synthetic.provider")); assertFalse(metadata.has("manifest"))
        assertEquals(selected().data, host.requireOriginal(request).uri)
        host.close(); assertFalse(host.isCurrent(request)); assertThrows(SecurityException::class.java) { host.requireOriginal(request) }
    }

    @Test fun pendingAndClosedWindowsRejectReplacementAndLateDiscardCannotClearANewRequest() {
        val host = SkillDocumentIntents(); host.acceptView(selected()); val old = host.pending!!.requestId
        assertThrows(IllegalStateException::class.java) { host.acceptView(selected("content://synthetic.provider/another")) }
        assertTrue(host.isCurrent(old)); host.discard(old); host.acceptView(selected("content://synthetic.provider/another")); val current = host.pending!!.requestId
        assertNotEquals(old, current); host.discard(old); assertTrue(host.isCurrent(current)); host.close()
        assertThrows(IllegalStateException::class.java) { host.acceptView(selected()) }
    }

    @Test fun receivingDoesNotAdmitFileNetworkOrMalformedAuthorityUris() {
        for (uri in listOf("file:///tmp/SKILL.md", "https://example.test/SKILL.md", "content:///SKILL.md", "content://user@synthetic.provider/SKILL.md", "content://synthetic.provider/SKILL.md#other")) {
            val host = SkillDocumentIntents(); assertThrows(IllegalArgumentException::class.java) { host.acceptView(selected(uri)) }; assertNull(host.pending)
        }
        assertFalse(SkillDocumentIntents().acceptView(Intent(Intent.ACTION_SEND)))
    }
}
