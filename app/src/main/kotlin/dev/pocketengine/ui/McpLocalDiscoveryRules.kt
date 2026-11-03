package dev.pocketengine.ui

import dev.pocketengine.features.local.project.ProjectCommandSpec
import dev.pocketengine.features.plugins.mcp.McpJson
import dev.pocketengine.features.workspace.VersionedWorkspace
import org.json.JSONArray
import org.json.JSONObject

/** Presentation correlation only; never an approval, pipe handle or replacement task authority. */
internal data class McpLocalSelection @JvmOverloads constructor(val id: String, val name: String, val checkout: String, val source: String, val executable: String, val argvText: String, val packageManager: String = "", val packageManagerVersion: String = "") {
    fun configuration(): JSONObject {
        val argv = McpJson.parse("{\"argv\":$argvText}").getJSONArray("argv")
        require(argv.length() in 1..32)
        val normalized = ProjectCommandSpec.normalize(JSONObject().put("executable", executable).put("argv", argv))
        val entry = argv.getString(0)
        require(VersionedWorkspace.path(entry) == entry)
        require(entry.split('/').none { it.startsWith(".") } && (if (executable == "node") listOf(".js", ".mjs", ".cjs").any(entry::endsWith) else entry.endsWith(".py")))
        require(executable in setOf("node", "python3") && id.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")) && name.isNotBlank() && name.length <= 80)
        require(checkout.matches(Regex("[a-f0-9-]{36}")) && source.matches(Regex("[a-f0-9]{64}")))
        val result = JSONObject().put("id", id).put("name", name).put("checkoutId", checkout).put("expectedSourceDigest", source).put("runtimeId", "managed_process_v1").put("executable", executable).put("argv", normalized.getJSONArray("argv")).put("workingDirectory", ".").put("protocolVersion", "2025-11-25")
        if (packageManager.isNotEmpty()) {
            require(executable == "node" && packageManager in setOf("npm", "pnpm"))
            require(packageManagerVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) && packageManagerVersion.startsWith(if (packageManager == "npm") "11." else "10."))
            result.put("dependencyPreparation", JSONObject().put("manager", packageManager).put("version", packageManagerVersion))
        } else require(packageManagerVersion.isEmpty())
        return result
    }
}
internal data class McpLocalPreparation(val requestId: String, val configuration: String, val revision: Long)
internal data class McpLocalReviewed(val preparation: McpLocalPreparation, val body: String, val digest: String)
internal object McpLocalDiscoveryRules {
    private fun integer(value: Any?): Long? = if (value !is Number) null else try { java.math.BigDecimal(value.toString()).longValueExact() } catch (_: Exception) { null }
    fun result(id: String?, envelope: JSONObject?): JSONObject? = McpInstallPreviewRules.result(id, envelope)
    fun freeze(preparation: McpLocalPreparation?, selection: McpLocalSelection, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): McpLocalReviewed? = try {
        if (preparation == null || preparation.revision != revision || McpInstallPreviewRules.digest(selection.configuration()) != McpInstallPreviewRules.digest(JSONObject(preparation.configuration))) null
        else {
            val body = result(preparation.requestId, envelope)
            if (body == null || !valid(body, selection.configuration(), snapshot, now)) null else McpLocalReviewed(preparation, body.toString(), McpInstallPreviewRules.digest(body))
        }
    } catch (_: Exception) { null }
    fun current(review: McpLocalReviewed?, selection: McpLocalSelection, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): JSONObject? {
        if (review == null) return null
        val again = freeze(review.preparation, selection, revision, snapshot, envelope, now) ?: return null
        return if (again.digest == review.digest && McpInstallPreviewRules.digest(JSONObject(review.body)) == review.digest) JSONObject(review.body) else null
    }
    private fun valid(body: JSONObject, config: JSONObject, snapshot: JSONObject, now: Long): Boolean {
        val id = body.optString("actionId")
        if (!id.matches(Regex("[a-f0-9-]{36}")) || body.optString("reviewId") != id || body.optString("configurationId") != id || body.optString("executionCopyId") != id || body.optString("purpose") != "native_mcp_discovery" || body.optString("format") != "pocketworld.native-mcp-discovery.v1") return false
        if (body.optString("pluginId") != config.getString("id") || body.optString("name") != config.getString("name") || body.optString("checkoutId") != config.getString("checkoutId") || body.optString("sourceDigest") != config.getString("expectedSourceDigest") || body.optString("executable") != config.getString("executable") || body.optString("protocolVersion") != "2025-11-25") return false
        if (body.optString("configurationDigest") != McpInstallPreviewRules.digest(config) || body.optJSONArray("argv")?.toString() != config.getJSONArray("argv").toString() || body.optString("argvDigest") != McpInstallPreviewRules.digest(JSONObject().put("argv", config.getJSONArray("argv")))) return false
        if (body.optString("executionWorkspace") != "per_operation_visible_copy" || body.optString("sourceManifestScope") != "visible_source_only" || body.optString("risk").isBlank() || body.optString("workingDirectory") != "." || (integer(body.opt("reviewExpiresAt")) ?: 0) <= now) return false
        for (field in listOf("executionAuthorized", "osSandbox", "hiddenFilesIncluded", "dependencyCachesIncluded", "wholeCheckoutVersion", "installationAuthorized", "toolInvocationAuthorized", "modelInferenceAllowed", "credentialsSupported", "environmentInputsSupported", "programStarted")) if (body.opt(field) != false) return false
        if (body.opt("previewOnly") != true) return false
        val preparation = config.optJSONObject("dependencyPreparation")
        if (preparation == null) {
            if (body.opt("preparationApprovalRequired") != false || body.opt("preparationApproved") != false || body.has("packagePreparation")) return false
        } else {
            val plan = body.optJSONObject("packagePreparation") ?: return false
            if (body.opt("preparationApprovalRequired") != true || body.opt("preparationApproved") !is Boolean || body.optString("preparationDigest") != McpInstallPreviewRules.digest(plan)) return false
            if (plan.optString("manager") != preparation.getString("manager") || plan.optString("managerVersion") != preparation.getString("version") || plan.optString("sourceDigest") != config.getString("expectedSourceDigest")) return false
        }
        val installed = McpInstallPreviewRules.objects(snapshot.optJSONArray("plugins") ?: return false).find { it.optString("id") == config.getString("id") }
        if (installed == null) { if (body.opt("expectedInstallationId") != "" || !body.isNull("expectedStateRevision")) return false }
        else if (body.optString("expectedInstallationId") != installed.optString("installationId") || integer(body.opt("expectedStateRevision")) == null || integer(body.opt("expectedStateRevision")) != integer(installed.opt("stateRevision"))) return false
        val scope = JSONObject(body.toString())
        for (key in listOf("planDigest", "argv", "executionAuthorized", "risk", "reviewId", "previewOnly", "programStarted", "preparationApprovalRequired", "preparationApproved")) scope.remove(key)
        return body.optString("planDigest") == McpInstallPreviewRules.digest(scope)
    }
    /** The reply is tied to this exact frozen native review; it grants no client-side authority. */
    fun preparationApproved(body: JSONObject?, requestId: String?, envelope: JSONObject?): Boolean = try {
        if (body == null) false
        else if (body.opt("preparationApprovalRequired") == false) true
        else {
            val approved = result(requestId, envelope)
            approved != null && approved.opt("preparationApproved") == true &&
                McpInstallPreviewRules.digest(approved) == McpInstallPreviewRules.digest(JSONObject(body.toString()).put("preparationApproved", true))
        }
    } catch (_: Exception) { false }
    fun confirmPayload(review: McpLocalReviewed?, selection: McpLocalSelection, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): JSONObject {
        val body = current(review, selection, revision, snapshot, envelope, now) ?: throw IllegalStateException("原本机程序审阅已变化或过期，请重新读取")
        return JSONObject().put("reviewId", body.getString("reviewId")).put("planDigest", body.getString("planDigest"))
    }
    fun operation(requestId: String?, envelope: JSONObject?, task: String): JSONObject? = result(requestId, envelope)?.takeIf { it.optString("taskId") == task && it.optJSONObject("scope")?.optString("actionId") == task && it.opt("replayAllowed") == false && it.opt("agentRunnerCreated") == false }
    fun diagnostic(requestId: String?, envelope: JSONObject?, task: String, cursor: Long): JSONObject? = result(requestId, envelope)?.takeIf {
        it.optString("format") == "pocketworld.native-mcp-diagnostic.v1" && it.optString("taskId") == task && it.optString("operationId") == task &&
            integer(it.opt("cursor")) == cursor && (integer(it.opt("nextCursor")) ?: -1) >= cursor && it.opt("queryOnly") == true && it.opt("replayAllowed") == false && it.opt("modelDelivery") == false
    }
    /** Only presentation of original stderr bytes; protocol stdout and statuses are untouched. */
    fun diagnosticText(page: JSONObject): String {
        if (!page.optBoolean("logAvailable")) return page.optString("message", "原日志暂不可用")
        val chunks = page.getJSONArray("chunks"); val stderr = java.io.ByteArrayOutputStream(); var total = 0
        for (index in 0 until chunks.length()) {
            val chunk = chunks.getJSONObject(index); val data = java.util.Base64.getDecoder().decode(chunk.getString("dataBase64"))
            require(data.size == chunk.getInt("bytes") && total + data.size <= 65536 && chunk.getInt("stream") in 1..2) { "原日志页字节范围无法核对" }
            total += data.size; if (chunk.getInt("stream") == 2) stderr.write(data)
        }
        return if (stderr.size() == 0) "本页没有新增 stderr 诊断。" else stderr.toString("UTF-8").filter { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }
    }
}
