package dev.pocketengine.ui.web

import dev.pocketengine.ui.PocketWorldController
import org.json.JSONArray
import org.json.JSONObject

/** Presentation adapter for the existing native archive/storage coordinators.
 * JSON identifies their currently displayed object; it never reconstructs a Review or Selection. */
internal class PocketWebData(private val controller: PocketWorldController) {
    private val archives get() = controller.ownerArchives
    private val storage get() = controller.projectStorage

    fun archivesSnapshot(): JSONObject = archives.state.let { s -> json(
        "open" to s.open, "busy" to s.busy, "inventory" to s.inventory, "selection" to s.selection,
        "preview" to s.preview, "review" to s.review, "originalAction" to s.originalAction,
        "originalPhase" to s.originalPhase, "result" to s.result, "experiencePage" to s.experiencePage,
        "passwordPrompt" to s.passwordPrompt, "passwordPromptId" to s.passwordPromptId,
        "projectMemoryName" to s.projectMemoryName, "projectMemoryCurrent" to s.projectMemoryCurrent,
        "notice" to s.notice, "error" to s.error, "webDavConfig" to s.webDavConfig,
        "webDavVersions" to s.webDavVersions, "webDavReview" to s.webDavReview,
        "webDavAction" to s.webDavAction, "webDavUpload" to s.webDavUpload
    ) }
    fun storageSnapshot(): JSONObject = storage.state.let { s -> json(
        "open" to s.open, "busy" to s.busy, "inventory" to s.inventory, "selected" to JSONArray(s.selected.toList()),
        "review" to s.review, "originalAction" to s.originalAction, "original" to s.original,
        "result" to s.result, "caches" to JSONArray(s.caches), "notice" to s.notice, "error" to s.error,
        "diagnosticText" to s.diagnosticText, "diagnosticSha" to s.diagnosticSha, "diagnosticBytes" to s.diagnosticBytes,
        "diagnosticReviewed" to s.diagnosticReviewed, "diagnosticPickerPending" to s.diagnosticPickerPending,
        "diagnosticExportEntered" to s.diagnosticExportEntered
    ) }
    fun open(domain: String) { owner(); if (domain == "archives") archives.open() else storage.open() }
    fun close(domain: String) { if (domain == "archives") archives.closePage() else storage.closePage() }

