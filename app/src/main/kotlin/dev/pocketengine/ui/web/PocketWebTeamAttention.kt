package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
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

/** A narrower use of the same private inbox read. No question text or member output is
 * materialized in the parent task, the model context or the conversation transcript. */
internal fun PocketWorldController.readWebWorkDecisions(parent: String, run: String, epoch: Int, conversation: String, team: String, done: (JSONObject?, String?) -> Unit) {
    val window = webWindowTicket()
    fun current() = webPrivateOwnerVisible() && webWindowTicket() == window && state.conversationId == conversation
    check(current()) { "请从发起这件工作的原对话查看" }
    val main = Handler(Looper.getMainLooper())
    runTeamPhoneWork {
        val result = runCatching {
            fun requireOriginal() {
                check(current()) { "原对话已经切换" }
                val link = nativeTokenBudgetHost().tasks.ownerWorkLinkForTeam(team)
                check(link != null && link.opt("readOnly") == true && link.opt("currentRun") == true && link.opt("stopping") == false
                    && link.opt("taskId") == parent && link.opt("runId") == run && link.optInt("epoch", -1) == epoch
                    && link.opt("conversationId") == conversation && link.opt("teamRunId") == team) { "原协作已经变化，请回这件工作核对" }
            }
            requireOriginal()
            val page = (webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队宿主尚未接入")).ownerDecisionPage(team)
            requireOriginal()
            page.put("parentTaskId", parent).put("parentRunId", run).put("parentEpoch", epoch).put("conversationId", conversation)
        }
        main.post {
            if (!current()) done(null, "原工作页面已变化，请重新读取")
            else result.fold({ done(it, null) }, { done(null, it.message ?: "原团队待办尚未读取") })
        }
    }
}
