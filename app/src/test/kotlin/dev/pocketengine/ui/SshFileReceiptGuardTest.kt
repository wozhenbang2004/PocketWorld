package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshFileReceiptGuardTest {
    private fun input(request: String) = JSONObject().put("_requestId", request).put("profileId", "host").put("path", "src/main.py").put("expectedVersion", "sha256:" + "a".repeat(64))
    private fun response() = JSONObject().put("profileId", "host").put("path", "src/main.py").put("status", "completed")

    @Test fun reopeningTheSamePathCannotAcceptAPreviousReadOrLateWrite() {
        val prior = response()
        assertNull(SshFileReceiptGuard.result(prior, input("old-open"), "new-open", "host", "src/main.py"))
        assertNull(SshFileReceiptGuard.result(prior, input("write-old"), "write-new", "host", "src/main.py"))
        assertSame(prior, SshFileReceiptGuard.result(prior, input("current"), "current", "host", "src/main.py"))
        assertNull(SshFileReceiptGuard.result(prior, input("current"), "current", "other-host", "src/main.py"))
        assertNull(SshFileReceiptGuard.result(prior, input("current"), "current", "host", "other.py"))
        assertNull(SshFileReceiptGuard.result(prior, input("current").put("_failed", true), "current", "host", "src/main.py"))
    }

    @Test fun reviewedVersionAndWrittenBytesAreBothRequiredBeforeMarkingTheDraftSaved() {
        val accepted = response().put("accepted", true).put("version", "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        assertTrue(SshFileReceiptGuard.saved(accepted, "hello"))
        assertFalse(SshFileReceiptGuard.saved(accepted, "a different draft"))
        assertNull(SshFileReceiptGuard.result(accepted, input("current"), "current", "host", "src/main.py", "sha256:" + "b".repeat(64)))
        accepted.put("status", "unknown")
        assertFalse(SshFileReceiptGuard.saved(accepted, "hello"))
    }
}
