package com.flowreader.app.core.designsystem.token

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Warm paper, pine ink and a restrained copper accent: a quiet reading room.
 */
object FlowBrandColors {
    val LightPrimary = Color(0xFF536B5D)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightPrimaryContainer = Color(0xFFE5EBE2)
    val LightOnPrimaryContainer = Color(0xFF283B2F)
    val LightSecondary = Color(0xFF5E5852)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightSecondaryContainer = Color(0xFFEDE7DB)
    val LightOnSecondaryContainer = Color(0xFF26221D)
    val LightTertiary = Color(0xFF8B6346)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightTertiaryContainer = Color(0xFFEDE5DE)
    val LightOnTertiaryContainer = Color(0xFF2E241C)
    val LightError = Color(0xFFBA1A1A)
    val LightOnError = Color(0xFFFFFFFF)
    val LightErrorContainer = Color(0xFFFFDAD6)
    val LightOnErrorContainer = Color(0xFF410002)
    val LightBackground = Color(0xFFF7F3EB)
    val LightOnBackground = Color(0xFF242321)
    val LightSurface = Color(0xFFF7F3EB)
    val LightOnSurface = Color(0xFF242321)
    val LightSurfaceVariant = Color(0xFFEEE9DF)
    val LightOnSurfaceVariant = Color(0xFF44413D)
    val LightOutline = Color(0xFF858079)
    val LightOutlineVariant = Color(0xFFD6D2CA)

    val DarkPrimary = Color(0xFFE9BF8B)
    val DarkOnPrimary = Color(0xFF381E10)
    val DarkPrimaryContainer = Color(0xFF514333)
    val DarkOnPrimaryContainer = Color(0xFFF3ECE6)
    val DarkSecondary = Color(0xFFCAC4BD)
    val DarkOnSecondary = Color(0xFF312D27)
    val DarkSecondaryContainer = Color(0xFF47433E)
    val DarkOnSecondaryContainer = Color(0xFFE6E1DC)
    val DarkTertiary = Color(0xFFC7BBB0)
    val DarkOnTertiary = Color(0xFF352B22)
    val DarkTertiaryContainer = Color(0xFF4C4137)
    val DarkOnTertiaryContainer = Color(0xFFE3D8CE)
    val DarkError = Color(0xFFF2B8B5)
    val DarkOnError = Color(0xFF601410)
    val DarkErrorContainer = Color(0xFF8C1D18)
    val DarkOnErrorContainer = Color(0xFFF9DEDC)
    val DarkBackground = Color(0xFF191917)
    val DarkOnBackground = Color(0xFFEBE6E1)
    val DarkSurface = Color(0xFF191917)
    val DarkOnSurface = Color(0xFFEBE6E1)
    val DarkSurfaceVariant = Color(0xFF302E2A)
    val DarkOnSurfaceVariant = Color(0xFFD6D2CA)
    val DarkOutline = Color(0xFF959089)
    val DarkOutlineVariant = Color(0xFF4A4641)
}

/**
 * FlowReader's monochrome palette: 玄墨极简（纯黑白极简）.
 * 纯粹轻灰 (#FAFAFA) + 深邃黑 (#18181B) + 浅灰微凸容器 (#F4F4F5)，零彩度纯粹素雅。
 */
object FlowMonochromeColors {
    val LightPrimary = Color(0xFF18181B)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightPrimaryContainer = Color(0xFFF4F4F5)
    val LightOnPrimaryContainer = Color(0xFF18181B)
    val LightSecondary = Color(0xFF3F3F46)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightSecondaryContainer = Color(0xFFE4E4E7)
    val LightOnSecondaryContainer = Color(0xFF18181B)
    val LightTertiary = Color(0xFF52525B)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightTertiaryContainer = Color(0xFFE4E4E7)
    val LightOnTertiaryContainer = Color(0xFF27272A)
    val LightError = Color(0xFFBA1A1A)
    val LightOnError = Color(0xFFFFFFFF)
    val LightErrorContainer = Color(0xFFFFDAD6)
    val LightOnErrorContainer = Color(0xFF410002)
    val LightBackground = Color(0xFFFAFAFA)
    val LightOnBackground = Color(0xFF18181B)
    val LightSurface = Color(0xFFFAFAFA)
    val LightOnSurface = Color(0xFF18181B)
    val LightSurfaceVariant = Color(0xFFF4F4F5)
    val LightOnSurfaceVariant = Color(0xFF44444A)
    val LightOutline = Color(0xFF71717A)
    val LightOutlineVariant = Color(0xFFE4E4E7)

