package dev.pocketengine.ui

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillArchiveStateTest {
    private val oldDigest = "a".repeat(64)
    private val incomingDigest = "b".repeat(64)
    private fun current() = JSONObject().put("id", "skill-owner").put("name", "review").put("revision", 4).put("digest", oldDigest)
    private fun inspected(kind: String = "changed-content") = JSONObject().put("package", JSONObject().put("digest", incomingDigest)).put("comparison", JSONObject().put("kind", kind).put("id", "skill-owner").put("revision", 4).put("expectedCurrentDigest", oldDigest).put("expectedExecutionGeneration", 0))

    @Test fun updateAndRepairPreserveExactInspectedRevisionAndDigest() {
        for (kind in listOf("same-content", "changed-content", "repair")) {
            val options = skillImportOptions(inspected(kind), "skill-owner", " Owner chosen file ", current())
            assertEquals("Owner chosen file", options.getString("source"))
            assertEquals("skill-owner", options.getString("id"))
            assertEquals(4, options.getInt("revision"))
            assertEquals(oldDigest, options.getString("expectedCurrentDigest"))
            assertEquals(incomingDigest, options.getString("inspectedDigest"))
            assertFalse(options.has("enabled")); assertFalse(options.has("requiredCapabilities")); assertFalse(options.has("expiresAt"))
        }
    }

    @Test fun changedDeletedOrDifferentTargetRequiresNewInspection() {
        assertFalse(skillInspectionIsCurrent(inspected(), "skill-owner", current().put("executionGeneration", 2)))
        assertFalse(skillInspectionIsCurrent(inspected(), "skill-owner", current().put("revision", 5)))
        assertFalse(skillInspectionIsCurrent(inspected(), "skill-owner", current().put("digest", incomingDigest)))
        assertFalse(skillInspectionIsCurrent(inspected(), "other", current()))
        assertFalse(skillInspectionIsCurrent(inspected(), "skill-owner", null))
        assertFalse(skillInspectionIsCurrent(inspected(), null, null))
        try { skillImportOptions(inspected(), "skill-owner", "file", current().put("revision", 5)); fail("stale import must fail") } catch (_: IllegalStateException) { }
    }

    @Test fun newImportCannotSmuggleHostScopeApprovalOrTargetFromPackage() {
        val inspection = JSONObject().put("package", JSONObject().put("digest", incomingDigest).put("scope", JSONObject().put("ownerId", "attacker")).put("enabled", true)).put("comparison", JSONObject().put("kind", "new"))
        val options = skillImportOptions(inspection, null, "selected file", null)
        assertFalse(options.has("id")); assertFalse(options.has("scope")); assertFalse(options.has("enabled"))
        assertEquals("owner", skillOwnerPrivateScope().getString("ownerId"))
        assertEquals("private", skillOwnerPrivateScope().getString("spaceId"))
    }

    @Test fun exportPinRequiresCompleteCurrentRevisionAndSafePickerSuggestion() {
        val pin = skillExportPin(current().put("name", "../review"))
        assertEquals(4, pin.revision); assertEquals(oldDigest, pin.digest); assertFalse(pin.fileName.contains("/"))
        try { skillExportPin(current().put("revision", 0)); fail("cannot export unknown revision") } catch (_: IllegalArgumentException) { }
    }

    @Test fun singleDocumentPinRetainsTheReviewedPackageAndBodyIdentity() {
        val checked = current().put("singleDocumentOnly", true).put("documentSha256", incomingDigest)
        val pin = skillDocumentExportPin(checked)
        assertEquals("SKILL.md", pin.fileName); assertEquals(oldDigest, pin.digest); assertEquals(incomingDigest, pin.documentSha256); assertEquals(4, pin.revision)
        assertThrows(IllegalArgumentException::class.java) { skillDocumentExportPin(JSONObject(checked.toString()).put("singleDocumentOnly", false)) }
        assertThrows(IllegalArgumentException::class.java) { skillDocumentExportPin(JSONObject(checked.toString()).put("documentSha256", "missing")) }
    }

    @Test fun exportReceiptIsReturnedOnlyAfterFlushAndClose() {
        val events = mutableListOf<String>()
        val output = object : ByteArrayOutputStream() {
            override fun flush() { events.add("flush") }
            override fun close() { events.add("close") }
        }
        val receipt = writeSkillArchive(output) { it.write(byteArrayOf(1, 2)); events.add("write"); JSONObject().put("archiveBytes", 2) }
        assertEquals(listOf("write", "flush", "close"), events)
        assertEquals(2, receipt.getInt("archiveBytes"))
    }

    @Test fun providerFlushCloseAndWriteFailuresNeverReturnSuccessReceipt() {
        for (stage in listOf("write", "flush", "close")) {
            var closed = false
            val output = object : ByteArrayOutputStream() {
                override fun flush() { if (stage == "flush") throw IOException("flush failed") }
                override fun close() { closed = true; if (stage == "close") throw IOException("close failed") }
            }
            try {
                writeSkillArchive(output) { if (stage == "write") throw IOException("write failed"); JSONObject().put("saved", true) }
                fail("$stage failure must not produce success")
            } catch (_: IOException) { }
            assertTrue(closed)
        }
    }
}
