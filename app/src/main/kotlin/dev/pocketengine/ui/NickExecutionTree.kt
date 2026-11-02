package dev.pocketengine.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Native port of the retained independent Nick stepper reference (agent-stepper.js f0e92405...).
// Visual target: author's frame17, https://x.com/n___vc/status/2115869056582795401.
// No upstream implementation/license was obtained; do not describe this as upstream MIT code.
// Only supplied native facts select states. No demo timeline, generated durations, or execution side effects.
internal enum class NickStepState { PENDING, RUNNING, COMPLETE, RETURNED, WAITING, LOCKED, ERROR, UNKNOWN, STOPPED }
internal data class NickSubstepData(val id: String, val title: String, val state: NickStepState, val stateLabel: String? = null)
internal data class NickStepData(val id: String, val title: String, val state: NickStepState,
    val durationMillis: Long? = null, val children: List<NickSubstepData> = emptyList(),
    val gateLabel: String? = null, val stateLabel: String? = null)
private val NickBackground get() = Soft
private val NickForeground get() = Ink
private val NickMuted get() = SecondaryInk
private val NickPending = Color(0xff777777)
private val NickLine = Color(0xffb6b6b6)

/** Presentation only. Disclosure never resumes, approves or settles the native task. */
@Composable
internal fun NickExecutionTree(steps: List<NickStepData>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val identities = steps.map { it.id }
    val rowHeights = remember(identities) { mutableStateMapOf<String, Int>() }
    val childRowHeights = remember(identities) { mutableStateMapOf<Pair<String, String>, Int>() }
    val visibleChildHeights = remember(identities) { mutableStateMapOf<String, Int>() }
    Surface(modifier.fillMaxWidth(), color = NickBackground, contentColor = NickForeground,
        shape = RoundedCornerShape(12.dp)) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val mainX = 8.dp.toPx()
                val childX = 21.dp.toPx()
                val points = ArrayList<Offset>()
                var top = 0f
                steps.forEachIndexed { index, step ->
                    val rowHeight = rowHeights[step.id]?.toFloat() ?: 44.dp.toPx()
                    val visibleHeight = (visibleChildHeights[step.id] ?: 0).toFloat()
                    points += Offset(mainX, top + rowHeight / 2f)
                    if (visibleHeight > 0f) {
                        val childTop = top + rowHeight
                        var childOffset = 4.dp.toPx()
                        step.children.forEach { child ->
                            val height = childRowHeights[step.id to child.id]?.toFloat() ?: 30.dp.toPx()
                            val center = childOffset + height / 2f
                            // The native transition clips from the top: do not draw toward a hidden node.
                            if (center <= visibleHeight) points += Offset(childX, childTop + center)
                            childOffset += height
                        }
                        if (index == steps.lastIndex) {
                            // A final expanded item also bends back to the original main spine.
                            val end = childTop + visibleHeight
                            if (points.last().y < end) points += Offset(mainX, end)
                        }
                    }
                    top += rowHeight + visibleHeight
                }
                if (points.size > 1) {
                    // This is one continuous spine through actual measured nodes, not two parallel rails.
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.zipWithNext().forEach { (from, to) ->
                            if (from.x == to.x) lineTo(to.x, to.y)
                            else {
                                val bend = minOf(10.dp.toPx(), (to.y - from.y).coerceAtLeast(0f) / 2f)
                                cubicTo(from.x, from.y + bend, to.x, to.y - bend, to.x, to.y)
                            }
                        }
                    }
                    drawPath(path, NickLine,
                        style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Column {
                steps.forEach { step -> key(step.id) {
                    var disclosure by rememberSaveable(step.id) { mutableStateOf<Boolean?>(null) }
                    val childAttention = nickChildAttention(step.children)
                    val hasChildren = step.children.isNotEmpty()
                    val expanded = hasChildren && (disclosure ?: (
                        step.state in setOf(NickStepState.RUNNING, NickStepState.LOCKED,
                            NickStepState.ERROR, NickStepState.UNKNOWN, NickStepState.WAITING) ||
                            childAttention != null))
                    val status = step.stateLabel?.takeIf { it.isNotBlank() } ?: nickStateDescription(step.state)
                    val visibleStatus = if (hasChildren && !expanded && childAttention != null)
                        status + " · " + childAttention else status
                    val interaction = if (hasChildren) Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起子步骤" else "展开子步骤"
                    ) { disclosure = !expanded } else Modifier
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).then(interaction)
                        .onSizeChanged { if (rowHeights[step.id] != it.height) rowHeights[step.id] = it.height }
                        .semantics(mergeDescendants = true) {
                            stateDescription = if (hasChildren)
                                visibleStatus + (if (expanded) "，已展开" else "，已收起") else visibleStatus
                        }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                            NickNode(step.state)
                        }
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(step.title, color = NickForeground,
                                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp,
                                    fontWeight = if (step.state == NickStepState.RUNNING)
                                        FontWeight.Medium else FontWeight.Normal))
                            Text(visibleStatus, Modifier.clearAndSetSemantics { }, color = NickMuted,
                                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp))
                        }
                        Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                            step.gateLabel?.takeIf { it.isNotBlank() && it != status }?.let { label ->
                                Text(label, color = NickForeground,
                                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp,
                                        fontWeight = FontWeight.Medium))
                            }
                            if (step.durationMillis != null && step.durationMillis >= 0 &&
                                step.state != NickStepState.PENDING) {
                                // Only a recorded source duration. Opening this surface starts no clock.
                                Text((step.durationMillis / 1000).toString() + "s", color = NickMuted,
                                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp,
                                        fontFeatureSettings = "tnum"))
                            }
                        }
                        if (hasChildren) NickDisclosure(expanded, Modifier.padding(start = 8.dp))
                    }
                    Box(Modifier.fillMaxWidth().onSizeChanged {
                        if (visibleChildHeights[step.id] != it.height) visibleChildHeights[step.id] = it.height
                    }) {
                        // Native Compose transitions respect system duration scaling and reverse in place.
                        androidx.compose.animation.AnimatedVisibility(expanded,
                            enter = expandVertically(tween(180, easing = FastOutSlowInEasing),
                                expandFrom = Alignment.Top) + fadeIn(tween(120)),
                            exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing),
                                shrinkTowards = Alignment.Top) + fadeOut(tween(90))) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                step.children.forEach { child -> key(child.id) {
                                    val childStatus = child.stateLabel?.takeIf { it.isNotBlank() }
                                        ?: nickStateDescription(child.state)
                                    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp)
                                        .onSizeChanged {
                                            val id = step.id to child.id
                                            if (childRowHeights[id] != it.height) childRowHeights[id] = it.height
                                        }.semantics(mergeDescendants = true) {
                                            contentDescription = child.title
                                            stateDescription = childStatus
                                        }, verticalAlignment = Alignment.CenterVertically) {
                                        Canvas(Modifier.width(36.dp).height(16.dp)) {
                                            val center = Offset(21.dp.toPx(), size.height / 2f)
                                            drawCircle(NickBackground, 5.dp.toPx(), center)
                                            val color = if (child.state == NickStepState.PENDING) NickPending else NickForeground
                                            if (child.state == NickStepState.COMPLETE)
                                                drawCircle(color, 3.dp.toPx(), center)
                                            else drawCircle(color, 3.dp.toPx(), center,
                                                style = Stroke(1.2.dp.toPx()))
                                        }
                                        Text(buildAnnotatedString {
                                            append(child.title)
                                            withStyle(SpanStyle(color = NickMuted, fontSize = 12.sp)) {
                                                append(" · " + childStatus)
                                            }
                                        }, Modifier.weight(1f).padding(vertical = 4.dp).clearAndSetSemantics { },
                                            color = NickMuted,
                                            style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp))
                                    }
                                } }
                            }
                        }
                    }
                } }
            }
        }
    }
}