    fun dispatch(action: String, args: JSONObject): JSONObject {
        owner()
        val archive = action.startsWith("archives.")
        check(if (archive) archives.state.open else storage.state.open) { "原数据页面已关闭，请重新进入" }
        val stop = action in setOf("archives.webdav.stop", "archives.password.cancel")
        check(stop || !(if (archive) archives.state.busy else storage.state.busy)) { "请先等待原数据操作回执" }
        when (action) {
            "archives.refresh" -> { fields(args); archives.refresh() }
            "archives.create" -> { fields(args, "sections"); val selected = strings(args, "sections", SECTIONS.size); require(selected.isNotEmpty() && SECTIONS.containsAll(selected)); archives.create(selected) }
            "archives.chooseImport" -> { fields(args, "encrypted"); if (bool(args, "encrypted")) archives.chooseImport() else archives.choosePlainImport() }
            "archives.offerExport" -> { fields(args, "selectionId", "sha256", "encrypted"); selection(args); if (bool(args, "encrypted")) archives.offerExport() else archives.offerPlainExport() }
            "archives.discardCache" -> { fields(args, "selectionId", "sha256"); selection(args); archives.discardLocalCache() }
            "archives.password.use" -> {
                fields(args, "promptId", "password"); passwordPrompt(args)
                val secret = string(args, "password", 1024).toCharArray()
                try { archives.usePassword(secret) } finally { secret.fill('\u0000') }
            }
            "archives.password.cancel" -> { fields(args, "promptId"); passwordPrompt(args); archives.cancelPassword() }
            "archives.reviewPhase" -> { fields(args, "selectionId", "sha256", "phase"); selection(args); val phase = string(args, "phase"); require(phase in SECTIONS); archives.reviewPhase(phase) }
            "archives.cancelReview" -> { fields(args, "actionId"); review(args, archives.state.review); archives.cancelReview() }
            "archives.confirmPhase" -> { fields(args, "actionId"); review(args, archives.state.review); archives.confirmPhase() }
            "archives.query", "archives.poll", "archives.stop" -> {
                fields(args, "actionId"); same(string(args, "actionId"), archives.state.originalAction, "原恢复动作已变化")
                when (action) { "archives.query" -> archives.queryOriginal(); "archives.poll" -> archives.pollOriginal(); else -> archives.stopOriginal() }
            }
            "archives.readOriginal" -> { fields(args, "actionId"); archives.readOriginal(identifier(args, "actionId")) }
            "archives.experiences" -> { fields(args, "selectionId", "sha256", "page"); selection(args); archives.readExperiencePage(number(args, "page", 0, 1_000_000).toInt()) }
            "archives.openCheckout", "archives.openAttachment" -> {
                fields(args, "actionId", "id"); same(string(args, "actionId"), archives.state.originalAction, "原恢复动作已变化")
                val checkout = action == "archives.openCheckout"
                val row = rows(archives.state.result?.optJSONArray(if (checkout) "checkouts" else "attachments"))
                    .singleOrNull { it.optString(if (checkout) "checkoutId" else "id") == string(args, "id") }
                    ?: error("原恢复结果已变化，请重新核对")
                if (checkout) archives.openRestoredCheckout(JSONObject(row.toString())) else archives.openRestoredAttachment(JSONObject(row.toString()))
            }
            "archives.webdav.load" -> { fields(args); archives.loadWebDav() }
            "archives.webdav.save" -> {
                fields(args, "revision", "collection", "username", "password", "enabled")
                val revision = number(args, "revision", 0, Long.MAX_VALUE)
                val secret = string(args, "password", 4096, true).toCharArray()
                try { archives.saveWebDavConfiguration(revision, string(args, "collection", 2048), string(args, "username", 256, true), secret, bool(args, "enabled")) }
                finally { secret.fill('\u0000') }
            }
            "archives.webdav.list" -> { fields(args, "revision"); connection(args); archives.listWebDavVersions() }
            "archives.webdav.offerUpload" -> { fields(args, "selectionId", "sha256", "revision"); selection(args); connection(args); archives.offerWebDavUpload() }
            "archives.webdav.confirm", "archives.webdav.cancelReview" -> {
                fields(args, "actionId"); review(args, archives.state.webDavReview)
                if (action == "archives.webdav.confirm") archives.confirmWebDavUpload() else archives.cancelWebDavReview()
            }
            "archives.webdav.query", "archives.webdav.stop" -> {
                fields(args, "actionId"); same(string(args, "actionId"), archives.state.webDavAction, "原上传动作已变化")
                if (action == "archives.webdav.query") archives.queryWebDavUpload() else archives.stopWebDavUpload()
            }
            "archives.webdav.readOriginal" -> { fields(args, "actionId"); archives.readWebDavUpload(identifier(args, "actionId")) }
            "archives.webdav.offerImport" -> { fields(args, "revision", "name"); connection(args); archives.offerWebDavImport(string(args, "name", 100)) }
            "storage.refresh" -> { fields(args); storage.refresh() }
            "storage.suggest" -> { fields(args); storage.suggest() }
            "storage.select" -> {
                fields(args, "id", "selected"); val id = string(args, "id")
                val row = rows(storage.state.inventory?.optJSONArray("groups")).singleOrNull { it.optString("id") == id }
                    ?: error("原存储范围已变化，请重新计量")
                check(row.optBoolean("cleanable") && row.optJSONObject("usage")?.optBoolean("complete") == true) { "此范围不可据当前计量清理" }
                storage.select(id, bool(args, "selected"))
            }
            "storage.reviewSelected" -> { fields(args, "selection"); check(strings(args, "selection", 20) == storage.state.selected) { "原清理选择已变化，请重新核对" }; storage.reviewSelected() }
            "storage.confirm", "storage.cancelReview" -> { fields(args, "actionId"); review(args, storage.state.review); if (action == "storage.confirm") storage.confirm() else storage.cancelReview() }
            "storage.query", "storage.poll", "storage.stop" -> {
                fields(args, "actionId"); same(string(args, "actionId"), storage.state.originalAction, "原清理动作已变化")
                when (action) { "storage.query" -> storage.query(); "storage.poll" -> storage.poll(); else -> storage.stop() }
            }
            "storage.readOriginal" -> { fields(args, "actionId"); storage.readOriginal(identifier(args, "actionId")) }
            "storage.savePolicy" -> {
                fields(args, "revision", "generations", "backups", "generated")
                same(string(args, "revision"), storage.state.inventory?.optJSONObject("retentionPolicy")?.optString("revision"), "原保留规则已变化")
                storage.savePolicy(number(args, "generations", 0, 5).toInt(), number(args, "backups", 0, 5).toInt(), bool(args, "generated"))
            }
            "storage.backupCache" -> {
                fields(args, "selectionId", "sha256"); val id = string(args, "selectionId")
                check(storage.state.caches.any { it.optString("selectionId") == id && it.optString("archiveSha256") == string(args, "sha256") }) { "原备份缓存已变化" }
                storage.backupCache(id); return json("status" to "requested", "navigate" to "archives")
            }
            "storage.diagnostics.read" -> { fields(args); storage.readDiagnostics() }
            "storage.diagnostics.review" -> { fields(args, "sha256", "accepted"); same(string(args, "sha256"), storage.state.diagnosticSha, "原诊断快照已变化"); storage.reviewDiagnostics(bool(args, "accepted")) }
            "storage.diagnostics.export" -> { fields(args, "sha256"); same(string(args, "sha256"), storage.state.diagnosticSha, "原诊断快照已变化"); controller.exportStorageDiagnostics() }
            else -> error("未知原数据操作")
        }
        return json("status" to "requested")
    }

