package dev.pocketengine.ui

import org.json.JSONObject

internal data class McpSourceAttempt(val command: String, val request: String, val operation: String, val installation: String, val revision: Long)

internal object McpEcosystemRules {
    fun result(plugin: JSONObject, attempt: McpSourceAttempt?, envelope: JSONObject?): JSONObject? {
        if (attempt == null || plugin.optString("installationId") != attempt.installation || plugin.optLong("stateRevision", -1) != attempt.revision) return null
        val result = McpInstallPreviewRules.result(attempt.request, envelope) ?: return null
        val source = result.optJSONObject("source") ?: return null
        return result.takeIf { source.optString("installationId") == attempt.installation && source.optLong("stateRevision", -1) == attempt.revision && result.optString("operationId") == attempt.operation }
    }
}

/** The original installed-service detail; returned text stays visibly separate from task instructions. */
