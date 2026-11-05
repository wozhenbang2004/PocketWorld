package dev.pocketengine.ui

import org.json.JSONObject

internal fun skillSourceClassification(value: String): String = when (value) {
    "current" -> "内容与上次来源一致"
    "upstream_changed" -> "上游有更新，本地未修改"
    "local_changed" -> "本地已修改，上游内容未变"
    "both_changed" -> "本地和上游都有改动"
    "untracked" -> "尚未建立这份来源的本地基线"
    else -> "暂不能确认变化"
}

internal fun skillSourceReviewMatches(view: JSONObject, target: JSONObject?): Boolean {
    val id = view.optString("targetId")
    if (id.isEmpty()) return target == null && view.optJSONObject("comparison")?.optString("kind") == "new"
    val comparison = view.optJSONObject("comparison") ?: return false
    return target != null && target.optString("id") == id && comparison.optString("id") == id &&
        comparison.optInt("revision", -1) == target.optInt("revision", -2) &&
        comparison.optString("expectedCurrentDigest") == target.optString("digest")
}

/** Native owner surface. A preview is an opaque Book object, not a JSON authority token. */
