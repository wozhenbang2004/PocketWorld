package dev.pocketengine.ui

import org.json.JSONObject

internal fun isDeviceWorkspaceTask(task: UiTask): Boolean = task.raw.optJSONObject("origin")?.optString("kind") == "paired_device" ||
    task.raw.optJSONArray("operations").objects().any { it.optString("tool").startsWith("device_") }

internal fun workspaceTaskLinks(snapshot: JSONObject?): List<JSONObject> {
    val value = snapshot?.opt("taskLinks")
    return when (value) { is JSONObject -> value.optJSONArray("links").objects(); is org.json.JSONArray -> value.objects(); else -> emptyList() }
}
