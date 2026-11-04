package dev.pocketengine.ui.web

import dev.pocketengine.ui.McpInstallAttempt
import dev.pocketengine.ui.McpInstallDiscovery
import dev.pocketengine.ui.McpInstallEnableAttempt
import dev.pocketengine.ui.McpInstallPreviewRules
import dev.pocketengine.ui.McpInstallReviewed
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Web presentation of the existing native MCP discovery and installation review. */
internal class PocketWebMcp(private val controller: PocketWorldController) {
    private var revision = 0L
    private var discovery: McpInstallDiscovery? = null
    private var reviewed: McpInstallReviewed? = null
    private var reviewHandle: String? = null
    private var attempt: McpInstallAttempt? = null
    private var enableReview: JSONObject? = null
    private var enableHandle: String? = null
    private var enableExpires = 0L
    private var enableAttempt: McpInstallEnableAttempt? = null
    private var grantReview: JSONObject? = null
    private var grantHandle: String? = null
    private var grantExpires = 0L
    private var grantAttempt: Pair<String, JSONObject>? = null

    fun clear() { revision++; discovery = null; reviewed = null; reviewHandle = null; attempt = null; enableReview = null; enableHandle = null; enableAttempt = null; grantReview = null; grantHandle = null; grantAttempt = null }

    fun discover(importRequestId: String): JSONObject {
        check("mcp.discoverMcp" !in controller.state.ownerPending && "mcp.installMcp" !in controller.state.ownerPending) { "请先等待原 MCP 操作的回执" }
        val config = McpInstallPreviewRules.importedConfig(importRequestId, controller.state.ownerResponses("mcp")["pickConfig"])
            ?: error("原导入配置已变化，请重新选择不含凭据的配置文件")
        val expected = McpInstallPreviewRules.installationId(controller.state.ownerSnapshot("mcp"), config.id)
            ?: error("原安装记录尚未读全，请刷新")
        discovery?.let { previous ->
            controller.state.ownerResponses("mcp")[previous.command]?.takeIf { it.optString("requestId") == previous.requestId }
                ?.optJSONObject("result")?.optString("previewId")?.takeIf { it.isNotBlank() }
                ?.let { controller.requestOwnerDomain("mcp", "discardMcpPreview", JSONObject().put("previewId", it)) }
        }
        val operation = UUID.randomUUID().toString()
        clear()
        val request = controller.requestOwnerDomain("mcp", "discoverMcp", config.json().put("_operationId", operation))
        discovery = McpInstallDiscovery(request, operation, config, revision, expected)
        return JSONObject().put("requestId", request).put("operationId", operation)
    }

    fun review(): JSONObject {
        val original = discovery ?: error("请先核对原配置并发现工具")
        val frozen = McpInstallPreviewRules.freeze(original, original.config, revision,
            controller.state.ownerSnapshot("mcp"), controller.state.ownerResponses("mcp")[original.command], System.currentTimeMillis())
            ?: error("原工具发现尚未完成、已变化或已过期，请读取原回执")
        reviewed = frozen; reviewHandle = UUID.randomUUID().toString()
        return JSONObject(frozen.body).put("reviewHandle", reviewHandle)
    }

    fun install(handle: String, selected: JSONArray): JSONObject {
        check(handle == reviewHandle && reviewed != null) { "原 MCP 审阅已关闭或已使用" }
        val frozen = reviewed!!
        val names = (0 until selected.length()).map { index -> selected.get(index).also { require(it is String && it.isNotBlank()) } as String }.toSet()
        require(names.size == selected.length()) { "工具选择含有重复项" }
        val payload = McpInstallPreviewRules.installPayload(frozen, frozen.discovery.config, revision,
            controller.state.ownerSnapshot("mcp"), controller.state.ownerResponses("mcp")[frozen.discovery.command], names, System.currentTimeMillis())
        reviewHandle = null
        val request = controller.requestOwnerDomain("mcp", "installMcp", payload)
        attempt = McpInstallAttempt(request, frozen, names)
        return JSONObject().put("requestId", request).put("status", "pending")
    }

    fun reviewEnabled(id: String, installation: String, enabled: Boolean): JSONObject {
        val snapshot = controller.state.ownerSnapshot("mcp")
        val plugin = McpInstallPreviewRules.objects(snapshot.optJSONArray("plugins")).firstOrNull { it.optString("id") == id && it.optString("installationId") == installation }
            ?: error("原 MCP 安装已变化，请刷新后核对")
        enableReview = McpInstallPreviewRules.enablePayload(plugin, snapshot, enabled)
        enableHandle = UUID.randomUUID().toString(); enableExpires = System.currentTimeMillis() + 120_000
        return JSONObject().put("reviewHandle", enableHandle).put("id", id).put("name", plugin.optString("name", id))
            .put("enabled", enabled).put("installationId", installation).put("stateRevision", plugin.getLong("stateRevision"))
            .put("grantsDigest", plugin.getString("grantsDigest")).put("grants", plugin.optJSONArray("grants") ?: JSONArray())
    }

