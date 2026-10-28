package com.flowreader.app.core.designsystem.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.flowreader.app.core.designsystem.token.FlowDarkColorScheme
import com.flowreader.app.core.designsystem.token.FlowLightColorScheme
import com.flowreader.app.core.designsystem.token.FlowMonochromeDarkColorScheme
import com.flowreader.app.core.designsystem.token.FlowMonochromeLightColorScheme
import com.flowreader.app.core.designsystem.token.FlowShapes
import com.flowreader.app.core.designsystem.token.FlowTypography
import com.flowreader.app.domain.model.AppThemeMode
import com.flowreader.app.domain.model.AppVisualStyle
import com.flowreader.app.domain.model.ColorSource

/** Resolves [AppThemeMode] against the platform setting. */
@Composable
@ReadOnlyComposable
fun AppThemeMode.isDark(): Boolean = when (this) {
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
    AppThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
}

/**
 * The one and only theme wrapper. Applied once at the navigation root — never per screen.
 *
 * [colorSource] replaces the pre-v52 behaviour where Android 12+ silently forced wallpaper
 * dynamic color and the brand palette was unreachable. The default is now [ColorSource.BRAND].
 */
@Composable
fun FlowTheme(
    themeMode: AppThemeMode = AppThemeMode.FOLLOW_SYSTEM,
    colorSource: ColorSource = ColorSource.BRAND,
    visualStyle: AppVisualStyle = AppVisualStyle.CLEAN_BLUE,
    content: @Composable () -> Unit
) {
    val darkTheme = themeMode.isDark()
    val colorScheme = flowColorScheme(darkTheme, colorSource, visualStyle)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(LocalFlowColorSource provides colorSource, LocalFlowVisualStyle provides visualStyle) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = remember(visualStyle) { if (visualStyle == AppVisualStyle.WARM_PAPER) {
                FlowTypography
            } else {
                FlowTypography.copy(
                    displayLarge = FlowTypography.displayLarge.copy(fontFamily = FontFamily.Default),
                    displayMedium = FlowTypography.displayMedium.copy(fontFamily = FontFamily.Default),
                    displaySmall = FlowTypography.displaySmall.copy(fontFamily = FontFamily.Default),
                    headlineSmall = FlowTypography.headlineSmall.copy(
                        fontFamily = FontFamily.Default,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                    titleLarge = FlowTypography.titleLarge.copy(fontFamily = FontFamily.Default),
                    titleMedium = FlowTypography.titleMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    bodyMedium = FlowTypography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    bodySmall = FlowTypography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    headlineMedium = FlowTypography.headlineMedium.copy(fontFamily = FontFamily.Default),
                    headlineLarge = FlowTypography.headlineLarge.copy(fontFamily = FontFamily.Default)
                )
            } },
            shapes = remember(visualStyle) { if (visualStyle == AppVisualStyle.WARM_PAPER) {
                FlowShapes
            } else {
                FlowShapes.copy(
                    large = RoundedCornerShape(12.dp),
                    extraLarge = RoundedCornerShape(24.dp)
                )
            } },
            content = content
        )
    }
}

val LocalFlowColorSource = staticCompositionLocalOf { ColorSource.BRAND }
val LocalFlowVisualStyle = staticCompositionLocalOf { AppVisualStyle.CLEAN_BLUE }

val FlowCleanLightColorScheme = FlowLightColorScheme.copy(
    primary = Color(0xFF28679D), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FA), onPrimaryContainer = Color(0xFF17446D),
    secondary = Color(0xFF54687B), secondaryContainer = Color(0xFFE8EEF5),
    onSecondaryContainer = Color(0xFF263B50), tertiary = Color(0xFF55746D),
    background = Color.White, onBackground = Color(0xFF202932),
    surface = Color.White, onSurface = Color(0xFF202932),
    surfaceVariant = Color(0xFFF1F2F4), onSurfaceVariant = Color(0xFF626D79),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF7F7F7), surfaceContainerHigh = Color(0xFFF1F2F4),
    surfaceContainerHighest = Color(0xFFE9EAEC), surfaceBright = Color.White,
    surfaceDim = Color(0xFFE9EAEC), surfaceTint = Color.White,
    outline = Color(0xFF737F8B), outlineVariant = Color(0xFFE3E8EE)
)

/** Resolve a complete scheme; reader darkness may differ from application darkness. */
@Composable
fun flowColorScheme(
    darkTheme: Boolean,
    colorSource: ColorSource = LocalFlowColorSource.current,
    visualStyle: AppVisualStyle = LocalFlowVisualStyle.current
): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    return remember(context, configuration, darkTheme, colorSource, visualStyle) { when {
        colorSource == ColorSource.DYNAMIC && dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        colorSource == ColorSource.DYNAMIC && dynamicAvailable -> dynamicLightColorScheme(context)
        colorSource == ColorSource.MONOCHROME && darkTheme -> FlowMonochromeDarkColorScheme
        colorSource == ColorSource.MONOCHROME -> FlowMonochromeLightColorScheme
        darkTheme -> FlowDarkColorScheme
        visualStyle == AppVisualStyle.CLEAN_BLUE -> FlowCleanLightColorScheme
        else -> FlowLightColorScheme
    } }
}
