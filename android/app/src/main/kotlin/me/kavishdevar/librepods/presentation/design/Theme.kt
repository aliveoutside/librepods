/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.material3.rememberDynamicColorScheme
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.LocalHazePerformanceMode
import dev.chrisbanes.haze.blur.HazeBlurDefaults
import dev.chrisbanes.haze.blur.LocalHazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.glass.GlassAccessibilitySettings
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import me.kavishdevar.librepods.data.app.AccessibilitySettings
import me.kavishdevar.librepods.data.app.ColorStyle
import me.kavishdevar.librepods.data.app.Design
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.data.app.FontFamilyOption
import me.kavishdevar.librepods.data.app.FontSettings
import me.kavishdevar.librepods.data.app.IconStyle
import me.kavishdevar.librepods.data.app.LibrePodsHazeBlurStyle
import me.kavishdevar.librepods.presentation.design.typography.InterTypography
import me.kavishdevar.librepods.presentation.design.typography.RobotoFlexExpressiveTypography
import me.kavishdevar.librepods.presentation.design.typography.RobotoFlexTypography
import me.kavishdevar.librepods.presentation.icons.AppleIcons
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.icons.MaterialIcons

val ColorScheme.sectionHeader: Color
    get() = onBackground.copy(alpha = 0.6f)

@Composable
fun LibrePodsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    design: Design = Design(),
    accessibilitySettings: AccessibilitySettings = AccessibilitySettings(),
    fontSettings: FontSettings = FontSettings(),
    content: @Composable () -> Unit
) {
    // The app is Material 3 Expressive only now: the iOS-style design, Apple icons and the
    // iOS color palette are no longer offered, whatever is stored in older settings.
    @Suppress("NAME_SHADOWING")
    val design = design.copy(
        system = DesignSystem.Material,
        iconStyle = IconStyle.Material,
        colorStyle = if (design.colorStyle == ColorStyle.Custom) ColorStyle.Custom else ColorStyle.Wallpaper,
    )

    val typography = when(fontSettings.fontFamilyOption) {
        FontFamilyOption.SystemDefault -> Typography()
        FontFamilyOption.RobotoFlex -> RobotoFlexTypography
        FontFamilyOption.RobotoFlexExpressive -> RobotoFlexExpressiveTypography
        FontFamilyOption.Inter -> InterTypography
    }

    CompositionLocalProvider(
        LocalDesign provides design,
        LocalAccessibilitySettings provides accessibilitySettings,
        LocalIcons provides when (design.iconStyle) {
            IconStyle.Material -> MaterialIcons
            IconStyle.Apple -> AppleIcons
        },
        LocalGlassAccessibilitySettings provides GlassAccessibilitySettings(
            reduceTransparency = accessibilitySettings.reduceTransparency,
            increaseContrast = accessibilitySettings.increaseContrast,
            showBorders = accessibilitySettings.showBorders,
        ),
        LocalHazePerformanceMode provides HazePerformanceMode.Fixed(design.hazePerformanceMode),
    ) {
        val context = LocalContext.current
        val colorScheme = when (design.colorStyle) {
            ColorStyle.Custom -> {
                rememberDynamicColorScheme(
                    seedColor = design.overrideMaterialColor,
                    isDark = darkTheme,
                    contrastLevel = design.contrastLevel,
                    isAmoled = design.amoledMode,
                    style = design.paletteStyle,
                )
            }
            ColorStyle.Default, ColorStyle.Wallpaper -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = typography
        ) {
            val containerColor = if (design.colorStyle == ColorStyle.Custom) MaterialTheme.colorScheme.surface.copy(0.3f) else MaterialTheme.colorScheme.surfaceContainer.copy(0.3f)

            val hazeBlurStyle = when(design.hazeBlurStyle) {
                LibrePodsHazeBlurStyle.UltraThin -> HazeMaterials.ultraThin(containerColor).then {
                    progressive(if (design.progressiveBlurEnabled) HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f) else null)
                }
                LibrePodsHazeBlurStyle.Thin -> HazeMaterials.thin(containerColor).then {
                    progressive(if (design.progressiveBlurEnabled) HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f) else null)
                }
                LibrePodsHazeBlurStyle.Regular -> HazeMaterials.regular(containerColor).then {
                    progressive(if (design.progressiveBlurEnabled) HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f) else null)
                }
                LibrePodsHazeBlurStyle.Thick -> HazeMaterials.thick(containerColor).then {
                    progressive(if (design.progressiveBlurEnabled) HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f) else null)
                }
                LibrePodsHazeBlurStyle.UltraThick -> HazeMaterials.ultraThick(containerColor).then {
                    progressive(if (design.progressiveBlurEnabled) HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f) else null)
                }
                LibrePodsHazeBlurStyle.Custom -> HazeBlurDefaults.style.then {
                    backgroundColor(containerColor)
                    blurRadius(design.hazeBlurRadius)
                    noiseFactor(design.hazeNoiseFactor)
                    alpha(design.alpha)
                    progressive(if (design.progressiveBlurEnabled) HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f) else null)
                }
            }

            CompositionLocalProvider(
                LocalHazeBlurStyle provides hazeBlurStyle,
            ) {
                content()
            }
        }
    }
}
