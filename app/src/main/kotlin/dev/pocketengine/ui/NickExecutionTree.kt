package dev.pocketengine.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
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
private val NickBackground = Color(0xff131313)
private val NickBorder = Color(0xff292929)
private val NickForeground = Color(0xffe8e8e8)
private val NickMuted = Color(0xff828282)
private val NickPending = Color(0xff858585)
private val NickLine = Color(0xff383838)

/** Read-only display. Native owners retain every execution, approval and retry action. */
@Composable
internal fun NickExecutionTree(steps: List<NickStepData>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    val identities = steps.map { it.id }
    val rowHeights = remember(identities) { mutableStateMapOf<String, Int>() }
    val childHeights = remember(identities) { mutableStateMapOf<String, Int>() }
    Surface(modifier.widthIn(max = 352.dp).fillMaxWidth(), color = NickBackground,
        contentColor = NickForeground, shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, NickBorder)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val x = 8.dp.toPx()
                var y = 0f
                steps.forEachIndexed { index, step ->
                    val row = (rowHeights[step.id]?.toFloat() ?: 32.dp.toPx())
                    val children = (childHeights[step.id] ?: 0).toFloat()
                    val a = y + row / 2f
                    if (index < steps.lastIndex) {
                        val next = rowHeights[steps[index + 1].id]?.toFloat() ?: 32.dp.toPx()
                        val b = y + row + children + next / 2f
                        // Same measured two-bend spine as the retained reference. The text axis does not indent.
                        val offset = minOf(13.dp.toPx(), children / 2f)
                        val top = a + 15.dp.toPx()
                        val bottom = b - 15.dp.toPx()
                        val bend = minOf(9.dp.toPx(), ((bottom - top) / 3f).coerceAtLeast(0f))
                        val path = Path().apply {
                            moveTo(x, a)
                            if (offset > .1f && bottom > top) {
                                lineTo(x, top - bend)
                                cubicTo(x, top + 2.dp.toPx(), x + offset, top - 2.dp.toPx(), x + offset, top + bend)
                                lineTo(x + offset, bottom - bend)
                                cubicTo(x + offset, bottom + 2.dp.toPx(), x, bottom - 2.dp.toPx(), x, bottom + bend)
                            }
                            lineTo(x, b)
                        }
                        val complete = step.state == NickStepState.COMPLETE
                        drawPath(path, if (complete) NickForeground else NickLine,
                            style = Stroke((if (complete) 1.7.dp else 1.3.dp).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    } else if (children > 10.dp.toPx()) {
                        // Real work plans may end at an active item, unlike the demo's fixed final approval gate.
                        val end = y + row + children - 17.dp.toPx()
                        val top = a + 15.dp.toPx()
                        val inset = x + minOf(13.dp.toPx(), children / 2f)
                        val path = Path().apply {
                            moveTo(x, a); lineTo(x, top - 9.dp.toPx())
                            cubicTo(x, top + 2.dp.toPx(), inset, top - 2.dp.toPx(), inset, top + 9.dp.toPx())
                            lineTo(inset, maxOf(top + 9.dp.toPx(), end))
                        }
                        drawPath(path, NickLine, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
                    }
                    y += row + children
                }
            }
            Column {
                steps.forEach { step -> key(step.id) {
                    val titleColor = when (step.state) {
                        NickStepState.COMPLETE -> NickMuted
                        NickStepState.PENDING -> NickPending
                        else -> NickForeground
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp)
                        .onSizeChanged { if (rowHeights[step.id] != it.height) rowHeights[step.id] = it.height }
                        .semantics(mergeDescendants = true) { stateDescription = step.stateLabel ?: nickStateDescription(step.state) },
                        verticalAlignment = Alignment.CenterVertically) {
                        NickNode(step.state)
                        Spacer(Modifier.width(20.dp))
                        Text(step.title, Modifier.weight(1f), color = titleColor,
                            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
                        if (!step.gateLabel.isNullOrBlank()) {
                            Text(step.gateLabel, Modifier.padding(start = 8.dp)
                                .background(Color(0xff303030), RoundedCornerShape(20.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp), color = NickForeground,
                                style = TextStyle(fontSize = 10.sp, lineHeight = 14.sp))
                        } else if (step.durationMillis != null && step.durationMillis >= 0 &&
                            step.state != NickStepState.PENDING) {
                            // No clock advances this value: it is a recorded duration supplied by the native source.
                            Text("${step.durationMillis / 1000}s", Modifier.padding(start = 10.dp), color = NickPending,
                                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"))
                        }
                    }
                    val showChildren = step.children.isNotEmpty() && step.state in setOf(
                        NickStepState.RUNNING, NickStepState.LOCKED, NickStepState.ERROR,
                        NickStepState.UNKNOWN, NickStepState.WAITING)
                    Box(Modifier.fillMaxWidth().onSizeChanged {
                        if (childHeights[step.id] != it.height) childHeights[step.id] = it.height
                    }) {
                    androidx.compose.animation.AnimatedVisibility(showChildren,
                        enter = expandVertically(tween(340, easing = CubicBezierEasing(.22f, .8f, .26f, 1f))) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(340, easing = CubicBezierEasing(.22f, .8f, .26f, 1f))) + fadeOut(tween(200))) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            step.children.forEach { child -> key(child.id) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 24.dp)
                                    .semantics(mergeDescendants = true) { stateDescription = child.stateLabel ?: nickStateDescription(child.state) },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Canvas(Modifier.width(36.dp).height(18.dp)) {
                                        val center = Offset(21.dp.toPx(), size.height / 2f)
                                        drawCircle(NickBackground, 6.5.dp.toPx(), center)
                                        drawCircle(if (child.state == NickStepState.RUNNING) NickForeground else Color(0xff777777),
                                            2.5.dp.toPx(), center)
                                    }
                                    val childLabel = child.title + when (child.state) {
                                        NickStepState.UNKNOWN -> " · 待核对"
                                        NickStepState.ERROR -> " · 失败"
                                        NickStepState.WAITING, NickStepState.LOCKED -> " · 等待中"
                                        NickStepState.STOPPED -> " · 已停止"
                                        else -> ""
                                    }
                                    Text(childLabel, Modifier.weight(1f).padding(vertical = 3.dp),
                                        color = if (child.state == NickStepState.RUNNING) Color(0xffb6b6b6) else Color(0xff8b8b8b),
                                        style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp))
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
            NickStepState.RETURNED -> { line(4.8f, 8.1f, 7f, 10.3f); line(7f, 10.3f, 11.2f, 5.9f) }
            NickStepState.WAITING -> { line(6f, 5f, 6f, 11f); line(10f, 5f, 10f, 11f) }
            NickStepState.PENDING -> Unit
        }
    }
}
