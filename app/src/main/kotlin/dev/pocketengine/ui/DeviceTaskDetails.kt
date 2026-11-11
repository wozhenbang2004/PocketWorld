package dev.pocketengine.ui

import org.json.JSONObject

private fun deviceWorkspaceNavigationMode(task: UiTask): String {
    if (task.raw.optString("actorId") != "owner" || task.raw.optString("spaceId") != "private" || task.raw.has("origin")) return ""
    val view = task.raw.optJSONObject("deviceWorkspaceNavigation") ?: return ""
    if (view.optString("format") != "pocketworld.device-workspace-navigation.v1" || view.opt("readOnly") != true || view.opt("executionAuthorized") != false) return ""
    return when (view.optString("mode")) {
        "manual_phone" -> if (task.raw.optString("kind") == "tool" && task.raw.optString("nativeExecutionMode") == "OWNER_PHONE_CONTROL") "manual_phone" else ""
        "agent_device" -> if (task.raw.optString("kind") == "assistant" && task.raw.optString("executionMode") == "capabilities") "agent_device" else ""
        else -> ""
    }
}

internal fun isOwnerPhoneControlTask(task: UiTask): Boolean =
    deviceWorkspaceNavigationMode(task) == "manual_phone" ||
    task.raw.optJSONObject("ownerPhoneControl")?.optString("format") == "pocketworld.owner-phone-control.v1" &&
        task.raw.optString("kind") == "tool" && task.raw.optString("nativeExecutionMode") == "OWNER_PHONE_CONTROL" &&
        task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private" && !task.raw.has("origin")

internal fun isDeviceWorkspaceTask(task: UiTask): Boolean =
    deviceWorkspaceNavigationMode(task).isNotEmpty() || isOwnerPhoneControlTask(task) ||
    task.raw.optJSONObject("ownerDeviceWork")?.optString("format") == "pocketworld.owner-device-work.v1" &&
        task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private" ||
    task.raw.optJSONObject("origin")?.optString("kind") == "paired_device" ||
    task.raw.optJSONArray("operations").objects().any { it.optString("tool").startsWith("device_") }

internal fun workspaceTaskLinks(snapshot: JSONObject?): List<JSONObject> {
    val value = snapshot?.opt("taskLinks")
    return when (value) { is JSONObject -> value.optJSONArray("links").objects(); is org.json.JSONArray -> value.objects(); else -> emptyList() }
}