    fun confirmEnabled(handle: String): JSONObject {
        check(handle == enableHandle && System.currentTimeMillis() < enableExpires) { "原启停审阅已关闭或过期" }
        val payload = enableReview ?: error("请重新核对原安装")
        val current = plugin(payload.getString("id"), payload.getString("expectedInstallationId"))
        check(current.optLong("stateRevision", -1) == payload.getLong("expectedStateRevision") && current.optString("grantsDigest") == payload.getString("expectedGrantsDigest")) { "原启停状态或工具权限已变化，请重新审阅" }
        enableHandle = null; enableReview = null
        val request = controller.requestOwnerDomain("mcp", "setEnabledReviewed", payload)
        enableAttempt = McpInstallEnableAttempt(request, payload.getString("id"), payload.getString("expectedInstallationId"), payload.getBoolean("enabled"))
        return JSONObject().put("requestId", request).put("status", "pending")
    }

    fun read(id: String, installation: String): JSONObject = JSONObject(plugin(id, installation).toString())

    private fun plugin(id: String, installation: String): JSONObject = McpInstallPreviewRules.objects(controller.state.ownerSnapshot("mcp").optJSONArray("plugins"))
        .firstOrNull { it.optString("id") == id && it.optString("installationId") == installation }
        ?: error("原安装已变化，请刷新后重新核对")

    fun reviewGrant(id: String, installation: String, digest: String, toolName: String, granted: Boolean): JSONObject {
        val current = plugin(id, installation)
        check(controller.state.ownerSnapshot("mcp").optJSONObject("nativeCapabilities")?.opt("setToolGrantReviewed") == true) { "宿主不支持此原安装的工具授权" }
        check(digest.matches(Regex("[a-f0-9]{64}")) && current.optString("grantsDigest") == digest) { "原工具权限已变化，请刷新核对" }
        val tool = McpInstallPreviewRules.objects(current.optJSONObject("manifest")?.optJSONArray("tools")).firstOrNull { it.optString("name") == toolName }
            ?: error("原安装不包含这项工具")
        grantReview = JSONObject().put("id", id).put("expectedInstallationId", installation).put("expectedGrantsDigest", digest).put("tool", toolName).put("granted", granted)
        grantHandle = UUID.randomUUID().toString(); grantExpires = System.currentTimeMillis() + 120_000
        return JSONObject(current.toString()).put("reviewHandle", grantHandle).put("selectedTool", JSONObject(tool.toString())).put("granted", granted)
    }

    fun confirmGrant(handle: String): JSONObject {
        check(handle == grantHandle && System.currentTimeMillis() < grantExpires) { "原工具权限审阅已关闭或过期" }
        val payload = grantReview ?: error("请重新核对原工具")
        check(plugin(payload.getString("id"), payload.getString("expectedInstallationId")).optString("grantsDigest") == payload.getString("expectedGrantsDigest")) { "原安装权限已变化，请重新核对" }
        grantHandle = null; grantReview = null
        val request = controller.requestOwnerDomain("mcp", "setToolGrantReviewed", payload)
        grantAttempt = request to payload
        return JSONObject().put("requestId", request).put("status", "pending")
    }

    fun snapshot(): JSONObject {
        val view = JSONObject().put("phase", "idle")
        enableAttempt?.let { original ->
            val envelope = controller.state.ownerResponses("mcp")["setEnabledReviewed"]
            val matched = envelope?.optString("requestId") == original.requestId
            view.put("enableReceipt", JSONObject().put("requestId", original.requestId).put("id", original.id)
                .put("status", if (!matched) "pending" else if (McpInstallPreviewRules.enabled(original, envelope) != null) "saved" else "unknown"))
        }
        grantAttempt?.let { (request, original) ->
            val envelope = controller.state.ownerResponses("mcp")["setToolGrantReviewed"]
            val actual = envelope?.optJSONObject("result")
            val grants = actual?.optJSONArray("grants") ?: JSONArray()
            val granted = (0 until grants.length()).any { grants.optString(it) == "tool:" + original.getString("tool") }
            val matched = envelope?.optString("requestId") == request
            val saved = matched && envelope?.opt("ok") == true && actual?.optString("id") == original.getString("id") && actual?.optString("installationId") == original.getString("expectedInstallationId") && granted == original.getBoolean("granted")
            view.put("grantReceipt", JSONObject().put("requestId", request).put("id", original.getString("id")).put("tool", original.getString("tool")).put("status", if (!matched) "pending" else if (saved) "saved" else "unknown"))
        }
        val selected = discovery ?: return view
        val response = controller.state.ownerResponses("mcp")[selected.command]
        view.put("requestId", selected.requestId).put("operationId", selected.operationId).put("configuration", selected.config.json())
        attempt?.let { original ->
            val envelope = controller.state.ownerResponses("mcp")["installMcp"]
            if (envelope?.optString("requestId") != original.requestId) return view.put("phase", "installing")
            val installed = McpInstallPreviewRules.installed(original, envelope)
            return if (installed != null) view.put("phase", "installed").put("installation", installed).put("message", "安装已保存，保持停用且未授予工具权限。")
                else view.put("phase", "unknown").put("message", "未取得原安装的完整回执，请刷新当前安装记录核对；不会自动重新安装。")
        }
        if (response == null || response.optString("requestId") != selected.requestId) return view.put("phase", "discovering")
        if (response.opt("ok") != true) return view.put("phase", "failed").put("message", response.optString("error", "原工具发现未完成"))
        val frozen = McpInstallPreviewRules.freeze(selected, selected.config, revision, controller.state.ownerSnapshot("mcp"), response, System.currentTimeMillis())
        return if (frozen == null) view.put("phase", "stale").put("message", "原发现已变化或过期，请重新核对配置。")
            else view.put("phase", "discovered").put("toolCount", JSONObject(frozen.body).getJSONArray("tools").length())
    }
}
