package dev.pocketengine.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Stable presentation names shared by existing screens. No task state lives here.
internal val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
internal val SecondaryInk: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Line: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
internal val Soft: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
internal val Danger: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
internal val ActionInk: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
internal val ActionSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer
internal val PageSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background

private val PocketLightColors = lightColorScheme(
    primary = Color(0xff202020), onPrimary = Color.White,
    primaryContainer = Color(0xffeaeaea), onPrimaryContainer = Color(0xff202020),
    secondary = Color(0xff666666), onSecondary = Color.White,
    secondaryContainer = Color(0xfff2f2f2), onSecondaryContainer = Color(0xff181818),
    tertiary = Color(0xff555555), onTertiary = Color.White,
    background = Color(0xfff5f5f5), onBackground = Color(0xff181818),
    surface = Color.White, onSurface = Color(0xff181818),
    surfaceVariant = Color(0xfff2f2f2), onSurfaceVariant = Color(0xff666666),
    surfaceTint = Color(0xff202020),
    inverseSurface = Color(0xff181818), inverseOnSurface = Color.White, inversePrimary = Color(0xffdddddd),
    surfaceBright = Color.White, surfaceDim = Color(0xffdedede),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xfff7f7f7),
    surfaceContainer = Color(0xfff2f2f2), surfaceContainerHigh = Color(0xffececec),
    surfaceContainerHighest = Color(0xffe6e6e6), scrim = Color.Black,
    outline = Color(0xff777777), outlineVariant = Color(0xffe4e4e4),
    error = Color(0xff242424), onError = Color.White,
    errorContainer = Color(0xfff0f0f0), onErrorContainer = Color(0xff242424)
)
private val PocketDarkColors = darkColorScheme(
    primary = Color(0xffededed), onPrimary = Color(0xff181818),
    primaryContainer = Color(0xff353535), onPrimaryContainer = Color(0xffededed),
    secondary = Color(0xffbdbdbd), onSecondary = Color(0xff202020),
    secondaryContainer = Color(0xff303030), onSecondaryContainer = Color(0xffe8e8e8),
    tertiary = Color(0xffb7b7b7), onTertiary = Color(0xff242424),
    background = Color(0xff121212), onBackground = Color(0xffe8e8e8),
    surface = Color(0xff1b1b1b), onSurface = Color(0xffe8e8e8),
    surfaceVariant = Color(0xff2b2b2b), onSurfaceVariant = Color(0xffbdbdbd),
    surfaceTint = Color(0xffededed),
    inverseSurface = Color(0xffe8e8e8), inverseOnSurface = Color(0xff202020), inversePrimary = Color(0xff303030),
    surfaceBright = Color(0xff3a3a3a), surfaceDim = Color(0xff121212),
    surfaceContainerLowest = Color(0xff0d0d0d), surfaceContainerLow = Color(0xff1b1b1b),
    surfaceContainer = Color(0xff212121), surfaceContainerHigh = Color(0xff2b2b2b),
    surfaceContainerHighest = Color(0xff363636), scrim = Color.Black,
    outline = Color(0xff929292), outlineVariant = Color(0xff4b4b4b),
    error = Color(0xffe8e8e8), onError = Color(0xff242424),
    errorContainer = Color(0xff363636), onErrorContainer = Color(0xffededed)
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
    val preference = rememberPocketAppearance().second
    val dark = when (preference) { PocketAppearance.LIGHT -> false; PocketAppearance.DARK -> true; PocketAppearance.SYSTEM -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
        (context as? android.app.Activity)?.let { activity ->
            activity.setTheme(if (dark) dev.pocketengine.R.style.AppTheme_Dark else dev.pocketengine.R.style.AppTheme_Light)
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) PocketDarkColors else PocketLightColors,
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
