package dev.pocketengine.ui.web

import org.json.JSONObject

/** Native-read IDs allow opening old rows outside the current summary cache.
 * They grant only a fresh owner read, never a task decision or execution. */
internal class PocketWebTaskHistory {
    private val readIds = linkedSetOf<String>()

    fun remember(page: JSONObject) {
        val rows = page.optJSONArray("tasks") ?: return
        require(rows.length() <= 40 && page.opt("auditBodyIncluded") == false) { "原任务摘要窗口不可核对" }
        val ids = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.opt("id") as? String ?: error("原任务身份缺失")
            require(id.isNotBlank() && id.length <= 240) { "原任务身份无效" }
            id
        }
        ids.forEach { readIds.remove(it); readIds.add(it) }
        while (readIds.size > 256) readIds.remove(readIds.first())
    }

    fun contains(id: String): Boolean = id in readIds
    fun clear() = readIds.clear()
}
