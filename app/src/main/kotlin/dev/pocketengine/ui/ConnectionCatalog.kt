package dev.pocketengine.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Adapted from the retained Affect Integrations reference (public-preview independent
// implementation, 0e69029b). Grouping/search only: activation still enters the original owner page.
@Composable
internal fun ConnectionCatalog(titles: Map<String, String>, description: (String) -> String,
    open: (String) -> Unit, history: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val groups = listOf(
        "模型" to listOf("accounts", "models", "proxy"),
        "工作" to listOf("workProfiles", "local", "devices", "remote", "ssh", "teams", "groups", "channels"),
        "记忆" to listOf("memory", "memoryReview", "automation", "commitments", "learning"),
        "扩展" to listOf("skills", "plugins", "pluginCatalog", "mcp", "media", "realtime")
    )
    val known = groups.flatMap { it.second }.toSet()
    val completeGroups = groups + listOf("其他" to titles.keys.filterNot { it in known })
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(query, { query = it.take(160) }, singleLine = true,
            placeholder = { Text("搜索模型、记忆、工具…", fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ink, unfocusedBorderColor = Line,
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
            trailingIcon = { if(query.isNotEmpty()) AffectToolButton("close", "清除能力搜索", { query = "" }) })
        ConnectionCategoryTabs(listOf("全部", "模型", "工作", "记忆", "扩展"), selected) { selected = it }
        var total = 0
        completeGroups.forEachIndexed { index, (group, ids) ->
            val visible = ids.filter { id -> titles.containsKey(id) &&
                (selected == 0 || selected == index + 1) &&
                (query.isBlank() || titles.getValue(id).contains(query.trim(), ignoreCase = true) ||
                    id.contains(query.trim(), ignoreCase = true)) }
            total += visible.size
            if(visible.isNotEmpty()) {
                Text(group, fontSize = 13.sp, color = SecondaryInk, fontWeight = FontWeight.Medium)
                Surface(Modifier.fillMaxWidth().pocketDepth(PocketDepth.CONTENT, 14.dp),
                    color = Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(.5.dp, Line)) {
                    Column {
                        visible.forEachIndexed { rowIndex, id ->
                            Row(Modifier.fillMaxWidth().clickable { open(id) }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Soft),
                                    contentAlignment = Alignment.Center) {
                                    AffectGlyph(when(group) { "模型" -> "spark"; "记忆" -> "file"; else -> "tool" }, Modifier.size(18.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(titles.getValue(id), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(description(id), color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp))
                                }
                                AffectGlyph("chevron-right", Modifier.size(12.dp), SecondaryInk)
                            }
                            if(rowIndex < visible.lastIndex) HorizontalDivider(Modifier.padding(start = 62.dp), color = Line, thickness = .5.dp)
                        }
                    }
                }
            }
        }
        if(total == 0) Text("没有匹配的能力，试试其他关键词或切回全部。", color = SecondaryInk, fontSize = 14.sp)
        TextButton(onClick = history, modifier = Modifier.heightIn(min = 48.dp)) { Text("模型调用与用量记录") }
    }
}

// Underline presentation adapted from Affect Segmented Control Clean (37b91fa), MIT.
// Native 48dp hit regions remain; no claim of the upstream pointer-drag interaction.
@Composable
private fun ConnectionCategoryTabs(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slot = maxWidth / labels.size
        val offset by animateDpAsState(slot * selected.coerceIn(0, labels.lastIndex),
            animationSpec = spring(dampingRatio = .82f, stiffness = 550f), label = "connectionCategory")
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(selected == index, role = Role.Tab, onClick = { select(index) })
                    .padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = if(selected == index) Ink else SecondaryInk)
                }
            }
        }
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = Line, thickness = .5.dp)
        Box(Modifier.align(Alignment.BottomStart).offset(x = offset).width(slot).height(2.dp).background(Ink))
    }
}
