package dev.pocketengine.ui.web

import dev.pocketengine.features.local.project.ProjectSources
import dev.pocketengine.ui.MemoryLearningReviewGuard
import org.json.JSONObject

/** A visible plain-text copy for one ordinary project draft. This receipt cannot read the Book
 * or authorize any task, command, file, model request, or future copy. */
internal object ProjectConventionDraft {
    const val KEY = "ownerPickedConvention"
    private val nativeId = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    fun source(origin: JSONObject, row: JSONObject): JSONObject {
        val project = origin.getString("projectId"); val checkout = origin.getString("checkoutId")
        val text = row.getString("text"); val revision = MemoryLearningReviewGuard.revision(row)
        require(nativeId.matches(project) && nativeId.matches(checkout) && origin.getString("scopeRevision").matches(Regex("[a-f0-9]{64}")))
        check(row.opt("ownerId")=="owner" && row.opt("spaceId")=="project:$project" && row.opt("active")==true &&
            row.opt("source")=="主人在本项目手写的约定" && row.opt("formation")=="explicit_save" && row.opt("layer")=="procedural" &&
            !row.optBoolean("archiveOnly") && !row.has("assertion") && !row.has("learningCandidateId") &&
            row.getJSONArray("sourceRefs").length()==0 && row.getJSONArray("sourceIds").length()==0 && revision>0 &&
            row.getString("id").isNotBlank() && !text.isBlank() && text.length<=4000 && '\u0000' !in text && Charsets.UTF_8.newEncoder().canEncode(text)) {
            "只可选当前项目中这一版有效的手写约定，不使用候选、文件摘录或临时聊天"
        }
        return JSONObject().put("projectId", project).put("checkoutId", checkout).put("recordId", row.getString("id"))
            .put("revision", revision).put("textSha256", ProjectSources.sha256(text.toByteArray(Charsets.UTF_8)))
    }
    fun present(draft: JSONObject): JSONObject? {
        val marker = draft.optJSONObject(KEY) ?: return null
        if(marker.optString("projectId")!=draft.optString("projectId") || marker.optString("checkoutId")!=draft.optString("checkoutId") ||
            !nativeId.matches(marker.optString("projectId")) || !nativeId.matches(marker.optString("checkoutId")) ||
            marker.optString("recordId").isBlank() || MemoryLearningReviewGuard.revision(marker)<=0 ||
            marker.optString("textSha256")!=ProjectSources.sha256(draft.optString("constraints").toByteArray(Charsets.UTF_8)))return null
        return JSONObject(marker.toString())
    }
    /** Never put source IDs/hashes in the web snapshot, task input or model context. */
    fun ownerView(draft: JSONObject): JSONObject = JSONObject(draft.toString()).also { out ->
        out.remove(KEY); present(draft)?.let { out.put(KEY, JSONObject().put("revision", it.getLong("revision")).put("checkoutId", it.getString("checkoutId"))) }
    }
}
