package dev.pocketengine.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketengine.ui.AffectGlyph
import dev.pocketengine.ui.PocketDepth
import dev.pocketengine.ui.PocketExecutionStatus
import dev.pocketengine.ui.TaskExecutionPresentation
import dev.pocketengine.ui.pocketDepth
import org.json.JSONArray
import org.json.JSONObject

/** Native adaptation of the saved Affect AgentRun plan/activity anatomy, not its timed demo.
 * Uses existing TaskExecutionPresentation and original records; never invents steps or executes work.
 * Reference: Affect reference lab v3, screens/agents.tsx + screens.css (0e69029).
 */
@Composable
internal fun BrowserWorkSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth().pocketDepth(PocketDepth.CONTENT, 16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

internal fun browserDomain(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
    ?: if (url.isBlank() || url == "about:blank") "空白页" else "原链接 · 详见页面"

@Composable
internal fun BrowserPageCard(page: JSONObject, selected: Boolean, onSelect: () -> Unit) {
    Surface(onClick = onSelect, modifier = Modifier.width(228.dp).testTag("browser-page-" + page.optString("tabId")).semantics { this.selected = selected }, shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AffectGlyph("globe", Modifier.size(16.dp), LocalContentColor.current)
                Text(browserDomain(page.optString("url")), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                if (page.optBoolean("selected")) Text("当前", style = MaterialTheme.typography.labelSmall)
            }
            Text(page.optString("title").ifBlank { "未命名页面" }, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(if (page.optString("openedBy") == "task") "属于当前任务" else "由你管理的页面", style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal class BrowserActivityPresentation {
    val expanded = mutableStateOf(false)
    val detail = mutableStateOf<String?>(null)
}

@Composable
internal fun BrowserActivityTrail(task: JSONObject, presentation: BrowserActivityPresentation) {
    val operations = task.optJSONArray("operations") ?: JSONArray()
    val window = TaskExecutionPresentation.recentOperations(operations)
    val records = window.records
    var expanded by presentation.expanded
    var detail by presentation.detail
    val events = task.optJSONArray("events") ?: JSONArray()
    val latest = (0 until events.length()).mapNotNull { events.optJSONObject(it) }.lastOrNull { it.optString("message").isNotBlank() }
    Column(Modifier.fillMaxWidth().testTag("browser-execution-activity").animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("执行活动", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (records.size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else window.expandLabel) }
        }
        if (records.isEmpty()) Text(latest?.optString("message") ?: "尚无工具调用记录；不会把计划当作已经执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val visible = if (expanded) records else records.takeLast(3)
        visible.forEachIndexed { index, record ->
            key(record.key) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) { AffectGlyph(if (record.tone == TaskExecutionPresentation.Tone.UNCERTAIN) "question" else "tool", Modifier.padding(6.dp).size(14.dp)) }
                        if (index < visible.lastIndex) Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = { detail = if (detail == record.key) null else record.key }, contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().testTag("browser-activity-" + record.key).semantics { stateDescription = if (detail == record.key) "已展开" else "已收起" }) {
                            Text(if (record.title.startsWith("browser_task_")) toolLabel(record.title) else record.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            AffectGlyph(if (detail == record.key) "chevron" else "chevron-right", Modifier.size(16.dp))
                        }
                        PocketExecutionStatus(record.label, record.tone)
                        AnimatedVisibility(detail == record.key) {
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("原调用记录 · 只读，不会重新执行", style = MaterialTheme.typography.labelMedium)
                                if (record.runId != task.optString("runId")) Text("此记录来自本任务的先前执行。", style = MaterialTheme.typography.bodySmall)
                                SelectionContainer { Text(listOf("参数", record.arguments.ifBlank { "未提供" }, "回执", record.receipt.ifBlank { "尚无回执" }).joinToString("\n"), Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
        window.summary(expanded)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
