package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.plugins.catalog.PluginCatalogSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Pure view guard; approval authority remains the opaque native object held by the session. */
internal object PluginCatalogPageGuard {
    @JvmStatic fun canConfirm(view: PluginCatalogSession.View, checked: Boolean): Boolean {
        val data = view.data()
        return checked && view.approval != null && !data.optBoolean("busy") && !data.optBoolean("unresolved") && data.optString("status") == "ready"
    }
    @JvmStatic fun sameRequest(page: String, revision: Long, data: JSONObject): Boolean =
        page == data.optString("pageRequestId") && revision == data.optLong("revision", -1)
}

/** Root supplies its existing worker and journal callback. No model feature route, file picker, runner or executor is created here. */
@Composable
internal fun PluginCatalogPage(
    session: PluginCatalogSession,
    nativeOwner: Any,
    runOnWorker: (Runnable) -> Unit,
    onBeforeDispatch: (PluginCatalogSession.Request, PluginCatalogSession.View, JSONObject) -> Unit,
    onSettled: (PluginCatalogSession.Request, PluginCatalogSession.View?, JSONObject?) -> Unit,
    runtimeState: JSONObject,
    onPrepareRuntime: () -> Unit,
    onMcpRediscovery: (JSONObject) -> Unit,
    onOpenPlugins: () -> Unit,
    onBack: () -> Unit,
    nativeHost: dev.pocketengine.features.NativePluginHost? = null,
    onOpenSkills: () -> Unit = onOpenPlugins,
    onOpenPackTask: (String) -> Unit = {}
) {
    var current by remember(session) { mutableStateOf(session.view(nativeOwner)) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var jsMode by remember(session) { mutableStateOf(false) }
    var selectedSource by remember(session) { mutableStateOf("") }
    var editing by remember(session) { mutableStateOf(false) }
    var editingId by remember(session) { mutableStateOf<String?>(null) }
    var sourceId by remember(session) { mutableStateOf("") }
    var sourceLabel by remember(session) { mutableStateOf("") }
    var sourceUrl by remember(session) { mutableStateOf("") }
    var pin by remember(session) { mutableStateOf("") }
    val main = remember { Handler(Looper.getMainLooper()) }
    val data = current.data()
    val busy = data.optBoolean("busy")
    val unresolved = data.optBoolean("unresolved")
    val config = data.optJSONObject("config") ?: JSONObject()
    val sources = catalogObjects(config.optJSONArray("sources"))
    val catalog = data.optJSONObject("catalog") ?: JSONObject()
    val kindMatches = (catalog.optString("kind") == "executable_js") == jsMode
    val review = data.optJSONObject("review") ?: JSONObject()
    val result = data.optJSONObject("result") ?: JSONObject()
    var acknowledged by remember(current.approval) { mutableStateOf(false) }

    fun execute(request: PluginCatalogSession.Request, nativeInput: JSONObject = JSONObject()) {
        current = session.view(nativeOwner); error = null
        val pendingView = current
        val fixedInput = JSONObject(nativeInput.toString())
        try {
            runOnWorker(Runnable {
                var admitted = false
                var settled = false
                try {
                    onBeforeDispatch(request, pendingView, fixedInput)
                    admitted = true
                    val next = request.execute()
                    // This callback must preserve the actual receipt even if navigation invalidated UI admission.
                    var receiptError: String? = null
                    try { settled = true; onSettled(request, next, request.receipt()) }
                    catch (_: Exception) { receiptError = "实际回执已返回，但记录未保存。请核对当前安装，勿重复提交。" }
                    main.post { if (session.accepts(nativeOwner, next)) { current = next; error = receiptError } }
                } catch (failure: Exception) {
                    var rejected: PluginCatalogSession.View? = null
                    if (!admitted) { try { rejected = request.rejected() } catch (_: Exception) { } }
                    else if (!settled) { try { onSettled(request, null, request.receipt()) } catch (_: Exception) { } }
                    val reply = rejected
                    main.post {
                        if (reply != null && session.accepts(nativeOwner, reply)) { current = reply; error = failure.message ?: "本次请求未开始。" }
                        else if (session.accepts(nativeOwner, pendingView)) error = failure.message ?: "读取失败，请重新打开目录。"
                    }
                }
            })
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            current = request.rejected()
        }
    }
    fun request(command: String, input: JSONObject = JSONObject()) {
        try { execute(session.begin(nativeOwner, UUID.randomUUID().toString(), command, input), input) }
        catch (failure: Exception) { error = failure.message ?: "请重新读取当前页面。" }
    }
    fun saveSources(next: JSONArray) {
        request("configure", JSONObject().put("sources", next).put("expectedRevision", config.optString("revision")))
        editing = false
    }
    fun leave() { session.close(); onBack() }
    LaunchedEffect(session) { request("read") }
    DisposableEffect(session) { onDispose { session.close() } }
    BackHandler { leave() }

    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { leave() }) { Text("返回") }
                Text("我的插件目录", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (busy) TextButton(onClick = { current = session.cancel(nativeOwner) }) { Text("停止") }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("按自己的需要扩展", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                CatalogCaption("添加你信任的目录，选择版本后核对内容。安装、批准和启用分别由你决定。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !jsMode, onClick = { jsMode = false; request("read") }, enabled = !busy && !unresolved, label = { Text("工具声明") })
                    FilterChip(selected = jsMode, onClick = { jsMode = true; request("read") }, enabled = !busy && !unresolved, label = { Text("JavaScript 扩展") })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                data.optString("message").takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, lineHeight = 22.sp, color = if (unresolved || data.optString("status") == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("目录来源", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editingId = null; sourceId = ""; sourceLabel = ""; sourceUrl = ""; pin = ""; editing = true; request("read") }, enabled = !busy && !unresolved) { Text("添加来源") }
                }
                if (sources.isEmpty() && !busy) CatalogCaption("还没有来源。可以使用自己的 HTTPS 目录；本机开发目录也支持回环 HTTP 地址。")
                sources.forEach { source ->
                    val id = source.getString("id")
                    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedSource == id, onClick = { selectedSource = id; request("read") }, enabled = !busy && source.getBoolean("enabled") && !unresolved)
                                Column(Modifier.weight(1f)) { Text(source.getString("label"), fontSize = 15.sp); Text(if (source.getBoolean("enabled")) "可读取" else "已停用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                TextButton(onClick = { editingId = id; sourceId = id; sourceLabel = source.getString("label"); sourceUrl = source.getString("catalogUrl"); pin = source.optString("pinnedCatalogSha256"); editing = true; request("read") }, enabled = !busy && !unresolved) { Text("编辑") }
                            }
                            SelectionContainer { Text(source.getString("catalogUrl"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { val next = JSONArray(); sources.forEach { next.put(JSONObject(it.toString()).also { copy -> if (copy.getString("id") == id) copy.put("enabled", !source.getBoolean("enabled")) }) }; saveSources(next) }, enabled = !busy && !unresolved) { Text(if (source.getBoolean("enabled")) "停用来源" else "启用来源") }
                                TextButton(onClick = { val next = JSONArray(); sources.filter { it.getString("id") != id }.forEach(next::put); saveSources(next); if (selectedSource == id) selectedSource = "" }, enabled = !busy && !unresolved) { Text("移除") }
                            }
                        }
                    }
                }
                if (editing) {
                    OutlinedTextField(sourceId, { sourceId = it }, label = { Text("来源标识") }, singleLine = true, enabled = editingId == null && !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sourceLabel, { sourceLabel = it }, label = { Text("显示名称") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("目录地址") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    var advanced by remember(editingId, editing) { mutableStateOf(false) }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起校验设置" else "固定内容校验（可选）") }
                    if (advanced) { OutlinedTextField(pin, { pin = it }, label = { Text("目录 SHA-256") }, enabled = !busy, modifier = Modifier.fillMaxWidth()); CatalogCaption("只有填入你从可信渠道取得的摘要才固定目录内容；目录更新后需由你更换摘要。") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (editingId == null && sources.any { it.getString("id") == sourceId.trim() }) error = "此来源标识已存在，请使用不同标识。"
                            else {
                                val source = JSONObject().put("id", sourceId.trim()).put("label", sourceLabel.trim()).put("catalogUrl", sourceUrl.trim()).put("enabled", sources.find { it.optString("id") == editingId }?.getBoolean("enabled") ?: true)
                                if (pin.isNotBlank()) source.put("pinnedCatalogSha256", pin.trim())
                                val next = JSONArray(); sources.filter { it.optString("id") != editingId }.forEach(next::put); next.put(source); selectedSource = sourceId.trim(); saveSources(next)
                            }
                        }, enabled = !busy && sourceId.isNotBlank() && sourceLabel.isNotBlank() && sourceUrl.isNotBlank()) { Text("保存来源") }
                        TextButton(onClick = { editing = false }, enabled = !busy) { Text("取消编辑") }
                    }
                }
                Button(onClick = { request(if (jsMode) "jsRefresh" else "refresh", JSONObject().put("sourceId", selectedSource)) }, enabled = !busy && !unresolved && sources.any { it.optString("id") == selectedSource && it.optBoolean("enabled") }) { Text(if (busy) "正在读取" else "刷新可选版本") }
                if (kindMatches && catalog.optJSONObject("source")?.optString("id") == selectedSource) {
                    CatalogCaption(if (jsMode) "这是独立的 JS 扩展目录。源码批准前不会运行或读取工具清单。" else "这里的能力为目录提供的说明，下载后会与实际包核对。版本由你选择，不自动更新。")
                    catalogObjects(catalog.optJSONArray("entries")).forEach { entry ->
                        val compatible = jsMode || entry.optJSONObject("compatibilityResult")?.optBoolean("compatible") == true
                        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(entry.getString("id"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("版本 " + entry.getString("version"), fontSize = 14.sp)
                                entry.optString("currentVersion").takeIf { it.isNotBlank() }?.let { CatalogCaption("当前安装：$it") }
                                if (!jsMode) catalogObjects(entry.optJSONArray("capabilities")).forEach { tool -> CatalogCaption(tool.optString("name") + " · " + if (tool.optString("effect") == "read") "读取" else "可能修改") }
                                if (!compatible) Text("此版本与当前手机或支持的格式不兼容。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                TextButton(onClick = { request(if (jsMode) "jsPrepare" else "prepare", JSONObject().put("sourceId", selectedSource).put("id", entry.getString("id")).put("version", entry.getString("version"))) }, enabled = compatible && !busy && !unresolved) { Text(if (jsMode) "下载并阅读源码" else "查看真实安装差异") }
                            }
                        }
                    }
                }
                if (review.length() > 0 && current.approval != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    val js = current.approval!!.kind != "declaration_install"
                    if (js) JsCatalogReview(review) else DeclarationCatalogReview(review)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(acknowledged, { acknowledged = it }, enabled = !busy && !unresolved)
                        Text(if (js) "我已阅读本次完整源码及权限范围" else "我已核对本次工具和权限变化", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    val ready = PluginCatalogPageGuard.canConfirm(current, acknowledged)
                    val canStop = PluginCatalogPageGuard.canConfirm(current, true)
                    val permission = current.approval!!.kind == "js_permission"
                    if (permission) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { try { execute(session.changeJs(nativeOwner, UUID.randomUUID().toString(), current.approval, if (review.optBoolean("approved")) "revoke" else "approve"), JSONObject().put("action", if (review.optBoolean("approved")) "revoke" else "approve")) } catch (failure: Exception) { error = failure.message } }, enabled = if (review.optBoolean("approved")) canStop else ready) { Text(if (review.optBoolean("approved")) "撤销源码批准" else "批准任务中执行") }
                            OutlinedButton(onClick = { try { execute(session.changeJs(nativeOwner, UUID.randomUUID().toString(), current.approval, if (review.optBoolean("enabled")) "disable" else "enable"), JSONObject().put("action", if (review.optBoolean("enabled")) "disable" else "enable")) } catch (failure: Exception) { error = failure.message } }, enabled = (if (review.optBoolean("enabled")) canStop else ready) && (review.optBoolean("approved") || review.optBoolean("enabled"))) { Text(if (review.optBoolean("enabled")) "停用扩展" else "启用扩展") }
                        }
                        CatalogCaption("卸载会移除这个安装及源码批准，旧任务不能继续使用它；已返回的结果仍保留在原任务。")
                        TextButton(onClick = {
                            try { execute(session.changeJs(nativeOwner, UUID.randomUUID().toString(), current.approval, "remove"), JSONObject().put("action", "remove")) }
                            catch (failure: Exception) { error = failure.message }
                        }, enabled = canStop) { Text("卸载这个安装", color = MaterialTheme.colorScheme.error) }
                    } else Button(onClick = { try { execute(session.submit(nativeOwner, UUID.randomUUID().toString(), current.approval)) } catch (failure: Exception) { error = failure.message } }, enabled = ready) { Text(if (js && review.optBoolean("restoring")) "回退为新安装，保持未批准" else if (js) "安装并保持未批准" else if (review.optJSONObject("packageReview")?.optBoolean("requiresMcpRediscovery") == true) "继续到服务重新发现" else "确认安装为停用插件") }
                }
                if (result.length() > 0 || unresolved) {
                    if (result.optString("status") == "rediscovery_required") Button(onClick = { onMcpRediscovery(JSONObject(result.toString())) }) { Text("打开 MCP 重新发现") }
                    OutlinedButton(onClick = { request("queryInstalled") }, enabled = !busy) { Text("查看当前实际安装") }
                    TextButton(onClick = onOpenPlugins) { Text("前往插件管理") }
                }
                if (data.optJSONObject("installed")?.optJSONArray("plugins") != null) catalogObjects(data.getJSONObject("installed").getJSONArray("plugins")).forEach { plugin -> CatalogCaption(plugin.optJSONObject("manifest")?.optString("name").orEmpty() + " · " + plugin.optJSONObject("manifest")?.optString("version").orEmpty() + if (plugin.optBoolean("enabled")) " · 已启用" else " · 已停用") }
                if (nativeHost != null) FunctionalPackControls(nativeHost, runOnWorker, onOpenPlugins, onOpenSkills, onOpenPackTask)
                if (jsMode) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("本机隔离运行时", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    CatalogCaption(runtimeState.optString("message", "尚未准备本机JS沙箱；未执行任何扩展。"))
                    CatalogCaption("每个原任务最多挂载16个已批准启用扩展，单次回调200毫秒；超量会明确停止，不静默略过。可在原任务的技能与工具中选用所需扩展，不必全局停用；新选用在新执行代次生效；撤销会使旧执行停止，不自动重提。没有网络、文件或Android桥。")
                    OutlinedButton(onClick = onPrepareRuntime, enabled = runtimeState.optString("status") != "preparing") { Text(if (runtimeState.optBoolean("prepared")) "重新核对本机运行时" else "准备本机JS沙箱") }
                    Text("已安装的 JS 扩展", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    val jsRows = catalogObjects(data.optJSONObject("jsInstalled")?.optJSONArray("plugins"))
                    if (jsRows.isEmpty()) CatalogCaption("尚未安装。安装后先审阅源码、批准，再单独启用。")
                    jsRows.forEach { plugin ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(plugin.optString("name"), fontSize = 15.sp); CatalogCaption(plugin.optString("version") + " · " + if (plugin.optBoolean("enabled")) "已启用" else if (plugin.optBoolean("approved")) "已批准，停用中" else "未批准") }
                            TextButton(onClick = { request("jsReviewInstalled", JSONObject().put("id", plugin.getString("id"))) }, enabled = !busy && !unresolved) { Text("源码与启停") }
                            TextButton(onClick = { request("jsHistory", JSONObject().put("id", plugin.getString("id"))) }, enabled = !busy && !unresolved) { Text("历史") }
                        }
                    }
                    val history = data.optJSONObject("jsHistory")
                    if (history != null && history.has("id")) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("${history.getString("id")} · 最近替换版本", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        CatalogCaption("保留最近三份准确原包。回退会替换当前安装，旧批准、任务选用和凭据不会复活。卸载移除本机原包历史，原任务回执仍可查看。")
                        val versions = catalogObjects(history.optJSONArray("versions"))
                        if (versions.isEmpty()) CatalogCaption("尚无被替换版本。首次安装不会制造历史。")
                        versions.forEach { version ->
                            Text("版本 ${version.optString("version")}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            CatalogCaption(version.optString("message"))
                            CatalogValue("原包 SHA-256", version.optString("packageSha256", "旧安装未保留准确原包"))
                            TextButton(onClick = { request("jsPrepareRestore", JSONObject().put("id", history.getString("id")).put("installationId", version.getString("installationId"))) }, enabled = !busy && !unresolved && version.optBoolean("restorable")) { Text("阅读此版源码与回退差异") }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DeclarationCatalogReview(review: JSONObject) {
    val packageReview = review.getJSONObject("packageReview")
    val preview = packageReview.getJSONObject("preview")
    val manifest = preview.getJSONObject("manifest")
    Text(manifest.getString("name"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    Text(if (preview.optBoolean("isUpdate")) "${preview.optString("currentVersion")} → ${preview.optString("targetVersion")}" else "版本 ${preview.optString("targetVersion")}")
    CatalogCaption("来源未经发布者验证。安装后保持停用，旧授权和凭据不会沿用。")
    for ((key, label) in listOf("addedTools" to "新增", "removedTools" to "移除")) catalogObjects(preview.optJSONArray(key)).forEach { tool -> CatalogCaption("$label ${tool.optString("name")} · ${tool.optString("kind")} · ${tool.optString("effect")}") }
    catalogObjects(preview.optJSONArray("changedTools")).forEach { changed ->
        Text(changed.optString("name") + " 的变化", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        for ((side, label) in listOf("before" to "原声明", "after" to "新声明")) changed.optJSONObject(side)?.let { tool -> CatalogCaption("$label：${tool.optString("kind")} · ${tool.optString("effect")}\n${tool.optString("endpoint", "本地回显")}") }
    }
    if (preview.optBoolean("sameManifest")) CatalogCaption("声明内容相同；重新安装仍会停用并清空旧授权。")
    if (preview.optInt("activeCallsToCancel") > 0) CatalogCaption("将停止等待中的 ${preview.optInt("activeCallsToCancel")} 个调用；已发出的请求仍需核对实际结果。")
    var full by remember(review.toString()) { mutableStateOf(false) }
    TextButton(onClick = { full = !full }) { Text(if (full) "收起完整声明" else "完整工具、端点与参数定义") }
    if (full) {
        CatalogValue("包内容校验", packageReview.getString("packageDigest"))
        catalogObjects(preview.optJSONArray("changedTools")).forEach { change ->
            CatalogValue(change.optString("name") + " · 原工具与参数", change.getJSONObject("before").toString(2))
            CatalogValue(change.optString("name") + " · 新工具与参数", change.getJSONObject("after").toString(2))
        }
        CatalogValue("完整目标声明", manifest.toString(2))
    }
    if (packageReview.optBoolean("requiresMcpRediscovery")) CatalogCaption("MCP 需重新发现当前服务、认证并另行批准，目录声明不会直接安装。")
}

@Composable private fun JsCatalogReview(review: JSONObject) {
    Text(review.optString("name"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    Text("JavaScript 扩展 · 版本 " + review.optString("version"), fontSize = 14.sp)
    CatalogCaption("源码尚未在此页面执行。批准包含初始化、工具定义和任务钩子的执行。它不能使用 Node、网络、文件、Android 或直接调用宿主工具。")
    CatalogValue("源码 SHA-256", review.getString("sourceSha256"))
    if (review.optBoolean("restoring")) { CatalogCaption("正在审阅保留的准确历史原包；确认仅恢复代码，不恢复旧授权或效果。旧任务选用将失效，需回原任务重新选择。"); CatalogValue("历史原包 SHA-256", review.getString("packageSha256")) }
    review.optJSONObject("current")?.let { old -> CatalogCaption("将替换当前版本 ${old.optString("version")}。原批准将失效，新版本保持未批准、停用。"); CatalogValue("当前源码 SHA-256", old.optString("sourceSha256")) }
    Text("完整源码", fontWeight = FontWeight.Medium, fontSize = 14.sp)
    SelectionContainer { Text(review.getString("source"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth()) }
    CatalogCaption("扩展提供的上下文属于不可信参考；它不能提高任务权限。停用、撤销批准或替换源码后，旧任务不能沿用旧许可。")
}
@Composable private fun CatalogValue(label: String, value: String) { Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); SelectionContainer { Text(value, fontSize = 13.sp, lineHeight = 21.sp) } }
@Composable private fun CatalogCaption(text: String) { Text(text, fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun catalogObjects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
