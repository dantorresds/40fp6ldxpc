package com.atendenteia.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E8C7E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF3EC),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFFF2B233),
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFFFFEFC7),
    onSecondaryContainer = Color(0xFF2B1D00),
    tertiary = Color(0xFF5B6B8C),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFE6EEEC),
    onSurfaceVariant = Color(0xFF475251),
    outline = Color(0xFF74807E),
    outlineVariant = Color(0xFFDCE5E3),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD3C2),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF00463C),
    onPrimaryContainer = Color(0xFFCFF3EC),
    secondary = Color(0xFFF2B233),
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFF5C4200),
    onSecondaryContainer = Color(0xFFFFEFC7),
    tertiary = Color(0xFFB3C3E8),
    background = Color(0xFF0E1413),
    onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF161D1C),
    onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF253130),
    onSurfaceVariant = Color(0xFFBEC9C7),
    outline = Color(0xFF899392),
    outlineVariant = Color(0xFF2C3836),
    error = Color(0xFFFFB4AB),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val Base = Typography()
private val AppTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.1.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = Base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Tema do app: verde-azulado, claro e escuro conforme o celular. */
@Composable
fun AtendenteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
