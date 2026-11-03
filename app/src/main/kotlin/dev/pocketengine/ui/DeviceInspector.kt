package dev.pocketengine.ui

import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

internal data class FramePoint(val x: Int, val y: Int)

/** Fit the image into its viewport; letterbox taps never become device coordinates. */
internal fun mapFramePoint(x: Float, y: Float, viewportWidth: Int, viewportHeight: Int, imageWidth: Int, imageHeight: Int): FramePoint? {
    if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0 || !x.isFinite() || !y.isFinite()) return null
    val scale = min(viewportWidth.toDouble() / imageWidth, viewportHeight.toDouble() / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (viewportWidth - width) / 2
    val top = (viewportHeight - height) / 2
    if (x < left || y < top || x > left + width || y > top + height) return null
    return FramePoint(((x - left) / width * 1000).roundToInt().coerceIn(0, 1000), ((y - top) / height * 1000).roundToInt().coerceIn(0, 1000))
}

/** Visible state is only a projection. The original host still checks task/run/display/session and journals every input. */
internal data class PhoneInspectionPresentation(val location: String, val frameFresh: Boolean, val manual: Boolean, val canTakeOver: Boolean, val message: String)
internal fun phoneInspectionPresentation(task: UiTask?, observation: JSONObject?, allowed: Boolean, busy: Boolean, unresolved: Boolean, activeTaskId: String?, nowUptime: Long): PhoneInspectionPresentation {
    val background = task?.executionMode == "background"
    val handoffState = observation?.optJSONObject("ownerHandoff")?.optString("state")
    val location = when (handoffState) { "main" -> "主屏 · 主人接管（助手暂停）"; "unknown" -> "应用位置待核对"; else -> when (task?.executionMode) { "background" -> "后台独立屏"; "main_screen" -> "手机主屏"; else -> "执行位置待核对" } }
    val at = observation?.optLong("observedAtUptimeMs", -1) ?: -1
    val age = nowUptime - at
    val bound = handoffState !in setOf("main", "unknown") && background && observation != null && observation.optBoolean("ok", true) && observation.optString("observationId").isNotBlank() && observation.optInt("displayId", -1) > 0 && observation.optString("sessionId").isNotBlank() && observation.optString("executionMode") == "background"
    val fresh = bound && at >= 0 && age in 0..120_000
    val manual = fresh && allowed && !busy && !unresolved && activeTaskId == task?.id && task?.status in setOf("paused", "awaiting_user")
    val canTakeOver = handoffState !in setOf("main", "unknown") && background && !busy && !unresolved && (activeTaskId == null || activeTaskId == task?.id) && task?.status in setOf("preparing", "running", "pausing", "paused", "awaiting_user", "interrupted", "needs_confirmation")
    val message = when {
        handoffState == "main" -> "应用由主人在主屏操作 · 助手未读取或控制主屏"
        handoffState == "unknown" -> "移屏结果待核对 · 不重发，不继续助手"
        unresolved -> "动作结果待核对 · 先不要重复操作"
        busy -> "本次请求处理中 · 旧快照仅供查看"
        !background -> if (task?.executionMode == "main_screen") "此任务使用主屏，请直接在手机处理；未切到独立屏" else "请回原任务核对执行位置"
        !bound -> "尚未取得可核对的原画面 · 不可点按"
        !fresh -> "原快照已过期或时间不可核对 · 请刷新"
        manual -> "已暂停助手 · 你可操作这份原画面"
        else -> "最近观察快照 · 仅查看，不是实时视频"
    }
    return PhoneInspectionPresentation(location, fresh, manual, canTakeOver, message)
}

