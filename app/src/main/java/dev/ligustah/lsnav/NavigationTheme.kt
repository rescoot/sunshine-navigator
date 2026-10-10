package dev.ligustah.lsnav

import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

@Composable
fun NavigationTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColors else lightColors
    MaterialTheme(colorScheme = colors, content = content)
}

fun configureNavigationWindow(window: Window, dark: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = AndroidColor.TRANSPARENT
    window.navigationBarColor = AndroidColor.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

private val lightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    inversePrimary = Color(0xFFA5B4FC),
    secondary = Color(0xFF2A2D30),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E7EA),
    onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFF1E40AF),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDBEAFE),
    onTertiaryContainer = Color(0xFF1E3A8A),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDF0F3),
    onSurfaceVariant = Color(0xFF555555),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2A2D30),
    inverseOnSurface = Color(0xFFF0F0F0),
    error = Color(0xFF991B1B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFFA0A4A8),
    outlineVariant = Color(0xFFD4D8DC),
    scrim = Color(0x99000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E7EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6F8),
    surfaceContainer = Color(0xFFEDF0F3),
    surfaceContainerHigh = Color(0xFFE8EAED),
    surfaceContainerHighest = Color(0xFFD4D8DC)
)

private val darkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    inversePrimary = Color(0xFF4F46E5),
    secondary = Color(0xFFF0F0F0),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color(0xFFF0F0F0),
    tertiary = Color(0xFF60A5FA),
    onTertiary = Color(0xFF071A33),
    tertiaryContainer = Color(0xFF1E3A5F),
    onTertiaryContainer = Color(0xFFDBEAFE),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFF999999),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF0F0F0),
    inverseOnSurface = Color(0xFF111111),
    error = Color(0xFFFB7185),
    onError = Color(0xFF3F0712),
    errorContainer = Color(0xFF4C101B),
    onErrorContainer = Color(0xFFFFD9DE),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF222222),
    scrim = Color(0x99000000),
    surfaceBright = Color(0xFF2C2C2C),
    surfaceDim = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2C2C2C)
)
