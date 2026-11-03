package io.github.szymonbonkowski.apexcompanion.platform

import io.github.szymonbonkowski.apexcompanion.maps.RotationProvider
import io.github.szymonbonkowski.apexcompanion.ranked.*

internal data class RotationRuntimeConfig(
    val provider: RotationProvider,
    val networkEnabled: Boolean,
    val configurationRevision: Long = 0,
    val rankedProvider: RankedProvider = UnconfiguredRankedProvider,
)
