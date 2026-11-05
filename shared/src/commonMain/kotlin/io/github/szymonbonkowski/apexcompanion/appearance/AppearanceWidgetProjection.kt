package io.github.szymonbonkowski.apexcompanion.appearance

import kotlinx.serialization.json.*

/** Presentation-only App Group / Glance projection. No presets, private history or instance IDs. */
fun appearanceWidgetWire(t: ThemeTokens, trueBlack: Boolean): String = buildJsonObject {
    put("schemaVersion", 1)
    put("style", t.style.name)
    val rgb =
        ((t.accent.red * 255).toInt() shl 16) or
            ((t.accent.green * 255).toInt() shl 8) or
            (t.accent.blue * 255).toInt()
    put("accentHex", rgb.toString(16).padStart(6, '0').uppercase())
    put("dark", t.dark)
    put("density", t.density.name)
    put("trueBlack", trueBlack)
}
    .toString()
