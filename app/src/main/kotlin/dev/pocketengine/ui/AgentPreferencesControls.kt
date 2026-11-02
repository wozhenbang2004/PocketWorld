package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Content of the existing native SettingsSurface. Model tools never call this owner action. */
@Composable
internal fun AgentPreferencesControls(
    preferences: JSONObject,
    busy: Boolean,
    error: String?,
    save: (Boolean, String, String, Long) -> Unit
) {
    val available = preferences.opt("enabled") is Boolean && preferences.optLong("revision", 0) > 0
    val enabled = preferences.optBoolean("enabled", false)
    val detail = preferences.optString("response_detail", "standard")
    val pace = preferences.optString("operation_pace", "standard")
    val revision = preferences.optLong("revision", 1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("允许通过对话调整偏好", fontSize = 14.sp)
                Text("允许新任务通过对话修改回复详略和普通操作节奏；关闭立即停止后续修改。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            }
            Switch(enabled, { save(it, detail, pace, revision) }, enabled = available && !busy)
        }
        Text("回复详略", style = MaterialTheme.typography.labelLarge)
        val details = listOf("concise", "standard", "detailed")
        PocketSegmentedTabs(listOf("简洁", "标准", "详细"), details.indexOf(detail).coerceAtLeast(0),
            { if (details[it] != detail) save(enabled, details[it], pace, revision) }, available && !busy)
        Text("普通操作节奏", style = MaterialTheme.typography.labelLarge)
        val paces = listOf("fast", "standard", "careful")
        PocketSegmentedTabs(listOf("快速", "标准", "从容"), paces.indexOf(pace).coerceAtLeast(0),
            { if (paces[it] != pace) save(enabled, detail, paces[it], revision) }, available && !busy)
        Text("从容会在普通手机动作前稍作停顿。偏好不会改变授权、事实或完成标准。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        TextButton(onClick = { save(enabled, "standard", "standard", revision) }, enabled = available && !busy && (detail != "standard" || pace != "standard")) { Text("恢复标准") }
        if (!available) Text("偏好设置尚未读到，请重新打开设置。", color = SecondaryInk, fontSize = 12.sp)
        if (busy) Text("正在保存偏好…", color = SecondaryInk, fontSize = 12.sp)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
