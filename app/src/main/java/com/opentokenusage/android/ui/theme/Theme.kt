package com.opentokenusage.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.opentokenusage.android.domain.logic.PaceTier
import com.opentokenusage.android.domain.model.AppThemeMode

@Immutable
data class OtuColors(
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val elevatedSurface: Color,
    val border: Color,
    val track: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val paceGreen: Color,
    val paceYellow: Color,
    val paceRed: Color,
    val warningBg: Color,
    val warningBorder: Color,
) {
    fun paceColor(tier: PaceTier?): Color = when (tier) {
        PaceTier.AHEAD -> paceGreen
        PaceTier.ON_TRACK -> paceYellow
        PaceTier.BEHIND -> paceRed
        null -> textMuted
    }
}

private val OledDarkOtuColors = OtuColors(
    isDark = true,
    background = Color(0xFF000000),
    card = Color(0xFF0A0A0A),
    elevatedSurface = Color(0xFF141414),
    border = Color(0x1FFFFFFF),
    track = Color(0x21FFFFFF),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFC5C5C5),
    textMuted = Color(0xFF8E8E93),
    accent = Color(0xFF0078D4),
    paceGreen = Color(0xFF6CCB5F),
    paceYellow = Color(0xFFFCE100),
    paceRed = Color(0xFFFF99A4),
    warningBg = Color(0x1AFF99A4),
    warningBorder = Color(0x55FF99A4),
)

private val LightOtuColors = OtuColors(
    isDark = false,
    background = Color(0xFFF3F3F3),
    card = Color(0xFFFFFFFF),
    elevatedSurface = Color(0xFFFAFAFA),
    border = Color(0x18000000),
    track = Color(0x18000000),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF5D5D5D),
    textMuted = Color(0xFF767676),
    accent = Color(0xFF0078D4),
    paceGreen = Color(0xFF0F7B0F),
    paceYellow = Color(0xFF9D5D00),
    paceRed = Color(0xFFC42B1C),
    warningBg = Color(0x14C42B1C),
    warningBorder = Color(0x44C42B1C),
)

val LocalOtuColors = staticCompositionLocalOf { OledDarkOtuColors }

private val OtuTypography = Typography(
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun OpenTokenUsageTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        AppThemeMode.OLED_DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val otuColors = if (useDark) OledDarkOtuColors else LightOtuColors
    val m3Scheme = if (useDark) {
        darkColorScheme(
            primary = otuColors.accent,
            onPrimary = Color.White,
            background = otuColors.background,
            onBackground = otuColors.textPrimary,
            surface = otuColors.card,
            onSurface = otuColors.textPrimary,
            surfaceVariant = otuColors.elevatedSurface,
            onSurfaceVariant = otuColors.textSecondary,
            outline = otuColors.border,
            error = otuColors.paceRed,
        )
    } else {
        lightColorScheme(
            primary = otuColors.accent,
            onPrimary = Color.White,
            background = otuColors.background,
            onBackground = otuColors.textPrimary,
            surface = otuColors.card,
            onSurface = otuColors.textPrimary,
            surfaceVariant = otuColors.elevatedSurface,
            onSurfaceVariant = otuColors.textSecondary,
            outline = otuColors.border,
            error = otuColors.paceRed,
        )
    }

    CompositionLocalProvider(LocalOtuColors provides otuColors) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = OtuTypography,
            content = content,
        )
    }
}
