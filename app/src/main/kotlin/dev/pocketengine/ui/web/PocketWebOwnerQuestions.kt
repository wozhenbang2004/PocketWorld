package dev.pocketengine.ui.web

import dev.pocketengine.features.assistant.OwnerPhoneQuestions
import dev.pocketengine.features.assistant.OwnerQuestionInputs
import dev.pocketengine.features.assistant.notifications.OwnerQuestionReply
import dev.pocketengine.runtime.policy.OwnerQuestionChoices
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiTask
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Presentation and reply routing only. The existing native reply transaction and retained
 * physical owner still decide whether an answer can be saved and the original run continued. */
internal object PocketWebOwnerQuestions {
    const val ANSWER = "tasks.answerQuestion"
    const val CHOOSE = "tasks.answerQuestionChoice"
    val commands = setOf(ANSWER, CHOOSE)

    private fun nativeQuestion(task: JSONObject): JSONObject? = when (task.optString("kind")) {
        "phone" -> OwnerPhoneQuestions.pageQuestion(task)
        "assistant" -> OwnerQuestionInputs.pageQuestion(task)
        else -> null
    }

    private fun kind(task: JSONObject) = if (task.optString("kind") == "phone") "phone" else "capabilities"

    /** A display/draft key, never an execution capability. A replacement physical hold gets
     * its own key even when the task and the textual question happen to be unchanged. */
    private fun key(task: JSONObject, identity: JSONObject): String {
        val values = JSONArray().put(kind(task))
        for (name in listOf("taskId", "runId", "coordinatorEpoch", "requestId", "waitRevision")) values.put(identity.get(name))
        if (kind(task) == "phone") {
            val hold = task.getJSONObject("ownerPhoneQuestionHold")
            for (name in listOf("holdId", "pauseRevision", "waitGeneration")) values.put(hold.get(name))
        }
        return MessageDigest.getInstance("SHA-256").digest(values.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun view(task: JSONObject): JSONObject? {
        val question = nativeQuestion(task) ?: return null
        val view = JSONObject().put("questionKey", key(task, question.getJSONObject("identity")))
            .put("kind", kind(task)).put("question", question.getString("question"))
            .put("answered", question.getBoolean("answered")).put("executionAuthorized", false)
        // Deliberately omit native identity, notice tokens, holds, grants and controllers.
        for (name in listOf("options", "answerText", "continuationState", "ownerAction"))
            if (question.has(name)) view.put(name, question.get(name))
        return JSONObject(view.toString())
    }

    fun actions(question: JSONObject?): Set<String> {
        if (question == null || question.optBoolean("answered")) return emptySet()
        return if ((question.optJSONArray("options")?.length() ?: 0) > 0) commands else setOf(ANSWER)
    }

    /** Only an unresolved current wait blocks generic resume. TaskStore leaves the old
     * question/phone hold after typed continuation, so their historical presence is insufficient. */
    fun blocksGenericResume(task: JSONObject): Boolean {
        if (task.optString("status") != "awaiting_user") return false
        val supported = runCatching {
            when (task.optString("kind")) {
                "phone" -> OwnerPhoneQuestions.requirePhoneTask(task)
                "assistant" -> OwnerQuestionInputs.requirePrivateTask(task)
                else -> return false
            }
        }.isSuccess
        if (!supported) return false
        // The identity validates this task/run/epoch/wait. A lost physical hold does not make
        // an unanswered current phone question safe to route through generic resume.
        if (OwnerQuestionReply.identity(task) != null) return true
        val question = nativeQuestion(task) ?: return false
        if (!question.getBoolean("answered") || question.optString("continuationState") != "original_worker_requested") return false
        val identity = question.getJSONObject("identity")
        val marker = task.optJSONObject("ownerQuestionContinuation")
        val nativeQuestion = task.getJSONObject("evidence").getJSONObject("ownerQuestion")
        val settled = marker != null && marker.optString("format") == "pocketworld.owner-question-continuation.v1" &&
            marker.optString("runId") == identity.getString("runId") && marker.optLong("epoch") == identity.getLong("coordinatorEpoch") &&
            marker.optString("requestId") == identity.getString("requestId") && marker.optLong("waitRevision") == identity.getLong("waitRevision") &&
            marker.optString("messageId").isNotBlank() && marker.optString("messageId") == nativeQuestion.optString("replyMessageId") &&
            marker.optString("phase") in setOf("claimed", "unavailable")
        // A queued original worker must settle first. Explicit saved/blocked outcomes and a
        // later ordinary pause use the existing native Continue/Stop and scope checks.
        return !settled
    }

    internal data class Submission(val identity: JSONObject, val questionKey: String, val kind: String,
                                   val text: String? = null, val optionId: String? = null)

    internal fun prepare(task: JSONObject, action: String, args: JSONObject): Submission {
        require(action in commands) { "不是原问题答复操作" }
        val field = if (action == CHOOSE) "optionId" else "text"
        require(args.keys().asSequence().all { it in setOf("reviewHandle", field) }) { "回答包含不属于原问题的参数" }
        val value = args.opt(field)
        require(value is String) { "原回答内容无效" }
        val question = nativeQuestion(task) ?: error("原问题或手机持有已变化，请重新核对")
        check(!question.getBoolean("answered")) { "原问题已经回答，请查看原任务回执" }
        val identity = JSONObject(question.getJSONObject("identity").toString())
        if (action == CHOOSE) {
            // Resolve only to validate membership; dispatch the original ID, never a Web label.
            OwnerQuestionChoices.answer(task.getJSONObject("evidence").getJSONObject("ownerQuestion"), identity, value)
        } else require(value.isNotBlank() && value.length <= 4000) { "请输入1至4000字的回答" }
        return Submission(identity, key(task, identity), kind(task),
            text = value.takeIf { action == ANSWER }, optionId = value.takeIf { action == CHOOSE })
    }

    fun dispatch(controller: PocketWorldController, task: UiTask, action: String, args: JSONObject,
                 done: (JSONObject?, String?) -> Unit) {
        val original = prepare(task.raw, action, args)
        val receive: (JSONObject?, String?) -> Unit = { value, error ->
            if (error != null) done(null, error)
            else runCatching { savedReceipt(original, value) }.fold(
                { done(it, null) }, { done(null, it.message ?: "回答保存回执未确认，请核对原任务；没有自动重发。") })
        }
        // Both methods already route phone/capability tasks through the original native owner.
        // No resume, new task, restored controller, grant or replacement phone port is created.
        if (original.optionId != null) controller.answerOwnerQuestionChoice(original.identity, original.optionId, receive)
        else controller.answerOwnerQuestion(original.identity, original.text!!, receive)
    }

    internal fun savedReceipt(original: Submission, result: JSONObject?): JSONObject {
        check(result?.opt("expired") != true) { "这个原问题已过期，请返回原任务查看当前状态。回答草稿仍保留。" }
        check(result != null && result.opt("accepted") == true && result.opt("answerSaved") == true) {
            "回答是否保存尚未确认，请核对原任务；草稿保留，没有自动重发。"
        }
        for (name in listOf("taskId", "runId", "requestId"))
            check(result.opt(name) == original.identity.getString(name)) { "回答回执不属于这个原问题，请核对原任务。" }
        for (name in listOf("coordinatorEpoch", "waitRevision")) {
            val actual = result.opt(name)
            check((actual is Int || actual is Long) && (actual as Number).toLong() == original.identity.getLong(name)) {
                "回答回执不属于这个原等待版本，请核对原任务。"
            }
        }
        check(result.opt("messageId") is String && result.getString("messageId").isNotBlank()) { "缺少原回答保存回执，请核对原任务。" }
        val view = JSONObject().put("questionKey", original.questionKey).put("kind", original.kind)
        for (name in listOf("accepted", "answerSaved", "duplicate", "taskId", "runId", "coordinatorEpoch", "requestId", "waitRevision",
            "messageId", "continuationRequested", "continuationState", "ownerAction", "executionAuthorized", "sourceKind", "formalSourceRefAvailable"))
            if (result.has(name)) view.put(name, result.get(name))
        // Saved is independent from continuation: exhausted/expired scopes are still saved replies.
        return JSONObject(view.toString())
    }
}
