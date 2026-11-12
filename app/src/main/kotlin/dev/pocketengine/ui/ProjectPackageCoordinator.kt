package dev.pocketengine.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.preview.ProjectPreviewBundle
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal data class ProjectPackageState(
    val checkoutId: String? = null, val projectId: String? = null, val busy: Boolean = false,
    val receivedDeviceFiles: List<JSONObject> = emptyList(),
    val artifacts: List<JSONObject> = emptyList(), val selection: JSONObject? = null,
    val artifact: JSONObject? = null, val preview: ProjectPreviewBundle.Verified? = null,
    val stagedPackages: List<JSONObject> = emptyList(), val staged: JSONObject? = null, val importPlan: JSONObject? = null, val imported: JSONObject? = null,
    val pendingFreeze: JSONObject? = null, val pendingImport: JSONObject? = null,
    val exportStatus: JSONObject? = null,
    val retryFreeze: Boolean = false, val retryImport: Boolean = false,
    val ownerPanelRequested: Boolean = false, val sourceImportRequested: Boolean = false, val reviewingTaskDelivery: Boolean = false, val taskDelivery: JSONObject? = null,
    val notice: String? = null, val error: String? = null
)

/** Owner navigation and cancellable document IO. Existing LocalModule/workspace remain authoritative. */
internal class ProjectPackageCoordinator(
    private val activity: ComposeActivity, private val app: EngineApplication,
    private val io: ExecutorService, private val main: Handler,
    private val privateOwner: () -> Boolean, private val importedCheckout: (JSONObject) -> Unit
) {
    var state by mutableStateOf(ProjectPackageState()); private set
    private var generation = 0L
    private var control: RunControl? = null
    private var future: Future<*>? = null
    private var closed = false
    private var activated = false
    private var mounted = false
    private var pickerImport: JSONObject? = null
    private var pickerExport: JSONObject? = null
    private var restoredPicker = false
    private var foreground = false
    private var exportUri: Uri? = null
    private var importUri: Uri? = null
    private class TaskExport(val pin: JSONObject, val stillSelected: () -> Boolean, var done: ((JSONObject?, String?) -> Unit)?) {
        @Volatile var selected = true
        @Volatile var writeStarted = false
        var phase = "checking"
    }
    private var taskExport: TaskExport? = null
    private val positions = linkedMapOf<String, JSONObject>()
    private fun positionKey(project: String?, checkout: String?) = project.orEmpty() + ":" + checkout.orEmpty()
    private fun retainPosition() {
        positions.remove(positionKey(state.projectId, state.checkoutId))
        if (state.pendingFreeze == null && state.pendingImport == null) return
        positions[positionKey(state.projectId, state.checkoutId)] = JSONObject().put("projectId", state.projectId.orEmpty()).put("checkoutId", state.checkoutId.orEmpty()).put("pendingFreeze", state.pendingFreeze).put("pendingImport", state.pendingImport)
    }
    private fun local() = app.features.module("local") as? LocalModule ?: error("原项目模块不可用")
    private fun requireOwner() { check(!closed && mounted && privateOwner()) { "请返回原私人项目入口操作" } }
    fun activate(project: String?, checkout: JSONObject?) {
        check(!closed && privateOwner()) { "请返回原私人项目入口操作" }; mounted = true
        val checkoutId = checkout?.getString("id")
        if (activated && state.checkoutId == checkoutId && state.projectId == project) { processPickerResults(); return }
        activated = true
        retainPosition(); cancel(); val pin = positions.remove(positionKey(project, checkoutId))
        state = ProjectPackageState(checkoutId = checkoutId, projectId = project, pendingFreeze = pin?.optJSONObject("pendingFreeze"), pendingImport = pin?.optJSONObject("pendingImport"), notice = if (pin != null) "只恢复原动作定位；未恢复确认或执行。请先核对原结果。" else null)
        refresh(); processPickerResults()
    }
    fun deactivate() { mounted = false; cancel(); closePreview() }
    private fun processPickerResults() {
        if (!foreground || closed || state.busy) return
        if (exportUri != null && (mounted || taskExport != null)) finishExport()
        if (mounted) { if (importUri != null && !state.busy) finishImport(); if (!state.busy) processTeamResult() }
    }
    fun refresh() {
        val project = state.projectId
        work { _, _ ->
            val rows = app.features.module("workspace").handle("listArtifacts", JSONObject().put("projectId", project.orEmpty())).getJSONArray("artifacts").objects().filter { it.optString("kind") == "project_bundle" }
            val staged = local().listStagedProjectPackagesForOwner().objects()
            val received = dev.pocketengine.features.attachments.AttachmentRepository.get(app).book.list().objects().filter { it.optString("principalId") == "owner" && it.optString("spaceId") == "private" && !it.optBoolean("outputSourceRetired") && it.optJSONObject("source")?.optString("kind") == "device_received_file" }
            return@work { state = state.copy(artifacts = rows, stagedPackages = staged, receivedDeviceFiles = received) }
        }
    }
    fun closeOwnerPanel() { state = state.copy(ownerPanelRequested = false, sourceImportRequested = false); closePreview() }
    fun reviewDelivery(taskId: String, approvalId: String) {
        requireOwner()
        if (state.busy) { state = state.copy(notice = "已有项目包操作正在进行，请稍后再打开方案。"); return }
        val project = state.projectId; val checkout = state.checkoutId
        state = state.copy(ownerPanelRequested = true, sourceImportRequested = false, reviewingTaskDelivery = true, selection = null, artifact = null, taskDelivery = null)
        work { _, _ ->
            val plan = app.tasks.projectPackagePlanForOwner(taskId, approvalId)
            check(plan.getString("projectId") == project && plan.getString("checkoutId") == checkout) { "原方案不属于当前选定项目" }
            val selected = JSONObject().put("manifest", plan.getJSONObject("packageManifest")).put("manifestIdentity", dev.pocketengine.features.local.project.ProjectPackageDelivery.selectionSummary(plan).getString("manifestIdentity")).put("projectId", project).put("checkoutId", checkout)
            return@work { state = state.copy(selection = selected, notice = "这是原任务待审批的完整清单。核对后返回原任务决定是否批准；这里不会再次保存或执行。") }
        }
    }
    private var pendingTeamResult: dev.pocketengine.store.TaskStore.TeamProjectPackageSelection? = null
    private var pendingTeamIntent: (() -> Boolean)? = null
    /** Called only from the native team's explicit result button, before existing private-project navigation. */
    fun queueFromTeam(selected: dev.pocketengine.store.TaskStore.TeamProjectPackageSelection, stillRequested: () -> Boolean) {
        check(!closed && !state.busy && state.pendingFreeze == null && state.pendingImport == null && pickerImport == null && pickerExport == null) { "先完成或核对当前项目包操作；没有改变原项目选择" }
        pendingTeamResult = selected; pendingTeamIntent = stillRequested
    }
    private fun processTeamResult() {
        val selected = pendingTeamResult ?: return
        if (pendingTeamIntent?.invoke() != true) { pendingTeamResult = null; pendingTeamIntent = null; state = state.copy(notice = "固定团队成果仍保留；你已切换导航，未强制打开原面板。"); return }
        if (!foreground || !mounted || closed || !privateOwner() || state.busy) return
        val project = runCatching { selected.view().getJSONObject("evidence").getString("projectId") }.getOrNull() ?: return
        if (state.projectId != project) return
        pendingTeamResult = null; pendingTeamIntent = null
        openFromTeam(selected)
    }
    fun openFromTeam(selected: dev.pocketengine.store.TaskStore.TeamProjectPackageSelection) {
        requireOwner()
        if (state.busy) { state = state.copy(notice = "已有项目包操作正在进行；未切换原项目/checkout。"); return }
        val expectedProject = state.projectId
        state = state.copy(ownerPanelRequested = true, sourceImportRequested = false, reviewingTaskDelivery = false, selection = null, artifact = null, taskDelivery = null)
        work { _, _ ->
            selected.checkCurrent()
            val facts = selected.view(); val evidence = facts.getJSONObject("evidence")
            check(evidence.getString("projectId") == expectedProject) { "此固定团队成果不属于当前原项目" }
            val pin = evidence.getJSONObject("packageArtifact")
            val actual = local().handle("project.package.get", JSONObject().put("artifactId", pin.getString("id")).put("sha256", pin.getString("sha256")))
            val reference = dev.pocketengine.features.local.project.ProjectPackageDelivery.requireArtifact(evidence, actual)
            selected.checkCurrent()
            return@work { state = state.copy(artifact = actual, preview = null, taskDelivery = reference.json(), notice = "原团队的同版检查与固定完整包。没有切换或覆盖父活跃checkout；不是当前主项目已测试的声明。预览使用成果真实checkout，导出/接收另需你的明确选择。") }
        }
    }
    fun openFromTask(taskId: String, operationId: String) {
        requireOwner()
        if (state.busy) { state = state.copy(notice = "已有项目包操作正在进行，请稍后再打开成果。"); return }
        val project = state.projectId; val checkout = state.checkoutId
        state = state.copy(ownerPanelRequested = true, sourceImportRequested = false, reviewingTaskDelivery = false, selection = null, artifact = null, taskDelivery = null)
        work { _, _ ->
            val evidence = app.tasks.projectPackageEvidenceForOwner(taskId, operationId)
            check(evidence.getString("projectId") == project && evidence.getString("checkoutId") == checkout) { "原成果不属于当前选定项目" }
            val pin = evidence.getJSONObject("packageArtifact")
            val actual = local().handle("project.package.get", JSONObject().put("artifactId", pin.getString("id")).put("sha256", pin.getString("sha256")))
            val reference = dev.pocketengine.features.local.project.ProjectPackageDelivery.requireArtifact(evidence, actual)
            return@work { state = state.copy(artifact = actual, preview = null, taskDelivery = reference.json(), notice = "已打开这次任务保存的原固定版本。当前项目继续修改不会替换它；导出与导入仍需你另行选择。") }
        }
    }
    fun forgetSelection() { if (state.pendingFreeze == null) state = state.copy(selection = null) }
    fun forgetImportPlan() { if (state.pendingImport == null) state = state.copy(importPlan = null) }
    fun review(mode: String, entry: String, includeGenerated: Boolean) {
        if (state.busy) return
        state = state.copy(reviewingTaskDelivery = false, taskDelivery = null)
        if (state.pendingFreeze != null) { state = state.copy(error = "请先核对原保存动作；不会另起一次保存"); return }
        val checkout = state.checkoutId ?: return
        work { _, _ ->
            val value = local().handle("project.package.plan", JSONObject().put("checkoutId", checkout).put("mode", mode).put("entryPath", if (mode == "web_output") entry else "").put("includeGeneratedRoots", includeGenerated))
            return@work { state = state.copy(selection = value, error = null, notice = null) }
        }
    }
    fun freeze() {
        if (state.busy || state.pendingFreeze != null || state.reviewingTaskDelivery) return
        val chosen = state.selection ?: return
        val manifest = chosen.getJSONObject("manifest")
        val pin = JSONObject().put("checkoutId", chosen.getString("checkoutId")).put("expectedSourceDigest", chosen.getString("sourceDigest")).put("expectedManifestIdentity", chosen.getString("manifestIdentity"))
            .put("mode", manifest.getString("mode")).put("entryPath", manifest.getString("entryPath")).put("includeGeneratedRoots", manifest.getJSONObject("policy").getBoolean("generatedRootsIncluded")).put("clientSnapshotId", UUID.randomUUID().toString())
        state = state.copy(pendingFreeze = pin, retryFreeze = false)
        freezeOriginal(pin)
    }
    private fun freezeOriginal(pin: JSONObject) = work { _, _ ->
        val saved = local().handle("project.package.freeze", JSONObject(pin.toString()))
        val value = local().handle("project.package.get", JSONObject().put("artifactId", saved.getString("id")).put("sha256", saved.getString("sha256")))
        return@work { state = state.copy(pendingFreeze = null, retryFreeze = false, selection = null, artifact = value, preview = null, artifacts = (state.artifacts.filterNot { it.optString("id") == value.getString("id") } + value), notice = "已保存固定文件版本。它不代表构建成功，也未替换原项目。") }
    }
    fun queryFreeze() {
        val pin = state.pendingFreeze ?: return
        work { _, _ ->
            var value = local().handle("project.package.find", JSONObject(pin.toString()))
            if (value.optBoolean("found")) value = local().handle("project.package.get", JSONObject().put("artifactId", value.getString("id")).put("sha256", value.getString("sha256"))).put("found", true)
            return@work { state = if (value.optBoolean("found")) state.copy(pendingFreeze = null, artifact = value, preview = null, retryFreeze = false, notice = "已找到这个原保存动作的固定成果；没有再次保存") else state.copy(retryFreeze = true, notice = "原动作尚未登记成果。可明确重试原动作；若实际文件变化会拒绝。") }
        }
    }
    fun retryFreeze() { if (state.retryFreeze) state.pendingFreeze?.let(::freezeOriginal) }
    fun open(artifact: JSONObject) {
        if (state.projectId != null && artifact.optString("projectId") != state.projectId) return
        val id = artifact.getString("id"); val sha = artifact.getString("sha256")
        work { _, _ ->
            val actual = local().handle("project.package.get", JSONObject().put("artifactId", id).put("sha256", sha))
            return@work { state = state.copy(artifact = actual, preview = null, notice = "读取的是原固定版本，不是当前源码或最新构建。") }
        }
    }
    fun preview(entry: String) {
        val artifact = state.artifact ?: return
        work { _, _ ->
            val actual = local().handle("project.package.preview", JSONObject().put("artifactId", artifact.getString("id")).put("sha256", artifact.getString("sha256")).put("entryPath", entry))
            val frozen = local().projectPreviewForOwner(actual.getString("previewId"), artifact.getString("checkoutId"))
            check(frozen.artifactId == artifact.getString("id") && frozen.artifactSha256 == artifact.getString("sha256")) { "固定预览与原成果版本不一致" }
            return@work { state = state.copy(preview = frozen) }
        }
    }
    fun removeArtifact(selected: JSONObject) {
        if (state.busy) return
        closePreview()
        val id = selected.getString("id"); val sha = selected.getString("sha256")
        work { _, _ ->
            val result = local().removeProjectPackageForOwner(id, sha)
            return@work { state = state.copy(artifact = state.artifact?.takeUnless { it.optString("id") == id }, artifacts = if (result.optBoolean("payloadCleanupComplete")) state.artifacts.filterNot { it.optString("id") == id } else state.artifacts.map { if (it.optString("id") == id) result else it }, notice = if (result.optBoolean("payloadCleanupComplete")) "仅移除了所选固定项目包版本；实际项目、原任务与工具环境保留。" else result.getString("message")) }
        }
    }
    fun closePreview() { state = state.copy(preview = null) }
    fun previewError(message: String) { state = state.copy(error = message.take(500)) }
    fun exportSnapshot(): JSONObject? = state.exportStatus?.let { JSONObject(it.toString()) }
    private fun taskExportStatus(selected: TaskExport, phase: String, message: String, bytes: Long? = null) {
        if (taskExport !== selected) return
        selected.phase = phase
        val pin = selected.pin
        val result = JSONObject().put("kind", "project_package_export").put("requestId", pin.getString("requestId"))
            .put("taskId", pin.getString("taskId")).put("operationId", pin.getString("operationId"))
            .put("artifactId", pin.getString("artifactId")).put("artifactSha256", pin.getString("sha256"))
            .put("projectId", pin.getString("projectId")).put("status", phase).put("message", message)
            .put("exported", phase == "exported")
        if (bytes != null) result.put("bytes", bytes)
        state = state.copy(exportStatus = result, notice = message)
    }
    private fun completeTaskExport(selected: TaskExport, result: JSONObject?, error: String?) {
        val done = selected.done ?: return
        selected.done = null; done(result, error)
    }
    private fun taskExportCurrent(selected: TaskExport) = !closed && foreground && privateOwner() &&
        taskExport === selected && selected.selected && selected.stillSelected()
    private fun requireTaskExportSource(selected: TaskExport): JSONObject {
        val pin = selected.pin
        val source = app.tasks.ownerPackagePreviewSource(pin.getString("taskId"), pin.getString("operationId"))
        check(source.getString("taskId") == pin.getString("taskId") && source.getString("packageOperationId") == pin.getString("operationId") &&
            source.getString("projectId") == pin.getString("projectId") && source.getString("artifactId") == pin.getString("artifactId") &&
            source.getString("artifactSha256") == pin.getString("sha256")) { "原任务、操作或固定包版本已变化，未改用其他成果" }
        if (pin.has("sourceFingerprint")) check(pin.getString("sourceFingerprint") == dev.pocketengine.store.TaskStore.capabilityFingerprint(source)) { "原固定包来源登记已变化，未导出其他版本" }
        return source
    }
    /** Explicit Web selection only. The old native package panel is not activated or mounted. */
    fun offerTaskExport(task: String, operation: String, artifactId: String, artifactSha256: String, project: String,
        ownerCurrent: () -> Boolean, stillSelected: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        check(!closed && foreground && privateOwner() && ownerCurrent() && stillSelected()) { "请从原私人项目选择固定包导出" }
        check(!state.busy && pickerExport == null && pickerImport == null && exportUri == null) { "原文件操作尚未结束，请先核对原结果，没有重新打开文件选择器" }
        require(task.isNotBlank() && operation.isNotBlank() && artifactId.isNotBlank() && project.isNotBlank() && artifactSha256.matches(Regex("[a-f0-9]{64}"))) { "固定包导出身份不完整" }
        val pin = JSONObject().put("requestId", UUID.randomUUID().toString()).put("taskId", task).put("operationId", operation)
            .put("artifactId", artifactId).put("sha256", artifactSha256).put("projectId", project)
        val selected = TaskExport(pin, stillSelected, done); taskExport = selected
        taskExportStatus(selected, "checking", "正在核对这次任务保存的固定包。")
        work(ownerCurrent = { taskExportCurrent(selected) && ownerCurrent() }, onFailure = { failure ->
            if (selected.phase == "checking") taskExportStatus(selected, "rejected", failure.message?.take(600) ?: "原固定包暂不可核对，未打开导出位置")
            completeTaskExport(selected, null, state.exportStatus?.optString("message") ?: "原固定包导出未开始")
        }) { c, _ ->
            val source = requireTaskExportSource(selected)
            val artifact = local().handle("project.package.get", JSONObject().put("artifactId", artifactId).put("sha256", artifactSha256))
            dev.pocketengine.features.local.project.ProjectPackageDelivery.requireArtifact(source, artifact)
            pin.put("checkoutId", source.getString("checkoutId")).put("sourceFingerprint", dev.pocketengine.store.TaskStore.capabilityFingerprint(source))
            requireTaskExportSource(selected); c.check()
            return@work {
                pickerExport = pin; restoredPicker = false
                taskExportStatus(selected, "document_requested", "请选择固定包的保存位置；导出尚未完成。")
                try {
                    activity.createProjectPackageDocument(exportName(artifact))
                    completeTaskExport(selected, exportSnapshot(), null)
                } catch (failure: Exception) {
                    if (pickerExport === pin) pickerExport = null
                    taskExportStatus(selected, "rejected", "系统文件选择器未能打开，未写入目标。")
                    completeTaskExport(selected, null, failure.message ?: "系统文件选择器未能打开")
                }
            }
        }
    }
    /** Retire only the original Web selection; a pending picker is consumed once when it returns. */
    fun invalidateTaskExportSelection() {
        val selected = taskExport ?: return
        if (selected.phase !in setOf("checking", "document_requested", "selection_returned", "writing")) return
        selected.selected = false
        if (selected.phase == "checking" || selected.phase == "writing") cancel()
        else taskExportStatus(selected, "cancelled", "原固定包窗口已变化，未向所选位置写入。")
        completeTaskExport(selected, null, "原固定包窗口已变化，导出未继续")
    }
    private fun exportName(artifact: JSONObject) = artifact.optString("name", "PocketWorld").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80) + ".pocketworld.zip"
    fun offerExport() {
        requireOwner(); if (state.busy || pickerExport != null) return
        val artifact = state.artifact ?: return
        taskExport = null
        pickerExport = JSONObject().put("artifactId", artifact.getString("id")).put("sha256", artifact.getString("sha256")).put("checkoutId", state.checkoutId.orEmpty()).put("projectId", state.projectId.orEmpty())
        restoredPicker = false
        activity.createProjectPackageDocument(exportName(artifact))
    }
    fun exportResult(uri: Uri?) {
        val pin = pickerExport ?: return
        val selected = taskExport?.takeIf { it.pin === pin }
        if (pin.has("taskId")) {
            if (selected == null || restoredPicker || !selected.selected || selected.phase != "document_requested") {
                pickerExport = null; exportUri = null
                if (selected != null) taskExportStatus(selected, "cancelled", "原固定包窗口已变化，未向所选位置写入。")
                return
            }
            if (uri == null) taskExportStatus(selected, "cancelled", "已取消选择保存位置，固定包未导出。")
            else taskExportStatus(selected, "selection_returned", "已收到保存位置，正在重新核对原固定包。")
        }
        exportUri = uri
        if (uri == null) pickerExport = null else processPickerResults()
    }
    private fun finishExport() {
        if (!foreground || closed || state.busy) return
        val uri = exportUri; exportUri = null
        val pin = pickerExport; pickerExport = null
        if (uri == null || pin == null) return
        val selected = taskExport?.takeIf { it.pin === pin }
        fun reject(message: String) { state = state.copy(error = message); if (selected != null) taskExportStatus(selected, "rejected", message) }
        val currentSelection = if (selected == null) !pin.has("taskId") && matches(pin) else taskExportCurrent(selected)
        if (restoredPicker || !currentSelection) { reject("原导出入口已变化。请重读原固定成果，再明确选择目标；未写入所选位置。"); return }
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) { reject("系统未授予可用 content 文件引用，未读取或写入其他路径"); return }
        if (selected != null) taskExportStatus(selected, "writing", "正在导出所选固定包，请等待保存结果。")
        work(ownerCurrent = selected?.let { original -> { taskExportCurrent(original) } }, onFailure = selected?.let { original -> { failure: Exception ->
            if (original.phase == "writing") taskExportStatus(original, if (original.writeStarted) "unknown" else "rejected",
                if (original.writeStarted) "导出未完整确认，目标可能已有部分内容；请核对所选文件，没有自动重写。" else failure.message?.take(600) ?: "原固定包导出未开始")
        } }) { c, _ ->
            val source = selected?.let(::requireTaskExportSource)
            val artifact = local().handle("project.package.get", JSONObject().put("artifactId", pin.getString("artifactId")).put("sha256", pin.getString("sha256")))
            if (source != null) dev.pocketengine.features.local.project.ProjectPackageDelivery.requireArtifact(source, artifact)
            fun requireCurrent() { c.check(); if (selected != null) check(taskExportCurrent(selected)) { "原固定包导出窗口已变化" } }
            if (selected != null) requireTaskExportSource(selected)
            requireCurrent(); if (selected != null) selected.writeStarted = true
            val opened = app.contentResolver.openOutputStream(uri, "wt") ?: error("所选位置无法写入")
            val cancel = Runnable { runCatching { opened.close() } }; c.onCancel(cancel)
            val bytes = try { opened.use { raw ->
                val checked = object : java.io.FilterOutputStream(raw) {
                    override fun write(value: Int) { requireCurrent(); out.write(value) }
                    override fun write(value: ByteArray, offset: Int, length: Int) { requireCurrent(); out.write(value, offset, length) }
                }
                val copied = local().exportProjectPackageForOwner(pin.getString("artifactId"), pin.getString("sha256"), checked)
                if (selected != null) { requireTaskExportSource(selected); check(copied == source!!.getLong("archiveBytes")) { "实际导出字节数与原固定包不符" } }
                requireCurrent(); raw.flush(); copied
            } } catch (failure: Exception) { throw IllegalStateException("导出未完整确认，所选目标可能已有部分内容；请核对原成果和目标", failure) }
            finally { c.removeCancelAction(cancel) }
            return@work {
                val message = "已导出原固定项目包（$bytes 字节）。"
                state = state.copy(notice = message)
                if (selected != null) taskExportStatus(selected, "exported", message, bytes)
            }
        }
    }
    fun openSourceImport() {
        requireOwner()
        if (state.busy) { state = state.copy(notice = "请先完成当前读取，再选择现有源码；当前选择未改变。"); return }
        if (state.pendingFreeze != null || state.pendingImport != null) { state = state.copy(error = "请先核对未完成的保存或导入结果。"); return }
        closePreview()
        state = state.copy(ownerPanelRequested = true, sourceImportRequested = true, reviewingTaskDelivery = false, taskDelivery = null, selection = null, error = null)
    }
    fun pickSourceDirectory(generated: Boolean) = pickSource("saf_tree", generated, false)
    fun pickSourceZip(generated: Boolean, stripRoot: Boolean) = pickSource("external_zip", generated, stripRoot)
    private fun pickSource(kind: String, generated: Boolean, stripRoot: Boolean) {
        requireOwner(); if (state.busy || pickerImport != null || state.pendingFreeze != null || state.pendingImport != null) return
        pickerImport = JSONObject().put("selectionId", UUID.randomUUID().toString()).put("checkoutId", state.checkoutId.orEmpty()).put("projectId", state.projectId.orEmpty()).put("sourceKind", kind).put("includeGeneratedRoots", generated).put("stripSingleRoot", stripRoot)
        restoredPicker = false
        if (kind == "saf_tree") activity.pickProjectSourceDirectory() else activity.pickProjectPackageDocument()
    }
    fun clearInterruptedSources() {
        if (state.busy) return
        work { _, _ -> val actual = local().clearInterruptedSourceImportsForOwner(); return@work { state = state.copy(notice = "已清理 ${actual.getInt("removedIncompleteCaches")} 项未完成读取；完整选择和项目保留。") } }
    }
    fun pickImport() {
        requireOwner(); if (state.busy || pickerImport != null) return
        pickerImport = JSONObject().put("selectionId", UUID.randomUUID().toString()).put("checkoutId", state.checkoutId.orEmpty()).put("projectId", state.projectId.orEmpty())
        restoredPicker = false; activity.pickProjectPackageDocument()
    }
    fun importResult(uri: Uri?) { importUri = uri; if (uri == null) pickerImport = null else processPickerResults() }
    private fun finishImport() {
        if (!foreground || !mounted || state.busy) return
        val uri = importUri; importUri = null
        val pin = pickerImport; pickerImport = null
        if (uri == null || pin == null) return
        if (restoredPicker || !matches(pin)) { state = state.copy(error = "原项目入口已变化，请重新选择项目包；未导入。"); return }
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) { state = state.copy(error = "系统未授予可用 content 文件引用，未读取或写入其他路径"); return }
        work { c, _ ->
            if (pin.optString("sourceKind") == "saf_tree") {
                val actual = dev.pocketengine.features.local.project.android.AndroidProjectSourceTree(app, uri, c).use { tree -> local().stageSourceTreeForOwner(tree, tree.sourceLabel(), pin.getString("selectionId"), pin.getBoolean("includeGeneratedRoots"), c) }
                return@work { state = state.copy(staged = actual, importPlan = null, sourceImportRequested = true, notice = "目录已完整读取并复核。请核对清单，再明确创建新项目；未执行代码。") }
            }
            val sourceLabel = if (pin.optString("sourceKind") == "external_zip") runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { row -> if (row.moveToFirst()) row.getString(0) else null } }.getOrNull() ?: "已有ZIP源码" else "PocketWorld项目包"
            val opened = app.contentResolver.openInputStream(uri) ?: error("所选项目包无法读取")
            val cancel = Runnable { runCatching { opened.close() } }; c.onCancel(cancel)
            val actual = try { opened.use { raw ->
                val checked = object : java.io.FilterInputStream(raw) {
                    override fun read(): Int { c.check(); return super.read() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { c.check(); return super.read(bytes, offset, length) }
                }
                if (pin.optString("sourceKind") == "external_zip") local().stageSourceZipForOwner(checked, sourceLabel, pin.getString("selectionId"), pin.getBoolean("stripSingleRoot"), pin.getBoolean("includeGeneratedRoots"), c)
                else local().stageProjectPackageForOwner(checked, pin.getString("selectionId"))
            } } finally { c.removeCancelAction(cancel) }
            return@work { state = state.copy(staged = actual, importPlan = null, notice = if (actual.has("sourceEvidence")) "普通ZIP源码已完整读取。请核对实际范围，再明确创建新项目；未执行代码。" else "项目包已读取，请核对后明确导入；尚未运行。") }
        }
    }
    /** IDs/digests come from the original Book list, never a remote path or model JSON. */
    fun selectDevicePackage(attachmentId: String, sha256: String) {
        requireOwner(); if (state.busy || state.pendingImport != null || state.pendingFreeze != null) return
        val selected = state.receivedDeviceFiles.firstOrNull { it.optString("id") == attachmentId && it.optString("sha256") == sha256 } ?: error("原设备文件列表已变化，请重新读取")
        work { c, _ ->
            val actual = local().stageDeviceProjectAttachmentForOwner(selected.getString("id"), selected.getString("sha256"), c)
            return@work { state = state.copy(staged = actual, importPlan = null, imported = null, notice = "已从原设备附件校验完整项目包；请审阅清单与目标副本，尚未导入或运行。") }
        }
    }
    fun planImport(name: String, sameProject: Boolean) {
        if (state.pendingImport != null) { state = state.copy(error = "请先核对原导入动作，未另起导入"); return }
        val selected = state.staged ?: return; val target = if (sameProject) state.checkoutId else null
        work { _, _ ->
            val value = local().planProjectPackageImportForOwner(selected.getString("selectionId"), selected.getString("sha256"), name, target)
            return@work { state = state.copy(importPlan = value, notice = value.getString("message")) }
        }
    }
    fun confirmImport() {
        if (state.busy || state.pendingImport != null) return
        val pin = state.importPlan?.getJSONObject("plan") ?: return
        state = state.copy(pendingImport = JSONObject(pin.toString()), retryImport = false)
        importOriginal(pin)
    }
    private fun importOriginal(pin: JSONObject) = work { _, _ ->
        val value = local().importProjectPackageForOwner(JSONObject(pin.toString())); app.features.sendState()
        return@work { state = state.copy(pendingImport = null, retryImport = false, importPlan = null, imported = value, notice = "已创建新的实际工作副本，原目录未覆盖；未恢复旧任务或运行授权。明确打开新副本后再开始项目任务。") }
    }
    fun queryImport() {
        val pin = state.pendingImport ?: return
        work { _, _ ->
            val value = local().findProjectPackageImportForOwner(JSONObject(pin.toString())); if (value.optBoolean("found")) app.features.sendState()
            return@work { state = if (value.optBoolean("found")) state.copy(pendingImport = null, imported = value, retryImport = false, notice = "已找到原导入动作创建的副本；未重复导入") else state.copy(retryImport = true, notice = "原动作未发布副本。可明确重试这个原方案；目标变化仍会拒绝。") }
        }
    }
    fun retryImport() { if (state.retryImport) state.pendingImport?.let(::importOriginal) }
    fun selectStaged(pin: JSONObject) {
        if (state.pendingImport != null) return
        work { _, _ ->
            val selected = if (pin.optBoolean("available")) local().inspectStagedProjectPackageForOwner(pin.getString("selectionId"), pin.getString("sha256")) else JSONObject(pin.toString())
            return@work { state = state.copy(staged = selected, importPlan = null) }
        }
    }
    fun discardStage() {
        if (state.pendingImport != null) return
        val pin = state.staged ?: return
        work { _, _ -> local().discardStagedProjectPackageForOwner(pin.getString("selectionId"), pin.getString("sha256")); return@work { state = state.copy(staged = null, stagedPackages = state.stagedPackages.filterNot { it.optString("selectionId") == pin.getString("selectionId") }, importPlan = null, notice = "仅移除了这份导入临时包；未删除项目、固定成果或环境。") } }
    }
    fun continueImported() {
        val selected = state.imported ?: return
        work { _, _ ->
            val actual = local().handle("changes", JSONObject().put("checkoutId", selected.getString("id")))
            check(actual.getString("projectId") == selected.getString("projectId")) { "原导入副本身份已不可核对，未打开其他项目" }
            return@work { importedCheckout(JSONObject(selected.toString())) }
        }
    }
    fun foreground(visible: Boolean) {
        foreground = visible
        if (!visible) { cancel(); closePreview() }
        else processPickerResults()
    }
    fun leavePrivate() { invalidateTaskExportSelection(); pendingTeamResult = null; pendingTeamIntent = null; mounted = false; retainPosition(); cancel(); state = state.copy(preview = null, artifact = null, selection = null, staged = null, importPlan = null) }
    fun cancel() {
        generation++; control?.cancel(); future?.cancel(true); control = null; future = null
        taskExport?.let { selected ->
            if (selected.phase == "checking") {
                selected.selected = false
                taskExportStatus(selected, "cancelled", "原固定包核对已停止，未打开保存位置。")
                completeTaskExport(selected, null, "原固定包核对已停止，未开始导出")
            } else if (selected.phase == "writing") {
                selected.selected = false
                taskExportStatus(selected, "unknown", "导出结果尚未完整确认；请核对所选文件，没有自动重写。")
            }
        }
        state = state.copy(busy = false, notice = if (state.pendingFreeze != null || state.pendingImport != null) "等待已停止。写入结果尚需核对原动作，未自动重试。" else state.notice)
    }
    fun detach() { invalidateTaskExportSelection(); cancel(); closed = true }
    private fun matches(pin: JSONObject) = !closed && mounted && privateOwner() && pin.optString("checkoutId") == state.checkoutId.orEmpty() && pin.optString("projectId") == state.projectId.orEmpty()
    private fun work(ownerCurrent: (() -> Boolean)? = null, onFailure: ((Exception) -> Unit)? = null, call: (RunControl, Long) -> (() -> Unit)) {
        if (ownerCurrent == null) requireOwner() else check(!closed && privateOwner() && ownerCurrent()) { "原固定包导出窗口已变化" }
        if (state.busy) return
        val token = ++generation; val checkout = state.checkoutId; val project = state.projectId
        fun current(): Boolean {
            if (closed || generation != token || !privateOwner()) return false
            return if (ownerCurrent == null) mounted && state.checkoutId == checkout && state.projectId == project else ownerCurrent()
        }
        val c = RunControl(); control = c; state = state.copy(busy = true, error = null)
        try { future = io.submit {
            try {
                c.check(); check(current()) { "原项目包操作窗口已变化" }; val update = call(c, token); c.check()
                main.post {
                    if (current()) { state = state.copy(busy = false); update() }
                    else if (onFailure != null) { if (!closed && generation == token) state = state.copy(busy = false); onFailure(IllegalStateException("原固定包导出窗口已变化，未继续原操作")) }
                    if (control === c) { control = null; future = null }; c.cancel(); processPickerResults()
                }
            } catch (failure: Exception) {
                main.post { if (!closed && generation == token) state = state.copy(busy = false, error = if (c.isCancelled) "等待已取消；有写入动作时请核对原结果" else failure.message?.take(600) ?: "原项目包操作未确认，请保留原定位再核对"); onFailure?.invoke(failure); if (control === c) { control = null; future = null }; c.cancel(); processPickerResults() }
            }
        } } catch (failure: java.util.concurrent.RejectedExecutionException) {
            if (control === c) { control = null; future = null }; c.cancel(); state = state.copy(busy = false)
            if (onFailure != null) onFailure(failure) else throw failure
        }
    }
    /** Small native locators only. No file bytes, URI permissions, confirmation or auto-execution. */
    fun save(out: Bundle) {
        retainPosition()
        out.putString("projectPackageActions", org.json.JSONArray(positions.values.toList().takeLast(41)).toString())
        pickerImport?.let { out.putString("projectPackageImportPicker", it.toString()) }; pickerExport?.let { out.putString("projectPackageExportPicker", it.toString()) }
        state.exportStatus?.let { out.putString("projectPackageExportStatus", it.toString()) }
    }
    fun restore(saved: Bundle?) {
        runCatching {
            saved?.getString("projectPackageActions")?.takeIf { it.length <= 131072 }?.let { text ->
                org.json.JSONArray(text).objects().take(41).forEach { pin -> positions[positionKey(pin.optString("projectId"), pin.optString("checkoutId"))] = pin }
            }
            pickerImport = saved?.getString("projectPackageImportPicker")?.takeIf { it.length <= 1024 }?.let(::JSONObject)
            pickerExport = saved?.getString("projectPackageExportPicker")?.takeIf { it.length <= 4096 }?.let(::JSONObject)
            restoredPicker = pickerImport != null || pickerExport != null
            saved?.getString("projectPackageExportStatus")?.takeIf { it.length <= 4096 }?.let { text ->
                val status = JSONObject(text)
                if (status.optString("kind") == "project_package_export") {
                    if (status.optString("status") in setOf("checking", "document_requested", "selection_returned", "writing")) {
                        status.put("status", "unknown").put("exported", false).put("message", "原导出结果尚未确认；请核对所选文件，没有恢复写入或重新打开选择器。")
                    }
                    state = state.copy(exportStatus = status)
                }
            }
        }.onFailure { positions.clear(); pickerImport = null; pickerExport = null }
    }
}
