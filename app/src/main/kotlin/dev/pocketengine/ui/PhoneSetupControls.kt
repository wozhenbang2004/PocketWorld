package dev.pocketengine.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Low-frequency owner controls, embedded below the existing primary setup flow. */
@Composable
internal fun PhoneSetupControls(snapshot: JSONObject, diagnostic: JSONObject?, apps: JSONObject? = null, onCommand: (String, JSONObject) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起连接恢复与应用隐私" else "连接恢复与应用隐私") }
    if (!expanded) return
    val context = LocalContext.current
    val recovery = snapshot.optJSONObject("recovery") ?: JSONObject()
    val access = snapshot.optJSONObject("access") ?: JSONObject()
    var packageName by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("0") }
    var automate by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("连接恢复", style = MaterialTheme.typography.titleSmall)
        Text("仅恢复此应用的界面读取连接。系统额外授权未取得时会明确显示原因，原任务动作不会重放。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("允许恢复界面读取服务")
            Switch(checked = recovery.optBoolean("consent"), onCheckedChange = { onCommand("recovery.consent", JSONObject().put("enabled", it).put("expectedRevision", recovery.optLong("revision"))) })
        }
        OutlinedButton(onClick = { onCommand("recovery.restore", JSONObject()) }, enabled = recovery.optBoolean("consent")) { Text("核对并恢复连接") }
        Text(if (recovery.optBoolean("secureSettingsGranted")) "系统恢复权限已取得；恢复后仍需核对服务实际连接。" else "尚无系统恢复权限；可继续使用原手动授权入口。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("应用访问", style = MaterialTheme.typography.titleSmall)
        Text("自动操作与主人手动分享分开设置。运行中撤销会使旧观察失效；助手不能修改这里。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { picker = !picker; if (picker) onCommand("apps.list", JSONObject()) }) { Text("选择应用与资料") }
        if (picker && apps != null) {
            val rows = apps.optJSONArray("apps")
            if (rows != null) Column(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                androidx.compose.foundation.lazy.LazyColumn { items(rows.length()) { i -> val app = rows.optJSONObject(i); if (app != null) TextButton(onClick = { packageName = app.optString("packageName"); userId = app.optInt("userId").toString(); automate = true; share = true; val saved = access.optJSONArray("rules"); if (saved != null) for (n in 0 until saved.length()) { val rule = saved.optJSONObject(n) ?: continue; if (rule.optString("packageName") == packageName && rule.optInt("userId") == app.optInt("userId")) { automate = rule.optBoolean("automation"); share = rule.optBoolean("ownerShare") } }; picker = false }) { Text("${app.optString("label")} · user ${app.optInt("userId")}") } } }
            }
        }
        OutlinedTextField(packageName, { packageName = it }, label = { Text("准确应用包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(userId, { userId = it.filter(Char::isDigit).take(5) }, label = { Text("已发现的 Android user 编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("允许自动操作"); Switch(automate, { automate = it }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("允许主人手动分享页面"); Switch(share, { share = it }) }
        OutlinedButton(onClick = { onCommand("access.set", JSONObject().put("packageName", packageName).put("userId", userId.toInt()).put("automation", automate).put("ownerShare", share).put("expectedRevision", access.optLong("revision"))) }, enabled = packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && (userId.toIntOrNull() ?: -1) in 0..99999 && access.optBoolean("available")) { Text("保存此应用权限") }
        val rules = access.optJSONArray("rules")
        if (rules != null) for (i in 0 until rules.length()) { val rule = rules.optJSONObject(i) ?: continue; Text("${rule.optString("packageName")} · user ${rule.optInt("userId")} · 自动操作${if (rule.optBoolean("automation")) "允许" else "禁用"} · 手动分享${if (rule.optBoolean("ownerShare")) "允许" else "禁用"}", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("后台任务打开 App 时静音"); Switch(snapshot.optBoolean("audioSilenceEnabled"), { onCommand("audio.policy", JSONObject().put("enabled", it)) }) }
        Text("只修改任务打开的 App 音频模式，并记录原值以便恢复；不会改系统音量。系统不支持时保留未恢复记录。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { onCommand("audio.restore", JSONObject()) }) { Text("核对遗留的 App 音频恢复") }
        if (snapshot.optBoolean("clipboardRecoveryPending")) OutlinedButton(onClick = { onCommand("clipboard.restore", JSONObject()) }) { Text("恢复上次兼容输入的剪贴板") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("允许普通输入框兼容粘贴"); Switch(snapshot.optBoolean("clipboardCompatibilityEnabled"), { onCommand("clipboard.policy", JSONObject().put("enabled", it)) }) }
        Text("默认关闭。仅在原任务当前普通字段使用；原剪贴板会加密保存并恢复，助手无法读取其内容。Android拒绝读取原值时不会粘贴。", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        OutlinedButton(onClick = { onCommand("diagnostics.preview", JSONObject()) }) { Text("预览手机诊断报告") }
        if (diagnostic != null) {
            val text = diagnostic.toString(2)
            Text(text, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PocketWorld 手机诊断", text)) }) { Text("复制报告") }
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享已预览的诊断报告").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("分享报告") }
            }
        }
    }
}
