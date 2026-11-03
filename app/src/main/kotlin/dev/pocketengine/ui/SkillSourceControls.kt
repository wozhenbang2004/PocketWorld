package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.skills.SkillBook
import dev.pocketengine.features.skills.SkillGitSource
import dev.pocketengine.features.skills.SkillSourcePolicy
import dev.pocketengine.features.skills.SkillsModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun skillSourceClassification(value: String): String = when (value) {
    "current" -> "内容与上次来源一致"
    "upstream_changed" -> "上游有更新，本地未修改"
    "local_changed" -> "本地已修改，上游内容未变"
    "both_changed" -> "本地和上游都有改动"
    "untracked" -> "尚未建立这份来源的本地基线"
    else -> "暂不能确认变化"
}

internal fun skillSourceReviewMatches(view: JSONObject, target: JSONObject?): Boolean {
    val id = view.optString("targetId")
    if (id.isEmpty()) return target == null && view.optJSONObject("comparison")?.optString("kind") == "new"
    val comparison = view.optJSONObject("comparison") ?: return false
    return target != null && target.optString("id") == id && comparison.optString("id") == id &&
        comparison.optInt("revision", -1) == target.optInt("revision", -2) &&
        comparison.optString("expectedCurrentDigest") == target.optString("digest")
}

