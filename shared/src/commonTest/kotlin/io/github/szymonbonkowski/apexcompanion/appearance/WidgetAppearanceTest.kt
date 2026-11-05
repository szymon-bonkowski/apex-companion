package io.github.szymonbonkowski.apexcompanion.appearance

import kotlin.test.*

class WidgetAppearanceTest {
    @Test fun fourDesignsResolveWithDistinctPalettesAndReadableRoles() {
        val appearances = WidgetDesign.entries.map { style ->
            resolveWidgetAppearance(WidgetAppearance(), WidgetInstanceAppearance(style = style))
        }
        assertEquals(4, appearances.map { widgetPalette(it).background }.distinct().size)
        appearances.forEach { appearance ->
            listOf(appearance, appearance.copy(dark = !appearance.dark), appearance.copy(trueBlack = true)).forEach {
                val palette = widgetPalette(it)
                assertTrue(widgetContrast(palette.foreground, palette.background) >= 7.0, "$it foreground")
                assertTrue(widgetContrast(palette.secondary, palette.background) >= 4.5, "$it secondary")
                assertTrue(widgetContrast(palette.accent, palette.background) >= 4.5, "$it accent")
                assertTrue(widgetContrast(palette.accent, palette.surface) >= 4.5, "$it surface accent")
            }
        }
    }

    @Test fun wireIsBoundedVersionedAndCosmeticOnly() {
        val value = WidgetAppearance(WidgetDesign.LIQUID, "b0d4ff", false, WidgetDensity.SPACIOUS, true)
        val encoded = WidgetAppearanceCodec.encode(value)
        assertEquals(value.copy(accentHex = "B0D4FF"), WidgetAppearanceCodec.decodeStrict(encoded))
        assertTrue(encoded.length < 2048)
        listOf("uid", "token", "profile", "history", "instance").forEach { assertFalse(encoded.contains(it)) }
    }

    @Test fun invalidFutureAndCorruptSnapshotsHaveSafeFallback() {
        val good = WidgetAppearanceCodec.encode(WidgetAppearance())
        val invalid = listOf("", "[]", "{", "x".repeat(2049),
            good.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            good.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            good.replace("CARBON", "UNRECOGNIZED"), good.replace("6EE7D1", "invalid"),
            good.replace("\"dark\":true", "\"dark\":\"true\""),
            good.dropLast(1) + ",\"token\":\"secret\"}")
        invalid.forEach { wire ->
            assertFails { WidgetAppearanceCodec.decodeStrict(wire) }
            assertEquals(WidgetAppearance(), WidgetAppearanceCodec.decode(wire))
        }
        assertEquals(WidgetAppearance(), WidgetAppearanceCodec.decode(null))
    }

    @Test fun instanceOverridesRemainIndependentWhileAppFollowingUpdates() {
        val app = WidgetAppearance(WidgetDesign.LIQUID, "D3B9FF", true, WidgetDensity.SPACIOUS)
        val first = WidgetInstanceAppearance(WidgetDesign.TACTICAL, "FFAA75", WidgetDensity.COMPACT, WidgetSurface.OLED)
        val second = WidgetInstanceAppearance(WidgetDesign.EDITORIAL, surface = WidgetSurface.LIGHT)
        val following = WidgetInstanceAppearance()
        val resolvedFirst = resolveWidgetAppearance(app, first)
        val resolvedSecond = resolveWidgetAppearance(app, second)
        val changedApp = app.copy(style = WidgetDesign.CARBON, accentHex = "6EE7D1", density = WidgetDensity.COMFORTABLE)
        assertEquals(resolvedFirst, resolveWidgetAppearance(changedApp, first))
        assertEquals(resolvedSecond.copy(density = WidgetDensity.COMFORTABLE), resolveWidgetAppearance(changedApp, second))
        assertEquals(changedApp, resolveWidgetAppearance(changedApp, following))
        assertTrue(resolvedFirst.trueBlack)
        assertFalse(resolvedSecond.dark)
        assertEquals(WidgetDesign.TACTICAL, first.style)
        assertEquals(WidgetDesign.EDITORIAL, second.style)
        val independent = WidgetInstanceAppearance(style = WidgetDesign.TACTICAL)
        assertFalse(resolveWidgetAppearance(changedApp.copy(trueBlack = true), independent).trueBlack)
        assertTrue(resolveWidgetAppearance(changedApp.copy(trueBlack = true), following).trueBlack)
    }

    @Test fun unsafeCustomAccentsFallBackAndNeverHideText() {
        WidgetDesign.entries.forEach { style ->
            listOf(true, false).forEach { dark ->
                listOf("000000", "FFFFFF", "GGGGGG", "", "121212").forEach { hex ->
                    val palette = widgetPalette(WidgetAppearance(style, hex, dark))
                    assertTrue(widgetContrast(palette.accent, palette.background) >= 4.5)
                    assertTrue(widgetContrast(palette.accent, palette.surface) >= 4.5)
                }
            }
        }
    }
}
