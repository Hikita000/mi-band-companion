package com.miband.companion.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val MiOrangePrimary = Color(0xFFFF6900)
val MiOrangeSecondary = Color(0xFFFF914D)
val AmoledBlack = Color(0xFF0A0A0C)
val CardDarkSurface = Color(0xFF16181D)
val CardDarkVariant = Color(0xFF21242C)
val TealAccent = Color(0xFF00E5FF)
val SuccessGreen = Color(0xFF00E676)
val ErrorRed = Color(0xFFFF5252)

private val DarkColorScheme = darkColorScheme(
    primary = MiOrangePrimary,
    onPrimary = Color.White,
    secondary = TealAccent,
    onSecondary = Color.Black,
    background = AmoledBlack,
    onBackground = Color(0xFFF2F4F8),
    surface = CardDarkSurface,
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = CardDarkVariant,
    onSurfaceVariant = Color(0xFFA0A8B8),
    error = ErrorRed
)

private val LightColorScheme = lightColorScheme(
    primary = MiOrangePrimary,
    onPrimary = Color.White,
    secondary = Color(0xFF0097A7),
    onSecondary = Color.White,
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF121316),
    surface = Color.White,
    onSurface = Color(0xFF121316),
    surfaceVariant = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFF5A6270),
    error = Color(0xFFD32F2F)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    )
)

@Composable
fun MiBandCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
