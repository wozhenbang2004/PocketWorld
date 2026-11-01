package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONObject

/** Select actual native imported originals, never a caller file path, URI, base64 or group grant. */
@Composable
internal fun NativeMediaImageInputs(
    state: PocketUiState, image: JSONObject?, mask: JSONObject?, enabled: Boolean,
    chooseImage: (JSONObject?) -> Unit, chooseMask: (JSONObject?) -> Unit,
    controller: PocketWorldController
) {
    val original = state.feature("attachments").optJSONArray("attachments").objects()
        .filter { it.optString("mimeType") in setOf("image/png", "image/jpeg", "image/webp") && it.optLong("size") in 1..4L*1024*1024 }
    Text("原图与可选透明 mask", style = MaterialTheme.typography.titleSmall)
    ImageInputChoice("原图", image, original, enabled, chooseImage)
    ImageInputChoice("透明 PNG mask（可选）", mask, original.filter { it.optString("mimeType") == "image/png" }, enabled, chooseMask)
    Text("每份最多4MiB/400万像素。mask需与原图同尺寸且有完全透明区域；不会擅自缩放或替换原图。确认后将上传完整原字节到已配置服务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    image?.let { pinned -> TextButton(onClick = { controller.attachmentOutcomes.openFile(pinned) }, enabled = enabled) { Text("核对原图版本") } }
    if (original.isEmpty()) Text("请先到文件与成果导入自己的图像。这里不会借用群文件只读权限上传。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ImageInputChoice(label: String, selected: JSONObject?, choices: List<JSONObject>, enabled: Boolean, choose: (JSONObject?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label + "：" + (selected?.optString("name") ?: "未选择"), modifier = Modifier.weight(1f)) }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("不选择") }, onClick = { choose(null); expanded = false })
            choices.forEach { file -> DropdownMenuItem(text = { Column { Text(file.optString("name")); Text("${file.optLong("size")} 字节 · ${file.optString("sha256").take(10)}", style = MaterialTheme.typography.labelSmall) } }, onClick = { choose(JSONObject(file.toString())); expanded = false }) }
        }
    }
}