@Composable
internal fun DeviceInspector(state: PocketUiState, controller: PocketWorldController) {
    val taskId = state.inspectionTaskId ?: return
    val task = state.tasks.find { it.id == taskId }
    val observation = state.inspectionObservation
    val unresolved = task?.let(::unresolvedOperations).orEmpty()
    val observationId = observation?.optString("observationId").orEmpty()
    val observedAt = observation?.optLong("observedAtUptimeMs", -1) ?: -1
    var now by remember(taskId, observationId, observedAt) { mutableStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(taskId, observationId, observedAt) {
        now = SystemClock.uptimeMillis()
        // Expiry is derived from the native observation timestamp, never used to infer task execution or dispatch a read.
        val remaining = 120_001L - (now - observedAt)
        if (observedAt >= 0 && remaining in 1..120_001) { delay(remaining); now = SystemClock.uptimeMillis() }
    }
    val presentation = phoneInspectionPresentation(task, observation, state.inspectionManualAllowed, state.inspectionBusy, unresolved.isNotEmpty(), state.activeTaskId, now)
    val handoff = observation?.optJSONObject("ownerHandoff")
    val handedOut = handoff?.optString("state") in setOf("main", "unknown")
    val manual = presentation.manual && !handedOut
    var handoffReview by remember(taskId) { mutableStateOf<String?>(null) }
    LaunchedEffect(handoff?.optString("handoffId")) { if (handoff?.optString("state") == "prepared" && !handoff.optBoolean("planRevoked")) handoffReview = "main" }
    var input by rememberSaveable(taskId) { mutableStateOf("") }
    var inputPanel by remember(taskId) { mutableStateOf(false) }
    var apps by remember(taskId) { mutableStateOf(false) }
    var selectedNode by remember(taskId) { mutableStateOf<String?>(null) }
    var returnToAgent by remember(taskId) { mutableStateOf(false) }
    var stopReview by remember(taskId) { mutableStateOf(false) }
    var details by remember(taskId) { mutableStateOf(false) }
    var more by remember(taskId) { mutableStateOf(false) }
    var privateInputPanel by remember(taskId) { mutableStateOf(false) }
    var privateInput by remember(taskId) { mutableStateOf("") }
    var privateNode by remember(taskId) { mutableStateOf<String?>(null) }
    val editable = observation?.optJSONArray("nodes").objects().filter { it.optBoolean("editable") && it.optString("text") != "[password]" }
    val privateNodes = observation?.optJSONArray("nodes").objects().filter { it.optBoolean("editable") && it.optString("text") == "[password]" }
    LaunchedEffect(observationId) {
        selectedNode = editable.singleOrNull()?.optString("id"); privateNode = privateNodes.singleOrNull()?.optString("id")
        // A fast refresh can settle between composition frames. Never carry a private editor or confirmation into a different native frame.
        privateInput = ""; privateInputPanel = false; inputPanel = false; apps = false; returnToAgent = false
    }
    LaunchedEffect(manual) { if (!manual) { privateInput = ""; privateInputPanel = false; inputPanel = false; apps = false; returnToAgent = false } }
    val context = LocalContext.current
    val lifecycle = remember(context) { generateSequence(context) { current -> (current as? ContextWrapper)?.baseContext?.takeUnless { it === current } }.filterIsInstance<LifecycleOwner>().firstOrNull() }
    DisposableEffect(taskId, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { privateInput = ""; privateInputPanel = false; returnToAgent = false } }
        lifecycle?.lifecycle?.addObserver(observer)
        onDispose { lifecycle?.lifecycle?.removeObserver(observer); privateInput = "" }
    }
    Dialog(onDismissRequest = controller::closeInspection, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = controller::closeInspection, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                    Column(Modifier.weight(1f)) {
                        Text(task?.goal?.takeIf(String::isNotBlank) ?: "原任务手机画面", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(presentation.location, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = controller::observeInspection, enabled = !state.inspectionBusy, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(presentation.message, style = MaterialTheme.typography.bodySmall, color = if (unresolved.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                state.inspectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                state.inspectionMessage?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                if (unresolved.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.heightIn(max = 176.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text("先核对原动作，再继续", style = MaterialTheme.typography.titleSmall)
                        unresolved.forEach { operation ->
                            Text(operation.optString("tool", "待核对动作"), style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { controller.resolve(taskId, operation.optString("operationId"), "occurred") }, modifier = Modifier.weight(1f)) { Text("已经发生") }
                                TextButton(onClick = { controller.resolve(taskId, operation.optString("operationId"), "not_occurred") }, modifier = Modifier.weight(1f)) { Text("没有发生") }
                            }
                        }
                    }
                }
                if (state.inspectionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                ObservationFrame(observation, manual, presentation.location, Modifier.weight(1f).fillMaxWidth()) { tool, args -> controller.manualAction(tool, args) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (!handedOut && task?.executionMode == "background" && task.status in setOf("paused", "awaiting_user") && state.activeTaskId == taskId && unresolved.isEmpty()) OutlinedButton(onClick = { controller.ownerPhoneHandoff("prepare") }, enabled = !state.inspectionBusy, modifier = Modifier.fillMaxWidth()) { Text("在主屏亲自操作这个应用…") }
                    if (handedOut) {
                        Text(if (handoff?.optString("state") == "main") "原应用由主人在主屏接管，助手仍暂停；此处没有读取或控制主屏。" else "原应用位置尚未确认，助手保持暂停；请先只读核对，不重发移屏。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { controller.ownerPhoneHandoff("status") }, enabled = !state.inspectionBusy, modifier = Modifier.fillMaxWidth()) { Text("核对原应用位置（不重发移屏）") }
                        if (handoff?.optString("state") == "main" && unresolved.isEmpty()) Button(onClick = { handoffReview = "return" }, enabled = !state.inspectionBusy, modifier = Modifier.fillMaxWidth()) { Text("操作完成，将原应用交还副屏") }
                    } else if (manual) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller.manualAction("phone_back") }, modifier = Modifier.weight(1f)) { Text("返回键") }
                            OutlinedButton(onClick = { inputPanel = true; privateInput = ""; privateInputPanel = false }, modifier = Modifier.weight(1f)) { Text("文字输入") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { controller.loadLaunchApps(); apps = true }, modifier = Modifier.weight(1f)) { Text("打开应用") }
                            if (privateNodes.isNotEmpty()) TextButton(onClick = { privateInput = ""; privateInputPanel = true; inputPanel = false }, modifier = Modifier.weight(1f)) { Text("私密输入") }
                        }
                        Button(onClick = { returnToAgent = true }, modifier = Modifier.fillMaxWidth()) { Text("交回助手，继续原任务") }
                    } else if (unresolved.isEmpty()) {
                        Button(onClick = { if (state.inspectionManualAllowed && !presentation.frameFresh) controller.observeInspection() else controller.takeOverInspection() }, enabled = presentation.canTakeOver, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.inspectionBusy) "正在核对原任务" else if (state.inspectionManualAllowed && !presentation.frameFresh) "刷新原画面后操作" else "暂停助手并接管")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (manual) "点按/滑动只作用于上方图像范围" else "返回只关闭查看，不批准或继续执行", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { more = true }) { Text("更多") }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(text = { Text("原观察信息") }, onClick = { more = false; details = true })
                                if (task?.executionMode == "background") DropdownMenuItem(text = { Text("连接与启用方式") }, onClick = { more = false; controller.openBackgroundSetup() })
                                DropdownMenuItem(text = { Text("停止这个原任务") }, onClick = { more = false; stopReview = true }, enabled = task != null && state.activeTaskId == taskId)
                            }
                        }
                    }
                }
            }
        }
        BackHandler { controller.closeInspection() }
    }
    if (handoffReview != null && handoff != null) AlertDialog(
        onDismissRequest = { handoffReview = null },
        title = { Text(if (handoffReview == "main") "将这一个应用移到主屏？" else "已不需要在主屏操作？") },
        text = { Text("${handoff.optString("component")}\nAndroid任务 ${handoff.optInt("rootTaskId")} · 原副屏 ${handoff.optInt("originalDisplayId")}\n" + if (handoffReview == "main") "仅移动已核实的原任务，不会搬动主屏上同包名的其他任务。Android 14+ 和已授权后台助手须支持；助手保持暂停。操作后回到本页交还。" else "将同一实际应用任务移回原副屏，重新观察后再由你决定是否继续助手。位置或身份变化时不会搬动其他任务。") },
        confirmButton = { TextButton(onClick = { val action = handoffReview ?: return@TextButton; handoffReview = null; controller.ownerPhoneHandoff(action) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { handoffReview = null }) { Text("暂不") } }
    )
    if (inputPanel && manual) AlertDialog(onDismissRequest = { inputPanel = false }, title = { Text("给原画面输入文字") }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).imePadding()) {
            Text("会替换你选中的原输入框；提交后重新观察，不会自动继续助手。", style = MaterialTheme.typography.bodySmall)
            if (editable.isEmpty()) Text("未发现普通可输入节点。先关闭，在原画面点选输入框并刷新。", style = MaterialTheme.typography.bodySmall)
            editable.forEach { node -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedNode == node.optString("id"), { selectedNode = node.optString("id") }); Text(node.optString("description").ifBlank { node.optString("text").ifBlank { "输入框" } }.take(70), style = MaterialTheme.typography.bodySmall, maxLines = 2) } }
            OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("普通输入内容") }, minLines = 2, maxLines = 4)
        }
    }, confirmButton = { Button(onClick = { inputPanel = false; controller.manualAction("phone_type", JSONObject().put("text", input).also { selectedNode?.let { id -> it.put("nodeId", id) } }) }, enabled = input.isNotBlank() && editable.any { it.optString("id") == selectedNode }) { Text("输入到原框") } }, dismissButton = { TextButton(onClick = { inputPanel = false }) { Text("返回画面") } })
    if (privateInputPanel && manual) AlertDialog(onDismissRequest = { privateInput = ""; privateInputPanel = false }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), title = { Text("私密输入，仅交给原框") }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).imePadding()) {
            Text("不进入任务文本、模型或记忆。取消、离开应用或原帧失效会清空。", style = MaterialTheme.typography.bodySmall)
            privateNodes.forEachIndexed { index, node -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(privateNode == node.optString("id"), { privateNode = node.optString("id") }); Text("安全输入框 " + (index + 1), style = MaterialTheme.typography.bodySmall) } }
            OutlinedTextField(privateInput, { privateInput = it }, label = { Text("私密内容") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
    }, confirmButton = { Button(onClick = { val text = privateInput; privateInput = ""; privateInputPanel = false; privateNode?.let { controller.ownerSecretInput(it, text) } }, enabled = privateInput.isNotEmpty() && privateNodes.any { it.optString("id") == privateNode }) { Text("输入到安全框") } }, dismissButton = { TextButton(onClick = { privateInput = ""; privateInputPanel = false }) { Text("清空并关闭") } })
    if (apps && manual) AlertDialog(onDismissRequest = { apps = false }, title = { Text("在原后台独立屏打开应用") }, text = {
        Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
            if (state.launchApps.isEmpty()) Text("正在读取系统允许访问的应用列表…", style = MaterialTheme.typography.bodySmall)
            state.launchApps.forEach { app -> TextButton(onClick = { apps = false; controller.manualAction("phone_launch_app", JSONObject().put("packageName", app.packageName)) }, modifier = Modifier.fillMaxWidth()) { Text(app.label) } }
        }
    }, confirmButton = { TextButton(onClick = { apps = false }) { Text("返回画面") } })
    if (returnToAgent && manual && task != null) AlertDialog(onDismissRequest = { returnToAgent = false }, title = { Text("交回助手继续原任务？") }, text = { Text("将保留同一任务与执行位置，重新观察你处理后的原页面再继续；未核对动作不会跳过。") }, confirmButton = { Button(onClick = { controller.resumeTask(task, ""); controller.closeInspection(); returnToAgent = false }) { Text("继续原任务") } }, dismissButton = { TextButton(onClick = { returnToAgent = false }) { Text("继续由我操作") } })
    if (stopReview && task != null) AlertDialog(onDismissRequest = { stopReview = false }, title = { Text("停止这个原任务？") }, text = { Text(task.goal + "\n会停止后续执行。已发生的操作不会撤回；实际收尾与资源释放请在原任务核对。") }, confirmButton = { TextButton(onClick = { stopReview = false; controller.stopReviewedTask(task) }) { Text("请求停止原任务", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { stopReview = false }) { Text("继续查看") } })
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("原观察信息") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("原任务 · $taskId\n执行地点 · ${presentation.location}\n${presentation.message}", style = MaterialTheme.typography.bodySmall)
            Text("观察编号 · ${observationId.ifBlank { "尚未取得" }}\nDisplay · ${observation?.optInt("displayId", -1) ?: -1}\nSession · ${observation?.optString("sessionId").orEmpty().ifBlank { "尚未取得" }}", style = MaterialTheme.typography.bodySmall)
            if (observedAt >= 0) Text("原生观察时间 · uptime ${observedAt}ms\n同一原帧最多使用两分钟；仍由原生宿主逐次复核。", style = MaterialTheme.typography.bodySmall)
            if (observation?.optBoolean("truncated") == true) Text("节点内容为部分读取，不代表完整页面。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("返回画面") } })
}

@Composable
internal fun ObservationFrame(observation: JSONObject?, interactive: Boolean, location: String, modifier: Modifier, action: (String, JSONObject) -> Unit) {
    val encoded = observation?.optString("imageBase64")?.takeIf { it.isNotBlank() } ?: observation?.optString("imageDataUrl")?.substringAfter("base64,", "").orEmpty()
    val bitmap = remember(encoded) {
        try {
            if (encoded.isBlank() || encoded.length > 8_000_000) null else {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16_000_000) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) { null }
    }
    val id = observation?.optString("observationId")
    val gestures = if (bitmap != null && interactive && !id.isNullOrBlank()) Modifier.pointerInput(id, interactive, bitmap.width, bitmap.height) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val started = SystemClock.uptimeMillis()
            val first = mapFramePoint(down.position.x, down.position.y, size.width, size.height, bitmap.width, bitmap.height)
            var position = down.position
            var last = down.position
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) { pressed = false; continue }
                last = change.position
                if ((last - down.position).getDistance() > (position - down.position).getDistance()) position = last
                pressed = change.pressed
                change.consume()
            }
            val end = mapFramePoint(last.x, last.y, size.width, size.height, bitmap.width, bitmap.height)
            if (first != null && end != null) {
                if (hypot((position.x - down.position.x).toDouble(), (position.y - down.position.y).toDouble()) >= viewConfiguration.touchSlop) {
                    action("phone_swipe", JSONObject().put("x1", first.x).put("y1", first.y).put("x2", end.x).put("y2", end.y).put("durationMs", (SystemClock.uptimeMillis() - started).coerceIn(100, 2000)))
                } else action("phone_tap", JSONObject().put("x", first.x).put("y", first.y))
            }
        }
    } else Modifier
    Box(modifier.background(Soft).then(gestures), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = if (interactive) "可点按和滑动的${location}快照" else "最近一次观察到的${location}快照，仅供查看", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text(observation?.optString("message")?.takeIf { it.isNotBlank() } ?: "尚未取得真实画面。\n接管后刷新，或先在独立屏打开应用。", fontSize = 13.sp, lineHeight = 22.sp, color = SecondaryInk, modifier = Modifier.padding(24.dp))
    }
}
