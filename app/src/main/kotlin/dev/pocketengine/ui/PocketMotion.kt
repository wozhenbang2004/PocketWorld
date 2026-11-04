package dev.pocketengine.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Independent Compose implementation of the adopted short press/base relationship.
// Native controls own click, cancellation, focus, disabled state and accessibility.
private val PressFace: Color @Composable get() = MaterialTheme.colorScheme.primary
private val PressBase = Color(0xff242424)
private val DisabledPressFace: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
private val DisabledPressInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)

@Composable
internal fun PocketPressButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val lift by animateDpAsState(
        targetValue = if (enabled && !pressed) (-2).dp else 0.dp,
        animationSpec = if (pressed) tween(durationMillis = 65)
            else spring(dampingRatio = .78f, stiffness = 650f),
        label = "pocketButtonLift"
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .pocketPressBase(lift, 12.dp, enabled),
        interactionSource = interactions,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PressFace,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = DisabledPressFace,
            disabledContentColor = DisabledPressInk
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp, pressedElevation = 0.dp,
            focusedElevation = 0.dp, hoveredElevation = 0.dp, disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        content = content
    )
}

@Composable
internal fun PocketPressIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val lift by animateDpAsState(
        targetValue = if (enabled && !pressed) (-2).dp else 0.dp,
        animationSpec = if (pressed) tween(durationMillis = 65)
            else spring(dampingRatio = .78f, stiffness = 650f),
        label = "pocketIconLift"
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .pocketPressBase(lift, 24.dp, enabled),
        interactionSource = interactions,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = PressFace,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = DisabledPressFace,
            disabledContentColor = DisabledPressInk
        ),
        content = content
    )
}

// This draw modifier stays outside the translated native face, so the base does not move.
private fun Modifier.pocketPressBase(lift: Dp, corner: Dp, enabled: Boolean): Modifier =
    drawBehind {
        if (enabled) drawRoundRect(
            color = PressBase,
            cornerRadius = CornerRadius(corner.toPx(), corner.toPx())
        )
    }.graphicsLayer { translationY = lift.toPx() }

private class PocketSceneContent(val key: Any?, val content: @Composable () -> Unit)

/**
 * Presentation only: incoming and outgoing compositions overlap briefly.
 * Keep AndroidView, resource ownership and business effects outside this wrapper.
 * The caller owns scroll state and the stable identity of the scene.
 */
@Composable
internal fun PocketScene(
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val incomingOffset = with(density) { 4.dp.roundToPx() }
    val outgoingOffset = with(density) { 2.dp.roundToPx() }
    AnimatedContent(
        targetState = PocketSceneContent(key, content),
        modifier = modifier,
        contentKey = { it.key },
        transitionSpec = {
            (fadeIn(tween(150)) + slideInVertically(tween(170)) { incomingOffset })
                .togetherWith(fadeOut(tween(95)) + slideOutVertically(tween(120)) { -outgoingOffset })
                .using(SizeTransform(clip = true) { _, _ -> tween(160) })
        },
        label = "pocketScene"
    ) { scene ->
        scene.content()
    }
}
