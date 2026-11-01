package dev.pocketengine.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.NativePluginHost
import dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost
import org.json.JSONObject

/** OAuth is connection consent, not tool approval. Uses the original installer after native authorization. */
@Composable internal fun McpOAuthControls(
    host: NativePluginHost, config: McpInstallConfig, busy: Boolean,
    runWork: (Runnable) -> Unit, runControl: (Runnable) -> Unit,
    onUseConnection: (JSONObject) -> Unit
) {
    val opened = remember(host) { runCatching { host.newMcpOAuthPage() } }
    val page = opened.getOrNull()
    if (page == null) { Text("MCP 授权入口暂不可用：${opened.exceptionOrNull()?.message ?: "原宿主未恢复"}"); return }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var working by remember(page) { mutableStateOf(false) }
    var controlling by remember(page) { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var issuer by rememberSaveable(config.endpoint) { mutableStateOf("") }
    var clientId by rememberSaveable(config.endpoint) { mutableStateOf("") }
    var callbackPort by rememberSaveable(config.endpoint) { mutableStateOf("0") }
    var cimd by rememberSaveable(config.endpoint) { mutableStateOf(false) }
    var pending by remember(page) { mutableStateOf<NativeMcpOAuthHost.Review?>(null) }
    var shown by remember(page) { mutableStateOf(JSONObject()) }
    var snapshot by remember(page) { mutableStateOf(JSONObject()) }
    var authorizationOpen by remember(page) { mutableStateOf(false) }
    var error by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }; lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page.setForeground(false); lifecycle.removeObserver(observer); runCatching { runControl(Runnable { page.close() }) } }
    }
    fun <T> work(control: Boolean = false, action: () -> T, accept: (T) -> Unit) {
        if (!alive || (if (control) controlling else working || controlling)) return
        if (control) controlling = true else working = true
        error = null
        try { (if (control) runControl else runWork)(Runnable { val result = runCatching(action); main.post { if (alive) { if (control) controlling = false else working = false; result.fold(accept) { error = it.message ?: "原授权未完成；未安装或调用工具" } } } }) }
        catch (_: Exception) { if (control) controlling = false else working = false; error = "原宿主工作队列不可用，未提交" }
    }
    LaunchedEffect(page) { work(action = { page.snapshot() }) { snapshot = it } }
    LaunchedEffect(page, authorizationOpen, shown.optString("phase")) {
        while (alive && authorizationOpen && shown.optString("phase") in setOf("waiting", "authorizing", "exchanging")) {
            kotlinx.coroutines.delay(750)
            work(action = { page.snapshot() }) { current ->
                snapshot = current
                McpInstallPreviewRules.objects(current.optJSONArray("connections")).find { it.optString("connectionId") == shown.optString("connectionId") }?.let { row -> shown = JSONObject(row.toString()).put("expiresAt", if (row.optString("phase") == "connected") row.optLong("expiresAt") else row.optLong("pendingExpiresAt")) }
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }, enabled = !busy && !working) { Text(if (expanded) "收起授权连接" else "这个 MCP 服务需要登录？") }
        if (expanded) {
            Text("先授权连接，再发现与选择工具", style = MaterialTheme.typography.titleSmall)
            Text("仅公开 HTTPS 服务；凭据保存在原加密 Vault，不接收粘贴 token/client secret。授权不会安装、启用或批准工具。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(issuer, { issuer = it }, label = { Text("服务实际授权 issuer（HTTPS）") }, enabled = !busy && !working, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(clientId, { clientId = it }, label = { Text(if (cimd) "已真实托管的 HTTPS client metadata URL" else "该服务已登记的公开 client ID") }, enabled = !busy && !working, modifier = Modifier.fillMaxWidth())
            Row { Checkbox(cimd, { cimd = it }, enabled = !busy && !working); Text("使用既有 CIMD（不替你托管或登记）") }
            OutlinedTextField(callbackPort, { callbackPort = it }, label = { Text("已登记的本机回调端口；0 仅适用允许动态端口的 client") }, enabled = !busy && !working, modifier = Modifier.fillMaxWidth())
            Text("需登记回调：http://127.0.0.1:端口/mcp/oauth/callback。本片授权/令牌端点须与所选 issuer 同 origin；资源元数据须与 MCP 地址同 origin。暂不自动刷新或重试写工具。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { work(action = { page.review(config.json(), issuer.trim(), clientId.trim(), if (cimd) "CIMD" else "PRE_REGISTERED", callbackPort.toIntOrNull() ?: -1) }) { pending = it; shown = it.view(); authorizationOpen = true } }, enabled = !busy && !working && McpInstallPreviewRules.validConfig(config) && issuer.isNotBlank() && clientId.isNotBlank()) { Text(if (working) "正在核对原授权元数据…" else "核对服务与准确授权范围") }
            McpInstallPreviewRules.objects(snapshot.optJSONArray("connections")).forEach { row -> key(row.getString("connectionId")) {
                Text(row.optString("resource"), style = MaterialTheme.typography.bodyMedium)
                Text("${row.optString("issuer")} · ${row.optString("phase")} · 原授权 r${row.optInt("authorizationRevision")}", style = MaterialTheme.typography.bodySmall)
                if (row.optString("phase") == "connected" || row.optBoolean("hasCredential") || row.optBoolean("cleanupPending")) {
                    Text("本地退出不等于服务端已撤销。到期后需明确重授权并重新发现/安装，不会复活旧安装授权。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onUseConnection(JSONObject().put("connectionId", row.getString("connectionId")).put("authorizationRevision", row.getInt("authorizationRevision")).put("bindingFingerprint", row.getString("bindingFingerprint")).put("resource", row.getString("resource")).put("protocolVersion", row.getString("protocolVersion"))) }, enabled = !busy && !working && row.optString("resource") == config.endpoint && row.optString("protocolVersion") == config.protocolVersion && row.optLong("expiresAt") > System.currentTimeMillis() && row.optString("phase") == "connected") { Text("用于当前服务的原工具发现") }
                    TextButton(onClick = { work(control = true, action = { page.signOut(row.getString("connectionId"), row.getInt("authorizationRevision")); page.snapshot() }) { snapshot = it } }, enabled = !working) { Text(if (row.optBoolean("cleanupPending")) "重试清理此原授权（不会恢复权限）" else "退出这个原授权连接") }
                } else if (row.optString("phase") in setOf("waiting", "checking", "review", "authorizing", "exchanging")) {
                    if (row.optString("phase") in setOf("waiting", "authorizing", "exchanging")) TextButton(onClick = { work(action = { page.restore(row.getString("connectionId"), row.getInt("authorizationRevision")) }) { shown = it; pending = null; authorizationOpen = true } }, enabled = !busy && !working) { Text("恢复同一原浏览器授权（不新建请求）") }
                    TextButton(onClick = { work(control = true, action = { page.cancel(row.getString("connectionId")); page.snapshot() }) { snapshot = it } }, enabled = !working) { Text("取消这个原等待授权") }
                } else if (row.optString("phase") in setOf("signed_out", "cancelled", "failed", "expired")) TextButton(onClick = { work(control = true, action = { page.forgetRecord(row.getString("connectionId"), row.getInt("authorizationRevision")); page.snapshot() }) { snapshot = it } }, enabled = !working) { Text("移除已结束的原连接记录") }
                HorizontalDivider()
            } }
            TextButton(onClick = { work(action = { page.snapshot() }) { snapshot = it } }, enabled = !working) { Text("刷新原授权状态") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (authorizationOpen) {
        val id = shown.optString("connectionId"); val fingerprint = shown.optString("bindingFingerprint")
        val view = McpAuthorizationView(shown.optString("reviewId"), shown.optString("installationId"), fingerprint, shown.optString("resource"), shown.optString("issuer"), shown.optString("clientId"), shown.optString("scopes").split(' ').filter(String::isNotBlank), shown.optString("phase"), shown.optLong("expiresAt"), shown.optString("flowId"))
        Dialog(onDismissRequest = { work(control = true, action = { page.cancel(id); page.snapshot() }) { snapshot = it; authorizationOpen = false } }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
            McpAuthorizationPage(view, view.installationId, fingerprint, working || controlling,
                onApprove = { _, _ -> pending?.let { exact -> work(action = { page.approve(exact, shown.getString("digest")) }) { shown = it } } },
                onOpenBrowser = { flow, binding -> runCatching { page.openBrowser(id, flow, binding, mcpOwnerActivity(context)) }.onFailure { error = it.message } },
                onCancel = { _, _, _ -> work(control = true, action = { page.cancel(id); page.snapshot() }) { snapshot = it; shown = JSONObject(shown.toString()).put("phase", "cancelled") } },
                onBack = { authorizationOpen = false; work(action = { page.snapshot() }) { snapshot = it } })
        }
    }
}
private fun mcpOwnerActivity(context: Context): Activity {
    var current = context
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
    while (seen.add(current)) { if (current is Activity) return current; if (current is ContextWrapper) current = current.baseContext else break }
    throw IllegalStateException("原前台 Activity 不可用，未打开授权网址")
}
