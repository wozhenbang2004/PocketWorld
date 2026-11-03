package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.plugins.PluginRevisions
import org.json.JSONObject

internal data class PluginPreviewDraft(val requestId: String, val manifestText: String, val pluginId: String)
internal data class PluginRestoreDraft(val pluginId: String, val targetInstallationId: String, val expectedInstallationId: String, val version: String, val expectedStateRevision: Long, val requestId: String)

private fun pluginRevision(value: Any?): Long? = if (value !is Number) null else try {
    java.math.BigDecimal(value.toString()).longValueExact().takeIf { it >= 0 }
} catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }

/** Local draft + exact native request identity, never a previous plugin's global result. */
internal fun reviewedPluginPreview(draft: PluginPreviewDraft?, currentText: String, metadata: JSONObject?, result: JSONObject?): JSONObject? {
    if (draft == null || metadata?.optString("_requestId") != draft.requestId || currentText.trim() != draft.manifestText || result == null) return null
    if (result.optString("id") != draft.pluginId || result.opt("expectedInstallationId") !is String || pluginRevision(result.opt("expectedStateRevision")) == null) return null
    val manifest = result.optJSONObject("manifest") ?: return null
    if (manifest.optString("id") != draft.pluginId || manifest.optString("version") != result.optString("targetVersion")) return null
    return result
}
internal fun pluginInstallPayload(draft: PluginPreviewDraft?, currentText: String, metadata: JSONObject?, result: JSONObject?, token: String): JSONObject {
    val reviewed = reviewedPluginPreview(draft, currentText, metadata, result) ?: throw IllegalStateException("声明或预览已变化，请重新检查差异")
    return JSONObject().put("manifest", JSONObject(reviewed.getJSONObject("manifest").toString())).put("expectedInstallationId", reviewed.getString("expectedInstallationId")).put("expectedStateRevision", reviewed.getLong("expectedStateRevision")).also { if (token.isNotEmpty()) it.put("token", token) }
}
internal fun reviewedPluginHistory(id: String?, requestId: String?, metadata: JSONObject?, result: JSONObject?): JSONObject? {
    if (id.isNullOrBlank() || requestId.isNullOrBlank() || metadata?.optString("_requestId") != requestId || metadata.optString("id") != id || result?.optString("id") != id) return null
    if (result.optString("currentInstallationId").isBlank() || pluginRevision(result.opt("currentStateRevision")) == null || result.optJSONArray("versions") == null) return null
    return result
}
internal fun reviewedPluginRestorePreview(draft: PluginRestoreDraft?, metadata: JSONObject?, result: JSONObject?): JSONObject? {
    if (draft == null || metadata?.optString("_requestId") != draft.requestId || metadata.optString("id") != draft.pluginId || metadata.optString("targetInstallationId") != draft.targetInstallationId || result == null) return null
    if (result.optString("id") != draft.pluginId || result.optString("targetInstallationId") != draft.targetInstallationId || result.optString("expectedInstallationId") != draft.expectedInstallationId || pluginRevision(result.opt("expectedStateRevision")) != draft.expectedStateRevision || result.optBoolean("restoration") != true) return null
    val manifest = result.optJSONObject("manifest") ?: return null
    if (manifest.optString("id") != draft.pluginId || manifest.optString("version") != draft.version || result.optString("targetVersion") != draft.version) return null
    return result
}
internal fun pluginRestorePayload(draft: PluginRestoreDraft, currentInstallationId: String, currentStateRevision: Long, metadata: JSONObject?, result: JSONObject?, token: String): JSONObject {
    if (draft.expectedInstallationId.isBlank() || draft.targetInstallationId.isBlank() || draft.expectedInstallationId != currentInstallationId || draft.targetInstallationId == currentInstallationId || draft.expectedStateRevision != currentStateRevision) throw IllegalStateException("当前安装、启停或授权已变化，请重新读取历史并确认")
    reviewedPluginRestorePreview(draft, metadata, result) ?: throw IllegalStateException("恢复差异尚未完成审阅，请重新读取历史并检查")
    return JSONObject().put("id", draft.pluginId).put("targetInstallationId", draft.targetInstallationId).put("expectedInstallationId", draft.expectedInstallationId).put("expectedStateRevision", draft.expectedStateRevision).also { if (token.isNotEmpty()) it.put("token", token) }
}
private fun pluginInstallationId(plugin: JSONObject?): String = plugin?.optString("installationId")?.takeIf { it.isNotBlank() } ?: plugin?.let { "legacy-" + it.optLong("installedAt") }.orEmpty()
private fun pluginStateRevision(plugin: JSONObject?): Long = try { PluginRevisions.stateRevision(plugin) } catch (_: IllegalArgumentException) { -1 }