    val DarkPrimary = Color(0xFFF4F4F5)
    val DarkOnPrimary = Color(0xFF18181B)
    val DarkPrimaryContainer = Color(0xFF27272A)
    val DarkOnPrimaryContainer = Color(0xFFF4F4F5)
    val DarkSecondary = Color(0xFFA1A1AA)
    val DarkOnSecondary = Color(0xFF18181B)
    val DarkSecondaryContainer = Color(0xFF3F3F46)
    val DarkOnSecondaryContainer = Color(0xFFF4F4F5)
    val DarkTertiary = Color(0xFF71717A)
    val DarkOnTertiary = Color(0xFF18181B)
    val DarkTertiaryContainer = Color(0xFF3F3F46)
    val DarkOnTertiaryContainer = Color(0xFFD4D4D8)
    val DarkError = Color(0xFFF2B8B5)
    val DarkOnError = Color(0xFF601410)
    val DarkErrorContainer = Color(0xFF8C1D18)
    val DarkOnErrorContainer = Color(0xFFF9DEDC)
    val DarkBackground = Color(0xFF121212)
    val DarkOnBackground = Color(0xFFF4F4F5)
    val DarkSurface = Color(0xFF18181B)
    val DarkOnSurface = Color(0xFFF4F4F5)
    val DarkSurfaceVariant = Color(0xFF27272A)
    val DarkOnSurfaceVariant = Color(0xFFD4D4D8)
    val DarkOutline = Color(0xFF71717A)
    val DarkOutlineVariant = Color(0xFF3F3F46)
}

val FlowLightColorScheme = lightColorScheme(
    surfaceContainerLowest = Color(0xFFFFFDF8),
    surfaceContainerLow = Color(0xFFF1EEE5),
    surfaceContainer = Color(0xFFEDE8DE),
    surfaceContainerHigh = Color(0xFFE8E3D9),
    surfaceContainerHighest = Color(0xFFE3DED4),
    primary = FlowBrandColors.LightPrimary,
    onPrimary = FlowBrandColors.LightOnPrimary,
    primaryContainer = FlowBrandColors.LightPrimaryContainer,
    onPrimaryContainer = FlowBrandColors.LightOnPrimaryContainer,
    secondary = FlowBrandColors.LightSecondary,
    onSecondary = FlowBrandColors.LightOnSecondary,
    secondaryContainer = FlowBrandColors.LightSecondaryContainer,
    onSecondaryContainer = FlowBrandColors.LightOnSecondaryContainer,
    tertiary = FlowBrandColors.LightTertiary,
    onTertiary = FlowBrandColors.LightOnTertiary,
    tertiaryContainer = FlowBrandColors.LightTertiaryContainer,
    onTertiaryContainer = FlowBrandColors.LightOnTertiaryContainer,
    error = FlowBrandColors.LightError,
    onError = FlowBrandColors.LightOnError,
    errorContainer = FlowBrandColors.LightErrorContainer,
    onErrorContainer = FlowBrandColors.LightOnErrorContainer,
    background = FlowBrandColors.LightBackground,
    onBackground = FlowBrandColors.LightOnBackground,
    surface = FlowBrandColors.LightSurface,
    onSurface = FlowBrandColors.LightOnSurface,
    surfaceVariant = FlowBrandColors.LightSurfaceVariant,
    onSurfaceVariant = FlowBrandColors.LightOnSurfaceVariant,
    outline = FlowBrandColors.LightOutline,
    outlineVariant = FlowBrandColors.LightOutlineVariant
)

val FlowDarkColorScheme = darkColorScheme(
    surfaceContainerLowest = Color(0xFF151513),
    surfaceContainerLow = Color(0xFF23221F),
    surfaceContainer = Color(0xFF292723),
    surfaceContainerHigh = Color(0xFF302D28),
    surfaceContainerHighest = Color(0xFF38342E),
    primary = FlowBrandColors.DarkPrimary,
    onPrimary = FlowBrandColors.DarkOnPrimary,
    primaryContainer = FlowBrandColors.DarkPrimaryContainer,
    onPrimaryContainer = FlowBrandColors.DarkOnPrimaryContainer,
    secondary = FlowBrandColors.DarkSecondary,
    onSecondary = FlowBrandColors.DarkOnSecondary,
    secondaryContainer = FlowBrandColors.DarkSecondaryContainer,
    onSecondaryContainer = FlowBrandColors.DarkOnSecondaryContainer,
    tertiary = FlowBrandColors.DarkTertiary,
    onTertiary = FlowBrandColors.DarkOnTertiary,
    tertiaryContainer = FlowBrandColors.DarkTertiaryContainer,
    onTertiaryContainer = FlowBrandColors.DarkOnTertiaryContainer,
    error = FlowBrandColors.DarkError,
    onError = FlowBrandColors.DarkOnError,
    errorContainer = FlowBrandColors.DarkErrorContainer,
    onErrorContainer = FlowBrandColors.DarkOnErrorContainer,
    background = FlowBrandColors.DarkBackground,
    onBackground = FlowBrandColors.DarkOnBackground,
    surface = FlowBrandColors.DarkSurface,
    onSurface = FlowBrandColors.DarkOnSurface,
    surfaceVariant = FlowBrandColors.DarkSurfaceVariant,
    onSurfaceVariant = FlowBrandColors.DarkOnSurfaceVariant,
    outline = FlowBrandColors.DarkOutline,
    outlineVariant = FlowBrandColors.DarkOutlineVariant
)

