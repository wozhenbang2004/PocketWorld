package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.plugins.PluginRevisions
import dev.pocketengine.features.plugins.distribution.PluginPackages
import org.json.JSONArray
import org.json.JSONObject

internal object PluginPackageTransferGuard {
    @JvmStatic fun canImport(displayed: String?, current: String?, busy: Boolean, confirmed: Boolean, submitted: Boolean): Boolean =
        displayed != null && displayed.matches(Regex("[a-f0-9]{64}")) && displayed == current && !busy && confirmed && !submitted
}

/** Native owner file-transfer leaf. The host owns SAF streams, exact requests and current-module state. */
@Composable
internal fun PluginPackageTransferPage(
    installed: List<JSONObject>,
    inspection: PluginPackages.Inspection?,
    currentReviewDigest: String?,
    busy: Boolean,
    message: String?,
    onChoosePackage: () -> Unit,
    onImport: (PluginPackages.Inspection, String) -> Unit,
    onChooseExport: (String, String) -> Unit,
    onCancelTransfer: () -> Unit,
    onBack: () -> Unit
) {
    val digest = inspection?.reviewDigest
    val review = remember(digest) { inspection?.review() }
    var confirmed by remember(digest) { mutableStateOf(false) }
    var submitted by remember(digest) { mutableStateOf(false) }
    var details by remember(digest) { mutableStateOf(false) }
    BackHandler { onBack() }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回") }
                Text("插件声明文件", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (busy) TextButton(onClick = onCancelTransfer) { Text("停止传输") }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, fontSize = 14.sp, lineHeight = 22.sp) }
                Text("先查看差异，再决定导入", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("文件只携带工具声明。导入会生成新的停用安装，工具授权和旧凭据需要重新设置。", fontSize = 14.sp, lineHeight = 22.sp)
                Button(onClick = onChoosePackage, enabled = !busy && !submitted) { Text("选择声明文件") }
                if (inspection != null && review != null) {
                    val preview = review.getJSONObject("preview")
                    val manifest = preview.getJSONObject("manifest")
                    val mcp = review.getBoolean("requiresMcpRediscovery")
                    Text(manifest.getString("name"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    PackageTransferValue("版本", if (preview.getBoolean("isUpdate")) "${preview.getString("currentVersion")} → ${preview.getString("targetVersion")}" else preview.getString("targetVersion"))
                    PackageTransferValue("来源 · 未验证", review.getJSONObject("source").getString("label"))
                    PackageTransferValue("声明 SHA-256", review.getString("declarationDigest"))
                    Text("此包没有签名验证；摘要用于识别内容。", fontSize = 13.sp, lineHeight = 21.sp)
                    for ((key, label) in listOf("addedTools" to "新增", "removedTools" to "移除")) {
                        val rows = preview.getJSONArray(key)
                        if (rows.length() > 0) PackageTransferValue(label, (0 until rows.length()).joinToString("、") { rows.getJSONObject(it).getString("name") })
                        if (key == "addedTools") for (i in 0 until rows.length()) {
                            val tool = rows.getJSONObject(i)
                            PackageTransferValue(tool.getString("name"), "${tool.getString("kind")} · ${tool.getString("effect")}\n${tool.optString("endpoint", "本地回显")}")
                        }
                    }
                    val changes = preview.getJSONArray("changedTools")
                    for (i in 0 until changes.length()) {
                        val change = changes.getJSONObject(i)
                        PackageTransferValue(change.getString("name") + " 的变化", transferArrayLines(change.getJSONArray("fields")))
                        for (side in listOf("before", "after")) {
                            val tool = change.getJSONObject(side)
                            PackageTransferValue(if (side == "before") "原声明" else "新声明", "${tool.getString("kind")} · ${tool.getString("effect")}\n${tool.optString("endpoint", "本地回显")}")
                        }
                    }
                    if (preview.getBoolean("sameManifest")) Text("声明内容相同。重新导入仍会生成新身份并清空旧授权。", fontSize = 13.sp)
                    if (preview.optInt("activeCallsToCancel") > 0) Text("将停止等待中的 ${preview.getInt("activeCallsToCancel")} 个工具调用；已发出的请求仍需核对实际结果。", fontSize = 13.sp, lineHeight = 21.sp)
                    TextButton(onClick = { details = !details }) { Text(if (details) "收起完整声明" else "查看完整工具、端点与参数定义") }
                    if (details) {
                        PackageTransferValue("包文件 SHA-256", inspection.packageDigest)
                        PackageTransferValue("完整声明 · 不可信参考", manifest.toString(2))
                    }
                    if (mcp) Text("MCP 工具需重新发现当前服务、认证并单独批准。此文件不会直接安装或启用远端工具。", fontSize = 14.sp, lineHeight = 22.sp)
                    if (currentReviewDigest != digest) Text("当前安装或预览已改变，请重新选择并检查文件。", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = !busy && !submitted)
                        Text("我已核对本次声明和工具权限变化", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = {
                        if (PluginPackageTransferGuard.canImport(digest, currentReviewDigest, busy, confirmed, submitted)) {
                            submitted = true
                            onImport(inspection, inspection.reviewDigest)
                        }
                    }, enabled = PluginPackageTransferGuard.canImport(digest, currentReviewDigest, busy, confirmed, submitted)) { Text(if (submitted) "请求已提交，请核对当前安装" else if (mcp) "继续到服务重新发现" else "确认导入为停用插件") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("导出已安装的声明", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("导出不包含宿主凭据、授权、安装身份或历史记录。", fontSize = 13.sp, lineHeight = 21.sp)
                if (installed.isEmpty()) Text("尚未安装插件。", fontSize = 14.sp)
                for (plugin in installed) {
                    val manifest = plugin.getJSONObject("manifest")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(manifest.getString("name"), fontSize = 14.sp); Text(manifest.getString("version"), fontSize = 12.sp) }
                        TextButton(onClick = { onChooseExport(plugin.getString("id"), PluginRevisions.installationId(plugin)) }, enabled = !busy) { Text("导出声明") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageTransferValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, fontSize = 14.sp, lineHeight = 22.sp) }
    }
}
private fun transferArrayLines(values: JSONArray): String = (0 until values.length()).joinToString("、") { values.getString(it) }
