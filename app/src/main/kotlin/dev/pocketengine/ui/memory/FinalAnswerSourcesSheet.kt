package dev.pocketengine.ui.memory

import dev.pocketengine.core.RunControl
import dev.pocketengine.features.memory.FinalAnswerSources
import dev.pocketengine.runtime.context.CompactionPlan
import dev.pocketengine.ui.UiMessage
import dev.pocketengine.ui.UiTask
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun finalAnswerSourceSelection(message: UiMessage): JSONObject? = runCatching {
    if (message.role != "assistant" || message.content.isBlank()) return null
    val reference = message.memoryReference ?: return null
    val task = reference.getString("taskId")
    if (message.taskId != null && message.taskId != task) return null
    JSONObject().put("taskId", task).put("runId", reference.getString("runId"))
        .put("epoch", reference.getInt("coordinatorEpoch")).put("requestId", reference.getString("requestId"))
        .put("answerId", reference.getString("answerId")).put("answerFingerprint", CompactionPlan.fingerprint(message.content))
}.getOrNull()

internal fun finalAnswerSourceSelection(task: UiTask): JSONObject? = runCatching {
    val proof = task.raw.optJSONObject("evidence")?.optJSONObject("nativeFinalAnswer") ?: return null
    if (proof.optString("format") != "pocketworld.runner-final-answer.v1" || proof.optString("taskId") != task.id) return null
    JSONObject().put("taskId", task.id).put("runId", proof.getString("runId"))
        .put("epoch", proof.getInt("coordinatorEpoch")).put("answerFingerprint", proof.getString("answerFingerprint"))
}.getOrNull()

/** Every selection owns one cancellable read. Cancellation discards a late native callback even
 * if SQLite finishes after this sheet has closed, refreshed, or moved to another source. */

internal suspend fun readFinalAnswerSourceRequest(
    request: JSONObject, dispatch: (Runnable) -> Unit, read: (JSONObject, RunControl) -> JSONObject
): JSONObject =
    suspendCancellableCoroutine { continuation ->
        val control = RunControl()
        continuation.invokeOnCancellation { control.cancel() }
        try {
            dispatch(Runnable {
                try {
                    val response = read(request, control)
                    control.check()
                    checkedFinalAnswerSourceReceipt(request, response)
                    if (continuation.isActive) continuation.resume(response)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            })
        } catch (failure: Exception) {
            control.cancel()
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }

/** Shared native/Web receipt validation; no source URL or label can replace an original pin. */

internal fun checkedFinalAnswerSourceReceipt(request: JSONObject, response: JSONObject): JSONObject {
    check(response.optString("format") == FinalAnswerSources.FORMAT && response.optString("readId") == request.getString("readId")) { "原来源读取回执不属于这次选择" }
    val expected = request.getJSONObject("answer")
    val actual = response.getJSONObject("answer")
    expected.keys().forEach { key -> check(actual.has(key) && CompactionPlan.fingerprint(expected.get(key)) == CompactionPlan.fingerprint(actual.get(key))) { "原来源回执改变了回答身份或修订" } }
    if (response.optString("status") == "current" && request.has("source")) check(CompactionPlan.fingerprint(request.getJSONObject("source")) == CompactionPlan.fingerprint(response.getJSONObject("source").getJSONObject("pin"))) { "原来源回执不属于选中的来源修订" }
    return response
}
