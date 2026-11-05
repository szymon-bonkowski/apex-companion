package io.github.szymonbonkowski.apexcompanion.appearance

/** Cosmetic state only: never includes identities, game records, credentials or OS widget IDs. */
enum class DesignStyle(val title: String, val description: String) {
    CARBON("Carbon", "Graphite · precise · quiet"),
    LIQUID("Liquid", "Layered · luminous · fluid"),
    EDITORIAL("Editorial", "Warm · spacious · typographic"),
    TACTICAL("Tactical", "Steel · structured · competitive"),
}

enum class AppearanceMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class LayoutDensity {
    DEFAULT,
    COMPACT,
    COMFORTABLE,
    SPACIOUS,
}

enum class CornerScale {
    DEFAULT,
    SMALL,
    MEDIUM,
    LARGE,
}

enum class CardTreatment {
    DEFAULT,
    FLAT,
    OUTLINED,
    ELEVATED,
}

enum class MotionPreference {
    FULL,
    REDUCED,
    OFF,
}

enum class SurfaceVariant {
    DEFAULT,
    WARM,
    COOL,
}

data class AppearancePreferences(
    val style: DesignStyle = DesignStyle.CARBON,
    val mode: AppearanceMode = AppearanceMode.SYSTEM,
    val accentHex: String? = null,
    val dynamicColor: Boolean = false,
    val trueBlack: Boolean = false,
    val density: LayoutDensity = LayoutDensity.DEFAULT,
    val corners: CornerScale = CornerScale.DEFAULT,
    val cards: CardTreatment = CardTreatment.DEFAULT,
    val borderStrength: Int = 1,
    val surfaceOpacity: Int = 90,
    val gradientIntensity: Int = 50,
    val textScalePercent: Int = 100,
    val numericEmphasis: Boolean = true,
    val motion: MotionPreference = MotionPreference.FULL,
    val reducedEffects: Boolean = false,
    val highContrast: Boolean = false,
    val reducedTransparency: Boolean = false,
    val surfaceVariant: SurfaceVariant = SurfaceVariant.DEFAULT,
) {
    fun validated(): AppearancePreferences {
        require(accentHex == null || accentHex.matches(Regex("[0-9A-Fa-f]{6}"))) {
            "Use a six-digit RGB color."
        }
        require(borderStrength in 0..3 && surfaceOpacity in 70..100 && gradientIntensity in 0..100)
        require(textScalePercent in 85..130) { "Text size must be between 85% and 130%." }
        return copy(accentHex = accentHex?.uppercase())
    }
}

enum class DashboardModule(val title: String) {
    MAP_ROTATION("Current rotation"),
    UPCOMING_MAP("Next drop"),
    RANKED_PROFILE("Ranked profile"),
    GOAL_PROGRESS("Ranked goal"),
    PREDATOR_THRESHOLD("Predator threshold"),
    RECENT_MATCH("Latest match"),
    RP_TREND("RP observations"),
    SESSION_SUMMARY("Ranked sessions"),
    CAPTURE_STATUS("Capture sync"),
}

enum class ModuleSize {
    COMPACT,
    STANDARD,
    EXPANDED,
}

data class DashboardItem(
    val module: DashboardModule,
    val visible: Boolean = true,
    val size: ModuleSize = ModuleSize.STANDARD,
)

data class DashboardLayout(
    val items: List<DashboardItem>,
    val hideUnavailable: Boolean = false,
    val mapModeId: String = "battle_royale",
) {
    fun validated(): DashboardLayout {
        require(
            mapModeId.isNotBlank() && mapModeId.length <= 80 && mapModeId.none(Char::isISOControl)
        )
        require(
            items.size == DashboardModule.entries.size &&
                items.map { it.module }.toSet().size == items.size
        ) {
            "Dashboard must contain each module once."
        }
        return this
    }

    fun move(module: DashboardModule, offset: Int): DashboardLayout {
        val index = items.indexOfFirst { it.module == module }
        require(index >= 0 && offset in -1..1)
        val target = (index + offset).coerceIn(items.indices)
        return copy(items = items.toMutableList().apply { add(target, removeAt(index)) })
    }
}

fun defaultDashboard(style: DesignStyle): DashboardLayout {
    val order =
        when (style) {
            DesignStyle.CARBON ->
                listOf(
                    DashboardModule.MAP_ROTATION,
                    DashboardModule.GOAL_PROGRESS,
                    DashboardModule.RANKED_PROFILE,
                    DashboardModule.RECENT_MATCH,
                )
            DesignStyle.LIQUID ->
                listOf(
                    DashboardModule.MAP_ROTATION,
                    DashboardModule.UPCOMING_MAP,
                    DashboardModule.RECENT_MATCH,
                    DashboardModule.GOAL_PROGRESS,
                )
            DesignStyle.EDITORIAL ->
                listOf(
                    DashboardModule.GOAL_PROGRESS,
                    DashboardModule.RP_TREND,
                    DashboardModule.MAP_ROTATION,
                    DashboardModule.SESSION_SUMMARY,
                )
            DesignStyle.TACTICAL ->
                listOf(
                    DashboardModule.RANKED_PROFILE,
                    DashboardModule.GOAL_PROGRESS,
                    DashboardModule.CAPTURE_STATUS,
                    DashboardModule.MAP_ROTATION,
                )
        }
    return DashboardLayout(
        (order + DashboardModule.entries.filter { it !in order }).mapIndexed { index, module ->
            DashboardItem(
                module,
                visible = module != DashboardModule.PREDATOR_THRESHOLD,
                size =
                    if (index == 0) ModuleSize.EXPANDED
                    else if (style == DesignStyle.TACTICAL) ModuleSize.COMPACT
                    else ModuleSize.STANDARD,
            )
        }
    )
}

data class AppearancePreset(
    val id: String,
    val name: String,
    val preferences: AppearancePreferences,
    val dashboard: DashboardLayout? = null,
)

data class FavoriteMapPreferences(
    val enabled: Boolean = false,
    val favorites: List<String> = emptyList(),
    val advanceMinutes: Int = 5,
) {
    fun validated(): FavoriteMapPreferences {
        require(
            favorites.size <= 20 &&
                favorites.distinct().size == favorites.size &&
                favorites.all { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) }
        )
        require(advanceMinutes in listOf(0, 5, 10, 15))
        return this
    }
}

data class AppearanceRecord(
    val preferences: AppearancePreferences = AppearancePreferences(),
    val presets: List<AppearancePreset> = emptyList(),
    val dashboard: DashboardLayout? = null,
    val favoriteMaps: FavoriteMapPreferences = FavoriteMapPreferences(),
) {
    fun validated(): AppearanceRecord {
        preferences.validated()
        dashboard?.validated()
        favoriteMaps.validated()
        require(presets.size <= 24 && presets.map { it.id }.distinct().size == presets.size)
        presets.forEach {
            require(
                it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
                    it.name.isNotBlank() &&
                    it.name.length <= 60 &&
                    it.name.none(Char::isISOControl)
            )
            it.preferences.validated()
            it.dashboard?.validated()
        }
        return this
    }
}

data class AppearanceState(
    val record: AppearanceRecord = AppearanceRecord(),
    val initialized: Boolean = false,
    val error: String? = null,
    val readOnly: Boolean = false,
)

/** Runtime-only platform signals. They are never stored over the user's selected preferences. */
data class AppearanceEnvironment(
    val systemDark: Boolean = true,
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val lowPower: Boolean = false,
    val dynamicAccentHex: String? = null,
)
