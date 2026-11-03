package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** UI identity fences supplement, never replace, native source/revision/digest checks. */
internal object ExperienceSkillReviewGuard {
    @JvmStatic fun eligible(record: JSONObject?): Boolean = record != null && record.optString("id").isNotBlank() &&
        MemoryLearningReviewGuard.revision(record) > 0 && record.optString("layer") == "procedural" &&
        record.optString("formation") == "reviewed_learning" && record.optBoolean("active")

    @JvmStatic fun sameProcedure(frozen: JSONObject?, current: JSONObject?): Boolean = eligible(frozen) && eligible(current) &&
        frozen!!.optString("id") == current!!.optString("id") && MemoryLearningReviewGuard.revision(frozen) == MemoryLearningReviewGuard.revision(current) &&
        frozen.optString("text") == current.optString("text") && frozen.optString("applicability") == current.optString("applicability") &&
        canonical(frozen.optJSONArray("sourceRefs")) == canonical(current.optJSONArray("sourceRefs"))

    @JvmStatic fun sameSkill(frozen: JSONObject?, current: JSONObject?): Boolean = frozen != null && current != null &&
        frozen.optString("id").isNotBlank() && frozen.optString("id") == current.optString("id") &&
        MemoryLearningReviewGuard.revision(frozen) > 0 && MemoryLearningReviewGuard.revision(frozen) == MemoryLearningReviewGuard.revision(current) &&
        frozen.optString("digest").matches(Regex("[a-f0-9]{64}")) && frozen.optString("digest") == current.optString("digest") &&
        canonical(frozen.optJSONObject("origin")) == canonical(current.optJSONObject("origin"))

    @JvmStatic fun sourceMatches(record: JSONObject, inspection: JSONObject): Boolean {
        val ref = inspection.optJSONObject("origin")?.optJSONObject("ref") ?: return false
        return ref.optString("recordId") == record.optString("id") && ref.optLong("revision") == MemoryLearningReviewGuard.revision(record) &&
            ref.optString("ownerId") == record.optString("ownerId") && ref.optString("spaceId") == record.optString("spaceId") &&
            inspection.optString("originStatus") == "current" && inspection.optString("inspectedDigest").matches(Regex("[a-f0-9]{64}"))
    }

    @JvmStatic fun importPayload(record: JSONObject, current: JSONObject?, draft: JSONObject, reviewedDraft: JSONObject?, inspection: JSONObject): JSONObject {
        check(sameProcedure(record, current)) { "程序记忆已变化，请返回并重新读取" }
        check(reviewedDraft != null && canonical(draft) == canonical(reviewedDraft) && sourceMatches(record, inspection)) { "草稿或来源已变化，请重新核对草稿" }
        val policy = inspection.getJSONObject("policy")
        val options = JSONObject().put("inspectedDigest", inspection.getString("inspectedDigest"))
            .put("requiredCapabilities", JSONArray(policy.getJSONArray("requiredCapabilities").toString())).put("expiresAt", policy.get("expiresAt"))
        return JSONObject().put("recordId", record.getString("id")).put("expectedRevision", MemoryLearningReviewGuard.revision(record))
            .put("draft", JSONObject(draft.toString())).put("options", options)
    }

    @JvmStatic fun validImport(record: JSONObject, inspection: JSONObject, result: JSONObject?): Boolean {
        val skill = result?.optJSONObject("skill") ?: return false
        val origin = skill.optJSONObject("origin") ?: return false
        return skill.optString("id").isNotBlank() && MemoryLearningReviewGuard.revision(skill) > 0 && skill.opt("enabled") == false &&
            skill.optString("approvedDigest").isEmpty() && skill.optString("digest") == inspection.optJSONObject("package")?.optString("digest") &&
            canonical(origin) == canonical(inspection.optJSONObject("origin")) && sourceMatches(record, inspection)
    }

    @JvmStatic fun approvalPayload(frozen: JSONObject, current: JSONObject?, read: JSONObject?): JSONObject {
        check(sameSkill(frozen, current) && current!!.optBoolean("contentVerified") && current.optString("originStatus") == "current") { "技能或来源已变化，请重新读取" }
        check(read != null && read.optString("id") == frozen.optString("id") && read.optLong("revision") == frozen.optLong("revision") &&
            read.optString("digest") == frozen.optString("digest") && read.optString("path") == "SKILL.md" && !read.optBoolean("hasMore", true)) { "请先读取完整的当前 SKILL.md" }
        check(frozen.optJSONObject("origin") != null && current.optJSONArray("statusReasons")?.toString()?.contains("expired") != true) { "技能来源或有效期不可用" }
        return JSONObject().put("id", frozen.getString("id")).put("revision", frozen.getInt("revision")).put("digest", frozen.getString("digest"))
    }

