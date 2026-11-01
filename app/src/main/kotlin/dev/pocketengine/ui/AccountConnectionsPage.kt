package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import org.json.JSONObject

/** UI request identity only. No credential, URL, account copy or optimistic connection state. */
internal data class AccountConnectionsRequest(
    val requestId: String,
    val command: String,
    val accountId: String,
    val expectedVersion: Long,
    val sessionId: String
)

internal data class AccountConnectionsLogout(val accountId: String, val version: Long, val label: String)

internal fun accountConnectionsRows(snapshot: JSONObject): List<JSONObject> {
    val rows = snapshot.optJSONArray("accounts") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
}

internal fun accountConnectionsRow(snapshot: JSONObject, id: String): JSONObject? =
    accountConnectionsRows(snapshot).firstOrNull { it.opt("id") is String && it.optString("id") == id }

private fun accountConnectionsNumber(row: JSONObject?, key: String): Long {
    val value = row?.opt(key)
    return if (value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() >= 0) value.toLong() else -1
}

internal fun accountConnectionsVersion(row: JSONObject?): Long = accountConnectionsNumber(row, "version")

/** The host must enforce this same version atomically; checking it in Compose is not admission. */
internal fun accountConnectionsPayload(command: String, row: JSONObject, enabled: Boolean, sessionId: String): JSONObject {
    val id = row.optString("id")
    val version = accountConnectionsVersion(row)
    require(id.isNotBlank() && version >= 0) { "账号版本不可用，请重新读取。" }
    require(command in setOf("beginLogin", "cancelLogin", "select", "setEnabled", "refresh", "models", "logout", "acknowledgePlanWelcome", "retryPlanUsage"))
    return JSONObject().put("accountId", id).put("expectedVersion", version).also {
        if (command == "beginLogin") it.put("label", row.optString("label"))
        if (command == "setEnabled") it.put("enabled", enabled)
        if (command == "cancelLogin") {
            require(sessionId.isNotBlank())
            it.put("sessionId", sessionId)
        }
    }
}

/** Exact response identity and the host's echo of the reviewed input, including failures. */
internal fun accountConnectionsEnvelope(request: AccountConnectionsRequest?, envelope: JSONObject?): JSONObject? {
    if (request == null || request.requestId.isBlank() || envelope == null || envelope.opt("requestId") !is String ||
        envelope.optString("requestId") != request.requestId || envelope.opt("ok") !is Boolean) return null
    if (request.accountId.isNotEmpty() && (envelope.optString("accountId") != request.accountId ||
            accountConnectionsNumber(envelope, "expectedVersion") != request.expectedVersion)) return null
    return envelope
}

internal fun accountConnectionsCanUse(row: JSONObject?, now: Long): Boolean = row != null &&
    accountConnectionsVersion(row) >= 0 && row.optBoolean("enabled") && row.optBoolean("planUsageEnabled") &&
    !row.optBoolean("usageLimited") && row.optString("status") in setOf("connected", "cooldown") && row.optLong("cooldownUntil") <= now

/** Only an actual, current pending native session can be handed back to the host browser opener. */
internal fun accountConnectionsAuthorization(snapshot: JSONObject, now: Long): String? {
    val login = snapshot.optJSONObject("login") ?: return null
    val id = login.optString("sessionId")
    val row = accountConnectionsRow(snapshot, login.optString("accountId")) ?: return null
    return id.takeIf { it.isNotBlank() && login.optString("status") == "pending" && login.optLong("expiresAt") > now &&
        row.optBoolean("enabled") && row.optString("status") == "authorizing" && accountConnectionsVersion(row) >= 0 }
}

/** Rebuilds and page re-entry have no originating request, so never reopen the browser automatically. */
internal fun accountConnectionsBrowserRequest(request: AccountConnectionsRequest?, envelope: JSONObject?, snapshot: JSONObject, now: Long): String? {
    if (request?.command != "beginLogin") return null
    val result = accountConnectionsEnvelope(request, envelope)?.takeIf { it.optBoolean("ok") }?.optJSONObject("result") ?: return null
    val session = accountConnectionsAuthorization(snapshot, now) ?: return null
    return session.takeIf { result.optString("status") == "pending" && result.optString("sessionId") == session &&
        result.optString("accountId") == snapshot.optJSONObject("login")?.optString("accountId") }
}

