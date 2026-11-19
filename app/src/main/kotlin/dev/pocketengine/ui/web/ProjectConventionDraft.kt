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
    /** Derive an action from this exact current note and this one local unsent draft only. */
    data class Transfer(val kind:String,val fromRevision:Long?=null,val oldChars:Int?=null)
    fun transfer(draft:JSONObject,selected:JSONObject):Transfer {
        val body=draft.optString("constraints")
        if(body.isEmpty())return Transfer("place")
        require(body.length<=4000) { "原草稿的限制无法在此核对，没有覆盖。" }
        // Any edit invalidates the source marker in the normal draft editor; manual text is never replaceable.
        val prior=present(draft) ?: error("原草稿有手写或已编辑的限制，没有覆盖。请先回原项目核对。")
        check(listOf("projectId","checkoutId","recordId").all { prior.getString(it)==selected.getString(it) }) {
            "原草稿不是从同项目、同副本的这条约定复制而来，没有替换。"
        }
        val previous=prior.getLong("revision");val next=selected.getLong("revision")
        val sameText=prior.getString("textSha256")==selected.getString("textSha256")
        check(previous<=next && (previous<next || sameText)) { "原来源修订或正文无法核对，没有替换。请重新打开原条目。" }
        return Transfer(if(sameText)"same_text" else "replace_older",previous,body.length)
    }
    /** This is a source-identity fence, never execution authority. Only a live native Review keeps the exact marker. */
    class Bound internal constructor(private val original:String?,val seal:String){
        fun copyMarker():JSONObject? = original?.let { JSONObject(it) }
        val submissionSeal:String? get() = if(original==null)null else seal
        fun matches(draft:JSONObject):Boolean = seal==bind(draft).seal
    }
    const val SUBMISSION_SEAL="nativeProjectConventionDraftSeal"
    fun bind(draft:JSONObject):Bound{
        val selected=present(draft)
        return Bound(selected?.toString(),selected?.let { ProjectSources.digest(it) } ?: "no_owner_source")
    }
    /** Never put source IDs/hashes in the web snapshot, task input or model context. */
    fun ownerView(draft: JSONObject): JSONObject = JSONObject(draft.toString()).also { out ->
        out.optJSONObject("submission")?.remove(SUBMISSION_SEAL);out.remove(KEY); present(draft)?.let { out.put(KEY, JSONObject().put("revision", it.getLong("revision")).put("checkoutId", it.getString("checkoutId"))) }
    }
}
