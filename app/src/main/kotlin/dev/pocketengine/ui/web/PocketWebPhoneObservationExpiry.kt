package dev.pocketengine.ui.web

import android.os.Handler
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Main thread only. Retire a fact that was ALREADY shown; do not re-read a task, app or screen.
 * The native port supplies the actual uptime cutoff. No polling, historical deletion or action. */
internal class PocketWebPhoneObservationExpiry(private val main: Handler, private val expired: (Long, JSONArray) -> Unit) {
    internal data class Item(val task: String, val run: String, val epoch: Int, val until: Long)
    private var held = emptyList<Item>()
    private var version = -1L
    private val timeout = Runnable { retire() }

    /** Consume the native-only marker before serialization. Even the monotonic device uptime stays off the wire. */
    fun take(snapshot: JSONObject): List<Item> {
        val rows = snapshot.optJSONObject("state")?.optJSONArray("livePrivatePhones") ?: return emptyList()
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val obs = row.optJSONObject("lastObservation") ?: continue
                val mark = obs.remove("ownerDisplayExpiryUptime")
                val until = if (mark is Long || mark is Int) (mark as Number).toLong() else continue
                val id = row.opt("taskId") as? String ?: continue
                val run = row.opt("runId") as? String ?: continue
                val epoch = row.opt("epoch")
                if (until>0 && row.opt("surfaceState")=="bound" && id.length in 1..200 && run.length in 1..200 &&
                    (epoch is Int || epoch is Long) && (epoch as Number).toLong() in 1..Int.MAX_VALUE.toLong()) add(Item(id,run,epoch.toInt(),until))
            }
        }
    }
    fun bind(revision: Long, source: List<Item>) {
        if (version == revision && held == source) return
        main.removeCallbacks(timeout); version = revision; held = source
        arm()
    }
    fun clear() { main.removeCallbacks(timeout); version = -1; held = emptyList() }
    private fun arm() { main.removeCallbacks(timeout); held.minOfOrNull { it.until }?.let { main.postAtTime(timeout,it) } }
    private fun retire() {
        val now = SystemClock.uptimeMillis()
        val (due,remaining) = held.partition { it.until <= now }; held = remaining
        if (due.isNotEmpty()) {
            val ids=JSONArray()
            due.forEach { ids.put(JSONObject().put("taskId",it.task).put("runId",it.run).put("epoch",it.epoch)) }
            expired(version,ids)
        }
        arm()
    }
}