/** Catalog revision is the version actually used by native I/O, not a version invented after the response. */
internal fun accountConnectionsCatalog(request: AccountConnectionsRequest?, envelope: JSONObject?, snapshot: JSONObject, now: Long): JSONObject? {
    if (request?.command != "models" || snapshot.optBoolean("closed")) return null
    val reply = accountConnectionsEnvelope(request, envelope)?.takeIf { it.optBoolean("ok") } ?: return null
    val result = reply.optJSONObject("result") ?: return null
    val row = accountConnectionsRow(snapshot, request.accountId) ?: return null
    if (!accountConnectionsCanUse(row, now) || result.optString("accountId") != request.accountId ||
        accountConnectionsNumber(result, "accountVersion") != accountConnectionsVersion(row) ||
        result.optLong("accountVersion", -1) < request.expectedVersion || result.optJSONArray("models") == null) return null
    return result
}

/** Copy only the host-approved reference fields. Missing, mismatched or stale references are not selectable. */
internal fun accountConnectionsModelReference(request: AccountConnectionsRequest?, envelope: JSONObject?, snapshot: JSONObject, modelId: String, now: Long): JSONObject? {
    val catalog = accountConnectionsCatalog(request, envelope, snapshot, now) ?: return null
    val row = accountConnectionsRow(snapshot, catalog.optString("accountId")) ?: return null
    val models = catalog.getJSONArray("models")
    val matches = (0 until models.length()).mapNotNull { models.optJSONObject(it) }.filter { it.optString("id") == modelId }
    if (matches.size != 1 || !modelId.matches(Regex("[A-Za-z0-9_.:-]{1,160}"))) return null
    val ref = matches.single().optJSONObject("nativeRef") ?: return null
    if (ref.opt("provider") !is String || ref.optString("provider") != row.optString("provider") || ref.optString("provider") != "chatgpt_plan" ||
        ref.opt("accountId") !is String || ref.optString("accountId") != row.optString("id") ||
        accountConnectionsNumber(ref, "accountVersion") != accountConnectionsVersion(row) ||
        ref.opt("modelId") !is String || ref.optString("modelId") != modelId) return null
    return JSONObject().put("provider", ref.getString("provider")).put("accountId", ref.getString("accountId"))
        .put("accountVersion", ref.getLong("accountVersion")).put("modelId", ref.getString("modelId"))
}

internal fun accountConnectionsLogoutPayload(draft: AccountConnectionsLogout, snapshot: JSONObject): JSONObject {
    val row = accountConnectionsRow(snapshot, draft.accountId)
    require(row != null && accountConnectionsVersion(row) == draft.version) { "账号已变化，请重新检查后确认。" }
    return accountConnectionsPayload("logout", row, false, "")
}

internal fun accountConnectionsFailure(code: String): String {
    ChatGptRecovery.message(code).takeIf { it.isNotBlank() }?.let { return it }
    return when (code) {
    "PLAN_USAGE_PERMISSION_REQUIRED" -> "账号尚未授权使用套餐模型。请在授权页面确认权限；登录成功不等于可以推理。"
    "ACCOUNT_COOLDOWN" -> "服务商要求稍后重试。冷却结束前不会继续请求模型。"
    "STALE_ACCOUNT_VERSION", "STALE_LOGIN_SESSION" -> "账号或授权会话已变化，请重新读取状态后再操作。"
    "AUTHORIZATION_DECLINED" -> "授权未获同意。没有建立新的连接，可以重新授权或返回。"
    "LOGIN_EXPIRED" -> "授权已过期，请重新发起。"
    "CANCELLED" -> "本次请求已取消；已经发生的远端操作不会因此撤回。"
    "REAUTHORIZATION_REQUIRED", "CREDENTIALS_UNAVAILABLE", "ACCOUNT_IDENTITY_MISMATCH" -> "需要重新授权。请使用此记录对应的账号登录。"
    "ACCOUNT_DISABLED" -> "此账号已停用。启用后仍需核对授权状态。"
    "GATEWAY_BUSY", "ACCOUNT_BUSY", "REFRESH_QUEUE_FULL" -> "账号服务正在处理其他请求，请稍后重试。"
    "LOGIN_ALREADY_PENDING" -> "已有授权进行中，请先继续或取消当前授权。"
    "PROCESS_INTERRUPTED" -> "上次授权被中断，请重新发起。"
    "ACCOUNT_LIMIT" -> "已达到本机账号数量上限。"
    "INVALID_ACCOUNT_LABEL" -> "请填写 1–80 个字符的账号名称。"
    "REMOTE_REVOCATION_UNCONFIRMED" -> "尚未确认远端授权已撤销，请到服务商账号设置中核对。"
    "ACCOUNT_NOT_FOUND" -> "账号记录已不存在，请重新读取状态。"
    "GATEWAY_CLOSED", "ACCOUNT_GATEWAY_UNAVAILABLE" -> "本机账号服务暂不可用，请返回后重试。"
    "INVALID_MODEL_CATALOG" -> "服务商返回的模型目录不可用，请稍后重新读取。"
    else -> "请求未完成，请重新读取状态后重试。详细信息以原生服务的安全诊断为准。"
    }
}