    private fun owner() = check(controller.webPrivateOwnerVisible()) { "请在本机前台私人页面核对数据" }
    private fun selection(args: JSONObject) {
        val selected = archives.state.selection ?: error("请先读取或生成原备份")
        same(string(args, "selectionId"), selected.optString("selectionId"), "原备份选择已变化")
        same(string(args, "sha256"), selected.optString("archiveSha256"), "原备份内容已变化")
    }
    private fun passwordPrompt(args: JSONObject) { same(string(args, "promptId"), archives.state.passwordPromptId, "原口令输入请求已关闭或变化") }
    private fun connection(args: JSONObject) { check(number(args, "revision", 0, Long.MAX_VALUE) == archives.state.webDavConfig?.optLong("revision", -1)) { "原 WebDAV 连接已变化，请重新读取" } }
    private fun review(args: JSONObject, current: JSONObject?) { same(string(args, "actionId"), current?.optString("actionId"), "原数据审阅已关闭或变化") }
    private fun same(expected: String, actual: String?, message: String) = check(expected.isNotBlank() && expected == actual) { message }
    private fun fields(args: JSONObject, vararg allowed: String) { require(args.keys().asSequence().all { it in allowed }) { "此原数据操作含有不适用字段" } }
    private fun string(args: JSONObject, key: String, max: Int = 512, empty: Boolean = false): String {
        val value = args.get(key); require(value is String && value.length <= max && (empty || value.isNotBlank())) { "请核对 $key" }; return value
    }
    private fun identifier(args: JSONObject, key: String): String = string(args, key, 36).also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private fun bool(args: JSONObject, key: String): Boolean { val value = args.get(key); require(value is Boolean); return value }
    private fun number(args: JSONObject, key: String, min: Long, max: Long): Long { val value = args.get(key); require(value is Int || value is Long); return (value as Number).toLong().also { require(it in min..max) } }
    private fun strings(args: JSONObject, key: String, max: Int): Set<String> {
        val value = args.getJSONArray(key); require(value.length() <= max)
        val result = (0 until value.length()).map { index -> value.get(index).also { require(it is String && it.length <= 512) } as String }.toSet()
        require(result.size == value.length()); return result
    }
    private fun rows(value: JSONArray?): List<JSONObject> = (0 until (value?.length() ?: 0)).mapNotNull { value?.optJSONObject(it) }
    private fun json(vararg values: Pair<String, Any?>): JSONObject = JSONObject().also { result -> values.forEach { (key, value) -> result.put(key, PocketWebState.publicValue(value)) } }

    companion object {
        private val SECTIONS = dev.pocketengine.features.workspace.OwnerDataArchives.SECTIONS
        val COMMANDS = setOf("archives.open", "archives.close", "archives.refresh", "archives.create", "archives.chooseImport", "archives.offerExport", "archives.discardCache", "archives.password.use", "archives.password.cancel", "archives.reviewPhase", "archives.cancelReview", "archives.confirmPhase", "archives.query", "archives.poll", "archives.stop", "archives.readOriginal", "archives.experiences", "archives.openCheckout", "archives.openAttachment", "archives.webdav.load", "archives.webdav.save", "archives.webdav.list", "archives.webdav.offerUpload", "archives.webdav.confirm", "archives.webdav.cancelReview", "archives.webdav.query", "archives.webdav.stop", "archives.webdav.readOriginal", "archives.webdav.offerImport", "storage.open", "storage.close", "storage.refresh", "storage.suggest", "storage.select", "storage.reviewSelected", "storage.confirm", "storage.cancelReview", "storage.query", "storage.poll", "storage.stop", "storage.readOriginal", "storage.savePolicy", "storage.backupCache", "storage.diagnostics.read", "storage.diagnostics.review", "storage.diagnostics.export")
    }
}
