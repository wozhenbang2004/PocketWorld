package dev.pocketengine.ui

import dev.pocketengine.features.proxy.accounts.ChatGptRecovery
import org.json.JSONObject

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
