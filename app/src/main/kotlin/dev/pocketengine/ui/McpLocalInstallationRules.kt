package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

/** Presentation correlation only. Native completed SQL facts and the original install review
 * remain authoritative; the UI compares native declarations, never interprets JSON Schema. */
internal data class McpLocalInstallCatalogue(val requestId: String, val taskId: String, val body: String, val digest: String)
internal data class McpLocalInstallPreparation(val requestId: String, val catalogueDigest: String, val tools: String, val revision: Long)
internal data class McpLocalInstallReviewed(val preparation: McpLocalInstallPreparation, val body: String, val digest: String)
internal data class McpLocalInstallAttempt(val requestId: String, val review: McpLocalInstallReviewed)
internal object McpLocalInstallationRules {
    private fun integer(value: Any?): Long? = if (value !is Number) null else try { java.math.BigDecimal(value.toString()).longValueExact() } catch (_: Exception) { null }
    private fun hash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
    private fun uuid(value: String) = value.matches(Regex("[a-f0-9-]{36}"))
    private fun tools(selected: Set<String>) = JSONArray(selected.sorted()).toString()
    private fun withoutDigest(body: JSONObject, field: String): String = JSONObject(body.toString()).let { it.remove(field); McpInstallPreviewRules.digest(it) }
    fun catalogue(request: String?, task: String, envelope: JSONObject?): McpLocalInstallCatalogue? { return try {
        val body = McpInstallPreviewRules.result(request, envelope)
        if (body == null || !uuid(task) || body.optString("discoveryTaskId") != task || body.optString("format") != "pocketworld.native-stdio-install-catalogue.v1" ||
            body.optString("protocolVersion") != "2025-11-25" || body.optString("id").isBlank() || body.optString("name").isBlank() ||
            body.opt("sourceRuntimeRevalidationRequired") != true || body.opt("untrusted") != true ||
            listOf("programStarted", "executionAuthorized", "installationAuthorized").any { body.opt(it) != false } ||
            listOf("discoveryFactsDigest", "configurationDigest", "sourceDigest", "runtimeIdentity", "argvDigest", "serverDigest", "catalogueDigest").any { !hash(body.optString(it)) } ||
            body.optString("catalogueDigest") != withoutDigest(body, "catalogueDigest") || (integer(body.opt("maxImportedTools")) ?: 0) !in 1..96) null
        else {
            val rows = body.optJSONArray("tools") ?: return null
            val parsed = McpInstallPreviewRules.objects(rows)
            if (rows.length() > 96 || parsed.size != rows.length() || parsed.map { it.optString("name") }.toSet().size != rows.length() || parsed.any { row ->
                row.optString("name").isBlank() || row.opt("supported") !is Boolean || row.opt("untrusted") != true ||
                    if (row.optBoolean("supported")) row.optJSONObject("declaration")?.let { declaration ->
                        declaration.optString("name") != row.optString("name") || declaration.optString("kind") != "mcp" || declaration.optString("transport") != "stdio" ||
                            declaration.optString("configurationDigest") != body.optString("configurationDigest") || declaration.optString("serverDigest") != body.optString("serverDigest") || declaration.optString("protocolVersion") != "2025-11-25"
                    } != false else row.optString("unsupportedReason").isBlank()
            }) null else McpLocalInstallCatalogue(request!!, task, body.toString(), body.getString("catalogueDigest"))
        }
    } catch (_: Exception) { null } }
    fun targetCurrent(body: JSONObject, snapshot: JSONObject): Boolean {
        val plugins = snapshot.optJSONArray("plugins") ?: return false
        val installed = McpInstallPreviewRules.objects(plugins).find { it.optString("id") == body.optString("id") }
        return if (installed == null) body.opt("expectedInstallationId") == "" && body.isNull("expectedStateRevision")
        else uuid(installed.optString("installationId")) && body.optString("expectedInstallationId") == installed.optString("installationId") &&
            integer(body.opt("expectedStateRevision"))?.let { it >= 0 && it == integer(installed.opt("stateRevision")) } == true
    }
    fun preparationPayload(catalogue: McpLocalInstallCatalogue, selected: Set<String>, snapshot: JSONObject): JSONObject {
        val body = JSONObject(catalogue.body)
        check(body.optString("catalogueDigest") == catalogue.digest && catalogue.digest == withoutDigest(body, "catalogueDigest") && targetCurrent(body, snapshot)) { "原目录或安装目标已变化，请重新核对；不会替换新安装" }
        val supported = McpInstallPreviewRules.objects(body.getJSONArray("tools")).filter { it.opt("supported") == true }.map { it.getString("name") }.toSet()
        require(selected.size in 1..body.getInt("maxImportedTools") && supported.containsAll(selected)) { "仅可选择本次目录中明确支持的工具，不能超过原导入上限" }
        return JSONObject().put("taskId", catalogue.taskId).put("tools", JSONArray(tools(selected)))
    }
    fun freeze(preparation: McpLocalInstallPreparation?, catalogue: McpLocalInstallCatalogue?, selected: Set<String>, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): McpLocalInstallReviewed? = try {
        if (preparation == null || catalogue == null || preparation.revision != revision || preparation.catalogueDigest != catalogue.digest || preparation.tools != tools(selected)) null
        else {
            preparationPayload(catalogue, selected, snapshot)
            val body = McpInstallPreviewRules.result(preparation.requestId, envelope)
            if (body == null || !validReview(body, JSONObject(catalogue.body), selected, now)) null
            else McpLocalInstallReviewed(preparation, body.toString(), McpInstallPreviewRules.digest(body))
        }
    } catch (_: Exception) { null }
    private fun validReview(body: JSONObject, catalogue: JSONObject, selected: Set<String>, now: Long): Boolean {
        if (!uuid(body.optString("reviewId")) || body.optString("purpose") != "install_native_stdio_mcp" || (integer(body.opt("expiresAt")) ?: 0) <= now || body.optString("reviewDigest") != withoutDigest(body, "reviewDigest")) return false
        for (field in listOf("discoveryTaskId", "discoveryFactsDigest", "id", "name", "configurationDigest", "sourceDigest", "checkoutId", "runtimeIdentity", "entryPoint", "argvDigest", "expectedInstallationId", "serverDigest")) if (body.opt(field) != catalogue.opt(field)) return false
        for (field in listOf("argvCount", "maxImportedTools")) if (integer(body.opt(field)) == null || integer(body.opt(field)) != integer(catalogue.opt(field))) return false
        if (body.isNull("expectedStateRevision") != catalogue.isNull("expectedStateRevision") || integer(body.opt("expectedStateRevision")) != integer(catalogue.opt("expectedStateRevision"))) return false
        if (listOf("programStarted", "executionAuthorized", "toolInvocationAuthorized", "enabledAfterInstall", "credentialsSupported", "environmentInputsSupported", "osSandbox").any { body.opt(it) != false } || body.optJSONArray("grantsAfterInstall")?.length() != 0 || body.optString("message").isBlank()) return false
        val names = body.optJSONArray("selectedTools") ?: return false
        if ((0 until names.length()).map { names.optString(it) }.toSet() != selected || names.length() != selected.size) return false
        val manifest = body.optJSONObject("manifest") ?: return false
        if (manifest.optString("id") != body.optString("id") || manifest.optString("name") != body.optString("name")) return false
        val declarations = manifest.optJSONArray("tools") ?: return false
        val rows = McpInstallPreviewRules.objects(declarations)
        val known = McpInstallPreviewRules.objects(catalogue.getJSONArray("tools")).associateBy { it.getString("name") }
        return rows.size == declarations.length() && rows.size == selected.size && rows.map { it.optString("name") }.toSet() == selected && rows.all { declaration ->
            known[declaration.optString("name")]?.takeIf { it.optBoolean("supported") }?.optJSONObject("declaration")?.let { McpInstallPreviewRules.digest(it) == McpInstallPreviewRules.digest(declaration) } == true
        }
    }
    fun current(review: McpLocalInstallReviewed?, catalogue: McpLocalInstallCatalogue?, selected: Set<String>, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): JSONObject? {
        if (review == null) return null
        val again = freeze(review.preparation, catalogue, selected, revision, snapshot, envelope, now) ?: return null
        return if (again.digest == review.digest && McpInstallPreviewRules.digest(JSONObject(review.body)) == review.digest) JSONObject(review.body) else null
    }
    fun confirmationPayload(review: McpLocalInstallReviewed?, catalogue: McpLocalInstallCatalogue?, selected: Set<String>, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): JSONObject {
        val body = current(review, catalogue, selected, revision, snapshot, envelope, now) ?: throw IllegalStateException("原安装审阅已变化或过期，请重新核对")
        return JSONObject().put("reviewId", body.getString("reviewId")).put("reviewDigest", body.getString("reviewDigest"))
    }
    fun installed(attempt: McpLocalInstallAttempt?, envelope: JSONObject?): JSONObject? = try {
        val result = McpInstallPreviewRules.result(attempt?.requestId, envelope)
        if (attempt == null || result == null) null else {
            val review = JSONObject(attempt.review.body)
            if (result.optString("id") != review.optString("id") || !uuid(result.optString("installationId")) || result.optString("installationId") == review.optString("expectedInstallationId") ||
                integer(result.opt("stateRevision")) != 0L || result.opt("enabled") != false || result.optJSONArray("grants")?.length() != 0 ||
                result.optJSONObject("manifest")?.let { McpInstallPreviewRules.digest(it) } != McpInstallPreviewRules.digest(review.getJSONObject("manifest")) ||
                result.optJSONObject("localMcp")?.optString("discoveryFactsDigest") != review.optString("discoveryFactsDigest")) null else JSONObject(result.toString())
        }
    } catch (_: Exception) { null }
}
