package dev.pocketengine.ui

import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class McpInstallConfig(val id: String, val name: String, val endpoint: String, val protocolVersion: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("endpoint", endpoint).put("protocolVersion", protocolVersion)
}

internal data class McpInstallDiscovery(val requestId: String, val operationId: String, val config: McpInstallConfig, val inputRevision: Long, val expectedInstallationId: String, val command: String = "discoverMcp")

internal data class McpInstallReviewed(val discovery: McpInstallDiscovery, val body: String, val digest: String)

internal data class McpInstallAttempt(val requestId: String, val reviewed: McpInstallReviewed, val tools: Set<String>)

internal data class McpInstallEnableAttempt(val requestId: String, val id: String, val installationId: String, val enabled: Boolean)

/** Pure identity checks shared with focused tests. JSON from the host is never mutated. */

internal object McpInstallPreviewRules {
    const val CURRENT = "2026-07-28"
    const val LEGACY = "2025-11-25"

    @JvmStatic fun validConfig(config: McpInstallConfig): Boolean = try {
        val uri = URI(config.endpoint)
        config.id.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")) && config.name.isNotBlank() && config.name.length <= 80 &&
            config.endpoint.length <= 2000 && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.scheme == "https" &&
            config.protocolVersion in setOf(CURRENT, LEGACY)
    } catch (_: Exception) { false }

    @JvmStatic fun importedConfig(requestId: String?, envelope: JSONObject?): McpInstallConfig? {
        val result = result(requestId, envelope) ?: return null
        val config = result.optJSONObject("config") ?: return null
        // Only this small, secret-free contract is accepted. Never retain a raw imported file.
        if (config.keys().asSequence().any { it !in setOf("id", "name", "endpoint", "protocolVersion") } ||
            listOf("id", "name", "endpoint").any { config.opt(it) !is String } ||
            config.has("protocolVersion") && config.opt("protocolVersion") !is String) return null
        val parsed = McpInstallConfig(config.optString("id").trim(), config.optString("name").trim(), config.optString("endpoint").trim(), config.optString("protocolVersion", CURRENT))
        return parsed.takeIf(::validConfig)
    }

    @JvmStatic fun result(requestId: String?, envelope: JSONObject?): JSONObject? =
        if (!requestId.isNullOrBlank() && envelope?.optString("requestId") == requestId && envelope.opt("ok") == true) envelope.optJSONObject("result") else null

    @JvmStatic fun installationId(snapshot: JSONObject, id: String): String? {
        val all = snapshot.optJSONArray("plugins") ?: return null
        val plugin = objects(all).find { it.optString("id") == id } ?: return ""
        return plugin.optString("installationId").takeIf { it.isNotBlank() }
            ?: plugin.optLong("installedAt").takeIf { it > 0 }?.let { "legacy-$it" }
    }

    @JvmStatic fun freeze(discovery: McpInstallDiscovery?, config: McpInstallConfig, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): McpInstallReviewed? = try {
        if (discovery == null || discovery.config != config || discovery.inputRevision != revision ||
            discovery.expectedInstallationId != installationId(snapshot, config.id)) null
        else {
            val body = result(discovery.requestId, envelope)
            if (body == null || !validPreview(discovery, body, now)) null
            else McpInstallReviewed(discovery, body.toString(), digest(body))
        }
    } catch (_: Exception) { null }

    @JvmStatic fun current(reviewed: McpInstallReviewed?, config: McpInstallConfig, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): JSONObject? = try {
        if (reviewed == null) null else {
            val again = freeze(reviewed.discovery, config, revision, snapshot, envelope, now)
            if (again?.digest != reviewed.digest || digest(JSONObject(reviewed.body)) != reviewed.digest) null else JSONObject(reviewed.body)
        }
    } catch (_: Exception) { null }

    /** Display correlation only. Native installation still consumes the retained discovery. */
    @JvmStatic fun source(body: JSONObject): JSONObject? = try {
        val raw = body.optJSONObject("mcpSource")
        if (raw == null) null else {
            val checked = dev.pocketengine.features.plugins.PluginRules.mcpSource(raw)
            val capabilities = body.getJSONObject("serverCapabilities")
            if (listOf("tools", "resources", "prompts").any { capabilities.has(it) && capabilities.optJSONObject(it) == null } ||
                capabilities.optJSONObject("resources") == null && capabilities.optJSONObject("prompts") == null ||
                checked.getString("endpoint") != body.optString("endpoint") || checked.getString("protocolVersion") != body.optString("protocolVersion") ||
                checked.getString("serverDigest") != body.optString("serverDigest")) null else checked
        }
    } catch (_: Exception) { null }

