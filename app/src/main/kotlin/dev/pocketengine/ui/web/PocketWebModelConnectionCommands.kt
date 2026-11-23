package dev.pocketengine.ui.web

import org.json.JSONObject

/** Typed source save/test commands; no native service, credential I/O or dispatch. */
internal object PocketWebModelConnectionCommands {
    const val STOP_WAITING = "models.connection.test.stopWaiting"
    val COMMANDS: Set<String> = setOf("models.connection.add", "models.connection.edit", "models.connection.test", "models.connection.select", "models.connections.add", "models.connections.update", STOP_WAITING)

    sealed interface Command {
        val id: String
        val expectedRevision: Long
    }
    class Select(override val id: String, override val expectedRevision: Long) : Command
    // Keep credential-bearing payloads out of generated data-class toString/copy methods.
    class Save(
        val adding: Boolean,
        override val id: String,
        override val expectedRevision: Long,
        val name: String,
        val input: JSONObject,
        val useForNewTasks: Boolean = false,
        val draftReference: JSONObject? = null
    ) : Command
    class Test(
        override val id: String,
        override val expectedRevision: Long,
        val input: JSONObject,
        val editorId: String,
        val draftRevision: Long,
        val wait: StopWaiting? = null
    ) : Command
    // Non-secret identity of one explicit test, not the whole model editor.
    data class StopWaiting(override val id: String, override val expectedRevision: Long,
        val editorId: String, val draftRevision: Long, val waitId: String) : Command

    fun prepare(action: String, args: JSONObject): Command {
        require(action in COMMANDS) { "这项操作没有已接入的宿主实现" }
        if (action == STOP_WAITING) {
            checkedPayload(args, setOf("id", "expectedRevision", "editorId", "draftRevision", "waitId"))
            return stopWaiting(args, requiredString(args, "id"),
                requiredInt(args, "expectedRevision").toLong(), requiredString(args, "editorId"), draftRevision(args))
        }
        val testing = action == "models.connection.test"
        val saveSourceOnly = action == "models.connections.add" || action == "models.connections.update"
        checkedPayload(args, setOf("id", "expectedRevision", "name", "url", "model", "apiKey", "bedrockRegion", "awsSession", "screenshots", "supportsImageInput", "provider", "maxOutputTokens", "cleartextConsent", "compatibility") +
            if (testing) setOf("editorId", "draftRevision", "waitId") else if (saveSourceOnly) setOf("draftReference") else emptySet())
        val adding = action == "models.connection.add" || action == "models.connections.add" || testing && args.optString("id").isEmpty()
        val id = if (adding) "" else requiredString(args, "id")
        val expected = if (adding) 0L else requiredInt(args, "expectedRevision").also { require(it > 0) { "请重新读取原连接修订" } }.toLong()
        if (action == "models.connection.select") return Select(id, expected)
        val model = if (saveSourceOnly) optionalString(args, "model") else requiredString(args, "model")
        val input = JSONObject().put("baseUrl", requiredString(args, "url")).put("model", model)
            .put("apiKey", args.optString("apiKey")).put("sendScreenshots", requiredBoolean(args, "screenshots"))
            .put("supportsImageInput", requiredBoolean(args, "supportsImageInput")).put("provider", requiredString(args, "provider"))
            .put("maxOutputTokens", requiredInt(args, "maxOutputTokens")).put("cleartextConsent", args.optString("cleartextConsent"))
        if (args.has("bedrockRegion")) input.put("bedrockRegion", requiredString(args, "bedrockRegion"))
        if (args.has("awsSession")) input.put("awsSession", checkedPayload(args.getJSONObject("awsSession"), setOf("accessKeyId", "secretAccessKey", "sessionToken", "expiresAt")))
        if (args.has("compatibility")) input.put("compatibility", if (args.isNull("compatibility")) JSONObject.NULL else checkedProviderCompatibility(args.getJSONObject("compatibility")))
        if (testing) {
            val editorId = requiredString(args, "editorId")
            val draftRevision = draftRevision(args)
            val wait = if (args.has("waitId")) stopWaiting(args, id, expected, editorId, draftRevision) else null
            return Test(id, expected, input, editorId, draftRevision, wait)
        }
        val draftReference = if (args.has("draftReference")) checkedPayload(args.getJSONObject("draftReference"), setOf("objectId", "revision", "requestId")) else null
        return Save(adding, id, expected, args.optString("name", model.ifBlank { input.getString("provider") }), input, action == "models.connection.add", draftReference)
    }

    private val waitUuid = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
    private fun draftRevision(args: JSONObject) = requiredInt(args, "draftRevision")
        .also { require(it >= 0) { "草稿修订无效，请重新打开连接" } }.toLong()
    private fun stopWaiting(args: JSONObject, id: String, revision: Long, editorId: String, draft: Long): StopWaiting {
        val waitId = requiredString(args, "waitId")
        require(id.isNotBlank() && id.length <= 240 && id.none(Char::isISOControl) && revision > 0 &&
            waitUuid.matches(editorId) && waitUuid.matches(waitId)) { "原已保存服务的测试身份不可核对" }
        return StopWaiting(id, revision, editorId, draft, waitId)
    }

    private fun checkedProviderCompatibility(options: JSONObject): JSONObject {
        val values = mapOf(
            "tokenField" to setOf("NATIVE", "MAX_TOKENS", "MAX_COMPLETION_TOKENS"),
            "streamUsage" to setOf("NATIVE", "OMIT"), "toolWireShape" to setOf("NATIVE", "OPENAI_FUNCTION", "ANTHROPIC_INPUT_SCHEMA"),
            "schemaSubset" to setOf("NATIVE", "CORE_JSON"), "cacheMode" to setOf("OFF", "EPHEMERAL"),
            "cacheSupport" to setOf("UNKNOWN", "SUPPORTED", "UNSUPPORTED"))
        val checked = checkedPayload(options, values.keys + setOf("maxTools", "maxRequestBytes"))
        for ((key, allowed) in values) if (checked.has(key)) require(requiredString(checked, key) in allowed) { "兼容或缓存选项不在当前支持范围" }
        if (checked.has("maxTools")) require(requiredInt(checked, "maxTools") in 1..512) { "工具数量上限应为 1–512" }
        if (checked.has("maxRequestBytes")) require(requiredInt(checked, "maxRequestBytes") in 256..25_165_824) { "请求大小上限应为 256–25165824 字节" }
        return checked
    }

    private fun checkedPayload(args: JSONObject, allowed: Set<String>): JSONObject {
        require(args.keys().asSequence().all { it in allowed }) { "操作包含未接入的参数" }
        return JSONObject(args.toString())
    }
    private fun requiredString(args: JSONObject, name: String): String {
        val value = args.opt(name); require(value is String && value.isNotBlank()) { "缺少有效参数 $name" }; return value
    }
    private fun optionalString(args: JSONObject, name: String): String {
        if (!args.has(name)) return ""
        val value = args.opt(name); require(value is String) { "参数 $name 必须是文本" }; return value
    }
    private fun requiredInt(args: JSONObject, name: String): Int {
        val value = args.opt(name); require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "缺少有效整数参数 $name" }; return value.toInt()
    }
    private fun requiredBoolean(args: JSONObject, name: String): Boolean {
        val value = args.opt(name); require(value is Boolean) { "缺少有效开关参数 $name" }; return value
    }
}