@Composable
internal fun PluginVersions(state: PocketUiState, controller: PocketWorldController) {
    val plugins = state.feature("plugins").optJSONArray("plugins").objects()
    var editing by remember { mutableStateOf(false) }
    var manifestText by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<PluginPreviewDraft?>(null) }
    var installRequest by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var historyRequest by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<JSONObject?>(null) }
    var restore by remember { mutableStateOf<PluginRestoreDraft?>(null) }
    var restoreToken by remember { mutableStateOf("") }
    var restoreRequest by remember { mutableStateOf<String?>(null) }
    val previewBusy = "plugins.previewInstall" in state.moduleBusy
    val installBusy = "plugins.install" in state.moduleBusy
    val restoreBusy = "plugins.rollback" in state.moduleBusy
    val restorePreviewBusy = "plugins.previewRollback" in state.moduleBusy
    val preview = reviewedPluginPreview(draft, manifestText, state.moduleResultInputs["plugins.previewInstall"], state.moduleResults["plugins.previewInstall"])
    val previewPlugin = plugins.find { it.optString("id") == preview?.optString("id") }
    val previewCurrent = preview != null && pluginInstallationId(previewPlugin) == preview.optString("expectedInstallationId") && pluginStateRevision(previewPlugin) == pluginRevision(preview.opt("expectedStateRevision"))

    LaunchedEffect(state.moduleResults["plugins.history"], historyRequest) {
        reviewedPluginHistory(selectedId, historyRequest, state.moduleResultInputs["plugins.history"], state.moduleResults["plugins.history"])?.let { history = JSONObject(it.toString()) }
    }
    LaunchedEffect(state.moduleResults["plugins.install"], installRequest) {
        if (installRequest != null && state.moduleResultInputs["plugins.install"]?.optString("_requestId") == installRequest) {
            val saved = state.moduleResults["plugins.install"]
            if (!saved?.optString("installationId").isNullOrBlank()) { draft = null; token = ""; installRequest = null; message = "插件版本已保存，保持停用；启用前需重新授予工具权限。" }
        }
    }
    LaunchedEffect(state.moduleResults["plugins.rollback"], restoreRequest) {
        if (restoreRequest != null && state.moduleResultInputs["plugins.rollback"]?.optString("_requestId") == restoreRequest) {
            val saved = state.moduleResults["plugins.rollback"]
            if (!saved?.optString("installationId").isNullOrBlank()) {
                restore = null; restoreToken = ""; restoreRequest = null; history = null
                message = "已恢复为新的安装记录，保持停用、零授权；旧凭据未恢复。"
                selectedId?.let { historyRequest = controller.requestFeature("plugins", "history", JSONObject().put("id", it)) }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Line)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = !editing; if (!editing) token = "" }, enabled = !installBusy && !restoreBusy) { Text(if (editing) "收起安装" else "安装或更新") }
        TextButton(onClick = { historyOpen = !historyOpen }) { Text(if (historyOpen) "收起历史" else "历史与恢复") }
    }
    message?.let { PluginCaption(it) }
    localError?.let { Text(it, color = Danger, fontSize = 12.sp) }
    if (editing) {
        OutlinedTextField(manifestText, { manifestText = it; localError = null }, label = { Text("插件声明 JSON") }, minLines = 5, maxLines = 11, enabled = !installBusy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        PluginCaption("先查看真实差异，再确认安装。编辑声明后必须重新检查；安装会停用插件并清空旧工具授权。")
        Button(onClick = {
            try {
                val text = manifestText.trim(); val manifest = JSONObject(text); val id = manifest.getString("id")
                val request = controller.requestFeature("plugins", "previewInstall", JSONObject().put("manifest", manifest))
                draft = PluginPreviewDraft(request, text, id); localError = null; message = null
            } catch (_: Exception) { localError = "声明不是有效的插件 JSON，请检查后重试。" }
        }, enabled = manifestText.isNotBlank() && !previewBusy && !installBusy && !restoreBusy) { Text(if (previewBusy) "正在检查差异" else "检查安装差异") }
        state.moduleErrors["plugins.previewInstall"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
        preview?.let { reviewed ->
            PluginChangeSummary(reviewed)
            if (!previewCurrent) PluginCaption("当前插件安装、启停或授权已经变化，请重新检查差异。")
            OutlinedTextField(token, { token = it }, label = { Text("本次工具凭据（可选）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !installBusy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            PluginCaption("旧凭据不会沿用；此输入不写入 UI 保存状态。")
            Button(onClick = {
                try {
                    val payload = pluginInstallPayload(draft, manifestText, state.moduleResultInputs["plugins.previewInstall"], state.moduleResults["plugins.previewInstall"], token)
                    installRequest = controller.requestFeature("plugins", "install", payload); token = ""; localError = null
                } catch (failure: Exception) { localError = failure.message }
            }, enabled = previewCurrent && !previewBusy && !installBusy && !restoreBusy, modifier = Modifier.padding(top = 10.dp)) { Text(if (installBusy) "正在保存版本" else if (reviewed.optBoolean("isUpdate")) "确认更新这个版本" else "确认安装这个插件") }
            state.moduleErrors["plugins.install"]?.let { Text(it + "；若需要工具凭据，请在本次重新填写。", color = Danger, fontSize = 12.sp) }
        }
        if (draft != null && preview == null && !previewBusy) PluginCaption("声明或预览已变化，确认按钮已锁定。请重新检查差异。")
    }
    if (historyOpen) {
        PluginCaption("最多保留三份历史声明。恢复会生成新安装身份并保持停用，不恢复授权或旧凭据。")
        plugins.forEach { plugin ->
            TextButton(onClick = { selectedId = plugin.optString("id"); history = null; historyRequest = controller.requestFeature("plugins", "history", JSONObject().put("id", selectedId)) }, enabled = "plugins.history" !in state.moduleBusy) { Text((plugin.optJSONObject("manifest")?.optString("name") ?: plugin.optString("id")) + " · 查看版本", fontSize = 12.sp) }
        }
        if (plugins.isEmpty()) PluginCaption("尚未安装插件。")
        val loaded = history?.takeIf { it.optString("id") == selectedId }
        loaded?.let { versions ->
            val current = pluginInstallationId(plugins.find { it.optString("id") == selectedId })
            val expected = versions.getString("currentInstallationId")
            val expectedState = versions.getLong("currentStateRevision")
            val currentState = pluginStateRevision(plugins.find { it.optString("id") == selectedId })
            val historyCurrent = current == expected && currentState == expectedState
            Text("当前版本 " + versions.optString("currentVersion"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (!historyCurrent) PluginCaption("插件安装、启停或授权已变化，旧历史不能用于确认。请重新读取版本。")
            versions.optJSONArray("versions").objects().forEach { version ->
                val target = version.optString("installationId"); val label = version.optJSONObject("manifest")?.optString("version").orEmpty()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) { Text("版本 $label", fontSize = 13.sp); PluginCaption("保存记录 " + target.take(8)) }
                    TextButton(onClick = {
                        val input = JSONObject().put("id", versions.getString("id")).put("targetInstallationId", target).put("expectedInstallationId", expected).put("expectedStateRevision", expectedState)
                        val request = controller.requestFeature("plugins", "previewRollback", input)
                        restore = PluginRestoreDraft(versions.getString("id"), target, expected, label, expectedState, request); restoreToken = ""; localError = null
                    }, enabled = historyCurrent && target.isNotBlank() && !restoreBusy && !restorePreviewBusy && !installBusy) { Text("查看恢复差异") }
                }
            }
            if (versions.optJSONArray("versions")?.length() == 0) PluginCaption("此插件尚无可恢复的历史声明。")
        }
        state.moduleErrors["plugins.history"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
    restore?.let { target ->
        val currentPlugin = plugins.find { it.optString("id") == target.pluginId }
        val current = pluginInstallationId(currentPlugin)
        val currentState = pluginStateRevision(currentPlugin)
        val restoreCurrent = current == target.expectedInstallationId && currentState == target.expectedStateRevision
        val reviewed = reviewedPluginRestorePreview(target, state.moduleResultInputs["plugins.previewRollback"], state.moduleResults["plugins.previewRollback"])
        var targetDeclarationOpen by remember(target.requestId) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!restoreBusy) { restore = null; restoreToken = "" } }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("恢复版本 " + target.version + "？") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (restorePreviewBusy) Text("正在核对目标声明与当前版本的实际差异…", fontSize = 13.sp)
                reviewed?.let {
                    PluginChangeSummary(it)
                    TextButton(onClick = { targetDeclarationOpen = !targetDeclarationOpen }) { Text(if (targetDeclarationOpen) "收起目标完整声明" else "查看目标完整声明", fontSize = 12.sp) }
                    if (targetDeclarationOpen) SelectionContainer { Text(it.getJSONObject("manifest").toString(2), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp) }
                }
                state.moduleErrors["plugins.previewRollback"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
                Text("将停止此插件正在等待的调用。已发生的远端效果不会撤回。恢复后插件停用、授权清空，旧凭据不恢复。", fontSize = 13.sp, lineHeight = 22.sp)
                OutlinedTextField(restoreToken, { restoreToken = it }, label = { Text("本次工具凭据（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = reviewed != null && restoreCurrent && !restoreBusy)
                if (!restoreCurrent) Text("当前安装、启停或授权已变化，请关闭后重新读取历史。", color = Danger, fontSize = 12.sp)
                if (reviewed == null && !restorePreviewBusy) Text("恢复差异未核对，确认按钮已锁定。请关闭后重新读取历史。", color = Danger, fontSize = 12.sp)
                state.moduleErrors["plugins.rollback"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
            }
        }, confirmButton = { Button(onClick = {
            try { restoreRequest = controller.requestFeature("plugins", "rollback", pluginRestorePayload(target, current, currentState, state.moduleResultInputs["plugins.previewRollback"], state.moduleResults["plugins.previewRollback"], restoreToken)); restoreToken = "" }
            catch (failure: Exception) { localError = failure.message }
        }, enabled = restoreCurrent && reviewed != null && !restorePreviewBusy && !restoreBusy && !installBusy) { Text(if (restoreBusy) "正在恢复" else "确认恢复声明") } }, dismissButton = { TextButton(onClick = { restore = null; restoreToken = "" }, enabled = !restoreBusy) { Text("取消") } })
    }
}

@Composable
private fun PluginChangeSummary(preview: JSONObject) {
    Text(if (preview.optBoolean("isUpdate")) "${preview.optString("currentVersion")} → ${preview.optString("targetVersion")}" else "新版本 " + preview.optString("targetVersion"), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 18.dp))
    for ((key, label) in listOf("addedTools" to "新增工具", "removedTools" to "移除工具")) {
        val tools = preview.optJSONArray(key).objects(); if (tools.isNotEmpty()) PluginCaption(label + "：" + tools.joinToString("、") { it.optString("name") })
    }
    preview.optJSONArray("changedTools").objects().forEach { changed ->
        var expanded by remember(changed.toString()) { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) { Text((if (expanded) "收起 " else "查看变化 ") + changed.optString("name"), fontSize = 12.sp) }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            val fields = changed.optJSONArray("fields")
            for (index in 0 until (fields?.length() ?: 0)) {
                val field = fields!!.getString(index)
                Text(field, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                for ((side, label) in listOf("before" to "原值", "after" to "新值")) {
                    val value = changed.optJSONObject(side)?.opt(field)
                    val rendered = when (value) { is JSONObject -> value.toString(2); null, JSONObject.NULL -> "未设置"; else -> value.toString() }
                    SelectionContainer { Text("$label：$rendered", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
        }
    }
    if (preview.optBoolean("sameManifest")) PluginCaption("声明内容相同；重新安装仍会生成新身份、停用并清空授权。")
    if (preview.optBoolean("nameChanged")) PluginCaption("插件显示名称将变化。")
    if (preview.optInt("activeCallsToCancel") > 0) PluginCaption("将停止等待中的 " + preview.optInt("activeCallsToCancel") + " 个工具调用；已经发生的远端效果不会撤回。")
}
@Composable private fun PluginCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)) }