private fun nickChildAttention(children: List<NickSubstepData>): String? = when {
    children.any { it.state == NickStepState.UNKNOWN } -> "子步骤有结果待核对"
    children.any { it.state == NickStepState.ERROR } -> "子步骤有失败"
    children.any { it.state == NickStepState.LOCKED } -> "子步骤等待确认"
    children.any { it.state == NickStepState.WAITING } -> "子步骤仍在等待"
    children.any { it.state == NickStepState.RUNNING } -> "子步骤正在执行"
    else -> null
}

@Composable
private fun NickDisclosure(expanded: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp)) {
        val middle = size.height / 2f
        val edge = 2.5.dp.toPx()
        val direction = if (expanded) -1f else 1f
        val path = Path().apply {
            moveTo(edge, middle - direction * edge / 2f)
            lineTo(size.width / 2f, middle + direction * edge / 2f)
            lineTo(size.width - edge, middle - direction * edge / 2f)
        }
        drawPath(path, NickMuted, style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun nickStateDescription(state: NickStepState): String = when (state) {
    NickStepState.PENDING -> "待执行"
    NickStepState.RUNNING -> "执行中"
    NickStepState.COMPLETE -> "已完成"
    NickStepState.RETURNED -> "已有返回，等待后续处理"
    NickStepState.WAITING -> "等待中"
    NickStepState.LOCKED -> "等待确认"
    NickStepState.ERROR -> "失败"
    NickStepState.UNKNOWN -> "结果待核实"
    NickStepState.STOPPED -> "已停止"
}

@Composable
private fun NickNode(state: NickStepState) {
    Canvas(Modifier.size(16.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = 8.dp.toPx()
        val complete = state == NickStepState.COMPLETE
        drawCircle(if (complete) NickForeground else NickBackground, radius, center)
        drawCircle(if (state == NickStepState.PENDING) Color(0xff414141) else NickForeground,
            radius - .7.dp.toPx(), center, style = Stroke(1.4.dp.toPx()))
        val color = if (complete) NickBackground else NickForeground
        val unit = size.width / 16f
        fun line(x: Float, y: Float, x2: Float, y2: Float, width: Float = 1.3f) =
            drawLine(color, Offset(x * unit, y * unit), Offset(x2 * unit, y2 * unit), width * unit, StrokeCap.Round)
        when (state) {
            NickStepState.COMPLETE -> { line(4.8f, 8.1f, 7f, 10.3f); line(7f, 10.3f, 11.2f, 5.9f) }
            NickStepState.RUNNING -> { line(8f, 4f, 8f, 8f); line(8f, 8f, 5.2f, 8f) }
            NickStepState.LOCKED -> {
                drawRoundRect(color, Offset(5f * unit, 7f * unit), androidx.compose.ui.geometry.Size(6f * unit, 5f * unit),
                    androidx.compose.ui.geometry.CornerRadius(unit, unit))
                drawArc(color, 180f, 180f, false, Offset(6f * unit, 3.8f * unit),
                    androidx.compose.ui.geometry.Size(4f * unit, 6f * unit), style = Stroke(1.2f * unit))
            }
            NickStepState.ERROR -> { line(5.5f, 5.5f, 10.5f, 10.5f); line(10.5f, 5.5f, 5.5f, 10.5f) }
            NickStepState.UNKNOWN -> { line(8f, 4.8f, 8f, 8.3f); drawCircle(color, .8f * unit, Offset(8f * unit, 11f * unit)) }
            NickStepState.STOPPED -> line(5f, 8f, 11f, 8f)
            NickStepState.RETURNED -> {
                // A returned operation is not a verified completion.
                line(11f, 5f, 11f, 8.5f); line(11f, 8.5f, 5f, 8.5f)
                line(5f, 8.5f, 7.5f, 6f); line(5f, 8.5f, 7.5f, 11f)
            }
            NickStepState.WAITING -> { line(6f, 5f, 6f, 11f); line(10f, 5f, 10f, 11f) }
            NickStepState.PENDING -> Unit
        }
    }
}
