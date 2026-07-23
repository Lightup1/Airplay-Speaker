package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun AirPlayTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF9EF2D0),
        onPrimary = Color(0xFF090A12),
        secondary = Color(0xFFA99BFF),
        background = Color(0xFF090A12),
        onBackground = Color(0xFFF6F4FF),
        surface = Color(0xFF11131E),
        onSurface = Color(0xFFF6F4FF),
        surfaceVariant = Color(0xFF171927),
        onSurfaceVariant = Color(0xFFAAA8B8),
    )

    MaterialTheme(colorScheme = colorScheme, content = content)
}
