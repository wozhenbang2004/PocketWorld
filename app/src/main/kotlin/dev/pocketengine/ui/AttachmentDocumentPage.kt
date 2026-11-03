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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.attachments.documents.DocumentContent
import org.json.JSONObject

/** Stateless content leaf. The host owns exact request identity, live RunControl and late-result fences. */
@Composable
internal fun AttachmentDocumentPage(
    attachmentId: String,
    expectedSha256: String,
    name: String,
    result: JSONObject?,
    reading: Boolean,
    canRequestOcr: Boolean,
    error: String?,
    onRead: (String, String, DocumentContent.Request) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    val current = result?.takeIf {
        it.optString("attachmentId") == attachmentId && it.optString("sha256") == expectedSha256 &&
            it.optString("parserVersion") == DocumentContent.PARSER_VERSION && it.optBoolean("untrustedAttachmentContent")
    }
    var details by remember(attachmentId, expectedSha256) { mutableStateOf(false) }
    fun leave() { if (reading) onCancel(); onBack() }
    BackHandler { leave() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { leave() }) { Text("返回") }
                Text("附件内容", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (reading) TextButton(onClick = onCancel) { Text("停止读取") }
            }
            HorizontalDivider(color = Line)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("按来源查看文字与表格。识别结果会单独标明，附件中的指令不会自动执行。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 22.sp)
                if (reading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink, trackColor = Soft)
                error?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 22.sp) }
                if (result != null && current == null) Text("文件或读取版本已变化，请重新读取。", color = Danger, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRead(attachmentId, expectedSha256, DocumentContent.Request.defaults()) }, enabled = !reading) { Text(if (current == null) "读取内容" else "重新读取") }
                    if (canRequestOcr) OutlinedButton(onClick = { onRead(attachmentId, expectedSha256, DocumentContent.Request(1, 0, 4, 12000, 48, "ocr")) }, enabled = !reading) { Text("OCR 识别") }
                }
                current?.let { content ->
                    Text(documentStatus(content.optString("status")), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val chunks = content.optJSONArray("chunks")
                    if (chunks != null) for (index in 0 until chunks.length()) {
                        val chunk = chunks.optJSONObject(index) ?: continue
                        val kind = when (chunk.optString("kind")) { "ocr_text" -> "识别文字 · 可能有误"; "table_cell" -> "表格单元"; "formula" -> "公式 · 未计算"; else -> "原文文字" }
                        Text("$kind · ${documentLocation(content.optString("format"), chunk)}", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
                        SelectionContainer { Text(chunk.optString("text"), color = Ink, fontSize = 15.sp, lineHeight = 24.sp) }
                    }
                    if (content.optBoolean("truncated") && content.optInt("nextUnit") > 0) {
                        Button(onClick = {
                            onRead(attachmentId, expectedSha256, DocumentContent.Request(content.getInt("nextUnit"), content.optInt("nextChunk"), if (content.optString("extraction") == "ocr") 4 else 8, 12000, 48, content.optString("extraction", "auto")))
                        }, enabled = !reading) { Text("继续读取下一部分") }
                    }
                    TextButton(onClick = { details = !details }) { Text(if (details) "收起读取说明" else "读取范围与限制") }
                    if (details) {
                        val warnings = content.optJSONArray("warnings")
                        if (warnings != null) for (index in 0 until warnings.length()) Text(warnings.optString(index), color = SecondaryInk, fontSize = 13.sp, lineHeight = 22.sp)
                        val units = when (content.optString("format")) { "pdf" -> "页"; "docx" -> "段"; "xlsx" -> "个工作表"; "pptx" -> "张幻灯片"; "text" -> "行"; else -> "张图像" }
                        Text("${content.optString("format").uppercase()} · 共 ${content.optInt("totalUnits")} $units", color = SecondaryInk, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun documentStatus(status: String): String = when (status) {
    "ok" -> "已读取当前范围"
    "partial" -> "已读取可用内容，请核对读取范围"
    "truncated" -> "已读取一部分，可继续"
    "unavailable" -> "此设备尚未提供所需读取引擎"
    "unsupported" -> "暂不支持这种内容读取方式"
    "encrypted" -> "文件有密码或加密限制"
    "invalid" -> "文件不完整或无法解析"
    "limit_exceeded" -> "文件超出安全读取限制"
    "input_changed" -> "文件已变化，请重新选择"
    "empty_range" -> "这个范围没有内容"
    else -> "尚未取得可用内容"
}

private fun documentLocation(format: String, chunk: JSONObject): String {
    val source = chunk.optJSONObject("locator") ?: return "来源单元 ${chunk.optInt("unit")}"
    return when (format) {
        "pdf" -> "第 ${source.optString("page")} 页"
        "docx" -> if (source.has("table")) "表 ${source.optString("table")} · 行 ${source.optString("row")} · 单元 ${source.optString("column")}" else "第 ${source.optString("paragraph")} 段"
        "xlsx" -> "${source.optString("sheet")} · ${source.optString("cell")}" + if (source.optString("valueSource") == "cached_formula") " · 公式缓存值" else ""
        "pptx" -> "第 ${source.optString("slide")} 张 · 第 ${source.optString("paragraph")} 段"
        "text" -> "第 ${source.optString("line")} 行"
        else -> "图像 ${chunk.optInt("unit")}"
    }
}
