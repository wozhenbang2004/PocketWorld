package dev.pocketengine.ui.web

import org.json.JSONObject

/** A restriction on an existing private tasks.open, never a task association.
 * Old receipts can omit the checkout; only the original TaskStore can attest
 * which project owns the work. Do not infer it from a title or the open page. */
internal data class ProjectTaskReadScope(val projectId: String, val checkoutId: String?) {
    fun addTo(args: JSONObject) {
        args.put("expectedProjectId", projectId)
        checkoutId?.let { args.put("expectedCheckoutId", it) }
    }

    fun requireOriginal(id: String, task: JSONObject) {
        check(task.optString("id") == id && task.optString("actorId") == "owner" && task.optString("spaceId") == "private" &&
            task.optString("kind") != "inference" && task.optBoolean("contextAvailable", true) &&
            !task.optBoolean("sourceCurrentReadRequired") && task.optString("taskProjection") != "source_unavailable") {
            "原工作来源已不可读取，未按当前项目打开"
        }
        val context = task.optJSONObject("developmentContext")
        val original = context?.opt("projectId") as? String
        check(!original.isNullOrBlank()) { "原工作记录中没有可核对的项目归属，可从工作目录查找原记录" }
        check(original == projectId) { "原工作不属于这个项目，没有打开其他项目的工作" }
        if (checkoutId != null) {
            val checkout = context?.opt("checkoutId") as? String
            check(!checkout.isNullOrBlank()) { "原工作未保留可核对的副本归属，可从项目工作列表查看原记录" }
            check(checkout == checkoutId) { "原工作与本次交办回执的副本不一致，未打开其他副本" }
        }
    }

    companion object {
        fun read(args: JSONObject): ProjectTaskReadScope? {
            if (!args.has("expectedProjectId")) {
                require(!args.has("expectedCheckoutId")) { "请从原项目打开这份工作" }
                return null
            }
            fun identity(key: String): String {
                val value = args.opt(key) as? String
                require(!value.isNullOrBlank() && value.length <= 240 && value == value.trim() && '\u0000' !in value) { "原项目或副本标识无效" }
                return value
            }
            return ProjectTaskReadScope(identity("expectedProjectId"), if (args.has("expectedCheckoutId")) identity("expectedCheckoutId") else null)
        }
    }
}
