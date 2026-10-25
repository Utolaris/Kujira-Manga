package com.par9uet.jm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.par9uet.jm.data.models.COLOR_PALETTE_PRESET_MONET
import com.par9uet.jm.storage.AppearancePreferences
import com.par9uet.jm.ui.interaction.ProvideScrollEdgeFeedback
import org.koin.compose.getKoin

val LocalExtendedColors = staticCompositionLocalOf<ExtendedColorScheme> {
    error("No extended color scheme provided")
}

object ExtendedTheme {
    val colors: ExtendedColorScheme
        @Composable
        get() = LocalExtendedColors.current
}

@Composable
fun AppTheme(
    appearancePreferences: AppearancePreferences = getKoin().get(),
    content: @Composable () -> Unit
) {
    val theme by appearancePreferences.theme.collectAsState()
    val colorPalette by appearancePreferences.colorPalette.collectAsState()
    val glassAppearance by appearancePreferences.glassAppearance.collectAsState()
    val context = LocalContext.current
    val isDark = when (theme) {
        "auto" -> isSystemInDarkTheme()
        "dark" -> true
        else -> false
    }
    // 仅当用户选择"自动取色"预设时才使用动态色；其余预设始终应用调色板覆盖
    val supportDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useDynamic = colorPalette.presetId == COLOR_PALETTE_PRESET_MONET && supportDynamic
    val baseScheme = when {
        useDynamic && isDark -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        isDark -> darkScheme
        else -> lightScheme
    }
    val colorScheme = if (useDynamic) {
        baseScheme
    } else {
        applyPaletteOverride(baseScheme, colorPalette.presetId, isDark) { slot ->
            when (slot) {
                0 -> colorPalette.customPrimary
                1 -> colorPalette.customSecondary
                2 -> colorPalette.customTertiary
                else -> colorPalette.customError
            }
        }
    }
    val extendedColorScheme = extendedColorSchemeFor(colorScheme, isDark)

    // 切换深浅色时同步刷新系统栏样式（状态栏/导航栏图标颜色）
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.DisposableEffect(isDark) {
            val window = (view.context as android.app.Activity).window
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
            onDispose {}
        }
    }

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColorScheme,
        com.par9uet.jm.ui.glass.LocalGlassAppearance provides glassAppearance,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
        ) {
            ProvideScrollEdgeFeedback {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = colorScheme.background,
                    content = content
                )
            }
        }
    }
}

internal fun applyPaletteOverride(
    base: ColorScheme,
    presetId: String,
    isDark: Boolean,
    customOverride: (Int) -> String?,
): ColorScheme {
    val preset = colorPresets.firstOrNull { it.id == presetId } ?: colorPresets.first()
    val presetColors = if (isDark) preset.darkColors else preset.colors
    val primary = customOverride(0)?.toColorOrNull() ?: Color(presetColors[0])
    val secondary = customOverride(1)?.toColorOrNull() ?: Color(presetColors[1])
    val tertiary = customOverride(2)?.toColorOrNull() ?: Color(presetColors[2])
    val error = customOverride(3)?.toColorOrNull() ?: Color(presetColors[3])
    // 浅色模式下容器色应叠加在白色上产生浅色调，深色模式叠加在黑色上产生深色调
    val containerBase = if (isDark) Color.Black else Color.White
    return base.copy(
        primary = primary,
        onPrimary = readableContentColor(primary),
        primaryContainer = primary.copy(alpha = 0.3f).compositeOver(containerBase),
        onPrimaryContainer = readableContentColor(primary.copy(alpha = 0.3f).compositeOver(containerBase)),
        inversePrimary = primary,
        secondary = secondary,
        onSecondary = readableContentColor(secondary),
        secondaryContainer = secondary.copy(alpha = 0.3f).compositeOver(containerBase),
        onSecondaryContainer = readableContentColor(secondary.copy(alpha = 0.3f).compositeOver(containerBase)),
        tertiary = tertiary,
        onTertiary = readableContentColor(tertiary),
        tertiaryContainer = tertiary.copy(alpha = 0.3f).compositeOver(containerBase),
        onTertiaryContainer = readableContentColor(tertiary.copy(alpha = 0.3f).compositeOver(containerBase)),
        error = error,
        onError = readableContentColor(error),
        errorContainer = error.copy(alpha = 0.3f).compositeOver(containerBase),
        onErrorContainer = readableContentColor(error.copy(alpha = 0.3f).compositeOver(containerBase)),
    )
}

private fun Color.compositeOver(background: Color): Color {
    val fgAlpha = alpha
    if (fgAlpha >= 1f) return this
    val a = alpha + background.alpha * (1f - fgAlpha)
    if (a <= 0f) return Color.Transparent
    val r = (red * fgAlpha + background.red * background.alpha * (1f - fgAlpha)) / a
    val g = (green * fgAlpha + background.green * background.alpha * (1f - fgAlpha)) / a
    val b = (blue * fgAlpha + background.blue * background.alpha * (1f - fgAlpha)) / a
    return Color(r, g, b, a)
}
