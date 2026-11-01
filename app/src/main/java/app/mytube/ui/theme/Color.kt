package app.mytube.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// A quiet, near-monochrome palette with one accent, so thumbnails carry the color.
private val Lavender = Color(0xFFA594FF)
private val LavenderDeep = Color(0xFF5B45E0)

val DarkColors = darkColorScheme(
    primary = Lavender,
    onPrimary = Color(0xFF16112E),
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFE4DEFF),
    secondary = Color(0xFFC9C6D6),
    onSecondary = Color(0xFF1B1A22),
    secondaryContainer = Color(0xFF26252E),
    onSecondaryContainer = Color(0xFFE6E3F0),
    background = Color(0xFF09090C),
    onBackground = Color(0xFFF3F2F7),
    surface = Color(0xFF09090C),
    onSurface = Color(0xFFF3F2F7),
    surfaceVariant = Color(0xFF1C1B22),
    onSurfaceVariant = Color(0xFF9E9CAB),
    surfaceContainerLowest = Color(0xFF060608),
    surfaceContainerLow = Color(0xFF111116),
    surfaceContainer = Color(0xFF15151B),
    surfaceContainerHigh = Color(0xFF1C1B23),
    surfaceContainerHighest = Color(0xFF24232C),
    outline = Color(0xFF3A3944),
    outlineVariant = Color(0xFF26252E),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0A06),
)

val LightColors = lightColorScheme(
    primary = LavenderDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF1B1257),
    secondary = Color(0xFF55535F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECEAF2),
    onSecondaryContainer = Color(0xFF1B1A22),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF111116),
    surface = Color(0xFFFAFAFC),
    onSurface = Color(0xFF111116),
    surfaceVariant = Color(0xFFEDECF2),
    onSurfaceVariant = Color(0xFF65636F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F3F8),
    surfaceContainer = Color(0xFFEFEEF4),
    surfaceContainerHigh = Color(0xFFE9E8EF),
    surfaceContainerHighest = Color(0xFFE3E2EA),
    outline = Color(0xFFCBC9D3),
    outlineVariant = Color(0xFFE3E2EA),
    error = Color(0xFFC62828),
    onError = Color.White,
)
