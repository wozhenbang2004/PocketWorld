package dev.pocketengine.ui

import android.content.ContextWrapper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject

@Composable
internal fun BackgroundConnectionSummary(state: PocketUiState, controller: PocketWorldController, compact: Boolean = false) {
    val connection = backgroundConnectionState(state.feature("display"), state.activeTaskId, state.moduleBusy)
    if (compact) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(backgroundConnectionHeadline(connection), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (connection.helperConnected && !connection.screenEstablished) SetupCaption("开始任务时核验实际独立屏")
            }
            TextButton(onClick = controller::openBackgroundSetup) { Text(if(connection.helperConnected) "查看状态" else "设置", fontSize = 13.sp) }
        }
        return
    }
    Text(backgroundConnectionHeadline(connection), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    SetupCaption(if (connection.helperConnected) "已连接，无需重复设置。开始任务时会核对实际独立屏；不会占用手机主屏。" else "跟随一次引导，完成系统要求的授权。不需要电脑，也不需要另装应用。")
    OutlinedButton(onClick = controller::openBackgroundSetup) { Text(if (connection.helperConnected) "查看控制状态" else "启用手机控制", fontSize = 12.sp) }
}

@Composable
internal fun BackgroundPhoneSetup(state: PocketUiState, controller: PocketWorldController) {
    val display = state.feature("display")
    val connection = backgroundConnectionState(display, state.activeTaskId, state.moduleBusy)
    var mode by remember { mutableStateOf(backgroundSetupMode(display)) }
    var modeChosen by remember { mutableStateOf(false) }
    LaunchedEffect(connection.backend) { if (!modeChosen) mode = backgroundSetupMode(display) }
    var alternatives by remember { mutableStateOf(false) }
    var foregroundConsent by remember { mutableStateOf(false) }
    var practice by remember { mutableStateOf(false) }
    var externalPractice by remember { mutableStateOf(false) }
    var practicePackage by remember { mutableStateOf("") }
    var practiceField by remember { mutableStateOf("") }
    var forget by remember { mutableStateOf(false) }
    var audioBusy by remember { mutableStateOf(false) }
    var audioEnabled by remember { mutableStateOf(display.optBoolean("audioSilenceEnabled", false)) }
    var audioReceipt by remember { mutableStateOf<String?>(null) }
    var audioError by remember { mutableStateOf<String?>(null) }
    var diagnosticBusy by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf<dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport.Report?>(null) }
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller.phoneDiagnosticsOwnerVisible) { if (!controller.phoneDiagnosticsOwnerVisible) { diagnosticBusy = false; diagnosticReport = null; diagnosticMessage = null } }
    val audioAvailable = display.opt("audioSilenceEnabled") is Boolean
    LaunchedEffect(display.opt("audioSilenceEnabled")) { if (audioAvailable) audioEnabled = display.getBoolean("audioSilenceEnabled") }
    LaunchedEffect(controller.backgroundAudioOwnerVisible) { if (!controller.backgroundAudioOwnerVisible) { audioBusy = false; audioReceipt = null; audioError = null } }
    LaunchedEffect(mode, connection.wirelessAvailable) {
        if (mode == "wireless_adb" && connection.wirelessAvailable && !connection.taskOwned) controller.display("adb.discover")
        if (mode != "wireless_adb") controller.cancelPairingNotification()
    }
    Dialog(onDismissRequest = controller::closeBackgroundSetup, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = controller::closeBackgroundSetup) { Text("返回") }
                    Text("启用手机控制", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = controller::refreshBackgroundSetup) { Text("刷新", fontSize = 12.sp) }
                }
                HorizontalDivider(color = Line)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp)) {
                    Text(if (!connection.helperConnected && !connection.requiresConnectionCheck && !connection.busy && connection.supported) "让助手帮你操作手机" else backgroundConnectionHeadline(connection), fontSize = 23.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                    SetupCaption(if (connection.screenEstablished) "这是宿主已核验的独立显示会话。返回原任务后继续操作。" else if (connection.helperConnected) "助手已完成真实连接。开始任务时才会建立独立显示会话。" else "首次使用只需完成下面的系统设置。授权由你确认，完成后返回原目标，不会自动开始任务。")
                    BackgroundSetupProgress(connection, state.accessibilityEnabled, state.notificationsEnabled)
                    SetupHeading("本机能力检查")
                    SetupCaption("逐项来自当前原生检查；已连接不代表输入、取帧或输入法隔离都可用。返回系统设置后请刷新。")
                    state.phoneReadiness.groups.forEach { group ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(group.title, modifier = Modifier.weight(1f), fontSize = 13.sp); Text(group.state.label, fontSize = 12.sp) }
                        group.checks.filter { it.state != PhoneCapabilityState.READY }.distinctBy { it.id }.forEach { check ->
                            SetupCaption(check.message.ifBlank { "尚未取得实际状态" })
                            when (check.action) {
                                "accessibility" -> TextButton(onClick = controller::openAccessibility) { Text("检查界面权限") }
                                "notifications" -> TextButton(onClick = controller::openNotifications) { Text("检查任务通知") }
                                "background_setup", "retry" -> TextButton(onClick = controller::refreshBackgroundSetup) { Text("重新核对") }
                                "main_screen_choice" -> TextButton(onClick = { foregroundConsent = true }) { Text("选择主屏兼容模式") }
                            }
                        }
                    }
                    TextButton(onClick = {
                        diagnosticBusy = true; diagnosticReport = null; diagnosticMessage = null
                        controller.previewPhoneDiagnostics { report, error -> diagnosticBusy = false; diagnosticReport = report; diagnosticMessage = error }
                    }, enabled = controller.phoneDiagnosticsOwnerVisible && !diagnosticBusy) { Text(if (diagnosticBusy) "正在读取诊断…" else "预览脱敏诊断报告") }
                    diagnosticReport?.let { report ->
                        SetupHeading("手机诊断预览")
                        SetupCaption("这是预览时的版本、权限与连接事实，不证明设备任务已完成。未打包的源码身份为unknown；不会运行演练或申请新权限。")
                        SelectionContainer { Text(report.text(), modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp) }
                        SetupCaption("复制会替换系统剪贴板。系统分享仅打开选择器，不代表已经发送；请先核对这份预览。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { diagnosticMessage = controller.copyPhoneDiagnostics(report) ?: "已交给系统剪贴板；未对外发送。" }, enabled = controller.phoneDiagnosticsOwnerVisible && !diagnosticBusy) { Text("复制这份诊断") }
                            TextButton(onClick = { diagnosticMessage = controller.sharePhoneDiagnostics(report) ?: "已请求系统分享选择器；发送结果未知。" }, enabled = controller.phoneDiagnosticsOwnerVisible && !diagnosticBusy) { Text("系统分享…") }
                        }
                    }
                    diagnosticMessage?.let { SetupCaption(it) }
                    TextButton(onClick = { practice = !practice; if (practice) controller.readPhonePracticeEvidence() }, enabled = !state.phoneSubmitting) { Text(if (practice) "收起实际演练" else "手机演练与实际结果…") }
                    if (practice) {
                        SetupCaption("在 PocketWorld 自有测试页真实点击、输入中英文、滚动和返回，检查更新与后台屏。仍是一条原手机任务，最多 5 分钟并受原预算限制；暂停、停止和清场回执都在原任务中。")
                        if (connection.helperConnected) OutlinedButton(onClick = { controller.startOwnedPhonePractice("background") }, enabled = !connection.taskOwned && !connection.busy && !state.phoneSubmitting) { Text("明确开始自有测试页后台演练") }
                        OutlinedButton(onClick = { controller.startOwnedPhonePractice("main_screen") }, enabled = !connection.taskOwned && !connection.busy && !state.phoneSubmitting) { Text("明确使用主屏演练（占用屏幕）") }
                        PhonePracticeEvidence(controller)
                        TextButton(onClick = { externalPractice = !externalPractice }, enabled = !connection.taskOwned && !state.phoneSubmitting) { Text(if (externalPractice) "收起其他应用字段演练" else "已有其他测试应用？", fontSize = 12.sp) }
                        if (externalPractice) {
                            SetupCaption("沿用原单字段演练。仅选择你能安全修改的普通测试字段，真实输入“中文输入演练”，不提交；其结果保存在该原任务中，不计入上方自有测试页检查。")
                            OutlinedTextField(practicePackage, { practicePackage = it }, label = { Text("目标测试应用包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(practiceField, { practiceField = it }, label = { Text("可安全测试的输入框标签") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (connection.helperConnected) OutlinedButton(onClick = { controller.startPhonePractice(practicePackage, practiceField, "background") }, enabled = !connection.taskOwned && !state.phoneSubmitting) { Text("明确开始此字段后台演练") }
                            OutlinedButton(onClick = { controller.startPhonePractice(practicePackage, practiceField, "main_screen") }, enabled = !connection.taskOwned && !state.phoneSubmitting) { Text("明确用主屏演练此字段") }
                        }
                    }
                    if (controller.backgroundAudioOwnerVisible) {
                        SetupHeading("后台应用声音")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("打开应用前静音", modifier = Modifier.weight(1f), fontSize = 14.sp)
                            Switch(audioEnabled, { next ->
                                audioBusy = true; audioError = null; audioReceipt = null
                                controller.setBackgroundAudioSilence(next) { actual, failure ->
                                    actual?.opt("enabled")?.let { if (it is Boolean) audioEnabled = it }
                                    audioBusy = false; audioError = failure
                                }
                            }, modifier = Modifier.semantics { contentDescription = "打开应用前静音" }, enabled = audioAvailable && !audioBusy && (!connection.taskOwned || audioEnabled) && !connection.busy)
                        }
                        SetupCaption("默认关闭。仅改变本机原后台启动用户的指定应用音频模式，不改全局音量。先保存原模式，接管或结束时尝试恢复；关闭只阻止后续静音。")
                        SetupCaption("查询后设置不是系统原子操作。已观察到主人更改的模式不会覆盖；恢复未确认时保留原记录，不当作成功。此开关不会授予系统或Shizuku权限。")
                        OutlinedButton(onClick = {
                            audioBusy = true; audioError = null; audioReceipt = null
                            controller.restoreBackgroundAudio { actual, failure ->
                                audioBusy = false; audioError = failure
                                if (actual != null) audioReceipt = "已核实恢复 ${actual.optInt("restored")} 项；仍有 ${actual.optInt("pending")} 项原记录待核对。"
                            }
                        }, enabled = !audioBusy && !connection.taskOwned && !connection.busy && connection.helperConnected) { Text("按原记录恢复声音") }
                        if (!audioAvailable) SetupCaption("尚未读取到真实音频策略，请刷新后再修改。")
                        if (audioBusy) SetupCaption("正在核对原音频回执…")
                        audioReceipt?.let { SetupCaption(it) }
                        audioError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    }
                    if (connection.taskOwned) {
                        SetupCaption("原手机任务正在持有执行资源。应用限制仍可撤销；更改连接前，请回原任务停止并等待资源释放。")
                        state.activeTaskId?.let { id -> TextButton(onClick = { controller.closeBackgroundSetup(); controller.focusTask(id) }) { Text("回到原任务") } }
                    }
                    if (connection.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    if (connection.loaded && !connection.supported) {
                        SetupCaption("当前 Android 版本不支持后台独立屏。这不是授权失败；无需安装其他应用或反复配对。你也可以明确选择主屏模式，但执行时会占用当前屏幕。")
                        OutlinedButton(onClick = { foregroundConsent = true }, enabled = !connection.taskOwned) { Text("选择主屏兼容模式") }
                    } else if (mode == "wireless_adb") WirelessSetup(state, connection, controller)
                    else {
                        SetupCaption("已保留你之前选择的高级兼容连接，不会自动更换。")
                        TextButton(onClick = { alternatives = true }) { Text("恢复已选连接", fontSize = 12.sp) }
                    }
                    state.moduleErrors.filterKeys { it.startsWith("display.") || it.startsWith("phone_setup.") }.values.distinct().forEach { Text(it, fontSize = 12.sp, lineHeight = 21.sp, color = Danger, modifier = Modifier.padding(top = 10.dp)) }
                    if (!state.accessibilityEnabled || !state.notificationsEnabled) {
                        SetupHeading("任务还需要的权限")
                        if (!state.notificationsEnabled) { SetupCaption("任务通知用于暂停和停止；允许通知不等于完成无线调试配对。"); OutlinedButton(onClick = controller::openNotifications) { Text("允许任务通知", fontSize = 12.sp) } }
                        if (!state.accessibilityEnabled) { SetupCaption("无障碍用于系统允许的界面节点与文字读取，以及明确选择的主屏控制。"); OutlinedButton(onClick = controller::openAccessibility) { Text("设置界面读取权限", fontSize = 12.sp) } }
                    }
                    if (controller.backgroundAudioOwnerVisible) {
                        PhoneOwnerExecutionControls(state, connection, controller)
                        PhoneSetupControls(state.feature("phone_setup"), controller::phoneSetup)
                    }
                    TextButton(onClick = { alternatives = !alternatives }, modifier = Modifier.padding(top = 20.dp)) { Text(if (alternatives) "收起高级设置" else "高级兼容与连接恢复", fontSize = 12.sp) }
                    if (alternatives) {
                        SetupCaption("正常启用无需选择这些方式。仅当你已在使用 Shizuku 或受信任电脑连接时使用；切换前先释放原连接。")
                        listOf("wireless_adb" to "内置手机引导（默认）", "shizuku" to "已有 Shizuku 的兼容连接", "adb" to "受信任电脑 ADB（高级）").forEach { (value, label) ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = connection.canChangeBackend || mode == value) { mode = value; modeChosen = true }, verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == value, { mode = value; modeChosen = true }, enabled = connection.canChangeBackend || mode == value); Text(label, fontSize = 13.sp) }
                        }
                        if (mode == "shizuku") ShizukuSetup(state, connection, controller)
                        if (mode == "adb") ComputerAdbSetup(state, connection, controller)
                        if (display.optBoolean("sessionActive") || display.optBoolean("releasePending")) TextButton(onClick = { controller.display("release") }, enabled = !connection.taskOwned && !connection.busy) { Text("释放原独立屏", fontSize = 12.sp) }
                        if (connection.identityPresent) TextButton(onClick = { forget = true }, enabled = connection.canForget) { Text("遗忘本机无线配对身份…", fontSize = 12.sp, color = Danger) }
                    }
                    if (connection.helperConnected) Button(onClick = controller::closeBackgroundSetup, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("连接已完成，返回原目标") }
                    Spacer(Modifier.height(26.dp))
                }
            }
        }
        BackHandler(onBack = controller::closeBackgroundSetup)
    }
    if (foregroundConsent) AlertDialog(onDismissRequest = { foregroundConsent = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("改用主屏兼容模式？") }, text = { Text("助手执行时会切换应用并占用当前屏幕。请先完成界面读取与任务通知授权；这里只选择模式，不会开始目标，也不会绕过系统限制。") }, confirmButton = { Button(onClick = { foregroundConsent = false; controller.selectMainScreenCompatibility() }) { Text("明确选择主屏模式") } }, dismissButton = { TextButton(onClick = { foregroundConsent = false }) { Text("暂不使用") } })
    if (forget) AlertDialog(onDismissRequest = { forget = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("遗忘本机无线配对身份？") }, text = { Text("先等待本应用启动进程实际退出，再删除本机身份。已发生的配对不会撤回；系统无线调试中的已配对设备仍需你手动遗忘。") }, confirmButton = { TextButton(onClick = { forget = false; controller.display("adb.forget") }, enabled = connection.canForget) { Text("确认遗忘本机身份", color = Danger) } }, dismissButton = { TextButton(onClick = { forget = false }) { Text("保留") } })
}

