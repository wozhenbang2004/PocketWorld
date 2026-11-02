package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Native owner-only, ephemeral input. No saved state, clipboard export, model or task logging. */
@Composable
internal fun DeviceRelayLeaseInput(localDeviceId: String, leases: JSONArray, busy: Boolean, close: () -> Unit, remove: (String, String) -> Unit, inspect: (String) -> Unit) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("已有的临时中继租约") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅接收你已取得并信任的本端短时租约JSON（pocketworld.relay-lease.v1）。不创建服务、账户或设备权限。中继可见连接时间和流量，设备内容仍由原设备间TLS保护。")
            Text("首次使用请先在‘我的设备’明确启用设备功能，建立原本机身份；核对或导入租约不会生成替代设备身份。", style = MaterialTheme.typography.bodySmall)
            if (localDeviceId.isNotBlank()) SelectionContainer { Text("本机设备ID：$localDeviceId", style = MaterialTheme.typography.bodySmall) }
            Text("需含 serviceBase（TLS服务源站，不带路径）、inviterDeviceId、routeId、side、routeToken、expiresAt。两端须持同一路由、不同槽位和各自凭据；有效期最多一小时。凭据只留在本次应用进程，重启后需重新导入。", style = MaterialTheme.typography.bodySmall)
            for (i in 0 until leases.length()) {
                val lease = leases.getJSONObject(i)
                Text(lease.optString("serviceBase"), style = MaterialTheme.typography.titleSmall)
                Text("邀请设备 ${lease.optString("inviterDeviceId")} · 槽位 ${lease.optString("side")}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { remove(lease.getString("serviceBase"), lease.getString("inviterDeviceId")) }, enabled = !busy) { Text("移除此临时租约并关闭其通道") }
            }
            Text("移除不等于停止远端已接收的任务；已发操作仍须从原任务核对。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(raw, { if (it.toByteArray(Charsets.UTF_8).size <= 4096) raw = it }, label = { Text("粘贴本端已有租约（包含私密凭据）") }, minLines = 3, maxLines = 5, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("device_relay_lease_input"))
        }
    }, confirmButton = { Button(onClick = { inspect(raw); raw = "" }, enabled = !busy && raw.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("device_relay_lease_review")) { Text("核对服务与期限") } },
        dismissButton = { TextButton(onClick = { raw = ""; close() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
internal fun DeviceRelayLeaseConfirmation(view: JSONObject, busy: Boolean, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("确认临时出站中继") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(view.optString("serviceBase"), style = MaterialTheme.typography.titleMedium)
            Text("本机：${view.optString("localDeviceId")}\n邀请设备：${view.optString("inviterDeviceId")}\n槽位：${view.optString("side")}", style = MaterialTheme.typography.bodySmall)
            Text("截至 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(view.optLong("expiresAt")))}。只在本次应用进程有效，尚未证明双端可达；不会自动配对或授予设备权限。仍需按原入口核对完整指纹与准确权限。")
        }
    }, confirmButton = { Button(onClick = confirm, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_relay_lease_confirm")) { Text("使用此临时租约") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
