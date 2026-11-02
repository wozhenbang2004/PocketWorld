package dev.pocketengine.ui

import dev.pocketengine.R
import androidx.compose.ui.res.stringResource

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
        if (linkFailure) Text(stringResource(R.string.w8_cv_000), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
    pendingLink?.let { address ->
        AlertDialog(onDismissRequest = { pendingLink = null }, title = { Text(stringResource(R.string.w8_cv_001)) }, text = {
            Column { Text(stringResource(R.string.w8_cv_002)); SelectionContainer { Text(address, modifier = Modifier.padding(top = 12.dp)) } }
        }, confirmButton = { TextButton(onClick = {
            if (RichMessageDocument.safeUrl(address)) try { uriHandler.openUri(address); linkFailure = false } catch (_: Exception) { linkFailure = true }
            pendingLink = null
        }) { Text(stringResource(R.string.w8_cv_003)) } }, dismissButton = { TextButton(onClick = { pendingLink = null }) { Text(stringResource(R.string.w8_cv_004)) } })
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
            Text(block.language.ifBlank { stringResource(R.string.w8_cv_005) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }, modifier = Modifier.testTag("conversation_copy_code")) { Text(if (copied) stringResource(R.string.w8_cv_006) else stringResource(R.string.w8_cv_007), fontSize = 12.sp) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SelectionContainer {
            Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                .horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).padding(12.dp).testTag("conversation_code"))
        }
        if (!block.closed) Text(stringResource(R.string.w8_cv_008), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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
    readAudit: ((String, (org.json.JSONObject?, String?) -> Unit) -> Unit)? = null) {
    var expanded by rememberSaveable(task.id, task.runId) {
        mutableStateOf(task.status in setOf("preparing", "running", "awaiting_user", "waiting_approval", "reconciling", "unknown", "failed"))
    }
    LaunchedEffect(task.id, task.runId) {
        if (expanded && !task.detailsLoaded) onLoadDetails(task.id)
    }
    var showAll by rememberSaveable(task.id, task.runId) { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth().testTag("conversation_task_" + task.id).pocketDepth(PocketDepth.CONTENT, 14.dp),
        shape = RoundedCornerShape(14.dp), color = Color.White) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = task.id.isNotBlank()) {
                expanded = !expanded
                if (expanded && !task.detailsLoaded) onLoadDetails(task.id)
            }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AffectGlyph(when {
                    task.status in setOf("unknown", "reconciling") -> "question"
                    task.status == "failed" -> "warning"
                    task.confirmedComplete -> "check"
                    else -> "clock"
                }, Modifier.size(20.dp), Ink)
                Text(if(task.detailsLoaded) stringResource(R.string.w8_cv_call_count, conversationTaskLabel(task.status), task.operations.size) else stringResource(R.string.w8_cv_process, conversationTaskLabel(task.status)),
                    fontSize = 13.sp, lineHeight = 20.sp, color = Ink,
                    modifier = Modifier.weight(1f), maxLines = 2)
                AffectGlyph("chevron", Modifier.size(12.dp), SecondaryInk)
            }
            TextButton(onClick = { onOpenTask(task.id) }, enabled = task.id.isNotBlank(),
                modifier = Modifier.testTag("conversation_open_task"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if(task.confirmedComplete) stringResource(R.string.w8_cv_009) else stringResource(R.string.w8_cv_010), fontSize = 13.sp)
            }
        }
        if (task.status != "completed" && task.next.isNotBlank()) Text(conversationTaskNext(task.status),
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
                    Text(stringResource(R.string.w8_cv_011), fontSize = 13.sp, color = SecondaryInk)
                    TextButton(onClick = { onLoadDetails(task.id) }) { Text(stringResource(R.string.w8_cv_012)) }
                } else if (task.operations.isEmpty()) Text(stringResource(R.string.w8_cv_013), fontSize = 13.sp, color = SecondaryInk)
                val visible = if(showAll) task.operations else task.operations.takeLast(3)
                if(task.operations.size > 3) TextButton(onClick = { showAll = !showAll }) {
                    Text(if(showAll) stringResource(R.string.w8_cv_014) else stringResource(R.string.w8_cv_more_calls, task.operations.size - 3), fontSize = 13.sp)
                }
                if(task.totalOperations > task.operations.size) Text(stringResource(R.string.w8_cv_015),
                    fontSize = 13.sp, color = SecondaryInk)
                visible.forEach { operation -> key(operation.id) { ConversationOperation(operation) } }
                InterruptedAttemptJournal(task.id, task.interruptedAttempts, readAudit)
            }
        }
    }
    }
}

