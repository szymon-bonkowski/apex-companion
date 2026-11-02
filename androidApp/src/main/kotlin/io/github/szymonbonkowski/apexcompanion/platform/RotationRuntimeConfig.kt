package io.github.szymonbonkowski.apexcompanion.platform

import io.github.szymonbonkowski.apexcompanion.maps.RotationProvider

internal data class RotationRuntimeConfig(
    val provider: RotationProvider,
    val networkEnabled: Boolean,
    val configurationRevision: Long = 0,
)
