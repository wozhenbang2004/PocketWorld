package dev.pocketengine.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Stable presentation names shared by existing screens. No task state lives here.
internal val ActionInk = Color(0xff202020)
internal val ActionSurface = Color(0xffeaeaea)
internal val PageSurface = Color(0xfff3f3f3)

private val PocketColors = lightColorScheme(
    primary = ActionInk, onPrimary = Color.White,
    primaryContainer = ActionSurface, onPrimaryContainer = Color(0xff202020),
    secondary = SecondaryInk, onSecondary = Color.White,
    secondaryContainer = Soft, onSecondaryContainer = Ink,
    tertiary = Color(0xff555555), onTertiary = Color.White,
    background = PageSurface, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Soft, onSurfaceVariant = SecondaryInk,
    surfaceTint = Color.Transparent,
    inverseSurface = Ink, inverseOnSurface = Color.White, inversePrimary = Color(0xffdddddd),
    surfaceBright = Color.White, surfaceDim = Color(0xffdedede),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff7f7f7),
    surfaceContainer = Color(0xfff2f2f2), surfaceContainerHigh = Color(0xffececec),
    surfaceContainerHighest = Color(0xffe6e6e6), scrim = Color.Black,
    outline = Color(0xff777777), outlineVariant = Line,
    error = Danger, onError = Color.White,
    errorContainer = Color(0xfff0f0f0), onErrorContainer = Color(0xff242424)
)
private val PocketTypography = Typography(
    headlineLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontFamily = FontFamily.SansSerif),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, fontFamily = FontFamily.SansSerif),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.SansSerif),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
)

@Composable
internal fun PocketWorldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PocketColors,
        typography = PocketTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = Rounded,
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
