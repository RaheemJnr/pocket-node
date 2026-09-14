package com.rjnr.pocketnode.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.core.prefs.ThemeMode

// Brand colors
val PocketGreen = Color(0xFF1DD781)

// Semantic status colors
val SuccessGreen = Color(0xFF22C55E)
val ErrorRed = Color(0xFFFF4444)
val PendingAmber = Color(0xFFF59E0B)
val TestnetOrange = Color(0xFFF57C00)
val TestnetOrangeDark = Color(0xFFBF360C)

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val DarkColorScheme = darkColorScheme(
    primary = PocketGreen,
    onPrimary = Color.Black,
    secondary = Color(0xFF81D4FA),
    tertiary = Color(0xFFA5D6A7),
    background = Color(0xFF0D0D0D),
    onBackground = Color.White,
    surface = Color(0xFF1A1A1A),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF404040),
    outlineVariant = Color(0xFF252525),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    secondary = Color(0xFF0288D1),
    tertiary = Color(0xFF388E3C)
)

@Composable
fun CkbWalletTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
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
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val defaultTypography = Typography()
    val interTypography = Typography(
        displayLarge = defaultTypography.displayLarge.copy(fontFamily = InterFontFamily),
        displayMedium = defaultTypography.displayMedium.copy(fontFamily = InterFontFamily),
        displaySmall = defaultTypography.displaySmall.copy(fontFamily = InterFontFamily),
        headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = InterFontFamily),
        headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = InterFontFamily),
        headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = InterFontFamily),
        titleLarge = defaultTypography.titleLarge.copy(fontFamily = InterFontFamily),
        titleMedium = defaultTypography.titleMedium.copy(fontFamily = InterFontFamily),
        titleSmall = defaultTypography.titleSmall.copy(fontFamily = InterFontFamily),
        bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = InterFontFamily),
        bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = InterFontFamily),
        bodySmall = defaultTypography.bodySmall.copy(fontFamily = InterFontFamily),
        labelLarge = defaultTypography.labelLarge.copy(fontFamily = InterFontFamily),
        labelMedium = defaultTypography.labelMedium.copy(fontFamily = InterFontFamily),
        labelSmall = defaultTypography.labelSmall.copy(fontFamily = InterFontFamily),
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = interTypography,
        content = content
    )
}
