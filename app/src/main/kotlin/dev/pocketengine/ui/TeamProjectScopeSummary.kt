package dev.pocketengine.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import org.json.JSONObject

/** Native owner review only: full baseline remains in original SQL; this bounded view grants nothing. */
@Composable internal fun TeamProjectScopeSummary(selection: JSONObject) {
    var offset by remember(selection.optString("selectionHash")) { mutableIntStateOf(0) }
    var showOmitted by remember(selection.optString("selectionHash")) { mutableStateOf(false) }
    val manifest = selection.getJSONObject("manifest")
    val rows = if (showOmitted) manifest.getJSONArray("omitted") else manifest.getJSONArray("files")
    val end = minOf(rows.length(), offset + 30)
    TeamCaption("${selection.optString("runtimeId")} / 环境 ${selection.optString("runtimeIdentity").take(16)}；目录 ${manifest.getJSONArray("directories").length()} 个，排除项 ${manifest.getJSONArray("omitted").length()} 个。以下为主人本次实际原件清单，不是模型声称的文件。")
    TextButton(onClick = { showOmitted = !showOmitted; offset = 0 }) { Text(if (showOmitted) "查看实际包含文件" else "核对已排除路径与原因") }
    for (i in offset until end) {
        val row = rows.getJSONObject(i)
        TeamCaption(if (showOmitted) "${row.optString("path")} · ${row.optString("reason")}" else "${row.getString("path")} · ${row.getLong("bytes")}B · ${row.getString("sha256").take(16)}${if (row.getBoolean("executable")) " · 可执行" else ""}")
    }
    if (rows.length() > 30) Row {
        TextButton(onClick = { offset = maxOf(0, offset - 30) }, enabled = offset > 0) { Text("上一段") }
        Text("${offset + 1}–$end / ${rows.length()}")
        TextButton(onClick = { offset = end }, enabled = end < rows.length()) { Text("下一段") }
    }
    TeamCaption("本版交付审查的修改输出仍为明确UTF-8源文件；基线中的二进制完整保留，不冒充二进制编辑。已知凭据排除不代表没有任何秘密。命令仍同应用UID执行；源清单或环境变化使原方案失效，不能由模板名维持旧批准。")
}
