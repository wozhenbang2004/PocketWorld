package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.R
import dev.pocketengine.features.operations.LiteralUiText
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
                Text(LiteralUiText.text(R.string.w1_preferences_allow, "允许通过对话调整偏好"), fontSize = 14.sp)
                Text(LiteralUiText.text(R.string.w1_preferences_allow_description, "允许新任务通过对话修改回复详略和普通操作节奏；关闭立即停止后续修改。"), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            }
            Switch(enabled, { save(it, detail, pace, revision) }, enabled = available && !busy)
        }
        Text(LiteralUiText.text(R.string.w1_preferences_response_detail, "回复详略"), style = MaterialTheme.typography.labelLarge)
        val details = listOf("concise", "standard", "detailed")
        PocketSegmentedTabs(listOf(LiteralUiText.text(R.string.w1_preferences_detail_concise, "简洁"), LiteralUiText.text(R.string.w1_preferences_detail_standard, "标准"), LiteralUiText.text(R.string.w1_preferences_detail_detailed, "详细")), details.indexOf(detail).coerceAtLeast(0),
            { if (details[it] != detail) save(enabled, details[it], pace, revision) }, available && !busy)
        Text(LiteralUiText.text(R.string.w1_preferences_operation_pace, "普通操作节奏"), style = MaterialTheme.typography.labelLarge)
        val paces = listOf("fast", "standard", "careful")
        PocketSegmentedTabs(listOf(LiteralUiText.text(R.string.w1_preferences_pace_fast, "快速"), LiteralUiText.text(R.string.w1_preferences_pace_standard, "标准"), LiteralUiText.text(R.string.w1_preferences_pace_careful, "从容")), paces.indexOf(pace).coerceAtLeast(0),
            { if (paces[it] != pace) save(enabled, detail, paces[it], revision) }, available && !busy)
        Text(LiteralUiText.text(R.string.w1_preferences_pace_description, "从容会在普通手机动作前稍作停顿。偏好不会改变授权、事实或完成标准。"), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        TextButton(onClick = { save(enabled, "standard", "standard", revision) }, enabled = available && !busy && (detail != "standard" || pace != "standard")) { Text(LiteralUiText.text(R.string.w1_preferences_restore_standard, "恢复标准")) }
        if (!available) Text(LiteralUiText.text(R.string.w1_preferences_unavailable, "偏好设置尚未读到，请重新打开设置。"), color = SecondaryInk, fontSize = 12.sp)
        if (busy) Text(LiteralUiText.text(R.string.w1_preferences_saving, "正在保存偏好…"), color = SecondaryInk, fontSize = 12.sp)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