    @JvmStatic fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}

private data class ExperienceRequest(val command: String, val id: String, val payload: JSONObject)

@Composable
internal fun ExperienceSkillReviewPage(
    snapshot: JSONObject,
    responses: Map<String, JSONObject>,
    pendingCommands: Set<String>,
    loading: Boolean,
    error: String?,
    onRequest: (command: String, payload: JSONObject) -> String,
    onBack: () -> Unit,
    onOpenInferenceRecord: ((JSONObject) -> Unit)? = null
) {
    var policyTask by remember { mutableStateOf("") }
    var policyPackage by remember { mutableStateOf("") }
    var policyReceipt by remember { mutableStateOf<JSONObject?>(null) }
    var policyConfirmed by remember { mutableStateOf(false) }
    var showLearningPolicy by remember { mutableStateOf(false) }
    var appIdentities by remember { mutableStateOf<JSONArray?>(null) }
    var appPackage by remember { mutableStateOf("") }
    var appVersion by remember { mutableStateOf("") }
    var appBase by remember { mutableStateOf<JSONObject?>(null) }
    var appLesson by remember { mutableStateOf(false) }
    var showAppRevision by remember { mutableStateOf(false) }
    var appApplications by remember { mutableStateOf<JSONArray?>(null) }
    var qualityApplications by remember { mutableStateOf<JSONArray?>(null) }
    var qualityNote by remember { mutableStateOf("") }
    var qualityOutcome by remember { mutableStateOf("verified") }
    var procedure by remember { mutableStateOf<JSONObject?>(null) }
    var selectedSkill by remember { mutableStateOf<JSONObject?>(null) }
    var draft by remember { mutableStateOf<JSONObject?>(null) }
    var reviewedDraft by remember { mutableStateOf<JSONObject?>(null) }
    var inspection by remember { mutableStateOf<JSONObject?>(null) }
    var request by remember { mutableStateOf<ExperienceRequest?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var showImported by remember { mutableStateOf(false) }
    var showResources by remember { mutableStateOf(false) }
    var selectedPath by remember { mutableStateOf("SKILL.md") }
    var readBody by remember { mutableStateOf("") }
    var readReceipt by remember { mutableStateOf<JSONObject?>(null) }
    var manifestReceipt by remember { mutableStateOf<JSONObject?>(null) }
    var inferenceReference by remember { mutableStateOf<JSONObject?>(null) }
    val procedures = experienceRows(snapshot.optJSONArray("procedures"))
    val skills = experienceRows(snapshot.optJSONArray("skills")).filter { it.optJSONObject("origin") != null }
    val currentProcedure = procedures.firstOrNull { it.optString("id") == procedure?.optString("id") }
    val currentSkill = skills.firstOrNull { it.optString("id") == selectedSkill?.optString("id") }
    val busy = request != null || pendingCommands.any { it in setOf("draftExperience", "inspectExperience", "importExperience", "approveExperience", "readSkill", "skillQualityApplications", "reviewSkillQuality", "draftAppRevision", "procedureAppIdentities") }
    val procedureCurrent = ExperienceSkillReviewGuard.sameProcedure(procedure, currentProcedure)
    val skillCurrent = ExperienceSkillReviewGuard.sameSkill(selectedSkill, currentSkill)
    val scroll = rememberScrollState()

    fun submit(command: String, payload: JSONObject = JSONObject()) {
        if (request != null) return
        localError = null; message = null
        try { request = ExperienceRequest(command, onRequest(command, JSONObject(payload.toString())), JSONObject(payload.toString())) }
        catch (failure: Exception) { localError = failure.message ?: "无法发起操作，请重试" }
    }
    fun cancelDraft() {
        val active = request ?: return
        if (active.command !in setOf("draftExperience", "draftAppRevision")) return
        try {
            onRequest("cancelLearning", JSONObject().put("requestId", active.id))
            request = null; message = "已请求停止提炼；迟到的结果不会覆盖当前审阅。"
        } catch (failure: Exception) { localError = failure.message ?: "停止请求未送达，请重试" }
    }
    fun resetDetail() {
        appApplications = null; qualityApplications = null; qualityNote = ""; showAppRevision = false; appBase = null; appIdentities = null; appPackage = ""; appVersion = ""
        procedure = null; selectedSkill = null; draft = null; reviewedDraft = null; inspection = null
        selectedPath = "SKILL.md"; readBody = ""; readReceipt = null; manifestReceipt = null; localError = null; message = null; inferenceReference = null
    }
    fun goBack() {
        if (request?.command == "draftExperience") { confirmation = "cancelAndBack"; return }
        if (busy) return
        if (procedure != null || selectedSkill != null) { if (draft != null) confirmation = "discard" else resetDetail() } else onBack()
    }
    BackHandler { goBack() }

    val pending = request
    LaunchedEffect(pending?.id, responses[pending?.command]?.toString()) {
        val active = request ?: return@LaunchedEffect
        val envelope = MemoryLearningReviewGuard.exactEnvelope(active.id, responses[active.command]) ?: return@LaunchedEffect
        request = null
        if (!envelope.optBoolean("ok")) { localError = envelope.optString("error", "操作未完成，请重试"); return@LaunchedEffect }
        val result = envelope.optJSONObject("result") ?: run { localError = "缺少原生操作回执，请刷新核对"; return@LaunchedEffect }
        try {
            when (active.command) {
                "getAppLearningPolicy", "setAppLearningPolicy" -> {
                    check(result.getString("taskId") == policyTask) { "原任务选择已改变，请重新读取" }
                    policyReceipt = JSONObject(result.toString()); policyConfirmed = false
                    message = if (result.getJSONObject("policy").optBoolean("enabled")) "已授权此任务限定 App 的终态提炼；只进入原待审候选，不自动采用。" else "自动提炼关闭；已有模型调用不能撤回，后续发送与候选保存会再次核对授权。"
                }

                "draftExperience" -> {
                    val source = procedure ?: kotlin.error("原程序记忆已关闭")
                    check(result.optString("recordId") == source.optString("id") && result.optLong("recordRevision") == source.optLong("revision")) { "返回的草稿不属于当前程序记忆" }
                    val checked = result.getJSONObject("inspection"); check(ExperienceSkillReviewGuard.sourceMatches(source, checked)) { "草稿来源版本不一致" }
                    draft = JSONObject(result.getJSONObject("draft").toString()); reviewedDraft = JSONObject(draft.toString()); inspection = JSONObject(checked.toString())
                    inferenceReference = result.optJSONObject("inferenceReference")?.let { JSONObject(it.toString()) }
                    message = "草稿已生成，尚未导入或启用。请核对正文、适用条件与附加资料。"
                }
                "inspectExperience" -> {
                    val source = procedure ?: kotlin.error("原程序记忆已关闭")
                    check(ExperienceSkillReviewGuard.canonical(active.payload.getJSONObject("draft")) == ExperienceSkillReviewGuard.canonical(draft)) { "核对期间草稿已变化" }
                    val checked = result.getJSONObject("inspection"); check(ExperienceSkillReviewGuard.sourceMatches(source, checked)) { "草稿来源版本不一致" }
                    inspection = JSONObject(checked.toString()); reviewedDraft = JSONObject(draft.toString()); message = "当前草稿与来源已核对，导入后仍将保持停用。"
                }
                "importExperience" -> {
                    check(ExperienceSkillReviewGuard.validImport(procedure ?: kotlin.error("原程序已关闭"), inspection ?: kotlin.error("缺少审阅版本"), result)) { "导入回执与审阅内容不一致，请刷新核对" }
                    selectedSkill = JSONObject(result.getJSONObject("skill").toString()); procedure = null; draft = null; reviewedDraft = null; inspection = null
                    readBody = ""; readReceipt = null; manifestReceipt = null; selectedPath = "SKILL.md"
                    message = "已导入，保持停用。读取当前文件后，你可单独决定是否批准启用。"
                }
                "readSkill" -> {
                    val skill = selectedSkill ?: kotlin.error("技能已关闭")
                    check(result.optString("id") == skill.optString("id") && result.optLong("revision") == skill.optLong("revision") && result.optString("digest") == skill.optString("digest") && result.optString("path") == active.payload.optString("path")) { "读取内容与当前技能版本不一致" }
                    val offset = active.payload.optInt("offset"); check(offset == 0 || offset == readReceipt?.optInt("nextOffset")) { "读取位置已变化" }
                    readBody = if (offset == 0) result.getString("content") else readBody + result.getString("content")
                    readReceipt = JSONObject(result.toString())
                    if (result.optString("path") == "SKILL.md" && !result.optBoolean("hasMore", true)) manifestReceipt = JSONObject(result.toString())
                }
                "approveExperience" -> {
                    val approved = result.getJSONObject("skill")
                    check(ExperienceSkillReviewGuard.sameSkill(selectedSkill, approved) && approved.optBoolean("enabled") && approved.optString("approvedDigest") == approved.optString("digest")) { "启用回执与审阅版本不一致，请刷新核对" }
                    selectedSkill = JSONObject(approved.toString()); message = "已批准当前版本。只会作为相关任务的参考，仍需任务本身的工具授权。"
                }
                "procedureAppIdentities" -> { appIdentities = result.getJSONArray("apps"); showAppRevision = true }
                "draftAppRevision" -> { message = if (result.optBoolean("proposed")) "App 修订已生成待审候选；请到学习候选审核，接受后才会试用替换。" else "没有可靠增量，保留当前经验。" }
                "appQualityApplications" -> { appApplications = result.getJSONArray("applications"); message = if (!result.optBoolean("completeHistory")) "当前仅显示原应用记录的有界窗口，不代表完整历史。" else null }
                "reviewAppQuality" -> { appApplications = null; qualityNote = ""; message = "已保存原方法采用的 App 结果归责；刷新可查看试用、转正或回滚。" }
                "skillQualityApplications" -> { qualityApplications = result.getJSONArray("applications"); message = if (result.optBoolean("scanLimited")) "仅扫描最近200个执行记录，更早使用尚未加载。" else null }
                "reviewSkillQuality" -> { selectedSkill = JSONObject(result.getJSONObject("skill").toString()); qualityApplications = null; qualityNote = ""; message = "已保存原任务结果归责；试用状态已更新，没有增加执行权限。" }
                "snapshot" -> Unit
            }
        } catch (failure: Exception) { localError = failure.message ?: "原生回执无效，请刷新后重试" }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { goBack() }, enabled = !busy || request?.command == "draftExperience") { Text("返回") }
                Text(if (selectedSkill != null) "审阅经验技能" else if (procedure != null) "提炼经验技能" else "经验技能", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { submit("snapshot") }, enabled = !busy) { Text("刷新", fontSize = 12.sp) }
            }
            HorizontalDivider(color = Line)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading || busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink, trackColor = Soft)
                    ExperienceCaption(if (request?.command == "draftExperience") "正在使用已配置模型提炼草稿；不会执行工具。" else "正在核对原生结果，不会重复提交。")
                    if (request?.command == "draftExperience") TextButton(onClick = { cancelDraft() }) { Text("停止提炼") }
                }
                (localError ?: error)?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 22.sp) }
                message?.let { ExperienceCaption(it) }
                inferenceReference?.takeIf { it.optString("taskId").matches(Regex("[a-fA-F0-9-]{36}")) && it.opt("executionAuthorized") == false }?.let { reference ->
                    onOpenInferenceRecord?.let { open -> TextButton(onClick = { open(JSONObject(reference.toString())) }) { Text("查看本次草稿的模型调用与用量") } }
                }
                if (procedure == null && selectedSkill == null) {
                    TextButton(onClick = { showLearningPolicy = !showLearningPolicy }, enabled = !busy) { Text("任务结束后自动起草 App 经验") }
                    if (showLearningPolicy) {
                        ExperienceCaption("默认关闭。重新授权只恢复输入未变且从未开始的原提炼任务；已有或未知调用须在原记录审阅，不会自动重发。仅对你选择的原私人任务、一个 App 包名生效，每轮最多一次模型调用。实际安装版本与动作由设备原回执核对（顺利结果至少五步，教训至少三步）；主人确认未完成或原生步数上限可生成教训；模型错误、未知/取消不提炼，不宣称成功。")
                        OutlinedTextField(value = policyTask, onValueChange = { policyTask = it.trim(); policyReceipt = null; policyConfirmed = false }, label = { Text("原任务 ID（任务详情可复制）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { submit("getAppLearningPolicy", JSONObject().put("taskId", policyTask)) }, enabled = !busy && policyTask.matches(Regex("[a-f0-9-]{36}"))) { Text("读取原任务授权") }
                        policyReceipt?.let { saved ->
                            val policy = saved.getJSONObject("policy")
                            ExperienceCaption("当前：${if (policy.optBoolean("enabled")) "已开启" else "关闭"} · 授权版本 ${policy.optLong("revision")} · ${policy.optJSONArray("packages") ?: JSONArray()}")
                            experienceRows(saved.optJSONArray("jobs")).forEach { job ->
                                SelectionContainer { ExperienceCaption("原提炼任务 ${job.optString("taskId")} · ${job.optString("status")} · ${when (job.optString("authorizationStatus")) { "disabled" -> "授权已关闭"; "prior_call_requires_review" -> "已有调用，须核对原记录，不会自动重发"; "awaiting_exact_input_recheck" -> "等待重核原输入，仅未开始任务可恢复"; else -> "授权有效" }}（在逐批提炼页查看用量与待审结果）") }
                            }
                            OutlinedTextField(value = policyPackage, onValueChange = { policyPackage = it.trim(); policyConfirmed = false }, label = { Text("限定 App 包名（选择，不是安装证明）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = policyConfirmed, onCheckedChange = { policyConfirmed = it }, enabled = !busy)
                                Text("我同意任务终态后，将此 App 的原动作证据和当前经验发送给已配置模型，仅生成待审候选。", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            }
                            TextButton(onClick = { submit("setAppLearningPolicy", JSONObject().put("taskId", policyTask).put("expectedRevision", policy.getLong("revision")).put("enabled", true).put("packages", JSONArray().put(policyPackage)).put("maxCalls", 1).put("confirmed", policyConfirmed)) }, enabled = !busy && policyConfirmed && policyPackage.isNotBlank()) { Text("授权此任务的限定 App") }
                            TextButton(onClick = { submit("setAppLearningPolicy", JSONObject().put("taskId", policyTask).put("expectedRevision", policy.getLong("revision")).put("enabled", false).put("packages", JSONArray()).put("maxCalls", 1).put("confirmed", false)) }, enabled = !busy && policy.optBoolean("enabled")) { Text("关闭后续自动提炼") }
                        }
                    }
                }
                when {
                    procedure != null -> {
                        val source = procedure!!
                        Text("先核对经验，再决定是否复用", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Text(source.optString("text"), color = Ink, fontSize = 15.sp, lineHeight = 24.sp) }
                        ExperienceCaption("适用条件：${source.optString("applicability")}")
                        ExperienceCaption("记忆已被接受，不代表方法已验证。来源改变或被遗忘后，派生技能将失效。")
                        if (!procedureCurrent) Text("原程序记忆已变化或不可用，请返回并刷新。", color = Danger, fontSize = 13.sp)
                        if (source.optJSONObject("appKnowledge") != null) {
                            TextButton(onClick = { submit("appQualityApplications", JSONObject().put("recordId", source.getString("id")).put("revision", source.getLong("revision"))) }, enabled = !busy) { Text("核对 App 经验原步骤的试用结果") }
                            appApplications?.let { applications ->
                                ExperienceCaption("模型说采用和动作成功都不是业务成功。请查看原任务后归责；环境失败、取消不计失败。")
                                OutlinedTextField(value = qualityNote, onValueChange = { qualityNote = it }, label = { Text("实际结果与归责依据") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                                listOf("verified" to "已核对成功", "skill_failed" to "方法失败", "verifier_rejected" to "核验不通过", "environment" to "环境问题", "cancelled" to "已取消", "model_error" to "模型错误").forEach { (value, label) ->
                                    TextButton(onClick = { qualityOutcome = value }, enabled = !busy) { Text((if (qualityOutcome == value) "✓ " else "") + label) }
                                }
                                experienceRows(applications).filter { it.optString("applicationState") == "model_apply_with_native_steps" }.forEach { application ->
                                    val prior = application.optJSONObject("appQualityFeedback")
                                    SelectionContainer { ExperienceCaption("原任务 ${application.optString("taskId")} · 原步骤 ${application.optJSONArray("stepRefs")?.length() ?: 0}") }
                                    Button(onClick = { submit("reviewAppQuality", JSONObject().put("recordId", source.getString("id")).put("revision", source.getLong("revision")).put("taskId", application.getString("taskId")).put("operationId", application.getString("operationId")).put("outcome", prior?.getString("outcome") ?: qualityOutcome).put("note", prior?.getString("note") ?: qualityNote)) }, enabled = !busy && (prior != null || qualityNote.isNotBlank())) { Text(if (prior == null) "确认此 App 试用结果" else "核对并恢复状态同步") }
                                }
                            }
                        }
                        TextButton(onClick = { if (showAppRevision) showAppRevision = false else submit("procedureAppIdentities", JSONObject().put("recordId", source.getString("id")).put("expectedRevision", source.getLong("revision"))) }, enabled = !busy && procedureCurrent) { Text("从此任务经验修订 App 知识") }
                        if (showAppRevision) {
                            ExperienceCaption("指定真实使用的 App 版本。模型只起草候选；审核接受后暂时替换旧稿，失败按归责回退。不会新增权限。")
                            val observedApps = appIdentities ?: JSONArray()
                            if (observedApps.length() == 0) ExperienceCaption("原任务没有可核对的已安装 App 版本观察，不能用手填版本冒充证明。请在新任务中实际观察该 App 后再提炼。")
                            for (i in 0 until observedApps.length()) {
                                val observed = observedApps.getJSONObject(i)
                                TextButton(onClick = { appPackage = observed.getString("packageName"); appVersion = observed.getString("appVersion"); appBase = null }, enabled = !busy) { Text((if (appPackage == observed.optString("packageName") && appVersion == observed.optString("appVersion")) "✓ " else "") + observed.optString("packageName") + " · " + observed.optString("versionName") + " / " + observed.optString("appVersion")) }
                            }
                            val bases = procedures.filter { it.optJSONObject("appKnowledge")?.let { app -> app.optString("packageName") == appPackage && app.optString("appVersion") == appVersion && app.optString("stage") == "verified" } == true && it.optBoolean("active") }
                            TextButton(onClick = { appBase = null }, enabled = !busy) { Text(if (appBase == null) "✓ 首次建立（无旧稿）" else "首次建立（无旧稿）") }
                            bases.forEach { base -> TextButton(onClick = { appBase = base }, enabled = !busy) { Text((if (appBase?.optString("id") == base.optString("id")) "✓ " else "") + "修订：" + base.optString("text").take(60)) } }
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = appLesson, onCheckedChange = { appLesson = it }, enabled = !busy); Text("来自磕绊或失败的教训（连续两次归责失败回退）", fontSize = 13.sp) }
                            Button(onClick = { submit("draftAppRevision", JSONObject().put("recordId", source.getString("id")).put("expectedRevision", source.getLong("revision")).put("packageName", appPackage).put("appVersion", appVersion).put("baseId", appBase?.optString("id") ?: "").put("baseRevision", appBase?.optLong("revision") ?: 0).put("lesson", appLesson)) }, enabled = !busy && procedureCurrent && appPackage.isNotBlank() && appVersion.isNotBlank()) { Text("发送此经验与旧稿，生成待审修订") }
                        }
                        if (draft == null) Button(onClick = { confirmation = "generate" }, enabled = !busy && procedureCurrent) { Text("生成可审阅草稿") }
                        else {
                            HorizontalDivider(color = Line)
                            Text("Skill 草稿 · 未导入", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            OutlinedTextField(value = draft!!.optString("manifest"), onValueChange = { text ->
                                if (text.length <= 64000) { draft = JSONObject(draft.toString()).put("manifest", text); inspection = null; reviewedDraft = null }
                            }, enabled = !busy, label = { Text("SKILL.md 正文") }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp), minLines = 8)
                            val resources = draft!!.optJSONObject("resources")
                            if (resources != null && resources.length() > 0) {
                                TextButton(onClick = { showResources = !showResources }) { Text(if (showResources) "收起附加资料" else "查看 ${resources.length()} 份附加资料") }
                                if (showResources) resources.keys().asSequence().toList().sorted().forEach { path ->
                                    Text(path, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    SelectionContainer { Text(resources.optString(path), color = Ink, fontSize = 13.sp, lineHeight = 21.sp) }
                                }
                            }
                            ExperienceCaption("导入只保存停用版本；启用是后续独立操作。草稿不会授予权限，也不会运行脚本。")
                            if (inspection == null) Button(onClick = { submit("inspectExperience", JSONObject().put("recordId", source.getString("id")).put("expectedRevision", source.getLong("revision")).put("draft", draft).put("options", JSONObject())) }, enabled = !busy && procedureCurrent) { Text("核对修改后的草稿") }
                            else Button(onClick = { confirmation = "import" }, enabled = !busy && procedureCurrent) { Text("导入为停用技能") }
                        }
                    }
                    selectedSkill != null -> {
                        val skill = selectedSkill!!
                        Text(skill.optString("name"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(skill.optString("description"), color = Ink, fontSize = 15.sp, lineHeight = 24.sp)
                        ExperienceCaption("版本 ${skill.optLong("revision")} · ${when (currentSkill?.optString("qualityState")) { "trial" -> "试用中"; "active" -> "已有主人成功核对"; "suspended" -> "因归责失败暂停"; else -> "尚无质量核对" }} · ${if (currentSkill?.optBoolean("enabled") == true) "可供参考" else "保持停用"}")
                        if (!skillCurrent) Text("技能已变化或不可用，请返回并刷新。", color = Danger, fontSize = 13.sp)
                        if (currentSkill != null && currentSkill.optString("originStatus") != "current") Text("经验来源当前不可用，不能批准或继续使用旧正文。", color = Danger, fontSize = 13.sp)
                        val paths = skill.optJSONArray("resources") ?: JSONArray().put("SKILL.md")
                        TextButton(onClick = { showResources = !showResources }) { Text(if (showResources) "收起文件列表" else "选择查看的文件（${paths.length()}）") }
                        if (showResources) for (i in 0 until paths.length()) {
                            val path = paths.optString(i)
                            TextButton(onClick = { selectedPath = path; readBody = ""; readReceipt = null }, enabled = !busy) { Text(if (path == selectedPath) "✓ $path" else path) }
                        }
                        Text(selectedPath, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (readBody.isNotEmpty() && skillCurrent && currentSkill?.optString("originStatus") == "current") SelectionContainer { Text(readBody, color = Ink, fontSize = 14.sp, lineHeight = 23.sp) }
                        val more = readReceipt?.optBoolean("hasMore") == true
                        TextButton(onClick = { submit("readSkill", JSONObject().put("id", skill.getString("id")).put("revision", skill.getInt("revision")).put("digest", skill.getString("digest")).put("path", selectedPath).put("offset", if (more) readReceipt!!.getInt("nextOffset") else 0).put("length", 12000)) }, enabled = !busy && skillCurrent) { Text(if (more) "继续读取" else if (readBody.isEmpty()) "读取当前文件" else "重新读取") }
                        TextButton(onClick = { submit("skillQualityApplications", JSONObject().put("id", skill.getString("id")).put("revision", skill.getInt("revision")).put("digest", skill.getString("digest"))) }, enabled = !busy && skillCurrent) { Text("查看实际使用并核对结果") }
                        qualityApplications?.let { applications ->
                            if (applications.length() == 0) ExperienceCaption("没有此版本的确定执行回执。模型自称成功、仅阅读技能、未知结果不计为成功。")
                            else {
                                OutlinedTextField(value = qualityNote, onValueChange = { if (it.length <= 1000) qualityNote = it }, label = { Text("实际结果与归责依据") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                listOf("verified" to "我已核对成功", "skill_failed" to "方法导致失败", "verifier_rejected" to "按方法执行但核查退回", "environment" to "环境问题（不归责）", "cancelled" to "取消（不归责）", "model_error" to "模型错误（不归责）").forEach { (value, label) ->
                                    TextButton(onClick = { qualityOutcome = value }, enabled = !busy) { Text((if (qualityOutcome == value) "✓ " else "") + label) }
                                }
                                for (i in 0 until applications.length()) {
                                    val application = applications.getJSONObject(i)
                                    ExperienceCaption("任务 ${application.optString("taskId")} · ${application.optString("status")} · 操作 ${application.optString("operationId")}")
                                    val prior = application.optJSONObject("qualityFeedback")
                                    if (prior != null) ExperienceCaption("已记录：${prior.optString("outcome")} · ${prior.optString("note")}")
                                    Button(onClick = { submit("reviewSkillQuality", JSONObject().put("id", skill.getString("id")).put("revision", skill.getInt("revision")).put("digest", skill.getString("digest")).put("taskId", application.getString("taskId")).put("operationId", application.getString("operationId")).put("outcome", prior?.getString("outcome") ?: qualityOutcome).put("note", prior?.getString("note") ?: qualityNote)) }, enabled = !busy && skillCurrent && (prior != null || qualityNote.isNotBlank())) { Text(if (prior == null) "确认此使用结果" else "核对并恢复状态同步") }
                                }
                            }
                        }
                        ExperienceCaption("批准只允许在相关任务中检索这份参考，不会增加任何工具权限。请核对正文及附加资料。")
                        if (currentSkill?.optBoolean("enabled") != true) Button(onClick = {
                            try { ExperienceSkillReviewGuard.approvalPayload(skill, currentSkill, manifestReceipt); confirmation = "approve" } catch (failure: Exception) { localError = failure.message }
                        }, enabled = !busy && skillCurrent && manifestReceipt != null && currentSkill?.optString("originStatus") == "current") { Text("批准试用当前版本") }
                    }
                    else -> {
                        Text("让已审阅的经验，成为可复用参考", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        ExperienceCaption("从接受过的程序记忆开始。生成、导入和启用分开确认；这里不会自动保存正式记忆或发起任务。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showImported, onClick = { showImported = false }, label = { Text("程序记忆") })
                            FilterChip(selected = showImported, onClick = { showImported = true }, label = { Text("已导入技能") })
                        }
                        val rows = if (showImported) skills else procedures.filter(ExperienceSkillReviewGuard::eligible)
                        if (rows.isEmpty() && !loading) ExperienceCaption(if (showImported) "尚未导入经验技能。先从程序记忆生成并审阅草稿。" else "没有可提炼的程序记忆。先从真实消息或已确认任务提炼候选，并在学习候选中审阅接受。")
                        rows.forEach { row ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                resetDetail(); if (showImported) selectedSkill = JSONObject(row.toString()) else procedure = JSONObject(row.toString())
                            }.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(if (showImported) row.optString("name") else row.optString("text"), color = Ink, fontSize = 15.sp, lineHeight = 23.sp, maxLines = 3)
                                ExperienceCaption(if (showImported) "${if (row.optBoolean("enabled")) "已批准" else "停用"} · 版本 ${row.optLong("revision")} · 查看内容与来源" else "${row.optString("applicability")} · 提炼前可再次核对")
                            }
                            HorizontalDivider(color = Line)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
    confirmation?.let { action ->
        val title = when (action) { "generate" -> "生成经验草稿？"; "import" -> "导入为停用技能？"; "approve" -> "批准当前版本？"; "cancelAndBack" -> "停止提炼并返回？"; else -> "放弃未导入草稿？" }
        val explanation = when (action) {
            "generate" -> "将把这条已接受的程序记忆发送给当前配置的模型，可能消耗模型额度。仅生成草稿，不执行工具、不导入或启用。"
            "import" -> "将保存刚核对的完整草稿和来源绑定。导入后保持停用，你仍需单独决定是否启用。"
            "approve" -> "只批准当前内容与来源版本，作为相关任务的低优先级参考。它不会赋予工具权限，也不代表方法已验证。"
            "cancelAndBack" -> "停止后不再接收此提炼的结果。已经发出的模型请求可能仍计入服务商用量。"
            else -> "此草稿尚未导入。返回后不再保留编辑内容，原程序记忆不会改变。"
        }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = { Text(explanation) }, confirmButton = {
            TextButton(onClick = {
                confirmation = null
                try {
                    when (action) {
                        "generate" -> { check(procedureCurrent); submit("draftExperience", JSONObject().put("recordId", procedure!!.getString("id")).put("expectedRevision", procedure!!.getLong("revision"))) }
                        "import" -> submit("importExperience", ExperienceSkillReviewGuard.importPayload(procedure!!, currentProcedure, draft!!, reviewedDraft, inspection!!))
                        "approve" -> submit("approveExperience", ExperienceSkillReviewGuard.approvalPayload(selectedSkill!!, currentSkill, manifestReceipt))
                        "cancelAndBack" -> { cancelDraft(); resetDetail() }
                        else -> resetDetail()
                    }
                } catch (failure: Exception) { localError = failure.message ?: "内容已变化，请刷新后重试" }
            }) { Text(when (action) { "generate" -> "生成草稿"; "import" -> "确认导入"; "approve" -> "批准启用"; "cancelAndBack" -> "停止并返回"; else -> "放弃草稿" }) }
        }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("继续核对") } })
    }
}

private fun experienceRows(rows: JSONArray?): List<JSONObject> = (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
@Composable private fun ExperienceCaption(text: String) { Text(text, color = SecondaryInk, fontSize = 13.sp, lineHeight = 22.sp) }
