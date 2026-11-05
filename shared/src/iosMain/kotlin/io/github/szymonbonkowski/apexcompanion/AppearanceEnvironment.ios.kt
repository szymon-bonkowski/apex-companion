@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.szymonbonkowski.apexcompanion.appearance

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIAccessibilityDarkerSystemColorsEnabled
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityIsReduceTransparencyEnabled

/** Implemented — Not Verified. UIKit accessibility and low-power signals, no native glass claim. */
@Composable
actual fun platformAppearanceEnvironment(): AppearanceEnvironment =
    AppearanceEnvironment(
        systemDark = isSystemInDarkTheme(),
        reduceMotion = UIAccessibilityIsReduceMotionEnabled(),
        reduceTransparency = UIAccessibilityIsReduceTransparencyEnabled(),
        highContrast = UIAccessibilityDarkerSystemColorsEnabled(),
        lowPower = NSProcessInfo.processInfo.lowPowerModeEnabled,
    )
