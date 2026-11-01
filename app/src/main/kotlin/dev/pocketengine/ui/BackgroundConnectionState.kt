package dev.pocketengine.ui

import org.json.JSONObject

internal data class WirelessEndpoint(val host: String, val port: Int, val kind: String) {
    val key: String get() = host + ":" + port
}
internal data class BackgroundConnectionState(
    val loaded: Boolean, val supported: Boolean, val wirelessAvailable: Boolean, val backend: String,
    val taskOwned: Boolean, val busy: Boolean, val displayBarrier: Boolean, val recoveryRequired: Boolean, val requiresConnectionCheck: Boolean,
    val helperConnected: Boolean, val screenEstablished: Boolean, val phase: String, val identityPresent: Boolean
) {
    val canPair: Boolean get() = supported && wirelessAvailable && !taskOwned && !busy && !displayBarrier && !recoveryRequired
    val canConnect: Boolean get() = supported && wirelessAvailable && !taskOwned && !busy && (!displayBarrier || backend == "wireless_adb")
    val canChangeBackend: Boolean get() = !taskOwned && !busy && !displayBarrier && !recoveryRequired
    val canForget: Boolean get() = supported && wirelessAvailable && !taskOwned && !busy && !displayBarrier
}
internal fun backgroundConnectionState(display: JSONObject, activeTaskId: String?, pending: Set<String> = emptySet()): BackgroundConnectionState {
    val adb = display.optJSONObject("adb")
    val helper = display.optBoolean("helperConnected")
    val screen = helper && display.optBoolean("sessionActive") && display.optBoolean("readyForTask") && display.optInt("displayId", -1) > 0 && display.optString("sessionId").isNotBlank()
    return BackgroundConnectionState(display.has("supportedAndroid"), display.optBoolean("supportedAndroid"), adb != null, display.optString("authorizationBackend"), display.optBoolean("taskOwned") || activeTaskId != null,
        display.optBoolean("bootstrapBusy") || adb?.optBoolean("busy") == true || adb?.optBoolean("processTerminationPending") == true || pending.any { it in setOf("display.adb.pair", "display.adb.connect", "display.adb.cancel", "display.adb.forget", "display.shizuku.start", "display.shizuku.stop", "display.setup", "display.release") },
        display.optBoolean("sessionActive") || display.optBoolean("releasePending") || display.optBoolean("recoveryRequired"), adb?.optBoolean("recoveryRequired") == true, adb?.optBoolean("recoveryRequired") == true || display.optBoolean("recoveryRequired") || display.optBoolean("releasePending"),
        helper, screen, adb?.optString("phase").orEmpty(), adb?.optBoolean("identityPresent") == true)
}
internal fun wirelessConnectStep(current: Boolean, connection: BackgroundConnectionState): Boolean = when {
    connection.busy -> current
    connection.requiresConnectionCheck || connection.phase == "paired" -> true
    connection.phase in setOf("needs_pairing", "identity_forgotten") -> false
    connection.identityPresent -> true
    else -> current
}
internal fun wirelessEndpoints(discovery: JSONObject?, kind: String, now: Long = System.currentTimeMillis()): List<WirelessEndpoint> = discovery?.optJSONArray(kind).objects().filter {
    it.optString("kind") == kind && it.optString("host").isNotBlank() && it.optInt("port") in 1..65535 && it.optLong("resolvedAt") in (now - 180_000)..(now + 30_000)
}.map { WirelessEndpoint(it.optString("host"), it.optInt("port"), kind) }.distinctBy { it.key }
internal fun wirelessPort(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..65535 && text.all(Char::isDigit) }
internal fun wirelessPairCodeValid(code: String): Boolean = code.length == 6 && code.all { it in '0'..'9' }
internal fun backgroundConnectionHeadline(connection: BackgroundConnectionState): String = when {
    connection.busy -> "正在处理本次连接"
    connection.requiresConnectionCheck -> "上次连接结果待核对"
    connection.screenEstablished -> "独立屏幕已建立"
    connection.helperConnected -> "后台助手已连接"
    !connection.loaded -> "正在读取后台连接"
    !connection.supported -> "后台独立屏需要 Android 14 或以上"
    connection.phase == "paired" -> "配对已确认，还需要连接助手"
    else -> "启用后台独立屏"
}
/** Pairing credentials exist only in the transient transport payload, never pending UI metadata. */
internal fun displayPendingMetadata(command: String, payload: JSONObject): JSONObject = JSONObject(payload.toString()).also { if (command == "adb.pair") it.remove("pairingCode") }

/** Fresh installs use the built-in path; opening setup never changes a saved backend. */
internal fun backgroundSetupMode(display: JSONObject): String = display.optString("authorizationBackend").takeIf { it in setOf("wireless_adb", "shizuku", "adb") } ?: "wireless_adb"

/** Read-only visible steps from the existing native connection snapshot; not another setup state machine. */
internal data class BackgroundSetupStep(val id: String, val title: String, val detail: String, val complete: Boolean, val attention: Boolean = false)
internal fun backgroundSetupSteps(connection: BackgroundConnectionState, accessibility: Boolean, notifications: Boolean): List<BackgroundSetupStep> {
    val permissions = accessibility && notifications
    val prepared = connection.loaded && connection.supported && permissions
    val alternate = connection.backend in setOf("shizuku", "adb")
    val identity = connection.identityPresent || connection.phase == "paired" || connection.helperConnected
    val verified = connection.helperConnected && !connection.busy && !connection.requiresConnectionCheck
    return listOf(
        BackgroundSetupStep("system", "系统准备", when {
            !connection.loaded -> "先读取真实系统与连接状态"
            !connection.supported -> "当前系统不支持后台独立屏；可另行明确选择主屏模式"
            !accessibility && !notifications -> "还需界面读取权限与任务通知"
            !accessibility -> "还需界面读取权限"
            !notifications -> "还需任务通知"
            else -> "所需权限已读取；授权不等于已经连接"
        }, prepared),
        BackgroundSetupStep("pair", if (alternate) "已选连接授权" else "本机配对身份", when {
            alternate -> if (connection.backend == "shizuku") "保留已选 Shizuku 兼容连接，不要求无线配对" else "保留受信任电脑 ADB 连接，不要求无线配对"
            connection.requiresConnectionCheck -> "先核对原连接，不重复提交配对码"
            connection.helperConnected -> "原连接已核对，无需重复配对"
            connection.phase == "paired" -> "这次配对已确认；还需连接助手"
            connection.identityPresent -> "已保存本机身份；连接时仍会核对是否有效"
            else -> "在系统当前配对窗口确认，不保存配对码"
        }, (if (alternate) verified else identity) && !connection.requiresConnectionCheck, connection.requiresConnectionCheck),
        BackgroundSetupStep("connect", "连接核对", when {
            connection.busy -> "本次连接或进程回收仍在进行"
            connection.requiresConnectionCheck -> "上次结果待核对；使用原连接恢复入口"
            verified -> "后台助手已连接；这不表示已开始任务"
            alternate -> "从下方高级兼容入口核对原连接，不更换后端"
            else -> "配对之后使用系统主页的另一连接端口"
        }, verified, connection.requiresConnectionCheck),
        BackgroundSetupStep("return", "返回原目标", when {
            connection.taskOwned -> "原任务持有资源；回原任务查看，不更换连接"
            !verified || !permissions -> "完成尚缺步骤后返回；本页不会启动目标"
            connection.screenEstablished -> "已有真实独立显示会话；任务是否继续由你在原目标确认"
            else -> "可返回确认原目标；独立屏在正式使用时另行建立"
        }, false)
    )
}
