package dev.pocketengine.ui

import org.json.JSONObject

internal data class PluginPreviewDraft(val requestId: String, val manifestText: String, val pluginId: String)

internal data class PluginRestoreDraft(val pluginId: String, val targetInstallationId: String, val expectedInstallationId: String, val version: String, val expectedStateRevision: Long, val requestId: String)

private fun pluginRevision(value: Any?): Long? = if (value !is Number) null else try {
    java.math.BigDecimal(value.toString()).longValueExact().takeIf { it >= 0 }
} catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }

/** Local draft + exact native request identity, never a previous plugin's global result. */

internal fun reviewedPluginPreview(draft: PluginPreviewDraft?, currentText: String, metadata: JSONObject?, result: JSONObject?): JSONObject? {
    if (draft == null || metadata?.optString("_requestId") != draft.requestId || currentText.trim() != draft.manifestText || result == null) return null
    if (result.optString("id") != draft.pluginId || result.opt("expectedInstallationId") !is String || pluginRevision(result.opt("expectedStateRevision")) == null) return null
    val manifest = result.optJSONObject("manifest") ?: return null
    if (manifest.optString("id") != draft.pluginId || manifest.optString("version") != result.optString("targetVersion")) return null
    return result
}

internal fun pluginInstallPayload(draft: PluginPreviewDraft?, currentText: String, metadata: JSONObject?, result: JSONObject?, token: String): JSONObject {
    val reviewed = reviewedPluginPreview(draft, currentText, metadata, result) ?: throw IllegalStateException("声明或预览已变化，请重新检查差异")
    return JSONObject().put("manifest", JSONObject(reviewed.getJSONObject("manifest").toString())).put("expectedInstallationId", reviewed.getString("expectedInstallationId")).put("expectedStateRevision", reviewed.getLong("expectedStateRevision")).also { if (token.isNotEmpty()) it.put("token", token) }
}

internal fun reviewedPluginHistory(id: String?, requestId: String?, metadata: JSONObject?, result: JSONObject?): JSONObject? {
    if (id.isNullOrBlank() || requestId.isNullOrBlank() || metadata?.optString("_requestId") != requestId || metadata.optString("id") != id || result?.optString("id") != id) return null
    if (result.optString("currentInstallationId").isBlank() || pluginRevision(result.opt("currentStateRevision")) == null || result.optJSONArray("versions") == null) return null
    return result
}

internal fun reviewedPluginRestorePreview(draft: PluginRestoreDraft?, metadata: JSONObject?, result: JSONObject?): JSONObject? {
    if (draft == null || metadata?.optString("_requestId") != draft.requestId || metadata.optString("id") != draft.pluginId || metadata.optString("targetInstallationId") != draft.targetInstallationId || result == null) return null
    if (result.optString("id") != draft.pluginId || result.optString("targetInstallationId") != draft.targetInstallationId || result.optString("expectedInstallationId") != draft.expectedInstallationId || pluginRevision(result.opt("expectedStateRevision")) != draft.expectedStateRevision || result.optBoolean("restoration") != true) return null
    val manifest = result.optJSONObject("manifest") ?: return null
    if (manifest.optString("id") != draft.pluginId || manifest.optString("version") != draft.version || result.optString("targetVersion") != draft.version) return null
    return result
}

internal fun pluginRestorePayload(draft: PluginRestoreDraft, currentInstallationId: String, currentStateRevision: Long, metadata: JSONObject?, result: JSONObject?, token: String): JSONObject {
    if (draft.expectedInstallationId.isBlank() || draft.targetInstallationId.isBlank() || draft.expectedInstallationId != currentInstallationId || draft.targetInstallationId == currentInstallationId || draft.expectedStateRevision != currentStateRevision) throw IllegalStateException("当前安装、启停或授权已变化，请重新读取历史并确认")
    reviewedPluginRestorePreview(draft, metadata, result) ?: throw IllegalStateException("恢复差异尚未完成审阅，请重新读取历史并检查")
    return JSONObject().put("id", draft.pluginId).put("targetInstallationId", draft.targetInstallationId).put("expectedInstallationId", draft.expectedInstallationId).put("expectedStateRevision", draft.expectedStateRevision).also { if (token.isNotEmpty()) it.put("token", token) }
}
