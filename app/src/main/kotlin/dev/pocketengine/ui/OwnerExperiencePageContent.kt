package dev.pocketengine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Presentation of already filtered original archive pages. This creates no source selection,
 * permission or model context; encoded fragments stay in the exact archive, not the reading pane. */
@Composable internal fun OwnerExperiencePageContent(data: JSONObject, pageKind: String) {
    ExperienceCaption("这些是当时保留的记录，不代表当前事实。原文与摘要分别标示；缺少的原件不会补写。")
    val rows = data.optJSONArray("entries")
    if (rows == null || rows.length() == 0) ExperienceCaption("这一页没有可显示的原件。请查看缺口说明或继续翻页。")
    for (index in 0 until (rows?.length() ?: 0)) {
        val row = rows?.optJSONObject(index) ?: continue
        key(row, pageKind) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                when {
                    row.optString("status") == "blocked_by_known_source_barrier" -> ExperienceCaption("此原经历已被本机已知的遗忘、删除或来源限制阻止，旧正文不再显示。")
                    row.optString("status") == "read_limited" || row.has("readable") && !row.optBoolean("readable") -> ExperienceCaption(experienceGap(row.optString("reason")))
                    else -> {
                        val kind = row.optString("kind")
                        when (kind) {
                            "message", "imported_message" -> {
                                val imported = kind == "imported_message"
                                val role = when (row.optString("role")) { "user" -> "当时的用户消息"; "assistant" -> "当时的助手答复"; else -> "导入的历史记录" }
                                Text(role, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                ExperienceCaption(ownerExperienceTime(row.opt("at")))
                                if (imported && row.optString("sourceFormat") == "conversation_export_v1") {
                                    ExperienceCaption("会话导入时保存的规范化片段；可核对本机保存的字节，不是外部原导出文件。历史身份未认证。")
                                    if (!row.optBoolean("transferMetadataKnown")) ExperienceCaption("这条较早的导入未保留附件引用与省略清单，相关元数据未知。")
                                    else row.optJSONObject("originExportEvidence")?.let { evidence ->
                                        val count = evidence.optJSONArray("attachmentReferences")?.length() ?: 0
                                        ExperienceCaption("保留 $count 条附件 hash 线索，均未验证或恢复附件；未匹配消息的省略项 ${evidence.optInt("unmatchedOmissions")} 条、附件引用 ${evidence.optInt("unmatchedAttachmentReferences")} 条未保留。")
                                    }
                                } else if (imported) ExperienceCaption("旧版导入的原片段；导入时分配的编号不能证明历史身份。")
                                ExperienceBody(row.optString("text"), "owner_experience_body_$index")
                            }
                            "task_header" -> {
                                Text("当时的任务", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                ExperienceCaption(ownerExperienceTime(row.opt("createdAt")))
                                ExperienceBody(row.optString("goal"), "owner_experience_body_$index")
                                row.optString("summary").takeIf { it.isNotBlank() }?.let { summary ->
                                    ExperienceCaption("当时的任务摘要（不是原始工具回执）")
                                    ExperienceBody(summary, "owner_experience_summary_$index")
                                }
                            }
                            "tool_output_chunk" -> {
                                Text("工具原回执 · 片段 ${row.optInt("chunkIndex") + 1}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                ExperienceCaption("按原始顺序保存的结果片段；已发生的效果仍以原操作回执为准。")
                                ExperienceBody(row.optString("text"), "owner_experience_body_$index")
                            }
                            else -> {
                                Text(if (pageKind == "operations") "原操作记录" else "原任务事件", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                if (row.optBoolean("derivedBodyIncluded", true).not()) ExperienceCaption("该次操作的发生记录仍保留，已撤回的学习正文不再显示。")
                                else ExperienceCaption("展开可查看当时的原事件或回执。它不会重新执行，也不会生成新的授权。")
                            }
                        }
                        ExperienceEvidence(row, pageKind)
                    }
                }
            }
        }
    }
    data.optJSONArray("gaps")?.let { gaps ->
        for (index in 0 until gaps.length()) {
            val gap = gaps.optJSONObject(index) ?: continue
            ExperienceCaption(experienceGap(gap.optString("reason")))
        }
    }
}

@Composable private fun ExperienceEvidence(row: JSONObject, pageKind: String) {
    var expanded by remember(row) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起来源与证据" else "查看来源与证据") }
    if (!expanded) return
    val evidence = remember(row) {
        JSONObject().also { output ->
            for (key in listOf("sourceRef", "importedLocator", "sourceFormat", "historicalRevision", "originalIdentity", "originExportRecord", "originExportEvidence", "sha256", "bytes", "at", "editedAt", "attachments", "originalAttachmentMetadata", "taskId", "runId", "outputId", "chunkIndex", "startChar", "chars", "outputFingerprint", "outputBytes", "outputChars", "dependencies", "reference", "eventKey", "position", "rowRevision", "tool", "state", "status", "createdAt")) if (row.has(key)) output.put(key, row.get(key))
        }.toString(2)
    }
    ExperienceCaption("版本、范围与 hash 用于回查这份快照中的原件；摘要和导入线索不能替代原始证据。")
    ExperienceBody(evidence, "owner_experience_evidence")
    if (pageKind == "events") row.optJSONObject("event")?.let { event -> ExperienceCaption("当时的事件内容"); ExperienceBody(event.toString(2), "owner_experience_event") }
    if (pageKind == "operations") {
        if (!row.isNull("arguments")) { ExperienceCaption("当时的操作输入"); ExperienceBody(row.get("arguments").toString(), "owner_experience_arguments") }
        if (!row.isNull("receipt")) { ExperienceCaption("当时保存的原回执"); ExperienceBody(row.get("receipt").toString(), "owner_experience_receipt") }
        else ExperienceCaption("没有保存完整回执，不能据此认定操作未发生或已成功。")
    }
}

@Composable private fun ExperienceBody(text: String, tag: String) {
    var visible by remember(text) { mutableIntStateOf(4000) }
    var end = minOf(visible, text.length)
    if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    SelectionContainer { Text(text.substring(0, end).ifEmpty { "（原文为空）" }, modifier = Modifier.testTag(tag), fontSize = 14.sp, lineHeight = 22.sp) }
    if (end < text.length) {
        ExperienceCaption("已显示原文前 $end / ${text.length} 个字符；剩余原文仍在这份快照中。")
        TextButton(onClick = { visible = minOf(text.length, visible + 4000) }) { Text("继续显示原文") }
    }
}

@Composable private fun ExperienceCaption(text: String) { Text(text, fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk) }

internal fun ownerExperienceTime(raw: Any?): String {
    val value = when (raw) { is Long -> raw; is Int -> raw.toLong(); is Short -> raw.toLong(); is Byte -> raw.toLong(); else -> null }
    return if (value != null && value >= 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value)) else "历史时间未知"
}
internal fun ownerExperienceKindLabel(kind: String): String = when (kind) { "messages" -> "原聊天"; "task_headers" -> "任务记录"; "events" -> "任务事件"; "operations" -> "操作记录"; "outputs" -> "工具原回执"; else -> "原经历" }
private fun experienceGap(reason: String): String = when (reason) {
    "imported_record_deleted", "deleted_retired_forgotten_missing_or_read_limited" -> "这段原件已删除、停止使用或无法读取；快照没有补写正文。"
    "original_body_exceeds_remaining_page_bytes", "imported_original_exceeds_archive_page_budget" -> "这段原件超过本次读取预算；它未被当作完整原文保存。"
    "protected_imported_fields_excluded", "protected_native_fields_excluded", "protected_fields_excluded" -> "此处含受保护的凭据或非用户可见字段，未收入快照。"
    "withdrawn_native_learning_draft_excluded" -> "学习来源已撤回；只保留操作发生记录。"
    "original_run_sources_unavailable", "withdrawn_missing_chunk_or_dependency_unavailable" -> "原来源、附件或输出片段当前不可用；没有把缺口当作成功结果。"
    else -> "此页有未保留或不可读取的原件，无法从这份快照补回。"
}
