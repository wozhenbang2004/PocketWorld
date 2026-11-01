package dev.pocketengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.local.project.ProjectFileTree
import org.json.JSONArray

/** Directory relationships over original file metadata, not another workspace or source cache. */
@Composable
internal fun ProjectFileTreeSection(
    checkoutId: String,
    files: JSONArray,
    dirtyPaths: List<String>,
    selectedPath: String?,
    controller: PocketWorldController,
    completeListing: Boolean = true,
    open: (String) -> Unit,
    inspectBinary: (ProjectFileTree.Node) -> Unit
) {
    val position = remember(controller, checkoutId) { controller.projectWorkbench.fileTree(checkoutId) }
    var query by position.query
    var filter by position.filter
    var expanded by position.expanded
    val treeResult = remember(checkoutId, files, dirtyPaths) { runCatching { ProjectFileTree.from(files, dirtyPaths) } }
    val tree = treeResult.getOrNull()
    treeResult.exceptionOrNull()?.let { Text(it.message ?: "原文件树暂无法核对，未把缺失当作空项目", color = MaterialTheme.colorScheme.error) }
    if (tree == null) return
    LaunchedEffect(checkoutId, selectedPath, tree) {
        if (selectedPath != null) expanded = expanded + tree.ancestors(selectedPath)
    }
    OutlinedTextField(query, { if (it.length <= 160) query = it }, label = { Text("按文件路径筛选") }, singleLine = true,
        trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } },
        modifier = Modifier.fillMaxWidth().testTag("project_file_tree_filter"))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("所有文件", "变更", "未保存草稿").forEachIndexed { index, label ->
            FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
        }
    }
    if (query.isNotBlank() || filter != ProjectFileTree.ALL) Text("仅显示筛选项 · 清除筛选可查看其他文件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val rows = remember(tree, query, filter, expanded) { tree.visible(expanded, query, filter) }
    if (rows.isEmpty()) Text(if (query.isNotEmpty()) "这个路径筛选没有匹配项，其他文件仍保留。" else if (filter == ProjectFileTree.DRAFTS) "没有未保存的文件草稿。" else if (filter == ProjectFileTree.ALL) { if (completeListing) "这里还没有文件，可以新建文本文件。" else "还未读到文件清单，请刷新。" } else "没有文件变更；切回所有文件继续。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
    else {
        if (query.isNotBlank()) Text("匹配 ${rows.size} 个文件或草稿", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(state = position.list, modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("project_file_tree")) {
            items(rows, key = { (if (it.node.directory) "dir:" else if (it.node.draftOnly) "draft:" else "file:") + it.node.path }) { row ->
                val node = row.node
                val selected = !node.directory && node.path == selectedPath
                val action = {
                    if (node.directory) expanded = if (row.expanded) expanded.filterNot { it == node.path || it.startsWith(node.path + "/") }.toSet() else expanded + node.path
                    else if (node.binary) inspectBinary(node) else open(node.path)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Button, onClick = action)
                    .semantics(mergeDescendants = true) {
                        contentDescription = (if (node.directory) "目录 " else if (node.draftOnly) "仅手机草稿 " else "文件 ") + node.path
                        stateDescription = if (node.directory) { if (row.expanded) "已展开" else "已折叠" } else if (node.draft) "有未保存草稿" else if (node.binary) "二进制，只查看信息" else if (selected) "当前文件" else ""
                    }.padding(start = (row.depth.coerceAtMost(8) * 16).dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { Text(if (node.directory) { if (row.expanded) "▾" else "▸" } else "·", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Column(Modifier.weight(1f)) {
                        Text(row.displayName, style = MaterialTheme.typography.bodyMedium, fontFamily = if (node.directory) null else FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val state = if (node.directory) buildString { append("${node.fileCount} 项"); if (node.changedCount > 0) append(" · ${node.changedCount} 项变更"); if (node.draftCount > 0) append(" · ${node.draftCount} 份草稿") }
                            else if (node.draftOnly) "未保存草稿 · 打开后核对当前文件"
                            else buildString {
                                append(when (node.change) { "added" -> "副本新增"; "deleted" -> "已从副本删除"; "modified" -> "副本已修改"; else -> "副本未变更" })
                                if (node.binary) append(" · 二进制，${node.bytes} 字节")
                                if (node.draft) append(" · 有未保存草稿")
                            }
                        Text(state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