private fun accountConnectionsError(envelope: JSONObject): String {
    val value = envelope.opt("error")
    return accountConnectionsFailure(if (value is JSONObject) value.optString("code") else value as? String ?: "")
}

/** A receipt is descriptive, never a second account authority or an optimistic model selection. */
internal fun accountConnectionsReceipt(request: AccountConnectionsRequest?, envelope: JSONObject?, snapshot: JSONObject): String? {
    val reply = accountConnectionsEnvelope(request, envelope) ?: return null
    request ?: return null
    val row = accountConnectionsRow(snapshot, request.accountId)
    if (!reply.optBoolean("ok")) {
        if (request.accountId.isNotEmpty() && accountConnectionsVersion(row) != request.expectedVersion) return "账号已变化，旧请求的回执未用于当前状态。请重新读取。"
        return accountConnectionsError(reply)
    }
    val result = reply.optJSONObject("result") ?: return "请求已返回，结果格式不可用。请重新读取状态。"
    return when (request.command) {
        "snapshot" -> if (result.optJSONArray("accounts") != null) "已读取本机账号状态。" else null
        "beginLogin", "cancelLogin" -> {
            val login = snapshot.optJSONObject("login") ?: return null
            if (result.optString("sessionId").isBlank() || result.optString("sessionId") != login.optString("sessionId") ||
                result.optString("accountId") != login.optString("accountId") || result.optString("status") != login.optString("status")) return null
            if (request.command == "cancelLogin" && result.optString("sessionId") != request.sessionId) return null
            when (result.optString("status")) {
                "pending" -> "请在系统浏览器用 ChatGPT 完成登录。返回应用后会显示真实连接结果。"
                "cancelled" -> "授权已取消；未把本次请求标为已连接。"
                else -> null
            }
        }
        "logout" -> if (result.optString("accountId") == request.accountId && result.optBoolean("signedOutLocally") &&
            row?.optString("status") == "signed_out" && accountConnectionsVersion(row) > request.expectedVersion &&
            accountConnectionsNumber(result, "accountVersion") == accountConnectionsVersion(row))
            "已在本机退出登录，账号记录仍保留。" +
                (if (result.optBoolean("credentialCleanupRequired")) " 本机凭据清理尚未完成。" else "") +
                (if (result.optBoolean("remoteRevocationConfirmed")) " 服务商已确认撤销授权。" else " 远端撤销尚未确认，请到服务商设置核对。") else null
        "select", "setEnabled", "refresh", "acknowledgePlanWelcome", "retryPlanUsage" -> {
            val returned = accountConnectionsRow(result, request.accountId) ?: return null
            if (row == null || accountConnectionsVersion(returned) != accountConnectionsVersion(row) ||
                accountConnectionsVersion(row) < request.expectedVersion || returned.optString("status") != row.optString("status") ||
                returned.optBoolean("enabled") != row.optBoolean("enabled")) return null
            when (request.command) {
                "select" -> if (result.optString("selectedAccountId") == request.accountId && snapshot.optString("selectedAccountId") == request.accountId) "已设为当前账号。模型仍需单独选择。" else null
                "acknowledgePlanWelcome" -> "已保存套餐使用说明，不会自动发送模型请求。"
                "retryPlanUsage" -> "已允许手动再试；这不代表用量限制已解除。请返回原任务核对后继续。"
                "setEnabled" -> if (row.optBoolean("enabled")) "账号已启用；可用性仍以授权和套餐权限为准。" else "账号已停用，不会自动重新授权。"
                else -> "原生服务已完成刷新，请核对下方账号状态。"
            }
        }
        else -> null
    }
}

