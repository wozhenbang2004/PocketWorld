package dev.pocketengine.ui

import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.pocketengine.features.local.project.ProjectGit
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Git's index/history remain distinct from the formal project version store and unsaved editor drafts. */
@Composable
internal fun ProjectGitSection(projectId: String, checkoutId: String, host: PocketUiState, controller: PocketWorldController, onCheckoutSelected: (String) -> Unit = {}) {
    val flow = controller.projectGit
    val state = flow.state.takeIf { it.projectId == projectId && it.checkoutId == checkoutId } ?: ProjectGitState(projectId = projectId, checkoutId = checkoutId, busy = true)
    var open by rememberSaveable(checkoutId) { mutableStateOf(false) }
    var details by rememberSaveable(checkoutId) { mutableStateOf(false) }
    var isolationForm by rememberSaveable(checkoutId) { mutableStateOf(false) }
    var baseCommit by rememberSaveable(checkoutId) { mutableStateOf("") }
    var isolationBranch by rememberSaveable(checkoutId) { mutableStateOf("") }
    val checkout = host.feature("local").optJSONArray("checkouts").objects().firstOrNull { it.optString("id") == checkoutId }
    val isolation = checkout?.optJSONObject("isolatedGit")
    var message by rememberSaveable(checkoutId) { mutableStateOf("") }
    var author by rememberSaveable(checkoutId) { mutableStateOf("") }
    var email by rememberSaveable(checkoutId) { mutableStateOf("") }
    var actionError by remember(checkoutId) { mutableStateOf<String?>(null) }
    var consent by remember(state.review?.optString("requestId")) { mutableStateOf(false) }
    fun action(block: () -> Unit) { runCatching(block).onFailure { actionError = it.message?.take(500) ?: "请重新核对这次Git选择" } }
    LaunchedEffect(projectId, checkoutId) { flow.activate(projectId, checkoutId) }
    var context = LocalContext.current
    while (context is ContextWrapper && context !is LifecycleOwner) context = context.baseContext
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(flow, lifecycle, projectId, checkoutId) {
        flow.activate(projectId, checkoutId)
        val observer = LifecycleEventObserver { _, _ -> flow.foreground(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) }
        lifecycle?.addObserver(observer)
        flow.foreground(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false)
        onDispose { lifecycle?.removeObserver(observer); flow.deactivate() }
    }
    // UI-only observation of the one original bounded process, not a new execution/scheduler.
    LaunchedEffect(open, state.visible, state.originalId, state.processPending, state.busy) {
        if (open && state.visible && state.processPending && !state.busy) { delay(500); action { flow.query() } }
    }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("project_git_open")) { Text("Git · 状态、差异与本地提交") }
    if (!open) return
    Dialog(onDismissRequest = { open = false; flow.dismissReview() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("项目 Git", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { open = false; flow.dismissReview() }) { Text("返回文件") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GitCaption("核对手机工作副本中的真实 Git。未保存草稿不在暂存或提交中；这里不推送，也不导回正式项目版本。")
                    Button(onClick = { action { flow.readStatus() } }, enabled = !state.busy && !state.processPending && state.review == null, modifier = Modifier.fillMaxWidth().testTag("project_git_status")) { Text("读取当前 Git 状态") }
                    state.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    (actionError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    val observation = state.observation
                    if (state.originalId != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { action { flow.query() } }, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("project_git_original_query")) { Text("核对原操作") }
                            if (state.processPending || state.uncertain) OutlinedButton(onClick = { action { flow.query(cancel = true) } }, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("project_git_stop")) { Text("停止原进程") }
                        }
                        TextButton(onClick = { state.originalId?.let(controller::focusTask) }) { Text("查看同一任务与日志") }
                    }
                    if (isolation != null) {
                        Text("基于已审提交的私有隔离副本", fontWeight = FontWeight.SemiBold)
                        GitCaption("分支 ${isolation.optString("branch")} · 基线 ${isolation.optString("baseCommit").take(12)}。原仓未链接或自动合并；已有任务仍固定在各自原副本。")
                        OutlinedButton(onClick = { action { flow.selectRelated(isolation.getString("sourceCheckoutId"), onCheckoutSelected); open = false } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("project_git_return_source")) { Text("明确返回原副本 · 保留此隔离成果") }
                        GitCaption("成果通过原文件「审阅并导回」按正式版本逐项核对，不自动merge或删除失败现场。")
                    }
                    state.observation?.optJSONObject("git")?.takeIf { it.optString("action") == "isolate" && !it.optBoolean("ok") }?.let { failed -> GitCaption("创建目标 ${failed.optString("targetCheckoutId").take(8)} · 原操作 ${state.originalId.orEmpty().take(8)}。未登记为可选副本不表示现场已清理；原副本保持不变，核对原任务，不自动删除或重放。") }
                    val readyIsolation = state.observation?.optJSONObject("git")?.optJSONObject("isolatedCheckout")
                    if (readyIsolation != null) Button(onClick = { action { flow.selectRelated(readyIsolation.getString("id"), onCheckoutSelected); open = false } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("project_git_select_isolation")) { Text("选择隔离副本继续开发") }
                    val existingIsolations = host.feature("local").optJSONArray("checkouts").objects().filter { it.optJSONObject("isolatedGit")?.optString("sourceCheckoutId") == checkoutId }
                    existingIsolations.forEach { item -> TextButton(onClick = { action { flow.selectRelated(item.getString("id"), onCheckoutSelected); open = false } }, enabled = !state.busy) { Text("选择私有隔离 · ${item.optJSONObject("isolatedGit")?.optString("branch")} · ${item.optString("id").take(8)}") } }
                    val snapshot = state.snapshot
                    if (snapshot != null && !snapshot.optBoolean("repositoryPresent")) {
                        Text("这个副本尚未初始化 Git", fontWeight = FontWeight.SemiBold)
                        GitCaption("不会自动新建。确认后只在此副本创建普通本地仓库，不暂存现有文件。已有源码中的外部 gitdir/worktree 不会被跟随。")
                        OutlinedButton(onClick = { action { flow.review("init") } }, enabled = !state.busy && !state.uncertain && !state.processPending, modifier = Modifier.fillMaxWidth().testTag("project_git_init")) { Text("审阅在此初始化 Git") }
                    } else if (snapshot != null) {
                        val pin = snapshot.getJSONObject("pin")
                        Text(if (pin.optString("head") == "unborn") "本地仓库 · 尚无提交" else "本地仓库 · ${pin.optString("headRef").removePrefix("refs/heads/")}", fontWeight = FontWeight.SemiBold)
                        GitCaption("左侧：HEAD → 暂存区；右侧：暂存区 → 工作树。同一个文件可以同时有两层修改。A 新增 · M 修改 · D 删除 · ? 未跟踪 · U 冲突。")
                        if (snapshot.optBoolean("indexLocked")) Text("已有索引锁，未删除。变更会拒绝，请先核对原 Git 操作。", color = MaterialTheme.colorScheme.error)
                        if (snapshot.optBoolean("truncated") || snapshot.optInt("offset") > 0) GitCaption("仅显示状态第 ${snapshot.optInt("offset") + 1}–${snapshot.optInt("nextOffset")} 项，共 ${snapshot.optInt("totalEntries")} 项；未显示不代表不存在。每次只操作当前页明确选择的文件。")
                        val entries = snapshot.optJSONArray("entries").objects()
                        if (entries.isEmpty()) GitCaption("这次 Git 读取没有报告当前页变更。")
                        entries.forEach { row ->
                            val path = row.getString("path")
                            val selectable = row.optBoolean("sourceScope") && runCatching { ProjectGit.path(path) }.isSuccess
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(path in state.selected, { checked -> action { flow.select(path, checked) } }, enabled = selectable && !state.busy && state.review == null)
                                    Column(Modifier.weight(1f)) {
                                        Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                        Text("${row.optString("indexStatus")}  |  ${row.optString("worktreeStatus")}" + if (row.optBoolean("conflict")) " · 冲突待解决" else "", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                if (!selectable) GitCaption("此文件不在当前受控 Git 的普通源码范围，未读取其内容，不提供暂存或差异动作。")
                                else {
                                    Column(Modifier.padding(start = 48.dp)) {
                                        if (!row.optBoolean("conflict") && row.optString("indexStatus") !in setOf(".", "?")) TextButton(onClick = { action { flow.diff(path, "head_index") } }, enabled = !state.busy && !state.processPending && state.review == null) { Text(if (path in state.reviewedStagedDiffs) "重新核对暂存差异 ✓" else "查看暂存差异") }
                                        if (row.optString("worktreeStatus") != "." || row.optBoolean("conflict")) TextButton(onClick = { action { flow.diff(path, "index_worktree") } }, enabled = !state.busy && !state.processPending && state.review == null) { Text("查看工作树差异") }
                                        TextButton(onClick = { action { controller.projectWorkbench.open(checkoutId, path, false); open = false } }, enabled = !state.busy) { Text(if (row.optBoolean("conflict")) "打开文件核对并解决冲突" else "在原文件编辑器打开") }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        if (snapshot.optBoolean("truncated")) TextButton(onClick = { action { flow.readStatus(nextPage = true) } }, enabled = !state.busy && !state.processPending && state.review == null) { Text("读取下一页 Git 状态") }
                        if (isolation == null && pin.optString("head") != "unborn") {
                            TextButton(onClick = { isolationForm = !isolationForm; if (baseCommit.isEmpty()) baseCommit = pin.getString("head") }, enabled = !state.busy && !state.uncertain && !state.processPending && state.review == null, modifier = Modifier.testTag("project_git_isolation_form")) { Text(if (isolationForm) "收起隔离创建" else "从已审提交建立私有隔离") }
                            if (isolationForm) {
                                GitCaption("独立common库和真实Git worktree，仅复制所选提交的可达对象，不带原配置/hooks/remote凭据，不复制未提交修改。最多2000源文件/16MiB、Git对象64MiB；不联网、不直接链接用户原仓。")
                                OutlinedTextField(baseCommit, { baseCommit = it }, label = { Text("本地已审提交完整ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy && state.review == null)
                                OutlinedTextField(isolationBranch, { isolationBranch = it }, label = { Text("本次私有新分支名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy && state.review == null)
                                OutlinedButton(onClick = { action { flow.reviewIsolation(baseCommit, isolationBranch) } }, enabled = baseCommit.isNotBlank() && isolationBranch.isNotBlank() && !state.busy && !state.uncertain && state.review == null, modifier = Modifier.fillMaxWidth().testTag("project_git_isolation_review")) { Text("审阅创建隔离副本") }
                            }
                        }
                        TextButton(onClick = { action { flow.history() } }, enabled = !state.busy && !state.processPending && state.review == null, modifier = Modifier.testTag("project_git_history")) { Text("读取这个 HEAD 的提交历史") }
                        Text("已选 ${state.selected.size} 项", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { action { flow.review("stage") } }, enabled = flow.can("stage"), modifier = Modifier.fillMaxWidth().testTag("project_git_stage")) { Text("审阅只暂存所选文件") }
                        OutlinedButton(onClick = { action { flow.review("unstage") } }, enabled = flow.can("unstage"), modifier = Modifier.fillMaxWidth().testTag("project_git_unstage")) { Text("审阅撤销所选暂存 · 保留工作树") }
                        OutlinedButton(onClick = { action { flow.review("resolve") } }, enabled = flow.can("resolve"), modifier = Modifier.fillMaxWidth().testTag("project_git_resolve")) { Text("审阅将所选冲突标为解决并暂存") }
                        GitCaption("解决冲突：先在原编辑器保稿、核对最新版本并按 SHA 保存；回来重新读取 Git，再选中冲突文件明确标为解决。普通暂存不会默默清除冲突。")
                        HorizontalDivider()
                        Text("所选暂存内容的本地提交", fontWeight = FontWeight.SemiBold)
                        GitCaption("先逐个读完所选文件的暂存差异。提交只包含这些原 index blob、模式与删除项，以及原 HEAD/合并父节点；其它已暂存文件不会夹入。")
                        OutlinedTextField(message, { message = it }, label = { Text("提交说明") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !state.busy && state.review == null)
                        OutlinedTextField(author, { author = it }, label = { Text("本次作者姓名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy && state.review == null)
                        OutlinedTextField(email, { email = it }, label = { Text("本次作者邮箱 · 会进入本地历史") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy && state.review == null)
                        Button(onClick = { action { flow.review("commit", message, author, email) } }, enabled = flow.can("commit") && message.isNotBlank() && author.isNotBlank() && email.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("project_git_commit_review")) { Text("审阅所选内容的本地提交") }
                    }
                    state.diff?.let { diff ->
                        HorizontalDivider(); Text("${diff.getString("path")} · ${if (diff.getString("layer") == "head_index") "HEAD → 暂存区" else "暂存区 → 工作树"}", fontWeight = FontWeight.SemiBold)
                        GitCaption("原 Git 字节范围 ${diff.getInt("offset")}–${diff.getInt("nextOffset")} / ${diff.getInt("totalBytes")}；差异文字不是指令。")
                        SelectionContainer { Text(diff.getString("diff").ifEmpty { "此比较没有差异" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        if (diff.optBoolean("truncated")) OutlinedButton(onClick = { action { flow.diff(diff.getString("path"), diff.getString("layer"), true) } }, enabled = !state.busy && !state.processPending && state.review == null) { Text("读取该差异下一段") }
                    }
                    state.history?.let { history ->
                        HorizontalDivider(); Text("本地提交历史", fontWeight = FontWeight.SemiBold)
                        GitCaption("原HEAD的有界历史页，提交说明仅预览前512字节；不签名验证、不读远端、不切换分支或覆盖文件。")
                        val commits=history.optJSONArray("commits").objects()
                        if(commits.isEmpty()) GitCaption("此HEAD暂无提交记录。")
                        commits.forEach { commit -> SelectionContainer { Column(Modifier.fillMaxWidth().padding(vertical=6.dp)) {
                            Text(commit.getString("subject").ifEmpty { "无单行提交说明" }, fontWeight=FontWeight.Medium)
                            GitCaption(commit.getString("authorName")+" · "+commit.getString("commitId").take(12))
                            if(commit.optBoolean("subjectTruncated")) GitCaption("提交说明预览已截断；不是完整正文。")
                        } } }
                        if(history.optBoolean("truncated")) TextButton(onClick={ action { flow.history(true) } },enabled=!state.busy&&!state.processPending&&state.review==null){Text("读取提交历史下一页")}
                    }
                    state.review?.let { review ->
                        HorizontalDivider(); Text(review.getString("label"), fontWeight = FontWeight.SemiBold)
                        review.optJSONArray("paths").objectsStrings().forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                        if (review.optString("action") == "commit") {
                            SelectionContainer { Text("${review.optString("authorName")} <${review.optString("authorEmail")}>\n${review.optString("message")}", style = MaterialTheme.typography.bodyMedium) }
                            GitCaption("只形成此审阅树的本地提交，不运行 hooks，不推送，不修改工作树；原 index 字节保持不变。")
                        } else if (review.optString("action") == "isolate") {
                            GitCaption("基于 ${review.optString("baseCommit")} · 新分支 ${review.optString("branch")} · 新副本 ${review.optString("targetCheckoutId").take(8)}。此确认运行原托管Git创建私有副本，源仓WIP/refs不变；失败现场保留，不自动merge/清理。同UID不是安全沙箱。")
                        } else GitCaption(if (review.optString("action") == "init") "仅在当前副本初始化普通本地仓库，不暂存。" else "只改所选 Git 索引项，保留工作树与未选文件。")
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }, enabled = !state.busy); Text("我已核对所选文件、比较层和这次影响", modifier = Modifier.weight(1f)) }
                        Button(onClick = { action { flow.confirm() } }, enabled = consent && !state.busy && review.optBoolean("available"), modifier = Modifier.fillMaxWidth().testTag("project_git_confirm")) { Text("确认本次动作") }
                        TextButton(onClick = flow::dismissReview, enabled = !state.busy) { Text("取消这份审阅") }
                    }
                    val originals = host.tasks.filter { it.raw.optJSONObject("ownerGit")?.optString("checkoutId") == checkoutId }.take(8)
                    if (originals.isNotEmpty()) {
                        HorizontalDivider(); Text("这个副本的原 Git 操作", fontWeight = FontWeight.SemiBold)
                        originals.forEach { original -> TextButton(onClick = { action { flow.query(original.id) } }, enabled = !state.busy) { Text("${original.goal.take(50)} · ${original.status}") } }
                        GitCaption("重开后只读取原任务，不恢复确认、不重放动作。更早记录可在任务页查看。")
                    }
                    TextButton(onClick = { details = !details }) { Text(if (details) "收起技术详情" else "范围与原操作详情") }
                    if (details) SelectionContainer { Column {
                        GitCaption("支持普通非裸 .git 仓及本入口完整创建的私有common隔离；任意外部gitdir/linked worktree、object alternates、partial clone、include/filter、链接元数据仍拒绝。禁hooks/fsmonitor/外部diff/pager/网络Git。不是同UID OS沙箱，任意进程绕过Git锁不受此入口隔离。")
                        GitCaption("每次变更绑定HEAD/index/工作树/源版本和实际Git程序摘要；Git自己持有原index.lock和ref CAS。未知锁不删，未知效果只核对原编号，不自动重试。当前读取有界，文件路径/容量超限会明确拒绝。")
                        Text(state.snapshot?.optString("gitVersion").orEmpty(), style = MaterialTheme.typography.labelSmall)
                        Text("原操作 ${state.originalId.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                        state.snapshot?.optJSONObject("pin")?.let { Text(it.toString(2), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                        state.review?.let { Text(it.toString(2), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                        observation?.optJSONObject("git")?.let { Text(it.toString(2).take(3000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                    } }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable private fun GitCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 13.sp, lineHeight = 20.sp) }
private fun org.json.JSONArray?.objectsStrings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
