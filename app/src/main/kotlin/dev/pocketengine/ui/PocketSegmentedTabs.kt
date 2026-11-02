package dev.pocketengine.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private data class PocketTabBounds(val x: Float, val width: Float)

@Composable
internal fun PocketSegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    PocketSegmentedTabs(labels, selected, onSelect, true)

@Composable
internal fun PocketSegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean) {
    if (labels.isEmpty()) return
    val active = selected.coerceIn(0, labels.lastIndex)
    val bounds = remember(labels) { mutableStateMapOf<Int, PocketTabBounds>() }
    val focus = remember(labels) { List(labels.size) { FocusRequester() } }
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val selectedBounds = bounds[active]
    val indicatorWidth = with(density) { 28.dp.toPx() }
    val indicatorHeight = with(density) { 2.dp.toPx() }
    val targetX = selectedBounds?.let { it.x + (it.width - indicatorWidth) / 2 } ?: 0f
    val indicatorX = if (selectedBounds != null) {
        val position by animateFloatAsState(
            targetValue = targetX,
            animationSpec = spring(dampingRatio = .9f, stiffness = 600f),
            label = "pocketTabIndicator"
        )
        position
    } else 0f
    val ink = if (enabled) ActionInk else SecondaryInk.copy(alpha = .6f)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()
            .drawWithContent {
                drawContent()
                if (selectedBounds != null) drawRoundRect(
                    color = ink,
                    topLeft = Offset(indicatorX, size.height - indicatorHeight),
                    size = Size(indicatorWidth, indicatorHeight),
                    cornerRadius = CornerRadius(indicatorHeight, indicatorHeight)
                )
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            Text(
                text = label,
                modifier = Modifier.widthIn(min = 48.dp, max = 176.dp).heightIn(min = 48.dp)
                    .onGloballyPositioned { coordinates ->
                        val value = PocketTabBounds(coordinates.positionInParent().x, coordinates.size.width.toFloat())
                        if (bounds[index] != value) bounds[index] = value
                    }
                    .focusRequester(focus[index])
                    .onPreviewKeyEvent { event ->
                        if (!enabled || event.type != KeyEventType.KeyDown) false else {
                            val next = when (event.key) {
                                Key.DirectionRight -> if (direction == LayoutDirection.Ltr) index + 1 else index - 1
                                Key.DirectionLeft -> if (direction == LayoutDirection.Ltr) index - 1 else index + 1
                                Key.MoveHome -> 0
                                Key.MoveEnd -> labels.lastIndex
                                else -> null
                            }
                            if (next == null) false else {
                                val destination = next.coerceIn(0, labels.lastIndex)
                                if (destination != active) onSelect(destination)
                                focus[destination].requestFocus()
                                true
                            }
                        }
                    }
                    .selectable(selected = active == index, enabled = enabled, role = Role.Tab,
                        onClick = { if (active != index) onSelect(index) })
                    .padding(horizontal = 10.dp, vertical = 13.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (!enabled) SecondaryInk.copy(alpha = .6f)
                    else if (active == index) ActionInk else SecondaryInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