@Composable
internal fun AccountConnectionsPage(
    snapshot: JSONObject,
    responses: Map<String, JSONObject>,
    pendingCommands: Set<String>,
    loading: Boolean,
    error: String?,
    onRequest: (command: String, payload: JSONObject) -> String,
    onOpenAuthorization: (authorizationSessionId: String) -> Unit,
    onSelectModel: (nativeModelReference: JSONObject) -> Unit,
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var openedSessions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var diagnosticsId by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var requests by remember { mutableStateOf<Map<String, AccountConnectionsRequest>>(emptyMap()) }
    var lastCommand by remember { mutableStateOf<String?>(null) }
    var completedRequests by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var logout by remember { mutableStateOf<AccountConnectionsLogout?>(null) }
    var modelQuery by remember(expandedId) { mutableStateOf("") }
    var modelOptions by remember { mutableStateOf<Pair<JSONObject, JSONObject>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rows = accountConnectionsRows(snapshot)
    val login = snapshot.optJSONObject("login")
    val activeLogin = login?.optString("status") in setOf("pending", "exchanging")
    val closed = snapshot.optBoolean("closed")
    val unresolved = requests.values.any { it.requestId !in completedRequests && accountConnectionsEnvelope(it, responses[it.command]) == null }
    val cancelBusy = "cancelLogin" in pendingCommands || requests["cancelLogin"]?.let { it.requestId !in completedRequests && accountConnectionsEnvelope(it, responses[it.command]) == null } == true
    val busy = loading || pendingCommands.isNotEmpty() || unresolved
    val hasSnapshot = snapshot.optJSONArray("accounts") != null
    val submit: (String, JSONObject) -> Unit = { command, payload ->
        try {
            val requestId = onRequest(command, payload)
            check(requestId.isNotBlank())
            requests = requests + (command to AccountConnectionsRequest(requestId, command, payload.optString("accountId"), payload.optLong("expectedVersion", -1), payload.optString("sessionId")))
            lastCommand = command
            localMessage = null
        } catch (_: Exception) { localMessage = "请求未能提交。请重新读取状态，不要重复执行不确定的操作。" }
    }
    val requestRow: (String, JSONObject, Boolean, String) -> Unit = { command, row, enabled, session ->
        try { submit(command, accountConnectionsPayload(command, row, enabled, session)) }
        catch (_: Exception) { localMessage = "账号记录已变化，请重新读取后再操作。" }
    }
    LaunchedEffect(Unit) { submit("snapshot", JSONObject()) }
    LaunchedEffect(requests, responses) {
        completedRequests = completedRequests + requests.values.filter { accountConnectionsEnvelope(it, responses[it.command]) != null }.map { it.requestId }
    }
    // Only the response to this visible login click can trigger the browser. Re-entry never replays it.
    LaunchedEffect(requests["beginLogin"], responses["beginLogin"], snapshot.toString()) {
        val request = requests["beginLogin"]
        val session = accountConnectionsBrowserRequest(request, responses["beginLogin"], snapshot, System.currentTimeMillis())
        if (session != null && session !in openedSessions) {
            openedSessions = openedSessions + session
            try { onOpenAuthorization(session) } catch (_: Exception) { localMessage = "无法打开系统浏览器，请点击继续登录或取消。" }
        }
    }
    LaunchedEffect(snapshot.toString()) {
        now = System.currentTimeMillis()
        val deadlines = (rows.map { it.optLong("cooldownUntil") } + listOf(login?.optLong("expiresAt") ?: 0)).filter { it > now }.distinct().sorted()
        for (deadline in deadlines) {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(1))
            now = System.currentTimeMillis()
        }
    }
    BackHandler { if (logout != null) logout = null else if (adding) adding = false else onBack() }

    Column(Modifier.fillMaxSize().background(Color.White).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("ChatGPT 账号", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { submit("snapshot", JSONObject()) }, enabled = !loading && "snapshot" !in pendingCommands) { Text("刷新状态") }
        }
        HorizontalDivider(color = Line)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
            AccountConnectionsCaption("用 ChatGPT 登录并选择套餐可用模型。API key 连接继续保留；登录不会授予手机操作权限。")
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Ink, trackColor = Soft)
            AccountConnectionsCaption(snapshot.optString("mainInferenceMessage").ifBlank { "主模型连接能力尚未读取，请刷新状态后再选择。" })
            snapshot.optJSONObject("selectedModelRef")?.let { selected ->
                if (!snapshot.optBoolean("selectedModelUsable")) AccountConnectionsWarning("当前主模型引用已失效，请刷新模型目录并重新选择，或在设置中明确切回 API 模型。")
                else if (snapshot.optString("mainModelRoute") == "account") {
                    val account = accountConnectionsRow(snapshot, selected.optString("accountId"))
                    Text("当前使用 · ChatGPT 套餐", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    AccountConnectionsCaption((account?.let { it.optString("email").ifBlank { it.optString("label") } } ?: "") + " · " + selected.optString("modelId"))
                    AccountConnectionsCaption(if (snapshot.optBoolean("mainInferenceSupportsImages")) "此账号模型已确认支持图片；仍需机主开启发送和原任务授权。" else "图片能力未知或未获支持，截图不会发送。")
                    TextButton(onClick = { try { uriHandler.openUri(ChatGptRecovery.MANAGE_USAGE_URL) } catch (_: Exception) { localMessage = "无法打开 Manage usage，请在 ChatGPT 设置中查看用量。" } }) { Text("Manage usage · 管理用量") }
                }
            }
            snapshot.optString("modelSelectionError").takeIf { it.isNotBlank() }?.let { AccountConnectionsWarning(it) }
            if (closed) AccountConnectionsWarning("本机账号服务已关闭。请返回后重新打开；当前记录不代表可用连接。")
            if (error != null) AccountConnectionsWarning(accountConnectionsFailure(error))
            if (snapshot.optBoolean("credentialCleanupRequired")) AccountConnectionsWarning("本机凭据清理尚未完成。退出登录不等于旧凭据已经从存储中清除，请保留此提示并检查原生服务诊断。")
            localMessage?.let { AccountConnectionsCaption(it) }
            lastCommand?.let { command -> accountConnectionsReceipt(requests[command], responses[command], snapshot)?.let { AccountConnectionsCaption(it) } }
            if (!hasSnapshot && !loading) AccountConnectionsWarning("账号状态尚未读取。请点击“刷新状态”，读取成功前不会发起授权或修改账号。")

            if (login != null && activeLogin) {
                Surface(color = Soft, shape = Rounded, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("正在用 ChatGPT 登录", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        AccountConnectionsCaption(accountConnectionsRow(snapshot, login.optString("accountId"))?.optString("label").orEmpty())
                        AccountConnectionsCaption(if (login.optString("status") == "exchanging") "正在确认服务商授权。返回页面或打开浏览器不代表登录成功。" else if (login.optLong("expiresAt") <= now) "此授权已过期，请读取最新状态后重新发起。" else "在系统浏览器完成登录。页面不接收密码、验证码或令牌。")
                        val session = accountConnectionsAuthorization(snapshot, now)
                        if (login.optString("status") == "pending") Button(onClick = {
                            accountConnectionsAuthorization(snapshot, System.currentTimeMillis())?.let {
                                try { onOpenAuthorization(it) } catch (_: Exception) { localMessage = "未能打开授权，请重新读取状态后重试。" }
                            }
                        }, enabled = session != null && !closed && !busy, modifier = Modifier.fillMaxWidth()) { Text("继续 ChatGPT 登录") }
                        TextButton(onClick = {
                            accountConnectionsRow(snapshot, login.optString("accountId"))?.let { requestRow("cancelLogin", it, false, login.optString("sessionId")) }
                        }, enabled = !cancelBusy && !closed && accountConnectionsRow(snapshot, login.optString("accountId")) != null) { Text("取消登录") }
                    }
                }
            } else if (login != null && login.optString("error").isNotBlank()) {
                AccountConnectionsCaption(accountConnectionsFailure(login.optString("error")))
            }

            if (rows.isEmpty() && hasSnapshot && !loading) {
                Text("还没有账号", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                AccountConnectionsCaption("点击“用 ChatGPT 登录”，在系统浏览器完成登录后返回此页。只在确认授权成功后显示已连接。")
            }
            rows.forEach { row ->
                val id = row.optString("id")
                val expanded = expandedId == id
                val validRow = id.isNotBlank() && accountConnectionsVersion(row) >= 0
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = validRow) { expandedId = if (expanded) null else id }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.optString("label").ifBlank { "未命名账号" }, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            if (row.optString("email").isNotBlank()) AccountConnectionsCaption(row.optString("email"))
                            AccountConnectionsCaption(accountConnectionsStatus(row) + if (snapshot.optString("selectedAccountId") == id) " · 当前账号" else "")
                        }
                        Text(if (expanded) "收起" else "管理", color = SecondaryInk, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                    if (expanded) {
                        if (row.optLong("cooldownUntil") > now) AccountConnectionsWarning("本机请求退避至：" + accountConnectionsDate(row.optLong("cooldownUntil")) + "。这不是套餐额度重置时间。")
                        if (row.optString("status") == "connected" && !row.optBoolean("planUsageEnabled")) AccountConnectionsWarning("已登录，但尚未授予套餐模型使用权限。当前不可选择模型，请重新授权并核对权限。")
                        if (row.optString("error").isNotBlank()) AccountConnectionsCaption(accountConnectionsFailure(row.optString("error")))
                        if (row.optBoolean("planUsageEnabled")) {
                            AccountConnectionsCaption("使用来源：ChatGPT 套餐。实际限制请以 ChatGPT 用量设置为准。")
                            if (!row.optBoolean("usageLimited")) TextButton(onClick = { try { uriHandler.openUri(ChatGptRecovery.MANAGE_USAGE_URL) } catch (_: Exception) { localMessage = "无法打开 Manage usage，请在 ChatGPT 设置中查看用量。" } }) { Text("Manage usage · 管理用量") }
                        }
                        if (row.optBoolean("usageLimited")) {
                            AccountConnectionsWarning("套餐或此应用的用量限制已到达，新的套餐请求已暂停。这里不推算余额或重置时间。")
                            Button(onClick = { try { uriHandler.openUri(ChatGptRecovery.MANAGE_USAGE_URL) } catch (_: Exception) { localMessage = "无法打开 Manage usage，请在 ChatGPT 设置中查看用量。" } }, modifier = Modifier.fillMaxWidth()) { Text("Manage usage · 管理用量") }
                            OutlinedButton(onClick = { requestRow("retryPlanUsage", row, false, "") }, enabled = !busy && !closed && validRow) { Text("已查看用量，允许手动再试") }
                        }
                        val diagnostics = row.optJSONObject("diagnostics")
                        if (diagnostics != null && diagnostics.optInt("httpStatus") > 0) {
                            TextButton(onClick = { diagnosticsId = if (diagnosticsId == id) null else id }) { Text(if (diagnosticsId == id) "收起诊断信息" else "诊断信息") }
                            if (diagnosticsId == id) AccountConnectionsCaption(listOf("HTTP " + diagnostics.optInt("httpStatus"), diagnostics.optString("providerCode"), diagnostics.optString("param"), diagnostics.optString("requestId"), diagnostics.optString("bodyShape")).filter { it.isNotBlank() }.joinToString(" · "))
                        }
                        if (row.optString("status") == "signed_out" && !row.optBoolean("remoteRevocationConfirmed")) AccountConnectionsCaption("当前已退出本机；远端授权撤销尚未确认。此记录仍保留，可重新连接。")
                        if (row.optLong("expiresAt") > 0) AccountConnectionsCaption("当前授权到期：" + accountConnectionsDate(row.optLong("expiresAt")))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("启用此账号", color = Ink, fontSize = 14.sp); AccountConnectionsCaption("关闭会停止新的账号请求，不会撤回已发生的远端操作。") }
                            Switch(checked = row.optBoolean("enabled"), onCheckedChange = { requestRow("setEnabled", row, it, "") }, enabled = !busy && !closed && validRow)
                        }
                        val usable = accountConnectionsCanUse(row, now) && !closed
                        if (usable && !activeLogin && !adding) Button(onClick = { requestRow("models", row, false, "") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if ("models" in pendingCommands) "正在读取模型" else "读取可用模型") }
                        else if (!activeLogin && !adding && (row.optString("status") in setOf("signed_out", "reauthorization_required") || !row.optBoolean("planUsageEnabled"))) Button(onClick = { requestRow("beginLogin", row, false, "") }, enabled = !busy && !closed && row.optBoolean("enabled") && validRow && row.optLong("cooldownUntil") <= now, modifier = Modifier.fillMaxWidth()) { Text("重新用 ChatGPT 登录") }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (snapshot.optString("selectedAccountId") != id) TextButton(onClick = { requestRow("select", row, false, "") }, enabled = !busy && usable) { Text("设为当前账号") }
                            TextButton(onClick = { requestRow("refresh", row, false, "") }, enabled = !busy && usable && !activeLogin) { Text("刷新授权") }
                        }
                        val modelRequest = requests["models"]?.takeIf { it.accountId == id }
                        val modelEnvelope = responses["models"]
                        val catalog = accountConnectionsCatalog(modelRequest, modelEnvelope, snapshot, now)
                        if (catalog != null) {
                            val models = catalog.getJSONArray("models")
                            Text("服务商可用模型", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                            AccountConnectionsCaption(if (snapshot.optBoolean("mainInferenceAvailable")) "选择后使用此账号模型的 ChatGPT 套餐。图片依当前目录证据和机主授权决定；不自动授权工具或执行手机任务。" else "选择后交由原生宿主重新核对，用于账号连接验证。尚未接入主模型推理，不会自动授权工具或执行手机任务。")
                            if (models.length() > 8) OutlinedTextField(value = modelQuery, onValueChange = { modelQuery = it }, label = { Text("筛选模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            val visibleModels = (0 until models.length()).mapNotNull { models.optJSONObject(it) }.filter { modelQuery.isBlank() || it.optString("displayName").contains(modelQuery, true) || it.optString("id").contains(modelQuery, true) }
                            if (models.length() == 0) AccountConnectionsCaption("服务商没有返回可用模型。请核对套餐权限，或稍后重新读取。")
                            else if (visibleModels.isEmpty()) AccountConnectionsCaption("没有匹配的模型，试试其他名称。")
                            visibleModels.forEach { model ->
                                val modelId = model.optString("id")
                                val ref = accountConnectionsModelReference(modelRequest, modelEnvelope, snapshot, modelId, now)
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(model.optString("displayName").ifBlank { modelId }, fontSize = 14.sp, color = Ink); AccountConnectionsCaption(modelId)
                                        val image = model.optJSONObject("imageInput")
                                        AccountConnectionsCaption(if (image?.optBoolean("known") == true) { if (image.optBoolean("value")) "图片输入：目录支持" else "图片输入：目录不支持" } else "图片输入：未知，不发送图片")
                                        if (ref == null) AccountConnectionsCaption("缺少有效原生引用，不能选择。") }
                                    TextButton(onClick = {
                                        accountConnectionsModelReference(modelRequest, modelEnvelope, snapshot, modelId, System.currentTimeMillis())?.let {
                                            modelOptions = JSONObject(model.toString()) to it
                                        }
                                    }, enabled = ref != null && !busy && !closed) { Text("选择") }
                                }
                                HorizontalDivider(color = Line)
                            }
                        } else if (modelRequest != null && accountConnectionsEnvelope(modelRequest, modelEnvelope)?.optBoolean("ok") == true) AccountConnectionsCaption("模型目录已过期或与账号版本不符，请重新读取，旧目录不能选择。")
                        TextButton(onClick = { logout = AccountConnectionsLogout(id, accountConnectionsVersion(row), row.optString("label")) }, enabled = !busy && !closed && validRow, modifier = Modifier.padding(top = 12.dp)) { Text("退出登录", color = Danger) }
                    }
                    HorizontalDivider(color = Line)
                }
            }
            if (!activeLogin && hasSnapshot) {
                if (adding) {
                    Text("给 ChatGPT 账号起个本机名称（可选）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(top = 24.dp))
                    OutlinedTextField(value = label, onValueChange = { if (it.length <= 80) label = it }, label = { Text("账号名称") }, placeholder = { Text("例如：我的个人账号") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    AccountConnectionsCaption("名称只用于本机辨认。点击登录后会打开系统浏览器；不要在这里填写密码或令牌。")
                    Button(onClick = { submit("beginLogin", JSONObject().put("label", label.trim().ifBlank { "我的 ChatGPT 账号" })); adding = false }, enabled = !busy && !closed, modifier = Modifier.fillMaxWidth()) { Text("用 ChatGPT 登录") }
                    TextButton(onClick = { adding = false }, enabled = !busy) { Text("取消添加") }
                } else if (rows.isEmpty()) Button(onClick = { submit("beginLogin", JSONObject().put("label", "我的 ChatGPT 账号")) }, enabled = !busy && !closed, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("用 ChatGPT 登录") }
                else OutlinedButton(onClick = { adding = true; expandedId = null }, enabled = !busy && !closed, modifier = Modifier.padding(top = 20.dp)) { Text("用其他 ChatGPT 账号登录") }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    modelOptions?.let { (model, reference) ->
        val actual = accountConnectionsRow(snapshot, reference.optString("accountId"))
        AccountModelOptions(model, reference, snapshot.optJSONObject("selectedModelOptions"),
            current = !busy && !closed && accountConnectionsCanUse(actual, now) && accountConnectionsVersion(actual) == reference.optLong("accountVersion"),
            onSelect = { selected -> onSelectModel(selected); localMessage = "模型与选项已提交，原生宿主会重新读取当前账号目录；请核对实际结果。" },
            onDismiss = { modelOptions = null })
    }
    val welcomeAccount = rows.firstOrNull { it.optBoolean("planUsageEnabled") && it.optBoolean("enabled") }
    if (snapshot.optBoolean("planWelcomeRequired") && welcomeAccount != null) {
        val acknowledge = { if (!busy && !closed) requestRow("acknowledgePlanWelcome", welcomeAccount, false, "") }
        AlertDialog(onDismissRequest = acknowledge, containerColor = Color.White,
            title = { Text("已通过 ChatGPT 登录") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("在本应用选择 ChatGPT 模型后，推理使用你的 ChatGPT 套餐，并受套餐及此应用的使用限制。API key 连接单独保留。", fontSize = 14.sp, lineHeight = 22.sp)
                Text("账号登录不授予手机操作权限。截图仍需你明确允许发送，且模型目录确认支持。", fontSize = 14.sp, lineHeight = 22.sp)
                TextButton(onClick = { try { uriHandler.openUri(ChatGptRecovery.MANAGE_USAGE_URL) } catch (_: Exception) { localMessage = "请在 ChatGPT 设置中查看用量。" } }) { Text("Manage usage · 管理用量") }
            } }, confirmButton = { TextButton(onClick = acknowledge, enabled = !busy && !closed) { Text("知道了") } })
    }
    logout?.let { draft ->
        val current = accountConnectionsRow(snapshot, draft.accountId)
        val currentVersion = accountConnectionsVersion(current) == draft.version
        AlertDialog(onDismissRequest = { logout = null }, containerColor = Color.White, title = { Text("退出这个账号？") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(draft.label, fontWeight = FontWeight.SemiBold)
                Text("将先停止本机使用并清理凭据，再尝试撤销远端授权。已发生的远端操作不会撤回，账号记录会保留。远端撤销和本机清理可能需要另行核对。", fontSize = 14.sp, lineHeight = 22.sp)
                if (!currentVersion) Text("账号已变化，请关闭此确认并重新检查。", color = Danger)
            }
        }, confirmButton = { TextButton(onClick = {
            try { submit("logout", accountConnectionsLogoutPayload(draft, snapshot)); logout = null }
            catch (_: Exception) { localMessage = "账号已变化，未提交退出。请重新检查。" }
        }, enabled = currentVersion && !busy && !closed) { Text("确认退出登录", color = Danger) } }, dismissButton = { TextButton(onClick = { logout = null }) { Text("保留账号") } })
    }
}

private fun accountConnectionsStatus(row: JSONObject): String {
    val status = when (row.optString("status")) {
        "connected" -> if (row.optBoolean("usageLimited")) "套餐请求已暂停 · 查看用量" else if (row.optBoolean("planUsageEnabled")) "已连接" else "已登录 · 无模型使用权限"
        "signed_out" -> "已退出登录"
        "authorizing" -> "授权中"
        "refreshing" -> "正在刷新授权"
        "reauthorization_required" -> "需要重新授权"
        "cooldown" -> "请求暂时退避"
        else -> "状态待核对"
    }
    return if (row.optBoolean("enabled")) status else "已停用 · $status"
}
private fun accountConnectionsDate(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
@Composable private fun AccountConnectionsCaption(value: String) { if (value.isNotBlank()) Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)) }
@Composable private fun AccountConnectionsWarning(value: String) { Text(value, color = Danger, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 8.dp)) }
