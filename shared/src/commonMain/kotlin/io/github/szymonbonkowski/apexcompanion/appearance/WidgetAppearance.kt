package io.github.szymonbonkowski.apexcompanion.appearance

import kotlinx.serialization.json.*
import kotlin.math.pow

/** Small, cosmetic-only wire contract. This model never contains game data or native instance IDs. */
enum class WidgetDesign { CARBON, LIQUID, EDITORIAL, TACTICAL }
enum class WidgetDensity { COMPACT, COMFORTABLE, SPACIOUS }
enum class WidgetSurface { FOLLOW, DARK, LIGHT, OLED }

data class WidgetAppearance(
    val style: WidgetDesign = WidgetDesign.CARBON,
    val accentHex: String = "6EE7D1",
    val dark: Boolean = true,
    val density: WidgetDensity = WidgetDensity.COMFORTABLE,
    val trueBlack: Boolean = false,
)

/** Null means follow the app. This record belongs to exactly one native widget instance. */
data class WidgetInstanceAppearance(
    val style: WidgetDesign? = null,
    val accentHex: String? = null,
    val density: WidgetDensity? = null,
    val surface: WidgetSurface = WidgetSurface.FOLLOW,
)

data class WidgetPalette(val background: Long, val surface: Long, val foreground: Long, val secondary: Long, val accent: Long)

fun resolveWidgetAppearance(app: WidgetAppearance, instance: WidgetInstanceAppearance): WidgetAppearance {
    val style = instance.style ?: app.style
    val dark = when (instance.surface) {
        WidgetSurface.DARK, WidgetSurface.OLED -> true
        WidgetSurface.LIGHT -> false
        WidgetSurface.FOLLOW -> if (instance.style == null) app.dark else style != WidgetDesign.EDITORIAL
    }
    return WidgetAppearance(style, instance.accentHex ?: if (instance.style == null) app.accentHex else defaultWidgetAccent(style, dark),
        dark, instance.density ?: app.density, instance.surface == WidgetSurface.OLED || (instance.surface == WidgetSurface.FOLLOW && instance.style == null && app.trueBlack && dark))
}

fun defaultWidgetAccent(style: WidgetDesign, dark: Boolean): String = when (style) {
    WidgetDesign.CARBON -> if (dark) "6EE7D1" else "006B5C"
    WidgetDesign.LIQUID -> if (dark) "D3B9FF" else "67429B"
    WidgetDesign.EDITORIAL -> if (dark) "E5C39B" else "795137"
    WidgetDesign.TACTICAL -> if (dark) "FFAA75" else "9D3C07"
}

fun widgetPalette(appearance: WidgetAppearance): WidgetPalette {
    val background = if (appearance.dark && appearance.trueBlack) 0xFF000000 else when (appearance.style) {
        WidgetDesign.CARBON -> if (appearance.dark) 0xFF0D1317 else 0xFFF1F6F4
        WidgetDesign.LIQUID -> if (appearance.dark) 0xFF20243D else 0xFFECE7F6
        WidgetDesign.EDITORIAL -> if (appearance.dark) 0xFF24211F else 0xFFF7F1E7
        WidgetDesign.TACTICAL -> if (appearance.dark) 0xFF171E27 else 0xFFEBEFF2
    }
    val surface = when (appearance.style) {
        WidgetDesign.CARBON -> if (appearance.dark) 0xFF1B262D else 0xFFE3EEEA
        WidgetDesign.LIQUID -> if (appearance.dark) 0xFF343957 else 0xFFFEFCFF
        WidgetDesign.EDITORIAL -> if (appearance.dark) 0xFF342E29 else 0xFFECE2D2
        WidgetDesign.TACTICAL -> if (appearance.dark) 0xFF293441 else 0xFFDCE4EA
    }
    val ink = if (appearance.dark) 0xFFF4F6F8 else 0xFF20252B
    val secondary = if (appearance.dark) 0xFFBCC7D1 else 0xFF48515B
    val requested = appearance.accentHex.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }?.toLong(16)?.or(0xFF000000)
    val accent = requested?.takeIf { widgetContrast(it, background) >= 4.5 && widgetContrast(it, surface) >= 4.5 }
        ?: defaultWidgetAccent(appearance.style, appearance.dark).toLong(16).or(0xFF000000)
    return WidgetPalette(background, surface, ink, secondary, accent)
}

fun widgetContrast(a: Long, b: Long): Double {
    fun luminance(color: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 255).toDouble() / 255
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    val first = luminance(a); val second = luminance(b)
    return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
}

/** Invalid/future snapshots use a safe presentation; semantic source projections are independent. */
object WidgetAppearanceCodec {
    private val json = Json { isLenient = false }
    private val keys = setOf("schemaVersion", "style", "accentHex", "dark", "density", "trueBlack")
    fun encode(value: WidgetAppearance): String = buildJsonObject {
        put("schemaVersion", 1); put("style", value.style.name); put("accentHex", value.accentHex.uppercase())
        put("dark", value.dark); put("density", value.density.name); put("trueBlack", value.trueBlack)
    }.toString().also { decodeStrict(it) }

    fun decode(value: String?): WidgetAppearance = if (value == null) WidgetAppearance() else runCatching { decodeStrict(value) }.getOrDefault(WidgetAppearance())
    fun decodeStrict(value: String): WidgetAppearance {
        require(value.encodeToByteArray().size <= 2048)
        val root = json.parseToJsonElement(value).jsonObject
        val version = root["schemaVersion"]?.jsonPrimitive
        require(root.keys == keys && version != null && !version.isString && version.intOrNull == 1)
        fun text(key: String): String { val p = root.getValue(key).jsonPrimitive; require(p.isString); return p.content }
        fun bool(key: String): Boolean { val p = root.getValue(key).jsonPrimitive; require(!p.isString); return requireNotNull(p.booleanOrNull) }
        val accent = text("accentHex"); require(accent.matches(Regex("[0-9A-Fa-f]{6}")))
        return WidgetAppearance(WidgetDesign.valueOf(text("style")), accent.uppercase(), bool("dark"), WidgetDensity.valueOf(text("density")), bool("trueBlack"))
    }
}
