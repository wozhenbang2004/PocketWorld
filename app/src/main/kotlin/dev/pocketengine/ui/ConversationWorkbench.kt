package dev.pocketengine.ui
import org.json.JSONObject

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
                "bold" -> addStyle(SpanStyle(fontWeight = FontWeight.Medium), start, length)
                "code" -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant), start, length)
                "link" -> addLink(LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) { link(span.url) }, start, length)
            }
        }
    }
    SelectionContainer {
        Text(annotated, modifier = modifier, style = LocalTextStyle.current.copy(color = colors.onSurface, fontSize = if (heading == 1) 21.sp else if (heading > 0) 18.sp else 15.sp,
            lineHeight = if (heading == 1) 31.sp else 25.sp, fontWeight = if (heading > 0) FontWeight.Medium else FontWeight.Normal))
    }
}

@Composable
private fun ConversationCode(block: RichMessageDocument.Block) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.language.ifBlank { "代码" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }, modifier = Modifier.testTag("conversation_copy_code")) { Text(if (copied) "已复制" else "复制代码", fontSize = 12.sp) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SelectionContainer {
            Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                .horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).padding(12.dp).testTag("conversation_code"))
        }
        if (!block.closed) Text("代码段尚未闭合，按原文展示。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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
    onLoadDetails: (String) -> Unit, modifier: Modifier = Modifier,
    interruptedSourceRevision: Long = 0, readInterrupted: ((String, (JSONObject?, String?) -> Unit) -> Unit)? = null,
    answerQuestion: OwnerQuestionSubmit? = null, chooseQuestion: OwnerQuestionSubmit? = null) {
    var expanded by rememberSaveable(task.id, task.runId) {
        mutableStateOf(task.status in setOf("preparing", "running", "awaiting_user", "waiting_approval", "reconciling", "unknown", "failed"))
    }
    LaunchedEffect(task.id, task.runId) {
        if (expanded && !task.detailsLoaded) onLoadDetails(task.id)
    }
    var showAll by rememberSaveable(task.id, task.runId) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        animationSpec = tween(180), label = "conversationTaskDisclosure")
    val needsAttention = task.status in setOf("unknown", "reconciling", "waiting_approval", "awaiting_approval", "failed") ||
        task.operations.any { it.uncertain || it.awaitingApproval }
    Column(modifier.fillMaxWidth().testTag("conversation_task_" + task.id)
        .padding(top = 4.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = task.id.isNotBlank(), role = Role.Button) {
                expanded = !expanded
                if (expanded && !task.detailsLoaded) onLoadDetails(task.id)
            }.semantics { stateDescription = if (expanded) "执行过程已展开" else "执行过程已收起" }
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AffectGlyph(when {
                    task.status in setOf("unknown", "reconciling") -> "question"
                    task.status == "failed" -> "warning"
                    task.confirmedComplete -> "check"
                    else -> "clock"
                }, Modifier.size(16.dp), Ink)
                Text("${task.label} · 执行过程",
                    fontSize = 13.sp, lineHeight = 20.sp, color = Ink,
                    modifier = Modifier.weight(1f), maxLines = 2)
                AffectGlyph("chevron", Modifier.size(12.dp).rotate(rotation), SecondaryInk)
            }
            TextButton(onClick = { onOpenTask(task.id) }, enabled = task.id.isNotBlank(),
                modifier = Modifier.testTag("conversation_open_task"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (needsAttention) "查看并处理" else if(task.confirmedComplete) "成果与详情" else "任务详情", fontSize = 13.sp)
            }
        }
        if (task.status != "completed" && task.next.isNotBlank()) Text(task.next,
            fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk,
            modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        AnimatedVisibility(visible = expanded,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(130)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(100))) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(task.goal, fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                if (task.summary.isNotBlank()) Text(task.summary, fontSize = 13.sp, lineHeight = 21.sp,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                if (!task.detailsLoaded) {
                    Text("原任务记录尚未载入。", fontSize = 13.sp, color = SecondaryInk)
                    TextButton(onClick = { onLoadDetails(task.id) }) { Text("读取记录") }
                } else if (task.operations.isEmpty()) Text("原任务还没有工具调用记录。", fontSize = 13.sp, color = SecondaryInk)
                if (answerQuestion != null && chooseQuestion != null) task.ownerQuestion?.let { question -> key(task.id, interruptedSourceRevision, question.toString()) { OwnerQuestionPanel(question, answerQuestion, chooseQuestion) } }
                if (readInterrupted != null) InterruptedAttemptJournal(task.id, interruptedSourceRevision, readInterrupted)
                val visible = if(showAll) task.operations else task.operations.takeLast(3)
                if(task.operations.size > 3) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(start = 16.dp)) {
                    Text(if(showAll) "只看最近调用" else "展开其余 ${task.operations.size - 3} 条", fontSize = 13.sp)
                }
                if(task.totalOperations > task.operations.size) Text("这里只展示当前窗口；完整记录可在原任务中查看。",
                    fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk,
                    modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
                visible.forEachIndexed { index, operation -> key(operation.id) {
                    ConversationOperation(operation, index == 0, index == visible.lastIndex) { onOpenTask(task.id) }
                } }
            }
        }
    }
}

