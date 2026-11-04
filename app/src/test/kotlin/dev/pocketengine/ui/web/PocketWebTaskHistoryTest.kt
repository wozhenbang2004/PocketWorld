package dev.pocketengine.ui.web

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketWebTaskHistoryTest {
    private fun page(vararg ids: String) = JSONObject().put("auditBodyIncluded", false)
        .put("tasks", JSONArray().also { rows -> ids.forEach { rows.put(JSONObject().put("id", it)) } })

    @Test fun openingRequiresAnActuallyReadRowAndPresentationInvalidationRetiresIt() {
        val history = PocketWebTaskHistory()
        assertFalse(history.contains("old-task"))
        history.remember(page("old-task", "accounting-row"))
        assertTrue(history.contains("old-task"))
        assertFalse(history.contains("unread-task"))
        history.clear()
        assertFalse(history.contains("old-task"))
    }

    @Test fun invalidPageCannotRegisterItsEarlierValidRows() {
        val history = PocketWebTaskHistory()
        try { history.remember(page("otherwise-valid", "")) } catch (_: IllegalArgumentException) { }
        assertFalse(history.contains("otherwise-valid"))
    }

    @Test fun oldNavigationIdsAreBoundedAndRereadingRetainsTheSelectedRow() {
        val history = PocketWebTaskHistory()
        for (index in 0 until 256) history.remember(page("task-$index"))
        history.remember(page("task-0", "task-256"))
        assertTrue(history.contains("task-0"))
        assertTrue(history.contains("task-256"))
        assertFalse(history.contains("task-1"))
    }
}
