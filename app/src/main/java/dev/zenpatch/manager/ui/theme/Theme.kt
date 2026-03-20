package dev.zenpatch.manager.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C9CBF),
    onPrimary = Color(0xFF1A3249),
    primaryContainer = Color(0xFF334960),
    onPrimaryContainer = Color(0xFFD0E5FF),
    secondary = Color(0xFFB4C8DB),
    onSecondary = Color(0xFF1E333F),
    secondaryContainer = Color(0xFF354A56),
    onSecondaryContainer = Color(0xFFD0E5F7),
    tertiary = Color(0xFFC5BFEA),
    onTertiary = Color(0xFF2D2A4E),
    background = Color(0xFF0F1418),
    onBackground = Color(0xFFE3E7ED),
    surface = Color(0xFF171B20),
    onSurface = Color(0xFFE3E7ED),
    surfaceVariant = Color(0xFF3C4650),
    onSurfaceVariant = Color(0xFFBBC7D0),
    outline = Color(0xFF87929C)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3A6082),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE5FF),
    onPrimaryContainer = Color(0xFF001E34),
    secondary = Color(0xFF506878),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E5F7),
    onSecondaryContainer = Color(0xFF0C1F2B),
    tertiary = Color(0xFF635C7F),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6FAFE),
    onBackground = Color(0xFF171C20),
    surface = Color(0xFFF6FAFE),
    onSurface = Color(0xFF171C20),
    surfaceVariant = Color(0xFFDBE5EF),
    onSurfaceVariant = Color(0xFF3F4950),
    outline = Color(0xFF6F7A82)
)

enum class AppTheme {
    SYSTEM, LIGHT, DARK
}

@Composable
fun ZenPatchTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (appTheme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