@Composable
private fun ConversationOperation(operation: TaskPresentation.Operation, first: Boolean, last: Boolean,
    onOpenTask: () -> Unit) {
    val tone = when {
        operation.uncertain -> TaskExecutionPresentation.Tone.UNCERTAIN
        operation.awaitingApproval -> TaskExecutionPresentation.Tone.WAITING
        operation.state == "failed" -> TaskExecutionPresentation.Tone.ERROR
        operation.state == "prepared" -> TaskExecutionPresentation.Tone.WAITING
        else -> TaskExecutionPresentation.Tone.NEUTRAL
    }
    val needsAttention = operation.uncertain || operation.awaitingApproval || operation.state == "failed"
    var expanded by rememberSaveable(operation.id) { mutableStateOf(needsAttention) }
    var manuallyToggled by rememberSaveable(operation.id) { mutableStateOf(false) }
    var showFull by rememberSaveable(operation.id) { mutableStateOf(false) }
    var clipped by remember(operation.output) { mutableStateOf(false) }
    var copied by remember(operation.output) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        animationSpec = tween(160), label = "conversationOperationDisclosure")
    LaunchedEffect(needsAttention) { if (needsAttention && !manuallyToggled) expanded = true }
    Column(Modifier.fillMaxWidth().testTag("conversation_operation_" + operation.id).drawBehind {
        val x = 7.dp.toPx()
        val nodeY = 24.dp.toPx()
        drawLine(Line, Offset(x, if (first) nodeY else 0f),
            Offset(x, if (last) nodeY else size.height), strokeWidth = 1.dp.toPx())
        drawCircle(if (needsAttention) Ink else SecondaryInk, 2.5.dp.toPx(), Offset(x, nodeY))
    }.padding(start = 24.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
            manuallyToggled = true
            expanded = !expanded
        }.semantics { stateDescription = if (expanded) "调用返回已展开" else "调用返回已收起" }
            .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(toolDisplayName(operation.tool), fontSize = 14.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PocketExecutionStatus(operation.label, tone, Modifier.padding(top = 2.dp))
            }
            AffectGlyph("chevron", Modifier.size(12.dp).rotate(rotation), SecondaryInk)
        }
        AnimatedVisibility(visible = expanded,
            enter = expandVertically(animationSpec = tween(170)) + fadeIn(tween(120)),
            exit = shrinkVertically(animationSpec = tween(170)) + fadeOut(tween(90))) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                if (toolDisplayName(operation.tool) != operation.tool) Text(operation.tool,
                    fontFamily = FontFamily.Monospace, color = SecondaryInk, fontSize = 12.sp,
                    lineHeight = 19.sp, modifier = Modifier.padding(bottom = 4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("实际返回", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.weight(1f))
                    AffectToolButton(if (copied) "check" else "copy", if (copied) "已复制" else "复制返回内容",
                        enabled = operation.output.isNotEmpty(), onClick = {
                            try { clipboard.setText(AnnotatedString(operation.output)); copied = true }
                            catch (_: Exception) { copied = false }
                        })
                }
                if (operation.output.isBlank()) Text("没有可展示的返回文本。", fontSize = 13.sp,
                    lineHeight = 21.sp, color = SecondaryInk)
                else SelectionContainer {
                    Text(operation.output, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        lineHeight = 21.sp, color = Ink, maxLines = if (showFull) Int.MAX_VALUE else 8,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!showFull) clipped = it.hasVisualOverflow },
                        modifier = Modifier.fillMaxWidth().heightIn(max = if (showFull) 360.dp else 220.dp)
                            .then(if (showFull) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(vertical = 4.dp))
                }
                if (clipped || showFull) TextButton(onClick = { showFull = !showFull },
                    contentPadding = PaddingValues(0.dp)) {
                    Text(if (showFull) "收起全文" else "展开完整返回", fontSize = 13.sp)
                }
                if (needsAttention) TextButton(onClick = onOpenTask, contentPadding = PaddingValues(0.dp)) {
                    Text(if (operation.awaitingApproval) "在原任务核对并授权" else "在原任务查看并处理", fontSize = 13.sp)
                }
            }
        }
    }
}

