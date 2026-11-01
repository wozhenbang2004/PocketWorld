package dev.pocketengine.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Native port: Affect UI Decision Inbox (Clean), 37b91fa302dea59e28ce1eb2ba7acd7ce23296be.
// Copyright (c) 2026 Florian Höller (adfects), MIT, licenses/affectui-MIT.txt.
// This adapter opens the original review. It never approves or hides a task optimistically.
internal data class AffectDecisionItem(val id: String, val title: String,
    val detail: String, val context: String, val primary: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AffectDecisionInbox(items: List<AffectDecisionItem>, onReview: (String) -> Unit) {
    if (items.isEmpty()) return
    Surface(color = Color.White, shape = RoundedCornerShape(10.dp),
        border = BorderStroke(.5.dp, Color(0xffe9e9e9)), shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().pocketDepth(PocketDepth.CONTENT, 10.dp).animateContentSize()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(16.dp).background(Ink, CircleShape), contentAlignment = Alignment.Center) {
                    Text("!", color = Color.White, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                }
                Text("${items.size} 项需要你处理", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Text("已载入", fontSize = 12.sp, color = SecondaryInk)
            }
            items.forEach { item -> key(item.id) {
                var detailOpen by rememberSaveable(item.id) { mutableStateOf(false) }
                HorizontalDivider(thickness = .5.dp, color = Color(0xffeeeeee))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(item.title, fontSize = 15.sp, lineHeight = 22.sp, color = Ink, fontWeight = FontWeight.Medium)
                    if(item.detail.isNotBlank()) Text(item.detail, fontSize = 13.sp, lineHeight = 20.sp,
                        color = SecondaryInk, modifier = Modifier.padding(top = 3.dp))
                    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AffectDecisionAction(item.primary, primary = true) { onReview(item.id) }
                        AffectDecisionAction(if(detailOpen) "收起详情" else "详情", primary = false) { detailOpen = !detailOpen }
                    }
                    AnimatedVisibility(detailOpen) {
                        Text(item.context, fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            } }
        }
    }
}

// Preserve the reference's 36dp coarse-pointer visual while retaining a separate 48dp native hit area.
// Height grows with large fonts; there is only one clickable/semantic action, not a nested duplicate.
@Composable
private fun AffectDecisionAction(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(6.dp), color = if(primary) Ink else Color.White,
            contentColor = if(primary) Color.White else Ink,
            border = if(primary) null else BorderStroke(.5.dp, Color(0xffdedede))) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}
