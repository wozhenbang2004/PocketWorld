package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Secret-free native projection. No authorization URL, state, verifier, code or credential reference. */
internal data class McpAuthorizationView(
    val reviewId: String,
    val installationId: String,
    val bindingFingerprint: String,
    val resource: String,
    val issuer: String,
    val clientId: String,
    val scopes: List<String>,
    val phase: String,
    val expiresAt: Long,
    val flowId: String = ""
)

internal fun mcpAuthorizationCurrent(view: McpAuthorizationView, installationId: String, bindingFingerprint: String, now: Long): Boolean =
    view.reviewId.isNotBlank() && view.installationId == installationId && view.bindingFingerprint == bindingFingerprint &&
        bindingFingerprint.matches(Regex("[a-f0-9]{64}")) && view.expiresAt > now && view.phase in setOf("review", "waiting")

/** The host repeats identity checks atomically; UI guards are only feedback, never authorization. */
@Composable
internal fun McpAuthorizationPage(
    view: McpAuthorizationView,
    currentInstallationId: String,
    currentBindingFingerprint: String,
    busy: Boolean,
    onApprove: (reviewId: String, bindingFingerprint: String) -> Unit,
    onOpenBrowser: (flowId: String, bindingFingerprint: String) -> Unit,
    onCancel: (reviewId: String, flowId: String, bindingFingerprint: String) -> Unit,
    onBack: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(view.reviewId, view.expiresAt) {
        while (now < view.expiresAt) { delay(1000); now = System.currentTimeMillis() }
    }
    fun leave() {
        if (view.phase in setOf("review", "waiting", "authorizing", "exchanging")) onCancel(view.reviewId, view.flowId, view.bindingFingerprint)
        onBack()
    }
    BackHandler { leave() }
    val current = mcpAuthorizationCurrent(view, currentInstallationId, currentBindingFingerprint, now)
    val sameInstallation = view.installationId == currentInstallationId && view.bindingFingerprint == currentBindingFingerprint &&
        currentBindingFingerprint.matches(Regex("[a-f0-9]{64}"))
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("连接 MCP 服务", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { leave() }) { Text("返回") }
            }
            Text(view.resource, style = MaterialTheme.typography.titleMedium)
            Text("核对服务和授权范围后，前往系统浏览器登录。", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text("授权服务", style = MaterialTheme.typography.labelLarge)
            Text(view.issuer, style = MaterialTheme.typography.bodyMedium)
            Text("客户端", style = MaterialTheme.typography.labelLarge)
            Text(view.clientId, style = MaterialTheme.typography.bodyMedium)
            Text("申请权限", style = MaterialTheme.typography.labelLarge)
            if (view.scopes.isEmpty()) Text("服务未指定额外权限", style = MaterialTheme.typography.bodyMedium)
            else view.scopes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val status = when {
                !sameInstallation -> "安装已变化，请重新读取当前连接。"
                view.phase == "connected" && view.expiresAt > now -> "连接凭据已保存。工具权限仍由当前安装的授权设置控制。"
                view.phase == "connected" -> "连接凭据已过期，请重新授权。"
                view.phase == "cancelled" -> "本次授权已取消。"
                view.phase == "failed" -> "授权未完成，请重新核对连接后发起。"
                view.phase == "exchanging" -> "正在核对并保存原授权结果；不会自动重复兑换。"
                view.phase == "authorizing" -> "正在准备这一次原浏览器授权。"
                view.phase == "expired" -> "这一次原授权已到期，请核对后明确重新发起。"
                !current -> "此授权已过期或安装已变化，请重新读取连接。"
                view.phase == "waiting" -> "正在等待浏览器授权结果。"
                else -> "继续后将在系统浏览器中打开上面的授权服务。"
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (view.phase == "review") Button(enabled = current && !busy, onClick = { onApprove(view.reviewId, view.bindingFingerprint) }, modifier = Modifier.fillMaxWidth()) { Text("继续授权") }
            if (view.phase == "waiting") Button(enabled = current && !busy && view.flowId.isNotBlank(), onClick = { onOpenBrowser(view.flowId, view.bindingFingerprint) }, modifier = Modifier.fillMaxWidth()) { Text("在浏览器继续授权") }
            if (view.phase in setOf("review", "waiting", "authorizing", "exchanging")) TextButton(onClick = { onCancel(view.reviewId, view.flowId, view.bindingFingerprint) }, enabled = !busy) { Text("取消授权") }
        }
    }
}
