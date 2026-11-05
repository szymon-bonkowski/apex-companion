package io.github.szymonbonkowski.apexcompanion.appearance

import androidx.compose.runtime.Composable

/** Read-only OS accessibility/power signals; no permission request or stored override. */
@Composable expect fun platformAppearanceEnvironment(): AppearanceEnvironment
