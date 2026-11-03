package dev.pocketengine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Native port of our retained Affect UI reference, pin 0e69029b.
// ChatComposer is the public-preview independent implementation, not paid upstream TSX.
// Layout/source identity is retained in the project reference; business state is supplied by callers.
@Composable
internal fun AffectGlyph(name: String, modifier: Modifier = Modifier, color: Color = Ink) {
    val data = when (name) {
        "tool" -> "M10.2 2.4a3.5 3.5 0 0 0-4.3 4.6L2.6 10.3a1.4 1.4 0 0 0 2 2L7.9 9a3.5 3.5 0 0 0 4.6-4.3l-2 2-1.6-.4-.4-1.6 1.7-2.3Z"
        "globe" -> "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0M3 12h18M12 3c-4 4-4 14 0 18M12 3c4 4 4 14 0 18"
        "clock" -> "M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0"
        "plus" -> "M12 4v16M4 12h16"
        "arrow" -> "m12 19 0-14m-6 6 6-6 6 6"
        "mic" -> "M9 4a3 3 0 0 1 6 0v8a3 3 0 0 1-6 0ZM5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"
        "chevron" -> "m8 10 4 4 4-4"
        "chevron-right" -> "m10 6 6 6-6 6"
        "file" -> "M6 2h8l4 4v16H6ZM14 2v5h4M9 12h6m-6 4h6"
        "copy" -> "M8 8h12v12H8ZM4 15H3V3h12v1"
        "close" -> "m6 6 12 12M18 6 6 18"
        "question" -> "M9.1 9a3 3 0 1 1 5 2.2c-1.3.7-2.1 1.1-2.1 2.8M12 17h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0"
        "warning" -> "m12 3 10 18H2ZM12 9v5M12 17h.01"
        "stop" -> "M7 7h10v10H7Z"
        "check" -> "m5 12 4 4L19 6"
        else -> "m12 2 2.4 7.6L22 12l-7.6 2.4L12 22l-2.4-7.6L2 12l7.6-2.4Z"
    }
    val viewport = if (name == "tool") 16f else 24f
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(modifier.size(18.dp)) {
        withTransform({ scale(size.width / viewport, size.height / viewport, pivot = Offset.Zero) }) {
            if (name == "more") {
                listOf(4f, 12f, 20f).forEach { x ->
                    drawCircle(color, radius = .6f, center = Offset(x, 12f),
                        style = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            } else drawPath(path, color, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun AffectToolButton(icon: String, label: String, onClick: () -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
        AffectGlyph(icon, color = if (enabled) SecondaryInk else SecondaryInk.copy(alpha = .4f))
    }
}

@Composable
internal fun AffectComposerFrame(
    modifier: Modifier = Modifier,
    editor: @Composable ColumnScope.() -> Unit,
    toolbar: @Composable RowScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit
) {
    Column(modifier.fillMaxWidth().pocketDepth(PocketDepth.INPUT, 16.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(.5.dp, Line), shadowElevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                editor()
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = toolbar)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = footer)
    }
}

@Composable
internal fun AffectSendButton(label: String, onClick: () -> Unit, enabled: Boolean,
    submitting: Boolean = false, modifier: Modifier = Modifier) {
    Box(Modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(if(enabled) Ink else MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = label }) {
            if(submitting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = SecondaryInk)
            else AffectGlyph("arrow", color = if(enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        }
    }
}
