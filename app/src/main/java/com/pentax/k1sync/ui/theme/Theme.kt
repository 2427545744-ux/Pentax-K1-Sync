package com.pentax.k1sync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianBg = Color(0xFF0B0B0D)
val ElevatedSurface = Color(0xFF131316)
val MatteCard = Color(0xFF1A1A1F)
val HairlineBorder = Color(0xFF28282F)
val SubtleDivider = Color(0xFF202026)

val PentaxGreen = Color(0xFF22C55E)
val PentaxGreenSoft = Color(0xFF163322)
val PentaxRed = Color(0xFFD0122A)
val ChampagneGold = Color(0xFFD4A84C)
val WarmAmber = Color(0xFFF59E0B)

val InkPrimary = Color(0xFFF5F5F7)
val InkSecondary = Color(0xFF8E8E98)
val InkTertiary = Color(0xFF5A5A64)

private val ProDarkroomScheme = darkColorScheme(
    primary = PentaxGreen,
    onPrimary = Color(0xFF04170B),
    primaryContainer = PentaxGreenSoft,
    onPrimaryContainer = Color(0xFFC8FAD9),
    secondary = PentaxRed,
    onSecondary = Color.White,
    background = ObsidianBg,
    onBackground = InkPrimary,
    surface = ElevatedSurface,
    onSurface = InkPrimary,
    surfaceVariant = MatteCard,
    onSurfaceVariant = InkSecondary,
    outline = HairlineBorder
)

@Composable
fun PentaxK1Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ProDarkroomScheme,
        content = content
    )
}
