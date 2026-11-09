package az.nvaton.komekci.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import az.nvaton.komekci.R

/*
 * One system, dealt by tasteskill for a software product: palette "Frost and slate" (off-white ground, slate ink,
 * one teal accent: calm and clinical-clean, never loud), type "Chivo" (a calm grotesk; headlines 500, text 400,
 * labels 500), radius "round" (a consumer app). Sizes are large on purpose: the people using this are elderly.
 */

val AlertRed = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D7480), // the one accent
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5ECEE),
    onPrimaryContainer = Color(0xFF07464E),
    secondary = Color(0xFF3E5761),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EAED),
    onSecondaryContainer = Color(0xFF1F3038),
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF1F3038),
    surface = Color.White,
    onSurface = Color(0xFF1F3038),
    surfaceVariant = Color(0xFFECF0F2),
    onSurfaceVariant = Color(0xFF4D6169),
    surfaceContainer = Color(0xFFECF0F2),
    surfaceContainerHigh = Color(0xFFE3E9EC),
    outline = Color(0xFFC9D3D8),
    outlineVariant = Color(0xFFDDE4E8),
    error = AlertRed,
    onError = Color.White,
    errorContainer = Color(0xFFFBE4E1),
    onErrorContainer = Color(0xFF7A1A14),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC3CE),
    onPrimary = Color(0xFF00363C),
    primaryContainer = Color(0xFF0B4D55),
    onPrimaryContainer = Color(0xFFCDEDF0),
    secondary = Color(0xFFB4C6CD),
    onSecondary = Color(0xFF1F3038),
    secondaryContainer = Color(0xFF2A3E46),
    onSecondaryContainer = Color(0xFFDDE7EB),
    background = Color(0xFF101A1F),
    onBackground = Color(0xFFE6EDF0),
    surface = Color(0xFF17252C),
    onSurface = Color(0xFFE6EDF0),
    surfaceVariant = Color(0xFF1F3038),
    onSurfaceVariant = Color(0xFFA9BAC1),
    surfaceContainer = Color(0xFF1F3038),
    surfaceContainerHigh = Color(0xFF283B44),
    outline = Color(0xFF3B5059),
    outlineVariant = Color(0xFF2C4049),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C1A15),
    onErrorContainer = Color(0xFFFFDAD5),
)

@OptIn(ExperimentalTextApi::class) // variable-font weights; stable in practice since Compose 1.5
private fun chivo(weight: Int) = Font(
    R.font.chivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Chivo = FontFamily(chivo(400), chivo(500), chivo(600))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Chivo,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
)

// One type scale. Headlines 500 with a little negative tracking; text 400; buttons and labels 500.
private val AppType = Typography(
    displayLarge = style(76, 80, 500, -0.03),
    headlineLarge = style(34, 40, 500, -0.015),
    headlineMedium = style(28, 36, 500, -0.01),
    headlineSmall = style(24, 31, 500),
    titleLarge = style(22, 28, 500),
    titleMedium = style(20, 26, 500),
    bodyLarge = style(20, 29, 400),
    bodyMedium = style(18, 26, 400),
    bodySmall = style(16, 23, 400),
    labelLarge = style(19, 24, 500),
    labelMedium = style(16, 20, 500),
)

// One radius scale: fields 16, cards 28, everything you press is a pill or a circle.
private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun KomekciTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppType,
        shapes = AppShapes,
        content = content,
    )
}
