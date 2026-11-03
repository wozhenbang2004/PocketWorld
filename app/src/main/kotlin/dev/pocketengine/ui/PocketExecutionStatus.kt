package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Presentation of an original execution fact, never a second task state. */
@Composable
internal fun PocketExecutionStatus(
    label: String,
    tone: TaskExecutionPresentation.Tone,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    animateRunning: Boolean = false
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tone == TaskExecutionPresentation.Tone.RUNNING && animateRunning) {
            ExecutionRays(Modifier.size(16.dp))
        } else {
            val icon = when (tone) {
                TaskExecutionPresentation.Tone.RUNNING, TaskExecutionPresentation.Tone.WAITING -> "clock"
                TaskExecutionPresentation.Tone.UNCERTAIN -> "question"
                TaskExecutionPresentation.Tone.ERROR -> "warning"
                TaskExecutionPresentation.Tone.RETURNED -> "check"
                TaskExecutionPresentation.Tone.STOPPED -> "stop"
                else -> "tool"
            }
            AffectGlyph(icon, Modifier.size(16.dp), if (prominent) Ink else SecondaryInk)
        }
        Text(label, style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (prominent) FontWeight.Medium else FontWeight.Normal,
            color = if (prominent) Ink else SecondaryInk, modifier = Modifier.weight(1f))
    }
}

/** Native monochrome port of Affect UI Thinking Loader ray geometry (37b91fa, MIT).
 * Copyright (c) 2026 Florian Höller (adfects); see licenses/affectui-MIT.txt.
 * No demo phases, elapsed times, or automatic completion are synthesized. */
@Composable
private fun ExecutionRays(modifier: Modifier) {
    val ink = Ink
    val transition = rememberInfiniteTransition(label = "executionRays")
    val phase by transition.animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)), label = "executionRayPhase")
    Canvas(modifier) {
        val scale = size.minDimension / 16f
        for (index in 0 until 8) {
            val progress = (phase + 1f - index / 8f) % 1f
            rotate(index * 45f, center) {
                drawLine(ink.copy(alpha = 1f - .8f * progress),
                    Offset(center.x, center.y - 6.25f * scale),
                    Offset(center.x, center.y - 3.25f * scale),
                    strokeWidth = 1.1f * scale, cap = StrokeCap.Round)
            }
        }
    }
}
