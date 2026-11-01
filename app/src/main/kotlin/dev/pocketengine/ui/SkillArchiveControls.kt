package dev.pocketengine.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.pocketengine.features.skills.SkillsModule
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class SkillExportPin(val id: String, val revision: Int, val digest: String, val fileName: String)
private data class SkillPickerTarget(val id: String?)
private data class SkillArchivePreview(val uri: Uri, val targetId: String?, val inspection: JSONObject)
private val skillDigestPattern = Regex("[0-9a-f]{64}")

/** The only scope offered by this native owner surface. Archive/model data never selects it. */
internal fun skillOwnerPrivateScope(): JSONObject = JSONObject().put("ownerId", "owner").put("spaceId", "private")

internal fun skillExportPin(skill: JSONObject): SkillExportPin {
    val id = skill.getString("id"); val revision = skill.getInt("revision"); val digest = skill.getString("digest")
    require(id.isNotBlank() && revision > 0 && skillDigestPattern.matches(digest)) { "当前技能版本信息不完整，请刷新" }
    // Only a picker suggestion, never a filesystem path.
    val name = skill.optString("name", "skill").replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "skill" }
    return SkillExportPin(id, revision, digest, "$name.zip")
}

internal fun skillInspectionIsCurrent(inspection: JSONObject, targetId: String?, current: JSONObject?): Boolean {
    val incoming = inspection.optJSONObject("package") ?: return false
    if (!skillDigestPattern.matches(incoming.optString("digest"))) return false
    val comparison = inspection.optJSONObject("comparison") ?: return false
    if (targetId == null) return comparison.optString("kind") == "new" && !comparison.has("id")
    if (current == null || current.optString("id") != targetId || comparison.optString("id") != targetId) return false
    return comparison.optString("kind") in setOf("same-content", "changed-content", "repair") &&
        comparison.optInt("revision", -1) > 0 && comparison.optInt("revision") == current.optInt("revision", -2) &&
        skillDigestPattern.matches(comparison.optString("expectedCurrentDigest")) && comparison.optString("expectedCurrentDigest") == current.optString("digest")
}

internal fun skillImportOptions(inspection: JSONObject, targetId: String?, source: String, current: JSONObject?): JSONObject {
    check(skillInspectionIsCurrent(inspection, targetId, current)) { "当前技能版本已变化，请重新选择文件并检查" }
    require(source.isNotBlank()) { "请填写这份文件的来源" }
    return JSONObject().put("source", source.trim()).put("inspectedDigest", inspection.getJSONObject("package").getString("digest")).also { options ->
        if (targetId != null) {
            val comparison = inspection.getJSONObject("comparison")
            options.put("id", targetId).put("revision", comparison.getInt("revision")).put("expectedCurrentDigest", comparison.getString("expectedCurrentDigest"))
        }
    }
}

/** A receipt escapes only after both provider flush and close succeed. */
internal fun writeSkillArchive(output: OutputStream, export: (OutputStream) -> JSONObject): JSONObject = output.use { stream ->
    val receipt = export(stream)
    stream.flush()
    receipt
}

