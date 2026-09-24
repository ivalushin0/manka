package app.manka.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF8A4F16),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF745845),
    secondaryContainer = Color(0xFFFFDCC7),
    tertiary = Color(0xFF5E6135),
    tertiaryContainer = Color(0xFFE4E6AF),
    background = Color(0xFFFFF8F5),
    surface = Color(0xFFFFF8F5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF4C2700),
    primaryContainer = Color(0xFF6C3A00),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE4BFA7),
    secondaryContainer = Color(0xFF5B4130),
    tertiary = Color(0xFFC8CA95),
    tertiaryContainer = Color(0xFF46491F),
    background = Color(0xFF1A120D),
    surface = Color(0xFF1A120D),
)

@Composable
fun MankaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
