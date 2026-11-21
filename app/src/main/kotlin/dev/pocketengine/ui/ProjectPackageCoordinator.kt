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
import dev.pocketengine.features.local.project.ProjectSourceImport.ZipRoot
import dev.pocketengine.features.local.project.ProjectTaskSourceOrigin
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
    val retryFreeze: Boolean = false, val retryImport: Boolean = false, val missingSourceAction: String? = null,
    val ownerPanelRequested: Boolean = false, val sourceImportRequested: Boolean = false, val reviewingTaskDelivery: Boolean = false, val taskDelivery: JSONObject? = null,
    val notice: String? = null, val error: String? = null, val sourceSkipCandidate: String? = null
)

internal data class ProjectSourceRecoveryPin(val projectId: String, val actionId: String, val planDigest: String) {
    fun matches(plan: JSONObject) = plan.opt("clientImportId") == actionId && plan.opt("planDigest") == planDigest
}

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
    private var pickerImport by mutableStateOf<JSONObject?>(null)
    private var webSourcePin: JSONObject? = null
    private var preferredWebSource: Pair<String, String>? = null
    var webSourceRecovery: ProjectSourceRecoveryPin? = null; private set
    private class HostSourceWait(val task: String, val token: String, val done: (JSONObject?, String?) -> Unit) { @Volatile var active = true }
    private var hostSourceWait: HostSourceWait? = null
    private fun finishHostSource(wait: HostSourceWait, value: JSONObject?, error: String?) {
        if (hostSourceWait !== wait) return
        wait.active = false; hostSourceWait = null; wait.done(value, error)
    }
    fun cancelHostSourceWait(task: String, token: String) {
        if (hostSourceWait?.let { it.task == task && it.token == token } == true) cancel()
    }
    val webSourcePickerPending get() = pickerImport != null
    private var pickerExport: JSONObject? = null
    private var restoredPicker = false
    private var foreground = false
    private var exportUri: Uri? = null
    private var importUri: Uri? = null
    private class TaskExport(val pin: JSONObject, var stillSelected: () -> Boolean, var done: ((JSONObject?, String?) -> Unit)?,
        val team: dev.pocketengine.store.TaskStore.TeamProjectPackageSelection? = null) {
        // An Android picker temporarily retires Teams reading handles. Its result is NOT permission
        // to write after a page switch. Only a new click in the exact original result consumes it.
        @Volatile var teamTarget: Uri? = null
        var teamTargetName: String? = null
        val targetProbe = RunControl()
        @Volatile var selected = true
        @Volatile var writeStarted = false
        var phase = "checking"
    }
    @Volatile private var taskExport: TaskExport? = null
    private val positions = linkedMapOf<String, JSONObject>()
    private fun positionKey(project: String?, checkout: String?) = project.orEmpty() + ":" + checkout.orEmpty()
    private fun retainPosition() {
        positions.remove(positionKey(state.projectId, state.checkoutId))
        if (state.pendingFreeze == null && state.pendingImport == null) return
        positions[positionKey(state.projectId, state.checkoutId)] = JSONObject().put("projectId", state.projectId.orEmpty()).put("checkoutId", state.checkoutId.orEmpty()).put("pendingFreeze", state.pendingFreeze).put("pendingImport", state.pendingImport)
    }
    private fun local() = app.features.module("local") as? LocalModule ?: error("原项目模块不可用")
    fun pendingWebSourceForProject(project: String): JSONObject? = local().pendingPhoneSourceForProject(project)
    private fun requireOwner() { check(!closed && mounted && privateOwner()) { "请返回原私人项目入口操作" } }
    fun activate(project: String?, checkout: JSONObject?, sourcesOnly: Boolean = false, prepared: Pair<String, String>? = null,
        readSettledSource: Boolean = false) {
        check(!closed && privateOwner()) { "请返回原私人项目入口操作" }; mounted = true
        val checkoutId = checkout?.getString("id")
        if (activated && state.checkoutId == checkoutId && state.projectId == project) { if (sourcesOnly) refreshSourceStages(prepared, readSettled = readSettledSource); processPickerResults(); return }
        activated = true
        retainPosition(); cancel(); val pin = positions.remove(positionKey(project, checkoutId))
        state = ProjectPackageState(checkoutId = checkoutId, projectId = project, exportStatus = state.exportStatus, pendingFreeze = pin?.optJSONObject("pendingFreeze"), pendingImport = pin?.optJSONObject("pendingImport"), notice = if (pin != null) "只恢复原动作定位；未恢复确认或执行。请先核对原结果。" else null)
        if (sourcesOnly) refreshSourceStages(prepared, readSettled = readSettledSource) else refresh(); processPickerResults()
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
    fun exportSnapshot(): JSONObject? = state.exportStatus?.takeUnless { it.has("teamRunId") }?.let { JSONObject(it.toString()) }
    /** Display only inside the current original Teams result. Never publish its target name to other pages. */
    fun teamExportSnapshot(descriptor: JSONObject): JSONObject? {
        val row = state.exportStatus ?: return null
        if (!row.has("teamRunId") || listOf("teamRunId", "taskId", "operationId", "runId", "epoch", "projectId", "checkoutId", "artifactId").any { row.opt(it) != descriptor.opt(it) } ||
            row.opt("artifactSha256") != descriptor.opt("archiveSha256")) return null
        return JSONObject(row.toString())
    }
    private fun taskExportStatus(selected: TaskExport, phase: String, message: String, bytes: Long? = null, target: JSONObject? = null) {
        if (taskExport !== selected) return
        val pin = selected.pin
        if(phase=="exported")check(bytes!=null&&ProjectPackageDocument.verified(target,bytes,pin.getString("sha256"))) { "没有原系统目标的完整回读，不确认导出" }
        selected.phase = phase
        val result = JSONObject().put("kind", "project_package_export").put("requestId", pin.getString("requestId"))
            .put("taskId", pin.getString("taskId")).put("operationId", pin.getString("operationId"))
            .put("artifactId", pin.getString("artifactId")).put("artifactSha256", pin.getString("sha256"))
            .put("projectId", pin.getString("projectId")).put("status", phase).put("message", message)
            .put("exported", phase == "exported")
        if (pin.has("teamRunId")) for (key in listOf("teamRunId", "runId", "epoch", "checkoutId")) result.put(key, pin.get(key))
        if (bytes != null) result.put("bytes", bytes)
        if (target != null) result.put("targetReadback",target)
        state = state.copy(exportStatus = result, notice = if (selected.team == null) message else state.notice)
    }
    private fun completeTaskExport(selected: TaskExport, result: JSONObject?, error: String?) {
        val done = selected.done ?: return
        selected.done = null; done(result, error)
    }
    private fun taskExportCurrent(selected: TaskExport) = !closed && foreground && privateOwner() &&
        taskExport === selected && selected.selected && selected.stillSelected()
    private fun requireTaskExportSource(selected: TaskExport): JSONObject {
        val pin = selected.pin
        val original = selected.team
        val source = if (original != null) {
            original.checkCurrent()
            val facts = original.view(); val evidence = facts.getJSONObject("evidence"); val delivery = facts.getJSONObject("delivery")
            check(facts.getString("teamRunId") == pin.getString("teamRunId") && delivery.getBoolean("complete") &&
                delivery.getBoolean("fixedBaselineOnly") && !delivery.getBoolean("currentMainProjectVerified") &&
                evidence.getString("runId") == pin.getString("runId") && evidence.getInt("coordinatorEpoch") == pin.getInt("epoch") &&
                evidence.getString("checkoutId") == pin.getString("checkoutId")) { "原团队签收或整合者执行已变化，未导出" }
            evidence.put("packageOperationId", evidence.getString("operationId")).put("artifactId", evidence.getJSONObject("packageArtifact").getString("id"))
                .put("artifactSha256", evidence.getString("archiveSha256"))
        } else app.tasks.ownerPackagePreviewSource(pin.getString("taskId"), pin.getString("operationId")).also {
            check(it.opt("ownerDirectViteBuild") != true) { "这份本机构建网页目前仅在原任务中查看" }
        }
        check(source.getString("taskId") == pin.getString("taskId") && source.getString("packageOperationId") == pin.getString("operationId") &&
            source.getString("projectId") == pin.getString("projectId") && source.getString("artifactId") == pin.getString("artifactId") &&
            source.getString("artifactSha256") == pin.getString("sha256")) { "原任务、操作或固定包版本已变化，未改用其他成果" }
        if (pin.has("sourceFingerprint")) check(pin.getString("sourceFingerprint") == dev.pocketengine.store.TaskStore.capabilityFingerprint(source)) { "原固定包来源登记已变化，未导出其他版本" }
        return source
    }
    /** Explicit Web selection only. The old native package panel is not activated or mounted. */
    fun offerTaskExport(task: String, operation: String, artifactId: String, artifactSha256: String, project: String,
        ownerCurrent: () -> Boolean, stillSelected: () -> Boolean, done: (JSONObject?, String?) -> Unit) =
        offerPackageExport(task, operation, artifactId, artifactSha256, project, null, ownerCurrent, stillSelected, done)

    fun offerTeamExport(original: dev.pocketengine.store.TaskStore.TeamProjectPackageSelection, current: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        val evidence = original.view().getJSONObject("evidence")
        offerPackageExport(evidence.getString("taskId"), evidence.getString("operationId"), evidence.getJSONObject("packageArtifact").getString("id"),
            evidence.getString("archiveSha256"), evidence.getString("projectId"), original, current, current, done)
    }
    private fun offerPackageExport(task: String, operation: String, artifactId: String, artifactSha256: String, project: String,
        team: dev.pocketengine.store.TaskStore.TeamProjectPackageSelection?, ownerCurrent: () -> Boolean, stillSelected: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        check(!closed && foreground && privateOwner() && ownerCurrent() && stillSelected()) { "请从原私人项目选择固定包导出" }
        check(!state.busy && pickerExport == null && pickerImport == null && exportUri == null) { "原文件操作尚未结束，请先核对原结果，没有重新打开文件选择器" }
        require(task.isNotBlank() && operation.isNotBlank() && artifactId.isNotBlank() && project.isNotBlank() && artifactSha256.matches(Regex("[a-f0-9]{64}"))) { "固定包导出身份不完整" }
        val pin = JSONObject().put("requestId", UUID.randomUUID().toString()).put("taskId", task).put("operationId", operation)
            .put("artifactId", artifactId).put("sha256", artifactSha256).put("projectId", project)
        if (team != null) {
            val facts=team.view(); val evidence=facts.getJSONObject("evidence")
            pin.put("teamRunId", facts.getString("teamRunId")).put("runId", evidence.getString("runId"))
                .put("epoch", evidence.getInt("coordinatorEpoch")).put("checkoutId", evidence.getString("checkoutId"))
        }
        taskExport?.let { it.teamTarget = null; it.targetProbe.cancel() } // a fresh choice never inherits an earlier destination
        val selected = TaskExport(pin, stillSelected, done, team); taskExport = selected
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
                    completeTaskExport(selected, state.exportStatus?.let { JSONObject(it.toString()) }, null)
                } catch (failure: Exception) {
                    if (pickerExport === pin) pickerExport = null
                    taskExportStatus(selected, "rejected", "系统文件选择器未能打开，未写入目标。")
                    completeTaskExport(selected, null, failure.message ?: "系统文件选择器未能打开")
                }
            }
        }
    }
    /** Rebind only the exact signed result. No target URI, alternate artifact or path comes from Web. */
    fun finishTeamExportChoice(original: dev.pocketengine.store.TaskStore.TeamProjectPackageSelection, request: String,
        write: Boolean, current: () -> Boolean): JSONObject {
        check(!closed && foreground && privateOwner() && current() && !state.busy && pickerExport == null && exportUri == null) { "请在原团队核对所选保存位置" }
        val selected = taskExport ?: error("原保存位置已失效；未写入")
        val first = selected.team ?: error("此保存位置不是团队选择")
        check(selected.selected && selected.phase == "target_selected" && selected.pin.getString("requestId") == request &&
            dev.pocketengine.store.TaskStore.capabilityFingerprint(first.view()) == dev.pocketengine.store.TaskStore.capabilityFingerprint(original.view())) { "目标不属于这次原团队整包选择；没有写入" }
        val uri = selected.teamTarget ?: error("原系统目标已失效；没有恢复写入")
        selected.teamTarget = null
        if (!write) taskExportStatus(selected, "cancelled", "已放弃这次另存，没有写入。系统选择位置时可能已创建空文档。")
        else {
            selected.stillSelected = current
            taskExportStatus(selected, "selection_returned", "已确认这份原团队完整包和所选目标，正在复核。")
            pickerExport = selected.pin; exportUri = uri; finishExport()
        }
        return JSONObject(requireNotNull(state.exportStatus).toString())
    }
    /** Retire only the original Web selection; a pending picker is consumed once when it returns. */
    fun invalidateTaskExportSelection() {
        val selected = taskExport ?: return
        if (selected.phase !in setOf("checking", "document_requested", "checking_target", "target_selected", "selection_returned", "writing")) return
        selected.teamTarget = null; selected.targetProbe.cancel()
        selected.selected = false
        if (selected.phase == "checking" || selected.phase == "writing") cancel()
        else taskExportStatus(selected, "cancelled", "原固定包窗口已变化，未向所选位置写入。")
        completeTaskExport(selected, null, "原固定包窗口已变化，导出未继续")
    }
    private fun inspectTeamTarget(selected: TaskExport, uri: Uri) {
        selected.teamTarget = uri
        taskExportStatus(selected, "checking_target", "正在核对系统选中的文件名；没有写入。")
        fun current() = taskExport === selected && selected.selected && selected.teamTarget == uri && !selected.targetProbe.isCancelled
        try { io.execute {
            val name = runCatching { ProjectPackageDocument.name(app, uri, selected.targetProbe) { check(current()) } }.getOrNull()
            main.post {
                if (current()) {
                    if (name == null) { selected.teamTarget = null; taskExportStatus(selected, "rejected", "无法核对所选位置的文件名，未写入。可重新选择系统位置。") }
                    else {
                        selected.teamTargetName = name
                        taskExportStatus(selected, "target_selected", "保存到「$name」（${uri.authority}）？确认后才会把这份团队完整包写入所选文档。")
                    }
                }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            selected.teamTarget = null; taskExportStatus(selected, "rejected", "原系统目标暂不可核对，未写入。")
        }
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
            if (selected.team != null && uri != null) {
                pickerExport = null; exportUri = null
                if (!ProjectPackageDocument.allowed(app, uri)) taskExportStatus(selected, "rejected", "没有取得系统所选的外部文档，未写入。")
                else inspectTeamTarget(selected, uri)
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
        if (!ProjectPackageDocument.allowed(app,uri)) { reject("没有取得系统选定的外部文档，未读取或写入其他位置"); return }
        if (selected != null) taskExportStatus(selected, "writing", "正在导出所选固定包，请等待保存结果。")
        work(ownerCurrent = selected?.let { original -> { taskExportCurrent(original) } }, onFailure = selected?.let { original -> { failure: Exception ->
            if (original.phase == "writing") taskExportStatus(original, if (original.writeStarted) "unknown" else "rejected",
                if (original.writeStarted) "导出未完整确认，目标可能已有部分内容；请核对所选文件，没有自动重写。" else failure.message?.take(600) ?: "原固定包导出未开始")
        } }) { c, _ ->
            val source = selected?.let(::requireTaskExportSource)
            val artifact = local().handle("project.package.get", JSONObject().put("artifactId", pin.getString("artifactId")).put("sha256", pin.getString("sha256")))
            if (source != null) dev.pocketengine.features.local.project.ProjectPackageDelivery.requireArtifact(source, artifact)
            val expected=pin.getString("sha256");val size=artifact.getLong("sizeBytes")
            check(size in 1..dev.pocketengine.features.local.project.ProjectPackageArchive.MAX_ARCHIVE_BYTES.toLong()&&artifact.getString("sha256")==expected){"原固定包摘要或字节数不可核对，未写入"}
            fun requireCurrent() {
                c.check()
                check(if(selected!=null)taskExportCurrent(selected) else foreground&&matches(pin)&&state.artifact?.let {it.opt("id")==pin.opt("artifactId")&&it.opt("sha256")==expected}==true){"原固定包导出窗口已变化"}
            }
            if (selected != null) requireTaskExportSource(selected)
            requireCurrent(); val displayName=ProjectPackageDocument.name(app,uri,c,::requireCurrent)
            if (selected?.team != null) check(displayName != null && displayName == selected.teamTargetName) { "所选文件名已变化，未写入；请重新选择位置" }
            requireCurrent(); if (selected != null) selected.writeStarted = true
            val opened = try {app.contentResolver.openOutputStream(uri, "wt") ?: error("所选位置无法写入")}
                catch(failure:Exception){throw IllegalStateException("系统目标未返回写入回执，可能已创建或截断；请核对原位置，没有自动重写",failure)}
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
            check(bytes==size){"原固定包的写入字节数不符；目标可能已有内容，未重新写入"}
            requireCurrent();val target=ProjectPackageDocument.readBack(app,uri,displayName,bytes,expected,c,::requireCurrent)
            val finalArtifact=local().handle("project.package.get",JSONObject().put("artifactId",pin.getString("artifactId")).put("sha256",expected))
            check(finalArtifact.opt("id")==pin.opt("artifactId")&&finalArtifact.opt("sha256")==expected&&finalArtifact.optLong("sizeBytes",-1)==size){"原固定包来源已变化；请核对目标，没有恢复写入"}
            if(selected!=null)dev.pocketengine.features.local.project.ProjectPackageDelivery.requireArtifact(requireTaskExportSource(selected),finalArtifact)
            requireCurrent();val verified=ProjectPackageDocument.verified(target,bytes,expected)
            return@work {
                val place=target.optString("displayName").takeIf {it.isNotBlank()}?.let {"「$it」"}?:target.getString("provider")
                val message=if(verified)"已在所选位置回读 $place：$bytes 字节、SHA-256 与原固定包一致。"
                    else if(target.opt("status")=="destination_changed")"写入已返回，但所选文档名称发生变化；请核对原位置，没有自动重写。"
                    else if(target.opt("status")=="content_mismatch")"写入已返回，但所选位置回读不符；请核对原文件，没有自动重写。"
                    else "写入已返回，但所选位置无法回读；不能确认已保存完整包，没有自动重写。"
                if(selected?.team==null)state=state.copy(notice=message)
                if(selected!=null)taskExportStatus(selected,if(verified)"exported" else "written_unverified",message,bytes,target)
            }
        }
    }
    /** Fixed files already in this phone's private Book, not a remote submit/read or general attachment bridge. */
    fun stageSavedHostSources(task: String, token: String, claims: org.json.JSONArray, current: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        check(foreground && !closed && privateOwner() && current() && !state.busy && state.pendingImport == null && state.pendingFreeze == null &&
            pickerImport == null && pickerExport == null && taskExport?.phase !in setOf("checking", "document_requested", "selection_returned", "writing")) {
            "请在原私人主机任务完成当前操作后，再选择手机原件"
        }
        require(token.matches(Regex("[a-f0-9-]{36}"))); check(hostSourceWait == null)
        val requested = org.json.JSONArray(claims.toString())
        val wait = HostSourceWait(task, token, done); hostSourceWait = wait
        val visible = { wait.active && current() }
        try { work(ownerCurrent = visible, onFailure = { finishHostSource(wait, null, it.message ?: "没有准备完整本机源码") }) { c, _ ->
            val gate = c.retainAuthorityCheck { check(visible() && !closed && foreground && privateOwner()) { "已离开原任务，未继续准备源码" } }
            val actual = try {
                val book = dev.pocketengine.features.attachments.AttachmentRepository.get(app).book
                val source = dev.pocketengine.features.local.project.ProjectHostSource.read(task, requested, { app.tasks.get(task, false) }, book, c)
                local().stageSavedHostSourceForOwner(source, c)
            } finally { gate.close() }
            val receipt = JSONObject().put("taskId", task).put("id", actual.getString("selectionId")).put("sha", actual.getString("sha256"))
                .put("files", actual.getJSONObject("sourceEvidence").getInt("files")).put("snapshotVersion", actual.getJSONObject("sourceEvidence").getJSONObject("original").getString("snapshotVersion"))
            return@work { finishHostSource(wait, receipt, null) }
        } } catch (e: Exception) { finishHostSource(wait, null, e.message ?: "原准备窗口已结束") }
    }
    // The web owner directory only reads prior selections. Opening it never opens a provider,
    // mounts a checkout, or fetches unrelated task artifacts and received attachments.
    private fun refreshSourceStages(prepared: Pair<String, String>?, recovery: ProjectSourceRecoveryPin? = webSourceRecovery,
        readSettled: Boolean = false) = work { control, _ ->
        val position = if (recovery != null) local().ownerSourcePosition(recovery.projectId, recovery.actionId, recovery.planDigest)
            else local().ownerSourcePosition()
        val durable = position.optJSONObject("plan")
        if (durable != null) {
            // A fresh intake can verify the actual old result, not just its locator.
            // Do not look at staged ZIPs or retire an action until the owner chooses another source.
            val observed = if (readSettled && recovery == null) try {
                local().queryReviewedPhoneSource(durable, false)
            } catch (failure: Exception) {
                if (failure is InterruptedException) throw failure
                control.check()
                null // Leave the original pending on an unreadable or unsettled result.
            } else null
            return@work {
                preferredWebSource = prepared
                webSourcePin = JSONObject(durable.toString())
                val settled = observed?.takeIf {
                    it.opt("found") == true && it.opt("projectRegistered") == true && it.opt("imported") == true &&
                        it.opt("taskOrGrantRestored") == false && matchesWebSourceReceipt(it) &&
                        state.pendingImport?.let(::matchesWebSourcePlan) != false
                }
                state = state.copy(importPlan = null, pendingImport = if (settled == null) webSourcePin else null,
                    retryImport = false, missingSourceAction = null, imported = settled, error = null,
                    notice = when {
                        settled != null && prepared != null -> "已核对上次创建的项目。选择「导入另一份源码」后会核对刚才选的来源。"
                        settled != null -> "已核对上次创建的项目，没有重新导入。"
                        prepared != null -> "刚准备的选集仍在。先核对上一次创建的实际副本，再选导入另一份。"
                        else -> "已找回上次在这台手机明确创建的操作，请先核对真正的副本；没有恢复执行。"
                    })
            }
        }
        val rows = local().listStagedProjectPackagesForOwner().objects()
        val target = prepared?.let { (id, sha) ->
            rows.singleOrNull { it.optBoolean("available") && it.optString("selectionId") == id && it.optString("sha256") == sha &&
                it.optJSONObject("sourceEvidence")?.optString("sourceKind") in setOf("host_saved_snapshot","task_source_package") }
                ?: error("原任务准备的那份手机源码已失效，没有选中其他工程")
        }
        return@work {
            if (position.optString("state") == "released" && webSourcePin?.let {
                    it.optString("clientImportId") == position.optString("clientImportId") && it.optString("planDigest") == position.optString("planDigest")
                } == true) {
                val old = state.pendingImport
                if (old == null || matchesWebSourcePlan(old)) state = state.copy(pendingImport = null, imported = null, importPlan = null, retryImport = false)
                webSourcePin = null
            }
            if (target != null && state.pendingImport == null) {
                webSourcePin = null
                state = state.copy(stagedPackages = rows, staged = target, importPlan = null, imported = null, notice = if(target.optJSONObject("sourceEvidence")?.optString("sourceKind")=="task_source_package") "只准备了原任务保存的这版固定源码，尚未创建项目。" else "只准备了原主机任务中选定的手机原件，尚未创建项目。")
            } else state = state.copy(stagedPackages = rows)
        }
    }
    fun openWebSourceIntake(prepared: Pair<String, String>? = null, recovery: ProjectSourceRecoveryPin? = null,
        readSettled: Boolean = false) {
        check(foreground && !closed && privateOwner() && !state.busy && pickerExport == null &&
            state.pendingFreeze == null && taskExport?.phase !in setOf("checking", "document_requested", "selection_returned", "writing") &&
            (pickerImport == null || pickerImport?.optString("sourceKind") in setOf("saf_tree", "external_zip")) &&
            (state.pendingImport == null || state.pendingImport?.optString("clientImportId") == webSourcePin?.optString("clientImportId"))) {
            "请先结束当前项目包操作，再导入本机源码"
        }
        check(prepared == null || recovery == null)
        preferredWebSource = prepared
        webSourceRecovery = recovery
        activate(null, null, sourcesOnly = true, prepared = prepared, readSettledSource = readSettled && recovery == null)
        val original = webSourcePin
        val found = matchesWebSourceReceipt(state.imported)
        state = state.copy(ownerPanelRequested = true, sourceImportRequested = true, importPlan = null,
            pendingImport = state.pendingImport ?: original?.takeUnless { found },
            notice = if (original != null && !found) "保留了原创建定位。先核对原结果，不会重复创建。" else null, error = null)
    }
    fun closeWebSourceIntake() {
        if (!state.sourceImportRequested || state.checkoutId != null || state.projectId != null) return
        preferredWebSource = null; webSourceRecovery = null; retireOwnerSourcePicker()
        if (pickerImport != null) restoredPicker = true
        forgetImportPlan(); closeOwnerPanel(); deactivate()
    }
    fun confirmWebSourcePlan(client: String) {
        requireOwner()
        val pin = state.importPlan?.optJSONObject("plan") ?: error("请重新核对这份源码")
        check(!state.busy && state.pendingImport == null && pin.optString("clientImportId") == client &&
            pin.optString("mode") == "new_project" && pin.has("sourceEvidenceSha256") && !pin.has("targetCheckoutId") &&
            state.staged?.optJSONObject("sourceEvidence")?.optString("sourceKind") in
                setOf("saf_tree", "external_zip", "host_saved_snapshot", "builtin_vite_starter", ProjectTaskSourceOrigin.KIND)) { "来源或创建方案已变化，没有开始新的导入" }
        webSourcePin = JSONObject(pin.toString())
        confirmImport()
    }
    fun releaseMissingWebSource(client: String) {
        val pin = state.pendingImport ?: error("原创建定位已变化")
        check((state.retryImport || state.missingSourceAction == client) && client == webSourcePin?.optString("clientImportId") && client == pin.optString("clientImportId"))
        work { _, _ ->
            val found = local().releaseReviewedPhoneSource(JSONObject(pin.toString()), false)
            if (found.optBoolean("found")) app.features.sendState()
            return@work {
                if (found.optBoolean("found")) state = state.copy(pendingImport = null, retryImport = false, importPlan = null, imported = found, notice = "已找到原工作副本，未另起创建。")
                else if (found.optBoolean("positionReleased")) {
                    webSourcePin = null
                    state = state.copy(pendingImport = null, retryImport = false, importPlan = null, imported = null, staged = null, notice = "原操作仍未找到已发布副本。已放下定位，没有重试或删除项目。")
                    val next = preferredWebSource; preferredWebSource = null
                    if (webSourceRecovery == null) refreshSourceStages(next)
                }
            }
        }
    }
    fun matchesWebSourcePlan(pin: JSONObject): Boolean {
        val original = webSourcePin ?: return false
        return webSourceRecovery?.matches(original) != false && original.getString("clientImportId") == pin.optString("clientImportId") &&
            original.getString("planDigest") == pin.optString("planDigest") && original.getString("selectionId") == pin.optString("selectionId")
    }
    fun matchesWebSourceReceipt(row: JSONObject?): Boolean {
        val original = webSourcePin ?: return false
        val source = row?.optJSONObject("sourceImport") ?: return false
        val recovery = webSourceRecovery
        if (recovery != null && (!recovery.matches(original) || row.opt("projectId") != recovery.projectId)) return false
        return source.optString("clientId") == original.getString("clientImportId") && source.optString("mode") == "new_project" &&
            source.optString("packageSha256") == original.getString("packageSha256") && source.optString("manifestIdentity") == original.getString("manifestIdentity") &&
            source.optString("sourceEvidenceSha256") == original.getString("sourceEvidenceSha256") && row.optString("name") == original.getString("projectName")
    }
    fun newWebSourceSelection() {
        requireOwner()
        check(!state.busy && state.pendingImport == null && pickerImport == null) { "请先核对原创建结果" }
        val old = webSourcePin
        fun clear() {
            webSourcePin = null
            state = state.copy(staged = null, importPlan = null, imported = null, notice = null, error = null)
        }
        if (old == null) { clear(); return }
        check(matchesWebSourceReceipt(state.imported)) { "请先核对上次创建的实际副本" }
        work { _, _ ->
            val release = local().releaseReviewedPhoneSource(JSONObject(old.toString()), true)
            return@work {
                if (release.optBoolean("positionReleased") && release.optBoolean("found")) {
                    clear(); val next = preferredWebSource; preferredWebSource = null; refreshSourceStages(next)
                } else state = state.copy(pendingImport = old, imported = null, retryImport = false, notice = "上次副本状态已变化，先核对真正的原结果，没有另建。")
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
    fun stageBuiltinViteStarter() {
        requireOwner()
        check(foreground && state.sourceImportRequested && state.projectId == null && state.checkoutId == null &&
            webSourceRecovery == null && !state.busy && pickerImport == null &&
            state.pendingImport == null && state.importPlan == null && state.imported == null) {
            "请先结束原源码操作，再选择内置小网页"
        }
        work { control, _ ->
            val actual = local().stageBuiltinViteStarterForOwner { control.check() }
            control.check()
            check(actual.optBoolean("complete") && actual.optJSONObject("sourceEvidence")?.opt("sourceKind") == "builtin_vite_starter") {
                "没有核对到内置小网页源码，未创建项目"
            }
            return@work {
                state = state.copy(staged = actual, stagedPackages = withStage(actual), sourceSkipCandidate = null,
                    notice = "内置小网页已备好。给项目取名后再确认创建。没有下载或运行。")
            }
        }
    }
    fun pickSourceDirectory(generated: Boolean, excludedDirectory: String = "") = pickSource("saf_tree", generated, ZipRoot.PRESERVE, excludedDirectory)
    fun pickSourceZip(generated: Boolean, stripRoot: Boolean) = pickSourceZip(generated, if (stripRoot) ZipRoot.STRIP_SINGLE else ZipRoot.PRESERVE)
    fun pickSourceZip(generated: Boolean, root: ZipRoot) = pickSource("external_zip", generated, root)
    private fun pickSource(kind: String, generated: Boolean, root: ZipRoot, excludedDirectory: String = "") {
        requireOwner(); if (state.busy || pickerImport != null || state.pendingFreeze != null || state.pendingImport != null) return
        val id = UUID.randomUUID().toString()
        val skip = dev.pocketengine.features.local.project.ProjectSourceImport.checkedSkipDirectory(excludedDirectory)
        require(skip.isEmpty() || kind == "saf_tree") { "只能从本机文件夹导入时跳过一个目录" }
        val stripRoot = root == ZipRoot.STRIP_SINGLE
        // Re-selecting a ZIP to use its inner root is tentative. Keep the
        // already frozen choice until a new result is actually read and staged.
        val previous = state.staged?.takeIf { staged ->
            val evidence = staged.optJSONObject("sourceEvidence") ?: return@takeIf false
            kind == "external_zip" && stripRoot && state.sourceImportRequested && state.projectId == null && state.checkoutId == null &&
                staged.opt("complete") == true && evidence.opt("sourceKind") == "external_zip" &&
                evidence.opt("ordinaryZipIsPocketWorldPackage") == false && evidence.optJSONObject("original")?.opt("singleRootStripped") == ""
        }
        pickerImport = JSONObject().put("ownerExcludedDirectory", skip.takeIf { it.isNotEmpty() }).put("selectionId", id).put("ownerSourcePicker", true).put("checkoutId", state.checkoutId.orEmpty()).put("projectId", state.projectId.orEmpty()).put("sourceKind", kind).put("includeGeneratedRoots", generated).put("stripSingleRoot", stripRoot)
            .put("recognizeWebRoot", true.takeIf { root == ZipRoot.RECOGNIZE_WEB })
            .put("priorStageId", previous?.getString("selectionId")).put("priorStageSha", previous?.getString("sha256"))
        restoredPicker = false
        state = state.copy(staged = previous, imported = null, importPlan = null, notice = previous?.let {
            "尚未更换来源。之前核对的 ZIP 和原目录结构仍保留。"
        }, error = null, sourceSkipCandidate = null)
        try { activity.pickOwnerProjectSource(id, kind == "saf_tree") }
        catch (_: RuntimeException) { retireOwnerSourcePicker(); state = state.copy(error = "无法打开系统文件选择器，未读取或创建项目。") }
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
    private fun retireOwnerSourcePicker() {
        val pin = pickerImport ?: return
        if (!pin.optBoolean("ownerSourcePicker")) return
        activity.cancelOwnerProjectSource(pin.getString("selectionId"))
        pickerImport = null; importUri = null
    }
    fun sourceResult(id: String, uri: Uri?) {
        if (pickerImport?.optBoolean("ownerSourcePicker") != true || pickerImport?.optString("selectionId") != id) return
        acceptImportResult(uri)
    }
    fun importResult(uri: Uri?) {
        // A late legacy package/tree result is not this new source picker's grant.
        if (pickerImport?.optBoolean("ownerSourcePicker") != true) acceptImportResult(uri)
    }
    private fun acceptImportResult(uri: Uri?) {
        importUri = uri
        if (uri == null) {
            val pin = pickerImport
            if (!restoredPicker && pin != null && pin.optString("sourceKind") in setOf("saf_tree", "external_zip")) {
                val kept = state.staged?.let { pin.has("priorStageId") && pin.opt("priorStageId") == it.opt("selectionId") &&
                    pin.opt("priorStageSha") == it.opt("sha256") } == true
                state = state.copy(notice = if (kept) "已取消重新选择。仍是之前核对的 ZIP，目录没有改动。"
                    else "没有选取源码，未创建项目。")
            }
            pickerImport = null
        } else if (restoredPicker) {
            pickerImport = null; importUri = null
            state = state.copy(error = "原选择窗口已结束，请重新选取源码；未读取返回的内容。")
        } else processPickerResults()
    }
    private fun finishImport() {
        if (!foreground || !mounted || state.busy) return
        val uri = importUri; importUri = null
        val pin = pickerImport; pickerImport = null
        if (uri == null || pin == null) return
        if (restoredPicker || !matches(pin)) { state = state.copy(error = "原项目入口已变化，请重新选择项目包；未导入。"); return }
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) { state = state.copy(error = "系统未授予可用 content 文件引用，未读取或写入其他路径"); return }
        work { c, _ ->
            if (pin.optString("sourceKind") == "saf_tree") {
                val actual = try { dev.pocketengine.features.local.project.android.AndroidProjectSourceTree(app, uri, c).use { tree -> local().stageSourceTreeForOwner(tree, tree.sourceLabel(), pin.getString("selectionId"), pin.getBoolean("includeGeneratedRoots"), pin.optString("ownerExcludedDirectory", ""), c) } } catch (limit: dev.pocketengine.features.local.project.ProjectSourceImport.FolderFileTooLarge) {
                    return@work { state = state.copy(error = limit.message, sourceSkipCandidate = limit.suggestedDirectory) }
                }
                return@work { state = state.copy(staged = actual, stagedPackages = withStage(actual), imported = null, importPlan = null, sourceImportRequested = true, sourceSkipCandidate = null, notice = "已读取并复核本次选定范围。请核对清单，再决定是否创建项目；未执行代码。") }
            }
            val sourceLabel = if (pin.optString("sourceKind") == "external_zip") runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { row -> if (row.moveToFirst()) row.getString(0) else null } }.getOrNull() ?: "已有ZIP源码" else "PocketWorld项目包"
            val opened = app.contentResolver.openInputStream(uri) ?: error("所选项目包无法读取")
            val cancel = Runnable { runCatching { opened.close() } }; c.onCancel(cancel)
            val actual = try { opened.use { raw ->
                val checked = object : java.io.FilterInputStream(raw) {
                    override fun read(): Int { c.check(); return super.read() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { c.check(); return super.read(bytes, offset, length) }
                }
                if (pin.optString("sourceKind") == "external_zip") {
                    val root = when {
                        pin.opt("recognizeWebRoot") == true -> ZipRoot.RECOGNIZE_WEB
                        pin.getBoolean("stripSingleRoot") -> ZipRoot.STRIP_SINGLE
                        else -> ZipRoot.PRESERVE
                    }
                    local().stageSourceZipForOwner(checked, sourceLabel, pin.getString("selectionId"), root, pin.getBoolean("includeGeneratedRoots"), c)
                } else local().stageProjectPackageForOwner(checked, pin.getString("selectionId"))
            } } finally { c.removeCancelAction(cancel) }
            return@work { state = state.copy(staged = actual, stagedPackages = withStage(actual), imported = null, importPlan = null, notice = when {
                actual.optBoolean("reusedSelectedZipBytes") -> "本次选的 ZIP 字节、名称和录入路径与已有暂存相同，已复用原包，没多占位置。未复用文件权限，也未创建项目。"
                actual.has("sourceEvidence") -> "已核对所选 ZIP。请查看实际录入的路径，再决定是否创建项目；未执行代码。"
                else -> "项目包已读取，请核对后明确导入；尚未运行。"
            }) }
        }
    }
    private fun withStage(actual: JSONObject) = listOf(JSONObject(actual, arrayOf("selectionId", "sha256", "manifestIdentity", "sourceEvidence", "manifest", "complete", "sizeBytes", "deviceSource")).put("available", true)) +
        state.stagedPackages.filterNot { it.optString("selectionId") == actual.optString("selectionId") }
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
    private fun importOriginal(pin: JSONObject) {
        val phoneSource = matchesWebSourcePlan(pin)
        work { _, _ ->
            val original = JSONObject(pin.toString())
            val value = if (phoneSource) local().publishReviewedPhoneSource(original) else local().importProjectPackageForOwner(original)
            app.features.sendState()
            return@work { state = state.copy(pendingImport = null, retryImport = false, missingSourceAction = null, importPlan = null, imported = value, notice = "已创建新的实际工作副本，原目录未覆盖；未恢复旧任务或运行授权。明确打开新副本后再开始项目任务。") }
        }
    }
    fun queryImport() {
        val pin = state.pendingImport ?: return
        val phoneSource = matchesWebSourcePlan(pin)
        work { _, _ ->
            val original = JSONObject(pin.toString())
            val value = if (phoneSource) local().queryReviewedPhoneSource(original) else local().findProjectPackageImportForOwner(original)
            if (value.optBoolean("found")) app.features.sendState()
            return@work {
                state = if (value.optBoolean("found")) state.copy(pendingImport = null, importPlan = null, imported = value, retryImport = false, missingSourceAction = null, notice = "已找到原导入动作创建的副本；未重复导入")
                else if (!phoneSource) state.copy(retryImport = true, notice = "原动作未发布副本。可明确重试这个原方案；目标变化仍会拒绝。")
                else {
                    val retry = value.optBoolean("retryAvailable")
                    val result = when {
                        value.optBoolean("checkoutWithoutProject") -> "能找到原副本文件，但项目目录没有相同登记。未当作可打开项目，也未重建。"
                        value.optBoolean("projectRegistered") -> "原项目已登记，但没有找到可打开的完整工作副本。"
                        else -> "没有找到该操作已发布的工作副本。"
                    }
                    state.copy(retryImport = retry, missingSourceAction = pin.getString("clientImportId"), notice = result +
                        if (retry) "原暂存仍匹配，可明确沿同一方案重试。" else "当前没有可安全重试的原方案，可以放下定位后另选来源。")
                }
            }
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
    fun continueWebSourceImport(stillSelected: (JSONObject) -> Boolean, failed: ((Exception) -> Unit)? = null, opened: () -> Unit) {
        val selected = state.imported?.takeIf(::matchesWebSourceReceipt) ?: error("没有已核对的原工作副本")
        val source = JSONObject(checkNotNull(webSourcePin).toString())
        work(onFailure = failed) { _, _ ->
            // This read checks the exact publication, its checkout directory
            // and project registration. Opening is not an export or source read.
            val now = local().queryReviewedPhoneSource(source, false)
            if (!now.optBoolean("found") || now.optString("id") != selected.optString("id") || now.optString("projectId") != selected.optString("projectId"))
                return@work {
                    val message = "上次副本或项目登记已变化。请核对原创建记录，没有打开别的项目。"
                    state = state.copy(imported = null, pendingImport = source, retryImport = false, missingSourceAction = null, notice = message)
                    failed?.invoke(IllegalStateException(message))
                }
            return@work {
                if (stillSelected(now)) {
                    importedCheckout(now)
                    opened()
                } else failed?.invoke(IllegalStateException("原导入窗口已变化，没有打开别的项目。"))
            }
        }
    }
    fun foreground(visible: Boolean) {
        foreground = visible
        if (!visible) { cancel(); closePreview() }
        else processPickerResults()
    }
    fun leavePrivate() { retireOwnerSourcePicker(); invalidateTaskExportSelection(); pendingTeamResult = null; pendingTeamIntent = null; mounted = false; retainPosition(); cancel(); state = state.copy(preview = null, artifact = null, selection = null, staged = null, importPlan = null) }
    fun cancel() {
        val source = hostSourceWait
        source?.active = false
        generation++; control?.cancel(); future?.cancel(true); control = null; future = null
        if (source != null) finishHostSource(source, null, "已停止本次等待。已固定的临时选集可在项目中核对，未创建或执行工程。")
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
    fun detach() { retireOwnerSourcePicker(); invalidateTaskExportSelection(); cancel(); closed = true }
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
        webSourcePin?.let { out.putString("ownerLocalSourcePosition", it.toString()) }
        out.putString("projectPackageActions", org.json.JSONArray(positions.values.toList().takeLast(41)).toString())
        pickerImport?.let { out.putString("projectPackageImportPicker", it.toString()) }; pickerExport?.let { out.putString("projectPackageExportPicker", it.toString()) }
        state.exportStatus?.let { out.putString("projectPackageExportStatus", it.toString()) }
    }
    fun restore(saved: Bundle?) {
        runCatching {
            webSourcePin = saved?.getString("ownerLocalSourcePosition")?.takeIf { it.length <= 4096 }?.let(::JSONObject)?.takeIf {
                it.optString("mode") == "new_project" && !it.has("targetCheckoutId") &&
                    listOf("planDigest", "sourceEvidenceSha256", "manifestIdentity", "packageSha256").all { key -> it.optString(key).matches(Regex("[a-f0-9]{64}")) } &&
                    listOf("clientImportId", "selectionId", "projectName").all { key -> it.optString(key).isNotBlank() }
            }
            saved?.getString("projectPackageActions")?.takeIf { it.length <= 131072 }?.let { text ->
                org.json.JSONArray(text).objects().take(41).forEach { pin -> positions[positionKey(pin.optString("projectId"), pin.optString("checkoutId"))] = pin }
            }
            pickerImport = saved?.getString("projectPackageImportPicker")?.takeIf { it.length <= 1024 }?.let(::JSONObject)?.takeUnless { it.optBoolean("ownerSourcePicker") }
            pickerExport = saved?.getString("projectPackageExportPicker")?.takeIf { it.length <= 4096 }?.let(::JSONObject)
            restoredPicker = pickerImport != null || pickerExport != null
            saved?.getString("projectPackageExportStatus")?.takeIf { it.length <= 4096 }?.let { text ->
                val status = JSONObject(text)
                if (status.optString("kind") == "project_package_export") {
                    if (status.optString("status") in setOf("checking_target", "target_selected") && status.has("teamRunId")) {
                        status.put("status", "cancelled").put("exported", false).put("message", "原系统目标未保存到本机记录，未恢复写入；请重新选取位置。")
                    } else if (status.optString("status") in setOf("checking", "document_requested", "selection_returned", "writing")) {
                        status.put("status", "unknown").put("exported", false).put("message", "原导出结果尚未确认；请核对所选文件，没有恢复写入或重新打开选择器。")
                    }
                    ProjectPackageDocument.restore(status)
                    state = state.copy(exportStatus = status)
                }
            }
        }.onFailure { positions.clear(); pickerImport = null; pickerExport = null }
    }
}