@Composable
internal fun SkillArchiveControls(state: PocketUiState, controller: PocketWorldController) {
    val resolver = LocalContext.current.contentResolver
    val app = LocalContext.current.applicationContext as? EngineApplication
    val module = remember(app) { app?.features?.module("skills") as? SkillsModule }
    val worker = rememberCoroutineScope()
    val skills = state.feature("skills").optJSONArray("skills").objects()
    var expanded by remember { mutableStateOf(false) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("机主通过文件选择器导入") }
    var pendingImport by remember { mutableStateOf<SkillPickerTarget?>(null) }
    var pendingExport by remember { mutableStateOf<SkillExportPin?>(null) }
    var preview by remember { mutableStateOf<SkillArchivePreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestSkills by rememberUpdatedState(skills)

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingImport; pendingImport = null
        if (uri == null) { message = "已取消文件选择，未导入技能。" }
        else if (target == null || module == null) { error = "文件选择上下文已结束，请重新选择并检查。" }
        else {
            busy = true; error = null; message = null; preview = null
            worker.launch {
                try {
                    val inspection = withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { module.inspectPackage(skillOwnerPrivateScope(), it, target.id) }
                            ?: throw IllegalStateException("无法读取所选文件")
                    }
                    preview = SkillArchivePreview(uri, target.id, JSONObject(inspection.toString()))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "技能包检查失败，请重新选择文件。" }
                finally { busy = false }
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val pin = pendingExport; pendingExport = null
        if (uri == null) { message = "已取消保存，未导出文件。" }
        else if (pin == null || module == null) { error = "保存版本上下文已结束，请刷新后重新导出；所选目标可能是空文件。" }
        else {
            busy = true; error = null; message = null
            worker.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = resolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("无法打开保存位置")
                        writeSkillArchive(output) { module.exportPackage(skillOwnerPrivateScope(), pin.id, pin.revision, pin.digest, it) }
                    }
                    message = "技能 ZIP 已保存（修订 ${pin.revision}），不包含授权或启用状态。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = (failure.message ?: "导出失败") + "。目标可能含不完整数据，请在文件应用中移除或重新导出。" }
                finally { busy = false }
            }
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Line)
    OutlinedButton(onClick = { expanded = !expanded }, enabled = !busy) { Text(if (expanded) "收起文件导入导出" else "文件导入与版本恢复") }
    message?.let { SkillArchiveCaption(it) }
    error?.let { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 21.sp) }
    if (!expanded) return
    if (module == null) { SkillArchiveCaption("技能文件模块暂不可用，请刷新连接中心。"); return }
    SkillArchiveCaption("使用 ZIP 文件转移完整技能内容。选择现有技能可更新或恢复旧内容；导入后产生新的停用修订，需另行审阅和启用。")
    Row {
        RadioButton(selected = targetId == null, onClick = { targetId = null; preview = null }, enabled = !busy && pendingImport == null && pendingExport == null)
        Text("登记为新技能", fontSize = 13.sp, modifier = Modifier.padding(top = 13.dp))
    }
    skills.forEach { skill ->
        Row {
            RadioButton(selected = targetId == skill.optString("id"), onClick = { targetId = skill.optString("id"); preview = null }, enabled = !busy && pendingImport == null && pendingExport == null)
            Column(Modifier.padding(top = 10.dp).weight(1f)) {
                Text(skill.optString("name"), fontSize = 13.sp)
                SkillArchiveCaption("修订 " + skill.optInt("revision") + " · 来源版本 " + skill.optString("sourceVersion", "未注明"))
            }
        }
    }
    OutlinedTextField(source, { source = it }, label = { Text("这份文件的来源") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        Button(onClick = {
            if (busy || pendingImport != null || pendingExport != null) return@Button
            preview = null; error = null; message = null; pendingImport = SkillPickerTarget(targetId)
            try { importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            catch (_: Exception) { pendingImport = null; error = "系统文件选择器不可用，请稍后重试。" }
        }, enabled = !busy && pendingImport == null && pendingExport == null && (targetId == null || skills.any { it.optString("id") == targetId })) { Text(if (busy) "正在处理文件" else "选择 ZIP 并检查") }
        OutlinedButton(onClick = {
            if (busy || pendingImport != null || pendingExport != null) return@OutlinedButton
            try {
                val selected = skills.find { it.optString("id") == targetId } ?: throw IllegalStateException("请先选择要导出的技能")
                val pin = skillExportPin(selected); pendingExport = pin; error = null; message = null
                exportPicker.launch(pin.fileName)
            } catch (failure: Exception) { pendingExport = null; error = failure.message ?: "无法打开保存位置" }
        }, enabled = targetId != null && !busy && pendingImport == null && pendingExport == null) { Text("导出所选版本") }
    }
    if (busy) SkillArchiveCaption("正在处理真实文件。离开页面后请返回核对技能列表与保存位置，避免重复操作。")
    preview?.let { checked ->
        val inspection = checked.inspection
        val info = inspection.getJSONObject("package")
        val comparison = inspection.getJSONObject("comparison")
        val current = skills.find { it.optString("id") == checked.targetId }
        val isCurrent = skillInspectionIsCurrent(inspection, checked.targetId, current)
        Text("待导入：" + info.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 18.dp))
        SkillArchiveCaption("来源版本 " + info.optString("sourceVersion") + " · " + info.optLong("sizeBytes") + " 字节\n" + if (checked.targetId == null) "将登记为新技能" else "将替换所选技能修订 " + comparison.optInt("revision"))
        SkillArchiveCaption("来源记录：" + source.trim())
        SelectionContainer { SkillArchiveCaption("内容摘要 " + info.optString("digest")) }
        when (comparison.optString("kind")) {
            "repair" -> Text("当前已安装内容无法通过校验。本次完整导入可修复内容，但不能证明所选文件可信。", color = Danger, fontSize = 12.sp, lineHeight = 21.sp)
            "same-content" -> SkillArchiveCaption("文件内容相同。导入仍将创建新修订并停用。")
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            for ((key, label) in listOf("added" to "新增", "removed" to "移除", "modified" to "修改")) {
                val paths = comparison.optJSONArray(key)
                if (paths != null && paths.length() > 0) SkillArchiveCaption(label + "：" + (0 until paths.length()).joinToString("、") { paths.getString(it) })
            }
        }
        SkillArchiveCaption("文件是未经信任的参考内容，不执行脚本。确认只导入内容，不恢复原授权；省略的旧文件将移除。")
        if (!isCurrent) Text("当前技能版本已变化，请重新选择文件并检查。", color = Danger, fontSize = 12.sp)
        Button(onClick = {
            if (busy || pendingImport != null || pendingExport != null) return@Button
            try {
                val currentAtClick = latestSkills.find { it.optString("id") == checked.targetId }
                val options = skillImportOptions(inspection, checked.targetId, source, currentAtClick)
                busy = true; error = null; message = null
                worker.launch {
                    try {
                        val imported = withContext(Dispatchers.IO) {
                            ensureActive()
                            resolver.openInputStream(checked.uri)?.use { stream ->
                                ensureActive()
                                module.importPackage(skillOwnerPrivateScope(), stream, options)
                            } ?: throw IllegalStateException("无法重新读取文件，请重新选择并检查")
                        }
                        val skill = imported.getJSONObject("skill")
                        preview = null; message = "已导入 " + skill.optString("name") + "（修订 " + skill.getInt("revision") + "），保持停用。请核对说明后另行启用。"
                        controller.refresh()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { preview = null; error = (failure.message ?: "导入结果未确认") + "。请先核对技能列表；再次操作前重新选择文件并检查，不会自动重试。"; controller.refresh() }
                    finally { busy = false }
                }
            } catch (failure: Exception) { preview = null; error = failure.message }
        }, enabled = isCurrent && source.isNotBlank() && !busy && pendingImport == null && pendingExport == null, modifier = Modifier.padding(top = 10.dp)) { Text(if (checked.targetId == null) "确认导入并保持停用" else "确认替换为新修订") }
        TextButton(onClick = { preview = null }, enabled = !busy) { Text("放弃本次检查") }
    }
}

@Composable private fun SkillArchiveCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)) }
