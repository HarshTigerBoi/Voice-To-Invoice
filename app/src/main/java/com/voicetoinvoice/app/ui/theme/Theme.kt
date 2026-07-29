package com.voicetoinvoice.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Corner radii are large and consistent -- the single cheapest thing that separates a
 * modern-looking Android surface from a default one. Cards and sheets share the same
 * 22dp family so nested surfaces stay visually concentric.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    primaryContainer = AccentPressed,
    onPrimaryContainer = TextPrimary,
    secondary = Info,
    onSecondary = TextPrimary,
    tertiary = Money,
    onTertiary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkElevated,
    onSurface = TextPrimary,
    surfaceVariant = InkCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = InkCard,
    surfaceContainerHigh = InkCardHigh,
    surfaceContainerHighest = InkCardHigh,
    outline = Hairline,
    outlineVariant = Hairline,
    error = Danger,
    onError = TextPrimary
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = PaperCard,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentPressed,
    secondary = Info,
    tertiary = Money,
    background = Paper,
    onBackground = TextOnPaper,
    surface = PaperCard,
    onSurface = TextOnPaper,
    surfaceVariant = Paper,
    onSurfaceVariant = TextOnPaperDim,
    surfaceContainer = PaperCard,
    surfaceContainerHigh = PaperCard,
    outline = PaperHairline,
    outlineVariant = PaperHairline,
    error = Danger,
    onError = PaperCard
)

/**
 * Deliberately does NOT opt into Material You dynamic color. The accent is load-bearing
 * here -- it is the "this needs your attention" signal on the review queue and the record
 * button's live state -- and letting it be rewritten by whatever wallpaper the shopkeeper
 * set would break that meaning on an unpredictable subset of devices.
 */
@Composable
fun VoiceToInvoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
