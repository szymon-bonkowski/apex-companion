package io.github.szymonbonkowski.apexcompanion.platform

import android.content.Context
import io.github.szymonbonkowski.apexcompanion.maps.UnconfiguredRotationProvider

/** Public permission/topology gate remains closed. Release has no developer-key reader. */
internal fun rotationRuntimeConfig(context: Context): RotationRuntimeConfig =
    RotationRuntimeConfig(UnconfiguredRotationProvider, false)
