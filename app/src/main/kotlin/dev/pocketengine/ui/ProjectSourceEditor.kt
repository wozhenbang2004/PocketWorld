package dev.pocketengine.ui

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.local.project.ProjectEditorDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Platform IME/draft input, bounded navigation and an optional real TypeScript service.
 * It is neither Sora/LSP nor a source-save/execution authority. */
@Composable
internal fun ProjectSourceEditor(
    checkoutId: String,
    path: String,
    text: String,
    enabled: Boolean,
    controller: PocketWorldController,
    modifier: Modifier = Modifier,
    draft: dev.pocketengine.features.local.project.ProjectFileDraft? = null,
    changedDraft: ((dev.pocketengine.features.local.project.ProjectFileDraft) -> Boolean)? = null,
    change: (String) -> Boolean
) {
    val currentParentText by rememberUpdatedState(text)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentChange by rememberUpdatedState(change)
    val session = controller.projectWorkbench
    val initial = remember(checkoutId, path) { session.editorPosition(checkoutId, path) }
    val initialViewport = remember(checkoutId, path) { session.editorViewport(checkoutId, path) }
    var value by remember(checkoutId, path) { mutableStateOf(TextFieldValue(text, TextRange(editorOffset(text, initial.first), editorOffset(text, initial.second)))) }
    var document by remember(checkoutId, path) { mutableStateOf<ProjectEditorDocument?>(null) }
    var documentError by remember(checkoutId, path) { mutableStateOf<String?>(null) }
    var layout by remember(checkoutId, path) { mutableStateOf<TextLayoutResult?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var restoredScroll by remember(checkoutId, path) { mutableStateOf(false) }
    var restoredSelection by remember(checkoutId, path) { mutableStateOf(false) }
    var notice by remember(checkoutId, path) { mutableStateOf<String?>(null) }
    var find by remember(checkoutId, path) { mutableStateOf(false) }
    var query by rememberSaveable(checkoutId, path) { mutableStateOf("") }
    var replacement by remember(checkoutId, path) { mutableStateOf("") }
    var caseSensitive by rememberSaveable(checkoutId, path) { mutableStateOf(true) }
    var selectionScope by remember(checkoutId, path) { mutableStateOf<EditorSelectionScope?>(null) }
    var origin by remember(checkoutId, path) { mutableStateOf<EditorFindOrigin?>(null) }
    var search by remember(checkoutId, path) { mutableStateOf<ProjectEditorDocument.Search?>(null) }
    var searching by remember(checkoutId, path) { mutableStateOf(false) }
    var searchError by remember(checkoutId, path) { mutableStateOf<String?>(null) }
    var selectedMatch by remember(checkoutId, path) { mutableIntStateOf(-1) }
    var pendingReplaceAll by remember(checkoutId, path) { mutableStateOf<EditorReplaceAll?>(null) }
    var changingDraft by remember(checkoutId, path) { mutableStateOf(false) }
    var jump by remember(checkoutId, path) { mutableStateOf(false) }
    var lineInput by remember(checkoutId, path) { mutableStateOf("") }
    var jumpError by remember(checkoutId, path) { mutableStateOf<String?>(null) }
    var syntaxText by remember(checkoutId, path) { mutableStateOf<String?>(null) }
    var syntax by remember(checkoutId, path) { mutableStateOf<org.json.JSONArray?>(null) }
    val font = session.editorFontSp()
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val ready = document?.takeIf { it.text == value.text && it.text == text }
    val compositionActive = value.composition != null
    val currentSearch = search?.takeIf { it.belongsTo(value.text) && it.belongsTo(text) }

    LaunchedEffect(checkoutId, path, text) {
        if (value.text != text) value = TextFieldValue(text, TextRange(editorOffset(text, value.selection.start), editorOffset(text, value.selection.end)))
        document = null; documentError = null
        try { document = withContext(Dispatchers.Default) { ProjectEditorDocument(text) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // The platform IME may briefly publish an incomplete surrogate while composing.
            // Keep its original TextFieldValue/draft; only document tools become unavailable.
            document = null; documentError = "当前文字暂不能定位或查替，草稿和输入仍保留；完成输入后会重新核对。"
        }
    }
    LaunchedEffect(find, document, query, caseSensitive, selectionScope) {
        search = null; selectedMatch = -1; searchError = null
        val original = document?.takeIf { it.text == text } ?: return@LaunchedEffect
        if (!find || query.isEmpty()) { searching = false; return@LaunchedEffect }
        searching = true
        try {
            delay(120)
            val range = selectionScope
            if (range != null && range.text != original.text) throw IllegalStateException("选区对应的草稿已变化，请重新选择范围或查找整个文件。")
            search = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                original.search(query, caseSensitive, range?.start ?: 0, range?.end ?: original.text.length) { context.ensureActive() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { searchError = failure.message }
        finally { searching = false }
    }
    LaunchedEffect(checkoutId, path, value.selection, restoredSelection) {
        if (restoredSelection) { delay(300); session.rememberEditorPosition(checkoutId, path, value.selection.start, value.selection.end) }
    }
    LaunchedEffect(checkoutId, path, layout, ready?.sha256, enabled) {
        val measured = layout ?: return@LaunchedEffect
        val original = ready ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        if (!restoredSelection) {
            value = value.copy(selection = TextRange(editorOffset(value.text, initial.first), editorOffset(value.text, initial.second)))
            restoredSelection = true
        }
        if (!restoredScroll && measured.layoutInput.text.text == original.text) {
            val saved = initialViewport?.takeIf { it.sourceSha256 == original.sha256 }
            if (saved != null) {
                val offset = saved.firstVisibleOffset.coerceIn(0, original.text.length)
                val line = measured.getLineForOffset(offset)
                vertical.scrollTo((measured.getLineTop(line).toInt() + saved.inLinePixels).coerceIn(0, vertical.maxValue))
                horizontal.scrollTo(saved.horizontalPixels.coerceIn(0, horizontal.maxValue))
            }
            restoredScroll = true
        }
    }
    fun viewport(): ProjectEditorViewport? {
        val measured = layout ?: return null
        val original = document?.takeIf { it.text == value.text && measured.layoutInput.text.text == it.text } ?: return null
        val line = measured.getLineForVerticalPosition(vertical.value.toFloat())
        return ProjectEditorViewport(original.sha256, measured.getLineStart(line),
            (vertical.value - measured.getLineTop(line).toInt()).coerceAtLeast(0), horizontal.value)
    }
    LaunchedEffect(checkoutId, path, vertical.value, horizontal.value, ready?.sha256) {
        if (restoredScroll) { delay(300); viewport()?.let { session.rememberEditorViewport(checkoutId, path, it) } }
    }
    val currentSelection by rememberUpdatedState(if (restoredSelection) value.selection else null)
    val currentViewport by rememberUpdatedState(if (restoredScroll) viewport() else null)
    DisposableEffect(checkoutId, path) {
        onDispose {
            currentSelection?.let { session.rememberEditorPosition(checkoutId, path, it.start, it.end) }
            currentViewport?.let { session.rememberEditorViewport(checkoutId, path, it) }
        }
    }
    fun selectRange(start: Int, end: Int) {
        if (value.composition != null) { notice = "请先完成输入法正在组合的文字，再定位或替换。"; return }
        restoredSelection = true; restoredScroll = true
        value = value.copy(selection = TextRange(start, end))
        layout?.takeIf { it.layoutInput.text.text == value.text }?.let { measured ->
            val rect = measured.getCursorRect(start)
            scope.launch {
                vertical.animateScrollTo((measured.getLineTop(measured.getLineForOffset(start)).toInt() - 20).coerceIn(0, vertical.maxValue))
                if (rect.left < horizontal.value || rect.right > horizontal.value + viewportWidth)
                    horizontal.animateScrollTo((rect.left.toInt() - viewportWidth / 3).coerceIn(0, horizontal.maxValue))
            }
        }
    }
    fun findNext(backwards: Boolean) {
        val found = currentSearch ?: return
        val offset = if (backwards) value.selection.min else value.selection.max
        val next = found.next(offset, backwards)
        if (next >= 0) { val match = found.match(next); selectRange(match.start, match.end); selectedMatch = next; notice = null }
    }
    fun applyReplacement(found: ProjectEditorDocument.Search, all: Boolean, replacementText: String, index: Int) {
        if (!currentEnabled || changingDraft || value.composition != null) { notice = "当前正在输入或文件操作中，请稍后重试。"; return }
        if (!found.belongsTo(value.text) || value.text != currentParentText) { notice = "草稿已变化，请重新查找；没有替换文字。"; return }
        val originalSelection = value.selection
        val originalText = value.text
        changingDraft = true; notice = null
        scope.launch {
            try {
                val changed = withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    if (all) ProjectEditorDocument.replaceAll(found, replacementText, originalText) { context.ensureActive() }
                    else ProjectEditorDocument.replace(found, index, replacementText, originalText)
                }
                if (currentParentText != originalText || value.text != originalText || value.selection != originalSelection || value.composition != null || !currentEnabled)
                    throw IllegalStateException("文字或选区已变化，请重新查找；没有替换文字。")
                if (currentChange(changed.text)) {
                    value = TextFieldValue(changed.text, TextRange(changed.selectionStart, changed.selectionEnd))
                    search = null; selectedMatch = -1
                    notice = "已在草稿替换 ${changed.replaced} 处，尚未保存到文件。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message }
            finally { changingDraft = false }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
    val findHeight = minOf(230.dp, maxHeight * .45f)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val caret = editorOffset(value.text, value.selection.start)
            TextButton(onClick = { lineInput = ready?.lineAt(caret)?.toString().orEmpty(); jumpError = null; jump = true }, enabled = ready != null && !compositionActive && !changingDraft,
                modifier = Modifier.semantics { contentDescription = "跳转到文件行号" }) {
                Text(ready?.let { "${it.lineAt(caret)}:${it.columnAt(caret)} / ${it.lineCount()}行" } ?: if (documentError != null) "定位暂不可用" else "正在读取行号…")
            }
            TextButton(onClick = {
                if (!find) { origin = EditorFindOrigin(value.text, value.selection, vertical.value, horizontal.value); focus.clearFocus() }
                find = !find; notice = null
            }, enabled = !compositionActive && !changingDraft, modifier = Modifier.testTag("project_editor_find")) { Text(if (find) "收起查找" else "查找替换") }
            TextButton(onClick = { session.editorFontSp(font - 1) }, enabled = font > 12, modifier = Modifier.semantics { contentDescription = "减小代码字号" }) { Text("A−") }
            TextButton(onClick = { session.editorFontSp(font + 1) }, enabled = font < 22, modifier = Modifier.semantics { contentDescription = "增大代码字号" }) { Text("A＋") }
        }
        if (draft != null && changedDraft != null) ProjectLanguageTools(controller, draft, value.selection, compositionActive, enabled && !changingDraft,
            select = { start, end -> selectRange(start, end) },
            complete = { next, caret ->
                if (value.composition != null || value.text != currentParentText || !currentEnabled) false
                else if (currentChange(next)) { value = TextFieldValue(next, TextRange(editorOffset(next, caret))); true } else false
            },
            staged = { updated ->
                if (value.composition != null || value.text != currentParentText || !currentEnabled) false
                else if (changedDraft(updated)) { value = TextFieldValue(updated.text, TextRange(editorOffset(updated.text, value.selection.start), editorOffset(updated.text, value.selection.end))); true } else false
            }, syntax = { original, tokens -> syntaxText = original; syntax = tokens })
        if (find) Column(Modifier.fillMaxWidth().heightIn(max = findHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(query, { if (it.length <= ProjectEditorDocument.MAX_QUERY_CHARACTERS) query = it else notice = "查找文字上限512字符。" },
                label = { Text("字面查找") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("project_editor_query"))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(caseSensitive, onClick = { caseSensitive = !caseSensitive }, label = { Text("区分大小写") })
                FilterChip(selectionScope != null, onClick = {
                    if (selectionScope != null) selectionScope = null
                    else if (value.selection.collapsed) notice = "请先在代码中选择文字，再限定查找范围。"
                    else selectionScope = EditorSelectionScope(value.text, value.selection.min, value.selection.max)
                }, enabled = !compositionActive && !changingDraft, label = { Text(if (selectionScope == null) "整个文件" else "仅原选区") })
                origin?.takeIf { it.text == value.text }?.let { original -> TextButton(onClick = {
                    if (!compositionActive) {
                        value = value.copy(selection = original.selection); scope.launch { vertical.scrollTo(original.vertical); horizontal.scrollTo(original.horizontal) }; find = false
                    }
                }, enabled = !compositionActive && !changingDraft) { Text("回原位置") } }
            }
            val status = when {
                searching -> "正在查找…"
                searchError != null -> searchError!!
                query.isEmpty() -> "只查当前草稿，不会自动改动或保存。"
                currentSearch == null -> "正在核对当前草稿…"
                currentSearch.totalMatches == 0 -> "未找到匹配文字。"
                else -> "${currentSearch.totalMatches} 处匹配" + (if (selectedMatch >= 0) " · 当前 ${selectedMatch + 1}" else "") + (if (!currentSearch.complete) " · 仅可定位前4096处，请缩小范围后全部替换。" else "")
            }
            Text(status, style = MaterialTheme.typography.labelMedium, color = if (searchError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { findNext(true) }, enabled = currentSearch?.visibleMatches()?.let { it > 0 } == true && !compositionActive && !changingDraft) { Text("上一处") }
                TextButton(onClick = { findNext(false) }, enabled = currentSearch?.visibleMatches()?.let { it > 0 } == true && !compositionActive && !changingDraft) { Text("下一处") }
            }
            OutlinedTextField(replacement, { if (it.length <= 4096) replacement = it else notice = "替换文字上限4096字符。" },
                label = { Text("替换为（空白表示删除）") }, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("project_editor_replacement"))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val exactSelection = currentSearch?.takeIf { selectedMatch in 0 until it.visibleMatches() }?.match(selectedMatch)?.let { value.selection.min == it.start && value.selection.max == it.end } == true
                OutlinedButton(onClick = { currentSearch?.let { applyReplacement(it, false, replacement, selectedMatch) } }, enabled = enabled && exactSelection && !compositionActive && !changingDraft) { Text("替换选中这一处") }
                TextButton(onClick = { currentSearch?.let { pendingReplaceAll = EditorReplaceAll(it, replacement) } },
                    enabled = enabled && currentSearch?.let { it.complete && it.totalMatches > 0 } == true && !compositionActive && !changingDraft) { Text("全部替换…") }
            }
        }
        documentError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
        if (changingDraft) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(Modifier.fillMaxWidth().weight(1f), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxSize()) {
                val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                val digits = (ready?.lineCount() ?: 1).toString().length.coerceAtLeast(2)
                Canvas(Modifier.width((font * digits * .65f + 16).dp).fillMaxHeight().semantics { contentDescription = "文件行号" }) {
                    val measured = layout?.takeIf { it.layoutInput.text.text == value.text } ?: return@Canvas
                    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { color = gutterColor; typeface = Typeface.MONOSPACE; textSize = font.sp.toPx(); textAlign = AndroidPaint.Align.RIGHT }
                    val topPadding = 14.dp.toPx()
                    var line = measured.getLineForVerticalPosition(vertical.value.toFloat())
                    while (line < measured.lineCount) {
                        val baseline = measured.getLineBaseline(line) - vertical.value + topPadding
                        if (baseline > size.height + paint.textSize) break
                        val logical = ready?.lineAt(editorOffset(value.text, measured.getLineStart(line))) ?: (line + 1)
                        drawContext.canvas.nativeCanvas.drawText(logical.toString(), size.width - 8.dp.toPx(), baseline, paint); line++
                    }
                }
                val commentColor = MaterialTheme.colorScheme.onSurfaceVariant
                val tokenColor = MaterialTheme.colorScheme.onSurface
                val highlighting = remember(syntaxText, syntax, value.text, commentColor, tokenColor) {
                    if (syntaxText != value.text || syntax == null) VisualTransformation.None else VisualTransformation { original ->
                        val styled = AnnotatedString.Builder(original)
                        val tokens = syntax!!
                        for (i in 0 until tokens.length()) {
                            val token = tokens.getJSONObject(i); val start = token.optInt("start", -1); val end = start + token.optInt("length", -1)
                            if (start >= 0 && end > start && end <= original.length) styled.addStyle(when (token.optString("kind")) {
                                "keyword" -> SpanStyle(color = tokenColor, fontWeight = FontWeight.SemiBold)
                                "comment" -> SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)
                                "string" -> SpanStyle(color = commentColor)
                                else -> SpanStyle(color = tokenColor)
                            }, start, end)
                        }
                        TransformedText(styled.toAnnotatedString(), OffsetMapping.Identity)
                    }
                }
                BasicTextField(value, onValueChange = { next ->
                    // Composition/selection-only updates stay in the platform engine; never rebuild
                    // TextFieldValue on every parent acknowledgement and commit an unfinished IME word.
                    if (next.text == text || change(next.text)) { restoredSelection = true; restoredScroll = true; value = next; notice = null }
                }, enabled = enabled && !changingDraft, visualTransformation = highlighting,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = font.sp, lineHeight = (font * 1.55f).sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), onTextLayout = { layout = it },
                    modifier = Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewportWidth = it.width }.horizontalScroll(horizontal).verticalScroll(vertical).padding(14.dp)
                        .testTag("project_fullscreen_source_editor").semantics { contentDescription = "文件草稿编辑器，$path" })
            }
        }
    }
    }
    pendingReplaceAll?.let { pending -> AlertDialog(onDismissRequest = { pendingReplaceAll = null }, title = { Text("替换 ${pending.search.totalMatches} 处文字？") },
        text = { Text("范围：" + (if (pending.search.scopeStart == 0 && pending.search.scopeEnd == value.text.length) "当前文件完整草稿" else "已选择的文字范围") + "。只改草稿，不自动保存、运行或覆盖外部修改。") },
        confirmButton = { TextButton(onClick = { pendingReplaceAll = null; applyReplacement(pending.search, true, pending.replacement, -1) }, enabled = enabled && !compositionActive && !changingDraft) { Text("替换这 ${pending.search.totalMatches} 处") } },
        dismissButton = { TextButton(onClick = { pendingReplaceAll = null }) { Text("保留原文") } }) }
    if (jump) AlertDialog(onDismissRequest = { jump = false }, title = { Text("跳转到行") }, text = { Column {
        OutlinedTextField(lineInput, { lineInput = it.filter(Char::isDigit).take(7); jumpError = null }, label = { Text("1–${ready?.lineCount() ?: 1}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        jumpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = {
        try {
            val original = ready ?: throw IllegalStateException("草稿正在变化，请稍后重试。")
            val offset = original.lineStart(lineInput.toIntOrNull() ?: throw IllegalArgumentException("请输入行号"))
            selectRange(offset, offset); jump = false; focus.clearFocus()
        } catch (failure: Exception) { jumpError = failure.message }
    }, enabled = !compositionActive && !changingDraft) { Text("定位") } }, dismissButton = { TextButton(onClick = { jump = false }) { Text("返回编辑") } })
}

private fun editorOffset(text: String, requested: Int): Int = requested.coerceIn(0, text.length).let { if (ProjectEditorDocument.codePointBoundary(text, it)) it else it - 1 }

private data class EditorSelectionScope(val text: String, val start: Int, val end: Int)
private data class EditorFindOrigin(val text: String, val selection: TextRange, val vertical: Int, val horizontal: Int)
private data class EditorReplaceAll(val search: ProjectEditorDocument.Search, val replacement: String)

/** Read-only paging keeps large diffs/current versions accessible without clipping and then overwriting the tail. */
@Composable
internal fun ProjectReadOnlyTextPages(title: String, text: String, identity: String, modifier: Modifier = Modifier) {
    var offset by rememberSaveable(identity) { mutableIntStateOf(0) }
    val start = offset.coerceIn(0, text.length).let { if (it > 0 && it < text.length && Character.isLowSurrogate(text[it]) && Character.isHighSurrogate(text[it - 1])) it - 1 else it }
    val end = minOf(text.length, start + 12000).let { if (it > start && it < text.length && Character.isHighSurrogate(text[it - 1]) && Character.isLowSurrogate(text[it])) it - 1 else it }
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(if (text.length > 12000) "只读第 ${start + 1}–$end / ${text.length} 字符" else "只读内容，不会写回文件。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { Text(text.substring(start, end).ifEmpty { "（空文件）" }, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
        if (text.length > 12000) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { offset = (start - 12000).coerceAtLeast(0) }, enabled = start > 0) { Text("上一段") }
            TextButton(onClick = { offset = end }, enabled = end < text.length) { Text("下一段") }
        }
    }
}