@Composable
private fun ConversationOperation(operation: TaskPresentation.Operation) {
    val tone = when {
        operation.uncertain -> TaskExecutionPresentation.Tone.UNCERTAIN
        operation.awaitingApproval -> TaskExecutionPresentation.Tone.WAITING
        operation.state == "failed" -> TaskExecutionPresentation.Tone.ERROR
        operation.state == "prepared" -> TaskExecutionPresentation.Tone.WAITING
        else -> TaskExecutionPresentation.Tone.NEUTRAL
    }
    AffectToolCallCard(id = operation.id, name = operation.tool, statusLabel = conversationOperationLabel(operation), tone = tone,
        output = operation.output, needsReview = operation.uncertain || operation.state == "failed",
        modifier = Modifier.padding(vertical = 4.dp).testTag("conversation_operation_" + operation.id))
}

/** Uses the existing persisted list; search never changes a conversation or starts a task. */
@Composable
internal fun ConversationHistoryBrowser(loaded: List<ConversationHistory.Item>, activeId: String?, switchingBlocked: Boolean, onOpen: (String) -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier, allowReadWhenBlocked: Boolean = false,
    onRename: ((ConversationHistory.Item, String) -> Unit)? = null, onPin: ((ConversationHistory.Item, Boolean) -> Unit)? = null, onDelete: ((ConversationHistory.Item) -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ConversationHistory.Item?>(null) }
    var deleting by remember { mutableStateOf<ConversationHistory.Item?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val groups = remember(loaded, query) { ConversationHistory.organize(loaded, query, System.currentTimeMillis(), ZoneId.systemDefault()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.w8_cv_016), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew, enabled = !switchingBlocked) { Text(stringResource(R.string.w8_cv_017)) }
        }
        OutlinedTextField(query, { if (it.length <= 200) query = it }, singleLine = true, label = { Text(stringResource(R.string.w8_cv_018)) }, modifier = Modifier.fillMaxWidth().testTag("conversation_history_query"), trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text(stringResource(R.string.w8_cv_019)) } })
        if (switchingBlocked) Text(if (allowReadWhenBlocked) stringResource(R.string.w8_cv_020) else stringResource(R.string.w8_cv_021), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (groups.isEmpty()) Text(if (loaded.isEmpty()) stringResource(R.string.w8_cv_022) else stringResource(R.string.w8_cv_023), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            groups.forEach { group ->
                item("group_" + group.label) { Text(conversationDateLabel(group.label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(group.items, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().background(if (item.id == activeId) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(enabled = !switchingBlocked || allowReadWhenBlocked) { onOpen(item.id) }.semantics { selected = item.id == activeId }.padding(horizontal = 10.dp, vertical = 14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title.ifBlank { stringResource(R.string.w8_cv_024) }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            if (item.updatedAt > 0) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (onRename != null || onPin != null || onDelete != null) Box {
                            TextButton(onClick = { menuFor = item.id }, modifier = Modifier.testTag("conversation_menu_" + item.id)) { Text(stringResource(R.string.w8_cv_025)) }
                            DropdownMenu(expanded = menuFor == item.id, onDismissRequest = { menuFor = null }) {
                                if (onRename != null) DropdownMenuItem(text = { Text(stringResource(R.string.w8_cv_026)) }, onClick = { menuFor = null; name = item.title; renaming = item })
                                if (onPin != null) DropdownMenuItem(text = { Text(if (item.pinnedAt > 0) stringResource(R.string.w8_cv_027) else stringResource(R.string.w8_cv_028)) }, onClick = { menuFor = null; onPin(item, item.pinnedAt == 0L) })
                                if (onDelete != null) DropdownMenuItem(text = { Text(stringResource(R.string.w8_cv_029)) }, enabled = !switchingBlocked, onClick = { menuFor = null; deleting = item })
                            }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { item -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.w8_cv_030)) }, text = {
        OutlinedTextField(name, { if (it.length <= 120) name = it }, label = { Text(stringResource(R.string.w8_cv_031)) }, singleLine = true, modifier = Modifier.testTag("conversation_rename_input"))
    }, confirmButton = { TextButton(onClick = { onRename?.invoke(item, name.trim()); renaming = null }, enabled = name.isNotBlank()) { Text(stringResource(R.string.w8_cv_032)) } }, dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.w8_cv_004)) } }) }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.w8_cv_033)) }, text = {
        Text(stringResource(R.string.w8_cv_034))
    }, confirmButton = { TextButton(onClick = { onDelete?.invoke(item); deleting = null }, enabled = !switchingBlocked) { Text(stringResource(R.string.w8_cv_035)) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.w8_cv_036)) } }) }
}
