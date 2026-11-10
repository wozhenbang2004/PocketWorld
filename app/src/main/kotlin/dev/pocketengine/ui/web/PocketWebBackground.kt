package dev.pocketengine.ui.web

import dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport
import dev.pocketengine.phone.setup.OwnerPhoneApps
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.backgroundConnectionState
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Owner phone settings in the single workspace. Android retains all device authority. */
internal class PocketWebBackground(
    private val controller: PocketWorldController,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val changed: () -> Unit
) {
    private var generation = 0L
    private var handleWindow: PocketWebWindowTicket? = null
    private var listed: List<OwnerPhoneApps.Selection>? = null
    private val selections = linkedMapOf<String, OwnerPhoneApps.Selection>()
    private var diagnostic: Pair<String, PhoneDiagnosticReport.Report>? = null
    private var audioBusy = false
    private var diagnosticBusy = false
    private var diagnosticRequest = 0L

    companion object {
        val COMMANDS = setOf(
            "background.audio.set", "background.audio.restore",
            "background.apps.read", "background.apps.set",
            "background.clipboard.consent", "background.clipboard.restore",
            "background.recovery.consent", "background.recovery.registration.consent", "background.recovery.restore", "background.recovery.compensate",
            "background.pairing.begin", "background.pairing.cancel",
            "background.shizuku.permission", "background.shizuku.start", "background.shizuku.stop",
            "background.adb.setup", "background.adb.check", "background.adb.forget", "background.release",
            "background.diagnostics.preview", "background.diagnostics.copy", "background.diagnostics.share",
            "background.practice.read", "background.practice.open", "background.practice.start", "background.practice.field"
        )
    }

    private fun visible() = controller.webPrivateOwnerVisible() && controller.state.backgroundSetupOpen

    fun clear() {
        generation++; diagnosticRequest++; handleWindow = null
        listed = null; selections.clear(); diagnostic = null; diagnosticBusy = false
        // Keep the audio operation busy until its original callback settles; clearing a view is not cancellation.
    }

    private fun currentWindow(): PocketWebWindowTicket {
        val current = captureWindow()
        if (handleWindow != null && handleWindow != current) clear()
        handleWindow = current
        return current
    }

    fun snapshot(): Any {
        if (!visible()) { clear(); return JSONObject.NULL }
        currentWindow()
        if (controller.phoneOwnerAppsLoading) { listed = null; selections.clear() }
        else if (listed !== controller.phoneOwnerApps) {
            listed = controller.phoneOwnerApps
            selections.clear()
            listed?.forEach { selections[UUID.randomUUID().toString()] = it }
        }
        return JSONObject()
            .put("audioBusy", audioBusy).put("diagnosticBusy", diagnosticBusy)
            .put("appsLoading", controller.phoneOwnerAppsLoading).put("appsComplete", controller.phoneOwnerAppsComplete)
            .put("policyBusy", controller.phoneOwnerPolicyBusy).put("policyMessage", controller.phoneOwnerPolicyMessage ?: JSONObject.NULL)
            .put("apps", JSONArray().also { out -> selections.forEach { (handle, copy) -> out.put(JSONObject()
                .put("selectionHandle", handle).put("label", copy.label()).put("packageName", copy.packageName())
                .put("userId", copy.userId()).put("profileSerial", copy.profileSerial()).put("currentProfile", copy.currentProfile())
                .put("versionCode", copy.versionCode()).put("uid", copy.uid())) } })
            .put("setupCommands", JSONArray(controller.state.setupCommands))
            .put("practiceReading", controller.phonePracticeReading)
            .put("practiceError", controller.phonePracticeReadError ?: JSONObject.NULL)
            .put("practiceEvidence", PocketWebState.publicValue(controller.phonePracticeEvidence))
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (!visible()) { clear(); throw IllegalArgumentException("请重新打开主人手机设置页") }
        val window = currentWindow(); val originalGeneration = generation
        val connection = backgroundConnectionState(controller.state.feature("display"), controller.state.activeTaskId, controller.state.moduleBusy)
        val display = controller.state.feature("display")
        fun keys(vararg names: String) { require(args.keys().asSequence().toSet() == names.toSet()) { "手机设置参数不完整，请重新打开原操作" } }
        fun flag(name: String): Boolean { require(args.opt(name) is Boolean) { "请明确选择这项设置" }; return args.getBoolean(name) }
        fun string(name: String, max: Int = 128): String {
            val raw = args.opt(name)
            require(raw is String && raw.isNotBlank() && raw.length <= max && '\u0000' !in raw) { "请重新核对原$name" }
            return raw
        }
        fun revision(): Long {
            val raw = args.opt("expectedRevision")
            require((raw is Int || raw is Long) && (raw as Number).toLong() >= 0) { "请重新读取原设置版本" }
            return (raw as Number).toLong()
        }
        fun current() = originalGeneration == generation && acceptsWindow(window) && visible()
        fun idle() { require(!connection.taskOwned && !connection.busy) { "请等待原任务释放资源或本次连接完成" } }
        fun confirmed() { require(flag("confirmed")) { "请先核对并确认本次操作" } }
        when (action) {
            "background.audio.set", "background.audio.restore" -> {
                require(!audioBusy) { "请等待原声音操作回执" }
                if (action.endsWith(".set")) {
                    keys("enabled")
                    require(display.opt("audioSilenceEnabled") is Boolean && !connection.busy) { "请先刷新核对音频策略" }
                    require(!connection.taskOwned || !flag("enabled")) { "任务执行中只能停止后续静音" }
                } else { keys("confirmed"); confirmed(); idle(); require(connection.helperConnected) { "请先核对原后台助手连接" } }
                audioBusy = true; changed()
                val completed: (JSONObject?, String?) -> Unit = { result, error ->
                    audioBusy = false
                    if (current()) done(result, error)
                    else done(null, "原声音窗口已变化，请刷新核对实际结果")
                    changed()
                }
                try {
                    if (action.endsWith(".set")) controller.setBackgroundAudioSilence(flag("enabled"), completed)
                    else controller.restoreBackgroundAudio(completed)
                } catch (failure: Exception) { audioBusy = false; changed(); throw failure }
                return
            }
            "background.apps.read" -> {
                keys(); require(!controller.phoneOwnerAppsLoading && !controller.phoneOwnerPolicyBusy)
                listed = null; selections.clear(); controller.loadPhoneOwnerApps()
            }
            "background.apps.set" -> {
                keys("selectionHandle", "automation", "ownerShare", "expectedRevision")
                val selected = selections[string("selectionHandle")] ?: error("原应用选择已过期，请重新读取并选择")
                require(listed === controller.phoneOwnerApps && controller.phoneOwnerApps.any { it === selected }
                    && !controller.phoneOwnerAppsLoading && !controller.phoneOwnerPolicyBusy) { "原应用列表已变化，请重新选择" }
                val policy = display.optJSONObject("appAccess")
                require(policy?.optBoolean("available") == true) { "应用规则尚不可读" }
                val expected = revision(); require(expected == policy.optLong("revision", -1)) { "原应用规则已变化，请刷新后重新核对" }
                controller.setPhoneAppAccess(selected, flag("automation"), flag("ownerShare"), expected)
            }
            "background.clipboard.consent" -> { keys("enabled"); require(display.opt("clipboardPasteAllowed") is Boolean && "display.clipboard.consent" !in controller.state.moduleBusy); controller.phoneClipboardConsent(flag("enabled")) }
            "background.clipboard.restore" -> { keys(); idle(); require("display.clipboard.restore" !in controller.state.moduleBusy); controller.restorePhoneClipboard() }
            "background.recovery.consent" -> {
                keys("enabled", "expectedRevision")
                val recovery = controller.state.feature("phone_setup").optJSONObject("recovery") ?: error("请先读取原连接恢复设置")
                val expected = revision(); require(expected == recovery.optLong("revision", -1) && "phone_setup.recovery.consent" !in controller.state.moduleBusy) { "原连接恢复设置已变化，请刷新后重新核对" }
                controller.phoneSetup("recovery.consent", JSONObject().put("enabled", flag("enabled")).put("expectedRevision", expected))
            }
            "background.recovery.registration.consent" -> {
                keys("enabled", "expectedRevision", "scope")
                val recovery = controller.state.feature("phone_setup").optJSONObject("recovery") ?: error("请先读取原服务登记恢复设置")
                val expected = revision(); val enabled = flag("enabled")
                require(expected == recovery.optLong("revision", -1) && recovery.opt("registrationRestoreConsent") is Boolean
                    && "phone_setup.recovery.registration.consent" !in controller.state.moduleBusy) { "原服务登记恢复设置已变化，请刷新后重新核对" }
                require(string("scope") == dev.pocketengine.phone.setup.AccessibilityRecovery.REGISTRATION_SCOPE) { "请明确选择恢复本应用服务登记" }
                if (enabled) require(recovery.optBoolean("consent") && recovery.optBoolean("secureSettingsGranted")
                    && recovery.optBoolean("userUnlocked") && recovery.optBoolean("systemRegistered")
                    && recovery.optBoolean("accessibilityEnabled")) { "请先在系统中启用本应用服务，并核对已经授予的安全系统设置权限" }
                controller.phoneSetup("recovery.registration.consent", JSONObject().put("enabled", enabled)
                    .put("expectedRevision", expected).put("scope", dev.pocketengine.phone.setup.AccessibilityRecovery.REGISTRATION_SCOPE))
            }
            "background.recovery.restore", "background.recovery.compensate" -> {
                keys(); val command = action.removePrefix("background.")
                val recovery = controller.state.feature("phone_setup").optJSONObject("recovery") ?: error("请先读取原连接恢复设置")
                require(!recovery.optBoolean("busy") && "phone_setup.$command" !in controller.state.moduleBusy
                    && recovery.optBoolean(if (command == "recovery.restore") "consent" else "compensationPending")) { "请先核对原连接恢复状态" }
                controller.phoneSetup(command)
            }
            "background.pairing.begin" -> {
                keys("host", "port"); require(connection.canPair && controller.state.notificationsEnabled) { "请先允许通知并核对本次配对状态" }
                val port = args.opt("port"); require(port is Number && port.toDouble() == port.toInt().toDouble() && port.toInt() in 1..65535)
                require(string("host") == "127.0.0.1") { "只能为这台手机开启通知输码" }
                controller.beginPairingNotification("127.0.0.1", port.toInt())
            }
            "background.pairing.cancel" -> { keys(); controller.cancelPairingNotification() }
            "background.shizuku.permission" -> {
                keys(); idle(); val shizuku = display.optJSONObject("shizuku")
                require(connection.supported && shizuku?.optBoolean("binderAvailable") == true && !shizuku.optBoolean("permissionRequestPending")) { "请先启动已有的 Shizuku 服务" }
                controller.display("shizuku.permission")
            }
            "background.shizuku.start" -> {
                keys(); idle(); require(connection.supported && !connection.displayBarrier && display.optJSONObject("shizuku")?.optBoolean("permissionGranted") == true) { "请先释放原连接并授权 Shizuku" }
                controller.display("shizuku.start")
            }
            "background.shizuku.stop" -> { keys(); idle(); controller.display("shizuku.stop") }
            "background.adb.setup" -> { keys(); require(connection.canChangeBackend) { "请先释放原连接" }; controller.display("setup") }
            "background.adb.check" -> { keys(); idle(); require(connection.backend == "adb"); controller.display("connect") }
            "background.adb.forget" -> { keys("confirmed"); confirmed(); require(connection.canForget) { "请先等待启动进程退出并释放原连接" }; controller.display("adb.forget") }
            "background.release" -> { keys(); idle(); controller.display("release") }
            "background.diagnostics.preview" -> {
                keys(); require(!diagnosticBusy) { "正在读取诊断" }
                diagnostic = null; diagnosticBusy = true; changed()
                val request = ++diagnosticRequest
                try {
                    controller.previewPhoneDiagnostics { report, error ->
                        if (request == diagnosticRequest) diagnosticBusy = false
                        if (request == diagnosticRequest && current() && report != null && error == null) {
                            val handle = UUID.randomUUID().toString(); diagnostic = handle to report
                            done(JSONObject().put("reportHandle", handle).put("text", report.text()), null)
                        } else done(null, error ?: "原诊断窗口已变化，请重新预览")
                        changed()
                    }
                } catch (failure: Exception) { if (request == diagnosticRequest) diagnosticBusy = false; changed(); throw failure }
                return
            }
            "background.diagnostics.copy", "background.diagnostics.share" -> {
                keys("reportHandle")
                val report = diagnostic?.takeIf { it.first == string("reportHandle") }?.second ?: error("请重新预览这份诊断")
                val copying = action.endsWith(".copy")
                val failure = if (copying) controller.copyPhoneDiagnostics(report) else controller.sharePhoneDiagnostics(report)
                done(if (failure == null) JSONObject().put("copied", copying).put("shareChooserRequested", !copying) else null, failure)
                return
            }
            "background.practice.read" -> { keys(); require(!controller.phonePracticeReading); controller.readPhonePracticeEvidence() }
            "background.practice.open" -> {
                keys("taskId"); val taskId = string("taskId")
                require(controller.phonePracticeEvidence?.optBoolean("ok") == true && controller.phonePracticeEvidence?.optString("taskId") == taskId) { "原演练记录已变化，请重新读取" }
                controller.closeBackgroundSetup(); controller.focusTask(taskId)
            }
            "background.practice.start", "background.practice.field" -> {
                if (action.endsWith(".field")) keys("mode", "packageName", "field", "confirmed") else keys("mode", "confirmed")
                confirmed(); idle(); require(!controller.state.phoneSubmitting) { "请等待原演练提交结果" }
                val mode = string("mode"); require(mode in setOf("background", "main_screen"))
                require(mode != "background" || connection.helperConnected && !connection.requiresConnectionCheck) { "请先完成并核对原连接" }
                if (action.endsWith(".field")) {
                    val name = string("packageName", 255); val field = string("field", 120)
                    require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && field.length <= 120) { "请填写实际包名和可安全测试的普通输入框名称" }
                    controller.startPhonePractice(name, field, mode)
                } else controller.startOwnedPhonePractice(mode)
            }
            else -> error("未知的手机设置操作")
        }
        // Async device operations publish their real state/receipt through the existing controller.
        done(JSONObject().put("accepted", true), null)
    }
}