val FlowMonochromeLightColorScheme = lightColorScheme(
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFEEEEF0),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    primary = FlowMonochromeColors.LightPrimary,
    onPrimary = FlowMonochromeColors.LightOnPrimary,
    primaryContainer = FlowMonochromeColors.LightPrimaryContainer,
    onPrimaryContainer = FlowMonochromeColors.LightOnPrimaryContainer,
    secondary = FlowMonochromeColors.LightSecondary,
    onSecondary = FlowMonochromeColors.LightOnSecondary,
    secondaryContainer = FlowMonochromeColors.LightSecondaryContainer,
    onSecondaryContainer = FlowMonochromeColors.LightOnSecondaryContainer,
    tertiary = FlowMonochromeColors.LightTertiary,
    onTertiary = FlowMonochromeColors.LightOnTertiary,
    tertiaryContainer = FlowMonochromeColors.LightTertiaryContainer,
    onTertiaryContainer = FlowMonochromeColors.LightOnTertiaryContainer,
    error = FlowMonochromeColors.LightError,
    onError = FlowMonochromeColors.LightOnError,
    errorContainer = FlowMonochromeColors.LightErrorContainer,
    onErrorContainer = FlowMonochromeColors.LightOnErrorContainer,
    background = FlowMonochromeColors.LightBackground,
    onBackground = FlowMonochromeColors.LightOnBackground,
    surface = FlowMonochromeColors.LightSurface,
    onSurface = FlowMonochromeColors.LightOnSurface,
    surfaceVariant = FlowMonochromeColors.LightSurfaceVariant,
    onSurfaceVariant = FlowMonochromeColors.LightOnSurfaceVariant,
    outline = FlowMonochromeColors.LightOutline,
    outlineVariant = FlowMonochromeColors.LightOutlineVariant
)

val FlowMonochromeDarkColorScheme = darkColorScheme(
    surfaceContainerLowest = Color(0xFF101012),
    surfaceContainerLow = Color(0xFF1D1D20),
    surfaceContainer = Color(0xFF232326),
    surfaceContainerHigh = Color(0xFF2B2B2F),
    surfaceContainerHighest = Color(0xFF343438),
    primary = FlowMonochromeColors.DarkPrimary,
    onPrimary = FlowMonochromeColors.DarkOnPrimary,
    primaryContainer = FlowMonochromeColors.DarkPrimaryContainer,
    onPrimaryContainer = FlowMonochromeColors.DarkOnPrimaryContainer,
    secondary = FlowMonochromeColors.DarkSecondary,
    onSecondary = FlowMonochromeColors.DarkOnSecondary,
    secondaryContainer = FlowMonochromeColors.DarkSecondaryContainer,
    onSecondaryContainer = FlowMonochromeColors.DarkOnSecondaryContainer,
    tertiary = FlowMonochromeColors.DarkTertiary,
    onTertiary = FlowMonochromeColors.DarkOnTertiary,
    tertiaryContainer = FlowMonochromeColors.DarkTertiaryContainer,
    onTertiaryContainer = FlowMonochromeColors.DarkOnTertiaryContainer,
    error = FlowMonochromeColors.DarkError,
    onError = FlowMonochromeColors.DarkOnError,
    errorContainer = FlowMonochromeColors.DarkErrorContainer,
    onErrorContainer = FlowMonochromeColors.DarkOnErrorContainer,
    background = FlowMonochromeColors.DarkBackground,
    onBackground = FlowMonochromeColors.DarkOnBackground,
    surface = FlowMonochromeColors.DarkSurface,
    onSurface = FlowMonochromeColors.DarkOnSurface,
    surfaceVariant = FlowMonochromeColors.DarkSurfaceVariant,
    onSurfaceVariant = FlowMonochromeColors.DarkOnSurfaceVariant,
    outline = FlowMonochromeColors.DarkOutline,
    outlineVariant = FlowMonochromeColors.DarkOutlineVariant
)