/** Native owner surface. A preview is an opaque Book object, not a JSON authority token. */
@Composable
internal fun SkillSourceControls(state: PocketUiState, controller: PocketWorldController) {
    val app = LocalContext.current.applicationContext as? EngineApplication
    val module = remember(app) { app?.features?.module("skills") as? SkillsModule }
    val worker = rememberCoroutineScope()
    val skills = state.feature("skills").optJSONArray("skills").objects().filter { !it.has("origin") }
    var expanded by remember { mutableStateOf(false) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var targetsOpen by remember { mutableStateOf(false) }
    var repository by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var subpath by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<SkillBook.SourceReview?>(null) }
    var batch by remember { mutableStateOf<SkillsModule.SourceBatch?>(null) }
    var selectedBatch by remember { mutableStateOf<Set<String>>(emptySet()) }
    var history by remember { mutableStateOf<JSONObject?>(null) }
    var body by remember { mutableStateOf<JSONObject?>(null) }
    var file by remember { mutableStateOf("SKILL.md") }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var cancellable by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<RunControl?>(null) }
    var request by remember { mutableLongStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val target = skills.find { it.optString("id") == targetId }
    val view = review?.view()
    val current = view != null && skillSourceReviewMatches(view, target)
    DisposableEffect(Unit) { onDispose { request++; active?.cancel() } }

    fun work(canCancel: Boolean = false, block: suspend (RunControl) -> Unit) {
        if (busy || module == null) return
        busy = true; cancellable = canCancel; error = null; message = null
        val control = RunControl(); active = control; val ticket = ++request
        worker.launch {
            try { block(control) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (request == ticket) error = if (control.isCancelled) { if (canCancel) "检查已停止，本地技能未更新。" else "处理已中断，请核对技能列表中已完成的结果；不会自动重试。" } else when (failure) {
                    is SkillBook.SourceUnavailable, is dev.pocketengine.features.skills.SkillGitClient.Unavailable, is IllegalArgumentException -> failure.message
                    else -> "本次处理未能确认，请核对技能列表后重新检查；不会自动重试。"
                }
                controller.refresh()
            } finally { if (request == ticket) { busy = false; active = null; cancellable = false } }
        }
    }
    fun clearPreview() { review = null; history = null; body = null; batch = null; discard = false }
    fun select(id: String?) {
        targetId = id; targetsOpen = false; clearPreview()
        val original = skills.find { it.optString("id") == id }?.optJSONObject("gitSource")?.optJSONObject("source")
        repository = original?.optString("repository").orEmpty(); ref = original?.optString("ref").orEmpty(); subpath = original?.optString("subpath").orEmpty()
    }

    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Line)
    OutlinedButton(onClick = { expanded = !expanded }, enabled = !busy) { Text(if (expanded) "收起来源与版本" else "来源、调用与版本") }
    message?.let { SourceCaption(it) }
    error?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 21.sp) }
    if (!expanded) return
    if (module == null) { SourceCaption("技能模块暂不可用，请刷新后重试。"); return }
    SourceCaption("从公开 GitHub 仓库检查完整文本技能。新内容先保持停用；不会执行脚本，也不会恢复旧授权。其他 Git 服务和私有仓库暂不支持。")
    Box {
        TextButton(onClick = { targetsOpen = true }, enabled = !busy) { Text(target?.optString("name") ?: "登记为新技能") }
        DropdownMenu(expanded = targetsOpen, onDismissRequest = { targetsOpen = false }) {
            DropdownMenuItem(text = { Text("登记为新技能") }, onClick = { select(null) })
            skills.forEach { skill -> DropdownMenuItem(text = { Text(skill.optString("name") + " · 修订 " + skill.optInt("revision")) }, onClick = { select(skill.optString("id")) }) }
        }
    }
    target?.let { selected ->
        SkillSourcePolicyControls(selected, busy) { layer, invocation ->
            val id = selected.getString("id"); val revision = selected.getInt("revision")
            val digest = selected.getString("digest"); val generation = selected.optLong("executionGeneration", 0)
            work {
                withContext(Dispatchers.IO) { module.configureSourceForOwner(skillOwnerPrivateScope(), id, revision, digest, generation, layer, invocation) }
                clearPreview(); message = "来源与调用方式已更新，此技能已停用。请核对内容后重新启用。"; controller.refresh()
            }
        }
    }
    OutlinedTextField(repository, { repository = it; clearPreview() }, label = { Text("仓库 https://github.com/owner/repo") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(ref, { ref = it; clearPreview() }, label = { Text("明确分支、标签或 commit") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    OutlinedTextField(subpath, { subpath = it; clearPreview() }, label = { Text("技能子目录（根目录可留空）") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            try {
                val source = SkillGitSource(repository.trim(), ref.trim(), subpath.trim()); val id = targetId
                clearPreview()
                work(true) { control -> review = withContext(Dispatchers.IO) { module.inspectGitSource(skillOwnerPrivateScope(), id, source, control) }; file = "SKILL.md" }
            } catch (failure: IllegalArgumentException) { error = failure.message }
        }, enabled = !busy && repository.isNotBlank() && ref.isNotBlank()) { Text("检查来源") }
        if (target != null) TextButton(onClick = {
            val id = targetId!!; review = null; body = null
            work { history = withContext(Dispatchers.IO) { module.retainedRevisions(skillOwnerPrivateScope(), id, 0, 10) } }
        }, enabled = !busy) { Text("查看保留版本") }
    }
    if (busy) {
        SourceCaption(if (cancellable) "正在读取固定 commit 的所选子目录，尚未导入。" else "正在处理，请稍后核对结果。")
        if (cancellable) TextButton(onClick = { active?.cancel() }) { Text("停止本次检查") }
    }
    history?.let { page ->
        Text("保留版本", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
        SourceCaption("仅可恢复来源元数据和内容都能核对的修订。旧版本缺少记录时不会猜测版本或时间，仍可用原 ZIP 导入流程恢复已知备份。")
        page.optJSONArray("revisions").objects().forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("修订 " + row.optInt("revision"), fontSize = 13.sp)
                    SourceCaption(if (row.optBoolean("available")) "来源版本 " + row.optString("sourceVersion") else "内容或版本记录不可核对")
                }
                TextButton(onClick = {
                    val id = page.getString("id"); val revision = row.getInt("revision"); body = null; discard = false
                    work { review = withContext(Dispatchers.IO) { module.inspectRetainedRevision(skillOwnerPrivateScope(), id, revision) }; file = "SKILL.md" }
                }, enabled = row.optBoolean("available") && !busy && targetId == page.optString("id")) { Text("检查此版本") }
            }
        }
        if (!page.isNull("beforeRevision")) TextButton(onClick = {
            val id = page.getString("id"); val cursor = page.getInt("beforeRevision")
            work { history = withContext(Dispatchers.IO) { module.retainedRevisions(skillOwnerPrivateScope(), id, cursor, 10) } }
        }, enabled = !busy && targetId == page.optString("id")) { Text("查看较早版本") }
    }
    if (view != null && review != null) {
        val pin = review!!
        val restored = view.optString("kind") == "restore_revision"
        val info = view.getJSONObject("package")
        Text(if (restored) "待恢复：修订 " + view.getJSONObject("restoredFrom").optInt("revision") else skillSourceClassification(view.optString("classification")), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
        if (restored) SourceCaption("来源版本 " + view.optString("historicalSourceVersion"))
        else view.optJSONObject("source")?.let { source -> SelectionContainer { SourceCaption("固定 commit " + source.optString("commit") + "\n内容摘要 " + source.optString("digest")) } }
        SourceCaption("${info.optString("name")} · ${info.optLong("sizeBytes")} 字节。全部文件共同校验；不存在的旧文件会从新修订中移除。")
        for ((key, label) in listOf("added" to "新增", "removed" to "移除", "modified" to "修改")) {
            val entries = view.getJSONObject("comparison").optJSONArray(key)
            if (entries != null && entries.length() > 0) SourceCaption(label + "：" + (0 until entries.length()).joinToString("、") { entries.getString(it) })
        }
        val resources = info.optJSONArray("resources")
        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            if (resources != null) for (i in 0 until resources.length()) {
                val name = resources.getString(i)
                TextButton(onClick = { file = name; body = null; work { body = withContext(Dispatchers.IO) { module.readSourceReview(skillOwnerPrivateScope(), pin, name, 0, 6000) } } }, enabled = !busy) { Text("查看 " + name, fontSize = 13.sp) }
            }
        }
        body?.let { page ->
            SelectionContainer { Text(page.optString("content"), fontSize = 13.sp, lineHeight = 21.sp) }
            if (page.optBoolean("hasMore")) TextButton(onClick = {
                val offset = page.getInt("nextOffset"); val name = file
                work { body = withContext(Dispatchers.IO) { module.readSourceReview(skillOwnerPrivateScope(), pin, name, offset, 6000) } }
            }, enabled = !busy) { Text("读取下一段（替换当前显示）") }
        }
        if (view.optBoolean("requiresDiscardConfirmation")) Row {
            Checkbox(checked = discard, onCheckedChange = { discard = it }, enabled = !busy)
            Text(if (restored) "已核对差异，确认用所选旧内容生成新的停用修订" else "已核对本地改动，确认用候选完整内容替换为新修订", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 12.dp).weight(1f))
        }
        if (!current) SourceCaption("当前选择或修订已变化，请重新检查。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val confirmation = discard
                work {
                    val result = withContext(Dispatchers.IO) { module.applySourceReview(skillOwnerPrivateScope(), pin, SkillBook.SourceChoice.REPLACE, confirmation) }
                    clearPreview(); message = "已生成修订 ${result.getJSONObject("skill").getInt("revision")}，保持停用。请另行审阅和启用。"; controller.refresh()
                }
            }, enabled = current && !busy && (!view.optBoolean("requiresDiscardConfirmation") || discard)) { Text(if (restored) "确认恢复为新修订" else if (targetId == null) "确认导入" else "确认更新") }
            if (!restored && targetId != null) TextButton(onClick = {
                work {
                    val result = withContext(Dispatchers.IO) { module.applySourceReview(skillOwnerPrivateScope(), pin, SkillBook.SourceChoice.FORK, false) }
                    clearPreview(); message = "已另存新技能（修订 ${result.getJSONObject("skill").getInt("revision")}），原技能保留，新技能停用。"; controller.refresh()
                }
            }, enabled = current && !busy) { Text("另存为新技能") }
        }
        TextButton(onClick = { clearPreview(); message = "已保留当前技能，未应用候选内容。" }, enabled = !busy) { Text("保留当前内容") }
    }

    val tracked = skills.filter { it.has("gitSource") }
    if (tracked.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
        Text("批量检查已登记来源", fontWeight = FontWeight.Medium)
        SourceCaption("每次最多选择8项。检查失败或有本地修改的项目保留不动，可逐项处理。")
        tracked.forEach { skill ->
            val id = skill.getString("id")
            Row {
                Checkbox(checked = id in selectedBatch, onCheckedChange = { checked -> selectedBatch = if (checked) selectedBatch + id else selectedBatch - id; batch = null }, enabled = !busy && (id in selectedBatch || selectedBatch.size < 8))
                Text(skill.optString("name"), fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }
        OutlinedButton(onClick = {
            val ids = selectedBatch.toList(); clearPreview()
            work(true) { control -> batch = withContext(Dispatchers.IO) { module.inspectTrackedSources(skillOwnerPrivateScope(), ids, control) } }
        }, enabled = !busy && selectedBatch.isNotEmpty()) { Text("检查所选来源") }
        batch?.let { checked ->
            val rows = checked.rows().objects()
            rows.forEachIndexed { index, row ->
                SourceCaption(skills.find { it.optString("id") == row.optString("id") }?.optString("name").orEmpty() + "：" + if (row.optString("status") == "checked") skillSourceClassification(row.getJSONObject("review").optString("classification")) else "检查未完成 · " + row.optString("reason"))
                if (row.optString("status") == "checked") TextButton(onClick = {
                    val original = checked.review(index)
                    select(row.optString("id")); review = original; body = null; discard = false
                }, enabled = !busy) { Text("单独查看与处理") }
            }
            Button(onClick = {
                work { control ->
                    val result = withContext(Dispatchers.IO) { module.applyCleanSourceUpdates(skillOwnerPrivateScope(), checked, control) }
                    batch = null; message = result.getJSONArray("items").objects().joinToString("\n") { row -> (skills.find { it.optString("id") == row.optString("id") }?.optString("name") ?: row.optString("id")) + "：" + when (row.optString("status")) { "imported_disabled" -> "已更新并停用"; "skipped" -> if (row.optString("reason") == "already_current") "内容未变，无需更新" else "已保留，需单独检查"; else -> "未更新 · " + row.optString("reason") } }; controller.refresh()
                }
            }, enabled = !busy && rows.any { it.optJSONObject("review")?.optString("classification") == "upstream_changed" }) { Text("仅更新本地未修改的项目") }
        }
    }
}

@Composable
private fun SkillSourcePolicyControls(skill: JSONObject, busy: Boolean, apply: (SkillSourcePolicy.Layer, SkillSourcePolicy.Invocation) -> Unit) {
    val policy = skill.optJSONObject("sourcePolicy") ?: JSONObject()
    val currentLayer = runCatching { SkillSourcePolicy.Layer.valueOf(policy.optString("layer", "USER")) }.getOrDefault(SkillSourcePolicy.Layer.USER)
    val currentInvocation = runCatching { SkillSourcePolicy.Invocation.valueOf(policy.optString("invocation", "MODEL_AND_OWNER")) }.getOrDefault(SkillSourcePolicy.Invocation.MODEL_AND_OWNER)
    val identity = skill.optString("id") + ":" + skill.optInt("revision") + ":" + skill.optString("digest") + ":" + skill.optLong("executionGeneration", 0)
    var expanded by remember(identity) { mutableStateOf(false) }
    var layer by remember(identity) { mutableStateOf(currentLayer) }
    var invocation by remember(identity) { mutableStateOf(currentInvocation) }
    var layersOpen by remember(identity) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, enabled = !busy) { Text(if (expanded) "收起来源顺序与调用方式" else "来源顺序与调用方式") }
    if (!expanded) return
    SourceCaption("同名技能依次采用项目、个人、随附来源；同层按登记标识稳定选择。更高顺序的来源不可用时，请核对或停用后再切换。调整不会改变原始出处，也不代表信任其代码。")
    SelectionContainer { SourceCaption("来源：${skill.optString("source")}\n版本：${skill.optString("sourceVersion")}") }
    if (skill.has("shadowedBy")) SourceCaption("此技能已被同名的更高顺序来源覆盖；当前保留在列表中，可继续核对内容。")
    Box {
        OutlinedButton(onClick = { layersOpen = true }, enabled = !busy) { Text(skillLayerLabel(layer)) }
        DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
            SkillSourcePolicy.Layer.values().forEach { option ->
                DropdownMenuItem(text = { Text(skillLayerLabel(option)) }, onClick = { layer = option; layersOpen = false })
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Checkbox(checked = invocation == SkillSourcePolicy.Invocation.OWNER_ONLY, onCheckedChange = { invocation = if (it) SkillSourcePolicy.Invocation.OWNER_ONLY else SkillSourcePolicy.Invocation.MODEL_AND_OWNER }, enabled = !busy)
        Text("仅由我明确选择和发起", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 12.dp).weight(1f))
    }
    if (policy.optBoolean("manifestRestrictsModel")) SourceCaption("技能清单已禁止模型调用；取消此选项也不会放宽清单限制。")
    if (!policy.optBoolean("userInvocable", true)) SourceCaption("技能清单禁止主人直接调用；这里不能改写该限制。")
    SourceCaption("保存后撤回当前启用与旧引用。每次脚本执行仍需要原任务审阅、运行环境和能力权限。")
    OutlinedButton(onClick = { apply(layer, invocation) }, enabled = !busy && (layer != currentLayer || invocation != currentInvocation)) { Text("保存并停用，重新审阅") }
}

private fun skillLayerLabel(layer: SkillSourcePolicy.Layer): String = when (layer) {
    SkillSourcePolicy.Layer.PROJECT -> "项目来源 · 优先"
    SkillSourcePolicy.Layer.USER -> "个人来源"
    SkillSourcePolicy.Layer.BUNDLED -> "随附来源 · 备用"
}

@Composable private fun SourceCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)) }