@Composable
private fun WirelessSetup(state: PocketUiState, connection: BackgroundConnectionState, controller: PocketWorldController) {
    val adb = state.feature("display").optJSONObject("adb") ?: JSONObject()
    val discovery = adb.optJSONObject("discovery")
    var connecting by remember { mutableStateOf(wirelessConnectStep(false, connection)) }
    var code by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val lifecycle = remember(context) { generateSequence(context) { current -> (current as? ContextWrapper)?.baseContext?.takeUnless { it === current } }.filterIsInstance<LifecycleOwner>().firstOrNull() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) code = "" }
        lifecycle?.lifecycle?.addObserver(observer)
        onDispose { lifecycle?.lifecycle?.removeObserver(observer); code = "" }
    }
    LaunchedEffect(connection.phase, connection.requiresConnectionCheck, connection.busy) {
        connecting = wirelessConnectStep(connecting, connection)
        if (connection.phase == "paired" || connection.requiresConnectionCheck || connection.phase in setOf("needs_pairing", "identity_forgotten")) code = ""
        if (!connection.busy && !connection.requiresConnectionCheck && connection.phase in setOf("needs_pairing", "identity_forgotten")) pairPort = null
    }
    LaunchedEffect(connection.taskOwned) { if (connection.taskOwned) code = "" }
    if (!connection.loaded) { SetupCaption("正在读取真实连接状态…"); return }
    if (!connection.supported) { SetupCaption("这台设备的系统版本不支持后台独立屏。执行模式中的手机主屏仍需你另行明确选择。"); return }
    if (!connection.wirelessAvailable) { SetupCaption("此安装包尚未接入手机内无线配对。可查看其他连接方式；不会把准备中的能力显示为可用。"); return }
    if (connection.taskOwned) { SetupCaption(adb.optString("message")); return }
    if (connection.helperConnected && connection.backend == "wireless_adb" && !state.feature("display").optBoolean("releasePending") && !state.feature("display").optBoolean("recoveryRequired")) {
        SetupCaption(adb.optString("message"))
        if (adb.optBoolean("helperAuthenticated")) SetupCaption("手机内启动器本次认证已完成。")
        return
    }
    SetupHeading(if (connecting) "最后一步 · 完成连接" else "第一步 · 开启系统连接开关")
    SetupCaption(if (connecting) "配对后回到系统无线调试主页，使用页面上的连接端口。它与配对窗口的端口不同。" else "在开发者选项中开启“无线调试”，再打开“使用配对码配对设备”。保持配对窗口打开；切回时若窗口关闭，可使用系统分屏，重新读取当前码。")
    DeveloperOptionsGuidance("无线调试与配对")
    OutlinedButton(onClick = { code = ""; controller.openWirelessSettings() }, enabled = !connection.taskOwned && !connection.busy) { Text("前往系统设置", fontSize = 12.sp) }
    SetupCaption("只连接这台手机的当前地址。无线调试由 Android 系统开关控制；停止 PocketWorld 助手后，若不再需要调试，请在系统设置中关闭无线调试。")
    if (connection.requiresConnectionCheck && !connection.busy) SetupCaption("上次请求的结果尚未确认。先连接核对原助手，不重复提交配对码。")
    val kind = if (connecting) "connect" else "pairing"
    val endpoints = wirelessEndpoints(discovery, kind)
    var selected by remember(kind) { mutableStateOf<String?>(null) }
    var manual by remember(kind) { mutableStateOf(false) }
    var portText by remember(kind) { mutableStateOf("") }
    LaunchedEffect(endpoints.map { it.key }, kind) { if (endpoints.none { it.key == selected }) selected = endpoints.singleOrNull()?.key }
    val endpoint = endpoints.find { it.key == selected }
    val port = if (manual) wirelessPort(portText) else endpoint?.port
    val host = if (manual) "127.0.0.1" else endpoint?.host ?: "127.0.0.1"
    LaunchedEffect(host, port) { code = ""; controller.cancelPairingNotification() }
    SetupHeading(if (connecting) "连接端口" else "当前配对窗口")
    if (!manual && endpoints.size == 1) SetupCaption(if (connecting) "已发现这台手机的连接服务。" else "已发现这台手机的配对窗口。")
    if (!manual && endpoints.size > 1) endpoints.forEachIndexed { index, item -> Row(Modifier.fillMaxWidth().clickable { selected = item.key }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == item.key, { selected = item.key }); Text("本机服务 " + (index + 1) + " · 端口 " + item.port, fontSize = 12.sp) } }
    if (!manual && endpoints.isEmpty()) SetupCaption(if (discovery?.optBoolean("discovering") == true) "正在查找本机服务。保持系统无线调试打开。" else "暂未发现本机服务。打开系统页面后再查找。")
    if (endpoints.isEmpty() && discovery?.optString("errorCode").orEmpty().isNotBlank()) SetupCaption(discovery?.optString("message").orEmpty())
    Row { TextButton(onClick = { controller.display("adb.discover") }, enabled = !connection.taskOwned && "display.adb.discover" !in state.moduleBusy) { Text("重新查找本机服务", fontSize = 12.sp) }; TextButton(onClick = { manual = !manual; portText = "" }, enabled = !connection.taskOwned && !connection.busy) { Text(if (manual) "使用自动发现" else "未找到？填写端口", fontSize = 12.sp) } }
    if (manual) {
        SetupCaption("只输入这台手机系统页面显示的端口，不填写其他设备的地址。关闭页面后端口可能改变。")
        OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, label = { Text(if (connecting) "无线调试主页的连接端口" else "配对窗口里的端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = portText.isNotBlank() && wirelessPort(portText) == null, enabled = !connection.taskOwned && !connection.busy)
    }
    if (!connecting) {
        OutlinedTextField(code, { code = it.filter { char -> char in '0'..'9' }.take(6) }, label = { Text("系统窗口当前的六位配对码") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = connection.canPair)
        Button(onClick = { val currentCode = code; code = ""; port?.let { pairPort = it; controller.pairWireless(host, it, currentCode) } }, enabled = connection.canPair && port != null && wirelessPairCodeValid(code), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("确认配对并继续") }
        val pairing = state.feature("phone_setup").optJSONObject("pairing")
        if (pairing != null) {
            if (pairing.optBoolean("active")) {
                SetupCaption("通知输码已开启。保持系统配对窗口打开，从通知输入当前六位码；两分钟后失效，配对后仍需完成连接。")
                TextButton(onClick = controller::cancelPairingNotification, enabled = "phone_setup.pairing.cancel" !in state.moduleBusy) { Text("取消这次通知输码", fontSize = 12.sp) }
            } else TextButton(onClick = { code = ""; port?.let { pairPort = it; controller.beginPairingNotification(host, it) } },
                enabled = connection.canPair && port != null && "phone_setup.pairing.begin" !in state.moduleBusy) { Text("改用通知输入配对码", fontSize = 12.sp) }
            if (pairing.optString("lastOutcome") == "expired") SetupCaption("上次通知已过期，请重新读取当前配对窗口再开启。")
            if (pairing.optString("lastOutcome") == "source_changed") SetupCaption("连接来源已改变，原通知已失效。请核对当前端口后重新开启。")
        }
        TextButton(onClick = { code = ""; controller.cancelPairingNotification(); connecting = true }, enabled = !connection.busy && !connection.taskOwned) { Text(if (connection.identityPresent) "使用已保存身份连接" else "此前配对过？直接连接", fontSize = 12.sp) }
    } else {
        if (port != null && port == pairPort) Text("这是刚才的配对端口。请读取无线调试主页的另一连接端口。", color = Danger, fontSize = 12.sp, lineHeight = 20.sp)
        Button(onClick = { port?.let { controller.connectWireless(host, it, pairPort) } }, enabled = connection.canConnect && port != null && port != pairPort, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(if (connection.requiresConnectionCheck) "恢复并核对原连接" else "完成手机连接") }
        TextButton(onClick = { code = ""; connecting = false }, enabled = connection.canPair) { Text("重新读取配对窗口", fontSize = 12.sp) }
    }
    SetupCaption(adb.optString("message"))
    if ((connection.busy || connection.recoveryRequired) && !connection.taskOwned) TextButton(onClick = { code = ""; controller.display("adb.cancel") }, enabled = "display.adb.cancel" !in state.moduleBusy) { Text("停止本次连接与启动", fontSize = 12.sp) }
}

@Composable
private fun ShizukuSetup(state: PocketUiState, connection: BackgroundConnectionState, controller: PocketWorldController) {
    val shizuku = state.feature("display").optJSONObject("shizuku") ?: JSONObject()
    SetupHeading("使用 Shizuku 授权")
    SetupCaption(shizuku.optString("message", "仅适用于已安装并在使用 Shizuku 的高级用户；普通启用不需要它。"))
    val idle = connection.supported && !connection.taskOwned && !connection.busy
    if (!shizuku.optBoolean("permissionGranted")) OutlinedButton(onClick = { controller.display("shizuku.permission") }, enabled = idle && shizuku.optBoolean("binderAvailable") && !shizuku.optBoolean("permissionRequestPending")) { Text("请求 Shizuku 授权", fontSize = 12.sp) }
    else Button(onClick = { controller.display("shizuku.start") }, enabled = idle && !connection.displayBarrier && !shizuku.optBoolean("userServiceBound")) { Text("启动后台助手", fontSize = 12.sp) }
    if (shizuku.optBoolean("userServiceBound")) TextButton(onClick = { controller.display("shizuku.stop") }, enabled = idle) { Text("停止 Shizuku 助手", fontSize = 12.sp) }
}

@Composable
private fun ComputerAdbSetup(state: PocketUiState, connection: BackgroundConnectionState, controller: PocketWorldController) {
    SetupHeading("使用受信任电脑 ADB")
    SetupCaption("这是备用连接方式，需要电脑、debug 安装包和手机上的 ADB 授权。准备命令后在你信任的电脑执行，再核对真实助手连接。")
    DeveloperOptionsGuidance("USB 调试与这台受信任电脑的授权")
    SetupCaption("完成后可拔掉 USB 并关闭系统 USB 调试。停止本应用的助手不会代替系统关闭调试授权。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { controller.display("setup") }, enabled = connection.canChangeBackend) { Text("准备电脑命令", fontSize = 12.sp) }; OutlinedButton(onClick = { controller.display("connect") }, enabled = !connection.taskOwned && !connection.busy && connection.backend == "adb") { Text("核对助手连接", fontSize = 12.sp) } }
    state.setupCommands.forEach { command -> SelectionContainer { Text(command, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) } }
}
@Composable
private fun DeveloperOptionsGuidance(required: String) {
    SetupCaption("本次只需 $required。不要更改“以 16 KB 页面大小启动”、OEM 解锁、引导加载程序或清除数据选项。若系统提示擦除数据、恢复出厂设置或解锁设备，请取消并返回本页；这些操作不属于本次启用步骤。")
}
@Composable private fun SetupCaption(text: String) { if (text.isNotBlank()) Text(text, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)) }
@Composable private fun SetupHeading(text: String) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp)) }


@Composable
private fun BackgroundSetupProgress(connection: BackgroundConnectionState, accessibility: Boolean, notifications: Boolean) {
    val steps = backgroundSetupSteps(connection, accessibility, notifications)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            steps.forEachIndexed { index, step ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text(if (step.complete) "✓" else (index + 1).toString(), style = MaterialTheme.typography.labelLarge, color = if (step.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(step.title, style = MaterialTheme.typography.titleSmall)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = if (step.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
