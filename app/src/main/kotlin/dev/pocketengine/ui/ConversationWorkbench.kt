package dev.pocketengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.conversation.ConversationHistory
import dev.pocketengine.features.conversation.RichMessageDocument
import dev.pocketengine.features.conversation.TaskPresentation
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/** Formal native rendering of persisted message text. Provisional model drafts stay separate. */
@Composable
internal fun RichConversationMessageBody(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { RichMessageDocument.parse(content) }
    var pendingLink by remember(content) { mutableStateOf<String?>(null) }
    var linkFailure by remember(content) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                RichMessageDocument.Kind.CODE -> ConversationCode(block)
                RichMessageDocument.Kind.TABLE -> ConversationTable(block) { pendingLink = it }
                RichMessageDocument.Kind.LIST_ITEM -> Row(Modifier.padding(start = (block.level * 12).dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(block.marker, fontSize = 15.sp)
                    ConversationSpans(block.spans, Modifier.weight(1f)) { pendingLink = it }
                }
                RichMessageDocument.Kind.QUOTE -> Row {
                    Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    ConversationSpans(block.spans, Modifier.weight(1f).padding(start = 12.dp)) { pendingLink = it }
                }
                RichMessageDocument.Kind.HEADING -> ConversationSpans(block.spans, heading = block.level) { pendingLink = it }
                else -> ConversationSpans(block.spans) { pendingLink = it }
            }
        }
        if (linkFailure) Text("链接暂时无法打开。你可以复制地址后手动处理。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
    pendingLink?.let { address ->
        AlertDialog(onDismissRequest = { pendingLink = null }, title = { Text("打开外部链接") }, text = {
            Column { Text("链接来自会话内容。请先核对完整地址。"); SelectionContainer { Text(address, modifier = Modifier.padding(top = 12.dp)) } }
        }, confirmButton = { TextButton(onClick = {
            if (RichMessageDocument.safeUrl(address)) try { uriHandler.openUri(address); linkFailure = false } catch (_: Exception) { linkFailure = true }
            pendingLink = null
        }) { Text("打开链接") } }, dismissButton = { TextButton(onClick = { pendingLink = null }) { Text("取消") } })
    }
}

@Composable
private fun ConversationSpans(spans: List<RichMessageDocument.Span>, modifier: Modifier = Modifier, heading: Int = 0, link: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val annotated = buildAnnotatedString {
        spans.forEach { span ->
            val start = length; append(span.text)
            when (span.style) {
                "bold" -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                "code" -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant), start, length)
                "link" -> addLink(LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) { link(span.url) }, start, length)
            }
        }
    }
    SelectionContainer {
        Text(annotated, modifier = modifier, style = LocalTextStyle.current.copy(color = colors.onSurface, fontSize = if (heading == 1) 21.sp else if (heading > 0) 18.sp else 15.sp,
            lineHeight = if (heading == 1) 31.sp else 25.sp, fontWeight = if (heading > 0) FontWeight.SemiBold else FontWeight.Normal))
    }
}

