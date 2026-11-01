package dev.pocketengine.ui

import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Project adaptation of the retained shadow recipe: tight contact + directional + broad ambient.
// Monochrome, top-lit, role-based. No business state, bitmap assets or animation-time blur allocation.
internal enum class PocketDepth { CONTENT, INPUT, FLOATING }
private data class DepthLayer(val blur: Float, val down: Float, val inset: Float, val opacity: Int)

internal fun Modifier.pocketDepth(role: PocketDepth, corner: Dp = 14.dp): Modifier = drawWithCache {
    val layers = when (role) {
        PocketDepth.CONTENT -> listOf(DepthLayer(1f, 1f, 0f, 20), DepthLayer(7f, 4f, 1f, 24), DepthLayer(14f, 9f, 4f, 13))
        PocketDepth.INPUT -> listOf(DepthLayer(1f, 1f, 0f, 24), DepthLayer(11f, 6f, 2f, 30), DepthLayer(23f, 15f, 6f, 20))
        PocketDepth.FLOATING -> listOf(DepthLayer(2f, 1f, 0f, 26), DepthLayer(18f, 10f, 2f, 32), DepthLayer(30f, 20f, 5f, 24))
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
