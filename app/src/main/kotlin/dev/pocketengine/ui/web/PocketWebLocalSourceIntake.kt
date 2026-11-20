package dev.pocketengine.ui.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.features.local.project.ProjectArchiveRoot
import dev.pocketengine.ui.ProjectPackageCoordinator
import dev.pocketengine.ui.ProjectSourceRecoveryPin
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Presentation for the original SAF source picker, freezer and importer. Never accepts a URI, path, bytes or a target checkout. */
internal class PocketWebLocalSourceIntake(private val original: ProjectPackageCoordinator,
    private val current: () -> Boolean, private val project: () -> String?) {
    companion object {
        const val PREFIX = "projects.localSource."
        val COMMANDS = setOf("open", "openPrepared", "close", "folder", "zip", "select", "review", "back", "confirm",
            "query", "retry", "releaseMissing", "discard", "openProject", "another").map { PREFIX + it }.toSet()
        private val KINDS = setOf("saf_tree", "external_zip", "host_saved_snapshot", "task_source_package")
    }
    private var window by mutableStateOf<String?>(null)
    private var origin: String? = null

    private fun here() = window != null && current() && project() == origin &&
        original.state.sourceImportRequested && original.state.projectId == null && original.state.checkoutId == null
    private fun evidence(row: JSONObject?): JSONObject? {
        row ?: return null
        return row.optJSONObject("sourceEvidence")?.takeIf { it.optString("sourceKind") in KINDS &&
            it.optBoolean("completeSelection") && row.optBoolean("complete") &&
            it.optString("manifestIdentity") == row.optString("manifestIdentity") && it.optString("selectionId") == row.optString("selectionId") }
    }
    private fun stageRow(row: JSONObject?, preview: Boolean = false): JSONObject? {
        val evidence = evidence(row) ?: return null
        val actual = row ?: return null
        val manifest = actual.optJSONObject("manifest")?.takeIf { preview }
        val zipRoot = if (preview && evidence.opt("sourceKind") == "external_zip" &&
            evidence.opt("ordinaryZipIsPocketWorldPackage") == false &&
            evidence.optJSONObject("original")?.opt("singleRootStripped") == "") {
            manifest?.let { runCatching { ProjectArchiveRoot.staged(it) }.getOrNull() }
        } else null
        val strippedRoot = evidence.optJSONObject("original")?.optString("singleRootStripped")?.takeIf {
            preview && evidence.opt("sourceKind") == "external_zip" && it.isNotBlank() && it.length <= 240 }
        val firstFile = manifest?.optJSONArray("files")?.takeIf { evidence.optString("sourceKind") in setOf("external_zip","saf_tree") }?.let {
            runCatching { ProjectArchiveRoot.firstWebFile(it,zipRoot?.optString("directory").orEmpty())?.getString("path") }.getOrNull()
        }
        val fixed = evidence.optJSONObject("original")?.takeIf { evidence.optString("sourceKind") == "external_zip" &&
            evidence.optBoolean("ordinaryZipIsPocketWorldPackage") && it.optString("fixedPackageManifestIdentity").matches(Regex("[a-f0-9]{64}")) &&
            it.optString("originalArchiveSha256").matches(Regex("[a-f0-9]{64}")) && it.optInt("fixedPackageOmitted", -1) in 0..2000 }
        val taskPackage=evidence.optJSONObject("original")?.takeIf { evidence.optString("sourceKind")=="task_source_package" &&
            it.opt("archiveSha256")==actual.opt("sha256") && it.opt("manifestIdentity")==actual.opt("manifestIdentity") }
        if(evidence.optString("sourceKind")=="task_source_package" && taskPackage==null)return null
        return JSONObject().put("id", actual.getString("selectionId")).put("sha", actual.getString("sha256"))
            .put("manifest", actual.getString("manifestIdentity")).put("kind", evidence.getString("sourceKind"))
            .put("label", evidence.optString("sourceLabel").take(120)).put("files", evidence.optInt("files"))
            .put("excludedDirectory", evidence.optJSONObject("original")?.optString("ownerExcludedDirectory")?.takeIf {
                evidence.optString("sourceKind") == "saf_tree" && it.isNotEmpty() && it.length <= 240 })
            .put("fixedPackage", fixed?.let { JSONObject().put("manifest", it.getString("fixedPackageManifestIdentity"))
                .put("archive", it.getString("originalArchiveSha256")).put("omitted", it.getInt("fixedPackageOmitted")) }
                ?: taskPackage?.let{JSONObject().put("manifest",it.getString("manifestIdentity")).put("archive",it.getString("archiveSha256")).put("omitted",evidence.getInt("omittedCount"))})
            .put("hostVersion", evidence.optJSONObject("original")?.optString("snapshotVersion")?.takeIf { evidence.optString("sourceKind") == "host_saved_snapshot" })
            .put("retainedArchiveRoot", zipRoot)
            .put("strippedArchiveRoot", strippedRoot).put("firstWebFile", firstFile)
            .put("bytes", evidence.optLong("expandedBytes")).put("omitted", evidence.optInt("omittedCount"))
            .put("paths", JSONArray().apply { if (preview) actual.optJSONObject("manifest")?.optJSONArray("files")?.let { rows ->
                for (i in 0 until minOf(rows.length(), 6)) rows.optJSONObject(i)?.optString("path")?.take(260)?.let { put(it) }
            } })
    }
    private fun pending() = original.state.pendingImport?.takeIf { original.matchesWebSourcePlan(it) &&
        it.optString("mode") == "new_project" && !it.has("targetCheckoutId") && it.has("sourceEvidenceSha256") }
    private fun result() = original.state.imported?.takeIf { row ->
        val source = row.optJSONObject("sourceImport")
        source != null && original.matchesWebSourceReceipt(row) && source.optString("sourceKind") in KINDS &&
            source.optString("mode") == "new_project" && row.optBoolean("imported") && row.optString("id").isNotBlank() && row.optString("projectId").isNotBlank()
    }
    private fun plan() = original.state.importPlan?.optJSONObject("plan")?.takeIf {
        val staged = original.state.staged ?: return@takeIf false
        evidence(staged) != null && it.optString("selectionId") == staged.optString("selectionId") &&
            it.optString("manifestIdentity") == staged.optString("manifestIdentity") && it.optString("packageSha256") == staged.optString("sha256") &&
            it.optString("mode") == "new_project" && !it.has("targetCheckoutId") && it.has("sourceEvidenceSha256")
    }
    private fun displayError(message: String?) = message?.let {
        if (it.contains("content://", true) || it.contains("file://", true) || it.contains("/data/") || it.contains("/storage/"))
            "系统文件提供方未返回可核对的原件，请重新选择或核对原操作。" else it.take(500)
    }
    fun snapshot(): JSONObject? {
        if (!here()) return null
        val state = original.state
        val recovery = original.webSourceRecovery != null
        val plan = if (recovery) null else plan(); val pending = pending(); val result = result()
        return JSONObject().put("window", window).put("busy", state.busy).put("picking", original.webSourcePickerPending)
            .put("recoveryOnly", recovery)
            .put("suggestedSkip", state.sourceSkipCandidate?.takeIf { !recovery && state.error != null && state.staged == null })
            .put("notice", state.notice).put("error", displayError(state.error)).put("staged", if (recovery) null else stageRow(state.staged, true))
            .put("saved", if (recovery) JSONArray() else JSONArray(state.stagedPackages.mapNotNull { stageRow(it) }.take(4)))
            .put("reviewId", plan?.getString("clientImportId")).put("reviewName", plan?.getString("projectName"))
            .put("pendingId", pending?.getString("clientImportId")).put("pendingName", pending?.getString("projectName"))
            .put("otherPending", !recovery && state.pendingImport != null && pending == null).put("mayRetry", pending != null && state.retryImport)
            .put("mayRelease", pending != null && (state.retryImport || state.missingSourceAction == pending.optString("clientImportId")))
            .put("project", result?.getString("projectId")).put("checkout", result?.getString("id")).put("name", result?.optString("name"))
    }

    fun close() {
        if (window != null) { window = null; origin = null; original.closeWebSourceIntake() }
    }
    fun pause() { if (window != null) original.forgetImportPlan() } // document picker necessarily pauses the same Activity

    fun dispatch(action: String, args: JSONObject): JSONObject {
        val op = action.removePrefix(PREFIX)
        val fields = when (op) {
            "open" -> if (args.has("recovery")) setOf("recovery") else emptySet()
            "openPrepared" -> setOf("id", "sha")
            "select", "discard" -> setOf("window", "id", "sha")
            "review" -> setOf("window", "id", "sha", "name")
            "confirm", "query", "retry", "releaseMissing" -> setOf("window", "actionId")
            "openProject" -> setOf("window", "project", "checkout")
            "folder" -> if (args.has("excludedDirectory")) setOf("window", "excludedDirectory") else setOf("window")
            "zip" -> setOf("window", "stripRoot")
            else -> setOf("window")
        }
        require(args.keys().asSequence().toSet() == fields) { "请重新打开原源码审阅" }
        if (op == "open" || op == "openPrepared") {
            check(current()) { "请回到本机私人项目目录" }
            val prepared = if (op == "openPrepared") {
                val id = args.getString("id"); val sha = args.getString("sha")
                require(id.matches(Regex("[a-f0-9-]{36}")) && sha.matches(Regex("[a-f0-9]{64}")))
                id to sha
            } else null
            val recovery = if (op == "open" && args.has("recovery")) {
                val pin = args.getJSONObject("recovery")
                require(pin.keys().asSequence().toSet() == setOf("projectId", "actionId", "planDigest"))
                val projectId = pin.getString("projectId"); val action = pin.getString("actionId"); val digest = pin.getString("planDigest")
                check(!here() && projectId == project() && action.matches(Regex("[a-f0-9-]{36}")) && digest.matches(Regex("[a-f0-9]{64}"))) {
                    "请回到原导入的项目核对"
                }
                ProjectSourceRecoveryPin(projectId, action, digest)
            } else null
            if (prepared != null || recovery != null) check(!original.state.busy && !original.webSourcePickerPending) { "请先结束已有的本机源码操作" }
            if (!here() || prepared != null) {
                if (!here()) close()
                original.openWebSourceIntake(prepared, recovery)
                origin = project(); window = UUID.randomUUID().toString()
            }
            return JSONObject().put("opened", true).also { if (recovery != null) it.put("recoveryWindow", window) }
        }
        check(window != null && args.getString("window") == window) { "原源码选择窗口已变化" }
        if (op == "close") { close(); return JSONObject().put("closed", true) }
        check(here()) { "请回到原私人项目目录" }
        val state = original.state
        check(original.webSourceRecovery == null || op in setOf("query", "retry", "releaseMissing", "openProject")) {
            "请先关闭原导入核对，再选择其他来源"
        }
        check(!state.busy && !original.webSourcePickerPending) { "请等待当前文件选择或本机读取结束" }
        if (op !in setOf("query", "retry", "releaseMissing")) check(state.pendingImport == null) { "请先核对原创建结果" }
        fun matches(row: JSONObject?) = row != null && evidence(row) != null && row.optString("selectionId") == args.getString("id") && row.optString("sha256") == args.getString("sha")
        when (op) {
            "folder", "zip" -> {
                check(plan() == null && result() == null) { "请先返回选择来源" }
                if (op == "folder") {
                    val skip = if (args.has("excludedDirectory")) args.get("excludedDirectory") as? String ?: error("跳过目录必须是确切相对路径") else ""
                    original.pickSourceDirectory(false, skip)
                }
                else { require(args.get("stripRoot") is Boolean); original.pickSourceZip(false, args.getBoolean("stripRoot")) }
            }
            "select" -> {
                check(plan() == null && result() == null)
                val saved = state.stagedPackages.firstOrNull { it.optBoolean("available") && matches(it) } ?: error("原临时源码已失效")
                original.selectStaged(saved)
            }
            "review" -> {
                require(matches(state.staged) && result() == null && plan() == null) { "已不是这份固定源码" }
                val value = args.get("name"); require(value is String && value.isNotBlank() && value.length <= 80) { "请输入 1 至 80 字的项目名" }
                original.planImport(value.trim(), false)
            }
            "back" -> original.forgetImportPlan()
            "confirm" -> { check(plan()?.optString("clientImportId") == args.getString("actionId")); original.confirmWebSourcePlan(args.getString("actionId")) }
            "query", "retry", "releaseMissing" -> {
                check(pending()?.optString("clientImportId") == args.getString("actionId")) { "原创建记录不匹配" }
                when (op) {
                    "query" -> original.queryImport()
                    "releaseMissing" -> original.releaseMissingWebSource(args.getString("actionId"))
                    else -> { check(state.retryImport) { "请先核对原创建结果" }; original.retryImport() }
                }
            }
            "discard" -> { check(matches(state.staged) && plan() == null && result() == null) { "原临时选择已变化" }; original.discardStage() }
            "openProject" -> {
                val row = result() ?: error("没有已核对的原工作副本")
                val requestedWindow = window
                val target = row.getString("projectId")
                check(target == args.getString("project") && row.getString("id") == args.getString("checkout")) { "项目或工作副本已变化" }
                original.continueWebSourceImport({ window == requestedWindow && here() && result() === row }) {
                    // Selecting the same project does not retire this window.
                    // Close only after the original native open has happened.
                    if (window == requestedWindow && current() && project() == target) close()
                }
            }
            "another" -> original.newWebSourceSelection()
            else -> error("不支持这个本机源码操作")
        }
        return JSONObject().put("received", true)
    }
}
