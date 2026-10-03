package dev.undefinedteam.wearagent.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

// Deep slate with a cool blue-grey cast: OLED-friendly dark levels without
// flat lifeless pure black. Primary is a soft wearable blue.
private val SlateColors = ColorScheme(
    primary = Color(0xFF8AB4F8),
    primaryContainer = Color(0xFF27405F),
    onPrimary = Color(0xFF0B1A2E),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7D93AB),
    secondaryContainer = Color(0xFF25313E),
    onSecondary = Color(0xFF0E141B),
    onSecondaryContainer = Color(0xFFC3D2E3),
    tertiary = Color(0xFF82C4B8),
    tertiaryContainer = Color(0xFF1E3A35),
    onTertiary = Color(0xFF0E1E1A),
    onTertiaryContainer = Color(0xFFBDE7DF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE3E6EA),
    surfaceContainerLow = Color(0xFF141920),
    surfaceContainer = Color(0xFF1A1F26),
    surfaceContainerHigh = Color(0xFF222932),
    onSurface = Color(0xFFE3E6EA),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF5B6673),
    outlineVariant = Color(0xFF3A434E),
    error = Color(0xFFF28B82),
    errorContainer = Color(0xFF4E2118),
    onError = Color(0xFF44110D),
    onErrorContainer = Color(0xFFF5B7AE),
)

@Composable
fun WearAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlateColors,
        content = content,
    )
}
