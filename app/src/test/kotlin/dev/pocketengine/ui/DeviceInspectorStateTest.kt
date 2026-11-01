package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceInspectorStateTest {
    @Test fun letterboxAreaDoesNotBecomeDeviceInput() {
        assertNull(mapFramePoint(25f, 100f, 200, 200, 100, 200))
        assertNull(mapFramePoint(175f, 100f, 200, 200, 100, 200))
        assertEquals(FramePoint(500, 500), mapFramePoint(100f, 100f, 200, 200, 100, 200))
    }

    @Test fun imageBoundsMapToNormalizedDeviceCoordinates() {
        assertEquals(FramePoint(0, 0), mapFramePoint(50f, 0f, 200, 200, 100, 200))
        assertEquals(FramePoint(1000, 1000), mapFramePoint(150f, 200f, 200, 200, 100, 200))
        assertEquals(FramePoint(250, 750), mapFramePoint(25f, 150f, 100, 200, 720, 1440))
    }

    @Test fun invalidFrameDimensionsNeverProduceInput() {
        assertNull(mapFramePoint(1f, 1f, 0, 100, 100, 100))
        assertNull(mapFramePoint(Float.NaN, 1f, 100, 100, 100, 100))
        assertNull(mapFramePoint(1f, 1f, 100, 100, -1, 100))
    }

    @Test fun onlyPendingHostApprovalsAreActionable() {
        val raw = JSONObject().put("id", "task").put("approvals", JSONArray().put(JSONObject().put("approvalId", "pending").put("status", "pending")).put(JSONObject().put("approvalId", "denied").put("status", "denied")).put(JSONObject().put("approvalId", "approved").put("state", "approved")))
        assertEquals(listOf("pending"), pendingCapabilityApprovals(taskFromJson(raw)).map { it.getString("approvalId") })
    }

    private fun phone(status: String = "paused", mode: String = "background", id: String = "task") = taskFromJson(JSONObject().put("id", id).put("kind", "phone").put("goal", "原手机目标").put("executionMode", mode).put("status", status))
    private fun frame(at: Long = 1000) = JSONObject().put("ok", true).put("observationId", "native-frame").put("displayId", 8).put("sessionId", "native-session").put("executionMode", "background").put("observedAtUptimeMs", at)
    @Test fun manualPresentationNeedsOriginalNativeGrantAndBoundFreshFrame() {
        assertTrue(phoneInspectionPresentation(phone(), frame(), true, false, false, "task", 2000).manual)
        assertFalse(phoneInspectionPresentation(phone(), frame().put("sessionId", ""), true, false, false, "task", 2000).manual)
        assertFalse(phoneInspectionPresentation(phone(), frame().put("displayId", 0), true, false, false, "task", 2000).manual)
        assertFalse(phoneInspectionPresentation(phone(), frame(), false, false, false, "task", 2000).manual)
    }
    @Test fun elapsedExpiryOrUnverifiableClockCannotPresentActionableOldFrame() {
        assertTrue(phoneInspectionPresentation(phone(), frame(), true, false, false, "task", 121000).manual)
        val stale = phoneInspectionPresentation(phone(), frame(), true, false, false, "task", 121001)
        assertFalse(stale.manual); assertFalse(stale.frameFresh); assertTrue(stale.message.contains("过期"))
        assertFalse(phoneInspectionPresentation(phone(), frame(5000), true, false, false, "task", 2000).manual)
        assertFalse(phoneInspectionPresentation(phone(), frame().also { it.remove("observedAtUptimeMs") }, true, false, false, "task", 2000).manual)
    }
    @Test fun unknownOrInFlightActionKeepsFrameReadonlyAndNeverPresentsContinue() {
        val unknown = phoneInspectionPresentation(phone(), frame(), true, false, true, "task", 2000)
        assertFalse(unknown.manual); assertFalse(unknown.canTakeOver); assertTrue(unknown.message.contains("结果待核对"))
        assertFalse(phoneInspectionPresentation(phone(), frame(), true, true, false, "task", 2000).manual)
    }
    @Test fun mainScreenIsNotRelabelledOrOfferedAsIndependentDisplay() {
        val main = phoneInspectionPresentation(phone(mode = "main_screen"), frame(), true, false, false, "task", 2000)
        assertEquals("手机主屏", main.location); assertFalse(main.manual); assertFalse(main.canTakeOver); assertTrue(main.message.contains("主屏"))
    }
    @Test fun terminalMissingOrOtherActiveOwnerNeverOffersTakeover() {
        for (status in listOf("completed", "failed", "cancelled")) assertFalse(phoneInspectionPresentation(phone(status), frame(), true, false, false, "task", 2000).canTakeOver)
        assertFalse(phoneInspectionPresentation(null, frame(), true, false, false, null, 2000).canTakeOver)
        assertFalse(phoneInspectionPresentation(phone(), frame(), false, false, false, "different-original-task", 2000).canTakeOver)
    }
    @Test fun runningSnapshotIsHonestReadonlyNotLiveVideoOrManualAuthority() {
        val value = phoneInspectionPresentation(phone("running"), frame(), true, false, false, "task", 2000)
        assertFalse(value.manual); assertTrue(value.canTakeOver); assertTrue(value.message.contains("仅查看")); assertTrue(value.message.contains("不是实时视频"))
    }
}
