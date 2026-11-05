package io.github.szymonbonkowski.apexcompanion.appearance

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.github.szymonbonkowski.apexcompanion.maps.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class AppearanceTest {
    private class Store(var value: String? = null) : RotationStore {
        var fail = false
        var writes = 0

        override suspend fun read() = value

        override suspend fun write(value: String) {
            if (fail) error("disk")
            this.value = value
            writes++
        }
    }

    @Test
    fun selectedControlsUseResolvedReadableRolesAcrossStyles() {
        val selected =
            DesignStyle.entries.map { style ->
                listOf(AppearanceMode.LIGHT, AppearanceMode.DARK).map { mode ->
                    val tokens = resolveTheme(AppearancePreferences(style = style, mode = mode))
                    val colors = designColorScheme(tokens)
                    assertTrue(
                        contrastRatio(colors.onSecondaryContainer, colors.secondaryContainer) >=
                            4.5f
                    )
                    assertTrue(contrastRatio(colors.onPrimary, colors.primary) >= 4.5f)
                    assertTrue(
                        contrastRatio(colors.onTertiaryContainer, colors.tertiaryContainer) >= 4.5f
                    )
                    colors.secondaryContainer
                }
            }
        assertEquals(4, selected.distinct().size)
    }

    @Test
    fun fourDistinctDefinitionsCoverBothModes() {
        assertEquals(4, DesignStyle.entries.size)
        val definitions =
            DesignStyle.entries.map { resolveTheme(AppearancePreferences(style = it)) }
        assertEquals(4, definitions.map { it.radius }.distinct().size)
        assertEquals(4, definitions.map { it.motionMillis }.distinct().size)
        assertEquals(4, definitions.map { it.accent }.distinct().size)
        for (style in DesignStyle.entries) for (mode in
            listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) {
            val t = resolveTheme(AppearancePreferences(style = style, mode = mode))
            assertTrue(contrastRatio(t.foreground, t.surface) >= 7f, "$style $mode body")
            assertTrue(contrastRatio(t.accent, t.surface) >= 4.5f, "$style $mode primary")
            assertTrue(contrastRatio(t.muted, t.surface) >= 4.5f, "$style $mode secondary")
        }
    }

    @Test
    fun arbitraryAccentsRemainReadableAndInvalidInputFallsBack() {
        for (hex in
            listOf("000000", "FFFFFF", "808080", "F0F000", "440044", "123456")) for (style in
            DesignStyle.entries) for (dark in listOf(true, false)) {
            val t =
                resolveTheme(
                    AppearancePreferences(style = style, accentHex = hex),
                    AppearanceEnvironment(systemDark = dark),
                )
            assertTrue(contrastRatio(t.accent, t.surface) >= 4.5f)
        }
        assertEquals(
            resolveTheme(AppearancePreferences()).accent,
            resolveTheme(AppearancePreferences(accentHex = "not a color")).accent,
        )
        assertFailsWith<IllegalArgumentException> {
            AppearancePreferences(accentHex = "AABBCCDD").validated()
        }
        assertFailsWith<IllegalArgumentException> {
            AppearancePreferences(textScalePercent = 40).validated()
        }
    }

    @Test
    fun environmentOverridesAreResolvedWithoutDestroyingPreferences() {
        val p =
            AppearancePreferences(
                style = DesignStyle.LIQUID,
                motion = MotionPreference.FULL,
                surfaceOpacity = 70,
            )
        val t =
            resolveTheme(
                p,
                AppearanceEnvironment(
                    reduceMotion = true,
                    reduceTransparency = true,
                    lowPower = true,
                ),
            )
        assertEquals(0, t.motionMillis)
        assertEquals(1f, t.surfaceOpacity)
        assertEquals(0f, t.gradientStrength)
        assertEquals(MotionPreference.FULL, p.motion)
        assertEquals(300, resolveTheme(p).motionMillis)
        assertFalse(
            resolveTheme(
                    p.copy(mode = AppearanceMode.LIGHT),
                    AppearanceEnvironment(systemDark = true),
                )
                .dark
        )
        assertTrue(
            resolveTheme(
                    p.copy(mode = AppearanceMode.DARK),
                    AppearanceEnvironment(systemDark = false),
                )
                .dark
        )
        assertEquals(Color.Black, resolveTheme(p.copy(trueBlack = true)).background)
        assertNotEquals(
            Color.Black,
            resolveTheme(p.copy(trueBlack = true, mode = AppearanceMode.LIGHT)).background,
        )
    }

    @Test
    fun dynamicAccentIsOptionalAndContrastResolved() {
        val environment = AppearanceEnvironment(dynamicAccentHex = "EE3300")
        assertNotEquals(
            resolveTheme(AppearancePreferences(), environment).accent,
            resolveTheme(AppearancePreferences(dynamicColor = true), environment).accent,
        )
        assertEquals(
            resolveTheme(AppearancePreferences(dynamicColor = true)).accent,
            resolveTheme(AppearancePreferences()).accent,
        )
    }

    @Test
    fun customAccentAndTextContrastSurviveActualCompositedGradientStops() {
        for (style in listOf(DesignStyle.LIQUID, DesignStyle.TACTICAL)) for (dark in
            listOf(true, false)) for (hex in listOf("000000", "777777", "FF0000")) for (opacity in
            listOf(70, 100)) for (intensity in listOf(10, 100)) {
            val p =
                AppearancePreferences(
                    style = style,
                    accentHex = hex,
                    surfaceOpacity = opacity,
                    gradientIntensity = intensity,
                )
            val t = resolveTheme(p, AppearanceEnvironment(systemDark = dark))
            for (background in renderedThemeBackgrounds(t)) {
                val scenario = "$style dark=$dark accent=$hex opacity=$opacity intensity=$intensity"
                assertTrue(contrastRatio(t.accent, background) >= 4.5f, "Primary $scenario")
                assertTrue(contrastRatio(t.foreground, background) >= 4.5f, "Body $scenario")
                assertTrue(contrastRatio(t.muted, background) >= 4.5f, "Secondary $scenario")
            }
        }
    }

    @Test
    fun liquidOpacityChangesActualCardCompositingAndReducedTransparencyIsSolid() {
        val p =
            AppearancePreferences(
                style = DesignStyle.LIQUID,
                surfaceOpacity = 70,
                gradientIntensity = 100,
            )
        val transparent = resolveTheme(p)
        val opaque = resolveTheme(p.copy(surfaceOpacity = 100))
        assertEquals(.7f, themeCardContainerColor(transparent).alpha, .005f)
        assertEquals(1f, themeCardContainerColor(opaque).alpha, .001f)
        assertNotEquals(
            themeCardContainerColor(transparent).compositeOver(transparent.background),
            themeCardContainerColor(opaque).compositeOver(opaque.background),
        )
        for (t in
            listOf(
                resolveTheme(p.copy(reducedTransparency = true)),
                resolveTheme(p, AppearanceEnvironment(reduceTransparency = true)),
            )) {
            assertEquals(1f, themeCardContainerColor(t).alpha, .001f)
            assertEquals(0f, t.gradientStrength)
            assertEquals(t.surface, renderedThemeBackgrounds(t)[2])
        }
    }

    @Test
    fun allControlsRoundTripAndSparseVersionOneMigrates() {
        val record =
            AppearanceRecord(
                AppearancePreferences(
                    style = DesignStyle.TACTICAL,
                    mode = AppearanceMode.LIGHT,
                    accentHex = "AABBCC",
                    dynamicColor = true,
                    trueBlack = true,
                    density = LayoutDensity.COMPACT,
                    corners = CornerScale.LARGE,
                    cards = CardTreatment.ELEVATED,
                    borderStrength = 3,
                    surfaceOpacity = 75,
                    gradientIntensity = 25,
                    textScalePercent = 125,
                    numericEmphasis = false,
                    motion = MotionPreference.REDUCED,
                    reducedEffects = true,
                    highContrast = true,
                    reducedTransparency = true,
                    surfaceVariant = SurfaceVariant.WARM,
                ),
                dashboard = defaultDashboard(DesignStyle.LIQUID).copy(mapModeId = "arenasRanked"),
                favoriteMaps = FavoriteMapPreferences(false, listOf("Example"), 10),
            )
        assertEquals(record, AppearanceCodec.decode(AppearanceCodec.encode(record)))
        assertEquals(AppearanceRecord(), AppearanceCodec.decode("{\"schemaVersion\":1}"))
    }

    @Test
    fun malformedFutureAndOutOfBoundsRecordsFailClosed() {
        val wire = AppearanceCodec.encode(AppearanceRecord())
        for (bad in
            listOf(
                "{}",
                "[]",
                wire.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                wire.replace("\"borderStrength\":1", "\"borderStrength\":100"),
                wire.replace("\"CARBON\"", "\"UNKNOWN\""),
                wire.dropLast(1) + ",\"token\":\"no\"}",
                "[".repeat(30) + "]".repeat(30),
                " ".repeat(APPEARANCE_MAX_BYTES + 1),
            )) assertFails { AppearanceCodec.decode(bad) }
    }

    @Test
    fun durableSwitchPresetsAndDataIsolation() = runTest {
        val store = Store()
        val repo = AppearanceRepository(store)
        repo.initialize()
        val dashboard = defaultDashboard(DesignStyle.TACTICAL)
        assertTrue(repo.saveDashboard(dashboard))
        for (style in DesignStyle.entries) assertTrue(
            repo.savePreferences(AppearancePreferences(style = style))
        )
        assertTrue(repo.createPreset("My setup"))
        val id = repo.state.value.record.presets.single().id
        assertTrue(repo.duplicatePreset(id))
        assertTrue(repo.renamePreset(id, "Renamed"))
        assertTrue(repo.resetToStyleDefaults(DesignStyle.EDITORIAL))
        assertEquals(dashboard, repo.state.value.record.dashboard)
        assertTrue(repo.restorePreset(id))
        assertEquals(DesignStyle.TACTICAL, repo.state.value.record.preferences.style)
        val reopened = AppearanceRepository(store)
        reopened.initialize()
        assertEquals(repo.state.value.record, reopened.state.value.record)
        assertTrue(reopened.deletePreset(id))
        assertEquals(1, reopened.state.value.record.presets.size)
        assertFalse(reopened.createPreset("\n"))
        assertNotNull(reopened.state.value.error)
    }

    @Test
    fun failedCommitRetainsPreviousPublishedAndDurableRecord() = runTest {
        val store = Store()
        val repo = AppearanceRepository(store)
        repo.initialize()
        assertTrue(repo.savePreferences(AppearancePreferences(style = DesignStyle.LIQUID)))
        val before = store.value
        store.fail = true
        assertFalse(repo.savePreferences(AppearancePreferences(style = DesignStyle.TACTICAL)))
        assertEquals(before, store.value)
        assertEquals(DesignStyle.LIQUID, repo.state.value.record.preferences.style)
    }

    @Test
    fun unreadableSettingsRetainedUntilExplicitRecovery() = runTest {
        val store = Store("future or corrupt")
        val repo = AppearanceRepository(store)
        repo.initialize()
        assertTrue(repo.state.value.readOnly)
        assertFalse(repo.savePreferences(AppearancePreferences()))
        assertFalse(repo.resetAll())
        assertEquals(0, store.writes)
        assertTrue(repo.resetAll(true))
        assertFalse(repo.state.value.readOnly)
        assertEquals(AppearanceRecord(), AppearanceCodec.decode(store.value!!))
    }

    @Test
    fun presetCapacityInvalidDashboardAndReordering() = runTest {
        val repo = AppearanceRepository(Store())
        repo.initialize()
        repeat(24) { assertTrue(repo.createPreset("Preset $it")) }
        assertFalse(repo.createPreset("Overflow"))
        val d = defaultDashboard(DesignStyle.CARBON)
        assertEquals(d.items[0], d.move(d.items[0].module, 1).items[1])
        assertEquals(d, d.move(d.items[0].module, -1))
        assertFails { d.copy(items = d.items.dropLast(1)).validated() }
        assertFails { d.copy(items = d.items.map { d.items.first() }).validated() }
    }

    @Test
    fun appearanceProjectionContainsNoPrivateRecords() {
        val wire = appearanceWidgetWire(resolveTheme(AppearancePreferences()), false)
        assertFalse(wire.contains("presets"))
        assertFalse(wire.contains("favoriteMaps"))
        assertFalse(wire.contains("uid"))
        assertTrue(wire.contains("CARBON"))
    }

    @Test
    fun notificationGateRejectsDemoStaleAndDuplicateUtcPlans() {
        val now = 1_800_000_000L
        val cache =
            RotationCache(
                now,
                listOf(
                    PlaylistSchedule(
                        "battle_royale",
                        "BR",
                        listOf(MapSegment("example", "Example", now + 1000, now + 2000)),
                        observedAtSeconds = now,
                    )
                ),
            )
        val preferences = FavoriteMapPreferences(true, listOf("Example"), 5)
        assertTrue(planFavoriteMapAlerts(cache, preferences, now).isEmpty())
        val planned = planFavoriteMapAlerts(cache, preferences, now, authorized = true)
        assertEquals(now + 700, planned.single().deliverAtSeconds)
        assertTrue(planFavoriteMapAlerts(cache.copy(demo = true), preferences, now, true).isEmpty())
        assertTrue(
            planFavoriteMapAlerts(cache, preferences, now + ROTATION_FRESH_SECONDS, true).isEmpty()
        )
        assertTrue(
            planFavoriteMapAlerts(cache, preferences, now, true, planned.map { it.id }.toSet())
                .isEmpty()
        )
        assertTrue(
            planFavoriteMapAlerts(cache, preferences.copy(enabled = false), now, true).isEmpty()
        )
    }
}
