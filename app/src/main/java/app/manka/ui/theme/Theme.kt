package app.manka.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Manka's own palette, the same tokens as the PC build (Manka-UI, app/src/app.css): a dark navy
 * background, cards with a thin border, violet and cyan accents. It replaces the dynamic Material 3
 * colours: the two apps should look like one product, and a wallpaper-generated palette cannot do
 * that. Keep in sync with docs/DESIGN.md.
 */
private val Manka = darkColorScheme(
    primary = Color(0xFF8B6DFF),
    onPrimary = Color(0xFF140A33),
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFDDD6FF),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF00272E),
    secondaryContainer = Color(0xFF123844),
    onSecondaryContainer = Color(0xFFB8F4FF),
    tertiary = Color(0xFF7C5CFF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF232043),
    onTertiaryContainer = Color(0xFFD9D2FF),
    background = Color(0xFF0A0E17),
    onBackground = Color(0xFFE8EEFC),
    surface = Color(0xFF0A0E17),
    onSurface = Color(0xFFE8EEFC),
    surfaceVariant = Color(0xFF172134),
    onSurfaceVariant = Color(0xFF93A1BD),
    surfaceContainerLowest = Color(0xFF080B12),
    surfaceContainerLow = Color(0xFF0F1522),
    surfaceContainer = Color(0xFF131B2B),
    surfaceContainerHigh = Color(0xFF172134),
    surfaceContainerHighest = Color(0xFF1B2538),
    outline = Color(0xFF223049),
    outlineVariant = Color(0xFF1B2538),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3A1618),
    onErrorContainer = Color(0xFFFCA5A5),
    inverseSurface = Color(0xFFE8EEFC),
    inverseOnSurface = Color(0xFF131B2B),
)

private val MankaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun MankaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Manka, shapes = MankaShapes, content = content)
}
