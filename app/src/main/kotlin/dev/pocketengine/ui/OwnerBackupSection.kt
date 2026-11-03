package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Low-frequency native owner tool, not an always-open settings catalogue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeOwnerDataArchives(flow: OwnerBackupCoordinator, showLauncher: Boolean = true) {
    val state = flow.state
    if (showLauncher) TextButton(onClick = flow::open, modifier = Modifier.testTag("owner_data_backup_open")) { Text("备份与迁移") }
    if (!state.open || state.presentation != (if (showLauncher) "settings" else "memory_review")) return
    val projectOnly = state.projectMemoryName != null
    var selected by remember(projectOnly) { mutableStateOf(if (projectOnly) setOf("projects", "memory") else setOf("projects", "artifacts", "memory", "remoteConnections", "sshConnections")) }
    var confirmed by remember(state.review?.optString("actionId")) { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var originalId by remember { mutableStateOf(state.originalAction.orEmpty()) }
    val labels = linkedMapOf("projects" to "项目、保留版本与私人工作副本", "artifacts" to "固定成果与私人原件", "memory" to "记忆档案（恢复后不自动启用）", "experiences" to "原聊天、任务事件与工具原回执（只读快照）", "remoteConnections" to "电脑连接配置（不含凭据）", "sshConnections" to "SSH 配置（不含密钥或信任）")
    LaunchedEffect(state.open, state.originalAction, state.originalPhase?.optString("status"), state.busy) {
        if (state.open && !state.busy && state.originalAction != null && state.originalPhase?.optString("status") in setOf("queued", "running")) { delay(700); flow.pollOriginal() }
    }
    state.passwordPrompt?.let { kind ->
        var password by remember(kind) { mutableStateOf("") }
        var repeated by remember(kind) { mutableStateOf("") }
        val exporting = kind == "export"
        AlertDialog(onDismissRequest = flow::cancelPassword, title = { Text(if (exporting) "设置备份口令" else "解密所选备份") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("至少12个字符，建议使用独立长口令。口令不会保存；遗失后无法恢复。文件完整认证通过后仍须逐分区审阅。", fontSize = 13.sp)
                OutlinedTextField(password, { if (it.length <= 1024) password = it }, label = { Text("备份口令") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                if (exporting) OutlinedTextField(repeated, { if (it.length <= 1024) repeated = it }, label = { Text("再次输入口令") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = { val exact = password.toCharArray(); password = ""; repeated = ""; flow.usePassword(exact) }, enabled = password.length >= 12 && (!exporting || password == repeated)) { Text(if (exporting) "加密并导出" else "解密并只读核对") } }, dismissButton = { TextButton(onClick = flow::cancelPassword) { Text("取消") } })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("移除本机备份缓存？") }, text = { Text("只删除这份临时备份副本，不删除项目、原件、成果或已导出的文件。有原恢复未决时会拒绝移除。") }, confirmButton = { TextButton(onClick = { discard = false; flow.discardLocalCache() }) { Text("仅移除本机缓存") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("保留") } })
    ModalBottomSheet(onDismissRequest = flow::closePage, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("备份与迁移", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Caption("先核对范围，再导出或分区恢复。恢复创建新的身份，不覆盖现有项目，也不恢复旧任务的执行权限。")
            if (projectOnly) Caption("范围：${state.projectMemoryName}。仅此项目的保留版本、已停止私人副本及其记忆；不含主人全局或其它项目。恢复时须先明确恢复项目，再将记忆按原映射放为只读档案，不恢复读取同意。")
            else labels.forEach { (key, label) -> Row(Modifier.fillMaxWidth()) { Checkbox(key in selected, { yes -> selected = if (yes) selected + key else selected - key }, enabled = !state.busy && state.review == null); Text(label, Modifier.weight(1f).padding(top = 13.dp), fontSize = 13.sp) } }
            Button(onClick = { flow.create(selected) }, enabled = selected.isNotEmpty() && (!projectOnly || state.projectMemoryCurrent) && !state.busy && state.review == null, modifier = Modifier.fillMaxWidth().testTag("owner_data_backup_capture")) { Text(if (state.busy) "核对中…" else if (projectOnly) "生成此项目与其记忆的备份" else "生成所选范围的备份") }
            OutlinedButton(onClick = flow::chooseImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("owner_data_backup_read")) { Text("选择加密备份，解密并核对…") }
            TextButton(onClick = { details = !details }) { Text(if (details) "收起范围说明" else "包含什么，不包含什么") }
            if (details) {
                Caption("项目保留当前版本库中的最近八版与删除标记，并复制可读、已停止的私人活动副本。活动副本按项目包规则排除工具环境、依赖缓存、Git 历史及已知账号配置，保留实际排除清单；版本库中的原文件按所选内容保存，仍可能包含敏感文本。未完成的团队活动副本不纳入。模块之间不是一次跨库事务，任何读取失败都不会发布半份备份。")
                Caption("备份上限 1.5GiB，原件仍受原单件和总量限制。文件在磁盘中分段复制，不把整包装入内存；空间不足会明确失败。本机审阅缓存未加密；默认导出为口令加密的 .pwbackup 文件。口令不保存，丢失无法恢复。用户文本、图片或项目内容仍可能含敏感信息。登录凭据、密钥、SSH 信任、会话、执行授权、未决委派与未保存草稿不迁移。Skills/Plugins 安装、模型/API设置、设备配对、自动化规则及运行环境不在此六分区档案中；这不是整机或完整应用备份。")
                TextButton(onClick = flow::choosePlainImport, enabled = !state.busy) { Text("读取旧版未加密 ZIP，只读核对…") }
                state.inventory?.let { Caption("当前 ${it.optJSONArray("projects")?.length() ?: 0} 个项目，${it.optJSONArray("privateCheckouts")?.length() ?: 0} 个私人副本，${it.optJSONArray("workspaceArtifacts")?.length() ?: 0} 项固定成果，${it.optJSONArray("attachments")?.length() ?: 0} 个原件。实际可读取性会在生成时重核。") }
            }
            state.selection?.let { archive ->
                HorizontalDivider()
                Text("固定备份", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Caption("${archive.optLong("archiveBytes")} 字节 · ${archive.optInt("objectCount")} 个原对象")
                OutlinedButton(onClick = flow::offerExport, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("owner_data_backup_export")) { Text("设置口令并导出这份备份…") }
                if (details) TextButton(onClick = flow::offerPlainExport, enabled = !state.busy) { Text("明确导出未加密 ZIP…") }
                TextButton(onClick = { discard = true }, enabled = !state.busy) { Text("移除这份本机备份缓存…") }
                val sections = state.preview?.optJSONObject("review")
                labels.forEach { (key, label) -> sections?.optJSONObject(key)?.let { part ->
                    Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    val summary = when (key) { "projects" -> "${part.optInt("projects")} 个项目 / ${part.optInt("checkouts")} 个活动副本"; "artifacts" -> "${part.optInt("workspaceFiles")} 个文件版本 / ${part.optInt("projectPackages")} 个项目包 / ${part.optInt("attachments")} 个原件"; "memory" -> if (part.optString("scopeMode") == "project_only") "原项目记忆：须先恢复同包项目，再按原映射放置只读数据；不会恢复旧读取同意或执行权限" else "原记忆、删除屏障与候选历史保存为档案；不进入活动记忆或待审队列"; "experiences" -> "原经历按确切版本、时间和hash保存；缺失/撤回原件明确列为缺口，不恢复执行权"; else -> "${part.optJSONArray("profiles")?.length() ?: 0} 项配置，恢复后仍须重新配置凭据或确认主机" }
                    Caption(summary)
                    if (key == "experiences") TextButton(onClick = { flow.readExperiencePage() }, enabled = !state.busy) { Text("打开只读原经历…") }
                    TextButton(onClick = { flow.reviewPhase(key) }, enabled = !state.busy && state.review == null, modifier = Modifier.testTag("owner_data_backup_review_$key")) { Text("审阅恢复这一分区") }
                } }
                if (details) Caption("固定 SHA：${archive.optString("archiveSha256")}\n本机临时定位：${archive.optString("selectionId")}")
            }
            state.experiencePage?.let { page ->
                HorizontalDivider()
                Text("原经历快照 · 第 ${page.optInt("pageIndex") + 1}/${page.optInt("pageCount")} 页", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Caption("快照时间：${ownerExperienceTime(page.opt("snapshotAt"))} · ${ownerExperienceKindLabel(page.optString("kind"))}。只核对归档字节，不证明内容真实或授权仍有效。附件字节须有同包原件分区，按hash核对。")
                val data = page.optJSONObject("data") ?: JSONObject()
                OwnerExperiencePageContent(data, page.optString("kind"))
                Row {
                    TextButton(onClick = { flow.readExperiencePage(page.optInt("pageIndex") - 1) }, enabled = !state.busy && page.optInt("pageIndex") > 0) { Text("上一页") }
                    TextButton(onClick = { flow.readExperiencePage(page.optInt("pageIndex") + 1) }, enabled = !state.busy && page.optBoolean("hasMore")) { Text("下一页") }
                }
            }
            state.review?.let { review ->
                HorizontalDivider()
                Text("确认恢复：${labels[review.optString("phase")]}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (review.optJSONObject("nativeScope")?.optString("scopeMode") == "project_only") Caption("来源：${review.getJSONObject("nativeScope").optString("originalSpaceId")}；仅放到同包已结算项目映射。归档正文不可直接进入 Agent 上下文；读取范围必须重新显选。")
                Caption("只按这份备份的固定分区创建新数据；恢复不启动项目、不连接电脑、不上传、不恢复凭据或执行授权。原件未确认的发布不会自动重试。")
                Row(Modifier.fillMaxWidth()) { Checkbox(confirmed, { confirmed = it }); Text("我已核对该分区，并明确恢复到这台设备", Modifier.weight(1f).padding(top = 13.dp), fontSize = 13.sp) }
                Button(onClick = flow::confirmPhase, enabled = confirmed && !state.busy, modifier = Modifier.fillMaxWidth().testTag("owner_data_backup_confirm_phase")) { Text("仅恢复这个分区") }
                TextButton(onClick = flow::cancelReview) { Text("取消，不恢复") }
            }
            state.originalAction?.let { id ->
                HorizontalDivider()
                Text("原恢复动作", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Caption(when (state.originalPhase?.optString("status")) { "running", "queued" -> "原任务正在恢复；离开页面不会重复执行。"; "completed" -> "原分区已恢复；可打开新项目或查看恢复的原件。"; else -> "原结果待核对；不自动重试或重复创建数据。" })
                TextButton(onClick = flow::queryOriginal, enabled = !state.busy) { Text("只读核对原结果") }
                if (state.originalPhase?.optString("status") in setOf("running", "queued", "stopping")) TextButton(onClick = flow::stopOriginal, enabled = !state.busy) { Text("请求停止后续恢复") }
                if (details) Caption(id)
            }
            state.result?.optJSONArray("checkouts")?.rows()?.filter { it.optBoolean("found") }?.forEach { result -> TextButton(onClick = { flow.openRestoredCheckout(result) }, enabled = !state.busy) { Text("打开恢复项目：${result.optString("name", "项目")}") } }
            state.result?.optJSONArray("attachments")?.rows()?.take(200)?.filter { it.optString("id").isNotEmpty() }?.forEach { result -> TextButton(onClick = { flow.openRestoredAttachment(result) }, enabled = !state.busy) { Text("查看原件：${result.optString("name", "恢复的文件")}") } }
            TextButton(onClick = { originalId = state.originalAction.orEmpty() }) { Text("按原恢复编号重新核对") }
            OutlinedTextField(originalId, { if (it.length <= 36) originalId = it }, label = { Text("原恢复任务编号") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = { flow.readOriginal(originalId) }, enabled = !state.busy && originalId.matches(Regex("[a-f0-9-]{36}"))) { Text("读取原恢复定位，不执行") }
            state.notice?.let { Caption(it) }; state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            TextButton(onClick = flow::closePage, modifier = Modifier.fillMaxWidth()) { Text("完成，返回") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
@Composable private fun Caption(value: String) { Text(value, fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk) }
private fun JSONArray.rows(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