@Composable
private fun ConversationCode(block: RichMessageDocument.Block) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.language.ifBlank { "代码" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }, modifier = Modifier.testTag("conversation_copy_code")) { Text(if (copied) "已复制" else "复制代码", fontSize = 12.sp) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SelectionContainer {
            Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                .horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).padding(12.dp).testTag("conversation_code"))
        }
        if (!block.closed) Text("代码段尚未闭合，按原文展示。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun ConversationTable(block: RichMessageDocument.Block, link: (String) -> Unit) {
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))) {
        block.rows.forEachIndexed { index, cells ->
            Row(Modifier.background(if (index == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)) {
                cells.forEach { value -> Box(Modifier.width(180.dp).padding(10.dp)) { ConversationSpans(RichMessageDocument.inline(value), heading = if (index == 0) 3 else 0, link = link) } }
            }
            if (index != block.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Source is an actual host TaskStore projection. Controls only open/refresh that same task. */
@Composable
internal fun ConversationTaskJournal(task: TaskPresentation.Snapshot, onOpenTask: (String) -> Unit,
    onLoadDetails: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(task.id, task.runId) { mutableStateOf(false) }
    var showAll by rememberSaveable(task.id, task.runId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("conversation_task_" + task.id)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = task.id.isNotBlank()) {
                expanded = !expanded
                if (expanded && !task.detailsLoaded) onLoadDetails(task.id)
            }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val iconCount = task.operations.size.coerceIn(1, 3)
                Box(Modifier.width((22 + 18 * (iconCount - 1)).dp).height(22.dp)) {
                    repeat(iconCount) { index ->
                        androidx.compose.material3.Surface(
                            modifier = Modifier.offset(x = (18 * index).dp).size(22.dp),
                            shape = RoundedCornerShape(6.dp), color = androidx.compose.ui.graphics.Color.White,
                            border = androidx.compose.foundation.BorderStroke(.5.dp, androidx.compose.ui.graphics.Color(0xffe9e9e9)),
                            shadowElevation = 1.dp) {
                            Box(contentAlignment = Alignment.Center) { AffectGlyph("tool", Modifier.size(12.dp), SecondaryInk) }
                        }
                    }
                }
                Text(if(task.detailsLoaded) "执行过程 · 最近 ${task.operations.size} 次调用" else "查看执行过程",
                    fontSize = 13.sp, lineHeight = 20.sp, color = Ink,
                    modifier = Modifier.weight(1f), maxLines = 2)
                AffectGlyph("chevron", Modifier.size(12.dp), SecondaryInk)
            }
            TextButton(onClick = { onOpenTask(task.id) }, enabled = task.id.isNotBlank(),
                modifier = Modifier.testTag("conversation_open_task"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if(task.confirmedComplete) "成果" else "任务", fontSize = 13.sp)
            }
        }
        if (task.status != "completed" && task.next.isNotBlank()) Text(task.next,
            fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk,
            modifier = Modifier.padding(bottom = 8.dp))
        androidx.compose.animation.AnimatedVisibility(visible = expanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(task.goal, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp))
                if (task.summary.isNotBlank()) Text(task.summary, fontSize = 13.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
                if (!task.detailsLoaded) {
                    Text("原任务记录尚未载入。", fontSize = 13.sp, color = SecondaryInk)
                    TextButton(onClick = { onLoadDetails(task.id) }) { Text("读取记录") }
                } else if (task.operations.isEmpty()) Text("原任务还没有工具调用记录。", fontSize = 13.sp, color = SecondaryInk)
                val visible = if(showAll) task.operations else task.operations.takeLast(3)
                if(task.operations.size > 3) TextButton(onClick = { showAll = !showAll }) {
                    Text(if(showAll) "只看最近调用" else "展开其余 ${task.operations.size - 3} 条", fontSize = 13.sp)
                }
                if(task.totalOperations > task.operations.size) Text("这里只展示当前窗口；完整记录可在原任务中查看。",
                    fontSize = 13.sp, color = SecondaryInk)
                visible.forEach { operation -> key(operation.id) { ConversationOperation(operation) } }
            }
        }
    }
}

@Composable
private fun ConversationOperation(operation: TaskPresentation.Operation) {
    AffectToolCallCard(id = operation.id, name = operation.tool, statusLabel = operation.label,
        output = operation.output, needsReview = operation.uncertain || operation.state == "failed",
        modifier = Modifier.padding(vertical = 4.dp).testTag("conversation_operation_" + operation.id))
}

/** Uses the existing persisted list; search never changes a conversation or starts a task. */
@Composable
internal fun ConversationHistoryBrowser(loaded: List<ConversationHistory.Item>, activeId: String?, switchingBlocked: Boolean, onOpen: (String) -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier, allowReadWhenBlocked: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(loaded, query) { ConversationHistory.organize(loaded, query, System.currentTimeMillis(), ZoneId.systemDefault()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("你的对话", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew, enabled = !switchingBlocked) { Text("新对话") }
        }
        OutlinedTextField(query, { if (it.length <= 200) query = it }, singleLine = true, label = { Text("按标题查找已保存对话") }, modifier = Modifier.fillMaxWidth().testTag("conversation_history_query"), trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } })
        if (switchingBlocked) Text(if (allowReadWhenBlocked) "任务继续运行；现在只读查看历史，不改变当前输入位置。" else "原任务仍在处理或登记输入，结束后再切换对话。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (groups.isEmpty()) Text(if (loaded.isEmpty()) "开始聊天后，对话会保存在这里。" else "已加载的对话标题中没有匹配项。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            groups.forEach { group ->
                item("group_" + group.label) { Text(group.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(group.items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().background(if (item.id == activeId) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(enabled = !switchingBlocked || allowReadWhenBlocked) { onOpen(item.id) }.semantics { selected = item.id == activeId }.padding(horizontal = 10.dp, vertical = 14.dp)) {
                        Text(item.title.ifBlank { "未命名对话" }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        if (item.updatedAt > 0) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
