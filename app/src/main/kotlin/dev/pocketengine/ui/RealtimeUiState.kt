package dev.pocketengine.ui
import org.json.JSONObject
/** Prefer native revision identity; late feature batches cannot reopen old capture/session UI. */
internal fun mergeRealtimeSnapshot(current: JSONObject, incoming: JSONObject): JSONObject {
    val old = current.opt("snapshotRevision")
    val next = incoming.opt("snapshotRevision")
    if (old is Number && (next !is Number || next.toLong() < old.toLong())) return current
    if (incoming.has("snapshotRevision") && (next !is Number || next.toLong() < 0)) return current
    return JSONObject(incoming.toString())
}
