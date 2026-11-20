package dev.pocketengine.ui.web

import org.json.JSONObject

/** Select one retained revision for the existing private owner reader. Its
 * completed SQL output and current reader access are still checked on open. */
internal fun archivedTeamResearchResult(team: JSONObject, phase: JSONObject, id: String, version: Any?): JSONObject {
    require(team.opt("mode") == "collaborate" && team.opt("researchExecutionFormat") == "pocketworld.team-research-agent.v1" &&
        !team.has("workPlan") && !team.has("productionScope") && phase.opt("researchTaskId") != id) { "这不是普通协作修订前的答复" }
    val history = requireNotNull(phase.optJSONArray("researchHistory")) { "这次修订没有保留原阶段记录" }
    require(version is Int && version in 1..history.length() && history.length() <= 32) { "原答复版本不在这次修订记录内" }
    val prior = history.getJSONObject(version - 1)
    require(prior.opt("taskId") == id && prior.opt("reason") == "native_input_revision" && prior.optLong("retiredAt") > 0L &&
        (0 until history.length()).count { history.optJSONObject(it)?.opt("taskId") == id } == 1) { "修订前的原答复或版本已变化" }
    return requireNotNull(prior.optJSONObject("result")) { "修订前没有可核对的原答复回执" }
}
