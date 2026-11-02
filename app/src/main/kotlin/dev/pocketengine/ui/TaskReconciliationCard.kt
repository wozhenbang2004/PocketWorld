package dev.pocketengine.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Affect Decision Inbox anatomy. It presents the original unknown operations; callbacks remain
 * the caller's original reviewed-task controls, never an optimistic completion or a retry. */
@Composable
internal fun TaskReconciliationCard(taskId: String, operations: List<JSONObject>, onResolve: (String, String) -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(.5.dp, Line),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).pocketDepth(PocketDepth.CONTENT, 14.dp)
            .testTag("task_reconciliation_card")) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AffectGlyph("question", Modifier.size(20.dp), Ink)
                Column(Modifier.weight(1f)) {
                    Text("需要你核对", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("操作可能已经发生，确认前不会重复执行。", fontSize = 13.sp, lineHeight = 20.sp,
                        color = SecondaryInk, modifier = Modifier.padding(top = 3.dp))
                }
                Text("${operations.size} 项", fontSize = 12.sp, color = SecondaryInk)
            }
            operations.forEach { operation ->
                val id = operation.optString("operationId", operation.optString("id"))
                key(taskId, id) {
                    var details by rememberSaveable(taskId, id) { mutableStateOf(false) }
                    val originalName = operation.optString("summary", operation.optString("tool", "待核对操作"))
                    HorizontalDivider(color = Line, thickness = .5.dp)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(toolDisplayName(originalName), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onResolve(id, "occurred") }, enabled = id.isNotBlank(),
                                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("已经发生", fontSize = 13.sp) }
                            OutlinedButton(onClick = { onResolve(id, "not_occurred") }, enabled = id.isNotBlank(),
                                shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("没有发生", fontSize = 13.sp) }
                        }
                        TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (details) "收起操作详情" else "查看操作详情", fontSize = 12.sp)
                        }
                        AnimatedVisibility(details) {
                            Column {
                                Text("工具 · ${operation.optString("tool", originalName)}", fontSize = 12.sp, color = SecondaryInk)
                                Text("操作编号 · $id", fontSize = 12.sp, color = SecondaryInk)
                                val body = operation.opt("arguments")?.toString().orEmpty()
                                if (body.isNotBlank()) {
                                    SelectionContainer { Text(body.take(4000), fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) }
                                    if (body.length > 4000) Text("内容较长，这里仅显示前一部分。", fontSize = 12.sp, color = SecondaryInk)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