    private fun validPreview(discovery: McpInstallDiscovery, body: JSONObject, now: Long): Boolean {
        val config = discovery.config
        if (!validConfig(config) || body.optString("previewId").isBlank() || body.optString("operationId") != discovery.operationId ||
            body.optString("id") != config.id || body.optString("name") != config.name || body.optString("endpoint") != config.endpoint ||
            body.optString("protocolVersion") != config.protocolVersion || body.opt("expectedInstallationId") !is String ||
            body.optString("expectedInstallationId") != discovery.expectedInstallationId || body.optLong("expiresAt") <= now ||
            body.opt("installed") != false || body.opt("enabledAfterInstall") != false || body.optJSONArray("grantsAfterInstall")?.length() != 0) return false
        val tools = body.optJSONArray("tools") ?: return false
        if (body.has("mcpSource") && source(body) == null) return false
        if (tools.length() > 96) return false
        val names = mutableSetOf<String>()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: return false
            val name = tool.optString("name")
            if (name.isBlank() || !names.add(name) || tool.opt("supported") !is Boolean) return false
            if (tool.optBoolean("supported")) {
                val declaration = tool.optJSONObject("declaration") ?: return false
                if (declaration.optString("name") != name || declaration.optString("kind") != "mcp" || declaration.optString("effect") != "write" ||
                    declaration.optString("endpoint") != config.endpoint || declaration.optString("protocolVersion") != config.protocolVersion ||
                    declaration.optJSONObject("schema") == null || !declaration.optString("descriptorDigest").matches(Regex("[a-f0-9]{64}")) ||
                    !declaration.optString("serverDigest").matches(Regex("[a-f0-9]{64}"))) return false
            }
        }
        return true
    }

    @JvmStatic fun installPayload(reviewed: McpInstallReviewed?, config: McpInstallConfig, revision: Long, snapshot: JSONObject, envelope: JSONObject?, selected: Set<String>, now: Long): JSONObject {
        val body = current(reviewed, config, revision, snapshot, envelope, now) ?: throw IllegalStateException("预览已变化或过期，请重新发现工具。")
        val supported = objects(body.getJSONArray("tools")).filter { it.optBoolean("supported") }.map { it.getString("name") }.toSet()
        require(selected.size <= 12 && (selected.isNotEmpty() || source(body) != null) && supported.containsAll(selected)) { "请选择此次支持的工具，或明确安装已发现的资源与提示来源。" }
        return JSONObject().put("previewId", body.getString("previewId")).put("expectedInstallationId", body.getString("expectedInstallationId"))
            .put("tools", JSONArray(selected.sorted()))
    }

    @JvmStatic fun installed(attempt: McpInstallAttempt?, envelope: JSONObject?): JSONObject? = try {
        val plugin = result(attempt?.requestId, envelope)
        if (attempt == null || plugin == null || plugin.optString("id") != attempt.reviewed.discovery.config.id ||
            plugin.optString("installationId").isBlank() || plugin.optString("installationId") == attempt.reviewed.discovery.expectedInstallationId ||
            plugin.opt("enabled") != false || plugin.optJSONArray("grants")?.length() != 0) null
        else {
            val manifest = plugin.optJSONObject("manifest")
            val tools = manifest?.optJSONArray("tools")
            val reviewedBody = JSONObject(attempt.reviewed.body)
            val reviewedTools = objects(reviewedBody.getJSONArray("tools")).associateBy { it.getString("name") }
            val reviewedSource = source(reviewedBody)
            val returnedSource = manifest?.optJSONObject("mcpSource")?.let { dev.pocketengine.features.plugins.PluginRules.mcpSource(it) }
            val rows = objects(tools)
            if (manifest?.optString("id") != plugin.optString("id") || manifest.optString("name") != attempt.reviewed.discovery.config.name ||
                manifest.has("mcpSource") != reviewedBody.has("mcpSource") || reviewedSource?.let(::digest) != returnedSource?.let(::digest) ||
                attempt.tools.isEmpty() && reviewedSource == null ||
                tools == null || rows.size != tools.length() || rows.size != attempt.tools.size || rows.map { it.optString("name") }.toSet() != attempt.tools ||
                rows.any { digest(it) != reviewedTools[it.optString("name")]?.optJSONObject("declaration")?.let(::digest) }) null
            else JSONObject(plugin.toString())
        }
    } catch (_: Exception) { null }

    @JvmStatic fun enablePayload(plugin: JSONObject, snapshot: JSONObject, enabled: Boolean): JSONObject {
        val id = plugin.optString("id"); val expected = plugin.optString("installationId")
        check(snapshot.optJSONObject("nativeCapabilities")?.opt("setEnabledReviewed") == true) { "当前宿主暂不支持安全启停，请返回后更新宿主。" }
        check(id.isNotBlank() && expected.isNotBlank() && expected == installationId(snapshot, id)) { "安装记录已变化，请重新核对当前版本。" }
        val current = objects(snapshot.optJSONArray("plugins")).find { it.optString("id") == id }
            ?: error("安装记录已变化，请重新核对当前版本。")
        val revisionValue = plugin.opt("stateRevision")
        check(revisionValue is Int || revisionValue is Long) { "启停状态版本不可核对，请重新审阅。" }
        val revision = (revisionValue as Number).toLong()
        val grants = plugin.optString("grantsDigest")
        check(revision >= 0 && (current.opt("stateRevision") is Int || current.opt("stateRevision") is Long) &&
            current.optLong("stateRevision", -1) == revision &&
            grants.matches(Regex("[a-f0-9]{64}")) && grants == current.optString("grantsDigest")) { "启停状态或工具权限已变化，请重新审阅。" }
        check(!enabled || current.optString("mcpStatus") != "needs_discovery") { "旧会话已失效，请重新发现并安装。" }
        return JSONObject().put("id", id).put("expectedInstallationId", expected).put("expectedStateRevision", revision)
            .put("expectedGrantsDigest", grants).put("enabled", enabled)
    }

    @JvmStatic fun enabled(attempt: McpInstallEnableAttempt?, envelope: JSONObject?): JSONObject? {
        val plugin = result(attempt?.requestId, envelope) ?: return null
        return plugin.takeIf { attempt != null && it.optString("id") == attempt.id && it.optString("installationId") == attempt.installationId && it.opt("enabled") == attempt.enabled }
    }

    @JvmStatic fun objects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
    @JvmStatic fun digest(value: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}

/** The cleanup owns only this page's discovery and preview, never another plugin's work. */
