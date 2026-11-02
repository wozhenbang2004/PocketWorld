package dev.pocketengine.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** One low-frequency native project intake, not an account browser or generic
 * shell/clone permission. The original task and local catalogue remain owners. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeProjectGitImport(flow: ProjectGitImportCoordinator, showEntry: Boolean = true) {
    val state = flow.state
    if (showEntry) TextButton(onClick = flow::open, modifier = Modifier.testTag("project_git_import_open")) { Text("从 Git 仓库导入…") }
    DisposableEffect(flow) { onDispose { flow.unmount() } }
    if (!state.open) return
    var url by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("refs/heads/main") }
    var name by remember { mutableStateOf("Git 项目") }
    var authenticate by remember { mutableStateOf(false) }
    // Deliberately not rememberSaveable: tokens do not enter Bundle/preferences.
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var acceptedRisk by remember(state.review?.optString("actionId")) { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var showFiles by remember(state.review?.optString("actionId")) { mutableStateOf(false) }
    var original by remember { mutableStateOf(state.sourceAction.orEmpty()) }
    var forget by remember { mutableStateOf(false) }
    val fetchStatus = state.fetch?.optString("status")
    val publicationStatus = state.publication?.let { it.optString("originalTaskStatus", it.optString("status")) }
    val pending = state.sourceAction != null && fetchStatus !in setOf("completed", "failed") || state.publicationAction != null && publicationStatus != "completed"
    val editable = !state.busy && state.review == null && !pending
    LaunchedEffect(state.review) { if (state.review != null) { username = ""; token = "" } }
    LaunchedEffect(state.open, state.busy, state.sourceAction, fetchStatus, state.publicationAction, publicationStatus) {
        if (!state.busy && state.open && state.sourceAction != null && fetchStatus in setOf("running", "queued")) { delay(700); flow.querySource() }
        else if (!state.busy && state.open && state.publicationAction != null && publicationStatus in setOf("running", "queued")) { delay(700); flow.queryPublication() }
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text("只清除页面定位？") }, text = { Text("不会取消任务或删除文件，也不会重放旧操作。已有未决动作仍会阻止新的读取与发布；请保留需要核对的原编号。") }, confirmButton = { TextButton(onClick = { forget = false; flow.forgetPosition() }) { Text("只清除定位") } }, dismissButton = { TextButton(onClick = { forget = false }) { Text("保留") } })
    ModalBottomSheet(onDismissRequest = { username = ""; token = ""; flow.closePage() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PageSurface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 22.dp).navigationBarsPadding().imePadding()) {
            Text("导入 Git 项目", style = MaterialTheme.typography.titleLarge, color = Ink)
            GitImportCaption(when {
                state.checkout != null -> "项目已就绪 · 可继续开发"
                state.publicationAction != null -> "第 3 步 · 核对项目创建结果"
                state.review?.optString("reviewKind") == "publish" -> "第 2 步 · 核对固定版本"
                state.sourceAction != null -> "第 1 步 · 核对分支读取结果"
                state.review != null -> "第 1 步 · 确认联网读取范围"
                else -> "读取分支 → 核对版本 → 创建项目"
            })
            Spacer(Modifier.height(12.dp))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Ink, trackColor = Line)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                item {
                    state.notice?.let { ImportFeedback(it) }
                    state.error?.let { ImportFeedback(it, error = true) }
                    if (state.review == null && !pending && state.sourceAction != null) ImportDisclosure("导入另一个仓库", showForm) { showForm = !showForm }
                    if (state.review == null && !pending && (state.sourceAction == null || showForm)) ImportSurface("仓库来源", "只读取指定分支，不安装依赖或运行代码") {
                        OutlinedTextField(url, { if (it.length <= 2048) url = it }, label = { Text("HTTPS 仓库地址") }, supportingText = { Text("请勿在地址中填写令牌") }, shape = RoundedCornerShape(10.dp), enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("project_git_import_url"))
                        OutlinedTextField(branch, { if (it.length <= 192) branch = it }, label = { Text("固定分支") }, supportingText = { Text("例如 refs/heads/main") }, shape = RoundedCornerShape(10.dp), enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("新项目名") }, shape = RoundedCornerShape(10.dp), enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                        HorizontalDivider(color = Line)
                        ImportCheckRow("使用一次性认证", "仅本次发送用户名与令牌，不保存账号", authenticate, editable) { authenticate = it; if (!it) { username = ""; token = "" } }
                        AnimatedVisibility(authenticate) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(username, { if (it.length <= 256) username = it }, label = { Text("用户名") }, shape = RoundedCornerShape(10.dp), enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(token, { if (it.length <= 4096) token = it }, label = { Text("仅本次使用的访问令牌") }, shape = RoundedCornerShape(10.dp), enabled = editable, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("project_git_import_token"))
                            }
                        }
                        if (editable) ImportPrimaryAction(onClick = { flow.review(url, branch, name, if (authenticate) username else "", if (authenticate) token else "") }, enabled = url.isNotEmpty() && branch.isNotEmpty() && name.isNotBlank() && (!authenticate || username.isNotEmpty() && token.isNotEmpty()), modifier = Modifier.testTag("project_git_import_review")) { Text("下一步：核对读取范围") }
                    }
                }
                state.review?.let { review ->
                    val publishing = review.optString("reviewKind") == "publish"
                    item {
                        ImportSurface(if (publishing) "确认创建新项目" else "确认这次联网读取", if (publishing) "核对固定版本后，再创建独立副本" else "仅批准下列来源与分支") {
                        val source = if (publishing) review.getJSONObject("source") else review.getJSONObject("target")
                        GitImportCaption(source.optString("url"))
                        GitImportCaption((if (publishing) source.optString("branch") else review.optString("branch")) + if (publishing) " · " + source.optString("commitId").take(12) else "")
                        if (publishing) GitImportCaption("${source.optString("projectName")} · ${source.optLong("sourceBytes")} 字节源码；${source.optLong("gitMetadataBytes")} 字节同版Git历史与索引。原项目不覆盖，凭据、旧任务和授权不继承。")
                        else GitImportCaption("本机 ${review.optString("gitVersion")}；${if (review.optBoolean("credentialProvided")) "仅本次向该来源传送已填写认证" else "不传送认证"}。读取完整历史但不执行仓库代码。PRoot/同UID不是OS沙箱；同UID进程仍可能访问文件和内存。")
                        HorizontalDivider(color = Line)
                        ImportCheckRow(if (publishing) "我已核对固定源码与历史" else "我已核对来源、认证与同UID风险", if (publishing) "创建新的项目副本，不覆盖原项目" else "仅批准这次读取，不授予代码执行权限", acceptedRisk, !state.busy) { acceptedRisk = it }
                        ImportPrimaryAction(onClick = flow::confirm, enabled = acceptedRisk && !state.busy, modifier = Modifier.fillMaxWidth().testTag(if (publishing) "project_git_import_confirm_publish" else "project_git_import_confirm_fetch")) { Text(if (publishing) "创建这个新项目" else "仅开始这次读取") }
                        TextButton(onClick = flow::cancelReview, enabled = !state.busy) { Text("取消审阅，不执行") }
                        if (details) GitImportCaption("原方案：${review.optString("planDigest", review.optJSONObject("plan")?.optString("planDigest").orEmpty())}")
                        if (publishing) ImportDisclosure("源码文件 · ${source.getJSONArray("files").length()} 项", showFiles) { showFiles = !showFiles }
                        }
                    }
                    if (publishing && showFiles) items(review.getJSONObject("source").getJSONArray("files").rows(), key = { it.getString("path") }) { file -> GitImportCaption("${file.getString("path")} · ${file.getLong("bytes")} 字节${if (file.optBoolean("executable")) " · 可执行位" else ""}") }
                }
                state.sourceAction?.let { id -> item {
                    ImportSurface(when (fetchStatus) { "completed" -> "分支读取完成"; "failed" -> "读取未完成"; "running", "queued" -> "正在读取分支"; else -> "读取结果待核对" }) {
                    GitImportCaption(when (fetchStatus) { "completed" -> if (state.fetch?.optBoolean("sourceAvailable", true) == false) "原读取已完成，但保留的来源文件现已不可用，不能再据此创建项目。" else "固定分支已读取，尚未自动发布。"; "failed" -> "原读取已明确结束，未成功完成。"; "running", "queued" -> "原任务已登记，正在读取或等待原进程结果。"; else -> "原结果待核，不重新下载或重送认证。" })
                    TextButton(onClick = { flow.querySource() }, enabled = !state.busy) { Text("核对原结果 · 不重新读取") }
                    if (fetchStatus in setOf("running", "queued", "unknown")) TextButton(onClick = { flow.querySource(stop = true) }, enabled = !state.busy) { Text("停止原读取与连接") }
                    if (fetchStatus == "completed" && state.fetch?.optBoolean("sourceAvailable", true) != false && state.publicationAction == null && state.review == null) ImportPrimaryAction(onClick = flow::reviewPublication, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("下一步：核对源码与历史") }
                    if (details) GitImportCaption(id)
                    }
                } }
                state.publicationAction?.let { id -> item {
                    ImportSurface(if (publicationStatus == "completed") "新项目已创建" else "项目创建结果") {
                    GitImportCaption(if (publicationStatus == "completed") "已找到创建的新项目，未恢复旧执行权限。" else "只核对原复制与发布，不重复创建项目。")
                    TextButton(onClick = { flow.queryPublication() }, enabled = !state.busy) { Text("核对原结果 · 不重复创建") }
                    if (publicationStatus in setOf("queued", "running", "reconciling")) TextButton(onClick = { flow.queryPublication(stop = true) }, enabled = !state.busy) { Text("请求停止后续复制") }
                    if (state.checkout != null) ImportPrimaryAction(onClick = flow::continueProject, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("project_git_import_open_checkout")) { Text("打开新项目，继续开发") }
                    if (details) GitImportCaption(id)
                    }
                } }
                item {
                    ImportDisclosure("支持范围与恢复选项", details) { details = !details }
                    AnimatedVisibility(details) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GitImportCaption("支持普通非裸 HTTPS 仓库的单个明确分支及完整可达历史。SSH、子模块、LFS、浅克隆/部分克隆、外部对象库、链接、特殊文件及不支持的字符会明确拒绝，不以缺文件的工程冒成功。每次最多2000源码文件、单文件2MiB、源码16MiB；Git元数据观察上限256MiB/20000条，传输最多256MiB/10分钟。不是硬磁盘或内存隔离。")
                        GitImportCaption("只通过本次原任务的有限连接访问已审域名与固定公有IP，不跟随重定向，不执行hooks/filter或仓库脚本。一次性认证不进URL、命令参数、任务记录、日志或持久Git配置。PRoot/同UID不是OS沙箱；同UID进程实际可达的文件和内存风险不会被此协议消除。应用存活时原宿主继续查询；不承诺系统后台永不终止。")
                        OutlinedTextField(original, { if (it.length <= 36) original = it }, label = { Text("原Git读取任务编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { flow.querySource(original) }, enabled = !state.busy && original.matches(Regex("[a-f0-9-]{36}"))) { Text("读取原定位，不执行") }
                        TextButton(onClick = { forget = true }, enabled = !state.busy && state.review == null) { Text("仅清除页面定位…") }
                    }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            TextButton(onClick = { username = ""; token = ""; flow.closePage() }, modifier = Modifier.fillMaxWidth()) { Text("返回项目 · 原任务保留") }
        }
    }
}
@Composable private fun GitImportCaption(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
private fun JSONArray.rows(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
