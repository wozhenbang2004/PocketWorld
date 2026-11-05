package dev.pocketengine.ui

import dev.pocketengine.features.teams.TeamResearchReadScope
import org.json.JSONArray
import org.json.JSONObject

internal data class ResearchReadFileChoice(val projectId: String, val path: String, val projectName: String) {
    val key: String get() = "$projectId\u0000$path"
}

internal data class ResearchReadAccessDraft(val webOrigins: String = "", val files: List<ResearchReadFileChoice> = emptyList()) {
    fun selection(): JSONObject {
        require(webOrigins.length <= 8192) { "站点列表过长" }
        val sites = webOrigins.lines().map(String::trim).filter(String::isNotEmpty).map { it.removeSuffix("/") }
        require(sites.size <= 4 && sites.distinct().size == sites.size) { "最多选择四个不重复的公共 HTTPS 站点" }
        sites.forEach { require(TeamResearchReadScope.origin(it) == it) { "只填写站点地址，例如 https://example.org，不含页面路径" } }
        require(files.size <= 32 && files.map { it.key }.distinct().size == files.size) { "最多选择三十二个不重复的项目文件" }
        val picked = JSONArray()
        files.forEach { require(it.projectId.matches(Regex("[a-f0-9-]{36}"))) { "请从原项目列表选择文件" }; TeamResearchReadScope.path(it.path); picked.put(JSONObject().put("projectId", it.projectId).put("path", it.path)) }
        return JSONObject().put("webOrigins", JSONArray(sites)).put("projectFiles", picked)
    }
    fun error(): String? = runCatching { selection() }.exceptionOrNull()?.message
}

/** Uses the original member-material page's selection/review/approval and worker. */
