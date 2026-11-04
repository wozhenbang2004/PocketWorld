package dev.pocketengine.ui

import org.json.JSONObject
import dev.pocketengine.features.assistant.OwnerComposerState

internal fun ownerWorkDraftFingerprint(contentFingerprint: String, target: JSONObject): String =
    dev.pocketengine.store.TaskStore.capabilityFingerprint(JSONObject().put("contentFingerprint", contentFingerprint).put("ownerWorkTarget", target))

/** A reopened composer reconciles its original work input even before a work is selected again. */
internal fun matchingOriginalOwnerWorkInput(model: OwnerComposerState, key: String, contentFingerprint: String): OwnerComposerState.Pending? =
    model.pending().firstOrNull { pending ->
        pending.draftKey == key && pending.originalTarget().let { target -> target.length() > 0 && pending.fingerprint == ownerWorkDraftFingerprint(contentFingerprint, target) }
    }

/** Prefer the host's current work read over a persisted task-list header with the same id. */
internal fun nativeTaskStateRows(payload: JSONObject): List<UiTask> =
    (payload.optJSONArray("ownerWorks").objects() + payload.optJSONArray("tasks").objects()).map(::taskFromJson).distinctBy { it.id }

/** The visible task's native identity; this carries no execution grant. */
internal fun originalOwnerWorkBinding(expected: JSONObject, conversation: String?, tasks: List<UiTask>): JSONObject {
    val taskId = expected.opt("taskId") as? String ?: error("请先读取原任务")
    val runId = expected.opt("runId") as? String ?: error("原执行身份未读取")
    val epoch = expected.opt("epoch")
    require(epoch is Int || epoch is Long) { "原执行代次无效" }
    val number = (epoch as Number).toLong()
    require(taskId.isNotBlank() && number in 0..Int.MAX_VALUE.toLong()) { "原任务身份无效" }
    val task = tasks.firstOrNull { it.id == taskId } ?: error("请重新读取原任务")
    val raw = task.raw
    require(runId.isNotBlank() && number > 0 || runId.isEmpty() && number == 0L && task.status == "queued" && raw.optJSONObject("controls")?.optBoolean("canStop") == true) { "原任务准入身份无效" }
    require(raw.optString("id") == taskId && raw.optString("executionRole") == "owner_work" &&
        raw.optString("actorId") == "owner" && raw.optString("spaceId") == "private" &&
        !conversation.isNullOrBlank() && raw.optString("conversationId") == conversation &&
        raw.optString("runId") == runId && raw.optLong("coordinatorEpoch") == number) { "原任务、对话或执行代次已变化，请重新核对" }
    return JSONObject().put("taskId", taskId).put("runId", runId).put("epoch", number.toInt())
}