/** Uses the existing persisted list; search never changes a conversation or starts a task. */
@Composable
internal fun ConversationHistoryBrowser(loaded: List<ConversationHistory.Item>, activeId: String?, switchingBlocked: Boolean, onOpen: (String) -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier, allowReadWhenBlocked: Boolean = false, onRename: ((ConversationHistory.Item) -> Unit)? = null, onPin: ((ConversationHistory.Item) -> Unit)? = null, managementBusy: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuId by remember { mutableStateOf<String?>(null) }
    val groups = remember(loaded, query) { ConversationHistory.organize(loaded, query, System.currentTimeMillis(), ZoneId.systemDefault()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("你的对话", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew, enabled = !switchingBlocked) { Text("新对话") }
        }
        OutlinedTextField(query, { if (it.length <= 200) query = it }, singleLine = true, label = { Text("按标题查找已保存对话") }, modifier = Modifier.fillMaxWidth().testTag("conversation_history_query"), trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } })
        if (switchingBlocked) Text(if (allowReadWhenBlocked) "任务继续运行；现在只读查看历史，不改变当前输入位置。" else "原任务仍在处理或登记输入，结束后再切换对话。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (groups.isEmpty()) Text(if (loaded.isEmpty()) "开始聊天后，对话会保存在这里。" else "已加载的对话标题中没有匹配项。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            groups.forEach { group ->
                item("group_" + group.label) { Text(group.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(group.items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().background(if (item.id == activeId) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(enabled = !switchingBlocked || allowReadWhenBlocked) { onOpen(item.id) }.semantics { selected = item.id == activeId }.padding(horizontal = 10.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title.ifBlank { "未命名对话" }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            if (onRename != null && onPin != null && item.organizationRevision >= 0) Box {
                                TextButton(onClick = { menuId = item.id }, enabled = !managementBusy, modifier = Modifier.semantics { contentDescription = "管理对话：" + item.title }) { Text("管理…") }
                                DropdownMenu(expanded = menuId == item.id, onDismissRequest = { menuId = null }) {
                                    DropdownMenuItem(text = { Text("改名…") }, onClick = { menuId = null; onRename(item) }, enabled = !managementBusy)
                                    DropdownMenuItem(text = { Text(if (item.pinOrder > 0) "取消置顶" else "置顶") }, onClick = { menuId = null; onPin(item) }, enabled = !managementBusy)
                                }
                            }
                        }
                        if (item.updatedAt > 0) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
