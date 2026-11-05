package dev.pocketengine.ui

import dev.pocketengine.features.local.runtime.RuntimeHealth
import org.json.JSONObject

/** Read-only owner copy of the existing native health DTO. Never an installer or grant. */
internal object ProjectRuntimeHealth {
    fun original(runtime: JSONObject?): JSONObject? = runtime?.optJSONObject("health")?.takeIf { it.optString("format") == RuntimeHealth.FORMAT }
    private fun identity(value: JSONObject?, key: String): String? = (value?.opt(key) as? String)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
    private fun label(value: JSONObject, key: String): String? = (value.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 160 && it.none { ch -> ch.isISOControl() } }

    /** Shared read-only presentation; preparation still requires the original project operation review. */
    fun presentation(runtime: JSONObject?, task: UiTask? = null, preparationEnabled: Boolean = false): JSONObject {
        val health = original(runtime)
        val executable = runtime?.opt("canExecute") == true
        val available = runtime?.opt("preparationAvailable") == true
        val progress = if (health?.optJSONObject("preparation")?.opt("current") == false) null
            else ProjectPreparationProgress.from(runtime?.optJSONObject("preparation")) ?: ProjectPreparationProgress.fromReceipt(ProjectWorkbenchGuard.preparationReceipt(task))
        return JSONObject().put("headline", if (executable) "手机开发工具可用" else if (progress?.error != null) "开发工具需要处理" else "开发工具尚未就绪")
            .put("caption", if (executable) "查看当前版本与保留范围" else if (available) "准备完成后即可构建和运行" else "仍可查看和编辑项目文件")
            .put("executable", executable).put("preparationAvailable", available).put("preparationEnabled", !executable && available && preparationEnabled)
            .put("preparationRequiresOriginalReview", true).put("readOnly", true).put("details", org.json.JSONArray(details(health)))
            .put("admissionMessage", if (!executable && available && !preparationEnabled) "请先处理原项目任务中的操作或审批。" else "任务仍使用自己的当前审批。")
            .put("phase", progress?.phase?.takeIf { it.matches(Regex("[a-z0-9_]{1,100}")) } ?: JSONObject.NULL)
            .put("fraction", progress?.fraction ?: JSONObject.NULL).put("resumableDownload", progress?.resumableDownload == true)
    }

    fun details(health: JSONObject?): List<String> {
        if (health?.optString("format") != RuntimeHealth.FORMAT) return emptyList()
        val rows = mutableListOf<String>()
        val release = identity(health, "releaseCatalogIdentity")
        val active = identity(health, "activeCatalogIdentity")
        val generation = identity(health, "activeGenerationIdentity")
        rows += "发布目录：${release ?: "尚不可核对"}"
        rows += "现用目录：${active ?: "尚无可核对的已安装目录"}"
        rows += "当前运行代次：${generation ?: "尚不可核对"}"
        if (release != null && active != null) rows += if (release == active) "现用目录与本次发布目录一致。" else "现用目录与本次发布目录不同；预览不会切换运行环境。"
        label(health, "abi")?.let { rows += "架构：$it" }
        label(health, "revision")?.let { rows += "发布版本：$it" }
        health.optJSONObject("preparation")?.let { preparation ->
            when (preparation.opt("current")) {
                false -> rows += "先前准备观察已过期，未把旧错误作为当前故障。"
                true -> {
                    label(preparation, "phase")?.let { rows += "当前准备观察：$it" }
                    val category = when (preparation.optString("failureCategory")) {
                        "network" -> "网络"; "storage" -> "存储空间"; "resource_busy" -> "原作业或维护仍占用资源"
                        "observation" -> "状态观察"; "probe" -> "工具探测"; "integrity" -> "文件完整性"
                        "policy_or_integrity" -> "范围或完整性校验"; "cancelled" -> "准备已取消"; "unknown" -> "原因尚不可核对"
                        else -> null
                    }
                    if (category != null) rows += "准备问题：$category；这不等于当前运行代次已失效。"
                    (preparation.opt("errorCode") as? String)?.takeIf { it.matches(Regex("runtime_[a-z0-9_]{1,180}")) }?.let { rows += "错误标识：$it" }
                }
                else -> rows += "准备观察是否属于当前代次尚不可核对。"
            }
        }
        val preview = health.optJSONObject("recoveryPreview")
        if (preview?.opt("previewOnly") != true) {
            rows += "恢复范围预览尚不可核对。"
            return rows
        }
        rows += "候选发布目录：${identity(preview, "candidateCatalogIdentity") ?: "尚不可核对"}（尚不是新运行代次）"
        rows += "保留的现用代次：${identity(preview, "retainedActiveGenerationIdentity") ?: "尚不可核对"}"
        val retained = listOf("homeRetained" to "HOME", "projectFilesRetained" to "项目文件", "originalReceiptsRetained" to "原执行回执").filter { preview.opt(it.first) == true }.map { it.second }
        if (retained.isNotEmpty()) rows += "原恢复范围保留：${retained.joinToString("、")}。"
        if (preview.opt("activationRequiresActualProbe") == true) rows += "候选环境必须经过实际工具探测。"
        if (preview.opt("activationRequiresOriginalJobsIdle") == true) rows += "原作业未结束或释放仍未确认时，不能切换环境或清理其目录。"
        if (preview.opt("unknownGenerationsDeleted") == false) rows += "未知归属的代次不在自动删除范围。"
        if (preview.opt("queryOriginalOperationBeforeRetry") == true) rows += "继续前先核对原操作；真正重新执行仍需当前审批。"
        if (preview.opt("restoresApprovals") == false && preview.opt("replayPriorOperations") == false && preview.opt("automaticRestart") == false) rows += "这份预览不恢复旧授权、不重放操作，也不自动重启。"
        health.optJSONObject("installedPackageManifest")?.let { manifest ->
            val count = (manifest.opt("packageCount") as? Number)?.takeIf { it is Int || it is Long }?.toLong()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            if (manifest.opt("present") == true && count != null && identity(manifest, "sha256") != null && manifest.optString("source") == "original_active_generation_dpkg_query") {
                rows += "现用代次的安装记录包含 $count 个软件包；此处未重新核验全部已安装字节。"
                rows += "安装记录 SHA-256：${manifest.getString("sha256")}"
            }
        }
        return rows
    }
}
