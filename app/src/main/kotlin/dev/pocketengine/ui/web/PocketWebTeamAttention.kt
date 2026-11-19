package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.teams.TeamParentRevisionOffer
import dev.pocketengine.features.teams.TeamsModule
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Private owner navigation projection; never a model capability or execution grant. */
internal fun PocketWorldController.readWebTeamAttention(afterRunId: String, afterItemId: String, limit: Int, done: (JSONObject?, String?) -> Unit) {
    check(webPrivateOwnerVisible()) { "请在前台私人工作区读取团队待处理" }
    val window = webWindowTicket()
    val main = Handler(Looper.getMainLooper())
    runTeamPhoneWork {
        val result = runCatching {
            check(webPrivateOwnerVisible() && webWindowTicket() == window) { "原工作页面已关闭" }
            (webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队宿主尚未接入"))
                .ownerAttention(afterRunId, afterItemId, limit)
        }
        main.post {
            if (!webPrivateOwnerVisible() || webWindowTicket() != window) done(null, "原工作页面已变化，请重新读取")
            else result.fold({ done(it, null) }, { done(null, it.message ?: "团队待处理尚未读取") })
        }
    }
}

/** A locator only, never an owner decision. Both the original chat and the opened
 * team compare it with current SQL links before returning this version's directory. */
internal fun PocketWorldController.requireWorkOrigin(source: JSONObject, team: String): JSONObject {
    val link = nativeTokenBudgetHost().tasks.ownerWorkLinkForTeam(team)
    check(link != null && link.opt("readOnly") == true && link.opt("currentRun") == true && link.opt("stopping") == false
        && team == source.optString("teamRunId") && link.opt("teamRunId") == team && link.opt("taskId") == source.opt("parentTaskId")
        && link.opt("runId") == source.opt("parentRunId") && link.opt("epoch") == source.opt("parentEpoch")
        && link.opt("conversationId") == source.opt("conversationId")) { "原父工作、对话或团队已变化，没有打开其他成果" }
    return link
}
internal fun PocketWorldController.requireWorkFileReview(source: JSONObject, team: String): JSONObject {
    val link = requireWorkOrigin(source,team)
    val received = source.getJSONObject("fileReview")
    val actual = link.optJSONObject("fileReview") ?: error("这版文件不再等主人核对，请返回原工作")
    val keys = setOf("planHash","reviewerTaskId","reviewerRunId","projectId","fileCount")
    require(received.keys().asSequence().toSet() == keys && keys.all { actual.has(it) && actual.opt(it) == received.opt(it) }) { "原独审、项目或文件版本已变，没有替换成另一份" }
    return actual
}

internal fun requireWorkFileIndex(index: JSONObject, decision: JSONObject, mark: JSONObject) {
    check(index.opt("planHash") == mark.opt("planHash") && index.opt("projectId") == mark.opt("projectId")
        && index.getJSONArray("files").length() == mark.getInt("fileCount") && decision.optString("state") == "eligible")
        { "这版文件或项目基线已变，或已有批准；请回原工作重新核对" }
}

/** Read one known team's member decisions, or preflight the owner's explicit file choice.
 * No question body, file, approval or execution is inserted into the conversation. */
internal fun PocketWorldController.readWebWorkDecisions(parent: String, run: String, epoch: Int, conversation: String, team: String, files: JSONObject?, proposalDirectory: Boolean, proposal: JSONObject?, done: (JSONObject?, String?) -> Unit) {
    val window = webWindowTicket()
    fun current() = webPrivateOwnerVisible() && webWindowTicket() == window && state.conversationId == conversation
    check(current()) { "请从发起这件工作的原对话查看" }
    val main = Handler(Looper.getMainLooper())
    runTeamPhoneWork {
        val result = runCatching {
            val origin = JSONObject().put("parentTaskId",parent).put("parentRunId",run).put("parentEpoch",epoch).put("conversationId",conversation).put("teamRunId",team)
            fun requireOriginal() { check(current()) { "原对话已经切换" }; requireWorkOrigin(origin,team) }
            requireOriginal()
            val module = webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队宿主尚未接入")
            val page = if (proposalDirectory || proposal != null) {
                val retained = module.retainedOwnerReading(team)
                val result = JSONObject().put("runId",team).put("readOnly",true).put("executionAuthorized",false)
                if (proposal != null) {
                    module.requireOwnerRevisionTarget(retained,origin,proposal)
                    result.put("proposal",proposal)
                } else result.put("proposals",TeamParentRevisionOffer.directory(retained))
            } else if (files == null) module.ownerDecisionPage(team) else {
                origin.put("fileReview",files)
                val marker = requireWorkFileReview(origin,team); val reader = RunControl()
                try {
                    val frozen = module.readOwnerFrozenFiles(team,reader)
                    requireWorkFileIndex(frozen.index(),module.ownerTextDecision(frozen,reader),marker)
                    requireWorkFileReview(origin,team)
                    JSONObject().put("fileReview",marker).put("runId",team).put("readOnly",true).put("executionAuthorized",false)
                } finally { reader.cancel() }
            }
            requireOriginal()
            page.put("parentTaskId", parent).put("parentRunId", run).put("parentEpoch", epoch).put("conversationId", conversation)
        }
        main.post {
            if (!current()) done(null, "原工作页面已变化，请重新读取")
            else result.fold({ done(it, null) }, { done(null, it.message ?: "原团队待办尚未读取") })
        }
    }
}
