package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SshUiStateTest {
    @Test fun terminalIdentityNeedsAllHostBindings() {
        val value = JSONObject().put("terminalId", "terminal").put("hostTaskId", "task").put("runId", "run").put("profileId", "profile")
        val binding = sshTerminalBinding(value)!!
        assertEquals("task", binding.payload().getString("hostTaskId"))
        assertEquals("run", binding.payload().getString("runId"))
        assertNull(sshTerminalBinding(JSONObject(value.toString()).also { it.remove("runId") }))
    }

    @Test fun partialUtf8PacketsDoNotCorruptChineseOutput() {
        val bytes = "你好".toByteArray(Charsets.UTF_8)
        val buffer = PtyTextBuffer()
        assertEquals("", buffer.append(Base64.getEncoder().encodeToString(bytes.copyOfRange(0, 2)), false, false))
        assertEquals("你好", buffer.append(Base64.getEncoder().encodeToString(bytes.copyOfRange(2, bytes.size)), false, false))
    }

    @Test fun terminalRecordIsBoundedAndDoesNotInterpretAnsi() {
        val buffer = PtyTextBuffer(20)
        val text = buffer.append(Base64.getEncoder().encodeToString("\u001b[31mred\u001b[0m".toByteArray()), false, true)
        assertEquals("red", text)
        assertTrue(buffer.append(Base64.getEncoder().encodeToString("x".repeat(100).toByteArray()), false, true).length <= 20)
    }

    @Test fun childFilePathsCannotBecomeParentOrAbsolutePaths() {
        assertEquals("src/你好.txt", sshChildPath("src", "你好.txt"))
        assertNull(sshChildPath("src", "../secret"))
        assertNull(sshChildPath("../src", "file"))
        assertNull(sshChildPath("/etc", "file"))
    }

    @Test fun oldReconciliationCannotUnlockAnotherUnknownPtyInput() {
        val task = taskFromJson(JSONObject().put("operations", JSONArray().put(JSONObject().put("operationId", "old").put("state", "resolved").put("receipt", JSONObject().put("source", "owner_reconciliation")))))
        assertTrue(ownerReviewedPtyInput(JSONObject().put("hostOperationId", "old"), task))
        assertFalse(ownerReviewedPtyInput(JSONObject().put("hostOperationId", "new"), task))
        assertFalse(ownerReviewedPtyInput(JSONObject(), task))
    }
}
