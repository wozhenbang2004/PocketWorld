package dev.pocketengine.ui

import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Independent role-based recipe: restrained content contact, lifted input, foreground sheet.
// Apply only to an opaque work surface, composer or foreground; ordinary rows stay flat.
internal enum class PocketDepth { CONTENT, INPUT, FLOATING }
private data class DepthLayer(val blur: Float, val down: Float, val inset: Float, val opacity: Int)

internal fun Modifier.pocketDepth(role: PocketDepth, corner: Dp = 14.dp): Modifier = drawWithCache {
    val layers = when (role) {
        PocketDepth.CONTENT -> listOf(DepthLayer(1f, 1f, 0f, 12), DepthLayer(6f, 3f, 1f, 17))
        PocketDepth.INPUT -> listOf(DepthLayer(1f, 1f, 0f, 23), DepthLayer(9f, 5f, 2f, 29), DepthLayer(17f, 10f, 4f, 17))
        PocketDepth.FLOATING -> listOf(DepthLayer(2f, 1f, 0f, 25), DepthLayer(16f, 8f, 2f, 33), DepthLayer(28f, 16f, 4f, 23))
    }
    val prepared = layers.map { layer ->
        layer to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            setShadowLayer(layer.blur.dp.toPx(), 0f, layer.down.dp.toPx(),
                android.graphics.Color.argb(layer.opacity, 0, 0, 0))
        }
    }
    val rounding = corner.toPx()
    onDrawBehind {
        drawIntoCanvas { canvas ->
            prepared.forEach { (layer, paint) ->
                val inset = layer.inset.dp.toPx()
                if (size.width > 2 * inset && size.height > 2 * inset) {
                    val radius = (rounding - inset).coerceAtLeast(0f)
                    canvas.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset,
                        radius, radius, paint)
                }
            }
        }
    }
}
