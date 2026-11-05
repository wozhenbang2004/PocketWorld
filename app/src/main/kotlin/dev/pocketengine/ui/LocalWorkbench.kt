package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

internal object LocalWorkbenchGuard {
    @JvmStatic fun result(response: JSONObject?, input: JSONObject?, requestId: String?, checkout: String? = null, path: String? = null): JSONObject? {
        if (response == null || input == null || requestId.isNullOrBlank() || input.optString("_requestId") != requestId) return null
        if (checkout != null && (input.optString("checkoutId") != checkout || response.optString("checkoutId", response.optString("id")) != checkout)) return null
        if (path != null && (input.optString("path") != path || response.optString("path") != path)) return null
        return response
    }
    @JvmStatic fun importPayload(review: JSONObject): JSONObject {
        require(!dev.pocketengine.features.local.project.ProjectBinaryImport.isReview(review)) { "原字节审阅须走原字节确认，不借文字参数导回" }
        if (review.optBoolean("baselineNeedsRefresh")) return dev.pocketengine.features.local.project.ProjectTextImport.reconciliationPayload(review)
        val version = review.opt("expectedVersion")
        require((version is Int || version is Long) && (version as Number).toLong() in 0..Int.MAX_VALUE.toLong()) { "缺少已审项目版本" }
        val hash = review.optString("expectedHash")
        require(hash == "absent" || hash.matches(Regex("[a-f0-9]{64}"))) { "缺少已审本地摘要" }
        require(review.optBoolean("importable") && !review.optBoolean("truncated")) { "请先完整审阅可导入的差异" }
        return JSONObject().put("checkoutId", review.getString("checkoutId")).put("path", review.getString("path"))
            .put("expectedHash", hash).put("expectedVersion", version)
    }
    @JvmStatic fun argv(executable: String, arguments: String): JSONArray {
        require(executable.isNotBlank()) { "请选择工具" }
        val args = arguments.split('\n').filter { it.isNotEmpty() }
        require(args.size <= 39 && args.all { it.length <= 2048 && !it.contains('\u0000') }) { "参数过多或过长" }
        return JSONArray().put(executable).also { array -> args.forEach { array.put(it) } }
    }
    @JvmStatic fun terminal(status: String, termux: Boolean): Boolean = if (termux) status in setOf("completed", "failed") else status in setOf("completed", "failed", "cancelled", "timed_out")
    @JvmStatic fun pollable(status: String, termux: Boolean): Boolean = if (termux) !terminal(status, true) else status in setOf("queued", "running", "stopping")
}
