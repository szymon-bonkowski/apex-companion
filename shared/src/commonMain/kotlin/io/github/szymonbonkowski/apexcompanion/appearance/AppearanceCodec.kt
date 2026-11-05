package io.github.szymonbonkowski.apexcompanion.appearance

import kotlinx.serialization.json.*

const val APPEARANCE_MAX_BYTES = 131_072

/**
 * Small explicit allowlist. Future/corrupt records fail closed; absent fields migrate to safe
 * defaults.
 */
object AppearanceCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun encode(record: AppearanceRecord): String {
        record.validated()
        return buildJsonObject {
            put("schemaVersion", 1)
            put("preferences", preferences(record.preferences))
            put(
                "presets",
                buildJsonArray {
                    record.presets.forEach { preset ->
                        add(
                            buildJsonObject {
                                put("id", preset.id)
                                put("name", preset.name)
                                put("preferences", preferences(preset.preferences))
                                preset.dashboard?.let { put("dashboard", dashboard(it)) }
                            }
                        )
                    }
                },
            )
            record.dashboard?.let { put("dashboard", dashboard(it)) }
            put(
                "favoriteMaps",
                buildJsonObject {
                    put("enabled", record.favoriteMaps.enabled)
                    put("advanceMinutes", record.favoriteMaps.advanceMinutes)
                    put(
                        "favorites",
                        buildJsonArray { record.favoriteMaps.favorites.forEach { add(it) } },
                    )
                },
            )
        }
            .toString()
            .also { require(it.encodeToByteArray().size <= APPEARANCE_MAX_BYTES) }
    }

    fun decode(value: String): AppearanceRecord {
        require(
            value.encodeToByteArray().size <= APPEARANCE_MAX_BYTES &&
                value.length <= APPEARANCE_MAX_BYTES
        )
        // Bound nesting before JSON parsing, without interpreting brackets inside strings.
        var depth = 0
        var quoted = false
        var escaped = false
        value.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else
                when (c) {
                    '"' -> quoted = true
                    '{',
                    '[' -> {
                        depth++
                        require(depth <= 12)
                    }
                    '}',
                    ']' -> {
                        depth--
                        require(depth >= 0)
                    }
                }
        }
        val root =
            json
                .parseToJsonElement(value)
                .obj(setOf("schemaVersion", "preferences", "presets", "dashboard", "favoriteMaps"))
        require(
            root["schemaVersion"]?.jsonPrimitive?.let { !it.isString && it.intOrNull == 1 } == true
        ) {
            "Unsupported appearance version."
        }
        val prefs = root["preferences"]?.let(::readPreferences) ?: AppearancePreferences()
        val presets =
            root["presets"]
                ?.jsonArray
                ?.also { require(it.size <= 24) }
                ?.map { element ->
                    val p = element.obj(setOf("id", "name", "preferences", "dashboard"))
                    AppearancePreset(
                        p.string("id"),
                        p.string("name"),
                        readPreferences(p.getValue("preferences")),
                        p["dashboard"]?.let(::readDashboard),
                    )
                }
                .orEmpty()
        val notifications =
            root["favoriteMaps"]?.obj(setOf("enabled", "favorites", "advanceMinutes"))?.let { f ->
                FavoriteMapPreferences(
                    f.bool("enabled", false),
                    f["favorites"]
                        ?.jsonArray
                        ?.also { require(it.size <= 20) }
                        ?.map {
                            require(it.jsonPrimitive.isString)
                            it.jsonPrimitive.content
                        }
                        .orEmpty(),
                    f.int("advanceMinutes", 5),
                )
            } ?: FavoriteMapPreferences()
        return AppearanceRecord(
                prefs,
                presets,
                root["dashboard"]?.let(::readDashboard),
                notifications,
            )
            .validated()
    }

    private fun preferences(p: AppearancePreferences) = buildJsonObject {
        put("style", p.style.name)
        put("mode", p.mode.name)
        p.accentHex?.let { put("accentHex", it.uppercase()) }
        put("dynamicColor", p.dynamicColor)
        put("trueBlack", p.trueBlack)
        put("density", p.density.name)
        put("corners", p.corners.name)
        put("cards", p.cards.name)
        put("borderStrength", p.borderStrength)
        put("surfaceOpacity", p.surfaceOpacity)
        put("gradientIntensity", p.gradientIntensity)
        put("textScalePercent", p.textScalePercent)
        put("numericEmphasis", p.numericEmphasis)
        put("motion", p.motion.name)
        put("reducedEffects", p.reducedEffects)
        put("highContrast", p.highContrast)
        put("reducedTransparency", p.reducedTransparency)
        put("surfaceVariant", p.surfaceVariant.name)
    }

    private fun readPreferences(element: JsonElement): AppearancePreferences {
        val p =
            element.obj(
                setOf(
                    "style",
                    "mode",
                    "accentHex",
                    "dynamicColor",
                    "trueBlack",
                    "density",
                    "corners",
                    "cards",
                    "borderStrength",
                    "surfaceOpacity",
                    "gradientIntensity",
                    "textScalePercent",
                    "numericEmphasis",
                    "motion",
                    "reducedEffects",
                    "highContrast",
                    "reducedTransparency",
                    "surfaceVariant",
                )
            )
        return AppearancePreferences(
                style = p.enum("style", DesignStyle.CARBON),
                mode = p.enum("mode", AppearanceMode.SYSTEM),
                accentHex =
                    p["accentHex"]?.let {
                        require(it.jsonPrimitive.isString)
                        it.jsonPrimitive.content
                    },
                dynamicColor = p.bool("dynamicColor", false),
                trueBlack = p.bool("trueBlack", false),
                density = p.enum("density", LayoutDensity.DEFAULT),
                corners = p.enum("corners", CornerScale.DEFAULT),
                cards = p.enum("cards", CardTreatment.DEFAULT),
                borderStrength = p.int("borderStrength", 1),
                surfaceOpacity = p.int("surfaceOpacity", 90),
                gradientIntensity = p.int("gradientIntensity", 50),
                textScalePercent = p.int("textScalePercent", 100),
                numericEmphasis = p.bool("numericEmphasis", true),
                motion = p.enum("motion", MotionPreference.FULL),
                reducedEffects = p.bool("reducedEffects", false),
                highContrast = p.bool("highContrast", false),
                reducedTransparency = p.bool("reducedTransparency", false),
                surfaceVariant = p.enum("surfaceVariant", SurfaceVariant.DEFAULT),
            )
            .validated()
    }

    private fun dashboard(d: DashboardLayout) = buildJsonObject {
        put("hideUnavailable", d.hideUnavailable)
        put("mapModeId", d.mapModeId)
        put(
            "items",
            buildJsonArray {
                d.items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("module", item.module.name)
                            put("visible", item.visible)
                            put("size", item.size.name)
                        }
                    )
                }
            },
        )
    }

    private fun readDashboard(element: JsonElement): DashboardLayout {
        val d = element.obj(setOf("items", "hideUnavailable", "mapModeId"))
        val items =
            d.getValue("items")
                .jsonArray
                .also { require(it.size == DashboardModule.entries.size) }
                .map {
                    val item = it.obj(setOf("module", "visible", "size"))
                    DashboardItem(
                        DashboardModule.valueOf(item.string("module")),
                        item.bool("visible", true),
                        item.enum("size", ModuleSize.STANDARD),
                    )
                }
        return DashboardLayout(
                items,
                d.bool("hideUnavailable", false),
                d["mapModeId"]?.let { d.string("mapModeId") } ?: "battle_royale",
            )
            .validated()
    }

    private fun JsonElement.obj(keys: Set<String>): JsonObject = jsonObject.also {
        require(it.keys.all { k -> k in keys }) { "Unknown appearance field." }
    }

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.let {
            require(it.isString)
            it.content
        }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.let {
            require(!it.isString)
            requireNotNull(it.booleanOrNull)
        } ?: default

    private fun JsonObject.int(key: String, default: Int): Int =
        this[key]?.jsonPrimitive?.let {
            require(!it.isString)
            requireNotNull(it.intOrNull)
        } ?: default

    private inline fun <reified T : Enum<T>> JsonObject.enum(key: String, default: T): T =
        this[key]?.let { enumValueOf<T>(string(key)) } ?: default
}
