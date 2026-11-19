package dev.pocketengine.ui.web

import android.content.Intent
import android.provider.Settings
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Same native, read-only preflight for an explicit composer goal and a formally proposed private-chat goal. */
internal class PrivatePhoneStartConditions(private val controller: PocketWorldController) {
    // A preflight is not an observation. Publish no session ids, screen data or operation grant.
    fun read(mode: String): JSONObject {
        val source = runCatching { controller.reviewedPhoneConditions() }.getOrNull()
        val notification = source?.optJSONObject("taskNotificationsForStart")
        val notificationIds = checks(notification).map { it.optString("id") }.toSet()
        val screen = source?.optJSONObject(if (mode == "main_screen") "mainPreparation" else "backgroundPreparation")
        return JSONObject().put("notification", condition(notification, "notifications"))
            .put("screen", condition(screen, "accessibility", notificationIds))
            .put("capacity", capacity(mode))
    }

    fun capacity(mode: String): JSONObject = runCatching { controller.reviewedPhoneCapacity(mode) }.getOrElse {
        JSONObject().put("state", "unknown").put("mode", mode).put("message", "本机执行位置暂时无法核实，请重新核对。")
    }

    private fun checks(group: JSONObject?): List<JSONObject> {
        val items = group?.optJSONArray("checks") ?: return emptyList()
        return (0 until items.length()).mapNotNull(items::optJSONObject)
    }

    private fun condition(group: JSONObject?, settings: String, excludedIds: Set<String> = emptySet()): JSONObject {
        fun unknown() = JSONObject().put("state", "unknown")
        // backgroundPreparation also contains the notification gate. Report each prerequisite once.
        val rows = checks(group).filterNot { it.optString("id") in excludedIds }
        if (rows.isEmpty()) return unknown()
        val issue = (if (settings == "notifications") rows.firstOrNull { it.optString("state") == "blocked" } else null)
            ?: rows.firstOrNull { it.optString("state") != "ready" }
        if (issue == null) return if (excludedIds.isNotEmpty() || group?.optBoolean("ready") == true && group.optString("state") == "ready")
            JSONObject().put("state", "ready") else unknown()
        val state = if (issue.optString("state") == "blocked") "blocked" else "unknown"
        return JSONObject().put("state", state).put("message", issue.optString("message")).also { view ->
            val action = issue.optString("action")
            // Keep the native non-authorizing route for this exact failed background check.
            // openReviewedSettings still accepts only the two Android settings targets.
            val place = settings == "accessibility" && when (issue.optString("id")) {
                "backgroundConnected", "backgroundClean" -> action == "background_setup"
                "backgroundSupported" -> action == "main_screen_choice"
                else -> false
            }
            if (state == "blocked" && (action == settings || place)) view.put("settings", action)
        }
    }

    // Open a system page on a deliberate click only; never grant or turn on its setting.
    fun openSetting(conditions: JSONObject, target: String): JSONObject {
        val notification = conditions.getJSONObject("notification")
        val issue = if (notification.optString("state") != "ready") notification else conditions.getJSONObject("screen")
        check(issue.optString("settings") == target && issue.optString("state") == "blocked") { "请从原任务当前缺少的条件打开设置" }
        val activity = controller.webPresentationActivity()
        when (target) {
            "notifications" -> activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName))
            "accessibility" -> controller.openAccessibility()
            else -> error("本次核对不支持这个设置入口")
        }
        return JSONObject().put("status","settings_opened")
    }
    companion object {
        fun canSubmit(shown: JSONObject): Boolean = listOf("notification","capacity").all {
            shown.optJSONObject(it)?.optString("state") == "ready"
        }
    }
}
