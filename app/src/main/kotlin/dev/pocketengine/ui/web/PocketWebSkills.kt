package dev.pocketengine.ui.web

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.skills.SkillBook
import dev.pocketengine.features.skills.SkillGitSource
import dev.pocketengine.features.skills.SkillSourcePolicy
import dev.pocketengine.features.skills.SkillsModule
import dev.pocketengine.store.TaskStore
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.discardUnsentSkillDocumentShare
import dev.pocketengine.ui.prepareSkillDocumentShare
import dev.pocketengine.ui.skillDocumentExportPin
import dev.pocketengine.ui.skillExportPin
import dev.pocketengine.ui.skillImportOptions
import dev.pocketengine.ui.skillOwnerPrivateScope
import dev.pocketengine.ui.writeSkillArchive
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Presentation handles around the original SkillBook, file picker and removal receipts.
 * Package bytes, source review objects, approval generations and task journals stay native. */
internal class PocketWebSkills(private val controller: PocketWorldController, private val module: SkillsModule,
    private val tasks: TaskStore, private val activity: ComponentActivity) {
    private data class Pin(val kind: String, val value: Any, val owner: Any, val expires: Long)
    private data class FileReview(val uri: Uri, val document: Boolean, val target: JSONObject?, val inspection: JSONObject, val incomingId: String?)
    private class Picker(val id: String, val owner: Any, val format: String, val target: JSONObject?, val export: JSONObject?) {
        var launcher: ActivityResultLauncher<*>? = null
        var returned = false
        var uri: Uri? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val pins = linkedMapOf<String, Pin>()
    private val pages = mutableMapOf<String, JSONObject>()
    private val manifestRead = mutableSetOf<String>()
    private val removalActions = mutableSetOf<String>()
    private var picker: Picker? = null
    @Volatile private var control: RunControl? = null
    private var activeOperationId: String? = null
    @Volatile private var generation = 0L
    private var state by mutableStateOf(JSONObject().put("open", false))
    fun snapshot(): JSONObject = JSONObject(state.toString()).put("incomingDocument", controller.skillDocuments.snapshot())
    fun clear() { generation++; control?.cancel(); control = null; activeOperationId = null; pins.clear(); pages.clear(); manifestRead.clear(); removalActions.clear(); picker?.launcher?.unregister(); picker = null; state = JSONObject().put("open", false) }
    fun background() { if (picker == null || control != null) clear() }
    private fun show(vararg values: Pair<String, Any?>) { state = JSONObject(state.toString()).also { next -> values.forEach { (key, value) -> next.put(key, value ?: JSONObject.NULL) } } }
    private fun fields(input: JSONObject, vararg names: String) { require(input.keys().asSequence().all { it in names }) { "技能操作包含不适用字段" } }
    private fun text(input: JSONObject, key: String, max: Int = 200): String { val value = input.opt(key); require(value is String && value.isNotBlank() && value.length <= max && '\u0000' !in value) { "请核对原技能参数" }; return value }
    private fun number(input: JSONObject, key: String, min: Int = 0): Int { val v = input.opt(key); require(v is Int && v >= min); return v }
    private fun issue(kind: String, value: Any): String { while (pins.size >= 96) pins.remove(pins.keys.first()); val id = UUID.randomUUID().toString(); pins[id] = Pin(kind, value, controller.webWindowTicket(), System.currentTimeMillis() + 600_000); return id }
    private fun pin(handle: String, kind: String, consume: Boolean = false): Any { val value = pins[handle] ?: error("原技能审阅已关闭，请重新读取"); check(value.kind == kind && value.owner == controller.webWindowTicket() && value.expires >= System.currentTimeMillis()) { "原技能审阅已变化或过期" }; if (consume) pins.remove(handle); return value.value }
    private fun selected(input: JSONObject): JSONObject = JSONObject((pin(text(input, "skillHandle"), "skill") as JSONObject).toString())
    private fun target(input: JSONObject): JSONObject? = if (input.optString("targetHandle").isEmpty()) null else JSONObject((pin(text(input, "targetHandle"), "skill") as JSONObject).toString())
    private fun current(expected: JSONObject): JSONObject {
        val actual = module.handle("get", JSONObject().put("id", expected.getString("id"))).getJSONObject("skill")
        check(actual.getInt("revision") == expected.getInt("revision") && actual.getString("digest") == expected.getString("digest") && actual.optLong("executionGeneration", 0) == expected.optLong("executionGeneration", 0)) { "原技能内容、来源策略或启停已变化，请重新打开" }; return actual
    }
    private fun work(done: (JSONObject?, String?) -> Unit, operation: (RunControl) -> JSONObject,
        accept: (JSONObject) -> JSONObject = { it }, discard: () -> Unit = {}) {
        check(control == null) { "请等待本次原技能操作结束" }
        val ticket = generation; val owner = controller.webWindowTicket(); val original = RunControl(); control = original; activeOperationId = UUID.randomUUID().toString()
        original.setAuthorityCheck { check(ticket == generation && controller.webWindowTicket() == owner && controller.webPrivateOwnerVisible()) { "原技能窗口已变化" } }
        show("busy" to true, "operationId" to activeOperationId, "error" to null)
        try { controller.runPluginCatalogWork(Runnable {
            val result = runCatching { original.check(); operation(original).also { original.check() } }
            main.post {
                if (ticket != generation || control !== original || controller.webWindowTicket() != owner || !controller.webPrivateOwnerVisible()) { if (control === original) { control = null; activeOperationId = null; show("busy" to false, "operationId" to null) }; discard(); done(null, "原技能页面已变化；请重新核对原记录，未自动重试"); return@post }
                control = null; activeOperationId = null; show("busy" to false, "operationId" to null)
                result.fold({ value -> runCatching { accept(value) }.fold({ done(PocketWebState.publicValue(it) as JSONObject, null) }, { failure -> discard(); show("error" to failure.message); done(null, failure.message ?: "原技能结果待核对") }) },
                    { failure -> discard(); show("error" to failure.message); done(null, failure.message ?: "本次结果未确认，请核对原技能记录") })
            }
        }) } catch (failure: Exception) { control = null; activeOperationId = null; show("busy" to false, "operationId" to null); discard(); done(null, failure.message) }
    }
    private fun content(uri: Uri): Uri { require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && !uri.authority.orEmpty().contains('@') && uri.fragment == null) { "只使用本次系统文件选择" }; return uri }
    private fun review(kind: String, value: Any, view: JSONObject): JSONObject = JSONObject(view.toString()).put("reviewKind", kind).put("reviewHandle", issue(kind, value))

    fun dispatch(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到原私人技能页面" }
        when (action) {
            "skills.cancel" -> { fields(input, "operationId"); check(text(input, "operationId") == activeOperationId) { "原技能处理已结束或换代，没有停止后续操作" }; val original = checkNotNull(control); original.cancel(); done(JSONObject().put("stopRequested", true).put("settled", false), null) }
            "skills.open" -> { fields(input); clear(); show("open" to true); work(done, { module.snapshot() }) }
            "skills.snapshot" -> { fields(input); work(done, { module.snapshot() }) }
            "skills.close" -> { fields(input, "handle"); if (input.has("handle")) { val id = text(input, "handle"); pins.remove(id); manifestRead.remove(id); pages.keys.removeAll { it.startsWith("$id:") }; done(JSONObject().put("closed", true), null) } else { clear(); done(snapshot(), null) } }
            "skills.get" -> {
                fields(input, "id"); val id = text(input, "id")
                work(done, { module.handle("get", JSONObject().put("id", id)) }, { value -> val skill = value.getJSONObject("skill"); value.put("skillHandle", issue("skill", JSONObject(skill.toString()))) })
            }
            "skills.read" -> {
                fields(input, "skillHandle", "path", "offset"); val handle = text(input, "skillHandle"); val expected = selected(input); val path = text(input, "path", 240); val offset = number(input, "offset")
                val key = "$handle:$path"; val prior = pages[key]; check(offset == 0 || prior != null && prior.optBoolean("hasMore") && prior.getInt("nextOffset") == offset) { "请从原资源首页连续读取" }
                if (offset == 0) { pages.remove(key); if (path == "SKILL.md") manifestRead.remove(handle) }
                work(done, { current(expected); val value = module.handle("read", JSONObject().put("id", expected.getString("id")).put("path", path).put("offset", offset).put("length", 8000)); current(expected)
                    check(value.getString("id") == expected.getString("id") && value.getInt("revision") == expected.getInt("revision") && value.getString("digest") == expected.getString("digest") && value.getString("path") == path && value.getInt("nextOffset") == offset + value.getString("content").length); value },
                    { value -> pages[key] = JSONObject(value.toString()); if (path == "SKILL.md" && !value.getBoolean("hasMore")) manifestRead.add(handle); value })
            }
            "skills.enabled.review" -> {
                fields(input, "skillHandle", "enabled"); val handle = text(input, "skillHandle"); val expected = selected(input); val enabled = input.get("enabled"); require(enabled is Boolean)
                if (enabled) { check(!expected.has("origin")) { "请在经验学习页核对原来源再批准" }; check(handle in manifestRead) { "请先连续阅读这份原 SKILL.md 的全部正文" } }
                work(done, { current(expected) }, { actual -> val value = JSONObject().put("before", actual).put("enabled", enabled); review("enabled", value, value) })
            }
            "skills.policy.choices" -> { fields(input); work(done, { JSONObject().put("projects", module.projectSourceChoicesForOwner()) }, { value -> val choices = value.getJSONArray("projects"); for (i in 0 until choices.length()) { val original = choices.getJSONObject(i); original.put("projectHandle", issue("project", JSONObject(original.toString()))) }; value }) }
            "skills.policy.review" -> {
                fields(input, "skillHandle", "layer", "invocation", "projectHandle"); val expected = selected(input); val layer = SkillSourcePolicy.Layer.valueOf(text(input, "layer")); val invocation = SkillSourcePolicy.Invocation.valueOf(text(input, "invocation"))
                val project = if (layer == SkillSourcePolicy.Layer.PROJECT) pin(text(input, "projectHandle"), "project") as JSONObject else null
                check(project != null || input.optString("projectHandle").isEmpty())
                work(done, { current(expected) }, { actual -> val value = JSONObject().put("before", actual).put("layer", layer.name).put("invocation", invocation.name).put("project", project ?: JSONObject.NULL); review("policy", value, value) })
            }
            "skills.remove.review" -> {
                fields(input, "skillHandle"); val expected = selected(input); var removal: SkillsModule.RemovalReview? = null
                work(done, { current(expected); module.reviewRemovalForOwner(tasks, expected).also { removal = it }.view() }, { value -> val original = checkNotNull(removal); removalActions.add(original.actionId()); review("removal", original, value.put("actionId", original.actionId())) })
            }
            "skills.removal.query" -> { fields(input, "actionId"); val id = text(input, "actionId"); check(id in removalActions); work(done, { val actual = tasks.get(id, false); JSONObject().put("actionId", id).put("found", actual != null).put("task", actual ?: JSONObject.NULL).put("replayAllowed", false) }) }
            "skills.confirm" -> {
                fields(input, "reviewHandle", "kind", "confirmed"); check(input.opt("confirmed") == true); val kind = text(input, "kind"); require(kind in setOf("enabled", "policy", "removal")); val value = pin(text(input, "reviewHandle"), kind, true)
                work(done, { original -> when (kind) {
                    "enabled" -> { val body = value as JSONObject; val before = body.getJSONObject("before"); module.setEnabledReviewedForOwner(skillOwnerPrivateScope(), before.getString("id"), before.getInt("revision"), before.getString("digest"), before.optLong("executionGeneration", 0), body.getBoolean("enabled")) }
                    "policy" -> { val body = value as JSONObject; val before = body.getJSONObject("before"); module.configureSourceForOwner(skillOwnerPrivateScope(), before.getString("id"), before.getInt("revision"), before.getString("digest"), before.optLong("executionGeneration", 0), SkillSourcePolicy.Layer.valueOf(body.getString("layer")), SkillSourcePolicy.Invocation.valueOf(body.getString("invocation")), body.optJSONObject("project")) }
                    else -> (value as SkillsModule.RemovalReview).remove(original)
                } })
            }
            "skills.chooseFile" -> {
                fields(input, "format", "targetHandle"); check(control == null && picker == null); val format = text(input, "format"); require(format in setOf("document", "package")); val selected = target(input)
                choose(format, selected, null); done(snapshot(), null)
            }
            "skills.discardSelection" -> { fields(input, "requestId"); check(control == null); val selected = picker?.takeIf { it.id == text(input, "requestId") } ?: error("原文件选择已变化"); selected.launcher?.unregister(); picker = null; show("selectedRequestId" to null, "pickerPending" to false, "selection" to null); done(snapshot(), null) }
            "skills.inspectSelected", "skills.incoming.inspect" -> {
                val uri: Uri; val document: Boolean; val expected: JSONObject?; val incoming: String?
                if (action == "skills.incoming.inspect") { fields(input, "requestId", "targetHandle"); incoming = text(input, "requestId"); uri = controller.skillDocuments.requireOriginal(incoming).uri; document = true; expected = target(input) }
                else { fields(input, "requestId"); val selected = picker?.takeIf { it.id == text(input, "requestId") && it.returned && it.export == null && it.owner == controller.webWindowTicket() } ?: error("请等待原系统文件选择"); uri = checkNotNull(selected.uri); document = selected.format == "document"; expected = selected.target; incoming = null; picker = null; show("selectedRequestId" to null, "selection" to null, "pickerPending" to false) }
                content(uri)
                work(done, { expected?.let { current(it) }; incoming?.let { check(controller.skillDocuments.isCurrent(it)) }
                    val inspection = activity.contentResolver.openInputStream(uri)?.use { if (document) module.inspectDocument(skillOwnerPrivateScope(), it, expected?.getString("id")) else module.inspectPackage(skillOwnerPrivateScope(), it, expected?.getString("id")) } ?: error("无法读取所选技能文件")
                    expected?.let { current(it) }; incoming?.let { check(controller.skillDocuments.isCurrent(it)) }; inspection },
                    { inspection -> review("file", FileReview(uri, document, expected, JSONObject(inspection.toString()), incoming), JSONObject().put("inspection", inspection).put("singleDocument", document).put("target", expected ?: JSONObject.NULL)) })
            }
            "skills.incoming.discard" -> { fields(input, "requestId"); controller.skillDocuments.discard(text(input, "requestId")); done(snapshot(), null) }
            "skills.import.confirm" -> {
                fields(input, "reviewHandle", "source", "confirmed"); check(input.opt("confirmed") == true); val source = text(input, "source", 1000); val reviewed = pin(text(input, "reviewHandle"), "file", true) as FileReview
                work(done, { original ->
                    reviewed.incomingId?.let { check(controller.skillDocuments.isCurrent(it)) }; val actual = reviewed.target?.let { current(it) }
                    val options = skillImportOptions(reviewed.inspection, actual?.getString("id"), source, actual); original.check()
                    activity.contentResolver.openInputStream(content(reviewed.uri))?.use { if (reviewed.document) module.importDocument(skillOwnerPrivateScope(), it, options) else module.importPackage(skillOwnerPrivateScope(), it, options) } ?: error("无法重新读取本次审阅的技能文件")
                }, { result -> reviewed.incomingId?.let { controller.skillDocuments.discard(it) }; result.put("importedDisabled", true) })
            }
            "skills.export.review" -> {
                fields(input, "skillHandle", "format"); val expected = selected(input); val format = text(input, "format"); require(format in setOf("document", "package"))
                work(done, { current(expected); check(!expected.has("origin")) { "来源型技能不能去掉来源身份导出，请回原经验页核对" }; if (format == "document") module.inspectDocumentExport(skillOwnerPrivateScope(), expected.getString("id"), expected.getInt("revision"), expected.getString("digest")) else JSONObject(expected.toString()) },
                    { result -> review("export", JSONObject(result.toString()).put("format", format), result.put("format", format)) })
            }
            "skills.chooseExport" -> { fields(input, "reviewHandle", "confirmed"); check(input.opt("confirmed") == true && control == null && picker == null); val reviewed = pin(text(input, "reviewHandle"), "export", true) as JSONObject; choose(reviewed.getString("format"), null, reviewed); done(snapshot(), null) }
            "skills.writeSelected" -> {
                fields(input, "requestId"); val selected = picker?.takeIf { it.id == text(input, "requestId") && it.returned && it.export != null && it.owner == controller.webWindowTicket() } ?: error("原保存选择已关闭或尚未返回")
                val uri = content(checkNotNull(selected.uri)); val reviewed = checkNotNull(selected.export); picker = null; show("selectedRequestId" to null, "selection" to null, "pickerPending" to false)
                work(done, { original -> original.check(); try { activity.contentResolver.openOutputStream(uri, "wt")?.let { output -> writeSkillArchive(output) { stream ->
                    if (selected.format == "document") module.exportDocument(skillOwnerPrivateScope(), reviewed.getString("id"), reviewed.getInt("revision"), reviewed.getString("digest"), reviewed.getString("documentSha256"), stream)
                    else module.exportPackage(skillOwnerPrivateScope(), reviewed.getString("id"), reviewed.getInt("revision"), reviewed.getString("digest"), stream)
                } } ?: error("无法写入所选保存位置") } catch (failure: Exception) { throw IllegalStateException((failure.message ?: "保存结果未确认") + "。目标可能为空或不完整，请先在文件应用中核对。", failure) } },
                    { it.put("saved", true) })
            }
            "skills.document.share" -> {
                fields(input, "reviewHandle", "confirmed"); check(input.opt("confirmed") == true); val reviewed = pin(text(input, "reviewHandle"), "export", true) as JSONObject; check(reviewed.getString("format") == "document"); var prepared: Intent? = null
                val discard = { prepared?.let { intent -> runCatching { controller.runPluginCatalogWork(Runnable { runCatching { discardUnsentSkillDocumentShare(activity, intent) } }) } }; Unit }
                work(done, { original -> prepared = prepareSkillDocumentShare(activity, module, reviewed, original); JSONObject().put("id", reviewed.getString("id")).put("documentSha256", reviewed.getString("documentSha256")) },
                    { value -> activity.startActivity(Intent.createChooser(checkNotNull(prepared), "分享 SKILL.md")); value.put("shareSheetOpened", true).put("deliveryConfirmed", false) }, discard)
            }
            "skills.git.inspect" -> {
                fields(input, "targetHandle", "repository", "ref", "subpath"); val expected = target(input); val source = SkillGitSource.fromInput(text(input, "repository", 1500), text(input, "ref"), input.optString("subpath")); var selected: SkillBook.SourceReview? = null
                work(done, { original -> expected?.let { current(it) }; module.inspectGitSource(skillOwnerPrivateScope(), expected?.getString("id"), source, original).also { selected = it }.view() },
                    { value -> review("source", checkNotNull(selected), value) })
            }
            "skills.history.list" -> { fields(input, "skillHandle", "beforeRevision"); val expected = selected(input); val before = number(input, "beforeRevision"); work(done, { current(expected); module.retainedRevisions(skillOwnerPrivateScope(), expected.getString("id"), before, 20) }) }
            "skills.history.inspect" -> {
                fields(input, "skillHandle", "revision"); val expected = selected(input); val revision = number(input, "revision", 1); var selected: SkillBook.SourceReview? = null
                work(done, { current(expected); module.inspectRetainedRevision(skillOwnerPrivateScope(), expected.getString("id"), revision).also { selected = it }.view() }, { value -> review("source", checkNotNull(selected), value) })
            }
            "skills.source.read" -> {
                fields(input, "reviewHandle", "path", "offset"); val selected = pin(text(input, "reviewHandle"), "source") as SkillBook.SourceReview; val path = text(input, "path", 240); val offset = number(input, "offset")
                work(done, { module.readSourceReview(skillOwnerPrivateScope(), selected, path, offset, 8000) })
            }
            "skills.source.confirm" -> {
                fields(input, "reviewHandle", "choice", "discardLocal", "confirmed"); check(input.opt("confirmed") == true); val choice = SkillBook.SourceChoice.valueOf(text(input, "choice")); val discard = input.get("discardLocal"); require(discard is Boolean)
                val selected = pin(text(input, "reviewHandle"), "source", true) as SkillBook.SourceReview; work(done, { module.applySourceReview(skillOwnerPrivateScope(), selected, choice, discard) }, { it.put("importedDisabled", true) })
            }
            "skills.batch.inspect" -> {
                fields(input, "skillHandles"); val values = input.getJSONArray("skillHandles"); require(values.length() in 1..8); val expected = (0 until values.length()).map { pin(values.getString(it), "skill") as JSONObject }; require(expected.map { it.getString("id") }.toSet().size == expected.size); var batch: SkillsModule.SourceBatch? = null
                work(done, { original -> expected.forEach { current(it) }; module.inspectTrackedSources(skillOwnerPrivateScope(), expected.map { it.getString("id") }, original).also { batch = it }; JSONObject().put("rows", checkNotNull(batch).rows()) }, { value -> review("batch", checkNotNull(batch), value) })
            }
            "skills.batch.confirm" -> { fields(input, "reviewHandle", "confirmed"); check(input.opt("confirmed") == true); val batch = pin(text(input, "reviewHandle"), "batch", true) as SkillsModule.SourceBatch; work(done, { module.applyCleanSourceUpdates(skillOwnerPrivateScope(), batch, it) }) }
            "skills.mcp.list" -> {
                fields(input, "pluginId", "installationId", "stateRevision"); val plugin = text(input, "pluginId"); val installation = text(input, "installationId"); val revision = input.opt("stateRevision"); require(revision is Int || revision is Long); val version = (revision as Number).toLong(); require(version >= 0)
                work(done, { module.listMcpSkillResources(plugin, installation, version, it) }, { value ->
                    val resources = value.optJSONArray("resources") ?: JSONArray(); for (i in 0 until resources.length()) { val resource = resources.getJSONObject(i); val choice = JSONObject().put("pluginId", plugin).put("installationId", installation).put("stateRevision", version).put("uri", resource.getString("uri")); resource.put("resourceHandle", issue("mcp", choice)) }; value
                })
            }
            "skills.mcp.inspect" -> {
                fields(input, "resourceHandle", "targetHandle"); val choice = pin(text(input, "resourceHandle"), "mcp") as JSONObject; val expected = target(input); var selected: SkillBook.SourceReview? = null
                work(done, { original -> expected?.let { current(it) }; module.inspectMcpSource(skillOwnerPrivateScope(), expected?.getString("id"), choice.getString("pluginId"), choice.getString("installationId"), choice.getLong("stateRevision"), choice.getString("uri"), original).also { selected = it }.view() }, { value -> review("source", checkNotNull(selected), value) })
            }
            else -> error("未知技能操作")
        }
    }
    private fun choose(format: String, target: JSONObject?, export: JSONObject?) {
        val selected = Picker(UUID.randomUUID().toString(), controller.webWindowTicket(), format, target, export); picker = selected
        show("selectedRequestId" to selected.id, "pickerPending" to true, "selection" to JSONObject().put("format", format).put("export", export != null).put("target", target ?: JSONObject.NULL), "error" to null)
        val callback: (Uri?) -> Unit = { uri ->
            selected.returned = true; selected.launcher?.unregister(); selected.launcher = null
            if (picker === selected && controller.webWindowTicket() == selected.owner) { selected.uri = uri; if (uri == null) { picker = null; show("selectedRequestId" to null, "pickerPending" to false, "selection" to null) } else show("pickerPending" to false) }
        }
        try {
            if (export == null) { val launcher = activity.activityResultRegistry.register("skill_file_${selected.id}", ActivityResultContracts.OpenDocument()) { uri -> callback(uri) }; selected.launcher = launcher; if (selected.returned) launcher.unregister() else launcher.launch(if (format == "document") arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream") else arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            else { val pin = if (format == "document") skillDocumentExportPin(export) else skillExportPin(export); val launcher = activity.activityResultRegistry.register("skill_file_${selected.id}", ActivityResultContracts.CreateDocument(if (format == "document") "text/markdown" else "application/zip")) { uri -> callback(uri) }; selected.launcher = launcher; if (selected.returned) launcher.unregister() else launcher.launch(pin.fileName) }
        } catch (failure: Exception) { selected.launcher?.unregister(); if (picker === selected) picker = null; show("selectedRequestId" to null, "pickerPending" to false, "selection" to null); throw failure }
    }
    companion object {
        val COMMANDS = setOf("skills.cancel", "skills.open", "skills.snapshot", "skills.close", "skills.get", "skills.read", "skills.enabled.review", "skills.policy.choices", "skills.policy.review", "skills.remove.review", "skills.removal.query", "skills.confirm", "skills.chooseFile", "skills.discardSelection", "skills.inspectSelected", "skills.incoming.inspect", "skills.incoming.discard", "skills.import.confirm", "skills.export.review", "skills.chooseExport", "skills.writeSelected", "skills.document.share", "skills.git.inspect", "skills.history.list", "skills.history.inspect", "skills.source.read", "skills.source.confirm", "skills.batch.inspect", "skills.batch.confirm", "skills.mcp.list", "skills.mcp.inspect")
    }
}
