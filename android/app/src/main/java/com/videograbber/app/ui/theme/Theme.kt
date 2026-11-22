package com.videograbber.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF2463EB), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF), onPrimaryContainer = Color(0xFF0D2A6B),
    secondary = Color(0xFF55555E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECECEF), onSecondaryContainer = Color(0xFF17171A),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF17171A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF17171A),
    surfaceVariant = Color(0xFFF0F0F2), onSurfaceVariant = Color(0xFF5E5E68),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F8F9),
    surfaceContainer = Color(0xFFF3F3F5), surfaceContainerHigh = Color(0xFFEDEDF0), surfaceContainerHighest = Color(0xFFE6E6EA),
    outline = Color(0xFFD2D2D8), outlineVariant = Color(0xFFE6E6EA),
    error = Color(0xFFD4362C), onError = Color.White,
    inverseSurface = Color(0xFF26262A), inverseOnSurface = Color(0xFFF4F4F5), inversePrimary = Color(0xFF8AB0FF),
    scrim = Color(0xFF000000),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5B93FF), onPrimary = Color.White,
    primaryContainer = Color(0xFF1B3672), onPrimaryContainer = Color(0xFFDDE7FF),
    secondary = Color(0xFFB3B3BB), onSecondary = Color(0xFF17171A),
    secondaryContainer = Color(0xFF2B2B30), onSecondaryContainer = Color(0xFFF1F1F3),
    background = Color(0xFF121214), onBackground = Color(0xFFF1F1F3),
    surface = Color(0xFF161618), onSurface = Color(0xFFF1F1F3),
    surfaceVariant = Color(0xFF26262A), onSurfaceVariant = Color(0xFFA6A6B0),
    surfaceContainerLowest = Color(0xFF0E0E10), surfaceContainerLow = Color(0xFF1A1A1D),
    surfaceContainer = Color(0xFF1E1E21), surfaceContainerHigh = Color(0xFF26262A), surfaceContainerHighest = Color(0xFF2F2F34),
    outline = Color(0xFF3C3C43), outlineVariant = Color(0xFF2C2C31),
    error = Color(0xFFFF6B5F), onError = Color(0xFF17171A),
    inverseSurface = Color(0xFFF1F1F3), inverseOnSurface = Color(0xFF1E1E21), inversePrimary = Color(0xFF2463EB),
    scrim = Color(0xFF000000),
)

private val AppType = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

data class Extra(val success: Color, val tagBg: Color, val isDark: Boolean)
val LocalExtra = staticCompositionLocalOf { Extra(Color(0xFF15924A), Color(0x0F000000), false) }

@Composable
fun isDarkTheme(pref: String): Boolean = when (pref) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }

@Composable
fun VideoGrabberTheme(dark: Boolean, content: @Composable () -> Unit) {
    val extra = if (dark) Extra(Color(0xFF3CCF7E), Color(0x14FFFFFF), true) else Extra(Color(0xFF15924A), Color(0x0D000000), false)
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppType, shapes = AppShapes, content = content)
    }
}

val MonoStyle = TextStyle(fontSize = 11.sp, letterSpacing = 0.sp)
