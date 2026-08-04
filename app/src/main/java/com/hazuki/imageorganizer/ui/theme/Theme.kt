package com.hazuki.imageorganizer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val FujiLightColors = lightColorScheme(
    primary = FujiPrimary,
    onPrimary = FujiOnPrimary,
    primaryContainer = FujiPrimaryLight,
    onPrimaryContainer = FujiOnSurface,
    secondary = FujiPrimaryDark,
    background = FujiBackground,
    onBackground = FujiOnSurface,
    surface = FujiSurface,
    onSurface = FujiOnSurface,
    surfaceVariant = FujiSurfaceVariant,
    onSurfaceVariant = FujiOnSurfaceMuted,
    outline = FujiOutline,
)

private val FujiTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun ImageViewOrganizerTheme(
    // 現状は和紙+藤色の単一テイストで統一(ダークモードは将来対応)
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FujiLightColors,
        typography = FujiTypography,
        content = content
    )
}
