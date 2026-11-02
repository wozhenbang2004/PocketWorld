package dev.pocketengine.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun PocketSegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    PocketSegmentedTabs(labels, selected, onSelect, true)

@Composable
internal fun PocketSegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean) {
    if (labels.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Soft)) {
        val slotWidth = ((maxWidth - 8.dp) / labels.size).coerceAtLeast(0.dp)
        val position by animateDpAsState(
            targetValue = slotWidth * selected.coerceIn(0, labels.lastIndex),
            animationSpec = spring(dampingRatio = .82f, stiffness = 550f),
            label = "projectFacetPosition"
        )
        Box(Modifier.matchParentSize().padding(4.dp)) {
            Box(Modifier.offset(x = position).width(slotWidth).fillMaxHeight()
                .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface))
        }
        Row(Modifier.fillMaxWidth().padding(4.dp).selectableGroup()) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = selected == index, enabled = enabled, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected == index) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected == index) ActionInk else SecondaryInk,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}
