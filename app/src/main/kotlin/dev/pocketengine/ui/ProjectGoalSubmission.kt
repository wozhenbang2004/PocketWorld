package dev.pocketengine.ui

import org.json.JSONObject

/** One client keeps its original payload/source while an admission is unconfirmed. */
internal data class ProjectGoalSubmission(val clientMessageId: String, val payload: String, val goalDraft: String?) {
    fun clears(draft: String): Boolean = goalDraft != null && draft == goalDraft
    fun input(): JSONObject = JSONObject(payload)
}
