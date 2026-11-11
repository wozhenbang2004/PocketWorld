package dev.pocketengine.ui.web

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Plain owner form input only. This store never contains a review, grant or queued action. */
internal class PocketWebFormDrafts(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "web-form-drafts-v1.json"))
    companion object {
        private val storageLock = Any()
        val COMMANDS = setOf("formDraft.read", "formDraft.save", "formDraft.remove")
        private const val TTL = 30L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRY = 262144
        private const val MAX_STORE = 4194304
        private val fields = mapOf(
            "memory" to setOf("text", "layer", "reason", "applicability", "confidence"),
            "groups" to setOf("name", "accountId", "roomId", "conversationKind", "conversationParentId", "senderId", "editingSenderId", "displayName", "role", "visibility", "quota", "hours"),
            "teams" to setOf("name", "mode", "leaderId", "members", "goal", "rounds", "text"),
            "reminders" to setOf("title", "phone", "goal", "type", "minutes", "date", "time", "zone", "weekdays", "monthDay", "lastDay", "skipGap", "laterOverlap", "cron", "misfire", "overlap", "maxCatchUp"),
            "commitments" to setOf("title", "details", "taskId", "hours", "date", "time", "zone", "laterOverlap", "changeTiming"),
            "plans" to setOf("goal", "delay", "steps", "timingEdited")
        )
        private val children = mapOf(
            "members" to setOf("id", "name", "role", "model", "runner", "modeId", "avatarShape", "assignment", "dependsOn", "device", "writeFiles", "production"),
            "device" to setOf("kind", "screenMode", "deviceId", "targetDeviceId", "profileId", "environmentId"),
            "production" to setOf("sourceMode", "sourceTaskId", "snapshotId", "packageId", "packageSha", "packageManifest", "wallSeconds", "outputBytes", "contributionBytes"),
            "quota" to setOf("dailyTasks", "memberDailyTasks", "dailyTokens", "maxTokensPerTask", "maxConcurrent", "dailyMedia", "memberDailyMedia", "maxMediaBytes"),
            "steps" to setOf("goal", "executor", "projectId", "path", "heading", "minimumBytes", "dependencies"),
            "dependencies" to setOf("step", "outcome")
        )
    }

    fun dispatch(action: String, args: JSONObject): JSONObject = synchronized(storageLock) { dispatchLocked(action, args) }

    private fun dispatchLocked(action: String, args: JSONObject): JSONObject {
        require(action in COMMANDS) { "不支持的草稿操作" }
        val allowed = setOf("kind", "scope", "objectId") + if (action == "formDraft.save") setOf("revision", "baseRevision", "draft") else if (action == "formDraft.remove") setOf("revision") else emptySet()
        require(args.keys().asSequence().all { it in allowed }) { "草稿包含不适用字段" }
        val kind = text(args, "kind", 24); val permitted = fields[kind] ?: error("此表单不支持保存草稿")
        val scope = text(args, "scope", 256); val objectId = text(args, "objectId", 256)
        val key = JSONArray().put(kind).put(scope).put(objectId).toString()
        val now = System.currentTimeMillis(); val state = read()
        val entries = state.optJSONObject("entries") ?: JSONObject().also { state.put("entries", it) }
        entries.keys().asSequence().toList().forEach { if ((entries.optJSONObject(it)?.optLong("expiresAt", 0L) ?: 0L) <= now) entries.remove(it) }
        var entry = entries.optJSONObject(key)
        if (action == "formDraft.read" && entry == null) {
            // Empty read/removal tokens carry no user input. Retiring one fails any
            // older writer closed, while a full draft store still permits reading.
            if (entries.length() >= 128) entries.keys().asSequence().filter { !entries.getJSONObject(it).has("draft") }
                .minByOrNull { entries.getJSONObject(it).optLong("expiresAt") }?.let { entries.remove(it) }
            entry = JSONObject().put("revision", UUID.randomUUID().toString()).put("baseRevision", "").put("savedAt", 0).put("expiresAt", now + TTL)
            entries.put(key, entry); write(state)
        }
        require(entry != null) { "草稿已过期或清除，请重新打开表单" }
        if (action != "formDraft.read") {
            require(text(args, "revision", 64) == entry.getString("revision")) { "草稿版本已变化，未覆盖新输入；请重新打开表单核对" }
            if (action == "formDraft.save") {
                require(entry.has("draft") || entries.keys().asSequence().count { entries.getJSONObject(it).has("draft") } < 128) { "本机草稿已满，请先清除不再需要的草稿" }
                val draft = args.getJSONObject("draft")
                val entryLimit = if (kind == "plans") 1048576 else MAX_ENTRY
                require(draft.toString().toByteArray(Charsets.UTF_8).size <= entryLimit) { "草稿过长，请缩短至 ${entryLimit / 1024} KiB 内再保存" }
                validate(draft, permitted + "outcomeUnverified", 0)
                entry.put("draft", JSONObject(draft.toString())).put("baseRevision", text(args, "baseRevision", 512, true)).put("savedAt", now)
            } else entry.remove("draft").also { entry.put("baseRevision", "").put("savedAt", 0) }
            entry.put("revision", UUID.randomUUID().toString()).put("expiresAt", now + TTL)
            write(state)
        }
        return JSONObject(entry.toString()).put("draft", entry.optJSONObject("draft") ?: JSONObject.NULL)
    }

    private fun validate(value: JSONObject, allowed: Set<String>, depth: Int) {
        require(depth <= 5 && value.keys().asSequence().all { it in allowed }) { "草稿包含权限、凭据或不支持的字段" }
        value.keys().asSequence().forEach { key ->
            when (val item = value.get(key)) {
                is JSONObject -> validate(item, children[key] ?: error("草稿字段结构无效"), depth + 1)
                is JSONArray -> {
                    require(item.length() <= 100) { "草稿列表过长" }
                    for (index in 0 until item.length()) when (val child = item.get(index)) {
                        is JSONObject -> validate(child, children[key] ?: error("草稿列表结构无效"), depth + 1)
                        is String -> require(child.length <= 4096 && '\u0000' !in child)
                        is Number -> require(child.toDouble().isFinite())
                        else -> error("草稿列表字段无效")
                    }
                }
                is String -> require(item.length <= 32000 && '\u0000' !in item) { "草稿文字过长或包含无效字符" }
                is Boolean -> Unit
                is Number -> require(item.toDouble().isFinite())
                JSONObject.NULL -> Unit
                else -> error("草稿字段无效")
            }
        }
    }
    private fun text(value: JSONObject, key: String, max: Int, empty: Boolean = false): String {
        val result = value.opt(key)
        require(result is String && result.length <= max && '\u0000' !in result && (empty || result.isNotBlank())) { "草稿字段 $key 无效" }
        return result
    }
    private fun read(): JSONObject {
        // openRead restores AtomicFile's committed .bak before opening the base.
        // A corrupt/oversized committed file is reported, never replaced by empty state.
        val input = try { file.openRead() } catch (failure: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw failure
            return JSONObject().put("version", 1).put("entries", JSONObject())
        }
        return input.use {
            require(it.channel.size() <= MAX_STORE) { "草稿文件超出保存范围" }
            val bytes = it.readBytes(); require(bytes.size <= MAX_STORE) { "草稿文件超出保存范围" }
            JSONObject(bytes.toString(Charsets.UTF_8)).also { value -> require(value.optInt("version") == 1) { "草稿版本不支持" } }
        }
    }
    private fun write(value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STORE) { "本机草稿已满，请先清除不再需要的草稿" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (failure: Exception) { file.failWrite(output); throw failure }
    }
}
