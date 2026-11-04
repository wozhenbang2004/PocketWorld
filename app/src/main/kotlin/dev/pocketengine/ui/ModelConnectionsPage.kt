package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import dev.pocketengine.features.proxy.accounts.ChatGptRecovery
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.models.*
import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Native owner only. Root provides its existing worker; no feature command, secret field or task loop. */
@Composable
internal fun ModelConnectionsPage(service: ModelConnectionService, runOnWorker: (Runnable) -> Unit, changed: () -> Unit, back: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var snapshot by remember(service) { mutableStateOf(JSONObject()) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    var busy by remember(service) { mutableStateOf(false) }
    var openedModel by remember(service) { mutableStateOf<String?>(null) }
    var edit by remember(service) { mutableStateOf<ModelFacts.Field?>(null) }
    var configuring by remember(service) { mutableStateOf(false) }
    var refreshControl by remember(service) { mutableStateOf<RunControl?>(null) }
    var library by remember(service) { mutableStateOf(JSONObject()) }
    var modelQuery by remember { mutableStateOf("") }
    var batchMode by remember { mutableStateOf(false) }
    var batchModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchPreview by remember { mutableStateOf<JSONObject?>(null) }
    var batchResult by remember { mutableStateOf<JSONObject?>(null) }
    var metadataExpanded by remember { mutableStateOf(false) }
    val alive = remember(service) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val secondary = SecondaryInk
    val selection = remember(service) { service.modelSelection() }
    val pendingWrite = remember(service) { AtomicReference<ModelSelectionCoordinator.Mutation?>(null) }
    var shared by remember(selection) { mutableStateOf(selection.status()) }
    var loadedRevision by remember(selection) { mutableLongStateOf(-1L) }
    var readAttemptRevision by remember(selection) { mutableLongStateOf(-1L) }
    var snapshotAvailable by remember(selection) { mutableStateOf(false) }

    fun execute(onSuccess: (() -> Unit)? = null, mutation: Boolean = true, action: () -> JSONObject) {
        if (busy || !alive.get()) return
        if (mutation && !snapshotAvailable) { error = "请先重新读取当前模型，再保存设置。"; refreshControl = null; return }
        val reservation = if (mutation) try { selection.beginMutation(loadedRevision) } catch (failed: IllegalStateException) { error = failed.message; shared = selection.status(); refreshControl = null; return } else null
        if (reservation != null) pendingWrite.set(reservation)
        busy = true; error = null
        try { runOnWorker(Runnable {
            var failure: String? = null
            try { if (reservation != null) reservation.run { action() } } catch (stopped: InterruptedException) {
                Thread.currentThread().interrupt(); failure = "目录操作已停止。"
            } catch (problem: Exception) {
                failure = when (problem) {
                    is ModelRequestPolicy.Rejected -> problem.message
                    is dev.pocketengine.store.SettingsStore.PersistenceUncertain -> problem.message
                    is CatalogException -> "目录刷新失败：${problem.code.name}。上次目录与机主覆盖仍保留，请核对连接后重试。"
                    else -> when (problem.message) {
                        "owner_revision_conflict" -> "能力已被另一操作修改，请重新打开该字段后再编辑。"
                        else -> if (problem is IllegalArgumentException || problem is IllegalStateException) problem.message ?: "请核对原连接设置。" else "操作未能完成，请刷新连接状态后核对；没有换模型或发送聊天请求。"
                    }
                }
            }
            if (reservation != null) pendingWrite.compareAndSet(reservation, null)
            val result = runCatching { selection.readStable {
                Pair(if (mutation) service.snapshot() else action(), service.modelPickerSnapshot())
            } }
            val message = failure
            val completed = selection.status()
            main.post {
                if (mutation) changed()
                if (alive.get()) {
                    shared = selection.status()
                    result.fold({
                        readAttemptRevision = it.revision()
                        if (!shared.pending() && shared.revision() == it.revision()) {
                            snapshot = it.value().first; library = it.value().second
                            loadedRevision = it.revision(); snapshotAvailable = true
                        }
                    }, { snapshotAvailable = false; readAttemptRevision = completed.revision() })
                    error = message ?: result.exceptionOrNull()?.let { if (it is IllegalArgumentException || it is IllegalStateException) it.message else "当前模型状态暂不可读取，请重新读取；上次画面仍保留。" }
                    busy = false; refreshControl = null
                    if (message == null && result.isSuccess) onSuccess?.invoke()
                }
            }
        }) } catch (_: Exception) { reservation?.close(); if (reservation != null) pendingWrite.compareAndSet(reservation, null); busy = false; snapshotAvailable = false; refreshControl = null; error = "本机工作线程暂不可用，请返回后重开模型页。"; readAttemptRevision = selection.status().revision() }
    }
    DisposableEffect(service) {
        alive.set(true)
        val subscription = selection.observe { main.post { if (alive.get()) shared = selection.status() } }
        shared = selection.status()
        onDispose { alive.set(false); pendingWrite.getAndSet(null)?.close(); subscription.close(); refreshControl?.cancel() }
    }
    LaunchedEffect(service, shared, busy) {
        if (!busy && !shared.pending() && readAttemptRevision < shared.revision()) execute(mutation = false) { service.connections() }
    }
    val blocked = busy || shared.pending() || !snapshotAvailable || loadedRevision < shared.revision()
    BackHandler { if (!busy && !shared.pending()) { if (openedModel != null) openedModel = null else back() } }
    val api = snapshot.optJSONObject("api") ?: JSONObject()
    val binding = snapshot.optJSONObject("binding")?.let { ModelBinding.fromJson(it) }
    val preferences = modelRows(library.optJSONArray("models")).filter { it.optString("connectionId") == binding?.connectionId() }.associateBy { it.optString("modelId") }
    val rows = modelRows(snapshot.optJSONArray("models")).sortedWith(compareByDescending<JSONObject> { preferences[it.optString("modelId")]?.optBoolean("favorite") == true }
        .thenByDescending { preferences[it.optString("modelId")]?.optLong("lastUsedAt") ?: 0L }.thenBy { it.optString("modelId").lowercase() })
    val selected = rows.find { it.optString("modelId") == openedModel }
    val profile = modelRows(snapshot.optJSONObject("connections")?.optJSONArray("connections")).find { it.optString("id") == binding?.connectionId() }
    LaunchedEffect(binding?.toJson()?.toString()) { batchModels = emptySet(); batchPreview = null; batchResult = null }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy && !shared.pending(), onClick = { if (openedModel != null) openedModel = null else back() }) { Text("返回") }
            Text(if (openedModel == null) "模型目录与能力" else openedModel.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (busy && refreshControl != null) TextButton(onClick = { refreshControl?.cancel() }) { Text("取消刷新") }
        }
        HorizontalDivider(color = Line)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (openedModel == null) {
                ApiConnectionProfilesPanel(snapshot.optJSONObject("connections") ?: JSONObject(), blocked, error,
                    { id, revision, name, values, saved -> execute(saved) { service.saveConnection(id, revision, name, values) } },
                    { id, revision -> execute { service.selectConnection(id, revision) } },
                    { id, revision, enabled -> execute { service.enableConnection(id, revision, enabled) } })
                if (snapshot.optBoolean("selectionUnavailable")) Text(snapshot.optString("selectionMessage"), color = MaterialTheme.colorScheme.error)
                ModelGroupsPanel(snapshot.optJSONObject("modelGroups") ?: JSONObject(), snapshot.optJSONObject("connections") ?: JSONObject(), library, blocked,
                    { id, revision, name, definition, saved -> execute(saved) { service.saveGroup(id, revision, name, definition) } },
                    { purpose, target -> execute { service.selectPurpose(purpose, target) } })
                StepRoutingControls(service, runOnWorker)
                val active = snapshot.optJSONObject("nativeSelection")
                Text("下一任务主模型：${active?.optString("model").orEmpty().ifBlank { "尚未配置" }}", fontSize = 14.sp)
                Text(if (snapshot.optString("activeRoute") == "account") "当前主模型使用 ChatGPT 套餐。账号与所选模型可在 ChatGPT 账号页核对；此页管理保留的 API key 连接。" else "正在管理的 API 模型：${api.optString("model").ifBlank { "尚未配置" }}", fontSize = 14.sp)
                if (snapshot.optString("activeRoute") == "account") TextButton(onClick = {
                    try { uriHandler.openUri(ChatGptRecovery.MANAGE_USAGE_URL) } catch (_: Exception) { error = "无法打开 Manage usage，请在 ChatGPT 设置中查看用量。" }
                }) { Text("Manage usage · 管理用量") }
                Text("${api.optString("provider")} · ${api.optString("baseUrl").ifBlank { "请先在设置中保存 API 连接" }}", fontSize = 12.sp, color = secondary)
                ConnectionInferenceStatus(snapshot.optJSONObject("inferenceObservation"), api.optJSONObject("capabilityBinding")?.toString().orEmpty() + "|" + api.optString("model"))
                TextButton(onClick = { execute(mutation = false) { service.connections() } }, enabled = !busy && !shared.pending()) { Text("读取最新本地调用记录（不请求模型）") }
                ModelDiagnosticsControls(service, snapshot, runOnWorker, { execute(mutation = false) { service.snapshot() } }, blocked)
                Text("目录读取不会发送聊天。标准 /models 通常只提供模型名称，缺失的上下文、图片、工具和推理能力会显示未知；能力声明不等于模型效果已实测。", fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
                if (snapshot.optString("activeRoute") == "account") Text("以下价目只属于保留的 API 模型，不用于 ChatGPT 套餐。", style = MaterialTheme.typography.bodySmall)
                ModelPriceControls(snapshot.optJSONObject("pricing") ?: JSONObject(), blocked, error) { expected, model, revision, values, clear, saved ->
                    execute(saved) { service.price(expected, model, revision, values, clear) }
                }
                val metadataBinding = api.optJSONObject("capabilityBinding")?.let(ModelBinding::fromJson)
                val metadata = snapshot.optJSONObject("metadataSuggestion") ?: JSONObject()
                if (metadataBinding != null && api.optString("model").isNotBlank()) {
                    TextButton(onClick = { metadataExpanded = !metadataExpanded }) { Text(if (metadataExpanded) "收起第三方规格参考" else "models.dev 规格参考（可选）") }
                    if (metadataExpanded) {
                        var sourceProvider by remember(metadataBinding, api.optString("model"), metadata.optString("providerId")) { mutableStateOf(metadata.optString("providerId")) }
                        Text("只核对当前保留 API 模型的参考资料。主人覆盖与原服务目录保持优先；参考不会改变工具、图片、预算、价目或请求。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(sourceProvider, { sourceProvider = it.take(100) }, label = { Text("models.dev Provider ID") }, supportingText = { Text("使用来源中的准确 ID，例如 openai；协议名与中转商不等于同一提供方。") }, singleLine = true, enabled = !blocked, modifier = Modifier.fillMaxWidth())
                        Text("点击读取会访问 models.dev 的公共目录，不发送密钥、账号、模型 ID 或聊天。没有精确 provider/model 组合时保持未知。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !blocked && sourceProvider.matches(Regex("[a-z0-9][a-z0-9_-]{0,99}")) && metadata.optBoolean("cacheReadable", true), onClick = {
                                val control = RunControl(); refreshControl = control
                                execute { service.refreshMetadataSuggestion(metadataBinding, api.getString("model"), sourceProvider, control) }
                            }) { Text("读取公共参考") }
                            if (metadata.optBoolean("available")) TextButton(enabled = !blocked, onClick = { execute { service.clearMetadataSuggestion(metadataBinding, api.getString("model")) } }) { Text("清除这项参考") }
                        }
                        if (!metadata.optBoolean("cacheReadable", true)) Text("参考缓存暂不可读取，原文件保留；原模型与机主设置仍可使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (metadata.optBoolean("available")) {
                            Text("${metadata.optString("providerId")} / ${metadata.optString("modelId")} · 读取于 ${modelTime(metadata.optLong("observedAtMillis"))}", style = MaterialTheme.typography.bodySmall)
                            Text(if (metadata.optBoolean("fresh")) "来源观察在7天内；仍未证明该连接实际支持。" else "离线保留的旧参考（超过7天或时钟变化），请重新核对。", style = MaterialTheme.typography.bodySmall)
                            Text(when (metadata.optString("originMatch")) { "different_origin" -> "来源的提供方地址与当前连接不同，不可将同名模型能力当作此中转连接事实。"; "same_origin_only_not_model_verification" -> "来源与当前连接同源；路径、账号和实际能力仍需分别核对。"; else -> "来源未给出可核对的服务地址，提供方归属尚未证实。" }, style = MaterialTheme.typography.bodySmall)
                            val hints = metadata.optJSONObject("hints") ?: JSONObject()
                            val labels = listOf("limit.context" to "上下文 tokens", "limit.input" to "输入上限 tokens", "limit.output" to "输出上限 tokens", "tool_call" to "工具结构", "image_input" to "图片输入", "reasoning" to "推理", "structured_output" to "结构化输出", "cost.input" to "输入 USD/百万 tokens", "cost.output" to "输出 USD/百万 tokens", "cost.cache_read" to "缓存读取 USD/百万 tokens", "cost.cache_write" to "缓存写入 USD/百万 tokens", "cost.reasoning" to "推理 USD/百万 tokens")
                            labels.forEach { (key, label) -> Text("$label：${if (hints.has(key)) when (val value = hints.get(key)) { is Boolean -> if (value) "来源声称支持" else "来源声称不支持"; else -> value.toString() } else "未知"}", style = MaterialTheme.typography.bodySmall) }
                            Text("来源 ${metadata.optString("source")} · ${metadata.optString("sourceVersion")}", style = MaterialTheme.typography.labelSmall)
                            Text("如需采用，请先核对原服务文档，再使用本页已有的逐项机主调整。此参考不是实测或账单。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (binding != null) {
                    Text("连接修订 ${binding.installationRevision()} · 凭据修订 ${binding.credentialRevision()}", fontSize = 12.sp, color = secondary)
                    Text(snapshot.optString("modelsEndpoint"), fontSize = 11.sp, color = secondary)
                    val attempt = snapshot.optJSONObject("refreshAttempt")
                    if (attempt != null) Text("最近尝试：${modelTime(attempt.optLong("startedAtMillis"))} · ${attempt.optString("status")} ${attempt.optString("code")}", fontSize = 11.sp, color = secondary)
                    Button(onClick = { val control = RunControl(); refreshControl = control; execute { service.refresh(binding, control) } }, enabled = !blocked) { Text(if (busy && refreshControl != null) "正在刷新目录…" else "刷新这项连接的目录") }
                    Text("目录全部分页共用 ${(snapshot.optLong("catalogTimeoutMillis", 15000) + 999) / 1000} 秒期限；可随时停止，失败保留原目录。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { configuring = true }, enabled = !blocked) { Text("目录来源与格式") }
                    OutlinedTextField(modelQuery, { modelQuery = it }, label = { Text("搜索目录模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("收藏优先，其后按实际成功调用排序。收藏只保存模型引用，不复制密钥，也不会选择或测试模型。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { batchMode = !batchMode; batchModels = emptySet() }, enabled = !blocked) { Text(if (batchMode) "结束多选" else "批量收藏") }
                        if (batchMode) TextButton(enabled = !blocked && batchModels.isNotEmpty() && profile != null, onClick = {
                            val ids = JSONArray(batchModels.toList())
                            var preview: JSONObject? = null
                            execute({ batchPreview = preview }, mutation = false) { preview = service.previewModelFavorites(binding, profile!!.getLong("revision"), ids); service.snapshot() }
                        }) { Text("预览 ${batchModels.size} 项") }
                    }
                    batchResult?.let { result ->
                        val results = modelRows(result.optJSONArray("results"))
                        Text("已添加 ${results.count { it.optString("status") == "added" }} 项，已存在 ${results.count { it.optString("status") == "existing" }} 项，不可用 ${results.count { it.optString("status") == "unavailable" }} 项。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (rows.isEmpty()) Text("暂无目录记录。刷新失败不会生成模型或能力；也可在设置中明确保存模型名称后回到此页配置它。", fontSize = 12.sp, lineHeight = 20.sp)
                    rows.filter { modelQuery.isBlank() || it.optString("modelId").contains(modelQuery, true) }.forEach { row ->
                        val preference = preferences[row.optString("modelId")]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        if (batchMode) Checkbox(row.optString("modelId") in batchModels, { checked -> batchModels = if (checked) batchModels + row.optString("modelId") else batchModels - row.optString("modelId") }, enabled = !blocked && (row.optString("modelId") in batchModels || batchModels.size < 512))
                        Column(Modifier.weight(1f).clickable(enabled = !blocked) { openedModel = row.optString("modelId") }.padding(vertical = 9.dp)) {
                            Text(row.optString("modelId") + if (row.optString("modelId") == api.optString("model")) " · 已选择" else "", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val listed = row.optJSONObject("listed")
                            if (listed?.optBoolean("known") == true && !listed.optBoolean("value")) Text("机主填写 · 目录未列出此模型", fontSize = 11.sp, color = secondary)
                            Text(if (row.optBoolean("catalogFresh")) "目录读取于 ${modelTime(row.optLong("catalogFetchedAtMillis"))}" else "目录未获取或已过期，机主覆盖仍独立有效", fontSize = 11.sp, color = secondary)
                            if (row.optString("protocolCompatibility") == "declared_incompatible") Text("目录声明使用其他接口协议，已从可选候选排除；连接仍保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(enabled = !blocked && profile != null && library.optBoolean("preferencesAvailable", true), onClick = {
                            execute { service.favoriteModel(binding, profile!!.getLong("revision"), row.getString("modelId"), preference?.optBoolean("favorite") != true); service.snapshot() }
                        }) { Text(if (preference?.optBoolean("favorite") == true) "已收藏" else "收藏") }
                        }
                        HorizontalDivider(color = Line)
                    }
                } else {
                    Text("当前保留原 API 配置。启用目录后，每次请求将核对这项连接的真实能力，未知工具或图片不会被当作支持。", fontSize = 12.sp, lineHeight = 20.sp)
                    Button(onClick = { configuring = true }, enabled = !blocked && api.optString("model").isNotBlank() && snapshot.optString("modelsEndpoint").isNotBlank()) { Text("为这项 API 连接启用目录") }
                }
            } else if (binding != null && selected != null) {
                val facts = selected.optJSONObject("facts") ?: JSONObject()
                val ownerRevision = selected.optLong("ownerRevision")
                Text("能力绑定当前连接、凭据和模型。机主逐字段覆盖独立保存，刷新不会覆盖；清除覆盖后恢复来源值，来源没有数据时仍为未知。", fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
                selected.optJSONObject("endpointSupport")?.let { declaration ->
                    Text("目录端点声明：${declaration.optJSONArray("endpointTokens")} · ${modelTime(declaration.optLong("observedAtMillis"))}", style = MaterialTheme.typography.bodySmall)
                    Text(declaration.optString("sourceRef"), style = MaterialTheme.typography.labelSmall)
                    if (selected.optString("protocolCompatibility") == "declared_incompatible") Text("该模型声明不支持当前协议，不能从目录选择。可在原连接修改协议或明确手填校正；不会跟随目录中的地址发送密钥。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                ModelFacts.Field.entries.forEach { field ->
                    val fact = facts.optJSONObject(field.name) ?: JSONObject()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(modelFieldTitle(field), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(modelFactText(field, fact), fontSize = 13.sp)
                            Text("${modelSource(fact.optString("source"))} · ${modelTime(fact.optLong("observedAtMillis"))}", fontSize = 11.sp, color = secondary)
                            if (!fact.optBoolean("known")) Text(modelFactReason(fact.optString("reason")), fontSize = 11.sp, color = secondary)
                        }
                        TextButton(onClick = { edit = field }, enabled = !blocked) { Text("调整") }
                    }
                    HorizontalDivider(color = Line)
                }
                var output by remember(binding, openedModel, api.optInt("maxOutputTokens")) { mutableStateOf(api.optInt("maxOutputTokens", 4096).toString()) }
                var imageReserve by remember(binding, openedModel, snapshot.optInt("imageTokenReserve")) { mutableStateOf(if (openedModel == api.optString("model")) snapshot.optInt("imageTokenReserve").toString() else "0") }
                val existing = if (openedModel == api.optString("model")) snapshot.optJSONObject("reasoning") else null
                var mode by remember(binding, openedModel, existing?.toString()) { mutableStateOf(existing?.optString("mode")?.let { ReasoningOptions.Mode.valueOf(it) } ?: ReasoningOptions.Mode.DEFAULT) }
                var level by remember(binding, openedModel, existing?.toString()) { mutableStateOf(existing?.optString("level").orEmpty()) }
                var budget by remember(binding, openedModel, existing?.toString()) { mutableStateOf(existing?.optInt("budgetTokens", 0)?.toString() ?: "1024") }
                OutlinedTextField(output, { output = it.filter(Char::isDigit).take(5) }, label = { Text("每次输出上限 · 128–32768") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (facts.optJSONObject("IMAGE_INPUT")?.optBoolean("value") == true) OutlinedTextField(imageReserve, { imageReserve = it.filter(Char::isDigit).take(7) }, label = { Text("每张图片的本地 token 预留") }, supportingText = { Text("0表示未配置；上下文已知时需明确预留。此值是机主的保守预算，不是模型实测用量。") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("推理设置", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("服务默认不发送额外字段，也不表示推理关闭。指定级别需已知能力与级别清单；token 预算会占用输出额度。模式兼容性仍由此模型的服务核对，不支持时不会自动改模式。", fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
                val modes = when (api.optString("provider")) {
                    "anthropic" -> listOf(ReasoningOptions.Mode.DEFAULT, ReasoningOptions.Mode.ANTHROPIC_ADAPTIVE, ReasoningOptions.Mode.ANTHROPIC_BUDGET)
                    "gemini" -> listOf(ReasoningOptions.Mode.DEFAULT, ReasoningOptions.Mode.GEMINI_LEVEL, ReasoningOptions.Mode.GEMINI_BUDGET)
                    else -> listOf(ReasoningOptions.Mode.DEFAULT, ReasoningOptions.Mode.OPENAI_EFFORT)
                }
                modes.forEach { choice -> Row(Modifier.fillMaxWidth().clickable(enabled = !blocked) { mode = choice }, verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == choice, { mode = choice }, enabled = !blocked); Text(modelReasoningTitle(choice), fontSize = 13.sp) } }
                if (mode in listOf(ReasoningOptions.Mode.OPENAI_EFFORT, ReasoningOptions.Mode.ANTHROPIC_ADAPTIVE, ReasoningOptions.Mode.GEMINI_LEVEL)) {
                    val known = facts.optJSONObject("REASONING_LEVELS")
                    val levels = known?.optJSONArray("value")
                    if (known?.optBoolean("known") != true || levels == null || levels.length() == 0) Text("没有已知可选级别，请保留服务默认，或先明确覆盖级别清单。", fontSize = 12.sp)
                    else for (i in 0 until levels.length()) { val value = levels.optString(i); Row(Modifier.clickable { level = value }, verticalAlignment = Alignment.CenterVertically) { RadioButton(level == value, { level = value }); Text(value) } }
                }
                if (mode == ReasoningOptions.Mode.ANTHROPIC_BUDGET || mode == ReasoningOptions.Mode.GEMINI_BUDGET) OutlinedTextField(budget, { budget = it.take(8) }, label = { Text("推理 token 预算") }, supportingText = { Text(if (mode == ReasoningOptions.Mode.ANTHROPIC_BUDGET) "旧版模式：至少1024且小于输出上限；新模型可能拒绝" else "-1 为动态，0 为显式零预算；支持范围由所选服务最终核对") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    try {
                        val named = mode in listOf(ReasoningOptions.Mode.OPENAI_EFFORT, ReasoningOptions.Mode.ANTHROPIC_ADAPTIVE, ReasoningOptions.Mode.GEMINI_LEVEL)
                        val counted = mode == ReasoningOptions.Mode.ANTHROPIC_BUDGET || mode == ReasoningOptions.Mode.GEMINI_BUDGET
                        val options = ReasoningOptions(mode, if (named) level else "", if (counted) budget.toInt() else 0)
                        val imageBudget = imageReserve.toInt()
                        val selectedModel = selected.getString("modelId")
                        val selectedOutput = output.toInt()
                        execute { service.select(binding, selectedModel, selectedOutput, options, imageBudget) }
                    } catch (_: Exception) { error = "请核对输出上限、推理模式、级别与预算。" }
                }, enabled = !blocked && output.toIntOrNull() in 128..32768) { Text(if (snapshot.optString("activeRoute") == "account") "选择并切换至此 API 模型" else "设为主模型并保存参数") }
                Text("这只保存机主选择，不会自动发起推理。原任务在下一请求边界消费其精确绑定的能力；换凭据或目录修订后，旧绑定会被拒绝。", fontSize = 12.sp, lineHeight = 20.sp, color = secondary)
                Text("已知上下文使用文字字节与机主图片预留进行保守估算，非真实 tokenizer；图片预留未配置时会拒绝已知上下文的图片请求。上下文未知时仍受本地请求大小限制。", fontSize = 11.sp, lineHeight = 19.sp, color = secondary)
                edit?.let { field -> ModelCapabilityDialog(field, facts.optJSONObject(field.name) ?: JSONObject(), api.optString("provider") == "openai_chat", { edit = null }) { value, clear ->
                    edit = null; execute { service.editField(binding, selected.getString("modelId"), ownerRevision, field, value, clear) }
                } }
            } else Text("该模型记录已变化，请返回目录重新打开。", fontSize = 13.sp)
            if (busy || shared.pending()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!snapshotAvailable && openedModel != null && !busy) TextButton(enabled = !shared.pending(), onClick = { execute(mutation = false) { service.connections() } }) { Text("重新读取模型") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 20.sp) }
        }
    }
    if (configuring) {
        var endpoint by remember { mutableStateOf(snapshot.optString("modelsEndpoint")) }
        var format by remember { mutableStateOf(ModelBinding.Format.valueOf(snapshot.optString("catalogFormat", "OPENAI"))) }
        val formats = if (api.optString("provider") == "openai_chat") listOf(ModelBinding.Format.OPENAI, ModelBinding.Format.OPENROUTER) else listOf(ModelCatalogRoutes.defaultFormat(api.optString("provider", "openai_chat")))
        AlertDialog(onDismissRequest = { configuring = false }, title = { Text("目录来源") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("只允许同一推理 origin。更改格式或路径会推进连接修订，旧能力不会迁移；不会读取另一账号。", fontSize = 12.sp)
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("目录完整地址") })
            formats.forEach { choice -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(format == choice, { format = choice }); Text(choice.name) } }
        } }, confirmButton = { TextButton(onClick = { try { val uri = URI(endpoint.trim()); configuring = false; execute { service.configure(binding, format, uri) } } catch (_: Exception) { error = "目录地址无效。" } }) { Text("保存来源") } }, dismissButton = { TextButton(onClick = { configuring = false }) { Text("保留当前配置") } })
    }
    batchPreview?.let { preview ->
        val results = modelRows(preview.optJSONArray("results"))
        AlertDialog(onDismissRequest = { if (!blocked) batchPreview = null }, title = { Text("核对批量模型引用") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只新增收藏引用；不复制凭据、不发起测试、不更改主模型。不可用项会保留原因并跳过。")
                results.forEach { Text(it.optString("modelId") + " · " + when (it.optString("status")) { "new" -> "将添加"; "existing" -> "已存在"; "duplicate" -> "重复项"; else -> pickerUnavailableReason(it.optString("reason")) }, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(enabled = !blocked, onClick = {
            val original = ModelBinding.fromJson(preview.getJSONObject("binding")); val revision = preview.getLong("connectionRevision")
            val ids = JSONArray(results.map { it.getString("modelId") })
            var saved: JSONObject? = null
            execute({ batchResult = saved; batchPreview = null; batchModels = emptySet() }) { saved = service.saveModelFavorites(original, revision, ids); service.snapshot() }
        }) { Text("保存引用") } }, dismissButton = { TextButton(enabled = !blocked, onClick = { batchPreview = null }) { Text("取消") } })
    }
}

@Composable
private fun ModelCapabilityDialog(field: ModelFacts.Field, fact: JSONObject, chat: Boolean, dismiss: () -> Unit, save: (ModelFacts.OwnerValue?, Boolean) -> Unit) {
    var input by remember(field) { mutableStateOf(when (field) { ModelFacts.Field.CONTEXT_WINDOW -> fact.optJSONObject("value")?.optLong("tokens")?.toString().orEmpty(); ModelFacts.Field.REASONING_LEVELS -> modelRowsText(fact.optJSONArray("value")); else -> if (fact.optBoolean("known")) fact.opt("value")?.toString().orEmpty() else "" }) }
    var inputOnly by remember(field) { mutableStateOf(fact.optJSONObject("value")?.optString("kind") == "INPUT_ONLY") }
    var problem by remember(field) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(modelFieldTitle(field)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("仅覆盖这个字段。标为未知会明确遮蔽来源；清除覆盖才恢复来源值。", fontSize = 12.sp)
        Text("当前来源：${modelSource(fact.optString("source"))}\n${fact.optString("sourceRef")}\n${modelTime(fact.optLong("observedAtMillis"))}", fontSize = 11.sp)
        when (field) {
            ModelFacts.Field.IMAGE_INPUT, ModelFacts.Field.TOOL_CALLS, ModelFacts.Field.REASONING -> listOf("true", "false").forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(input == value, { input = value }); Text(if (value == "true") "支持" else "不支持") } }
            ModelFacts.Field.CHAT_OUTPUT_PARAMETER -> (if (chat) listOf("MAX_TOKENS", "MAX_COMPLETION_TOKENS") else listOf("NOT_APPLICABLE")).forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(input == value, { input = value }); Text(value.lowercase()) } }
            else -> OutlinedTextField(input, { input = it }, label = { Text(if (field == ModelFacts.Field.REASONING_LEVELS) "逗号分隔级别；留空为已知无级别" else "正整数 tokens") })
        }
        if (field == ModelFacts.Field.CONTEXT_WINDOW) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(inputOnly, { inputOnly = it }); Text("此上限仅指输入；不勾选则包含输出", fontSize = 12.sp) }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        TextButton(onClick = { save(ModelFacts.OwnerValue.unknown(), false) }) { Text("明确标为未知") }
        TextButton(onClick = { save(null, true) }) { Text("清除这项机主覆盖") }
    } }, confirmButton = { TextButton(onClick = {
        try {
            val value: Any = when (field) {
                ModelFacts.Field.CONTEXT_WINDOW -> ModelFacts.TokenWindow(input.toLong(), if (inputOnly) ModelFacts.WindowKind.INPUT_ONLY else ModelFacts.WindowKind.INPUT_AND_OUTPUT)
                ModelFacts.Field.OUTPUT_TOKENS -> input.toLong().also { require(it > 0) }
                ModelFacts.Field.IMAGE_INPUT, ModelFacts.Field.TOOL_CALLS, ModelFacts.Field.REASONING -> input.toBooleanStrict()
                ModelFacts.Field.CHAT_OUTPUT_PARAMETER -> ModelFacts.ChatOutputParameter.valueOf(input)
                ModelFacts.Field.REASONING_LEVELS -> if (input.isBlank()) emptyList<String>() else input.split(',').map { it.trim() }
            }
            save(ModelFacts.OwnerValue.known(value), false)
        } catch (_: Exception) { problem = "该值无效，请核对格式。" }
    }) { Text("保存字段覆盖") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

private fun modelRows(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
private fun modelRowsText(array: JSONArray?): String = if (array == null) "" else (0 until array.length()).joinToString(",") { array.optString(it) }
internal fun modelTime(at: Long): String = if (at <= 0) "未获取" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
private fun modelSource(source: String): String = when (source) { "OWNER_OVERRIDE" -> "机主覆盖"; "PROVIDER_CATALOG" -> "提供方目录"; else -> "尚未观察" }
private fun modelFactReason(reason: String): String = when (reason) { "not_observed" -> "尚未获取到此项能力"; "catalog_expired" -> "目录已过期，请刷新后核对"; "owner_marked_unknown" -> "机主明确标为未知，暂不采用来源值"; "catalog_capabilities_unavailable" -> "提供方目录本次未能提供可靠能力"; else -> "来源未声明可验证的值" }
private fun modelFieldTitle(field: ModelFacts.Field): String = when (field) { ModelFacts.Field.CONTEXT_WINDOW -> "上下文窗口"; ModelFacts.Field.OUTPUT_TOKENS -> "最大输出"; ModelFacts.Field.IMAGE_INPUT -> "图像输入"; ModelFacts.Field.TOOL_CALLS -> "工具调用"; ModelFacts.Field.REASONING -> "推理能力"; ModelFacts.Field.REASONING_LEVELS -> "推理级别"; ModelFacts.Field.CHAT_OUTPUT_PARAMETER -> "Chat 输出参数字段" }
private fun modelFactText(field: ModelFacts.Field, fact: JSONObject): String {
    if (!fact.optBoolean("known")) return "未知"
    return when (field) {
        ModelFacts.Field.CONTEXT_WINDOW -> fact.getJSONObject("value").let { "${it.getLong("tokens")} tokens · " + if (it.optString("kind") == "INPUT_ONLY") "仅输入" else "输入与输出合计" }
        ModelFacts.Field.OUTPUT_TOKENS -> "${fact.optLong("value")} tokens"
        ModelFacts.Field.IMAGE_INPUT, ModelFacts.Field.TOOL_CALLS, ModelFacts.Field.REASONING -> if (fact.optBoolean("value")) "支持" else "不支持"
        ModelFacts.Field.REASONING_LEVELS -> modelRowsText(fact.optJSONArray("value")).ifBlank { "已知无可选级别" }
        ModelFacts.Field.CHAT_OUTPUT_PARAMETER -> if (fact.optString("value") == "NOT_APPLICABLE") "此原生协议不使用 Chat 参数" else fact.optString("value").lowercase()
    }
}
private fun modelReasoningTitle(mode: ReasoningOptions.Mode): String = when (mode) { ReasoningOptions.Mode.DEFAULT -> "服务默认"; ReasoningOptions.Mode.OPENAI_EFFORT -> "指定推理 effort"; ReasoningOptions.Mode.ANTHROPIC_ADAPTIVE -> "Adaptive thinking 与 effort"; ReasoningOptions.Mode.ANTHROPIC_BUDGET -> "旧版手动 thinking token 预算"; ReasoningOptions.Mode.GEMINI_LEVEL -> "指定 thinkingLevel"; ReasoningOptions.Mode.GEMINI_BUDGET -> "指定 thinkingBudget" }
